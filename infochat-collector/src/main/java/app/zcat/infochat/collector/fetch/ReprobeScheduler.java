package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.collector.fetcher.PaginationSaturationTracker;
import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * D42 re-probe ladder (as amended by M1-752; M1-754): a source parked
 * by consecutive fetch failures is automatically re-probed on
 * exponential backoff, restored to {@code active} on the first
 * successful probe, and terminally parked once the absolute cap is
 * exhausted — after which only {@code /source-enable} revives it.
 *
 * <p>This is a scheduling path SEPARATE from the active fetch
 * enumeration ({@link FetchScheduler#onTick}): probes never ride the
 * per-kind dispatch queues, so a wall of parked sources cannot delay
 * healthy ones. Eligibility is decided on the recorded park reason —
 * only {@code 'fetch-failure'} parks are ever selected; the
 * {@code 'unknown-rate'} and {@code 'stream-cycle-cap'} security
 * parks and NULL-reason (pre-discriminator) parks stay manual-only
 * (D42 properties (b) and (c)).
 *
 * <p>The probe goes through the registered {@link Fetcher} SPI
 * instance ({@link FetchScheduler#fetcherFor}), which is how it
 * inherits the D20 SSRF allowlist — a bespoke HTTP client here would
 * silently bypass it.
 */
@ApplicationScoped
public class ReprobeScheduler {

    static final String ERROR_CLASS_REPROBE_RECOVERED = "reprobe_recovered";

    /** Audit actor for the automatic restore (the {@code re_eval_job} pattern). */
    static final String JOB_ACTOR = "reprobe_job";

    private static final Logger LOG = LoggerFactory.getLogger(ReprobeScheduler.class);

    @Inject
    DataSource dataSource;

    @Inject
    SourceRepository sourceRepository;

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    PaginationSaturationTracker saturationTracker;

    // Every backoff/cadence/cap decision reads the injected Clock
    // (engineering-rules §9): the job both writes next_reprobe_at /
    // reprobe_restored_at and reads them back, so schedule state never
    // splits across the app and DB clocks. The systemUTC() initializer
    // is what the CDI producer supplies; injection overrides it in the
    // managed bean.
    @Inject
    Clock clock = Clock.systemUTC();

    @ConfigProperty(name = "infochat.fetch.reprobe.first-delay")
    Duration firstDelay;

    @ConfigProperty(name = "infochat.fetch.reprobe.backoff-factor")
    double backoffFactor;

    @ConfigProperty(name = "infochat.fetch.reprobe.backoff-ceiling")
    Duration backoffCeiling;

    @ConfigProperty(name = "infochat.fetch.reprobe.cap")
    int reprobeCap;

    @ConfigProperty(name = "infochat.fetch.reprobe.sustained-success-window")
    Duration sustainedSuccessWindow;

    @Scheduled(every = "{infochat.fetch.reprobe.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void onTick() {
        try {
            runOnce();
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "ReprobeScheduler: tick failed; skipping", e);
        }
    }

    /**
     * One re-probe sweep: seed schedules for newly-seen parks, clear
     * sustained-success counters, probe every due candidate. Public so
     * tests (including the cross-package exclusion tests) can invoke
     * sweeps deterministically without waiting on the scheduler clock —
     * the {@link FetchScheduler#tickOnce} precedent.
     */
    public void runOnce() throws SQLException {
        // One clock sample feeds schedule seeding, the sustained-success
        // floor, and the dueness comparison, so a single tick's decisions
        // cannot straddle two instants (M1-444 / M1-449 discipline).
        Instant now = clock.instant();
        sourceRepository.initializeReprobeSchedule(now.plus(firstDelay), reprobeCap);
        int cleared = sourceRepository.clearSustainedSuccessCounters(
            now.minus(sustainedSuccessWindow));
        if (cleared > 0) {
            LOG.info("ReprobeScheduler: cleared re-probe counters for {} source(s) "
                + "healthy past the sustained-success window", cleared);
        }
        long dispatch = 1L;
        for (SourceRepository.ReprobeCandidate candidate
                : sourceRepository.selectDueReprobes(now, reprobeCap)) {
            probeOne(candidate, dispatch++);
        }
    }

    private void probeOne(SourceRepository.ReprobeCandidate candidate, long dispatchKey)
            throws SQLException {
        Fetcher fetcher = fetchScheduler.fetcherFor(candidate.kind());
        if (fetcher == null) {
            // No probe ran, so no attempt is consumed; the row stays due
            // and is retried once the kind's Fetcher is bound.
            LOG.warn("ReprobeScheduler: no fetcher registered for kind '{}', "
                + "skipping probe of source uuid={}", candidate.kind(), candidate.uuid());
            return;
        }

        // Record the attempt BEFORE the fetch: the cap counts probes, so
        // the attempt must be durable whatever the probe or the process
        // does next — a crash mid-probe must not grant a free retry, and
        // a successful restore keeps the count (only the sustained-
        // success sweep clears it; the flap-forever leg of D42).
        int attemptNumber = candidate.reprobeCount() + 1;
        sourceRepository.recordReprobeAttempt(
            candidate.uuid(), clock.instant().plus(backoffAfter(attemptNumber)));

        List<NormalizedPost> posts;
        try {
            posts = fetcher.fetch(dispatchKey, candidate.identifier());
            // Drain both thread-local fetch signals immediately, success or
            // failure: the Quarkus scheduler shares its thread pool with
            // FetchScheduler.onTick, so a flag leaked here would be
            // consumed by the next active tick on this thread and notify
            // against the wrong uuid (the M1-753 / M1-757 one-tick-
            // lifetime rule). The probe path discards the values — a
            // probe is an authorization check, not an ingest tick, so it
            // reports no truncation and no saturation.
            saturationTracker.consumeCapHit();
            saturationTracker.consumeTruncation();
        } catch (Exception e) {
            saturationTracker.consumeTruncation();
            saturationTracker.consumeCapHit();
            // uuid + dispatch only, message chain URL-redacted — never the
            // identifier URL (M1-023 INFO-LEAK; M1-042 redaction contract).
            LOG.warn("ReprobeScheduler: probe failed for source uuid={} "
                + "(attempt {} of {}): {}",
                candidate.uuid(), attemptNumber, reprobeCap,
                FetchScheduler.redactUrlsInText(FetchScheduler.exceptionChainMessage(e)));
            return;
        }

        Instant restoredAt = clock.instant();
        // CAS restore + audit row + RECOVERED notification in ONE
        // transaction: a rollback leaves no orphan audit row and no
        // notification for a restore that never landed, and a zero-row
        // CAS (eligibility vanished during the probe — reason upgraded
        // to a security park, or /remove-source soft-deleted the row)
        // writes neither (D42 property (e)).
        int updated = TransactionHelper.inTransactionReturning(dataSource,
            "ReprobeScheduler.restore", conn -> {
                int u = sourceRepository.casRestore(
                    conn, candidate.uuid(), restoredAt, reprobeCap);
                if (u > 0) {
                    writeRestoreAudit(conn, candidate.uuid(), attemptNumber);
                    throttledAdminNotifier.notifyOnce(conn,
                        ERROR_CLASS_REPROBE_RECOVERED + ":" + candidate.uuid(),
                        ERROR_CLASS_REPROBE_RECOVERED,
                        "Source " + candidate.uuid() + " kind=" + candidate.kind()
                            + " recovered by automatic re-probe (attempt "
                            + attemptNumber + " of " + reprobeCap
                            + "); status restored to active");
                }
                return u;
            });

        if (updated == 0) {
            // The fetched batch is DISCARDED on a no-op. This deliberately
            // inverts the active tick's persist-before-emit outbox
            // discipline for the re-probe path only: on the active path
            // the source is already authorized and persisting first makes
            // a crash recoverable, whereas here authorization is exactly
            // what the CAS is deciding — persisting first would let a
            // parked feed use its probe to deliver the borderline content
            // the UNKNOWN-rate control exists to keep out (D42 (e)).
            LOG.info("ReprobeScheduler: restore no-op for source uuid={}; "
                + "probe batch of {} post(s) discarded", candidate.uuid(), posts.size());
            return;
        }

        // Restored: the probe's batch is real ingest now. A failure here
        // is a Collector-side fault, not a source-health signal (the
        // tickOnce Stage-2 posture) — the source stays restored and the
        // next active tick re-delivers the feed's current window.
        try {
            for (NormalizedPost post : posts) {
                Optional<PostPersister.PersistedPostKey> key =
                    postPersister.persist(candidate.uuid(), post);
                key.ifPresent(evalQueueProducer::emit);
            }
        } catch (Exception e) {
            LOG.warn("ReprobeScheduler: persist/enqueue failed for restored "
                + "source uuid={} (exception={})",
                candidate.uuid(), e.getClass().getName());
        }
        LOG.info("ReprobeScheduler: source uuid={} restored to active on "
            + "re-probe attempt {} of {}", candidate.uuid(), attemptNumber, reprobeCap);
    }

    private void writeRestoreAudit(Connection conn, UUID sourceUuid, int attemptNumber)
            throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
            .actorContactId(JOB_ACTOR)
            .action(AuditAction.SOURCE_REPROBE_RESTORED)
            .targetKind(TargetKind.SOURCE)
            .targetId(sourceUuid.toString())
            .detailsJson("{\"reprobe_attempt\":" + attemptNumber + "}")
            .build();
        auditLogWriter.write(conn, row);
    }

    // The k-th attempt schedules slot k+1 at first-delay * factor^k,
    // capped at the ceiling. Double math cannot overflow before the
    // min() the way long multiplication would.
    private Duration backoffAfter(int attemptsMade) {
        double scaledMillis = firstDelay.toMillis() * Math.pow(backoffFactor, attemptsMade);
        return Duration.ofMillis(
            (long) Math.min(scaledMillis, (double) backoffCeiling.toMillis()));
    }
}
