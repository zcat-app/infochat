package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.render.DisplayHeadline;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationPipeline;

import java.text.MessageFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Renders one summary cluster block — the six deterministic fields plus the
 * single LLM-authored {@code summary:} field — shared verbatim by
 * {@code /summary --full} (terminal compose) and {@code /retry} (anchored
 * replay of a {@code --full} anchor; default-form anchors replay categorized
 * via {@code DigestRenderer.renderSummarySections} instead, M1-696).
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
 * marker stays verbatim; the headline is source-authored text — the post title,
 * or its body when the title is empty — so it is passed through
 * {@link LlmOutputSanitizer} inside {@link DisplayHeadline} (M1-675) — this
 * cluster block is group-visible and the headline renders at line start, where
 * command-shaped text would otherwise be one copy-paste from dispatch.
 * Deriving the headline is a deterministic pure function; for an {@code en}
 * scope (every scope today) the byte-identical-replay property (D19/D36)
 * holds exactly as before. For a non-English scope the headline additionally
 * runs the display-hit translation leg (M1-747), which carries the same
 * cache/provider temporal variance the {@code summary:} field's translation
 * already has — replay parity is preserved because /retry re-projects the
 * post's source language and its English anchor (not because the leg is
 * pure). Degraded clusters are excepted from the TRANSLATION only: they
 * render the headline untranslated, because the degraded branch exists
 * precisely because the LLM path failed and
 * {@code security.md} §Failure handling pins its shape as zero LLM calls
 * (redteam 2026-08-03, low/DOS). They are NOT excepted from the anchor or
 * the bracket — both are column reads and cost no call (M1-759). The order of the
 * steps inside {@link DisplayHeadline} is load-bearing for the sanitizer's
 * guarantees and is documented there — never re-apply one of them from a
 * caller. A legit-slash title (TCP/IP) is returned byte-identical.
 * Label prefixes carry no trailing space in the bundle — the renderer appends
 * the single separator space — so the bundle never depends on invisible
 * trailing whitespace surviving an editor round-trip.
 */
final class ClusterBlockRenderer {

    /**
     * The no-signal classification label. {@code unknown} is a real per-post
     * label from the closed classification set (docs/design/05-llm-and-embeddings.md
     * §5.4.4 Classifier, landed by M1-597) but carries no cluster-level signal:
     * it is dropped from the rendered union whenever any substantive label is
     * present, and shown alone only when the whole union is exactly {@code {unknown}}.
     */
    private static final String UNKNOWN_CLASSIFICATION = "unknown";

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
    void appendClusterBlock(StringBuilder out, ClusterProse cp, String scopeLanguage,
                            String scopeKind, UUID scopeId) {
        Cluster cluster = cp.cluster();
        List<Post> posts = cluster.posts();
        Post first = posts.get(0);

        // [topic_id=...] — deterministic; ClusterTraversal computed it.
        out.append("[topic_id=").append(cluster.topicId()).append("]\n");
        // headline — first post's title (or its body when the title is empty,
        // as it is for every Bluesky post), closed-list-sanitized (M1-675): a
        // group-visible line-start title shaped like a privileged command
        // would otherwise reflect straight into a broadcast reply.
        // Sanitize-unit invariant (M1-697) still holds inside the helper: the
        // input is ONE post's single field — every sanitize call over
        // feed-derived text takes a single author's field, never a
        // concatenation of several posts' bytes (the flag-entry span would
        // otherwise erase whole posts between a command word in one title and
        // its flag in another), and never title and body concatenated.
        // An empty result means the post carries no renderable text at all, so
        // the line is omitted rather than emitted blank (M1-714) — the block
        // is still identified by the topic_id above and the covered-by line
        // below.
        DisplayHeadline.AnchoredHeadline headline =
                DisplayHeadline.anchorFirst(first, llmOutputSanitizer);
        if (!headline.isEmpty()) {
            boolean usesAnchor = DisplayHeadline.usesAnchor(
                    headline, first.sourceLanguage(), scopeLanguage);
            String primary = usesAnchor ? headline.readerLine() : headline.originalLine();
            // Display-hit translation (M1-747): a no-op for en scopes,
            // same-language hits, and null source language — the pipeline
            // owns the decision, the controls (pre-bound → flatten →
            // sanitizer-2 → re-truncate) and the fallback. Input
            // is the DisplayHeadline OUTPUT, so the snippet is capped
            // before the translator call by construction. A DEGRADED
            // cluster skips the leg outright — untranslated:
            // the branch exists because the LLM path failed, and turning
            // the cost-shedding path into one translator round-trip per
            // cluster would invert it (security.md §Failure handling pins
            // degraded = headlines + URLs + UIDs, no LLM calls; redteam
            // 2026-08-03, low/DOS). Skipping it does NOT make the line
            // bare: a degraded cluster whose source language differs from
            // the reader's is bracketed like any other untranslated
            // primary, which costs no LLM call (M1-759).
            TranslationPipeline.DisplayHit hit = cp.degraded()
                    ? TranslationPipeline.skipped(primary)
                    : translationPipeline.runForDisplayHit(
                            primary, first.sourceLanguage(), usesAnchor,
                            scopeKind, scopeId, scopeLanguage);
            out.append(DisplayHeadline.anchorBlock(
                            hit.headline(),
                            TranslationPipeline.primaryInReaderLanguage(
                                    hit, usesAnchor, first.sourceLanguage(), scopeLanguage),
                            headline.originalLine(),
                            hit.note()))
               .append("\n");
        }
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
        // summary: degraded prose is DERIVED from the cluster, never read
        // from cp.prose() (M1-697, redteam 2026-07-25): ClusterProse is a
        // public record any caller can populate, so degraded bytes in it
        // are untrusted. degradedProseFor re-composes and sanitizes EACH
        // post's title — one author's field per sanitize call (the same
        // invariant as the headline above). Non-degraded prose runs
        // through sanitizer-1 (one LLM-authored value — the correct unit)
        // then the translation pipeline (sanitizer-2 + cache inside).
        String summaryText = cp.degraded()
                ? SummaryProseGenerator.degradedProseFor(cluster, llmOutputSanitizer, scopeLanguage)
                : translationPipeline.run(
                        llmOutputSanitizer.sanitize(cp.prose()), scopeLanguage);
        out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_CLUSTER_SUMMARY_LABEL, scopeLanguage))
           .append(' ').append(summaryText).append("\n");
        // classification: union of the cluster's per-post classification label
        // sets (the ingest classifier's output) — genuinely independent of the
        // tags: line below, NOT the tag-union stub M1-591 reverted.
        out.append(bundleLoader.get(BundleKeys.REPLY_SUMMARY_CLUSTER_CLASSIFICATION_LABEL, scopeLanguage))
           .append(' ').append(joinedClassifications(posts)).append("\n");
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

    private static String joinedClassifications(List<Post> posts) {
        // Union the cluster's per-post classification sets in first-seen order
        // for deterministic (D19/D36) output — same discipline as joinedTags.
        Set<String> union = new LinkedHashSet<>();
        for (Post p : posts) {
            union.addAll(p.classification());
        }
        // Drop `unknown` when any substantive label is present; keep it alone
        // only when the whole union is exactly {unknown}. The DB guarantees a
        // non-empty classification per post (NOT NULL + cardinality>=1 CHECK,
        // V57), so the union is never empty and the line is always populated.
        boolean hasSubstantive = union.stream().anyMatch(label -> !UNKNOWN_CLASSIFICATION.equals(label));
        if (hasSubstantive) {
            union.remove(UNKNOWN_CLASSIFICATION);
        }
        return String.join(", ", union);
    }
}
