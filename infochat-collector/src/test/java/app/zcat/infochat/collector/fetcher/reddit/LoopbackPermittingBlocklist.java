package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.ssrf.IpBlocklist;

import java.net.InetAddress;

/**
 * Test-only blocklist that permits loopback addresses so the
 * in-process {@link com.sun.net.httpserver.HttpServer} on 127.0.0.1
 * is reachable through {@link app.zcat.infochat.ssrf.SsrfGuardedHttpClient}.
 */
final class LoopbackPermittingBlocklist extends IpBlocklist {

    @Override
    public boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            return false;
        }
        return super.isBlocked(addr);
    }
}
