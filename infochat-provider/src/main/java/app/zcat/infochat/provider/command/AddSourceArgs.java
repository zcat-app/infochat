package app.zcat.infochat.provider.command;

import org.jspecify.annotations.Nullable;

import app.zcat.infochat.provider.source.KindResolver.SourceKind;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parsed form of an {@code /add-source} invocation. The router hands the
 * handler the post-normalization inbound body verbatim (including the
 * leading {@code /add-source} token); {@link #parse(String)} strips the
 * leading token and produces either a populated {@link AddSourceArgs}
 * or a typed {@link Failure} carrying the bundle key the handler should
 * surface as a friendly error.
 *
 * <p>Argument shape per {@code docs/spec/commands.md} §Source
 * management for {@code /add-source}:
 * <ul>
 *   <li>positional URL (required)</li>
 *   <li>{@code --tags <tag>[,<tag>...]} (required, ≥1 non-empty)</li>
 *   <li>{@code --type <kind>} (optional; case-insensitive match against
 *       the {@link SourceKind} closed enum)</li>
 *   <li>{@code --category <cat>} (optional; closed set
 *       {@code news|blog|social}; defaults to {@code news})</li>
 *   <li>{@code --name "..."} (optional; defaults to a host-derived
 *       display name when omitted)</li>
 * </ul>
 *
 * <p>The parser is conservative on validation: SQL-tier constraints
 * (the {@code tag.name} regex CHECK, the {@code source.kind} closed
 * set) remain authoritative — the parser only catches the parse-time
 * shape problems the spec assigns friendly errors to (missing
 * {@code --tags}, unknown {@code --type}, unknown {@code --category},
 * malformed URL, embedded userinfo).
 */
public record AddSourceArgs(
        URI url,
        List<String> tags,
        Optional<SourceKind> typeOverride,
        String category,
        Optional<String> displayNameOverride) {

    /** Closed category set per {@code docs/spec/commands.md} §Source management. */
    public static final List<String> ALLOWED_CATEGORIES = List.of("news", "blog", "social");

    private static final String DEFAULT_CATEGORY = "news";

    public sealed interface ParseResult permits Success, Failure {}

    public record Success(AddSourceArgs args) implements ParseResult {}

    /**
     * Parse failure carrying the en.properties bundle key the handler
     * should surface to the caller. {@code interpolationArgs} captures
     * any caller-supplied values the bundle template references (e.g.
     * the unknown {@code --type} value).
     */
    public record Failure(String bundleKey, List<String> interpolationArgs) implements ParseResult {
        public Failure(String bundleKey) {
            this(bundleKey, List.of());
        }
    }

    /**
     * Parse the post-normalization inbound body. {@code rawBody} carries
     * the leading {@code /add-source} token; the parser drops the first
     * whitespace-delimited token before walking the remaining args.
     */
    public static ParseResult parse(String rawBody) {
        // Drop the leading /add-source token. The router has already
        // confirmed the body begins with a slash and the dispatch table
        // routed by the slashless command name; we don't validate it here.
        String[] split = rawBody.trim().split("\\s+", 2);
        String remainder = split.length > 1 ? split[1].trim() : "";

        List<String> tokens = CommandTokenizer.tokenize(remainder);

        URI url = null;
        List<String> tags = null;
        Optional<SourceKind> typeOverride = Optional.empty();
        String category = DEFAULT_CATEGORY;
        Optional<String> displayNameOverride = Optional.empty();

        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (token.startsWith("--tags=")) {
                tags = parseTagList(token.substring("--tags=".length()));
                i++;
            } else if (token.equals("--tags")) {
                if (i + 1 >= tokens.size()) {
                    return new Failure("error.add_source.tags_required");
                }
                tags = parseTagList(tokens.get(i + 1));
                i += 2;
            } else if (token.startsWith("--type=")) {
                Optional<SourceKind> resolved = SourceKind.fromString(token.substring("--type=".length()));
                if (resolved.isEmpty()) {
                    return unknownKind(token.substring("--type=".length()));
                }
                typeOverride = resolved;
                i++;
            } else if (token.equals("--type")) {
                if (i + 1 >= tokens.size()) {
                    return unknownKind("");
                }
                Optional<SourceKind> resolved = SourceKind.fromString(tokens.get(i + 1));
                if (resolved.isEmpty()) {
                    return unknownKind(tokens.get(i + 1));
                }
                typeOverride = resolved;
                i += 2;
            } else if (token.startsWith("--category=")) {
                String value = token.substring("--category=".length());
                if (!ALLOWED_CATEGORIES.contains(value)) {
                    return unknownCategory(value);
                }
                category = value;
                i++;
            } else if (token.equals("--category")) {
                if (i + 1 >= tokens.size()) {
                    return unknownCategory("");
                }
                String value = tokens.get(i + 1);
                if (!ALLOWED_CATEGORIES.contains(value)) {
                    return unknownCategory(value);
                }
                category = value;
                i += 2;
            } else if (token.startsWith("--name=")) {
                displayNameOverride = Optional.of(token.substring("--name=".length()));
                i++;
            } else if (token.equals("--name")) {
                if (i + 1 >= tokens.size()) {
                    displayNameOverride = Optional.empty();
                    i++;
                } else {
                    displayNameOverride = Optional.of(tokens.get(i + 1));
                    i += 2;
                }
            } else if (token.startsWith("--")) {
                // Unknown flag — surface as malformed for MVP (the spec
                // does not enumerate per-unknown-flag bundle keys).
                return new Failure("error.add_source.malformed_url");
            } else {
                // First positional token is the URL; reject a second one.
                if (url != null) {
                    return new Failure("error.add_source.malformed_url");
                }
                url = parseUri(token);
                if (url == null) {
                    return new Failure("error.add_source.malformed_url");
                }
                if (url.getRawUserInfo() != null) {
                    // Reject embedded credentials at parse time: the fetch
                    // path never sends userinfo, so accepting it would
                    // store un-fetchable (and needlessly retained)
                    // credentials in the source row.
                    return new Failure("error.add_source.userinfo_rejected");
                }
                i++;
            }
        }

        if (url == null) {
            return new Failure("error.add_source.malformed_url");
        }
        if (tags == null || tags.isEmpty()) {
            return new Failure("error.add_source.tags_required");
        }

        return new Success(new AddSourceArgs(url, tags, typeOverride, category, displayNameOverride));
    }

    private static List<String> parseTagList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String normalized = normalizeTag(raw);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return out;
    }

    /**
     * NFC + {@code Locale.ROOT} lower-case, matching the
     * {@code BootstrapLoader.normalizeTag} shape so vocab union behaves
     * identically across bootstrap-seeded and {@code /add-source}-supplied
     * tags. The {@code tag.name} CHECK regex catches any residual
     * invalid chars at the SQL boundary.
     */
    private static String normalizeTag(String raw) {
        return Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static @Nullable URI parseUri(String s) {
        try {
            URI uri = new URI(s);
            // The probe layer accepts http/https only; reject anything
            // that lacks a scheme or a host here so the friendly error
            // surfaces at parse time rather than via the SSRF wrapper.
            // wss/ws (Nostr) and other StreamSource shapes are spec'd
            // for the kind resolver to accept; the probe path is HTTP-
            // only in MVP per docs/design/03-commands.md §/add-source.
            if (uri.getScheme() == null) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static Failure unknownKind(String supplied) {
        return new Failure("error.add_source.unknown_kind",
                List.of(supplied, SourceKind.commaList()));
    }

    private static Failure unknownCategory(String supplied) {
        return new Failure("error.add_source.unknown_category",
                List.of(supplied, String.join(", ", ALLOWED_CATEGORIES)));
    }
}
