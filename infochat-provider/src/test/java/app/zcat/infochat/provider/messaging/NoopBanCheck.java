package app.zcat.infochat.provider.messaging;

/** No-op {@link BanCheck} — always reports {@code is_banned=false}. */
final class NoopBanCheck extends BanCheck {
    @Override
    public boolean isBanned(String adapter, String contactId) {
        return false;
    }
}
