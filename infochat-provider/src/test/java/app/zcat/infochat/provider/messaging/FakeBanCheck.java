package app.zcat.infochat.provider.messaging;

/**
 * Records {@code banCheck.isBanned}; returns the {@link #banned} flag.
 * The flag is settable both at construction and by field mutation so
 * call sites can pick whichever wiring style their scenario reads best.
 */
final class FakeBanCheck extends BanCheck {
    private final CallLog log;
    boolean banned;

    FakeBanCheck(CallLog log) {
        this(log, false);
    }

    FakeBanCheck(CallLog log, boolean banned) {
        this.log = log;
        this.banned = banned;
    }

    @Override
    public boolean isBanned(String adapter, String contactId) {
        log.calls.add("banCheck.isBanned");
        return banned;
    }
}
