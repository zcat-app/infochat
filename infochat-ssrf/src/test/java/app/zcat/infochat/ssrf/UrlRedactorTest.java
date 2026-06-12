package app.zcat.infochat.ssrf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Plain JUnit 5 unit tests for {@link UrlRedactor}: per-shape redaction.
 * The authority — scheme, host, port — is preserved for triage; userinfo,
 * path, and query all collapse into the single {@code /[REDACTED]}
 * placeholder. No {@code @QuarkusTest}; this is a pure helper with no CDI
 * surface.
 */
class UrlRedactorTest {

    @Test
    void redactCollapsesPathAndStripsUserinfo() {
        assertEquals(
            "https://host/[REDACTED]",
            UrlRedactor.redact("https://user:secret@host/path"),
            "userinfo must be stripped and the path collapsed to /[REDACTED]");
    }

    @Test
    void redactCollapsesPathBearingSecret() {
        assertEquals(
            "https://host/[REDACTED]",
            UrlRedactor.redact("https://host/p?token=abc"),
            "path and query must both collapse into the single /[REDACTED] placeholder");
    }

    @Test
    void redactStripsUserinfoAndCollapsesPathTogether() {
        assertEquals(
            "https://host/[REDACTED]",
            UrlRedactor.redact("https://user:secret@host/p?token=abc"),
            "userinfo, path, and query must all be redacted in a single pass");
    }

    @Test
    void redactHostOnlyAppendsNothing() {
        // Nothing after the authority to redact — scheme and host pass
        // through unchanged with no spurious /[REDACTED] suffix.
        assertEquals(
            "https://host",
            UrlRedactor.redact("https://host"),
            "a bare scheme://host with no path or query must round-trip untouched");
    }

    @Test
    void redactWebhookPathTokenNeverAppears() {
        // Slack/Discord webhook tokens live in the URL PATH, not the
        // query — collapsing the path is what keeps them out of WARN logs.
        String token = "T00000000XXXXtokenXXXXsecret";
        String webhook = "https://hooks.slack.com/services/B00000000/" + token;
        String redacted = UrlRedactor.redact(webhook);
        assertEquals("https://hooks.slack.com/[REDACTED]", redacted,
            "webhook path must collapse to /[REDACTED]");
        assertFalse(redacted.contains(token),
            "the path-borne webhook token must never appear in the redacted output");
    }

    @Test
    void redactMalformedReturnsLiteralPlaceholder() {
        assertEquals(
            "<malformed-url>",
            UrlRedactor.redact("not a url at all"),
            "any unparseable input must return the literal <malformed-url> string "
            + "rather than raising — UrlRedactor is a logging helper and must be infallible");
    }

    @Test
    void redactNullReturnsLiteralPlaceholder() {
        assertEquals(
            "<malformed-url>",
            UrlRedactor.redact(null),
            "null input must not throw — surface as <malformed-url>");
    }

    @Test
    void redactPreservesPortInAuthority() {
        assertEquals(
            "https://host:8443/[REDACTED]",
            UrlRedactor.redact("https://user:pw@host:8443/p?token=abc"),
            "non-default port must round-trip into the redacted output");
    }

    @Test
    void redactBracketsIpv6Host() {
        // URI.getHost() returns IPv6 literals bracketed ("[::1]"), so
        // the rendered output must keep the brackets — otherwise the
        // address would be ambiguous against a port suffix (C-URLREDACTOR-IPV6).
        assertEquals(
            "https://[::1]/[REDACTED]",
            UrlRedactor.redact("https://[::1]/p?token=abc"),
            "IPv6 literal host must keep its brackets in the redacted output");
    }

    @Test
    void redactBracketsIpv6HostWithUserinfoAndPort() {
        assertEquals(
            "https://[2606:4700::1111]:8443/[REDACTED]",
            UrlRedactor.redact("https://user:pw@[2606:4700::1111]:8443/p?token=abc"),
            "bracketed IPv6 with userinfo+port: brackets and port preserved, "
            + "path and query collapsed");
    }
}
