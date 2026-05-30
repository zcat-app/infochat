package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.core.ingest.NormalizedPost;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        @JsonProperty("id") String id,
        @JsonProperty("pubkey") String pubkey,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("kind") int kind,
        @JsonProperty("tags") List<List<String>> tags,
        @JsonProperty("content") String content,
        @JsonProperty("sig") String sig
) {

    /**
     * Map this event onto the outbox-input shape. {@code upstream_identifier}
     * is the Nostr event {@code id}; {@code body} is {@code content};
     * {@code published_at} is the event {@code created_at} (Unix seconds).
     * Nostr text events have no title or canonical web URL, so both are null.
     *
     * @param sourceId  the dispatch token the supervisor registered this
     *                  stream under; stamped onto the post (opaque to the
     *                  persister, which writes the resolved UUID instead).
     * @param fetchedAt wall-clock receipt time, supplied by the caller.
     */
    @NonNull
    public NormalizedPost toNormalizedPost(long sourceId, @NonNull Instant fetchedAt) {
        return new NormalizedPost(
                sourceId,
                id,
                null,
                content,
                null,
                Instant.ofEpochSecond(createdAt),
                fetchedAt,
                Map.of());
    }
}
