package app.zcat.infochat.provider.messaging;

/**
 * Records {@code setAdapterName} into the {@link CallLog}; the
 * recorded entry is the spec's "identity" gate (step 1 — adapter
 * name set BEFORE any size-cap / rate-cap / normalize work).
 */
final class RecordingInboundContext extends InboundContext {
    private final CallLog log;

    RecordingInboundContext(CallLog log) {
        this.log = log;
    }

    @Override
    public void setAdapterName(String adapterName) {
        log.calls.add("setAdapterName");
        super.setAdapterName(adapterName);
    }
}
