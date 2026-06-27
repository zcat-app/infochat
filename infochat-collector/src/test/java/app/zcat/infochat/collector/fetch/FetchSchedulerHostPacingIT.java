package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.nitter.NitterFetcher;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives {@link FetchScheduler#onTick()} with per-host pacing ENABLED — a
 * nested {@link QuarkusTestProfile} overrides the collector test profile's
 * {@code infochat.fetch.host-min-interval=off} to {@code 1m} — to prove the
 * M1-466 pacing behavior:
 *
 * <ul>
 *   <li>sources sharing one host dispatch at most one-per-window; the rest are
 *       deferred to later heartbeats and eventually all fetched (none dropped);
 *   <li>sources on distinct hosts all dispatch in a single heartbeat.
 * </ul>
 *
 * <p>The {@code nitter} kind is the vehicle: it has a registered Fetcher but no
 * active source in the bootstrap fixture (only rss/bluesky are active; nostr is
 * a stream kind), so forcing it due exercises pacing against ONLY the sources
 * this test seeds. {@link NitterFetcher} is replaced with a recording stub via
 * {@link QuarkusMock} so no real network fetch occurs. The injected
 * {@link Clock} is pinned and advanced between {@code onTick()} calls (the
 * {@link FetchSchedulerClockIT} fixed-clock pattern) so pacing windows are
 * exercised deterministically without wall-clock waits.
 */
@QuarkusTest
@TestProfile(FetchSchedulerHostPacingIT.PacingEnabledProfile.class)
class FetchSchedulerHostPacingIT {

    /**
     * Turns pacing ON for this IT only. The base collector test profile sets
     * {@code infochat.fetch.host-min-interval=off} so every other
     * FetchScheduler IT keeps its single-tick behavior; this nested profile
     * boots a separate Quarkus instance with the key set to a real window.
     */
    public static class PacingEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("infochat.fetch.host-min-interval", "1m");
        }
    }

    private static final String PACED_KIND = "nitter";
    private static final Instant BASE = Instant.parse("2125-01-01T00:00:00Z");
    // Each modelled heartbeat advances the clock past the 1m pacing window so
    // the previously-dispatched host frees for the next deferred source.
    private static final Duration ADVANCE = Duration.ofMinutes(2);

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    private RecordingNitterFetcher recorder;

    @BeforeEach
    void setUp() throws Exception {
        deleteNitterSources();
        installClock(BASE);
        recorder = new RecordingNitterFetcher();
        QuarkusMock.installMockForType(recorder, NitterFetcher.class,
            new FetcherKind.Literal(PACED_KIND));

        // Pacing is ON for every test in this class, so the in-memory pacing
        // maps would bleed between tests — clear them, mirroring the
        // FetchSchedulerClockIT reflection pattern.
        FetchScheduler real = ClientProxy.unwrap(fetchScheduler);
        mapField(real, "hostNextAllowed").clear();
        mapField(real, "pendingByKind").clear();
        mapField(real, "lastTickByKind").clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteNitterSources();
    }

    @Test
    void sameHostDueSourcesPaceAcrossHeartbeats() throws Exception {
        seedNitterSource("https://rss.xcancel.com/feedA");
        seedNitterSource("https://rss.xcancel.com/feedB");
        seedNitterSource("https://rss.xcancel.com/feedC");

        // Heartbeat 1: one source dispatches; the other two share the host and
        // are deferred to a later heartbeat.
        heartbeat(BASE);
        assertEquals(1, recorder.dispatched.size(),
            "a crowded host dispatches at most one source per window");

        // Re-tick at the SAME instant: the host is still cooling down, so no
        // further source dispatches — the deferred sources stay pending and the
        // kind is NOT marked done.
        heartbeat(BASE);
        assertEquals(1, recorder.dispatched.size(),
            "within one pacing window the same host dispatches no second source");

        // Heartbeat 2 (window elapsed): the next deferred source dispatches.
        heartbeat(BASE.plus(ADVANCE));
        assertEquals(2, recorder.dispatched.size(),
            "after the window elapses the next deferred source dispatches");

        // Heartbeat 3 (window elapsed again): the last deferred source dispatches.
        heartbeat(BASE.plus(ADVANCE).plus(ADVANCE));
        assertEquals(3, recorder.dispatched.size(),
            "all crowded-host sources are eventually fetched, none dropped");

        assertEquals(
            Set.of("https://rss.xcancel.com/feedA", "https://rss.xcancel.com/feedB",
                "https://rss.xcancel.com/feedC"),
            Set.copyOf(recorder.dispatched),
            "every seeded source is fetched exactly once across the heartbeats");

        // The kind is stamped only once its pending queue drains: proof that a
        // deferred source is delayed WITHIN the cycle and the next kind-interval
        // starts only after the whole crowded host is drained — never postponed
        // a whole kind-interval.
        Instant lastTick = (Instant) mapField(
            ClientProxy.unwrap(fetchScheduler), "lastTickByKind").get(PACED_KIND);
        assertEquals(BASE.plus(ADVANCE).plus(ADVANCE), lastTick,
            "lastTickByKind is stamped from the injected Clock only after the "
                + "kind's pending sources all drain");
    }

    @Test
    void distinctHostDueSourcesAllDispatchInOneHeartbeat() throws Exception {
        seedNitterSource("https://host-a.example/x");
        seedNitterSource("https://host-b.example/y");
        seedNitterSource("https://host-c.example/z");

        heartbeat(BASE);

        assertEquals(3, recorder.dispatched.size(),
            "sources on distinct hosts are not throttled — all dispatch in one heartbeat");
        assertEquals(
            Set.of("https://host-a.example/x", "https://host-b.example/y",
                "https://host-c.example/z"),
            Set.copyOf(recorder.dispatched),
            "every distinct-host source is fetched in the single heartbeat");
    }

    /**
     * Advance the injected clock to {@code now}, mark every kind EXCEPT the
     * paced kind not-due (so no source-bearing kind's real Fetcher fires this
     * heartbeat), then drive one {@code onTick()}.
     */
    private void heartbeat(Instant now) throws Exception {
        installClock(now);
        markOthersNotDue(now);
        fetchScheduler.onTick();
    }

    private void installClock(Instant now) {
        QuarkusMock.installMockForType(Clock.fixed(now, ZoneOffset.UTC), Clock.class);
    }

    @SuppressWarnings("unchecked")
    private void markOthersNotDue(Instant now) throws Exception {
        FetchScheduler real = ClientProxy.unwrap(fetchScheduler);
        Map<String, Instant> lastTick = (Map<String, Instant>) mapField(real, "lastTickByKind");
        for (String kind : mapField(real, "fetchersByKind").keySet()) {
            if (!kind.equals(PACED_KIND)) {
                lastTick.put(kind, now);
            }
        }
    }

    private void seedNitterSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status) "
                     + "VALUES ('nitter', ?, ?, 'news', '{}', 'active')")) {
            ps.setString(1, identifier);
            ps.setString(2, "host-pacing IT " + identifier);
            ps.executeUpdate();
        }
    }

    private void deleteNitterSources() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM source WHERE kind = 'nitter'")) {
            ps.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> mapField(FetchScheduler scheduler, String name) throws Exception {
        var field = FetchScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, ?>) field.get(scheduler);
    }

    /**
     * Stub {@link NitterFetcher} that records each dispatched identifier and
     * performs no network I/O. The super's public no-arg constructor builds an
     * unused {@code SsrfGuardedHttpClient}; {@link #fetch} is overridden so it
     * is never exercised.
     */
    private static final class RecordingNitterFetcher extends NitterFetcher {
        final List<String> dispatched = new CopyOnWriteArrayList<>();

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            dispatched.add(identifier);
            return List.of();
        }
    }
}
