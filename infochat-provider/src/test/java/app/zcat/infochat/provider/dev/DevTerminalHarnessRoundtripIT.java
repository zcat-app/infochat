package app.zcat.infochat.provider.dev;

import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M1-414 round-trip IT: drives the dev harness's real file transport end to end.
 *
 * <p>Two directives are written to the harness input file and the poll cycle is
 * invoked directly (the {@code @Scheduled} timer is set to 24h under test so it
 * never races):
 * <ol>
 *   <li>a fresh contact redeems a PENDING invite → the welcome reply proves the
 *       register-via-invite round-trip through the file transport;</li>
 *   <li>the seeded, already-subscribed user runs {@code /summary} → the captured
 *       reply cites the seeded READY post uids, proving the seed is visible to a
 *       content command (acceptance item 3).</li>
 * </ol>
 *
 * <p>Seeding is a TEST concern via the established {@code @SeedDataSource} pattern
 * (the harness itself does no seeding): {@link #applySeedFixture()} executes the
 * M1-413 {@code /fixtures/seed-ready-posts.sql} resource on the owner-role
 * datasource, reusing the fixture as-is.
 */
@QuarkusTest
@TestProfile(DevTerminalHarnessRoundtripIT.HarnessProfile.class)
class DevTerminalHarnessRoundtripIT {

    private static final String INPUT_FILE = "target/m1-414-dev-harness-in.txt";
    private static final String OUTPUT_FILE = "target/m1-414-dev-harness-out.txt";

    private static final String FIXTURE_RESOURCE = "/fixtures/seed-ready-posts.sql";
    private static final String SEED_USER = "m1-413-seed-user";
    private static final UUID SEED_USER_ID =
            UUID.fromString("00000413-0000-4000-8000-000000000001");

    @Inject DevTerminalHarness harness;
    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject CommandPermissions commandPermissions;
    @Inject TestLlmProvider mockLlm;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        mockLlm.reset();
        mockLlm.setResponseText("Seeded summary prose.");
        Files.deleteIfExists(Path.of(INPUT_FILE));
        Files.deleteIfExists(Path.of(OUTPUT_FILE));
        applySeedFixture();
    }

    @Test
    void registerViaInviteThenSeededSummaryRoundTripsThroughTheFileTransport() throws Exception {
        // Unique per run so no cross-run user/audit cleanup is needed; the invite
        // is created_by the seeded user (the column records authorship only).
        String newContact = "m1-414-newuser-" + UUID.randomUUID();
        UUID inviteCode = UUID.randomUUID();
        seedPendingInvite(inviteCode, newContact, SEED_USER_ID);

        Files.writeString(Path.of(INPUT_FILE),
                "dm " + newContact + " " + inviteCode + "\n"
                        + "dm " + SEED_USER + " /summary -w 24h\n",
                StandardCharsets.UTF_8);

        harness.poll();

        String output = Files.readString(Path.of(OUTPUT_FILE), StandardCharsets.UTF_8);

        // The welcome renders the canonical probation command list via
        // MessageFormat (M1-590); build the expected the way the router does.
        assertTrue(output.contains(MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH),
                        commandPermissions.renderProbationCommandList())),
                "directive 1: invite redemption must round-trip the welcome reply "
                        + "through the harness output file. Got:\n" + output);
        assertTrue(output.contains("m1-413-ready-security"),
                "directive 2: /summary must cite the seeded security post uid. Got:\n" + output);
        assertTrue(output.contains("m1-413-ready-ai"),
                "directive 2: /summary must cite the seeded AI post uid. Got:\n" + output);
        assertTrue(output.contains("m1-413-ready-java"),
                "directive 2: /summary must cite the seeded Java post uid. Got:\n" + output);
    }

    // ----- helpers ---------------------------------------------------------

    private void applySeedFixture() {
        String sql = readFixtureSql();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply seed fixture " + FIXTURE_RESOURCE, e);
        }
    }

    private static String readFixtureSql() {
        try (InputStream in = DevTerminalHarnessRoundtripIT.class
                .getResourceAsStream(FIXTURE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Seed fixture resource not found: " + FIXTURE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read seed fixture " + FIXTURE_RESOURCE, e);
        }
    }

    private void seedPendingInvite(UUID code, String expectedContactId, UUID createdBy)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO invite_code (code, invite_type, adapter, "
                             + "expected_contact_id, status, created_by) "
                             + "VALUES (?, 'CONTACT_BOUND', 'inmemory', ?, 'PENDING', ?)")) {
            ps.setObject(1, code);
            ps.setString(2, expectedContactId);
            ps.setObject(3, createdBy);
            ps.executeUpdate();
        }
    }

    public static final class HarnessProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.dev.harness.enabled", "true",
                    "infochat.dev.harness.poll-interval", "24h",
                    "infochat.dev.harness.input-file", INPUT_FILE,
                    "infochat.dev.harness.output-file", OUTPUT_FILE,
                    "infochat.summary.cluster-cap", "200",
                    "infochat.profile.label", "laptop");
        }
    }
}
