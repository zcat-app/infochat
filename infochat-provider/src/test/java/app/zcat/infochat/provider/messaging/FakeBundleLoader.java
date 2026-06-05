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

    static String stubFor(String key) {
        return "bundle:" + key;
    }
}
