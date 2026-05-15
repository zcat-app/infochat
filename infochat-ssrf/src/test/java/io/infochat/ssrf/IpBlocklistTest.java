package io.infochat.ssrf;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

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
}
