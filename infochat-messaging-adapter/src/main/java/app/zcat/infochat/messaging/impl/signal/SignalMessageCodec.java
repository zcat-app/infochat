package app.zcat.infochat.messaging.impl.signal;


import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Pure JSON-RPC 2.0 framing for signal-cli's daemon protocol. No I/O,
 * no threading, no static mutable state — every method is a pure
 * function over its arguments so a {@link SignalJsonRpcClient}'s
 * reader and writer threads can call into the codec concurrently
 * without coordination.
 *
 * <p>signal-cli speaks line-delimited JSON on its TCP daemon endpoint:
 * each JSON-RPC envelope (request, response, notification) is one
 * object terminated by a literal {@code "\n"}. {@link #encodeSend},
 * {@link #encodeGroupSend}, {@link #encodeUpdateMessage},
 * {@link #encodeGroupUpdateMessage}, and {@link #encodeSendTyping} emit
 * the object WITHOUT the trailing newline — the caller frames at the
 * stream layer. {@link #decode} accepts a single line (one object).</p>
 *
 * <p>The decoded view is a sealed {@link JsonRpcMessage} so
 * {@link SignalJsonRpcClient}'s reader dispatch is a compile-checked
 * exhaustive switch — a new envelope kind (e.g. JSON-RPC batch
 * responses, not used by signal-cli today) is a protocol amendment,
 * not a per-call invention.</p>
 *
 * <p>ACIs surfaced by signal-cli's {@code envelope.sourceUuid} are
 * gated at decode by {@link #isAcceptableAci} (v1 accepts canonical
 * UUID identities only) and normalized via {@link #canonicalizeAci} to
 * a lowercase UUID string, so the cross-adapter join key
 * {@code (adapter, contact_id)} from {@code docs/spec/messaging.md}
 * §Per-adapter trust level cannot be broken by case-folding upstream,
 * and a wire value that cannot be asserted as a UUID is dropped rather
 * than persisted as a join key.</p>
 */
final class SignalMessageCodec {

    /**
     * Inbound decoded-body cap in UTF-8 bytes — the single source of the
     * {@code maxInboundMessageBytes} capability {@code SignalAdapter}
     * advertises (which reads this constant directly, so the decode-time
     * enforcement and the advertised SPI value cannot drift; v1 fixes the
     * value at 16 KiB per {@code docs/design/06-messaging.md} §6.2.2). The
     * coarse char-domain line cap in {@link SignalJsonRpcClient} bounds the
     * raw envelope line against an unterminated-line OOM; this bounds the
     * decoded message body so the Provider's downstream budgets plan against
     * a real ceiling rather than the line cap.
     */
    static final int MAX_INBOUND_TEXT_BYTES = 16_384;

    /**
     * Canonical UUID charset gate for v1 inbound identities. Signal
     * binds an ACI (a UUID) to each account; v1 accepts UUID identities
     * only (M1-242 §Notes), so a wire {@code sourceUuid} that is not a
     * canonical UUID is dropped at decode rather than asserted as a join
     * key. Matched case-insensitively (the value is lowercased first).
     */
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    String encodeSend(long rpcId, String account, String recipient, String message) {
        JsonObject params = Json.createObjectBuilder()
                .add("account", account)
                .add("recipient", Json.createArrayBuilder().add(recipient))
                .add("message", message)
                .build();
        return encodeRequest(rpcId, "send", params);
    }

    /**
     * Group variant of {@link #encodeSend}: signal-cli's {@code send}
     * addresses a group by a single {@code groupId} string in place of
     * the {@code recipient} array — the two are mutually exclusive on
     * the wire, so the group destination is never an array.
     */
    String encodeGroupSend(long rpcId, String account, String groupId, String message) {
        JsonObject params = Json.createObjectBuilder()
                .add("account", account)
                .add("groupId", groupId)
                .add("message", message)
                .build();
        return encodeRequest(rpcId, "send", params);
    }

    String encodeUpdateMessage(long rpcId, String account, String recipient,
                               long targetSentTimestamp, String message) {
        JsonObject params = Json.createObjectBuilder()
                .add("account", account)
                .add("recipient", Json.createArrayBuilder().add(recipient))
                .add("targetSentTimestamp", targetSentTimestamp)
                .add("message", message)
                .build();
        return encodeRequest(rpcId, "updateMessage", params);
    }

    /**
     * Group variant of {@link #encodeUpdateMessage}: edits a prior
     * group message addressed by {@code groupId} instead of the
     * {@code recipient} array, targeting the {@code targetSentTimestamp}
     * signal-cli returned for the original group send.
     */
    String encodeGroupUpdateMessage(long rpcId, String account, String groupId,
                                    long targetSentTimestamp, String message) {
        JsonObject params = Json.createObjectBuilder()
                .add("account", account)
                .add("groupId", groupId)
                .add("targetSentTimestamp", targetSentTimestamp)
                .add("message", message)
                .build();
        return encodeRequest(rpcId, "updateMessage", params);
    }

    String encodeSendTyping(long rpcId, String account, String recipient, boolean typing) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("account", account)
                .add("recipient", Json.createArrayBuilder().add(recipient));
        // signal-cli's sendTyping defaults to start=true; setting stop=true
        // toggles the indicator off.
        if (!typing) {
            b.add("stop", true);
        }
        return encodeRequest(rpcId, "sendTyping", b.build());
    }

    private String encodeRequest(long rpcId, String method, JsonObject params) {
        return Json.createObjectBuilder()
                .add("jsonrpc", "2.0")
                .add("id", String.valueOf(rpcId))
                .add("method", method)
                .add("params", params)
                .build()
                .toString();
    }

    /**
     * Decode one line of the daemon stream. Throws
     * {@link IllegalArgumentException} on malformed JSON or on an
     * envelope that fits no JSON-RPC 2.0 shape — the reader logs the
     * exception class name and drops the line. The raw line is user
     * content (chat-mode bodies ride in it), so per D37 and the
     * security spec §User content in exceptions the thrown message is
     * fixed text: no frame bytes, and no parser cause either — the
     * parser's own message embeds the offending token.
     */
    JsonRpcMessage decode(String line) {
        JsonObject obj;
        try (JsonReader reader = Json.createReader(new StringReader(line))) {
            obj = reader.readObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed JSON-RPC envelope");
        }
        String method = obj.getString("method", null);
        if (method != null) {
            // instanceof doubles as the null-check and the type check: a
            // wrong-typed (non-object) params member is treated like an
            // absent one rather than letting getJsonObject throw CCE.
            JsonObject params = obj.get("params") instanceof JsonObject p
                    ? p
                    : JsonValue.EMPTY_JSON_OBJECT;
            return new JsonRpcMessage.Notification(method, params);
        }
        // Response: id is present per spec; signal-cli echoes the string id
        // we sent. Absent ids fail below.
        String id = obj.getString("id", null);
        if (id == null) {
            throw new IllegalArgumentException("JSON-RPC envelope missing both method and id");
        }
        // A wrong-typed (non-object) error member falls through to the
        // Response branch: the frame carried our id, and an empty-result
        // Response fails the caller fast with a classified error instead
        // of leaving its future to time out.
        if (obj.get("error") instanceof JsonObject err) {
            // A missing or non-numeric "code" member cannot prove the
            // daemon's transient -32603 case, so it must fall to the spec's
            // default-PERMANENT rule (FailureCategory). Surface it as 0 —
            // unassigned in JSON-RPC, classified PERMANENT like every
            // non--32603 code — never as a synthesized -32603, which would
            // misclassify an unprovable error as TRANSIENT.
            int code = err.get("code") instanceof JsonNumber n ? n.intValue() : 0;
            String msg = err.getString("message", "(no message)");
            return new JsonRpcMessage.ErrorResponse(id, code, msg);
        }
        JsonObject result = obj.containsKey("result") && obj.get("result").getValueType() == JsonValue.ValueType.OBJECT
                ? obj.getJsonObject("result")
                : JsonValue.EMPTY_JSON_OBJECT;
        return new JsonRpcMessage.Response(id, result);
    }

    /**
     * Try to extract a DM-scope inbound message from a
     * {@code method=receive} notification. Returns empty when the
     * envelope is a group message, a sync/typing/receipt notification,
     * or otherwise lacks a usable sender ACI + body.
     */
    Optional<ReceivedDm> extractDm(JsonObject receiveParams) {
        // This method must be total over arbitrary inbound frame shapes:
        // the daemon stream is a trust boundary, and an NPE/CCE escaping
        // here used to kill the thread that processes inbound frames while
        // the subprocess stayed alive — a permanently deaf adapter with no
        // restart trigger. instanceof doubles as null-check + type check.
        if (!(receiveParams.get("envelope") instanceof JsonObject envelope)) {
            return Optional.empty();
        }
        String sourceUuid = envelope.getString("sourceUuid", null);
        if (sourceUuid == null || !isAcceptableAci(sourceUuid)) {
            // v1 accepts only canonical-UUID identities: an inbound ACI
            // that cannot be asserted is dropped at decode rather than
            // becoming a permanent (adapter, contact_id) join key.
            return Optional.empty();
        }
        if (!(envelope.get("dataMessage") instanceof JsonObject dataMessage)) {
            return Optional.empty();
        }
        // Group messages carry groupInfo / groupV2 — skip (not a DM); the
        // group route handles them.
        if (dataMessage.containsKey("groupInfo") || dataMessage.containsKey("groupV2")) {
            return Optional.empty();
        }
        String body = dataMessage.getString("message", null);
        if (body == null || body.isEmpty()) {
            return Optional.empty();
        }
        if (exceedsInboundByteCap(body)) {
            // Decoded-body UTF-8 byte cap (the maxInboundMessageBytes
            // capability), mirroring SimpleX — the coarse char-domain line
            // cap in SignalJsonRpcClient does not bound the body.
            return Optional.empty();
        }
        Long timestamp = usableTimestamp(envelope, dataMessage);
        if (timestamp == null) {
            return Optional.empty();
        }
        // sourceName is the sender's profile name (informational only,
        // D10: never authoritative); absent on profile-less senders → null.
        String sourceName = envelope.getString("sourceName", null);
        return Optional.of(new ReceivedDm(canonicalizeAci(sourceUuid), sourceName, body, timestamp));
    }

    /**
     * Millisecond timestamp from {@code envelope.timestamp}, falling
     * back to {@code dataMessage.timestamp}; null when neither field
     * holds a usable (integral, long-range) JSON number — the caller
     * drops the frame instead of letting a typed accessor throw
     * NPE (absent) or CCE (wrong-typed). Package-private so the group
     * inbound path ({@link SignalGroupHandler}) guards the same untrusted
     * field through this one total reference implementation.
     */
    static @Nullable Long usableTimestamp(JsonObject envelope, JsonObject dataMessage) {
        Long fromEnvelope = integralLong(envelope.get("timestamp"));
        return fromEnvelope != null ? fromEnvelope : integralLong(dataMessage.get("timestamp"));
    }

    private static @Nullable Long integralLong(@Nullable JsonValue value) {
        if (!(value instanceof JsonNumber number)) {
            return null;
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException e) {
            // Fractional or beyond-long-range — not a usable timestamp.
            return null;
        }
    }

    /**
     * Normalize a Signal ACI to its canonical lowercase-UUID form so
     * {@code (adapter, contact_id)} comparisons cannot be broken by
     * case-folding upstream (design §6.5.3). Returns the input
     * untouched if it does not parse as a UUID — the caller decides
     * whether to accept non-UUID identifiers (e.g. legacy phone-number
     * sources during account migration).
     */
    String canonicalizeAci(String aci) {
        return aci.toLowerCase(Locale.ROOT);
    }

    /**
     * True when {@code body}'s UTF-8 encoding exceeds
     * {@link #MAX_INBOUND_TEXT_BYTES}. Shared by the DM path
     * ({@link #extractDm}) and the group path ({@link SignalGroupHandler})
     * so both reject an oversize decoded body the same way SimpleX does.
     */
    static boolean exceedsInboundByteCap(String body) {
        return body.getBytes(StandardCharsets.UTF_8).length > MAX_INBOUND_TEXT_BYTES;
    }

    /**
     * True when {@code aci} is acceptable as a v1 inbound identity: a
     * canonical UUID, matched case-insensitively against
     * {@link #CANONICAL_UUID}. Shared by the DM path and the group path
     * so both drop an unassertable identity at decode instead of
     * persisting it as an {@code (adapter, contact_id)} join key.
     */
    static boolean isAcceptableAci(String aci) {
        return CANONICAL_UUID.matcher(aci.toLowerCase(Locale.ROOT)).matches();
    }

    /** Decoded JSON-RPC 2.0 envelope. Sealed for exhaustive dispatch. */
    sealed interface JsonRpcMessage {

        /** Successful response — {@code id} matches the request, {@code result} is the call's return payload. */
        record Response(String id, JsonObject result) implements JsonRpcMessage {}

        /** Error response — {@code id} matches the request, {@code code} is the JSON-RPC error code. */
        record ErrorResponse(String id, int code, String message) implements JsonRpcMessage {}

        /** Server-initiated notification — {@code method} is the event name (e.g. {@code "receive"}). */
        record Notification(String method, JsonObject params) implements JsonRpcMessage {}
    }

    /**
     * Result of decoding a DM-scope inbound from a {@code receive}
     * notification. {@code senderDisplayName} is the sender's Signal
     * profile name (informational only, D10), null when the envelope
     * carries no {@code sourceName}.
     */
    record ReceivedDm(String senderContactId, @Nullable String senderDisplayName,
                      String body, long timestamp) {}
}
