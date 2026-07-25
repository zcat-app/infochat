package app.zcat.infochat.provider.translation;

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
 * Full-pipeline IT for the translation path (Shape B+). Exercises
 * the end-to-end flow: {@code /summary} → summarizer LLM call →
 * TranslationPipeline (cache + translator + sanitizer-2) → adapter
 * delivery.
 *
 * <p><b>{@code @TestProfile} config-key rule:</b> In the Provider
 * test classpath, {@link TestLlmProvider} ({@code @Alternative
 * @Priority(Integer.MAX_VALUE)}) replaces the production
 * {@code OpenAiCompatibleProvider}. {@code LlmRouter.providerName(p)}
 * resolves the provider's simple class name for any non-OpenAI
 * instance, so the languages config key is
 * {@code infochat.llm.TestLlmProvider.languages} — NOT the
 * production {@code infochat.llm.openai-compatible.languages} key.
 * The production key only applies in deployments where the OpenAI-
 * shaped provider holds the CDI slot.
 *
 * <p>The IT uses {@link TestLlmProvider}'s existing single
 * {@link TestLlmProvider#callCount()} as the en-vs-cs discriminator:
 * cs-scope → 2 calls (summarizer + translator); en-scope → 1 call
 * (summarizer only, translator skipped by the pipeline's
 * en-short-circuit).
 */
@QuarkusTest
@TestProfile(TranslationPipelineIT.TranslationProfile.class)
class TranslationPipelineIT {

    private static final String PREFIX = "m1-059-it-";
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

    /**
     * cs-scope happy path: summarizer prose is translated into a DISTINCT
     * cs sentinel, so the full pipeline runs (translator -> sanitizer-2 ->
     * cache write) and the second identical cluster hits the translation
     * cache. The summarizer and translator responses must differ: an
     * echoed translation (output byte-identical to input) is now treated
     * as a fallback by {@code TranslationPipeline} (M1-437 condition b),
     * which would skip sanitizer-2 and the cache. The translator response
     * is set via {@code setTranslatorResponseText} so it is distinct from
     * the summarizer prose returned by {@code setResponseText}.
     */
    @Test
    void summaryInCsScopeRunsThroughFullTranslationPipeline() throws Exception {
        UUID userId = insertUser(USER_CONTACT_ID);
        UUID sourceId = insertSource(PREFIX + "src-1", "TransSrc");
        insertSubscription(userId, sourceId);

        Instant now = Instant.now();
        String postUid1 = PREFIX + "p1";
        String postUid2 = PREFIX + "p2";
        insertPost(postUid1, sourceId, "Translation headline 1",
                now.minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });
        insertPost(postUid2, sourceId, "Translation headline 2",
                now.minus(Duration.ofMinutes(2)), "READY",
                new String[] { PREFIX + "news" });

        // Set scope language to Czech.
        setScopeLanguage(userId, "cs");

        mockLlm.reset();
        // Distinct responses per task: the summarizer produces English prose,
        // the translator produces a distinct cs sentinel. They MUST differ so
        // the translator output is not byte-identical to its input (which the
        // pipeline's M1-437 condition (b) would treat as a fallback).
        mockLlm.setResponseText("<en-summary-prose>");
        mockLlm.setTranslatorResponseText("<cs-translation>");

        adapter.deliverDm(USER_CONTACT_ID, "/summary -w 24h");

        // /summary runs on an M1-634 worker — drain the pool so the
        // exactly-one bounds below are race-free.
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent");

        assertEquals(1, adapter.sentMessages().size(), "exactly one placeholder send");
        List<String> finalized = adapter.finalizedBodies();
        assertEquals(1, finalized.size(), "exactly one finalized summary message");
        String body = finalized.get(0);

        assertTrue(body.contains("<cs-translation>"),
                "cs-scope outbound must contain the translated text. Got: " + body);
        assertFalse(body.contains("<en-summary-prose>"),
                "happy-path translation must REPLACE the English prose, not fall "
                        + "back to it. Got: " + body);

        assertPostBodyUnchanged(postUid1, "Body for Translation headline 1");
        assertPostBodyUnchanged(postUid2, "Body for Translation headline 2");

        // 2 singleton clusters: 2 summarizer calls + 1 translator call
        // (second cluster's translation is a cache hit since both
        // clusters produce identical post-sanitizer-1 text).
        assertEquals(3, mockLlm.callCount(),
                "cs-scope: 2 summarizer + 1 translator (second cluster hits "
                        + "translation cache since identical sanitizer-1 output)");
    }

    /**
     * en-scope: summarizer only = callCount matches cluster count.
     * The pipeline's en-short-circuit skips the translator entirely.
     */
    @Test
    void summaryInEnScopeShortCircuitsTranslator() throws Exception {
        UUID userId = insertUser(USER_CONTACT_ID);
        UUID sourceId = insertSource(PREFIX + "src-1", "TransSrc");
        insertSubscription(userId, sourceId);

        Instant now = Instant.now();
        insertPost(PREFIX + "p1", sourceId, "En headline 1",
                now.minus(Duration.ofMinutes(1)), "READY",
                new String[] { PREFIX + "news" });
        insertPost(PREFIX + "p2", sourceId, "En headline 2",
                now.minus(Duration.ofMinutes(2)), "READY",
                new String[] { PREFIX + "news" });

        // Scope language defaults to 'en' (V7 migration default).

        mockLlm.reset();
        mockLlm.setResponseText("<en-summary>");

        adapter.deliverDm(USER_CONTACT_ID, "/summary -w 24h");

        // /summary runs on an M1-634 worker — drain the pool so the
        // exactly-one bounds below are race-free.
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent");

        assertEquals(1, adapter.sentMessages().size(), "exactly one placeholder send");
        List<String> finalized = adapter.finalizedBodies();
        assertEquals(1, finalized.size(), "exactly one finalized summary message");
        String body = finalized.get(0);

        assertTrue(body.contains("<en-summary>"),
                "en-scope outbound must contain the summarizer output. Got: " + body);

        // 2 singleton clusters → 2 summarizer calls, 0 translator calls.
        assertEquals(2, mockLlm.callCount(),
                "en-scope: only summarizer calls (translator skipped by pipeline "
                        + "en-short-circuit)");
    }

    // ----- helpers -------------------------------------------------------

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

    private void setScopeLanguage(UUID scopeId, String language) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_preferences (scope_kind, scope_id, language) "
                             + "VALUES ('dm', ?, ?) "
                             + "ON CONFLICT (scope_kind, scope_id) "
                             + "DO UPDATE SET language = EXCLUDED.language")) {
            ps.setObject(1, scopeId);
            ps.setString(2, language);
            ps.executeUpdate();
        }
    }

    private void assertPostBodyUnchanged(String uid, String expectedBody) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT body FROM post WHERE uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post with uid " + uid + " must exist");
                assertEquals(expectedBody, rs.getString("body"),
                        "post.body must be byte-unchanged after translation pipeline");
            }
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * Test profile extending the MVP shape with the translation
     * provider's language config. The key is
     * {@code infochat.llm.TestLlmProvider.languages} because
     * {@code LlmRouter.providerName(p)} returns the simple class
     * name for any non-{@code OpenAiCompatibleProvider} instance.
     */
    public static final class TranslationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.summary.cluster-cap", "200",
                    "infochat.profile.label", "laptop",
                    "infochat.llm.TestLlmProvider.languages", "en,cs");
        }
    }
}
