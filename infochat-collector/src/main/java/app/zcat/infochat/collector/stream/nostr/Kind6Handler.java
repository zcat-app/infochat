package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.core.ingest.NormalizedPost;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Downstream handler for NIP-18 kind-6 Nostr repost events. The
 * {@code NostrStreamSource.Registrar} deliver lambda routes any
 * {@link NormalizedPost} whose {@code rawMetadata.get(NostrEvent.META_KIND)}
 * equals {@code "6"} to {@link #handle}; kind-1 events follow the existing
 * {@code postPersister.persist(...) → evalQueueProducer.emit(...)} path
 * unchanged.
 *
 * <h2>What this handler does</h2>
 * <ol>
 *   <li>Persist the kind-6 post (commentary as body, or empty body) via
 *     {@link PostPersister}. The persister's
 *     {@code ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING}
 *     means a duplicate same-tick delivery silently no-ops.</li>
 *   <li>If the persisted row exists AND the rawMetadata carries
 *     {@link NostrEvent#META_REPOST_TARGET}, write one
 *     {@code post_reference} row with:
 *     <ul>
 *       <li>{@code from_post} = the kind-6 post's real
 *         {@code post.id} UUID (from the persister)</li>
 *       <li>{@code to_post} =
 *         {@code UUID.nameUUIDFromBytes(originalEventId.getBytes(UTF_8))} —
 *         a deterministic UUID v3 derived from ONLY the original event
 *         id. Source-independent so any future relay arrival of the
 *         original re-derives the same to_post UUID, allowing downstream
 *         resolution by re-derivation (architecture.md §Ingest SPIs:
 *         "the upstream_identifier is the stable, protocol-level key
 *         that survives this ordering").</li>
 *       <li>{@code link_type} = {@code 'repost'} (V29 CHECK constraint
 *         already admits this value)</li>
 *       <li>{@code score} = {@code 1.0f} — repost has no natural scalar;
 *         the per-link-type unit applies (V29 comment: "score's unit is
 *         per-link_type; callers do not compare scores across link types").</li>
 *     </ul>
 *     The edge is unidirectional, matching architecture.md's directional
 *     "kind-6 post UID →repost→ original" language. Downstream queries
 *     resolve by computing the same derivation from any newly-arriving
 *     post's upstream_identifier (the inverse leg is not needed because
 *     the to_post derivation is deterministic and re-derivable from
 *     either endpoint).</li>
 *   <li>Emit the persisted post's {@code PersistedPostKey} to the
 *     {@code eval-queue} so Stage 1 / Stage 2 / tagger / embedding run
 *     on the commentary body — kind-6 events go through the same
 *     evaluation pipeline as kind-1 (the body, after all, is operator-
 *     supplied prose for a meaningful repost).</li>
 * </ol>
 *
 * <h2>Edge-cases the handler tolerates</h2>
 * <ul>
 *   <li><b>Kind-6 with no NIP-18 {@code ["e", ...]} tag.</b> The
 *     post is persisted; the post_reference edge is skipped (no target
 *     to reference). This is malformed per NIP-18 but a hostile or
 *     buggy client could emit one; dropping the edge is the safe
 *     response, dropping the whole event would lose the commentary.</li>
 *   <li><b>Kind-6 referencing a disallowed-kind original (kind 4, 7,
 *     etc.).</b> The handler does NOT know the original's kind (it
 *     would require auto-resolution, forbidden by D38). It writes the
 *     edge unconditionally. The edge stores a deterministic UUID derived
 *     from a cryptographic event id hash — it reveals no content about
 *     the original event. security.md §Repost handling: "The reference
 *     is a cryptographic event id (a hash) and reveals no content about
 *     the original event."</li>
 *   <li><b>Duplicate delivery (same event_id arriving multiple times).</b>
 *     {@link NostrDedupFilter} drops duplicates upstream of the deliver
 *     lambda, so this handler sees each kind-6 event at most once per
 *     source lifetime. Even if dedup were bypassed, PostPersister's
 *     ON CONFLICT silently no-ops; the post_reference INSERT would
 *     succeed (its PK includes created_at, so two writes at slightly
 *     different timestamps land as separate rows — acceptable for the
 *     'repost' link type since the per-direction dedup the LinkingJob
 *     uses for entity/semantic does not apply here).</li>
 * </ul>
 */
@ApplicationScoped
public class Kind6Handler {

    @Inject
    DataSource dataSource;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    /**
     * Process one kind-6 NormalizedPost: persist, write the
     * {@code post_reference} edge if the rawMetadata carries the
     * repost target, emit the persisted key onto the eval queue.
     *
     * @param post the NormalizedPost emitted by
     *             {@link NostrEvent#toNormalizedPost(long, java.time.Instant)}
     *             for a kind-6 event. The caller has already verified
     *             {@code rawMetadata.get(NostrEvent.META_KIND).equals("6")}.
     * @param sourceUuid the {@code source.id} UUID for this Nostr source,
     *                   passed by the Registrar from the source row.
     */
    public void handle(@NonNull NormalizedPost post, @NonNull UUID sourceUuid) {
        Optional<PostPersister.PersistedPostKey> persisted = postPersister.persist(sourceUuid, post);
        if (persisted.isEmpty()) {
            // ON CONFLICT branch: duplicate (source_id, upstream_identifier,
            // fetched_at) — the kind-6 was already persisted on a prior
            // tick of this same fetched_at second. Do NOT write a fresh
            // post_reference row (it would duplicate the prior one) and
            // do NOT re-emit to the eval queue (the prior delivery already did).
            return;
        }
        PostPersister.PersistedPostKey key = persisted.get();
        String repostTarget = post.rawMetadata().get(NostrEvent.META_REPOST_TARGET);
        if (repostTarget != null && !repostTarget.isEmpty()) {
            writeRepostEdge(key.id(), repostTarget);
        }
        evalQueueProducer.emit(key);
    }

    /**
     * Insert one {@code post_reference} row keyed by the deterministic
     * UUID derivation of the original event id. The transaction wraps
     * a single INSERT — TransactionHelper is the project convention for
     * raw-JDBC writes; using it here keeps the SQL-failure surface
     * consistent with LinkingJob.
     */
    private void writeRepostEdge(UUID fromPostId, String originalEventId) {
        UUID toPostUuid = deriveToPostUuid(originalEventId);
        TransactionHelper.inTransaction(dataSource, "Kind6Handler", conn -> {
            final String sql =
                "INSERT INTO post_reference (from_post, to_post, link_type, score) "
                    + "VALUES (?, ?, 'repost', 1.0)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, fromPostId);
                ps.setObject(2, toPostUuid);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Derive the {@code post_reference.to_post} UUID for a kind-6 repost
     * edge from the original event id. Source-independent (does NOT
     * incorporate {@code source_id}) so the original event arriving
     * later from any relay re-derives the same UUID — the architecture
     * contract that lets downstream code resolve the link without an
     * UPDATE on post_reference. Package-private so the tests can pin
     * the derivation against a known fixture.
     */
    @NonNull
    static UUID deriveToPostUuid(@NonNull String originalEventId) {
        return UUID.nameUUIDFromBytes(originalEventId.getBytes(StandardCharsets.UTF_8));
    }
}
