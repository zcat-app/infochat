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

    // M1-705: the router's cheap-command draw would NPE on the null
    // @ConfigProperty window field outside CDI; this double admits
    // everything (the M1-222 precedent for the group buckets in
    // AdmitAllRateCapBucket).
    @Override
    public boolean tryAcquireCheapCommand(String adapter, String contactId) {
        return true;
    }
}
