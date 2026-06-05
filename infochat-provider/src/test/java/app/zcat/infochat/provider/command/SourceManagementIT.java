package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
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

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for the two confirm-gated source-management
 * admin commands ({@code /remove-source}, {@code /source-enable} against a
 * soft-deleted row). Mirrors {@link ConfirmFlowIT} and
 * {@link AddSourceIT}: each scenario drives the dispatch path through
 * {@link InMemoryAdapter#deliverDm} and asserts on
 * {@link InMemoryAdapter#sentMessages()} plus DB state.
 *
 * <p>The IT installs an {@link Alternative} {@link UrlProbe}
 * ({@link AlwaysSuccessProbe}) so the {@code /source-enable} revive
 * path's probe step is deterministic without outbound network access.
 * One inner class — well below the
 * {@code [[feedback_avoid_test_inner_classes]]} >3 threshold.</p>
 */
@QuarkusTest
@TestProfile(SourceManagementIT.MvpProfile.class)
class SourceManagementIT {

    private static final String PREFIX = "m1-053-it-";
    private static final String ADAPTER = "inmemory";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the per-test DELETE on admin
            // rows. This @TestProfile-scoped IT boots its own Quarkus
            // instance (MvpProfile) and therefore gets its own
            // DevServices Postgres container — no prior-test guardians
            // carry over. Mirrors the ConfirmFlowIT precedent.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-053-it-permanent");
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
    void removeSourcePromptThenConfirmCascadeDeletesSubscriptions() throws Exception {
        String admin = PREFIX + "rmAdmin";
        UUID adminId = seedUser(admin, true);

        // Two more users so the cascade has three subscription scopes.
        UUID otherAId = seedUser(PREFIX + "rmOtherA", false);
        UUID otherBId = seedUser(PREFIX + "rmOtherB", false);

        UUID sourceId = seedSource("rmSrc", "rss", "active", false);
        seedSubscription("dm", adminId, sourceId);
        seedSubscription("dm", otherAId, sourceId);
        seedSubscription("dm", otherBId, sourceId);

        // First call → prompt outbound.
        adapter.deliverDm(admin, "/remove-source " + sourceId);
        List<OutboundMessage> afterPrompt = adapter.sentMessages();
        assertEquals(1, afterPrompt.size(),
                "first /remove-source must produce exactly one outbound (the prompt)");
        assertTrue(afterPrompt.get(0).text().contains("/remove-source confirm"),
                "prompt outbound must name /remove-source confirm — got: "
                        + afterPrompt.get(0).text());
        assertEquals(3L, countSubscriptions(sourceId),
                "no subscription must be deleted before the confirm arrives");
        assertEquals(1L, countAuditByActionForTarget("REMOVE_SOURCE_INTENT", sourceId),
                "first call writes exactly one REMOVE_SOURCE_INTENT audit row");
        assertEquals(0L, countAuditByActionForTarget("REMOVE_SOURCE", sourceId),
                "first call must NOT write REMOVE_SOURCE (completion) row");
        assertFalse(isSoftDeleted(sourceId),
                "no soft-delete before confirm");

        // Confirm call → success outbound + cascade.
        adapter.deliverDm(admin, "/remove-source confirm");
        List<OutboundMessage> afterConfirm = adapter.sentMessages();
        assertEquals(2, afterConfirm.size(),
                "confirm must produce exactly one MORE outbound (the success reply)");
        assertTrue(afterConfirm.get(1).text().contains("removed"),
                "confirm outbound must surface the success literal — got: "
                        + afterConfirm.get(1).text());

        assertTrue(isSoftDeleted(sourceId),
                "confirm must set source.deleted_at IS NOT NULL");
        assertEquals(0L, countSubscriptions(sourceId),
                "every source_subscription row referencing the removed source must be cascade-deleted");
        assertEquals(1L, countAuditByActionForTarget("REMOVE_SOURCE", sourceId),
                "confirm writes exactly one REMOVE_SOURCE (completion) audit row");
        assertEquals(1L, countAuditByActionForTarget("REMOVE_SOURCE_INTENT", sourceId),
                "the REMOVE_SOURCE_INTENT row from the first call must persist");
    }

    @Test
    void sourceEnablePromptThenConfirmRevivesSoftDeletedRow() throws Exception {
        String admin = PREFIX + "enAdmin";
        seedUser(admin, true);

        // Soft-deleted source with NO subscriptions — the revival path
        // commits to NOT recreating subscriptions.
        UUID sourceId = seedSource("enSrc", "rss", "active", true);

        // First call → prompt outbound naming the revival flow.
        adapter.deliverDm(admin, "/source-enable " + sourceId);
        List<OutboundMessage> afterPrompt = adapter.sentMessages();
        assertEquals(1, afterPrompt.size(),
                "first /source-enable against soft-deleted must produce exactly one outbound");
        assertTrue(afterPrompt.get(0).text().contains("/source-enable confirm"),
                "prompt must instruct /source-enable confirm — got: "
                        + afterPrompt.get(0).text());
        assertTrue(afterPrompt.get(0).text().contains("No subscriptions will be restored"),
                "prompt must include the no-subscriptions-restored notice — got: "
                        + afterPrompt.get(0).text());
        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE_INTENT", sourceId),
                "first call writes exactly one SOURCE_ENABLE_INTENT audit row");
        assertTrue(isSoftDeleted(sourceId),
                "no revival before confirm — row stays soft-deleted");

        // Confirm call → success outbound + revival.
        adapter.deliverDm(admin, "/source-enable confirm");
        List<OutboundMessage> afterConfirm = adapter.sentMessages();
        assertEquals(2, afterConfirm.size(),
                "confirm must produce exactly one MORE outbound (the revival success)");
        assertTrue(afterConfirm.get(1).text().contains("No subscriptions were restored"),
                "confirm outbound must include the no-subscriptions-restored disclosure — got: "
                        + afterConfirm.get(1).text());

        assertFalse(isSoftDeleted(sourceId),
                "confirm must clear source.deleted_at");
        assertEquals("active", readStatus(sourceId),
                "confirm must transition status to 'active'");
        assertEquals(0L, countSubscriptions(sourceId),
                "revival must NOT recreate any source_subscription rows");
        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE", sourceId),
                "confirm writes exactly one SOURCE_ENABLE completion row");
        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE_INTENT", sourceId),
                "the SOURCE_ENABLE_INTENT row from the first call must persist");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state, "
                             + "  probation_until) "
                             + "VALUES (?, ?, ?, 'vouched', ?) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            // Bootstrap-admin pattern: past probation so step 5 does
            // not block admin commands (ConfirmFlowIT precedent).
            if (isAdmin) {
                ps.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(4, OffsetDateTime.now().plusHours(24));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String slug, String kind, String status, boolean softDeleted)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status, deleted_at) "
                             + "VALUES (?, ?, ?, 'news', '{}', ?, ?) RETURNING id")) {
            ps.setString(1, kind);
            ps.setString(2, "https://example.com/" + PREFIX + slug);
            ps.setString(3, PREFIX + slug + "-name");
            ps.setString(4, status);
            if (softDeleted) {
                ps.setObject(5, OffsetDateTime.now());
            } else {
                ps.setObject(5, null);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedSubscription(String scopeKind, UUID scopeId, UUID sourceId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private boolean isSoftDeleted(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT deleted_at FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("deleted_at") != null;
            }
        }
    }

    private String readStatus(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("status");
            }
        }
    }

    private long countSubscriptions(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM source_subscription WHERE source_id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditByActionForTarget(String action, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? "
                             + "AND target_kind = 'source' AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, sourceId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * CDI {@link Alternative} {@link UrlProbe} that always returns
     * SUCCESS — the revival path's probe step is exercised
     * deterministically without outbound network I/O. One inner class
     * per the {@code [[feedback_avoid_test_inner_classes]]} threshold.
     */
    @Alternative
    @ApplicationScoped
    public static class AlwaysSuccessProbe extends UrlProbe {
        @Override
        public ProbeResult probe(URI url) {
            return ProbeResult.success(200, Optional.of("application/rss+xml"));
        }
    }

    public static class MvpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(AlwaysSuccessProbe.class);
        }
    }
}
