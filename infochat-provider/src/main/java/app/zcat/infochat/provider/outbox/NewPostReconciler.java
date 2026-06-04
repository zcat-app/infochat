package app.zcat.infochat.provider.outbox;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider startup catch-up reconciler for the {@code new_post} channel
 * (docs/spec/architecture.md §Inter-service communication §Catch-up;
 * docs/design/01-architecture.md §1.4.3 Startup-bean ordering;
 * docs/design/02-schema.md §2.9.2).
 *
 * <p><b>Bean ordering.</b> {@code @Startup} at {@code @Priority(250)} per the
 * Provider startup table in docs/design/01-architecture.md §1.4.3
 * (50 InstanceLockGuard → 100 Flyway → 200 AdminBootstrap → 250
 * NewPostReconciler → 300 AdapterRegistry → 400 CommandRouter). The
 * reconciler MUST run before AdapterRegistry so the first inbound message
 * after startup sees a caught-up Provider; the live LISTEN/NOTIFY worker
 * ({@link NewPostListener}) starts at priority {@code 260} so any NOTIFY
 * arriving mid-catch-up is queued behind this scan.
 *
 * <p><b>Catch-up SQL.</b> Per docs/design/02-schema.md §2.9.2:
 * <pre>{@code
 *   SELECT id, ready_at FROM post
 *    WHERE status = 'READY'
 *      AND (ready_at, id) > (:cursor_high, :cursor_low_id)
 *    ORDER BY ready_at, id;
 * }</pre>
 * The partial index {@code idx_post_ready_at ON post(ready_at, id) WHERE
 * status = 'READY'} (V7__joins_post.sql) backs the scan.
 *
 * <p><b>First-boot seed conversion.</b> V9 seeds {@code cursor_low_id = ''}
 * (the empty-string sentinel from §2.9.2). The catch-up predicate compares
 * {@code post.id} (UUID) against {@code cursor_low_id}; we convert the
 * empty sentinel to the all-zeros UUID
 * ({@code 00000000-0000-0000-0000-000000000000}) at the SQL boundary so
 * the tuple comparison is type-correct. The all-zeros UUID is strictly
 * less than any UUID Postgres' {@code gen_random_uuid()} produces (which
 * always has version-4 bits set), so the predicate selects every READY
 * row whose {@code ready_at > 'epoch'} — i.e. all READY rows — on the
 * first boot.
 *
 * <p><b>Per-row transactions.</b> Each {@link NewPostHandler#handle} call
 * opens its own JTA transaction (the handler's {@code @Transactional}
 * annotation). A bulk transaction across N rows would hold the cursor
 * row's UPDATE lock for the entire scan; a duplicate NOTIFY arriving
 * mid-catch-up would block. Per-row transactions let the CAS no-op
 * short-circuit duplicates without contention.
 */
@Startup
@Priority(250)
@ApplicationScoped
public class NewPostReconciler {

    /**
     * Sentinel used in place of the empty-string {@code cursor_low_id} seed
     * so the catch-up's tuple comparison is type-correct against
     * {@code post.id} (UUID). See class-level Javadoc.
     */
    private static final UUID ZERO_UUID =
        UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final Logger LOG = Logger.getLogger(NewPostReconciler.class);

    @Inject
    DataSource dataSource;

    @Inject
    ProviderStateDao providerStateDao;

    @Inject
    NewPostHandler newPostHandler;

    /**
     * Catch-up scan batch size. The default of 500 bounds the SQL result
     * set per page so a long-outage backlog of millions of READY rows
     * does not block @Startup arbitrarily; subsequent pages let other
     * startup beans observe progress and let a concurrent NOTIFY arrival
     * see a partially-advanced cursor. Operator override via
     * {@code -Dinfochat.provider.catchup.page-size=N} or environment
     * variable; the default lives in the annotation per the M1-030
     * files_scope constraint.
     */
    @Inject
    @ConfigProperty(name = "infochat.provider.catchup.page-size", defaultValue = "500")
    int pageSize;

    private int caughtUpCount;
    private int pagesProcessed;
    // Cursor watermarks of the catch-up range, assigned by runCatchUp (driven
    // from @PostConstruct) before any read; the field-init check cannot see
    // the @PostConstruct-time initialization.
    @SuppressWarnings("NullAway.Init")
    private Instant startCursorHigh;
    @SuppressWarnings("NullAway.Init")
    private String startCursorLowId;
    @SuppressWarnings("NullAway.Init")
    private Instant endCursorHigh;
    @SuppressWarnings("NullAway.Init")
    private String endCursorLowId;

    @PostConstruct
    void onStartup() {
        try {
            runCatchUp();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "NewPostReconciler failed to complete catch-up scan", e);
        }
    }

    void runCatchUp() throws SQLException {
        // Reset per-run so caughtUpCount() reports the latest invocation's
        // delta — used by NewPostReconcilerIT to assert per-run idempotency
        // (the second call must count zero additional handler invocations).
        caughtUpCount = 0;
        pagesProcessed = 0;

        Optional<ProviderStateDao.Cursor> maybeCursor =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        if (maybeCursor.isEmpty()) {
            throw new IllegalStateException(
                "provider_state row for channel='new_post' is missing — "
                    + "V9__provider_state.sql first-boot INSERT did not apply");
        }
        ProviderStateDao.Cursor cursor = maybeCursor.get();
        startCursorHigh = cursor.cursorHigh();
        startCursorLowId = cursor.cursorLowId();

        // Local paging cursor: tracks the highest (ready_at, id) observed
        // in the current scan and serves as the SQL predicate's lower
        // bound for the NEXT page. The persistent cursor
        // (provider_state.cursor_high, cursor_low_id) is what each handler
        // call advances atomically with its side effect; the local cursor
        // mirrors that monotonic advance so successive pages skip rows we
        // just processed without re-reading provider_state mid-scan.
        Instant pagingHigh = cursor.cursorHigh();
        UUID pagingLowId = cursor.cursorLowId().isEmpty()
            ? ZERO_UUID
            : UUID.fromString(cursor.cursorLowId());

        while (true) {
            int rowsInPage = 0;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, ready_at FROM post "
                         + "WHERE status = 'READY' "
                         + "  AND (ready_at, id) > (?, ?) "
                         + "ORDER BY ready_at, id "
                         + "LIMIT ?")) {
                ps.setTimestamp(1, java.sql.Timestamp.from(pagingHigh));
                ps.setObject(2, pagingLowId);
                ps.setInt(3, pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID postId = rs.getObject("id", UUID.class);
                        Instant readyAt = rs.getTimestamp("ready_at").toInstant();
                        newPostHandler.handle(postId, readyAt);
                        pagingHigh = readyAt;
                        pagingLowId = postId;
                        rowsInPage++;
                        caughtUpCount++;
                    }
                }
            }
            pagesProcessed++;
            // Loop exit: a partial page means the SELECT exhausted the
            // backlog. Equal-to-pageSize means there MIGHT be more rows,
            // so a subsequent page is issued; the final empty page closes
            // the loop.
            if (rowsInPage < pageSize) {
                break;
            }
        }

        Optional<ProviderStateDao.Cursor> after =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        endCursorHigh = after.map(ProviderStateDao.Cursor::cursorHigh).orElse(startCursorHigh);
        endCursorLowId = after.map(ProviderStateDao.Cursor::cursorLowId).orElse(startCursorLowId);

        LOG.infof(
            "NewPostReconciler: caught up %d posts in %d page(s) from cursor=(ready_at=%s, id=%s) "
                + "to cursor=(ready_at=%s, id=%s)",
            caughtUpCount, pagesProcessed, startCursorHigh, startCursorLowId,
            endCursorHigh, endCursorLowId);
    }

    /** Test-visible accessor — number of rows processed by the most recent run. */
    public int caughtUpCount() {
        return caughtUpCount;
    }

    /**
     * Test-visible accessor — number of pages issued by the most recent
     * run. Used by NewPostReconcilerPagingIT to assert the catch-up
     * actually pages when the backlog exceeds {@code pageSize}.
     */
    public int pagesProcessed() {
        return pagesProcessed;
    }
}
