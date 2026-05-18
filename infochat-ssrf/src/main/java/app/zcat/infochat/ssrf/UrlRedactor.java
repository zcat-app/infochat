package app.zcat.infochat.ssrf;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Pure-string redaction helper for URLs that may appear in exception
 * messages, JUL log lines, or audit-log-adjacent surfaces. Strips the
 * userinfo segment (which can carry credentials like
 * {@code user:secret@}) and replaces the query string (which can carry
 * tokens like {@code ?token=abc}) with the literal placeholder
 * {@code [REDACTED]}.
 *
 * <p>Output shapes:
 * <ul>
 *   <li>{@code https://user:secret@host/path}
 *       → {@code https://host/path}</li>
 *   <li>{@code https://host/p?token=abc}
 *       → {@code https://host/p?[REDACTED]}</li>
 *   <li>{@code https://user:secret@host/p?token=abc}
 *       → {@code https://host/p?[REDACTED]}</li>
 *   <li>any unparseable input → the literal string
 *       {@code <malformed-url>}</li>
 * </ul>
 *
 * <p>This is a <strong>logging helper</strong> and must be infallible:
 * the only legitimate callers are inside exception constructors and log
 * call sites, where re-raising would mask the real failure. A blanket
 * catch here is a documented system-boundary commitment per the ticket's
 * Definition of Done, not internal defensive code.
 */
public final class UrlRedactor {

    private static final String MALFORMED = "<malformed-url>";

    private static final String REDACTED_QUERY = "?[REDACTED]";

    private UrlRedactor() {
        // static-only
    }

    public static String redact(String url) {
        if (url == null) {
            return MALFORMED;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return MALFORMED;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            // A URI without scheme+host has no meaningful "userinfo and
            // query stripped" rendering — surface as malformed rather
            // than emitting a half-formed string.
            return MALFORMED;
        }

        StringBuilder out = new StringBuilder();
        out.append(scheme).append("://").append(host);
        int port = uri.getPort();
        if (port != -1) {
            out.append(':').append(port);
        }
        String path = uri.getRawPath();
        if (path != null) {
            out.append(path);
        }
        if (uri.getRawQuery() != null) {
            out.append(REDACTED_QUERY);
        }
        return out.toString();
    }
}
