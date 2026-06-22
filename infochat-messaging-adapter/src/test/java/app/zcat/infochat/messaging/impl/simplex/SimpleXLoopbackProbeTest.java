package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
class SimpleXLoopbackProbeTest {

    @Test
    void boundToAllInterfacesReportsOffLoopbackExposure() throws Exception {
        // M1-430 acceptance item 1: a chat-server bound to 0.0.0.0 is reachable
        // on a non-loopback interface, so the probe reports exposure (true). The
        // probe needs a real non-loopback IPv4 address to dial (the 0.0.0.0
        // wildcard bind is IPv4); skip on a host that has none — the assertion
        // would be vacuous there.
        List<InetAddress> nonLoopbackIpv4 = SimpleXLoopbackProbe.nonLoopbackLocalAddresses()
                .stream()
                .filter(address -> address instanceof Inet4Address)
                .toList();
        assumeFalse(nonLoopbackIpv4.isEmpty(),
                "host has no non-loopback IPv4 interface; off-loopback probe is untestable here");
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("0.0.0.0", 0));
            int port = server.getLocalPort();
            assertTrue(SimpleXLoopbackProbe.isExposedOffLoopback(port),
                    "a 0.0.0.0 bind must be reported as off-loopback exposure");
        }
    }

    @Test
    void boundToLoopbackReportsLoopbackOnly() throws Exception {
        // M1-430 acceptance item 1: a chat-server bound to 127.0.0.1 is NOT
        // reachable on any non-loopback interface, so the probe reports
        // loopback-only (false) — the safe, trust-boundary-#7-honoring case.
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = server.getLocalPort();
            assertFalse(SimpleXLoopbackProbe.isExposedOffLoopback(port),
                    "a 127.0.0.1 bind must be reported as loopback-only");
        }
    }
}
