package app.zcat.infochat.provider.messaging;

/**
 * Factory for a pass-through {@link OutboundDelivery} used by the plain-JUnit
 * tests that construct beans ({@link InboundRouter}, {@link StageProgressNotifier})
 * directly and wire the {@code outboundDelivery} field by hand. The instance
 * delivers through whatever adapter it is handed with a no-op back-off sleeper
 * and recording doubles, so a successful send is a single adapter call and a
 * failure path is never exercised by these tests.
 */
final class TestOutboundDelivery {

    private TestOutboundDelivery() {
    }

    static OutboundDelivery passThrough() {
        return new OutboundDelivery(
                new RecordingAdminNotifier(), new RecordingGroupRepository(),
                3, 0L, 2.0, 3, millis -> { });
    }
}
