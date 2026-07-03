package app.zcat.infochat.provider.live;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-545: proves the scenario grammar's capture/substitution extension gives
 * scenarios cross-step data flow — declaratively, through the unmodified
 * {@link ScenarioRunner} core. The headline test drives the invite lifecycle
 * (admin mints, the code is captured from the reply, a fresh contact registers
 * by sending the captured code) from a plain-text scenario; the companion tests
 * pin the extension's contract points: substitution into address tokens, the
 * loud unbound-placeholder failure (nothing may reach the transport), the loud
 * capture-no-match failure, and parse-time rejection of malformed directives.
 *
 * <p>Runs under the same {@code inmemory} profile as {@link ScenarioRunnerIT}
 * (same deployment shape, same bootstrap admin); lifecycle correctness itself
 * stays with {@code GoldenPathJourneyIT} and the per-scenario ITs (D-live-5).
 */
@QuarkusTest
@TestProfile(ScenarioRunnerIT.Profile.class)
class ScenarioCaptureIT {

    /**
     * Contact prefix owned by THIS IT — deliberately not scn-smoke-, so this
     * cleanup and ScenarioRunnerIT's cannot interfere with each other.
     */
    private static final String CAPTURE_CONTACT_PREFIX = "scn-cap-";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        // Idempotency under Dev Services DB reuse: the scenario REGISTERS
        // scn-cap-user, so a prior run leaves a users row (plus its invite and
        // audit trail) that would turn step 2's reply into an already-registered
        // response instead of the welcome.
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM invite_code WHERE expected_contact_id LIKE ?",
                    CAPTURE_CONTACT_PREFIX + "%");
            // audit_log carries no-update + no-delete triggers (V5); disable them
            // for the prefixed wipe then re-enable.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn, "DELETE FROM audit_log WHERE target_contact_id LIKE ?",
                        CAPTURE_CONTACT_PREFIX + "%");
                exec(conn, "DELETE FROM users WHERE contact_id LIKE ?",
                        CAPTURE_CONTACT_PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void captureAndSubstitutionDriveInviteMintConsumeDeclaratively() throws IOException {
        Scenario scenario = loadScenario("invite-mint-consume.scenario");
        ScenarioRunner runner = new ScenarioRunner(new InMemoryConversationBackend(adapter));

        ScenarioRunner.RunReport report = runner.run(scenario);

        assertEquals(2, report.steps().size(), "expected the mint step and the consume step");
        assertTrue(report.steps().get(0).matchedReply().contains("Invite code:"),
                "step 1 should have matched the minted invite reply");
        assertTrue(report.steps().get(1).matchedReply().contains("Welcome! You're registered."),
                "step 2: the fresh contact must have registered with the captured code");
    }

    @Test
    void capturedBindingSubstitutesIntoAddressTokens() {
        Scenario scenario = Scenario.parse("address-probe", """
                send dm oracle whoami
                expect substring 200 you are
                capture who you are ([a-z]+)

                send dm ${who} hello
                expect substring 200 ok
                """);
        RecordingBackend backend = new RecordingBackend("you are bob", "ok");

        new ScenarioRunner(backend).run(scenario);

        assertEquals("bob", backend.sends.get(1).addresses().get(0),
                "step 2's contact address should be the captured binding");
    }

    @Test
    void unboundPlaceholderFailsNamingStepAndPlaceholderAndSendsNothing() {
        Scenario scenario = new Scenario("unbound-probe", List.of(
                new Scenario.Step(
                        new Scenario.Send(Scenario.Scope.DM, List.of("scn-cap-user"), "use ${nope} here"),
                        new Scenario.Expect(Scenario.MatchKind.SUBSTRING, "irrelevant",
                                Duration.ofMillis(200)))));
        RecordingBackend backend = new RecordingBackend();

        ScenarioRunner.ScenarioFailedException failure = assertThrows(
                ScenarioRunner.ScenarioFailedException.class,
                () -> new ScenarioRunner(backend).run(scenario));

        assertTrue(failure.getMessage().contains("step 1"), "failure names the step");
        assertTrue(failure.getMessage().contains("${nope}"), "failure names the placeholder");
        assertTrue(backend.sends.isEmpty(), "the raw ${...} text must never reach the transport");
    }

    @Test
    void captureRegexNoMatchFailsNamingStepAndCapture() {
        Scenario scenario = Scenario.parse("no-match-probe", """
                send dm oracle whoami
                expect substring 200 you are
                capture who number ([0-9]+)
                """);
        RecordingBackend backend = new RecordingBackend("you are bob");

        ScenarioRunner.ScenarioFailedException failure = assertThrows(
                ScenarioRunner.ScenarioFailedException.class,
                () -> new ScenarioRunner(backend).run(scenario));

        assertTrue(failure.getMessage().contains("step 1"), "failure names the step");
        assertTrue(failure.getMessage().contains("'who'"), "failure names the capture");
    }

    @Test
    void malformedCaptureDirectivesFailAtParseTime() {
        // capture before any completed step
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("bad", """
                capture code ([0-9]+)
                """));
        // capture between a send and its expect
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("bad", """
                send dm a /help
                capture code ([0-9]+)
                expect substring 200 x
                """));
        // capture regex without a capturing group
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("bad", """
                send dm a /help
                expect substring 200 x
                capture code [0-9]+
                """));
    }

    private Scenario loadScenario(String resourceName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/scenarios/" + resourceName)) {
            assertNotNull(in, "scenario resource not found: " + resourceName);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Scenario.parse(resourceName, text);
        }
    }

    private static void exec(Connection conn, String sql, String... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Scripted transport stub: records the RESOLVED sends the runner delivers
     * (post-substitution — the point under test) and replies from a fixed queue,
     * so the contract tests need no adapter or DB.
     */
    private static final class RecordingBackend implements ConversationBackend {

        final List<Scenario.Send> sends = new ArrayList<>();
        private final Deque<String> scriptedReplies = new ArrayDeque<>();

        RecordingBackend(String... replies) {
            scriptedReplies.addAll(List.of(replies));
        }

        @Override
        public void send(Scenario.Send send) {
            sends.add(send);
        }

        @Override
        public Optional<String> awaitReply(Scenario.Expect expect) {
            String reply = scriptedReplies.poll();
            return reply != null && expect.matches(reply) ? Optional.of(reply) : Optional.empty();
        }
    }
}
