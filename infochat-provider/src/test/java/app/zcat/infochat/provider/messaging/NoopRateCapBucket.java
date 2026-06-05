package app.zcat.infochat.provider.messaging;

/** No-op {@link RateCapBucket} — always admits ({@code tryAcquire} = true). */
final class NoopRateCapBucket extends RateCapBucket {
    @Override
    public boolean tryAcquire(String adapter, String contactId) {
        return true;
    }
}
