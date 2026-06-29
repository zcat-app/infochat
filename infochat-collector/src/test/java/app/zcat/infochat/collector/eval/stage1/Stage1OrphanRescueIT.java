package app.zcat.infochat.collector.eval.stage1;

import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-501: a Stage-1-flagged post left in the orphan bitmap
 * (status='RAW', stage1_done=TRUE, stage1_flagged=TRUE,
 * stage2_done=FALSE, stage2_failed=FALSE) — the state a crash or a
 * failed Stage-2 verdict write leaves behind — must be rescued by
 * {@link Stage1Worker} rather than stranded in RAW forever. The
 * stage1_done short-circuit now detects the orphan and routes it
 * through the verdict handler's INFRA_FAILURE path ("Stage 2 owed a
 * verdict but never ran"); the re-evaluation job then owns the
 * eventual re-judge via the stage2_failed flag.
 *
 * <h2>Why fail-closed</h2>
 * <p>Runs under {@code release-on-stage2-failure=false} so the rescue
 * lands the post at QUARANTINED — a crisp "no longer RAW" outcome for
 * both the orphan-convergence assertion (acceptance 1/3) and the
 * stale-RAW re-emit-loop assertion (acceptance 2). Under the base
 * fail-open default the rescue releases the post RAW (with
 * stage2_done/stage2_failed set), which is equally out of the orphan
 * bitmap but would leave the post at status='RAW', muddying the
 * "rather than remaining RAW" wording.
 *
 * <p>A second, ordinary stale-RAW post is seeded as a control: it
 * proves {@link Stage1Worker#reEmitStaleRaw} still selects genuine
 * stale-RAW posts, so the rescued orphan's exclusion is a real
 * difference and not a vacuous empty-result.
 */
@QuarkusTest
@TestProfile(Stage1OrphanRescueIT.FailClosedProfile.class)
class Stage1OrphanRescueIT {

    /**
     * Force the fail-closed Stage-2-failure posture so the orphan
     * rescue quarantines the post (see class javadoc). Triggers a fresh
     * Quarkus container; the base ITs keep the inherited fail-open
     * default.
     */
    public static class FailClosedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("infochat.security.release-on-stage2-failure", "false");
        }
    }

    private static final Instant FETCHED_AT = Instant.parse("2026-06-09T09:00:00Z");

    @Inject
    Stage1Worker worker;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TestEvalQueueConsumer consumer;

    @BeforeEach
    void drainConsumerBuffer() {
        consumer.drain();
    }

    @Test
    void orphanedStage1FlaggedPostConvergesToStage2OutcomeAndStopsLooping() throws Exception {
        UUID sourceUuid = seedRssSource();
        // status_changed_at one day old → comfortably past the base
        // stale-raw age (30m under %test), so both posts ARE stale-RAW
        // re-emit candidates before the rescue — the exact posts
        // reEmitStaleRaw would re-select. stage1_flagged distinguishes
        // the orphan (TRUE) from the ordinary in-flight control (FALSE).
        Instant oneDayOld = Instant.now().minusSeconds(86_400);
        UUID orphanId = seedStaleRawPost(sourceUuid, "orphan", /* stage1Flagged */ true, oneDayOld);
        UUID controlId = seedStaleRawPost(sourceUuid, "control", /* stage1Flagged */ false, oneDayOld);
        PostPersister.PersistedPostKey orphanKey =
            new PostPersister.PersistedPostKey(orphanId, FETCHED_AT);

        // First worker pass on the re-enqueued orphan: the stage1_done
        // short-circuit detects the orphan and routes it through the
        // verdict handler's INFRA_FAILURE path.
        worker.onPostKey(orphanKey);

        PostRow afterFirst = readPost(orphanId);
        assertTrue(afterFirst.stage2Done(),
            "orphan rescued: stage2_done is now set, so the post left the orphan bitmap");
        assertTrue(afterFirst.stage2Failed(),
            "the rescue routes through the Stage-2 infra-failure path");
        assertEquals("QUARANTINED", afterFirst.status(),
            "fail-closed rescue quarantines the post — it no longer remains stranded in RAW");

        // Repeated worker passes must not re-process or re-strand it:
        // with stage2_done=TRUE the orphan branch no longer fires, so the
        // post stays at its Stage-2 outcome.
        worker.onPostKey(orphanKey);
        PostRow afterSecond = readPost(orphanId);
        assertEquals("QUARANTINED", afterSecond.status(),
            "the post stays at its Stage-2 outcome across repeated worker passes");
        assertTrue(afterSecond.stage2Done());

        // Acceptance 2: the stale-RAW re-emit predicate no longer selects
        // the rescued orphan (it is no longer RAW), while the untouched
        // control post is still re-emitted — proving the exclusion is a
        // real state difference, not an empty sweep.
        consumer.drain();
        worker.reEmitStaleRaw();
        awaitConsumerStable();
        Set<UUID> emitted = consumer.drain().stream()
            .map(PostPersister.PersistedPostKey::id)
            .collect(Collectors.toSet());
        assertTrue(emitted.contains(controlId),
            "an ordinary stale-RAW post is still a re-emit candidate (predicate is live)");
        assertFalse(emitted.contains(orphanId),
            "a rescued (non-RAW) orphan is no longer a stale-RAW re-emit candidate");
    }

    private UUID seedStaleRawPost(UUID sourceUuid, String slug, boolean stage1Flagged,
                                  Instant statusChangedAt) throws Exception {
        // status='RAW', stage1_done=TRUE, stage2_done=FALSE, stage2_failed=FALSE.
        // stage1_flagged=TRUE is the orphan (Stage 1 flagged, Stage 2 never
        // completed); stage1_flagged=FALSE is an ordinary in-flight RAW post.
        final String sql =
            "INSERT INTO post ("
                + "  id, uid, source_id, upstream_identifier, url, title, body, "
                + "  author, published_at, fetched_at, status, status_changed_at, "
                + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                + ") VALUES ("
                + "  gen_random_uuid(), ?, ?, ?, NULL, ?, ?, NULL, NULL, ?, 'RAW', ?, "
                + "  TRUE, FALSE, FALSE, FALSE, ?, FALSE, FALSE, '{}'"
                + ") RETURNING id";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "m1-501-" + slug + "-uid");
            ps.setObject(2, sourceUuid);
            ps.setString(3, "m1-501-" + slug + "-upstream");
            ps.setString(4, "Stage1 orphan rescue IT post " + slug);
            ps.setString(5, "An ordinary headline body.");
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(7, Timestamp.from(statusChangedAt));
            ps.setBoolean(8, stage1Flagged);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedRssSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}') "
                     + "RETURNING id")) {
            ps.setString(1, "https://m1-501-orphan-it.example.test/feed.xml");
            ps.setString(2, "Stage1 orphan rescue IT source");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private PostRow readPost(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, stage2_done, stage2_failed FROM post "
                     + "WHERE id = ? AND fetched_at = ?")) {
            ps.setObject(1, postId);
            ps.setTimestamp(2, Timestamp.from(FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "seeded post present");
                return new PostRow(
                    rs.getString("status"),
                    rs.getBoolean("stage2_done"),
                    rs.getBoolean("stage2_failed"));
            }
        }
    }

    /**
     * Wait for the consumer's buffer to stop changing for one polling
     * window — the heuristic that SmallRye has delivered whatever
     * {@code reEmitStaleRaw()} emitted. Bounded at 5 seconds. Mirrors
     * {@code Stage1WorkerStaleRawReEmitterIT#awaitConsumerStable}.
     */
    private void awaitConsumerStable() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        int lastSeen = consumer.size();
        int stableTicks = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
            int now = consumer.size();
            if (now == lastSeen) {
                stableTicks++;
                if (stableTicks >= 3) {
                    return;
                }
            } else {
                lastSeen = now;
                stableTicks = 0;
            }
        }
    }

    private record PostRow(String status, boolean stage2Done, boolean stage2Failed) {
    }
}
