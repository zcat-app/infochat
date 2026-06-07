package app.zcat.infochat.ssrf;

import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.util.Arrays;
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
 *   <li>IPv6 site-local {@code fec0::/10} — deprecated by RFC 3879
 *       but still routed like private space by legacy resolvers and
 *       OS stacks; the spec's "IPv6 equivalents" of private ranges
 *       covers it.</li>
 *   <li>IPv6 unique-local {@code fc00::/7}</li>
 *   <li>IPv6 multicast {@code ff00::/8}</li>
 *   <li>IPv6 IPv4-mapped form ({@code ::ffff:0:0/96}) of every blocked
 *       IPv4 range — a naive v6-only check would let an attacker bypass
 *       the v4 blocklist by spelling {@code 127.0.0.1} as
 *       {@code ::ffff:127.0.0.1}.</li>
 *   <li>IPv6 transition forms that embed an IPv4 target — 6to4
 *       ({@code 2002::/16}), Teredo ({@code 2001:0000::/32}), the NAT64
 *       well-known prefix ({@code 64:ff9b::/96}), and the deprecated
 *       IPv4-compatible form ({@code ::a.b.c.d}). Each embedded IPv4 is
 *       decoded and routed through the v4 blocklist, so an attacker
 *       cannot reach a blocked v4 range (e.g. {@code 169.254.169.254})
 *       by spelling it as {@code 2002:a9fe:a9fe::} or {@code ::127.0.0.1}.
 *       The decoded IPv4 is also matched against the host's own
 *       interface set (next bullet): a transition form arrives as an
 *       {@code Inet6Address}, so the as-supplied host-interface check
 *       cannot catch a host's v4 binding spelled in transition form.</li>
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
 * package-private {@link Supplier} overload (M1-026; lets tests supply
 * a fixed set, or simulate post-startup interface changes via a
 * mutable supplier). Tests that also need to dial localhost supply a
 * SUBCLASS that overrides {@link #isBlocked} to carve out the loopback
 * range.
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
        Set<InetAddress> hostInterfaces = hostInterfacesProvider.get();
        if (hostInterfaces.contains(addr)) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            return isBlockedV4(raw);
        }
        // length == 16: IPv6. Native blocked ranges (::, ::1,
        // fe80::/10, fec0::/10, fc00::/7, ff00::/8) are checked first; if none
        // match, decode any embedded IPv4 (IPv4-mapped or one of the
        // transition formats) and route it through isBlockedV4 so an
        // attacker cannot reach a blocked v4 range by spelling it as an
        // IPv6 transition address (e.g. ::127.0.0.1, 2002:a9fe:a9fe::).
        if (isBlockedV6(raw)) {
            return true;
        }
        byte[] embedded = embeddedV4(raw);
        if (embedded == null) {
            return false;
        }
        // A transition-form host (6to4/Teredo/NAT64/IPv4-compatible)
        // arrives as a genuine Inet6Address, so the contains() check
        // above could not have matched a host's own v4 interface
        // binding — re-check the decoded IPv4 against the host-interface
        // set too. (The IPv4-mapped form ::ffff:a.b.c.d is normalized to
        // an Inet4Address by the JDK and so was already covered by the
        // contains() check; only the genuinely-16-byte transition forms
        // need this extra hop.)
        return isBlockedV4(embedded) || isHostInterfaceV4(embedded, hostInterfaces);
    }

    /**
     * True if the 4-byte IPv4 address {@code v4} matches any address in
     * the host's own non-loopback interface set. Extends the
     * host-interface clause to the IPv4 embedded in an IPv6 transition
     * form, which arrives as an {@link java.net.Inet6Address} and so
     * cannot be matched by the {@link Set#contains} check on the
     * as-supplied address.
     */
    private static boolean isHostInterfaceV4(byte[] v4, Set<InetAddress> hostInterfaces) {
        for (InetAddress hostAddr : hostInterfaces) {
            if (Arrays.equals(hostAddr.getAddress(), v4)) {
                return true;
            }
        }
        return false;
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
        // fec0::/10 — deprecated site-local (RFC 3879).
        if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) {
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

    /**
     * Decode the IPv4 address embedded in an IPv6 transition / mapping
     * format, or {@code null} if {@code raw} is not one of them. The
     * caller routes a non-null result through {@link #isBlockedV4} so a
     * blocked v4 range cannot be reached by spelling it as an IPv6
     * address. {@code ::} and {@code ::1} never reach here — they are
     * caught by {@link #isBlockedV6} before the embedded-v4 decode.
     */
    private static byte @Nullable [] embeddedV4(byte[] raw) {
        // ::ffff:a.b.c.d — IPv4-mapped (bytes 0-9 zero, 10-11 == ffff).
        if (isIpv4Mapped(raw)) {
            return new byte[] { raw[12], raw[13], raw[14], raw[15] };
        }
        // 2002:a.b.c.d::/48 — 6to4 (RFC 3056); IPv4 in bytes 2-5.
        if ((raw[0] & 0xFF) == 0x20 && (raw[1] & 0xFF) == 0x02) {
            return new byte[] { raw[2], raw[3], raw[4], raw[5] };
        }
        // 2001:0000::/32 — Teredo (RFC 4380); the client global IPv4 is
        // in bytes 12-15, obfuscated by XOR 0xFFFFFFFF.
        if ((raw[0] & 0xFF) == 0x20 && (raw[1] & 0xFF) == 0x01
                && raw[2] == 0 && raw[3] == 0) {
            return new byte[] {
                (byte) (raw[12] ^ 0xFF), (byte) (raw[13] ^ 0xFF),
                (byte) (raw[14] ^ 0xFF), (byte) (raw[15] ^ 0xFF) };
        }
        // 64:ff9b::/96 — NAT64 well-known prefix (RFC 6052); IPv4 in
        // bytes 12-15, with bytes 4-11 zero in the well-known prefix.
        if ((raw[0] & 0xFF) == 0x00 && (raw[1] & 0xFF) == 0x64
                && (raw[2] & 0xFF) == 0xFF && (raw[3] & 0xFF) == 0x9B
                && allZero(raw, 4, 12)) {
            return new byte[] { raw[12], raw[13], raw[14], raw[15] };
        }
        // ::a.b.c.d — deprecated IPv4-compatible (RFC 4291); bytes 0-11
        // zero, IPv4 in bytes 12-15.
        if (allZero(raw, 0, 12)) {
            return new byte[] { raw[12], raw[13], raw[14], raw[15] };
        }
        return null;
    }

    private static boolean allZero(byte[] raw, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
