package app.zcat.infochat.collector.eval.entity;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Parsed, normalized, and vocabulary-filtered output of one entity
 * extraction LLM call. Carries only the entities that survived the
 * {@code entity_type} CHECK-vocabulary filter, each with
 * {@code entity_text} already normalized ({@link java.util.Locale#ROOT}
 * lower-cased, whitespace-stripped) and duplicate {@code (text, type)}
 * pairs collapsed.
 *
 * <p>An empty {@link #entities()} list is a valid SUCCESS result — the
 * post had no extractable in-vocabulary entities — and is distinct from
 * a failure-release, which the {@link EntityExtractorWorker} represents
 * separately (the worker advances {@code entity_done=TRUE} in both
 * cases but only notifies on failure-release).
 */
public record EntityExtractionResult(@NonNull List<Entity> entities) {

    /** One extracted entity destined for a {@code post_entity} row. */
    public record Entity(@NonNull String text, @NonNull String type) {
    }
}
