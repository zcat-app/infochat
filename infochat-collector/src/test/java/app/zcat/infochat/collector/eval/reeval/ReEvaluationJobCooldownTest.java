package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-attempt re-eval cooldown on
 * {@link ReEvaluationJob#enumerateCandidates} (M1-370).
 *
 * <p>The infra-failure disjunct excludes a fail-open post
 * ({@code stage2_failed=TRUE}) whose {@code last_reeval_at} is within
 * {@code infochat.reeval.cooldown}, so a post is re-judged at most once per
 * cooldown rather than every tick. The stamp rides the same transaction as the
 * {@code re_eval_attempts} increment, so a just-attempted candidate is excluded
 * on the immediately-following enumeration yet still advances toward its cap.
 */
@QuarkusTest
class ReEvaluationJobCooldownTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ReEvaluationJob reEvaluationJob;

    @Inject
    LlmProvider llmProvider;

    // Whatever the resolved profile value is, the test backdates relative to
    // it so the assertions hold for any cooldown the active profile sets.
    @ConfigProperty(name = "infochat.reeval.cooldown")
    Duration cooldown;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        // enumerateCandidates returns a LIMIT-capped, fetched_at-ordered slice
        // over the whole (shared, accumulating) test DB; clear other tests'
        // candidates first so the slice reflects exactly this test's seeds.
        clearReEvalCandidates();
    }

    @Test
    void infraFailureCandidate_excludedWithinCooldown_reEnumeratedAfterItElapses() throws Exception {
        // Three otherwise-identical in-window infra-failure posts; last_reeval_at
        // is the only discriminator. NULL = never attempted; within = just
        // attempted; beyond = cooldown elapsed.
        UUID neverAttempted = seedInfraFailurePost("never", null);
        UUID withinCooldown = seedInfraFailurePost("within", Instant.now());
        UUID beyondCooldown = seedInfraFailurePost("beyond",
            Instant.now().minus(cooldown).minus(Duration.ofMinutes(1)));

        Set<UUID> enumerated = enumeratedPostIds();

        assertTrue(enumerated.contains(neverAttempted),
            "a never-attempted (last_reeval_at IS NULL) candidate must be enumerated");
        assertFalse(enumerated.contains(withinCooldown),
            "a candidate re-judged within the cooldown must be excluded");
        assertTrue(enumerated.contains(beyondCooldown),
            "a candidate whose cooldown has elapsed must be re-enumerated");
    }

    @Test
    void attempt_stampsCooldown_excludesNextTick_thenAdvancesAndReEnumerates() throws Exception {
        // A fresh fail-open post is enumerated, then re-judged once. INJECTION
        // keeps stage2_failed=TRUE (it stays in the infra-failure class) and
        // advances the counter, while the same UPDATE stamps last_reeval_at.
        UUID postId = seedInfraFailurePost("attempt", null);
        assertTrue(enumeratedPostIds().contains(postId),
            "a never-attempted candidate must be enumerated before its first re-judge");

        stub().setNextResponse("INJECTION");
        reEvaluationJob.processOne(candidateFor(postId));

        assertEquals(1, readReEvalAttempts(postId),
            "the re-judge must advance the post toward its cap");
        assertFalse(enumeratedPostIds().contains(postId),
            "a just-attempted candidate must be excluded on the immediately-following tick");

        // Cooldown elapsed: the same post (still below its cap) becomes a
        // candidate again, so re-eval progress continues.
        backdateLastReEval(postId, cooldown.plus(Duration.ofMinutes(1)));
        assertTrue(enumeratedPostIds().contains(postId),
            "after the cooldown elapses the candidate must be re-enumerated");
        assertEquals(1, readReEvalAttempts(postId),
            "re-enumeration after the cooldown must not have lost prior progress");
    }

    // ---------- helpers ----------

    private Set<UUID> enumeratedPostIds() throws Exception {
        return reEvaluationJob.enumerateCandidates().stream()
            .map(ReEvaluationJob.ReEvalCandidate::postId)
            .collect(Collectors.toSet());
    }

    private ReEvaluationJob.ReEvalCandidate candidateFor(UUID postId) throws Exception {
        // Mirror enumerateCandidates' read for an infra-failure post: no
        // recorded verdict (the judge never ran), the seeded fetched_at, 0 attempts.
        return new ReEvaluationJob.ReEvalCandidate(postId, readFetchedAt(postId), true, 0, null);
    }

    private UUID seedInfraFailurePost(String slug, @Nullable Instant lastReEvalAt) throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts,"
                     + "  last_reeval_at"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, 'body',"
                     + "  ?, 'RAW',"
                     + "  TRUE, TRUE, TRUE, TRUE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0,"
                     + "  ?"
                     + ") RETURNING id")) {
            ps.setString(1, "reeval-cooldown-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-cooldown-" + slug);
            ps.setString(4, "Cooldown " + slug);
            // fetched_at = now(): the current-month partition, comfortably
            // inside the candidate scan window.
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.setTimestamp(6, lastReEvalAt == null ? null : Timestamp.from(lastReEvalAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[]) "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://reeval-cooldown-test.example/" + slug);
            ps.setString(2, "ReEval Cooldown " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private Instant readFetchedAt(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT fetched_at FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected seeded post " + postId);
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    private int readReEvalAttempts(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT re_eval_attempts FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private void backdateLastReEval(UUID postId, Duration age) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET last_reeval_at = now() - ?::INTERVAL WHERE id = ?")) {
            ps.setString(1, age.toSeconds() + " seconds");
            ps.setObject(2, postId);
            ps.executeUpdate();
        }
    }

    private void clearReEvalCandidates() throws Exception {
        // Match both disjuncts regardless of last_reeval_at (no cooldown leg)
        // so the slate is clean. quarantine carries no FK to post (V10), so the
        // delete is FK-safe.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post "
                     + "WHERE (stage2_failed = TRUE AND status <> 'NEEDS_REVIEW') "
                     + "   OR (status = 'QUARANTINED' AND stage2_done = TRUE AND stage2_failed = FALSE "
                     + "       AND (stage2_verdict = 'UNKNOWN' OR re_eval_attempts > 0))")) {
            ps.executeUpdate();
        }
    }
}
