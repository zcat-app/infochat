package app.zcat.infochat.llm;

import java.util.List;

/**
 * Batch text → vector SPI. One {@code embed} call against a list of
 * inputs yields one {@link EmbeddingResult} per input, in input order.
 *
 * <p>The batch shape is the SPI commitment (docs/spec/llm.md §Embedding
 * pipeline "Batch SPI"): callers may batch as wide as they like; the
 * impl decides how to chunk on the wire. Per-element error mapping is
 * an impl concern — the spec's "one-failure-fails-batch retry" rule is
 * an impl escape hatch, not per-element nullability on this return.</p>
 *
 * <p>The active embedding model's identifier and dimensionality are
 * stored in a singleton metadata row and validated on every startup
 * (docs/spec/llm.md §Embedding pipeline). That guard runs in the
 * embedding-pipeline wiring ticket against the database; it is NOT a
 * method on this SPI.</p>
 */
public interface EmbeddingProvider {

    /**
     * Embed a batch of texts.
     *
     * @param texts the inputs to embed; never null. May be empty (the
     *              impl returns an empty list).
     * @return one {@link EmbeddingResult} per input, in input order.
     *         Never null; size equals {@code texts.size()}.
     */
    List<EmbeddingResult> embed(List<String> texts);
}
