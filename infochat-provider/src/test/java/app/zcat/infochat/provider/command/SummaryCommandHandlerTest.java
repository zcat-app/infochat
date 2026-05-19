package app.zcat.infochat.provider.command;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.messaging.InboundRouter;
import app.zcat.infochat.provider.testing.TestLlmProvider;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier tests for {@link SummaryCommandHandler}. Boots
 * {@code @QuarkusTest} with the {@link MvpProfile} so the
 * {@code inmemory} adapter activates and the router → handler chain
 * runs against the production CDI graph. The {@link FixedBlobLlmProvider}
 * {@link Alternative} replaces the OpenAI-compatible provider so the
 * test can pin LLM behavior (a fixed prose blob or a throwing variant).
 *
 * <p>Asserted invariants (one {@code @Test} per acceptance bullet
 * branch — items 1, 2, 3, 12 and item 17's config-property reachability):
 * <ul>
 *   <li>CDI discovery: InboundRouter dispatches /summary to the
 *       handler exactly once.</li>
 *   <li>Handler returns {@code "summary"} as its name.</li>
 *   <li>Happy path: 3 eligible posts → 3 clusters → 3 LLM calls → reply
 *       contains 3 cluster blocks in the documented structure.</li>
 *   <li>Empty-window branch: zero eligible posts → no_posts_yet reply,
 *       NO LLM call.</li>
 *   <li>Zero-subscriptions branch: same no_posts_yet reply, NO LLM call.</li>
 *   <li>LLM-unreachable branch: degraded fallback reply with
 *       degraded_notice prefix.</li>
 *   <li>Cap-excess branch: cap_excess_notice prefix interpolated with
 *       cap + total + excluded count.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(SummaryCommandHandlerTest.MvpProfile.class)
class SummaryCommandHandlerTest {

    private static final String PREFIX = "m1-037h-";

    @Inject InMemoryAdapter adapter;

    @Inject InboundRouter inboundRouter;

    @Inject SummaryCommandHandler handler;

    @Inject DataSource dataSource;

    @Inject LlmProvider llmProvider;

    private TestLlmProvider mockLlm() {
        return (TestLlmProvider) llmProvider;
    }

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        mockLlm().reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_tag "
                    + "WHERE tag_id IN (SELECT id FROM tag WHERE name LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_preferences "
                    + "WHERE scope_id IN (SELECT id FROM users "
                    + "                    WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM tag WHERE name LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void handlerNameIsLiteralSummary() {
        assertEquals("summary", handler.name(),
                "name() returns the literal `summary` (router strips the slash)");
    }

    @Test
    void inboundRouterDispatchesSummaryToHandlerExactlyOnce() {
        // No subscriptions / posts seeded → handler still runs once and
        // returns the no_posts_yet reply. The presence of exactly one
        // outbound is what the dispatch test asserts.
        adapter.deliverDm(PREFIX + "disp", "/summary");
        assertEquals(1, adapter.sentMessages().size(),
                "exactly one outbound reply must be produced via the router → handler chain");
    }

    @Test
    void zeroSubscriptionsProducesNoPostsYetReplyWithoutLlmCall() throws Exception {
        // Pre-register the user with no subscriptions.
        insertUser(PREFIX + "nosub");
        adapter.deliverDm(PREFIX + "nosub", "/summary");
        String body = adapter.sentMessages().get(0).text();
        assertTrue(body.contains("No posts to summarize"),
                "zero subscriptions → no_posts_yet reply. Got: " + body);
        assertEquals(0, mockLlm().callCount(),
                "zero-subscription path must NOT call the LLM");
    }

    @Test
    void emptyWindowProducesNoPostsYetReplyWithoutLlmCall() throws Exception {
        // User has a subscription but no READY posts in the window.
        UUID userId = insertUser(PREFIX + "empty");
        UUID sourceId = insertSource(PREFIX + "empty-src");
        insertSubscription(userId, sourceId);

        adapter.deliverDm(PREFIX + "empty", "/summary");
        String body = adapter.sentMessages().get(0).text();
        assertTrue(body.contains("No posts to summarize"));
        assertEquals(0, mockLlm().callCount());
    }

    @Test
    void happyPathThreeEligiblePostsYieldsThreeClusterBlocksAndThreeLlmCalls() throws Exception {
        UUID userId = insertUser(PREFIX + "happy");
        UUID sourceId = insertSource(PREFIX + "happy-src");
        insertSubscription(userId, sourceId);
        Instant now = Instant.now();
        insertPost(PREFIX + "h1", sourceId, "Headline A", now.minus(Duration.ofMinutes(1)),
                "READY", new String[] { PREFIX + "news" });
        insertPost(PREFIX + "h2", sourceId, "Headline B", now.minus(Duration.ofMinutes(2)),
                "READY", new String[] { PREFIX + "news" });
        insertPost(PREFIX + "h3", sourceId, "Headline C", now.minus(Duration.ofMinutes(3)),
                "READY", new String[] { PREFIX + "news" });
        mockLlm().setResponseText("Summary prose for the cluster.");

        adapter.deliverDm(PREFIX + "happy", "/summary");

        assertEquals(3, mockLlm().callCount(), "one LLM call per cluster");
        String body = adapter.sentMessages().get(0).text();
        // Three cluster blocks → three [topic_id=...] markers.
        int blocks = body.split("\\[topic_id=").length - 1;
        assertEquals(3, blocks, "three cluster blocks in reply. Got: " + body);
        // The mocked prose appears at the `summary:` slot.
        assertTrue(body.contains("Summary prose for the cluster."),
                "LLM-authored prose lands at the summary: slot. Got: " + body);
        // Deterministic field labels in the documented order.
        assertTrue(body.contains("covered by:"));
        assertTrue(body.contains("score:"));
        assertTrue(body.contains("classification:"));
        assertTrue(body.contains("tags:"));
        // Headline (first post's title) appears verbatim.
        assertTrue(body.contains("Headline A"));
    }

    @Test
    void llmUnreachableYieldsDegradedFallbackReply() throws Exception {
        UUID userId = insertUser(PREFIX + "deg");
        UUID sourceId = insertSource(PREFIX + "deg-src");
        insertSubscription(userId, sourceId);
        insertPost(PREFIX + "d1", sourceId, "Degraded headline", Instant.now(),
                "READY", new String[] { PREFIX + "news" });
        mockLlm().setThrowOnCall(true);

        adapter.deliverDm(PREFIX + "deg", "/summary");

        String body = adapter.sentMessages().get(0).text();
        assertTrue(body.contains("LLM is unreachable"),
                "degraded reply must include the degraded_notice prefix. Got: " + body);
        assertTrue(body.contains("Degraded headline"),
                "degraded prose includes the headline");
    }

    @Test
    void capExcessYieldsCapExcessNoticePrefix() throws Exception {
        // MvpProfile pins cluster-cap=3 so we don't need 200 posts.
        UUID userId = insertUser(PREFIX + "cap");
        UUID sourceId = insertSource(PREFIX + "cap-src");
        insertSubscription(userId, sourceId);
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            insertPost(PREFIX + "c" + i, sourceId, "Cap headline " + i,
                    now.minus(Duration.ofMinutes(i)), "READY", new String[] { PREFIX + "news" });
        }
        mockLlm().setResponseText("Prose.");

        adapter.deliverDm(PREFIX + "cap", "/summary");

        String body = adapter.sentMessages().get(0).text();
        // Cap=3 with 5 eligible → "Showing 3 of 5 posts (cap: test=3; 2 oldest excluded)".
        assertTrue(body.contains("Showing 3 of 5"),
                "cap-excess prefix must cite included/total counts. Got: " + body);
        assertTrue(body.contains("2 oldest excluded"),
                "cap-excess prefix must cite the excluded count. Got: " + body);
        assertEquals(3, mockLlm().callCount(),
                "only the retained 3 posts (= 3 clusters) get LLM calls");
    }

    @Test
    void groupScopeReturnsNoPostsYet() {
        OutboundMessage reply = handler.handle(new ScopeRef.Group("g-some-id"), "/summary");
        assertTrue(reply.text().contains("No posts to summarize"),
                "group scope (no actor seam in v1) falls through to no_posts_yet. Got: "
                        + reply.text());
        assertEquals(0, mockLlm().callCount(),
                "group scope must NOT invoke the LLM");
    }

    @Test
    void sanitizerStripsPrivilegedCommandFromLlmAuthoredProse() throws Exception {
        UUID userId = insertUser(PREFIX + "san");
        UUID sourceId = insertSource(PREFIX + "san-src");
        insertSubscription(userId, sourceId);
        insertPost(PREFIX + "s1", sourceId, "San headline", Instant.now(), "READY",
                new String[] { PREFIX + "news" });
        // A small LLM emits prose containing /grant-admin — the sanitizer
        // must replace it with [redacted command] before the reply lands.
        mockLlm().setResponseText("Ops should run /grant-admin to escalate.");

        adapter.deliverDm(PREFIX + "san", "/summary");

        String body = adapter.sentMessages().get(0).text();
        assertFalse(body.contains("/grant-admin"),
                "sanitizer MUST strip /grant-admin from LLM-authored prose. Got: " + body);
        assertTrue(body.contains("[redacted command]"),
                "sanitizer MUST replace the matched command with the fixed literal. Got: " + body);
    }

    // ----- helpers ------------------------------------------------------

    private UUID insertUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES ('inmemory', ?, FALSE, 'vouched') "
                             + "ON CONFLICT (adapter, contact_id) "
                             + "DO UPDATE SET is_banned = FALSE RETURNING id")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID insertSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status) "
                             + "VALUES ('rss', ?, 'TestSrc', 'news', '{}', 'active') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertSubscription(UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES ('dm', ?, ?)")) {
            ps.setObject(1, scopeId);
            ps.setObject(2, sourceId);
            ps.executeUpdate();
        }
    }

    private void insertPost(String uid, UUID sourceId, String title,
                             Instant publishedAt, String status, String[] tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                             + "status, tags) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setString(5, "https://example.com/" + uid);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setString(7, status);
            ps.setArray(8, conn.createArrayOf("TEXT", tags));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * Test profile pins the cluster-cap low so the cap-excess test
     * doesn't seed 200 posts. The {@link TestLlmProvider} stub is the
     * shared {@code @Mock LlmProvider} replacement; no per-profile
     * alternative registration needed.
     */
    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.summary.cluster-cap", "3",
                    "infochat.profile.label", "test");
        }

    }
}
