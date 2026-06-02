package app.zcat.infochat.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit5 tests for {@link EmbeddingResult}'s value semantics:
 * element-wise {@code equals}/{@code hashCode} and the defensive copies
 * that stop a caller mutating a stored vector.
 */
class EmbeddingResultTest {

    @Test
    void equalVectorsCompareEqual() {
        EmbeddingResult a = new EmbeddingResult(new float[] {1.0f, 2.0f, 3.0f});
        EmbeddingResult b = new EmbeddingResult(new float[] {1.0f, 2.0f, 3.0f});

        assertEquals(a, b, "results with element-wise-equal vectors must be equal");
        assertEquals(a.hashCode(), b.hashCode(), "equal results must share a hashCode");
    }

    @Test
    void differingVectorsCompareUnequal() {
        EmbeddingResult a = new EmbeddingResult(new float[] {1.0f, 2.0f, 3.0f});
        EmbeddingResult c = new EmbeddingResult(new float[] {1.0f, 2.0f, 4.0f});

        assertFalse(a.equals(c), "results with differing vectors must not be equal");
    }

    @Test
    void constructorDefensiveCopiesSource() {
        float[] source = {1.0f, 2.0f, 3.0f};
        EmbeddingResult result = new EmbeddingResult(source);

        source[0] = 99.0f;

        assertEquals(1.0f, result.vector()[0],
            "mutating the constructor argument must not change the stored vector");
    }

    @Test
    void accessorReturnsDefensiveCopy() {
        EmbeddingResult result = new EmbeddingResult(new float[] {1.0f, 2.0f, 3.0f});

        float[] first = result.vector();
        float[] second = result.vector();
        first[0] = 99.0f;

        assertNotSame(first, second, "accessor must hand back a fresh array each call");
        assertEquals(1.0f, second[0],
            "mutating a returned array must not change the stored vector");
        assertTrue(result.equals(new EmbeddingResult(new float[] {1.0f, 2.0f, 3.0f})),
            "the stored vector must survive accessor mutation");
    }
}
