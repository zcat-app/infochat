package app.zcat.infochat.messaging.impl.simplex;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.ScopeRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live-e2e Phase 4b (HANDOFF §4b-2): drives ONE host-side simplex-chat client
 * identity (LiveAdmin / LiveUser under {@code prod/runtime/simplex-clients/})
 * against the real deployed bot, reusing the adapter's own transport pieces —
 * {@link SimpleXSubprocess} (process lifecycle), {@link SimpleXWebSocketClient}
 * (corrId command/response + async inbound) and {@link SimpleXMessageCodec}
 * (wire shapes) — so there is exactly ONE wire-shape source of truth and no
 * forked encoder (D-live-9). Lives in this package on purpose: those three
 * collaborators are package-private, and this bridge is the narrow public
 * surface the transport-agnostic {@code SimpleXConversationBackend} consumes.
 *
 * <p>Send path: {@link SimpleXMessageCodec#encodeSendCommand} +
 * {@link SimpleXWebSocketClient#sendCommand} — the exact encoder/ack path the
 * production adapter uses. Receive path: the client's {@code InboundConsumer}
 * accumulates decoded {@link InboundMessage}s (the bot's replies, as parsed by
 * the same codec that parses the bot's own inbound), exposed via a
 * watermark-index read for the backend's poll-until-match wait.</p>
 *
 * <p>NOT thread-safe beyond the received-list; the scenario runner is
 * single-threaded by contract.</p>
 */
public final class LiveSimpleXClient implements AutoCloseable {

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SUBPROCESS_BACKOFF_BASE = Duration.ofMillis(500);
    private static final Duration SUBPROCESS_BACKOFF_MAX = Duration.ofSeconds(5);
    private static final int SUBPROCESS_CRASH_CAP = 3;
    private static final int WS_CONNECT_ATTEMPTS = 5;
    private static final Duration WS_CONNECT_RETRY_PAUSE = Duration.ofSeconds(2);

    private final String label;
    private final int wsPort;
    private final SimpleXSubprocess subprocess;
    private final List<InboundMessage> received = new CopyOnWriteArrayList<>();
    private final AtomicLong corrIdSequence = new AtomicLong();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleXWebSocketClient webSocket;

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
     * Launch the client subprocess and connect the WS API. Retries the WS
     * handshake with a fresh client instance per attempt (mirroring the
     * adapter's rebuild-on-reconnect) because simplex-chat needs a moment to
     * open its socket after process start.
     */
    public void start() throws InterruptedException {
        subprocess.start();
        MessagingException lastFailure = null;
        for (int attempt = 1; attempt <= WS_CONNECT_ATTEMPTS; attempt++) {
            SimpleXWebSocketClient candidate = new SimpleXWebSocketClient(
                    URI.create("ws://127.0.0.1:" + wsPort),
                    HttpClient.newHttpClient(),
                    received::add,
                    groupCandidate -> { },
                    invitation -> { });
            try {
                candidate.start();
                this.webSocket = candidate;
                return;
            } catch (MessagingException e) {
                candidate.close();
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
        String envelope = SimpleXMessageCodec.encodeSendCommand(
                corrId, new ScopeRef.Dm(contactId), text);
        webSocket.sendCommand(corrId, envelope, ACK_TIMEOUT);
    }

    /** How many inbound messages have arrived so far (watermark for {@link #receivedSince}). */
    public int receivedCount() {
        return received.size();
    }

    /** Inbound message bodies observed after the {@code watermark} index, in order. */
    public List<String> receivedSince(int watermark) {
        List<String> bodies = new ArrayList<>();
        List<InboundMessage> snapshot = List.copyOf(received);
        for (int i = watermark; i < snapshot.size(); i++) {
            bodies.add(snapshot.get(i).text());
        }
        return bodies;
    }

    /**
     * Resolve the contact id of the sole contact whose local display name is
     * {@code displayName} in THIS client's DB, via a corrId {@code /contacts}
     * command. Uses a short-lived SIDE WebSocket connection, not the
     * production {@link SimpleXWebSocketClient}: that client completes corrId
     * futures only for send acks and command errors (all the adapter needs),
     * so a {@code contactsList} response would time out its
     * {@code sendCommand}. This is a harness fixture query, done exactly the
     * way the original live-frame-capture probe did (corrId responses return
     * on the issuing connection; async events keep flowing to the client's
     * main connection). Message send/receive still single-sources through the
     * production codec/client. The response nesting is version-sensitive, so
     * the parse walks the whole tree for objects carrying both
     * {@code contactId} and {@code localDisplayName}.
     */
    public String resolveContactId(String displayName) throws Exception {
        String response = rawCorrIdCommand("/contacts");
        List<String> matches = new ArrayList<>();
        collectContactIds(objectMapper.readTree(response), displayName, matches);
        if (matches.size() != 1) {
            throw new IllegalStateException("[" + label + "] expected exactly one contact named '"
                    + displayName + "' in the client DB, found " + matches.size());
        }
        return matches.get(0);
    }

    /**
     * Send one raw corrId command over a dedicated short-lived WebSocket and
     * return the (complete, possibly multi-part) frame carrying that corrId.
     */
    private String rawCorrIdCommand(String cmd) throws Exception {
        String corrId = nextCorrId();
        CompletableFuture<String> matched = new CompletableFuture<>();
        java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
            private final StringBuilder frame = new StringBuilder();

            @Override
            public java.util.concurrent.CompletionStage<?> onText(
                    java.net.http.WebSocket ws, CharSequence data, boolean last) {
                frame.append(data);
                if (last) {
                    String complete = frame.toString();
                    frame.setLength(0);
                    if (complete.contains("\"" + corrId + "\"")) {
                        matched.complete(complete);
                    }
                }
                ws.request(1);
                return null;
            }
        };
        java.net.http.WebSocket sideSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + wsPort), listener)
                .get(ACK_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        try {
            sideSocket.sendText("{\"corrId\":\"" + corrId + "\",\"cmd\":\"" + cmd + "\"}", true)
                    .get(ACK_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            return matched.get(ACK_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            sideSocket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done");
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

    private String nextCorrId() {
        return "live-" + label + "-" + corrIdSequence.incrementAndGet();
    }

    @Override
    public void close() {
        if (webSocket != null) {
            webSocket.close();
        }
        subprocess.stop();
    }
}
