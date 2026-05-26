# Session handoff — Tier 3 Group D: Anthropic LLM provider

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T3-D ticket file and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0, Tier 1, and Tier 2 implementation tickets are done and
  merged on main. T3-A (production adapters), T3-B (polled fetchers),
  and T3-C (Nostr StreamSource) may or may not be done — T3-D has NO
  dependency on them.
- The LlmProvider SPI (M1-007b), OpenAiCompatibleProvider reference
  implementation (M1-033), LlmRouter + LlmRouterStartupGuard, and all
  eval pipeline consumers (Stage 2 judge, tagger, summarizer, chat
  agent, translator) are on main.
- Deferred: M1-019, M1-020, M1-021, M1-031, M1-034, M1-042.
- Branch is main, otherwise clean.

**Verify at authoring time:**

  - Next free ticket ID:
    `ls docs/plan/m1/tickets/ | sort -V | tail`
  - LlmProvider SPI shape:
    `cat infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java`
  - LlmResponse shape:
    `cat infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmResponse.java`
  - ModelTask enum values:
    `cat infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java`
  - OpenAiCompatibleProvider reference impl:
    `cat infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java`
  - LlmRouter (how providers are discovered and routed):
    `cat infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java`
  - LlmRouterStartupGuard (local-only validation):
    `cat infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java`
  - Existing per-task property keys:
    `grep -rn "infochat.llm" infochat-collector/src/main/resources/application.properties`
    `grep -rn "infochat.llm" infochat-provider/src/main/resources/application.properties`
  - EmbeddingProvider SPI (T3-D may also add an Anthropic embedding
    provider):
    `cat infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingProvider.java`

## What T3-D creates

One ticket: the AnthropicProvider — a second `LlmProvider`
implementation using the native Anthropic Messages API (not the
OpenAI-compatible endpoint). Per design §5.3, the specific reasons
for a native Anthropic implementation rather than routing through the
OpenAI-compatible endpoint:

1. **Prompt caching.** Anthropic's `cache_control` blocks let the
   caller mark the system prompt and few-shot examples as cached.
   This saves ~90% on repeated system prompts (huge win for the
   summarizer and chat agent, which share a long system prompt across
   calls).
2. **Cache-control placement.** The `cache_control` field is a
   Messages API concept; the OpenAI-compatible endpoint does not
   expose it.

### AnthropicProvider responsibilities

1. **Implement `LlmProvider`.** Same SPI as OpenAiCompatibleProvider:
   `LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt)`

2. **Anthropic Messages API wire format.** POST to
   `https://api.anthropic.com/v1/messages` (or operator-configured
   base URL). Request body shape: `{"model": "...", "max_tokens": N,
   "system": [{"type": "text", "text": "<systemPrompt>",
   "cache_control": {"type": "ephemeral"}}], "messages":
   [{"role": "user", "content": "<userPrompt>"}]}`.
   Response path: `content[0].text` (first text content block).

3. **Auth header.** `x-anthropic-version: 2023-06-01` (or latest
   stable). `anthropic-api-key: <api-key>` header (NOT Bearer token).

4. **Per-task configuration.** Same pattern as
   OpenAiCompatibleProvider: per-task property block
   (`infochat.llm.<task>.provider=anthropic`,
   `infochat.llm.<task>.base-url=...`,
   `infochat.llm.<task>.api-key=...`,
   `infochat.llm.<task>.model=...`,
   `infochat.llm.<task>.timeout-ms=...`).
   The LlmRouter's per-task override property
   (`infochat.llm.<task>.provider`) selects the provider by name;
   when set to `"anthropic"`, the router returns AnthropicProvider.

5. **Provider name and router registration.** Define
   `AnthropicProvider.PROVIDER_NAME = "anthropic"`. Registered as a
   CDI bean. **IMPORTANT:** `LlmRouter.providerName()` (line 255)
   currently only recognizes `OpenAiCompatibleProvider` by its
   constant; all other providers fall through to `getSimpleName()`
   (which would yield `"AnthropicProvider"`, not `"anthropic"`).
   T3-D must update `providerName()` to recognize
   `AnthropicProvider` → `PROVIDER_NAME`, parallel to the existing
   OpenAI check. Add `LlmRouter.java` to `files_scope`.

6. **Language capabilities (config-driven, NOT method-driven).**
   `LlmProvider` has NO `capabilities()` method — the SPI surface
   is frozen (LlmRouter.java line 224). The `Capability` enum from
   design §5.3 does NOT exist in code. Language capabilities are
   config-driven via `infochat.llm.<providerName>.languages`
   (comma-separated ISO 639-1 codes). T3-D adds
   `infochat.llm.anthropic.languages=en,cs` to application.properties.
   The router's `supportedLanguagesFor()` reads this property to
   build the `Entry.supportedLanguages` set.

6b. **`max_tokens` is REQUIRED by Anthropic.** Unlike the OpenAI API
   where max_tokens is optional, the Anthropic Messages API requires
   it. Add a per-task config property
   `infochat.llm.<task>.max-tokens` (profile-driven defaults).
   OpenAiCompatibleProvider may ignore this property.

7. **Prompt caching.** The system prompt is wrapped in a
   `cache_control: {"type": "ephemeral"}` block so Anthropic's
   server-side prompt cache kicks in. This is the primary win over
   the OpenAI-compatible path. No client-side caching needed.

8. **LlmRouterStartupGuard integration.** When
   `infochat.llm.local-only=true`, the startup guard rejects non-
   loopback base URLs. AnthropicProvider's default base URL
   (`https://api.anthropic.com`) is non-loopback. The guard's
   `PER_TASK_BASE_URL_KEYS` map must include every task that can route
   to the Anthropic provider. Verify the guard's current state at
   authoring time.

9. **Error handling.** Same pattern as OpenAiCompatibleProvider:
   IOException → LlmCallFailedException, non-2xx → LlmCallFailedException
   with status and body preview. Anthropic-specific errors:
   - 401: invalid API key
   - 429: rate limited (with `retry-after` header)
   - 529: overloaded
   The caller's retry-once-then-fallback harness handles these; the
   provider throws unchecked exceptions.

### AnthropicEmbeddingProvider (optional, evaluate at authoring time)

Design §5.3 mentions only `LlmProvider`, not `EmbeddingProvider`.
Anthropic's embeddings API exists but is less commonly used than
OpenAI-compatible embedding models (Ollama, nomic-embed-text). If
the operator wants Anthropic embeddings, a parallel
`AnthropicEmbeddingProvider` implementing `EmbeddingProvider` would
be needed. This is a stretch goal — evaluate whether to include it or
defer it. The session-grouping-plan does not mention it explicitly.

### What T3-D does NOT create

  - No new Flyway migration.
  - No changes to OpenAiCompatibleProvider.
  - No changes to the LlmProvider SPI (SPI surface is frozen).
  - No changes to eval pipeline consumers (Stage 2 judge, tagger,
    etc.) — they call `LlmRouter.forTask()` which returns whichever
    provider is configured; switching to Anthropic is a property change.
  - No adapter or fetcher work.

  **T3-D DOES change:** `LlmRouter.providerName()` (to recognize
  AnthropicProvider), `LlmRouterStartupGuard.PER_TASK_BASE_URL_KEYS`
  (to add Anthropic-routable tasks), and application.properties
  (language config + per-task property blocks).

## Key seams in the current code

### LlmProvider SPI

Location: `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java`

Signature: `LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt)`

### OpenAiCompatibleProvider (reference implementation)

Location: `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java`

Pattern to follow:
- `@ApplicationScoped` CDI bean
- `PROVIDER_NAME = "openai-compatible"`
- Per-task property blocks via `@ConfigProperty`
- JDK `java.net.http.HttpClient` for HTTP calls
- JSON assembly via Jackson `ObjectMapper`
- `configFor(ModelTask task)` switch expression → per-task config
- `doCall(TaskConfig, systemPrompt, userPrompt)` → HTTP POST → parse
  response
- Exceptions: `LlmCallFailedException` (unchecked)
- Response parsing: `choices[0].message.content` (OpenAI shape)
- API key: `Authorization: Bearer <key>` (omitted if empty)

AnthropicProvider diverges on:
- Wire format (Messages API, not chat/completions)
- Auth header (`x-anthropic-version` + `anthropic-api-key`, not Bearer)
- Response path (`content[0].text`, not `choices[0].message.content`)
- System prompt: top-level `system` array with `cache_control`, not a
  `messages` entry with `role: "system"`
- Provider-specific headers (`anthropic-beta` if using beta features)

### LlmRouter

Location: `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java`

Discovery: `Instance<LlmProvider>` CDI injection. AnthropicProvider
registers automatically as an `@ApplicationScoped` CDI bean.

Three-priority resolution: (1) per-task override property
(`infochat.llm.<task>.provider`), (2) language-aware check against
`Entry.supportedLanguages`, (3) profile default.

`Entry.supportedLanguages` is populated from config via
`supportedLanguagesFor(p, config)`, which reads
`infochat.llm.<providerName>.languages` (comma-separated). Defaults
to `Set.of("en")` when absent.

`providerName(p)` resolves: OpenAiCompatibleProvider → its
`PROVIDER_NAME` constant; everything else → `getSimpleName()`.
T3-D adds an `instanceof AnthropicProvider` branch so the provider
name is `"anthropic"` (matching the property key
`infochat.llm.<task>.provider=anthropic`).

### LlmRouterStartupGuard

Location: `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java`

- @Startup @Priority(150)
- Validates `infochat.llm.local-only=true` against per-task base URLs
- `PER_TASK_BASE_URL_KEYS` map currently has SECURITY_JUDGE only
  (at brief-authoring time — verify, later tickets may have expanded)
- T3-D may need to add entries for tasks that the operator might
  point at Anthropic

## Spec sections T3-D cites

- `docs/spec/llm.md` §SPI shape (line 27) — LlmProvider,
  EmbeddingProvider, ModelTask, Router
- `docs/spec/llm.md` §Why a thin SPI on top of LangChain4j (line 82)
  — hot-swap by config, per-task qualifiers, cache-friendly shapes
- `docs/spec/llm.md` §Per-task routing rules (line 118)
- `docs/spec/llm.md` §Failure handling (line 310)
- `docs/spec/llm.md` §Hardware profile contract (line 370) —
  profile-driven model defaults
- `docs/spec/llm.md` §Bounded concurrency (line 379)
- `docs/design/05-llm-and-embeddings.md` §5.3 (AnthropicProvider
  design, capability flags, property keys)
- `docs/design/05-llm-and-embeddings.md` §5.7 (profile defaults table)
- `docs/design/05-llm-and-embeddings.md` §5.8 (failure handling per
  task)

## Recommended ticket structure

**Single ticket.** File count estimate:

  - AnthropicProvider.java (~150-200 lines)
  - AnthropicResponseParser.java (optional; may inline in provider)
  - LlmRouter.java edit (add providerName branch)
  - LlmRouterStartupGuard edit (expand PER_TASK_BASE_URL_KEYS)
  - Config properties additions to application.properties
  - Test: AnthropicProviderTest.java (mock HTTP server)
  - Test: LlmRouterTest additions (verify Anthropic routing)
  - Test fixture: canned Anthropic API response JSON

  Estimate: 5-8 files. Well within 12-file budget.

  - complexity: medium (wire format is well-documented; follows
    existing provider pattern)
  - risk: low (isolated addition, no cross-cutting changes)
  - security_relevant: false (API key handling follows existing
    pattern; secrets management per spec §Secrets handling)

## Dependencies

- Depends on Tier 2 completion. For `blocked_by`, use the last done
  M1 ticket at authoring time (verify via
  `ls docs/plan/m1/tickets/ | sort -V | tail`).
- Independent of T3-A, T3-B, T3-C.
- If Anthropic embedding support is desired, it can be a follow-up
  ticket or folded into this one (evaluate file count).
- Profile-driven values use Quarkus config profiles:
  `%vps.infochat.llm.summarizer.provider=anthropic`,
  `%remote-llm.infochat.llm.anthropic.languages=en,cs`, etc.

## Design-vs-spec drift notes

1. **`capabilities()` does NOT exist on LlmProvider** (confirmed).
   The SPI surface is frozen. The design doc §5.3 `Capability` enum
   is aspirational — it was never implemented. Language capabilities
   are config-driven: `infochat.llm.<providerName>.languages=en,cs`.
   T3-D uses this mechanism, not a method on the provider.

2. **LlmRouter language resolution** (confirmed). The router reads
   `infochat.llm.<providerName>.languages` at CDI build time via
   `supportedLanguagesFor()`, defaulting to `Set.of("en")`. The
   router's priority-2 branch picks the first registered provider
   whose `supportedLanguages` contains the scope language. T3-D adds
   `infochat.llm.anthropic.languages=en,cs` so Czech summarization
   routes to Anthropic when configured.

3. **LlmRouter.providerName() hardcodes OpenAI** (confirmed). Only
   `OpenAiCompatibleProvider` is checked by `instanceof`; others fall
   through to `getSimpleName()`. T3-D must add an
   `instanceof AnthropicProvider` branch. This is a required code
   change, not a config-only addition.

4. **Anthropic API version.** The `x-anthropic-version` header value
   should be verified against current Anthropic API docs at
   implementation time.

5. **`max_tokens` is a required Anthropic API parameter** (unlike
   OpenAI where it's optional). T3-D needs a per-task
   `infochat.llm.<task>.max-tokens` property with profile-driven
   defaults.

6. **Anthropic error response format.** Error responses use
   `{"type":"error","error":{"type":"...","message":"..."}}`. The
   provider should parse this for meaningful error logging rather
   than just dumping the raw body preview.

## Existing tests to not break

- LlmRouterTest — tests provider discovery and per-task routing
- OpenAiCompatibleProviderTest (if it exists) — unrelated but must
  stay green
- LlmRouterStartupGuardTest — tests local-only validation
- All eval pipeline tests that call through LlmRouter (Stage2JudgeTest,
  TaggerPipelineTest, etc.)
- Full `mvn verify` from repo root

## Task

Author the T3-D ticket file (1 ticket) in `docs/plan/m1/tickets/`.
Follow the ticket template at `docs/process/ticket-template.md`. Use
the next free ID at the tail.

After authoring, run `scripts/lint-ticket.py` on the new ticket file
and fix any errors. Do NOT run `/m1-tick start` — only author.
```
