package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.JsonObject;


import org.junit.jupiter.api.Test;

class SignalMessageCodecTest {

    private final SignalMessageCodec codec = new SignalMessageCodec();

    @Test
    void encodesAndDecodesMessages() {
        // Outbound `send` — round-trip the request envelope through
        // jakarta.json so we read it as JSON, not as a fragile substring.
        String sendEnvelope = codec.encodeSend(
                42L, "+15551234567", "+15557654321", "Hello, world!");
        JsonObject sendObj = parse(sendEnvelope);
        assertEquals("2.0", sendObj.getString("jsonrpc"));
        assertEquals("42", sendObj.getString("id"));
        assertEquals("send", sendObj.getString("method"));
        JsonObject sendParams = sendObj.getJsonObject("params");
        assertEquals("+15551234567", sendParams.getString("account"));
        assertEquals("+15557654321", sendParams.getJsonArray("recipient").getString(0));
        assertEquals("Hello, world!", sendParams.getString("message"));

        // Outbound edit — a `send` carrying editTimestamp, targeting the
        // edited revision's send timestamp: signal-cli has no updateMessage
        // method (its jsonRpc surface mirrors the CLI, whose edit is
        // `send --edit-timestamp`; F-live-11).
        String editEnvelope = codec.encodeEditSend(
                43L, "+15551234567", "+15557654321", 1700000000123L, "Hello, edited!");
        JsonObject editObj = parse(editEnvelope);
        assertEquals("send", editObj.getString("method"),
                "an edit rides the send method — signal-cli has no updateMessage");
        JsonObject editParams = editObj.getJsonObject("params");
        assertEquals(1700000000123L,
                editParams.getJsonNumber("editTimestamp").longValueExact());
        assertEquals("Hello, edited!", editParams.getString("message"));
        assertEquals("+15557654321", editParams.getJsonArray("recipient").getString(0));
        assertFalse(editParams.containsKey("targetSentTimestamp"),
                "targetSentTimestamp belongs to the nonexistent updateMessage method");

        // Outbound `sendTyping` — start typing.
        String typingStart = codec.encodeSendTyping(
                44L, "+15551234567", "+15557654321", true);
        JsonObject typingStartObj = parse(typingStart);
        assertEquals("sendTyping", typingStartObj.getString("method"));
        JsonObject typingStartParams = typingStartObj.getJsonObject("params");
        assertFalse(typingStartParams.containsKey("stop"),
                "stop must be absent when starting typing");

        // Outbound `sendTyping` — stop typing.
        String typingStop = codec.encodeSendTyping(
                45L, "+15551234567", "+15557654321", false);
        JsonObject typingStopObj = parse(typingStop);
        JsonObject typingStopParams = typingStopObj.getJsonObject("params");
        assertTrue(typingStopParams.getBoolean("stop"),
                "stop=true must be set when stopping typing");

        // Inbound `receive` notification — decode and extract a DM.
        String receiveLine = """
                {
                  "jsonrpc": "2.0",
                  "method": "receive",
                  "params": {
                    "envelope": {
                      "source": "+15557654321",
                      "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                      "sourceName": "Alice",
                      "sourceDevice": 1,
                      "timestamp": 1700000001000,
                      "dataMessage": {
                        "timestamp": 1700000001000,
                        "message": "hi from Alice",
                        "expiresInSeconds": 0,
                        "viewOnce": false
                      }
                    }
                  }
                }
                """;
        SignalMessageCodec.JsonRpcMessage decoded = codec.decode(receiveLine);
        SignalMessageCodec.JsonRpcMessage.Notification notif = assertInstanceOf(
                SignalMessageCodec.JsonRpcMessage.Notification.class, decoded);
        assertEquals("receive", notif.method());
        SignalMessageCodec.ReceivedDm dm = assertInstanceOf(SignalMessageCodec.DmMessage.class,
                codec.extractDm(notif.params()),
                "DM extraction must succeed for envelope with dataMessage").received();
        assertEquals(
                "aabbccdd-1111-2222-3333-444455556666",
                dm.senderContactId(),
                "ACI must be lower-cased per design §6.5.3 canonicalization");
        assertEquals("hi from Alice", dm.body());
        assertEquals(1700000001000L, dm.timestamp());

        // Inbound success Response — `send` returns timestamp result.
        String responseLine = """
                {
                  "jsonrpc": "2.0",
                  "id": "42",
                  "result": {
                    "timestamp": 1700000000456,
                    "results": []
                  }
                }
                """;
        SignalMessageCodec.JsonRpcMessage rsp = codec.decode(responseLine);
        SignalMessageCodec.JsonRpcMessage.Response ok = assertInstanceOf(
                SignalMessageCodec.JsonRpcMessage.Response.class, rsp);
        assertEquals("42", ok.id());
        assertEquals(1700000000456L,
                ok.result().getJsonNumber("timestamp").longValueExact());

        // Inbound error Response — signal-cli returns a JSON-RPC error.
        String errorLine = """
                {"jsonrpc":"2.0","id":"42","error":{"code":-32603,"message":"Internal error"}}
                """;
        SignalMessageCodec.JsonRpcMessage errMsg = codec.decode(errorLine);
        SignalMessageCodec.JsonRpcMessage.ErrorResponse err = assertInstanceOf(
                SignalMessageCodec.JsonRpcMessage.ErrorResponse.class, errMsg);
        assertEquals("42", err.id());
        assertEquals(-32603, err.code());
        assertEquals("Internal error", err.message());
    }

    @Test
    void groupEditEncodesAsSendWithEditTimestamp() {
        // Group edit frame shape (F-live-11 twin of the DM pin above):
        // method `send`, addressed by groupId, editTimestamp present,
        // no targetSentTimestamp.
        String envelope = codec.encodeGroupEditSend(
                50L, "+15551234567", "group-1", 1700000000123L, "group edit");
        JsonObject obj = parse(envelope);
        assertEquals("send", obj.getString("method"),
                "a group edit rides the send method — signal-cli has no updateMessage");
        JsonObject params = obj.getJsonObject("params");
        assertEquals("group-1", params.getString("groupId"));
        assertFalse(params.containsKey("recipient"),
                "group edit must not carry a recipient array — groupId replaces it");
        assertEquals(1700000000123L,
                params.getJsonNumber("editTimestamp").longValueExact());
        assertEquals("group edit", params.getString("message"));
        assertFalse(params.containsKey("targetSentTimestamp"),
                "targetSentTimestamp belongs to the nonexistent updateMessage method");
    }

    @Test
    void groupReceiveDoesNotExtractAsDm() {
        // A receive notification with groupInfo present is a group
        // message and must NOT extract as a DM — group support is M1-108.
        String groupReceive = """
                {
                  "jsonrpc": "2.0",
                  "method": "receive",
                  "params": {
                    "envelope": {
                      "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                      "timestamp": 1700000000000,
                      "dataMessage": {
                        "timestamp": 1700000000000,
                        "message": "hi group",
                        "groupInfo": {"groupId": "abc=="}
                      }
                    }
                  }
                }
                """;
        SignalMessageCodec.JsonRpcMessage.Notification notif =
                (SignalMessageCodec.JsonRpcMessage.Notification) codec.decode(groupReceive);
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(notif.params()),
                "Group-scope receive notification must NOT extract as a DM");

        // groupV2 path — newer signal-cli format.
        String groupV2Receive = """
                {
                  "jsonrpc": "2.0",
                  "method": "receive",
                  "params": {
                    "envelope": {
                      "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                      "timestamp": 1700000000000,
                      "dataMessage": {
                        "timestamp": 1700000000000,
                        "message": "hi v2 group",
                        "groupV2": {"id": "abc=="}
                      }
                    }
                  }
                }
                """;
        SignalMessageCodec.JsonRpcMessage.Notification notifV2 =
                (SignalMessageCodec.JsonRpcMessage.Notification) codec.decode(groupV2Receive);
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(notifV2.params()),
                "groupV2 receive notification must NOT extract as a DM");
    }

    @Test
    void emptyOrMissingBodyDoesNotExtractAsDm() {
        // A `receive` notification with no dataMessage (e.g. typing,
        // delivery receipts) must not extract as a DM.
        String typingOnly = """
                {
                  "jsonrpc": "2.0",
                  "method": "receive",
                  "params": {
                    "envelope": {
                      "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                      "timestamp": 1700000000000,
                      "typingMessage": {"action": "STARTED"}
                    }
                  }
                }
                """;
        SignalMessageCodec.JsonRpcMessage.Notification notif =
                (SignalMessageCodec.JsonRpcMessage.Notification) codec.decode(typingOnly);
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(notif.params()));
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("not json {{{"));
    }

    @Test
    void canonicalizeAciLowercases() {
        assertEquals(
                "aabbccdd-1111-2222-3333-444455556666",
                codec.canonicalizeAci("AABBCCDD-1111-2222-3333-444455556666"));
    }

}
