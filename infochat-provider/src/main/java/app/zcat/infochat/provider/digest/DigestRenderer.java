package app.zcat.infochat.provider.digest;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import app.zcat.infochat.llm.LlmCallBudget;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestCategorizer.CategorySection;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.render.DisplayHeadline;
import app.zcat.infochat.provider.summary.ClusterProminence;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

/**
 * Renders the group digest under deterministic D62 category headers with a
 * cap on the NUMBER of sections (M1-721) and one closing affordance line.
 * The category body is mode-dependent ({@code groups.digest_mode}, V67,
 * M1-732): {@code brief} renders a true-count header + roll-up per
 * category, {@code normal} adds bare headlines, and {@code full} keeps the
 * per-cluster LLM prose (sanitized and translated) with the item cap
 * lifted. The {@code /summary} render forms below are mode-independent.
 *
 * <p>A non-{@code brief} digest with at least {@code infochat.digest.lead-minimum}
 * clusters opens with a LEAD section (M1-725): the top
 * {@code infochat.digest.lead-size} clusters by {@link ClusterProminence}
 * order across the WHOLE digest, rendered with full per-cluster prose —
 * the render {@code normal} categories no longer do. Lead clusters are
 * removed from their home sections (section counts drop with them; a
 * category gutted below the D62 threshold folds into Other), so no cluster
 * renders twice. The lead is the FIRST returned section and never carries
 * the closing affordance, which stays on the LAST category section.
 */
@ApplicationScoped
public class DigestRenderer {

    @Inject
    ClusterTraversal clusterTraversal;

    @Inject
    DigestCategorizer digestCategorizer;

    // Field-initialized to a default instance so plain-JUnit constructions
    // score with the ticket-default weights; CDI overwrites at runtime —
    // the {@link #categoryRollupGenerator} pattern below.
    @Inject
    ClusterProminence clusterProminence = new ClusterProminence();

    @Inject
    SummaryProseGenerator summaryProseGenerator;

    // Field-initialized to a default instance so a plain-JUnit construction
    // that reaches renderShortBody() does not NPE on a null field; CDI
    // overwrites this with the deployment-wide bean at runtime — the same
    // pattern DigestWorker.clock follows (M1-444 reference).
    @Inject
    CategoryRollupGenerator categoryRollupGenerator = new CategoryRollupGenerator();

    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    @Inject
    TranslationPipeline translationPipeline;

    /**
     * Consulted ONLY to tell a cache hit from a miss before spending
     * {@link #translationMaxPerRender} (M1-756) — the pipeline owns every
     * read and write of the entry itself. A hit makes no provider call, so
     * charging it against a generative budget would shrink the translated
     * portion of a digest the more often that digest had been rendered.
     * The /saved leg draws the same distinction the same way (M1-755).
     */
    @Inject
    TranslationCache translationCache;

    @Inject
    BundleLoader bundleLoader;

    // M1-767: the system-wide LLM call budget — the scheduled digest
    // route's only rate-limiting control (shared with /retry --digest,
    // whose FALLBACK re-run binds the same pool behind the pre-charge
    // refusal in RetryCommandHandler; the replay leg is never gated in
    // steady state — a stale probe refuses it free, see
    // DigestRetryService.retryLeg). DigestWorker gates ADMISSION against
    // it before the render starts; renderSections binds it as the
    // render-scoped sink so each call the render issues draws itself and
    // is refused once the window is exhausted (M1-769). No draw is
    // written from this class any more — a call site can only ever
    // estimate what it is about to spend, which is what M1-767's
    // over- and under-count legs were.
    // Field-initialized so plain-JUnit constructions draw into an inert
    // budget; CDI overwrites at runtime — the {@link
    // #categoryRollupGenerator} pattern.
    @Inject
    SystemLlmBudget systemLlmBudget = new SystemLlmBudget();

    // Field-initialized so plain-JUnit constructions get identity keying
    // (empty map); CDI overwrites with the real bean — the systemLlmBudget
    // pattern. Null DataSource in the default returns Map.of().
    @Inject
    app.zcat.infochat.provider.summary.TagTreeExpansion tagTreeExpansion =
            new app.zcat.infochat.provider.summary.TagTreeExpansion();

    /** Max clusters rendered per category section in FULL mode is unbounded; in the pre-M1-732 render the overflow became a localized "+N more" line. Still the cap /summary's render forms use. */
    @ConfigProperty(name = "infochat.digest.category-item-cap", defaultValue = "12")
    int categoryItemCap;

    /**
     * Max bare headlines per category section in NORMAL mode (M1-732).
     * Field-initialized so a plain-JUnit construction that forgets to set
     * it renders the documented default rather than zero headlines — the
     * {@link #categoryRollupGenerator} field-initialization pattern above.
     */
    @ConfigProperty(name = "infochat.digest.category-headline-count", defaultValue = "5")
    int categoryHeadlineCount = 5;

    /**
     * Generative display-hit translator calls allowed per {@link
     * #renderSections} invocation (M1-756). Without this bound one render
     * would translate up to {@code max-categories} ×
     * {@link #categoryHeadlineCount} = 8 × 5 = 40 headlines. Bounded, a
     * group's HEADLINE worst case is 5 per render and 10 per day (two
     * slots) — the same per-invocation budget {@code /saved} spends
     * ({@code infochat.save.translation-max-per-page}) at a strictly lower
     * invocation rate. It bounds THIS leg only: {@link
     * #appendClusterProse} and {@link CategoryRollupGenerator} reach the
     * same {@code ModelTask.TRANSLATOR} on the same render with no
     * per-render budget of their own, so this key is not a bound on the
     * render's translator cost as a whole.
     *
     * <p>TWO ROUTES reach this render, and they are metered differently
     * [redteam 2026-08-04, low/DOS]. The SCHEDULED route
     * ({@code DigestScheduler} → {@code DigestWorker.executeSlot}) has no
     * user in the loop, so no per-user or per-group bucket is drawn and
     * this budget is the only rate-limiting control that exists on it. The
     * USER-INITIATED route is {@code /retry --digest}, which reaches the
     * very same render via {@code DigestRetryService.fallbackRerun} →
     * {@code DigestWorker.execute}; there {@code RetryCommandHandler} has
     * already drawn the per-user LLM token AND the D47 per-group
     * sub-bucket (refunding the former if the group cap rejects), and
     * {@code DigestRetryService} adds a per-group cooldown. So this budget
     * is the sole meter on one route and an inner bound on the other; it
     * is never a substitute for caps that "do not apply to the digest".
     *
     * <p>Rows past the budget render untranslated, and therefore
     * BRACKETED: the primary line is then in a language the reader did not
     * ask for, and D29 (c)'s invariant — an unbracketed line is always in
     * the reader's language — has to hold on the degraded path too, or a
     * budget exhaustion silently produces exactly the bare foreign line
     * the bracket exists to distinguish. The bracket is punctuation the
     * renderer adds; it costs no provider call. Field-initialized to the
     * documented default — the {@link #categoryHeadlineCount} pattern.
     */
    @ConfigProperty(name = "infochat.digest.translation-max-per-render", defaultValue = "5")
    int translationMaxPerRender = 5;

    /**
     * Clusters promoted to the lead section (M1-725). Field-initialized to
     * the documented default — the {@link #categoryHeadlineCount} pattern.
     */
    @ConfigProperty(name = "infochat.digest.lead-size", defaultValue = "3")
    int leadSize = 3;

    /**
     * Minimum digest size (in clusters) for a lead to render at all
     * (M1-725): below it a lead header would cap the WHOLE digest, not a
     * selection — a header over 3 of 4 stories says nothing and costs an
     * extra message under D63. Field-initialized to the documented
     * default — the {@link #categoryHeadlineCount} pattern.
     */
    @ConfigProperty(name = "infochat.digest.lead-minimum", defaultValue = "6")
    int leadMinimum = 6;

    /**
     * The lead section's {@link RenderedSection#tag()} — the value
     * {@code DigestSectionRepository.slugOf} maps to its
     * {@code (group_id, window_start, category_slug)} key, the delivery
     * correlationId, and the delivery-record slug. UPPERCASE on purpose:
     * the V6 {@code tag.name} CHECK ({@code ^[a-z0-9][a-z0-9-]{0,47}$})
     * makes an uppercase string un-storable as a controlled-vocabulary
     * tag, so the lead's slug can NEVER collide with a real category the
     * way a lowercase {@code "lead"} one day could, and it stays distinct
     * from the Other bucket's {@code "other"} mapping of a null tag.
     * Package-visible for {@code DigestDelivery}, which splits the lead
     * out of the batched modes on this marker.
     */
    static final String LEAD_TAG = "LEAD";

    /**
     * The display-hit cache-partition kind for every digest render
     * (M1-756). Constant, not a parameter: the periodic digest is a group
     * broadcast and has no other scope to run under, so admitting a second
     * value here would only create a way to mis-partition the cache.
     */
    private static final String SCOPE_KIND = "group";

    /**
     * Per-group digest verbosity ({@code groups.digest_mode}, V67, M1-732).
     * {@code brief} renders a true-count header + roll-up per category;
     * {@code normal} adds up to {@code infochat.digest.category-headline-count}
     * bare headlines; {@code full} keeps the pre-M1-732 per-cluster prose
     * with the item cap lifted. The storage strings are the lowercase enum
     * names; parsing lives at the SQL-deserialization boundary
     * ({@code DigestWorker.readGroupMetadata}), where a NULL or
     * unrecognized value falls back to {@code NORMAL} with one WARN.
     */
    public enum DigestMode {
        BRIEF, NORMAL, FULL
    }

    /**
     * Render the digest as an ordered list of per-category sections — the
     * EXACT delivery bytes (M1-652 fork closed, arm (b), 2026-07-20). The
     * closing affordance is folded into the LAST section's text inside this
     * pass, so the caller's {@code "\n\n"} join over the section list
     * reproduces the pre-M1-652 single-message bytes and M1-652 can persist
     * the list at render time and replay a filtered subset on
     * {@code /retry --digest} without re-deriving anything.
     *
     * <p>Section order is {@link DigestCategorizer#categorize}'s D62 order
     * (assigned-cluster count descending, alphabetical ties, Other last)
     * inherited as-is — never sorted here. The only filtering is
     * {@link DigestCategorizer#capSections}, which drops whole sections
     * off the tail (M1-721). WITHIN each section the cluster order is the
     * M1-724 prominence order (urgent gate → weighted percentile score →
     * the existing recency key), computed by {@link ClusterProminence}
     * over the pre-cap section list; the reorder never moves a cluster
     * between sections, so a high-scoring cluster cannot starve a small
     * category — every surviving section still renders its own head.
     *
     * <p>The {@code mode} (the group's {@code digest_mode}, V67) selects
     * the category body. {@code brief} and {@code normal} render the hybrid
     * body — a header carrying the section's TRUE cluster count, ONE
     * {@link CategoryRollupGenerator} roll-up per surviving section, and
     * (normal only) up to {@link #categoryHeadlineCount} bare headlines —
     * and make ZERO {@link SummaryProseGenerator} calls. {@code full}
     * keeps the pre-M1-732 per-cluster prose with the item cap lifted to a
     * LOCAL {@code Integer.MAX_VALUE} effective cap ({@code categoryItemCap}
     * is shared {@code @ApplicationScoped} state — mutating it per render
     * would race concurrent slot renders on the virtual-thread executor;
     * the local-cap precedent is {@code renderSummarySections}' M1-700
     * {@code effectiveCap}), so the old {@code +N more} overflow line can
     * never appear and its bundle keys are deleted.
     *
     * <p>M1-725 lead: a non-{@code brief} digest with at least
     * {@link #leadMinimum} clusters returns a LEAD section FIRST — the top
     * {@link #leadSize} clusters by the prominence total order across the
     * whole digest, rendered with full per-cluster prose in
     * {@code normal} AND {@code full}. Promoted clusters are removed from
     * their home sections before the count headers, the fold-to-Other pass
     * and the section cap run, so no cluster renders twice and the lead
     * consumes no section slot. The lead's tag is {@link #LEAD_TAG} (never
     * a controlled-vocabulary tag, never the Other bucket's null), it
     * carries no footer, and the closing affordance stays on the LAST
     * category section — {@code DigestDelivery} splits the lead into its
     * own first message on this marker in the batched modes.
     *
     * <p>{@code groupId} is the broadcast's scope identity, required
     * (M1-756) because the NORMAL-mode headlines run through the
     * display-hit translation leg, whose cache is partitioned per
     * {@code (scopeKind, scopeId, scopeLanguage)}. The renderer is
     * {@code @ApplicationScoped} and therefore holds no per-render scope of
     * its own; the caller's {@code slot.groupId()} is the only source. The
     * scope KIND is the literal {@code "group"} — the periodic digest
     * broadcasts to groups and nothing else.
     *
     * <p>This method is also the sole binder of the {@link
     * SystemLlmBudget} render sink (M1-769): every LLM call issued
     * beneath it draws, and every call issued anywhere else in the
     * deployment does not. See {@link #renderSectionsUnderBudget}.
     */
    public List<RenderedSection> renderSections(List<EligiblePostQuery.Post> posts,
                                                String langCode,
                                                DigestMode mode,
                                                UUID groupId) {
        return LlmCallBudget.callWith(systemLlmBudget.forRender(groupId),
                () -> renderSectionsUnderBudget(posts, langCode, mode, groupId));
    }

    /**
     * The render itself, running under the bound {@link SystemLlmBudget}
     * sink (M1-769). The binding sits HERE, inside the method, and not
     * around {@code DigestWorker}'s {@code renderExecutor.submit(...)}:
     * a {@code ScopedValue} binding is not inherited across a plain
     * executor submit, so a binder placed at the submit site would be
     * absent on the render thread and every draw would silently vanish —
     * a deployment that looks metered and is not.
     *
     * <p>Binding at this altitude is also what makes the scoping
     * structural rather than a matter of discipline. Both routes that
     * genuinely spend digest budget reach this method — the scheduled
     * one ({@code DigestScheduler} → {@code DigestWorker.executeSlot})
     * and {@code /retry --digest}'s fallback re-run ({@code
     * DigestRetryService.fallbackRerun} → {@code DigestWorker.execute} →
     * {@code executeSlot}) — while {@link #renderSummarySections} and
     * {@link #renderShortBody}, the user-initiated forms metered by
     * {@code LlmRateCap} and the D47 per-group sub-bucket instead, do
     * not. The generative helpers below are shared with those routes;
     * the binding, not the helper, decides what draws.
     */
    private List<RenderedSection> renderSectionsUnderBudget(List<EligiblePostQuery.Post> posts,
                                                            String langCode,
                                                            DigestMode mode,
                                                            UUID groupId) {
        List<Cluster> clusters = clusterTraversal.cluster(posts);
        Map<String, String> sectionKeys = tagTreeExpansion.sectionKeyByLeaf("group", groupId);
        List<CategorySection> allSections = digestCategorizer.categorize(clusters, Set.of(), sectionKeys);
        // M1-724 prominence ranking: score every cluster (urgent gate →
        // weighted percentile score → input-order tiebreak) and re-sort
        // WITHIN each section. Section membership stays D62 tag arithmetic —
        // the reorder builds new CategorySection copies over the same
        // cluster sets, never moves a cluster between sections, and never
        // reorders the sections themselves. Scoring runs over the PRE-CAP
        // section list so percentile populations don't move with
        // max-categories, and needs categorize()'s FINAL assigned tags
        // (post-fold) for the corroboration denominators. The map is
        // identity-keyed because categorize() partitions the very instances
        // passed in (the renderSummarySections idiom below).
        Map<Cluster, String> assignedTagByCluster = new IdentityHashMap<>();
        for (CategorySection section : allSections) {
            for (Cluster cluster : section.clusters()) {
                assignedTagByCluster.put(cluster, section.tag());
            }
        }
        Map<Cluster, ClusterProminence.ScoredCluster> scoredByCluster = new IdentityHashMap<>();
        for (ClusterProminence.ScoredCluster scored
                : clusterProminence.score(clusters, assignedTagByCluster)) {
            scoredByCluster.put(scored.cluster(), scored);
        }
        // M1-725 lead selection: the top lead-size clusters by prominence
        // order across the WHOLE digest (personal clusters sort last via
        // the M1-727 bottom gate, so cat pictures lead only when nothing
        // else exists) — but only for a non-brief digest of at least
        // lead-minimum clusters; below that a lead header caps the WHOLE
        // digest rather than a selection and costs an extra message to say
        // nothing. The take is clamped to leave at least one cluster in
        // the body even under a misconfigured lead-minimum <= lead-size:
        // a lead-only digest would strand the closing affordance, which
        // rides the last category section. totalOrder is a TOTAL order
        // (input-index tiebreak), so the selection is deterministic
        // regardless of map iteration order.
        List<Cluster> leadClusters = List.of();
        if (mode != DigestMode.BRIEF && clusters.size() >= leadMinimum) {
            List<ClusterProminence.ScoredCluster> ordered =
                    new ArrayList<>(scoredByCluster.values());
            ordered.sort(ClusterProminence.totalOrder());
            int take = Math.min(leadSize, clusters.size() - 1);
            if (take > 0) {
                leadClusters = ordered.subList(0, take).stream()
                        .map(ClusterProminence.ScoredCluster::cluster)
                        .toList();
            }
        }
        if (!leadClusters.isEmpty()) {
            // Re-run categorization with the lead clusters excluded from
            // their home sections. The D62 assignment is untouched (the
            // full-set run above already produced the FINAL tags the
            // scoring consumed, and exclusion never re-tags); membership,
            // the true-count headers and the fold-to-Other pass are what
            // now reflect the removal. Identity set: categorize partitions
            // the very instances passed in.
            Set<Cluster> leadSet = Collections.newSetFromMap(new IdentityHashMap<>());
            leadSet.addAll(leadClusters);
            allSections = digestCategorizer.categorize(clusters, leadSet, sectionKeys);
        }
        List<CategorySection> rankedSections = new ArrayList<>(allSections.size());
        for (CategorySection section : allSections) {
            List<Cluster> reordered = new ArrayList<>(section.clusters());
            reordered.sort((a, b) -> ClusterProminence.totalOrder().compare(
                    scoredByCluster.get(a), scoredByCluster.get(b)));
            rankedSections.add(new CategorySection(section.tag(), reordered));
        }
        // The section cap is applied HERE and not inside categorize(), so it
        // reaches only the digest broadcast: renderSummarySections and
        // renderShortBody share the categorizer and must keep every section
        // (M1-721). Everything below runs over the capped list, which is what
        // keeps prose off the dropped sections.
        List<CategorySection> sections = digestCategorizer.capSections(rankedSections);
        int droppedCategories = allSections.size() - sections.size();

        // FULL: ONE generate() call covering every cluster of every
        // surviving section (the cap is lifted — there is no capped-out
        // cluster to spare), so per-call behavior (e.g. degraded fallback)
        // stays uniform across sections. brief/normal render no per-cluster
        // prose at all, so the generator is never invoked.
        List<ClusterProse> proseList;
        if (mode == DigestMode.FULL) {
            List<Cluster> shownClusters = new ArrayList<>();
            for (CategorySection section : sections) {
                shownClusters.addAll(section.clusters());
            }
            proseList = summaryProseGenerator.generate(shownClusters, langCode);
        } else {
            proseList = List.of();
        }
        // Lead prose (M1-725): ONE generate() call over exactly the
        // promoted clusters, in lead (prominence) order — the lead renders
        // full per-cluster prose in normal AND full, the render the
        // hybrid-body categories no longer do. An empty lead skips the
        // call entirely, so prose is paid for the promoted clusters and
        // no others.
        List<ClusterProse> leadProse = leadClusters.isEmpty()
                ? List.of()
                : summaryProseGenerator.generate(leadClusters, langCode);

        List<RenderedSection> rendered = new ArrayList<>(sections.size() + 1);
        if (!leadClusters.isEmpty()) {
            // The lead is its OWN first section — never the affordance,
            // never a footer; those belong to the category body. The
            // header uppercases in code like sectionHeader's (plain-text
            // output, caps are the strongest anchor). Positional
            // prose↔cluster correspondence holds here exactly as in the
            // FULL loop below (no categorization reorders the lead list).
            StringBuilder leadSb = new StringBuilder();
            leadSb.append(bundleLoader.get(BundleKeys.REPLY_DIGEST_LEAD_HEADER, langCode)
                    .toUpperCase(Locale.forLanguageTag(langCode)));
            for (ClusterProse cp : leadProse) {
                leadSb.append("\n\n");
                appendClusterProse(leadSb, cp, langCode);
            }
            rendered.add(new RenderedSection(LEAD_TAG, leadSb.toString()));
        }
        int proseIndex = 0;
        // ONE generative translator budget for the WHOLE render, not one
        // per section: the bound this exists to enforce is on the digest a
        // group receives, and a per-section budget would multiply by the
        // section count (M1-756). appendHeadlines spends from it and hands
        // back what is left.
        int translationBudget = translationMaxPerRender;
        for (int sectionIdx = 0; sectionIdx < sections.size(); sectionIdx++) {
            CategorySection section = sections.get(sectionIdx);
            StringBuilder sb = new StringBuilder();
            // brief/normal carry the TRUE cluster count in the header (the
            // count a capped headline list would otherwise understate);
            // full keeps the pre-M1-732 header bytes.
            sb.append(mode == DigestMode.FULL
                    ? sectionHeader(section, langCode)
                    : sectionCountHeader(section, langCode));
            if (mode == DigestMode.FULL) {
                for (int i = 0; i < section.clusters().size(); i++) {
                    sb.append("\n\n");
                    appendClusterProse(sb, proseList.get(proseIndex++), langCode);
                }
            } else {
                // The roll-up sees ALL clusters in the section (its existing
                // contract — it names what a capped headline list hides) and
                // sanitizes its own output. A roll-up failure ships the
                // section with header (+ headlines) + footer but no
                // synthesis line (CategoryRollupGenerator's existing failure
                // containment, same as renderShortBody).
                Optional<String> rollup =
                        categoryRollupGenerator.generateRollup(
                                section.clusters(), section.tag(), langCode);
                if (rollup.isPresent()) {
                    sb.append("\n\n").append(rollup.get());
                }
                if (mode == DigestMode.NORMAL) {
                    translationBudget =
                            appendHeadlines(sb, section, langCode, groupId, translationBudget);
                }
                sb.append("\n\n").append(shortFooter(section, langCode));
            }
            // The closing affordance is folded into the LAST section's text
            // here (the caller's "\n\n" join reproduces the pre-M1-652
            // trailing affordance byte-for-byte: s1 + "\n\n" + ... + "\n\n"
            // + sN joined with "\n\n" between sections, where the last
            // section's text already ends with "\n\n" + affordance). Other
            // sections are unaffected.
            if (sectionIdx == sections.size() - 1) {
                // One section-cap overflow line for the whole digest, ahead
                // of the affordance: it accounts for the categories the cap
                // dropped, whose clusters are rendered nowhere (M1-721). It
                // names no tags — listing eight of them would spend the lines
                // the cap just saved — so it steers to /summary, which is
                // uncapped in section count.
                if (droppedCategories > 0) {
                    sb.append("\n\n").append(MessageFormat.format(
                            bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORIES_MORE, langCode),
                            droppedCategories));
                }
                sb.append("\n\n").append(
                        bundleLoader.get(BundleKeys.REPLY_DIGEST_CLOSING_AFFORDANCE, langCode));
            }
            rendered.add(new RenderedSection(section.tag(), sb.toString()));
        }
        return rendered;
    }

    /**
     * Render ALREADY-GENERATED per-cluster prose into category sections —
     * the {@code /summary} entry point (M1-694), reused by {@code /retry}
     * for the default-form anchored replay (M1-696). Makes no LLM call of any
     * kind: the caller owns prose generation, so this method is safe on
     * {@code /summary}'s over-cap branch, whose whole purpose is to render
     * a degraded reply without reaching the summarizer.
     *
     * <p>Taking {@link ClusterProse} rather than a post list is what keeps
     * {@code SummaryCommandHandler} the owner of its single
     * {@code summaryProseGenerator.generate(clusters, "en")} call. Routing
     * {@code /summary} through {@link #renderSections} instead would pass
     * the scope language where the handler passes {@code "en"} (changing
     * cs-scope routing and the summarizer/translator call counts
     * {@code TranslationPipelineIT} pins), hide the prose list the handler
     * needs for its degraded-notice check, and cluster the posts a second
     * time.
     *
     * <p>The name differs from {@code renderSections} by convention — the
     * erasure collision that originally forced it (an overload taking
     * {@code List<ClusterProse>} sharing the 2-arg
     * {@code renderSections(List, String)}'s erasure) ended when M1-732
     * made {@code renderSections} 3-arg.
     *
     * <p>Two things {@link #renderSections} emits are deliberately absent.
     * The closing affordance is a broadcast-digest device — {@code /summary}
     * is interactive and composes its own prefixes and notices — and the
     * overflow line uses a {@code /summary}-scoped bundle key: the digest's
     * own {@code reply.digest.category.more} was group-worded
     * ("@mention me to see them") and wrong in a DM, and M1-732 deleted it
     * outright when {@code full} mode lifted the cap.
     *
     * <p>One thing it relies on: for degraded clusters it DERIVES the prose
     * from the cluster ({@code SummaryProseGenerator.degradedProseFor},
     * M1-697) — sanitizing each post's title, one author's field per call —
     * and never reads {@code cp.prose()}. {@code ClusterProse} is a public
     * record any caller can populate, so the redaction cannot rest on
     * producer provenance; this form renders no headline, so the derivation
     * is the only place a feed-controlled title can be redacted on the
     * {@code /summary} default path. The digest's own unsanitized assembly
     * operands are M1-691 and are deliberately untouched.
     */
    public List<RenderedSection> renderSummarySections(List<ClusterProse> proseList,
                                                       String langCode) {
        return renderSummarySections(proseList, langCode, categoryItemCap, Map.of());
    }

    /** Tree-aware default-cap overload: passes the followed-node section-key map. */
    public List<RenderedSection> renderSummarySections(List<ClusterProse> proseList,
                                                       String langCode,
                                                       Map<String, String> sectionKeyByLeaf) {
        return renderSummarySections(proseList, langCode, categoryItemCap, sectionKeyByLeaf);
    }

    /**
     * Cap-overload for the {@code /summary}-side entry point (M1-700).
     * {@code /summary --full} passes {@code Integer.MAX_VALUE} to render
     * ALL clusters per category with NO 12-per-section cap and NO
     * "+N more" overflow line — the cap-skip is a render-side switch, NOT
     * a re-tune of {@code infochat.digest.category-item-cap}. The digest
     * broadcast was out-of-scope for M1-700; M1-732 later gave the digest's
     * own {@code renderSections} the same LOCAL effective-cap treatment in
     * {@code full} mode. Only the {@code /summary} path calls this overload.
     */
    public List<RenderedSection> renderSummarySections(List<ClusterProse> proseList,
                                                       String langCode,
                                                       int effectiveCap) {
        return renderSummarySections(proseList, langCode, effectiveCap, Map.of());
    }

    // Tree-aware overload: sectionKeyByLeaf rolls tags up to followed-node keys.
    // Empty map = identity = today's bytes.
    public List<RenderedSection> renderSummarySections(List<ClusterProse> proseList,
                                                       String langCode,
                                                       int effectiveCap,
                                                       Map<String, String> sectionKeyByLeaf) {
        // Categorization reorders clusters into sections, so the positional
        // prose↔cluster correspondence the caller passed does not survive
        // and the prose must be looked up per section cluster.
        //
        // The key is cluster IDENTITY, not topicId: topicIdFor truncates the
        // cluster's smallest uid to 8 chars (ClusterTraversal:167), so
        // clusters whose uids share a prefix collide on topicId — a
        // topicId-keyed map silently renders one cluster's prose several
        // times and drops the others. categorize() partitions the very
        // instances passed in (CategorySection copies the list, not the
        // elements), so identity is exact here.
        Map<Cluster, ClusterProse> proseByCluster = new IdentityHashMap<>();
        List<Cluster> clusters = new ArrayList<>(proseList.size());
        for (ClusterProse cp : proseList) {
            clusters.add(cp.cluster());
            proseByCluster.put(cp.cluster(), cp);
        }
        List<CategorySection> sections = digestCategorizer.categorize(clusters, Set.of(), sectionKeyByLeaf);

        List<RenderedSection> rendered = new ArrayList<>(sections.size());
        for (CategorySection section : sections) {
            StringBuilder sb = new StringBuilder();
            sb.append(sectionHeader(section, langCode));
            int shownCount = Math.min(section.clusters().size(), effectiveCap);
            for (int i = 0; i < shownCount; i++) {
                sb.append("\n\n");
                // categorize() assigns each input cluster to exactly one
                // section, so every section cluster is a key this method just
                // put into the map (ClusterTraversal:128 uses the same idiom
                // for the same reason).
                ClusterProse cp = Objects.requireNonNull(
                        proseByCluster.get(section.clusters().get(i)));
                // Degraded prose is DERIVED here from the cluster, never
                // read from cp.prose() (M1-697, redteam 2026-07-25):
                // ClusterProse is a public record any caller can populate
                // with arbitrary bytes, so trusting prose() would make
                // redaction a producer convention. degradedProseFor
                // re-composes from cluster.posts() and sanitizes EACH
                // post's title — one author's field per sanitize call, so
                // no multi-post string ever reaches the sanitizer (the
                // M1-694-r3 cross-post span bug). Only non-degraded prose
                // (one LLM-authored value — the correct unit) is sanitized
                // from the record and translated. The bare URL operands
                // survive (D30): the closed-list pass never sees them, and
                // the `](` no-link guarantee is carried at OutboundDelivery
                // (M1-691), not here.
                sb.append(cp.degraded()
                        ? SummaryProseGenerator.degradedProseFor(cp.cluster(), llmOutputSanitizer, langCode)
                        : translationPipeline.run(
                                llmOutputSanitizer.sanitize(cp.prose()), langCode));
            }
            int overflow = section.clusters().size() - shownCount;
            if (overflow > 0) {
                sb.append("\n\n").append(MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_SUMMARY_CATEGORY_MORE, langCode), overflow));
            }
            rendered.add(new RenderedSection(section.tag(), sb.toString()));
        }
        return rendered;
    }

    /**
     * Render the {@code --short} form (M1-700): ONE
     * {@link CategoryRollupGenerator} roll-up synthesis per category
     * header, NO per-cluster prose, NO {@link ClusterBlockRenderer} flat
     * blocks. The roll-up sees ALL clusters in the category including
     * past-cap ones ({@link CategoryRollupGenerator#generateRollup}'s
     * existing contract). Each section carries a footer steering the
     * reader to the deeper {@code /summary} paths: a real category names
     * {@code /summary <tag>} and {@code /summary <tag> --full}; the Other
     * bucket (tag {@code == null}, not in the controlled vocabulary) names
     * bare {@code /summary} and {@code /summary --full} instead.
     *
     * <p>One LLM call per category (the roll-up); zero
     * {@link SummaryProseGenerator} calls. A roll-up failure yields
     * {@link Optional#empty()} and the section ships with its header and
     * footer but no roll-up line (CategoryRollupGenerator's existing
     * failure containment).
     *
     * <p>Single-string return: the {@code --short} overview is short
     * enough to ride in one router-sent message (like {@code --flat}),
     * not per-section (the bare form's M1-695 per-section delivery is for
     * long sections). Acceptance pins content, not message count.
     *
     * <p>Failure reporting: {@link ShortResult#clustersDegraded()} counts
     * the clusters whose category roll-up came back empty (LLM outage,
     * empty response, or REFUSAL marker) — every cluster in a failed
     * category renders the deterministic degraded form, so the count is the
     * subset of {@link ShortResult#clustersTotal()} the caller must be honest
     * about. The caller distinguishes none / partial / total
     * ({@code clustersDegraded() == clustersTotal()}) to emit the right
     * notice: no notice, the partial one, or the D43 degraded_notice — so
     * the user is NOT shown a silent wall of empty headers OR a "no prose"
     * banner above a mostly-prose reply (security.md §Failure handling;
     * redteam M1-700 kimi r1, M1-703).
     */
    public ShortResult renderShortBody(List<Cluster> clusters, String langCode) {
        return renderShortBody(clusters, langCode, Map.of());
    }

    /** Tree-aware overload: {@code sectionKeyByLeaf} rolls cluster tags up to followed-node keys. */
    public ShortResult renderShortBody(List<Cluster> clusters, String langCode,
                                       Map<String, String> sectionKeyByLeaf) {
        List<CategorySection> sections = digestCategorizer.categorize(clusters, Set.of(), sectionKeyByLeaf);
        StringBuilder out = new StringBuilder();
        int clustersTotal = 0;
        int clustersDegraded = 0;
        for (int sectionIdx = 0; sectionIdx < sections.size(); sectionIdx++) {
            CategorySection section = sections.get(sectionIdx);
            clustersTotal += section.clusters().size();
            if (sectionIdx > 0) {
                out.append("\n\n");
            }
            out.append(sectionHeader(section, langCode));
            Optional<String> rollup =
                    categoryRollupGenerator.generateRollup(
                            section.clusters(), section.tag(), langCode);
            if (rollup.isPresent()) {
                out.append("\n\n").append(rollup.get());
            } else {
                // Roll-up failed (LLM outage / empty / REFUSAL). Honor the
                // D17 degraded-form half of security.md §Failure handling
                // (redteam M1-700 r2 kimi+opencode): render the deterministic
                // headlines + URLs + post UIDs degraded prose for each cluster
                // in the category, the same fallback the other three forms
                // get through SummaryProseGenerator's degraded path. The
                // per-cluster degradedProseFor carries the M1-697 title
                // redaction (one author's field per sanitize call), so a
                // command-shaped feed title is redacted here too.
                clustersDegraded += section.clusters().size();
                for (Cluster c : section.clusters()) {
                    out.append("\n\n").append(
                            SummaryProseGenerator.degradedProseFor(c, llmOutputSanitizer, langCode));
                }
            }
            out.append("\n\n").append(shortFooter(section, langCode));
        }
        return new ShortResult(out.toString(), clustersTotal, clustersDegraded);
    }

    /**
     * Result of {@link #renderShortBody}: the rendered body plus the cluster
     * counts the caller uses to pick the honesty signal. The body is always
     * complete enough to deliver (headers + footers ride even when a roll-up
     * failed); {@code clustersDegraded == 0} means every roll-up succeeded,
     * {@code clustersDegraded == clustersTotal} means a total outage, and
     * anything between is a partial outage (M1-703): the caller emits the
     * partial notice rather than the total "no prose" banner.
     */
    public record ShortResult(String body, int clustersTotal, int clustersDegraded) {}

    /**
     * The {@code --short} per-category footer. Real categories steer to
     * {@code /summary <tag>} (categorized-capped) and
     * {@code /summary <tag> --full} (categorized-uncapped); the Other
     * bucket ({@code tag == null}, not in the controlled vocabulary so
     * {@code /summary other} would hit {@code error.summary.unknown_tag})
     * steers to bare {@code /summary} and {@code /summary --full} instead.
     */
    private String shortFooter(CategorySection section, String langCode) {
        if (section.tag() == null) {
            return bundleLoader.get(BundleKeys.REPLY_SUMMARY_SHORT_OTHER_FOOTER, langCode);
        }
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_SUMMARY_SHORT_CATEGORY_FOOTER, langCode),
                section.tag());
    }

    /**
     * Cross-package construction seam for {@link #renderSummarySections}.
     * The collaborator fields above are package-private {@code @Inject}
     * fields, so a plain-JUnit test in {@code provider.command} cannot wire
     * a real renderer — and it must wire a real one, because
     * {@code SummaryCommandHandler}'s tests assert on the sanitizer's effect
     * on rendered prose, which a hand-written fake renderer would turn into
     * a test of the fake. {@code ClusterTraversal}'s public constructor is
     * the in-repo precedent for the same problem.
     *
     * <p>Only the collaborators {@link #renderSummarySections} needs are
     * set. The returned instance is NOT usable for {@link #renderSections},
     * which additionally needs the cluster traversal and the prose
     * generator. {@link #renderShortBody} IS
     * usable: it shares the categorizer, roll-up generator, sanitizer,
     * translator and bundle loader this seam wires.
     */
    public static DigestRenderer forSummaryRendering(LlmOutputSanitizer llmOutputSanitizer,
                                                     TranslationPipeline translationPipeline,
                                                     BundleLoader bundleLoader,
                                                     int categoryItemCap,
                                                     int categoryMinClusters) {
        return forSummaryRendering(llmOutputSanitizer, translationPipeline, bundleLoader,
                categoryItemCap, categoryMinClusters, new CategoryRollupGenerator());
    }

    /**
     * Extended seam that also wires the {@link CategoryRollupGenerator}
     * (M1-700): the {@code --short} path calls
     * {@link CategoryRollupGenerator#generateRollup} per
     * category, so a {@code --short} test injects a recording subclass to
     * assert the roll-up call count and stub its output. The 5-arg
     * overload above delegates here with a default
     * {@code new CategoryRollupGenerator()} (no LLM wiring),
     * which is correct for the categorized/flat tests that never reach the
     * {@code --short} branch.
     */
    public static DigestRenderer forSummaryRendering(LlmOutputSanitizer llmOutputSanitizer,
                                                     TranslationPipeline translationPipeline,
                                                     BundleLoader bundleLoader,
                                                     int categoryItemCap,
                                                     int categoryMinClusters,
                                                     CategoryRollupGenerator categoryRollupGenerator) {
        DigestRenderer renderer = new DigestRenderer();
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = categoryMinClusters;
        renderer.digestCategorizer = categorizer;
        renderer.llmOutputSanitizer = llmOutputSanitizer;
        renderer.translationPipeline = translationPipeline;
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = categoryItemCap;
        renderer.categoryRollupGenerator = categoryRollupGenerator;
        return renderer;
    }

    /**
     * One rendered category section: the {@link CategorySection#tag()}
     * (null for the Other bucket) plus the section's delivery text. The
     * slug a delivery path needs to compose a per-(slot, category)
     * correlationId lives on the tag — {@code tag} is the category's
     * controlled-vocabulary string as-is, the literal {@code "other"} for
     * the null bucket (M1-652's (group_id, window_start, category_slug)
     * delivery-state key inherits this mapping).
     */
    public record RenderedSection(@Nullable String tag, String text) {}


    /**
     * Header lines are deterministic bundle strings resolved in the scope
     * language — never routed through the translation pipeline (that is for
     * LLM prose only). Uppercased in code: v1 output is plain text, so caps
     * are the strongest available header anchor.
     *
     * <p>The case pass runs under the SCOPE language, not {@link Locale#ROOT}
     * (M1-762): the text being cased is translated prose, and Turkish maps
     * {@code i} to {@code İ}, so a ROOT pass ships {@code DIĞER HABERLER} where
     * the language requires {@code DİĞER HABERLER}. It stays over the COMPOSED
     * header (the pre-M1-762 shape), which is what casts the {@code
     * {n,choice,…}} plural sub-messages the cs and tr values carry as the
     * translated prose they are; {@link #headerTag} is what keeps the
     * interpolated tag out of that pass's reach.</p>
     */
    private String sectionHeader(CategorySection section, String langCode) {
        String tag = section.tag();
        if (tag == null) {
            return bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_OTHER, langCode)
                    .toUpperCase(Locale.forLanguageTag(langCode));
        }
        return MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_HEADER, langCode),
                        headerTag(tag))
                .toUpperCase(Locale.forLanguageTag(langCode));
    }

    /**
     * The brief/normal digest header (M1-732): today's category header plus
     * the section's TRUE cluster count — the FULL count of clusters
     * assigned to the section, not the number of headlines the mode shows.
     * Digest-only keys: {@link #sectionHeader}'s two keys are shared with
     * {@code /summary}'s render forms (out of scope for M1-732), so the
     * count must not leak into those bytes. The cs values carry a
     * {@code {N,choice,...}} plural shape (D43 twin) whose selected
     * sub-message is translated prose, so it is cased by the scope-language
     * pass inherited from {@link #sectionHeader} along with the rest of the
     * composed header.
     */
    private String sectionCountHeader(CategorySection section, String langCode) {
        String tag = section.tag();
        if (tag == null) {
            return MessageFormat.format(
                            bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_OTHER_COUNT, langCode),
                            section.clusters().size())
                    .toUpperCase(Locale.forLanguageTag(langCode));
        }
        return MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_HEADER_COUNT, langCode),
                        headerTag(tag), section.clusters().size())
                .toUpperCase(Locale.forLanguageTag(langCode));
    }

    /**
     * The category tag as it appears inside a header: upper-cased under
     * {@link Locale#ROOT}, never the scope locale. Tags are an English
     * controlled vocabulary (D38), so a scope-language pass must not reach
     * them — under Turkish, {@code ai} would render {@code Aİ} instead of
     * {@code AI}, trading one wrong header for another (M1-762). Pre-casing
     * here is what makes the header's own scope-locale pass a no-op over the
     * tag: the JDK's language-specific upper-casing rules (Turkish, Azeri,
     * Lithuanian) all key on LOWER-case input, so an already-upper-cased
     * ASCII tag is a fixed point of every locale.
     */
    private static String headerTag(String tag) {
        return tag.toUpperCase(Locale.ROOT);
    }

    /**
     * The ONE per-cluster prose render (M1-725): FULL's category sections
     * and the lead share this helper, so the lead is byte-for-byte the
     * render the hybrid-body categories no longer do. Degraded prose is
     * DERIVED from the cluster, never read from the record (M1-697) — the
     * same structural-trust rule as {@link #renderSummarySections}:
     * {@code degradedProseFor} re-composes and sanitizes each post's title
     * (one author's field per call). Non-degraded prose skips nothing:
     * sanitizer-1 (one LLM-authored value), then the translator.
     */
    private void appendClusterProse(StringBuilder sb, ClusterProse cp, String langCode) {
        if (cp.degraded()) {
            // No translator call on the degraded arm (M1-756), and the
            // reason is the SPEC PIN, not a cost story: security.md
            // §Failure handling pins degraded output to headlines + URLs +
            // UIDs with no LLM calls, which is the same pin
            // ClusterBlockRenderer cites for its own degraded skip. The
            // cost argument would NOT carry on its own — the translator is
            // a different ModelTask.TRANSLATOR route than the summarizer
            // whose failure produced this branch, and a cache hit makes no
            // provider call at all.
            sb.append(SummaryProseGenerator.degradedProseFor(cp.cluster(), llmOutputSanitizer, langCode));
        } else {
            String sanitized = llmOutputSanitizer.sanitize(cp.prose());
            // No draw here (M1-769): the en-scope short-circuit and a cache
            // hit both return before the translation provider, and a real
            // call draws itself at the provider decorator — so the probe
            // this used to need, and the eviction race that made it charge
            // one call short, are both gone rather than fixed.
            sb.append(translationPipeline.run(sanitized, langCode));
        }
    }

    /**
     * The NORMAL-mode headline block (M1-732): up to
     * {@link #categoryHeadlineCount} bare headlines, one per cluster (the
     * cluster's first post is the representative), each an anchor-first
     * {@link DisplayHeadline} block plus its URL on its own line and NO
     * prose, entries separated by a blank line (M1-759).
     * {@code DisplayHeadline} carries flatten → sanitize → truncate with
     * ONE author's field per call, so the M1-697 redaction travels onto
     * this path — required, because the section bytes are persisted
     * post-sanitize and replayed verbatim (the boundary DigestWorker pins).
     * The URL is appended UNSANITIZED: the {@code ](} no-link guarantee is
     * carried once at OutboundDelivery (M1-691), and sanitizing a URL would
     * rewrite ordinary feed paths to {@code [redacted command]}. An empty
     * headline (a post with no renderable text) drops its separator — the
     * DegradedDigestRenderer idiom — and a cluster with neither headline
     * nor URL contributes no line.
     *
     * <p>Each headline then runs through the display-hit translation leg
     * (M1-756) — the SAME entry point, no-op legs, §10 controls, fallback
     * and cache {@code /summary} uses, over the {@link DisplayHeadline}
     * OUTPUT, so the translator's input is sanitized and capped by
     * construction. What it is handed is the PRIMARY line — the English
     * anchor when the reader does not already read the source language —
     * so the translation direction collapses to en → reader (D29).
     *
     * <p>Metering: a cache hit makes no provider call and so renders free,
     * while a miss spends one slot of {@code translationBudget} (the row
     * that spends the last slot still calls). Past the budget the headline
     * renders untranslated — and therefore BRACKETED, since the primary
     * line is then in a language the reader did not ask for; the bracket
     * costs no provider call. Returns the REMAINING budget so
     * one allowance covers the whole render across sections.
     */
    private int appendHeadlines(StringBuilder sb, CategorySection section,
                                String langCode, UUID groupId, int translationBudget) {
        int shown = Math.min(section.clusters().size(), categoryHeadlineCount);
        List<String> lines = new ArrayList<>(shown);
        int budgetLeft = translationBudget;
        for (int i = 0; i < shown; i++) {
            EligiblePostQuery.Post p = section.clusters().get(i).posts().getFirst();
            DisplayHeadline.AnchoredHeadline headline =
                    DisplayHeadline.anchorFirst(p, llmOutputSanitizer);
            boolean usesAnchor = DisplayHeadline.usesAnchor(
                    headline, p.sourceLanguage(), langCode);
            // The primary line — the anchor for a reader who does not
            // already read the source language, the publisher's own words
            // otherwise. This is what the translator sees and what the
            // cache is probed with; probing the pre-anchor string instead
            // would miss on every anchored row and re-spend the generative
            // budget on rows that cost nothing.
            String primary = usesAnchor ? headline.readerLine() : headline.originalLine();
            TranslationPipeline.DisplayHit hit = TranslationPipeline.skipped(primary);
            if (translatesUnderThisScope(primary, p.sourceLanguage(), langCode)) {
                // The cache probe is what keeps an already-converged digest
                // from being throttled by its own history: the pipeline
                // would serve these from the cache without a provider call,
                // so they must not consume the generative allowance.
                // Accepted residual (the /saved precedent, M1-755): the
                // probe and the pipeline's own read are separate lookups,
                // so a TTL expiry or size eviction landing between them
                // turns a hit this loop counted as free into a provider
                // call it did not budget. The bound is per-render-budget
                // plus that race, not an exact ceiling; nothing an
                // attacker steers, since neither eviction trigger is
                // reachable from feed content.
                String displayHitKey = TranslationPipeline.displayHitCacheLanguage(
                        SCOPE_KIND, groupId, langCode);
                boolean cacheHit = translationCache.get(primary, displayHitKey).isPresent();
                if (cacheHit || budgetLeft > 0) {
                    if (!cacheHit) {
                        budgetLeft--;
                    }
                    hit = translationPipeline.runForDisplayHit(
                            primary, p.sourceLanguage(), usesAnchor, SCOPE_KIND, groupId, langCode);
                }
            }
            String url = p.url();
            boolean hasUrl = url != null && !url.isEmpty();
            if (headline.isEmpty() && !hasUrl) {
                continue;
            }
            // The bullet marks the ENTRY, so it stays on the primary line
            // alone: the bracketed original and the URL are continuation
            // lines of the same entry, and a bullet on either would make an
            // entry read — and count — as several.
            StringBuilder line = new StringBuilder("· ");
            if (!headline.isEmpty()) {
                line.append(DisplayHeadline.anchorBlock(
                        hit.headline(),
                        TranslationPipeline.primaryInReaderLanguage(
                                hit, usesAnchor, p.sourceLanguage(), langCode),
                        headline.originalLine(),
                        hit.note()));
                if (hasUrl) {
                    line.append("\n");
                }
            }
            if (hasUrl) {
                line.append(url);
            }
            lines.add(line.toString());
        }
        if (!lines.isEmpty()) {
            // Entries are separated by a BLANK line now that each spans
            // several: on a one-line-per-entry list "\n" was enough, but a
            // three-line block needs the gap to stay scannable.
            sb.append("\n\n").append(String.join("\n\n", lines));
        }
        return budgetLeft;
    }

    /**
     * Whether a headline can reach the translator at all under this scope
     * — the pipeline's own no-op conditions, restated here ONLY so a
     * guaranteed no-op never draws from the generative budget or probes
     * the cache. The pipeline stays the authority: every value that gets
     * past this predicate is still handed to
     * {@link TranslationPipeline#runForDisplayHit}, which re-decides.
     * A null {@code sourceLanguage} is "unknown — never translate" per
     * D29's declared-never-inferred rule.
     */
    private static boolean translatesUnderThisScope(String headline,
                                                    @Nullable String sourceLanguage,
                                                    String langCode) {
        return !headline.isEmpty()
                && !"en".equalsIgnoreCase(langCode)
                && sourceLanguage != null
                && !sourceLanguage.equalsIgnoreCase(langCode);
    }
}
