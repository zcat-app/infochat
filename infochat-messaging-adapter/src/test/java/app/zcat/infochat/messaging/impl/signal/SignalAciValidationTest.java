package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.json.Json;
import jakarta.json.JsonObject;


import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.metrics.AdapterMetrics;

/**
 * T2: an inbound Signal contact id (the DM {@code sourceUuid} and the
 * group sender id) is validated at decode against the v1 accepted-identity
 * set before it becomes an {@code (adapter, contact_id)} join key. v1
 * accepts canonical UUID identities only (M1-242 §Notes): a UUID is
 * accepted (case-folded to its canonical lowercase form); a non-UUID wire
 * string — including an E.164 phone number — is dropped at decode.
 */
class SignalAciValidationTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";
    private static final String CANONICAL_UUID = "aabbccdd-1111-2222-3333-444455556666";

    private final SignalMessageCodec codec = new SignalMessageCodec();

    @Test
    void canonicalLowercaseUuidAcceptedOnDmPath() {
        SignalMessageCodec.ReceivedDm dm = assertInstanceOf(SignalMessageCodec.DmMessage.class,
                codec.extractDm(dmParams(CANONICAL_UUID)),
                "a canonical lowercase UUID ACI must be accepted").received();
        assertEquals(CANONICAL_UUID, dm.senderContactId());
    }

    @Test
    void uppercaseUuidAcceptedAndCanonicalizedOnDmPath() {
        // Case-fold contract: an uppercase wire UUID is accepted and
        // normalized so the (adapter, contact_id) join key is stable.
        SignalMessageCodec.ReceivedDm dm = assertInstanceOf(SignalMessageCodec.DmMessage.class,
                codec.extractDm(dmParams("AABBCCDD-1111-2222-3333-444455556666")),
                "an uppercase UUID ACI must be accepted").received();
        assertEquals(CANONICAL_UUID, dm.senderContactId(),
                "the accepted UUID must be canonicalized to lowercase");
    }

    @Test
    void nonUuidWireStringDroppedOnDmPath() {
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(dmParams("not-a-uuid")),
                "a non-UUID sourceUuid must drop at decode rather than become a join key");
    }

    @Test
    void e164PhoneNumberDroppedOnDmPath() {
        // v1 is UUID-only (M1-242 §Notes): an E.164 phone-number identity is
        // NOT accepted — it cannot anchor a stable cryptographic join key.
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(dmParams("+15557654321")),
                "an E.164 ACI must drop under the v1 UUID-only policy");
    }

    @Test
    void uuidGroupSenderDelivered() {
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, null, AdapterMetrics.noop());
        handler.handleReceive(groupParams(CANONICAL_UUID));
        assertEquals(1, inbound.messages.size(),
                "a group message from a canonical-UUID sender must deliver");
        assertEquals(CANONICAL_UUID, inbound.messages.get(0).sender().contactId());
    }

    @Test
    void nonUuidGroupSenderDropped() {
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, null, AdapterMetrics.noop());
        handler.handleReceive(groupParams("not-a-uuid"));
        assertEquals(0, inbound.messages.size(),
                "a group message from a non-UUID sender must drop at decode");
    }

    private static JsonObject dmParams(String sourceUuid) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("sourceUuid", sourceUuid)
                        .add("timestamp", 1_700_000_001_000L)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", 1_700_000_001_000L)
                                .add("message", "hi from the sender")))
                .build();
    }

    private static JsonObject groupParams(String sourceUuid) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("sourceUuid", sourceUuid)
                        .add("timestamp", 1_700_000_001_000L)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", 1_700_000_001_000L)
                                .add("message", "@bot summarise")
                                .add("groupV2", Json.createObjectBuilder().add("id", GROUP_V2_ID))
                                .add("mentions", Json.createArrayBuilder()
                                        .add(Json.createObjectBuilder()
                                                .add("uuid", BOT_ACI)
                                                .add("start", 0)
                                                .add("length", 4)))))
                .build();
    }

}
