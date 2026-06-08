package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Wiring assertion for M1-202 item 5: {@link NostrStreamSource.Registrar} must
 * obtain its {@link SsrfGuardedHttpClient} from CDI (the
 * {@code CollectorSsrfClientProducer} {@code @Produces} method) rather than
 * constructing its own at field init.
 *
 * <p>Asserts the Registrar's client is the exact CDI-produced singleton — which
 * proves both that the {@code @Inject} is wired and that the producer is the one
 * shared source of the guard. The accessor (not a direct field read) routes
 * through the {@code @ApplicationScoped} client proxy to the contextual bean.
 */
@QuarkusTest
class NostrRegistrarSsrfWiringIT {

    @Inject
    SsrfGuardedHttpClient producedClient;

    @Inject
    NostrStreamSource.Registrar registrar;

    @Test
    void registrarResolvesToCdiProducedSsrfClient() {
        assertSame(producedClient, registrar.ssrfClient(),
            "Registrar must inject the CDI-produced SsrfGuardedHttpClient, not construct its own");
    }
}
