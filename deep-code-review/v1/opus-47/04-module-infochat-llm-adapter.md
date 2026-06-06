# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-01 12:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [critical] SECURITY — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:139,142 — Outbound Anthropic header names are wrong (`x-anthropic-version` and `anthropic-api-key`); the real API requires `anthropic-version` and `x-api-key`, so every production call to Anthropic will be rejected (likely 401) and the operator-supplied key value is leaked into a non-canonical header that Anthropic ignores.
- [high] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java:133-134,154-157 — Auth-header test was written against the wrong header names, locking in the bug instead of catching it (test-integrity §8 "test was modified to match a new (wrong) behavior rather than the code being fixed to match the test").
- [high] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java:162-201 — `parseEmbeddings` violates the `EmbeddingProvider.embed` SPI contract ("size equals texts.size()") by returning a mismatched-size list with only a WARN log; the comment defers detection to the caller, but the SPI javadoc promises the contract.
- [high] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingResult.java:14 — Public record stores a mutable `float[]`; record-generated `equals()`/`hashCode()` use reference equality on the array, and any caller can mutate `vector()` in place — both are surprising on a public SPI value type.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:399 — Hidden "null" string sentinel (`s.toLowerCase().equals("null") → ""`) in `MicroProfileConfigReader.get` is undocumented; a future reader cannot tell whether the literal property value `null` is operator-facing semantics or a fossil.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:144-146,182 — Defensive null checks behind `@NonNull` parameters / on values that cannot be null (engineering rule §7).
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:109,134 — Public/protected constructors lack JSpecify `@NonNull`/`@Nullable` annotations on reference-type parameters (engineering rule §7a).
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:298-315 — `providerName(LlmProvider)` couples the router to every concrete impl via `instanceof` chains; every new provider edits the router (layering smell).
- [low] SIMPLIFICATION — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:24 — Unused `java.util.Optional` import.

## Detail

### F1. Anthropic auth/version headers use the wrong names

- **Category:** SECURITY
- **Severity:** critical
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:139,142

**Current code:**

```java
HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
    .timeout(Duration.ofMillis(cfg.timeoutMs()))
    .header("Content-Type", "application/json")
    .header("x-anthropic-version", API_VERSION)
    .POST(HttpRequest.BodyPublishers.ofString(body));
if (!cfg.apiKey().isEmpty()) {
    reqBuilder.header("anthropic-api-key", cfg.apiKey());
}
```

**Why this is wrong / suboptimal / risky:**

The Anthropic Messages API requires two specific header names, documented in their public reference:

- API version: `anthropic-version` (no `x-` prefix).
- API key: `x-api-key` (with `x-` prefix), carrying the raw key value.

The current code emits `x-anthropic-version` and `anthropic-api-key`, both of which Anthropic's gateway does not recognize. In production the call will be rejected as unauthenticated (HTTP 401), surfaced to the operator as a generic `LlmCallFailedException` with no hint that the header names are inverted. The operator-supplied secret is still transmitted, but to a header Anthropic discards — a minor secrets-exposure concern (the value is sent to Anthropic over TLS, so the practical leak surface is internal proxy / mirror logs that key off `Anthropic-api-key`).

The error has propagated from the M1-085 ticket body into the design handoff (`docs/plan/m1/drafts/handoff-tier3-D-anthropic-llm.md` lines 80-81, 194, 305) and into the test (see F2). Fixing it in code requires fixing it in the test in the same diff.

Reference: https://docs.anthropic.com/en/api/messages — request headers section. The Anthropic Java SDK and OpenRouter's Anthropic passthrough both use `x-api-key` + `anthropic-version`.

**Recommended fix:**

```java
HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
    .timeout(Duration.ofMillis(cfg.timeoutMs()))
    .header("Content-Type", "application/json")
    .header("anthropic-version", API_VERSION)
    .POST(HttpRequest.BodyPublishers.ofString(body));
if (!cfg.apiKey().isEmpty()) {
    reqBuilder.header("x-api-key", cfg.apiKey());
}
```

Also update the class javadoc at lines 50-52 ("`x-anthropic-version` carries ... `anthropic-api-key` carries ...") to the corrected names, and update the test (F2) in the same diff.

**Reasoning:**

This is the only way real Anthropic calls succeed. Without the fix, the AnthropicProvider is a non-functional impl — useful only to satisfy CDI bean discovery in the router and pass the local mock-server tests. The fix is one-line per header and has no behavioral consequence in any other code path.

**Trade-offs:**

None — the fix is strictly better.

---

### F2. Anthropic auth-header test asserts the wrong header names

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java:133-134,154-157

**Current code:**

```java
Map<String, List<String>> headers = capturedHeaders.get();
assertEquals("2023-06-01", headers.get("X-anthropic-version").get(0));
assertEquals(API_KEY, headers.get("Anthropic-api-key").get(0));
```

and

```java
assertFalse(headers.containsKey("Anthropic-api-key"),
    "anthropic-api-key header must be omitted when api-key config is empty");
assertNotNull(headers.get("X-anthropic-version"),
    "x-anthropic-version header must still be present");
```

**Why this is wrong / suboptimal / risky:**

The two `generateSendsAuthHeaders` / `generateOmitsApiKeyHeaderWhenEmpty` tests assert the exact header keys that the production code emits — but those keys are wrong (see F1). The test thus locks the bug in: any future change that fixes the production headers will fail this test, and a reader who runs the test green would conclude the integration is correct. This is exactly the §8 test-integrity pattern "A test was modified to match a new (wrong) behavior rather than the code being fixed to match the test."

The test is not weakened — it is precise — but it is pinned to the wrong specification. The fix is to align the assertions with Anthropic's documented header names. Both the test and the production code need to be fixed in the same diff so the test continues to enforce the contract.

**Recommended fix:**

```java
Map<String, List<String>> headers = capturedHeaders.get();
assertEquals("2023-06-01", headers.get("Anthropic-version").get(0));
assertEquals(API_KEY, headers.get("X-api-key").get(0));
```

and

```java
assertFalse(headers.containsKey("X-api-key"),
    "x-api-key header must be omitted when api-key config is empty");
assertNotNull(headers.get("Anthropic-version"),
    "anthropic-version header must still be present");
```

(Note: `com.sun.net.httpserver.Headers` normalizes header names by capitalizing the first letter — hence `Anthropic-version` and `X-api-key` in the lookup keys, mirroring the test's existing `X-anthropic-version` / `Anthropic-api-key` casing.)

**Reasoning:**

Aligning the test with the real Anthropic contract turns it into a regression guard against any future drift back to the broken header names. The fix carries an explicit authorization in the same diff as F1's code change — both code and test move together against a documented external contract.

**Trade-offs:**

None — the fix is strictly better.

---

### F3. Embedding provider silently breaks the SPI's size-equals-input contract

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java:162-201

**Current code:**

```java
List<EmbeddingResult> results = new ArrayList<>(data.size());
for (int i = 0; i < data.size(); i++) {
    JsonNode embedding = data.get(i).path("embedding");
    if (!embedding.isArray()) {
        throw new EmbeddingCallFailedException(
            "OpenAiCompatibleEmbeddingProvider: data[" + i + "].embedding missing or not array from "
                + uri + "; preview: " + preview(responseBody));
    }
    float[] vector = new float[embedding.size()];
    for (int j = 0; j < embedding.size(); j++) {
        vector[j] = (float) embedding.get(j).asDouble();
    }
    results.add(new EmbeddingResult(vector));
}
// The caller's wrong-shape detection compares results.size()
// to expectedCount and treats divergence as a batch failure
// (one-failure-fails-batch retry). Surfacing the divergence
// here as a log line aids operator triage when the provider
// silently truncates a batch reply.
if (results.size() != expectedCount) {
    LOG.warnf(
        "OpenAiCompatibleEmbeddingProvider: response shape mismatch from %s — expected %d embeddings, got %d",
        uri, expectedCount, results.size());
}
return results;
```

The `EmbeddingProvider.embed` SPI javadoc explicitly states (`EmbeddingProvider.java:32`):

```
@return one {@link EmbeddingResult} per input, in input order.
        Never null; size equals {@code texts.size()}.
```

**Why this is wrong / suboptimal / risky:**

The impl returns a wrong-size list when the provider returns fewer (or more) elements than requested. The SPI contract — written immediately above the implementation — promises the size invariant. The impl violates it and points at the caller to "detect" the violation.

Two concrete consequences:

1. A caller that trusts the SPI contract (legitimate reading of the javadoc) will index-zip the result with the input list and silently mis-attribute vectors to the wrong posts. With the current behavior the mis-attribution survives even after one retry succeeds with a "correct" wrong-sized response — there is no per-element error signal.
2. The spec at `docs/spec/llm.md` §Embedding pipeline mandates "one-failure-fails-batch retry … the entire batch retries once. If retry also fails, every post in the batch follows the embedding-failure release path." Detection lives at the SPI boundary by spec — not at every caller. The current impl pushes the detection into every caller of the SPI, multiplying the surface area for bugs.

A SECURITY angle exists too: silently mis-attributing post-A's vector to post-B leaks one user-scoped post's semantic features into another's similarity graph. The risk is low (post bodies are public ingest content, and mis-attributed vectors degrade rather than poison retrieval), but the bug is silent.

**Recommended fix:**

```java
List<EmbeddingResult> results = new ArrayList<>(data.size());
for (int i = 0; i < data.size(); i++) {
    JsonNode embedding = data.get(i).path("embedding");
    if (!embedding.isArray()) {
        throw new EmbeddingCallFailedException(
            "OpenAiCompatibleEmbeddingProvider: data[" + i + "].embedding missing or not array from "
                + uri + "; preview: " + preview(responseBody));
    }
    float[] vector = new float[embedding.size()];
    for (int j = 0; j < embedding.size(); j++) {
        vector[j] = (float) embedding.get(j).asDouble();
    }
    results.add(new EmbeddingResult(vector));
}
if (results.size() != expectedCount) {
    throw new EmbeddingCallFailedException(
        "OpenAiCompatibleEmbeddingProvider: response shape mismatch from " + uri
            + " — expected " + expectedCount + " embeddings, got " + results.size());
}
return results;
```

**Reasoning:**

The SPI's contract — "size equals texts.size()" — is the load-bearing invariant for the rest of the pipeline. Throwing on violation collapses the failure into the existing exception flow that the EmbeddingWorker already retries-once-then-releases-without-vector. The behavior the spec promises is preserved; the contract the SPI promises is preserved; callers no longer need to re-validate the shape.

**Trade-offs:**

A provider that returns truncated-but-non-zero data on a partial failure now triggers a full batch retry instead of partial credit. This matches what the spec already mandates ("entire batch retries once") and is what the comment above the WARN already says the caller does — moving the throw upstream just enforces it.

---

### F4. `EmbeddingResult` exposes a mutable array via a record value type

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingResult.java:14

**Current code:**

```java
public record EmbeddingResult(float[] vector) {
}
```

**Why this is wrong / suboptimal / risky:**

Two distinct problems flow from putting a Java array in a public record component:

1. **Auto-generated `equals()` and `hashCode()` use array reference equality**, not deep content equality. Two `EmbeddingResult` records holding `float[] {1.0f, 2.0f}` are NOT equal, and their hash codes differ. Any test that uses `assertEquals` on two embeddings will quietly always fail; any future code that inserts these into a `Set` or uses them as `Map` keys silently mis-behaves.
2. **The accessor `vector()` returns the live array reference.** Any caller can write `result.vector()[0] = 0f` and mutate the value held by the record — and anyone else holding the same reference observes the mutation. This is a recipe for cross-thread corruption when an EmbeddingResult is cached or shared across the eval pipeline and the linker.

Both are textbook record-with-array hazards. The wrapper exists precisely to give the API a stable value type; both invariants a value type promises (deep equality, immutability) are broken on this one.

**Recommended fix:**

The cleanest fix is to drop the wrapper and just expose `float[]` directly (the EmbeddingResult javadoc itself notes that "A bare `float[]` would also meet the spec; the wrapper costs one record now and decouples cross-call-site signatures from any later additive change."). The future-expansion justification can be re-introduced when there is a real second field to add — at that point the type can be a record-with-defensive-copy or a record-of-DenseVector with the right semantics.

```java
// EmbeddingProvider.java
List<float[]> embed(@NonNull List<String> texts);
```

Delete `EmbeddingResult.java`.

Alternatively, if the wrapper is kept for forward-compatibility, give it the right semantics:

```java
public record EmbeddingResult(float[] vector) {
    public EmbeddingResult {
        // Defensive copy on construction so callers cannot mutate
        // the held array post hoc.
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        // Defensive copy on read so callers cannot mutate the held
        // array through the accessor.
        return vector.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EmbeddingResult r && Arrays.equals(this.vector, r.vector);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(vector);
    }
}
```

**Reasoning:**

For a v1 SPI with one known producer and one known consumer, the bare `float[]` is honest about what is being passed; the wrapper costs a public class while delivering broken equality and shared-reference mutability. Dropping the wrapper is the simplification the spec already hints at. If the wrapper is kept, it must override `equals`/`hashCode` and defensive-copy on construction and accessor — otherwise it is worse than the bare array.

**Trade-offs:**

- **Drop the wrapper:** every existing call site of `EmbeddingResult.vector()` becomes a direct `float[]` access. Future expansion (per-element metadata, etc.) needs a new type — but adding it then is a small diff, and the working assumption that it WILL be needed is unverified.
- **Override equals/hashCode/accessor:** two extra `clone()` calls per result (one on construction, one on every read). For batch-of-N embeddings of dimension D, that's `2 * N * D * 4 bytes` of extra allocation per call. At D=768 (laptop profile) and N=64 (vps batch), that's ~400 KiB per batch — measurable but small relative to the JSON parse cost.

**Alternative options:**

- **Option A** — Drop the wrapper; expose `List<float[]>` from the SPI.
- **Option B** — Keep the wrapper with `clone()` on construction + accessor + `Arrays.equals/hashCode` overrides.

Option A is simpler and matches the SPI's actual v1 needs.

---

### F5. Hidden "null" string sentinel in `MicroProfileConfigReader.get`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:395-400

**Current code:**

```java
@Override
public Optional<String> get(@NonNull String key) {
    return delegate.getOptionalValue(key, String.class)
        .map(s -> s.trim())
        .map(s -> s.toLowerCase(Locale.ROOT).equals("null") ? "" : s);
}
```

**Why this is wrong / suboptimal / risky:**

The third `.map` lambda silently converts any operator-supplied value whose lowercased form is the literal string `"null"` to an empty string. There is no comment explaining why, no spec or design-note reference, and the test suite does not exercise the branch. A future reader (or anyone reviewing the next time an operator's config behaves oddly) has no way to tell whether:

- this is intentional public semantics (set `provider=null` to mean "unconfigured"),
- this is a workaround for some Quarkus / SmallRye config behavior (e.g., a YAML value of `null` being injected as the literal string),
- or this is fossil code from a deleted use case.

The Coding-style guide explicitly says new code with hidden invariants must be commented — this is one. The fact that the rule lives in CLAUDE.md §"Comment important, crucial, or complex code" makes this a maintainability-drift finding.

The behavior also has a subtle bug: `s.toLowerCase(Locale.ROOT).equals("null")` only matches the exact 4-character string. An operator who writes `provider=NULL ` (with trailing space) is rescued by the prior `.trim()`; an operator who writes `provider=Null\n` is also rescued; but `provider=null;` is NOT — making the sentinel format-fragile.

**Recommended fix:**

Either delete the sentinel (if it has no real purpose), or document and bound it:

```java
@Override
public Optional<String> get(@NonNull String key) {
    // SmallRye Config returns empty Optional for an unset key, but a
    // YAML value of `null` deserializes as the literal four-character
    // string "null" rather than absent. Treat that as unset so an
    // operator who removes a value by setting it to YAML null gets
    // the same router behavior as removing the key entirely.
    return delegate.getOptionalValue(key, String.class)
        .map(String::trim)
        .map(s -> s.equalsIgnoreCase("null") ? "" : s);
}
```

If the YAML-null hypothesis is wrong, delete the line outright.

**Reasoning:**

A WHY-comment makes the invariant inspectable. Using `equalsIgnoreCase` avoids the redundant `toLowerCase` allocation per call and is the canonical Java idiom. Method-reference `String::trim` is one fewer lambda.

**Trade-offs:**

None — the fix is strictly better. (The behavior is unchanged; only the comment + idiom is updated.)

---

### F6. Defensive null checks inside internal trust boundary

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:144-146 and OpenAiCompatibleProvider.java:182

**Current code:**

`LlmRouter.forTask`:

```java
public LlmProvider forTask(@NonNull ModelTask task, @Nullable String scopeLanguage) {
    if (task == null) {
        throw new IllegalArgumentException("LlmRouter.forTask: task must be non-null");
    }
    ...
}
```

`OpenAiCompatibleProvider.doCall`:

```java
if (cfg.apiKey() != null && !cfg.apiKey().isEmpty()) {
    reqBuilder.header("Authorization", "Bearer " + cfg.apiKey());
}
```

`cfg.apiKey()` is produced at line 147 by `securityApiKey.orElse("")` — it cannot be null.

**Why this is wrong / suboptimal / risky:**

Engineering rule §7 "No defensive code for impossible scenarios":

> Don't add error handling, fallbacks, or validation for scenarios that cannot happen given the trust boundary the code lives in. Inside those boundaries, internal code calling internal code is trusted: no null-checks for parameters that callers cannot legally pass null for ...

`LlmRouter.forTask` is called from internal Java code (Stage 2 worker, embedding worker, etc.) — none of which can legally pass `null` for `task` (the parameter is already `@NonNull`-annotated, which is the §7a contract). The `task == null` branch is dead-on-arrival.

`OpenAiCompatibleProvider`'s `cfg.apiKey() != null` is unreachable for the same reason — the value is `securityApiKey.orElse("")`, where `Optional.orElse("")` cannot return null.

Both checks are noise that distracts from the real validation (the `@NonNull` annotation in the router's case, the `Optional.orElse("")` in the provider's case).

**Recommended fix:**

`LlmRouter.forTask`:

```java
public LlmProvider forTask(@NonNull ModelTask task, @Nullable String scopeLanguage) {
    String lang = scopeLanguage == null ? "en" : scopeLanguage.toLowerCase(Locale.ROOT);
    ...
}
```

`OpenAiCompatibleProvider.doCall`:

```java
if (!cfg.apiKey().isEmpty()) {
    reqBuilder.header("Authorization", "Bearer " + cfg.apiKey());
}
```

(This matches `AnthropicProvider.java:141`'s shape, which is already correct.)

**Reasoning:**

The contract is the `@NonNull` annotation; trust it. The `Optional.orElse("")` already guarantees non-null; trust it. Deleting the dead branch reduces line count and makes the trust boundary visible.

**Trade-offs:**

None — the fix is strictly better.

---

### F7. Public constructors missing JSpecify nullability annotations

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:109 and 134

**Current code:**

```java
public LlmRouter(List<Entry> entries, ConfigReader config) {
    if (entries == null || entries.isEmpty()) {
        ...
```

and

```java
@Inject
public LlmRouter(Instance<LlmProvider> providers, Config mpConfig) {
    this(buildFromCdi(providers, mpConfig), new MicroProfileConfigReader(mpConfig));
}
```

**Why this is wrong / suboptimal / risky:**

Engineering rule §7a "Method parameter contracts":

> Every reference-type parameter on a public method declares nullability — either via annotation (`@NonNull`/`@Nullable` from `org.jspecify.annotations`) or via javadoc `@param`. Public/protected methods MUST annotate ...

Both constructors are public. Neither parameter carries `@NonNull` / `@Nullable`. The test-friendly constructor's defensive `null` check (which is at a system-boundary-like seam — the test seam) at least surfaces the intent at runtime, but the §7a contract requires the static annotation as well so the reviewer-time check and callers reading the signature see the same answer.

The pattern is correctly applied on `Entry`, `ConfigReader.get`, and `LlmProvider.generate` — the constructors are an oversight.

**Recommended fix:**

```java
public LlmRouter(@NonNull List<Entry> entries, @NonNull ConfigReader config) {
    ...
}

@Inject
public LlmRouter(@NonNull Instance<LlmProvider> providers, @NonNull Config mpConfig) {
    ...
}
```

The runtime `null` check in the test seam constructor is acceptable at the test-seam trust boundary (a hand-rolled test caller is the system boundary here), so leave it; just add the annotation so the static contract is explicit.

**Reasoning:**

The annotation makes the contract visible to lint (`scripts/lint-contracts.py`) and to anyone reading the signature without opening the body. Once added, the runtime check can be removed from the inject constructor's delegate path (the CDI container never passes null to an `@Inject` constructor with a `@NonNull` parameter).

**Trade-offs:**

None — the fix is strictly better.

---

### F8. Router tightly coupled to concrete provider impls via `instanceof` chain

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:298-315

**Current code:**

```java
private static String providerName(LlmProvider p) {
    if (p instanceof OpenAiCompatibleProvider) {
        return OpenAiCompatibleProvider.PROVIDER_NAME;
    }
    if (p instanceof AnthropicProvider) {
        return AnthropicProvider.PROVIDER_NAME;
    }
    // CDI client proxies are subclasses whose name carries a
    // framework suffix (e.g. _ClientProxy). Walk up to the
    // developer-authored class so the operator-facing config key
    // is stable across framework versions.
    Class<?> cls = p.getClass();
    while (cls.getSimpleName().contains("_") && cls.getSuperclass() != null
            && LlmProvider.class.isAssignableFrom(cls.getSuperclass())) {
        cls = cls.getSuperclass();
    }
    return cls.getSimpleName();
}
```

**Why this is wrong / suboptimal / risky:**

The router knows the names of every concrete impl by `instanceof`. Every new impl ticket (M1-085 added one branch, the next provider adds another) modifies the router. The SPI is supposed to be the contract; right now the router is part of the contract too.

Three concrete drawbacks:

1. The router cannot be packaged independently of the impls — it imports both `OpenAiCompatibleProvider` and `AnthropicProvider` at compile time. A consumer module that wants only the OpenAI-compatible provider on its classpath still drags Anthropic-specific symbols (today benign; tomorrow a hard dependency).
2. The router's authoritative name registry is split across the impls' `PROVIDER_NAME` constants and the router's `instanceof` cascade. To register a name an impl author edits two files.
3. The fall-through clause walks the class hierarchy looking for `_`-suffix-stripped framework proxies. That heuristic is fragile (Quarkus ArC's proxy naming convention is not a public contract) and is the only path test stubs reach — meaning real impls and test stubs take different code paths to the same answer.

The cleanest move is to let each impl declare its own name via the SPI. Since the SPI is frozen per M1-007b, the next-best move is to read the name from a CDI qualifier or `@Named` annotation, or to expose a small `LlmProvider.name()` default method.

**Recommended fix:**

Add a default method to the SPI (preferred):

```java
// LlmProvider.java
public interface LlmProvider {
    LlmResponse generate(@NonNull ModelTask task, @NonNull String systemPrompt, @NonNull String userPrompt);

    /**
     * Stable operator-facing name. The router uses this to match
     * per-task override properties (e.g.
     * {@code infochat.llm.security.provider=anthropic}).
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
```

Each impl overrides with its `PROVIDER_NAME` constant:

```java
@Override public String name() { return PROVIDER_NAME; }
```

Then `LlmRouter.providerName` becomes:

```java
private static String providerName(LlmProvider p) {
    return p.name();
}
```

If the M1-007b SPI freeze is unbreakable, a thin annotation (`@ProviderName("anthropic")` on the impl class) read by the router via reflection is the same shape with one fewer SPI-method change.

**Reasoning:**

Names move from the router to the impls, where the rest of the impl-specific identity already lives. The router becomes oblivious to which impls exist, which is what an SPI router should be. The CDI-proxy heuristic disappears because impls declare their own names. The test-stub path collapses to the same path as production.

**Trade-offs:**

A one-method addition to the SPI (which is what M1-007b's freeze rule guards against). If the freeze is binding, use the annotation-based variant instead — both fixes remove the `instanceof` chain.

**Alternative options:**

- **Option A** — Default method on `LlmProvider`; each impl overrides.
- **Option B** — `@ProviderName("...")` annotation; router reads via reflection. Pros: no SPI surface change. Cons: reflection at startup; one indirection more.

---

### F9. Unused `Optional` import

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:24

**Current code:**

```java
import java.util.Optional;
```

**Why this is wrong / suboptimal / risky:**

`Optional` is not used as a type anywhere in `AnthropicProvider` — `Config.getOptionalValue(...)` returns an `Optional` but the result is immediately collapsed with `.orElse(...)`. The import is dead. Most IDEs flag this on save; leaving it in suggests the file was edited without running the toolchain that would clean it.

§1 surgical-changes prohibits cleaning up *pre-existing* dead imports, but this import was added with the file and the file itself is brand new (M1-085 / M1-120), so cleaning the file's own unused import is fair game.

**Recommended fix:**

Delete the line.

**Reasoning:**

Removes a dead import. No other change.

**Trade-offs:**

None — the fix is strictly better.

---

## Synthesizer-relevant observations

- The spec at `docs/spec/llm.md` §Per-task routing rules commits: "Switching the embedding provider to a remote service emits an explicit confirmation log line on startup so operators see when post bodies start leaving the host." No code in this module emits such a startup log line for the embedding provider (`OpenAiCompatibleEmbeddingProvider` reads `infochat.embeddings.base-url` but never logs whether it is remote). `LlmRouterStartupGuard` logs only the local-only enforcement; the remote-switch confirmation is missing. This is a cross-module spec-drift observation (the log could live in the embedding provider, in a new startup guard, or in the existing `LlmRouterStartupGuard`); flagging here so the architecture pass can assign ownership.
- `OpenAiCompatibleProvider.configFor` throws `UnsupportedOperationException` for every `ModelTask` other than `SECURITY_JUDGE`. The `LlmRouter` will happily resolve this provider for any task (it is the v1 default). A misconfigured router that sends `TAGGER` calls through `OpenAiCompatibleProvider` blows up at the call site rather than at startup. This is consistent with M1-033's ticket-by-ticket landing but creates an architecture-level "router can return a provider that cannot serve the requested task" surface — flagging for the architecture pass.
