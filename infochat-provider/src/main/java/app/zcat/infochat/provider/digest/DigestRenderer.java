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

        StringBuilder sb = new StringBuilder();
        int proseIndex = 0;
        for (CategorySection section : sections) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(sectionHeader(section, langCode));
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
        }
        sb.append("\n\n").append(bundleLoader.get(BundleKeys.REPLY_DIGEST_CLOSING_AFFORDANCE, langCode));
        return sb.toString();
    }

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
