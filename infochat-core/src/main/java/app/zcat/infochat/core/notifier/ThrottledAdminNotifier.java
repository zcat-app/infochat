package app.zcat.infochat.core.notifier;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * T2-G throttled admin notifier per docs/spec/schema.md §Operational
 * ("Admin notification state — backing store for the throttled admin
 * notifier (decision D22)") and docs/spec/security.md §Failure
 * handling (decision D42's per-source failure-coalescing). Callers
 * invoke {@link #notifyOnce(String, String, String)} on every
 * failure occurrence; the
 * notifier emits at most one WARN log line per
 * {@code (notification_key, throttle-window)} pair, persists row-
 * level counters to {@code admin_notification_state}, and routes
 * subsequent within-window calls to a SUPPRESSED outcome that bumps
 * the per-key {@code suppressed_count}.
 *
 * <h2>Race safety</h2>
 * <p>A single {@code INSERT ... ON CONFLICT (notification_key)
 * DO UPDATE} with a conditional WHERE on
 * {@code last_notified_at + window <= EXCLUDED.last_notified_at}
 * serializes concurrent first-time callers for the same key:
 * Postgres acquires a row-level lock on the conflicting row, the
 * first INSERT wins (returns EMITTED), and subsequent UPDATEs see
 * the just-refreshed timestamp and route to SUPPRESSED. The
 * RETURNING expression encodes the decision in a boolean so Java
 * does not race-read the row in a follow-up SELECT.</p>
 *
 * <h2>Delivery in v1</h2>
 * <p>"Delivery" today is the WARN log line + the persisted row.
 * Operators tail logs (canonical format
 * {@code ADMIN-NOTIFY key=... error=... message=...} for log
 * scraping) and may query {@code admin_notification_state} for
 * counter summaries. A future {@code AdminNotificationDelivery}
 * SPI (push to SimpleX DM / email / etc.) is out of scope.</p>
 *
 * <h2>Clock seam</h2>
 * <p>{@link Clock} is consumed via CDI; the {@link #systemUtcClock()}
 * producer below ships in this file so production gets
 * {@link Clock#systemUTC()} without a separate bean class.
 * {@code @QuarkusTest} callers substitute a fixed or mutable clock
 * via {@code QuarkusMock.installMockForType(mutableClock,
 * Clock.class)} — the same Quarkus-idiomatic CDI swap pattern
 * FetchScheduler uses for its {@code RssFetcher} test seam.</p>
 */
@ApplicationScoped
public class ThrottledAdminNotifier {

    private static final Logger LOG = Logger.getLogger(ThrottledAdminNotifier.class);

    // Sanitization caps for notifyOnce inputs. Key + error_class are
    // bounded tight because both persist into admin_notification_state
    // and the table grows monotonically (DBA TRUNCATE is the only
    // recovery, per the spec); an attacker-influenced key without a
    // cap would inflate the row count without bound. Message lives
    // only in the log line — looser cap, but still bounded so a single
    // ADMIN-NOTIFY line cannot exceed a few KB.
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_ERROR_CLASS_LENGTH = 256;
    private static final int MAX_MESSAGE_LENGTH = 2048;
    private static final String TRUNCATION_SUFFIX = "...[truncated]";

    // Canonical notification key for the degraded-DB fallback. Pinned
    // so operator scrapes keyed on `grep ADMIN-NOTIFY` catch the
    // notifier-cannot-persist case on the same pattern as ordinary
    // notifications.
    private static final String PERSISTENCE_FAILED_KEY = "admin-notifier-persistence-failed";

    /**
     * CDI producer for the production {@link Clock}. Static so CDI
     * does not need to instantiate {@link ThrottledAdminNotifier} to
     * resolve the producer — the {@code @Inject Clock} field below
     * receives the produced singleton, not a self-reference.
     */
    @Produces
    @ApplicationScoped
    static Clock systemUtcClock() {
        return Clock.systemUTC();
    }

    /**
     * Defend the ADMIN-NOTIFY log line and the persisted row against
     * externally-influenced inputs. Replaces every C0 control
     * character (0x00–0x1F) with a single space — CR/LF would break
     * the line-boundary semantics any operator grep relies on (a
     * future caller forwarding feed-body text or a driver-supplied
     * error message cannot forge a second ADMIN-NOTIFY line), and
     * ESC (0x1B) would open an ANSI escape sequence that can forge
     * or visually overwrite terminal output when an operator scrapes
     * the log — and caps the result at {@code maxLen}, appending
     * {@link #TRUNCATION_SUFFIX} when truncation fires so the trim
     * is visible to a reader.
     *
     * <p>Applied at the boundary between caller-supplied strings and
     * the log/DB sinks per CLAUDE.md §"No defensive code" — the
     * notifier owns the ADMIN-NOTIFY scrape contract; the cost of
     * sanitizing once at the entry point is one method call, the
     * cost of trusting every future caller to pre-sanitize is a
     * forgery vulnerability the moment one caller forgets.</p>
     */
    private static String sanitize(String s, int maxLen) {
        // Full C0 sweep, not an enumerated blacklist: every control
        // below 0x20 either breaks the one-line scrape contract or is
        // a terminal-control character with no legitimate place in a
        // key/error/message; replacing the whole range leaves no gaps.
        StringBuilder stripped = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            stripped.append(c < 0x20 ? ' ' : c);
        }
        if (stripped.length() <= maxLen) {
            return stripped.toString();
        }
        int keep = Math.max(0, maxLen - TRUNCATION_SUFFIX.length());
        return stripped.substring(0, keep) + TRUNCATION_SUFFIX;
    }

    @ConfigProperty(name = "infochat.admin-notifier.throttle-window", defaultValue = "1h")
    Duration throttleWindow;

    @Inject
    Clock clock;

    @Inject
    DataSource dataSource;

    /**
     * Single-statement UPSERT built once at {@link #init} with the
     * configured throttle window inlined as a Postgres
     * {@code INTERVAL} literal. The window is a runtime-fixed
     * config value (no SQL-injection risk —
     * {@link Duration#toMillis()} is a {@code long}), so inlining
     * is preferred over a {@code ?::interval} bind.
     *
     * <p><b>Discriminator: row presence in RETURNING.</b>
     * {@code ON CONFLICT DO UPDATE ... WHERE <window-elapsed>}
     * runs the UPDATE only when the conditional is true; if false,
     * the row is locked but no SET applies and RETURNING produces
     * NO ROW. So the Java side observes:
     * <ul>
     *   <li>RETURNING returned a row + {@code xmax = 0} → fresh
     *       INSERT (first time for this key) → EMITTED.</li>
     *   <li>RETURNING returned a row + {@code xmax != 0} → UPDATE
     *       fired (window had elapsed) → EMITTED.</li>
     *   <li>RETURNING returned NO ROW → CONFLICT but WHERE filtered
     *       out the UPDATE → within-window → SUPPRESSED.</li>
     * </ul>
     * The suppressed-branch follow-up
     * ({@link #SUPPRESSED_BUMP_SQL}) increments
     * {@code suppressed_count} as a separate atomic UPDATE.</p>
     */
    // Assigned in the @PostConstruct init() before any notifyOnce() can run.
    // NullAway's field-init check models only constructors/initializers, not
    // @PostConstruct, so suppress that one check here; the field stays non-null
    // for every dereference.
    @SuppressWarnings("NullAway.Init")
    private String upsertSql;

    /**
     * Suppressed-branch follow-up: bump {@code suppressed_count}
     * by 1 for an already-existing row. Run only when {@link
     * #upsertSql}'s RETURNING produced no row. Atomic at the row
     * level — concurrent SUPPRESSED callers add up correctly to
     * N-1 increments.
     */
    private static final String SUPPRESSED_BUMP_SQL =
        "UPDATE admin_notification_state SET suppressed_count = suppressed_count + 1 "
        + "WHERE notification_key = ?";

    @PostConstruct
    void init() {
        long ms = throttleWindow.toMillis();
        String interval = "INTERVAL '" + ms + " milliseconds'";
        this.upsertSql = """
            INSERT INTO admin_notification_state
                (notification_key, error_class, last_notified_at, notification_count, suppressed_count, first_seen_at)
            VALUES (?, ?, ?, 1, 0, ?)
            ON CONFLICT (notification_key) DO UPDATE SET
                last_notified_at = EXCLUDED.last_notified_at,
                notification_count = admin_notification_state.notification_count + 1,
                error_class = EXCLUDED.error_class
            WHERE admin_notification_state.last_notified_at + %s <= EXCLUDED.last_notified_at
            RETURNING notification_key
            """.formatted(interval);
    }

    /**
     * Record one notification occurrence under {@code key} and decide
     * whether to emit a WARN log line. Atomic against concurrent
     * callers for the same key — the UPSERT serializes on the row PK,
     * so among N concurrent callers exactly one returns
     * {@link NotifyOutcome#EMITTED} and the rest return
     * {@link NotifyOutcome#SUPPRESSED}.
     *
     * @param key        coalescing key — e.g. {@code "stage1-regex-timeout"} or
     *                   {@code "asset-source-failed:zcash:price"}. Caller's
     *                   responsibility to pick a low-cardinality key so the
     *                   table doesn't accumulate distinct rows for every
     *                   per-instance failure.
     * @param errorClass canonical error_class string the pipeline already
     *                   records elsewhere; stored verbatim in
     *                   {@code admin_notification_state.error_class}.
     * @param message    human-readable detail for the WARN log line. Stored
     *                   in the log only, not in the table.
     * @return {@link NotifyOutcome#EMITTED} when the call resulted in a
     *         log emission (first call for the key, or first call after
     *         the throttle window elapsed); {@link NotifyOutcome#SUPPRESSED}
     *         when the call landed inside the throttle window.
     */
    public NotifyOutcome notifyOnce(String key,
                                    String errorClass,
                                    String message) {
        // Sanitize once at the entry boundary so the log sink, the
        // DB row, and any future side-channel see the same bounded,
        // line-boundary-safe values.
        String safeKey = sanitize(key, MAX_KEY_LENGTH);
        String safeErrorClass = sanitize(errorClass, MAX_ERROR_CLASS_LENGTH);
        String safeMessage = sanitize(message, MAX_MESSAGE_LENGTH);
        try (Connection conn = dataSource.getConnection()) {
            return runNotify(conn, safeKey, safeErrorClass, safeMessage);
        } catch (SQLException e) {
            // Degraded-DB fallback. A DB failure inside the notifier
            // must not propagate — the notifier IS the failure-
            // visibility path; throwing here would bury the caller's
            // original failure under a notifier failure. Emit on the
            // canonical ADMIN-NOTIFY shape with PERSISTENCE_FAILED_KEY
            // so operator scrapes keyed on `grep ADMIN-NOTIFY` catch
            // the notifier-cannot-persist case. The exception is
            // bound as the log throwable so stack trace + cause chain
            // reach the operator's log. SQLException.getMessage() is
            // sanitized — JDBC driver errors are a system-boundary
            // input (per CLAUDE.md §"No defensive code") whose text
            // we don't fully trust to be line-boundary safe.
            String exceptionMessage = e.getMessage() == null ? "" : e.getMessage();
            LOG.warnf(e, "ADMIN-NOTIFY key=%s error=%s message=%s",
                PERSISTENCE_FAILED_KEY,
                e.getClass().getSimpleName(),
                sanitize(exceptionMessage, MAX_MESSAGE_LENGTH));
            return NotifyOutcome.SUPPRESSED;
        }
    }

    /**
     * Transaction-participating variant of
     * {@link #notifyOnce(String, String, String)}: runs the UPSERT on
     * the supplied {@code conn} and PROPAGATES {@link SQLException}
     * instead of routing it to the degraded-DB fallback. For callers
     * whose notification persistence must commit atomically with
     * other writes in the same transaction (e.g. a LISTEN/NOTIFY
     * consumer's high-water-mark advance per
     * docs/spec/architecture.md §Inter-service communication) — a
     * swallowed failure here would let the surrounding transaction
     * commit while the notification is silently lost. The caller owns
     * failure handling: the propagated exception must roll back the
     * whole transaction, side effects included.
     */
    public NotifyOutcome notifyOnce(Connection conn,
                                    String key,
                                    String errorClass,
                                    String message) throws SQLException {
        return runNotify(conn,
            sanitize(key, MAX_KEY_LENGTH),
            sanitize(errorClass, MAX_ERROR_CLASS_LENGTH),
            sanitize(message, MAX_MESSAGE_LENGTH));
    }

    /**
     * Shared UPSERT + suppressed-bump core. Inputs are pre-sanitized
     * by both public entry points; the connection's transaction
     * semantics (own pooled connection vs caller-enlisted) are the
     * entry points' concern.
     */
    private NotifyOutcome runNotify(Connection conn, String safeKey, String safeErrorClass,
                                    String safeMessage) throws SQLException {
        Instant now = clock.instant();
        OffsetDateTime nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        boolean emitted;
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, safeKey);
            ps.setString(2, safeErrorClass);
            ps.setObject(3, nowOdt);
            ps.setObject(4, nowOdt);
            try (ResultSet rs = ps.executeQuery()) {
                // RETURNING produces a row iff the INSERT
                // succeeded OR the DO UPDATE WHERE matched —
                // either way an emit happened. No row → CONFLICT
                // but WHERE filtered out the UPDATE → suppressed.
                emitted = rs.next();
            }
        }
        if (emitted) {
            // Canonical WARN format pinned for operator log
            // scraping per the ticket's acceptance contract.
            LOG.warnf("ADMIN-NOTIFY key=%s error=%s message=%s", safeKey, safeErrorClass, safeMessage);
            return NotifyOutcome.EMITTED;
        }
        // Within-window: bump suppressed_count on the row that
        // already exists. The follow-up UPDATE is atomic at the
        // row level so N concurrent SUPPRESSED callers produce
        // exactly N increments.
        try (PreparedStatement bump = conn.prepareStatement(SUPPRESSED_BUMP_SQL)) {
            bump.setString(1, safeKey);
            bump.executeUpdate();
        }
        return NotifyOutcome.SUPPRESSED;
    }

    /**
     * Read the current {@code admin_notification_state} row for
     * {@code key}, if any. Used by tests and (in a future ticket) by
     * admin commands that surface notifier counters to bot admins.
     * Returns {@link Optional#empty()} when no row exists for the key.
     */
    public Optional<AdminNotificationRecord> getState(String key) {
        // Sanitize the lookup key the same way notifyOnce does so the
        // two calls with the same caller-supplied key reach the same
        // row (the row was persisted under the sanitized form).
        String safeKey = sanitize(key, MAX_KEY_LENGTH);
        final String sql =
            "SELECT notification_key, error_class, last_notified_at, notification_count, suppressed_count, first_seen_at "
            + "FROM admin_notification_state WHERE notification_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, safeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AdminNotificationRecord(
                    rs.getString("notification_key"),
                    rs.getString("error_class"),
                    rs.getObject("last_notified_at", OffsetDateTime.class).toInstant(),
                    rs.getLong("notification_count"),
                    rs.getLong("suppressed_count"),
                    rs.getObject("first_seen_at", OffsetDateTime.class).toInstant()
                ));
            }
        } catch (SQLException e) {
            // safeKey, not key: this WARN is part of the same sanitized
            // scrape surface as the ADMIN-NOTIFY lines — a raw key here
            // would be the one sink that lets a caller-supplied line
            // break or control character reach the operator's log.
            LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", safeKey);
            return Optional.empty();
        }
    }
}
