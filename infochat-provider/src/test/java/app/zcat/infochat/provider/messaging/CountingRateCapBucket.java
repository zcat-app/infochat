package app.zcat.infochat.provider.messaging;

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
}
