package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.bundle.BundleLoader;

/**
 * Records each {@code bundleLoader.get(key)} call and returns a
 * stub string keyed on the bundle key (so each test can assert
 * the precise reply body without depending on en.properties).
 */
final class FakeBundleLoader extends BundleLoader {
    private final CallLog log;

    FakeBundleLoader(CallLog log) {
        this.log = log;
    }

    @Override
    public String get(String key) {
        log.calls.add("bundleLoader.get(" + key + ")");
        return stubFor(key);
    }

    // Same recorded shape and stub as the 1-arg accessor: existing
    // call-log assertions stay language-agnostic, and the real (never
    // loaded) bundle is never consulted.
    @Override
    public String get(String key, String langCode) {
        log.calls.add("bundleLoader.get(" + key + ")");
        return stubFor(key);
    }

    static String stubFor(String key) {
        return "bundle:" + key;
    }
}
