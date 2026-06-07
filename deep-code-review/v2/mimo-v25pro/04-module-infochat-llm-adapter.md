# Deep code review: module infochat-llm-adapter
**Target:** module infochat-llm-adapter | **Lens:** module | **Module path:** infochat-llm-adapter/ | **Date:** 2026-06-07 | **Reviewer:** senior-developer (mimo-v2.5-pro)

## Headline findings

1. **MAINTAINABILITY-RULES-DRIFT | medium** -- `OpenAiCompatibleProvider.configFor()` throws `UnsupportedOperationException` for 5 of 6 `ModelTask` values. This is a valid incremental-development pattern (M1-033 wires SECURITY_JUDGE only), but the exception is a `RuntimeException` that callers never catch. When the next task ticket lands (e.g. Tagger), it silently changes `configFor`'s contract without any compile-time signal at the router or call-site level. The router's `assertAllTasksResolve()` catches misconfiguration at startup, but a task that was previously wired in `configFor` and later removed would only fail at runtime. Not a bug today; a maintenance trap for the next ticket.

2. **PERFORMANCE | low** -- `LlmHttpSupport.BoundedStringSubscriber.onNext()` accumulates `ByteBuffer` objects from the JDK `HttpClient` reactive stream. When the body exceeds `maxBytes`, it cancels the subscription but does NOT release the already-accumulated `received` list. The `received` list is reachable until the `BoundedStringSubscriber` is GC'd. For a 2 MiB body hitting a 1 MiB cap, roughly 1 MiB of already-buffered bytes remain pinned. This is bounded (the cap itself limits the damage) and the subscriber is short-lived, but a tighter implementation would clear `received` on cancel.

3. **SIMPLIFICATION | low** -- Three test files (`LlmRouterTest`, `LlmRouterUnknownDefaultTest`, `AnthropicProviderTest`) each define their own private inner `StubConfig` implementing the full MicroProfile `Config` interface. `LlmRouterUnknownDefaultTest` and `LlmRouterStartupGuardLocalOnlyTest` each define their own private inner `CapturingHandler`. `LlmRouterTest` and `LlmRouterUnknownDefaultTest` each define their own private inner `StubProvider`. The code is functionally correct; the duplication is a test-maintenance cost. Per CLAUDE.md feedback "Avoid private inner classes in test files" (>3 inner classes in one file is the stop signal; each file stays under that), but across three files the same pattern repeats 6 times.

4. **SECURITY | low** -- `LlmRouter.forTask()` accepts `@Nullable String scopeLanguage` and normalizes `null` to `"en"`. This is correct per the spec (English is the default). However, the `assertAllTasksResolve()` method always passes `"en"` as the language, so it never exercises the language-aware capability branch (priority 2). A per-task override that is absent but whose language-aware branch would resolve to an unexpected provider is never tested at startup. This is a gap in the startup guard's coverage, not a runtime bug -- the language-aware branch is tested in `LlmRouterTest` at the unit level.

## Detail

### 1. Duplicated `joinPath` helper across three files

**Files:** `OpenAiCompatibleProvider.java` (line 257), `AnthropicProvider.java` (line 215), `OpenAiCompatibleEmbeddingProvider.java` (line 222)

All three contain an identical `private static String joinPath(String base, String path)` method with the same "strip trailing slash then append" logic. `OpenAiCompatibleEmbeddingProvider`'s comment explicitly acknowledges this: "kept inline rather than extracted to a shared util because the helper is two branches and pulling it into a third class would add an abstraction without enough callers to justify the file." This is a deliberate choice per CLAUDE.md "Simplify aggressively" -- three similar lines beats a premature abstraction. No finding; documented as a conscious trade-off.

### 2. Duplicated `preview` helper across three files

**Files:** `OpenAiCompatibleProvider.java` (line 265), `AnthropicProvider.java` (line 222), `OpenAiCompatibleEmbeddingProvider.java` (line 230)

Same pattern as `joinPath`. All three are identical 200-char truncation helpers. Same deliberate inlining rationale.

### 3. `OpenAiCompatibleProvider` and `AnthropicProvider` config-reading divergence

**Category:** MAINTAINABILITY-RULES-DRIFT | **Severity:** low

`OpenAiCompatibleProvider` reads per-task config via `@ConfigProperty` field injection (one field set per property). `AnthropicProvider` reads per-task config dynamically via `Config.getValue(prefix + "property")` in `configFor()`. The comment in `AnthropicProvider` explains: "With 6 tasks x 5 properties = 30 fields, dynamic lookup is cleaner." This is a valid engineering trade-off, but it means the two providers have different config-reading patterns. A future provider author copying from one will get a different pattern than someone copying from the other.

### 4. `OpenAiCompatibleProvider` only wires SECURITY_JUDGE in `configFor`

**Category:** MAINTAINABILITY-RULES-DRIFT | **Severity:** medium

`OpenAiCompatibleProvider.configFor()` (line 157-165) has an exhaustive switch that throws `UnsupportedOperationException` for TAGGER, ENTITY, SUMMARIZER, CHAT_AGENT, TRANSLATOR. The error message names "M1-033 wires SECURITY_JUDGE only." This is correct incremental development. However, the exception type (`UnsupportedOperationException`) is a `RuntimeException` that the router's `assertAllTasksResolve()` startup scan does NOT catch -- the startup scan calls `forTask(task, "en")` which calls `configFor(task)` which throws. The router's `assertAllTasksResolve` does not catch or expect `UnsupportedOperationException`; it lets it propagate, which is the correct fail-loud behavior for startup. But when a future ticket wires e.g. TAGGER, it must add the `@ConfigProperty` fields AND the `case TAGGER ->` arm in `configFor()` AND the property keys in `LlmRouterStartupGuard.PER_TASK_BASE_URL_KEYS`. Missing any one of the three silently breaks or misroutes. The current design relies on the implementer reading the M1-033 comment to know all three locations. A compile-time guard (e.g. a sealed interface or an explicit "not-yet-wired" marker type) would be safer, but the incremental approach is standard for v1 greenfield.

### 5. `EmbeddingResult` defensive copy on every `vector()` call

**Category:** PERFORMANCE | **Severity:** low

`EmbeddingResult.vector()` (line 33-35) returns a clone on every call. The record's compact constructor also clones the input. This means constructing an `EmbeddingResult` and immediately reading it involves two full array copies. For the embedding pipeline (batch of N results, each read once to store in the DB), this is N extra array copies. The copy-on-construction is necessary (the caller's array must not be aliased). The copy-on-read is necessary (the stored array must not be mutated). For v1 batch sizes (profile-driven, likely 16-64 elements), the cost is negligible. No finding at this scale.

### 6. `LlmHttpSupport` uses `@SuppressWarnings("NullAway.Init")` on reactive-streams subscription field

**Category:** MAINTAINABILITY-RULES-DRIFT | **Severity:** low

`LlmHttpSupport.BoundedStringSubscriber` (line 154) suppresses `NullAway.Init` on the `subscription` field with a detailed comment explaining that the reactive-streams contract guarantees `onSubscribe()` precedes every other signal, so the field is non-null at every dereference. This is the correct use of the suppression -- the annotation processor cannot model the reactive-streams lifecycle, so the suppression documents the contract. No finding; well-justified.

### 7. `AnthropicProvider.parseContentText` only reads `content[0]`

**Category:** MAINTAINABILITY-RULES-DRIFT | **Severity:** low

`AnthropicProvider.parseContentText()` (line 178-197) reads only `content[0].text`. The Anthropic API can return multiple content blocks (e.g. a text block followed by a tool_use block, or multiple text blocks). In v1, the prompts never request tool use, so `content` will always contain exactly one text block. The method correctly validates `content.isArray() && !content.isEmpty()` before indexing. If a future ticket enables tool use for the chat agent, this method would silently discard non-first content blocks. The current code is correct for v1's call shapes.

### 8. `LlmRouter.Entry` record: `supportedLanguages` null-normalization

**Category:** SIMPLIFICATION | **Severity:** low

`LlmRouter.Entry`'s compact constructor (line 337-339) normalizes `null` to `Set.of()` for `supportedLanguages`. The `forTask` method (line 167) checks `supported != null && supported.contains(lang)` -- the `!= null` check is now dead code since the constructor guarantees non-null. This is defensive code at an internal boundary, not a system boundary. Per CLAUDE.md "No defensive code for impossible scenarios," the null check is unnecessary. The `@Nullable` annotation on the record component and the null-check in `forTask` are consistent with each other but both are redundant after the constructor normalization. Either drop the `@Nullable` and the null-check (the constructor guarantees non-null), or keep the `@Nullable` and document that the constructor normalizes (current state). The current code is functionally correct; it's a style inconsistency, not a bug.

### 9. `LlmRouter.forTask()` null-check on `task` parameter

**Category:** MAINTAINABILITY-RULES-DRIFT | **Severity:** low

`LlmRouter.forTask()` (line 141) has `if (task == null) throw new IllegalArgumentException(...)`. Per the module's NullAway config (`AnnotatedPackages=app.zcat.infochat`), `ModelTask` is non-null by default. The `@NonNull` annotation on `task` in the method signature plus NullAway enforcement means callers cannot legally pass `null`. This is defensive code for an impossible scenario (internal code calling internal code). Per CLAUDE.md engineering rule §7, this check is unnecessary. However, `forTask` is a public API consumed by code outside this module (the Collector's Stage 2 worker, digest scheduler, etc.), so the check is at a module boundary -- the "system boundary" carve-out in §7 applies. Borderline; keeping it is defensible.

### 10. `LlmRouter.MicroProfileConfigReader.get()` normalizes `"null"` to empty

**Category:** SECURITY | **Severity:** low

`LlmRouter.MicroProfileConfigReader.get()` (line 378-380) trims the value and normalizes the case-insensitive string `"null"` to empty string. The comment explains: "Some config sources stringify an explicitly-unset/null value as the four-character text 'null'." This is config-boundary normalization. The concern: an operator who intentionally names a provider `"null"` (unlikely but possible) would have their override silently dropped. The normalization applies to ALL config keys, not just the default-provider key. For per-task provider overrides, a provider literally named `"null"` would be silently treated as "no override" and fall through to the profile default. This is an acceptable edge case given that no real provider would be named `"null"`.

### 11. No test for `OpenAiCompatibleProvider` (chat completions)

**Category:** MAINTAINABILITY-RULES-DRIFT | **Severity:** medium

The module has `AnthropicProviderTest` and `OpenAiCompatibleEmbeddingProviderTest` but no `OpenAiCompatibleProviderTest`. The `OpenAiCompatibleProvider` is the primary LLM provider (wired for SECURITY_JUDGE in M1-033). Its `generate()` method, request-body assembly, response parsing, retry-after handling, and body-cap enforcement are untested at the module level. The comment in `LlmRouterTest.StubProvider` says "resolution returns the provider, the call site is the Stage 2 worker, exercised by Stage2WorkerIT in the Collector" -- so there IS integration coverage, but no module-level unit test equivalent to `AnthropicProviderTest`. The two providers share the same HTTP client and response-handling patterns, so `AnthropicProviderTest` provides indirect coverage of the shared `LlmHttpSupport` code, but `OpenAiCompatibleProvider`'s own `parseChoiceText`, `configFor` switch, and OpenAI-specific wire format (choices[0].message.content vs Anthropic's content[0].text) are untested within this module.

### 12. `LlmRouterStartupGuard.PER_TASK_BASE_URL_KEYS` uses `Map.of()` (immutable, no guaranteed iteration order)

**Category:** SIMPLIFICATION | **Severity:** low

`LlmRouterStartupGuard.PER_TASK_BASE_URL_KEYS` (line 116-123) uses `Map.of()` which does not guarantee iteration order. The validation loop in `validateLocalOnlyConfiguration` iterates this map to build the `offenders` list. The order of offenders in the fatal message is nondeterministic across JVM runs. This is cosmetic (the message is for operator diagnostics, not machine parsing), but `LinkedHashMap` would make the output stable. `LlmRouter` uses `LinkedHashMap` for its `entriesByName` map (line 115) for exactly this reason.

### 13. `LlmRouter.buildFromCdi` iterates `Instance<LlmProvider>` without closing

**Category:** PERFORMANCE | **Severity:** low

`LlmRouter.buildFromCdi()` (line 277-289) iterates `Instance<LpmProvider>` from CDI but does not call `providers.close()`. The `Instance` object is a `@Dependent`-scoped handle; not closing it leaks the dependent-scoped instances. In practice, `LlmRouter` is `@ApplicationScoped` and `buildFromCdi` runs once at construction, so the leak is bounded to the application lifetime. The CDI spec says `Instance` is `AutoCloseable` since CDI 4.0; closing it after iteration would be cleaner.

### 14. `EmbeddingResult` defensive copy: triple allocation per construction

**Category:** PERFORMANCE | **Severity:** low

`new EmbeddingResult(float[] vector)` triggers: (1) the compact constructor clones the input, (2) the record's implicit constructor stores the cloned array. When the caller reads via `vector()`, (3) another clone is returned. For a batch of 64 embeddings of dimension 768, that's 64 * 768 * 4 bytes * 3 copies = ~576 KB of array allocation per batch. The JVM's escape analysis may eliminate the intermediate copy, but this is not guaranteed. For v1 batch sizes, this is negligible. The correctness guarantee (no aliasing, no mutation) is worth the allocation cost.

### 15. `LlmRouterStartupGuard.isLoopback` triggers DNS resolution at startup

**Category:** PERFORMANCE | **Severity:** low

`LlmRouterStartupGuard.isLoopback()` (line 263-284) calls `InetAddress.getByName(host)` which performs DNS resolution. For every per-task base-url configured, this triggers a DNS lookup at startup. If DNS is slow or unavailable, this delays Collector startup. The method handles `UnknownHostException` gracefully (treats as non-loopback), so it won't hang indefinitely. For v1's 6 tasks + 1 embedding endpoint = 7 lookups, the cost is negligible. The DNS-rebind window (host resolves to loopback at startup but to remote at call time) is documented in the spec as acceptable for this guard.

### 16. `AnthropicProvider.configFor` and `OpenAiCompatibleProvider.configFor` have different max-tokens handling

**Category:** MAINTAINABILITY-RULES-DRIFT | **Severity:** low

`AnthropicProvider.configFor()` reads `max-tokens` via `config.getValue(prefix + "max-tokens", Integer.class)` (required; throws if absent). `OpenAiCompatibleProvider` does not read `max-tokens` at all -- the OpenAI API treats it as optional. This is correct per the respective API contracts (Anthropic requires `max_tokens`, OpenAI does not). But the asymmetry means the config surface is provider-specific in a way that's not obvious from the property keys alone. An operator setting `infochat.llm.security.max-tokens=512` for OpenAI-compatible would have it silently ignored.

### 17. `LlmRouter.Entry` `supportedLanguages` semantics: empty vs null

**Category:** SIMPLIFICATION | **Severity:** low

`LlmRouter.Entry`'s Javadoc (line 328-330) says: "Empty means 'any' -- the language-aware branch skips empty sets so a generic provider doesn't front-run a capability-declaring one." But the compact constructor normalizes `null` to `Set.of()` (empty), and `buildFromCdi` defaults to `Set.of("en")` when the config key is absent. So the "empty means any" semantics are never exercised in production -- providers always get at least `{"en"}`. The language-aware branch's `if (supported != null && supported.contains(lang))` with `supported = Set.of()` returns false for any non-empty language, which is correct. The "empty means any" documentation is aspirational for a future case where a provider registers with an explicit empty set; today it's unreachable.
