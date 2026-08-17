package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.messaging.InterruptibleDispatcher;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.DispatchAwaits;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-867 review round 1: a {@code /retry} replay of a {@code /summary --short}
 * anchor must re-run categorization with the SAME followed-level section keys
 * the anchored summary used (the replay re-derives sections from the frozen
 * clusters, so the key map is the only divergence point). On a top-followed
 * EXPLICIT scope the original renders ONE aggregated tech section; the replay
 * must not re-key per leaf.
 *
 * <p>Test isolation: every fixture carries the {@code m1-867r-} prefix;
 * {@link #cleanup()} deletes matching rows before each {@code @Test}.</p>
 */
@QuarkusTest
@TestProfile(SummaryRetryTreeKeyingIT.MvpProfile.class)
class SummaryRetryTreeKeyingIT {

    private static final String PREFIX = "m1-867r-";
    private static final String USER_CONTACT = PREFIX + "user";
    /**
     * Every fixture instant derives from this pinned "now" and the injected
     * Clock is fixed to it (M1-740): posts land in the migration-provisioned
     * May 2026 partition and the retrieval window is deterministic, instead
     * of breaking on each unprovisioned month boundary.
     */
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");

    @Inject InMemoryAdapter adapter;

    @Inject @SeedDataSource DataSource dataSource;

    @Inject TestLlmProvider mockLlm;

    @Inject InterruptibleDispatcher interruptibleDispatcher;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        mockLlm.reset();
        // The /summary retrieval window reads the injected Clock — pin it
        // into the same time family as the fixtures.
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM summary_anchor WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM scope_tag WHERE scope_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_preferences WHERE scope_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn, "DELETE FROM audit_log WHERE actor_user_id IN "
                        + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
                exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void retryShortReplayKeepsFollowedLevelSections() throws Exception {
        insertUser(USER_CONTACT);
        UUID userId = userIdOf(USER_CONTACT);
        UUID sourceId = insertSource(PREFIX + "src", "RetryTreeNews");
        insertSubscription("dm", userId, sourceId);
        insertExplicitScopeFollowingTech("dm", userId);
        // 3 ai + 3 cybersecurity clusters: per-leaf keying would render TWO
        // qualifying sections (AI, CYBERSECURITY); the followed-level map
        // rolls both up to ONE aggregated tech section.
        for (int i = 1; i <= 3; i++) {
            insertPost(PREFIX + "ai" + i, sourceId, "RETRY TREE AI " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(30 + i)), new String[] { "ai" });
            insertPost(PREFIX + "cyb" + i, sourceId, "RETRY TREE CYB " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(40 + i)), new String[] { "cybersecurity" });
        }
        mockLlm.setResponseText("Retry tree roll-up prose.");

        adapter.deliverDm(USER_CONTACT, "/summary --short");
        // The /summary turn runs on an M1-634 worker — it must fully finish
        // (anchor written) BEFORE the reset below.
        awaitDispatchIdle();

        String originalBody = adapter.finalizedBodies().get(0);
        assertTrue(originalBody.contains("TECH NEWS"),
                "the anchored /summary --short renders ONE aggregated tech section. Got: "
                        + originalBody);
        // Drop the /summary placeholder + finalize bookkeeping so the
        // post-retry sentMessages() snapshot holds only the /retry reply.
        // The anchor row itself lives in the DB and survives the reset.
        adapter.reset();

        adapter.deliverDm(USER_CONTACT, "/retry");
        awaitDispatchIdle();

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(), "/retry must produce exactly one reply");
        String retryBody = sent.get(0).text();
        assertTrue(retryBody.contains("TECH NEWS"),
                "the replay must reproduce the anchored followed-level sectioning: "
                        + "ONE aggregated tech section. Got: " + retryBody);
        assertFalse(retryBody.contains("AI NEWS"),
                "the replay must not re-key per leaf (no ai section). Got: " + retryBody);
        assertFalse(retryBody.contains("CYBERSECURITY NEWS"),
                "the replay must not re-key per leaf (no cybersecurity section). Got: "
                        + retryBody);
    }

    /**
     * Round-3 fix: a /retry against an anchor written by a POSITIONAL
     * /summary &lt;tag&gt; replays with identity keying — the tag echo in
     * the anchor's command_name is the mode bit. On a tech-following
     * EXPLICIT scope, {@code /summary football} renders FOOTBALL NEWS;
     * the replay must not fold the frozen football clusters into the
     * Other bucket through the followed-level map.
     */
    @Test
    void retryOfPositionalSummaryKeepsItsOwnSection() throws Exception {
        insertUser(USER_CONTACT);
        UUID userId = userIdOf(USER_CONTACT);
        UUID sourceId = insertSource(PREFIX + "src", "RetryTreeNews");
        insertSubscription("dm", userId, sourceId);
        insertExplicitScopeFollowingTech("dm", userId);
        for (int i = 1; i <= 3; i++) {
            insertPost(PREFIX + "foot" + i, sourceId, "RETRY POS FOOT " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(30 + i)), new String[] { "football" });
        }
        mockLlm.setResponseText("Retry positional prose.");

        adapter.deliverDm(USER_CONTACT, "/summary football");
        awaitDispatchIdle();

        String originalBody = adapter.finalizedBodies().get(0);
        assertTrue(originalBody.contains("FOOTBALL NEWS"),
                "the anchored positional /summary renders its own section. Got: "
                        + originalBody);
        // The anchor row lives in the DB and survives the reset — see the
        // followed-level test above.
        adapter.reset();

        adapter.deliverDm(USER_CONTACT, "/retry");
        awaitDispatchIdle();

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(), "/retry must produce exactly one reply");
        String retryBody = sent.get(0).text();
        assertTrue(retryBody.contains("FOOTBALL NEWS"),
                "the replay of a positional anchor must keep identity keying: "
                        + "the football section renders. Got: " + retryBody);
        assertFalse(retryBody.contains("OTHER NEWS"),
                "the replay must not fold the requested tag into the Other bucket "
                        + "through the followed-level map. Got: " + retryBody);
    }

    // ----- helpers ------------------------------------------------------

    /** Await M1-634 worker-pool quiescence so negative asserts are race-free. */
    private void awaitDispatchIdle() {
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent");
    }

    private void insertUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES ('inmemory', ?, FALSE, 'vouched') "
                             + "ON CONFLICT (adapter, contact_id) "
                             + "DO UPDATE SET is_banned = FALSE, probation_until = NULL")) {
            ps.setString(1, contactId);
            ps.executeUpdate();
        }
    }

    private UUID userIdOf(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = 'inmemory' AND contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID insertSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertSubscription(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
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

    private void insertExplicitScopeFollowingTech(String scopeKind, UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                    + "VALUES ('" + scopeKind + "', '" + scopeId + "', 'EXPLICIT')");
            exec(conn, "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                    + "SELECT '" + scopeKind + "', '" + scopeId + "', id FROM tag WHERE name = 'tech'");
        }
    }

    private void insertPost(String uid, UUID sourceId, String title, Instant readyAt,
                            String[] tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                             + "fetched_at, ready_at, status, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?)")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setString(5, "https://example.com/" + uid);
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.setTimestamp(7, Timestamp.from(readyAt));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setArray(9, conn.createArrayOf("TEXT", tags));
            ps.setString(10, uid);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.summary.cluster-cap", "200",
                    "infochat.profile.label", "laptop");
        }
    }
}
