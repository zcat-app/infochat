package io.infochat.collector.fetch;

import io.infochat.collector.fetcher.rss.RssFetcher;
import io.infochat.collector.outbox.EvalQueueProducer;
import io.infochat.collector.outbox.PostPersister;
import io.infochat.core.ingest.NormalizedPost;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives the per-source fetch loop for {@code kind='rss'} sources.
 *
 * <h2>Outbox discipline</h2>
 * <p>Per tick, for each enabled {@code kind='rss'} source: invoke
 * {@link RssFetcher#fetch}, then for each {@link NormalizedPost}
 * call {@link PostPersister#persist} BEFORE
 * {@link EvalQueueProducer#emit}. Persist-then-enqueue is the outbox
 * discipline per {@code docs/spec/architecture.md} §Pipelines and
 * §Architectural principles 2 — a crash between the two leaves the
 * post recoverable via {@link io.infochat.collector.outbox.OutboxRehydrator}
 * on next startup. When the persist returns a no-op (ON CONFLICT
 * dedup hit), the enqueue is skipped — the post has already been
 * emitted on a prior tick and the downstream eval state is the
 * source of truth.
 *
 * <h2>Scheduling shape</h2>
 * <p>One {@code @Scheduled} tick at the global RSS interval
 * ({@code infochat.fetch.rss.interval}, default {@code 5m}) iterates
 * every enabled {@code kind='rss'} row. Per-source cadence overrides
 * are out-of-scope per {@code docs/design/01-architecture.md} §1.6.
 * The single-tick / iterate design is observably equivalent to
 * per-source jobs under a uniform interval (every source fetches
 * once per interval); it is simpler to test (the
 * {@link #tickOnce(SourceRow)} method is directly callable by the IT).
 *
 * <h2>Startup ordering</h2>
 * <p>{@code @Priority(400)} per
 * {@code docs/design/01-architecture.md} §1.4.3 — runs after
 * Flyway (100), BootstrapLoader (200), and OutboxRehydrator (300),
 * and before any future {@code StreamSourceSupervisor} (450). The
 * rehydrator's older {@code fetched_at} posts drain ahead of new
 * fetches naturally.
 *
 * <h2>Failure handling</h2>
 * <p>Per-tick exceptions are caught and logged at WARN with the
 * {@code source.id} (the UUID — never the source identifier URL,
 * which can carry embedded credentials per M1-023's redteam
 * INFO-LEAK finding). No update to
 * {@code source.consecutive_failures} / {@code last_fetch_at} /
 * {@code last_success_at} / {@code status} — that wiring is T2-B's
 * D42 work per {@code docs/design/01-architecture.md} §1.6. T1-C's
 * failure-handling contract is "log and keep ticking".
 */
@Startup
@Priority(400)
@ApplicationScoped
public class FetchScheduler {

    private static final Logger LOG = Logger.getLogger(FetchScheduler.class);

    @Inject
    DataSource dataSource;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    // RssFetcher is @ApplicationScoped; CDI hands FetchScheduler a
    // client proxy. Tests substitute the bean via
    // QuarkusMock.installMockForType (the Quarkus-idiomatic CDI
    // replacement) so the test-mode Fetcher reaches the scheduler
    // through the same proxy. No field/setter test seam — the proxy
    // does not propagate raw field writes.
    @Inject
    RssFetcher rssFetcher;

    /**
     * Scheduled tick at the global RSS interval. The
     * {@code every = "{infochat.fetch.rss.interval}"} expression
     * resolves the property at deployment time; the default value
     * lives in {@code application.properties} alongside the other
     * Quarkus config defaults (Quarkus convention), not as an inline
     * fallback in this annotation or a {@code static final} constant
     * in source.
     */
    @Scheduled(every = "{infochat.fetch.rss.interval}")
    void onTick() {
        List<SourceRow> rows;
        try {
            rows = enumerateActiveRssSources();
        } catch (SQLException e) {
            LOG.warn("FetchScheduler: failed to enumerate rss sources; skipping tick", e);
            return;
        }
        for (SourceRow row : rows) {
            tickOnce(row);
        }
    }

    /**
     * Fetch one source's current batch, persist each post as
     * {@code 'RAW'}, then enqueue the persisted-post keys onto
     * {@code eval-queue}. Public so the IT can invoke ticks
     * deterministically without waiting on the scheduler clock.
     *
     * @param row the source row to tick (already enumerated as
     *            {@code kind='rss' AND status='active' AND
     *            deleted_at IS NULL}).
     */
    public void tickOnce(SourceRow row) {
        try {
            List<NormalizedPost> posts = rssFetcher.fetch(row.dispatchKey(), row.identifier());
            for (NormalizedPost post : posts) {
                Optional<PostPersister.PersistedPostKey> key =
                    postPersister.persist(row.uuid(), post);
                // Persist-before-enqueue per the outbox discipline.
                // On ON-CONFLICT dedup (empty), skip the enqueue —
                // the post has already been emitted on a prior tick.
                key.ifPresent(evalQueueProducer::emit);
            }
        } catch (Exception e) {
            // T1-C failure-handling: WARN-log only, no source-row
            // update (D42 wiring lives in T2-B). Log the numeric
            // dispatch key + UUID; NEVER the identifier URL (which
            // can carry embedded credentials per M1-023's redteam
            // INFO-LEAK finding).
            LOG.warnf(e,
                "FetchScheduler tick failed for source uuid=%s (dispatch=%d)",
                row.uuid(), row.dispatchKey());
        }
    }

    /**
     * Reads all enabled {@code kind='rss'} rows from {@code source}.
     * Public so the IT can re-invoke after seeding test sources
     * mid-test.
     *
     * <p>Soft-deleted rows ({@code deleted_at IS NOT NULL}) and
     * non-active rows ({@code status != 'active'}) are skipped.
     */
    public List<SourceRow> enumerateActiveRssSources() throws SQLException {
        final String sql =
            "SELECT id, identifier FROM source "
                + "WHERE kind = 'rss' "
                + "  AND status = 'active' "
                + "  AND deleted_at IS NULL "
                + "ORDER BY added_at, id";

        List<SourceRow> rows = new ArrayList<>();
        long dispatch = 1L;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = (UUID) rs.getObject(1);
                String identifier = rs.getString(2);
                rows.add(new SourceRow(id, identifier, dispatch++));
            }
        }
        return rows;
    }

    /**
     * One enumerated source row. The {@code dispatchKey} is a
     * monotonically-assigned per-startup token passed to the Fetcher
     * SPI's {@code long sourceId} parameter; it is NOT the
     * {@code source.id} UUID and is opaque to the Fetcher.
     */
    public record SourceRow(UUID uuid, String identifier, long dispatchKey) {
    }
}
