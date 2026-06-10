package app.zcat.infochat.provider.messaging;

/** No-op {@link RegisteredContactSet} — reports every contact unregistered
 *  (the conservative stranger route, matching a freshly seeded empty set). */
final class NoopRegisteredContactSet extends RegisteredContactSet {
    @Override
    public boolean isRegistered(String adapter, String contactId) {
        return false;
    }
}
