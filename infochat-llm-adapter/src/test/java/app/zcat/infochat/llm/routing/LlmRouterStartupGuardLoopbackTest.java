package app.zcat.infochat.llm.routing;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-243: pins the loopback decision primitive
 * {@link LlmRouterStartupGuard#everyAddressLoopback(InetAddress[])}.
 *
 * <p>The guard's {@code isLoopback} now resolves a host via
 * {@code InetAddress.getAllByName} (all records, not just the first) and
 * treats the host as loopback only when the resolution is non-empty AND
 * every resolved address is a loopback address. This closes the
 * first-IP-only gap: a multi-A-record host whose first record sorts
 * loopback while a sibling record is a public address would otherwise
 * pass the guard while the per-call client connects to the public sibling
 * — the silent post-body leak the guard exists to prevent.
 *
 * <p>Plain JUnit5 (no Quarkus boot): the predicate is exercised directly
 * with hand-built address arrays. Addresses are built from IP literals via
 * {@link InetAddress#getByName(String)}, which parses the literal without a
 * DNS lookup, so the mixed-result case — which cannot be produced
 * deterministically through real DNS — is stable offline.
 */
class LlmRouterStartupGuardLoopbackTest {

    @Test
    void singleLoopbackAddressIsLoopback() throws UnknownHostException {
        InetAddress[] resolved = {InetAddress.getByName("127.0.0.1")};
        assertTrue(LlmRouterStartupGuard.everyAddressLoopback(resolved));
    }

    @Test
    void allLoopbackAddressesAreLoopback() throws UnknownHostException {
        InetAddress[] resolved = {InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")};
        assertTrue(LlmRouterStartupGuard.everyAddressLoopback(resolved));
    }

    @Test
    void emptyResolutionIsNotLoopback() {
        assertFalse(LlmRouterStartupGuard.everyAddressLoopback(new InetAddress[0]));
    }

    @Test
    void mixedLoopbackAndPublicIsNotLoopback() throws UnknownHostException {
        InetAddress[] resolved = {InetAddress.getByName("127.0.0.1"), InetAddress.getByName("8.8.8.8")};
        assertFalse(LlmRouterStartupGuard.everyAddressLoopback(resolved));
    }
}
