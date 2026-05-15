package io.infochat.ssrf;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/**
 * Enumerates the host's own non-loopback {@link InetAddress} bindings,
 * per {@code docs/spec/security.md} §SSRF and outbound connections:
 *
 * <blockquote>
 *   DNS-resolved IPs are checked against a blocklist of private,
 *   loopback, link-local, multicast, CGNAT, and cloud-metadata ranges
 *   (notably {@code 169.254.169.254} and IPv6 equivalents) <strong>plus
 *   the host's own non-loopback interfaces</strong>.
 * </blockquote>
 *
 * <p>The {@code IpBlocklist}'s no-arg constructor snapshots this set at
 * construction time and consults it on every {@link IpBlocklist#isBlocked}
 * call. The snapshot intentionally captures interfaces at JVM start; a
 * cloud VM whose IPs change after startup is treated as an
 * out-of-scope refresh-cadence concern (the spec does not commit to a
 * refresh policy).
 *
 * <p>The loopback exclusion ({@link InetAddress#isLoopbackAddress()})
 * is explicit because the literal loopback range ({@code 127.0.0.0/8},
 * {@code ::1}) is already covered by {@link IpBlocklist}'s range
 * checks; including loopback IPs here would be redundant, and the
 * spec text reads "non-loopback interfaces" verbatim.
 */
public final class HostInterfaceSet {

    private HostInterfaceSet() {
        // utility class
    }

    public static Set<InetAddress> enumerate() {
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            // System-boundary I/O failure. The spec's host-non-loopback
            // clause is unsatisfiable without OS interface enumeration,
            // so we surface the failure at module init rather than
            // silently degrading the defense surface to an empty set.
            throw new IllegalStateException(
                "could not enumerate host network interfaces", e);
        }
        if (interfaces == null) {
            // JDK contract: null means no interfaces are configured
            // on this host. Legitimate empty result.
            return Set.of();
        }
        Set<InetAddress> out = new HashSet<>();
        while (interfaces.hasMoreElements()) {
            NetworkInterface nic = interfaces.nextElement();
            Enumeration<InetAddress> addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr.isLoopbackAddress()) {
                    continue;
                }
                out.add(addr);
            }
        }
        return Set.copyOf(out);
    }
}
