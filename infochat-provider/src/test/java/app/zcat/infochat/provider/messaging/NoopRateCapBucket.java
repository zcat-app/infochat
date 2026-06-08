package app.zcat.infochat.provider.messaging;

/** No-op {@link RateCapBucket} — always admits ({@code tryAcquire} = true). */
final class NoopRateCapBucket extends RateCapBucket {
    // M1-229: override the 3-arg form the router now calls (per-id vs
    // shared-stranger split); the registered flag is ignored — this
    // double admits everything.
    @Override
    public boolean tryAcquire(String adapter, String contactId, boolean registered) {
        return true;
    }
}
