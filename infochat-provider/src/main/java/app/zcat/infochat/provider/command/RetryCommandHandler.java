package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository.AnchorRow;
import app.zcat.infochat.provider.digest.DigestRetryService;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implements {@code /retry} per docs/spec/commands.md §Conversation control.
 * The personal path replays the prose layer of the last summary-producing
 * command using the frozen post selection and cluster mapping stored in
 * {@code summary_anchor} (D19, D36). The {@code --digest} path delegates
 * to {@link DigestRetryService} for per-group serialized digest
 * re-generation (M1-080c).
 */
@ApplicationScoped
public class RetryCommandHandler implements CommandHandler {

    private static final String SELECT_USER_ID =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_ACTOR_WITH_ADMIN =
            "SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    // Fetch posts by uid where status='READY'. The uid column on post
    // is the human-readable "p-<hash>" identifier; we join source for
    // display_name. Only READY posts pass the status filter.
    private static final String SELECT_POSTS_BY_UIDS = """
            SELECT p.id, p.uid, p.source_id, s.display_name AS source_display_name,
                   p.title, p.url, p.body, p.published_at, p.tags
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
    LlmOutputSanitizer llmOutputSanitizer;

    @Inject
    TranslationPipeline translationPipeline;

    @Inject
    InboundContext inboundContext;

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    DigestRetryService digestRetryService;

    @Inject
    GroupMembershipRepository groupMembershipRepository;

    @Inject
    AuditLogWriter auditLogWriter;

    @ConfigProperty(name = "infochat.retry.cap", defaultValue = "3")
    int retryCap;

    @ConfigProperty(name = "infochat.retry.status-drift-threshold", defaultValue = "0.25")
    double statusDriftThreshold;

    @Override
    public @NonNull String name() {
        return "retry";
    }

    @Override
    public @NonNull OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        if (hasFlag(rawText, "--digest")) {
            return handleDigestRetry(scope);
        }
        Optional<UUID> userId = resolveUserId(scope);
        if (userId.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR));
        }

        // v1 DM scope: scopeId = userId
        UUID scopeId = userId.get();

        Optional<AnchorRow> anchorOpt = summaryAnchorRepository.read(userId.get(), scopeId);
        if (anchorOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR));
        }
        AnchorRow anchor = anchorOpt.get();

        // Filter frozen UIDs against current post.status='READY' before
        // incrementing the retry counter — a retry against quarantined
        // posts should not consume a cap slot
        List<Post> readyPosts = fetchReadyPosts(anchor.postUids());
        int originalCount = anchor.postUids().size();
        int excludedCount = originalCount - readyPosts.size();

        if (readyPosts.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ELIGIBLE_POSTS));
        }

        // Enforce profile-driven retry cap
        int retryCount = summaryAnchorRepository.incrementAndGetRetryCount(userId.get(), scopeId);
        if (retryCount > retryCap) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_RETRY_CAP_EXHAUSTED),
                    String.valueOf(retryCap)));
        }

        // Acquire in-flight slot for the LLM re-roll
        String scopeKind = "dm";
        if (!inFlightTracker.tryAcquire(userId.get(), scopeKind, scopeId)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR));
        }

        try {
            // Reconstruct clusters from the stored cluster_map, filtering
            // to only READY posts
            List<Cluster> clusters = reconstructClusters(anchor.clusterMapJson(), readyPosts);
            if (clusters.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ELIGIBLE_POSTS));
            }

            String scopeLanguage = readScopeLanguage(scopeKind, scopeId);

            // Re-run the LLM prose layer
            List<ClusterProse> prose = summaryProseGenerator.generate(clusters, "en");

            StringBuilder out = new StringBuilder();

            // Prepend status-drift notice if drift exceeds threshold
            if (excludedCount > 0
                    && (double) excludedCount / originalCount >= statusDriftThreshold) {
                out.append(MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_RETRY_STATUS_DRIFT_NOTICE),
                        String.valueOf(excludedCount),
                        String.valueOf(originalCount)));
            }

            boolean anyDegraded = prose.stream().anyMatch(ClusterProse::degraded);
            if (anyDegraded) {
                out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_DEGRADED_NOTICE));
                out.append("\n\n");
            }

            for (ClusterProse cp : prose) {
                appendClusterBlock(out, cp, scopeLanguage);
            }

            return reply(scope, out.toString().stripTrailing());
        } finally {
            inFlightTracker.release(userId.get(), scopeKind, scopeId);
        }
    }

    private void appendClusterBlock(StringBuilder out, ClusterProse cp, String scopeLanguage) {
        Cluster cluster = cp.cluster();
        List<Post> posts = cluster.posts();
        Post first = posts.get(0);

        out.append("[topic_id=").append(cluster.topicId()).append("]\n");
        out.append(first.title()).append("\n");
        out.append("covered by: ");
        for (int i = 0; i < posts.size(); i++) {
            Post p = posts.get(i);
            if (i > 0) out.append(", ");
            out.append(p.sourceDisplayName()).append(" (uid ").append(p.uid()).append(")");
        }
        out.append("\n");
        Set<String> sourceSet = new LinkedHashSet<>();
        for (Post p : posts) {
            sourceSet.add(p.sourceDisplayName());
        }
        out.append("score: ").append(sourceSet.size())
           .append(sourceSet.size() == 1 ? " source" : " sources")
           .append("\n");
        // /retry output passes through LlmOutputSanitizer (spec §LLM output sanitizer)
        String summaryText = cp.degraded()
                ? cp.prose()
                : translationPipeline.run(
                        llmOutputSanitizer.sanitize(cp.prose()), scopeLanguage);
        out.append("summary: ").append(summaryText).append("\n");
        out.append("classification: ").append(joinedTags(posts)).append("\n");
        out.append("tags: ").append(joinedTags(posts)).append("\n");
        out.append("\n");
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
        return new Post(
                (UUID) rs.getObject("id"),
                rs.getString("uid"),
                (UUID) rs.getObject("source_id"),
                rs.getString("source_display_name"),
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("body"),
                rs.getTimestamp("published_at").toInstant(),
                List.of(rawTags));
    }

    private Optional<UUID> resolveUserId(ScopeRef scope) {
        if (!(scope instanceof ScopeRef.Dm dm)) {
            return Optional.empty();
        }
        String adapterName = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_ID)) {
            ps.setString(1, adapterName);
            ps.setString(2, dm.contactId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("RetryCommandHandler.resolveUserId failed", e);
        }
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

    private static String joinedTags(List<Post> posts) {
        Set<String> union = new LinkedHashSet<>();
        for (Post p : posts) {
            union.addAll(p.tags());
        }
        return String.join(", ", union);
    }

    private OutboundMessage handleDigestRetry(ScopeRef scope) {
        if (scope instanceof ScopeRef.Dm) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_GROUP_ONLY));
        }
        ScopeRef.Group group = (ScopeRef.Group) scope;

        String adapter = inboundContext.adapterName();
        String contactId = inboundContext.senderContactId();
        ActorRow actor = lookupActor(adapter, contactId);
        if (actor == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_GROUP_ADMIN_REQUIRED));
        }

        UUID groupDbId = lookupGroupId(group.adapterGroupId());
        if (groupDbId == null
                || (!actor.isAdmin
                    && !groupMembershipRepository.isGroupAdmin(groupDbId, actor.id))) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_GROUP_ADMIN_REQUIRED));
        }

        writeDigestRetryAudit(actor, adapter, contactId, groupDbId);
        DigestRetryService.RetryResult result = digestRetryService.retryDigest(groupDbId);
        return switch (result) {
            case SUCCESS -> reply(scope,
                    bundleLoader.get(BundleKeys.REPLY_RETRY_DIGEST_SUCCESS));
            case ALREADY_IN_PROGRESS -> reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_ALREADY_IN_PROGRESS));
            case NO_PRIOR_DIGEST -> reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_NO_PRIOR));
            case RATE_LIMITED -> reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_RATE_LIMITED));
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
                        "group", groupDbId.toString(),
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_WITH_ADMIN)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ActorRow(
                        (UUID) rs.getObject("id"),
                        rs.getBoolean("is_admin"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RetryCommandHandler.lookupActor failed", e);
        }
    }

    private @Nullable UUID lookupGroupId(String adapterGroupId) {
        String adapter = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, adapterGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RetryCommandHandler.lookupGroupId failed", e);
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

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
