package app.zcat.infochat.collector.eval.classifier;


import java.util.List;

/**
 * Parsed, closed-enum-filtered, and capped output of one classification
 * LLM call. Carries only the labels that survived the substantive-label
 * membership filter ({@link ClassifierWorker#SUBSTANTIVE_LABELS}),
 * de-duplicated and capped, with the {@code unknown}-mutual-exclusion
 * rule already applied by the worker's parse path.
 *
 * <p>{@link #labels()} is ALWAYS non-empty and CHECK-valid — it is either
 * 1..{@link ClassifierWorker#MAX_LABELS_PER_POST} substantive labels or
 * the single-element {@code [unknown]}. There is no empty-list state: an
 * empty-after-filter parse resolves to {@code [unknown]} in the worker,
 * mirroring the DB {@code NOT NULL DEFAULT ARRAY['unknown']} column
 * contract, so the {@code post.classification} write never violates the
 * non-empty CHECK.
 */
public record ClassificationResult(List<String> labels) {
}
