package app.zcat.infochat.llm;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

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
 *
 * <p>The record has <em>value semantics</em>: two results holding
 * element-wise-equal vectors are {@code equals} (the compiler-generated
 * record {@code equals} would compare the array by reference identity,
 * leaving two identical embeddings unequal). The array is defensive-copied
 * on construction and on read so the record owns its vector and no caller
 * can mutate a stored embedding through the constructor argument or the
 * accessor return.</p>
 */
public record EmbeddingResult(float[] vector) {

    public EmbeddingResult {
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof EmbeddingResult other && Arrays.equals(vector, other.vector);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(vector);
    }
}
