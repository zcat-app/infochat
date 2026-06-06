# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-01 20:57
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — AnthropicProvider.java:209, LlmRouter.java:249, AnthropicProviderTest.java:221 — Task key segment mapping duplicated in three locations will drift on the next ModelTask addition
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — AnthropicProvider.java:201 — Silent exception swallow in extractErrorMessage violates §8 test-integrity rule against empty catch blocks
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT / SPEC-DRIFT — LlmRouterStartupGuard.java:44 comment vs docs/spec/llm.md:133 — Spec says local-only conflict "fails Provider startup" but the guard runs on Collector startup
- [LOW] MAINTAINABILITY-RULES-DRIFT — LlmProvider.java:36 — Missing @NonNull annotation on LlmResponse return type in the SPI interface
- [LOW] MAINTAINABILITY-RULES-DRIFT — LlmRouter.java:399 — Undocumented "null" string normalization in MicroProfileConfigReader

## Detail

### F1. Task key segment mapping duplicated in three locations

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** AnthropicProvider.java:209-218, LlmRouter.java:249-258, AnthropicProviderTest.java:221-229

**Current code:**

AnthropicProvider.java:209-218:
```java
private static String taskKeySegment(ModelTask task) {
    return switch (task) {
        case SECURITY_JUDGE -> "security";
        case TAGGER -> "tagger";
        case ENTITY -> "entity";
        case SUMMARIZER -> "summarizer";
        case CHAT_AGENT -> "chat";
        case TRANSLATOR -> "translator";
    };
}
```

LlmRouter.java:249-258:
```java
static String taskKeySegment(ModelTask task) {
    return switch (task) {
        case SECURITY_JUDGE -> "security";
        case TAGGER -> "tagger";
        case ENTITY -> "entity";
        case SUMMARIZER -> "summarizer";
        case CHAT_AGENT -> "chat";
        case TRANSLATOR -> "translator";
    };
}
```

AnthropicProviderTest.java:221-229:
```java
private static String taskKeySegment(ModelTask task) {
    return switch (task) {
        case SECURITY_JUDGE -> "security";
        case TAGGER -> "tagger";
        case ENTITY -> "entity";
        case SUMMARIZER -> "summarizer";
        case CHAT_AGENT -> "chat";
        case TRANSLATOR -> "translator";
    };
}
```

**Why this is wrong / suboptimal / risky:**

The same `ModelTask`-to-config-key-segment mapping appears verbatim in three places. The comment at AnthropicProvider.java:207 explicitly acknowledges the duplication: "Mirrors LlmRouter.taskKeySegment — duplicated here to avoid a cross-package dependency on a package-private method." The test file has yet another copy.

When a new `ModelTask` value is added (a spec-level contract change), all three copies must be updated in lockstep. A missed update will cause: (1) a compile error in the source copies (exhaustiveness check on the switch — not silent), BUT (2) routing or config-resolution failures at runtime that are harder to diagnose, or (3) silent fallback to wrong config keys. The compiler catches the switch exhaustiveness failure for existing copies, but the test copy will silently compile against the old enum and go stale. Adding a sixth `ModelTask` value today means the test's switch becomes non-exhaustive only when someone runs that specific test method, which is easy to miss.

The engineering rules call for simplicity (§2: "Never sacrifice simplicity"), and CLAUDE.md mandates preferring well-factored code over duplication ("Three similar lines beats a premature abstraction" does not apply here — this is three identical methods that embody the same mapping).

**Recommended fix:**

Move the mapping onto `ModelTask` itself as an instance method, eliminating all three copies:

```java
// In ModelTask.java
public String configKeySegment() {
    return switch (this) {
        case SECURITY_JUDGE -> "security";
        case TAGGER -> "tagger";
        case ENTITY -> "entity";
        case SUMMARIZER -> "summarizer";
        case CHAT_AGENT -> "chat";
        case TRANSLATOR -> "translator";
    };
}
```

Then replace all three sites with `task.configKeySegment()`.

**Reasoning:**

The mapping is an inherent property of each enum value, not of any particular consumer. Putting it on the enum eliminates the duplication, centralizes the contract (one place to update when a value is added), and makes the test automatically exercise the production mapping rather than its own stale copy. The compiler's exhaustiveness check on the switch in `ModelTask.configKeySegment()` ensures all values are covered.

**Trade-offs:**

Adds one method to `ModelTask.java`; removes duplication from three classes. Net reduction: roughly 25 lines deleted, 8 lines added. None — the fix is strictly better.

---

### F2. Silent exception swallow in extractErrorMessage

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** AnthropicProvider.java:201-203

**Current code:**

```java
} catch (Exception ignored) {
    // Fall through to preview
}
```

**Why this is wrong / suboptimal / risky:**

This empty `catch (Exception ignored)` block in production code violates the test-integrity rule (§8): "New `catch (Exception ignored) {}` or silent-swallow blocks in production code" is explicitly forbidden. The broad `Exception` type catches every checked and unchecked exception including `NullPointerException`, `ClassCastException`, and other unexpected failures that the fallthrough to `preview(body)` would then silently mask. If a `RuntimeException` occurs during JSON parsing (e.g., the `body` is null), the method silently falls through to `preview(body)` which handles null gracefully, but a non-JSON-related exception (e.g., out of memory, some JVM-internal failure) would also be swallowed without any diagnostic.

The comment "Fall through to preview" explains the intent but does not make the swallow acceptable per project rules. A system-boundary parsing helper that degrades gracefully should log the failure at TRACE or DEBUG level, not swallow it entirely.

**Recommended fix:**

```java
} catch (IOException | RuntimeException e) {
    LOG.tracef("AnthropicProvider: failed to parse error response body: %s", e.getMessage());
}
```

**Reasoning:**

TRACE-level logging preserves the diagnostic signal for developers debugging integration issues without polluting operator-facing logs. Narrowing the catch type to the actual exceptions that can occur during JSON parsing (IOException and Jackson's JsonProcessingException, which extends IOException, plus any RuntimeExceptions from Jackson internals) ensures unexpected exceptions are still captured. The `preview(body)` fallback is still reached, preserving the best-effort error-extraction contract.

**Trade-offs:**

TRACE log line adds minimal overhead (guarded by level check inside Logger). The fix is strictly better than a silent swallow.

---

### F3. Spec-drift: local-only guard runs on Collector, spec says Provider startup

- **Category:** MAINTAINABILITY-RULES-DRIFT (SPEC-DRIFT)
- **Severity:** MEDIUM
- **Location:** LlmRouterStartupGuard.java:40-44 (code comment), docs/spec/llm.md:132-134

**Current code comment (LlmRouterStartupGuard.java:39-44):**

```java
 * <h2>Doc-bug routing</h2>
 * <p>The spec wording above says "fails Provider startup", but
 * Stage 2 — the security-judge LLM call site — runs in the Collector,
 * not the Provider. Treat as a doc-bug routing call: the guard runs
 * on the Collector startup chain because the security-judge config
 * keys live on the Collector.
```

**Spec text (docs/spec/llm.md:132-134):**

> a per-task override pointing to a remote provider while local-only is set is **a configuration conflict that fails Provider startup with a fatal log line identifying the offending task and provider**.

**Why this is wrong / suboptimal / risky:**

The spec contract says the local-only conflict "fails Provider startup." The implementation runs the check on Collector startup (where the security-judge config keys and call-site live) and documents the discrepancy as a "doc-bug routing call." This is a spec-drift finding: the spec is the authoritative contract (per CLAUDE.md and the project conventions), and the code knowingly diverges.

The code comment documents the discrepancy, which is good for maintainers, but the spec has not been updated to reflect the actual behavior. A future operator reading the spec would expect the guard on the Provider service, not the Collector. If a future ticket adds a new LLM task whose config keys live on the Provider (e.g., the chat agent), the guard will miss it because it only inspects config keys from the Collector's config surface.

**Recommended fix:**

Update docs/spec/llm.md:132-134 to say "Collector startup" instead of "Provider startup" and add a note explaining that the guard runs wherever the LLM call's config keys live (which is the Collector for the ingest pipeline tasks, and may differ for Provider-side tasks).

**Reasoning:**

The spec must match the implementation. The operator needs to know which service will refuse to start if the local-only conflict is triggered. The code is correct in running on the Collector (that is where the security-judge config is read), but the spec must be updated to reflect reality.

**Trade-offs:**

None -- the spec needs a two-word correction. The implementation is sound.

---

### F4. Missing @NonNull annotation on LlmProvider.generate return type

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** LOW
- **Location:** LlmProvider.java:36

**Current code:**

```java
LlmResponse generate(@NonNull ModelTask task, @NonNull String systemPrompt, @NonNull String userPrompt);
```

**Why this is wrong / suboptimal / risky:**

The Javadoc says "Never null" on the return value, and the AnthropicProvider implementation annotates its return type with `@NonNull` (AnthropicProvider.java:94). But the SPI interface itself does not annotate the return type. The engineering rule (§7a) requires explicit nullability contracts: "Every reference-type parameter on a public method declares nullability." While the rule specifically mentions _parameters_, the same principle applies symmetrically to the return type -- a caller reading the signature should see immediately whether null is a possible return value.

The `LlmProvider` interface is the central SPI of this module, consumed by multiple downstream modules (collector, provider). A missing `@NonNull` on the return type leaves ambiguity: should callers guard against null? The Javadoc says no, but the type system does not enforce it. The inconsistency with the AnthropicProvider implementation (which does add `@NonNull` on the return) further suggests this was an oversight.

**Recommended fix:**

```java
@NonNull LlmResponse generate(@NonNull ModelTask task, @NonNull String systemPrompt, @NonNull String userPrompt);
```

**Reasoning:**

Matches the Javadoc contract, matches the AnthropicProvider implementation, and gives callers compile-time certainty that the return value is never null.

**Trade-offs:**

None -- the annotation is purely additive and strictly improves the contract signal.

---

### F5. Undocumented "null" string normalization in MicroProfileConfigReader

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** LOW
- **Location:** LlmRouter.java:396-399

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

The `.map(s -> s.toLowerCase(Locale.ROOT).equals("null") ? "" : s)` step converts a config value that is literally the string `"null"` (case-insensitive) to an empty string, which the caller then treats as absent. There is no comment explaining why this transformation exists -- whether it handles a specific MicroProfile Config source behavior (e.g., environment variables that can't represent empty strings natively), a SmallRye Config quirk, or a defensive guard against operator misconfiguration.

Without a why-comment, the next developer encountering this cannot tell whether it is essential behavior (must preserve) or a workaround for a now-fixed framework issue (should remove). Code that carries a hidden invariant must document it per CLAUDE.md's style guidance: "Comment new code that carries an invariant, a hidden constraint, a non-obvious decision."

**Recommended fix:**

Add a comment explaining why the `"null"` normalization exists:

```java
@Override
public Optional<String> get(@NonNull String key) {
    return delegate.getOptionalValue(key, String.class)
        .map(s -> s.trim())
        // MicroProfile Config sources that cannot distinguish between
        // "unset" and "empty" (e.g. EnvConfigSource) expose the
        // property with value "null" as a literal string when the
        // operator writes ${KEY:=}. Normalize this to empty so the
        // caller treats it as absent.
        .map(s -> s.toLowerCase(Locale.ROOT).equals("null") ? "" : s);
}
```

**Reasoning:**

If the explanation above is incorrect, the fix is still correct: it surfaces the need for a documented rationale. If the operation is actually unnecessary (a leftover from an earlier iteration), the comment enables confident removal. If it is correct, the comment saves future maintainers from wondering.

**Trade-offs:**

Adds one block comment (7 lines) to a private class with one method. None -- the fix is strictly better.
