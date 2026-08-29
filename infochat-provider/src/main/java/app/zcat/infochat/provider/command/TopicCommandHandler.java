package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.digest.DigestRenderer;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.digest.SummaryCacheRepository;
import app.zcat.infochat.provider.digest.TopicRanking;
import app.zcat.infochat.provider.digest.TopicRanking.RankedTopic;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.StageProgressNotifier;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements {@code /topic} per docs/spec/commands.md §Content: bare and
 * {@code --full} are the deterministic ranked listing (no LLM call,
 * token, or slot); {@code <tag>} mirrors /summary's render machinery and
 * guards. No summary anchor — /retry never replays a topic run.
 */
@ApplicationScoped
public class TopicCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TopicCommandHandler.class);

    private static final String SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_BY_ADAPTER_AND_UPSTREAM_ID =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    private static final String SELECT_SCOPE_LANGUAGE =
            "SELECT language FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";

    /** Max fuzzy-suggestion entries in the zero-match reply (the /summary idiom). */
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
    DigestRenderer digestRenderer;

    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    @Inject
    InboundContext inboundContext;

    @Inject
    SummaryCacheRepository summaryCacheRepository;

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    LlmRateCap llmRateCap;

    @Inject
    StageProgressNotifier progressNotifier;

    /** §9 injected Clock — CDI overrides the systemUTC() default at runtime. */
    @Inject
    Clock clock = Clock.systemUTC();

    /** Bare-listing cap ({@code infochat.topic.listing-size}); floor 5 per design 03. */
    @ConfigProperty(name = "infochat.topic.listing-size", defaultValue = "7")
    int listingSize = 7;

    /** {@code --full} hard cap ({@code infochat.topic.full-cap}). */
    @ConfigProperty(name = "infochat.topic.full-cap", defaultValue = "50")
    int fullListingCap = 50;

    /** The drill-down's over-cap gate bound (the /summary bound, mirrored). */
    @ConfigProperty(name = "infochat.summary.summarizer-post-cap", defaultValue = "50")
    int summarizerPostCap = 50;

    @Override
    public String name() {
        return "topic";
    }

    @Override
    public @Nullable OutboundMessage handle(ScopeRef scope, String rawText) {
        TopicArgs.ParseResult parsed = TopicArgs.parse(rawText);
        if (parsed instanceof TopicArgs.Failure f) {
            return reply(scope, format(f.bundleKey(), f.interpolationArgs().toArray()));
        }
        TopicArgs args = ((TopicArgs.Success) parsed).args();

        String adapterName = inboundContext.adapterName();
        Optional<UUID> actorIdOpt = lookupUserId(adapterName, inboundContext.senderContactId());
        if (actorIdOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_TOPIC_NONE,
                    inboundContext.effectiveLanguage()));
        }
        UUID actorId = actorIdOpt.get();

        String scopeKind = EligiblePostQuery.scopeKindOf(scope);
        Optional<UUID> scopeId = resolveScopeId(scope, actorId, adapterName);
        if (scopeId.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_TOPIC_NONE,
                    inboundContext.effectiveLanguage()));
        }

        Duration window = args.window().orElse(defaultWindow(scopeKind, scopeId.get()));

        return args.tag().isEmpty()
                ? handleListing(scope, args, scopeKind, scopeId.get(), window)
                : handleDrillDown(scope, args, actorId, scopeKind, scopeId.get(), window);
    }

    // ----- bare / --full listing (deterministic) ------------------------------

    /** Ranked listing; {@code --full} floors at 2-story topics and caps at
     * {@link #fullListingCap}; bare caps at {@link #listingSize}. */
    private OutboundMessage handleListing(ScopeRef scope, TopicArgs args,
                                          String scopeKind, UUID scopeId, Duration window) {
        Result result = eligiblePostQuery.fetch(scopeKind, scopeId, Optional.empty(), window);
        List<RankedTopic> ranked = TopicRanking.rank(result.posts());
        if (ranked.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_TOPIC_NONE,
                    inboundContext.effectiveLanguage()));
        }
        List<RankedTopic> candidates = args.full()
                ? ranked.stream().filter(t -> t.postCount() >= 2).toList()
                : ranked;
        int cap = args.full() ? fullListingCap : listingSize;
        int shown = Math.min(candidates.size(), cap);

        StringBuilder line = new StringBuilder(bundleLoader.get(
                BundleKeys.REPLY_TOPIC_LISTING_HEADER, inboundContext.effectiveLanguage()));
        for (int i = 0; i < shown; i++) {
            RankedTopic topic = candidates.get(i);
            line.append(i == 0 ? " " : ", ")
                .append(topic.name()).append(" (").append(topic.postCount()).append(')');
        }
        if (candidates.size() > shown) {
            line.append(' ').append(format(BundleKeys.REPLY_TOPIC_MORE,
                    candidates.size() - shown));
        }
        if (args.full()) {
            long singleStory = ranked.stream().filter(t -> t.postCount() == 1).count();
            if (singleStory > 0) {
                line.append('\n').append(format(BundleKeys.REPLY_TOPIC_MORE_SINGLE_POST,
                        singleStory));
            }
        }
        return reply(scope, line.toString());
    }

    // ----- <tag> drill-down (the /summary render machinery) -------------------

    /** Drill-down: zero matches get the tolerant fuzzy reply (never a
     * vocabulary error); populated sets mirror /summary minus the anchor. */
    private @Nullable OutboundMessage handleDrillDown(ScopeRef scope, TopicArgs args,
                                                      UUID actorId, String scopeKind,
                                                      UUID scopeId, Duration window) {
        Result result = eligiblePostQuery.fetchByTopicPrefix(
                scopeKind, scopeId, args.tag().get(), window);
        if (result.posts().isEmpty()) {
            List<String> windowTags = TopicRanking.rank(eligiblePostQuery
                            .fetch(scopeKind, scopeId, Optional.empty(), window).posts())
                    .stream().map(RankedTopic::name).toList();
            List<String> suggestions = EligiblePostQuery.fuzzySuggest(
                    args.tag().get(), windowTags, FUZZY_SUGGESTION_MAX);
            StringBuilder text = new StringBuilder(format(
                    BundleKeys.REPLY_TOPIC_NO_MATCH, args.tag().get()));
            if (!suggestions.isEmpty()) {
                text.append(format(BundleKeys.REPLY_TOPIC_SUGGESTIONS_FOOTER,
                        String.join(", ", suggestions)));
            }
            return reply(scope, text.toString());
        }

        // The /summary over-cap gate, mirrored: past the summarizer cap
        // the degraded form renders per-section — deterministic, no token,
        // no slot, no anchor.
        if (result.posts().size() > summarizerPostCap) {
            String scopeLanguage = readScopeLanguage(scopeKind, scopeId);
            List<Cluster> clusters = clusterTraversal.cluster(result.posts());
            List<ClusterProse> degradedProse = clusters.stream()
                    .map(cluster -> new ClusterProse(cluster,
                            SummaryProseGenerator.degradedProseFor(
                                    cluster, llmOutputSanitizer, scopeLanguage), true))
                    .toList();
            StringBuilder prefixes = new StringBuilder();
            if (result.excludedCount() > 0) {
                prefixes.append(format(BundleKeys.REPLY_SUMMARY_CAP_EXCESS_NOTICE,
                        result.posts().size(), result.totalBeforeCap(),
                        result.profileLabel(), result.excludedCount()));
                prefixes.append("\n\n");
            }
            prefixes.append(format(BundleKeys.REPLY_SUMMARY_WINDOW_TOO_LARGE_NOTICE,
                    result.totalBeforeCap(), summarizerPostCap));
            prefixes.append("\n\n");
            List<RenderedSection> sections = args.full()
                    ? digestRenderer.renderSummarySections(degradedProse, scopeLanguage,
                            Integer.MAX_VALUE, Map.of())
                    : digestRenderer.renderSummarySections(degradedProse, scopeLanguage);
            deliverPerSection(scope, prefixes.toString(), sections, false);
            return null;
        }

        InFlightTracker.CancellationHandle slot =
                inFlightTracker.tryAcquire(actorId, scopeKind, scopeId);
        if (slot == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT,
                    inboundContext.effectiveLanguage()));
        }
        try {
            if (slot.isCancelled()) {
                progressNotifier.complete(scope, stoppedTerminal());
                return null;
            }
            if (!llmRateCap.tryAcquire(actorId)) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP,
                        inboundContext.effectiveLanguage()));
            }

            boolean delivered = false;
            try {
                progressNotifier.publish(scope, ProgressStage.STARTED);
                progressNotifier.publish(scope, ProgressStage.RETRIEVING);
                String scopeLanguage = readScopeLanguage(scopeKind, scopeId);

                List<Cluster> clusters = clusterTraversal.cluster(result.posts());
                // Identity keying, the positional /summary idiom: the
                // requested topic's own sections stay their own, never
                // folded through the followed-node map.
                progressNotifier.publish(scope, ProgressStage.GENERATING);
                List<ClusterProse> prose = summaryProseGenerator.generate(clusters, "en");

                if (!scopeLanguage.equalsIgnoreCase("en")) {
                    progressNotifier.publish(scope, ProgressStage.TRANSLATING);
                }

                int clustersTotal = prose.size();
                int degradedClusters = (int) prose.stream().filter(ClusterProse::degraded).count();
                StringBuilder prefixes = new StringBuilder();
                if (result.excludedCount() > 0) {
                    prefixes.append(format(BundleKeys.REPLY_SUMMARY_CAP_EXCESS_NOTICE,
                            result.posts().size(), result.totalBeforeCap(),
                            result.profileLabel(), result.excludedCount()));
                    prefixes.append("\n\n");
                }
                if (degradedClusters > 0) {
                    if (degradedClusters == clustersTotal) {
                        prefixes.append(bundleLoader.get(
                                BundleKeys.REPLY_SUMMARY_DEGRADED_NOTICE,
                                inboundContext.effectiveLanguage()));
                    } else {
                        prefixes.append(format(
                                BundleKeys.REPLY_SUMMARY_PARTIAL_DEGRADED_NOTICE,
                                degradedClusters, clustersTotal));
                    }
                    prefixes.append("\n\n");
                }

                progressNotifier.publish(scope, ProgressStage.FINALIZING);
                if (slot.isCancelled()) {
                    progressNotifier.complete(scope, stoppedTerminal());
                } else if (args.full()) {
                    deliverPerSection(scope, prefixes.toString(),
                            digestRenderer.renderSummarySections(prose, scopeLanguage,
                                    Integer.MAX_VALUE, Map.of()),
                            true);
                } else {
                    deliverPerSection(scope, prefixes.toString(),
                            digestRenderer.renderSummarySections(prose, scopeLanguage),
                            true);
                }
                delivered = true;
            } catch (RuntimeException e) {
                if (slot.isCancelled()) {
                    progressNotifier.complete(scope, stoppedTerminal());
                } else {
                    SafeLog.error(log, "TopicCommandHandler drill-down failed", e);
                    progressNotifier.fail(scope);
                }
                delivered = true;
            } finally {
                if (!delivered) {
                    progressNotifier.fail(scope);
                }
            }
            return null;
        } finally {
            inFlightTracker.release(actorId, scopeKind, scopeId, slot);
            slot.releaseWorker();
        }
    }

    // ----- shared helpers ------------------------------------------------------

    /** Group default = the period since the previous digest boundary
     * (DigestWorker's collect rule); DM or never-digested group = 24h. */
    private Duration defaultWindow(String scopeKind, UUID scopeId) {
        if ("group".equals(scopeKind)) {
            try {
                Optional<Instant> boundary =
                        summaryCacheRepository.findPreviousBoundary(scopeId, clock.instant());
                if (boundary.isPresent()) {
                    Duration since = Duration.between(boundary.get(), clock.instant());
                    if (!since.isNegative() && !since.isZero()) {
                        return since;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "TopicCommandHandler.defaultWindow failed", e);
            }
        }
        return SummaryArgs.DEFAULT_WINDOW;
    }

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

    private Optional<UUID> resolveScopeId(ScopeRef scope, UUID actorId, String adapter) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> Optional.of(actorId);
            case ScopeRef.Group group -> lookupGroupId(adapter, group.adapterGroupId());
        };
    }

    private Optional<UUID> lookupUserId(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of((UUID) rs.getObject("id")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("TopicCommandHandler.lookupUserId failed", e);
        }
    }

    private Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_GROUP_ID_BY_ADAPTER_AND_UPSTREAM_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of((UUID) rs.getObject("id")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("TopicCommandHandler.lookupGroupId failed", e);
        }
    }

    private String readScopeLanguage(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCOPE_LANGUAGE)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("language") : "en";
            }
        } catch (SQLException e) {
            throw new IllegalStateException("TopicCommandHandler.readScopeLanguage failed", e);
        }
    }

    private String format(String bundleKey, Object... args) {
        String template = bundleLoader.get(bundleKey, inboundContext.effectiveLanguage());
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    private String stoppedTerminal() {
        return bundleLoader.get(BundleKeys.PROGRESS_STOPPED, inboundContext.effectiveLanguage());
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
