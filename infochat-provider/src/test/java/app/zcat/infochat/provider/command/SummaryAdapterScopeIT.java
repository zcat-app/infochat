package app.zcat.infochat.provider.command;

import app.zcat.infochat.llm.LlmProvider;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the multi-adapter identity-bleed regression for
 * {@code /summary} per {@code docs/spec/security.md}
 * §Per-(user, scope) isolation + the V5
 * {@code UNIQUE (adapter, contact_id)} constraint on {@code users}.
 *
 * <p>Two users with the SAME {@code contact_id} literal exist under
 * different adapters — one under {@code inmemory} (the live adapter),
 * one under a synthetic {@code simplex-fake-m1-040si} name. The
 * inmemory user holds a subscription with one READY post titled
 * {@code ALPHA HEADLINE m1-040si}; the foreign-adapter user holds a
 * subscription with one READY post titled {@code BRAVO HEADLINE
 * m1-040si}. When {@code /summary} is delivered through the
 * {@link InMemoryAdapter} (the router sets
 * {@code InboundContext.adapterName="inmemory"}), the handler MUST
 * resolve user A — surface ALPHA, never BRAVO. A {@code contact_id}-
 * only SELECT (the pre-M1-040 shape) would cross-resolve to either
 * row and leak the foreign-adapter user's feed.
 */
@QuarkusTest
@TestProfile(SummaryAdapterScopeIT.MvpProfile.class)
class SummaryAdapterScopeIT {

    private static final String PREFIX = "m1-040si-";
    private static final String SHARED_CONTACT_ID = PREFIX + "shared";
    private static final String FOREIGN_ADAPTER = "simplex-fake-m1-040si";

    @Inject InMemoryAdapter adapter;

    @Inject @SeedDataSource DataSource dataSource;

    @Inject LlmProvider llmProvider;

    @Inject InterruptibleDispatcher interruptibleDispatcher;

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
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM tag WHERE name LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void inmemoryDeliveryResolvesInmemoryUserAndSurfacesOnlyTheirPosts() throws Exception {
        // Seed BOTH (adapter, shared-contact-id) rows BEFORE the
        // dispatch so AutoRegisterService's ON-CONFLICT-DO-NOTHING
        // upsert on (inmemory, SHARED_CONTACT_ID) is a no-op and we
        // know which UUID belongs to which adapter row.
        UUID inmemoryUserId = insertUser("inmemory", SHARED_CONTACT_ID);
        UUID foreignUserId = insertUser(FOREIGN_ADAPTER, SHARED_CONTACT_ID);
        UUID sourceAlpha = insertSource(PREFIX + "alpha-src");
        UUID sourceBravo = insertSource(PREFIX + "bravo-src");
        insertSubscription(inmemoryUserId, sourceAlpha);
        insertSubscription(foreignUserId, sourceBravo);
        Instant now = Instant.now();
        insertPost(PREFIX + "alpha-post", sourceAlpha, "ALPHA HEADLINE m1-040si",
                now, "READY", new String[] { PREFIX + "news" });
        insertPost(PREFIX + "bravo-post", sourceBravo, "BRAVO HEADLINE m1-040si",
                now, "READY", new String[] { PREFIX + "news" });
        mockLlm().setResponseText("ProseFor(m1-040si)");

        // --full renders the flat per-cluster blocks carrying the headlines
        // this test's D46 non-leakage pair asserts on (ALPHA present, BRAVO
        // absent); the M1-694 default form renders prose only, which would
        // make the assertFalse pass vacuously.
        adapter.deliverDm(SHARED_CONTACT_ID, "/summary --full");

        // /summary runs on an M1-634 worker — drain the pool so the
        // exactly-one and never-BRAVO negative asserts are race-free.
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent");

        // The notifier delivers via placeholder send + in-place finalize
        // on the SAME adapter the inbound arrived through (inmemory). The
        // summary content lands on the finalized body.
        assertEquals(1, adapter.sentMessages().size(),
                "exactly one placeholder send must be produced by the router → handler chain");
        assertEquals(1, adapter.finalizedBodies().size(),
                "exactly one finalized summary delivered to the inmemory adapter scope");
        String body = adapter.finalizedBodies().get(0);
        assertTrue(body.contains("ALPHA HEADLINE m1-040si"),
                "router→handler MUST resolve (adapter='inmemory', contact_id=shared) "
                        + "to the inmemory user and surface that user's post. Got: " + body);
        assertFalse(body.contains("BRAVO HEADLINE m1-040si"),
                "the foreign-adapter user's post MUST NOT appear in the reply — a "
                        + "contact_id-only SELECT would cross-resolve and leak this row. "
                        + "Got: " + body);
        // The mock LLM is hit exactly once (one cluster from one eligible post).
        assertEquals(1, mockLlm().callCount(),
                "exactly one LLM call expected (one eligible post → one cluster)");
        assertFalse(inmemoryUserId.equals(foreignUserId),
                "test fixture sanity: the two users have distinct user ids");

        // The same D46 property on the DEFAULT form, which is what users
        // actually receive. A headline assertFalse would pass vacuously here
        // (the categorized form renders prose only), so the discriminator is
        // the paragraph COUNT: the shared contact subscribes to the alpha
        // source alone, so a contact_id-only cross-resolve surfaces the
        // foreign post as a SECOND cluster prose paragraph. Counting is
        // required because the stub returns one fixed string per cluster.
        adapter.reset();
        mockLlm().reset();
        mockLlm().setResponseText("ProseFor(m1-040si)");

        adapter.deliverDm(SHARED_CONTACT_ID, "/summary");

        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent");

        assertEquals(1, adapter.finalizedBodies().size(),
                "exactly one finalized summary on the default form too");
        String defaultBody = adapter.finalizedBodies().get(0);
        assertEquals(1, countOccurrences(defaultBody, "ProseFor(m1-040si)"),
                "the default form MUST render exactly one cluster prose paragraph — a "
                        + "second one means the foreign-adapter user's post leaked through "
                        + "a contact_id-only SELECT. Got: " + defaultBody);
        assertEquals(1, mockLlm().callCount(),
                "exactly one LLM call on the default form (one eligible post → one cluster)");
    }

    // --- helpers ---------------------------------------------------------

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private UUID insertUser(String adapterName, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, "
                             + "registration_state) "
                             + "VALUES (?, ?, FALSE, 'vouched') "
                             + "RETURNING id")) {
            ps.setString(1, adapterName);
            ps.setString(2, contactId);
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
                     "INSERT INTO post (uid, source_id, title, body, url, "
                             + "published_at, ready_at, status, tags, upstream_identifier) "
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
                    "infochat.summary.cluster-cap", "3",
                    "infochat.profile.label", "test");
        }
    }
}
