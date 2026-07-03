package app.zcat.infochat.provider.live;

import app.zcat.infochat.messaging.impl.simplex.LiveSimpleXClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-e2e Phase 4b-3 (M1-546): drives the 7 transport-relevant scenarios
 * (Phase 1 enumeration — 3, 4, 7, 10, 11, 12, 15) through the REAL transport
 * via the unmodified {@link ScenarioRunner}: host-side simplex-chat clients
 * (LiveAdmin + LiveUser) → SMP relays → deployed Provider bot → replies back.
 *
 * <p><b>Opt-in only.</b> Gated on {@code -Dinfochat.live.simplex=true} (same
 * gate as {@link LiveSimpleXRoundTripIT}) so {@code mvn verify} stays green and
 * hermetic everywhere — skipped, never failed, without the flag.</p>
 *
 * <p><b>The suite order is a default, not a fixture manager.</b> Several
 * scenarios need HOST actions between them (HANDOFF §Live-run notes): s04 and
 * s03 need an UNREGISTERED user ({@code prod/live-reset.sh}; s03 then registers
 * them); s07 needs the 'live-group' fixture + a vouched user; s10 needs
 * {@code prod/live-seed.sh}, follows, and a config-aimed digest window
 * (D-live-8); s15 needs a reset again after s03. Run scenarios individually as
 * the host orchestration progresses:
 * {@code mvn -pl infochat-provider verify -Dit.test='LiveSimpleXScenarioSuiteIT#s11ZcashSnapshot' -Dinfochat.live.simplex=true}</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "infochat.live.simplex", matches = "true",
        disabledReason = "live SimpleX scenarios run only on the host, against the deployed bot")
class LiveSimpleXScenarioSuiteIT {

    /** Scenario tokens (the addresses in the live .scenario resources). */
    private static final String ADMIN_TOKEN = "admin";
    private static final String USER_TOKEN = "user";
    /** The bot's contact name as recorded in BOTH client DBs (HANDOFF §HOST STATE). */
    private static final String BOT_DISPLAY_NAME = "Admin-Reno";

    private static LiveSimpleXClient admin;
    private static LiveSimpleXClient user;
    private static ScenarioRunner runner;

    @BeforeAll
    static void startClients() throws Exception {
        Path clientsDir = clientsDir();
        String binary = clientsDir.resolve("bin/simplex-chat").toString();
        assertTrue(Files.isRegularFile(Path.of(binary)), "simplex-chat binary not found: " + binary);

        admin = new LiveSimpleXClient(binary, clientsDir.resolve("admin").toString(),
                Integer.getInteger("infochat.live.simplex.admin-ws-port", 5226), ADMIN_TOKEN);
        user = new LiveSimpleXClient(binary, clientsDir.resolve("user").toString(),
                Integer.getInteger("infochat.live.simplex.user-ws-port", 5227), USER_TOKEN);
        admin.start();
        user.start();

        SimpleXConversationBackend backend = new SimpleXConversationBackend(Map.of(
                ADMIN_TOKEN, new SimpleXConversationBackend.ClientBinding(
                        admin, admin.resolveContactId(BOT_DISPLAY_NAME)),
                USER_TOKEN, new SimpleXConversationBackend.ClientBinding(
                        user, user.resolveContactId(BOT_DISPLAY_NAME))));
        runner = new ScenarioRunner(backend);
    }

    @AfterAll
    static void closeClients() {
        if (user != null) {
            user.close();
        }
        if (admin != null) {
            admin.close();
        }
    }

    // s04 before s03: s04 needs the user UNREGISTERED, s03 registers them.
    @Test
    @Order(1)
    void s04UninvitedDmRejected() throws Exception {
        run("s04-uninvited-dm-rejected.scenario");
    }

    @Test
    @Order(2)
    void s03InviteMintConsume() throws Exception {
        run("s03-invite-mint-consume.scenario");
    }

    @Test
    @Order(3)
    void s07GroupPendingApproveAutoPromote() throws Exception {
        run("s07-group-pending-approve-autopromote.scenario");
    }

    @Test
    @Order(4)
    void s10SummaryAndGroupDigest() throws Exception {
        run("s10-summary-digest.scenario");
    }

    @Test
    @Order(5)
    void s11ZcashSnapshot() throws Exception {
        run("s11-zcash.scenario");
    }

    @Test
    @Order(6)
    void s12ChatMode() throws Exception {
        run("s12-chat-mode.scenario");
    }

    @Test
    @Order(7)
    void s15FullHappyPath() throws Exception {
        run("s15-happy-path.scenario");
    }

    private static Path clientsDir() {
        // Module-relative default (failsafe cwd = infochat-provider/); overridable
        // for a non-standard layout — same convention as LiveSimpleXRoundTripIT.
        String configured = System.getProperty(
                "infochat.live.simplex.clients-dir", "../prod/runtime/simplex-clients");
        Path dir = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(dir), "clients dir not found: " + dir
                + " (set -Dinfochat.live.simplex.clients-dir)");
        return dir;
    }

    private static void run(String resourceName) throws Exception {
        Scenario scenario = LiveScenarioParseTest.load(resourceName);
        ScenarioRunner.RunReport report = runner.run(scenario);
        assertFalse(report.steps().isEmpty(), "scenario produced no steps");
        for (ScenarioRunner.StepResult result : report.steps()) {
            // Real-transport latency evidence is part of the live run's point.
            System.out.printf("%s step %d: matched in %d ms%n",
                    resourceName, result.stepNumber(), result.latency().toMillis());
            assertTrue(result.latency().toMillis() > 0,
                    "live round-trip latency must be non-zero");
        }
    }
}
