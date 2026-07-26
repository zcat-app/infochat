package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.messaging.OutboundMessage;
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
import app.zcat.infochat.provider.digest.DigestRenderer;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.StageProgressNotifier;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 *   <li>Compose the leading prefixes (optional top-3 / cap-excess /
 *       degraded notices) plus the rendered body and deliver: the default
 *       (categorized) form goes out as ONE outbound message per category
 *       section (M1-695) — the placeholder finalized with the first
 *       section, the rest as fresh sends — while {@code --full} keeps the
 *       single flat body.</li>
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

    /**
     * Max posts allowed into LLM prose generation per /summary
     * invocation (M1-623). Distinct from {@code
     * infochat.summary.cluster-cap}, which bounds the SQL retrieval
     * set: a window can be under the retrieval cap yet still too large
     * for one summarizer pass — the per-cluster prompts (or their
     * count) blow the summarizer timeout and the run degrades with a
     * misleading "unreachable" notice. Over THIS cap the handler skips
     * the summarizer entirely and degrades by explicit decision.
     */
    @ConfigProperty(name = "infochat.summary.summarizer-post-cap", defaultValue = "50")
    int summarizerPostCap;

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

    /**
     * Renders the DEFAULT (categorized) form via
     * {@link DigestRenderer#renderSummarySections} — the no-LLM entry point,
     * so it is equally usable on the over-cap branch below. {@code --full}
     * bypasses it for {@link ClusterBlockRenderer}'s flat blocks.
     */
    @Inject
    DigestRenderer digestRenderer;

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

    /**
     * The concrete notifier, not the {@code ProgressNotifier} SPI: the
     * default-form per-section delivery (M1-695) needs
     * {@link StageProgressNotifier#deliverFresh}, which — like
     * {@code completeDelivered} — is a public non-SPI terminal. Same
     * concrete-injection precedent as {@code InboundRouter}.
     */
    @Inject
    StageProgressNotifier progressNotifier;

    @Override
    public String name() {
        return "summary";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a non-null {@link OutboundMessage} for every guard /
     * error branch (parse failure, no-posts, unknown tag, in-flight,
     * rate-cap) and for the {@code --full} over-cap reply — the router
     * sends those. For the terminal summary path (and the default-form
     * over-cap path, M1-695) the handler owns its own message lifecycle
     * through {@link StageProgressNotifier} (placeholder &rarr; coalesced
     * {@code update} &rarr; {@code complete}/{@code fail}, plus
     * {@code deliverFresh} for the follow-on section messages) and
     * returns {@code null}, so the router performs no send.</p>
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
            // Distinguish an EMPTY WORLD (every bootstrap source excluded and
            // nothing subscribed — M1-621, commands.md §Content) from a
            // populated-world-but-empty window: only the former gets the
            // actionable steer; anything else is the window-blaming
            // no_posts_yet. Under D59's implicit bootstrap a fresh scope's
            // world is non-empty, so the steer no longer fires for new
            // users. The world count runs ONLY on this empty branch (a
            // resolved scope id in hand), so a /summary that returns posts
            // pays no extra query.
            String emptyKey = eligiblePostQuery.countWorldSources(scopeKind, scopeId.get()) == 0
                    ? BundleKeys.REPLY_SUMMARY_NO_SUBSCRIPTIONS
                    : BundleKeys.REPLY_SUMMARY_NO_POSTS_YET;
            return reply(scope, bundleLoader.get(emptyKey, inboundContext.effectiveLanguage()));
        }

        // Explicit size decision (M1-623): a window over the summarizer
        // post cap never reaches the LLM — the doomed call would only
        // burn the timeout and mislabel the outcome "unreachable".
        // Render the same degraded form the LLM-failure path uses
        // (headlines + bare URLs + UIDs, in the caller's chosen render
        // form) plus an honest too-large notice steering the user to
        // narrow with -w. Like the other guard branches this path is
        // deterministic and makes no LLM call, so it consumes no
        // rate-cap token and holds no in-flight slot. No summary anchor
        // is written either: a written anchor would let /retry replay
        // the over-cap set straight into the doomed per-cluster calls
        // this gate exists to prevent.
        if (result.posts().size() > summarizerPostCap) {
            String scopeLanguage = readScopeLanguage(scopeKind, scopeId.get());
            if (args.full()) {
                return reply(scope, composeWindowTooLargeReply(result, scopeLanguage));
            }
            // M1-695: the default (categorized) over-cap form is delivered
            // per-section like the terminal path. This branch runs before
            // the in-flight slot and any publish, so there is no
            // placeholder to finalize — every section goes out as a fresh
            // send and the handler returns null (self-delivered, so the
            // router performs no send).
            List<Cluster> overCapClusters = clusterTraversal.cluster(result.posts());
            // Degraded prose is composed here, not by the summarizer —
            // that is the whole point of this branch — so the categorized
            // render below reaches no LLM either (renderSummarySections
            // takes prose, it does not generate it).
            List<ClusterProse> overCapProse = overCapClusters.stream()
                    .map(cluster -> new ClusterProse(
                            cluster, SummaryProseGenerator.degradedProseFor(cluster, llmOutputSanitizer), true))
                    .toList();
            StringBuilder overCapPrefixes = new StringBuilder();
            appendWindowPrefixes(overCapPrefixes, result);
            overCapPrefixes.append(format(BundleKeys.REPLY_SUMMARY_WINDOW_TOO_LARGE_NOTICE,
                    result.totalBeforeCap(), summarizerPostCap));
            overCapPrefixes.append("\n\n");
            deliverPerSection(scope, overCapPrefixes.toString(),
                    digestRenderer.renderSummarySections(overCapProse, scopeLanguage),
                    /* finalizePlaceholder */ false);
            return null;
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
            // Adopted a turn /stop had already cancelled — marked while
            // QUEUED, between the stage preamble and this acquire (M1-638):
            // render the D35 stopped terminal onto the acknowledgement
            // placeholder BEFORE the LLM bucket draw below, so a cancelled
            // turn burns no token (the check-order doctrine above).
            if (slot.isCancelled()) {
                progressNotifier.complete(scope, stoppedTerminal());
                return null;
            }
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

                StringBuilder prefixes = new StringBuilder();
                appendWindowPrefixes(prefixes, result);

                boolean anyDegraded = prose.stream().anyMatch(ClusterProse::degraded);
                if (anyDegraded) {
                    prefixes.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_DEGRADED_NOTICE, inboundContext.effectiveLanguage()));
                    prefixes.append("\n\n");
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
                } else if (args.full()) {
                    progressNotifier.complete(scope,
                            (prefixes.toString() + renderFlatBody(prose, scopeLanguage)).stripTrailing());
                } else {
                    // M1-695: the default (categorized) form is delivered as
                    // one outbound message per category section — the
                    // placeholder is finalized with the first section (the
                    // leading prefixes ride on it), the rest go out as
                    // fresh sends in section order.
                    deliverPerSection(scope, prefixes.toString(),
                            digestRenderer.renderSummarySections(prose, scopeLanguage),
                            /* finalizePlaceholder */ true);
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
            // Gate close + interrupt-status clear, LAST in the section —
            // see CancellationHandle.releaseWorker (M1-634).
            slot.releaseWorker();
        }
    }

    /**
     * Compose the window-too-large reply (M1-623) for the FLAT
     * ({@code --full}) form, which stays a single router-sent message:
     * the same prefix ordering as the terminal path (top-3 restriction,
     * cap-excess), then the too-large notice in the slot the degraded
     * notice occupies on the LLM-failure path, then one degraded block
     * per cluster. The default (categorized) over-cap form is NOT composed
     * here — it is delivered per-section at the call site (M1-695).
     * Clustering still runs — it is deterministic DB+memory work — so the
     * degraded blocks are byte-identical to what the LLM-failure path
     * would have rendered for the same posts.
     */
    private String composeWindowTooLargeReply(Result result, String scopeLanguage) {
        List<Cluster> clusters = clusterTraversal.cluster(result.posts());
        StringBuilder out = new StringBuilder();
        appendWindowPrefixes(out, result);
        out.append(format(BundleKeys.REPLY_SUMMARY_WINDOW_TOO_LARGE_NOTICE,
                result.totalBeforeCap(), summarizerPostCap));
        out.append("\n\n");
        // Degraded prose is composed here, not by the summarizer, which is
        // the whole point of this branch.
        List<ClusterProse> degradedProse = clusters.stream()
                .map(cluster -> new ClusterProse(
                        cluster, SummaryProseGenerator.degradedProseFor(cluster, llmOutputSanitizer), true))
                .toList();
        out.append(renderFlatBody(degradedProse, scopeLanguage));
        return out.toString().stripTrailing();
    }

    /**
     * The leading top-3-restriction and cap-excess prefixes, in the order
     * both the terminal path and the over-cap branch emit them.
     */
    private void appendWindowPrefixes(StringBuilder out, Result result) {
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
    }

    /**
     * Per-section delivery (M1-695): one outbound message per category
     * section, sequentially in section order (D63's digest shape applied
     * to the {@code /summary} scope). {@code prefixes} (the leading
     * top-3 / cap-excess / degraded / too-large notices) ride on the
     * FIRST section's message, so the message count equals the section
     * count. On the terminal path the first message finalizes the
     * placeholder ({@code complete}); on the over-cap path no placeholder
     * was ever published, so every message is a fresh send.
     */
    private void deliverPerSection(ScopeRef scope, String prefixes,
                                   List<RenderedSection> sections, boolean finalizePlaceholder) {
        String first = (prefixes + sections.get(0).text()).stripTrailing();
        if (finalizePlaceholder) {
            progressNotifier.complete(scope, first);
        } else {
            progressNotifier.deliverFresh(scope, first);
        }
        for (int i = 1; i < sections.size(); i++) {
            progressNotifier.deliverFresh(scope, sections.get(i).text().stripTrailing());
        }
    }

    /**
     * Render the flat ({@code --full}) per-cluster body:
     * {@link ClusterBlockRenderer}'s seven-field block per cluster, which
     * is what {@code /retry} replays and what the {@code --full}-retargeted
     * tests assert on. Takes prose the CALLER already generated (or
     * composed degraded), so it makes no LLM call and the summarizer call
     * count is identical in both forms — the property
     * {@code TranslationPipelineIT}'s exact {@code mockLlm.callCount()}
     * assertions rest on.
     */
    private String renderFlatBody(List<ClusterProse> prose, String scopeLanguage) {
        StringBuilder out = new StringBuilder();
        ClusterBlockRenderer clusterBlockRenderer =
                new ClusterBlockRenderer(llmOutputSanitizer, translationPipeline, bundleLoader);
        for (ClusterProse cp : prose) {
            clusterBlockRenderer.appendClusterBlock(out, cp, scopeLanguage);
        }
        return out.toString();
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
