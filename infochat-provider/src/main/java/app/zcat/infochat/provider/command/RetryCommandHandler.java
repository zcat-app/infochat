package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository.AnchorRow;
import app.zcat.infochat.provider.digest.DigestRenderer;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.digest.DigestRetryService;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /retry} per docs/spec/commands.md §Conversation control.
 * The personal path replays the prose layer of the last summary-producing
 * command using the frozen post selection and cluster mapping stored in
 * {@code summary_anchor} (D19, D36). The replay comes back in the render
 * form the anchored {@code /summary} produced (M1-696): an anchor whose
 * {@code command_name} carries the exact {@code --full} marker replays the
 * flat per-cluster blocks ({@link ClusterBlockRenderer}), anything else
 * replays the default categorized sections
 * ({@link DigestRenderer#renderSummarySections}) — still as a single
 * {@link OutboundMessage}. The {@code --digest} path delegates
 * to {@link DigestRetryService} for per-group serialized digest
 * re-generation (M1-080c).
 */
@ApplicationScoped
public class RetryCommandHandler implements CommandHandler {

    private static final String SELECT_GROUP =
            "SELECT id, digest_enabled FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    // Fetch posts by uid where status='READY'. The uid column on post
    // is the human-readable "p-<hash>" identifier; we join source for
    // display_name. Only READY posts pass the status filter.
    // p.classification is projected alongside p.tags so the replayed cluster
    // block renders the same classification: line /summary did for the same DB
    // state (D19/D36 byte-identical replay; the shared ClusterBlockRenderer
    // reads Post.classification).
    private static final String SELECT_POSTS_BY_UIDS = """
            SELECT p.id, p.uid, p.source_id, s.display_name AS source_display_name,
                   p.title, p.url, p.body, p.published_at, p.tags, p.classification
            FROM post p
            JOIN source s ON s.id = p.source_id
            WHERE p.uid = ANY(?) AND p.status = 'READY'
            """;

    private static final String SELECT_SCOPE_LANGUAGE =
            "SELECT language FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    CancellationService cancellationService;

    @Inject
    DataSource dataSource;

    @Inject
    SummaryAnchorRepository summaryAnchorRepository;

    @Inject
    SummaryProseGenerator summaryProseGenerator;

    @Inject
    DigestRenderer digestRenderer;

    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    @Inject
    TranslationPipeline translationPipeline;

    @Inject
    InboundContext inboundContext;

    @Inject
    UserRepository userRepository;

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    LlmRateCap llmRateCap;

    @Inject
    DigestRetryService digestRetryService;

    @Inject
    RateCapBucket rateCapBucket;

    @Inject
    GroupMembershipRepository groupMembershipRepository;

    @Inject
    AuditLogWriter auditLogWriter;

    @ConfigProperty(name = "infochat.retry.cap", defaultValue = "3")
    int retryCap;

    @ConfigProperty(name = "infochat.retry.status-drift-threshold", defaultValue = "0.25")
    double statusDriftThreshold;

    @Override
    public String name() {
        return "retry";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (hasFlag(rawText, "--digest")) {
            return handleDigestRetry(scope);
        }
        Optional<UUID> userId = resolveUserId(scope);
        if (userId.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR, inboundContext.effectiveLanguage()));
        }

        // The anchor read key must match what SummaryCommandHandler writes
        // (per-(user, scope) isolation, D19/D36): scope_kind is derived from
        // the actual inbound scope, scope_id is the caller's own id in a DM
        // and the group's id in a group. A group with no registered row can
        // hold no anchor, so it reads as NO_ANCHOR.
        String scopeKind = EligiblePostQuery.scopeKindOf(scope);
        Optional<UUID> scopeIdOpt = resolveScopeId(scope, userId.get());
        if (scopeIdOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR, inboundContext.effectiveLanguage()));
        }
        UUID scopeId = scopeIdOpt.get();

        Optional<AnchorRow> anchorOpt =
                summaryAnchorRepository.read(userId.get(), scopeKind, scopeId);
        if (anchorOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR, inboundContext.effectiveLanguage()));
        }
        AnchorRow anchor = anchorOpt.get();

        // Filter frozen UIDs against current post.status='READY' before
        // incrementing the retry counter — a retry against quarantined
        // posts should not consume a cap slot
        List<Post> readyPosts = fetchReadyPosts(anchor.postUids());
        int originalCount = anchor.postUids().size();
        int excludedCount = originalCount - readyPosts.size();

        if (readyPosts.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ELIGIBLE_POSTS, inboundContext.effectiveLanguage()));
        }

        // Acquire the in-flight slot for the LLM re-roll BEFORE the
        // LLM bucket and the retry counter, so a busy rejection
        // consumes neither a bucket token nor one of the anchor's
        // retry slots — rejections leave both in a state where the
        // next permitted request succeeds.
        InFlightTracker.CancellationHandle slot =
                inFlightTracker.tryAcquire(userId.get(), scopeKind, scopeId);
        if (slot == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_IN_FLIGHT, inboundContext.effectiveLanguage()));
        }

        try {
            // Adopted a turn /stop had already cancelled — marked while
            // QUEUED, between the stage preamble and this acquire (M1-638):
            // skip before the cap peek and the LLM bucket, so a cancelled
            // re-roll burns neither a retry slot nor a token. The seeded
            // stage reconciles this Reply into the acknowledgement
            // placeholder as the D35 stopped terminal.
            if (slot.isCancelled()) {
                return reply(scope, bundleLoader.get(BundleKeys.PROGRESS_STOPPED,
                        inboundContext.effectiveLanguage()));
            }
            // Read-then-check the retry cap BEFORE acquiring an LLM token or
            // incrementing the counter: an at-cap /retry must consume neither
            // a rate-cap token nor further counter growth. The in-memory
            // retry counter is monotonic and does NOT self-heal (unlike the
            // rate-cap bucket), so an increment-then-check would let the
            // counter grow unboundedly past the cap and would burn a token on
            // a re-roll the cap already forbids. The in-flight slot acquired
            // above serializes re-rolls for this (user, scope), so the peeked
            // count cannot be raced by a concurrent increment before the
            // increment below.
            if (summaryAnchorRepository.peekRetryCount(userId.get(), scopeKind, scopeId) >= retryCap) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_RETRY_CAP_EXHAUSTED, inboundContext.effectiveLanguage()),
                        String.valueOf(retryCap)));
            }

            // security.md §Rate limiting: /retry re-rolls draw from the
            // same per-user LLM bucket as chat replies and /summary.
            // Acquired AFTER the cap pre-check (no token spent on an at-cap
            // retry) but BEFORE the counter increment so a rate-cap rejection
            // does not burn a retry slot (bucket tokens self-heal after 60 s;
            // the anchor's retry slots do not).
            if (!llmRateCap.tryAcquire(userId.get())) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP, inboundContext.effectiveLanguage()));
            }

            // Under cap (checked above) — record this retry.
            summaryAnchorRepository.incrementAndGetRetryCount(
                    userId.get(), scopeKind, scopeId);

            // Reconstruct clusters from the stored cluster_map, filtering
            // to only READY posts
            List<Cluster> clusters = reconstructClusters(anchor.clusterMapJson(), readyPosts);
            if (clusters.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ELIGIBLE_POSTS, inboundContext.effectiveLanguage()));
            }

            String scopeLanguage = readScopeLanguage(scopeKind, scopeId);

            // Re-run the LLM prose layer
            List<ClusterProse> prose = summaryProseGenerator.generate(clusters, "en");

            StringBuilder out = new StringBuilder();

            // Prepend status-drift notice if drift exceeds threshold
            if (excludedCount > 0
                    && (double) excludedCount / originalCount >= statusDriftThreshold) {
                out.append(MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_RETRY_STATUS_DRIFT_NOTICE, inboundContext.effectiveLanguage()),
                        String.valueOf(excludedCount),
                        String.valueOf(originalCount)));
            }

            boolean anyDegraded = prose.stream().anyMatch(ClusterProse::degraded);
            if (anyDegraded) {
                out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_DEGRADED_NOTICE, inboundContext.effectiveLanguage()));
                out.append("\n\n");
            }

            // Replay in the render form the anchored /summary produced
            // (M1-696, M1-699): render_form is the typed dispatch axis.
            // 'flat' replays the flat per-cluster blocks; 'bare' replays
            // the categorized sections, joined back into the single
            // OutboundMessage /retry has always returned (per-section
            // delivery is /summary's shape, M1-695, not /retry's). The
            // switch is structured so M1-700 can add 'short' and 'full'
            // arms without restructuring; the default throws so a future
            // form written without its dispatch arm fails loudly rather
            // than silently replaying the wrong shape.
            switch (anchor.renderForm()) {
                case "flat" -> {
                    ClusterBlockRenderer clusterBlockRenderer =
                            new ClusterBlockRenderer(llmOutputSanitizer, translationPipeline, bundleLoader);
                    for (ClusterProse cp : prose) {
                        clusterBlockRenderer.appendClusterBlock(out, cp, scopeLanguage);
                    }
                }
                case "bare" -> {
                    out.append(String.join("\n\n",
                            digestRenderer.renderSummarySections(prose, scopeLanguage).stream()
                                    .map(RenderedSection::text)
                                    .toList()));
                }
                default -> throw new IllegalStateException(
                        "Unhandled summary_anchor.render_form: " + anchor.renderForm()
                                + " ('short'/'full' are M1-700)");
            }

            return reply(scope, out.toString().stripTrailing());
        } finally {
            inFlightTracker.release(userId.get(), scopeKind, scopeId, slot);
            // Gate close + interrupt-status clear, LAST in the section —
            // see CancellationHandle.releaseWorker (M1-634).
            slot.releaseWorker();
        }
    }

    /**
     * Reconstruct clusters from the stored JSON cluster map, filtering
     * to only posts present in the readyPosts list. Clusters whose
     * posts all got filtered out are dropped.
     */
    List<Cluster> reconstructClusters(@Nullable String clusterMapJson, List<Post> readyPosts) {
        if (clusterMapJson == null || clusterMapJson.isBlank()) {
            // Fallback: re-cluster from scratch (same algorithm as /summary).
            // clusterMapJson is always non-null when written by SummaryCommandHandler,
            // so this path is a safety net for malformed anchor rows.
            return readyPosts.stream()
                    .map(p -> new Cluster("t-" + p.uid().substring(0, Math.min(8, p.uid().length())),
                            List.of(p)))
                    .toList();
        }

        Map<String, Post> postsByUid = new LinkedHashMap<>();
        for (Post p : readyPosts) {
            postsByUid.put(p.uid(), p);
        }

        List<Cluster> clusters = new ArrayList<>();
        try (JsonReader reader = Json.createReader(new StringReader(clusterMapJson))) {
            JsonArray arr = reader.readArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.getJsonObject(i);
                String topicId = obj.getString("topicId");
                JsonArray uids = obj.getJsonArray("postUids");
                List<Post> clusterPosts = new ArrayList<>();
                for (JsonString uidVal : uids.getValuesAs(JsonString.class)) {
                    Post p = postsByUid.get(uidVal.getString());
                    if (p != null) {
                        clusterPosts.add(p);
                    }
                }
                if (!clusterPosts.isEmpty()) {
                    clusters.add(new Cluster(topicId, clusterPosts));
                }
            }
        }
        return clusters;
    }

    private List<Post> fetchReadyPosts(List<String> postUids) {
        String[] uidStrings = postUids.toArray(new String[0]);
        List<Post> posts = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_POSTS_BY_UIDS)) {
            cancellationService.applyStatementTimeout(conn);
            Array sqlArray = conn.createArrayOf("text", uidStrings);
            ps.setArray(1, sqlArray);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    posts.add(mapPost(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("RetryCommandHandler.fetchReadyPosts failed", e);
        }
        return posts;
    }

    @SuppressWarnings("unchecked")
    private Post mapPost(ResultSet rs) throws SQLException {
        String[] rawTags = (String[]) rs.getArray("tags").getArray();
        String[] rawClassification = (String[]) rs.getArray("classification").getArray();
        Timestamp publishedTs = rs.getTimestamp("published_at");
        return new Post(
                (UUID) rs.getObject("id"),
                rs.getString("uid"),
                (UUID) rs.getObject("source_id"),
                rs.getString("source_display_name"),
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("body"),
                // Nullable per V7__joins_post.sql: a source need not supply a
                // publication date. This re-fetch keys on the uids /summary
                // froze into the anchor and applies no window of its own, so
                // it inherits reachability from whatever /summary could
                // return — and since M1-689 moved that window predicate off
                // published_at, a NULL row can reach here. Under the old
                // published_at >= ? predicate it could not, which is why this
                // read was unguarded (M1-689 redteam round 3).
                publishedTs == null ? null : publishedTs.toInstant(),
                List.of(rawTags),
                List.of(rawClassification));
    }

    private Optional<UUID> resolveUserId(ScopeRef scope) {
        // The caller's contact id: ScopeRef carries it only for a DM, but
        // InboundContext carries the sender's contact id for both DM and group
        // scope, so a group member resolves to their own users.id here.
        String contactId = switch (scope) {
            case ScopeRef.Dm dm -> dm.contactId();
            case ScopeRef.Group ignored -> inboundContext.senderContactId();
        };
        return userRepository.resolveUserId(inboundContext.adapterName(), contactId);
    }

    // DM: scope_id is the caller's own user id; group: the group's db id
    // (matching SummaryCommandHandler's write-side resolution). Empty when
    // the group has no registered row, so no anchor can exist for it.
    private Optional<UUID> resolveScopeId(ScopeRef scope, UUID userId) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> Optional.of(userId);
            case ScopeRef.Group group -> {
                GroupRow groupRow = lookupGroup(group.adapterGroupId());
                yield groupRow == null ? Optional.empty() : Optional.of(groupRow.id());
            }
        };
    }

    private String readScopeLanguage(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCOPE_LANGUAGE)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "en";
                }
                return rs.getString("language");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("RetryCommandHandler.readScopeLanguage failed", e);
        }
    }

    private OutboundMessage handleDigestRetry(ScopeRef scope) {
        if (scope instanceof ScopeRef.Dm) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_GROUP_ONLY, inboundContext.effectiveLanguage()));
        }
        ScopeRef.Group group = (ScopeRef.Group) scope;

        String adapter = inboundContext.adapterName();
        String contactId = inboundContext.senderContactId();
        ActorRow actor = lookupActor(adapter, contactId);
        if (actor == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_GROUP_ADMIN_REQUIRED, inboundContext.effectiveLanguage()));
        }

        GroupRow groupRow = lookupGroup(group.adapterGroupId());
        if (groupRow == null
                || (!actor.isAdmin
                    && !groupMembershipRepository.isGroupAdmin(groupRow.id(), actor.id))) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_GROUP_ADMIN_REQUIRED, inboundContext.effectiveLanguage()));
        }
        UUID groupDbId = groupRow.id();

        // Close the /retry --digest bypass: this path runs through
        // DigestRetryService, NOT the scheduler, so the digest_enabled gate
        // in queryActiveGroups does not cover it. Without this check a group
        // admin could regenerate and re-send a paused group's stale cached
        // digest. Reject before the audit/cap gates so a paused retry costs
        // no audit row and no rate-limit token.
        if (!groupRow.digestEnabled()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_PAUSED, inboundContext.effectiveLanguage()));
        }

        writeDigestRetryAudit(actor, adapter, contactId, groupDbId);

        // security.md §Rate limiting: /retry re-rolls draw from the same
        // per-user LLM bucket as chat replies and /summary; the per-group
        // sub-bucket (D47) is the aggregate backstop and fires second. A
        // group-cap rejection refunds the per-user token so the backstop
        // consumes no personal budget. Audited above before the cap gates
        // — every authorized attempt leaves an audit row regardless of
        // outcome (matching the cooldown / NO_PRIOR shapes below), so an
        // admin hammering --digest stays audit-visible. Tokens are spent
        // before retryDigest even though non-SUCCESS results skip the LLM
        // — over-counting is conservative for an anti-DOS cap.
        if (!llmRateCap.tryAcquire(actor.id)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP, inboundContext.effectiveLanguage()));
        }
        if (!rateCapBucket.tryAcquireGroupLlm(groupDbId)) {
            llmRateCap.refund(actor.id);
            return reply(scope, bundleLoader.get(BundleKeys.GROUP_LLM_RATE_LIMIT, inboundContext.effectiveLanguage()));
        }
        DigestRetryService.RetryResult result = digestRetryService.retryDigest(groupDbId);
        return switch (result) {
            case SUCCESS -> reply(scope,
                    bundleLoader.get(BundleKeys.REPLY_RETRY_DIGEST_SUCCESS, inboundContext.effectiveLanguage()));
            case REPLAYED_MISSING -> reply(scope,
                    bundleLoader.get(BundleKeys.REPLY_RETRY_DIGEST_REPLAYED_MISSING, inboundContext.effectiveLanguage()));
            case ALL_ALREADY_DELIVERED -> reply(scope,
                    bundleLoader.get(BundleKeys.REPLY_RETRY_DIGEST_ALL_ALREADY_DELIVERED, inboundContext.effectiveLanguage()));
            case ALREADY_IN_PROGRESS -> reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_ALREADY_IN_PROGRESS, inboundContext.effectiveLanguage()));
            case NO_PRIOR_DIGEST -> reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_NO_PRIOR, inboundContext.effectiveLanguage()));
            case RATE_LIMITED -> reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_RATE_LIMITED, inboundContext.effectiveLanguage()));
        };
    }

    private void writeDigestRetryAudit(ActorRow actor, String adapter,
                                       String contactId, UUID groupDbId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                auditLogWriter.write(conn, new RedactionHook.AuditRow(
                        actor.id, contactId, adapter,
                        AuditAction.DIGEST_RETRY,
                        TargetKind.GROUP, groupDbId.toString(),
                        null, groupDbId, null, null));
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RetryCommandHandler.writeDigestRetryAudit failed", e);
        }
    }

    private @Nullable ActorRow lookupActor(String adapter, String contactId) {
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new ActorRow(u.id(), u.isAdmin()))
                .orElse(null);
    }

    private @Nullable GroupRow lookupGroup(String adapterGroupId) {
        String adapter = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP)) {
            ps.setString(1, adapter);
            ps.setString(2, adapterGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new GroupRow(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("digest_enabled"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RetryCommandHandler.lookupGroup failed", e);
        }
    }

    private static boolean hasFlag(String rawText, String flag) {
        for (String part : rawText.split("\\s+")) {
            if (part.equals(flag)) return true;
        }
        return false;
    }

    private record ActorRow(UUID id, boolean isAdmin) {
    }

    private record GroupRow(UUID id, boolean digestEnabled) {
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
