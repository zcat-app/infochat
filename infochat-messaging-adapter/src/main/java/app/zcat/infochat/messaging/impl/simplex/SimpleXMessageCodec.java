package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Instant;

/**
 * JSON codec for the simplex-chat WebSocket bot API. Pure functions; no I/O,
 * no state. The simplex-chat envelope shape (verbatim from
 * {@code docs/design/06-messaging.md} §6.4.5) is JSON wrapping a SimpleX
 * command string:
 *
 * <pre>{@code
 *   {"corrId":"<id>","cmd":"<simplex-command-string>"}    // outbound
 *   {"corrId":"<id>","resp":{"type":"<typeName>",...}}    // command response
 *   {"resp":{"type":"<typeName>",...}}                    // async event
 * }</pre>
 *
 * <p>The simplex-chat command string itself follows the {@code /_send @<id> ...},
 * {@code /_update item @<id> <chatItemId> live=<on|off> json ...},
 * {@code /_set_contact_typing @<id> on|off} forms from design §6.4.5. The
 * exact byte-level interaction with a live simplex-chat is verified in the
 * Provider-side integration ticket (M1-105 production-IT, M1-109); this
 * codec exercises the envelope shape via round-trip tests against
 * {@code FakeSimpleXProcess}.</p>
 *
 * <p><strong>Failure classification.</strong> Per
 * {@code docs/spec/messaging.md} §Failure handling: an adapter that cannot
 * tell transient from permanent MUST default to {@link
 * FailureCategory#PERMANENT}. {@link #classifyError(String)} maps the
 * SimpleX error tags this codec recognises; everything unknown falls
 * through to PERMANENT (silently looping a permanent failure is worse than
 * aborting an occasionally-transient one).</p>
 */
final class SimpleXMessageCodec {

    /**
     * Adapter-local clock cap on the outbound text we paste into the command
     * envelope. The Provider-side capability flag enforces the protocol cap
     * (4 KB per design §6.4.2); this constant exists only as a defensive
     * second wall against a Provider that forgot to chunk — the codec is the
     * narrowest internal boundary that still sees the raw text.
     */
    static final int MAX_OUTBOUND_TEXT_BYTES = 4_000;

    // FAIL_ON_UNKNOWN_PROPERTIES off so a future simplex-chat field
    // additions on the same envelope do not break the v1 decoder. The
    // failure mode we DO want is "frame is not JSON at all" or "frame
    // is missing a required field" — both still throw via the explicit
    // null-checks in decode() below.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SimpleXMessageCodec() {
        // Pure-static codec.
    }

    // --- Outbound encoding ---------------------------------------------------

    /**
     * Encode a fresh outbound message as a {@code /_send} command envelope.
     * The {@code corrId} is the adapter-chosen correlation id used to pair
     * the response carrying the new {@code chatItemId} back to the caller.
     *
     * @throws IllegalArgumentException if {@code text} exceeds
     *         {@link #MAX_OUTBOUND_TEXT_BYTES} — the Provider's
     *         capability-flag chunking should have prevented this; the
     *         check is a system-boundary defense.
     */
    static @NonNull String encodeSendCommand(@NonNull String corrId,
                                             @NonNull ScopeRef scope,
                                             @NonNull String text) {
        requireWithinCap(text);
        String target = targetSelector(scope);
        String cmd = "/_send " + target + " json " + jsonString(textContent(text));
        return envelope(corrId, cmd);
    }

    /**
     * Encode an in-place edit of a previously-sent message. {@code live=on}
     * for the progress-notifier update sequence; {@code finalize} (the
     * terminal edit) uses {@code live=off} via
     * {@link #encodeFinalizeCommand}.
     */
    static @NonNull String encodeUpdateCommand(@NonNull String corrId,
                                               @NonNull String chatItemId,
                                               @NonNull ScopeRef scope,
                                               @NonNull String text) {
        return encodeEdit(corrId, chatItemId, scope, text, /* live */ true);
    }

    /** Encode the terminal edit ({@code live=off}). */
    static @NonNull String encodeFinalizeCommand(@NonNull String corrId,
                                                 @NonNull String chatItemId,
                                                 @NonNull ScopeRef scope,
                                                 @NonNull String text) {
        return encodeEdit(corrId, chatItemId, scope, text, /* live */ false);
    }

    private static String encodeEdit(String corrId, String chatItemId, ScopeRef scope,
                                     String text, boolean live) {
        requireWithinCap(text);
        String target = targetSelector(scope);
        String cmd = "/_update item " + target + " " + chatItemId
                + " live=" + (live ? "on" : "off")
                + " json " + jsonString(textContent(text));
        return envelope(corrId, cmd);
    }

    /**
     * Encode a typing on/off pulse. Per acceptance item 11 the adapter
     * exposes this even though design §6.4.2 calls SimpleX's first-class
     * typing surface absent: the ticket commits to issuing the
     * {@code apiSetContactTyping}-shaped command, and the SimpleX server
     * either acts on it or returns an error envelope the codec classifies
     * via {@link #classifyError(String)}.
     */
    static @NonNull String encodeTypingCommand(@NonNull String corrId,
                                               @NonNull ScopeRef scope,
                                               boolean typing) {
        String target = targetSelector(scope);
        String cmd = "/_set_contact_typing " + target + " " + (typing ? "on" : "off");
        return envelope(corrId, cmd);
    }

    private static String targetSelector(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm dm -> "@" + dm.contactId();
            case ScopeRef.Group g -> "#" + g.adapterGroupId();
        };
    }

    private static String textContent(String text) {
        ObjectNode msgContent = MAPPER.createObjectNode();
        msgContent.put("type", "text");
        msgContent.put("text", text);
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("msgContent", msgContent);
        return wrapper.toString();
    }

    private static String envelope(String corrId, String cmd) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("corrId", corrId);
        root.put("cmd", cmd);
        return root.toString();
    }

    private static String jsonString(String raw) {
        // raw is already a serialised JSON object; pass through as-is.
        return raw;
    }

    private static void requireWithinCap(String text) {
        int byteLength = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (byteLength > MAX_OUTBOUND_TEXT_BYTES) {
            throw new IllegalArgumentException(
                    "outbound text " + byteLength + " bytes exceeds adapter cap "
                            + MAX_OUTBOUND_TEXT_BYTES);
        }
    }

    // --- Inbound decoding ---------------------------------------------------

    /**
     * Parse a single text frame received from simplex-chat. Returns one of
     * the {@link DecodedFrame} variants; never null. A frame that is not
     * valid JSON or is missing the {@code resp} envelope throws
     * {@link MalformedFrameException} so the WS-client layer can log + drop
     * without tearing the connection down (parallel to NostrMessage.parse's
     * MalformedFrameException discipline).
     *
     * <p>Group-scope inbound messages are decoded as {@link Ignored} for
     * M1-103 — group support and the mention-recognition handshake land in
     * M1-104, which extends this method to emit {@link Inbound} for groups
     * once the bot's queue address is known at decode time.</p>
     */
    static @NonNull DecodedFrame decode(@NonNull String frame) {
        JsonNode root;
        try {
            root = MAPPER.readTree(frame);
        } catch (JsonProcessingException e) {
            throw new MalformedFrameException("frame is not JSON: " + e.getOriginalMessage());
        }
        JsonNode resp = root.get("resp");
        if (resp == null || !resp.isObject()) {
            throw new MalformedFrameException("frame missing 'resp' object");
        }
        JsonNode typeNode = resp.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw new MalformedFrameException("frame missing 'resp.type'");
        }
        String corrId = optText(root, "corrId");
        String type = typeNode.asText();
        return switch (type) {
            case "newChatItem" -> decodeNewChatItem(resp);
            case "sentMessage", "apiSendMessageResponse", "newChatItems" -> decodeSendAck(corrId, resp);
            case "chatCmdError", "chatItemUpdateError" -> decodeError(corrId, resp);
            default -> new Ignored(type);
        };
    }

    private static DecodedFrame decodeNewChatItem(JsonNode resp) {
        JsonNode chatItem = resp.get("chatItem");
        if (chatItem == null || !chatItem.isObject()) {
            return new Ignored("newChatItem-without-chatItem");
        }
        JsonNode chatInfo = chatItem.get("chatInfo");
        if (chatInfo == null) {
            return new Ignored("newChatItem-without-chatInfo");
        }
        String chatType = optText(chatInfo, "chatType");
        if (chatType == null) {
            return new Ignored("newChatItem-without-chatType");
        }
        // Group scope is M1-104 territory — the mention-recognition rule
        // depends on the bot's queue address which the adapter does not
        // surface to the codec in this ticket. Drop with a marker so the
        // WS client can DEBUG-log.
        if (!"direct".equals(chatType)) {
            return new Ignored("newChatItem-non-direct:" + chatType);
        }
        JsonNode contact = chatInfo.get("contact");
        if (contact == null) {
            return new Ignored("newChatItem-direct-without-contact");
        }
        String contactId = optText(contact, "contactId");
        if (contactId == null) {
            return new Ignored("newChatItem-without-contactId");
        }
        String displayName = optText(contact, "displayName");
        JsonNode itemBody = chatItem.get("chatItem");
        if (itemBody == null) {
            return new Ignored("newChatItem-without-inner-chatItem");
        }
        JsonNode content = itemBody.get("content");
        if (content == null) {
            return new Ignored("newChatItem-without-content");
        }
        JsonNode msgContent = content.get("msgContent");
        if (msgContent == null) {
            return new Ignored("newChatItem-without-msgContent");
        }
        String text = optText(msgContent, "text");
        if (text == null) {
            return new Ignored("newChatItem-without-text");
        }
        String adapterMessageId = optText(itemBody, "itemId");
        if (adapterMessageId == null) {
            adapterMessageId = "simplex-" + System.nanoTime();
        }
        Identity sender = new Identity(contactId, displayName, Instant.now());
        InboundMessage msg = new InboundMessage(
                sender,
                new ScopeRef.Dm(contactId),
                text,
                Instant.now(),
                adapterMessageId);
        return new Inbound(msg);
    }

    private static DecodedFrame decodeSendAck(String corrId, JsonNode resp) {
        // Real simplex-chat returns one of several response shapes. Look for
        // a chatItemId anywhere reasonable; fall back to the bare envelope if
        // simplex-chat's response shape later varies.
        String chatItemId = findFirstString(resp, "itemId", "chatItemId");
        if (chatItemId == null) {
            return new Ignored("send-ack-without-chatItemId");
        }
        return new SendAck(corrId == null ? "" : corrId, chatItemId);
    }

    private static DecodedFrame decodeError(String corrId, JsonNode resp) {
        String errorTag = findFirstString(resp, "chatError", "errorType", "error");
        FailureCategory category = classifyError(errorTag == null ? "" : errorTag);
        return new CommandError(corrId == null ? "" : corrId,
                category,
                errorTag == null ? resp.toString() : errorTag);
    }

    private static String optText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Depth-first search for a textual node with one of the given names. */
    private static String findFirstString(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode direct = node.get(name);
            if (direct != null && direct.isTextual()) {
                return direct.asText();
            }
        }
        // One-level descent: simplex-chat error envelopes nest the
        // discriminating tag under {chatError: {type: "..."}}.
        if (node.isObject()) {
            var fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                JsonNode child = node.get(fieldNames.next());
                if (child != null && child.isObject()) {
                    for (String name : names) {
                        JsonNode tagged = child.get(name);
                        if (tagged != null && tagged.isTextual()) {
                            return tagged.asText();
                        }
                    }
                }
            }
        }
        return null;
    }

    // --- Failure classification ---------------------------------------------

    /**
     * Bucket a simplex-chat error tag into {@link FailureCategory#TRANSIENT}
     * or {@link FailureCategory#PERMANENT} per {@code docs/spec/messaging.md}
     * §Failure handling and {@code docs/design/06-messaging.md} §6.4.7.
     * Unknown tags default to {@code PERMANENT} per the spec rule.
     */
    static @NonNull FailureCategory classifyError(@NonNull String errorTag) {
        String lower = errorTag.toLowerCase(java.util.Locale.ROOT);
        // Transient: anything that looks like a network reset, idle close,
        // rate limit, or "try again" signal. The spec wants these retried by
        // the Provider's uniform retry policy.
        if (lower.contains("ratelimit")
                || lower.contains("tryagain")
                || lower.contains("networkerror")
                || lower.contains("timeout")
                || lower.contains("temporary")
                || lower.contains("unavailable")
                || lower.contains("connectionerror")) {
            return FailureCategory.TRANSIENT;
        }
        // Permanent: blocked, gone, oversize, rotated identity, policy
        // violation. The full list lives in design §6.4.7; the tags below
        // are the ones M1-103 commits to bucketing — anything else falls
        // through to PERMANENT by the spec's "cannot tell → PERMANENT" rule.
        return FailureCategory.PERMANENT;
    }

    // --- Sealed decoded-frame surface ---------------------------------------

    /** Sealed hierarchy of frame variants returned by {@link #decode}. */
    sealed interface DecodedFrame permits Inbound, SendAck, CommandError, Ignored {
    }

    /** A direct-message inbound that should be delivered to Provider. */
    record Inbound(@NonNull InboundMessage message) implements DecodedFrame {
    }

    /** Response to a command we previously issued, carrying the chat-item id. */
    record SendAck(@NonNull String corrId, @NonNull String chatItemId) implements DecodedFrame {
    }

    /** Error response to a command, with the categorised failure. */
    record CommandError(@NonNull String corrId,
                        @NonNull FailureCategory category,
                        @NonNull String detail) implements DecodedFrame {
    }

    /** A frame we recognised but do not handle in this milestone. */
    record Ignored(@NonNull String reason) implements DecodedFrame {
    }

    /**
     * Thrown when a received frame cannot be parsed as a SimpleX envelope.
     * Unchecked so the WS listener can catch at one boundary and log + drop;
     * mirrors {@code NostrMessage.MalformedFrameException}.
     */
    static final class MalformedFrameException extends RuntimeException {
        MalformedFrameException(@NonNull String message) {
            super(message);
        }
    }
}
