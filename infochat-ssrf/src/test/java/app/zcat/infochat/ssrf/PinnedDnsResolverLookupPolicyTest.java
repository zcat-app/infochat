package app.zcat.infochat.ssrf;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * U-39: the pinned lookup path honors the JDK
 * {@link LookupPolicy}'s address-family characteristics.
 * {@link PinnedDnsResolver#filterByFamily} is the shared filter both
 * the production forwarding resolver and these tests exercise; an
 * IPv4-only or IPv6-only policy must narrow a dual-family pinned set to
 * the requested family, while a both-families policy passes it through
 * unchanged. Before this, the pinned path returned every validated
 * address regardless of the requested family (only the delegate path
 * honored the policy).
 */
class PinnedDnsResolverLookupPolicyTest {

    private static final InetAddress IPV4 = address("192.0.2.7");

    private static final InetAddress IPV6 = address("2001:db8::7");

    @Test
    void ipv4OnlyPolicyKeepsOnlyIpv4FromDualFamilyPin() {
        List<InetAddress> filtered = PinnedDnsResolver.filterByFamily(
            List.of(IPV4, IPV6), LookupPolicy.of(LookupPolicy.IPV4));
        assertEquals(List.of(IPV4), filtered,
            "an IPv4-only policy must drop the IPv6 pinned address");
    }

    @Test
    void ipv6OnlyPolicyKeepsOnlyIpv6FromDualFamilyPin() {
        List<InetAddress> filtered = PinnedDnsResolver.filterByFamily(
            List.of(IPV4, IPV6), LookupPolicy.of(LookupPolicy.IPV6));
        assertEquals(List.of(IPV6), filtered,
            "an IPv6-only policy must drop the IPv4 pinned address");
    }

    @Test
    void bothFamiliesPolicyKeepsTheFullPinnedSet() {
        List<InetAddress> filtered = PinnedDnsResolver.filterByFamily(
            List.of(IPV4, IPV6),
            LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6));
        assertEquals(List.of(IPV4, IPV6), filtered,
            "a both-families policy applies no family restriction");
    }

    private static InetAddress address(String literal) {
        try {
            // IP-literal forms resolve without a DNS lookup.
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
