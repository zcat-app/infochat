package app.zcat.infochat.collector.bootstrap;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * One row in {@code bootstrap-sources.json} per the schema in
 * {@code docs/design/07-deployment.md} §7.6.1. The parser
 * ({@link BootstrapSourcesParser}) deserializes the JSON array into a
 * {@code List<BootstrapSourcesEntry>} and applies post-parse semantic
 * validation (≥1 tags, per-kind config shape, Nostr identifier
 * canonicalization) before the loader sees the records.
 *
 * <p>For {@code kind = nostr} the {@code identifier} is the canonical
 * form — keys sorted lexicographically, compact whitespace
 * ({@code docs/spec/architecture.md} §Ingest SPIs Source identity). The
 * parser performs that canonicalization; the loader stores whatever the
 * parser handed it.
 *
 * <p>{@code config} is {@code null} for HTTP-shaped sources
 * ({@code rss}, {@code bluesky}, {@code nitter}, {@code reddit},
 * {@code youtube}, {@code odysee}) and a {@code Map} carrying the
 * relay list for {@code nostr}. {@code tags} is {@code @Nullable}
 * because Jackson leaves an omitted field null at the parse boundary;
 * the parser's validation rejects a null/empty array, so loader-side
 * consumers re-state non-nullness via {@code requireNonNull}.</p>
 *
 * <p>{@code language} is {@code @Nullable} for the same Jackson-boundary
 * reason: an omitted {@code language} defaults to {@code "en"} (the V74
 * column default), resolved by the parser before the loader sees the
 * record, so loader-side consumers re-state non-nullness via
 * {@code requireNonNull}. Unknown codes are rejected by the parser
 * against {@code SourceLanguageRegistry} (D29 — declared, never
 * inferred).</p>
 */
public record BootstrapSourcesEntry(
    String kind,
    String identifier,
    String name,
    String category,
    @Nullable List<String> tags,
    @Nullable Map<String, Object> config,
    @Nullable String language
) {
}
