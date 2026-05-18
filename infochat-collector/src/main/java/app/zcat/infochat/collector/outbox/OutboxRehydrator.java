package app.zcat.infochat.collector.outbox;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * On startup, scans {@code post} for rows with {@code status='RAW'}
 * and re-emits each row's {@code (id, fetched_at)} key onto the
 * {@code eval-queue} channel via the shared {@link EvalQueueProducer}.
 * This is the crash-recovery half of the outbox discipline per
 * {@code docs/spec/architecture.md} §Architectural principles 2
 * ("Outbox + LISTEN/NOTIFY + high-water mark. Postgres provides
 * durability and push semantics without an external broker.")
 *
 * <h2>Predicate</h2>
 * <p>The scan reads {@code WHERE status='RAW'} ONLY. Per
 * {@code docs/spec/schema.md} §Invariants Invariant 5, {@code 'RAW'}
 * IS the in-flight marker; there is no distinct {@code 'EVALUATING'}
 * status — the per-stage {@code *_done} flag bitmap tells the
 * downstream eval workers WHERE to restart. The rehydrator's
 * predicate does NOT look at the flags; it scans the live
 * {@code 'RAW'} set on every call.
 *
 * <h2>Ordering</h2>
 * <p>The {@code ORDER BY (fetched_at, id)} clause makes the
 * re-enqueue deterministic across crashes — the partition order
 * matches the natural insertion order, and the row {@code id}
 * tie-breaks any same-{@code fetched_at} cluster.
 *
 * <h2>Idempotency</h2>
 * <p>There is no "rehydrated" flag on the post row. The rehydrator
 * re-emits the entire {@code 'RAW'} set on every call. Idempotency
 * is at the eval-worker boundary — T1-D's stage 1/2/tagger/embedding
 * workers read each {@code *_done} flag and skip stages already
 * completed per Invariant 5. Re-enqueueing the same {@code 'RAW'}
 * post twice produces one logical evaluation pass (the second
 * enqueue may double work briefly, but each stage's
 * idempotent-by-{@code *_done}-flag check prevents double execution).
 *
 * <h2>Startup ordering</h2>
 * <p>{@code @Priority(300)} runs after {@code InstanceLockGuard}
 * (50), Flyway (100), and {@code BootstrapLoader} (200), and BEFORE
 * {@code FetchScheduler} (400) per
 * {@code docs/design/01-architecture.md} §1.4.3. Prior-run RAW posts
 * are in the eval queue BEFORE the scheduler starts adding new ones,
 * so their older {@code fetched_at} naturally drains first.
 */
@Startup
@Priority(300)
@ApplicationScoped
public class OutboxRehydrator {

    private static final Logger LOG = Logger.getLogger(OutboxRehydrator.class);

    @Inject
    DataSource dataSource;

    @Inject
    EvalQueueProducer evalQueueProducer;

    @PostConstruct
    void onStartup() {
        rehydrate();
    }

    /**
     * Scans {@code WHERE status='RAW'} and emits one
     * {@link PostPersister.PersistedPostKey} to {@code eval-queue}
     * per row, in {@code (fetched_at, id)} order. Exposed
     * (non-private) so the IT can re-invoke the rehydrator to assert
     * re-run idempotency and the shrink-on-status-change behavior.
     *
     * <h2>Connection lifetime</h2>
     * <p>The scan collects {@link PostPersister.PersistedPostKey}s
     * into a {@link List} INSIDE the try-with-resources block, then
     * emits to the channel AFTER the block has released the JDBC
     * {@link Connection}. SmallRye's in-memory channel applies
     * back-pressure on a saturated buffer (the default overflow
     * strategy is {@code BUFFER}; {@code emitter.send} blocks until
     * a slot frees). Emitting inside the scan loop would pin the
     * Connection slot for the duration of the back-pressure stall,
     * starving the Agroal pool against the live serving path. The
     * memory cost of the list is bounded by the count of in-flight
     * RAW posts (one {@link java.util.UUID} + one
     * {@link Instant} per row).
     *
     * @return the number of posts re-enqueued.
     */
    public int rehydrate() {
        final String sql =
            "SELECT id, fetched_at FROM post "
                + "WHERE status = 'RAW' "
                + "ORDER BY fetched_at, id";

        List<PostPersister.PersistedPostKey> keys = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                UUID id = (UUID) rs.getObject(1);
                Instant fetchedAt = rs.getTimestamp(2).toInstant();
                keys.add(new PostPersister.PersistedPostKey(id, fetchedAt));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "OutboxRehydrator: scan of status='RAW' posts failed", e);
        }

        // Connection released. Back-pressure on the eval-queue channel
        // now blocks the rehydrator without pinning a DB connection.
        for (PostPersister.PersistedPostKey key : keys) {
            evalQueueProducer.emit(key);
        }

        LOG.infof("OutboxRehydrator: re-enqueued %d RAW posts from prior run.", keys.size());
        return keys.size();
    }
}
