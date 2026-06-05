package app.zcat.infochat.provider.messaging;

/**
 * No-op {@link InviteCodeConsumer} — never invoked, because the tests
 * that wire it use a router whose user lookup returns a non-empty
 * snapshot, so step 2 (DM unknown contact) does not fire. Throws to
 * fail loudly if a future change starts consuming it.
 */
final class NoopInviteCodeConsumer extends InviteCodeConsumer {
    @Override
    public Outcome consume(String adapter, String contactId, String body) {
        throw new UnsupportedOperationException(
                "inviteCodeConsumer must not run when the user snapshot is non-empty");
    }
}
