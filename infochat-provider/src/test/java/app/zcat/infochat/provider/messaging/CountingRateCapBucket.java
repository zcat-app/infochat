package app.zcat.infochat.provider.messaging;

import java.util.UUID;

/**
 * Records {@code rateCapBucket.tryAcquire}; default returns
 * {@code true} (under-cap). The {@link #next} field flips the
 * return value for the over-cap scenario without changing the
 * recorded call name.
 */
final class CountingRateCapBucket extends RateCapBucket {
    private final CallLog log;
    boolean next = true;

    CountingRateCapBucket(CallLog log) {
        this.log = log;
    }

    @Override
    public boolean tryAcquire(String adapter, String contactId) {
        log.calls.add("rateCapBucket.tryAcquire");
        return next;
    }

    /**
     * Step 6 per-group command cap (M1-222 redteam follow-up). Outside
     * CDI the inherited implementation NPEs on its null
     * {@code @ConfigProperty} refill-window field, so the fake answers
     * directly: always under-cap (over-cap behavior is covered by
     * {@code InboundRouterTest}). Deliberately log-silent — the
     * intake-ordering tests pin precise call sequences (see the
     * {@code NoopConfirmStateService} rationale in
     * {@code InboundRouterIntakeOrderingTest}).
     */
    @Override
    public boolean tryAcquireGroupCommand(UUID groupId) {
        return true;
    }
}
