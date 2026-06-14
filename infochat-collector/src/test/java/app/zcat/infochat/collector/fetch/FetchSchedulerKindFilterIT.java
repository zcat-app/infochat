package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-341: pins {@link FetchScheduler}'s stream-kind exclusion and
 * due-kind SQL filter (deep-review F3 + F4).
 *
 * <ul>
 *   <li><b>F3 — stream-kind exclusion.</b> {@code nostr} is a
 *       {@code StreamSource}, deliberately never bound to a Fetcher, so
 *       the orphan-warning check must not log "No fetcher registered for
 *       kind 'nostr'". An active nostr source flows through the real
 *       {@link FetchScheduler#distinctActiveKinds()} query and the
 *       {@link FetchScheduler#warnUnboundPolledKinds(Set)} decision
 *       without producing a warning.</li>
 *   <li><b>Orphan signal preserved.</b> A genuinely-missing polled-fetch
 *       binding (an active polled kind with no registered Fetcher) still
 *       warns exactly once per scheduler lifecycle.</li>
 *   <li><b>F4 — due-kind SQL filter.</b>
 *       {@link FetchScheduler#enumerateActiveSourcesByKinds(Set)} returns
 *       only rows of the requested kinds, so the heartbeat's result set
 *       scales by due-source count rather than total active source
 *       count.</li>
 * </ul>
 *
 * <p>The orphan-warning cases drive the package-private seam directly
 * rather than the {@code @Scheduled} {@code onTick} clock: a heartbeat
 * would also dispatch every active source to its Fetcher (real HTTP),
 * and the shared scheduler singleton could race the once-per-kind warn
 * set. Driving {@code warnUnboundPolledKinds} with a controlled kind set
 * — for the orphan case a kind with no source row, which the live
 * scheduler therefore never enumerates — keeps the assertion
 * deterministic. Same seam rationale as {@code FetchSchedulerLogRedactionTest}.
 */
@QuarkusTest
class FetchSchedulerKindFilterIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    FetchScheduler fetchScheduler;

    private Logger jul;
    private Logger jbossJul;
    private CapturingHandler capturer;

    @BeforeEach
    void attachLogHandler() {
        capturer = new CapturingHandler();
        capturer.setLevel(Level.ALL);
        // Dual-attach mirrors FetchSchedulerLogRedactionTest: capture
        // works regardless of which LogManager the surefire JVM picked.
        jul = Logger.getLogger(FetchScheduler.class.getName());
        jul.setLevel(Level.ALL);
        jul.addHandler(capturer);
        jbossJul = Logger.getLogger("org.jboss.logging");
        jbossJul.setLevel(Level.ALL);
        jbossJul.addHandler(capturer);
    }

    @AfterEach
    void detachLogHandler() {
        jul.removeHandler(capturer);
        jbossJul.removeHandler(capturer);
    }

    @Test
    void activeNostrSourceProducesNoOrphanWarning() throws Exception {
        seedSource("nostr", "m1-341-kindfilter-nostr-" + UUID.randomUUID());

        Set<String> activeKinds = fetchScheduler.distinctActiveKinds();
        assertTrue(activeKinds.contains("nostr"),
            "the seeded active nostr source must appear in the distinct active kinds: "
                + activeKinds);

        fetchScheduler.warnUnboundPolledKinds(activeKinds);

        for (LogRecord record : capturer.records) {
            assertFalse(renderRecord(record).contains("nostr"),
                "stream kind nostr must not trip the orphan warning: "
                    + renderRecord(record));
        }
    }

    @Test
    void genuinelyMissingPolledFetcherWarnsOnce() {
        // A kind with NO source row, so the live scheduler's heartbeat
        // never enumerates it and cannot race the once-per-kind warn set.
        String orphanKind = "m1-341-orphan-probe";

        fetchScheduler.warnUnboundPolledKinds(Set.of(orphanKind));
        assertEquals(1, countOrphanWarnings(orphanKind),
            "a genuinely-missing polled Fetcher binding must warn exactly once");

        fetchScheduler.warnUnboundPolledKinds(Set.of(orphanKind));
        assertEquals(1, countOrphanWarnings(orphanKind),
            "the orphan warning must not repeat on a later heartbeat (once per kind)");
    }

    @Test
    void enumerateByKindsReturnsOnlyRequestedKind() throws Exception {
        UUID rssId = seedSource("rss", "m1-341-kindfilter-rss-" + UUID.randomUUID());
        UUID redditId = seedSource("reddit", "m1-341-kindfilter-reddit-" + UUID.randomUUID());

        List<FetchScheduler.SourceRow> rssRows =
            fetchScheduler.enumerateActiveSourcesByKinds(Set.of("rss"));
        assertTrue(containsId(rssRows, rssId),
            "the rss filter must include the seeded rss source");
        assertFalse(containsId(rssRows, redditId),
            "the rss filter must exclude the seeded reddit source");
        for (FetchScheduler.SourceRow row : rssRows) {
            assertEquals("rss", row.kind(),
                "every row from the rss filter must be kind=rss: " + row.kind());
        }

        List<FetchScheduler.SourceRow> redditRows =
            fetchScheduler.enumerateActiveSourcesByKinds(Set.of("reddit"));
        assertTrue(containsId(redditRows, redditId),
            "the reddit filter must include the seeded reddit source");
        assertFalse(containsId(redditRows, rssId),
            "the reddit filter must exclude the seeded rss source");
    }

    private long countOrphanWarnings(String kind) {
        String needle = "No fetcher registered for source kind '" + kind + "'";
        return capturer.records.stream()
            .map(FetchSchedulerKindFilterIT::renderRecord)
            .filter(line -> line.contains(needle))
            .count();
    }

    private static boolean containsId(List<FetchScheduler.SourceRow> rows, UUID id) {
        return rows.stream().anyMatch(row -> row.uuid().equals(id));
    }

    private UUID seedSource(String kind, String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                 + "VALUES (?, ?, ?, 'news', '{}') "
                 + "RETURNING id")) {
            ps.setString(1, kind);
            ps.setString(2, identifier);
            ps.setString(3, "M1-341 kind-filter IT " + kind + " source");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static String renderRecord(LogRecord r) {
        String raw = r.getMessage();
        if (raw == null) {
            return "";
        }
        Object[] params = r.getParameters();
        if (params == null || params.length == 0) {
            return raw;
        }
        try {
            return String.format(raw, params);
        } catch (Exception fmtEx) {
            return raw;
        }
    }

    /**
     * Minimal JUL handler recording every emitted {@link LogRecord}.
     * Same shape as {@code FetchSchedulerLogRedactionTest}'s capturer.
     */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
