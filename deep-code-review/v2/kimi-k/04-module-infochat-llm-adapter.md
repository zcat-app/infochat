# Deep code review: module
**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:**
    infochat-llm-adapter/
**Date:** 2026-06-07 00:57
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — LlmProvider.java:36 — `@NonNull` annotation on interface method parameter violates engineering-rules §7a (JSpecify is the v1 source; `@NonNull` is no longer written by hand; bare reference type means "never null" in null-marked packages)
- [high] MAINTAINABILITY-RULES-DRIFT — LlmRouter.java:139-142 — Defensive null-check on `task` parameter inside internal method violates §7 "No defensive code for impossible scenarios" and contradicts §7a bare-reference contract
- [medium] MAINTAINABILITY-RULES-DRIFT — LlmRouterStartupGuard.java:179 — `validateLocalOnlyConfiguration` is declared `public static` for test access, but its Javadoc says "Public for test invocation ... no other consumer should call it directly" — this is a test-seam leakage that should be package-private per its own design intent
- [medium] MAINTAINABILITY-RULES-DRIFT — OpenAiCompatibleProvider.java:125-126, AnthropicProvider.java:106-107, OpenAiCompatibleEmbeddingProvider.java:94-95 — API-key injection uses `Optional<String>` with `@ConfigProperty` to handle empty-string sentinel, but SmallRye Config already treats empty string as absent for `Optional<String>`; the `orElse("")` fallback is redundant and the comment about empty-string mapping is misleading
- [medium] MAINTAINABILITY-RULES-DRIFT — LlmRouterStartupGuard.java:116-123 — `PER_TASK_BASE_URL_KEYS` is a static `Map.of` keyed by `ModelTask` enum, but `ModelTask` values are iterated in `validateLocalOnlyConfiguration` via `Map.Entry<ModelTask, String>`; this works but the map is only ever iterated for values and keys; a `LinkedHashMap` or simple list of records would be clearer and avoid the misleading "keyed by" semantics when the enum key is never used for lookup
- [low] MAINTAINABILITY-RULES-DRIFT — LlmRouter.java:100 — `warnedUnknownDefault` is an `AtomicBoolean` guarding a one-shot WARN log, but the field is `final` and initialized to `false`; this is correct but the comment says "one-shot guard ... set true on the first forTask call" — the field name and type are fine, but the comment could be clearer that it is a CAS-guarded log deduplicator, not a general-purpose flag

## Detail

### F1. `@NonNull` annotation on `LlmProvider.generate` parameter violates §7a

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** LlmProvider.java:36

**Current code:**

```java
LlmResponse generate(@NonNull ModelTask task, @NonNull String systemPrompt, @NonNull String userPrompt);
```

**Why this is wrong / suboptimal / risky:**

Engineering rules §7a state: "Only genuinely-nullable parameters, returns, and fields carry `@Nullable` (from `org.jspecify.annotations`); `@NonNull` is no longer written by hand." The `app.zcat.infochat` package tree is null-marked via NullAway `AnnotatedPackages`, so a bare reference type already means "never null." Writing `@NonNull` on the parameter is redundant, violates the project's own style rule, and sets a bad precedent for future SPI additions. The build enforces nullability via NullAway:ERROR, so the annotation adds no machine-checked value.

**Recommended fix:**

```java
LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt);
```

**Reasoning:**

Removing `@NonNull` aligns the code with §7a. The bare reference type in a null-marked package is the canonical non-null contract. NullAway will still flag any call site that passes a potentially-null value.

**Trade-offs:**

None — the fix is strictly better.

---

### F2. Defensive null-check on `task` in `LlmRouter.forTask` contradicts §7 and §7a

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** LlmRouter.java:139-142

**Current code:**

```java
public LlmProvider forTask(@NonNull ModelTask task, @Nullable String scopeLanguage) {
    if (task == null) {
        throw new IllegalArgumentException("LlmRouter.forTask: task must be non-null");
    }
```

**Why this is wrong / suboptimal / risky:**

Engineering rules §7: "No null-checks for parameters that callers cannot legally pass null for." The `task` parameter is declared `@NonNull` (and even after F1 is fixed, the bare reference type in a null-marked package means non-null). Callers of `forTask` are internal code (Stage 2 worker, chat agent, digest scheduler) — they cannot legally pass null. The null-check is defensive code for an impossible scenario inside a trust boundary. §7a explicitly says: "A caller reading the signature can see immediately whether passing null is a legal call or a bug." The check undermines that contract by treating a bug as a recoverable condition.

**Recommended fix:**

```java
public LlmProvider forTask(ModelTask task, @Nullable String scopeLanguage) {
    String lang = scopeLanguage == null ? "en" : scopeLanguage.toLowerCase(Locale.ROOT);
```

**Reasoning:**

Remove the `if (task == null)` guard. NullAway will enforce the non-null contract at compile time. If a caller ever passes null, it is a bug in the caller, not a condition the router should handle.

**Trade-offs:**

None — the fix is strictly better.

---

### F3. `validateLocalOnlyConfiguration` is `public static` for test access, leaking a test seam

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** LlmRouterStartupGuard.java:179

**Current code:**

```java
public static void validateLocalOnlyConfiguration(@NonNull Map<String, String> snapshot) {
```

**Why this is wrong / suboptimal / risky:**

The Javadoc on this method says: "Public for test invocation: LocalOnlyConflictStartupIT ... invokes this validator directly ... no other consumer should call it directly." A method that is public solely for test access is a test-seam leakage. The design notes in `LlmRouterStartupGuard`'s own class-level Javadoc say the test seam is "package-private so LocalOnlyConflictStartupIT can invoke it directly." But the actual declaration is `public static`, not package-private. This is a drift between the documented intent and the code.

**Recommended fix:**

Change the access modifier to package-private (no modifier) and move the test that calls it into the same package (`app.zcat.infochat.llm.routing`). If the cross-module IT (`infochat-collector`'s `LocalOnlyConflictStartupIT`) truly needs access, it should either (a) live in the same package via a test-jar dependency, or (b) the method should be documented as a supported public API, not a test seam.

```java
static void validateLocalOnlyConfiguration(@NonNull Map<String, String> snapshot) {
```

**Reasoning:**

Package-private is the narrowest access that satisfies same-package tests. The `LlmRouterStartupGuardLocalOnlyTest` in this module already lives in the same package and calls this method — it would continue to compile. The cross-module IT in `infochat-collector` should be re-evaluated: if it needs to call this directly, that is an architecture-level coupling issue (the collector IT should test the guard via the CDI `@Startup` path or via a test in the same package with a test-jar dependency).

**Trade-offs:**

If the collector IT is intentionally testing the guard from outside the package, making this package-private breaks that test. The correct fix is to move the test into the same package or add a test-jar export. Either way, the current `public` declaration is a leak.

---

### F4. Misleading comment about `Optional<String>` empty-string mapping for API keys

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** OpenAiCompatibleProvider.java:120-126, AnthropicProvider.java:106-107, OpenAiCompatibleEmbeddingProvider.java:89-95

**Current code (OpenAiCompatibleProvider):**

```java
/**
 * Optional injection so an empty {@code api-key=} property maps
 * to {@link Optional#empty()} (SmallRye Config's default
 * converter treats {@code ""} as absent rather than empty).
 * Local Ollama is the canonical empty-key case.
 */
@ConfigProperty(name = "infochat.llm.security.api-key")
Optional<String> securityApiKey;
```

And later:

```java
case SECURITY_JUDGE -> new TaskConfig(
    securityBaseUrl, securityApiKey.orElse(""), securityModel, securityTimeoutMs);
```

**Why this is wrong / suboptimal / risky:**

The comment claims "SmallRye Config's default converter treats `""` as absent rather than empty." This is misleading. SmallRye Config's `Optional<String>` converter does indeed return `Optional.empty()` for an empty string, but the `@ConfigProperty` on a plain `String` field with `defaultValue = ""` would also work and would map to `""`. The current design uses `Optional<String>` to distinguish "not configured" from "configured as empty," but then immediately coalesces back to `""` via `orElse("")`. The extra `Optional` wrapper and the `orElse` call are unnecessary indirection when the downstream code only cares about emptiness. A plain `String` field with `defaultValue = ""` would be simpler and would produce the same behavior.

**Recommended fix:**

Change to a plain `String` with an empty default:

```java
@ConfigProperty(name = "infochat.llm.security.api-key", defaultValue = "")
String securityApiKey;
```

And remove the `orElse("")` coalescence at the call site:

```java
case SECURITY_JUDGE -> new TaskConfig(securityBaseUrl, securityApiKey, securityModel, securityTimeoutMs);
```

Apply the same simplification to `AnthropicProvider` (dynamic `Config` lookup already returns `""` via `orElse("")`) and `OpenAiCompatibleEmbeddingProvider`.

**Reasoning:**

Simpler code, fewer objects, no misleading comment. The empty-string sentinel is the documented operator path for local Ollama; `Optional` adds no value when the empty case is the normal path.

**Trade-offs:**

If a future consumer needs to distinguish "absent" from "empty," the `Optional` wrapper would be justified. No such consumer exists in v1.

---

### F5. `PER_TASK_BASE_URL_KEYS` as `Map<ModelTask, String>` is semantically misleading

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** LlmRouterStartupGuard.java:116-123

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

**Why this is wrong / suboptimal / risky:**

The map is keyed by `ModelTask`, but in `validateLocalOnlyConfiguration` it is only iterated via `entrySet()` — the `ModelTask` key is used to name the offending task in the log, but never for lookup. A `Map` implies a lookup data structure; using it as an ordered sequence of pairs is a semantic mismatch. A `List<Entry>` or a small record array would express the intent more directly and avoid the overhead of hash-based storage for six fixed elements.

**Recommended fix:**

```java
private static final List<TaskKey> PER_TASK_KEYS = List.of(
    new TaskKey(ModelTask.SECURITY_JUDGE, "infochat.llm.security.base-url"),
    new TaskKey(ModelTask.TAGGER,         "infochat.llm.tagger.base-url"),
    new TaskKey(ModelTask.ENTITY,         "infochat.llm.entity.base-url"),
    new TaskKey(ModelTask.SUMMARIZER,     "infochat.llm.summarizer.base-url"),
    new TaskKey(ModelTask.CHAT_AGENT,     "infochat.llm.chat.base-url"),
    new TaskKey(ModelTask.TRANSLATOR,     "infochat.llm.translator.base-url")
);

private record TaskKey(ModelTask task, String baseUrlKey) {}
```

And update the iteration:

```java
for (TaskKey tk : PER_TASK_KEYS) {
    String baseUrl = stripOrEmpty(snapshot.get(tk.baseUrlKey()));
    if (!baseUrl.isEmpty() && !isLoopback(baseUrl)) {
        offenders.add("task=" + tk.task().name()
            + " key=" + tk.baseUrlKey() + " base-url=" + baseUrl);
    }
    String providerKey = providerKeyFor(tk.baseUrlKey());
    ...
}
```

**Reasoning:**

A `List<record>` is the right shape for an ordered, fixed sequence of pairs. It removes the hash-map overhead, makes the "no lookup" intent explicit, and is slightly more memory-efficient.

**Trade-offs:**

Slightly more lines of code (the record declaration). The `Map.of` is concise but misleading.

---

### F6. `warnedUnknownDefault` comment is imprecise about its mechanism

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** LlmRouter.java:94-100

**Current code:**

```java
/**
 * One-shot guard for the priority-3 unknown-default-provider WARN
 * (M1-042). Set true on the first {@link #forTask} call that
 * observes an operator-configured {@link #CONFIG_KEY_DEFAULT_PROVIDER}
 * naming a provider that resolves to no registered entry. The
 * audit-loud-fallback posture is documented in {@link #forTask}'s
 * priority-3 branch.
 */
private final AtomicBoolean warnedUnknownDefault = new AtomicBoolean(false);
```

**Why this is wrong / suboptimal / risky:**

The comment says "Set true on the first forTask call that observes ..." but the actual mechanism is a `compareAndSet(false, true)` CAS operation. The comment is not wrong, but it is imprecise: it does not mention the CAS, the atomicity, or the "exactly once per JVM" guarantee that the code provides. A future reader might think this is a simple boolean flag and miss the thread-safety and deduplication intent.

**Recommended fix:**

```java
/**
 * CAS-guarded deduplicator for the priority-3 unknown-default-provider
 * WARN (M1-042). Emits the WARN exactly once per JVM lifetime via
 * {@link AtomicBoolean#compareAndSet}, so a misconfigured default
 * provider does not flood the logs on every router call.
 */
```

**Reasoning:**

The revised comment names the mechanism (CAS), the guarantee (exactly once per JVM), and the purpose (log deduplication). This is a "why" comment that explains the non-obvious choice of `AtomicBoolean` over a plain `boolean`.

**Trade-offs:**

None — the fix is strictly better.

---

## Synthesizer-relevant observations

- The `LlmRouterStartupGuard` is placed in `infochat-llm-adapter` but its `@Startup` `@Priority(150)` bean is intended to run in the Collector. This is correct per the spec (the guard runs on Collector startup), but it means the module has a Quarkus-runtime dependency (`quarkus-core` provided scope) for a `@Startup` annotation that only makes sense when the module is consumed by a Quarkus application. This is not a finding for the module lens, but the architecture pass should verify that no non-Quarkus consumer of this SPI accidentally triggers the guard.
- `TranslationProvider` is mentioned in `LlmProvider`'s Javadoc as living in `infochat-messaging-adapter`, but the design notes (`docs/design/05-llm-and-embeddings.md`) show it as part of `infochat-llm-adapter/api/`. This is a spec-vs-design drift that the architecture lens should reconcile.
