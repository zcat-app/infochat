# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-06 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — cross-cutting — `@NonNull` is written by hand throughout the module, contradicting engineering rule §7a (NullAway treats `app.zcat.infochat` as non-null-by-default; `@NonNull` is no longer written).
- [medium] MAINTAINABILITY-RULES-DRIFT — `LlmRouter.java:140-142` — defensive null-check on a `@NonNull` parameter (`if (task == null) throw ...`); §7 No-defensive-code violation.
- [medium] MAINTAINABILITY-RULES-DRIFT — `LlmRouter.java:332,162-170` — `Entry.supportedLanguages` is declared `@Nullable` on the record component, yet the compact constructor coerces null to `Set.of()`; the accessor never returns null, so the `@Nullable` declaration and the corresponding null-guard in `forTask` lie about the contract.
- [medium] SECURITY — `AnthropicProvider.java:178-197` — multi-block Anthropic responses (thinking blocks, tool-use, anything with text at index > 0) cause the provider to throw or return empty text; partial-trust output handling assumes `content[0].type == "text"` without inspection.
- [medium] SIMPLIFICATION — cross-cutting (see Detail) — `joinPath` and `preview` are duplicated verbatim across `OpenAiCompatibleProvider`, `OpenAiCompatibleEmbeddingProvider`, and `AnthropicProvider`; the existing package-private `LlmHttpSupport` is the obvious home.
- [medium] MAINTAINABILITY-RULES-DRIFT — `LlmRouterStartupGuard.java:179` — javadoc claims `validateLocalOnlyConfiguration` is a "pure-function validator" but it calls `InetAddress.getByName` (DNS I/O) through `isLoopback`; misleading contract.
- [low] SECURITY — `LlmRouter.java:149-156` and `LlmRouterStartupGuard.java:144,210-211` — case-normalization of provider names is inconsistent between the router (case-sensitive) and the local-only guard (lower-cased); an operator configuring `Anthropic` (capital A) bypasses the cloud-only conflict check while still routing to the anthropic provider.
- [low] MAINTAINABILITY-RULES-DRIFT — `AnthropicProvider.java:203-213` — `extractErrorMessage` catches `IOException` and silently swallows it via `// Fall through to preview`; the body is in-memory `String`, so no `IOException` can actually fire from `JSON.readTree(String)`. Dead arm hides intent.
- [low] PERFORMANCE — `AnthropicProvider.java:104-112` — MicroProfile `Config.getValue` is invoked five times per generate-call; the bean is `@ApplicationScoped` and the config is immutable post-startup, so a one-time read per task would suffice.
- [low] MAINTAINABILITY-RULES-DRIFT — `OpenAiCompatibleEmbeddingProvider.java:217-227` — comment says the helper is "kept inline rather than extracted to a shared util because the helper is two branches and pulling it into a third class would add an abstraction without enough callers to justify the file"; that file (`LlmHttpSupport`) already exists, making the rationale stale.

## Detail

### F1. `@NonNull` is hand-written across the module despite NullAway's non-null-by-default

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

`EmbeddingProvider.java:33`
```java
List<EmbeddingResult> embed(@NonNull List<String> texts);
```

`LlmProvider.java:36`
```java
LlmResponse generate(@NonNull ModelTask task, @NonNull String systemPrompt, @NonNull String userPrompt);
```

`LlmResponse.java:15`
```java
public record LlmResponse(@NonNull String text) {
}
```

`AnthropicProvider.java:82, 98-99`
```java
public AnthropicProvider(@NonNull Config config) {
    ...
public @NonNull LlmResponse generate(@NonNull ModelTask task, @NonNull String systemPrompt,
                                      @NonNull String userPrompt) {
```

`LlmRouter.java:139, 332, 351, 353, 368`
```java
public LlmProvider forTask(@NonNull ModelTask task, @Nullable String scopeLanguage) {
...
public record Entry(@NonNull String name, @NonNull LlmProvider provider, @Nullable Set<String> supportedLanguages) {
...
Optional<String> get(@NonNull String key);
static ConfigReader fromMap(@NonNull Map<String, String> map) {
...
public Optional<String> get(@NonNull String key) {
```

`LlmRouterStartupGuard.java:100, 179, 254`: same pattern.

`LlmHttpSupport.java:100, 89`: same pattern.

**Why this is wrong / suboptimal / risky:**

Engineering rule §7a in `docs/process/engineering-rules-verbatim.md` (and `CLAUDE.md` §Method parameter contracts) is explicit:

> "Non-null is the **package default** — every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`), so a bare reference type means 'never null.' Only genuinely-nullable parameters, returns, and fields carry `@Nullable` (from `org.jspecify.annotations`); `@NonNull` is no longer written by hand."

Hand-written `@NonNull` is noise — it duplicates the package default, dilutes the signal that `@Nullable` annotations carry, and trains readers to expect that any unannotated reference is "I don't know if it's nullable" rather than "guaranteed non-null." The rule was set to make `@Nullable` a positive signal that you can't miss; flooding the module with `@NonNull` reverses that.

NullAway also does not need or use the `@NonNull` annotations — they have zero behavioural effect under the project's static-analysis configuration. Removing them is risk-free for the build.

**Recommended fix:**

Drop every `@NonNull` import and annotation in this module's `src/main` and `src/test`. Keep `@Nullable` where it appears.

```java
// Before
List<EmbeddingResult> embed(@NonNull List<String> texts);

// After
List<EmbeddingResult> embed(List<String> texts);
```

**Reasoning:**

This matches the rule verbatim, removes redundant noise, and makes the remaining `@Nullable` annotations carry their intended weight. NullAway will continue to enforce non-nullity for the bare types because the package is annotated.

**Trade-offs:**

None — strictly better. The annotations are purely informational under NullAway's `AnnotatedPackages`-driven config.

---

### F2. Defensive null-check inside the trust boundary in `LlmRouter.forTask`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `LlmRouter.java:139-142`

**Current code:**

```java
public LlmProvider forTask(@NonNull ModelTask task, @Nullable String scopeLanguage) {
    if (task == null) {
        throw new IllegalArgumentException("LlmRouter.forTask: task must be non-null");
    }
    String lang = scopeLanguage == null ? "en" : scopeLanguage.toLowerCase(Locale.ROOT);
```

**Why this is wrong / suboptimal / risky:**

Engineering rule §7:

> "No defensive code for impossible scenarios. ... No null-checks for parameters callers cannot legally pass null for."

`task` is a non-null parameter (both per the package default and the redundant `@NonNull`). NullAway forbids the caller from passing `null` here — the build fails before the check could ever fire. The null check is dead code. It also distracts from the real branch logic immediately below (the `scopeLanguage == null` coercion, which IS legal since `scopeLanguage` is `@Nullable`).

**Recommended fix:**

```java
public LlmProvider forTask(ModelTask task, @Nullable String scopeLanguage) {
    String lang = scopeLanguage == null ? "en" : scopeLanguage.toLowerCase(Locale.ROOT);
```

**Reasoning:**

NullAway already proves `task` is non-null at every call site at compile time, so the runtime branch is unreachable. Removing it makes the function shorter and aligns with §7.

**Trade-offs:**

None — strictly better.

---

### F3. `Entry.supportedLanguages` declares `@Nullable` but the value is never null

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `LlmRouter.java:332-340, 162-171`

**Current code:**

```java
public record Entry(@NonNull String name, @NonNull LlmProvider provider, @Nullable Set<String> supportedLanguages) {
    public Entry {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Entry.name must be non-empty");
        }
        supportedLanguages = supportedLanguages == null
            ? Set.of()
            : Set.copyOf(supportedLanguages);
    }
}
```

And the caller relies on the `@Nullable` declaration:

```java
for (Entry e : entries) {
    // supportedLanguages() is @Nullable per the Entry component
    // contract; a null reads as "no declared language" and skips
    // the entry, matching the compact constructor's null→empty
    // normalization.
    Set<String> supported = e.supportedLanguages();
    if (supported != null && supported.contains(lang)) {
        return e.provider();
    }
}
```

**Why this is wrong / suboptimal / risky:**

The compact constructor unconditionally coerces `null` → `Set.of()`. After construction, `this.supportedLanguages` is never null, and the record-generated accessor `supportedLanguages()` returns the field directly. So the `@Nullable` on the component is a lie about what the accessor produces — and the comment in `forTask` is wrong: the field is `Set.of()`, not null, when the caller passed null.

The downstream `if (supported != null && supported.contains(lang))` check therefore has dead nullness handling. The logic still works because `Set.of().contains(lang)` is false, but the code documents an impossible nullness path. Worse, JSpecify component nullness applies type-use semantics: `@Nullable Set<String>` on a record component means the accessor return is nullable, which is incorrect here.

The `@Nullable` is currently load-bearing for one subtle case the comment hints at: "Empty means 'any' — the language-aware branch skips empty sets so a generic provider doesn't front-run a capability-declaring one." But that is a SEMANTIC handling of "empty == 'any languages'", which the current implementation already misses (it treats empty as "no languages match" rather than "matches anything"). The doc says one thing and the code does another.

**Recommended fix:**

Decide the actual contract — pick ONE:

Option A (constructor accepts null → empty, accessor non-null):

```java
public record Entry(String name, LlmProvider provider, Set<String> supportedLanguages) {
    public Entry(String name, LlmProvider provider, @Nullable Set<String> supportedLanguages) {
        this(name, provider, supportedLanguages == null ? Set.of() : Set.copyOf(supportedLanguages));
    }
    public Entry {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Entry.name must be non-empty");
        }
        supportedLanguages = Set.copyOf(supportedLanguages);
    }
}
```

Then in `forTask`:

```java
for (Entry e : entries) {
    if (e.supportedLanguages().contains(lang)) {
        return e.provider();
    }
}
```

Option B (constructor requires non-null, callers pass `Set.of()`):

```java
public record Entry(String name, LlmProvider provider, Set<String> supportedLanguages) {
    public Entry {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Entry.name must be non-empty");
        }
        supportedLanguages = Set.copyOf(supportedLanguages);
    }
}
```

Either way, drop the `@Nullable` on the component and the `supported != null` check in `forTask`.

**Reasoning:**

The API surface should match the implementation. A `@Nullable` accessor that never returns null is a documentation bug that misleads anyone reading the type signature; the resulting null-guard is dead code that §7 forbids.

**Trade-offs:**

Option A keeps caller convenience (callers can still pass null and have it coerced), at the cost of a secondary constructor. Option B is simpler but forces every caller to write `Set.of()` for "no languages." Option B is cleaner if the existing call sites in `buildFromCdi` and the tests already pass non-null sets — they do, so Option B wins.

**Alternative options:**

- **Option A** — accept null via a secondary constructor, coerce inside, expose non-null accessor.
- **Option B** (recommended) — require non-null at construction, drop coercion and `@Nullable`. Simpler, smaller surface.

---

### F4. Anthropic response parser silently truncates multi-block content

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `AnthropicProvider.java:178-197`

**Current code:**

```java
private static LlmResponse parseContentText(String responseBody, URI uri) {
    JsonNode root;
    try {
        root = JSON.readTree(responseBody);
    } catch (IOException e) {
        throw new LlmCallFailedException(
            "AnthropicProvider: failed to parse JSON response from " + uri, e);
    }
    JsonNode content = root.path("content");
    if (!content.isArray() || content.isEmpty()) {
        throw new LlmCallFailedException(
            "AnthropicProvider: response missing content[] from " + uri);
    }
    JsonNode text = content.get(0).path("text");
    if (!text.isTextual()) {
        throw new LlmCallFailedException(
            "AnthropicProvider: response missing content[0].text from " + uri);
    }
    return new LlmResponse(text.asText());
}
```

**Why this is wrong / suboptimal / risky:**

The Anthropic Messages API returns `content` as an array of typed blocks. A response may contain:

- `{"type":"text", "text":"..."}` — the textual reply
- `{"type":"thinking", "thinking":"..."}` (extended-thinking models) — model reasoning
- `{"type":"tool_use", ...}` — tool invocation
- Multiple text blocks (less common, but contractual)

The current code reads `content[0].text` only. If the model emits any non-text block first (extended-thinking is the most common: Claude 3.7 Sonnet, claude-sonnet-4 with `thinking` enabled), the first block carries no `text` field — the code throws `LlmCallFailedException` and the Stage 2 / summarizer call fails infrastructure-style. If the API ever emits a leading metadata block, the same. This is a contract drift with the Anthropic API.

The spec promises an LLM call returns prose; receiving thinking+text on the same call should yield the text concatenated (or just the text block), not a failure.

A second, subtler concern: if the model emits text in `content[0]` AND another text block in `content[1]`, the second block is silently dropped. For a summarizer this is a content-truncation bug invisible to downstream callers.

**Recommended fix:**

```java
private static LlmResponse parseContentText(String responseBody, URI uri) {
    JsonNode root;
    try {
        root = JSON.readTree(responseBody);
    } catch (IOException e) {
        throw new LlmCallFailedException(
            "AnthropicProvider: failed to parse JSON response from " + uri, e);
    }
    JsonNode content = root.path("content");
    if (!content.isArray() || content.isEmpty()) {
        throw new LlmCallFailedException(
            "AnthropicProvider: response missing content[] from " + uri);
    }
    StringBuilder out = new StringBuilder();
    for (JsonNode block : content) {
        if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
            out.append(block.path("text").asText());
        }
    }
    if (out.isEmpty()) {
        throw new LlmCallFailedException(
            "AnthropicProvider: response carried no text content blocks from " + uri);
    }
    return new LlmResponse(out.toString());
}
```

**Reasoning:**

Iterating typed blocks and concatenating only the `type=="text"` payloads matches the wire contract. Thinking blocks are correctly skipped (they are not user-facing text); a multi-text-block reply is correctly concatenated. Failure is reserved for the actual semantic failure (no text emitted at all), which is a schema-violation per spec §Failure handling.

**Trade-offs:**

A `StringBuilder` allocation per call (negligible). Thinking-block content is dropped from the provider's perspective, which is intentional — extended thinking is not "the reply."

---

### F5. `joinPath` and `preview` are duplicated verbatim across three provider classes

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

`OpenAiCompatibleProvider.java:257-273`:
```java
private static String joinPath(String base, String path) {
    if (base.endsWith("/")) {
        return base.substring(0, base.length() - 1) + path;
    }
    return base + path;
}

private static String preview(String s) {
    if (s == null) {
        return "<null>";
    }
    if (s.length() <= 200) {
        return s;
    }
    return s.substring(0, 200) + "…(" + s.length() + " bytes)";
}
```

`OpenAiCompatibleEmbeddingProvider.java:222-238`: same two helpers, byte-identical.

`AnthropicProvider.java:215-230`: same two helpers, byte-identical.

The package already has a `LlmHttpSupport` final class whose javadoc explicitly scopes it as "Shared response-hardening helpers for the LLM / embedding HTTP provider impls in this package."

**Why this is wrong / suboptimal / risky:**

`CLAUDE.md` §Simplify aggressively: "If a simpler form meets the same goal, prefer it." Three identical copies of one helper invite drift the moment any of the three providers tweaks edge-case handling (trailing-slash normalization, body truncation length, null-handling). `LlmHttpSupport` is the right home and is already package-private to the three providers. Worse, `OpenAiCompatibleEmbeddingProvider`'s comment now reads:

> "kept inline rather than extracted to a shared util because the helper is two branches and pulling it into a third class would add an abstraction without enough callers to justify the file."

That rationale was true when there was no shared util; `LlmHttpSupport` now exists as the third class, so the comment is stale and the duplication argument no longer holds.

**Recommended fix:**

Move both helpers into `LlmHttpSupport`:

```java
final class LlmHttpSupport {
    // ... existing members ...

    static String joinPath(String base, String path) {
        if (base.endsWith("/")) {
            return base.substring(0, base.length() - 1) + path;
        }
        return base + path;
    }

    static String preview(String s) {
        if (s.length() <= 200) {
            return s;
        }
        return s.substring(0, 200) + "…(" + s.length() + " bytes)";
    }
}
```

Note: the `s == null` arm is dropped — see Finding F8.

Delete the local copies in the three providers; update call sites to `LlmHttpSupport.joinPath(...)` and `LlmHttpSupport.preview(...)`.

**Reasoning:**

One source of truth for two operations that are inherently shared across the package's three providers. Any future tweak (e.g. lifting the 200-char preview cap, normalizing double-slashes) happens in one place.

**Trade-offs:**

None — strictly better. The helpers are already package-private; the move keeps that scope.

---

### F6. `validateLocalOnlyConfiguration` is documented as a "pure function" but performs DNS I/O

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `LlmRouterStartupGuard.java:159-179, 263-284`

**Current code:**

```java
/**
 * Pure-function validator: examines the supplied key/value
 * snapshot and throws {@link LocalOnlyConflictException} when
 * ...
 * <p>Public for test invocation: {@code LocalOnlyConflictStartupIT}
 * (in the {@code infochat-collector} module's
 * {@code app.zcat.infochat.collector.eval.stage2} package) invokes
 * this validator directly with a hand-rolled snapshot, side-
 * stepping the @Startup-throws-aborts-boot mechanism that makes
 * the CDI path awkward to test from inside a normal @Test
 * method.
 */
public static void validateLocalOnlyConfiguration(@NonNull Map<String, String> snapshot) {
    ...
}

private static boolean isLoopback(String baseUrl) {
    ...
    try {
        InetAddress addr = InetAddress.getByName(host);
        return addr.isLoopbackAddress();
    } catch (UnknownHostException e) {
        ...
    }
}
```

**Why this is wrong / suboptimal / risky:**

The docstring claims "pure-function validator." `InetAddress.getByName(host)` issues a DNS resolution — that is not pure (it can block, it depends on global system state, and its result can change between calls). The test class `LlmRouterStartupGuardLocalOnlyTest` comments around line 41 also lean on this property:

> "A remote host literal (api.openai.com) is treated as non-loopback whether or not DNS resolves it (a failed lookup also counts as non-loopback), so the assertions are stable offline."

That note papers over the impurity but does not eliminate it. A reader who trusts the javadoc may assume the function can be called from an offline test, called from a unit test framework that forbids network I/O, or called from a hot path without worrying about latency — all wrong assumptions.

A second, related concern: the loopback check at startup time is the only check; the spec acknowledges this explicitly ("DNS-rebind window ... acceptable here"). But the docstring saying "pure" obscures the fact that the loopback determination depends on /etc/hosts and the resolver, which can vary between the operator's startup environment and the actual call-time environment.

**Recommended fix:**

```java
/**
 * Validator: examines the supplied key/value snapshot and throws
 * {@link LocalOnlyConflictException} when {@code infochat.llm.local-only=true}
 * is set alongside any off-host route. <b>Not pure</b>: invokes
 * {@link InetAddress#getByName(String)} via {@link #isLoopback(String)} to
 * resolve each base-url's host, so this method performs DNS lookups and may
 * block on the system resolver. Failed lookups are treated as non-loopback
 * (fail-safe), so the function returns or throws deterministically given a
 * stable DNS view; results may differ between hosts.
 *
 * <p>Public for test invocation ...
 */
```

**Reasoning:**

Reset the contract to match the implementation. Readers and test authors should know that calling this from an offline context costs a DNS lookup and that the result is environment-dependent.

**Trade-offs:**

None — strictly better. The fix is documentation-only.

---

### F7. Inconsistent provider-name normalization between router and startup guard

- **Category:** SECURITY
- **Severity:** low
- **Location:** `LlmRouter.java:148-156`, `LlmRouterStartupGuard.java:144, 210-211`

**Current code:**

In `LlmRouterStartupGuard`:

```java
private static final Set<String> REMOTE_PROVIDER_NAMES = Set.of(AnthropicProvider.PROVIDER_NAME);
...
String providerName = stripOrEmpty(snapshot.get(providerKey)).toLowerCase(Locale.ROOT);
if (REMOTE_PROVIDER_NAMES.contains(providerName)) {
    offenders.add("task=" + kv.getKey().name()
        + " key=" + providerKey + " provider=" + providerName);
}
```

In `LlmRouter.forTask`:

```java
Optional<String> overrideName = config.get(overrideKey);
if (overrideName.isPresent() && !overrideName.get().isEmpty()) {
    Entry entry = entriesByName.get(overrideName.get());
    ...
}
```

And the registration side:

```java
for (LlmProvider p : providers) {
    String name = p.providerName();
    ...
    out.add(new Entry(name, p, langs));
}
```

`AnthropicProvider.PROVIDER_NAME = "anthropic"` (lowercase).

**Why this is wrong / suboptimal / risky:**

The guard lower-cases the operator-supplied provider name before comparing to `REMOTE_PROVIDER_NAMES`. The router does NOT lower-case — `entriesByName.get(overrideName.get())` is case-sensitive.

Construct a config:

```
infochat.llm.local-only=true
infochat.llm.chat.provider=Anthropic   (capital A)
```

Behavior:
1. The startup guard lower-cases `"Anthropic"` to `"anthropic"`, matches `REMOTE_PROVIDER_NAMES`, throws `LocalOnlyConflictException`. Good — startup fails.

But construct:

```
infochat.llm.local-only=false
infochat.llm.chat.provider=Anthropic
```

Behavior:
1. The guard's local-only check is skipped.
2. At call time, `LlmRouter.forTask(CHAT_AGENT, ...)` reads `overrideName = "Anthropic"`, tries `entriesByName.get("Anthropic")`, gets null, throws `IllegalStateException`.

That's loud but at the wrong site. More importantly, the registration side and the guard side disagree about normalization: an operator who flips local-only off and uses `Anthropic` (mixed case) gets a fatal runtime error on the first chat call, NOT a startup error — defeating the rule "checked once at startup, not per call."

If the registration side later lower-cases for consistency (a plausible future tweak), the asymmetric normalization could even let `Anthropic` route to the anthropic provider WITHOUT the local-only guard catching it (depending on the order of changes). Defence in depth is missing.

**Recommended fix:**

Normalize provider names at both registration and lookup time:

```java
// In buildFromCdi
String name = p.providerName().toLowerCase(Locale.ROOT);

// In forTask
String overrideRaw = overrideName.get().toLowerCase(Locale.ROOT);
Entry entry = entriesByName.get(overrideRaw);

// In MicroProfileConfigReader.get (already trims; also normalize)
return delegate.getOptionalValue(key, String.class)
    .map(s -> s.trim().toLowerCase(Locale.ROOT))
    .map(s -> s.equals("null") ? "" : s);
```

(Or define `providerName()` to be case-insensitive by spec and document it.)

**Reasoning:**

A single normalization rule applied at every comparison point removes the gap. Provider names are operator-visible strings drawn from a small enum-like set ("openai-compatible", "anthropic"); case-sensitive matching brings no benefit.

**Trade-offs:**

Operator can no longer use case to distinguish two providers — which would itself be a confusing pattern, so this is mostly a feature.

**Alternative options:**

- **Option A** (recommended) — normalize at every comparison point.
- **Option B** — require provider names to be lowercase at the SPI level (`LlmProvider.providerName()` doc says "MUST be lower-case ASCII") and validate at registration. Same effect, stronger contract.

---

### F8. `extractErrorMessage` catches an impossible `IOException`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `AnthropicProvider.java:203-213`

**Current code:**

```java
private static String extractErrorMessage(String body) {
    try {
        JsonNode root = JSON.readTree(body);
        if ("error".equals(root.path("type").asText())) {
            return preview(root.path("error").path("message").asText("(no message)"));
        }
    } catch (IOException ignored) {
        // Fall through to preview
    }
    return preview(body);
}
```

**Why this is wrong / suboptimal / risky:**

`ObjectMapper.readTree(String)` is declared `throws JsonProcessingException` (a subclass of `IOException`). It cannot throw a plain `IOException` because there is no underlying stream. The `catch (IOException ignored)` arm catches what the call advertises but not what actually fires; more importantly, the `// Fall through to preview` comment turns a legitimate parse failure into a silent fallback to the raw body preview. If the server returns malformed JSON, the operator sees no diagnostic about why structured error extraction failed.

Engineering rule §7 forbids defensive code for impossible scenarios; this catch arm is partially that (catching a broader exception than actually thrown) AND partially a swallow. Either narrow it to `JsonProcessingException` and at least log the failure, or remove the broader catch and let the call fall through naturally if the response is not JSON.

**Recommended fix:**

```java
private static String extractErrorMessage(String body) {
    try {
        JsonNode root = JSON.readTree(body);
        if ("error".equals(root.path("type").asText())) {
            return preview(root.path("error").path("message").asText("(no message)"));
        }
    } catch (JsonProcessingException notJson) {
        // Server returned a non-JSON error body (e.g. a bare HTML 502
        // from an intermediate gateway); fall through to raw preview.
    }
    return preview(body);
}
```

**Reasoning:**

Narrowing to `JsonProcessingException` reflects the actual API surface of `readTree(String)`. Renaming the variable from `ignored` to `notJson` makes the intent explicit — the caller is "this is the expected case when Anthropic-style error JSON is absent," not "swallow whatever." `preview(body)` similarly should not handle null (see also Finding F5).

**Trade-offs:**

None — strictly better.

---

### F9. Per-call MicroProfile lookups for stable config

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** `AnthropicProvider.java:104-112`

**Current code:**

```java
private TaskConfig configFor(ModelTask task) {
    String prefix = "infochat.llm." + task.keySegment() + ".";
    String baseUrl = config.getValue(prefix + "base-url", String.class);
    String apiKey = config.getOptionalValue(prefix + "api-key", String.class).orElse("");
    String model = config.getValue(prefix + "model", String.class);
    long timeoutMs = config.getOptionalValue(prefix + "timeout-ms", Long.class).orElse(30000L);
    int maxTokens = config.getValue(prefix + "max-tokens", Integer.class);
    return new TaskConfig(baseUrl, apiKey, model, timeoutMs, maxTokens);
}
```

`config.getValue` is called five times per `generate` call. The bean is `@ApplicationScoped` and operates on profile-static config (config values do not change at runtime in v1).

**Why this is wrong / suboptimal / risky:**

The class-level comment explicitly defends this choice:

> "Config is read dynamically via {@link Config} rather than per-field {@code @ConfigProperty} injection. With 6 tasks × 5 properties = 30 fields, dynamic lookup is cleaner."

Cleanliness is fair — but the choice trades per-call CPU for declaration brevity. Each `Config.getValue` walks the source chain (system properties → env → application.properties → microprofile-config.properties → defaults), parses the string, and returns. For Stage 2 / summarizer / digest hot paths that issue many LLM calls per second under load, that's measurable overhead. The comment justifies the dynamic-lookup style but not the per-call repetition; a one-time per-task cache lookup would have the same brevity.

**Recommended fix:**

Compute the `TaskConfig` lazily once per task and cache:

```java
private final Map<ModelTask, TaskConfig> taskConfigs = new ConcurrentHashMap<>();

private TaskConfig configFor(ModelTask task) {
    return taskConfigs.computeIfAbsent(task, this::loadConfig);
}

private TaskConfig loadConfig(ModelTask task) {
    String prefix = "infochat.llm." + task.keySegment() + ".";
    String baseUrl = config.getValue(prefix + "base-url", String.class);
    String apiKey = config.getOptionalValue(prefix + "api-key", String.class).orElse("");
    String model = config.getValue(prefix + "model", String.class);
    long timeoutMs = config.getOptionalValue(prefix + "timeout-ms", Long.class).orElse(30000L);
    int maxTokens = config.getValue(prefix + "max-tokens", Integer.class);
    return new TaskConfig(baseUrl, apiKey, model, timeoutMs, maxTokens);
}
```

**Reasoning:**

Per-task config is immutable after startup; caching avoids five MicroProfile lookups per `generate` call without changing the operator-facing config model. The `ConcurrentHashMap` is thread-safe and the lambda runs at most once per task.

**Trade-offs:**

- Slight increase in code surface (the cache field + helper).
- A config-change-at-runtime story is gone, but MicroProfile config in Quarkus is already effectively immutable post-startup, so no real loss.

---

### F10. Stale rationale comment in `OpenAiCompatibleEmbeddingProvider.joinPath`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `OpenAiCompatibleEmbeddingProvider.java:214-227`

**Current code:**

```java
/**
 * Concatenate {@code base} + {@code path} with exactly one slash
 * between them. Same helper shape as
 * {@link OpenAiCompatibleProvider#joinPath} — kept inline rather
 * than extracted to a shared util because the helper is two
 * branches and pulling it into a third class would add an
 * abstraction without enough callers to justify the file.
 */
private static String joinPath(String base, String path) {
```

**Why this is wrong / suboptimal / risky:**

The rationale "pulling it into a third class would add an abstraction without enough callers to justify the file" was true when no shared util existed. `LlmHttpSupport` is now that file — it already lives in the same package, already hosts `boundedStringHandler`, `clampBodyCapBytes`, `retryAfterMsFor`, etc. The justification is stale; the comment defends an out-of-date decision.

This finding overlaps with F5; it is listed separately because the comment itself is the bug, not just the duplication. A reader applying F5's refactor needs to know to delete this comment too.

**Recommended fix:**

Apply Finding F5 — move `joinPath` into `LlmHttpSupport` and delete the local copy + the misleading comment.

**Reasoning:**

Outdated rationale comments are an active maintenance hazard: they steer the next reader away from the correct refactor.

**Trade-offs:**

None — strictly better.

---

## Cross-module notes

Two items observed outside the module but worth flagging briefly for the cross-cutting reviewer:

- The provider HTTP path does NOT funnel through `infochat-ssrf`'s `SsrfGuardedHttpClient`. `LlmHttpSupport`'s class comment acknowledges this explicitly ("these calls do not pass through the `infochat-ssrf` guard's `readBounded`") and frames the LLM endpoint as operator-configured / semi-trusted. The framing is reasonable, but it depends on operators not pointing a per-task `base-url` at an attacker-controlled host. The local-only guard mitigates this when set, but a relaxed deployment trusts every operator-configured URL. Not strictly a bug in this module — the choice is consciously made — but a deployment reviewer should keep this surface in mind.
- The `Anthropic` provider name normalization concern (Finding F7) interacts with the local-only guard's `REMOTE_PROVIDER_NAMES` set. Adding any new cloud-only provider in the future requires editing both the LLM module (new `*Provider` class) and the guard's constant — the coupling is not enforced. Consider a `LlmProvider` capability method like `boolean isCloudOnly()` so the guard discovers cloud-only providers via CDI instead of via a hand-maintained constant.
