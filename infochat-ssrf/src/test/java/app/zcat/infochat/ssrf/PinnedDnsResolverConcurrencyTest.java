package app.zcat.infochat.ssrf;

import app.zcat.infochat.ssrf.PinnedDnsResolver.Provider;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency contract of the {@link Provider} per-host pin map: pin
 * isolation across hosts, refcounted lifetime for overlapping
 * same-host holders, and release-on-throw. Lookups are asserted by
 * composing a {@link PinnedDnsResolver} over
 * {@link Provider#activePinsSnapshot()} — the same composition the
 * JVM-wide forwarding resolver uses — rather than through
 * {@code InetAddress.getAllByName}, whose positive cache sits ABOVE
 * the resolver SPI and can mask pin/release transitions. Hostnames
 * are unique RFC 6761 {@code .invalid} names per test so no state
 * leaks between tests through the pin map or the JDK cache.
 */
class PinnedDnsResolverConcurrencyTest {

    private static final InetAddressResolver.LookupPolicy ANY_POLICY =
        InetAddressResolver.LookupPolicy.of(
            InetAddressResolver.LookupPolicy.IPV4 | InetAddressResolver.LookupPolicy.IPV6);

    /**
     * Delegate that fails the test if consulted: a pinned host must
     * NEVER fall through to the builtin resolver.
     */
    private static final InetAddressResolver REJECTING_DELEGATE = new InetAddressResolver() {

        @Override
        public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                throws UnknownHostException {
            throw new UnknownHostException(
                "delegate must not be consulted for a pinned host: " + host);
        }

        @Override
        public String lookupByAddress(byte[] addr) throws UnknownHostException {
            throw new UnknownHostException("reverse lookup not expected in these tests");
        }
    };

    private static List<InetAddress> lookupThroughActivePins(String host) throws Exception {
        return new PinnedDnsResolver(Provider.activePinsSnapshot(), REJECTING_DELEGATE)
            .lookupByName(host, ANY_POLICY)
            .toList();
    }

    @Test
    void pinTwoHostsConcurrentlyEachResolvesOwnAddresses() throws Exception {
        String hostA = "pin-isolation-a.invalid";
        String hostB = "pin-isolation-b.invalid";
        InetAddress addressA = InetAddress.getByName("192.0.2.1");
        InetAddress addressB = InetAddress.getByName("192.0.2.2");

        CountDownLatch bothPinned = new CountDownLatch(2);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> pinnerA = pool.submit(() -> {
                Provider.PinHandle pin = Provider.pin(hostA, List.of(addressA));
                bothPinned.countDown();
                try {
                    releaseA.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pin.release();
                }
                return null;
            });
            Future<?> pinnerB = pool.submit(() -> {
                Provider.PinHandle pin = Provider.pin(hostB, List.of(addressB));
                bothPinned.countDown();
                try {
                    releaseB.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pin.release();
                }
                return null;
            });
            assertTrue(bothPinned.await(5, TimeUnit.SECONDS),
                "both pinner threads must install their pins without blocking each other");

            // Both pins held simultaneously: each host resolves to
            // exactly its own validated list; neither observes or
            // disturbs the other's entry.
            assertEquals(List.of(addressA), lookupThroughActivePins(hostA),
                "host A must resolve to exactly its own validated addresses while host B is pinned");
            assertEquals(List.of(addressB), lookupThroughActivePins(hostB),
                "host B must resolve to exactly its own validated addresses while host A is pinned");

            releaseB.countDown();
            pinnerB.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(addressA), lookupThroughActivePins(hostA),
                "releasing host B's pin must leave host A's pin observable and unchanged");
            assertFalse(Provider.activePinsSnapshot().containsKey(hostB),
                "host B's entry must be gone after its only holder released");

            releaseA.countDown();
            pinnerA.get(5, TimeUnit.SECONDS);
            assertFalse(Provider.activePinsSnapshot().containsKey(hostA),
                "host A's entry must be gone after its only holder released");
        } finally {
            // Unblock the pinner threads on any assertion failure so
            // their finally arms release the pins (JVM-global state
            // must not leak into other tests).
            releaseA.countDown();
            releaseB.countDown();
            pool.shutdown();
        }
    }

    @Test
    void throwInsideGuardedSectionReleasesHostPin() {
        // Strict production blocklist + a seam mapping the host to
        // loopback: validation inside checkAndPinForWebSocket throws
        // BLOCKED_IP. The guarded section must leave no stale pin for
        // the host behind — a stale pin would survive as JVM-wide
        // resolver state.
        String host = "ws-throw-releases-pin.invalid";
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            new IpBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024,
            3,
            unused -> {
                try {
                    return List.of(InetAddress.getByName("127.0.0.1"));
                } catch (UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            });

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.checkAndPinForWebSocket(URI.create("wss://" + host + "/relay")));

        assertEquals(SsrfPolicyException.Reason.BLOCKED_IP, ex.reason(),
            "the strict blocklist must reject the loopback-resolving host");
        assertFalse(Provider.activePinsSnapshot().containsKey(host),
            "a throw inside the guarded section must not leave a stale pin for the host");
    }

    @Test
    void sameHostConcurrentHoldersDoNotReleaseEachOtherEarly() throws Exception {
        String host = "same-host-overlap.invalid";
        InetAddress firstValidation = InetAddress.getByName("192.0.2.10");
        InetAddress secondValidation = InetAddress.getByName("192.0.2.11");

        Provider.PinHandle firstHolder = Provider.pin(host, List.of(firstValidation));
        try {
            Provider.PinHandle secondHolder = Provider.pin(host, List.of(secondValidation));
            try {
                // Overlapping same-host pins serve the most recent
                // validation's set (latest-wins, documented on
                // Provider.pin): both sets passed the blocklist, and
                // the freshest validation is closest to its connect.
                assertEquals(List.of(secondValidation), lookupThroughActivePins(host),
                    "overlapping same-host pins must serve the most recent validation's addresses");

                firstHolder.release();
                assertEquals(List.of(secondValidation), lookupThroughActivePins(host),
                    "the first holder's release must not unpin the host while the second holder is active");
            } finally {
                secondHolder.release();
            }
            assertFalse(Provider.activePinsSnapshot().containsKey(host),
                "the entry must be removed once the last holder has released");
        } finally {
            // Idempotent: a no-op if the test body already released.
            firstHolder.release();
        }
    }

    @Test
    void releaseIsIdempotentAndCannotReleaseAnotherHoldersPin() throws Exception {
        String host = "double-release.invalid";
        InetAddress address = InetAddress.getByName("192.0.2.20");

        Provider.PinHandle firstHolder = Provider.pin(host, List.of(address));
        Provider.PinHandle secondHolder = Provider.pin(host, List.of(address));
        try {
            firstHolder.release();
            firstHolder.release();
            assertEquals(List.of(address), lookupThroughActivePins(host),
                "a double release of one handle must not decrement the refcount twice "
                + "and steal the surviving holder's pin");
        } finally {
            secondHolder.release();
        }
        assertFalse(Provider.activePinsSnapshot().containsKey(host),
            "the entry must be removed once the surviving holder releases");
    }
}
