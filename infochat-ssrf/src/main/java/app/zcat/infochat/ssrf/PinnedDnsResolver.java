package app.zcat.infochat.ssrf;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Holder for the JVM-wide DNS-pinning resolver {@link Provider}. The
 * pinning serves the spec's "DNS-rebind defense" promise
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
 * <p>The forwarding resolver inside {@link Provider} is the single
 * production lookup lens: it probes the live pin map with a
 * canonicalize-then-get-then-delegate policy (M1-277 removed the
 * per-lookup ephemeral composition). This class itself is a
 * non-instantiable holder — there is no standalone resolver instance.
 */
public final class PinnedDnsResolver {

    private PinnedDnsResolver() {
        // Holder for Provider + the shared LookupPolicy filter; not
        // instantiable — the JVM-wide resolver is Provider's nested
        // ForwardingResolver, registered via META-INF/services.
    }

    /**
     * Filter a pinned address set down to the families the JDK asked
     * for via {@code lookupPolicy} (U-39). The builtin resolver already
     * receives the {@link InetAddressResolver.LookupPolicy} and honors
     * it; before this, the PINNED path returned every validated address
     * regardless of the requested family, so an IPv4-only caller could
     * be handed an IPv6 pinned address it cannot use. The policy's
     * {@code characteristics()} bitmask carries the family request:
     * {@code IPV4} and/or {@code IPV6}. When exactly one family bit is
     * set the set is filtered to that family; when both (or neither)
     * are set there is no family restriction and the set passes through
     * unchanged. Every returned address was blocklist-validated at the
     * pin that installed it — filtering narrows, never widens.
     *
     * <p>Package-private static so both the production lens
     * ({@link Provider.ForwardingResolver}) and the U-39 tests invoke
     * the identical filter.
     */
    static List<InetAddress> filterByFamily(List<InetAddress> addresses,
                                            InetAddressResolver.LookupPolicy lookupPolicy) {
        int characteristics = lookupPolicy.characteristics();
        boolean wantIpv4 = (characteristics & InetAddressResolver.LookupPolicy.IPV4) != 0;
        boolean wantIpv6 = (characteristics & InetAddressResolver.LookupPolicy.IPV6) != 0;
        if (wantIpv4 == wantIpv6) {
            // Both families requested (or, defensively, neither bit set):
            // no family restriction to apply.
            return addresses;
        }
        List<InetAddress> filtered = new ArrayList<>(addresses.size());
        for (InetAddress address : addresses) {
            if ((address instanceof Inet4Address) == wantIpv4) {
                filtered.add(address);
            }
        }
        return filtered;
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
         * (latest-wins). Every address served passed the
         * {@link IpBlocklist} at the pin that installed it; that pin's
         * holder may since have released — release() decrements the
         * refcount but never reverts the stored set — so the served
         * set is the freshest validation, not necessarily one a
         * still-active holder validated. That freshest validation is
         * the one closest to its connect — the strongest TOCTOU posture
         * available without per-call resolver scoping.
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
         * addresses. Package-private test-inspection seam: the only
         * window onto the otherwise-private {@link #PINS} map, so tests
         * can assert pin/release transitions ({@code containsKey},
         * {@code get}) without reaching through {@code InetAddress
         * .getAllByName}, whose positive cache sits ABOVE the resolver
         * SPI. Weakly consistent like the underlying map — but a
         * caller's own pin is always visible to its own dial's lookup,
         * because the pin is installed happens-before the send that
         * needs it. Has no production caller; the live
         * {@link ForwardingResolver} probes {@link #PINS} directly.
         */
        static Map<String, List<InetAddress>> activePinsSnapshot() {
            Map<String, List<InetAddress>> snapshot = new HashMap<>();
            PINS.forEach((host, entry) -> snapshot.put(host, entry.addresses()));
            return snapshot;
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
                    if (entry == null) {
                        // Unreachable while the refcount invariant holds:
                        // this handle's acquisition is still counted, so
                        // the entry must exist. compute's remapping
                        // function takes a @Nullable value, so the branch
                        // cannot be deleted (NullAway) — make it LOUD
                        // rather than a silent no-op so a refcount bug
                        // surfaces here instead of corrupting pin state.
                        throw new IllegalStateException(
                            "pin entry missing on release for host "
                            + canonicalHost + " (refcount invariant violated)");
                    }
                    if (entry.holders() == 1) {
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
                // Every lookup in the JVM (DB pool, LLM endpoints, ...)
                // routes through this provider — the single production
                // lookup lens. No-pin fast path first; then (M1-277,
                // M-S2) an exact probe of the live map. Canonicalize
                // BEFORE the get (M1-026 Finding 2): the pin map is keyed
                // by canonical form, and the JDK may pass a case /
                // trailing-dot / IDN variant here. A pinned hit is
                // filtered to the requested address family (U-39) so an
                // IPv4-only or IPv6-only policy is honored on the pinned
                // path exactly as the builtin already honors it on the
                // delegate path.
                if (PINS.isEmpty()) {
                    return BUILTIN.lookupByName(host, lookupPolicy);
                }
                String canonicalHost;
                try {
                    canonicalHost = SsrfGuardedHttpClient.canonicalizeHost(host);
                } catch (IllegalArgumentException e) {
                    // Not canonicalizable -> cannot be in the pin map
                    // (always keyed by the canonical form); the builtin
                    // may reject or accept on its own terms.
                    return BUILTIN.lookupByName(host, lookupPolicy);
                }
                PinEntry entry = PINS.get(canonicalHost);
                if (entry != null) {
                    return filterByFamily(entry.addresses(), lookupPolicy).stream();
                }
                return BUILTIN.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return BUILTIN.lookupByAddress(addr);
            }
        }
    }
}
