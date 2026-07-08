package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.nitter.NitterFetcher;
import app.zcat.infochat.collector.fetcher.youtube.YouTubeFetcher;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link FetchScheduler#onTick()} with per-host pacing ENABLED — a
 * nested {@link QuarkusTestProfile} overrides the collector test profile's
 * {@code infochat.fetch.host-min-interval=off} to {@code 1m} — to prove the
 * M1-466 pacing behavior:
 *
 * <ul>
 *   <li>sources sharing one host dispatch at most one-per-window; the rest are
 *       deferred to later heartbeats and eventually all fetched (none dropped);
 *   <li>sources on distinct hosts all dispatch in a single heartbeat;
 *   <li>a single-host <em>crowd</em> (many due sources of one kind collapsed onto
 *       ONE host, mirroring the live 27-nitter-on-nitter.net event of M1-596)
 *       drains fully across heartbeats WITHOUT a scheduler restart — one dispatch
 *       per heartbeat, the queue strictly shrinking, never wedging — while a
 *       second kind on a distinct host keeps dispatching on its own schedule
 *       throughout (per-kind isolation: one kind's mid-drain queue never gates
 *       another kind's dispatch).
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
            // youtube.interval=1m so the second kind re-becomes due on every
            // modelled 2m heartbeat, letting the single-host-collapse test observe
            // it dispatch throughout nitter's multi-heartbeat drain (per-kind
            // isolation). The other tests suppress youtube, so the key is inert
            // for them.
            return Map.of(
                "infochat.fetch.host-min-interval", "1m",
                "infochat.fetch.youtube.interval", "1m");
        }
    }

    private static final String PACED_KIND = "nitter";
    // A registered-Fetcher kind with NO bootstrap active source (like nitter),
    // so the test fully controls its source set; used as the distinct-host
    // "second kind" proving one kind's mid-drain never gates another's dispatch.
    private static final String SECOND_KIND = "youtube";
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
    private RecordingYouTubeFetcher youtubeRecorder;

    @BeforeEach
    void setUp() throws Exception {
        deleteNitterSources();
        deleteYouTubeSources();
        installClock(BASE);
        recorder = new RecordingNitterFetcher();
        QuarkusMock.installMockForType(recorder, NitterFetcher.class,
            new FetcherKind.Literal(PACED_KIND));
        // Always mock the second kind's Fetcher too, so no real YouTubeFetcher
        // network fetch can occur even if a test forces youtube due.
        youtubeRecorder = new RecordingYouTubeFetcher();
        QuarkusMock.installMockForType(youtubeRecorder, YouTubeFetcher.class,
            new FetcherKind.Literal(SECOND_KIND));

        // Pacing is ON for every test in this class, so the in-memory pacing
        // maps would bleed between tests — clear them through the package-private
        // scheduling-state seams.
        FetchScheduler real = ClientProxy.unwrap(fetchScheduler);
        real.hostNextAllowed().clear();
        real.pendingByKind().clear();
        real.lastTickByKind().clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteNitterSources();
        deleteYouTubeSources();
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
        Instant lastTick = ClientProxy.unwrap(fetchScheduler).lastTickByKind().get(PACED_KIND);
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
     * M1-596 regression: many due sources of ONE kind collapsed onto a SINGLE
     * host (mirroring the live 27-nitter-on-nitter.net event) drain FULLY across
     * heartbeats without a scheduler restart, and a second kind on a distinct
     * host dispatches on its own schedule throughout.
     *
     * <p>This pins the investigation's conclusion (see the M1-596 ticket Notes):
     * there is NO reachable permanent dispatch wedge under a heartbeat interval
     * wider than the host-min-interval — {@code hostNextAllowed[host]} is only
     * ever set to {@code now + host-min-interval}, so a cooldown written one
     * heartbeat ago has always expired by the next heartbeat, and the crowded
     * host dispatches exactly one more source every heartbeat. The queue strictly
     * shrinks and always empties; the live 8-minute "frozen nitter" was the
     * expected slow-but-progressing paced drain of a single-host crowd, not a
     * stall. Red-before/green-after is not applicable (no FetchScheduler code
     * change) — this test pins the slow-but-progressing drain as intended.
     */
    @Test
    void singleHostCollapseDrainsAcrossHeartbeatsWhileSecondKindDispatchesOnItsOwnSchedule()
            throws Exception {
        // Collapse N sources of one kind onto ONE host, all forced due at once.
        // N need only exceed one heartbeat's single-per-host budget to exercise
        // the multi-heartbeat drain; the wedge question is whether the queue can
        // fail to drain, not the exact count.
        int collapsedCount = 5;
        for (int i = 1; i <= collapsedCount; i++) {
            seedNitterSource("https://nitter.net/feed" + i);
        }
        // Second kind, distinct host, due every heartbeat (youtube.interval=1m).
        seedYouTubeSource("https://youtube.example/channel");

        // Drive one heartbeat per pacing window across enough heartbeats to fully
        // drain the crowd. The SAME live FetchScheduler instance throughout — no
        // restart / re-construction between heartbeats.
        for (int heartbeatIndex = 0; heartbeatIndex < collapsedCount; heartbeatIndex++) {
            heartbeatWithSecondKindDue(BASE.plus(ADVANCE.multipliedBy(heartbeatIndex)));

            // The crowded host dispatches exactly ONE more source this heartbeat:
            // the queue strictly shrinks — it never stalls at zero (no wedge).
            assertEquals(heartbeatIndex + 1, recorder.dispatched.size(),
                "single-host crowd drains exactly one source per heartbeat, never wedging");
            // Per-kind isolation: the second kind dispatches every heartbeat even
            // while nitter is mid-drain, so a mid-drain kind never gates another.
            assertEquals(heartbeatIndex + 1, youtubeRecorder.dispatched.size(),
                "distinct-host second kind dispatches every heartbeat, ungated by the "
                    + "mid-drain nitter queue");
            if (heartbeatIndex + 1 < collapsedCount) {
                assertTrue(
                    ClientProxy.unwrap(fetchScheduler).pendingByKind().containsKey(PACED_KIND),
                    "nitter is still mid-drain here, so the second kind's dispatch this "
                        + "heartbeat proves per-kind isolation");
            }
        }

        // Every collapsed source was eventually fetched, exactly once, with NO
        // restart — the drain self-completes. Slow-but-progressing, not wedged.
        assertEquals(collapsedCount, recorder.dispatched.size(),
            "every single-host source is eventually fetched across heartbeats, no restart");
        assertEquals(collapsedCount, Set.copyOf(recorder.dispatched).size(),
            "no single-host source is re-blasted — each dispatched exactly once");

        // nitter's queue fully drained, so its kind-interval finally restarts:
        // lastTickByKind is stamped from the injected Clock only at drain completion.
        Instant lastTick =
            ClientProxy.unwrap(fetchScheduler).lastTickByKind().get(PACED_KIND);
        assertEquals(BASE.plus(ADVANCE.multipliedBy(collapsedCount - 1)), lastTick,
            "lastTickByKind is stamped only once the crowded host fully drains");
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

    private void markOthersNotDue(Instant now) {
        FetchScheduler real = ClientProxy.unwrap(fetchScheduler);
        Map<String, Instant> lastTick = real.lastTickByKind();
        for (String kind : real.registeredKinds()) {
            if (!kind.equals(PACED_KIND)) {
                lastTick.put(kind, now);
            }
        }
    }

    /**
     * Like {@link #heartbeat(Instant)} but leaves BOTH the paced kind and the
     * second kind to their natural dueness — every OTHER registered kind is
     * suppressed so no bootstrap-seeded real Fetcher (rss/bluesky) fires. Used
     * by the single-host-collapse test to let the second kind dispatch on its
     * own schedule while the paced kind drains.
     */
    private void heartbeatWithSecondKindDue(Instant now) throws Exception {
        installClock(now);
        FetchScheduler real = ClientProxy.unwrap(fetchScheduler);
        Map<String, Instant> lastTick = real.lastTickByKind();
        for (String kind : real.registeredKinds()) {
            if (!kind.equals(PACED_KIND) && !kind.equals(SECOND_KIND)) {
                lastTick.put(kind, now);
            }
        }
        fetchScheduler.onTick();
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

    private void seedYouTubeSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status) "
                     + "VALUES ('youtube', ?, ?, 'news', '{}', 'active')")) {
            ps.setString(1, identifier);
            ps.setString(2, "host-pacing IT youtube " + identifier);
            ps.executeUpdate();
        }
    }

    private void deleteYouTubeSources() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM source WHERE kind = 'youtube'")) {
            ps.executeUpdate();
        }
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

    /**
     * Stub {@link YouTubeFetcher} recording each dispatched identifier and doing
     * no network I/O — the second-kind counterpart of
     * {@link RecordingNitterFetcher}. The super's public no-arg constructor
     * builds an unused {@code SsrfGuardedHttpClient}; {@link #fetch} is
     * overridden so it is never exercised.
     */
    private static final class RecordingYouTubeFetcher extends YouTubeFetcher {
        final List<String> dispatched = new CopyOnWriteArrayList<>();

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            dispatched.add(identifier);
            return List.of();
        }
    }
}
