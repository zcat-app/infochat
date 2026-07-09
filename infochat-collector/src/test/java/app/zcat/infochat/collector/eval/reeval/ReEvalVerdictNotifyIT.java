package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.classifier.ClassifierWorker;
import app.zcat.infochat.collector.eval.embedding.EmbeddingWorker;
import app.zcat.infochat.collector.eval.entity.EntityExtractorWorker;
import app.zcat.infochat.collector.eval.ready.ReadyPromoter;
import app.zcat.infochat.collector.eval.tagger.TaggerWorker;
import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the re-evaluation verdict transitions and
 * their NOTIFY announcements (docs/spec/security.md §Re-evaluation
 * job; docs/spec/architecture.md §Inter-service communication):
 * the non-BENIGN re-hide of a released post, the BENIGN-close
 * quarantine_review emission, and the UNKNOWN→BENIGN requeue that
 * completes the tagger/entity/embedding pipeline and fires new_post.
 *
 * <p>JDBC LISTEN fixture: same shape as {@link
 * app.zcat.infochat.collector.eval.stage1.QuarantinePendingNotifyIT}.
 * Only the judge reply is queued in {@link StubLlmProvider}; the
 * tagger and entity legs ride their documented empty-queue fallback /
 * failure-release paths and the embedding leg rides the documented
 * two-failure no-vector release path. Queuing happy-path replies for
 * those stages would expose the test to the background 5s scheduler
 * ticks stealing FIFO replies for leftover posts from other test
 * classes; the fallback paths are stable under an empty queue.</p>
 */
@QuarkusTest
class ReEvalVerdictNotifyIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-07T10:00:00Z");
    private static final String UID_PREFIX = "reeval-notify-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ReEvaluationJob reEvaluationJob;

    @Inject
    LlmProvider llmProvider;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    EntityExtractorWorker entityExtractorWorker;

    @Inject
    EmbeddingWorker embeddingWorker;

    @Inject
    ClassifierWorker classifierWorker;

    @Inject
    ReadyPromoter readyPromoter;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void setup() throws Exception {
        // Pin the app-wide injected Clock so every eval stage's scan-window floor
        // (fetched_at >= scanWindowFloor(clock.instant()) = now - 32d) includes
        // the seeded FETCHED_AT regardless of the real calendar date. Without the
        // pin the absolute FETCHED_AT ages out of the rolling window and the
        // requeued pipeline post never leaves RAW (the date-boundary time-bomb
        // that fired on 2026-07-09T10:00Z; engineering-rules §9, M1-444 pattern).
        // Fixed shortly after FETCHED_AT so the post is in-window (and never
        // future) for every stage's pickup gate.
        QuarkusMock.installMockForType(
                Clock.fixed(FETCHED_AT.plus(Duration.ofHours(1)), ZoneOffset.UTC),
                Clock.class);
        stub().reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
        }
    }

    // ---------- acceptance items 1 + 2: non-BENIGN re-hide + announce ----------

    @Test
    void releasedInfraFailurePostNonBenignReEvalIsReHidden() throws Exception {
        // A Stage-2-infra-failure post released READY (user-visible with
        // redactions) that the judge now classifies INJECTION must not
        // stay visible: status → QUARANTINED, the verdict recorded, the
        // attempt counter incremented, and the re-hide announced on
        // quarantine_review in the same transaction.
        stub().setNextResponse("INJECTION");
        SeededPost post = seedReleasedInfraFailurePost("re-hide");
        UUID quarantineRowId = seedPendingQuarantineRow(post, "ph-re-hide");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            reEvaluationJob.processOne(candidateFor(post, true, 0));

            assertPostStatus(post.id, "QUARANTINED");
            assertPostField(post.id, "stage2_verdict", "INJECTION");
            assertPostField(post.id, "re_eval_attempts", 1);
            // stage2_failed preserved alongside the new verdict (spec:
            // "the stage2_failed flag is preserved").
            assertPostField(post.id, "stage2_failed", true);

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications, "the re-hide must be announced on quarantine_review");
            assertEquals(1, notifications.length, "exactly one NOTIFY for the post's open row");
            String payload = notifications[0].getParameter();
            assertTrue(payload.matches(".*\"target_kind\"\\s*:\\s*\"quarantine\".*"),
                "payload must carry target_kind=quarantine: " + payload);
            assertTrue(payload.matches(".*\"new_status\"\\s*:\\s*\"PENDING\".*"),
                "payload must carry new_status=PENDING: " + payload);
            assertTrue(payload.contains("\"target_id\":\"" + quarantineRowId + "\""),
                "payload target_id must be the post's quarantine row: " + payload);
        }
    }

    // ---------- acceptance item 3: BENIGN close emits BENIGN_CLOSED ----------

    @Test
    void benignReEvalEmitsBenignClosedNotify() throws Exception {
        stub().setNextResponse("BENIGN");
        SeededPost post = seedRawInfraFailurePost("benign-close");
        UUID quarantineRowId = seedPendingQuarantineRow(post, "ph-benign-close");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            reEvaluationJob.processOne(candidateFor(post, true, 0));

            assertQuarantineStatus(quarantineRowId, "BENIGN_CLOSED");
            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications, "the BENIGN close must be announced on quarantine_review");
            assertEquals(1, notifications.length, "exactly one NOTIFY for the closed row");
            String payload = notifications[0].getParameter();
            assertTrue(payload.matches(".*\"new_status\"\\s*:\\s*\"BENIGN_CLOSED\".*"),
                "payload must carry new_status=BENIGN_CLOSED: " + payload);
            assertTrue(payload.contains("\"target_id\":\"" + quarantineRowId + "\""),
                "payload target_id must be the closed row: " + payload);
        }
    }

    // ---------- acceptance item 4: UNKNOWN→BENIGN completes the pipeline ----------

    @Test
    void unknownBenignReEvalCompletesPipelineAndEmitsNewPost() throws Exception {
        stub().setNextResponse("BENIGN");
        SeededPost post = seedUnknownQuarantinedPost("pipeline");
        seedPendingQuarantineRow(post, "ph-pipeline");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "new_post");

            reEvaluationJob.processOne(candidateFor(post, false, 0));
            // The requeued post resumes at the next uncompleted stage
            // (Invariant 5); drive the workers explicitly rather than
            // awaiting the 5s scheduler. Tagger falls back to the
            // source's bootstrap_tags ('{ai}'), entity releases with
            // zero rows, embedding rides the two-failure no-vector
            // release — all within one tick each — then ReadyPromoter
            // flips READY and fires the suite's only new_post emit.
            taggerWorker.onTick();
            entityExtractorWorker.onTick();
            embeddingWorker.onTick();
            // The classifier is the third parallel-after-tagger stage and gates
            // RAW→READY (M1-597). No classifier reply is queued on the stub, so
            // it rides the empty-queue → {unknown} graceful release, advancing
            // classifier_done=TRUE — same "drive each stage, fall back on empty
            // stub" shape as the tagger/entity/embedding ticks above.
            classifierWorker.onTick();
            readyPromoter.onTick();

            assertPostStatus(post.id, "READY");
            assertPostHasNonEmptyTags(post.id);
            assertPostField(post.id, "entity_done", true);
            assertPostField(post.id, "embedding_done", true);
            assertQuarantineStatusForPost(post.id, "BENIGN_CLOSED");

            // A background ReadyPromoter tick may promote leftover posts
            // from other test classes in the same window, so scan for
            // THIS post's payload instead of asserting a single arrival.
            String payload = awaitNotificationContaining(pg, "\"post_id\":\"" + post.id + "\"");
            assertNotNull(payload, "a new_post NOTIFY must carry the requeued post's id");
        }
    }

    // ---------- acceptance item 7: admin approval is terminal over re-eval ----------

    @Test
    void adminApprovedReleasedPostIsNeverReEnumeratedOrReHidden() throws Exception {
        // V41: approve_quarantine clears stage2_failed, so an approved
        // (READY, span-restored) infra-failure post leaves the re-eval
        // queue for good — a later non-BENIGN roll can no longer
        // silently reverse the audited admin decision
        // (docs/spec/security.md §Quarantine workflow: redactions are
        // lifted only by /quarantine approve; admin review is the
        // terminal authority).
        SeededPost post = seedReleasedInfraFailurePost("approve-terminal");
        UUID quarantineRowId = seedPendingQuarantineRow(post, "ph-approve-terminal");
        UUID adminId = seedAdminUser("reeval-notify-it-admin");

        approveQuarantine(quarantineRowId, adminId);

        assertPostStatus(post.id, "READY");
        assertPostField(post.id, "stage2_failed", false);

        // Never re-enumerated...
        boolean enumerated = reEvaluationJob.enumerateCandidates().stream()
            .anyMatch(candidate -> candidate.postId().equals(post.id));
        assertFalse(enumerated, "an approved post must not re-enter the re-eval queue");

        // ...and a full tick with a hostile verdict queued never re-hides it.
        stub().setNextResponse("INJECTION");
        reEvaluationJob.onTick();

        assertPostStatus(post.id, "READY");
        assertPostField(post.id, "re_eval_attempts", 0);
    }

    // ---------- helpers ----------

    private ReEvaluationJob.ReEvalCandidate candidateFor(SeededPost post, boolean stage2Failed,
                                                         int attempts) throws Exception {
        // Mirrors the seeded state: infra-failure posts carry no recorded
        // verdict (the judge never ran), UNKNOWN-class posts carry 'UNKNOWN'.
        // Carry the live post.body, as enumerateCandidates folds body into
        // the candidate scan that reconstructOriginalBody reads from.
        return new ReEvaluationJob.ReEvalCandidate(post.id, post.uid, post.fetchedAt, stage2Failed, attempts,
            stage2Failed ? null : "UNKNOWN", currentBody(post));
    }

    private @Nullable String currentBody(SeededPost post) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT body FROM post WHERE id = ? AND fetched_at = ?")) {
            ps.setObject(1, post.id);
            ps.setTimestamp(2, Timestamp.from(post.fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /**
     * A genuinely released Stage-2-infra-failure post: READY and
     * user-visible, every pipeline flag TRUE, {@code stage2_failed}
     * still set — the state the re-eval queue's branch 1 enumerates
     * after release-on-stage2-failure plus a completed pipeline run.
     */
    private SeededPost seedReleasedInfraFailurePost(String slug) throws Exception {
        return seedPost(slug, "READY",
            /* stage2Failed */ true, /* taggerDone */ true, /* entityDone */ true,
            /* embeddingDone */ true, /* tags */ "{ai}", /* readyAt */ true,
            /* stage2Verdict */ null);
    }

    /** The pre-release infra-failure shape: still RAW in the pipeline. */
    private SeededPost seedRawInfraFailurePost(String slug) throws Exception {
        return seedPost(slug, "RAW",
            /* stage2Failed */ true, /* taggerDone */ false, /* entityDone */ false,
            /* embeddingDone */ false, /* tags */ "{}", /* readyAt */ false,
            /* stage2Verdict */ null);
    }

    private SeededPost seedUnknownQuarantinedPost(String slug) throws Exception {
        return seedPost(slug, "QUARANTINED",
            /* stage2Failed */ false, /* taggerDone */ false, /* entityDone */ false,
            /* embeddingDone */ false, /* tags */ "{}", /* readyAt */ false,
            /* stage2Verdict */ "UNKNOWN");
    }

    private SeededPost seedPost(String slug, String status, boolean stage2Failed,
                                boolean taggerDone, boolean entityDone, boolean embeddingDone,
                                String tags, boolean readyAt,
                                @Nullable String stage2Verdict) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID sourceId = seedRssSource(conn, slug);
            String uid = UID_PREFIX + slug;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post ("
                        + "  uid, source_id, upstream_identifier, title, body,"
                        + "  fetched_at, status, status_changed_at, ready_at,"
                        + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                        + "  tagger_done, tagger_fallback, entity_done, embedding_done,"
                        + "  tags, re_eval_attempts, stage2_verdict"
                        + ") VALUES ("
                        + "  ?, ?, ?, ?, ?,"
                        + "  ?, ?, now(), " + (readyAt ? "now()" : "NULL") + ","
                        + "  TRUE, TRUE, TRUE, ?,"
                        + "  ?, FALSE, ?, ?,"
                        + "  ?::text[], 0, ?"
                        + ") RETURNING id, fetched_at")) {
                ps.setString(1, uid);
                ps.setObject(2, sourceId);
                ps.setString(3, "reeval-notify-upstream-" + slug);
                ps.setString(4, "ReEval notify IT " + slug);
                ps.setString(5, "Body with [REDACTED:ph-" + slug + "] here");
                ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
                ps.setString(7, status);
                ps.setBoolean(8, stage2Failed);
                ps.setBoolean(9, taggerDone);
                ps.setBoolean(10, entityDone);
                ps.setBoolean(11, embeddingDone);
                ps.setString(12, tags);
                ps.setString(13, stage2Verdict);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return new SeededPost((UUID) rs.getObject(1), uid,
                        rs.getTimestamp(2).toInstant());
                }
            }
        }
    }

    private UUID seedPendingQuarantineRow(SeededPost post, String placeholderId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, now(), 'stage1',"
                     + "  'regex-test', ?, '<b>span</b>', 'PENDING'"
                     + ") RETURNING id")) {
            ps.setObject(1, post.id);
            ps.setString(2, post.uid);
            ps.setTimestamp(3, Timestamp.from(post.fetchedAt));
            ps.setString(4, placeholderId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedAdminUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                     + "VALUES ('test', ?, TRUE, 'vouched') "
                     + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE "
                     + "RETURNING id")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void approveQuarantine(UUID quarantineId, UUID actorId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT approve_quarantine(?, ?)")) {
            ps.setObject(1, quarantineId);
            ps.setObject(2, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    private UUID seedRssSource(Connection conn, String slug) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                    + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                    + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                    + "RETURNING id")) {
            ps.setString(1, "https://reeval-notify-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "ReEval notify IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void assertPostStatus(UUID postId, String expected) throws Exception {
        assertPostField(postId, "status", expected);
    }

    private void assertPostField(UUID postId, String field, Object expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + field + " FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getObject(1), "post." + field);
            }
        }
    }

    private void assertPostHasNonEmptyTags(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT cardinality(tags) FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "post.tags must be non-empty after the pipeline run");
            }
        }
    }

    private void assertQuarantineStatus(UUID quarantineId, String expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE id = ?")) {
            ps.setObject(1, quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
            }
        }
    }

    private void assertQuarantineStatusForPost(UUID postId, String expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * LISTEN on a clean slate. Pooled connections keep their LISTEN
     * registrations across pool check-ins and accumulate notifications
     * from other tests' commits, so reset the registrations and drain
     * anything already delivered before the test acts.
     */
    private static PGConnection listenTo(Connection conn, String channel) throws Exception {
        conn.setAutoCommit(true);
        try (Statement s = conn.createStatement()) {
            s.execute("UNLISTEN *");
            s.execute("LISTEN " + channel);
        }
        PGConnection pg = conn.unwrap(PGConnection.class);
        pg.getNotifications();
        return pg;
    }

    /**
     * Poll {@code getNotifications} until at least {@code minimum}
     * notifications arrive OR the bounded wait elapses. Returns the
     * accumulated array (possibly more than {@code minimum} elements)
     * or null when nothing arrived — same shape as
     * QuarantinePendingNotifyIT.
     */
    private PGNotification[] awaitNotifications(PGConnection pg, int minimum) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        List<PGNotification> collected = new ArrayList<>();
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    collected.add(n);
                }
                if (collected.size() >= minimum) {
                    return collected.toArray(new PGNotification[0]);
                }
            }
        }
        return collected.isEmpty() ? null : collected.toArray(new PGNotification[0]);
    }

    /**
     * Poll until a notification whose payload contains
     * {@code substring} arrives, or the bounded wait elapses. Returns
     * that payload or null. Tolerates unrelated notifications on the
     * same channel (e.g. leftover posts promoted by a concurrent
     * scheduler tick).
     */
    private String awaitNotificationContaining(PGConnection pg, String substring) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    if (n.getParameter().contains(substring)) {
                        return n.getParameter();
                    }
                }
            }
        }
        return null;
    }

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }
}
