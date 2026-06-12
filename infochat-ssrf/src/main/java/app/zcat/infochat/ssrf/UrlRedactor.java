package app.zcat.infochat.ssrf;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Pure-string redaction helper for URLs that may appear in exception
 * messages, JUL log lines, or audit-log-adjacent surfaces. Preserves
 * only the triage-relevant authority — scheme, host, and port — and
 * collapses everything that can carry a secret into the single
 * placeholder {@code /[REDACTED]}: the userinfo segment (credentials
 * like {@code user:secret@}), the path (webhook tokens like
 * {@code /services/T00/B00/XXXX}), and the query string (tokens like
 * {@code ?token=abc}).
 *
 * <p>Output shapes:
 * <ul>
 *   <li>{@code https://user:secret@host/path}
 *       → {@code https://host/[REDACTED]}</li>
 *   <li>{@code https://host/p?token=abc}
 *       → {@code https://host/[REDACTED]}</li>
 *   <li>{@code https://user:secret@host/p?token=abc}
 *       → {@code https://host/[REDACTED]}</li>
 *   <li>{@code https://host} (no path or query)
 *       → {@code https://host}</li>
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

    // Single placeholder for everything after the authority. Path and
    // query are collapsed together because both can carry secrets (a
    // Slack/Discord webhook token lives in the path); the leading slash
    // keeps the rendered value a recognizable URL.
    private static final String REDACTED_PATH = "/[REDACTED]";

    private UrlRedactor() {
        // static-only
    }

    public static String redact(@Nullable String url) {
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
        boolean hasPath = path != null && !path.isEmpty();
        if (hasPath || uri.getRawQuery() != null) {
            out.append(REDACTED_PATH);
        }
        return out.toString();
    }
}
