# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-08 17:30
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — LlmRouter.java:139-141 — `forTask` null-checks a non-`@Nullable` parameter, a defensive check between internal classes (§7 violation).
- [low] SIMPLIFICATION — AnthropicProvider.java:113-184 / OpenAiCompatibleProvider.java:154-224 — the two HTTP providers duplicate config-read, request-build, send, status-check, and error-wrap plumbing nearly verbatim.
- [low] MAINTAINABILITY-RULES-DRIFT — LlmHttpSupport.java:45 — `DEFAULT_BODY_CAP_BYTES` Javadoc claims it is mirrored as `"8388608"` in each provider's `@ConfigProperty`, but two of three providers read the cap dynamically and never reference that literal.

## Detail

### F1. `forTask` null-checks a parameter that the null-marked contract forbids being null

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** LlmRouter.java:138-141

**Current code:**

```java
public LlmProvider forTask(ModelTask task, @Nullable String scopeLanguage) {
    if (task == null) {
        throw new IllegalArgumentException("LlmRouter.forTask: task must be non-null");
    }
    String lang = scopeLanguage == null ? "en" : scopeLanguage.toLowerCase(Locale.ROOT);
```

**Why this is wrong / suboptimal / risky:**

`task` is a bare `ModelTask` reference. Every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`, decision D48, engineering-rules §7a), so a bare reference type means "never null" and NullAway fails the build on any call site that passes a nullable value. The contract is already machine-enforced; passing `null` for `task` is a compile-time error, not a runtime possibility.

`forTask` lives entirely inside the trust boundary — its callers are `assertAllTasksResolve` (same class) and the collector's Stage 2 / digest call sites (internal code calling internal code). Engineering-rules §7 forbids "null-checks for parameters that callers cannot legally pass null for" between two internal classes. The check is dead: NullAway guarantees the branch is unreachable, so it can never throw and only adds noise. Note the contrast with `scopeLanguage`, which is correctly `@Nullable` and correctly handled by the ternary on the next line — that is the right pattern; the `task` check is the wrong one.

**Recommended fix:**

```java
public LlmProvider forTask(ModelTask task, @Nullable String scopeLanguage) {
    String lang = scopeLanguage == null ? "en" : scopeLanguage.toLowerCase(Locale.ROOT);
```

**Reasoning:**

Removing the guard aligns the method with the null-marked contract the rest of the module relies on. The signature already documents "task is never null"; the build proves it. Deleting the check removes three lines of unreachable code and removes the implicit (false) suggestion to a reader that `null` is a value this method is prepared to receive.

**Trade-offs:**

None — the fix is strictly better. The check cannot fire under the enforced contract, so no behavior is lost.

---

### F2. The two HTTP `LlmProvider` impls duplicate the full call pipeline

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** AnthropicProvider.java:113-184 and OpenAiCompatibleProvider.java:154-224 (also the duplicated `configFor`, `LlmCallFailedException` catch arms, and body-cap read across both)

**Current code:**

`OpenAiCompatibleProvider.configFor` (line 154):

```java
private TaskConfig configFor(ModelTask task) {
    String prefix = "infochat.llm." + task.keySegment() + ".";
    String baseUrl = config.getValue(prefix + "base-url", String.class);
    String apiKey = config.getOptionalValue(prefix + "api-key", String.class).orElse("");
    String model = config.getValue(prefix + "model", String.class);
    long timeoutMs = config.getOptionalValue(prefix + "timeout-ms", Long.class).orElse(30000L);
    return new TaskConfig(baseUrl, apiKey, model, timeoutMs);
}
```

`AnthropicProvider.configFor` (line 113) is byte-identical except for the extra `max-tokens` line. The send / status-check / cap-read blocks (`OpenAiCompatibleProvider` lines 199-223, `AnthropicProvider` lines 159-183) differ only in the exception class name and the URI path segment.

**Why this is wrong / suboptimal / risky:**

CLAUDE.md §Coding style "Simplify aggressively" and §"No workarounds" both push against carrying near-identical plumbing in two places. The duplication is not merely cosmetic: the body-cap read, the `[1 MiB, 8 MiB]` clamp, the `non-2xx → warn + throw` shape, and the I/O-vs-interrupt catch arms are a security/robustness contract that must stay identical across providers, and right now nothing enforces that. A future change to the cap-read (e.g. a per-task override key) has to be applied twice and can silently drift. The shared `LlmHttpSupport` already exists as the natural home for this; only the wire-body assembly and response-text extraction are genuinely provider-specific.

**Recommended fix:**

Hoist the common pipeline into `LlmHttpSupport` (or a package-private base) parameterized by the two provider-specific steps — request-body JSON and response-text extraction:

```java
// in LlmHttpSupport
static LlmResponse call(HttpClient http, Config config, URI uri, String body,
                        long timeoutMs, @Nullable String apiKey, String authHeaderName,
                        String authValuePrefix, Function<HttpResponse<String>, LlmResponse> parse,
                        BiFunction<String, Throwable, RuntimeException> wrap) {
    long cap = clampBodyCapBytes(config.getOptionalValue("infochat.llm.max-response-bytes", Long.class)
        .orElse(DEFAULT_BODY_CAP_BYTES));
    HttpRequest.Builder b = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (apiKey != null && !apiKey.isEmpty()) {
        b.header(authHeaderName, authValuePrefix + apiKey);
    }
    // ... send + status check + wrap, once
}
```

Each provider keeps only `configFor`, the body assembly, and the response parser.

**Reasoning:**

This collapses the duplicated send/clamp/catch logic to one site so the response-cap and failure-surface contract cannot diverge between providers, while the genuinely provider-specific code (Anthropic's `system[]`/`cache_control`/`max_tokens` shape vs OpenAI's `messages[]` shape, and the two response paths) stays separate. It matches the module's existing pattern of putting shared HTTP hygiene in `LlmHttpSupport`.

**Trade-offs:**

The shared method needs the two function parameters (body builder, response parser) and the auth-header name/prefix, which is some indirection. With only two providers today the payoff is modest, which is why this is low and not higher. If a third HTTP provider lands, the value rises; if the plan is that future providers move to LangChain4j-backed impls instead (per the pom comment), the duplication may never grow and leaving it is defensible.

**Alternative options:**

- **Option A** (the recommended fix above) — shared method in `LlmHttpSupport`.
- **Option B** — leave as-is and accept the duplication, relying on the two impls being touched together. Pros: zero churn, no new indirection. Cons: the cap/clamp/failure contract can drift silently; "simplify aggressively" is not served.

---

### F3. `DEFAULT_BODY_CAP_BYTES` Javadoc describes a mirroring that two of three providers do not do

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** LlmHttpSupport.java:37-45

**Current code:**

```java
/**
 * Default cap when the operator does not configure one. The most
 * permissive value in the allowed range ... Mirrored as the literal
 * {@code "8388608"} in each provider's {@code @ConfigProperty}
 * default (annotation arguments must be compile-time constants).
 */
static final long DEFAULT_BODY_CAP_BYTES = MAX_BODY_CAP_BYTES;
```

**Why this is wrong / suboptimal / risky:**

The comment asserts the literal `"8388608"` is mirrored "in each provider's `@ConfigProperty` default." That is only true of `OpenAiCompatibleEmbeddingProvider` (the one impl that uses a `@ConfigProperty` field, line 107). `OpenAiCompatibleProvider` (line 200) and `AnthropicProvider` (line 160) read the cap dynamically via `config.getOptionalValue(...).orElse(LlmHttpSupport.DEFAULT_BODY_CAP_BYTES)` and reference the constant directly — they have no `@ConfigProperty` default and no `"8388608"` literal. CLAUDE.md §Coding style "WHY-not-WHAT … don't narrate code that named identifiers already explain" and the engineering bar for accurate why-comments both apply: a comment that mis-describes where a value is duplicated will mislead a reader trying to find every place the default is set, and rots further as providers are added or changed.

**Recommended fix:**

```java
/**
 * Default cap when the operator does not configure one. The most
 * permissive value in the allowed range — large enough not to
 * truncate a legitimate batch-embedding reply, still bounded so a
 * runaway response cannot exhaust the heap. The HTTP LlmProviders
 * reference this constant directly via getOptionalValue(...).orElse(...);
 * OpenAiCompatibleEmbeddingProvider mirrors the literal "8388608" in
 * its @ConfigProperty default because annotation arguments must be
 * compile-time constants.
 */
static final long DEFAULT_BODY_CAP_BYTES = MAX_BODY_CAP_BYTES;
```

**Reasoning:**

The corrected comment names the one place the literal is unavoidably duplicated (the embedding provider's annotation default) and is explicit that the other two providers reference the constant, so a reader auditing the cap surface knows exactly what to grep for.

**Trade-offs:**

None — the fix is strictly better; it only corrects a comment.

---

## Synthesizer-relevant observations

- The SPI surface (`LlmProvider`, `EmbeddingProvider`, `ModelTask`, `LlmResponse`, `EmbeddingResult`) is well-shaped and matches docs/spec/llm.md §SPI shape: the embedder is correctly kept off the `ModelTask` enum, `forTask` returns exactly one provider with no fallback chain, the determinism boundary is respected (the module does no retrieval), and prompt-injection wrapping is present in every untrusted-content prompt template with a per-call random delimiter id. No security or determinism-boundary finding.
- API keys are correctly never logged: exception messages carry only the URI, and `preview` caps response-body log inclusion at 200 chars. The `Authorization`/`x-api-key` headers are set but never echoed. No SECURITY finding.
- Cross-module: whether the collector's Stage 2 / embedding workers actually honor the "retry-once-then-fallback" and "one-failure-fails-batch" contracts these impls assume (per their Javadoc and docs/spec/llm.md §Failure handling) is not verifiable from inside this module — the workers live in infochat-collector. That contract check belongs to the architecture lens. The impls themselves throw the uniform exception types those contracts require.
