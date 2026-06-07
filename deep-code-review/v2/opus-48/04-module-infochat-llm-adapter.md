# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-06
**Reviewer:** senior-developer (opus)

## Headline findings

- [HIGH] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java:157-166 — the default provider hard-throws for 5 of 6 `ModelTask`s while production call sites exist for all 6, so every default (local-Ollama) deployment permanently degrades tagger, entity, summarizer, chat-agent, and translator to their failure paths and silently ignores the operator's configured `infochat.llm.tagger.*` / `infochat.llm.entity.*` keys.
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:180-215 — an explicitly configured but unknown `infochat.llm.default.provider` falls back to `entries.get(0)` (CDI discovery order) with a one-shot WARN instead of failing startup, contradicting the spec's "never silently switch provider" rule and the module's own per-task-override posture.
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java:100-135 — the entire Retry-After machinery is dead in production (no caller ever reads `retryAfterMs()`), the exception javadocs assert sleeping behavior the Stage 2 / embedding workers do not have, and the parse is unclamped/overflow-prone if it is ever wired.
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:332-341 — `Entry.supportedLanguages` is annotated `@Nullable` but the compact constructor normalizes null to empty, making the accessor effectively non-null; the annotation forces a dead null-check in `forTask` and the component javadoc contradicts the code ("Empty means 'any'" while the branch skips empty sets).
- [LOW] MAINTAINABILITY-RULES-DRIFT — cross-cutting (see CURRENT-CODE) — §7 defensive code inside the trust boundary: dead null-check on a `@NonNull` parameter in `LlmRouter.forTask`, `s == null` checks in three private `preview` helpers whose argument can never be null, and `catch (RuntimeException ...)` around in-memory Jackson tree assembly that cannot throw.
- [LOW] MAINTAINABILITY-RULES-DRIFT — cross-cutting (see CURRENT-CODE) — ticket IDs, reviewer-workflow references, and stale claims embedded in production comments (10 occurrences across three files, including a `buildFromCdi` javadoc that is now factually false).
- [LOW] MAINTAINABILITY-RULES-DRIFT — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java:28 — the `CHAT_AGENT` key segment is `chat` in code but every operator-facing example in `docs/design/05-llm-and-embeddings.md` spells it `chat-agent`; one of the two must be corrected.
- [LOW] SECURITY — infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java:144,200-215 — under `local-only=true` the guard rejects `infochat.llm.<task>.provider=anthropic` but accepts `infochat.llm.default.provider=anthropic`, which expresses the identical operator intent; no data-leak path exists (the base-url scan still catches off-host routes) but the posture is inconsistent.
- [LOW] SIMPLIFICATION — cross-cutting (see CURRENT-CODE) — `joinPath` and `preview` are copy-pasted across all three provider impls while `LlmHttpSupport` already exists as the shared package-private support class; the inline-justification comment claims a shared home would require "a third class" that in fact already exists.

## Detail

### F1. Default provider throws `UnsupportedOperationException` for 5 of 6 tasks that production already routes to it

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java:157-166

**Current code:**

```java
private TaskConfig configFor(ModelTask task) {
    return switch (task) {
        case SECURITY_JUDGE -> new TaskConfig(
            securityBaseUrl, securityApiKey.orElse(""), securityModel, securityTimeoutMs);
        case TAGGER, ENTITY, SUMMARIZER, CHAT_AGENT, TRANSLATOR ->
            throw new UnsupportedOperationException(
                "OpenAiCompatibleProvider: no per-task config wired for " + task
                    + " yet (M1-033 wires SECURITY_JUDGE only)");
    };
}
```

**Why this is wrong / suboptimal / risky:**

`OpenAiCompatibleProvider` is the router's priority-3 default (`LlmRouter.java:178`) and the only host-neutral provider — every deployment that follows the spec's "local-first by default" goal (`docs/spec/llm.md` §Goals 1) routes all six tasks to it. Production call sites for the other five tasks already exist and are live:

- `infochat-collector .../eval/tagger/TaggerWorker.java:228,290` — `forTask(TAGGER, "en")` → `generate(ModelTask.TAGGER, ...)`
- `infochat-collector .../eval/entity/EntityExtractorWorker.java:206,233` — `ENTITY`
- `infochat-provider .../summary/SummaryProseGenerator.java:104` — `SUMMARIZER`
- `infochat-provider .../chat/ChatAgent.java:151`, `.../command/CompressCommandHandler.java:159` — `CHAT_AGENT`
- `infochat-provider .../translation/LlmTranslationProvider.java:86-87` — `TRANSLATOR`

In the default profile (no per-task `provider` override, no `%remote-llm`), every one of those calls hits this `throw`. Each worker catches generic `RuntimeException` (e.g. `TaggerWorker.java:291`, `Stage2Worker.java:205`) and routes to its retry-then-fallback path, so the `UnsupportedOperationException` — a programming-error signal — is misclassified as transient LLM infra failure: the tagger retries the throw, then falls back to bootstrap tags on **every post, forever**; the entity extractor releases every post without entities; `/summary` always returns the "summarizer unavailable" raw list; chat always replies "couldn't reach the model"; non-English scopes always get the English fallback.

Worse, the collector's `application.properties` already ships operator config for the tasks this switch rejects (`infochat.llm.tagger.base-url/model/api-key` at lines 339-342, `infochat.llm.entity.*` at lines 353-356). Those keys are dead: the provider never reads them. An operator who tunes `infochat.llm.tagger.model` sees no effect and no error other than throttled "tagger fallback" notifications.

This violates the spec contract that per-task routing works for all six tasks (`docs/spec/llm.md` §SPI shape, §Per-task routing rules) and the module's sibling already demonstrates the correct shape: `AnthropicProvider.configFor` (AnthropicProvider.java:104-112) reads the exact same key pattern dynamically for every task.

**Recommended fix:**

```java
// Replace the six per-field @ConfigProperty injections with the
// dynamic read AnthropicProvider already uses (same key pattern):
private final Config config;

@Inject
public OpenAiCompatibleProvider(Config config) {
    this.config = config;
    this.http = HttpClient.newHttpClient();
}

private TaskConfig configFor(ModelTask task) {
    String prefix = "infochat.llm." + task.keySegment() + ".";
    return new TaskConfig(
        config.getValue(prefix + "base-url", String.class),
        config.getOptionalValue(prefix + "api-key", String.class).orElse(""),
        config.getValue(prefix + "model", String.class),
        config.getOptionalValue(prefix + "timeout-ms", Long.class).orElse(30000L));
}
```

(The refactor also removes the currently unused `jakarta.inject.Inject` import at OpenAiCompatibleProvider.java:13 by actually using it.)

**Reasoning:**

The two providers then read the identical property surface through the identical mechanism, the dead `infochat.llm.tagger.*` / `infochat.llm.entity.*` keys come alive, and a routed task with config present simply works. A routed task with config *missing* fails with `NoSuchElementException` naming the missing key — a far more diagnosable failure than `UnsupportedOperationException` masquerading as LLM downtime. The Anthropic sibling's own javadoc (AnthropicProvider.java:55-58) already argues this design: "With 6 tasks × 5 properties = 30 fields, dynamic lookup is cleaner."

**Trade-offs:**

- `@ConfigProperty` field injection fails Quarkus startup when `infochat.llm.security.base-url` is absent; the dynamic read moves that to first-call time. Mitigation: `LlmRouterStartupGuard` already snapshots every per-task key (`snapshotConfig`, LlmRouterStartupGuard.java:297-309) and is the natural place to assert presence of `base-url`/`model` for wired tasks at startup.
- Slightly more work per call (config lookups instead of field reads); MicroProfile Config lookups are cheap relative to an LLM HTTP round trip.

**Alternative options:**

- **Option A** (the recommended fix above).
- **Option B** — keep field injection and add 5 × 4 more `@ConfigProperty` fields plus switch arms — pros: startup-time validation for all keys; cons: 30 fields, the exact shape the Anthropic javadoc rejects, and another file edit for every future task-related property.
- **Option C** — if the missing wiring is intentionally deferred, at minimum the workers must distinguish `UnsupportedOperationException` from infra failure so the misconfiguration is loud; that is a worse fix because it spreads provider-internal knowledge into every worker.

---

### F2. Unknown configured default provider falls back to CDI-discovery order instead of failing startup

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:180-215

**Current code:**

```java
Entry defaultEntry = entriesByName.get(defaultName);
if (defaultEntry == null) {
    // ... 24-line comment ...
    if (configuredDefault.isPresent()
            && warnedUnknownDefault.compareAndSet(false, true)) {
        LOG.warnf(
            "LlmRouter: %s='%s' is an unknown default provider "
                + "(registered providers: %s); falling back to first "
                + "registered entry '%s'. ...",
            CONFIG_KEY_DEFAULT_PROVIDER, defaultName,
            entriesByName.keySet(), entries.get(0).name());
    }
    defaultEntry = entries.get(0);
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/llm.md` §Per-task routing rules: the router "does NOT silently switch to a different configured provider." A typo in `infochat.llm.default.provider` (e.g. `anthropc`) reroutes **every task with no per-task override** — including `SECURITY_JUDGE` — to whichever bean CDI `Instance` iteration happens to list first, which with two registered providers is unspecified and can change across builds. One WARN per JVM is the only signal, and on a long-running collector that line scrolls out of view immediately.

The posture is also internally inconsistent: a typo in a *per-task* override throws `IllegalStateException` (LlmRouter.java:151-153) and `assertAllTasksResolve()` converts that into a startup failure, while a typo in the *default* key keeps serving. The in-code justification (lines 192-203) says fail-startup was rejected because (1) the ticket's out-of-scope list forbade touching `LlmRouterStartupGuard` and (2) test fixtures rely on silent fallback. Neither holds against the code as it exists now: throwing from `forTask` requires no guard change — `assertAllTasksResolve()` (already invoked from the guard's `@PostConstruct`, LlmRouterStartupGuard.java:156) turns the throw into a startup abort through the exact mechanism the per-task case uses; and the test-fixture path is the *unconfigured*-default case (`configuredDefault.isEmpty()`), which can keep its silent fallback untouched. Shaping production failure semantics around a ticket's scope fence and test-boot log noise, then documenting that in a comment, is exactly the kind of decision §3/§4 say should have been surfaced rather than baked in.

**Recommended fix:**

```java
Entry defaultEntry = entriesByName.get(defaultName);
if (defaultEntry == null) {
    if (configuredDefault.isPresent()) {
        throw new IllegalStateException(
            "LlmRouter: " + CONFIG_KEY_DEFAULT_PROVIDER + "='" + defaultName
                + "' names no registered provider (registered: "
                + entriesByName.keySet() + ")");
    }
    // Unconfigured default whose implicit name is absent (test-fixture
    // deployments): fall back to the only registered entry.
    defaultEntry = entries.get(0);
}
```

**Reasoning:**

An explicitly-set-but-unresolvable default becomes a startup failure (via the existing `assertAllTasksResolve()` scan), identical in shape and timing to the per-task-override case, and the spec's no-silent-switch commitment holds for the default key too. The `warnedUnknownDefault` `AtomicBoolean` and its 24-line justification comment are deleted. The unconfigured-default test path keeps working unchanged.

**Trade-offs:**

- Availability: the current posture keeps the system serving (on the wrong provider) where the fix refuses to start. For a misrouted security judge, refusing to start is the correct trade.
- `LlmRouterUnknownDefaultTest.unknownConfiguredDefaultProviderFallsBackToFirstEntryWithOneShotWarn` asserts the current behavior and would need rewriting to assert the throw — per §8 that modification requires explicit ticket authorization stating the new assertion and why.

---

### F3. Retry-After machinery is dead code whose javadoc asserts consumer behavior that does not exist; parse is unclamped if ever wired

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java:100-135 (plus OpenAiCompatibleProvider.java:304-313, OpenAiCompatibleEmbeddingProvider.java:264-273)

**Current code:**

```java
// OpenAiCompatibleProvider.LlmCallFailedException:
/**
 * Server-advised retry delay in milliseconds parsed from a
 * 429/503 {@code Retry-After} header, or 0 when the response
 * carried no such advice. The Stage 2 worker's retry-once
 * harness sleeps this long before re-issuing the call instead of
 * immediately re-hitting the rate limit.
 */
public long retryAfterMs() {
    return retryAfterMs;
}
```

```java
// LlmHttpSupport.parseRetryAfterMs:
try {
    long seconds = Long.parseLong(raw);
    return seconds <= 0 ? 0L : seconds * 1000L;
} catch (NumberFormatException notDeltaSeconds) {
    // Not an integer — fall through to the HTTP-date form.
}
```

**Why this is wrong / suboptimal / risky:**

No production code ever reads `retryAfterMs()`. The Stage 2 worker catches bare `RuntimeException` and returns to its own retry policy (`Stage2Worker.java:205-209`); the embedding worker does the same (`EmbeddingWorker.java:266-269`); a repo-wide grep finds the only readers are this module's own tests. The javadoc on both exception accessors states, as fact, that "The Stage 2 worker's retry-once harness sleeps this long" / "The EmbeddingWorker's retry-once harness sleeps this long" — both statements are false. A future reader debugging rate-limit behavior will trust the javadoc and look in the wrong place. The class-level javadoc of `LlmHttpSupport` (lines 33-37) makes the same false claim ("so the caller can sleep before its single retry").

Secondary: if a consumer ever does wire the sleep, the value is attacker/peer-controlled and unbounded — `Retry-After: 99999999` produces a ~3-year sleep, and `seconds * 1000L` can overflow to a negative value for very large inputs (the `<= 0` check runs *before* the multiply). A remote OpenAI-compatible endpoint is operator-chosen but third-party; a compromised or misbehaving endpoint wedging bounded-concurrency workers in multi-day sleeps is a pipeline-stall vector.

**Recommended fix:**

```java
// 1. Correct the three javadocs to describe what exists:
/**
 * Server-advised retry delay in milliseconds parsed from a 429/503
 * {@code Retry-After} header, or 0 when the response carried no such
 * advice. Callers MAY honor it before retrying; no production caller
 * does yet.
 */

// 2. Clamp the parse so a wired consumer can sleep it blindly:
private static final long MAX_RETRY_AFTER_MS = 60_000L;

long seconds = Long.parseLong(raw);
if (seconds <= 0) {
    return 0L;
}
return Math.min(seconds, MAX_RETRY_AFTER_MS / 1000L) * 1000L;
// (apply the same Math.min to the HTTP-date branch's deltaMs)
```

**Reasoning:**

The javadoc fix removes a documented lie about cross-module behavior. The clamp makes the value safe-by-construction at the boundary where the untrusted header is parsed, so every future consumer inherits the bound instead of each re-implementing it; it also eliminates the overflow. Whether the workers should actually honor `retryAfterMs` (design note 05 §5.8 backoff table suggests yes) is a collector-side follow-up ticket, not a reason to keep false documentation here.

**Trade-offs:**

None — the fix is strictly better. (If genuine multi-minute Retry-After advice ever matters, the 60 s cap is one constant to revisit.)

---

### F4. `Entry.supportedLanguages` carries a false `@Nullable` contract that generates dead code and a contradictory javadoc

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:332-341 (dead check at 161-171)

**Current code:**

```java
/**
 * @param supportedLanguages  ISO 639-1 codes the provider can
 *                            emit. Empty means "any" — the
 *                            language-aware branch skips empty
 *                            sets so a generic provider doesn't
 *                            front-run a capability-declaring one.
 */
public record Entry(@NonNull String name, @NonNull LlmProvider provider, @Nullable Set<String> supportedLanguages) {
    public Entry {
        supportedLanguages = supportedLanguages == null
            ? Set.of()
            : Set.copyOf(supportedLanguages);
    }
}
```

```java
// forTask, priority 2:
// supportedLanguages() is @Nullable per the Entry component
// contract; a null reads as "no declared language" and skips
// the entry, matching the compact constructor's null→empty
// normalization.
Set<String> supported = e.supportedLanguages();
if (supported != null && supported.contains(lang)) {
```

**Why this is wrong / suboptimal / risky:**

§7a requires the nullability annotation to *be* the contract. Here it is the opposite of the truth: because the compact constructor normalizes null to `Set.of()`, the record's accessor can never return null — yet the component annotation says it can, which forces NullAway to demand the `supported != null` check in `forTask`, which is permanently dead code, which then needs a four-line comment explaining a null case that cannot occur. No caller passes null anyway: `buildFromCdi` passes the non-null result of `supportedLanguagesFor`, and every test constructs entries with `Set.of(...)`. The whole nullable-component + normalization + dead-check + explanatory-comment chain exists to support a call shape nobody uses.

Separately, the javadoc asserts «Empty means "any"» and in the same sentence says the branch *skips* empty sets — empty therefore means "matches no language", the exact opposite of "any".

**Recommended fix:**

```java
/**
 * @param supportedLanguages  ISO 639-1 codes the provider can emit.
 *                            An empty set declares no languages, so
 *                            the language-aware branch never selects
 *                            this entry.
 */
public record Entry(String name, LlmProvider provider, Set<String> supportedLanguages) {
    public Entry {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Entry.name must be non-empty");
        }
        supportedLanguages = Set.copyOf(supportedLanguages);
    }
}
```

```java
// forTask, priority 2:
if (e.supportedLanguages().contains(lang)) {
    return e.provider();
}
```

**Reasoning:**

The signature now states the real contract (non-null, package default), `Set.copyOf` throws on a null argument so an illegal call fails loudly at construction, the dead branch and its comment disappear, and the javadoc matches the behavior.

**Trade-offs:**

None — the fix is strictly better.

---

### F5. §7 defensive code inside the trust boundary: dead null-checks and catches around operations that cannot throw

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// LlmRouter.java:139-142 — parameter is @NonNull in a null-marked package:
public LlmProvider forTask(@NonNull ModelTask task, @Nullable String scopeLanguage) {
    if (task == null) {
        throw new IllegalArgumentException("LlmRouter.forTask: task must be non-null");
    }
```

```java
// OpenAiCompatibleProvider.java:265-268 (identical copies at
// AnthropicProvider.java:222-225, OpenAiCompatibleEmbeddingProvider.java:230-233);
// the argument is always the non-null String produced by BoundedStringSubscriber:
private static String preview(String s) {
    if (s == null) {
        return "<null>";
    }
```

```java
// OpenAiCompatibleProvider.java:185 (identical at AnthropicProvider.java:134,
// OpenAiCompatibleEmbeddingProvider.java:130) — createObjectNode/put/putArray
// on an in-memory tree cannot throw; only the checked
// JsonProcessingException from writeValueAsString needs handling:
} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
```

**Why this is wrong / suboptimal / risky:**

§7: internal code calling internal code is trusted; no null-checks for parameters callers cannot legally pass null for, no try/catch around operations that cannot throw. The `task == null` check is dead under NullAway's package-wide non-null default and duplicates the machine-checked contract by hand — the exact pattern §7a exists to make unnecessary. The `preview` null branch can never execute: every call site passes `response.body()` from `BoundedStringSubscriber`, which completes only with a constructed `String`. The `RuntimeException` arm of the request-assembly catch converts an impossible failure ("Jackson tree building threw") into `LlmCallFailedException`, i.e. it would *mask a bug as LLM downtime* if it ever fired — the same misclassification pattern as F1. Only the checked `JsonProcessingException` needs the catch.

**Recommended fix:**

```java
// forTask: delete the null-check (the @NonNull contract is the guard).

// preview: delete the null branch.
private static String preview(String s) {
    if (s.length() <= 200) {
        return s;
    }
    return s.substring(0, 200) + "…(" + s.length() + " bytes)";
}

// request assembly: narrow the catch.
} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
```

**Reasoning:**

Removes seven dead branches across four files and restores the §7 invariant that a defensive check signals a real boundary. A genuine bug in body assembly then surfaces as itself instead of as a fake infra failure.

**Trade-offs:**

None — the fix is strictly better.

---

### F6. Ticket IDs, reviewer-workflow references, and stale claims in production comments

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// LlmRouterStartupGuard.java:96-98:
 * constants — referencing this field via a {@code Class.NAME}
 * qualifier doesn't satisfy the reviewer's regex grep for
 * {@code @Priority(150)}).
```

```java
// LlmRouter.java:262-269 (buildFromCdi javadoc) — false since AnthropicProvider landed:
 * beans Quarkus discovered. In v1, only
 * {@link OpenAiCompatibleProvider} is registered; future ticket
 * ...
 * (M1-007b) and adding a {@code capabilities()} method would
 * violate this ticket's out-of-scope list.
```

```java
// further occurrences:
// OpenAiCompatibleProvider.java:66  "the M1-033 ticket's Implementation notes"
// OpenAiCompatibleProvider.java:164 "(M1-033 wires SECURITY_JUDGE only)" — in a runtime exception message
// LlmRouterStartupGuard.java:110    "(M1-033's first call site)"
// LlmRouter.java:94,181,186,193     M1-042 / M1-033 workflow narrative
```

**Why this is wrong / suboptimal / risky:**

CLAUDE.md §Coding style: comments must not reference the current ticket, fix, or reviewer machinery — "that belongs in the commit message and rots as the codebase evolves." The rot is already visible: `buildFromCdi`'s "only OpenAiCompatibleProvider is registered" is false (two providers are registered), and "this ticket's out-of-scope list" is meaningless to any reader after the ticket merged. The `reviewer's regex grep` comment couples production source to a review-tooling implementation detail. The M1-033 reference inside a runtime exception message ships workflow vocabulary to operators' logs.

**Recommended fix:**

```java
// buildFromCdi — replace the stale paragraph with the timeless fact:
 * Build the production entry list from the live {@link LlmProvider}
 * beans Quarkus discovered. Language capability is config-driven
 * (see {@link #supportedLanguagesFor}) rather than an SPI method,
 * keeping the frozen LlmProvider surface unchanged.

// Elsewhere: delete the ticket IDs and reviewer references, keep the
// technical why (e.g. "annotation arguments must be compile-time
// constants" stands on its own). The exception message in configFor
// disappears entirely with F1.
```

**Reasoning:**

The technical content of each comment survives; the workflow narrative — which is already partially false — moves to where it lived all along (ticket files and commit messages, both greppable by ID).

**Trade-offs:**

None — the fix is strictly better.

---

### F7. `CHAT_AGENT` config-key segment is `chat` in code, `chat-agent` in the operator-facing design doc

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java:28

**Current code:**

```java
SUMMARIZER("summarizer"),
CHAT_AGENT("chat"),
TRANSLATOR("translator");
```

**Why this is wrong / suboptimal / risky:**

`docs/design/05-llm-and-embeddings.md` §5.1 and the §5.7 canonical profile table document `infochat.llm.chat-agent.provider`, `infochat.llm.chat-agent.model`, and `infochat.llm.chat-agent.max-concurrency`. The code (single source of truth via `keySegment()`, deliberately per its javadoc) reads `infochat.llm.chat.*`, and the shipped `application.properties` profiles already use `chat` (`%remote-llm.infochat.llm.chat.provider=anthropic`). An operator following the design doc sets `infochat.llm.chat-agent.model=...` and the property is silently ignored — there is no unknown-key diagnostic for the `infochat.*` namespace. Spec leaves the key shape to design notes, so the code is not spec-drift, but design and code must agree on the one operator-facing spelling.

**Recommended fix:**

```markdown
# docs/design/05-llm-and-embeddings.md — replace every
# `infochat.llm.chat-agent.` occurrence (§5.1 example block, §5.7 table)
# with `infochat.llm.chat.`:
infochat.llm.chat.provider=ollama
infochat.llm.chat.model=llama3.1:8b
```

**Reasoning:**

The code's spelling is already deployed in three property files, the guard's key map, and a test; the design note is the cheaper and safer side to change (design notes "may change without a spec amendment" by their own banner).

**Trade-offs:**

None — the fix is strictly better.

**Alternative options:**

- **Option A** (fix the design note, above).
- **Option B** — change `keySegment()` to `"chat-agent"` — pros: more descriptive key; cons: touches `ModelTask`, the guard map, three `application.properties` profile blocks, and `LlmRouterStartupGuardLocalOnlyTest`, for no functional gain.

---

### F8. Local-only guard rejects a cloud-only provider as a per-task override but accepts it as the global default

- **Category:** SECURITY
- **Severity:** low
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java:144, 200-215

**Current code:**

```java
private static final Set<String> REMOTE_PROVIDER_NAMES = Set.of(AnthropicProvider.PROVIDER_NAME);
...
String providerKey = providerKeyFor(kv.getValue());
String providerName = stripOrEmpty(snapshot.get(providerKey)).toLowerCase(Locale.ROOT);
if (REMOTE_PROVIDER_NAMES.contains(providerName)) {
    offenders.add("task=" + kv.getKey().name()
        + " key=" + providerKey + " provider=" + providerName);
}
```

**Why this is wrong / suboptimal / risky:**

The guard's stated rationale for the provider-name check is intent-level: "the operator selected a remote provider while claiming local-only" — regardless of base-url. `infochat.llm.summarizer.provider=anthropic` under `local-only=true` therefore fails startup. But `infochat.llm.default.provider=anthropic` expresses the identical intent for *every* task at once and passes the guard silently: `snapshotConfig` never reads `LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER`. No data-leak path follows (every `AnthropicProvider` call reads a per-task `base-url`, and all six base-url keys plus the embedding base-url are scanned for non-loopback hosts), so this is a consistency gap in a defense-in-depth layer, not an exploitable hole. The spec's scan list ("per-task provider overrides that name a cloud-only provider") matches the code, so closing the gap also wants a one-line spec touch-up.

**Recommended fix:**

```java
// snapshotConfig: also capture the default-provider key.
snap.put(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER,
    config.getOptionalValue(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, String.class).orElse(""));

// validateLocalOnlyConfiguration, inside the localOnly branch:
String defaultProvider = stripOrEmpty(snapshot.get(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER))
    .toLowerCase(Locale.ROOT);
if (REMOTE_PROVIDER_NAMES.contains(defaultProvider)) {
    offenders.add("default key=" + LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER
        + " provider=" + defaultProvider);
}
```

**Reasoning:**

The intent-conflict rule then covers both spellings of "route my tasks to a cloud provider", and the FATAL line names the offending key the same way it names per-task offenders.

**Trade-offs:**

- Adds a guard→router constant dependency (already present in spirit; both live in the same package).
- Extends the guard's scan beyond the spec's literal list — accompany with a one-line amendment to `docs/spec/llm.md` §Per-task routing rules so spec and code stay aligned.

---

### F9. `joinPath` and `preview` triplicated while the shared support class already exists

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// OpenAiCompatibleEmbeddingProvider.java:214-227 — note the stale justification:
/**
 * Concatenate {@code base} + {@code path} with exactly one slash
 * between them. Same helper shape as
 * {@link OpenAiCompatibleProvider#joinPath} — kept inline rather
 * than extracted to a shared util because the helper is two
 * branches and pulling it into a third class would add an
 * abstraction without enough callers to justify the file.
 */
private static String joinPath(String base, String path) { ... }
```

```java
// Byte-identical copies:
// OpenAiCompatibleProvider.java:257-262 joinPath, 264-273 preview
// AnthropicProvider.java:215-220 joinPath, 222-230 preview
// OpenAiCompatibleEmbeddingProvider.java:222-227 joinPath, 229-238 preview
```

**Why this is wrong / suboptimal / risky:**

"Three similar lines beats a premature abstraction" stops applying when (a) the copies are byte-identical across three files, (b) all three callers are the exact set of classes a shared home already names as its only clients, and (c) that shared home — `LlmHttpSupport`, package-private, "Shared response-hardening helpers for the LLM / embedding HTTP provider impls in this package" — already exists. The justification comment's premise ("pulling it into a third class would add … the file") is factually wrong in the current tree and will mislead the next author into adding a fourth copy.

**Recommended fix:**

```java
// LlmHttpSupport: add the two helpers once (same bodies), delete the
// six private copies and the stale comment; call sites become
// LlmHttpSupport.joinPath(cfg.baseUrl(), "/messages") and
// LlmHttpSupport.preview(responseBody).
static String joinPath(String base, String path) { ... }

static String preview(String s) { ... }
```

**Reasoning:**

Net ~30 lines deleted, one place to fix the `preview` length-vs-bytes labeling (it prints `s.length()` and calls it "bytes"), and the package keeps exactly one HTTP-support seam instead of one-and-three-eighths.

**Trade-offs:**

None — the fix is strictly better (F5's `preview` null-branch removal folds into the single shared copy).

---

## Synthesizer-relevant observations

- `TranslationProvider` lives in `infochat-messaging-adapter` (`app.zcat.infochat.messaging.TranslationProvider`) while `docs/spec/llm.md` §SPI shape says "The LLM adapter exposes pluggable interfaces (decision D32): … `TranslationProvider`". `LlmProvider`'s javadoc documents the relocation as deliberate; either the spec sentence or the placement is drifted — architecture-lens call.
- Hand-written `@NonNull` appears throughout this module and ~125 times across other modules' main sources, while `engineering-rules-verbatim.md` §7a states "`@NonNull` is no longer written by hand" under the NullAway package default. Repo-wide convention question, not a per-module fix.
- `docs/design/05-llm-and-embeddings.md` still names the profile `remote` (§5.5, §5.7) where spec/CLAUDE.md use `remote-llm`, and describes a `capabilities()` SPI method plus Mustache templating that the implementation replaced with config-driven language sets and a hand-rolled renderer — the design note needs a refresh pass against the as-built module.
