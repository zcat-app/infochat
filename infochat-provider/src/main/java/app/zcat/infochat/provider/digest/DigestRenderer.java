package app.zcat.infochat.provider.digest;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
