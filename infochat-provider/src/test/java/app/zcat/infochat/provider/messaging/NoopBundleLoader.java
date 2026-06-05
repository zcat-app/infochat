package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.bundle.BundleLoader;

/**
 * No-op {@link BundleLoader} — returns a deterministic stub value if
 * asked. For tests that assert nothing about bundle bodies; exists to
 * avoid a null-field NPE if a code path consults BundleLoader.
 */
final class NoopBundleLoader extends BundleLoader {
    @Override
    public String get(String key) {
        return "noop:" + key;
    }
}
