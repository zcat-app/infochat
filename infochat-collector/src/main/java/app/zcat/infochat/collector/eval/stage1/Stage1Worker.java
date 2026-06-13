package app.zcat.infochat.collector.eval.stage1;

import app.zcat.infochat.collector.eval.stage2.Stage2Worker;
import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The first M1 consumer of the {@code eval-queue} channel authored
 * by M1-028's {@link app.zcat.infochat.collector.outbox.EvalQueueProducer}.
 * Picks each {@link PostPersister.PersistedPostKey} off the channel,
 * loads the post's {@code uid} + {@code body}, and invokes
 * {@link Stage1Pipeline}. The downstream M1-033 Stage 2 worker reads
 * the resulting {@link Stage1Pipeline.Stage1Result} (which carries
 * the original body for the LLM judge); the M1-034 Tagger consumer
 * reads {@code post.stage1_done} as its readiness gate.
 *
 * <h2>Short-circuit on already-done</h2>
 * <p>Per {@code docs/spec/schema.md} §Invariants Invariant 5, in-flight
 * evaluation is {@code status='RAW'} plus per-stage flag bitmap.
 * The {@link app.zcat.infochat.collector.outbox.OutboxRehydrator} may
 * re-enqueue a post mid-evaluation (e.g. after a Collector crash
 * between Stage 1 and Stage 2). The worker MUST short-circuit when
 * {@code post.stage1_done} is already TRUE so a re-enqueue does not
 * double-process Stage 1's writes (which would produce duplicate
 * {@code quarantine} rows for the same matches).
 *
 * <h2>Broadcast wiring</h2>
 * <p>The {@code eval-queue} channel fans out to multiple subscribers
 * because {@link EvalQueueProducer}'s {@code @Channel("eval-queue")}
 * emitter carries the SmallRye {@code @Broadcast} annotation. M1-028
 * left a test-scope subscriber ({@code TestEvalQueueConsumer}) on the
 * channel to assert the producer's emissions; the production worker
 * added here is the second subscriber. SmallRye Reactive Messaging
 * requires the outgoing-side {@code @Broadcast} for a multi-subscriber
 * channel; the v1 single-subscriber default would reject the wiring.
 *
 * <h2>NULL post.body</h2>
 * <p>The {@code post.body} column is nullable per V7. Production
 * Fetchers feed non-null bodies (NormalizedPost SPI contract), but
 * test seeds in preserved ITs INSERT rows with {@code body=NULL} via
 * direct JDBC, bypassing the SPI. The worker tolerates null at the
 * DB boundary; {@link Stage1Pipeline#process} coerces null → empty.
 */
@ApplicationScoped
public class Stage1Worker {

    private static final Logger LOG = Logger.getLogger(Stage1Worker.class);

    @Inject
    DataSource dataSource;

    @Inject
    Stage1Pipeline stage1Pipeline;

    /**
     * M1-033 hand-off: when Stage 1 flags a post via the regex set
     * (NOT the watchdog fail-closed path), this worker hands the
     * Stage1Result to Stage 2 in-process per
     * {@code docs/spec/security.md} §Ingest pipeline ("Stage 2 —
     * LLM judge. Only invoked when Stage 1 flagged something").
     * The alternative @Channel("stage2-queue") shape was rejected
     * in M1-033 Implementation notes — extra machinery for no
     * benefit at v1 scale.
     */
    @Inject
    Stage2Worker stage2Worker;

    @Inject
    EvalQueueProducer evalQueueProducer;

    // Profile-driven age past which a status='RAW' post is treated as
    // stranded and re-enqueued by reEmitStaleRaw. No inline defaultValue
    // — profile-driven keys carry their defaults in application.properties
    // (FetchScheduler.java:95-100 convention); the base value is the
    // test-time fallback.
    @ConfigProperty(name = "infochat.eval.stale-raw.age")
    Duration staleRawAge;

    // Per-sweep cap on the stale-RAW re-emit. A wedged eval pipeline can
    // leave the entire RAW set past the age threshold; the LIMIT bounds
    // the in-memory key list (mirroring OutboxRehydrator's page-size
    // bound) and any residual tail drains on the next sweep.
    private static final int STALE_RAW_BATCH = 500;

    /**
     * Consume one {@link PostPersister.PersistedPostKey} from
     * {@code eval-queue}, load the parent post's columns, and run
     * Stage 1. The {@code @Incoming} method's parameter type matches
     * the producer's emit shape exactly.
     *
     * <p>{@code @RunOnVirtualThread} hops each key off the emitting
     * thread. Without it, SmallRye in-memory channels run the
     * subscriber inline on the emitter's thread, so one Stage-1 regex
     * hit parks the fetch dispatcher (or the Nostr delivery loop) for
     * the full Stage-2 LLM duration — the slowest stage executing on
     * the fastest stage's thread. The annotation implies blocking
     * dispatch, one virtual thread per message, unordered. Unordered
     * is safe here: keys are independent, and a rehydrator re-enqueue
     * of an already-processed post is absorbed by the
     * {@code stage1_done} short-circuit below. Stage-2 parallelism is
     * NOT governed by this dispatch — {@code Stage2Worker}'s
     * per-profile semaphore still bounds concurrent LLM calls, and a
     * permit-holder's backoff sleep still back-pressures the queue.
     */
    @Incoming("eval-queue")
    @RunOnVirtualThread
    public void onPostKey(PostPersister.PersistedPostKey key) {
        // System-boundary catch (messaging-channel consumer). An escape
        // out of this @Incoming method either kills the subscription or
        // drops the message depending on the SmallRye failure strategy —
        // and no mp.messaging failure-strategy is configured, so the
        // outcome is version-dependent; either branch strands the post
        // and every later key. Swallow so the next key still gets
        // processed. The post stays status='RAW' and reEmitStaleRaw
        // re-enqueues it. Only the exception class name is logged: a
        // pipeline escape can carry post-body text, and the raw message
        // must not reach operator logs (docs/spec/security.md §Secrets
        // handling — User content in exceptions).
        try {
            processKey(key);
        } catch (RuntimeException e) {
            LOG.errorf(
                "Stage1Worker: evaluation failed for post_id=%s; left RAW for re-enqueue (exception=%s)",
                key.id(), e.getClass().getName());
        }
    }

    private void processKey(PostPersister.PersistedPostKey key) {
        PostRow row;
        try {
            row = loadPost(key);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Stage1Worker: failed to load post for key " + key, e);
        }
        if (row == null) {
            LOG.warnf("Stage1Worker: post not found for key %s — skipping.", key);
            return;
        }
        if (row.stage1Done) {
            // Invariant 5 re-enqueue: rehydrator may re-emit a post
            // whose Stage 1 already completed. INFO log + short
            // circuit so Stage 2 (M1-033) and Tagger (M1-034) reach
            // their downstream advance paths without doubled Stage-1
            // side effects.
            LOG.infof("Stage1Worker: post_id=%s already stage1_done; skipping.", key.id());
            return;
        }
        Stage1Pipeline.Stage1Result result =
            stage1Pipeline.process(key.id(), row.uid, key.fetchedAt(), row.body);
        // Stage 2 fires only on regex hits. The watchdog/sanitizer
        // fail-closed branches set quarantinedByWatchdog=true and
        // have already written status='QUARANTINED' — re-judging
        // them would be incorrect (those posts never reach RAW
        // again).
        if (result.flagged() && !result.quarantinedByWatchdog()) {
            stage2Worker.judge(key.id(), key.fetchedAt(), result);
        }
    }

    /**
     * Recovery half of the {@link #onPostKey} boundary swallow: re-enqueue
     * posts still stuck in {@code status='RAW'} past {@link #staleRawAge}.
     * Reuses the {@link app.zcat.infochat.collector.outbox.OutboxRehydrator}
     * query shape (the startup rehydrator scans the same {@code 'RAW'} set,
     * minus the age floor) — it does not replace it. A post still draining
     * through the eval pipeline keeps a fresh {@code status_changed_at} and
     * is skipped; only genuinely-stranded posts are re-emitted. Idempotency
     * lives at the eval-worker boundary (Invariant 5 {@code *_done} flags),
     * so re-emitting a post that is in fact mid-flight is harmless.
     */
    @Scheduled(every = "{infochat.eval.stale-raw.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reEmitStaleRaw() {
        List<PostPersister.PersistedPostKey> stale;
        try {
            stale = loadStaleRawKeys();
        } catch (SQLException e) {
            LOG.errorf("Stage1Worker: stale-RAW sweep query failed (exception=%s)",
                e.getClass().getName());
            return;
        }
        for (PostPersister.PersistedPostKey key : stale) {
            evalQueueProducer.emit(key);
        }
        if (!stale.isEmpty()) {
            LOG.infof("Stage1Worker: re-enqueued %d post(s) stuck in status='RAW' past %s",
                stale.size(), staleRawAge);
        }
    }

    private List<PostPersister.PersistedPostKey> loadStaleRawKeys() throws SQLException {
        final String sql =
            "SELECT id, fetched_at FROM post "
                + "WHERE status = 'RAW' "
                + "  AND status_changed_at < now() - ?::INTERVAL "
                + "ORDER BY fetched_at, id "
                + "LIMIT ?";
        List<PostPersister.PersistedPostKey> keys = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, staleRawAge.toSeconds() + " seconds");
            ps.setInt(2, STALE_RAW_BATCH);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(new PostPersister.PersistedPostKey(
                        (UUID) rs.getObject("id"),
                        rs.getTimestamp("fetched_at").toInstant()));
                }
            }
        }
        return keys;
    }

    private @Nullable PostRow loadPost(PostPersister.PersistedPostKey key) throws SQLException {
        final String sql =
            "SELECT uid, body, stage1_done FROM post "
                + "WHERE id = ? AND fetched_at = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, key.id());
            ps.setTimestamp(2, Timestamp.from(key.fetchedAt()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PostRow(
                    rs.getString("uid"),
                    rs.getString("body"),
                    rs.getBoolean("stage1_done"));
            }
        }
    }

    // body reflects the V7 schema: post.body is a nullable column.
    private record PostRow(String uid, @Nullable String body, boolean stage1Done) {
    }
}
