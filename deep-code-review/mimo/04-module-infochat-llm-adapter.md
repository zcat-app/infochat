# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-01 23:55
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — EmbeddingResult.java:14 — Mutable `float[]` exposed directly via record accessor; violates the SPI's implicit immutability contract for the vector carrier.
- [low] MAINTAINABILITY-RULES-DRIFT — AnthropicProvider.java:201 — `catch (Exception ignored)` silently swallows parsing failures in `extractErrorMessage`, contrary to §7 test-integrity rule against silent-swallow blocks.

## Detail

### F1. EmbeddingResult exposes mutable float array without defensive copy

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** EmbeddingResult.java:14

**Current code:**

```java
public record EmbeddingResult(float[] vector) {
}
```

**Why this is wrong / suboptimal / risky:**

A Java record's canonical constructor stores its component reference directly, and its accessor returns that same reference. Two independent paths expose the mutable `float[]`:

1. `new EmbeddingResult(someArray)` — the caller retains the array reference and can mutate it after construction.
2. `result.vector()` — the consumer receives the same mutable array and can mutate it.

The javadoc states "v1 commits only to the dense vector itself," implying the vector is the stable, immutable value of the record. But the array is mutable, so any consumer holding a reference to the `EmbeddingResult` can silently corrupt the vector after construction. This is a data-integrity risk in any path where the result is cached, stored in a collection, or passed between pipeline stages. The spec's embedding-pipeline section (docs/spec/llm.md) describes embeddings as durable artifacts stored in the database and used for cosine-similarity scoring; a corrupted vector would produce wrong similarity scores.

The record also has mutable-state semantics for `equals`/`hashCode` — while Java records delegate to `java.util.Arrays` for array components (so two records with equal-content arrays compare as equal), mutating the array after the record participates in a `HashSet` or `HashMap` breaks the hash contract.

**Recommended fix:**

```java
public record EmbeddingResult(float[] vector) {
    /** Defensive copy: the record owns its vector immutably. */
    public EmbeddingResult {
        vector = vector.clone();
    }
}
```

**Reasoning:**

A compact canonical constructor clones the array on construction, so the record's internal state is decoupled from the caller's array. The accessor `vector()` still returns the internal copy directly, but since no external code holds a reference to that specific array instance, mutation requires deliberate (and obviously wrong) `result.vector()[i] = ...` by the consumer — a much smaller attack surface than the current "caller and consumer share the same mutable reference."

The cost is one array copy per `EmbeddingResult`. For a 768-dimensional vector (`nomic-embed-text`), that is 3,072 bytes — negligible compared to the HTTP round-trip to the embedding provider. The batch SPI (`List<String> -> List<EmbeddingResult>`) processes at most a few hundred texts per batch; even at 768 dimensions and 500 texts, the total copy cost is ~1.5 MB, which is trivial on JDK 25.

**Trade-offs:**

One extra `float[]` allocation per embedding result. At the batch sizes and dimensions in play (768 or 1536 floats), this is not measurable against the HTTP call cost. The fix is strictly better for correctness; the performance cost is negligible.

---

### F2. AnthropicProvider.extractErrorMessage silently swallows Exception

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** AnthropicProvider.java:201

**Current code:**

```java
private static String extractErrorMessage(String body) {
    try {
        JsonNode root = JSON.readTree(body);
        if ("error".equals(root.path("type").asText())) {
            return root.path("error").path("message").asText("(no message)");
        }
    } catch (Exception ignored) {
        // Fall through to preview
    }
    return preview(body);
}
```

**Why this is wrong / suboptimal / risky:**

Engineering rules §7 (test-integrity rules, syntactic) and the §8 production-code rule state: "New `catch (Exception ignored) {}` or silent-swallow blocks in production code" are forbidden patterns. While this is in an error-path diagnostic method (parsing an HTTP error body for a human-readable message), the blanket `catch (Exception ignored)` swallows all exceptions — including `NullPointerException` from unexpected null returns, `ClassCastException` from type mismatches, or any programming error. The fallback to `preview(body)` silently masks these, making debugging harder when the error-parsing logic itself is wrong.

The only expected failure here is `IOException` from Jackson's `readTree` on malformed JSON. Catching that specific type (and optionally `IllegalArgumentException` for edge cases) would be precise and safe.

**Recommended fix:**

```java
private static String extractErrorMessage(String body) {
    try {
        JsonNode root = JSON.readTree(body);
        if ("error".equals(root.path("type").asText())) {
            return root.path("error").path("message").asText("(no message)");
        }
    } catch (IOException e) {
        LOG.debugf("AnthropicProvider: could not parse error body as JSON: %s", e.getMessage());
    }
    return preview(body);
}
```

**Reasoning:**

Narrowing the catch to `IOException` (the only exception Jackson's `readTree` declares) makes the error handling precise. Adding a `debug`-level log line preserves the diagnostic signal that the blanket catch currently loses. The `preview(body)` fallback still applies for non-JSON error responses, which is the intended behavior.

**Trade-offs:**

If the Anthropic API ever returns a JSON structure that causes Jackson to throw something other than `IOException` (e.g., a deeply nested structure causing `StackOverflowError`), the narrower catch would propagate it instead of falling through. This is arguably the correct behavior — such a case would be a bug worth surfacing, not silently swallowing.
