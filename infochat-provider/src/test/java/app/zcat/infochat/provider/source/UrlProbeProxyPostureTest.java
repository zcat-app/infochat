package app.zcat.infochat.provider.source;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * U-09: the relay-probe dial {@link HttpClient} is built with proxying
 * disabled. A WebSocket dial inherits its proxy selector from the
 * {@link HttpClient}, and a default-built client honors ambient JVM
 * proxy properties (http/https/socksProxyHost) — an ambient proxy would
 * re-resolve the relay host and void the validated peer IP + DNS pin
 * {@code SsrfGuardedHttpClient.checkAndPinForWebSocket} installs.
 */
class UrlProbeProxyPostureTest {

    @Test
    void relayDialClientDisablesAmbientProxy() {
        assertEquals(Optional.of(HttpClient.Builder.NO_PROXY),
            new UrlProbe().relayDialClient().proxy(),
            "the relay-probe dial client must be built with NO_PROXY so an "
            + "ambient JVM proxy cannot re-resolve the relay host and void the "
            + "DNS pin");
    }
}
