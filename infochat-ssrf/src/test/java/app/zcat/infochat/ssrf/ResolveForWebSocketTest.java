package app.zcat.infochat.ssrf;

import app.zcat.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Direct in-module coverage for
 * {@link SsrfGuardedHttpClient#resolveForWebSocket(java.net.URI)} — the
 * pin-free WebSocket re-resolution the Nostr peer-IP watcher calls each tick
 * (M1-498, 36#F1). The collector-side {@code NostrSsrfIT} exercises it
 * indirectly through the live watcher; these tests pin its contract directly:
 * an allowed host returns the validated address set, and any policy violation
 * raises the typed {@link SsrfPolicyException} — the guaranteed signal the
 * watcher's catch-as-divergence arm depends on.
 */
class ResolveForWebSocketTest {

    private static SsrfGuardedHttpClient clientWithSeam(Function<String, List<InetAddress>> seam) {
        return new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024,
            3,
            seam);
    }

    @Test
    void returnsValidatedAddressSetForAllowedHost() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        SsrfGuardedHttpClient client = clientWithSeam(host -> List.of(loopback));

        List<InetAddress> resolved =
            client.resolveForWebSocket(URI.create("wss://relay.example.test/"));

        assertEquals(List.of(loopback), resolved,
            "an allowed host must return exactly the seam-supplied validated address set");
    }

    @Test
    void throwsBlockedIpWhenReResolvedAddressIsBlocked() throws Exception {
        // 169.254.169.254 (cloud-metadata) stays blocked even under the
        // loopback-permitting blocklist — the mid-session-rebind arm: a host
        // that re-resolves to a blocked address must fail closed.
        InetAddress metadata = InetAddress.getByName("169.254.169.254");
        SsrfGuardedHttpClient client = clientWithSeam(host -> List.of(metadata));

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.resolveForWebSocket(URI.create("wss://relay.example.test/")));
        assertEquals(SsrfPolicyException.Reason.BLOCKED_IP, ex.reason(),
            "a blocked re-resolved address must raise BLOCKED_IP");
    }

    @Test
    void rejectsNonWebSocketScheme() {
        // The scheme gate runs before the resolver seam, so an http(s) URI is
        // rejected regardless of what the host would resolve to: resolveForWebSocket
        // accepts only {ws, wss}.
        SsrfGuardedHttpClient client = clientWithSeam(host -> List.of());

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.resolveForWebSocket(URI.create("https://relay.example.test/")));
        assertEquals(SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, ex.reason(),
            "resolveForWebSocket gates {ws, wss}; an https URI must be rejected with SCHEME_NOT_ALLOWED");
    }
}
