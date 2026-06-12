package app.zcat.infochat.collector.stream.nostr;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * U-09: the shared relay-dial {@link HttpClient} the nostr WebSocket
 * connections dial through is built with proxying disabled. A WebSocket
 * dial inherits its proxy selector from the {@link HttpClient}, and a
 * default-built client honors ambient JVM proxy properties
 * (http/https/socksProxyHost) — an ambient proxy would re-resolve the
 * relay host and void the validated peer IP + DNS pin that
 * {@code SsrfGuardedHttpClient.checkAndPinForWebSocket} installs. The
 * builder is the only lever (there is no per-dial proxy override).
 */
class NostrStreamSourceProxyPostureTest {

    @Test
    void relayDialClientDisablesAmbientProxy() {
        assertEquals(Optional.of(HttpClient.Builder.NO_PROXY),
            NostrStreamSource.newRelayDialClient().proxy(),
            "the relay-dial HttpClient must be built with NO_PROXY so an "
            + "ambient JVM proxy cannot re-resolve the relay host and void "
            + "the DNS pin the WebSocket dial relies on");
    }
}
