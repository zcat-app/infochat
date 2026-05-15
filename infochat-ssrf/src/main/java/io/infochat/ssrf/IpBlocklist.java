package io.infochat.ssrf;

import java.net.InetAddress;
import java.util.Set;

/**
 * Range-matcher for IPv4 and IPv6 addresses that
 * {@link SsrfGuardedHttpClient} MUST refuse to dial. The ranges are
 * spec-mandated by {@code docs/spec/security.md} §SSRF and outbound
 * connections (decision D20):
 *
 * <ul>
 *   <li>IPv4 loopback {@code 127.0.0.0/8}</li>
 *   <li>IPv4 unspecified {@code 0.0.0.0/8} — the kernel rewrites
 *       {@code connect(0.0.0.0)} to {@code connect(127.0.0.1)} on
 *       Linux/BSD/Windows, making {@code 0.0.0.0} a loopback bypass
 *       form. Spec's "loopback" intent covers its kernel-level
 *       bypasses.</li>
 *   <li>IPv4 limited broadcast {@code 255.255.255.255} — RFC 919.</li>
 *   <li>IPv4 link-local {@code 169.254.0.0/16}
 *       (covers the AWS / GCP / OpenStack cloud-metadata address
 *       {@code 169.254.169.254} by virtue of being in this range)</li>
 *   <li>IPv4 multicast {@code 224.0.0.0/4}</li>
 *   <li>IPv4 private {@code 10.0.0.0/8}, {@code 172.16.0.0/12},
 *       {@code 192.168.0.0/16}</li>
 *   <li>IPv4 CGNAT {@code 100.64.0.0/10}</li>
 *   <li>IPv6 loopback {@code ::1}</li>
 *   <li>IPv6 unspecified {@code ::} — the IPv6 analog of
 *       {@code 0.0.0.0}; kernel-level bypass of loopback.</li>
 *   <li>IPv6 link-local {@code fe80::/10}</li>
 *   <li>IPv6 unique-local {@code fc00::/7}</li>
 *   <li>IPv6 multicast {@code ff00::/8}</li>
 *   <li>IPv6 IPv4-mapped form ({@code ::ffff:0:0/96}) of every blocked
 *       IPv4 range — a naive v6-only check would let an attacker bypass
 *       the v4 blocklist by spelling {@code 127.0.0.1} as
 *       {@code ::ffff:127.0.0.1}.</li>
 *   <li>The host's own non-loopback {@link InetAddress} bindings,
 *       snapshotted at construction time via
 *       {@link HostInterfaceSet#enumerate()}. The spec's
 *       "plus the host's own non-loopback interfaces" clause prevents
 *       routing a feed URL to the box's own public IP, which would
 *       bypass perimeter filtering.</li>
 * </ul>
 *
 * <p>Range data is encoded in the byte-level checks below; the
 * host-interface set is the only per-instance state, captured once
 * at construction and never mutated thereafter.
 *
 * <p>The wrapper's default-mode (production) usage instantiates the
 * strict blocklist via the no-arg constructor. Tests that need a
 * deterministic host-IP set independent of the test machine's
 * network configuration use the package-private constructor accepting
 * an explicit {@link Set} of host {@link InetAddress}es. Tests that
 * also need to dial a localhost
 * {@link com.sun.net.httpserver.HttpServer com.sun.net.httpserver.HttpServer}
 * fixture supply a SUBCLASS that overrides {@link #isBlocked} to
 * carve out the loopback range. Both seams are deliberate API
 * surfaces — accidentally enabling either in production requires
 * writing code that visibly subclasses or uses the package-private
 * constructor, which is impossible to do by configuration alone.
 */
public class IpBlocklist {

    private final Set<InetAddress> hostInterfaces;

    /**
     * Production constructor. Snapshots the host's non-loopback
     * interface bindings at construction via
     * {@link HostInterfaceSet#enumerate()} so subsequent
     * {@link #isBlocked} calls do not incur per-call OS calls.
     */
    public IpBlocklist() {
        this(HostInterfaceSet.enumerate());
    }

    /**
     * Test-mode constructor. Lets tests inject a deterministic
     * host-IP set without depending on the test machine's network
     * configuration. Package-private — production callers must use
     * the no-arg constructor.
     */
    IpBlocklist(Set<InetAddress> hostInterfaces) {
        this.hostInterfaces = Set.copyOf(hostInterfaces);
    }

    public boolean isBlocked(InetAddress addr) {
        // Host-interface check first: cheap equality, common case
        // for an empty (test-mode) set is a zero-cost contains.
        if (hostInterfaces.contains(addr)) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            return isBlockedV4(raw);
        }
        // length == 16: IPv6, possibly IPv4-mapped (::ffff:0:0/96).
        if (isIpv4Mapped(raw)) {
            byte[] mapped = new byte[] { raw[12], raw[13], raw[14], raw[15] };
            return isBlockedV4(mapped);
        }
        return isBlockedV6(raw);
    }

    private static boolean isBlockedV4(byte[] raw) {
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        int b2 = raw[2] & 0xFF;
        int b3 = raw[3] & 0xFF;

        // 0.0.0.0/8 — unspecified / "this host" (RFC 1122). The kernel
        // rewrites connect(0.0.0.0) -> connect(127.0.0.1) on
        // Linux/BSD/Windows, so this is a loopback bypass that the
        // spec's "loopback" intent must cover.
        if (b0 == 0) {
            return true;
        }
        // 255.255.255.255 — IPv4 limited broadcast (RFC 919). Sending
        // here is meaningless from a userland fetcher and risks
        // hitting the local segment.
        if (b0 == 0xFF && b1 == 0xFF && b2 == 0xFF && b3 == 0xFF) {
            return true;
        }
        // 127.0.0.0/8 — loopback.
        if (b0 == 127) {
            return true;
        }
        // 10.0.0.0/8 — RFC 1918 private.
        if (b0 == 10) {
            return true;
        }
        // 169.254.0.0/16 — link-local (includes the 169.254.169.254
        // cloud-metadata address that the AWS / GCP / OpenStack
        // metadata service binds).
        if (b0 == 169 && b1 == 254) {
            return true;
        }
        // 172.16.0.0/12 — RFC 1918 private. Second octet 16..31.
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            return true;
        }
        // 192.168.0.0/16 — RFC 1918 private.
        if (b0 == 192 && b1 == 168) {
            return true;
        }
        // 100.64.0.0/10 — CGNAT (RFC 6598). Second octet 64..127.
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return true;
        }
        // 224.0.0.0/4 — multicast. First octet 224..239.
        if (b0 >= 224 && b0 <= 239) {
            return true;
        }
        return false;
    }

    private static boolean isBlockedV6(byte[] raw) {
        // :: — IPv6 unspecified address (all sixteen bytes zero).
        // The IPv6 analog of 0.0.0.0; a kernel-level bypass of
        // loopback that the spec's "loopback" intent must cover.
        if (isAllZeroV6(raw)) {
            return true;
        }
        // ::1 — IPv6 loopback. Fifteen zero bytes followed by 0x01.
        if (isLoopbackV6(raw)) {
            return true;
        }
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        // fe80::/10 — link-local. First byte 0xFE, top two bits of
        // second byte == 10b (i.e. 0x80 or 0x90 ... 0xBF).
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
            return true;
        }
        // fc00::/7 — unique-local. Top 7 bits of first byte 1111110;
        // 0xFC or 0xFD.
        if ((b0 & 0xFE) == 0xFC) {
            return true;
        }
        // ff00::/8 — multicast. First byte 0xFF.
        if (b0 == 0xFF) {
            return true;
        }
        return false;
    }

    private static boolean isLoopbackV6(byte[] raw) {
        for (int i = 0; i < 15; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[15] == 1;
    }

    private static boolean isAllZeroV6(byte[] raw) {
        for (int i = 0; i < 16; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        // ::ffff:0:0/96 → first ten bytes zero, bytes 10 and 11 are 0xFF.
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
    }
}
