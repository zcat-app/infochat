package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the injected {@link Clock} to a FIXED instant and drives
 * {@link FetchScheduler#onTick()} to prove the per-kind tick-interval gate
 * ({@code Duration.between(lastTick, now)}) and the {@code lastTickByKind}
 * stamp both read that instant — not the wall-clock run date. (M1-449)
 *
 * <p>The pin is the distant future (year 2125). The {@code reddit} kind has a
 * registered Fetcher but no active source in the bootstrap fixture (only rss
 * and bluesky are active; nostr is a stream kind), so forcing it due exercises
 * the gate and the stamp without any network fetch. Every other registered
 * kind is stamped to the pinned now (not due) so no source-bearing kind ticks.
 * The assertion that reddit's stamp equals the pinned instant is
 * discriminating: a wall-clock gate would see the future seed as not-yet-
 * elapsed, leave reddit not-due, and never re-stamp it.
 */
@QuarkusTest
class FetchSchedulerClockIT {

    private static final Instant PINNED_NOW = Instant.parse("2125-01-01T00:00:00Z");
    // Registered Fetcher kind with no active source in the bootstrap fixture,
    // so onTick exercises the interval gate + stamp with zero fetched rows.
    private static final String NO_SOURCE_KIND = "reddit";

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @BeforeEach
    void pinClock() {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
    }

    @Test
    void onTickStampsLastTickFromInjectedClock() throws Exception {
        assertEquals(0, activeSourceCount(NO_SOURCE_KIND),
            "precondition: the forced-due kind must have no active source so onTick fetches nothing");

        FetchScheduler real = ClientProxy.unwrap(fetchScheduler);
        Map<String, Instant> lastTick = lastTickByKind(real);
        Map<String, ?> fetchersByKind = fetchersByKind(real);

        lastTick.clear();
        // Mark every registered kind as just-ticked under the pinned clock so no
        // source-bearing kind (rss/bluesky) is due → no fetch this tick.
        for (String kind : fetchersByKind.keySet()) {
            lastTick.put(kind, PINNED_NOW);
        }
        // Force one no-source kind due: a day before the pinned now, well past
        // any per-kind interval.
        lastTick.put(NO_SOURCE_KIND, PINNED_NOW.minus(Duration.ofDays(1)));

        fetchScheduler.onTick();

        assertEquals(PINNED_NOW, lastTick.get(NO_SOURCE_KIND),
            "onTick must gate and stamp lastTickByKind from the injected Clock: the due kind is "
                + "re-stamped to the pinned now; a wall-clock gate would see the future seed as "
                + "not-yet-elapsed and leave it unchanged");
    }

    private long activeSourceCount(String kind) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM source WHERE kind = ? AND status = 'active' "
                     + "AND deleted_at IS NULL")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Instant> lastTickByKind(FetchScheduler scheduler) throws Exception {
        var field = FetchScheduler.class.getDeclaredField("lastTickByKind");
        field.setAccessible(true);
        return (Map<String, Instant>) field.get(scheduler);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> fetchersByKind(FetchScheduler scheduler) throws Exception {
        var field = FetchScheduler.class.getDeclaredField("fetchersByKind");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(scheduler);
    }
}
