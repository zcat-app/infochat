package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.stage2.Stage2VerdictHandler;
import app.zcat.infochat.collector.notify.QuarantineNotifyEmitter;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-tick infra-failure fan-out bound (M1-342). Drives the real
 * {@link ReEvaluationJob#onTick} over a multi-candidate infra-failure backlog
 * with a {@link CountingStage2Worker} stub and asserts the provider-down
 * latch: when the worker keeps returning INFRA_FAILURE, exactly one
 * {@code judgeBody} call is issued per tick and the remaining candidates are
 * deferred; when it returns a normal verdict, every candidate is processed.
 *
 * <p>{@code ReEvaluationJob} is {@code @ApplicationScoped}, so an injected
 * reference is a CDI proxy whose field writes never reach the delegate. The
 * job under test is therefore hand-assembled from injected collaborators so
 * the {@code stage2Worker} seam can be swapped for the counting stub. Each
 * test neutralizes any pre-existing re-eval candidates from sibling classes
 * sharing the Dev Services database (a flag flip, not a delete — FK-safe) so
 * the seeded backlog is the entire candidate set.
 */
@QuarkusTest
class ReEvaluationJobInfraFailureFanOutIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    QuarantineNotifyEmitter quarantineNotifyEmitter;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    AuditLogWriter auditLogWriter;

    @ConfigProperty(name = "infochat.reeval.infra-failure-cap")
    int infraFailureCap;

    @ConfigProperty(name = "infochat.reeval.unknown-cap")
    int unknownCap;

    @ConfigProperty(name = "infochat.reeval.batch-size")
    int batchSize;

    @ConfigProperty(name = "infochat.reeval.needs-review-depth-threshold")
    int needsReviewDepthThreshold;

    @ConfigProperty(name = "infochat.reeval.cooldown")
    Duration reEvalCooldown;

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    private CountingStage2Worker stubWorker;
    private ReEvaluationJob job;

    @BeforeEach
    void setUp() throws Exception {
        neutralizeExistingCandidates();
        stubWorker = new CountingStage2Worker();
        job = new ReEvaluationJob();
        job.dataSource = dataSource;
        job.stage2Worker = stubWorker;
        job.quarantineNotifyEmitter = quarantineNotifyEmitter;
        job.throttledAdminNotifier = throttledAdminNotifier;
        job.auditLogWriter = auditLogWriter;
        job.infraFailureCap = infraFailureCap;
        job.unknownCap = unknownCap;
        job.batchSize = batchSize;
        job.needsReviewDepthThreshold = needsReviewDepthThreshold;
        job.reEvalCooldown = reEvalCooldown;
        job.postRetentionDays = postRetentionDays;
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteSeededPosts();
    }

    @Test
    void sustainedOutage_boundsToOneJudgeCallPerTick() throws Exception {
        List<UUID> postIds = List.of(
            seedInfraFailurePost("fanout-down-a"),
            seedInfraFailurePost("fanout-down-b"),
            seedInfraFailurePost("fanout-down-c"));
        stubWorker.setVerdict(Stage2VerdictHandler.Verdict.INFRA_FAILURE);

        job.onTick();

        assertEquals(1, stubWorker.judgeBodyCallCount(),
            "a tick over a multi-candidate infra-failure backlog must issue at most one "
                + "judgeBody call: the first INFRA_FAILURE latches providerDown and the "
                + "remaining candidates defer to the next tick");
        // No counter advances: INFRA_FAILURE never increments re_eval_attempts,
        // and the deferred candidates were never judged.
        for (UUID postId : postIds) {
            assertReEvalAttempts(postId, 0);
        }
    }

    @Test
    void healthyProvider_judgesEveryCandidate_noLatch() throws Exception {
        List<UUID> postIds = List.of(
            seedInfraFailurePost("fanout-up-a"),
            seedInfraFailurePost("fanout-up-b"),
            seedInfraFailurePost("fanout-up-c"));
        stubWorker.setVerdict(Stage2VerdictHandler.Verdict.BENIGN);

        job.onTick();

        assertEquals(postIds.size(), stubWorker.judgeBodyCallCount(),
            "a healthy provider must not short-circuit the tick: every candidate is judged");
        // Each candidate was released as before: stage2_failed cleared and the
        // BENIGN release counted one attempt — proof none were skipped.
        for (UUID postId : postIds) {
            assertStage2Failed(postId, false);
            assertReEvalAttempts(postId, 1);
        }
    }

    // ---------- helpers ----------

    /**
     * Push every currently-enumerable re-eval candidate out of both scan
     * branches so {@link ReEvaluationJob#enumerateCandidates} returns only the
     * posts this test seeds. Flag flips only (no DELETE) keep the shared
     * partitioned {@code post} table's FKs intact; the affected rows are
     * sibling-class residue whose own assertions have already run.
     */
    private void neutralizeExistingCandidates() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            // Infra-failure branch: stage2_failed = TRUE.
            st.executeUpdate("UPDATE post SET stage2_failed = FALSE WHERE stage2_failed = TRUE");
            // UNKNOWN branch: QUARANTINED + stage2_done + (verdict UNKNOWN OR attempts > 0).
            st.executeUpdate(
                "UPDATE post SET stage2_verdict = 'BENIGN', re_eval_attempts = 0 "
                    + "WHERE status = 'QUARANTINED' AND stage2_done = TRUE "
                    + "AND (stage2_verdict = 'UNKNOWN' OR re_eval_attempts > 0)");
        }
    }

    private UUID seedInfraFailurePost(String slug) throws Exception {
        UUID sourceId = seedSource(slug);
        // fetched_at = now() so the fixture always sits inside the retention +
        // slack scan window regardless of the wall-clock date the suite runs.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, 'body',"
                     + "  now(), 'RAW',"
                     + "  TRUE, TRUE, TRUE, TRUE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, "reeval-fanout-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-fanout-" + slug);
            ps.setString(4, "ReEval FanOut " + slug);
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
            ps.setString(1, "https://reeval-fanout.example/" + slug);
            ps.setString(2, "ReEval FanOut " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void deleteSeededPosts() throws Exception {
        // The seeded posts carry no quarantine / embedding / entity children,
        // so a direct delete is FK-safe; removing them keeps this class's
        // infra-failure rows from leaking into sibling tests' candidate scans.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE upstream_identifier LIKE 'upstream-fanout-%'")) {
            ps.executeUpdate();
        }
    }

    private void assertReEvalAttempts(UUID postId, int expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT re_eval_attempts FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getInt(1));
            }
        }
    }

    private void assertStage2Failed(UUID postId, boolean expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT stage2_failed FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getBoolean(1));
            }
        }
    }
}
