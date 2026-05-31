package app.zcat.infochat.collector.outbox;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
 * <h2>Pagination (M1-042)</h2>
 * <p>The scan is keyset-paginated on {@code (fetched_at, id)} with a
 * configurable page size ({@code infochat.collector.outbox.rehydrate-page-size},
 * default 500 — matching M1-030's catch-up page size). Each chunk
 * acquires an Agroal connection, reads at most {@code pageSize} rows
 * into a bounded {@link List}, then releases the connection BEFORE
 * emitting to the channel. This keeps two invariants simultaneously:
 * <ul>
 *   <li><b>Memory bounded.</b> The {@link List} holds at most
 *       {@code pageSize} keys at any moment, so a steady-state RAW
 *       backlog of N posts (where N can be in the millions during a
 *       sustained eval-pipeline stall) does not require an N-row
 *       in-memory allocation at startup. Pre-M1-042, the rehydrator
 *       collected the entire RAW set into one unbounded
 *       {@link ArrayList} before emitting — an OUT-OF-MODEL
 *       defense-in-depth gap flagged by M1-028's redteam audit.</li>
 *   <li><b>Connection released before per-chunk emit.</b> SmallRye's
 *       in-memory channel applies back-pressure on a saturated buffer
 *       (the default overflow strategy is {@code BUFFER};
 *       {@code emitter.send} blocks until a slot frees). Emitting
 *       inside the JDBC scan loop would pin the Connection slot for
 *       the duration of the back-pressure stall, starving the Agroal
 *       pool against the live serving path. The per-chunk
 *       acquire/scan/release/emit cycle preserves the M1-028 design
 *       invariant at the chunk granularity.</li>
 * </ul>
 *
 * <h2>Keyset cursor invariant</h2>
 * <p>The first chunk runs {@code WHERE status='RAW' ORDER BY ...
 * LIMIT pageSize}. Subsequent chunks advance the cursor via row
 * comparison: {@code WHERE status='RAW' AND (fetched_at, id) > (?, ?)
 * ORDER BY ... LIMIT pageSize}, where the bookmark is the last
 * emitted chunk's tail row. PostgreSQL evaluates row comparison
 * lexicographically, matching the {@code ORDER BY (fetched_at, id)}
 * sort — so the next chunk starts strictly after the prior chunk's
 * tail with no gaps and no duplicates. The loop terminates when a
 * chunk returns fewer than {@code pageSize} rows (the residual
 * tail) or the chunk is empty (the RAW set was an exact multiple of
 * {@code pageSize}).
 *
 * <h2>Concurrent inserts during pagination</h2>
 * <p>The rehydrator runs at {@code @Priority(300)} BEFORE the
 * {@link app.zcat.infochat.collector.fetch.FetchScheduler}'s
 * {@code @Priority(400)} — so no FetchScheduler-driven INSERTs land
 * during the rehydrate loop. If a future ticket lets some other
 * @Startup bean INSERT RAW rows mid-rehydrate, the keyset cursor
 * will pick up rows whose {@code (fetched_at, id)} sorts AFTER the
 * current bookmark (the natural ordering of arriving rows). Rows
 * sorting BEFORE the bookmark would be missed — relied on by the
 * eval pipeline's idempotency: the eval workers' per-stage
 * {@code *_done} flag check (Invariant 5) makes a missed re-enqueue
 * acceptable only because the original enqueue path
 * ({@link app.zcat.infochat.collector.fetch.FetchScheduler#tickOnce})
 * has not yet started. Maintain the @Priority gap when adding new
 * @Startup beans that touch the post table.
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

    /**
     * Operator-facing configuration key for the rehydrate page size.
     * Default 500 matches M1-030's catch-up page size. Reducing this
     * bounds per-chunk memory tighter at the cost of more round
     * trips; raising it amortizes the per-chunk acquire/release
     * overhead but raises the per-chunk allocation. Operators should
     * not need to tune this — surface the key for emergency profiles
     * (e.g. memory-constrained Pi deployments).
     */
    static final String CONFIG_KEY_PAGE_SIZE = "infochat.collector.outbox.rehydrate-page-size";

    @Inject
    DataSource dataSource;

    @Inject
    EvalQueueProducer evalQueueProducer;

    @ConfigProperty(name = CONFIG_KEY_PAGE_SIZE, defaultValue = "500")
    int rehydratePageSize;

    /**
     * Test seam backing field: the maximum chunk size observed during
     * the most recent {@link #rehydrate()} call. Reset to zero at the
     * start of each rehydrate. The field is private so callers MUST
     * route through {@link #lastObservedMaxChunkSize()} — the CDI
     * client proxy that an injected {@link OutboxRehydrator} reference
     * resolves to in @QuarkusTest fixtures forwards method calls to
     * the underlying bean but reads the proxy's own (uninitialized)
     * fields on direct field access. The accessor is the only safe
     * read path from a {@code @Inject}'d handle.
     */
    private int lastObservedMaxChunkSize;

    @PostConstruct
    void onStartup() {
        rehydrate();
    }

    /**
     * Scans {@code WHERE status='RAW'} in keyset-paginated chunks and
     * emits one {@link PostPersister.PersistedPostKey} per row in
     * {@code (fetched_at, id)} order. Exposed (non-private) so the IT
     * can re-invoke the rehydrator to assert re-run idempotency and
     * the shrink-on-status-change behavior.
     *
     * @return the number of posts re-enqueued.
     */
    public int rehydrate() {
        int pageSize = rehydratePageSize;
        if (pageSize <= 0) {
            throw new IllegalStateException(
                "OutboxRehydrator: " + CONFIG_KEY_PAGE_SIZE
                    + " must be > 0; got " + pageSize);
        }

        int observedMaxChunk = 0;
        int totalProcessed = 0;
        Instant bookmarkFetchedAt = null;
        UUID bookmarkId = null;

        while (true) {
            List<PostPersister.PersistedPostKey> chunk =
                loadChunk(bookmarkFetchedAt, bookmarkId, pageSize);
            if (chunk.size() > observedMaxChunk) {
                observedMaxChunk = chunk.size();
            }
            if (chunk.isEmpty()) {
                break;
            }
            // Connection released by loadChunk's try-with-resources.
            // Back-pressure on eval-queue now blocks only this chunk's
            // emit, not the next chunk's DB acquire.
            for (PostPersister.PersistedPostKey key : chunk) {
                evalQueueProducer.emit(key);
            }
            PostPersister.PersistedPostKey tail = chunk.get(chunk.size() - 1);
            bookmarkFetchedAt = tail.fetchedAt();
            bookmarkId = tail.id();
            totalProcessed += chunk.size();
            if (chunk.size() < pageSize) {
                // Residual tail chunk — the keyset cursor has reached
                // the end of the RAW set. Skip the empty-probe round
                // trip the next iteration would otherwise perform.
                break;
            }
        }

        this.lastObservedMaxChunkSize = observedMaxChunk;
        LOG.infof(
            "OutboxRehydrator: re-enqueued %d RAW posts from prior run "
                + "(page size %d, max chunk observed %d).",
            totalProcessed, pageSize, observedMaxChunk);
        return totalProcessed;
    }

    /**
     * Test seam accessor: see {@link #lastObservedMaxChunkSize} field
     * doc for the CDI-proxy rationale. Package-private —
     * {@code OutboxRehydratorPaginationIT} reads this to assert the
     * per-chunk memory-bound invariant.
     */
    int lastObservedMaxChunkSize() {
        return lastObservedMaxChunkSize;
    }

    /**
     * Load one keyset-paginated chunk of up to {@code pageSize}
     * {@code (id, fetched_at)} rows whose {@code (fetched_at, id)}
     * sorts STRICTLY AFTER the supplied bookmark, or the leading
     * chunk when {@code bookmarkFetchedAt} is {@code null}. The
     * connection is acquired and released inside this method's
     * try-with-resources block — the returned list is the only
     * artifact that outlives the connection.
     */
    private List<PostPersister.PersistedPostKey> loadChunk(
            @Nullable Instant bookmarkFetchedAt,
            @Nullable UUID bookmarkId,
            int pageSize) {
        final boolean firstChunk = bookmarkFetchedAt == null;
        final String sql = firstChunk
            ? "SELECT id, fetched_at FROM post "
                + "WHERE status = 'RAW' "
                + "ORDER BY fetched_at, id "
                + "LIMIT ?"
            : "SELECT id, fetched_at FROM post "
                + "WHERE status = 'RAW' "
                + "  AND (fetched_at, id) > (?, ?) "
                + "ORDER BY fetched_at, id "
                + "LIMIT ?";

        List<PostPersister.PersistedPostKey> rows = new ArrayList<>(pageSize);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (firstChunk) {
                ps.setInt(1, pageSize);
            } else {
                ps.setTimestamp(1, Timestamp.from(bookmarkFetchedAt));
                ps.setObject(2, bookmarkId);
                ps.setInt(3, pageSize);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    Instant fetchedAt = rs.getTimestamp(2).toInstant();
                    rows.add(new PostPersister.PersistedPostKey(id, fetchedAt));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "OutboxRehydrator: chunk scan failed "
                    + "(bookmarkFetchedAt=" + bookmarkFetchedAt
                    + ", bookmarkId=" + bookmarkId
                    + ", pageSize=" + pageSize + ")",
                e);
        }
        return rows;
    }
}
