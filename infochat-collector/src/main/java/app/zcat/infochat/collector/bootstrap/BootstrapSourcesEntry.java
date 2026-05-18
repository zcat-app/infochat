package app.zcat.infochat.collector.bootstrap;

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
 * relay list for {@code nostr}.
 */
public record BootstrapSourcesEntry(
    String kind,
    String identifier,
    String name,
    String category,
    List<String> tags,
    Map<String, Object> config
) {
}
