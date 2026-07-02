package app.zcat.infochat.provider.live;

import app.zcat.infochat.messaging.impl.simplex.LiveSimpleXClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-e2e Phase 4b-2 host validation (D-live-9): drives a declarative
 * {@link Scenario} through the REAL transport — host-side simplex-chat client
 * (LiveAdmin identity) → SMP relays → deployed Provider bot → reply back — via
 * {@link SimpleXConversationBackend}. This is exactly the run CI cannot fake:
 * real process, real WS API, real relays, real bot.
 *
 * <p><b>Opt-in only.</b> Gated on {@code -Dinfochat.live.simplex=true} so
 * {@code mvn verify} stays green and hermetic everywhere (the class is skipped,
 * never failed, without the flag). Preconditions when enabled: the prod stack
 * is UP (per HANDOFF §HOST STATE), the LiveAdmin client identity exists under
 * the clients dir, its WS port is free, and no other process (e.g. a one-shot
 * CLI session) holds the client DB.</p>
 *
 * <p>Host run: {@code mvn -pl infochat-provider test
 * -Dtest=LiveSimpleXRoundTripIT -Dinfochat.live.simplex=true}</p>
 */
@EnabledIfSystemProperty(named = "infochat.live.simplex", matches = "true",
        disabledReason = "live SimpleX round-trip runs only on the host, against the deployed bot")
class LiveSimpleXRoundTripIT {

    /** Scenario token for the LiveAdmin-driven conversation side. */
    private static final String ADMIN_TOKEN = "admin";
    /** The bot's contact name as recorded in the LiveAdmin client DB (HANDOFF). */
    private static final String BOT_DISPLAY_NAME = "Admin-Reno";

    @Test
    void adminHelpRoundTripsOverRealSimpleX() throws Exception {
        Path clientsDir = clientsDir();
        String binary = clientsDir.resolve("bin/simplex-chat").toString();
        Path adminDataDir = clientsDir.resolve("admin");
        assertTrue(Files.isRegularFile(Path.of(binary)), "simplex-chat binary not found: " + binary);
        assertTrue(Files.isDirectory(adminDataDir), "LiveAdmin data dir not found: " + adminDataDir);

        try (LiveSimpleXClient admin = new LiveSimpleXClient(
                binary, adminDataDir.toString(), wsPort(), ADMIN_TOKEN)) {
            admin.start();
            String botContactId = admin.resolveContactId(BOT_DISPLAY_NAME);

            SimpleXConversationBackend backend = new SimpleXConversationBackend(Map.of(
                    ADMIN_TOKEN, new SimpleXConversationBackend.ClientBinding(admin, botContactId)));

            // /help from the claimed bootstrap admin: a short-command plain reply,
            // generous timeout for relay latency. The reply header is the same one
            // ScenarioRunnerIT asserts on the InMemory backend — same scenario
            // grammar, real transport (M1-539 seam proven live).
            Scenario scenario = Scenario.parse("live-simplex-admin-help", """
                    send dm admin /help
                    expect substring 60000 Available commands:
                    """);

            ScenarioRunner.RunReport report = new ScenarioRunner(backend).run(scenario);

            List<ScenarioRunner.StepResult> steps = report.steps();
            assertFalse(steps.isEmpty(), "scenario produced no steps");
            for (ScenarioRunner.StepResult result : steps) {
                // Real-transport latency is necessarily non-trivial (relay hops);
                // print it — the live run's whole point includes latency evidence.
                System.out.printf("live step %d: matched in %d ms%n",
                        result.stepNumber(), result.latency().toMillis());
                assertTrue(result.latency().toMillis() > 0,
                        "live round-trip latency must be non-zero");
            }
        }
    }

    private static Path clientsDir() {
        // Module-relative default (surefire cwd = infochat-provider/); overridable
        // for a non-standard layout.
        String configured = System.getProperty(
                "infochat.live.simplex.clients-dir", "../prod/runtime/simplex-clients");
        Path dir = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(dir), "clients dir not found: " + dir
                + " (set -Dinfochat.live.simplex.clients-dir)");
        return dir;
    }

    private static int wsPort() {
        // 5226: the bot owns 5225; keep client ports distinct per identity.
        return Integer.getInteger("infochat.live.simplex.admin-ws-port", 5226);
    }
}
