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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MVP exit criterion §6 end-to-end IT via {@link InMemoryAdapter}. Per
 * acceptance item 13 of M1-037: seed 2 sources, subscribe DM
 * {@code m1-037-mvp-user-1} to both, seed 4 READY posts (2 per source)
 * within 24h, mock the {@link LlmProvider} to return a fixed prose
 * blob per cluster, then assert the seven (a)–(g) properties.
 *
 * <p>Test isolation: every fixture this IT writes carries the
 * {@code m1-037-mvp-} prefix; {@link #cleanup()} deletes rows matching
 * that prefix before each {@code @Test} so two runs do not race.</p>
 */
@QuarkusTest
@TestProfile(SummaryIT.MvpProfile.class)
class SummaryIT {

    private static final String PREFIX = "m1-037-mvp-";
    private static final String USER_CONTACT_ID = PREFIX + "user-1";

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
    void mvpExitCriterionSixEndToEndSummaryProducesSanitizedProseAndCitations() throws Exception {
        UUID userId = insertUser(USER_CONTACT_ID);
        UUID source1Id = insertSource(PREFIX + "src-1", "MvpNews");
        UUID source2Id = insertSource(PREFIX + "src-2", "MvpTech");
        insertSubscription(userId, source1Id);
        insertSubscription(userId, source2Id);

        Instant now = Instant.now();
        insertPost(PREFIX + "p1", source1Id, "MVP headline 1", now.minus(Duration.ofMinutes(1)),
                "READY", new String[] { PREFIX + "news" });
        insertPost(PREFIX + "p2", source1Id, "MVP headline 2", now.minus(Duration.ofMinutes(2)),
                "READY", new String[] { PREFIX + "news" });
        insertPost(PREFIX + "p3", source2Id, "MVP headline 3", now.minus(Duration.ofMinutes(3)),
                "READY", new String[] { PREFIX + "tech" });
        insertPost(PREFIX + "p4", source2Id, "MVP headline 4", now.minus(Duration.ofMinutes(4)),
                "READY", new String[] { PREFIX + "tech" });

        mockLlm.setResponseText("Fixed prose blob per cluster.");

        // --full renders the flat per-cluster blocks whose uid and
        // display-name fields this test asserts on; the M1-694 default form
        // renders prose only.
        adapter.deliverDm(USER_CONTACT_ID, "/summary --full -w 24h");

        // /summary is D35-interruptible → the whole handler runs on an
        // M1-634 worker; drain the pool so the exactly-one negative
        // bounds below are race-free.
        awaitDispatchIdle();

        // (b) one visibly-evolving message: the ProgressNotifier sends a
        // single placeholder (recorded on sentMessages) and finalizes it
        // in place with the real summary (recorded on finalizedBodies).
        // The router performs no send of its own — the handler returned
        // null (self-delivered).
        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(), "exactly one placeholder send");
        List<String> finalized = adapter.finalizedBodies();
        assertEquals(1, finalized.size(), "exactly one finalized summary message");
        String body = finalized.get(0);

        // (c) the finalized body contains all 4 post UIDs.
        assertTrue(body.contains(PREFIX + "p1"), "reply must cite p1 uid");
        assertTrue(body.contains(PREFIX + "p2"), "reply must cite p2 uid");
        assertTrue(body.contains(PREFIX + "p3"), "reply must cite p3 uid");
        assertTrue(body.contains(PREFIX + "p4"), "reply must cite p4 uid");

        // (d) the reply body contains both source display names (the
        // identifier itself isn't user-visible — display_name is).
        // The IT exercises the display-name pathway since that's what
        // the spec layout shows on `covered by:`.
        assertTrue(body.contains("MvpNews"), "reply must cite source1 display name");
        assertTrue(body.contains("MvpTech"), "reply must cite source2 display name");

        // (e) no markdown link syntax in the reply (plain-text invariant).
        assertFalse(body.contains("](http"),
                "reply MUST contain no markdown-link syntax (D30 + sanitizer first pass). "
                        + "Got: " + body);

        // (f) the LlmProvider was invoked 4 times (4 singleton clusters
        // → one LLM call per cluster).
        assertEquals(4, mockLlm.callCount(),
                "exactly 4 LLM calls (one per singleton cluster)");
    }

    @Test
    void windowOverSummarizerPostCapSkipsLlmAndSteersToNarrow() throws Exception {
        UUID userId = insertUser(USER_CONTACT_ID);
        UUID source1Id = insertSource(PREFIX + "src-1", "MvpNews");
        insertSubscription(userId, source1Id);

        // 6 READY posts > summarizer-post-cap=5 (profile override below):
        // the M1-623 explicit size decision must fire BEFORE any LLM call.
        Instant now = Instant.now();
        for (int i = 1; i <= 6; i++) {
            insertPost(PREFIX + "p" + i, source1Id, "MVP capped headline " + i,
                    now.minus(Duration.ofMinutes(i)), "READY",
                    new String[] { PREFIX + "news" });
        }

        // A summarizer call on this path would be a regression even if it
        // succeeded — make one fail loudly AND assert the count stays 0.
        mockLlm.setThrowOnCall(true);

        adapter.deliverDm(USER_CONTACT_ID, "/summary -w 24h");

        // Worker-side guard reply (M1-634) — drain before the zero-call /
        // zero-finalize negative asserts.
        awaitDispatchIdle();

        // The gate branch is a plain guard reply through the router —
        // no ProgressNotifier placeholder lifecycle.
        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(), "exactly one direct guard reply");
        assertEquals(0, adapter.finalizedBodies().size(),
                "no placeholder lifecycle on the over-cap path");
        String body = sent.get(0).text();

        assertEquals(0, mockLlm.callCount(),
                "over-cap window must make zero summarizer LLM calls");
        assertTrue(body.contains("more than the 5-post summarizer limit"),
                "reply must explain the window is over the summarizer cap. Got: " + body);
        assertTrue(body.contains("-w"),
                "reply must steer the user to narrow with -w. Got: " + body);
        assertFalse(body.contains("LLM is unreachable"),
                "over-cap degrade must NOT claim the LLM is unreachable. Got: " + body);
        // Degraded blocks still render: headline + uid per post.
        assertTrue(body.contains("MVP capped headline 1"),
                "degraded blocks include the headlines");
        assertTrue(body.contains(PREFIX + "p6"),
                "degraded blocks include the post UIDs");
    }

    @Test
    void mvpExitCriterionSixDegradedFallbackWhenLlmThrows() throws Exception {
        UUID userId = insertUser(USER_CONTACT_ID);
        UUID source1Id = insertSource(PREFIX + "src-1", "MvpNews");
        insertSubscription(userId, source1Id);
        insertPost(PREFIX + "p1", source1Id, "MVP degraded headline",
                Instant.now().minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });

        mockLlm.setThrowOnCall(true);

        adapter.deliverDm(USER_CONTACT_ID, "/summary -w 24h");

        awaitDispatchIdle();

        // Degraded prose is still a composed (successful) terminal
        // delivery → finalized in place, not a fail() placeholder.
        String body = adapter.finalizedBodies().get(0);
        // (g) LLM throws → degraded notice prefix + headline + bare URL + uid.
        assertTrue(body.contains("LLM is unreachable"),
                "degraded reply must include the degraded_notice prefix. Got: " + body);
        assertTrue(body.contains("MVP degraded headline"),
                "degraded prose includes the headline");
        assertTrue(body.contains(PREFIX + "p1"),
                "degraded prose includes the post UID");
    }

    // ----- helpers ------------------------------------------------------

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
            // The retrieval window keys on ready_at (M1-689). These fixtures
            // model negligible fetch+evaluation lag, so it mirrors published_at
            // and the window means the same thing it did before.
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

    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.summary.cluster-cap", "200",
                    // M1-623 boundary pin: 5 keeps the 4-post MVP tests on the
                    // prose path while the 6-post over-cap test trips the gate.
                    "infochat.summary.summarizer-post-cap", "5",
                    "infochat.profile.label", "laptop");
        }
    }
}
