package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link DigestWorker} now-vs-{@code windowEnd} deadline against an
 * injected {@link Clock} (M1-454, engineering-rules §9). The decision is
 * {@code remaining = windowEnd - now}: {@code remaining <= 0} degrades, else it
 * renders within that timeout budget. Each test pins a Clock whose verdict
 * DISAGREES with the wall clock — a {@code windowEnd} ahead of the pinned now
 * but decades behind wall-clock now (renders only under the pin), and one
 * behind the pinned now but ahead of wall-clock now (degrades only under the
 * pin) — so the outcome can only come from {@code clock.instant()}.
 *
 * <p>Hand-wired like {@link DigestWorkerTest} (Mockito is intentionally absent
 * from the Provider classpath). An empty {@link AdapterRegistry} short-circuits
 * delivery right after the cache upsert, so the degrade/render verdict is read
 * off the recorded cache row without wiring the outbound chokepoint.
 */
class DigestWorkerClockTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final String ADAPTER_NAME = "inmemory";
    private static final String UPSTREAM_GROUP_ID = "group-clock";
    private static final Duration REPLAY_RETENTION = Duration.ofMinutes(10);

    private DigestWorker worker;
    private RecordingPostCollector postCollector;
    private RecordingDigestRenderer digestRenderer;
    private RecordingDegradedRenderer degradedRenderer;
    private RecordingCacheRepository cacheRepository;
    private RecordingSectionRepository sectionRepository;

    @BeforeEach
    void setUp() {
        worker = new DigestWorker();
        postCollector = new RecordingPostCollector();
        digestRenderer = new RecordingDigestRenderer();
        degradedRenderer = new RecordingDegradedRenderer();
        cacheRepository = new RecordingCacheRepository();
        sectionRepository = new RecordingSectionRepository();

        worker.postCollector = postCollector;
        worker.digestRenderer = digestRenderer;
        worker.degradedRenderer = degradedRenderer;
        worker.cacheRepository = cacheRepository;
        worker.sectionRepository = sectionRepository;
        // No activated adapter → executeSlot returns right after the cache
        // upsert and section persist, so the outbound chokepoint is never
        // exercised. The persist step DOES run on the render path now
        // (M1-652), which is why the section-repo stub is wired here.
        worker.adapterRegistry = new AdapterRegistry();
        worker.dataSource = new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en");
        worker.replayRetention = REPLAY_RETENTION;
    }

    @Test
    void rendersWhenWindowEndAfterInjectedNow_thoughExpiredOnWallClock() {
        Instant pinnedNow = Instant.parse("2000-01-01T00:00:00Z");
        worker.clock = Clock.fixed(pinnedNow, ZoneOffset.UTC);
        // windowEnd is 1h AHEAD of the pinned now (→ render) but decades behind
        // wall-clock now (→ would degrade).
        Instant windowStart = pinnedNow.minusSeconds(3600);
        Instant windowEnd = pinnedNow.plusSeconds(3600);
        postCollector.seed(List.of(postPublishedAt(windowStart)), 1, 1);
        digestRenderer.setResponse("full prose");
        degradedRenderer.setResponse("headlines only");
        DigestSlot slot = new DigestSlot(GROUP_ID, "UTC", "morning", windowStart, windowEnd);

        worker.execute(slot);

        assertEquals(1, digestRenderer.callCount(),
                "window open under the injected clock → the LLM renderer runs");
        assertEquals(0, degradedRenderer.callCount(),
                "no degrade when the injected clock leaves the window open");
        assertFalse(cacheRepository.lastIsDegraded());
        assertEquals("full prose", cacheRepository.lastContent());
    }

    @Test
    void degradesWhenWindowEndBeforeInjectedNow_thoughOpenOnWallClock() {
        Instant pinnedNow = Instant.parse("2099-01-01T00:00:00Z");
        worker.clock = Clock.fixed(pinnedNow, ZoneOffset.UTC);
        // windowEnd is ahead of wall-clock now (→ would render) but behind the
        // pinned now (→ degrade).
        Instant windowEnd = Instant.parse("2030-01-01T00:00:00Z");
        Instant windowStart = windowEnd.minusSeconds(3600);
        postCollector.seed(List.of(postPublishedAt(windowStart)), 1, 1);
        digestRenderer.setResponse("full prose");
        degradedRenderer.setResponse("headlines only");
        DigestSlot slot = new DigestSlot(GROUP_ID, "UTC", "morning", windowStart, windowEnd);

        worker.execute(slot);

        assertEquals(0, digestRenderer.callCount(),
                "window closed under the injected clock → the LLM renderer is skipped");
        assertEquals(1, degradedRenderer.callCount(),
                "the deadline gate degrades when the injected clock has passed windowEnd");
        assertTrue(cacheRepository.lastIsDegraded());
        assertEquals("headlines only", cacheRepository.lastContent());
    }

    private static Post postPublishedAt(Instant publishedAt) {
        return new Post(UUID.randomUUID(), "uid-clock", UUID.randomUUID(),
                "Src", "Title", "https://example.com/1", "body", publishedAt,
                List.of("crypto"), List.of("unknown"));
    }
}
