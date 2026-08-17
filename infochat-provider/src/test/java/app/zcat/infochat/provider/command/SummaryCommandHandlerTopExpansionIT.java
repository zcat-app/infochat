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
 * M1-867 end-of-path /summary render pins on a top-followed EXPLICIT scope:
 * the followed-node section-key map reaches the user only through the
 * delivered section headers, so these ITs drive {@link SummaryCommandHandler}
 * through the {@link InMemoryAdapter} and assert on the delivered bytes.
 *
 * <p>Test isolation: every fixture carries the {@code m1-867s-} prefix;
 * {@link #cleanup()} deletes matching rows before each {@code @Test}.</p>
 */
@QuarkusTest
@TestProfile(SummaryCommandHandlerTopExpansionIT.MvpProfile.class)
class SummaryCommandHandlerTopExpansionIT {

    private static final String PREFIX = "m1-867s-";
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

    /**
     * A positional {@code /summary <tag>} names its own level: the
     * followed-node key map must NOT apply to it. The scope follows the top
     * 'tech' (EXPLICIT) but asks for the unfollowed 'football' leaf — the
     * pre-change render (a FOOTBALL section steering to
     * {@code /summary football --full}), never the Other bucket.
     */
    @Test
    void unfollowedPositionalLeafKeepsItsOwnSection() throws Exception {
        String user = PREFIX + "pos-user";
        insertUser(user);
        UUID userId = userIdOf(user);
        UUID sourceId = insertSource(PREFIX + "pos-src", "PosTreeNews");
        insertSubscription("dm", userId, sourceId);
        insertExplicitScopeFollowingTech("dm", userId);
        for (int i = 1; i <= 3; i++) {
            insertPost(PREFIX + "foot" + i, sourceId, "POS TREE FOOT " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(30 + i)), new String[] { "football" });
        }
        mockLlm.setResponseText("Positional tree prose.");

        adapter.deliverDm(user, "/summary football");
        awaitDispatchIdle();

        // Exactly one qualifying section (3 football clusters clear
        // category-min-clusters): the placeholder is finalized with it.
        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(), "one category section → one placeholder send");
        List<String> finalized = adapter.finalizedBodies();
        assertEquals(1, finalized.size(), "the placeholder is finalized with the section");
        String body = finalized.get(0);
        assertTrue(body.contains("FOOTBALL NEWS"),
                "a positional request names its own level — the football section renders "
                        + "under its own header. Got: " + body);
        assertFalse(body.contains("OTHER NEWS"),
                "the requested clusters must not fold into the Other bucket through the "
                        + "followed-node map. Got: " + body);
    }

    /**
     * Bare {@code /summary} on a top-followed EXPLICIT scope renders ONE
     * aggregated section keyed at the followed top: the delivered section
     * header token is the top's name, never the leaf names. Fails if the
     * section-key wiring in {@code SummaryCommandHandler} is dropped
     * (identity keying would render per-leaf AI / CYBERSECURITY sections).
     */
    @Test
    void bareSummaryOnTopFollowRendersOneAggregatedSectionWithTopSteerToken() throws Exception {
        String user = PREFIX + "top-user";
        insertUser(user);
        UUID userId = userIdOf(user);
        UUID sourceId = insertSource(PREFIX + "top-src", "TopTreeNews");
        insertSubscription("dm", userId, sourceId);
        insertExplicitScopeFollowingTech("dm", userId);
        // 3 ai + 3 cybersecurity clusters: identity keying would render TWO
        // qualifying leaf sections; the followed-level map renders ONE tech
        // section holding all six.
        for (int i = 1; i <= 3; i++) {
            insertPost(PREFIX + "ai" + i, sourceId, "TOP TREE AI " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(30 + i)), new String[] { "ai" });
            insertPost(PREFIX + "cyb" + i, sourceId, "TOP TREE CYB " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(40 + i)), new String[] { "cybersecurity" });
        }
        mockLlm.setResponseText("Top-follow tree prose.");

        adapter.deliverDm(user, "/summary");
        awaitDispatchIdle();

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "ONE aggregated tech section → one placeholder send, no follow-on sections");
        List<String> finalized = adapter.finalizedBodies();
        assertEquals(1, finalized.size(), "the placeholder is finalized with the one section");
        String body = finalized.get(0);
        assertTrue(body.contains("TECH NEWS"),
                "the single aggregated section's header token is the followed TOP's name. "
                        + "Got: " + body);
        assertFalse(body.contains("AI NEWS"),
                "no per-leaf ai section on a top-followed scope. Got: " + body);
        assertFalse(body.contains("CYBERSECURITY NEWS"),
                "no per-leaf cybersecurity section on a top-followed scope. Got: " + body);
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
