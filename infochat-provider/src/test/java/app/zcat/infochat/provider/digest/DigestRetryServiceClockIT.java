package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestRetryService.RetryResult;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts
 * {@link DigestRetryService#retryDigest}'s retry-cooldown gate decides against
 * that instant — not the wall-clock run date. Complements the unit
 * {@code DigestRetryServiceTest}, whose cooldown gate is never exercised
 * against a pinned clock. (M1-449)
 *
 * <p>Each case is discriminating: the {@code lastRetryAt} stamp is the pinned
 * "now" (the natural state immediately after a retry), and the two pinned
 * instants (year 1999 and 2100) bracket every realistic CI run date, so the
 * verdict under the pinned clock is the OPPOSITE of the verdict a wall-clock
 * {@code Instant.now()} gate would return. {@code lastRetryAt} is seeded via
 * reflection on the unwrapped contextual instance, mirroring the unit test's
 * direct map access.
 */
@QuarkusTest
class DigestRetryServiceClockIT {

    @Inject
    DigestRetryService digestRetryService;

    @Test
    void rateLimitedWhenWithinCooldownUnderPinnedClock_thoughElapsedOnWallClock() throws Exception {
        // Pin "now" to the distant past with the last retry AT that instant: the
        // 2-minute cooldown has NOT elapsed under the pin (RATE_LIMITED), but on
        // any real wall clock (2026+) it elapsed long ago — a wall-clock gate
        // would fall through to the DB and return NO_PRIOR_DIGEST.
        Instant pinned = Instant.parse("1999-01-01T00:00:00Z");
        QuarkusMock.installMockForType(Clock.fixed(pinned, ZoneOffset.UTC), Clock.class);
        UUID groupId = UUID.randomUUID();
        seedLastRetryAt(groupId, pinned);

        assertEquals(RetryResult.RATE_LIMITED, digestRetryService.retryDigest(groupId),
            "the cooldown gate must read the injected Clock: within cooldown under the pinned "
                + "now, even though the cooldown elapsed on the wall clock");
    }

    @Test
    void notRateLimitedWhenCooldownElapsedUnderPinnedClock_thoughWithinOnWallClock() throws Exception {
        // Pin "now" to the distant future with the last retry an hour earlier:
        // the cooldown elapsed under the pin so the gate falls through (no cache
        // row → NO_PRIOR_DIGEST), but on any real wall clock (2026) the future
        // stamp is still within cooldown and a wall-clock gate would return
        // RATE_LIMITED.
        Instant pinned = Instant.parse("2100-01-01T00:00:00Z");
        QuarkusMock.installMockForType(Clock.fixed(pinned, ZoneOffset.UTC), Clock.class);
        UUID groupId = UUID.randomUUID();
        seedLastRetryAt(groupId, pinned.minusSeconds(3600));

        assertEquals(RetryResult.NO_PRIOR_DIGEST, digestRetryService.retryDigest(groupId),
            "the cooldown gate must read the injected Clock: cooldown elapsed under the pinned "
                + "now so the retry proceeds (no cache row → NO_PRIOR_DIGEST), even though the "
                + "stamp is still within cooldown on the wall clock");
    }

    @SuppressWarnings("unchecked")
    private void seedLastRetryAt(UUID groupId, Instant at) throws Exception {
        // Unwrap the client proxy so the reflected field is the real contextual
        // instance's map — the same map retryDigest reads on the proxy call.
        DigestRetryService real = ClientProxy.unwrap(digestRetryService);
        var field = DigestRetryService.class.getDeclaredField("lastRetryAt");
        field.setAccessible(true);
        ((ConcurrentHashMap<UUID, Instant>) field.get(real)).put(groupId, at);
    }
}
