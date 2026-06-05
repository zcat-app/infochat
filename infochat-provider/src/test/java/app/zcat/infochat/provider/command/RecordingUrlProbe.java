package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.source.UrlProbe;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recording {@link UrlProbe} subclass with per-URL canned probe
 * outcomes. An unmapped URL falls through to a SUCCESS with no
 * content-type so a dispatch test's probe doesn't need to be
 * seeded with the dispatch URL twice.
 */
final class RecordingUrlProbe extends UrlProbe {

    private final Map<String, ProbeResult> canned = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public ProbeResult probe(URI url) {
        callCount.incrementAndGet();
        return canned.getOrDefault(
                url.toString(),
                ProbeResult.success(200, Optional.empty()));
    }

    void setProbe(String url, ProbeResult result) {
        canned.put(url, result);
    }

    int callCount() {
        return callCount.get();
    }
}
