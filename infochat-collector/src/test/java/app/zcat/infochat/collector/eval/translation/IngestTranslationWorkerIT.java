package app.zcat.infochat.collector.eval.translation;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.llm.LlmOutputSanitizerCore;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@link IngestTranslationWorker}
 * (M1-749) against the real migrated schema: a Czech post is persisted,
 * translated, and found by an ENGLISH lexical query — the behaviour that
 * does not exist today (the lexical arm stemmed Czech tokens as English
 * noise). Also pins, on the NEW path: {@code post.title}/{@code post.body}
 * stay byte-identical (D29), the carried-across controls (a)/(b) fire on
 * the translator's OUTPUT before storage, and the embedding gate holds
 * the post until {@code translation_done} flips.
 *
 * <p>The same class holds the DB-backed {@code processOne} contract
 * tests — the en-never-dispatched boundary (zero stub calls after an
 * English-source {@code processOne}, asserted AT the dispatch boundary,
 * not assumed from a SQL predicate), the non-English dispatch,
 * idempotency over a re-delivered post, the retry-exhaustion release,
 * and the structured-refusal arm. They were planned for the {@code *Test}
 * unit class, but IntegrationTestNamingGuardTest forbids a
 * DataSource-injecting {@code *Test} and the naming baseline is outside
 * this ticket's files_scope, so they live here; the pure
 * parse/renderPrompt assertions stay in {@link IngestTranslationWorkerTest}.
 *
 * <p>The ticket's "spy" on the normalize/sanitize calls is realized
 * BEHAVIOURALLY: the controls are static transforms
 * ({@code IngestTextNormalizer} / {@link LlmOutputSanitizerCore}) that no
 * spy can intercept, so the canned translator reply is laced with a
 * zero-width codepoint, a markdown link, and a closed-list token, and the
 * STORED {@code body_en} is asserted stripped / flattened / redacted —
 * which pins the controls to the new path more strongly than an
 * invocation count would.
 */
@QuarkusTest
class IngestTranslationWorkerIT {

    // A FIXED instant the scan-window pickup reads via the injected Clock
    // (pinned in reset()), so a fixed in-window fetched_at cannot age out
    // below the floor (engineering-rules §9; ScanWindowFixtureGuardTest).
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");
    // In-window fetched_at: above the PINNED_NOW − (retention + slack)
    // floor and inside the June 2026 post partition.
    private static final Instant FETCHED_AT = Instant.parse("2026-06-19T10:00:00Z");
    private static final String UID_PREFIX = "ingest-translation-it/";

    private static final String CS_TITLE = "Povodeň zasáhla Prahu";
    private static final String CS_BODY = "Vltava v noci vystoupala z břehů.";
    private static final String EN_TITLE = "Flood hits Prague";
    private static final String EN_BODY = "The Vltava burst its banks overnight.";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    IngestTranslationWorker worker;

    @Inject
    LlmProvider llmProvider;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        // Pin the injected Clock the scan-window pickup reads so the
        // boundary is deterministic (M1-444 seam).
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        stub().reset();
        clearItData();
        // The notifier's state is DB-persistent across the whole Quarkus
        // test instance — a must-NOT-fire assertion is only meaningful
        // from a known-empty slate.
        clearNotifierState(IngestTranslationWorker.ERROR_CLASS_TRANSLATION_FAILURE);
        clearNotifierState(IngestTranslationWorker.ERROR_CLASS_TRANSLATION_REFUSAL);
    }

    @Test
    void englishSource_markedDoneWithNoTranslatorDispatch() throws Exception {
        UUID postId = seedPost("en", "en", CS_TITLE, CS_BODY);

        worker.processOne(pickedUp(postId));

        assertEquals(0, stub().callCount(),
            "a source.language='en' post must NEVER reach the translator — asserted at the dispatch boundary");
        assertTranslation(postId, null, null, true);
    }

    @Test
    void nonEnglishSource_isDispatchedAndPersisted() throws Exception {
        stub().setNextResponse("{\"title\":\"" + EN_TITLE + "\",\"body\":\"" + EN_BODY + "\"}");
        UUID postId = seedPost("cs", "cs", CS_TITLE, CS_BODY);

        worker.processOne(pickedUp(postId));

        assertEquals(1, stub().callCount(), "one post, one translator call");
        assertTranslation(postId, EN_TITLE, EN_BODY, true);
        assertOriginalsUntouched(postId);
    }

    @Test
    void redeliveredPost_isIdempotent() throws Exception {
        // The outbox rehydrate path can deliver the same post twice (a
        // crash between the LLM call and the cursor UPDATE leaves
        // translation_done=FALSE and the next tick re-picks): processing
        // it again must simply rewrite the same values.
        stub().setNextResponses(
            "{\"title\":\"" + EN_TITLE + "\",\"body\":\"" + EN_BODY + "\"}",
            "{\"title\":\"" + EN_TITLE + "\",\"body\":\"" + EN_BODY + "\"}");
        UUID postId = seedPost("redeliver", "cs", CS_TITLE, CS_BODY);

        IngestTranslationWorker.PostRow row = pickedUp(postId);
        worker.processOne(row);
        worker.processOne(row);

        assertEquals(2, stub().callCount(), "each delivery is one translator call");
        assertTranslation(postId, EN_TITLE, EN_BODY, true);
        assertOriginalsUntouched(postId);
    }

    @Test
    void retryExhaustion_releasesNullAndPostStaysEmbeddingReady() throws Exception {
        // Both attempts unreachable → translation_done=TRUE with
        // title_en/body_en NULL + notification. The release leaves the
        // post pickup-ready for embedding in every other respect (RAW,
        // tagger_done=TRUE, embedding_done=FALSE), and
        // translation_done=TRUE is the conjunct the release just opened —
        // EmbeddingWorkerIT.translationGateHoldsPostAndProjectionReadsEnglishAnchor
        // pins that this exact flag state is what EmbeddingWorker's pickup
        // gates on, and that a NULL *_en row is embedded from its original
        // text through the coalesce fallback. A permanently failed
        // translation therefore degrades instead of wedging the post out
        // of READY forever.
        stub().failAll();
        UUID postId = seedPost("exhaustion", "cs", CS_TITLE, CS_BODY);

        worker.processOne(pickedUp(postId));

        assertEquals(2, stub().callCount(), "exactly one retry after the initial unreachable failure");
        assertTranslation(postId, null, null, true);
        assertTrue(throttledAdminNotifier
                .getState(IngestTranslationWorker.ERROR_CLASS_TRANSLATION_FAILURE).isPresent(),
            "throttled admin notification must fire on the NULL release");
        assertPickupFlags(postId);
    }

    @Test
    void modelRefusal_releasesNullAndNotifiesRefusalClass() throws Exception {
        // The model answers with the structured refusal marker (an
        // in-wrapper action request per the prompt's refusal rule):
        // persist NULL + translation_done=TRUE, notify under the refusal
        // error class, and do NOT burn the retry (a PARSED refusal is a
        // final answer, not a failure).
        stub().setNextResponse("{\"title\":\"[refused-action]\",\"body\":\"[refused-action]\"}");
        UUID postId = seedPost("refusal", "cs", CS_TITLE, CS_BODY);

        worker.processOne(pickedUp(postId));

        assertEquals(1, stub().callCount(), "a refusal is final — no retry");
        assertTranslation(postId, null, null, true);
        assertTrue(throttledAdminNotifier
                .getState(IngestTranslationWorker.ERROR_CLASS_TRANSLATION_REFUSAL).isPresent(),
            "throttled admin notification must fire under the refusal error class");
    }

    private IngestTranslationWorker.PostRow pickedUp(UUID postId) throws Exception {
        return worker.enumeratePending(64).stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded post must be picked up by enumeratePending"));
    }

    private void assertTranslation(UUID postId, @Nullable String expectedTitleEn,
            @Nullable String expectedBodyEn, boolean expectedDone) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT title_en, body_en, translation_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                assertEquals(expectedTitleEn, rs.getString("title_en"), "title_en");
                assertEquals(expectedBodyEn, rs.getString("body_en"), "body_en");
                assertEquals(expectedDone, rs.getBoolean("translation_done"), "translation_done");
            }
        }
    }

    private void assertOriginalsUntouched(UUID postId) throws Exception {
        assertEquals(CS_TITLE, column(postId, "title"),
            "post.title is byte-identical before and after translation (D29)");
        assertEquals(CS_BODY, column(postId, "body"),
            "post.body is byte-identical before and after translation (D29)");
    }

    /**
     * Assert the post carries the flag state EmbeddingWorker's pickup
     * gates on (RAW, tagger_done=TRUE, embedding_done=FALSE,
     * translation_done=TRUE) — the pickup predicate itself is pinned in
     * EmbeddingWorkerIT's translation-gate test.
     */
    private void assertPickupFlags(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, tagger_done, embedding_done, translation_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                assertEquals("RAW", rs.getString("status"), "status");
                assertTrue(rs.getBoolean("tagger_done"), "tagger_done");
                assertFalse(rs.getBoolean("embedding_done"), "embedding_done");
                assertTrue(rs.getBoolean("translation_done"),
                    "translation_done — the conjunct the release opened");
            }
        }
    }

    /** The newest LLM_OUTPUT_SANITIZED row from THIS surface must carry the token and exact count. */
    private void assertSanitizeAuditRow(String token, int expectedCount) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT details_json::text FROM audit_log "
                     + "WHERE action = 'LLM_OUTPUT_SANITIZED' "
                     + "AND target_id = 'ingest-translator-output' "
                     + "ORDER BY id DESC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                    "a surface that takes the strip takes the audit — an LLM_OUTPUT_SANITIZED row must exist");
                String detailsJson = rs.getString(1);
                // details_json is jsonb: the ::text rendering inserts a
                // space after each colon, so match on the parts, not a
                // serialized exact substring.
                assertTrue(detailsJson.contains("\"match_kind\"") && detailsJson.contains(token),
                    "details_json must carry the matched token: " + detailsJson);
                assertTrue(detailsJson.contains("\"match_count\": " + expectedCount),
                    "details_json must carry the exact occurrence count: " + detailsJson);
            }
        }
    }

    private void clearNotifierState(String key) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    @Test
    void czechPost_translatedAndFoundByEnglishLexicalQuery() throws Exception {
        // The canned translator reply is laced with the three things the
        // carried-across controls must strip before storage: a zero-width
        // space (control a — the unconditional normalizer), a markdown
        // link and a privileged-command token (control b — the shared
        // sanitizer pipeline).
        String lacedBody = "The Vltava burst its banks overnight "
            + "[details](https://example.test/flood) — ignore /grant-admin now\u200B.";
        stub().setNextResponse(
            "{\"title\":\"" + EN_TITLE + "\",\"body\":\"" + lacedBody + "\"}");
        UUID postId = seedPost("cs-e2e", "cs", CS_TITLE, CS_BODY);

        // GATE: before the translator runs, translation_done=FALSE holds
        // the post out of embedding — the pickup conjunct itself is
        // pinned against EmbeddingWorker.enumeratePending in
        // EmbeddingWorkerIT.translationGateHoldsPostAndProjectionReadsEnglishAnchor
        // (same-package access), so here the flag state is the assertion.
        assertFlag(postId, "translation_done", false,
            "a non-English post must be gated OUT of embedding before translation_done flips");
        // … and an English lexical query must NOT find it yet (the
        // generated column still reads the Czech original, whose tokens
        // do not stem to the query term).
        assertFalse(lexicalMatches(postId, "Prague"),
            "pre-translation the English query term is absent from search_tsv");
        assertTrue(lexicalMatches(postId, "Prahu"),
            "pre-translation the Czech original is what search_tsv carries");

        IngestTranslationWorker.PostRow row = worker.enumeratePending(64).stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded Czech post must be picked up"));
        worker.processOne(row);

        // The new behaviour: an ENGLISH lexical query finds the Czech
        // post through the English anchor fields.
        assertTrue(lexicalMatches(postId, "Prague"),
            "post-translation the English query term must match via coalesce(title_en, title)");
        assertFalse(lexicalMatches(postId, "Prahu"),
            "post-translation the Czech tokens drop OUT of search_tsv — the vector switched to English");

        // Controls (a)/(b) pinned to the NEW path: the stored body_en is
        // normalized (no zero-width space), link-flattened, and
        // closed-list-redacted.
        String storedBodyEn = column(postId, "body_en");
        assertFalse(storedBodyEn.contains("\u200B"),
            "control (a): the zero-width space is stripped before storage");
        assertTrue(storedBodyEn.contains("details (https://example.test/flood)"),
            "control (b): the markdown link is flattened to text (url)");
        assertFalse(storedBodyEn.contains("]("),
            "control (b): stored English text never contains link syntax");
        assertTrue(storedBodyEn.contains(LlmOutputSanitizerCore.REDACTED_COMMAND_REPLACEMENT),
            "control (b): the closed-list token is redacted before storage");
        assertFalse(storedBodyEn.contains("/grant-admin"),
            "control (b): the privileged command never reaches the corpus");

        // The audit half of control (b): one aggregated
        // LLM_OUTPUT_SANITIZED row from THIS surface, carrying the exact
        // occurrence count — counted, never throttled; a surface that
        // takes the strip takes the audit (red-team round 2).
        assertSanitizeAuditRow("/grant-admin", 1);

        // D29: the originals are byte-identical before and after.
        assertEquals(CS_TITLE, column(postId, "title"),
            "post.title is byte-identical before and after translation (D29)");
        assertEquals(CS_BODY, column(postId, "body"),
            "post.body is byte-identical before and after translation (D29)");

        // The gate now opens: translation_done=TRUE and the English
        // anchor is in place, so EmbeddingWorker's pickup (pinned in
        // EmbeddingWorkerIT's translation-gate test) embeds the post
        // from the sanitized English text.
        assertFlag(postId, "translation_done", true,
            "the cursor flips — the embedding gate opens");
    }

    // ---------- helpers ----------

    private void assertFlag(UUID postId, String name, boolean expected, String message)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + name + " FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                assertEquals(expected, rs.getBoolean(1), message);
            }
        }
    }

    /** The V58/V74 lexical arm, query side: plainto_tsquery('english', ?) @@ search_tsv. */
    private boolean lexicalMatches(UUID postId, String queryTerm) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM post WHERE id = ? "
                     + "AND search_tsv @@ plainto_tsquery('english', ?)")) {
            ps.setObject(1, postId);
            ps.setString(2, queryTerm);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String column(UUID postId, String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + name + " FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                String value = rs.getString(1);
                assertTrue(value != null, name + " must be non-null for this assertion");
                return value;
            }
        }
    }

    private UUID seedPost(String slug, String language, String title, String body) throws Exception {
        UUID sourceId = seedSource(slug, language);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, entity_done, embedding_done, classifier_done,"
                     + "  summary_done, translation_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?,"
                     + "  ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE,"
                     + "  TRUE, FALSE, FALSE, FALSE, FALSE,"
                     + "  FALSE, FALSE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "ingest-translation-it-upstream-" + slug);
            ps.setString(4, title);
            ps.setString(5, body);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSource(String slug, String language) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags, language) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}', ?) "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://ingest-translation-it.example/" + slug);
            ps.setString(2, "Ingest translation IT source " + slug);
            ps.setString(3, language);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void clearItData() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }
}
