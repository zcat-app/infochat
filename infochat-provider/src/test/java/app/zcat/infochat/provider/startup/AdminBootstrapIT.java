package app.zcat.infochat.provider.startup;

import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for {@link AdminBootstrap} per {@code docs/spec/deployment.md}
 * §Operator inputs item 2 + §Bootstrap behavior on startup.
 *
 * <p>The boot path itself is exercised by the container start: the
 * base {@code %test} profile configures
 * {@code infochat.adapters=inmemory} with
 * {@code infochat.adapters.inmemory.admin=test-bootstrap-contact},
 * so the {@code @Startup} bean has already seeded that row by the
 * time any test method runs. The remaining shapes (multi-adapter,
 * rotation, skip, idempotency, promote) drive the parameterized
 * {@link AdminBootstrap#seed(String)} directly, mirroring how
 * {@code AdapterRegistryTest} drives
 * {@code AdapterRegistry.start(String)}.</p>
 *
 * <p><b>Order independence.</b> The DevServices database is shared
 * across profile boots and test methods, so every assertion below is
 * targeted by {@code (adapter, contact_id)} — never a global row
 * count — and re-seeding is idempotent, so no test depends on
 * running first.</p>
 */
@QuarkusTest
@TestProfile(AdminBootstrapIT.Profile.class)
class AdminBootstrapIT {

    /** A bare SimpleX queue address (URL-safe base64, 44 chars ≥ the 43 floor). */
    static final String SIMPLEX_BARE_QUEUE_ID = "SimplexBootstrapAdminQueueAddr0000000000000A";

    /**
     * The same bare id wrapped in a full SimpleX contact link — the
     * operator-facing form {@link AdminBootstrap} must canonicalize to the
     * bare queue id before seeding (M1-465). The SMP URI is URL-encoded so
     * the test does not hand-roll the percent-encoding the adapter's
     * extractor expects.
     */
    static final String SIMPLEX_ADMIN_FULL_LINK =
            "https://simplex.chat/contact#/?v=2-7&smp="
                    + URLEncoder.encode("smp://hQ@smp.example.com/" + SIMPLEX_BARE_QUEUE_ID
                            + "#/?v=1&dh=AB", StandardCharsets.UTF_8);

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    AdminBootstrap adminBootstrap;

    @Inject
    AdapterRegistry registry;

    @Test
    void bootSeedsAdminRowForConfiguredAdapterAndWritesBootstrapAudit() throws Exception {
        // No seed(...) call here: the row must come from the @Startup
        // boot execution itself — a bean that compiles but never runs
        // at startup is exactly the C2 failure this asserts against.
        assertTrue(isAdminAt("inmemory", "test-bootstrap-contact"),
                "the @Startup boot must seed the configured inmemory bootstrap admin");
        assertTrue(bootstrapAuditCountFor("test-bootstrap-contact") >= 1,
                "the boot seeding must write a BOOTSTRAP_ADMIN audit row");
    }

    @Test
    void twoConfiguredAdaptersEachGetTheirOwnAdminRowAndAuditRow() throws Exception {
        adminBootstrap.seed("fake-x,fake-y");

        assertEquals(1, usersCountAt("fake-x", "fake-x-bootstrap-admin"),
                "one admin row per (adapter, contact_id): fake-x");
        assertEquals(1, usersCountAt("fake-y", "fake-y-bootstrap-admin"),
                "one admin row per (adapter, contact_id): fake-y");
        assertTrue(isAdminAt("fake-x", "fake-x-bootstrap-admin"));
        assertTrue(isAdminAt("fake-y", "fake-y-bootstrap-admin"));
        assertEquals(1, bootstrapAuditCountFor("fake-x-bootstrap-admin"),
                "the fake-x bootstrap row must be audit-logged");
        assertEquals(1, bootstrapAuditCountFor("fake-y-bootstrap-admin"),
                "the fake-y bootstrap row must be audit-logged");
    }

    @Test
    void rotatedContactIdAddsNewAdminRowAndLeavesPriorAdminIntact() throws Exception {
        insertUser("fake-x", "rotated-away-old-admin", true, "vouched");

        adminBootstrap.seed("fake-x");

        assertTrue(isAdminAt("fake-x", "rotated-away-old-admin"),
                "the prior admin row must keep is_admin = true after rotation");
        assertTrue(isAdminAt("fake-x", "fake-x-bootstrap-admin"),
                "the rotated-to contact id must get its own admin row");
    }

    @Test
    void seededRowCarriesBootstrapAdminColumnShape() throws Exception {
        adminBootstrap.seed("fake-x");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin, is_banned, probation_until, registration_state"
                             + " FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, "fake-x");
            ps.setString(2, "fake-x-bootstrap-admin");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the seeded row must exist");
                assertTrue(rs.getBoolean("is_admin"), "is_admin must be true");
                assertFalse(rs.getBoolean("is_banned"), "is_banned must be false");
                assertNull(rs.getTimestamp("probation_until"),
                        "probation_until must be NULL (bootstrap admins skip the slow-start tier)");
                assertEquals("vouched", rs.getString("registration_state"),
                        "registration_state must be 'vouched'");
            }
        }
    }

    @Test
    void bootstrapAuditRowRecordsCauseBootstrapInDetailsJson() throws Exception {
        adminBootstrap.seed("fake-x");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT details_json ->> 'cause' AS cause FROM audit_log"
                             + " WHERE action = 'BOOTSTRAP_ADMIN' AND target_contact_id = ?")) {
            ps.setString(1, "fake-x-bootstrap-admin");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the bootstrap audit row must exist");
                assertEquals("bootstrap", rs.getString("cause"),
                        "the audit row must record details_json.cause = 'bootstrap'");
            }
        }
    }

    @Test
    void reseedWithUnchangedConfigurationCreatesNoAdditionalRows() throws Exception {
        adminBootstrap.seed("fake-y");
        int usersAfterFirst = usersCountAt("fake-y", "fake-y-bootstrap-admin");
        int auditAfterFirst = bootstrapAuditCountFor("fake-y-bootstrap-admin");
        assertEquals(1, usersAfterFirst, "first run must have created exactly one row");
        assertEquals(1, auditAfterFirst, "first run must have written exactly one audit row");

        adminBootstrap.seed("fake-y");

        assertEquals(usersAfterFirst, usersCountAt("fake-y", "fake-y-bootstrap-admin"),
                "second run must create no additional users rows");
        assertEquals(auditAfterFirst, bootstrapAuditCountFor("fake-y-bootstrap-admin"),
                "second run must write no additional BOOTSTRAP_ADMIN audit rows");
    }

    @Test
    void adapterWithoutConfiguredAdminIsSkippedWhileUnionGateStillEnforced() throws Exception {
        // fake-a (StartupGatesTest.FakeAdapterA) has no admin property
        // configured anywhere; fake-x carries one in this profile.
        adminBootstrap.seed("fake-x,fake-a");

        assertTrue(isAdminAt("fake-x", "fake-x-bootstrap-admin"),
                "the configured adapter must get its admin row");
        assertEquals(0, adminCountForAdapter("fake-a"),
                "an adapter without a configured admin must be skipped — no row created");

        // The AdapterRegistry union gate is untouched by the bean:
        // one-of-two configured passes (union non-empty via fake-x)...
        registry.start("fake-x,fake-a");
        // ...and a zero-admin activation set still fails gate 7.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.start("fake-a"));
        assertTrue(e.getMessage().contains("Bootstrap admin"),
                "gate 7 must reject an empty admin union, got: " + e.getMessage());
    }

    @Test
    void adminGivenAsFullContactLinkSeedsBareQueueIdRow() throws Exception {
        // The simplex bean canonicalizes the full contact link to its bare
        // queue id before seeding (M1-465), so the seeded row is the id inbound
        // messages byte-match — not the raw link.
        adminBootstrap.seed("simplex");

        assertTrue(isAdminAt("simplex", SIMPLEX_BARE_QUEUE_ID),
                "the seeded admin row must carry the bare queue id, not the raw link");
        assertFalse(isAdminAt("simplex", SIMPLEX_ADMIN_FULL_LINK),
                "the raw contact link must NOT be seeded as a contact id");
    }

    @Test
    void existingNonAdminUserIsPromotedToAdminAndAudited() throws Exception {
        // "ensures ... that the configured contact exists with
        // is_admin = true (creating the user if needed)": the
        // exists-but-not-admin leg promotes in place.
        insertUser("fake-promote", "fake-promote-bootstrap-admin", false, "invited");

        adminBootstrap.seed("fake-promote");

        assertTrue(isAdminAt("fake-promote", "fake-promote-bootstrap-admin"),
                "an existing non-admin row for the configured contact must be promoted");
        assertEquals("invited", registrationStateAt("fake-promote", "fake-promote-bootstrap-admin"),
                "promotion sets is_admin only — pre-existing registration_state stays");
        assertEquals(1, bootstrapAuditCountFor("fake-promote-bootstrap-admin"),
                "the promotion must be audit-logged as BOOTSTRAP_ADMIN");
    }

    private boolean isAdminAt(String adapter, String contactId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private String registrationStateAt(String adapter, String contactId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT registration_state FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a users row at (" + adapter + ", " + contactId + ")");
                return rs.getString(1);
            }
        }
    }

    private int usersCountAt(String adapter, String contactId) throws SQLException {
        return scalarCount(
                "SELECT count(*) FROM users WHERE adapter = ? AND contact_id = ?",
                adapter, contactId);
    }

    private int adminCountForAdapter(String adapter) throws SQLException {
        return scalarCount(
                "SELECT count(*) FROM users WHERE adapter = ? AND is_admin = TRUE",
                adapter, null);
    }

    private int bootstrapAuditCountFor(String contactId) throws SQLException {
        return scalarCount(
                "SELECT count(*) FROM audit_log"
                        + " WHERE action = 'BOOTSTRAP_ADMIN' AND target_contact_id = ?",
                contactId, null);
    }

    private int scalarCount(String sql, String param1, @Nullable String param2) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param1);
            if (param2 != null) {
                ps.setString(2, param2);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertUser(String adapter, String contactId, boolean isAdmin,
                            String registrationState) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned,"
                             + " registration_state) VALUES (?, ?, ?, FALSE, ?)"
                             + " ON CONFLICT (adapter, contact_id) DO NOTHING")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setString(4, registrationState);
            ps.executeUpdate();
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // The boot itself inherits infochat.adapters=inmemory plus
            // its admin contact from the base %test profile; the
            // fake-* admins below exist only for the parameterized
            // seed(...) calls — none of these adapters is activated.
            return Map.of(
                    "infochat.adapters.fake-x.admin", "fake-x-bootstrap-admin",
                    "infochat.adapters.fake-y.admin", "fake-y-bootstrap-admin",
                    "infochat.adapters.fake-promote.admin", "fake-promote-bootstrap-admin",
                    // Full SimpleX contact link: the simplex bean canonicalizes it
                    // to the bare queue id before seeding (M1-465). simplex is not
                    // activated at boot (infochat.adapters=inmemory), so this is
                    // consumed only by the explicit seed("simplex") call.
                    "infochat.adapters.simplex.admin", SIMPLEX_ADMIN_FULL_LINK);
        }
    }
}
