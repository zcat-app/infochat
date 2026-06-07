package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.core.ingest.NormalizedPost;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
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
 *       <li>{@code to_upstream_identifier} = the original event id,
 *         verbatim — architecture.md §Ingest SPIs: the link is written
 *         as "(kind-6 post UID) →repost→ (original upstream_identifier)",
 *         and "Implementations MUST NOT use the derived UID as the
 *         join key".</li>
 *       <li>{@code to_post} = NULL at write time; resolved to the
 *         original's real {@code post.id} if and when the original
 *         event is also seen and stored. Both arrival orders resolve:
 *         the handler sweeps for an already-present original right
 *         after the edge INSERT (original-first), and the Registrar
 *         invokes {@link RepostEdgeResolver#resolveEdgesPointingTo}
 *         after every successful Nostr persist (repost-first).</li>
 *       <li>{@code link_type} = {@code 'repost'} (V29 CHECK constraint
 *         already admits this value)</li>
 *       <li>{@code score} = {@code 1.0f} — repost has no natural scalar;
 *         the per-link-type unit applies (V29 comment: "score's unit is
 *         per-link_type; callers do not compare scores across link types").</li>
 *     </ul>
 *     The edge is unidirectional, matching architecture.md's directional
 *     "kind-6 post UID →repost→ original" language. The INSERT runs
 *     strictly before the original lookup: insert-then-resolve on this
 *     side plus persist-then-resolve on the original's side means the
 *     two orderings cannot both miss, whatever the cross-source
 *     interleaving (a lookup-before-insert would leave a permanent-miss
 *     race with no retry path).</li>
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
 *     edge unconditionally. The edge stores the original's event id
 *     verbatim — a cryptographic hash that reveals no content about
 *     the original event. security.md §Repost handling: "The reference
 *     is a cryptographic event id (a hash) and reveals no content about
 *     the original event."</li>
 *   <li><b>Duplicate delivery (same event_id arriving multiple times).</b>
 *     {@link NostrDedupFilter} drops duplicates upstream of the deliver
 *     lambda, so this handler sees each kind-6 event at most once per
 *     source lifetime. Even if dedup were bypassed, PostPersister's
 *     ON CONFLICT silently no-ops the persist; the empty-Optional
 *     return then skips the edge write and the eval-queue emit
 *     entirely.</li>
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

    @Inject
    RepostEdgeResolver repostEdgeResolver;

    /**
     * Process one kind-6 NormalizedPost: persist, write the
     * {@code post_reference} edge if the rawMetadata carries the
     * repost target (resolving {@code to_post} immediately when the
     * original is already stored), emit the persisted key onto the
     * eval queue.
     *
     * @param post the NormalizedPost emitted by
     *             {@link NostrEvent#toNormalizedPost(long, java.time.Instant)}
     *             for a kind-6 event. The caller has already verified
     *             {@code rawMetadata.get(NostrEvent.META_KIND).equals("6")}.
     * @param sourceUuid the {@code source.id} UUID for this Nostr source,
     *                   passed by the Registrar from the source row.
     * @return the persisted post's key, or empty on the ON CONFLICT
     *         duplicate branch — the Registrar uses the key to resolve
     *         repost edges that name this kind-6 post as their target
     *         (a kind-6 can itself be reposted).
     */
    public Optional<PostPersister.PersistedPostKey> handle(NormalizedPost post,
                                                           UUID sourceUuid) {
        Optional<PostPersister.PersistedPostKey> persisted = postPersister.persist(sourceUuid, post);
        if (persisted.isEmpty()) {
            // ON CONFLICT branch: duplicate (source_id, upstream_identifier,
            // fetched_at) — the kind-6 was already persisted on a prior
            // tick of this same fetched_at second. Do NOT write a fresh
            // post_reference row (it would duplicate the prior one) and
            // do NOT re-emit to the eval queue (the prior delivery already did).
            return persisted;
        }
        PostPersister.PersistedPostKey key = persisted.get();
        String repostTarget = post.rawMetadata().get(NostrEvent.META_REPOST_TARGET);
        if (repostTarget != null && !repostTarget.isEmpty()) {
            writeRepostEdge(key.id(), repostTarget);
        }
        evalQueueProducer.emit(key);
        return persisted;
    }

    /**
     * Insert one unresolved {@code post_reference} row carrying the
     * original event id verbatim, then resolve it in place if the
     * original is already persisted. The transaction wraps a single
     * INSERT — TransactionHelper is the project convention for
     * raw-JDBC writes; using it here keeps the SQL-failure surface
     * consistent with LinkingJob.
     */
    private void writeRepostEdge(UUID fromPostId, String originalEventId) {
        TransactionHelper.inTransaction(dataSource, "Kind6Handler", conn -> {
            final String sql =
                "INSERT INTO post_reference (from_post, to_post, to_upstream_identifier, link_type, score) "
                    + "VALUES (?, NULL, ?, 'repost', 1.0)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, fromPostId);
                ps.setString(2, originalEventId);
                ps.executeUpdate();
            }
        });
        // Original-first arrival order: the original is already stored,
        // so the edge resolves right away. The resolver's UPDATE (not a
        // resolved-at-INSERT value) keeps this side symmetric with the
        // repost-first order and closes the cross-source race — see the
        // class javadoc.
        repostEdgeResolver.findNostrOriginalPostId(originalEventId)
            .ifPresent(originalPostId ->
                repostEdgeResolver.resolveEdgesPointingTo(originalPostId, originalEventId));
    }
}
