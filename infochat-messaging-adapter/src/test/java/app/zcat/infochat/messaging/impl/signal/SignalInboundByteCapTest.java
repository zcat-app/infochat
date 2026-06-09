package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;

/**
 * T1: the decoded-body UTF-8 byte cap ({@link SignalMessageCodec#MAX_INBOUND_TEXT_BYTES})
 * is enforced on both the DM path ({@link SignalMessageCodec#extractDm})
 * and the group path ({@link SignalGroupHandler}), mirroring SimpleX. A
 * body that is under the coarse char-domain line cap (16_384 UTF-16
 * chars) but over the 16_384-byte body cap — reachable only with
 * multi-byte characters — is rejected at decode; a well-formed body is
 * delivered.
 */
class SignalInboundByteCapTest {

    private static final String SENDER_ACI = "AABBCCDD-1111-2222-3333-444455556666";
    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    // 6000 BMP CJK chars: 6000 UTF-16 code units (< the 16_384 char line
    // cap) but 18_000 UTF-8 bytes (> the 16_384 byte body cap) — the
    // multi-byte gap T1 closes.
    private static final String OVERSIZE_BODY = "中".repeat(6_000);

    private final SignalMessageCodec codec = new SignalMessageCodec();

    @Test
    void oversizeBodyIsUnderCharCapButOverByteCap() {
        assertTrue(OVERSIZE_BODY.length() < 16_384,
                "test body must stay under the 16_384 UTF-16 char line cap");
        assertTrue(OVERSIZE_BODY.getBytes(StandardCharsets.UTF_8).length
                        > SignalMessageCodec.MAX_INBOUND_TEXT_BYTES,
                "test body must exceed the 16_384 UTF-8 byte body cap");
    }

    @Test
    void dmPathRejectsOversizeBody() {
        assertTrue(codec.extractDm(dmParams(OVERSIZE_BODY)).isEmpty(),
                "a decoded body over the byte cap must drop on the DM path");
    }

    @Test
    void groupPathRejectsOversizeBody() {
        RecordingInbound inbound = new RecordingInbound();
        // Membership handler unused — message frames never hit that branch.
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, null);
        handler.handleReceive(groupParams(OVERSIZE_BODY));
        assertEquals(0, inbound.messages.size(),
                "a decoded body over the byte cap must drop on the group path "
                        + "(before the bot-mention check)");
    }

    @Test
    void wellFormedBodyDeliveredOnBothPaths() {
        assertTrue(codec.extractDm(dmParams("hi from Alice")).isPresent(),
                "a body under the cap must extract on the DM path");

        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, null);
        handler.handleReceive(groupParams("@bot summarise"));
        assertEquals(1, inbound.messages.size(),
                "a body under the cap with a bot mention must deliver on the group path");
    }

    private static JsonObject dmParams(String body) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("sourceUuid", SENDER_ACI)
                        .add("timestamp", 1_700_000_001_000L)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", 1_700_000_001_000L)
                                .add("message", body)))
                .build();
    }

    private static JsonObject groupParams(String body) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("sourceUuid", SENDER_ACI)
                        .add("timestamp", 1_700_000_001_000L)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", 1_700_000_001_000L)
                                .add("message", body)
                                .add("groupV2", Json.createObjectBuilder().add("id", GROUP_V2_ID))
                                .add("mentions", Json.createArrayBuilder()
                                        .add(Json.createObjectBuilder()
                                                .add("uuid", BOT_ACI)
                                                .add("start", 0)
                                                .add("length", 4)))))
                .build();
    }

    private static final class RecordingInbound implements MessagingAdapter.InboundHandler {
        final List<InboundMessage> messages = new ArrayList<>();

        @Override
        public void onMessage(InboundMessage msg) {
            messages.add(msg);
        }
    }
}
