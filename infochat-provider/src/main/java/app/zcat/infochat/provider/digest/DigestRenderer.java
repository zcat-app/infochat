package app.zcat.infochat.provider.digest;

import java.util.List;

import org.jspecify.annotations.NonNull;

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
 * sanitizes and translates each cluster's output.
 */
@ApplicationScoped
public class DigestRenderer {

    @Inject
    ClusterTraversal clusterTraversal;

    @Inject
    SummaryProseGenerator summaryProseGenerator;

    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    @Inject
    TranslationPipeline translationPipeline;

    public @NonNull String render(@NonNull List<EligiblePostQuery.Post> posts,
                                  @NonNull String langCode) {
        List<Cluster> clusters = clusterTraversal.cluster(posts);
        List<ClusterProse> proseList = summaryProseGenerator.generate(clusters, langCode);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < proseList.size(); i++) {
            if (i > 0) sb.append("\n\n");
            ClusterProse cp = proseList.get(i);
            // Degraded per-cluster prose skips sanitizer+translator (same as SummaryCommandHandler)
            if (cp.degraded()) {
                sb.append(cp.prose());
            } else {
                String sanitized = llmOutputSanitizer.sanitize(cp.prose());
                sb.append(translationPipeline.run(sanitized, langCode));
            }
        }
        return sb.toString();
    }
}
