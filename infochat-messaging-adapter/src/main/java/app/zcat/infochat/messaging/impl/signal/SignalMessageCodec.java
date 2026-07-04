package app.zcat.infochat.messaging.impl.signal;


import app.zcat.infochat.messaging.ContactIdRedactor;
import app.zcat.infochat.messaging.Utf8;

import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.Base64;
import java.util.Locale;
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
 * {@link #encodeGroupSend}, {@link #encodeEditSend},
 * {@link #encodeGroupEditSend}, and {@link #encodeSendTyping} emit
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

    /**
     * Edit of a prior DM, encoded as a {@code send} carrying
     * {@code editTimestamp}: signal-cli's jsonRpc methods mirror its CLI
     * command surface, and the CLI edit is {@code send --edit-timestamp} —
     * there is no {@code updateMessage} method, so 0.14.5 rejects that
     * spelling as method-not-found and every edit silently degraded to the
     * fresh-send fallback on real wire (F-live-11 / M1-566).
     */
    String encodeEditSend(long rpcId, String account, String recipient,
                          long editTimestamp, String message) {
        JsonObject params = Json.createObjectBuilder()
                .add("account", account)
                .add("recipient", Json.createArrayBuilder().add(recipient))
                .add("message", message)
                .add("editTimestamp", editTimestamp)
                .build();
        return encodeRequest(rpcId, "send", params);
    }

    /**
     * Group variant of {@link #encodeEditSend}: edits a prior group
     * message addressed by {@code groupId} instead of the
     * {@code recipient} array, targeting the {@code editTimestamp} of
     * the revision being replaced.
     */
    String encodeGroupEditSend(long rpcId, String account, String groupId,
                               long editTimestamp, String message) {
        JsonObject params = Json.createObjectBuilder()
                .add("account", account)
                .add("groupId", groupId)
                .add("message", message)
                .add("editTimestamp", editTimestamp)
                .build();
        return encodeRequest(rpcId, "send", params);
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
     * Classify a {@code method=receive} notification as a DM-scope
     * outcome ({@link DmDecode}): a usable {@link DmMessage}, an
     * {@link OversizeDm} (the decoded body exceeds the inbound size cap —
     * dropped at decode, but the sender + adapterMessageId are surfaced so
     * the consumer can count the drop and WARN per §6.3.10), or
     * {@link NotDm#INSTANCE} (a group / sync / typing / receipt frame, or
     * one lacking a usable sender ACI, body, or timestamp). Stays a pure
     * function: the cap CHECK and the attribution extraction happen here;
     * the counter + WARN are the consumer's job.
     */
    DmDecode extractDm(JsonObject receiveParams) {
        // This method must be total over arbitrary inbound frame shapes:
        // the daemon stream is a trust boundary, and an NPE/CCE escaping
        // here used to kill the thread that processes inbound frames while
        // the subprocess stayed alive — a permanently deaf adapter with no
        // restart trigger. instanceof doubles as null-check + type check.
        if (!(receiveParams.get("envelope") instanceof JsonObject envelope)) {
            return NotDm.INSTANCE;
        }
        String sourceUuid = envelope.getString("sourceUuid", null);
        if (sourceUuid == null || !isAcceptableAci(sourceUuid)) {
            // v1 accepts only canonical-UUID identities: an inbound ACI
            // that cannot be asserted is dropped at decode rather than
            // becoming a permanent (adapter, contact_id) join key.
            return NotDm.INSTANCE;
        }
        if (!(envelope.get("dataMessage") instanceof JsonObject dataMessage)) {
            return NotDm.INSTANCE;
        }
        // Group messages carry groupInfo / groupV2 — skip (not a DM); the
        // group route handles them.
        if (dataMessage.containsKey("groupInfo") || dataMessage.containsKey("groupV2")) {
            return NotDm.INSTANCE;
        }
        String body = dataMessage.getString("message", null);
        if (body == null || body.isEmpty()) {
            return NotDm.INSTANCE;
        }
        Long timestamp = usableTimestamp(envelope, dataMessage);
        if (timestamp == null) {
            return NotDm.INSTANCE;
        }
        String senderContactId = canonicalizeAci(sourceUuid);
        if (exceedsInboundByteCap(body)) {
            // Decoded-body UTF-8 byte cap (the maxInboundMessageBytes
            // capability), mirroring SimpleX — the coarse char-domain line
            // cap in SignalJsonRpcClient does not bound the body. The cap
            // CHECK stays here at decode (enforcement point unchanged); the
            // drop is surfaced as OversizeDm rather than silently swallowed
            // so the consumer raises adapter.inbound.dropped{reason=oversize}
            // and the §6.3.10 WARN with the sender + adapterMessageId.
            return new OversizeDm(senderContactId, "signal-" + timestamp);
        }
        // sourceName is the sender's profile name (informational only,
        // D10: never authoritative); absent on profile-less senders → null.
        String sourceName = envelope.getString("sourceName", null);
        return new DmMessage(new ReceivedDm(senderContactId, sourceName, body, timestamp));
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
     * case-folding upstream (design §6.5.3). Lowercases unconditionally;
     * the UUID gate runs upstream in {@link #isAcceptableAci}, so every
     * value reaching here has already been asserted as a canonical UUID.
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
        return Utf8.exceedsByteLength(body, MAX_INBOUND_TEXT_BYTES);
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

    /**
     * True when {@code groupId} is acceptable as a v1 group scope key: it
     * strict-{@link Base64} decodes AND the decoded length falls within the
     * [16, 64]-byte band. Mirrors {@link #isAcceptableAci} — the group
     * route's admission gate on the scope key, so a malformed or unbounded
     * group id never becomes a {@code ScopeRef.Group} key. Defense-in-depth
     * (M1-565): the value arrives from the co-located signal-cli over a
     * loopback channel the threat model trusts today, but if that boundary
     * is ever redrawn the scope key is already shape-gated, exactly as the
     * sender ACI is.
     *
     * <p>The bounds are deliberately a BAND, not an exact pin: the
     * live-observed group v2 id is 32 bytes and the existing test fixtures
     * decode to 20 — an exact-length gate is the F-live-10
     * overstrict-assumption failure mode (a too-narrow shape assumption
     * silently dropping real traffic) in reverse.</p>
     */
    static boolean isAcceptableGroupId(String groupId) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(groupId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return decoded.length >= 16 && decoded.length <= 64;
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

    /**
     * Outcome of {@link #extractDm}. Sealed so the consumer's dispatch is
     * an exhaustive, compile-checked switch over the three DM-decode
     * results.
     */
    sealed interface DmDecode permits DmMessage, OversizeDm, NotDm {}

    /** A usable DM-scope inbound, ready for delivery to Provider. */
    record DmMessage(ReceivedDm received) implements DmDecode {}

    /**
     * A DM dropped at decode for exceeding the inbound size cap
     * (§6.3.10). Carries the attribution the consumer needs to raise
     * {@code adapter.inbound.dropped{reason=oversize}} and the WARN: the
     * canonicalized sender contact id (redacted at the log site — never
     * logged raw, D37) and the {@code adapterMessageId}.
     */
    record OversizeDm(String senderContactId, String adapterMessageId) implements DmDecode {}

    /**
     * Not a usable DM: a group / sync / typing / receipt frame, or one
     * lacking a usable sender ACI, body, or timestamp. The group route
     * ({@link SignalGroupHandler}) owns the group-scope shapes.
     */
    enum NotDm implements DmDecode { INSTANCE }

    /**
     * Non-reversible short token for a sender contact id, safe to log
     * under D37 (a Signal ACI is a sensitive identifier and is never
     * logged raw). Stable per sender so a repeat flooder stays
     * correlatable across drop WARN lines without exposing the id. Delegates
     * to the shared {@link ContactIdRedactor} so the SimpleX and Signal
     * transports share one implementation of the primitive (M1-472); the
     * Signal drop sites ({@link SignalJsonRpcClient}, {@link SignalGroupHandler})
     * keep calling through the codec.
     */
    static String redactContactId(String contactId) {
        return ContactIdRedactor.redact(contactId);
    }
}
