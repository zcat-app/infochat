package app.zcat.infochat.collector.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * Parses {@code bootstrap-sources.json} into a
 * {@code List<BootstrapSourcesEntry>} per the schema in
 * {@code docs/design/07-deployment.md} §7.6.1. Strict-by-default —
 * unknown top-level fields are rejected (Jackson
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = true}) and the per-kind
 * {@code config} shape table from §7.6.1 is enforced as post-parse
 * semantic validation.
 *
 * <p>For {@code kind = nostr} entries the JSON-object {@code identifier}
 * is canonicalized — keys sorted lexicographically and re-serialized
 * with compact whitespace — before being handed to the loader, per the
 * source-identity rule in {@code docs/spec/architecture.md} §Ingest
 * SPIs Source identity. Two semantically-identical filter specs with
 * differently-ordered keys produce the same canonical string, so the
 * {@code (kind, identifier)} UNIQUE key in {@code source} cannot fork
 * into duplicate rows on every relay-list edit (decision D38).
 *
 * <p>This class constructs its own {@link ObjectMapper} rather than
 * consuming a CDI-injected one. The project does not yet carry the
 * {@code quarkus-jackson} extension (only the transitively-available
 * {@code jackson-databind} via {@code flyway-core}); the inline
 * configuration is the single point where the strictness toggles can
 * be tightened later. When {@code quarkus-jackson} lands, this parser
 * migrates to {@code @Inject ObjectMapper} in a focused diff.
 */
public final class BootstrapSourcesParser {

    private static final Set<String> HTTP_SHAPED_KINDS = Set.of(
        "rss", "bluesky", "nitter", "reddit", "youtube", "odysee");

    private static final String NOSTR_KIND = "nostr";

    private final ObjectMapper objectMapper;

    public BootstrapSourcesParser() {
        // FAIL_ON_UNKNOWN_PROPERTIES rejects extras in each entry object;
        // ORDER_MAP_ENTRIES_BY_KEYS is what canonicalize() leans on when
        // re-serializing the Nostr identifier as a key-sorted, compact
        // JSON string.
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * Parses the JSON bytes and applies post-parse validation. Returns
     * the canonicalized entry list ready for the loader to upsert. Any
     * parse or validation failure surfaces as
     * {@link BootstrapSourcesParseException} so the startup bean's
     * top-level handler can fail the boot.
     */
    public List<BootstrapSourcesEntry> parse(byte[] jsonBytes) {
        BootstrapSourcesEntry[] raw;
        try {
            raw = objectMapper
                .readerFor(BootstrapSourcesEntry[].class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(jsonBytes);
        } catch (IOException e) {
            throw new BootstrapSourcesParseException(
                "bootstrap-sources.json parse failed: " + e.getMessage(), e);
        }

        List<BootstrapSourcesEntry> out = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            out.add(validateAndCanonicalize(raw[i], i));
        }
        return out;
    }

    /**
     * Enforces the per-entry schema and returns the canonicalized form.
     * For Nostr entries the {@code identifier} is re-serialized with
     * sorted keys; for HTTP-shaped kinds the entry is passed through
     * unchanged after the {@code config == null} check.
     */
    private BootstrapSourcesEntry validateAndCanonicalize(BootstrapSourcesEntry entry, int index) {
        requireNonBlank(entry.kind(),       "entry[" + index + "].kind");
        requireNonBlank(entry.identifier(), "entry[" + index + "].identifier");
        requireNonBlank(entry.name(),       "entry[" + index + "].name");
        requireNonBlank(entry.category(),   "entry[" + index + "].category");

        if (entry.tags() == null || entry.tags().isEmpty()) {
            throw new BootstrapSourcesParseException(
                "entry[" + index + "].tags must be a non-empty array (docs/design/07-deployment.md §7.6.1: tags yes, ≥1)");
        }

        String kind = entry.kind().toLowerCase(Locale.ROOT);

        if (HTTP_SHAPED_KINDS.contains(kind)) {
            // HTTP-shaped sources: config MUST be null or omitted
            // (per the Per-kind config shape table in §7.6.1).
            if (entry.config() != null) {
                throw new BootstrapSourcesParseException(
                    "entry[" + index + "] kind=" + kind
                        + " requires config to be null or omitted; non-null configs are reserved for stream-shaped kinds (HTTP-shaped sources have no per-kind config in v1)");
            }
            return entry;
        }

        if (NOSTR_KIND.equals(kind)) {
            validateNostrConfig(entry.config(), index);
            String canonicalIdentifier = canonicalizeNostrIdentifier(entry.identifier(), index);
            return new BootstrapSourcesEntry(
                entry.kind(),
                canonicalIdentifier,
                entry.name(),
                entry.category(),
                entry.tags(),
                entry.config());
        }

        throw new BootstrapSourcesParseException(
            "entry[" + index + "] has unsupported kind '" + entry.kind()
                + "' (expected one of: " + HTTP_SHAPED_KINDS + " or '" + NOSTR_KIND + "')");
    }

    /**
     * Validates the Nostr {@code config.relays} array — non-empty,
     * every element {@code wss://}-prefixed — per
     * {@code docs/design/07-deployment.md} §7.6.1 Per-kind config shape.
     */
    @SuppressWarnings("unchecked")
    private void validateNostrConfig(Map<String, Object> config, int index) {
        if (config == null) {
            throw new BootstrapSourcesParseException(
                "entry[" + index + "] kind=nostr requires a config object with a non-empty 'relays' array");
        }
        Object relaysRaw = config.get("relays");
        if (!(relaysRaw instanceof List<?> relays) || relays.isEmpty()) {
            throw new BootstrapSourcesParseException(
                "entry[" + index + "] kind=nostr config.relays must be a non-empty array");
        }
        for (Object r : relays) {
            if (!(r instanceof String s) || !s.startsWith("wss://")) {
                throw new BootstrapSourcesParseException(
                    "entry[" + index + "] kind=nostr config.relays entries must be wss:// strings; got: " + r);
            }
        }
    }

    /**
     * Re-serializes a Nostr filter-spec identifier with
     * lexicographically-sorted keys and compact whitespace. The input
     * is the operator's JSON string from the file; the output is the
     * canonical form keyed by the {@code (kind, identifier)} UNIQUE in
     * {@code source}. Equivalent specs with swapped key orders
     * canonicalize to identical strings — the D38 invariant.
     *
     * <p>Implementation: parse to a generic {@code Map}, copy entries
     * into a {@code TreeMap} (recursive for nested objects so deep
     * keys are also sorted), re-serialize. Jackson's
     * {@code SORT_PROPERTIES_ALPHABETICALLY} would not sort recursive
     * nested objects deterministically without the manual tree walk.
     */
    private String canonicalizeNostrIdentifier(String rawIdentifier, int index) {
        try {
            Object parsed = objectMapper.readValue(rawIdentifier, Object.class);
            Object sorted = sortKeysRecursively(parsed);
            return objectMapper.writeValueAsString(sorted);
        } catch (IOException e) {
            throw new BootstrapSourcesParseException(
                "entry[" + index + "] kind=nostr identifier is not valid JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object sortKeysRecursively(@Nullable Object node) {
        if (node instanceof Map<?, ?> mapNode) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : mapNode.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), sortKeysRecursively(e.getValue()));
            }
            return sorted;
        }
        if (node instanceof List<?> listNode) {
            List<Object> out = new ArrayList<>(listNode.size());
            for (Object item : listNode) {
                out.add(sortKeysRecursively(item));
            }
            return out;
        }
        return node;
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BootstrapSourcesParseException(label + " must be a non-blank string");
        }
    }

    /**
     * Conversion helper for the loader: re-serializes the entry's
     * {@code config} map (already validated, already key-sorted via
     * {@code ORDER_MAP_ENTRIES_BY_KEYS}) into a JSON string suitable
     * for the {@code source.config JSONB} column. Returns {@code "{}"}
     * for {@code null} config (the column is NOT NULL DEFAULT '{}').
     */
    public String configToJsonString(Map<String, Object> config) {
        if (config == null) {
            return "{}";
        }
        try {
            Map<String, Object> sorted = new LinkedHashMap<>();
            for (Object key : new TreeMap<>(config).keySet()) {
                sorted.put((String) key, sortKeysRecursively(config.get(key)));
            }
            return objectMapper.writeValueAsString(sorted);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Thrown for any parse-time or post-parse semantic-validation
     * failure. The {@link BootstrapLoader} catches at startup and
     * propagates as a startup failure (Quarkus default — the service
     * refuses to start, per {@code docs/design/01-architecture.md}
     * §1.4.3).
     */
    public static final class BootstrapSourcesParseException extends RuntimeException {
        public BootstrapSourcesParseException(String message) {
            super(message);
        }

        public BootstrapSourcesParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
