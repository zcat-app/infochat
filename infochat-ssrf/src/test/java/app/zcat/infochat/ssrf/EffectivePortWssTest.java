package app.zcat.infochat.ssrf;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SsrfGuardedHttpClient#effectivePort(URI)}: the
 * scheme→default-port mapping that {@code isCrossOrigin} consults when a
 * URI omits an explicit port. The wss branch is unreachable through
 * {@link SsrfGuardedHttpClient#get(URI)} (http/https only), so it is
 * exercised here via the package-private method directly — the same
 * same-package test-access idiom {@code canonicalizeHost} uses.
 *
 * <p>The wss→443 mapping matters so a future reuse of {@code effectivePort}
 * / {@code isCrossOrigin} on a WebSocket path cannot misjudge {@code
 * wss://h/} (implicit 443) against {@code wss://h:443/} (explicit 443) as
 * cross-origin and wrongly scrub credentials.
 */
class EffectivePortWssTest {

    @Test
    void wssWithoutPortDefaultsTo443() {
        assertEquals(443, SsrfGuardedHttpClient.effectivePort(URI.create("wss://example.com/")));
    }

    @Test
    void wssWithoutPortMatchesExplicit443() {
        // The whole point of the fix: implicit and explicit 443 must agree
        // so they read as same-origin.
        assertEquals(
            SsrfGuardedHttpClient.effectivePort(URI.create("wss://example.com:443/")),
            SsrfGuardedHttpClient.effectivePort(URI.create("wss://example.com/")));
    }

    @Test
    void wsWithoutPortDefaultsTo80() {
        assertEquals(80, SsrfGuardedHttpClient.effectivePort(URI.create("ws://example.com/")));
    }

    @Test
    void httpsWithoutPortStillDefaultsTo443() {
        assertEquals(443, SsrfGuardedHttpClient.effectivePort(URI.create("https://example.com/")));
    }

    @Test
    void httpWithoutPortStillDefaultsTo80() {
        assertEquals(80, SsrfGuardedHttpClient.effectivePort(URI.create("http://example.com/")));
    }

    @Test
    void explicitPortWinsOverSchemeDefault() {
        assertEquals(8443, SsrfGuardedHttpClient.effectivePort(URI.create("https://example.com:8443/")));
        assertEquals(9001, SsrfGuardedHttpClient.effectivePort(URI.create("wss://example.com:9001/")));
    }

    @Test
    void schemeIsCaseInsensitive() {
        assertEquals(443, SsrfGuardedHttpClient.effectivePort(URI.create("WSS://example.com/")));
        assertEquals(443, SsrfGuardedHttpClient.effectivePort(URI.create("HTTPS://example.com/")));
    }

    @Test
    void nullSchemeDefaultsTo80() {
        // A scheme-relative redirect target (e.g. Location: //host/path)
        // resolves to a URI with a null scheme; effectivePort must treat it
        // as the 80 default, matching the prior equalsIgnoreCase(null)==false
        // behavior so an unvalidated redirect target never NPEs the origin
        // comparison.
        assertEquals(80, SsrfGuardedHttpClient.effectivePort(URI.create("//host/path")));
    }
}
