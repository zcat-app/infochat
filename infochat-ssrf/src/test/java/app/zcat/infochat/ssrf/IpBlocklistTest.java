package app.zcat.infochat.ssrf;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for {@link IpBlocklist}: one {@code @Test}
 * per distinct address shape to keep the IDENTICAL-AGGREGATE
 * acceptance grep stable. Blocked-addresses are spec-mandated by
 * {@code docs/spec/security.md} §SSRF and outbound connections.
 */
class IpBlocklistTest {

    private final IpBlocklist blocklist = new IpBlocklist();

    @Test
    void blocksIpv4Loopback() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("127.0.0.1")),
            "127.0.0.0/8 is loopback per spec");
    }

    @Test
    void blocksIpv4LoopbackUpperBoundary() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("127.255.255.254")),
            "the entire 127.0.0.0/8 range must block, not just 127.0.0.1");
    }

    @Test
    void blocksAwsCloudMetadataAddress() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("169.254.169.254")),
            "169.254.169.254 (AWS / GCP / OpenStack cloud metadata) "
            + "must block as part of the 169.254.0.0/16 link-local range");
    }

    @Test
    void blocksIpv4PrivateClassA() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("10.0.0.1")),
            "10.0.0.0/8 is RFC 1918 private per spec");
    }

    @Test
    void blocksIpv4PrivateClassB() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("172.16.0.1")),
            "172.16.0.0/12 is RFC 1918 private per spec");
    }

    @Test
    void blocksIpv4PrivateClassBUpperBoundary() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("172.31.255.254")),
            "172.16.0.0/12 must extend up through 172.31.x.x");
    }

    @Test
    void blocksIpv4PrivateClassC() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("192.168.1.1")),
            "192.168.0.0/16 is RFC 1918 private per spec");
    }

    @Test
    void blocksIpv4Cgnat() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("100.64.0.1")),
            "100.64.0.0/10 is the RFC 6598 CGNAT range");
    }

    @Test
    void blocksIpv4Multicast() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("224.0.0.1")),
            "224.0.0.0/4 is the IPv4 multicast range");
    }

    @Test
    void blocksIpv6Loopback() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("::1")),
            "::1 is IPv6 loopback");
    }

    @Test
    void blocksIpv6LinkLocal() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("fe80::1")),
            "fe80::/10 is IPv6 link-local");
    }

    @Test
    void blocksIpv6UniqueLocal() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("fc00::1")),
            "fc00::/7 is IPv6 unique-local");
    }

    @Test
    void blocksIpv6Multicast() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("ff02::1")),
            "ff00::/8 is IPv6 multicast");
    }

    @Test
    void blocksIpv4MappedIpv6Loopback() throws UnknownHostException {
        // ::ffff:127.0.0.1 — IPv4-mapped form of 127.0.0.1. A naive
        // IPv6-only check would miss this; the mapped-v4 delegation
        // step is the load-bearing piece.
        assertTrue(blocklist.isBlocked(InetAddress.getByName("::ffff:127.0.0.1")),
            "::ffff:127.0.0.1 must block via the IPv4-mapped delegation");
    }

    @Test
    void blocksIpv4MappedAwsMetadata() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("::ffff:169.254.169.254")),
            "the IPv4-mapped form of 169.254.169.254 must block — "
            + "otherwise the cloud-metadata bypass survives");
    }

    @Test
    void allowsGooglePublicDns() throws UnknownHostException {
        assertFalse(blocklist.isBlocked(InetAddress.getByName("8.8.8.8")),
            "8.8.8.8 is a public address; must NOT be blocked");
    }

    @Test
    void allowsCloudflarePublicDns() throws UnknownHostException {
        assertFalse(blocklist.isBlocked(InetAddress.getByName("1.1.1.1")),
            "1.1.1.1 is a public address; must NOT be blocked");
    }

    @Test
    void allowsPublicIpv6() throws UnknownHostException {
        assertFalse(blocklist.isBlocked(InetAddress.getByName("2606:4700:4700::1111")),
            "2606:4700:4700::1111 is a public IPv6 address; must NOT be blocked");
    }

    // -----------------------------------------------------------------
    // M1-025 host-interface seam (Finding 1: INFO-LEAK / high). The
    // spec's "plus the host's own non-loopback interfaces" clause
    // must be enforceable with a deterministic host-IP set so tests
    // do not depend on the test machine's network configuration.
    // -----------------------------------------------------------------

    @Test
    void hostInterfaceIpIsBlocked() throws UnknownHostException {
        // 203.0.113.5 is TEST-NET-3 (RFC 5737), reserved for
        // documentation and never assigned in the real internet —
        // so it cannot be the test machine's actual interface IP
        // by accident.
        InetAddress hostIp = InetAddress.getByName("203.0.113.5");
        IpBlocklist withHost = new IpBlocklist(Set.of(hostIp));
        assertTrue(withHost.isBlocked(InetAddress.getByName("203.0.113.5")),
            "an IP in the host-interface set must block; "
            + "spec's \"host's own non-loopback interfaces\" clause");
    }

    @Test
    void nonHostPublicIpStillAllowed() throws UnknownHostException {
        // Negative control: the host-IP seam adds host IPs WITHOUT
        // affecting the public-IP allowlist. 8.8.8.8 must still pass.
        InetAddress hostIp = InetAddress.getByName("203.0.113.5");
        IpBlocklist withHost = new IpBlocklist(Set.of(hostIp));
        assertFalse(withHost.isBlocked(InetAddress.getByName("8.8.8.8")),
            "a public IP not in the host-interface set must remain "
            + "allowed even when the seam is populated");
    }

    // -----------------------------------------------------------------
    // M1-025 loopback bypass forms (Finding 3: INFO-LEAK / medium).
    // Spec lists "loopback" as a blocked category. On Linux/BSD/
    // Windows the kernel rewrites connect(0.0.0.0) -> connect(127.0.0.1),
    // so 0.0.0.0 is a loopback bypass; ::/0 is the IPv6 analog;
    // 255.255.255.255 is limited broadcast. All three are spec-
    // intent loopback bypasses that the literal range checks would
    // otherwise miss.
    // -----------------------------------------------------------------

    @Test
    void unspecifiedV4IsBlocked() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("0.0.0.0")),
            "0.0.0.0 (IPv4 unspecified / 'this host', RFC 1122) is a "
            + "loopback bypass per kernel connect-rewrite behavior");
    }

    @Test
    void unspecifiedV6IsBlocked() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("::")),
            ":: (IPv6 unspecified) is the IPv6 analog of 0.0.0.0; "
            + "spec's loopback intent must cover its bypass forms");
    }

    @Test
    void limitedBroadcastIsBlocked() throws UnknownHostException {
        assertTrue(blocklist.isBlocked(InetAddress.getByName("255.255.255.255")),
            "255.255.255.255 (IPv4 limited broadcast, RFC 919) "
            + "must block — sending here from userland is meaningless "
            + "and risks hitting the local segment");
    }

    // -----------------------------------------------------------------
    // M1-026 per-call host-interface enumeration (Finding 3:
    // INFO-LEAK / low). Spec text is present-tense ("are checked"),
    // no startup-snapshot qualifier. M1-025 snapshotted at IpBlocklist
    // construction, so post-startup interfaces (VPN tunnels,
    // hot-plugged NICs, K8s sidecar IPs, freshly-attached cloud
    // EIPs) were never seen. M1-026 widens the host-interface field
    // from a frozen Set to a Supplier consulted per call.
    // -----------------------------------------------------------------

    @Test
    void hostInterfaceAddedAfterStartupIsBlocked() throws UnknownHostException {
        // 203.0.113.5 is TEST-NET-3 (RFC 5737), reserved for
        // documentation and never assigned in the real internet —
        // so it cannot be the test machine's actual interface IP
        // by accident.
        InetAddress later = InetAddress.getByName("203.0.113.5");
        AtomicReference<Set<InetAddress>> ref = new AtomicReference<>(Set.of());
        IpBlocklist blocklist = new IpBlocklist(ref::get);

        assertFalse(blocklist.isBlocked(later),
            "203.0.113.5 is TEST-NET-3 (not in any blocked range) "
            + "and the host-interface set is initially empty; "
            + "isBlocked must return false");

        // Simulate an interface coming up post-startup (VPN, NIC,
        // sidecar, cloud EIP). The Supplier seam means the next
        // isBlocked call must see the new IP — no snapshot caching.
        ref.set(Set.of(later));

        assertTrue(blocklist.isBlocked(later),
            "after the simulated interface is added to the host-set, "
            + "the very next isBlocked call must see it via the "
            + "per-call Supplier.get() invocation. If the M1-025 "
            + "snapshot-at-construction semantics had survived, "
            + "this assertion would fail");
    }
}
