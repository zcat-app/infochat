package app.zcat.infochat.core.ingest;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * The outbox-input shape that every ingest implementation hands the
 * pipeline. v1 minimum field set; downstream stages (Stage 1 sanitizer,
 * tagger, embedding) produce derived columns on the {@code posts} row
 * separately and never on this record.
 *
 * <h2>Field contract</h2>
 * <ul>
 *   <li>{@code sourceId} — the per-tick opaque dispatch token the
 *       scheduler handed the Fetcher SPI for this fetch; it is NOT
 *       the {@code source.id} UUID, is not stable across ticks, and
 *       must not be used to key any persistent or cross-tick
 *       state.</li>
 *   <li>{@code upstreamIdentifier} — the source-side unique id used by
 *       the dedup column (RSS guid / Bluesky cid / Nostr event id /
 *       Reddit fullname / etc.). Never null.</li>
 *   <li>{@code title} — nullable; not every source has a title.</li>
 *   <li>{@code body} — the post text. Never null; use empty string for
 *       genuinely empty content.</li>
 *   <li>{@code url} — nullable; some sources (e.g. Nostr text events)
 *       have no canonical web URL.</li>
 *   <li>{@code publishedAt} — source-supplied publish time; nullable
 *       because not every source provides one.</li>
 *   <li>{@code fetchedAt} — the wall-clock time this row was produced
 *       by the Fetcher / StreamSource. Never null.</li>
 *   <li>{@code rawMetadata} — non-null, possibly empty. Map of string
 *       to string by design: richer per-element metadata that sources
 *       want to carry must be serialized (e.g. JSON-in-string) into
 *       one entry rather than smuggled through as {@code Object}.</li>
 * </ul>
 *
 * <p>The contract is documented, not enforced — internal callers
 * (Fetcher / StreamSource impls) are trusted to satisfy it. Validation
 * belongs at system boundaries, not inside the SPI record.</p>
 */
public record NormalizedPost(
        long sourceId,
        @NonNull String upstreamIdentifier,
        @Nullable String title,
        @NonNull String body,
        @Nullable String url,
        @Nullable Instant publishedAt,
        @NonNull Instant fetchedAt,
        @NonNull Map<String, String> rawMetadata
) {
}
