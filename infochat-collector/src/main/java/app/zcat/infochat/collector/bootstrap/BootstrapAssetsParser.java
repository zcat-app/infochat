package app.zcat.infochat.collector.bootstrap;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses {@code bootstrap-assets.json} into a
 * {@code List<BootstrapAssetsEntry>} per the schema in
 * {@code docs/design/10-asset-commands.md} §10.6. Strict-by-default —
 * unknown top-level or per-entry fields are rejected (Jackson
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = true}); the post-parse
 * semantic check enforces {@code default_sub_verb ∈ sub_verbs[].id}
 * and rejects duplicate asset ids within one document.
 *
 * <p>The top-level JSON shape is an OBJECT (not an array) carrying
 * {@code default_vs} (the document-level quote-currency default) and
 * {@code assets[]} (the per-asset entries). The parser deserializes
 * via a package-private wrapper record and enriches each
 * {@link BootstrapAssetsEntry} with the document-level
 * {@code defaultQuoteCurrency} so the loader is purely entry-driven.
 *
 * <p>This class constructs its own {@link ObjectMapper} — same
 * rationale as {@link BootstrapSourcesParser} (the project does not
 * yet carry the {@code quarkus-jackson} extension; the inline
 * configuration is the single tightening point). When
 * {@code quarkus-jackson} lands, both parsers migrate together.
 *
 * <p>Oversize-input defense: the parser relies on Jackson's default
 * {@code StreamReadConstraints} ({@code maxNestingDepth = 1000},
 * {@code maxDocumentLength = 5_000_000} on 2.17+). A deeply-nested
 * adversarial input trips the constraint and surfaces as
 * {@link BootstrapAssetsParseException} rather than exhausting the
 * heap.
 */
public final class BootstrapAssetsParser {

    private final ObjectMapper objectMapper;

    public BootstrapAssetsParser() {
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    /**
     * Parses the contents of {@code path} and returns the validated
     * entries. Convenience overload around {@link #parse(InputStream)}.
     */
    public List<BootstrapAssetsEntry> parse(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new BootstrapAssetsParseException(
                "bootstrap-assets.json read failed at " + path.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses the JSON byte stream, applies semantic validation, and
     * returns each entry enriched with the document-level
     * {@code defaultQuoteCurrency}. Any parse or validation failure
     * surfaces as {@link BootstrapAssetsParseException} so the
     * loader's startup bean can abort Collector boot.
     */
    public List<BootstrapAssetsEntry> parse(InputStream in) {
        DocWrapper doc;
        try {
            doc = objectMapper
                .readerFor(DocWrapper.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(in);
        } catch (IOException e) {
            throw new BootstrapAssetsParseException(
                "bootstrap-assets.json parse failed: " + e.getMessage(), e);
        }

        requireNonBlank(doc.defaultVs(), "default_vs");
        if (doc.assets() == null || doc.assets().isEmpty()) {
            throw new BootstrapAssetsParseException(
                "bootstrap-assets.json must declare a non-empty 'assets' array");
        }

        Set<String> seenAssetIds = new HashSet<>(doc.assets().size());
        List<BootstrapAssetsEntry> out = new ArrayList<>(doc.assets().size());
        for (int i = 0; i < doc.assets().size(); i++) {
            EntryWrapper raw = doc.assets().get(i);
            BootstrapAssetsEntry validated = validate(raw, i, doc.defaultVs());
            if (!seenAssetIds.add(validated.id())) {
                throw new BootstrapAssetsParseException(
                    "assets[" + i + "] id='" + validated.id() + "' is a duplicate of an earlier entry");
            }
            out.add(validated);
        }
        return out;
    }

    private BootstrapAssetsEntry validate(EntryWrapper raw, int index, String defaultVs) {
        requireNonBlank(raw.id(),             "assets[" + index + "].id");
        requireNonBlank(raw.displayName(),    "assets[" + index + "].display_name");
        requireNonBlank(raw.ticker(),         "assets[" + index + "].ticker");
        requireNonBlank(raw.defaultSubVerb(), "assets[" + index + "].default_sub_verb");

        if (raw.subVerbs() == null || raw.subVerbs().isEmpty()) {
            throw new BootstrapAssetsParseException(
                "assets[" + index + "].sub_verbs must be a non-empty array");
        }
        // sub_verbs[].id must be unique within an entry, and the
        // entry's default_sub_verb must resolve to one of them. The
        // default-sub-verb check is the operator-typo guard: a missed
        // value here would silently break bare /asset invocations.
        Set<String> seenSubVerbIds = new LinkedHashSet<>(raw.subVerbs().size());
        List<BootstrapAssetsEntry.SubVerb> validatedSubVerbs = new ArrayList<>(raw.subVerbs().size());
        for (int j = 0; j < raw.subVerbs().size(); j++) {
            SubVerbWrapper sv = raw.subVerbs().get(j);
            requireNonBlank(sv.id(),         "assets[" + index + "].sub_verbs[" + j + "].id");
            requireNonBlank(sv.externalId(), "assets[" + index + "].sub_verbs[" + j + "].external_id");
            if (!seenSubVerbIds.add(sv.id())) {
                throw new BootstrapAssetsParseException(
                    "assets[" + index + "].sub_verbs[" + j + "].id='" + sv.id()
                        + "' is a duplicate within the entry");
            }
            validatedSubVerbs.add(new BootstrapAssetsEntry.SubVerb(sv.id(), sv.externalId()));
        }

        if (!seenSubVerbIds.contains(raw.defaultSubVerb())) {
            throw new BootstrapAssetsParseException(
                "assets[" + index + "].default_sub_verb='" + raw.defaultSubVerb()
                    + "' is not present in sub_verbs[].id (operator typo would silently break bare /"
                    + raw.id() + ")");
        }

        return new BootstrapAssetsEntry(
            raw.id(),
            raw.displayName(),
            raw.ticker(),
            raw.defaultSubVerb(),
            List.copyOf(validatedSubVerbs),
            defaultVs);
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BootstrapAssetsParseException(label + " must be a non-blank string");
        }
    }

    /**
     * Top-level JSON shape per §10.6 — {@code default_vs} and
     * {@code assets[]}. Package-private and only used during
     * deserialization; the public-facing record is
     * {@link BootstrapAssetsEntry}.
     */
    record DocWrapper(
        @JsonProperty("default_vs") String defaultVs,
        @JsonProperty("assets")     List<EntryWrapper> assets
    ) {
        @JsonCreator
        DocWrapper {
        }
    }

    record EntryWrapper(
        @JsonProperty("id")               String id,
        @JsonProperty("display_name")     String displayName,
        @JsonProperty("ticker")           String ticker,
        @JsonProperty("default_sub_verb") String defaultSubVerb,
        @JsonProperty("sub_verbs")        List<SubVerbWrapper> subVerbs
    ) {
        @JsonCreator
        EntryWrapper {
        }
    }

    record SubVerbWrapper(
        @JsonProperty("id")          String id,
        @JsonProperty("external_id") String externalId
    ) {
        @JsonCreator
        SubVerbWrapper {
        }
    }

    /**
     * Thrown for any parse-time or post-parse semantic-validation
     * failure. {@link BootstrapAssetsLoader} catches at startup and
     * propagates as a startup failure (Quarkus default).
     */
    public static final class BootstrapAssetsParseException extends RuntimeException {
        public BootstrapAssetsParseException(String message) {
            super(message);
        }

        public BootstrapAssetsParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
