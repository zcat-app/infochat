package app.zcat.infochat.provider.live;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-539 (live-e2e Phase 4a): proves the backend-agnostic scenario runner executes
 * a declarative scenario end to end through {@link InMemoryAdapter} and captures
 * per-step latency. Runs under the {@code inmemory} deployment shape (D46) — the
 * same profile {@code GoldenPathJourneyIT} uses.
 *
 * <p>This does NOT re-prove lifecycle correctness (that stays in
 * {@code GoldenPathJourneyIT} and the per-scenario ITs, D-live-5). It proves the
 * substrate: a scenario file drives ordered send->expect steps, each matched within
 * its timeout, with a populated per-step latency — plus that the poll-until-timeout
 * wait actually gates (a never-matching step fails rather than false-greens).
 */
@QuarkusTest
@TestProfile(ScenarioRunnerIT.Profile.class)
class ScenarioRunnerIT {

    private static final String ADMIN = "scn-smoke-admin";
    /** All contact ids this IT mints invites for share this prefix, for reuse-safe cleanup. */
    private static final String SCENARIO_CONTACT_PREFIX = "scn-smoke-";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        // Idempotency under Dev Services DB reuse: drop any invite this IT minted on a
        // prior run so /invite create does not accumulate toward the per-contact cap.
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM invite_code WHERE expected_contact_id LIKE ?")) {
            ps.setString(1, SCENARIO_CONTACT_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    @Test
    void runsGoldenPathSmokeScenarioAndCapturesPerStepLatency() throws IOException {
        Scenario scenario = loadScenario("golden-path-smoke.scenario");
        ScenarioRunner runner = new ScenarioRunner(new InMemoryConversationBackend(adapter));

        ScenarioRunner.RunReport report = runner.run(scenario);

        // Every expect matched within its timeout (run() would have thrown otherwise),
        // and the runner produced one result per step.
        assertEquals(scenario.steps().size(), report.steps().size(),
                "expected one result per scenario step");
        assertFalse(report.steps().isEmpty(), "scenario produced no steps");

        for (ScenarioRunner.StepResult result : report.steps()) {
            assertNotNull(result.latency(), "step " + result.stepNumber() + " latency not captured");
            assertFalse(result.latency().isNegative(), "step " + result.stepNumber() + " latency negative");
            assertFalse(result.matchedReply().isBlank(),
                    "step " + result.stepNumber() + " matched an empty reply");
        }

        // Sanity: the two known steps matched their intended replies.
        List<ScenarioRunner.StepResult> steps = report.steps();
        assertTrue(steps.get(0).matchedReply().contains("Available commands:"),
                "step 1 should have matched the /help header");
        assertTrue(steps.get(1).matchedReply().contains("Invite code:"),
                "step 2 should have matched the minted invite reply");
    }

    @Test
    void neverMatchingStepFailsWithinItsTimeout() {
        // A step whose reply can never satisfy the predicate must fail via the timeout,
        // not false-green — this proves the poll-until-match-or-timeout wait gates.
        Scenario scenario = new Scenario("timeout-probe", List.of(
                new Scenario.Step(
                        new Scenario.Send(Scenario.Scope.DM, List.of(ADMIN), "/help"),
                        new Scenario.Expect(Scenario.MatchKind.SUBSTRING,
                                "token-that-never-appears-xyzzy", Duration.ofMillis(200)))));
        ScenarioRunner runner = new ScenarioRunner(new InMemoryConversationBackend(adapter));

        long startNanos = System.nanoTime();
        ScenarioRunner.ScenarioFailedException failure = assertThrows(
                ScenarioRunner.ScenarioFailedException.class, () -> runner.run(scenario));
        long waitedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertTrue(failure.getMessage().contains("timeout-probe"), "failure names the scenario");
        // It waited at least ~the timeout (proving it polled, not gave up early) and did
        // not hang far beyond it (proving the deadline is honoured).
        assertTrue(waitedMillis >= 150, "should have waited close to the 200ms timeout, was " + waitedMillis);
        assertTrue(waitedMillis < 5000, "should not hang well past the timeout, was " + waitedMillis);
    }

    private Scenario loadScenario(String resourceName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/scenarios/" + resourceName)) {
            assertNotNull(in, "scenario resource not found: " + resourceName);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Scenario.parse(resourceName, text);
        }
    }

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.adapters.inmemory.admin", ADMIN);
        }
    }
}
