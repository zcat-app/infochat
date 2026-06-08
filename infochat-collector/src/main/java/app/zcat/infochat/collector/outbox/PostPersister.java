package app.zcat.infochat.collector.outbox;

import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.util.Sha256;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes one {@code post} row at {@code status='RAW'} per call. The
 * outbox-input shape is the {@link NormalizedPost} produced by a
 * {@link app.zcat.infochat.core.ingest.Fetcher} / {@code StreamSource};
 * the persisted row is the durable cursor that the
 * {@link OutboxRehydrator} re-enqueues across crashes and that the
 * T1-D eval pipeline transitions through {@code 'READY'} /
 * {@code 'QUARANTINED'} / {@code 'NEEDS_REVIEW'} (per
 * {@code docs/spec/schema.md} §Invariants Invariant 5).
 *
 * <h2>UID derivation</h2>
 * <p>The post UID is computed as
 * {@code sha256(source_id || '|' || upstream_identifier)} lower-case
 * hex-encoded, per {@code docs/spec/schema.md} §UID derivation. The
 * M1-007a SPI declares {@code NormalizedPost.upstreamIdentifier} as
 * "Never null"; the persister trusts the contract. A null arrival is
 * treated as an SPI-contract violation and raises
 * {@link IllegalArgumentException} — system-boundary validation
 * against an internal-SPI contract, NOT defensive code (CLAUDE.md
 * §"No defensive code for impossible scenarios"). The spec's
 * content-hash UID fallback is deliberately not implemented in M1
 * (see {@code docs/plan/m1/tickets/M1-028-collector-outbox-fetch.md}
 * §Alternatives considered).
 *
 * <h2>{@code source.id} surrogate</h2>
 * <p>The {@code NormalizedPost.sourceId} field is a {@code long}
 * dispatch key — opaque to the Fetcher; the FetchScheduler holds the
 * long ↔ UUID mapping at startup and passes the resolved UUID into
 * this persister directly. The persist signature therefore takes the
 * UUID explicitly; the NormalizedPost's {@code sourceId} long is not
 * read here.
 *
 * <h2>INSERT shape</h2>
 * <p>One {@code PreparedStatement} writes the full column list
 * explicitly. {@code status} is the literal {@code 'RAW'}; every
 * per-stage {@code *_done} / {@code *_flagged} / {@code *_failed} /
 * {@code *_fallback} flag defaults FALSE; {@code tags} is the empty
 * array. A {@code WHERE NOT EXISTS} uid pre-filter dedups re-fetches
 * of the same item across ticks and partitions (the uid is the
 * cross-refetch dedup key per {@code docs/spec/schema.md} §UID
 * derivation, decision D38); {@code ON CONFLICT (source_id,
 * upstream_identifier, fetched_at) DO NOTHING} remains as
 * belt-and-suspenders for same-partition races (the UNIQUE
 * constraint per {@code docs/design/02-schema.md} §2.3.1).
 */
@ApplicationScoped
public class PostPersister {

    @Inject
    DataSource dataSource;

    /**
     * INSERT one post at {@code status='RAW'}.
     *
     * @param sourceUuid the {@code source.id} this post belongs to;
     *                   the FetchScheduler resolves this from its
     *                   source enumeration.
     * @param normalized the outbox-input post; the caller has already
     *                   stamped {@code fetchedAt}.
     *                   {@code upstreamIdentifier} MUST be non-null
     *                   per the M1-007a SPI contract.
     * @return the persisted row's {@code (id, fetched_at)} composite
     *         key wrapped in {@link Optional}, or empty when the row
     *         was deduplicated — a post with the same uid already
     *         exists (re-fetch on a later tick, uid pre-filter) or
     *         the same {@code (source_id, upstream_identifier,
     *         fetched_at)} raced in concurrently ({@code ON CONFLICT}
     *         branch). Callers treat empty as "already persisted and
     *         enqueued; do not enqueue again".
     * @throws IllegalArgumentException if
     *         {@code normalized.upstreamIdentifier} is null or empty
     *         (SPI contract violation).
     * @throws IllegalStateException on JDBC failure.
     */
    public Optional<PersistedPostKey> persist(UUID sourceUuid, NormalizedPost normalized) {
        Objects.requireNonNull(sourceUuid, "sourceUuid");
        Objects.requireNonNull(normalized, "normalized");
        String upstreamIdentifier = normalized.upstreamIdentifier();
        if (upstreamIdentifier == null || upstreamIdentifier.isEmpty()) {
            // SPI-contract assertion: NormalizedPost.upstreamIdentifier
            // is declared "Never null" (M1-007a). A null / empty
            // arrival here is a Fetcher bug, not a recoverable runtime
            // condition; throw loudly so the FetchScheduler's WARN
            // log surfaces the bug instead of persisting an ID-less
            // row.
            throw new IllegalArgumentException(
                "PostPersister: upstreamIdentifier required by NormalizedPost SPI contract; "
                + "got null/empty for sourceUuid=" + sourceUuid);
        }

        String uid = deriveUid(sourceUuid, upstreamIdentifier);

        // Cross-tick dedup: the NOT EXISTS pre-filter checks the uid
        // (stable across re-fetches per docs/spec/schema.md §UID
        // derivation, decision D38) against ALL partitions, so a
        // re-fetched item with a fresh fetched_at never creates a
        // second row. The lookup is index-backed — UNIQUE
        // (uid, fetched_at) gives each partition a btree with uid as
        // the leading column. No table-level UNIQUE on uid alone is
        // possible (the partition key must participate in every
        // unique constraint), so under concurrently-overlapping
        // scheduler ticks the pre-filter is advisory; within the
        // single-instance Collector's non-overlapping ticks it is
        // deterministic. ON CONFLICT stays as belt-and-suspenders for
        // exactly that same-partition race window.
        final String sql =
            "INSERT INTO post ("
                + "  id, uid, source_id, upstream_identifier, url, title, body, "
                + "  author, published_at, fetched_at, status, "
                + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                + ") SELECT "
                + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RAW', "
                + "  FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, '{}'::text[] "
                + "WHERE NOT EXISTS (SELECT 1 FROM post WHERE uid = ?) "
                + "ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING "
                + "RETURNING id, fetched_at";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, uid);
            ps.setObject(2, sourceUuid);
            ps.setString(3, upstreamIdentifier);
            ps.setString(4, normalized.url());
            // post.title is NOT NULL per V7 schema; the SPI marks title
            // nullable. Coerce null -> "" so a malformed feed item
            // missing a title does not abort the whole batch.
            ps.setString(5, normalized.title() == null ? "" : normalized.title());
            ps.setString(6, normalized.body());
            // NormalizedPost v1 has no author field; the column is
            // nullable per V7.
            ps.setString(7, null);
            // Clamp a source-claimed publish time to the wall-clock
            // receipt time. published_at is source-controlled (RSS
            // <pubDate>, Atom <published>, Bluesky indexedAt); a
            // future-dated claim would sort to the top of every
            // searchPosts window and ORDER BY published_at DESC fed to
            // the chat LLM (security.md §Prompt-injection defenses) and
            // push the per-source reconnect cursor MAX(published_at)
            // past now. A non-future value binds unchanged; a null
            // value binds as SQL NULL. fetchedAt is non-null per the
            // NormalizedPost SPI contract, so the comparison is total.
            Instant fetchedAt = normalized.fetchedAt();
            Instant publishedAt = normalized.publishedAt();
            Instant clampedPublishedAt =
                publishedAt != null && publishedAt.isAfter(fetchedAt) ? fetchedAt : publishedAt;
            ps.setTimestamp(8, clampedPublishedAt == null ? null : Timestamp.from(clampedPublishedAt));
            ps.setTimestamp(9, Timestamp.from(fetchedAt));
            // The NOT EXISTS pre-filter's uid probe.
            ps.setString(10, uid);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID insertedId = (UUID) rs.getObject(1);
                    Instant insertedFetchedAt = rs.getTimestamp(2).toInstant();
                    return Optional.of(new PersistedPostKey(insertedId, insertedFetchedAt));
                }
                // Silent dedup: the uid pre-filter matched an
                // existing row (cross-tick re-fetch) or the ON
                // CONFLICT branch fired (same-partition race).
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "PostPersister: INSERT into post failed for sourceUuid=" + sourceUuid
                + " uid=" + uid, e);
        }
    }

    /**
     * Computes the post UID per
     * {@code docs/spec/schema.md} §UID derivation:
     * {@code sha256(source_id || '|' || upstream_identifier)}
     * lower-case hex. Package-private so the IT can pin the exact
     * digest against a fixture.
     */
    static String deriveUid(UUID sourceUuid, String upstreamIdentifier) {
        String preimage = sourceUuid.toString() + "|" + upstreamIdentifier;
        return Sha256.hex(preimage.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Composite key of a successfully persisted post row.
     *
     * @param id        the row's {@code id} UUID.
     * @param fetchedAt the partition key.
     */
    public record PersistedPostKey(UUID id, Instant fetchedAt) {
    }
}
