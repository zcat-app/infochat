package app.zcat.infochat.messaging.impl.simplex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Test fixture for the self-address derivation wire surface
 * ({@code SimpleXAdapter#deriveAndAdoptIdentity}): contact-link and
 * {@code userContactLink}-frame builders plus responders that answer the
 * {@code /show_address} query a starting adapter issues. Public, like
 * {@code SignalAccountStoreFixture} on the Signal side, because the
 * Provider-side ITs start real adapters against {@link FakeSimpleXProcess}
 * and must answer the query from outside this package (the fake's
 * {@code sendFrame} is package-private by design — this fixture is the
 * narrow doorway, not the whole frame-injection surface).
 */
public final class SimpleXSelfAddressFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SimpleXSelfAddressFixture() {
    }

    /**
     * A full contact link as simplex-chat returns it: the SMP queue URI —
     * {@code smp://<keyhash>@<host>/<queueId>#…} — percent-encoded into
     * the {@code smp} fragment parameter. Built by concatenation because
     * the percent escapes collide with {@code String.formatted} syntax.
     */
    public static String contactLink(String queueAddressId) {
        return "simplex:/contact#/?v=2-7"
                + "&smp=smp%3A%2F%2FServerKeyHashFingerprint00000000000000000%3D"
                + "%40smp.example.org%2F" + queueAddressId
                + "%23%2F%3Fv%3D1-4%26dh%3DMCowBQYDK2VuAyEAExampleDhKey0000"
                + "&data=%7B%7D";
    }

    /** The {@code userContactLink} response frame carrying {@code fullLink}. */
    public static String userContactLinkFrame(String corrId, String fullLink) {
        return "{\"corrId\":\"" + corrId + "\",\"resp\":{\"type\":\"userContactLink\","
                + "\"user\":{\"userId\":1},"
                + "\"contactLink\":{\"connLinkContact\":"
                + "{\"connFullLink\":\"" + fullLink + "\"}}}}";
    }

    /**
     * Standing responder: answer every {@code /show_address} query with a
     * {@code userContactLink} frame carrying the currently supplied link;
     * non-query frames are left alone. Exits on interrupt or when no frame
     * arrives for 15 s. Do NOT use where the test itself awaits non-query
     * frames from the same fake — {@code awaitFrame} consumes, so a
     * standing loop would steal them; use
     * {@link #answerNextShowAddress(FakeSimpleXProcess, String)} there.
     */
    public static Thread startShowAddressResponder(FakeSimpleXProcess fake,
                                                   Supplier<String> servedLink) {
        return Thread.ofVirtual().name("fake-simplex-show-address-responder").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String corrId = awaitShowAddressCorrId(fake);
                    fake.sendFrame(userContactLinkFrame(corrId, servedLink.get()));
                }
            } catch (Exception e) {
                // awaitFrame timeout / interrupt / teardown IO — done.
            }
        });
    }

    /**
     * One-shot responder: answer the FIRST {@code /show_address} query with
     * {@code fullLink}, then exit — leaving every subsequent frame for the
     * test to await. For tests that probe the fake's frame queue directly
     * after {@code start()} completes.
     */
    public static Thread answerNextShowAddress(FakeSimpleXProcess fake, String fullLink) {
        return Thread.ofVirtual().name("fake-simplex-show-address-one-shot").start(() -> {
            try {
                String corrId = awaitShowAddressCorrId(fake);
                fake.sendFrame(userContactLinkFrame(corrId, fullLink));
            } catch (Exception e) {
                // awaitFrame timeout / interrupt / teardown IO — done.
            }
        });
    }

    /** Block until a {@code /show_address} envelope arrives; return its corrId. */
    private static String awaitShowAddressCorrId(FakeSimpleXProcess fake) throws Exception {
        while (true) {
            String envelope = fake.awaitFrame(Duration.ofSeconds(15));
            JsonNode root = MAPPER.readTree(envelope);
            JsonNode corrId = root.get("corrId");
            JsonNode cmd = root.get("cmd");
            if (corrId != null && cmd != null
                    && cmd.asText().startsWith("/show_address")) {
                return corrId.asText();
            }
        }
    }
}
