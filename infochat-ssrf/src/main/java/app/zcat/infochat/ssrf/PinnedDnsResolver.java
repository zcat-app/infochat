package app.zcat.infochat.ssrf;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Implements {@link InetAddressResolver} (Java 18+ SPI; this project
 * runs on JDK 25). Given a pin map of hostname → preferred IPs and a
 * delegate {@link InetAddressResolver} (typically the JDK builtin),
 * returns the pinned IPs for hosts in the map and DELEGATES to the
 * supplied delegate for any unmapped host.
 *
 * <p>The pinning serves the spec's "DNS-rebind defense" promise
 * ({@code docs/spec/security.md} §SSRF and outbound connections):
 * after the wrapper has resolved and IP-validated a hostname, the
 * subsequent {@code HttpClient.send} must connect to those SAME IPs
 * — the JDK must not perform an independent DNS lookup between
 * validate and connect, because a DNS-rebind attacker could return
 * a public IP at validate-time and a private IP at connect-time for
 * the same hostname.
 *
 * <p>The JDK 25 {@code InetAddressResolverProvider} SPI loads exactly
 * ONE resolver per JVM via {@link java.util.ServiceLoader} at startup;
 * there is no per-{@link java.net.http.HttpClient HttpClient} or
 * per-call scoping. Per-call pinning effect is achieved by the
 * {@link Provider} nested class — a JVM-wide forwarding resolver
 * registered via {@code META-INF/services/java.net.spi.InetAddressResolverProvider}
 * that consults a refcounted per-host pin map. Wrapper callers
 * ({@link SsrfGuardedHttpClient#get}) pin the validated addresses
 * for one canonical host before the dial, send the request (which
 * routes its DNS through this provider), and release the pin once
 * the connection is established. Calls touching DIFFERENT hosts
 * proceed independently; there is no global mutual exclusion, so a
 * slow or adversarial host cannot stall the JVM's outbound plane.
 *
 * <p>Production callers never instantiate this class directly; the
 * forwarding resolver inside {@link Provider} constructs an ephemeral
 * {@link PinnedDnsResolver} per lookup when a pin is active. The
 * public constructor exists so the class is independently testable
 * (pin-or-delegate behavior in isolation).
 */
public final class PinnedDnsResolver implements InetAddressResolver {

    private final Map<String, List<InetAddress>> pins;

    private final InetAddressResolver delegate;

    public PinnedDnsResolver(Map<String, List<InetAddress>> pins,
                             InetAddressResolver delegate) {
        this.pins = Map.copyOf(pins);
        this.delegate = delegate;
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
            throws UnknownHostException {
        // M1-026 Finding 2: canonicalize the host BEFORE pins.get().
        // The pin map is keyed by SsrfGuardedHttpClient.canonicalizeHost
        // on the install side; the JDK may pass a different form of
        // the host argument here (case-fold, trailing-dot strip,
        // IDN <-> punycode). The shared helper normalizes both sides
        // to the same key, so the pin matches regardless of JDK
        // transformation choices.
        //
        // If canonicalization throws (invalid host per IDN.toASCII),
        // the host cannot be in our pin map (always keyed by the
        // canonical form). Delegate to the builtin resolver — it
        // may reject with UnknownHostException or accept on its own
        // terms; either is correct policy.
        String canonicalHost;
        try {
            canonicalHost = SsrfGuardedHttpClient.canonicalizeHost(host);
        } catch (IllegalArgumentException e) {
            return delegate.lookupByName(host, lookupPolicy);
        }
        List<InetAddress> pinned = pins.get(canonicalHost);
        if (pinned != null) {
            return pinned.stream();
        }
        return delegate.lookupByName(host, lookupPolicy);
    }

    @Override
    public String lookupByAddress(byte[] addr) throws UnknownHostException {
        // Reverse lookups always go to the delegate. The wrapper never
        // pins reverse-lookup answers; only forward (name -> address)
        // lookups carry SSRF rebind exposure.
        return delegate.lookupByAddress(addr);
    }

    /**
     * The JVM-wide {@link InetAddressResolverProvider} that the JDK
     * loads at startup via the
     * {@code META-INF/services/java.net.spi.InetAddressResolverProvider}
     * resource. {@link #get(Configuration)} is called exactly once
     * per JVM by the SPI machinery; it captures the builtin resolver
     * (so non-pinned lookups still see the JDK default) and returns
     * a forwarding resolver that consults the per-host pin map.
     */
    public static final class Provider extends InetAddressResolverProvider {

        // Pin entries keyed by canonical host (see
        // SsrfGuardedHttpClient.canonicalizeHost). Each entry pairs the
        // validated address list with a holder refcount so overlapping
        // pins of the SAME host are independent acquisitions: the
        // entry is removed only when the LAST holder releases. All
        // mutations go through ConcurrentHashMap.compute, which is
        // atomic per key — there is no check-then-act window on
        // increment, decrement, or remove-at-zero — and operations on
        // different hosts never contend.
        private static final ConcurrentHashMap<String, PinEntry> PINS =
            new ConcurrentHashMap<>();

        // BUILTIN is captured once during get(Configuration) — the
        // JDK contract guarantees get() is invoked before any lookup
        // uses the returned resolver, so by the time the forwarding
        // resolver below is called, BUILTIN is non-null. NullAway's
        // field-init check models only constructors/initializers, not
        // the SPI get() lifecycle, so suppress that one check; the
        // field stays non-null for every dereference.
        @SuppressWarnings("NullAway.Init")
        private static volatile InetAddressResolver BUILTIN;

        @Override
        public InetAddressResolver get(Configuration configuration) {
            BUILTIN = configuration.builtinResolver();
            return new ForwardingResolver();
        }

        @Override
        public String name() {
            return "infochat-ssrf-pinned-resolver";
        }

        /**
         * Pin {@code addresses} as the answer the JVM-wide resolver
         * serves for {@code canonicalHost}, and return the handle that
         * releases this acquisition. Overlapping pins of the same host
         * stack via the refcount: the host stays pinned until every
         * holder has released.
         *
         * <p>When overlapping holders of the same host validated
         * DIVERGENT address sets, the most recent pin's set is served
         * (latest-wins). Every address served has passed the
         * {@link IpBlocklist} in some still-active holder's
         * validation, and the freshest validation is the one closest
         * to its connect — the strongest TOCTOU posture available
         * without per-call resolver scoping.
         *
         * <p>{@code canonicalHost} must already be canonical (the
         * install side canonicalizes in
         * {@code SsrfGuardedHttpClient.resolveAndValidate}); the
         * lookup side canonicalizes the JDK-supplied host before
         * consulting the map, so the keys match.
         */
        static PinHandle pin(String canonicalHost, List<InetAddress> addresses) {
            List<InetAddress> pinned = List.copyOf(addresses);
            PINS.compute(canonicalHost, (host, entry) -> entry == null
                ? new PinEntry(pinned, 1)
                : new PinEntry(pinned, entry.holders() + 1));
            return new PinHandle(canonicalHost);
        }

        /**
         * Snapshot of the hosts currently pinned → their validated
         * addresses, in the {@code Map} shape
         * {@link PinnedDnsResolver} consumes. The forwarding resolver
         * composes an ephemeral {@link PinnedDnsResolver} over this
         * snapshot per lookup so the canonicalize-before-get logic
         * (M1-026 Finding 2) lives in exactly one place. Weakly
         * consistent like the underlying map — but a caller's own pin
         * is always visible to its own dial's lookup, because the pin
         * is installed happens-before the send that needs it.
         */
        static Map<String, List<InetAddress>> activePinsSnapshot() {
            Map<String, List<InetAddress>> snapshot = new HashMap<>();
            PINS.forEach((host, entry) -> snapshot.put(host, entry.addresses()));
            return snapshot;
        }

        /**
         * The JDK-default resolver captured at JVM startup. Exposed
         * package-privately for tests that want to compose a
         * {@link PinnedDnsResolver} on top of the real builtin
         * without installing into the JVM-wide pin map.
         */
        static InetAddressResolver builtin() {
            return BUILTIN;
        }

        /** Validated addresses plus the number of active holders. */
        private record PinEntry(List<InetAddress> addresses, int holders) {}

        /**
         * Release handle for one {@link #pin} acquisition. Idempotent:
         * {@link #release()} decrements the host's holder count
         * exactly once no matter how many times it is called, so a
         * double release can neither throw nor release a concurrent
         * same-host holder's pin early. Callable from any thread —
         * release carries no thread affinity.
         */
        static final class PinHandle {

            private final String canonicalHost;

            private final AtomicBoolean released = new AtomicBoolean();

            private PinHandle(String canonicalHost) {
                this.canonicalHost = canonicalHost;
            }

            void release() {
                if (!released.compareAndSet(false, true)) {
                    return;
                }
                PINS.compute(canonicalHost, (host, entry) -> {
                    // entry == null cannot happen while the refcount
                    // invariant holds (this handle's acquisition is
                    // still counted); the arm exists because compute's
                    // contract supplies null for an absent key.
                    if (entry == null || entry.holders() == 1) {
                        return null;
                    }
                    return new PinEntry(entry.addresses(), entry.holders() - 1);
                });
            }
        }

        private static final class ForwardingResolver implements InetAddressResolver {

            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                // Fast path: every lookup in the JVM (DB, LLM
                // endpoints, ...) routes through this provider; skip
                // the snapshot allocation when no pin is active.
                if (PINS.isEmpty()) {
                    return BUILTIN.lookupByName(host, lookupPolicy);
                }
                return new PinnedDnsResolver(activePinsSnapshot(), BUILTIN)
                    .lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return BUILTIN.lookupByAddress(addr);
            }
        }
    }
}
