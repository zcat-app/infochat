package app.zcat.infochat.messaging.impl.signal;


import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.Locale;
import java.util.Optional;

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
 * {@link #encodeUpdateMessage}, and {@link #encodeSendTyping} emit
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
 * normalized via {@link #canonicalizeAci} to a lowercase UUID string
 * so the cross-adapter join key {@code (adapter, contact_id)} from
 * {@code docs/spec/messaging.md} §Per-adapter trust level cannot be
 * broken by case-folding upstream.</p>
 */
final class SignalMessageCodec {

    String encodeSend(long rpcId, String account, String recipient, String message) {
        JsonObject params = Json.createObjectBuilder()
                .add("account", account)
                .add("recipient", Json.createArrayBuilder().add(recipient))
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
     * envelope that fits no JSON-RPC 2.0 shape — the reader treats
     * the throw as a transport-level corruption and disconnects.
     */
    JsonRpcMessage decode(String line) {
        JsonObject obj;
        try (JsonReader reader = Json.createReader(new StringReader(line))) {
            obj = reader.readObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed JSON-RPC envelope: " + line, e);
        }
        String method = obj.getString("method", null);
        if (method != null) {
            JsonObject params = obj.getJsonObject("params");
            if (params == null) {
                params = JsonValue.EMPTY_JSON_OBJECT;
            }
            return new JsonRpcMessage.Notification(method, params);
        }
        // Response: id is present per spec; signal-cli echoes the string id
        // we sent. Absent ids fail below.
        String id = obj.getString("id", null);
        if (id == null) {
            throw new IllegalArgumentException("JSON-RPC envelope missing both method and id: " + line);
        }
        if (obj.containsKey("error")) {
            JsonObject err = obj.getJsonObject("error");
            int code = err.getInt("code", -32603);
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
     * envelope is a group message (group support is M1-108), a
     * sync/typing/receipt notification, or otherwise lacks a usable
     * sender ACI + body.
     */
    Optional<ReceivedDm> extractDm(JsonObject receiveParams) {
        JsonObject envelope = receiveParams.getJsonObject("envelope");
        if (envelope == null) {
            return Optional.empty();
        }
        String sourceUuid = envelope.getString("sourceUuid", null);
        if (sourceUuid == null) {
            return Optional.empty();
        }
        JsonObject dataMessage = envelope.getJsonObject("dataMessage");
        if (dataMessage == null) {
            return Optional.empty();
        }
        // Group messages carry groupInfo / groupV2 — skip; group is M1-108.
        if (dataMessage.containsKey("groupInfo") || dataMessage.containsKey("groupV2")) {
            return Optional.empty();
        }
        String body = dataMessage.getString("message", null);
        if (body == null || body.isEmpty()) {
            return Optional.empty();
        }
        long timestamp = envelope.containsKey("timestamp")
                ? envelope.getJsonNumber("timestamp").longValueExact()
                : dataMessage.getJsonNumber("timestamp").longValueExact();
        return Optional.of(new ReceivedDm(canonicalizeAci(sourceUuid), body, timestamp));
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

    /** Decoded JSON-RPC 2.0 envelope. Sealed for exhaustive dispatch. */
    sealed interface JsonRpcMessage {

        /** Successful response — {@code id} matches the request, {@code result} is the call's return payload. */
        record Response(String id, JsonObject result) implements JsonRpcMessage {}

        /** Error response — {@code id} matches the request, {@code code} is the JSON-RPC error code. */
        record ErrorResponse(String id, int code, String message) implements JsonRpcMessage {}

        /** Server-initiated notification — {@code method} is the event name (e.g. {@code "receive"}). */
        record Notification(String method, JsonObject params) implements JsonRpcMessage {}
    }

    /** Result of decoding a DM-scope inbound from a {@code receive} notification. */
    record ReceivedDm(String senderContactId, String body, long timestamp) {}
}
