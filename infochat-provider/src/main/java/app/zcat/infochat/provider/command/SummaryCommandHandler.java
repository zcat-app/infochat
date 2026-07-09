package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressNotifier;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.command.SummaryArgs.Failure;
import app.zcat.infochat.provider.command.SummaryArgs.ParseResult;
import app.zcat.infochat.provider.command.SummaryArgs.Success;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /summary} per docs/spec/commands.md §Content +
 * docs/design/03-commands.md §`/summary [tag] [-w 24h]`. Auto-discovered
 * by {@code InboundRouter} via the CDI {@code Instance<CommandHandler>}
 * scan; no router-side edit needed when this handler lands.
 *
 * <p>Flow:
 * <ol>
 *   <li>Parse args via {@link SummaryArgs#parse(String)}; a typed
 *       {@link Failure} short-circuits to the friendly error.</li>
 *   <li>Resolve the caller's {@code users.id} from the inbound
 *       {@code (adapter, contact_id)} — carried by {@link InboundContext}
 *       for both DM and group scope — then the scope id: the caller's own
 *       {@code users.id} for DM, the {@code groups.id} for group scope
 *       (the router resolved + validated the group at its step 4.1 before
 *       dispatch).</li>
 *   <li>If a positional tag was supplied, verify it appears in the
 *       controlled vocabulary; otherwise emit the
 *       {@code error.summary.unknown_tag} bundle with a fuzzy
 *       suggestion footer.</li>
 *   <li>Run {@link EligiblePostQuery#fetch} for the deterministic
 *       eligible-post set (status='READY', window, subscriptions,
 *       optional tag filter, cap).</li>
 *   <li>Acquire the per-(user, scope) {@link InFlightTracker} slot and
 *       a per-user {@link LlmRateCap} token; a busy slot or empty
 *       bucket short-circuits to the localized rejection reply with no
 *       LLM call.</li>
 *   <li>Run {@link ClusterTraversal#cluster} (singletons in MVP).</li>
 *   <li>Run {@link SummaryProseGenerator#generate} (one LLM call per
 *       cluster).</li>
 *   <li>For each prose, run {@link LlmOutputSanitizer#sanitize}.</li>
 *   <li>Compose the per-cluster output blocks (6 deterministic fields +
 *       sanitized {@code summary:} field) + optional cap-excess /
 *       top-3 prefix into a single {@link OutboundMessage}.</li>
 * </ol>
 */
@ApplicationScoped
public class SummaryCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SummaryCommandHandler.class);

    /**
     * Per the V5 {@code UNIQUE (adapter, contact_id)} constraint on
     * {@code users}, the lookup MUST qualify on both columns: a
     * {@code contact_id}-only WHERE would cross-resolve to a different
     * adapter's user row in a multi-adapter deployment (decision D46:
     * SimpleX + Signal running side-by-side). The adapter name reaches
     * this handler through the {@link InboundContext} CDI bean that
     * the {@link app.zcat.infochat.provider.messaging.InboundRouter}
     * populates on every inbound dispatch.
     */
    private static final String SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_SCOPE_LANGUAGE =
            "SELECT language FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";

    /**
     * Group-scope id resolution: the active (not soft-removed)
     * {@code groups} row for the inbound {@code (adapter,
     * upstream_group_id)}. Same query the {@code InboundRouter} runs at
     * step 4.1 and the sibling {@code ClearCommandHandler} uses, so the
     * scope id the anchor keys on is the same {@code groups.id} the
     * router carried through the dispatch.
     */
    private static final String SELECT_GROUP_ID_BY_ADAPTER_AND_UPSTREAM_ID =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    /** Max fuzzy-suggestion entries surfaced in the unknown-tag error. */
    private static final int FUZZY_SUGGESTION_MAX = 5;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    EligiblePostQuery eligiblePostQuery;

    @Inject
    ClusterTraversal clusterTraversal;

    @Inject
    SummaryProseGenerator summaryProseGenerator;

    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    @Inject
    TranslationPipeline translationPipeline;

    @Inject
    InboundContext inboundContext;

    @Inject
    SummaryAnchorRepository summaryAnchorRepository;

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    LlmRateCap llmRateCap;

    @Inject
    ProgressNotifier progressNotifier;

    @Override
    public String name() {
        return "summary";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a non-null {@link OutboundMessage} for every guard /
     * error branch (parse failure, no-posts, unknown tag, in-flight,
     * rate-cap) — the router sends those. For the terminal
     * summary path the handler owns its own message lifecycle through
     * {@link ProgressNotifier} (placeholder &rarr; coalesced
     * {@code update} &rarr; {@code complete}/{@code fail}) and returns
     * {@code null}, so the router performs no send and the user gets
     * exactly one visibly-evolving message rather than a duplicate.</p>
     */
    @Override
    public @Nullable OutboundMessage handle(ScopeRef scope, String rawText) {
        ParseResult parsed = SummaryArgs.parse(rawText);
        if (parsed instanceof Failure f) {
            return reply(scope, format(f.bundleKey(), f.interpolationArgs().toArray()));
        }
        SummaryArgs args = ((Success) parsed).args();

        // Resolve the caller's users.id from the inbound (adapter,
        // contact_id). InboundContext carries the sender's contact id for
        // both DM and group scope (ScopeRef carries it only for DM), so a
        // group member resolves to their own users.id here. An unknown
        // contact (no users row) yields no_posts_yet.
        String adapterName = inboundContext.adapterName();
        Optional<UUID> actorIdOpt = lookupUserId(adapterName, inboundContext.senderContactId());
        if (actorIdOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, inboundContext.effectiveLanguage()));
        }
        UUID actorId = actorIdOpt.get();

        // Resolve the scope id the post query and the anchor key on: the
        // caller's own users.id for DM, the groups.id for group scope
        // (per-member personal anchors, commands.md ~:779). Per-(user,
        // scope) isolation: the anchor row and the in-flight slot key on
        // (actorId, scope_kind, scopeId), so a user's DM summary and their
        // group summary never read or overwrite each other.
        String scopeKind = EligiblePostQuery.scopeKindOf(scope);
        Optional<UUID> scopeId = resolveScopeId(scope, actorId, adapterName);
        if (scopeId.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, inboundContext.effectiveLanguage()));
        }

        // Unknown-tag check via the controlled vocabulary. The parser
        // accepts any syntactically-valid tag; the handler tells the
        // user when the tag misses the catalogue.
        if (args.tag().isPresent()) {
            List<String> vocab = eligiblePostQuery.readVocabulary();
            if (!vocab.contains(args.tag().get())) {
                List<String> suggestions = EligiblePostQuery.fuzzySuggest(
                        args.tag().get(), vocab, FUZZY_SUGGESTION_MAX);
                Failure failure = SummaryArgs.unknownTagFailure(args.tag().get(), suggestions);
                return reply(scope, format(failure.bundleKey(),
                        failure.interpolationArgs().toArray()));
            }
        }

        Result result = eligiblePostQuery.fetch(scopeKind, scopeId.get(), args.tag(), args.window());
        if (result.posts().isEmpty()) {
            // Distinguish the fresh-user empty-feed cliff from a
            // subscribed-but-empty window (M1-593, commands.md §Content): a scope
            // following no sources gets the actionable no_subscriptions steer, not
            // the window-blaming no_posts_yet. The subscription count runs ONLY on
            // this empty branch (a resolved scope id in hand), so a /summary that
            // returns posts pays no extra query.
            String emptyKey = eligiblePostQuery.countSubscriptions(scopeKind, scopeId.get()) == 0
                    ? BundleKeys.REPLY_SUMMARY_NO_SUBSCRIPTIONS
                    : BundleKeys.REPLY_SUMMARY_NO_POSTS_YET;
            return reply(scope, bundleLoader.get(emptyKey, inboundContext.effectiveLanguage()));
        }

        // At most one in-flight interruptible request per (user, scope)
        // (commands.md §Surface conventions); the registration is also
        // what lets /stop find the prose generation (D35). The slot is
        // checked BEFORE the LLM bucket so neither check consumes
        // anything on a rejection — an already-in-flight rejection
        // takes no bucket token, and a rate-cap rejection records no
        // timestamp, so the next permitted request still succeeds. The
        // slot keys on (actorId, scope_kind, scopeId) so /stop targets the
        // caller's request in this exact scope.
        InFlightTracker.CancellationHandle slot =
                inFlightTracker.tryAcquire(actorId, scopeKind, scopeId.get());
        if (slot == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT, inboundContext.effectiveLanguage()));
        }
        try {
            // security.md §Rate limiting: on-demand /summary draws from
            // the same per-user LLM bucket as chat replies and /retry
            // re-rolls.
            if (!llmRateCap.tryAcquire(actorId)) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP, inboundContext.effectiveLanguage()));
            }

            // Committed to the terminal summary path. From here the
            // handler owns its outbound lifecycle via ProgressNotifier:
            // publish STARTED (placeholder send + typing on), then the
            // stages around the real work, then complete()/fail(). The
            // method returns null so InboundRouter performs no send.
            // The progress strings are resolved from the D43 bundle by
            // the notifier; no user-authored text reaches them.
            boolean delivered = false;
            try {
                progressNotifier.publish(scope, ProgressStage.STARTED);
                progressNotifier.publish(scope, ProgressStage.RETRIEVING);
                String scopeLanguage = readScopeLanguage(scopeKind, scopeId.get());

                List<Cluster> clusters = clusterTraversal.cluster(result.posts());

                progressNotifier.publish(scope, ProgressStage.GENERATING);
                List<ClusterProse> prose = summaryProseGenerator.generate(clusters, "en");

                // Write the summary anchor — enables /retry to replay the prose
                // layer with the same deterministic post selection (D19, D36).
                // Written on both normal and degraded paths: spec says "/retry
                // against this degraded run regenerates the prose if the LLM
                // has recovered."
                List<String> postUids = result.posts().stream().map(Post::uid).toList();
                String argHash = computeArgHash(rawText);
                String clusterMapJson = serializeClusterMap(clusters);
                summaryAnchorRepository.write(
                        actorId, scopeKind, scopeId.get(), "summary",
                        argHash, postUids, clusterMapJson);

                // Only publish TRANSLATING when the scope actually translates —
                // the same guard TranslationPipeline uses. For an English scope
                // (the default), no translation work happens, so the label would be
                // misleading.
                if (!scopeLanguage.equalsIgnoreCase("en")) {
                    progressNotifier.publish(scope, ProgressStage.TRANSLATING);
                }

                StringBuilder out = new StringBuilder();

                if (result.topTagRestriction().isPresent()) {
                    out.append(format(BundleKeys.REPLY_SUMMARY_TOP_3_OF_N_PREFIX,
                            result.topTagRestriction().get().followedTagCount()));
                    out.append("\n\n");
                }
                if (result.excludedCount() > 0) {
                    out.append(format(BundleKeys.REPLY_SUMMARY_CAP_EXCESS_NOTICE,
                            result.posts().size(),
                            result.totalBeforeCap(),
                            result.profileLabel(),
                            result.excludedCount()));
                    out.append("\n\n");
                }

                boolean anyDegraded = prose.stream().anyMatch(ClusterProse::degraded);
                if (anyDegraded) {
                    out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_DEGRADED_NOTICE, inboundContext.effectiveLanguage()));
                    out.append("\n\n");
                }

                ClusterBlockRenderer clusterBlockRenderer =
                        new ClusterBlockRenderer(llmOutputSanitizer, translationPipeline, bundleLoader);
                for (ClusterProse cp : prose) {
                    clusterBlockRenderer.appendClusterBlock(out, cp, scopeLanguage);
                }

                // Degraded prose is still a successful terminal delivery
                // (the composed body carries the degraded notice). Only a
                // thrown failure routes to fail() — the notifier renders a
                // localized failure string and finalizes the placeholder
                // so it is never left dangling.
                progressNotifier.publish(scope, ProgressStage.FINALIZING);
                if (slot.isCancelled()) {
                    // /stop marked this request while the work completed (a
                    // missed interrupt): discard the composed summary and
                    // render the D35 stopped terminal instead of delivering a
                    // result as if /stop never happened.
                    progressNotifier.complete(scope, stoppedTerminal());
                } else {
                    progressNotifier.complete(scope, out.toString().stripTrailing());
                }
                delivered = true;
            } catch (RuntimeException e) {
                if (slot.isCancelled()) {
                    // A landed cancellation interrupt surfaced as an exception
                    // out of generation. Render the D35 stopped terminal, not
                    // the generic failure reply — D31/D35 forbid the failure
                    // terminal for a user-initiated /stop.
                    progressNotifier.complete(scope, stoppedTerminal());
                } else {
                    SafeLog.error(log, "SummaryCommandHandler summary generation failed", e);
                    progressNotifier.fail(scope);
                }
                delivered = true;
            } finally {
                // A non-RuntimeException throwable (e.g. an Error such as
                // OutOfMemoryError) escaping before complete()/fail() would
                // otherwise leave the notifier's per-scope placeholder state
                // dangling (spec step 4: "placeholders are never left
                // dangling"). fail() finalizes the placeholder and evicts the
                // per-scope state; the throwable then continues to propagate.
                if (!delivered) {
                    progressNotifier.fail(scope);
                }
            }
            // Self-delivered via the notifier — no router send.
            return null;
        } finally {
            inFlightTracker.release(actorId, scopeKind, scopeId.get(), slot);
        }
    }

    /**
     * Resolve the scope id the post query and the anchor key on: the
     * caller's own {@code users.id} for DM, the {@code groups.id} for
     * group scope. The router resolved + validated the group at its step
     * 4.1 before this handler runs; an empty result here is the
     * vanished-group race (concurrent removal) and falls through to
     * no_posts_yet, matching the unknown-contact path.
     */
    private Optional<UUID> resolveScopeId(ScopeRef scope, UUID actorId, String adapter) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> Optional.of(actorId);
            case ScopeRef.Group group -> lookupGroupId(adapter, group.adapterGroupId());
        };
    }

    /**
     * Resolve the caller's {@code users.id} from the inbound
     * {@code (adapter, contact_id)}. The lookup MUST qualify on both
     * columns per the V5 {@code UNIQUE (adapter, contact_id)} constraint
     * (decision D46: SimpleX + Signal side-by-side). An absent row means
     * the contact has no users row; the caller maps empty to no_posts_yet.
     */
    private Optional<UUID> lookupUserId(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            // Do NOT interpolate the raw contact_id into the wrapping
            // message; the SQLException stack already carries the SQL
            // diagnostic. The cause preserves the exception chain.
            throw new IllegalStateException(
                    "SummaryCommandHandler.lookupUserId failed", e);
        }
    }

    /**
     * Resolve the active (not soft-removed) {@code groups.id} for the
     * inbound {@code (adapter, upstream_group_id)}, or empty when no such
     * row exists (the vanished-group race).
     */
    private Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_GROUP_ID_BY_ADAPTER_AND_UPSTREAM_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SummaryCommandHandler.lookupGroupId failed", e);
        }
    }

    /**
     * Read the scope's configured language from
     * {@code scope_preferences.language}. Defaults to {@code "en"}
     * when no row exists (scope never invoked {@code /lang}).
     */
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
            throw new IllegalStateException(
                    "SummaryCommandHandler.readScopeLanguage failed", e);
        }
    }

    private String format(String bundleKey, Object... args) {
        String template = bundleLoader.get(bundleKey, inboundContext.effectiveLanguage());
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    /**
     * The D35 "stopped" terminal string in the requester's effective
     * language — rendered through the progress notifier when /stop cancelled
     * the in-flight summary, in place of the generic failure terminal.
     */
    private String stoppedTerminal() {
        return bundleLoader.get(BundleKeys.PROGRESS_STOPPED, inboundContext.effectiveLanguage());
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    /**
     * SHA-256 of the normalized command text, used as the arg_hash in
     * the summary_anchor row so /retry can detect whether the same
     * args were used.
     */
    static String computeArgHash(String rawText) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawText.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is a mandatory JDK algorithm", e);
        }
    }

    /**
     * Serialize the cluster mapping as a JSON array of objects, each
     * with {@code topicId} and {@code postUids}. Minimal JSON without
     * external dependencies.
     */
    static String serializeClusterMap(List<Cluster> clusters) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < clusters.size(); i++) {
            if (i > 0) sb.append(",");
            Cluster c = clusters.get(i);
            sb.append("{\"topicId\":\"").append(JsonEscaper.escape(c.topicId())).append("\",\"postUids\":[");
            List<Post> posts = c.posts();
            for (int j = 0; j < posts.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(JsonEscaper.escape(posts.get(j).uid())).append("\"");
            }
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }
}
