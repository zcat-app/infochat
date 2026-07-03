package app.zcat.infochat.messaging.impl.simplex;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.ScopeRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live-e2e Phase 4b (HANDOFF §4b-3, M1-546): drives ONE host-side simplex-chat
 * client identity (LiveAdmin / LiveUser under {@code prod/runtime/simplex-clients/})
 * against the real deployed bot over a SINGLE raw {@code java.net.http} WebSocket
 * connection. simplex-chat delivers asynchronous events to one connection only
 * (the M1-544 lesson), so this client owns the connection exclusively: send acks,
 * command errors, fixture corrId queries ({@code /contacts}, {@code /groups},
 * {@code /members}), inbound messages, group messages and item-edit events all
 * arrive on the same socket, routed here.
 *
 * <p>Every inbound frame is fed through the production
 * {@link SimpleXMessageCodec#decode} — one wire-shape source of truth, no forked
 * decoder (D-live-9). Harness-side parsing exists ONLY for what the codec
 * deliberately does not model: {@code chatItemUpdated} finalize events (the bot
 * never consumes edits) and the D51 mention-envelope encode (the bot never
 * mentions). Lives in this package on purpose: the codec's {@code decode()} is
 * package-private, and this bridge is the narrow public surface the
 * transport-agnostic {@code SimpleXConversationBackend} consumes.</p>
 *
 * <p>NOT thread-safe beyond the observed/finalized lists and pending maps; the
 * scenario runner is single-threaded by contract.</p>
 */
public final class LiveSimpleXClient implements AutoCloseable {

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SUBPROCESS_BACKOFF_BASE = Duration.ofMillis(500);
    private static final Duration SUBPROCESS_BACKOFF_MAX = Duration.ofSeconds(5);
    private static final int SUBPROCESS_CRASH_CAP = 3;
    private static final int WS_CONNECT_ATTEMPTS = 5;
    private static final Duration WS_CONNECT_RETRY_PAUSE = Duration.ofSeconds(2);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The bot's identity within one group, as recorded in THIS client's DB (D51 mention anchor). */
    public record GroupMember(long groupMemberId, String displayName) {}

    private final String label;
    private final int wsPort;
    private final SimpleXSubprocess subprocess;
    // Bodies of decoded Inbound (DM) and GroupCandidate (group) frames, in arrival order.
    private final List<String> received = new CopyOnWriteArrayList<>();
    // Bodies of harness-parsed chatItemUpdated frames (item-edit finalize), in arrival order.
    private final List<String> finalized = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<String>> pendingSendAcks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pendingRawQueries = new ConcurrentHashMap<>();
    private final AtomicLong corrIdSequence = new AtomicLong();

    private WebSocket webSocket;

    /**
     * @param binary  path to the simplex-chat binary (the Provider-image-baked
     *                build extracted to the host, HANDOFF §HOST STATE)
     * @param dataDir the client identity's data dir; the subprocess derives the
     *                {@code simplex_v1} DB prefix inside it (same rule as the
     *                production adapter's {@link SimpleXSubprocess#commandFor})
     * @param wsPort  loopback WS API port for THIS client (must not collide
     *                with the bot's or another client's)
     */
    public LiveSimpleXClient(String binary, String dataDir, int wsPort, String label) {
        this.label = label;
        this.wsPort = wsPort;
        SimpleXConfig config = new SimpleXConfig(binary, dataDir, wsPort);
        this.subprocess = new SimpleXSubprocess(
                SimpleXSubprocess.commandFor(config),
                SUBPROCESS_BACKOFF_BASE, SUBPROCESS_BACKOFF_MAX, SUBPROCESS_CRASH_CAP,
                failure -> System.err.println("[" + label + "] simplex-chat subprocess: " + failure),
                new Random());
    }

    /**
     * Launch the client subprocess and connect the WS API. Retries the handshake
     * because simplex-chat needs a moment to open its socket after process start.
     */
    public void start() throws InterruptedException {
        subprocess.start();
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= WS_CONNECT_ATTEMPTS; attempt++) {
            try {
                this.webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + wsPort), new FrameListener())
                        .get(ACK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                return;
            } catch (ExecutionException | TimeoutException e) {
                lastFailure = e;
                Thread.sleep(WS_CONNECT_RETRY_PAUSE.toMillis());
            }
        }
        throw new IllegalStateException(
                "[" + label + "] simplex-chat WS API on port " + wsPort + " not reachable after "
                        + WS_CONNECT_ATTEMPTS + " attempts", lastFailure);
    }

    /** Send a DM to {@code contactId} (the peer's row id in THIS client's DB). */
    public void sendDm(String contactId, String text) throws MessagingException {
        String corrId = nextCorrId();
        sendCommand(corrId, SimpleXMessageCodec.encodeSendCommand(
                corrId, new ScopeRef.Dm(contactId), text));
    }

    /** Send a plain (mention-free) group message via the production codec's group encoding. */
    public void sendGroup(String groupId, String text) throws MessagingException {
        String corrId = nextCorrId();
        sendCommand(corrId, SimpleXMessageCodec.encodeSendCommand(
                corrId, new ScopeRef.Group(groupId), text));
    }

    /**
     * Send a group message carrying a D51 structured mention of {@code botMember}.
     * The bot recognises mentions only by a {@code mentions{}} memberId byte-equal
     * to its own per-group memberId — plain-text "@Name" is silently dropped — and
     * the production encoder has no mention support (the bot never mentions), so
     * the envelope is composed harness-side.
     */
    public void sendGroupMention(String groupId, String text, GroupMember botMember)
            throws MessagingException {
        String corrId = nextCorrId();
        sendCommand(corrId, encodeMentionSendCommand(corrId, groupId, text, botMember));
    }

    /** How many inbound (DM + group) bodies have arrived so far (watermark for {@link #receivedSince}). */
    public int receivedCount() {
        return received.size();
    }

    /** Inbound (DM + group) bodies observed after the {@code watermark} index, in order. */
    public List<String> receivedSince(int watermark) {
        return since(received, watermark);
    }

    /** How many item-edit finalized bodies have arrived so far (watermark for {@link #finalizedSince}). */
    public int finalizedCount() {
        return finalized.size();
    }

    /** Item-edit finalized bodies observed after the {@code watermark} index, in order. */
    public List<String> finalizedSince(int watermark) {
        return since(finalized, watermark);
    }

    private static List<String> since(List<String> all, int watermark) {
        List<String> bodies = new ArrayList<>();
        List<String> snapshot = List.copyOf(all);
        for (int i = watermark; i < snapshot.size(); i++) {
            bodies.add(snapshot.get(i));
        }
        return bodies;
    }

    /**
     * Resolve the contact id of the sole contact whose local display name is
     * {@code displayName} in THIS client's DB, via a corrId {@code /contacts}
     * query on the single connection. The response nesting is version-sensitive,
     * so the parse walks the whole tree for objects carrying both
     * {@code contactId} and {@code localDisplayName}.
     */
    public String resolveContactId(String displayName) throws Exception {
        JsonNode response = rawQuery("/contacts");
        List<String> matches = new ArrayList<>();
        collectContactIds(response, displayName, matches);
        if (matches.size() != 1) {
            throw new IllegalStateException("[" + label + "] expected exactly one contact named '"
                    + displayName + "' in the client DB, found " + matches.size());
        }
        return matches.get(0);
    }

    /**
     * Resolve the per-client group id of the sole group whose local display name
     * is {@code groupName}, via a corrId {@code /groups} query. Group ids are
     * per-client DB rows (D10-adjacent), so each client resolves its own. The
     * {@code groupProfile} sibling distinguishes group-info objects from group
     * MEMBER objects, which also carry a {@code groupId} + {@code localDisplayName}.
     */
    public String resolveGroupId(String groupName) throws Exception {
        JsonNode response = rawQuery("/groups");
        List<String> matches = new ArrayList<>();
        collectGroupIds(response, groupName, matches);
        if (matches.size() != 1) {
            throw new IllegalStateException("[" + label + "] expected exactly one group named '"
                    + groupName + "' in the client DB, found " + matches.size());
        }
        return matches.get(0);
    }

    /**
     * Resolve the group member whose {@code memberContactId} is {@code contactId}
     * in group {@code groupName}, via a corrId {@code /members} query — the D51
     * mention envelope needs the BOT's sender-local numeric {@code groupMemberId}
     * and display name as recorded in THIS client's DB. Command form and response
     * nesting live-confirmed 2026-07-03 (4b-3 run); the parse tree-walks for
     * member objects like the other fixture queries.
     */
    public GroupMember resolveGroupMember(String groupName, String contactId) throws Exception {
        JsonNode response = rawQuery("/members " + groupName);
        Map<String, String> displayNamesByGroupMemberId = new LinkedHashMap<>();
        collectGroupMembers(response, contactId, displayNamesByGroupMemberId);
        if (displayNamesByGroupMemberId.size() != 1) {
            throw new IllegalStateException("[" + label + "] expected exactly one member with contact id '"
                    + contactId + "' in group '" + groupName + "', found " + displayNamesByGroupMemberId.size());
        }
        Map.Entry<String, String> member = displayNamesByGroupMemberId.entrySet().iterator().next();
        return new GroupMember(Long.parseLong(member.getKey()), member.getValue());
    }

    /**
     * Harness-side parse of a {@code chatItemUpdated} frame's finalized body. The
     * production codec deliberately has no case for item edits (the bot never
     * consumes them), so progress-notified replies (/summary, chat, digest) —
     * which deliver their final body via an item EDIT — are observable only here.
     * The body path mirrors the codec's singular {@code newChatItem} shape
     * ({@code resp.chatItem.chatItem.content.msgContent.text}); best-guess in CI,
     * a declared live-discovery item (M1-546). Missing fields yield empty, never
     * throw — one unexpected frame must not kill the listener.
     */
    static Optional<String> extractFinalizedBody(String frame) {
        JsonNode root;
        try {
            root = MAPPER.readTree(frame);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        JsonNode resp = root.path("resp");
        if (!"chatItemUpdated".equals(resp.path("type").asText())) {
            return Optional.empty();
        }
        JsonNode text = resp.path("chatItem").path("chatItem")
                .path("content").path("msgContent").path("text");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    /**
     * Compose a group send carrying a D51 structured mention: the production
     * codec's group-scope {@code /_send #<id> json [...]} form, plus a
     * {@code mentions{}} map of display name → the sender-local NUMERIC
     * {@code groupMemberId} (simplex-chat resolves it to the wire memberId the
     * bot byte-compares). Live-corrected 2026-07-03 on the 4b-3 run: the
     * original best-guess {@code {memberId}} object value is rejected by
     * v6.5.4.1 ("bad chat command: Failed reading: empty"); the numeric form
     * was probe-confirmed via raw {@code /_send} on the real CLI. Pinned by
     * {@code LiveSimpleXHarnessFrameTest}.
     */
    static String encodeMentionSendCommand(String corrId, String groupId, String text,
                                           GroupMember botMember) {
        ObjectNode msgContent = MAPPER.createObjectNode();
        msgContent.put("type", "text");
        msgContent.put("text", text);
        ObjectNode mentions = MAPPER.createObjectNode();
        mentions.put(botMember.displayName(), botMember.groupMemberId());
        ObjectNode composed = MAPPER.createObjectNode();
        composed.set("msgContent", msgContent);
        composed.set("mentions", mentions);
        ArrayNode payload = MAPPER.createArrayNode();
        payload.add(composed);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("corrId", corrId);
        root.put("cmd", "/_send #" + groupId + " json " + payload);
        return root.toString();
    }

    /** Single listener for the single connection: assemble fragments, route complete frames. */
    private final class FrameListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                onFrame(frame);
            }
            ws.request(1);
            return null;
        }
    }

    private void onFrame(String frame) {
        // Raw fixture-query responses (contactsList, groupsList, members…) are
        // shapes the codec deliberately does not model — match them by corrId
        // first, then still feed the frame through the production decode.
        String corrId = frameCorrId(frame);
        if (corrId != null) {
            CompletableFuture<String> rawQuery = pendingRawQueries.remove(corrId);
            if (rawQuery != null) {
                rawQuery.complete(frame);
            }
        }
        SimpleXMessageCodec.DecodedFrame decoded;
        try {
            decoded = SimpleXMessageCodec.decode(frame);
        } catch (SimpleXMessageCodec.MalformedFrameException e) {
            return;
        }
        switch (decoded) {
            case SimpleXMessageCodec.Inbound inbound -> received.add(inbound.message().text());
            case SimpleXMessageCodec.GroupCandidate gc -> received.add(gc.text());
            case SimpleXMessageCodec.SendAck ack -> {
                CompletableFuture<String> pending = pendingSendAcks.remove(ack.corrId());
                if (pending != null) {
                    pending.complete(ack.chatItemId());
                }
            }
            case SimpleXMessageCodec.CommandError error -> {
                CompletableFuture<String> pending = pendingSendAcks.remove(error.corrId());
                if (pending != null) {
                    pending.completeExceptionally(new MessagingException(error.category(),
                            "simplex-chat error: " + error.detail()));
                }
            }
            default -> extractFinalizedBody(frame).ifPresent(finalized::add);
        }
    }

    private static String frameCorrId(String frame) {
        try {
            JsonNode corrId = MAPPER.readTree(frame).path("corrId");
            return corrId.isTextual() ? corrId.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Transmit one command envelope and block until its corrId ack (or error). */
    private void sendCommand(String corrId, String envelope) throws MessagingException {
        CompletableFuture<String> ack = new CompletableFuture<>();
        pendingSendAcks.put(corrId, ack);
        try {
            webSocket.sendText(envelope, true).get(ACK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            ack.get(ACK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "interrupted while awaiting ack for corrId=" + corrId, e);
        } catch (TimeoutException e) {
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "no ack for corrId=" + corrId + " within " + ACK_TIMEOUT, e);
        } catch (ExecutionException e) {
            Throwable cause = Objects.requireNonNull(e.getCause());
            if (cause instanceof MessagingException me) {
                throw me;
            }
            throw new MessagingException(FailureCategory.PERMANENT,
                    "send for corrId=" + corrId + " failed: " + cause.getClass().getSimpleName(),
                    cause);
        } finally {
            pendingSendAcks.remove(corrId);
        }
    }

    /** Send one raw corrId command on the single connection and return the parsed response frame. */
    private JsonNode rawQuery(String cmd) throws Exception {
        String corrId = nextCorrId();
        CompletableFuture<String> response = new CompletableFuture<>();
        pendingRawQueries.put(corrId, response);
        try {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("corrId", corrId);
            envelope.put("cmd", cmd);
            webSocket.sendText(envelope.toString(), true).get(ACK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return MAPPER.readTree(response.get(ACK_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        } finally {
            pendingRawQueries.remove(corrId);
        }
    }

    private static void collectContactIds(JsonNode node, String displayName, List<String> out) {
        if (node.isObject() && node.hasNonNull("contactId")
                && displayName.equals(node.path("localDisplayName").asText())) {
            out.add(node.get("contactId").asText());
        }
        for (JsonNode child : node) {
            collectContactIds(child, displayName, out);
        }
    }

    private static void collectGroupIds(JsonNode node, String groupName, List<String> out) {
        if (node.isObject() && node.hasNonNull("groupId") && node.has("groupProfile")
                && groupName.equals(node.path("localDisplayName").asText())) {
            out.add(node.get("groupId").asText());
        }
        for (JsonNode child : node) {
            collectGroupIds(child, groupName, out);
        }
    }

    private static void collectGroupMembers(JsonNode node, String contactId,
                                            Map<String, String> displayNamesByGroupMemberId) {
        if (node.isObject() && node.hasNonNull("groupMemberId")
                && contactId.equals(node.path("memberContactId").asText())) {
            displayNamesByGroupMemberId.putIfAbsent(
                    node.get("groupMemberId").asText(), node.path("localDisplayName").asText());
        }
        for (JsonNode child : node) {
            collectGroupMembers(child, contactId, displayNamesByGroupMemberId);
        }
    }

    private String nextCorrId() {
        return "live-" + label + "-" + corrIdSequence.incrementAndGet();
    }

    @Override
    public void close() {
        if (webSocket != null) {
            webSocket.abort();
        }
        subprocess.stop();
    }
}
