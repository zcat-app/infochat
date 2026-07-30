package app.zcat.infochat.provider.digest;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestCategorizer.CategorySection;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

/**
 * Renders digest prose via the LLM summarizer with the group's language code,
 * sanitizes and translates each cluster's output, grouped under deterministic
 * category headers with a per-section item cap, a cap on the NUMBER of
 * sections (M1-721) and one closing affordance line (D62).
 */
@ApplicationScoped
public class DigestRenderer {

    @Inject
    ClusterTraversal clusterTraversal;

    @Inject
    DigestCategorizer digestCategorizer;

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

    @Inject
    BundleLoader bundleLoader;

    /** Max clusters rendered per category section; the overflow becomes a localized "+N more" line. */
    @ConfigProperty(name = "infochat.digest.category-item-cap", defaultValue = "12")
    int categoryItemCap;

    public String render(List<EligiblePostQuery.Post> posts,
                                  String langCode) {
        return String.join("\n\n", renderSections(posts, langCode).stream()
                .map(RenderedSection::text)
                .toList());
    }

    /**
     * Render the digest as an ordered list of per-category sections — the
     * EXACT delivery bytes (M1-652 fork closed, arm (b), 2026-07-20). The
     * closing affordance is folded into the LAST section's text inside this
     * pass, so {@link #render} stays a pure {@code "\n\n"} join over the
     * section list and M1-652 can
     * persist the list at render time and replay a filtered subset on
     * {@code /retry --digest} without re-deriving anything.
     *
     * <p>Section order is {@link DigestCategorizer#categorize}'s D62 order
     * (assigned-cluster count descending, alphabetical ties, Other last)
     * inherited as-is — never sorted here. The only filtering is
     * {@link DigestCategorizer#capSections}, which drops whole sections
     * off the tail (M1-721); within the survivors the order is untouched.
     */
    public List<RenderedSection> renderSections(List<EligiblePostQuery.Post> posts,
                                                String langCode) {
        List<Cluster> clusters = clusterTraversal.cluster(posts);
        List<CategorySection> allSections = digestCategorizer.categorize(clusters);
        // The section cap is applied HERE and not inside categorize(), so it
        // reaches only the digest broadcast: renderSummarySections and
        // renderShortBody share the categorizer and must keep every section
        // (M1-721). Everything below runs over the capped list, which is what
        // keeps prose off the dropped sections.
        List<CategorySection> sections = digestCategorizer.capSections(allSections);
        int droppedCategories = allSections.size() - sections.size();

        // Prose is generated only for the clusters that will actually render
        // (up to the per-section cap) — a capped-out cluster gets the "+N
        // more" line instead, so its LLM call would be pure waste. Still ONE
        // generate() call for the whole digest, as before categorization, so
        // per-call behavior (e.g. degraded fallback) stays uniform across
        // sections.
        List<Cluster> shownClusters = new ArrayList<>();
        for (CategorySection section : sections) {
            int shownCount = Math.min(section.clusters().size(), categoryItemCap);
            shownClusters.addAll(section.clusters().subList(0, shownCount));
        }
        List<ClusterProse> proseList = summaryProseGenerator.generate(shownClusters, langCode);

        List<RenderedSection> rendered = new ArrayList<>(sections.size());
        int proseIndex = 0;
        for (int sectionIdx = 0; sectionIdx < sections.size(); sectionIdx++) {
            CategorySection section = sections.get(sectionIdx);
            StringBuilder sb = new StringBuilder();
            sb.append(sectionHeader(section, langCode));
            int shownCount = Math.min(section.clusters().size(), categoryItemCap);
            for (int i = 0; i < shownCount; i++) {
                sb.append("\n\n");
                ClusterProse cp = proseList.get(proseIndex++);
                // Degraded prose is DERIVED from the cluster, never read
                // from the record (M1-697) — same structural-trust rule as
                // renderSummarySections below: degradedProseFor re-composes
                // and sanitizes each post's title (one author's field per
                // call). Non-degraded prose skips nothing: sanitizer-1
                // (one LLM-authored value), then the translator.
                if (cp.degraded()) {
                    sb.append(SummaryProseGenerator.degradedProseFor(cp.cluster(), llmOutputSanitizer));
                } else {
                    String sanitized = llmOutputSanitizer.sanitize(cp.prose());
                    sb.append(translationPipeline.run(sanitized, langCode));
                }
            }
            int overflow = section.clusters().size() - shownCount;
            if (overflow > 0) {
                // Real categories steer to /summary <tag> --full (token {1} =
                // the raw controlled-vocab tag the command parses, same value
                // sectionHeader passes to the {0} NEWS header); the Other
                // bucket (tag == null, not in the vocabulary) steers to bare
                // /summary --full — mirrors shortFooter's null-tag branch.
                String moreKey = section.tag() == null
                        ? BundleKeys.REPLY_DIGEST_CATEGORY_MORE_OTHER
                        : BundleKeys.REPLY_DIGEST_CATEGORY_MORE;
                sb.append("\n\n").append(MessageFormat.format(
                        bundleLoader.get(moreKey, langCode), overflow, section.tag()));
            }
            // The closing affordance is folded into the LAST section's text
            // here (render()'s "\n\n" join reproduces today's trailing
            // affordance byte-for-byte: s1 + "\n\n" + ... + "\n\n" + sN
            // joined with "\n\n" between sections, where the last section's
            // text already ends with "\n\n" + affordance). Other sections
            // are unaffected.
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
     * <p>The name differs from {@code renderSections} deliberately: an
     * overload taking {@code List<ClusterProse>} shares
     * {@code renderSections(List, String)}'s erasure and would not compile.
     *
     * <p>Two things {@link #renderSections} emits are deliberately absent.
     * The closing affordance is a broadcast-digest device — {@code /summary}
     * is interactive and composes its own prefixes and notices — and the
     * overflow line uses a {@code /summary}-scoped bundle key, because the
     * digest's own {@code reply.digest.category.more} is group-worded
     * ("@mention me to see them") and would be wrong in a DM.
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
        return renderSummarySections(proseList, langCode, categoryItemCap);
    }

    /**
     * Cap-overload for the {@code /summary}-side entry point (M1-700).
     * {@code /summary --full} passes {@code Integer.MAX_VALUE} to render
     * ALL clusters per category with NO 12-per-section cap and NO
     * "+N more" overflow line — the cap-skip is a render-side switch, NOT
     * a re-tune of {@code infochat.digest.category-item-cap} (the digest's
     * own {@code render}/{@code renderSections} entry points stay capped).
     * The digest broadcast is out-of-scope for M1-700; only the
     * {@code /summary} path calls this overload.
     */
    public List<RenderedSection> renderSummarySections(List<ClusterProse> proseList,
                                                       String langCode,
                                                       int effectiveCap) {
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
        List<CategorySection> sections = digestCategorizer.categorize(clusters);

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
                        ? SummaryProseGenerator.degradedProseFor(cp.cluster(), llmOutputSanitizer)
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
        List<CategorySection> sections = digestCategorizer.categorize(clusters);
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
                    categoryRollupGenerator.generateRollup(section.clusters(), langCode);
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
                            SummaryProseGenerator.degradedProseFor(c, llmOutputSanitizer));
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
     * set. The returned instance is NOT usable for {@link #render} or
     * {@link #renderSections}, which additionally need the cluster
     * traversal and the prose generator. {@link #renderShortBody} IS
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
     */
    private String sectionHeader(CategorySection section, String langCode) {
        String tag = section.tag();
        if (tag == null) {
            return bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_OTHER, langCode)
                    .toUpperCase(Locale.ROOT);
        }
        return MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_HEADER, langCode), tag)
                .toUpperCase(Locale.ROOT);
    }
}
