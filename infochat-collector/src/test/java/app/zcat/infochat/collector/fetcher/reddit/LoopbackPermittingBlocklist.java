package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.ssrf.IpBlocklist;

import java.net.InetAddress;
import java.util.Set;

/**
 * Test-only blocklist that permits loopback addresses so the
 * in-process {@link com.sun.net.httpserver.HttpServer} on 127.0.0.1
 * is reachable through {@link app.zcat.infochat.ssrf.SsrfGuardedHttpClient}.
 */
final class LoopbackPermittingBlocklist extends IpBlocklist {

    @Override
    protected boolean isBlockedAgainst(InetAddress addr, Set<InetAddress> hostInterfaces) {
        if (addr.isLoopbackAddress()) {
            return false;
        }
        return super.isBlockedAgainst(addr, hostInterfaces);
    }
}
