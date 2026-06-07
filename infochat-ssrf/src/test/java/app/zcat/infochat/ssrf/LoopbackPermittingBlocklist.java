package app.zcat.infochat.ssrf;

import java.net.InetAddress;

/**
 * Test-only {@link IpBlocklist} subclass that permits loopback so the
 * in-process {@code com.sun.net.httpserver.HttpServer} fixtures bound
 * to {@code 127.0.0.1} can be dialed, while still blocking every other
 * range (e.g. {@code 169.254.169.254}). Top-level rather than an inner
 * class because {@link PinnedDnsResolverConcurrencyTest} and
 * {@link SsrfGuardedHttpClientConcurrencyTest} share it.
 */
class LoopbackPermittingBlocklist extends IpBlocklist {

    @Override
    public boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            return false;
        }
        return super.isBlocked(addr);
    }
}
