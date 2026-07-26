package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.messaging.InterruptibleDispatcher;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.DispatchAwaits;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end IT for the four {@code /summary} render forms (M1-700):
 * bare (categorized-capped), {@code --short} (roll-up), {@code --full}
 * (categorized-uncapped), {@code --flat} (flat per-cluster blocks). Drives
 * each form through the real CDI wiring — InboundRouter →
 * SummaryCommandHandler → DigestRenderer/ClusterBlockRenderer/
 * CategoryRollupGenerator → TestLlmProvider — via the InMemoryAdapter, and
 * pins the distinguishing property of each form against one shared
 * 14-post fixture (one {@code m1-700-news} category of 14 singleton
 * clusters, which exceeds {@code infochat.digest.category-item-cap} (12)
 * by exactly 2 so the capped-vs-uncapped contrast is observable).
 *
 * <p>Test isolation: every fixture carries the {@code m1-700-} prefix;
 * {@link #cleanup()} deletes rows matching that prefix before each
 * {@code @Test}.</p>
 */
@QuarkusTest
@TestProfile(SummaryRenderFormIT.RenderFormProfile.class)
class SummaryRenderFormIT {

    private static final String PREFIX = "m1-700-";
    private static final String USER_CONTACT_ID = PREFIX + "user-1";
    private static final String TAG = PREFIX + "news";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject TestLlmProvider mockLlm;
    @Inject InterruptibleDispatcher interruptibleDispatcher;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        mockLlm.reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_preferences "
                    + "WHERE scope_id IN (SELECT id FROM users "
                    + "                    WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    /**
     * Bare {@code /summary}: the categorized-capped default. 14 clusters in
     * one category → 12 shown, 2 past-cap → the DM-worded "+N more" overflow
     * line appears. Per-cluster prose IS generated for every cluster (the
     * handler generates before the render caps).
     */
    @Test
    void bareFormCapsAtTwelveAndEmitsOverflow() throws Exception {
        seedFourteenNewsPosts();
        mockLlm.setResponseText("Bare cluster prose.");

        adapter.deliverDm(USER_CONTACT_ID, "/summary -w 24h");
        awaitDispatchIdle();

        String body = deliveredBody();
        assertTrue(body.contains((TAG + " news").toUpperCase(Locale.ROOT)),
                "bare form must carry the category header. Got: " + body);
        assertTrue(body.contains("more stories"),
                "bare form must emit the '+N more' overflow (14 > cap 12). Got: " + body);
        assertFalse(body.contains("[topic_id="),
                "bare form is categorized, not flat. Got: " + body);
    }

    /**
     * {@code --full}: categorized-uncapped. The same 14-cluster category
     * renders with NO 12-cap and NO "+N more" overflow line — all clusters
     * appear. Per-cluster prose IS generated.
     */
    @Test
    void fullFormRendersAllClustersUncapped() throws Exception {
        seedFourteenNewsPosts();
        mockLlm.setResponseText("Full cluster prose.");

        adapter.deliverDm(USER_CONTACT_ID, "/summary --full -w 24h");
        awaitDispatchIdle();

        String body = deliveredBody();
        assertTrue(body.contains((TAG + " news").toUpperCase(Locale.ROOT)),
                "--full must carry the category header. Got: " + body);
        assertFalse(body.contains("more stories"),
                "--full must NOT emit the '+N more' overflow (cap skipped). Got: " + body);
        assertFalse(body.contains("[topic_id="),
                "--full is categorized, not flat. Got: " + body);
        assertTrue(body.contains("Full cluster prose."),
                "--full carries the per-cluster prose. Got: " + body);
    }

    /**
     * {@code --flat}: the renamed legacy {@code --full}. Flat per-cluster
     * blocks (the seven-field ClusterBlockRenderer form), single message.
     */
    @Test
    void flatFormRendersFlatPerClusterBlocks() throws Exception {
        seedFourteenNewsPosts();
        mockLlm.setResponseText("Flat cluster prose.");

        adapter.deliverDm(USER_CONTACT_ID, "/summary --flat -w 24h");
        awaitDispatchIdle();

        List<String> finalized = adapter.finalizedBodies();
        assertEquals(1, finalized.size(),
                "--flat is a single router-sent message (one in-place finalize)");
        assertEquals(1, adapter.sentMessages().size(),
                "--flat sends exactly one placeholder message (no follow-on fresh sends)");
        String body = finalized.get(0);
        assertEquals(14, body.split("\\[topic_id=").length - 1,
                "--flat renders one flat block per cluster (14). Got: " + body);
        assertTrue(body.contains("covered by:"));
        assertTrue(body.contains("summary: Flat cluster prose."));
        assertFalse(body.contains("more stories"),
                "--flat has no category cap / overflow. Got: " + body);
    }

    /**
     * {@code --short}: one CategoryRollupGenerator roll-up per category,
     * NO per-cluster prose, NO flat blocks. One roll-up LLM call for the
     * single category. The per-category footer steers to
     * {@code /summary <tag> --full}.
     */
    @Test
    void shortFormRendersRollupNotClusterProse() throws Exception {
        seedFourteenNewsPosts();
        mockLlm.setResponseText("Short roll-up synthesis.");

        adapter.deliverDm(USER_CONTACT_ID, "/summary --short -w 24h");
        awaitDispatchIdle();

        List<String> finalized = adapter.finalizedBodies();
        assertEquals(1, finalized.size(),
                "--short is a single router-sent message");
        String body = finalized.get(0);
        assertTrue(body.contains((TAG + " news").toUpperCase(Locale.ROOT)),
                "--short must carry the category header. Got: " + body);
        assertTrue(body.contains("Short roll-up synthesis."),
                "--short must carry the roll-up line. Got: " + body);
        assertFalse(body.contains("[topic_id="),
                "--short emits NO flat cluster blocks. Got: " + body);
        assertFalse(body.contains("summary:"),
                "--short emits NO per-cluster summary: field. Got: " + body);
        assertFalse(body.contains("more stories"),
                "--short has no category cap / overflow. Got: " + body);
        assertTrue(body.contains("/summary " + TAG + " --full"),
                "the --short footer steers to /summary <tag> --full. Got: " + body);
        // One roll-up LLM call for the one category (no per-cluster calls,
        // no translation on an English scope).
        assertEquals(1, mockLlm.callCount(),
                "--short makes one roll-up LLM call per category, not one per cluster");
    }

    // ----- helpers ------------------------------------------------------

    private String deliveredBody() {
        List<String> finalized = adapter.finalizedBodies();
        if (!finalized.isEmpty()) {
            return String.join("\n\n", finalized);
        }
        // The per-section forms (bare/--full) finalize the first section and
        // send the rest as fresh messages; collapse both into one body.
        List<OutboundMessage> sent = adapter.sentMessages();
        return sent.stream().map(OutboundMessage::text).reduce("", (a, b) -> a + "\n\n" + b);
    }

    private void seedFourteenNewsPosts() throws Exception {
        UUID userId = insertUser(USER_CONTACT_ID);
        UUID sourceId = insertSource(PREFIX + "src-1", "M1-700 News");
        insertSubscription(userId, sourceId);
        Instant now = Instant.now();
        for (int i = 1; i <= 14; i++) {
            insertPost(PREFIX + "p" + i, sourceId, "M1-700 headline " + i,
                    now.minus(Duration.ofMinutes(i)), "READY", new String[] { TAG });
        }
    }

    /** Await M1-634 worker-pool quiescence so negative asserts are race-free. */
    private void awaitDispatchIdle() {
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent");
    }

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

    private UUID insertSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
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

    private void insertPost(String uid, UUID sourceId, String title, Instant publishedAt,
                             String status, String[] tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                             + "ready_at, status, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setString(5, "https://example.com/" + uid);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(publishedAt));
            ps.setString(8, status);
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

    public static final class RenderFormProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.summary.cluster-cap", "200",
                    // Keep the summarizer-post-cap well above 14 so the
                    // over-cap gate never trips and every form reaches its
                    // terminal render path.
                    "infochat.summary.summarizer-post-cap", "50",
                    // category-item-cap stays at the default 12 so the
                    // 14-post fixture overflows by exactly 2 on the capped
                    // (bare) form and is fully shown on the uncapped
                    // (--full) form.
                    "infochat.profile.label", "laptop");
        }
    }
}
