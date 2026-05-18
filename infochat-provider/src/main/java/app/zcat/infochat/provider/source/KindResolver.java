package app.zcat.infochat.provider.source;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.IDN;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves a feed URL + optional caller-supplied {@code --type} hint to
 * a {@link SourceKind} per the closed table in
 * {@code docs/spec/commands.md} §Source management. The decision shape
 * is deterministic in this exact order:
 *
 * <ol>
 *   <li>Explicit caller {@code --type} wins (case-insensitive match
 *       against the {@link SourceKind} enum).</li>
 *   <li>Host-pattern table:
 *       <ul>
 *         <li>{@code wss}/{@code ws} scheme → {@link SourceKind#NOSTR}</li>
 *         <li>{@code bsky.app} or {@code *.bsky.social} →
 *             {@link SourceKind#BLUESKY}</li>
 *         <li>{@code reddit.com} or {@code redd.it} (incl. subdomains) →
 *             {@link SourceKind#REDDIT}</li>
 *         <li>{@code youtube.com} or {@code youtu.be} (incl. subdomains)
 *             → {@link SourceKind#YOUTUBE}</li>
 *         <li>{@code odysee.com} (incl. subdomains) →
 *             {@link SourceKind#ODYSEE}</li>
 *       </ul>
 *       The host is folded via {@link IDN#toASCII(String, int)
 *       IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)} +
 *       {@code Locale.ROOT} lower-case before pattern compare so
 *       Unicode hosts (e.g. {@code блюски.рф}) match the same patterns
 *       a punycoded form would.</li>
 *   <li>RSS auto-detection: path ends in {@code .xml} or {@code .rss},
 *       or contains {@code /feed} → {@link SourceKind#RSS}.</li>
 *   <li>Otherwise → {@link Resolution#ambiguous()}; the caller surfaces
 *       a friendly error asking for an explicit {@code --type}.</li>
 * </ol>
 *
 * <p>Host-pattern matches BEAT RSS path matches when both apply (a
 * Bluesky URL whose path ends in {@code /feed} is Bluesky, not RSS).
 * Explicit {@code --type} BEATS all auto-detect paths.</p>
 */
@ApplicationScoped
public class KindResolver {

    /**
     * Closed set per {@code source.kind}'s usage in
     * {@code docs/spec/commands.md} §Source management. The schema does
     * not pin the set via a SQL CHECK (extensible for v2 sources), but
     * the application layer is the closure enforcer.
     */
    public enum SourceKind {
        RSS, NOSTR, BLUESKY, REDDIT, YOUTUBE, ODYSEE;

        /** Lower-case wire form used in the {@code source.kind} column. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Case-insensitive {@code --type} resolution. */
        public static Optional<SourceKind> fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String upper = raw.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(k -> k.name().equals(upper))
                    .findFirst();
        }

        /** Comma-joined human-readable list of valid wire values for friendly errors. */
        public static String commaList() {
            return Arrays.stream(values())
                    .map(SourceKind::wire)
                    .collect(Collectors.joining(", "));
        }
    }

    /**
     * Either a resolved {@link SourceKind} or the {@link #ambiguous()}
     * sentinel. A sealed result keeps the dispatch surface in the
     * handler exhaustive.
     */
    public record Resolution(Optional<SourceKind> kind, boolean isAmbiguous) {

        public static Resolution of(SourceKind kind) {
            return new Resolution(Optional.of(kind), false);
        }

        public static Resolution ambiguous() {
            return new Resolution(Optional.empty(), true);
        }
    }

    /**
     * Resolve the URL + caller-supplied {@code --type} hint to a kind.
     * The closed table applies in the documented order; this method
     * is a pure function with no side effects.
     */
    public Resolution resolve(URI url, Optional<SourceKind> explicitType) {
        if (explicitType.isPresent()) {
            return Resolution.of(explicitType.get());
        }

        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("ws") || scheme.equals("wss")) {
            return Resolution.of(SourceKind.NOSTR);
        }

        String host = url.getHost();
        String canonicalHost = host == null ? "" : canonicalize(host);
        if (matchesHost(canonicalHost, "bsky.app") || matchesHost(canonicalHost, "bsky.social")) {
            return Resolution.of(SourceKind.BLUESKY);
        }
        if (matchesHost(canonicalHost, "reddit.com") || matchesHost(canonicalHost, "redd.it")) {
            return Resolution.of(SourceKind.REDDIT);
        }
        if (matchesHost(canonicalHost, "youtube.com") || matchesHost(canonicalHost, "youtu.be")) {
            return Resolution.of(SourceKind.YOUTUBE);
        }
        if (matchesHost(canonicalHost, "odysee.com")) {
            return Resolution.of(SourceKind.ODYSEE);
        }

        String path = url.getPath() == null ? "" : url.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".xml") || path.endsWith(".rss") || path.contains("/feed")) {
            return Resolution.of(SourceKind.RSS);
        }

        return Resolution.ambiguous();
    }

    /**
     * IDN fold per {@code SsrfGuardedHttpClient.canonicalizeHost}:
     * {@link IDN#toASCII(String, int)} with
     * {@link IDN#ALLOW_UNASSIGNED} (so Unicode hosts under any
     * version of IDN match the same patterns the punycoded forms
     * would), then {@code toLowerCase(Locale.ROOT)} to avoid the
     * Turkish-dotless-i hazard. A pinned host without this fold would
     * silently miss the Bluesky/Reddit/etc. patterns when the caller
     * supplies a Unicode subdomain.
     */
    static String canonicalize(String host) {
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            // Malformed IDN — leave the original (lower-cased) form so
            // pattern matching can still fail-safe to AMBIGUOUS without
            // raising. The probe layer will catch malformed hosts on
            // its own validation pass.
            ascii = host;
        }
        String lower = ascii.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) {
            return lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    /**
     * Host match: the canonical host equals the suffix exactly, OR
     * ends with {@code "." + suffix} (subdomain match). The dot
     * boundary prevents {@code evilbsky.app.attacker.com} from
     * matching {@code bsky.app}.
     */
    private static boolean matchesHost(String canonicalHost, String suffix) {
        return canonicalHost.equals(suffix) || canonicalHost.endsWith("." + suffix);
    }
}
