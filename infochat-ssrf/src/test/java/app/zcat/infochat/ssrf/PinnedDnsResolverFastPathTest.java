package app.zcat.infochat.ssrf;

import app.zcat.infochat.ssrf.PinnedDnsResolver.Provider;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M1-277 (M-S2) fast-path contract of the JVM-wide forwarding
 * resolver: with a pin active, a pinned host resolves to exactly its
 * pinned addresses (exact canonical-key probe of the live pin map —
 * no snapshot composition), and an unpinned host delegates to the
 * builtin exactly as it would with no pin active. Lookups go through
 * {@link InetAddress#getAllByName} so the REAL JVM-installed
 * forwarding resolver runs; every hostname is a unique RFC 6761
 * {@code .invalid} name used at most once per cache-relevant form, so
 * the JDK's positive/negative caches (which sit ABOVE the resolver
 * SPI) cannot mask the path under test.
 */
class PinnedDnsResolverFastPathTest {

    @Test
    void pinnedAndUnpinnedHostsResolveIdenticallyWhilePinActive() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByName("192.0.2.30");
        Provider.PinHandle pin =
            Provider.pin("fastpath-pinned.invalid", List.of(pinnedAddress));
        try {
            assertEquals(List.of(pinnedAddress),
                List.of(InetAddress.getAllByName("fastpath-pinned.invalid")),
                "a pinned host must resolve to exactly its pinned addresses "
                + "through the JVM-wide resolver's exact-probe fast path");
            assertThrows(UnknownHostException.class,
                () -> InetAddress.getAllByName("fastpath-unpinned.invalid"),
                "an unpinned host must delegate to the builtin while a pin "
                + "is active — .invalid is RFC 6761 never-resolvable, so "
                + "delegation surfaces UnknownHostException exactly as with "
                + "no pin active");
        } finally {
            pin.release();
        }
    }

    @Test
    void fastPathCanonicalizesBeforeExactProbe() throws Exception {
        // The pin map is keyed by canonical form; the JDK may pass a
        // case variant or trailing-dot form to the resolver SPI. The
        // exact probe must canonicalize FIRST or the pin would miss and
        // the builtin would reject the .invalid name. The mixed-case
        // form below is the first lookup of this name in the JVM, so
        // the JDK cache cannot serve it.
        InetAddress pinnedAddress = InetAddress.getByName("192.0.2.31");
        Provider.PinHandle pin =
            Provider.pin("fastpath-case.invalid", List.of(pinnedAddress));
        try {
            assertEquals(List.of(pinnedAddress),
                List.of(InetAddress.getAllByName("FASTPATH-Case.INVALID")),
                "a case-variant lookup of a pinned host must hit the pin — "
                + "the fast path must canonicalize before the exact get");
        } finally {
            pin.release();
        }
    }
}
