package app.zcat.infochat.ssrf;

import java.net.InetAddress;
import java.util.Set;

/**
 * Test-only {@link IpBlocklist} subclass that permits loopback so the
 * in-process {@code com.sun.net.httpserver.HttpServer} fixtures bound
 * to {@code 127.0.0.1} can be dialed, while still blocking every other
 * range (e.g. {@code 169.254.169.254}). Top-level rather than an inner
 * class because {@link PinnedDnsResolverConcurrencyTest} and
 * {@link SsrfGuardedHttpClientConcurrencyTest} share it.
 */
class LoopbackPermittingBlocklist extends IpBlocklist {

    // Override the shared core check rather than isBlocked(addr): both
    // the single-address isBlocked and the batch firstBlocked funnel
    // through isBlockedAgainst, so carving out loopback here applies to
    // whichever path SsrfGuardedHttpClient uses to consult the blocklist.
    @Override
    protected boolean isBlockedAgainst(InetAddress addr, Set<InetAddress> hostInterfaces) {
        if (addr.isLoopbackAddress()) {
            return false;
        }
        return super.isBlockedAgainst(addr, hostInterfaces);
    }
}
