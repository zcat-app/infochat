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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end /topic drill-down IT: prefix matching, world/window
 * isolation, bare cap vs --full cap skip, no-anchor posture. */
@QuarkusTest
@TestProfile(TopicCommandHandlerIT.TopicProfile.class)
class TopicCommandHandlerIT {

    private static final String PREFIX = "m1-936-";
    private static final String USER_CONTACT_ID = PREFIX + "user-1";
    private static final String TAG = PREFIX + "news";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");
    private static final String PROSE = "Topic drill prose.";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject TestLlmProvider mockLlm;
    @Inject InterruptibleDispatcher interruptibleDispatcher;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        mockLlm.reset();
        // The /topic window reads the injected Clock — pin it into the
        // same time family as the fixtures (the SummaryRenderFormIT idiom).
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM summary_anchor WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
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

    @Test
    void topicDrillDownRendersClustersOverPrefixMatchedPosts() throws Exception {
        UUID userId = insertUser(USER_CONTACT_ID);
        UUID sourceId = insertSource(PREFIX + "src-1", "M1-936 News");
        insertSubscription(userId, sourceId);
        // 3 world-and-window czechia posts — the match set.
        for (int i = 1; i <= 3; i++) {
            insertPost(PREFIX + "in" + i, sourceId, "M1-936 inside " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(i)), new String[] { TAG },
                    new String[] { "czechia" });
        }
        // 1 czechia post OUTSIDE the window (25h old) — never surfaces.
        insertPost(PREFIX + "old1", sourceId, "M1-936 stale",
                PINNED_NOW.minus(Duration.ofHours(25)), new String[] { TAG },
                new String[] { "czechia" });
        // 2 czechia posts from an UNSUBSCRIBED (user-origin, invisible)
        // source — outside the D59 world, never surface.
        UUID stranger = insertSource(PREFIX + "src-2", "M1-936 Stranger");
        for (int i = 1; i <= 2; i++) {
            insertPost(PREFIX + "out" + i, stranger, "M1-936 outside world " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(i)), new String[] { TAG },
                    new String[] { "czechia" });
        }
        mockLlm.setResponseText(PROSE);

        adapter.deliverDm(USER_CONTACT_ID, "/topic czech");
        awaitDispatchIdle();

        String body = deliveredBody();
        assertTrue(body.contains((TAG + " news").toUpperCase(Locale.ROOT)),
                "the drill-down renders the /summary category sections. Got: " + body);
        // One stub-prose occurrence per MATCHED cluster: 3 inside posts —
        // a window or world leak would raise the count to 6.
        assertEquals(3, countOccurrences(body, PROSE),
                "exactly the 3 world-and-window prefix matches render. Got: " + body);
        // No summary anchor: /retry never replays a topic run (the
        // deliberate omission recorded on the ticket).
        assertEquals(0, countAnchors(userId),
                "/topic writes no summary_anchor row");
    }

    @Test
    void fullDrillDownSkipsThePerSectionCap() throws Exception {
        UUID userId = insertUser(USER_CONTACT_ID);
        UUID sourceId = insertSource(PREFIX + "src-1", "M1-936 News");
        insertSubscription(userId, sourceId);
        // 14 czechia clusters in one category: bare caps at 12 (+2 demoted),
        // --full shows all (the SummaryRenderFormIT contrast shape).
        for (int i = 1; i <= 14; i++) {
            insertPost(PREFIX + "p" + i, sourceId, "M1-936 headline " + i,
                    PINNED_NOW.minus(Duration.ofMinutes(i)), new String[] { TAG },
                    new String[] { "czechia" });
        }
        mockLlm.setResponseText(PROSE);

        adapter.deliverDm(USER_CONTACT_ID, "/topic czech");
        awaitDispatchIdle();
        String bare = deliveredBody();
        assertEquals(12, countOccurrences(bare, PROSE),
                "the bare drill-down caps at 12 per section");
        assertTrue(bare.contains("more stories"),
                "the capped form emits the demotion overflow line. Got: " + bare);

        adapter.reset();
        adapter.deliverDm(USER_CONTACT_ID, "/topic czech --full");
        awaitDispatchIdle();
        String full = deliveredBody();
        assertEquals(14, countOccurrences(full, PROSE),
                "--full renders every matched cluster, no cap");
        assertFalse(full.contains("more stories"),
                "--full emits no demotion overflow line. Got: " + full);
    }

    // ----- helpers ------------------------------------------------------

    private String deliveredBody() {
        List<String> finalized = adapter.finalizedBodies();
        if (!finalized.isEmpty()) {
            return String.join("\n\n", finalized);
        }
        List<OutboundMessage> sent = adapter.sentMessages();
        return sent.stream().map(OutboundMessage::text).reduce("", (a, b) -> a + "\n\n" + b);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private int countAnchors(UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM summary_anchor WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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
        // source_origin defaults to 'user': without a subscription the
        // source is INVISIBLE under D59 — exactly the isolation leg.
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
                             String[] tags, String[] searchTags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                             + "fetched_at, ready_at, status, tags, search_tags, "
                             + "upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setString(5, "https://example.com/" + uid);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(publishedAt));
            ps.setTimestamp(8, Timestamp.from(publishedAt));
            ps.setArray(9, conn.createArrayOf("TEXT", tags));
            ps.setArray(10, conn.createArrayOf("TEXT", searchTags));
            ps.setString(11, uid);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    public static final class TopicProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.summary.cluster-cap", "200",
                    "infochat.summary.summarizer-post-cap", "50",
                    "infochat.profile.label", "laptop");
        }
    }
}
