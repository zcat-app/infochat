# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-01 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — LlmRouterStartupGuard.java:183-204 — the local-only guard only inspects per-task `base-url` keys and ignores both the embedding provider's `base-url` and the per-task `provider`/`default.provider` name, so a `local-only=true` deployment can still ship post bodies to a remote endpoint with no startup failure.
- [medium] MAINTAINABILITY-RULES-DRIFT — AnthropicProvider.java:201 — `catch (Exception ignored)` in `extractErrorMessage` is a silent-swallow block.
- [medium] SIMPLIFICATION — LlmRouter.java:110-116, 359-363 — defensive null-checks on internal-only constructor/record parameters violate §7 (no defensive code inside the trust boundary).
- [low] MAINTAINABILITY-RULES-DRIFT — OpenAiCompatibleProvider.java:182 — redundant `cfg.apiKey() != null` check on a field that `configFor` already coalesces to non-null.
- [low] SIMPLIFICATION — AnthropicProvider.java:209-218 — `taskKeySegment` is duplicated across three files; the comment admits the duplication but the chosen seam is avoidable.

## Detail

### F1. local-only startup guard misses the embedding endpoint and provider-name overrides

- **Category:** SECURITY
- **Severity:** high
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java:96-103, 183-204

**Current code:**

```java
private static final Map<ModelTask, String> PER_TASK_BASE_URL_KEYS = Map.of(
    ModelTask.SECURITY_JUDGE, "infochat.llm.security.base-url",
    ModelTask.TAGGER, "infochat.llm.tagger.base-url",
    ModelTask.ENTITY, "infochat.llm.entity.base-url",
    ModelTask.SUMMARIZER, "infochat.llm.summarizer.base-url",
    ModelTask.CHAT_AGENT, "infochat.llm.chat.base-url",
    ModelTask.TRANSLATOR, "infochat.llm.translator.base-url"
);
```

```java
private static boolean isLoopback(String baseUrl) {
    URI uri;
    try {
        uri = new URI(baseUrl);
    } catch (URISyntaxException e) {
        ...
        return false;
    }
    String host = uri.getHost();
    ...
    InetAddress addr = InetAddress.getByName(host);
    return addr.isLoopbackAddress();
}
```

**Why this is wrong / suboptimal / risky:**

The spec makes local-only a hard data-leakage commitment: "The local-only posture is a privacy and data-leakage commitment (post bodies must not leave the host); silently letting a per-task override bypass it would defeat the commitment without operator notice" (`docs/spec/llm.md` §Per-task routing rules). The same section explicitly names the embedding boundary: "Switching the embedding provider to a remote service emits an explicit confirmation log line on startup so operators see when post bodies start leaving the host." Embedding inputs are post title + summary (`docs/spec/llm.md` §Embedding pipeline) — i.e. post bodies leave the host on a remote embedding call exactly as they do on a remote summarizer call.

The guard's offender set is `PER_TASK_BASE_URL_KEYS`, which is the six `LlmProvider` tasks only. It never inspects `infochat.embeddings.base-url` (the key `OpenAiCompatibleEmbeddingProvider` reads at line 86). A `local-only=true` deployment whose operator points `infochat.embeddings.base-url` at `https://api.openai.com/v1` boots cleanly and ships every post's title+summary to OpenAI — the precise failure the local-only commitment exists to prevent. There is no `EmbeddingProvider` selection path through this guard, and the embedding SPI's own javadoc (`EmbeddingProvider.java:17-21`) defers the model-identity guard to a different ticket that checks identity/dimensionality, not loopback. So nothing in the module catches a remote embedding endpoint under local-only.

A second, narrower gap: the guard keys exclusively on `base-url`. The `AnthropicProvider` also reads a per-task `base-url`, so a remote Anthropic endpoint configured that way is caught. But the spec frames the conflict as "a per-task override pointing to a remote provider" — the operator-facing selector is `infochat.llm.<task>.provider`. If a future provider impl hardcodes or defaults its endpoint (the design note describes `ollama` as "a thin alias of openai-compatible with the local URL pre-filled," implying providers may carry built-in URLs), selecting it by name with no explicit `base-url` would route remote while the guard sees an empty `base-url` and passes. The guard's correctness is currently coupled to the accident that every shipped provider requires an explicit `base-url`.

**Recommended fix:**

Add the embedding endpoint to the inspected set and fail closed on it under local-only:

```java
/** Embedding endpoint is a post-body egress path too (title+summary
 *  leave the host), so local-only must gate it alongside the LLM tasks. */
static final String EMBEDDINGS_BASE_URL_KEY = "infochat.embeddings.base-url";

public static void validateLocalOnlyConfiguration(@NonNull Map<String, String> snapshot) {
    boolean localOnly = "true".equalsIgnoreCase(stripOrEmpty(snapshot.get(CONFIG_KEY_LOCAL_ONLY)));
    if (!localOnly) {
        return;
    }

    List<Offender> offenders = new ArrayList<>();
    for (Map.Entry<ModelTask, String> kv : PER_TASK_BASE_URL_KEYS.entrySet()) {
        String baseUrl = stripOrEmpty(snapshot.get(kv.getValue()));
        if (!baseUrl.isEmpty() && !isLoopback(baseUrl)) {
            offenders.add(new Offender("task=" + kv.getKey().name(), kv.getValue(), baseUrl));
        }
    }
    String embedUrl = stripOrEmpty(snapshot.get(EMBEDDINGS_BASE_URL_KEY));
    if (!embedUrl.isEmpty() && !isLoopback(embedUrl)) {
        offenders.add(new Offender("embeddings", EMBEDDINGS_BASE_URL_KEY, embedUrl));
    }
    // ... existing fatal-log + throw on non-empty offenders
}
```

and add `EMBEDDINGS_BASE_URL_KEY` to `snapshotConfig`'s materialized keys so the `@PostConstruct` path sees it.

**Reasoning:**

The fix closes the post-body egress path the spec names verbatim. It is small, lives entirely in the existing pure validator, and keeps the "checked once at startup, not per call" posture. The offender row gains a label that is no longer task-typed (`Offender` instead of `TaskBaseUrl`) because the embedding endpoint is not a `ModelTask` — that matches the SPI split (`ModelTask` enumerates `LlmProvider` tasks only).

**Trade-offs:**

If an operator intentionally runs a remote embedding model under an otherwise-local deployment, this turns a previously-booting config into a startup failure. That is the correct behavior under a stated local-only commitment, and the operator's escape hatch is to not set `local-only=true`. No silent behavior change for non-local-only deployments.

**Alternative options:**

- **Option A** (recommended above) — add the embedding key to the loopback guard.
- **Option B** — also resolve `infochat.llm.<task>.provider` / `infochat.embeddings.provider` names to their effective endpoints and gate on the resolved endpoint rather than the raw `base-url` key. More robust against a future provider that carries a built-in remote URL, but it couples the guard to per-provider endpoint defaults that do not yet exist in v1 — pros: future-proof; cons: speculative, adds a provider→endpoint resolution table the guard does not currently need. Defer until a provider with a built-in URL actually ships.

---

### F2. silent exception swallow in AnthropicProvider.extractErrorMessage

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:195-205

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

`engineering-rules-verbatim.md` §8 lists "new `catch (Exception ignored) {}` or silent-swallow blocks in production code" as a test-integrity / code-integrity violation. This is exactly that shape. The intent (parse the Anthropic error envelope, fall back to a raw preview when the body is not the expected JSON) is legitimate, but `catch (Exception ignored)` is broader than the failure it handles and discards the throwable entirely. `JSON.readTree` on a `String` throws `JsonProcessingException` (an `IOException`); the only checked failure here is a parse failure. Catching `Exception` also swallows any unchecked bug in the path (e.g. an NPE) with zero signal.

The sibling `OpenAiCompatibleProvider` does not have this pattern — its non-2xx path logs a `preview` and throws, without a best-effort error-envelope parse. The asymmetry is the source of the smell: the Anthropic-specific envelope extraction was added without the corresponding narrowing.

**Recommended fix:**

```java
private static String extractErrorMessage(String body) {
    try {
        JsonNode root = JSON.readTree(body);
        if ("error".equals(root.path("type").asText())) {
            return root.path("error").path("message").asText("(no message)");
        }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        // Non-JSON or truncated error body — fall back to a raw preview.
    }
    return preview(body);
}
```

**Reasoning:**

Narrowing to `JsonProcessingException` catches exactly the expected failure (the body is not parseable JSON) and lets any genuinely unexpected unchecked exception propagate instead of being silently absorbed into a log-only diagnostic path. The empty catch body is acceptable once the type is narrow and the comment states why falling through is correct — this is the documented "non-JSON body" branch, not a swallow of arbitrary failure.

**Trade-offs:**

None — strictly better. The behavior on a non-JSON body is identical; the only change is that a non-`JsonProcessingException` (a real bug) is no longer hidden.

---

### F3. defensive null-checks on internal LlmRouter constructor and Entry record

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:109-116, 357-368

**Current code:**

```java
public LlmRouter(List<Entry> entries, ConfigReader config) {
    if (entries == null || entries.isEmpty()) {
        throw new IllegalArgumentException(
            "LlmRouter: at least one provider entry must be registered");
    }
    if (config == null) {
        throw new IllegalArgumentException("LlmRouter: ConfigReader must be non-null");
    }
    ...
}
```

```java
public record Entry(@NonNull String name, @NonNull LlmProvider provider, @Nullable Set<String> supportedLanguages) {
    public Entry {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Entry.name must be non-empty");
        }
        if (provider == null) {
            throw new IllegalArgumentException("Entry.provider must be non-null");
        }
        ...
    }
}
```

**Why this is wrong / suboptimal / risky:**

`engineering-rules-verbatim.md` §7 forbids null-checks for parameters callers cannot legally pass null for, between two internal classes. `LlmRouter`'s two constructors and `Entry` are not a system boundary — config parsing happens upstream in MicroProfile Config; the values reaching these constructors are produced by `buildFromCdi` (internal) or hand-built by tests (internal). The `@NonNull` annotations on `Entry`'s components already encode the contract; adding a runtime `provider == null` throw on top of `@NonNull LlmProvider provider` is the paranoia §7 calls out, not a boundary check.

The `entries.isEmpty()` / "at least one provider" check is partially redundant with `buildFromCdi`, which already throws `IllegalStateException` when CDI discovers no providers (line 284-288). The remaining empty-list check only guards the test constructor, where an empty list is a test bug, not a runtime input.

The `name.isEmpty()` check is the one with a thread of justification (a misconfigured provider could in principle register an empty name), but the name is produced by `providerName(p)` which returns a class simple-name or a non-empty constant — it cannot be empty in any real path.

**Recommended fix:**

Drop the `null`/`config == null`/`provider == null` checks and rely on the `@NonNull` contract plus the NPE that the `Map.copyOf` / `List.copyOf` calls already raise on null. Keep at most a single guard where a real boundary exists:

```java
public LlmRouter(List<Entry> entries, ConfigReader config) {
    this.entries = List.copyOf(entries);   // NPE on null, by contract
    this.config = config;
    Map<String, Entry> byName = new LinkedHashMap<>();
    for (Entry e : this.entries) {
        byName.put(e.name(), e);
    }
    this.entriesByName = Map.copyOf(byName);
}
```

```java
public record Entry(@NonNull String name, @NonNull LlmProvider provider, @Nullable Set<String> supportedLanguages) {
    public Entry {
        supportedLanguages = supportedLanguages == null ? Set.of() : Set.copyOf(supportedLanguages);
    }
}
```

**Reasoning:**

The `@NonNull` annotation is the contract that §7a requires; §7 then says the implementation should not re-check it between internal callers. `List.copyOf`/`Map.copyOf` already throw NPE on a null element, so a genuinely null argument still fails fast with a stack trace pointing at the construction site — no behavior is lost. The result is fewer lines and a single source of truth for the nullability contract (the annotation).

**Trade-offs:**

The thrown exception type changes from `IllegalArgumentException` with a descriptive message to `NullPointerException` from `copyOf`. No production caller depends on the type or message (the only assertions are in tests that pass valid input). If a descriptive message on the empty-list case is judged worth keeping for test ergonomics, retain only that one check and drop the rest.

**Alternative options:**

- **Option A** (recommended) — remove the internal null/non-null guards, keep the `@NonNull` annotations.
- **Option B** — keep the empty-`entries` check (it catches a test-construction mistake with a clear message) but drop the `config == null`, `provider == null`, and `name == null` checks. Pros: preserves the most useful diagnostic; cons: still mixes annotation-contract and runtime-check for nullability on the same parameters.

---

### F4. redundant null-check on apiKey already coalesced to non-null

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java:146-147, 182-184

**Current code:**

```java
case SECURITY_JUDGE -> new TaskConfig(
    securityBaseUrl, securityApiKey.orElse(""), securityModel, securityTimeoutMs);
```

```java
if (cfg.apiKey() != null && !cfg.apiKey().isEmpty()) {
    reqBuilder.header("Authorization", "Bearer " + cfg.apiKey());
}
```

**Why this is wrong / suboptimal / risky:**

`configFor` always sets `TaskConfig.apiKey` from `securityApiKey.orElse("")`, so `cfg.apiKey()` is never null by construction. The `cfg.apiKey() != null` half of the guard at line 182 is dead — it can only ever be true. This is a defensive check (§7) on an internal value the same method just guaranteed non-null. The sibling `AnthropicProvider` (line 141) and `OpenAiCompatibleEmbeddingProvider` (line 132-133) both correctly write `if (!key.isEmpty())` with no null branch, which is the consistent form.

**Recommended fix:**

```java
if (!cfg.apiKey().isEmpty()) {
    reqBuilder.header("Authorization", "Bearer " + cfg.apiKey());
}
```

**Reasoning:**

Removes a branch that can never be false-on-the-null-arm, matching the two sibling providers' form. The `orElse("")` at the construction site is the single place the empty-vs-present decision is made; the call site only needs the emptiness test.

**Trade-offs:**

None — strictly better.

---

### F5. taskKeySegment triplicated across router and both LLM providers

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:207-218; LlmRouter.java:249-258; and the test copy in AnthropicProviderTest.java:221-230

**Current code:**

```java
// AnthropicProvider — comment admits the duplication
// Mirrors LlmRouter.taskKeySegment — duplicated here to avoid
// a cross-package dependency on a package-private method.
private static String taskKeySegment(ModelTask task) {
    return switch (task) {
        case SECURITY_JUDGE -> "security";
        case TAGGER -> "tagger";
        ...
    };
}
```

**Why this is wrong / suboptimal / risky:**

The enum→config-segment mapping is the operator-facing property-key shape (`infochat.llm.<segment>.*`). It now exists in three production-relevant copies (`LlmRouter`, `AnthropicProvider`, and `LlmRouterStartupGuard`'s `PER_TASK_BASE_URL_KEYS` encodes the same mapping by hand) plus a fourth in the test. The mapping is load-bearing: if the `CHAT_AGENT -> "chat"` abbreviation drifts in one copy, the router resolves `infochat.llm.chat.provider` while the provider reads `infochat.llm.chat_agent.base-url`, and the mismatch is silent until a chat-agent call 404s. The comment's stated reason for duplicating ("avoid a cross-package dependency on a package-private method") is solvable by promoting the mapping to the `ModelTask` enum itself, which both packages already depend on.

**Recommended fix:**

Put the segment on the enum so all callers share one definition:

```java
public enum ModelTask {
    SECURITY_JUDGE("security"),
    TAGGER("tagger"),
    ENTITY("entity"),
    SUMMARIZER("summarizer"),
    CHAT_AGENT("chat"),
    TRANSLATOR("translator");

    private final String keySegment;
    ModelTask(String keySegment) { this.keySegment = keySegment; }

    /** Operator-facing config-key segment: infochat.llm.&lt;segment&gt;.* */
    public String keySegment() { return keySegment; }
}
```

Then `LlmRouter`, `AnthropicProvider`, and the test all call `task.keySegment()`; `LlmRouterStartupGuard` builds `PER_TASK_BASE_URL_KEYS` as `"infochat.llm." + t.keySegment() + ".base-url"` over `ModelTask.values()`.

**Reasoning:**

The segment is an intrinsic property of the task (it is the task's operator-facing name), so it belongs on the enum. Both `impl` and `routing` packages already import `ModelTask`, so this removes the cross-package-dependency excuse entirely and collapses four hand-maintained copies into one. It also lets `LlmRouterStartupGuard` derive its base-url key map from `ModelTask.values()` rather than a hand-written `Map.of` that can fall out of sync with the enum.

**Trade-offs:**

Adds a field and accessor to `ModelTask`, which the SPI-freeze note in `LlmRouter.buildFromCdi` (line 261-268) describes as frozen for the M1-007b ticket. Adding a key-segment accessor is additive and does not change the enum's value set (the smoke test asserting exactly six values still passes), so it does not widen the spec-committed contract — but if the enum is contractually frozen against any change in the current ticket window, this is a follow-up rather than an inline edit. The mapping is correct today; this is a maintainability hardening, not a bug fix.

## Synthesizer-relevant observations

- The `local-only` startup guard (F1) lives in `infochat-llm-adapter` but is documented as running on the Collector startup chain (`LlmRouterStartupGuard` javadoc §Doc-bug routing). The embedding-endpoint gap in F1 is only fully closed if the embedding-pipeline wiring ticket (which owns the model-identity guard, per `EmbeddingProvider.java:17-21`) also surfaces a remote-embedding startup signal — the spec promises "an explicit confirmation log line on startup" for a remote embedding switch (`docs/spec/llm.md` §Per-task routing rules), and no code in this module emits it. Whether that log line belongs here or in the collector-side wiring ticket is a cross-module routing call for the synthesizer.
- `LlmRouterStartupGuard.validateLocalOnlyConfiguration` is invoked directly by `LocalOnlyConflictStartupIT` in the `infochat-collector` module (per the javadoc at line 121-129). The §8 test-integrity check on whether that IT was weakened cannot be assessed from inside this module; flag for the collector-module review.
