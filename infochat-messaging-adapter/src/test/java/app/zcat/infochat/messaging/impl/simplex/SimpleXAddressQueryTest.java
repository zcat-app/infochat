package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SimpleX self-address query behind {@code connectContact()} (M1-620):
 * the {@code /show_address} envelope encodes as a constant command, and the
 * live v6.5.4.1 {@code userContactLink} response decodes to the bot's
 * shareable contact link — short link preferred, full link as fallback, and
 * a link-less response failing fast as a {@link SimpleXMessageCodec.CommandError}
 * rather than stranding the caller's pending future until the ack timeout.
 *
 * <p>The decode fixture is a REAL captured v6.5.4.1 response frame
 * (WsProbe against the live provider's simplex-chat, 2026-07-13),
 * byte-faithful except for two substitutions: the corrId is parameterized,
 * and the two contact-link VALUES are replaced with same-grammar synthetic
 * strings — the bot's real address must not be persisted to any file
 * (D37; M1-620 acceptance item 3). Substitution is via {@code replace},
 * not {@code formatted} — the percent-encoded link bytes ({@code %3A}…)
 * would be misread as format specifiers.</p>
 */
class SimpleXAddressQueryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final String SHORT_LINK =
            "https://smp5.simplex.im/a#TESTfixtureShortLinkHash0000000000000000000";
    private static final String FULL_LINK =
            "simplex:/contact#/?v=2-7&smp=smp%3A%2F%2FTESTfixtureKeyHash0000000000000000000000000"
                    + "%3D%40smp5.simplex.im%2FTESTfixtureQueueId00000000000000%23%2F%3Fv%3D1-4"
                    + "%26dh%3DMCowBQYDK2VuAyEATESTfixtureDhKey00000000000000000000000000%253D"
                    + "%26q%3Dc%26srv%3Dtestfixtureonionserver0000000000000000000000000000000000.onion";

    /** The captured frame, single-line as received off the wire. */
    private static String userContactLinkFrame(String corrId) {
        return ("{\"corrId\":\"__CORRID__\",\"resp\":{\"type\":\"userContactLink\","
                + "\"user\":{\"userId\":1,\"agentUserId\":\"1\",\"userContactId\":1,"
                + "\"localDisplayName\":\"infochat-bot\",\"profile\":{\"profileId\":1,"
                + "\"displayName\":\"infochat-bot\",\"fullName\":\"\",\"preferences\":"
                + "{\"files\":{\"allow\":\"no\"}},\"peerType\":\"bot\",\"localAlias\":\"\"},"
                + "\"fullPreferences\":{\"timedMessages\":{\"allow\":\"yes\"},\"fullDelete\":"
                + "{\"allow\":\"no\"},\"reactions\":{\"allow\":\"yes\"},\"voice\":{\"allow\":\"yes\"},"
                + "\"files\":{\"allow\":\"no\"},\"calls\":{\"allow\":\"yes\"},\"sessions\":"
                + "{\"allow\":\"no\"},\"commands\":[]},\"activeUser\":true,\"activeOrder\":1,"
                + "\"showNtfs\":true,\"sendRcptsContacts\":true,\"sendRcptsSmallGroups\":true,"
                + "\"autoAcceptMemberContacts\":false,\"userChatRelay\":false},"
                + "\"contactLink\":{\"userContactLinkId\":1,\"connLinkContact\":"
                + "{\"connFullLink\":\"__FULL__\",\"connShortLink\":\"__SHORT__\"},"
                + "\"shortLinkDataSet\":true,\"shortLinkLargeDataSet\":true,"
                + "\"addressSettings\":{\"businessAddress\":false,\"autoAccept\":"
                + "{\"acceptIncognito\":false}}}}}")
                .replace("__CORRID__", corrId)
                .replace("__FULL__", FULL_LINK)
                .replace("__SHORT__", SHORT_LINK);
    }

    @TempDir
    Path tempDir;

    @Test
    void encodeShowAddressCommandProducesConstantCommandEnvelope() throws Exception {
        JsonNode envelope = MAPPER.readTree(
                SimpleXMessageCodec.encodeShowAddressCommand("addr-corr-1"));
        assertEquals("addr-corr-1", envelope.get("corrId").asText());
        assertEquals("/show_address", envelope.get("cmd").asText());
    }

    @Test
    void decodePrefersShortLinkFromCapturedFrame() {
        SimpleXMessageCodec.DecodedFrame decoded =
                SimpleXMessageCodec.decode(userContactLinkFrame("addr-corr-2"));
        SimpleXMessageCodec.ContactAddress address =
                assertInstanceOf(SimpleXMessageCodec.ContactAddress.class, decoded);
        assertEquals("addr-corr-2", address.corrId());
        assertEquals(SHORT_LINK, address.contactLink());
    }

    @Test
    void decodeFallsBackToFullLinkWhenShortLinkAbsent() {
        String frame = "{\"corrId\":\"addr-corr-3\",\"resp\":{\"type\":\"userContactLink\","
                + "\"contactLink\":{\"connLinkContact\":{\"connFullLink\":\"__FULL__\"}}}}"
                .replace("__FULL__", FULL_LINK);
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(frame);
        SimpleXMessageCodec.ContactAddress address =
                assertInstanceOf(SimpleXMessageCodec.ContactAddress.class, decoded);
        assertEquals(FULL_LINK, address.contactLink());
    }

    @Test
    void decodeWithoutAnyLinkFailsFastAsCommandError() {
        String frame = "{\"corrId\":\"addr-corr-4\",\"resp\":{\"type\":\"userContactLink\","
                + "\"contactLink\":{\"connLinkContact\":{}}}}";
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(frame);
        SimpleXMessageCodec.CommandError error =
                assertInstanceOf(SimpleXMessageCodec.CommandError.class, decoded);
        assertEquals("addr-corr-4", error.corrId());
        assertEquals(FailureCategory.PERMANENT, error.category());
        // Fixed sentinel, never frame bytes — the detail flows into logs.
        assertEquals("userContactLink-without-link", error.detail());
    }

    @Test
    void decodeWithoutCorrIdIsIgnored() {
        String frame = "{\"resp\":{\"type\":\"userContactLink\",\"contactLink\":"
                + "{\"connLinkContact\":{\"connShortLink\":\"__SHORT__\"}}}}"
                .replace("__SHORT__", SHORT_LINK);
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(frame);
        assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded);
    }

    @Test
    void connectContactRoundTripReturnsShortLink() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, tempDir);
            adapter.rebuildWebSocket();
            try {
                fake.awaitClient(WAIT);
                Thread responder = Thread.ofVirtual().start(() -> {
                    try {
                        String envelope = fake.awaitFrame(WAIT);
                        JsonNode query = MAPPER.readTree(envelope);
                        assertEquals("/show_address", query.get("cmd").asText());
                        fake.sendFrame(userContactLinkFrame(query.get("corrId").asText()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                assertEquals(Optional.of(SHORT_LINK), adapter.connectContact());
                assertTrue(responder.join(WAIT), "responder did not see the query within " + WAIT);
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void connectContactSurfacesCommandErrorAsMessagingException() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, tempDir);
            adapter.rebuildWebSocket();
            try {
                fake.awaitClient(WAIT);
                Thread responder = Thread.ofVirtual().start(() -> {
                    try {
                        String envelope = fake.awaitFrame(WAIT);
                        String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                        fake.sendFrame("{\"corrId\":\"" + corrId + "\",\"resp\":"
                                + "{\"type\":\"chatCmdError\",\"chatError\":{\"errorType\":"
                                + "{\"type\":\"commandError\"}}}}");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                MessagingException failure =
                        assertThrows(MessagingException.class, adapter::connectContact);
                assertEquals(FailureCategory.PERMANENT, failure.category());
                assertTrue(responder.join(WAIT), "responder did not see the query within " + WAIT);
            } finally {
                adapter.close();
            }
        }
    }
}
