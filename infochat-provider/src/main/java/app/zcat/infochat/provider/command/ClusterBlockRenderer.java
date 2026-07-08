package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationPipeline;

import java.text.MessageFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders one summary cluster block — the six deterministic fields plus the
 * single LLM-authored {@code summary:} field — shared verbatim by
 * {@code /summary} (terminal compose) and {@code /retry} (anchored replay).
 *
 * <p>The byte-identical-replay property (D19/D36) requires both call sites to
 * emit the same bytes for the same cluster; collapsing the two former copies
 * into this one renderer is what guarantees it. The sanitize&rarr;translate
 * ordering on the {@code summary:} field is load-bearing: sanitizer-1 runs on
 * the raw LLM prose, then the translation pipeline (which internally runs
 * sanitizer-2 + cache). Reordering it — or altering the surrounding
 * whitespace — would silently break replay.
 *
 * <p>Not a CDI bean: each handler constructs it from its own injected
 * {@link LlmOutputSanitizer} / {@link TranslationPipeline} / {@link BundleLoader},
 * so the collaborators stay owned by the handlers (and remain test-settable
 * on them).
 *
 * <p>The field labels ("covered by:", "score:", "summary:", ...) are
 * bundle-resolved in the scope's reply language (D43 / design 05 §418: cluster
 * headers and classification labels are Translated). The {@code topic_id}
 * marker and the headline are structural / source-authored and stay verbatim.
 * Label prefixes carry no trailing space in the bundle — the renderer appends
 * the single separator space — so the bundle never depends on invisible
 * trailing whitespace surviving an editor round-trip.
 */
final class ClusterBlockRenderer {

    private final LlmOutputSanitizer llmOutputSanitizer;
    private final TranslationPipeline translationPipeline;
    private final BundleLoader bundleLoader;

    ClusterBlockRenderer(LlmOutputSanitizer llmOutputSanitizer,
                         TranslationPipeline translationPipeline,
                         BundleLoader bundleLoader) {
        this.llmOutputSanitizer = llmOutputSanitizer;
        this.translationPipeline = translationPipeline;
        this.bundleLoader = bundleLoader;
    }

    /**
     * Compose one cluster block. Six fields are deterministic; only
     * {@code summary:} is LLM-authored (degraded entries write the degraded
     * prose into the same slot, bypassing the pipeline per D43's
     * bundle-not-translator invariant).
     */
    void appendClusterBlock(StringBuilder out, ClusterProse cp, String scopeLanguage) {
        Cluster cluster = cp.cluster();
        List<Post> posts = cluster.posts();
        Post first = posts.get(0);

        // [topic_id=...] — deterministic; ClusterTraversal computed it.
        out.append("[topic_id=").append(cluster.topicId()).append("]\n");
        // headline — first post's title.
        out.append(first.title()).append("\n");
        // covered by: source display name (uid p-...), ...
        out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_CLUSTER_COVERED_BY, scopeLanguage))
           .append(' ');
        for (int i = 0; i < posts.size(); i++) {
            Post p = posts.get(i);
            if (i > 0) out.append(", ");
            out.append(p.sourceDisplayName()).append(" (uid ").append(p.uid()).append(")");
        }
        out.append("\n");
        // score: <count> sources (placeholder shape for MVP). The bundle value
        // is a MessageFormat {0,choice,...} plural template so cs renders its
        // three-form plural (1 zdroj / 2 zdroje / 5 zdrojů).
        Set<String> sourceSet = new LinkedHashSet<>();
        for (Post p : posts) {
            sourceSet.add(p.sourceDisplayName());
        }
        out.append(MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_SUMMARY_CLUSTER_SCORE, scopeLanguage),
                        sourceSet.size()))
           .append("\n");
        // summary: degraded prose bypasses the pipeline per D43
        // (bundle-not-translator invariant); non-degraded prose runs
        // through sanitizer-1 then the translation pipeline (which
        // internally runs sanitizer-2 + cache).
        String summaryText = cp.degraded()
                ? cp.prose()
                : translationPipeline.run(
                        llmOutputSanitizer.sanitize(cp.prose()), scopeLanguage);
        out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_CLUSTER_SUMMARY_LABEL, scopeLanguage))
           .append(' ').append(summaryText).append("\n");
        // classification: comma-joined union of cluster.posts.tags.
        out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_CLUSTER_CLASSIFICATION_LABEL, scopeLanguage))
           .append(' ').append(joinedTags(posts)).append("\n");
        // tags: deduplicated union of cluster.posts.tags.
        out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_CLUSTER_TAGS_LABEL, scopeLanguage))
           .append(' ').append(joinedTags(posts)).append("\n");
        out.append("\n");
    }

    private static String joinedTags(List<Post> posts) {
        Set<String> union = new LinkedHashSet<>();
        for (Post p : posts) {
            union.addAll(p.tags());
        }
        return String.join(", ", union);
    }
}
