package app.zcat.infochat.ssrf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain JUnit 5 unit tests for {@link UrlRedactor}: per-shape redaction
 * over the four scenarios enumerated in M1-024 acceptance item 11. No
 * {@code @QuarkusTest}; this is a pure helper with no CDI surface.
 */
class UrlRedactorTest {

    @Test
    void redactStripsUserinfoSegment() {
        assertEquals(
            "https://host/path",
            UrlRedactor.redact("https://user:secret@host/path"),
            "userinfo (user:secret@) must be stripped from the rendered URL");
    }

    @Test
    void redactReplacesQueryWithPlaceholder() {
        assertEquals(
            "https://host/p?[REDACTED]",
            UrlRedactor.redact("https://host/p?token=abc"),
            "query string must be replaced with the literal [REDACTED] placeholder");
    }

    @Test
    void redactStripsUserinfoAndQueryTogether() {
        assertEquals(
            "https://host/p?[REDACTED]",
            UrlRedactor.redact("https://user:secret@host/p?token=abc"),
            "both userinfo and query must be redacted in a single pass");
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
            "https://host:8443/p?[REDACTED]",
            UrlRedactor.redact("https://user:pw@host:8443/p?token=abc"),
            "non-default port must round-trip into the redacted output");
    }
}
