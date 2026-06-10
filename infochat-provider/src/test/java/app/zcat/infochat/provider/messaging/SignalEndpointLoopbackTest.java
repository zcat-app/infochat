package app.zcat.infochat.provider.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/**
 * Loopback trust-model coverage for
 * {@link ProductionAdapterBeans#parseEndpoint}: signal-cli's
 * unauthenticated TCP JSON-RPC daemon is trusted only on loopback, so a
 * non-loopback endpoint host is rejected unless the operator sets the
 * explicit {@code infochat.adapters.signal.allow-non-loopback-endpoint}
 * opt-in. IP literals are used so the address resolves without DNS.
 */
class SignalEndpointLoopbackTest {

    @Test
    void loopbackEndpointIsAcceptedWithoutOptIn() {
        InetSocketAddress endpoint =
                ProductionAdapterBeans.parseEndpoint("127.0.0.1:7654", /* allowNonLoopback */ false);

        assertEquals(7654, endpoint.getPort());
        assertTrue(endpoint.getAddress().isLoopbackAddress(),
                "127.0.0.1 must resolve to a loopback address");
    }

    @Test
    void nonLoopbackEndpointIsRejectedWithoutOptIn() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ProductionAdapterBeans.parseEndpoint("10.0.0.5:7654", /* allowNonLoopback */ false));

        assertTrue(ex.getMessage().contains("allow-non-loopback-endpoint"),
                "rejection must point the operator at the opt-in property: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("10.0.0.5"),
                "rejection must name the offending host: " + ex.getMessage());
    }

    @Test
    void nonLoopbackEndpointIsAcceptedWithOptIn() {
        InetSocketAddress endpoint = assertDoesNotThrow(() ->
                ProductionAdapterBeans.parseEndpoint("10.0.0.5:7654", /* allowNonLoopback */ true));

        assertEquals(7654, endpoint.getPort());
        assertEquals("10.0.0.5", endpoint.getHostString(),
                "the opt-in path must still construct the operator's endpoint");
    }
}
