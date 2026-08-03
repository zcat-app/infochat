package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code /source-enable} fresh-ladder reset (M1-754): both
 * handler UPDATE legs — reactivate (parked, not deleted; no confirm)
 * and revive (soft-deleted; confirm-gated) — clear the park reason AND
 * the whole re-probe state in the same statement that sets
 * {@code status='active'}, so a re-enabled source starts with a full
 * automatic-recovery budget.
 *
 * <p>Also pins the V75 grant surface under the REAL
 * {@code infochat_provider} role via {@code SET ROLE} (the
 * DbGrantsRevocationIT idiom): the new park/re-probe columns are
 * UPDATE-granted (without which the handler dies with 42501 at
 * runtime, invisibly to owner-role tests), while
 * {@code source.identifier} stays revoked — the anti-repoint property
 * V31 exists for.
 */
@QuarkusTest
@TestProfile(SourceEnableParkResetIT.ParkResetProbeProfile.class)
class SourceEnableParkResetIT {

    private static final String PREFIX = "m1-754-reset-";
    private static final String ADAPTER = "inmemory";

    /** SQLState insufficient_privilege — raised by the ACL check itself. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Inject SourceEnableCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject InboundContext inboundContext;
    @Inject UrlProbe urlProbe;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        ((ParkResetUrlProbe) urlProbe)
            .setNext(ProbeResult.success(200, Optional.of("application/rss+xml")));
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the per-test DELETE on admin rows.
            exec(conn,
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                    + "VALUES (?, ?, TRUE, 'vouched') "
                    + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                    + "  SET is_admin = TRUE, is_banned = FALSE",
                ADAPTER, "guardian-m1-754-reset-permanent");
            // audit_log.actor_user_id FKs the users rows deleted below, so the
            // command's own audit rows must go first or the users DELETE trips
            // the constraint. audit_log is append-only by trigger, so the
            // triggers come off for the delete and back on in a finally — the
            // SourceEnableCommandHandlerTest cleanup pattern.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                    "DELETE FROM audit_log WHERE target_kind = 'source' AND target_id IN ("
                        + "  SELECT id::TEXT FROM source WHERE identifier LIKE ?)",
                    "https://example.com/" + PREFIX + "%");
                exec(conn,
                    "DELETE FROM audit_log WHERE actor_user_id IN ("
                        + "  SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            exec(conn,
                "DELETE FROM source_subscription WHERE source_id IN ("
                    + "  SELECT id FROM source WHERE identifier LIKE ?)",
                "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?",
                "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void reactivateLegClearsParkAndReprobeState() throws Exception {
        String actor = PREFIX + "reactivate-actor";
        seedUser(actor);
        UUID sourceId = seedParkedSource("reactivate", false);

        OutboundMessage reply = handler.handle(
            new ScopeRef.Dm(actor), "/source-enable " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "reactivate-name"),
            "/source-enable success must name the source — got: " + reply.text());
        assertParkStateFullyReset(sourceId);
    }

    @Test
    void reviveLegClearsParkAndReprobeState() throws Exception {
        String actor = PREFIX + "revive-actor";
        seedUser(actor);
        UUID sourceId = seedParkedSource("revive", true);

        // Soft-deleted revival is confirm-gated: prompt then confirm.
        handler.handle(new ScopeRef.Dm(actor), "/source-enable " + sourceId);
        OutboundMessage reply = handler.handle(
            new ScopeRef.Dm(actor), "/source-enable confirm");

        assertTrue(reply.text().contains(PREFIX + "revive-name"),
            "confirmed revival must name the source — got: " + reply.text());
        assertNull(readText(sourceId, "deleted_at"), "revival must clear deleted_at");
        assertParkStateFullyReset(sourceId);
    }

    @Test
    void providerRoleCanUpdateParkColumnsButNotIdentifier() throws Exception {
        UUID sourceId = seedParkedSource("grant", false);

        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");

                // Allow direction: the V75 column-scoped extension must
                // cover every column the /source-enable reset writes —
                // without it the handler dies with 42501 under the real
                // role, invisibly to owner-role tests.
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE source SET status = 'active', consecutive_failures = 0, "
                        + "  park_reason = NULL, parked_at = NULL, reprobe_count = 0, "
                        + "  next_reprobe_at = NULL, reprobe_restored_at = NULL "
                        + "WHERE id = ?")) {
                    ps.setObject(1, sourceId);
                    assertEquals(1, ps.executeUpdate(),
                        "the full /source-enable reset UPDATE must succeed as "
                            + "infochat_provider (V75 column-scoped grant)");
                }

                // Deny direction: identity columns stay revoked — the
                // anti-repoint property (V31/V75: a Provider SQL-injection
                // foothold cannot repoint a trusted source).
                SQLException denied = assertThrows(SQLException.class, () -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE source SET identifier = 'https://attacker.example/feed' "
                            + "WHERE id = ?")) {
                        ps.setObject(1, sourceId);
                        ps.executeUpdate();
                    }
                }, "UPDATE source.identifier as infochat_provider must be denied");
                assertEquals(INSUFFICIENT_PRIVILEGE, denied.getSQLState(),
                    "the identifier update must fail on the column ACL (42501); was: "
                        + denied.getSQLState() + " — " + denied.getMessage());
            } finally {
                try (Statement st = conn.createStatement()) {
                    st.execute("RESET ROLE");
                }
            }
        }
    }

    // ----- helpers ---------------------------------------------------------

    private void assertParkStateFullyReset(UUID sourceId) throws Exception {
        assertEquals("active", readText(sourceId, "status"),
            "/source-enable must restore status='active'");
        assertEquals(0, readInt(sourceId, "consecutive_failures"),
            "/source-enable must zero consecutive_failures");
        assertNull(readText(sourceId, "park_reason"),
            "/source-enable must clear the park reason");
        assertNull(readText(sourceId, "parked_at"),
            "/source-enable must clear parked_at");
        assertEquals(0, readInt(sourceId, "reprobe_count"),
            "/source-enable must zero the re-probe cap counter — a revived source "
                + "starts a FRESH ladder, not a part-spent or exhausted one");
        assertNull(readText(sourceId, "next_reprobe_at"),
            "/source-enable must clear the probe schedule");
        assertNull(readText(sourceId, "reprobe_restored_at"),
            "/source-enable must clear the sustained-success anchor");
    }

    private void seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                     + "VALUES (?, ?, TRUE, 'vouched')")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.executeUpdate();
        }
    }

    // Parked with a spent, terminal-looking re-probe budget and every
    // park/re-probe column non-NULL, so each reset assertion observes a
    // real transition rather than a vacuous already-NULL pass.
    private UUID seedParkedSource(String slug, boolean softDeleted) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, deleted_at, consecutive_failures, "
                     + "  park_reason, parked_at, reprobe_count, next_reprobe_at, "
                     + "  reprobe_restored_at) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'failed', ?, 3, "
                     + "  'unknown-rate', now(), 7, now(), now()) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setObject(3, softDeleted ? OffsetDateTime.now() : null);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private String readText(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + "::TEXT FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int readInt(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Deterministic probe stub, declared HERE rather than reusing
     * {@code SourceEnableCommandHandlerTest}'s equivalent. A
     * {@code @TestProfile} value is resolved by the JUnit extension in the
     * application classloader, so naming another {@code @QuarkusTest}
     * class's nested profile forces that outer test class — and every
     * production type in its field signatures — to load in the app
     * classloader instead of Quarkus's. That poisons later boots with
     * LinkageErrors ("a different class with the same name was previously
     * loaded by 'app'"). Self-contained profiles are what 41 of the 42
     * @TestProfile sites in this module do.
     */
    @Alternative
    @ApplicationScoped
    public static class ParkResetUrlProbe extends UrlProbe {

        private final AtomicReference<ProbeResult> next =
            new AtomicReference<>(ProbeResult.success(200, Optional.empty()));

        @Override
        public ProbeResult probe(URI url) {
            return next.get();
        }

        public void setNext(ProbeResult result) {
            next.set(result);
        }
    }

    public static class ParkResetProbeProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ParkResetUrlProbe.class);
        }
    }
}
