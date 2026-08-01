package app.zcat.infochat.provider.command;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group-scope {@code /summary} end-to-end IT (M1-288). Pins the three
 * behaviors the unit tier cannot prove against a real
 * {@code summary_anchor} table:
 *
 * <ol>
 *   <li>{@link #groupSummaryRunsTheFlowAndWritesAnAnchor} — acceptance
 *       item 1: a registered, approved-group member invoking
 *       {@code /summary} gets the summary flow (placeholder + finalized
 *       reply citing the group's posts) and a personal anchor row,
 *       instead of the {@code no_posts_yet} reply every group invocation
 *       produced before this ticket.</li>
 *   <li>{@link #twoMembersOfSameGroupGetDistinctPersonalAnchors} —
 *       acceptance item 2: two different members of the same group get
 *       distinct per-member personal anchors (commands.md ~:779: "one
 *       per (user, group)") — same {@code scope_id} (the group), distinct
 *       {@code user_id}.</li>
 *   <li>{@link #dmAndGroupAnchorsForSameUserNeverAlias} — acceptance
 *       item 3: a user's DM anchor and their group anchor are distinct
 *       rows that never read or overwrite each other (per-(user, scope)
 *       isolation).</li>
 * </ol>
 *
 * <p>Test isolation: every fixture carries the {@code m1-288-} prefix;
 * {@link #cleanup()} deletes matching rows before each {@code @Test}.
 * Groups are seeded directly with {@code approval_status='approved'} so
 * the router's D47 step-3.5 gate passes and the message reaches the
 * handler; the router resolves the {@code groups.id} at step 4.1 and the
 * handler keys the anchor on it.</p>
 */
@QuarkusTest
@TestProfile(SummaryGroupScopeIT.MvpProfile.class)
class SummaryGroupScopeIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-288-";
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
            // FK-safe order: anchor + membership (→ users, groups) first,
            // then subscriptions + posts (→ source), scope prefs, then
            // groups (before users; groups has no activated_by set here but
            // the order is robust), source, then audit_log (the summary
            // dispatch writes a row keyed on actor_user_id → users), and
            // users last.
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
            // audit_log is append-only (Invariant 10): disable the no-delete
            // trigger so test cleanup can clear the rows the summary dispatch
            // wrote on actor_user_id before deleting the users they reference.
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
    void groupSummaryRunsTheFlowAndWritesAnAnchor() throws Exception {
        String member = PREFIX + "m1-member";
        String group = PREFIX + "g-flow";
        UUID memberId = insertUser(member);
        UUID groupId = insertApprovedGroup(group);
        UUID sourceId = insertSource(PREFIX + "flow-src", "FlowNews");
        insertSubscription("group", groupId, sourceId);
        insertPost(PREFIX + "flow-p1", sourceId, "GROUP FLOW HEADLINE",
                PINNED_NOW.minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });
        mockLlm.setResponseText("Group flow prose.");

        // --flat (renamed from --full by M1-700) renders the flat per-cluster
        // blocks whose headline and uid this test asserts on (M1-694 made the
        // categorized form default).
        adapter.deliverGroupMention(group, member, "/summary --flat");

        // /summary runs on an M1-634 worker — drain before the
        // exactly-one bounds and anchor reads.
        awaitDispatchIdle();

        // (1a) the summary flow ran: one placeholder send + one finalized
        // reply (self-delivered via the notifier), NOT the no_posts_yet
        // reply every group invocation produced before M1-288.
        assertEquals(1, adapter.sentMessages().size(),
                "group /summary must produce exactly one placeholder send");
        List<String> finalized = adapter.finalizedBodies();
        assertEquals(1, finalized.size(),
                "group /summary must finalize exactly one summary reply");
        String body = finalized.get(0);
        assertTrue(body.contains("GROUP FLOW HEADLINE"),
                "the group summary must cite the group's subscribed post. Got: " + body);
        assertTrue(body.contains(PREFIX + "flow-p1"),
                "the group summary must cite the post uid. Got: " + body);

        // (1b) a personal anchor row was written, keyed on the caller's
        // user id and the group's scope id.
        List<AnchorKey> anchors = anchorsForGroup(groupId);
        assertEquals(1, anchors.size(),
                "group /summary must write exactly one personal anchor for the group");
        assertEquals(memberId, anchors.get(0).userId(),
                "anchor user_id must be the calling member's users.id");
        assertEquals("group", anchors.get(0).scopeKind(),
                "anchor scope_kind must be 'group'");
        assertEquals(groupId, anchors.get(0).scopeId(),
                "anchor scope_id must be the group's id");
    }

    @Test
    void twoMembersOfSameGroupGetDistinctPersonalAnchors() throws Exception {
        String memberA = PREFIX + "m2-a";
        String memberB = PREFIX + "m2-b";
        String group = PREFIX + "g-twomembers";
        UUID memberAId = insertUser(memberA);
        UUID memberBId = insertUser(memberB);
        UUID groupId = insertApprovedGroup(group);
        UUID sourceId = insertSource(PREFIX + "tm-src", "TwoMemberNews");
        // Group-scope subscription is shared by all members of the group.
        insertSubscription("group", groupId, sourceId);
        insertPost(PREFIX + "tm-p1", sourceId, "TWO MEMBER HEADLINE",
                PINNED_NOW.minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });
        mockLlm.setResponseText("Two-member prose.");

        adapter.deliverGroupMention(group, memberA, "/summary");
        adapter.deliverGroupMention(group, memberB, "/summary");

        // Distinct (user, scope) keys → the two turns may run
        // concurrently on M1-634 workers; drain before the anchor reads.
        awaitDispatchIdle();

        List<AnchorKey> anchors = anchorsForGroup(groupId);
        assertEquals(2, anchors.size(),
                "each member must get their own per-(user, group) personal anchor");
        // Both anchors share the group's scope id but carry distinct user ids.
        assertEquals(groupId, anchors.get(0).scopeId());
        assertEquals(groupId, anchors.get(1).scopeId());
        assertNotEquals(anchors.get(0).userId(), anchors.get(1).userId(),
                "the two members' anchors must carry distinct user_id values");
        assertTrue(
                anchors.stream().anyMatch(a -> a.userId().equals(memberAId))
                        && anchors.stream().anyMatch(a -> a.userId().equals(memberBId)),
                "the two anchors must belong to member A and member B respectively");
    }

    @Test
    void dmAndGroupAnchorsForSameUserNeverAlias() throws Exception {
        String user = PREFIX + "m3-user";
        String group = PREFIX + "g-isolation";
        UUID userId = insertUser(user);
        UUID groupId = insertApprovedGroup(group);

        // DM scope: a DM subscription + post so the DM /summary runs.
        UUID dmSource = insertSource(PREFIX + "iso-dm-src", "IsoDmNews");
        insertSubscription("dm", userId, dmSource);
        insertPost(PREFIX + "iso-dm-p1", dmSource, "DM ISOLATION HEADLINE",
                PINNED_NOW.minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });
        // Group scope: a group subscription + post so the group /summary runs.
        UUID groupSource = insertSource(PREFIX + "iso-grp-src", "IsoGrpNews");
        insertSubscription("group", groupId, groupSource);
        insertPost(PREFIX + "iso-grp-p1", groupSource, "GROUP ISOLATION HEADLINE",
                PINNED_NOW.minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });
        mockLlm.setResponseText("Isolation prose.");

        adapter.deliverDm(user, "/summary");
        adapter.deliverGroupMention(group, user, "/summary");

        // Different scopes for the same user → concurrent M1-634 workers;
        // drain before the anchor reads.
        awaitDispatchIdle();

        // The same user now holds two distinct anchors: one DM-keyed
        // (scope_kind='dm', scope_id = user id) and one group-keyed
        // (scope_kind='group', scope_id = group id). Neither overwrote the
        // other.
        List<AnchorKey> all = anchorsForUser(userId);
        assertEquals(2, all.size(),
                "the user must hold both a DM anchor and a group anchor — neither overwrote the other");
        AnchorKey dm = all.stream().filter(a -> a.scopeKind().equals("dm")).findFirst().orElseThrow();
        AnchorKey grp = all.stream().filter(a -> a.scopeKind().equals("group")).findFirst().orElseThrow();
        assertEquals(userId, dm.scopeId(),
                "the DM anchor scope_id is the user's own id");
        assertEquals(groupId, grp.scopeId(),
                "the group anchor scope_id is the group's id");
        assertNotEquals(dm.scopeId(), grp.scopeId(),
                "the DM and group anchors must key on different scope ids");
    }

    // ----- helpers ------------------------------------------------------

    /** Await M1-634 worker-pool quiescence so negative asserts are race-free. */
    private void awaitDispatchIdle() {
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent");
    }

    private record AnchorKey(UUID userId, String scopeKind, UUID scopeId) {}

    private UUID insertUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, FALSE, 'vouched') "
                             + "ON CONFLICT (adapter, contact_id) "
                             + "DO UPDATE SET is_banned = FALSE, probation_until = NULL "
                             + "RETURNING id")) {
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
                             + "fetched_at, ready_at, status, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setString(5, "https://example.com/" + uid);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            // The retrieval window keys on ready_at (M1-689). These fixtures
            // model negligible fetch+evaluation lag, so fetched_at (the
            // partition key) and ready_at both mirror published_at and the
            // window means the same thing it did before.
            ps.setTimestamp(7, Timestamp.from(publishedAt));
            ps.setTimestamp(8, Timestamp.from(publishedAt));
            ps.setString(9, status);
            ps.setArray(10, conn.createArrayOf("TEXT", tags));
            ps.setString(11, uid);
            ps.executeUpdate();
        }
    }

    private List<AnchorKey> anchorsForGroup(UUID groupId) throws Exception {
        return queryAnchors(
                "SELECT user_id, scope_kind, scope_id FROM summary_anchor "
                        + "WHERE scope_kind = 'group' AND scope_id = ? ORDER BY user_id",
                groupId);
    }

    private List<AnchorKey> anchorsForUser(UUID userId) throws Exception {
        return queryAnchors(
                "SELECT user_id, scope_kind, scope_id FROM summary_anchor "
                        + "WHERE user_id = ? ORDER BY scope_kind",
                userId);
    }

    private List<AnchorKey> queryAnchors(String sql, UUID key) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                List<AnchorKey> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new AnchorKey(
                            (UUID) rs.getObject("user_id"),
                            rs.getString("scope_kind"),
                            (UUID) rs.getObject("scope_id")));
                }
                return out;
            }
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
