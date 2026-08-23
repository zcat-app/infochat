package app.zcat.infochat.core.ingest;

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
 *   <li>{@code dispatchKey} — the per-tick opaque dispatch token the
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
 *   <li>{@code likes} / {@code reposts} / {@code comments} — engagement
 *       counts as reported by the source, or null when the source has no
 *       such signal. <strong>Null is not zero</strong>: an RSS article has
 *       no like count, while a Bluesky post with {@code likeCount: 0} was
 *       seen and ignored. Only the bluesky and reddit fetchers populate
 *       {@code likes}/{@code reposts}; {@code comments} is reddit-only
 *       (its {@code num_comments}); nitter, youtube, odysee, RSS and Nostr
 *       leave all three null, which is the common path (M1-723,
 *       M1-914).</li>
 *   <li>{@code socialScore} — derived, never caller-supplied; see
 *       below.</li>
 * </ul>
 *
 * <h2>Derived {@code socialScore}</h2>
 * <p>The compact constructor <em>overwrites</em> whatever
 * {@code socialScore} it is handed with
 * {@code 2 * coalesce(reposts,0) + coalesce(likes,0)}, the canonical
 * formula in {@code docs/design/05-llm-and-embeddings.md} §5.4.5. It is
 * derived here rather than at each call site so the invariant
 * "socialScore agrees with its two inputs" holds for every instance
 * regardless of construction path — a fetcher cannot produce a post
 * whose stored score disagrees with its stored counts. When BOTH inputs
 * are null the score is null, not 0, so a source with no social signal
 * stays distinguishable from a social post nobody engaged with.</p>
 *
 * <h2>Count bound</h2>
 * <p>{@code likes}, {@code reposts} and {@code comments} arrive from
 * untrusted upstream JSON and are clamped to
 * ±{@link #MAX_ENGAGEMENT_COUNT} — {@code comments} for the same
 * magnitude bound as its siblings even though it never enters the
 * {@code socialScore} multiply. This is system-boundary validation on
 * fetcher input, not internal defensive code: the record is where an
 * untrusted JSON number first becomes a typed domain value, and doing it
 * here covers every present and future fetcher in one place instead of
 * trusting each parser to remember. Sign is preserved — Reddit's
 * {@code score} is a net vote count and is legitimately negative, so only
 * magnitude is bounded.</p>
 *
 * <p><strong>The bound holds only for a value that reached {@code int}
 * intact.</strong> A parser reading an untrusted numeric MUST saturate
 * an out-of-range value, never narrow it: a truncating cast (Jackson's
 * {@code JsonNode.asInt()} is one) wraps modulo 2^32 and arrives here
 * already corrupt, and no clamp can then recover it — the sign is
 * gone ({@code 2147483648} presents as {@code -2147483648}) and, worse,
 * {@code 4294967296} presents as a clean {@code 0}, indistinguishable
 * from a genuine "seen and ignored" observation. Both fetchers that
 * populate these fields saturate on {@code canConvertToInt()} for this
 * reason (M1-723 redteam finding, 2026-07-30).</p>
 *
 * <p>The rest of the contract is documented, not enforced — internal
 * callers (Fetcher / StreamSource impls) are trusted to satisfy it.</p>
 */
public record NormalizedPost(
        long dispatchKey,
        String upstreamIdentifier,
        @Nullable String title,
        String body,
        @Nullable String url,
        @Nullable Instant publishedAt,
        Instant fetchedAt,
        Map<String, String> rawMetadata,
        @Nullable Integer likes,
        @Nullable Integer reposts,
        @Nullable Integer comments,
        @Nullable Integer socialScore
) {

    /**
     * Per-count magnitude bound. {@code Integer.MAX_VALUE / 4} leaves
     * headroom for the {@code 2 * reposts + likes} combination: the
     * worst case is {@code 2 * MAX/4 + MAX/4 = 3*MAX/4}, still short of
     * overflow.
     */
    public static final int MAX_ENGAGEMENT_COUNT = Integer.MAX_VALUE / 4;

    public NormalizedPost {
        likes = clampCount(likes);
        reposts = clampCount(reposts);
        comments = clampCount(comments);
        // Derived, not accepted: see the class javadoc. The incoming
        // socialScore argument is deliberately discarded so the
        // invariant cannot be bypassed by a caller. comments does NOT
        // enter the formula — it is a ranking input, not an
        // amplification count (M1-914).
        socialScore = deriveSocialScore(likes, reposts);
    }

    /**
     * Construct a post from a source that carries no engagement
     * signals — the nitter, youtube, odysee, RSS and Nostr path. All
     * four social columns stay null, the documented "no social signal
     * available" state.
     */
    public NormalizedPost(
            long dispatchKey,
            String upstreamIdentifier,
            @Nullable String title,
            String body,
            @Nullable String url,
            @Nullable Instant publishedAt,
            Instant fetchedAt,
            Map<String, String> rawMetadata) {
        this(dispatchKey, upstreamIdentifier, title, body, url, publishedAt, fetchedAt,
            rawMetadata, null, null, null, null);
    }

    /**
     * Construct a post from a source that reports engagement counts —
     * the bluesky path and the pre-M1-914 reddit path. {@code socialScore}
     * is derived from the two counts; either may be null when that
     * particular source does not expose it (Reddit has no repost count).
     */
    public NormalizedPost(
            long dispatchKey,
            String upstreamIdentifier,
            @Nullable String title,
            String body,
            @Nullable String url,
            @Nullable Instant publishedAt,
            Instant fetchedAt,
            Map<String, String> rawMetadata,
            @Nullable Integer likes,
            @Nullable Integer reposts) {
        this(dispatchKey, upstreamIdentifier, title, body, url, publishedAt, fetchedAt,
            rawMetadata, likes, reposts, null, null);
    }

    /**
     * Construct a post from a source that also reports a reply count —
     * the reddit path since M1-914. {@code comments} is a ranking
     * input only; it never enters the derived {@code socialScore}.
     */
    public NormalizedPost(
            long dispatchKey,
            String upstreamIdentifier,
            @Nullable String title,
            String body,
            @Nullable String url,
            @Nullable Instant publishedAt,
            Instant fetchedAt,
            Map<String, String> rawMetadata,
            @Nullable Integer likes,
            @Nullable Integer reposts,
            @Nullable Integer comments) {
        this(dispatchKey, upstreamIdentifier, title, body, url, publishedAt, fetchedAt,
            rawMetadata, likes, reposts, comments, null);
    }

    private static @Nullable Integer clampCount(@Nullable Integer count) {
        if (count == null) {
            return null;
        }
        if (count > MAX_ENGAGEMENT_COUNT) {
            return MAX_ENGAGEMENT_COUNT;
        }
        if (count < -MAX_ENGAGEMENT_COUNT) {
            return -MAX_ENGAGEMENT_COUNT;
        }
        return count;
    }

    private static @Nullable Integer deriveSocialScore(@Nullable Integer likes,
                                                       @Nullable Integer reposts) {
        if (likes == null && reposts == null) {
            return null;
        }
        return 2 * (reposts == null ? 0 : reposts) + (likes == null ? 0 : likes);
    }
}
