package app.zcat.infochat.provider.summary;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates per-cluster summary prose by invoking the
 * {@link LlmProvider} once per {@link Cluster}. The prompt retains
 * {@code [REDACTED:<id>]} placeholders verbatim per
 * docs/spec/security.md §Failure handling — the placeholder serves the
 * same defensive purpose at summarize time as at delivery time. The
 * caller (the handler) is responsible for sanitizing the LLM's reply
 * (LlmOutputSanitizer) BEFORE the prose lands in the outbound message.
 *
 * <p>When the LLM call throws, the generator falls back to the
 * degraded form (headlines + bare URLs + UIDs, no prose) per decision
 * D17 + docs/design/03-commands.md §`/summary`. The deterministic post
 * selection is unaffected; only the prose body changes.
 */
@ApplicationScoped
public class SummaryProseGenerator {

    private static final Logger LOG = Logger.getLogger(SummaryProseGenerator.class);

    /**
     * System-role framing for the SUMMARIZER task. Asks the LLM ONLY
     * for the prose body that fills the {@code summary:} field — the
     * surrounding structural fields are deterministic and the handler
     * computes them from {@code cluster.posts} metadata (acceptance
     * item 12). Plain text only; the sanitizer enforces this on the
     * output side.
     */
    static final String SUMMARIZER_SYSTEM_PROMPT =
            "You write short, neutral news prose. Output ONLY the summary "
          + "paragraph for the cluster — no headlines, no field labels, no "
          + "lists, no markdown formatting. Use plain text and bare URLs only.";

    @Inject
    LlmRouter llmRouter;

    /** Carries the per-cluster generation outcome the handler reads. */
    public record ClusterProse(Cluster cluster, String prose, boolean degraded) {}

    /**
     * Run one LLM call per cluster. The {@code scopeLanguage} parameter
     * is forwarded to {@link LlmRouter#forTask(ModelTask, String)}; v1
     * resolves to the English provider. When any cluster's call throws,
     * THIS cluster falls back to the degraded form; other clusters in
     * the batch continue to attempt generation (the per-cluster
     * boundary is the failure unit).
     */
    public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
        LlmProvider provider;
        try {
            provider = llmRouter.forTask(ModelTask.SUMMARIZER, scopeLanguage);
        } catch (RuntimeException e) {
            LOG.warnf(e, "SUMMARIZER provider unresolvable; degrading every cluster");
            return clusters.stream()
                    .map(c -> new ClusterProse(c, degradedProseFor(c), true))
                    .collect(Collectors.toList());
        }

        List<ClusterProse> out = new ArrayList<>(clusters.size());
        for (Cluster cluster : clusters) {
            String userPrompt = buildPrompt(cluster);
            try {
                LlmResponse response = provider.generate(
                        ModelTask.SUMMARIZER, SUMMARIZER_SYSTEM_PROMPT, userPrompt);
                String text = response == null || response.text() == null
                        ? "" : response.text().trim();
                if (text.isEmpty()) {
                    LOG.warnf("SUMMARIZER returned empty text for topic %s; degrading",
                            cluster.topicId());
                    out.add(new ClusterProse(cluster, degradedProseFor(cluster), true));
                } else {
                    out.add(new ClusterProse(cluster, text, false));
                }
            } catch (RuntimeException e) {
                LOG.warnf(e, "SUMMARIZER call failed for topic %s; degrading",
                        cluster.topicId());
                out.add(new ClusterProse(cluster, degradedProseFor(cluster), true));
            }
        }
        return out;
    }

    /**
     * Build the user prompt for one cluster. Includes every post's
     * title + body verbatim; {@code [REDACTED:<id>]} placeholders are
     * NOT stripped (docs/spec/security.md §Failure handling).
     */
    static String buildPrompt(Cluster cluster) {
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize the following posts in one short paragraph:\n\n");
        int n = 1;
        for (Post p : cluster.posts()) {
            sb.append('[').append(n++).append("] ").append(p.title()).append('\n');
            if (p.body() != null && !p.body().isEmpty()) {
                sb.append(p.body()).append('\n');
            }
            if (p.url() != null && !p.url().isEmpty()) {
                sb.append(p.url()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Degraded form: headlines + bare URLs + post UIDs, no prose
     * paragraph (decision D17). The deterministic post selection is
     * unaffected.
     */
    public static String degradedProseFor(Cluster cluster) {
        StringBuilder sb = new StringBuilder();
        for (Post p : cluster.posts()) {
            sb.append(p.title());
            if (p.url() != null && !p.url().isEmpty()) {
                sb.append(" — ").append(p.url());
            }
            sb.append(" (uid ").append(p.uid()).append(")\n");
        }
        return sb.toString().stripTrailing();
    }
}
