package io.infochat.ssrf;

import java.net.InetAddress;
import java.util.Set;
import java.util.function.Supplier;

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
 *       consulted PER CALL via the {@link Supplier} seam (M1-026
 *       Finding 3 remediation). The production no-arg constructor
 *       passes {@link HostInterfaceSet#enumerate} directly so each
 *       {@link #isBlocked} call sees the current set of interfaces
 *       — VPN tunnels, hot-plugged NICs, container bridges, K8s
 *       sidecar IPs, freshly-attached cloud EIPs brought up
 *       post-startup are seen on the very next call. M1-025
 *       snapshotted at construction, contradicting the spec's
 *       present-tense "are checked" clause.</li>
 * </ul>
 *
 * <p>The wrapper's default-mode (production) usage instantiates the
 * strict blocklist via the no-arg constructor, which supplies
 * {@code HostInterfaceSet::enumerate} as the per-call host-interface
 * provider. Tests that need a deterministic host-IP set use the
 * package-private constructors: a fixed {@link Set} overload
 * (preserved from M1-025; internally widens to a Supplier returning
 * the snapshot) or the Supplier overload (M1-026; lets tests
 * simulate post-startup interface changes via a mutable supplier).
 * Tests that also need to dial localhost supply a SUBCLASS that
 * overrides {@link #isBlocked} to carve out the loopback range.
 */
public class IpBlocklist {

    private final Supplier<Set<InetAddress>> hostInterfacesProvider;

    /**
     * Production constructor. The host's non-loopback interface
     * bindings are consulted PER CALL via {@link HostInterfaceSet#enumerate()}
     * — post-startup interfaces are seen on the next {@link #isBlocked}
     * invocation. The JNI {@code NetworkInterface.getNetworkInterfaces()}
     * call is cheap on hot paths.
     */
    public IpBlocklist() {
        this((Supplier<Set<InetAddress>>) HostInterfaceSet::enumerate);
    }

    /**
     * M1-025 test-mode constructor — preserved as an overload so
     * M1-025 tests pass unchanged. Internally widens to the Supplier
     * form via a defensive copy: the snapshot is captured once at
     * construction and returned by the supplier on every call, so
     * isBlocked semantics match the fixed-set intent.
     */
    IpBlocklist(Set<InetAddress> hostInterfaces) {
        this(() -> Set.copyOf(hostInterfaces));
    }

    /**
     * M1-026 test-mode constructor. Lets tests inject a per-call
     * host-interface set source — typically an
     * {@link java.util.concurrent.atomic.AtomicReference} that is
     * mutated mid-test to simulate a network interface coming up
     * after IpBlocklist construction.
     */
    IpBlocklist(Supplier<Set<InetAddress>> hostInterfacesProvider) {
        this.hostInterfacesProvider = hostInterfacesProvider;
    }

    public boolean isBlocked(InetAddress addr) {
        // M1-026 Finding 3: invoke the provider PER CALL so
        // post-startup interfaces (VPN, hot-plugged NIC, freshly-
        // attached cloud EIP) are seen on the very next isBlocked.
        // M1-025 snapshotted at construction; spec is present-tense
        // ("are checked") with no startup-snapshot qualifier.
        if (hostInterfacesProvider.get().contains(addr)) {
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

        // 0.0.0.0/8 — unspecified / "this host" (RFC 1122).
        if (b0 == 0) {
            return true;
        }
        // 255.255.255.255 — IPv4 limited broadcast (RFC 919).
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
        // 169.254.0.0/16 — link-local (covers 169.254.169.254).
        if (b0 == 169 && b1 == 254) {
            return true;
        }
        // 172.16.0.0/12 — RFC 1918 private.
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            return true;
        }
        // 192.168.0.0/16 — RFC 1918 private.
        if (b0 == 192 && b1 == 168) {
            return true;
        }
        // 100.64.0.0/10 — CGNAT (RFC 6598).
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return true;
        }
        // 224.0.0.0/4 — multicast.
        if (b0 >= 224 && b0 <= 239) {
            return true;
        }
        return false;
    }

    private static boolean isBlockedV6(byte[] raw) {
        if (isAllZeroV6(raw)) {
            return true;
        }
        if (isLoopbackV6(raw)) {
            return true;
        }
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        // fe80::/10 — link-local.
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
            return true;
        }
        // fc00::/7 — unique-local.
        if ((b0 & 0xFE) == 0xFC) {
            return true;
        }
        // ff00::/8 — multicast.
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
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
    }
}
