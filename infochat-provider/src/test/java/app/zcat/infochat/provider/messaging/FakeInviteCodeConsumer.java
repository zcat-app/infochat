package app.zcat.infochat.provider.messaging;

/** Records {@code inviteCodeConsumer.consume}; returns the canned outcome. */
final class FakeInviteCodeConsumer extends InviteCodeConsumer {
    private final CallLog log;
    Outcome outcome = new Rejected();

    FakeInviteCodeConsumer(CallLog log) {
        this.log = log;
    }

    @Override
    public Outcome consume(String adapter, String contactId, String body) {
        log.calls.add("inviteCodeConsumer.consume");
        return outcome;
    }
}
