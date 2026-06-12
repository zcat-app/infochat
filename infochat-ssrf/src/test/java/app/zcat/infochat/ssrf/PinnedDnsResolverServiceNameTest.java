package app.zcat.infochat.ssrf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * U-48 regression pin: the resolver service-registration name is
 * load-bearing for JDK {@code InetAddressResolverProvider} SPI
 * discovery and must not change. A silent rename would deregister the
 * pinned resolver — guarded dials would fall back to the platform
 * resolver and the DNS-pin rebind defense would vanish without any
 * compile-time or test failure. This test makes such a rename break
 * the build.
 */
class PinnedDnsResolverServiceNameTest {

    @Test
    void providerNameIsUnchanged() {
        assertEquals("infochat-ssrf-pinned-resolver",
            new PinnedDnsResolver.Provider().name(),
            "renaming the resolver SPI registration silently breaks "
                + "JDK resolver discovery and disables the DNS pin");
    }
}
