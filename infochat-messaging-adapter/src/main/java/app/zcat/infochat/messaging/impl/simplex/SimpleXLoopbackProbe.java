package app.zcat.infochat.messaging.impl.simplex;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Defense-in-depth runtime guard (M1-430) that the simplex-chat chat-server
 * port — the credential-free bot WebSocket — is NOT reachable on any
 * non-loopback local interface.
 *
 * <p>{@code docs/spec/security.md} trust boundary #7 makes the unauthenticated
 * WebSocket safe only while it stays loopback: an off-loopback bind exposes a
 * surface that can drive the bot's SimpleX identity (the D10 trust anchor).
 * The pinned simplex-chat v6.5.4 binds its {@code -p} server to
 * {@code 127.0.0.1} only, but the configured binary path
 * ({@code infochat.adapters.simplex.binary}) is operator config and is NOT
 * pinned to the Docker artifact, so a host-installed / forked / upgraded
 * binary whose {@code -p} default binds {@code 0.0.0.0} would silently widen
 * the surface. This probe makes that state observable so the adapter can fail
 * fast instead of serving it.</p>
 *
 * <p>Mechanism (ticket §Notes): a loopback connect alone CANNOT detect a
 * {@code 0.0.0.0} bind — loopback is a subset of all-interfaces — so the probe
 * enumerates the host's non-loopback local addresses and attempts a
 * short-timeout TCP connect to {@code <addr>:<port>} on each. A successful
 * connect on a non-loopback address proves the server is listening there. The
 * probe is single-shot: callers invoke it only once the port is known to be
 * bound (the supervisor's bind check waits for readiness first), so a refused
 * connect means loopback-only, not not-yet-started.</p>
 */
final class SimpleXLoopbackProbe {

    /**
     * Per-address TCP connect timeout. The listener is local, so a real
     * off-loopback bind accepts immediately and a loopback-only bind refuses
     * (RST) immediately; this timeout only bounds the rare case of a
     * non-loopback address that silently drops the SYN (a host firewall).
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(250);

    private SimpleXLoopbackProbe() {
    }

    /**
     * @return {@code true} if {@code port} is reachable on at least one
     *     non-loopback local interface (off-loopback exposure); {@code false}
     *     if no non-loopback address accepts a connection (loopback-only, or
     *     the host has no non-loopback interface).
     */
    static boolean isExposedOffLoopback(int port) {
        for (InetAddress address : nonLoopbackLocalAddresses()) {
            if (isReachable(address, port)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The host's non-loopback local addresses, gathered from every up,
     * non-loopback {@link NetworkInterface}. NIC enumeration is an OS-level
     * system boundary, so its failures are caught here and treated as "no
     * off-loopback surface to probe" rather than propagated. Package-private so
     * {@code SimpleXLoopbackProbeTest} can skip its exposure assertion on a host
     * with no non-loopback interface (where it would be vacuous).
     */
    static List<InetAddress> nonLoopbackLocalAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            return addresses;
        }
        if (interfaces == null) {
            // The JDK contract permits a null enumeration when the host has no
            // interfaces; nothing to probe.
            return addresses;
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            try {
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
            } catch (SocketException e) {
                continue;
            }
            Enumeration<InetAddress> ifaceAddresses = iface.getInetAddresses();
            while (ifaceAddresses.hasMoreElements()) {
                InetAddress address = ifaceAddresses.nextElement();
                if (!address.isLoopbackAddress()) {
                    addresses.add(address);
                }
            }
        }
        return addresses;
    }

    private static boolean isReachable(InetAddress address, int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(address, port),
                    (int) CONNECT_TIMEOUT.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
