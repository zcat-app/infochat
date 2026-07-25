package app.zcat.infochat.provider.digest;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
 * category headers with a per-section item cap and one closing affordance
 * line (D62).
 */
@ApplicationScoped
public class DigestRenderer {

    @Inject
    ClusterTraversal clusterTraversal;

    @Inject
    DigestCategorizer digestCategorizer;

    @Inject
    SummaryProseGenerator summaryProseGenerator;

    // Field-initialized to a default instance whose categorySummaryEnabled
    // stays false (the Java default), so generateRollup() short-circuits
    // without touching its own null @Inject collaborators. CDI overwrites
    // this with the deployment-wide bean at runtime — the same pattern
    // DigestWorker.clock follows (M1-444 reference). The default keeps
    // pre-existing plain-JUnit tests (DigestRendererTest,
    // DigestWorkerClockTest) passing UNMODIFIED: render()'s new thin-join
    // over renderSections() calls generateRollup() per section, and a null
    // field here would NPE those tests' hand-wired SetUps.
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
     * pass, and flag-on roll-up prefixes live inside their sections, so
     * {@link #render} stays a pure {@code "\n\n"} join over the section
     * list (byte-identical at the roll-up flag's default) and M1-652 can
     * persist the list at render time and replay a filtered subset on
     * {@code /retry --digest} without re-deriving anything.
     *
     * <p>Section order is {@link DigestCategorizer#categorize}'s D62 order
     * (assigned-cluster count descending, alphabetical ties, Other last)
     * inherited as-is — never sorted or filtered here.
     */
    public List<RenderedSection> renderSections(List<EligiblePostQuery.Post> posts,
                                                String langCode) {
        List<Cluster> clusters = clusterTraversal.cluster(posts);
        List<CategorySection> sections = digestCategorizer.categorize(clusters);

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
            // Optional per-category roll-up prefix (default-off flag). The
            // roll-up is LLM prose generated inside this renderSections()
            // pass — the same windowEnd-bounded future cluster prose runs
            // in — then sanitized and translated by CategoryRollupGenerator
            // before returning. The prefix folds INSIDE the section's text
            // (M1-652 fork closed, arm (b): persisted sections replay as the
            // exact delivery bytes). A roll-up failure yields Optional.empty
            // and the section ships without a prefix — exactly the flag-off
            // shape.
            categoryRollupGenerator.generateRollup(section.clusters(), langCode)
                    .ifPresent(rollup -> sb.append("\n\n").append(rollup));
            int shownCount = Math.min(section.clusters().size(), categoryItemCap);
            for (int i = 0; i < shownCount; i++) {
                sb.append("\n\n");
                ClusterProse cp = proseList.get(proseIndex++);
                // Degraded per-cluster prose skips sanitizer+translator (same as SummaryCommandHandler)
                if (cp.degraded()) {
                    sb.append(cp.prose());
                } else {
                    String sanitized = llmOutputSanitizer.sanitize(cp.prose());
                    sb.append(translationPipeline.run(sanitized, langCode));
                }
            }
            int overflow = section.clusters().size() - shownCount;
            if (overflow > 0) {
                sb.append("\n\n").append(MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_MORE, langCode), overflow));
            }
            // The closing affordance is folded into the LAST section's text
            // here (render()'s "\n\n" join reproduces today's trailing
            // affordance byte-for-byte: s1 + "\n\n" + ... + "\n\n" + sN
            // joined with "\n\n" between sections, where the last section's
            // text already ends with "\n\n" + affordance). Other sections
            // are unaffected.
            if (sectionIdx == sections.size() - 1) {
                sb.append("\n\n").append(
                        bundleLoader.get(BundleKeys.REPLY_DIGEST_CLOSING_AFFORDANCE, langCode));
            }
            rendered.add(new RenderedSection(section.tag(), sb.toString()));
        }
        return rendered;
    }

    /**
     * Render ALREADY-GENERATED per-cluster prose into category sections —
     * the {@code /summary} entry point (M1-694). Makes no LLM call of any
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
     * <p>One thing it adds: degraded prose is sanitized here, where
     * {@link #renderSections} passes it through. This form renders no
     * headline, so it is the only place a feed-controlled title can be
     * redacted on the {@code /summary} path — see the inline note at the
     * sanitize call. The digest's own unsanitized assembly operands are
     * M1-691 and are deliberately untouched.
     */
    public List<RenderedSection> renderSummarySections(List<ClusterProse> proseList,
                                                       String langCode) {
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
            int shownCount = Math.min(section.clusters().size(), categoryItemCap);
            for (int i = 0; i < shownCount; i++) {
                sb.append("\n\n");
                // categorize() assigns each input cluster to exactly one
                // section, so every section cluster is a key this method just
                // put into the map (ClusterTraversal:128 uses the same idiom
                // for the same reason).
                ClusterProse cp = Objects.requireNonNull(
                        proseByCluster.get(section.clusters().get(i)));
                // BOTH branches sanitize; only non-degraded prose is also
                // translated. The two bypasses degraded prose gets elsewhere
                // have different reasons and only the translation one
                // survives here: translation is skipped because degraded
                // prose is deterministic non-LLM text and D43 is a
                // bundle-not-translator rule, but the BYTES are
                // upstream-controlled feed titles (degradedProseFor composes
                // "title — url (uid)"), which is exactly the tier M1-675's
                // render-side redaction exists for. ClusterBlockRenderer can
                // skip sanitizing its degraded prose only because it
                // sanitizes the headline separately (:87); this form renders
                // no headline, so skipping here would leave nothing
                // sanitized at all. The sanitizer loses no characters and
                // only breaks "](" adjacency, so the bare URL survives (D30).
                String sanitized = llmOutputSanitizer.sanitize(cp.prose());
                sb.append(cp.degraded() ? sanitized
                        : translationPipeline.run(sanitized, langCode));
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
     * traversal and the prose generator.
     */
    public static DigestRenderer forSummaryRendering(LlmOutputSanitizer llmOutputSanitizer,
                                                     TranslationPipeline translationPipeline,
                                                     BundleLoader bundleLoader,
                                                     int categoryItemCap,
                                                     int categoryMinClusters) {
        DigestRenderer renderer = new DigestRenderer();
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = categoryMinClusters;
        renderer.digestCategorizer = categorizer;
        renderer.llmOutputSanitizer = llmOutputSanitizer;
        renderer.translationPipeline = translationPipeline;
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = categoryItemCap;
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
