package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group-scope {@code /retry} end-to-end IT (M1-478). Before this ticket a
 * group member who ran {@code /summary} then {@code /retry} in the same
 * group always received {@code error.retry.no_anchor}: the {@code /retry}
 * read hardcoded {@code scope_kind='dm'} (and rejected non-DM scope
 * outright) while {@code /summary} wrote the anchor with
 * {@code scope_kind='group'}, so the read key could never match the write
 * key.
 *
 * <ol>
 *   <li>{@link #groupRetryReReadsTheGroupAnchor} — acceptance item 1+2: a
 *       group member's {@code /retry} re-renders their own group summary
 *       (cites the group's post) instead of the NO_ANCHOR reply, proving
 *       the read key now matches the {@code /summary} write key.</li>
 *   <li>{@link #dmRetryStillReReadsTheDmAnchor} — acceptance item 2: the
 *       existing DM-scope {@code /retry} path is unchanged and still
 *       re-renders the caller's DM summary.</li>
 * </ol>
 *
 * <p>Test isolation: every fixture carries the {@code m1-478-} prefix;
 * {@link #cleanup()} deletes matching rows before each {@code @Test}. The
 * cleanup and seed helpers mirror {@code SummaryGroupScopeIT} (M1-288) —
 * the same DB shape produces the anchor this ticket's read must match.</p>
 */
@QuarkusTest
@TestProfile(RetryCommandHandlerGroupScopeIT.MvpProfile.class)
class RetryCommandHandlerGroupScopeIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-478-";

    @Inject InMemoryAdapter adapter;

    @Inject @SeedDataSource DataSource dataSource;

    @Inject TestLlmProvider mockLlm;

    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        mockLlm.reset();
        try (Connection conn = dataSource.getConnection()) {
            // FK-safe order (mirrors SummaryGroupScopeIT): anchor +
            // membership first, then subscriptions + posts, scope prefs,
            // groups, source, audit_log (the summary dispatch writes a row
            // keyed on actor_user_id → users), and users last.
            exec(conn, "DELETE FROM summary_anchor WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM group_membership WHERE group_id IN "
                    + "(SELECT id FROM groups WHERE upstream_group_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM scope_preferences WHERE scope_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%') "
                    + "OR scope_id IN (SELECT id FROM groups WHERE upstream_group_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE '" + PREFIX + "%'");
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
    void groupRetryReReadsTheGroupAnchor() throws Exception {
        String member = PREFIX + "g-member";
        String group = PREFIX + "g-retry";
        insertUser(member);
        UUID groupId = insertApprovedGroup(group);
        UUID sourceId = insertSource(PREFIX + "g-src", "GroupRetryNews");
        insertSubscription("group", groupId, sourceId);
        insertPost(PREFIX + "g-p1", sourceId, "GROUP RETRY HEADLINE",
                Instant.now().minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });
        mockLlm.setResponseText("Group retry prose.");

        // First /summary writes the group anchor (scope_kind='group').
        adapter.deliverGroupMention(group, member, "/summary");
        // Drop the /summary placeholder + finalize bookkeeping so the
        // post-retry sentMessages() snapshot holds only the /retry reply.
        // The anchor row itself lives in the DB and survives the reset.
        adapter.reset();

        adapter.deliverGroupMention(group, member, "/retry");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "group /retry must produce exactly one reply");
        String retryBody = sent.get(0).text();
        assertNotEquals(noAnchorText(), retryBody,
                "group /retry must re-render the member's group summary, "
                        + "not the NO_ANCHOR reply (the read key now matches "
                        + "what /summary wrote). Got: " + retryBody);
        assertTrue(retryBody.contains("GROUP RETRY HEADLINE"),
                "the retried group summary must cite the group's post. Got: " + retryBody);
    }

    @Test
    void dmRetryStillReReadsTheDmAnchor() throws Exception {
        String user = PREFIX + "dm-user";
        insertUser(user);
        UUID userId = userIdOf(user);
        UUID sourceId = insertSource(PREFIX + "dm-src", "DmRetryNews");
        insertSubscription("dm", userId, sourceId);
        insertPost(PREFIX + "dm-p1", sourceId, "DM RETRY HEADLINE",
                Instant.now().minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });
        mockLlm.setResponseText("DM retry prose.");

        adapter.deliverDm(user, "/summary");
        adapter.reset();

        adapter.deliverDm(user, "/retry");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "DM /retry must produce exactly one reply");
        String retryBody = sent.get(0).text();
        assertNotEquals(noAnchorText(), retryBody,
                "DM /retry must stay unchanged and re-render the caller's "
                        + "DM summary. Got: " + retryBody);
        assertTrue(retryBody.contains("DM RETRY HEADLINE"),
                "the retried DM summary must cite the DM-subscribed post. Got: " + retryBody);
    }

    // ----- helpers ------------------------------------------------------

    private String noAnchorText() {
        return bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR, "en");
    }

    private void insertUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, FALSE, 'vouched') "
                             + "ON CONFLICT (adapter, contact_id) "
                             + "DO UPDATE SET is_banned = FALSE, probation_until = NULL")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.executeUpdate();
        }
    }

    private UUID userIdOf(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID insertApprovedGroup(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, approval_status, removed_at) "
                             + "VALUES (?, ?, 'approved', NULL) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
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

    private void insertPost(String uid, UUID sourceId, String title, Instant publishedAt,
                            String status, String[] tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                             + "status, tags, upstream_identifier) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setString(5, "https://example.com/" + uid);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setString(7, status);
            ps.setArray(8, conn.createArrayOf("TEXT", tags));
            ps.setString(9, uid);
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
