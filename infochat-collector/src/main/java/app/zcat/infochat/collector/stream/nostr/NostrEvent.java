package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.core.ingest.NormalizedPost;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One parsed NIP-01 event (the object inside an {@code ["EVENT", subId,
 * {...}]} frame). Carries the seven canonical fields verbatim; downstream
 * tickets read the fields this ticket does not map onto the outbox row:
 * {@code sig} / {@code pubkey} feed signature verification (M1-097) and
 * {@code kind} / {@code tags} feed kind-6 cross-source linking (M1-100).
 *
 * <p>{@code id} is the lower-case hex SHA-256 of the canonical event JSON
 * per NIP-01. This ticket trusts the relay-supplied {@code id} as the
 * dedup key; recomputing it from the canonical serialization and checking
 * it against the signature is M1-097 (verification), out of scope here.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NostrEvent(
        // Every reference field is @Nullable: the record is Jackson-built
        // from hostile relay JSON where any field can be absent.
        // NostrEventVerifier.verify is the gate that rejects null fields;
        // post-verify consumers re-state that invariant via requireNonNull.
        @JsonProperty("id") @Nullable String id,
        @JsonProperty("pubkey") @Nullable String pubkey,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("kind") int kind,
        @JsonProperty("tags") @Nullable List<List<String>> tags,
        @JsonProperty("content") @Nullable String content,
        @JsonProperty("sig") @Nullable String sig
) {

    /** rawMetadata key carrying the literal Nostr kind ("1", "6") for downstream dispatch. */
    public static final String META_KIND = "nostr.kind";

    /** rawMetadata key carrying the original event id from a kind-6 repost's first ["e", ...] tag. */
    public static final String META_REPOST_TARGET = "nostr.repost-target";

    /**
     * Map this event onto the outbox-input shape. {@code upstream_identifier}
     * is the Nostr event {@code id}; {@code body} is {@code content};
     * {@code published_at} is the event {@code created_at} (Unix seconds),
     * clamped to {@code fetchedAt} so a future-dated event cannot push it
     * past receipt time. Nostr text events have no title or canonical web
     * URL, so both are null.
     *
     * <p>{@code rawMetadata} carries Nostr-specific side-channel information
     * that the Registrar's deliver lambda reads to dispatch:
     * <ul>
     *   <li>{@code META_KIND} ({@code "nostr.kind"}) is always populated for
     *     kind-6 events (value {@code "6"}). Kind-1 events emit an empty
     *     metadata map for cardinality discipline — the dispatch path only
     *     needs to know "is this kind 6?".</li>
     *   <li>{@code META_REPOST_TARGET} ({@code "nostr.repost-target"}) carries
     *     the original event id from the NIP-18 first {@code ["e", event_id,
     *     ...]} tag, populated only when the kind-6 event has such a tag.
     *     Absence (no {@code e} tag) is valid input: the
     *     {@link Kind6Handler} still persists the kind-6 as a post but skips
     *     the {@code post_reference} edge.</li>
     * </ul>
     *
     * @param dispatchKey the dispatch token the supervisor registered this
     *                    stream under; stamped onto the post (opaque to the
     *                    persister, which writes the resolved UUID instead).
     * @param fetchedAt wall-clock receipt time, supplied by the caller.
     */
    public NormalizedPost toNormalizedPost(long dispatchKey, Instant fetchedAt) {
        // Callers deliver only events NostrEventVerifier.verify accepted
        // (the relay trust-boundary gate that rejects null fields); the
        // requireNonNulls re-state that invariant for the type system.
        String id = Objects.requireNonNull(this.id);
        String content = Objects.requireNonNull(this.content);
        Map<String, String> rawMetadata;
        if (kind == 6) {
            String repostTarget = extractFirstETag();
            rawMetadata = (repostTarget == null)
                    ? Map.of(META_KIND, "6")
                    : Map.of(META_KIND, "6", META_REPOST_TARGET, repostTarget);
        } else {
            rawMetadata = Map.of();
        }
        // Clamp published_at to LEAST(created_at, now()) with fetchedAt as
        // "now" (wall-clock receipt time per the param contract). created_at
        // is relay-supplied: one future-dated event would otherwise push the
        // per-source reconnect cursor (MAX(published_at)) past now, making
        // the since filter exclude every genuine event and blinding the bot
        // to that source.
        Instant createdAtInstant = Instant.ofEpochSecond(createdAt);
        Instant publishedAt = createdAtInstant.isAfter(fetchedAt) ? fetchedAt : createdAtInstant;
        return new NormalizedPost(
                dispatchKey,
                id,
                null,
                content,
                null,
                publishedAt,
                fetchedAt,
                rawMetadata);
    }

    /**
     * Return the second element of the first NIP-18 {@code ["e", event_id, ...]}
     * tag, or null if no such tag exists or the tag is malformed (fewer than
     * two elements). NIP-18 kind-6 reposts encode the original event id in
     * the first {@code e} tag; later {@code e} tags (if any) reference
     * additional events the protocol does not bind a "repost target"
     * semantic to.
     */
    @Nullable
    private String extractFirstETag() {
        if (tags == null) {
            return null;
        }
        for (List<String> tag : tags) {
            if (tag != null && tag.size() >= 2 && "e".equals(tag.get(0))) {
                return tag.get(1);
            }
        }
        return null;
    }
}
