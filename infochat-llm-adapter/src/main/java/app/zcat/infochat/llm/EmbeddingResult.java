package app.zcat.infochat.llm;

/**
 * The single value an {@link EmbeddingProvider} call returns per input
 * element. v1 commits only to the dense vector itself; future expansion
 * (per-element metadata, multi-vector returns, dimensionality reporting
 * out-of-band) lands as additional record components without changing
 * the {@code List<EmbeddingResult>} batch shape consumers depend on.
 *
 * <p>A bare {@code float[]} would also meet the spec; the wrapper costs
 * one record now and decouples cross-call-site signatures from any
 * later additive change.</p>
 */
public record EmbeddingResult(float[] vector) {
}
