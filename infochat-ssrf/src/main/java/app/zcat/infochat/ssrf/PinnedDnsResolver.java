package app.zcat.infochat.ssrf;

import org.jspecify.annotations.NonNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
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
 * that consults a static pin slot guarded by a {@link ReentrantLock}.
 * Wrapper callers ({@link SsrfGuardedHttpClient#get}) acquire the
 * lock for the duration of one {@code get(uri)} call, install the
 * pin map, send the request (which routes its DNS through this
 * provider), clear the pin in {@code finally}, and release the lock.
 * Concurrent wrapper calls serialize on the lock — acceptable for
 * v1's RSS cadence.
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
    public Stream<InetAddress> lookupByName(@NonNull String host, @NonNull LookupPolicy lookupPolicy)
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
     * a forwarding resolver that consults the static pin slot.
     */
    public static final class Provider extends InetAddressResolverProvider {

        private static final ReentrantLock LOCK = new ReentrantLock();

        // ACTIVE_PINS is mutated only while LOCK is held; reads are
        // unsynchronized but volatile, so the JDK's DNS-lookup
        // threads see a consistent snapshot (either null or a fully
        // populated immutable map).
        private static volatile Map<String, List<InetAddress>> ACTIVE_PINS;

        // BUILTIN is captured once during get(Configuration) — the
        // JDK contract guarantees get() is invoked before any lookup
        // uses the returned resolver, so by the time the forwarding
        // resolver below is called, BUILTIN is non-null.
        private static volatile InetAddressResolver BUILTIN;

        @Override
        public InetAddressResolver get(@NonNull Configuration configuration) {
            BUILTIN = configuration.builtinResolver();
            return new ForwardingResolver();
        }

        @Override
        public String name() {
            return "infochat-ssrf-pinned-resolver";
        }

        /**
         * Acquire the JVM-wide lock that serializes wrapper pinning.
         * Callers MUST release via {@link ReentrantLock#unlock()} in
         * a {@code finally} block.
         */
        static ReentrantLock lock() {
            return LOCK;
        }

        /**
         * Install the per-call pin map. Caller must hold {@link #LOCK}.
         */
        static void installPins(Map<String, List<InetAddress>> pins) {
            ACTIVE_PINS = Map.copyOf(pins);
        }

        /**
         * Clear the pin slot. Caller must hold {@link #LOCK}.
         */
        static void clearPins() {
            ACTIVE_PINS = null;
        }

        /**
         * The JDK-default resolver captured at JVM startup. Exposed
         * package-privately for tests that want to compose a
         * {@link PinnedDnsResolver} on top of the real builtin
         * without installing into the JVM-wide slot.
         */
        static InetAddressResolver builtin() {
            return BUILTIN;
        }

        private static final class ForwardingResolver implements InetAddressResolver {

            @Override
            public Stream<InetAddress> lookupByName(@NonNull String host, @NonNull LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                Map<String, List<InetAddress>> pins = ACTIVE_PINS;
                if (pins != null) {
                    return new PinnedDnsResolver(pins, BUILTIN)
                        .lookupByName(host, lookupPolicy);
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
