package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * U-35 successor: {@link SimpleXAdapter#start()} validates the DERIVED bot
 * queue address with {@link SimpleXIdentity#isWellFormed} (parity with
 * Signal's startup ACI validation). The anchor is no longer operator-typed
 * — it is derived from the running simplex-chat's {@code /show_address}
 * answer — so the rejected-value start failure re-targets to the
 * equivalent derivation-failure modes: a well-formed-charset but too-short
 * address fails adoption, and a response without an extractable contact
 * link fails the query itself. Failure semantics are preserved: start()
 * fails THAT adapter, and the message names the derivation source — never
 * the value (D37: queue addresses are never logged raw).
 *
 * <p>start() runs for real against a {@link FakeSimpleXProcess} answering
 * the self-address query, with a stay-alive wrapper script standing in for
 * the simplex-chat binary so {@code cfg.validate()} passes and the
 * supervised child is restart-free (same choreography as
 * {@code SimpleXAdapterIdentityDerivationTest}).</p>
 */
@DisabledOnOs(OS.WINDOWS)
class SimpleXStartIdentityValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Passes the codec's charset gate but fails SimpleXIdentity.isWellFormed's
    // 43-char cryptographic-length floor at adoption.
    private static final String SHORT_QUEUE_ID = "TooShortToBeARealQueueAddr";

    @TempDir
    Path tempDir;

    @Test
    void startRejectsMalformedDerivedQueueAddressNamingTheSource() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            Thread responder = startShowAddressResponder(fake,
                    "{\"corrId\":\"%CORR%\",\"resp\":{\"type\":\"userContactLink\","
                            + "\"contactLink\":{\"connLinkContact\":{\"connFullLink\":\""
                            + contactLink(SHORT_QUEUE_ID) + "\"}}}}");
            SimpleXAdapter adapter = newAdapter(fake);
            try {
                IllegalStateException thrown =
                        assertThrows(IllegalStateException.class, adapter::start);
                assertTrue(thrown.getMessage().contains("show-address"),
                        "the rejection message must name the derivation source the"
                                + " operator investigates; was: " + thrown.getMessage());
                assertFalse(thrown.getMessage().contains(SHORT_QUEUE_ID),
                        "the rejected address value must never appear in the message"
                                + " (D37); was: " + thrown.getMessage());
            } finally {
                responder.interrupt();
                adapter.close();
            }
        }
    }

    @Test
    void startFailsWhenContactLinkCannotBeExtractedNamingTheSource() throws Exception {
        // The response carries no extractable contact link — the codec maps
        // it to the fixed self-address sentinel and the query fails the
        // start() promptly (PERMANENT: wire-contract drift is fixed by
        // code, not retries).
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            Thread responder = startShowAddressResponder(fake,
                    "{\"corrId\":\"%CORR%\",\"resp\":{\"type\":\"userContactLink\"}}");
            SimpleXAdapter adapter = newAdapter(fake);
            try {
                MessagingException thrown =
                        assertThrows(MessagingException.class, adapter::start);
                assertEquals(FailureCategory.PERMANENT, thrown.category(),
                        "an undecodable self-address response cannot be retried away");
                assertTrue(thrown.getMessage().contains("self-address"),
                        "the failure message must name the self-address query as the"
                                + " source; was: " + thrown.getMessage());
            } finally {
                responder.interrupt();
                adapter.close();
            }
        }
    }

    // -- choreography helpers --------------------------------------------------

    private SimpleXAdapter newAdapter(FakeSimpleXProcess fake) throws IOException {
        SimpleXConfig cfg = new SimpleXConfig(
                stayAliveBinary().toString(), tempDir.toString(), fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused */ });
    }

    /** Executable stand-in for the simplex-chat binary: ignores args, stays alive. */
    private Path stayAliveBinary() throws IOException {
        Path script = tempDir.resolve("fake-simplex-chat");
        if (!Files.exists(script)) {
            Files.writeString(script, "#!/bin/sh\nexec sleep 300\n");
            if (!script.toFile().setExecutable(true)) {
                throw new IllegalStateException(
                        "could not mark the stand-in binary executable: " + script);
            }
        }
        return script;
    }

    /**
     * Answer the first {@code /show_address} query with {@code response}
     * ({@code %CORR%} substituted with the query's corrId), then exit.
     */
    private static Thread startShowAddressResponder(FakeSimpleXProcess fake, String response) {
        return Thread.ofVirtual().name("fake-simplex-show-address-responder").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String envelope = fake.awaitFrame(Duration.ofSeconds(15));
                    JsonNode root = MAPPER.readTree(envelope);
                    JsonNode corrId = root.get("corrId");
                    JsonNode cmd = root.get("cmd");
                    if (corrId == null || cmd == null
                            || !cmd.asText().startsWith("/show_address")) {
                        continue;
                    }
                    fake.sendFrame(response.replace("%CORR%", corrId.asText()));
                    return;
                }
            } catch (Exception e) {
                // awaitFrame timeout / interrupt / teardown IO — done.
            }
        });
    }

    private static String contactLink(String queueAddressId) {
        return SimpleXSelfAddressFixture.contactLink(queueAddressId);
    }
}
