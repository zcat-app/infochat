# Session handoff — Tier 2 Group C: translation (TranslationProvider impl + /lang + cs bundle)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T2-C ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- Tier 0, Tier 1, and Tier 2 sub-groups T2-A and T2-B are done and
  merged on main.
- T2-G has its infrastructure ticket M1-058 (ThrottledAdminNotifier
  + admin_notification_state) currently `pending` and runnable;
  T2-H (M1-055 umbrella + M1-055b + M1-055c) is `pending` and
  blocked by M1-055b (which itself is blocked by M1-058).
  None of these collide with T2-C — translation has no overlap
  with quarantine throttling or asset commands at the schema,
  routing, or handler level.
- STATUS.md as of brief authoring: pending=4, in-progress=0,
  in-review=0, done=68, deferred=6, total=78.
- The full M1 history is reproducible from `git log --grep "^M1-"`.
- Branch is main, otherwise clean.
- All Tier-1 deferred tickets (M1-019/020/021/031/041/042) are out
  of T2-C's path; the §LLM output sanitizer (M1-041) consolidation
  is done — T2-C splices into the EXISTING `LlmOutputSanitizer` call
  site in `SummaryCommandHandler` rather than introducing a new
  sanitizer.

## What's NOT yet on disk that T2-C creates

T2-C lands the translation pipeline. Two distinct surfaces, each
self-contained, planned as two independent tickets:

  - **T2-C.1** — TranslationProvider implementation +
    translation cache + LlmRouter capability widening (config-
    driven `supportedLanguages` per provider) + pipeline splice
    in SummaryCommandHandler (sanitize-1 → translate-if-not-en →
    sanitize-2 → cache write → adapter send) + translator
    prompt template. No new migration.
  - **T2-C.2** — `/lang <code>` command + `cs.properties`
    bundle + BundleLoader refactor (load all bundles at startup;
    per-scope language lookup with `en` fallback on missing key).
    No new migration; `scope_preferences.language` already
    exists with default `'en'` (V7).

Neither subticket adds a migration. **`scope_preferences.language`
ships in V7 already** — `infochat-core/src/main/resources/db/migration/V7__joins_post.sql`
line 87: `language TEXT NOT NULL DEFAULT 'en'`. Re-verify at
authoring time via:

  ```
  grep -nE 'language\s+TEXT' infochat-core/src/main/resources/db/migration/V7__joins_post.sql
  ```

**Verify at the moment of authoring** (do not trust this brief's
numbers if `main` has moved):

  - Next free M1 ticket id under `docs/plan/m1/tickets/`. At
    authoring time M1-058 was the last allocated id. Re-run
    `ls docs/plan/m1/tickets/ | sort -V | tail` to confirm
    before assigning. **Likely allocation**: T2-C.1 → M1-059,
    T2-C.2 → M1-060.
  - Next free Flyway migration integer under
    `infochat-core/src/main/resources/db/migration/`. At brief
    authoring time V15 (`saved_post`) was the last consumed; the
    next free integer is V16. T2-C consumes ZERO migration
    integers — both subtickets work against existing tables.
  - Existing TranslationProvider SPI location and shape. Brief
    authoring time:
    ```
    infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/TranslationProvider.java
    ```
    Method: `String translate(String text, Locale from, Locale to)`.
    **No `supportedTargetLangs()`, no `canTranslate()` — the
    design-notes shape diverges from the shipped SPI.** T2-C
    locks to the shipped SPI; see §"Design-vs-spec drift" below.

What does NOT yet exist (T2-C creates / extends):

  - `LlmTranslationProvider` — concrete `TranslationProvider`
    bean dispatching via `LlmRouter.forTask(TRANSLATOR, toLang)`.
    Reads the configured translator provider (per spec §SPI shape
    line 33 + §Translation flow line 200); calls the LlmProvider
    with the translator prompt template; returns the translated
    text.
  - `TranslationCache` — Caffeine (`io.quarkus:quarkus-caffeine`)
    cache keyed on `(sha256(text), toLang)` with 24h TTL per
    `docs/design/05-llm-and-embeddings.md` §5.6. The cache stores
    the **post-sanitizer-2 translated form** so cache hits skip
    both the translator call and the second sanitizer pass
    (spec §Pipeline order lines 249–264).
  - `prompts/translator.md` resource — the translator prompt
    template per `docs/design/05-llm-and-embeddings.md` §5.6
    "Default impl: LlmTranslationProvider" (preserve backticks
    and triple-backtick code blocks, preserve UIDs, treat input
    as data with the `<<<UNTRUSTED_CONTENT id=...>>>` wrapper per
    spec §Prompt-injection-aware prompt shape).
  - LlmRouter capability widening: today
    `LlmRouter.supportedLanguagesFor(LlmProvider)` hardcodes
    `Set.of("en")` for `OpenAiCompatibleProvider` (lines 265–270).
    T2-C.1 replaces the hardcode with a config lookup
    (`infochat.llm.<provider>.languages=en,cs` shaped). The router
    contract — `(ModelTask, scope_language) → LlmProvider` — is
    unchanged; only the source of the capability set moves from
    code to config. **This widens, not narrows: with no config
    set the existing `Set.of("en")` hardcoded default stands.**
  - `LangCommandHandler` — handler bean. DM scope: callers
    update their own scope only. Group scope: group-admin only
    (spec §Permission model — "Group-admin" closed list). Reply
    on unsupported code MUST list the supported codes per spec
    line 581 "An unsupported code produces a friendly error
    that lists the supported codes — never a silent no-op and
    never a fall-through to the default."
  - `cs.properties` — translation of every key currently in
    `infochat-provider/src/main/resources/bundles/en.properties`
    (278 lines as of brief authoring; re-grep at authoring time
    via `wc -l infochat-provider/src/main/resources/bundles/en.properties`).
    Use the message-bundle interpolation tokens (no string
    concatenation) so adjective ordering / verb position in Czech
    matches grammar. The `BundleLoaderTest` reflection-completeness
    check enforces parity at build time.
  - BundleLoader refactor: today hardcodes
    `BUNDLE_RESOURCE = "/bundles/en.properties"` (line 37) and
    `load()` reads one file. T2-C.2 changes load() to load every
    `bundles/<lang>.properties` on the classpath at startup
    (today: `en` and `cs`) into a `Map<String, Properties>`.
    `get(String key)` becomes `get(String key, Locale lang)`
    or equivalent; the existing single-arg `get(String key)`
    either becomes a per-scope-default lookup OR is renamed —
    decide at authoring time after auditing every call site:
    ```
    grep -rn "bundleLoader.get(\|BundleLoader.get(" infochat-provider/src/main/java/
    ```
    The completeness assertion in `BundleLoaderTest` widens to
    check every shipped bundle has every key; missing-key in
    `cs` falls back to `en` per spec §Translation flow line 234.

## What you do this session

Author two standalone T2-C ticket files in
`docs/plan/m1/tickets/`. Per session-grouping-plan.md row T2-C
(line 140, "1 ticket" estimate), the original estimate was a
single ticket — but the file count clearly exceeds the
`files_budget: 12` threshold when LlmTranslationProvider +
TranslationCache + Router widening + SummaryCommandHandler
splice + `/lang` handler + cs.properties + BundleLoader
refactor + their tests all land together. The **2-ticket split
below preserves the `files_budget: 12` invariant**:

  T2-C.1 — TranslationProvider impl + cache + router capability
           widening + pipeline splice into SummaryCommandHandler
           (translator-side wiring)
  T2-C.2 — /lang command + cs.properties + BundleLoader per-
           scope language refactor (bundle-side wiring)

The two are independent at runtime: T2-C.1 reads
`scope_preferences.language` directly to gate translation;
T2-C.2 is the user-facing mutator that lets that column move
off `'en'`. Either order is implementable; **recommended start
order is T2-C.1 first** (load-bearing translation pipeline) so
T2-C.2's user-facing surface lands against a working backend.

If during authoring EITHER subticket exceeds `files_budget: 12`,
restructure THAT subticket into the M1-008 / M1-044 / M1-055
umbrella+subs pattern. Most likely candidate is T2-C.1 (more
moving pieces); T2-C.2 is bounded by a single new bundle file +
one handler + one BundleLoader edit, well under the threshold.

**Push-back option** (per CLAUDE.md §"Push back when simpler
exists"): if you, during authoring, find a materially simpler
1-ticket shape that still respects `files_budget: 12` — for
instance, if the `cs.properties` file is treated as a docs-side
deliverable folded into a `spec:` commit rather than a code
ticket — surface that BEFORE writing the file. The 2-ticket
default below is the conservative shape, not the only one.

## Where you are in the milestone

Tier 1 is complete. Tier 2 is mid-flight: T2-A and T2-B done;
T2-C begins with this session; T2-G has its infrastructure
ticket pending; T2-H is mid-flight blocked on T2-G's infra.

  T2-A onboarding / auth          (done)
  T2-B DM commands on entities    (done — M1-052/053/054)
  T2-C translation                (THIS SESSION — 2 tickets,
                                   T2-C.1 + T2-C.2)
  T2-D chat-mode                  (next: chat agent + memory +
                                   /compress)
  T2-E privacy                    (/forget, /export)
  T2-F groups                     (group support + digests)
  T2-G quarantine                 (M1-058 infra pending;
                                   /quarantine handler is later)
  T2-H assets                     (M1-055 umbrella pending;
                                   blocked on M1-058)

After T2-C, the next session authors T2-D's detailed handoff JIT.
See `docs/plan/m1/drafts/session-grouping-plan.md` for the full
plan.

## ID allocation (LOCKED at the tail)

Per session-grouping-plan §"ID allocation": T2-C gets fresh IDs
at the tail. At the time this brief was authored, M1-058 was the
last allocated id. Verify at authoring time via
`ls docs/plan/m1/tickets/ | sort -V | tail`.

Likely allocation:

  - T2-C.1 → M1-059
  - T2-C.2 → M1-060

If during authoring you take the umbrella+subs escape hatch for
T2-C.1, IDs shift to: T2-C.1 umbrella → M1-059, T2-C.1a/b/c →
M1-059a/b/c, T2-C.2 → M1-060.

Per-ticket title shapes (use verbatim modulo imperative-summary
tightening):

  T2-C.1 → "TranslationProvider impl — LlmTranslationProvider +
            24h translation cache + router language widening +
            SummaryCommandHandler pipeline splice"
  T2-C.2 → "/lang <code> + cs.properties bundle + BundleLoader
            per-scope language lookup with en fallback"

## Per-ticket framing

### T2-C.1 (M1-059) — TranslationProvider impl + pipeline splice

**Spec anchors** (cite verbatim in `spec_refs:`):

  - `docs/spec/llm.md` §SPI shape — `TranslationProvider`,
    `ModelTask.TRANSLATOR`, `(ModelTask, scope_language) →
    LlmProvider` router contract, the "Translator — produces
    plain-text prose in the requested target language; defaults
    to the chat model with a translation prompt" paragraph.
  - `docs/spec/llm.md` §Translation flow — the FULL section
    (line 195 onward). Every separable sentence becomes one
    acceptance item (per the project's transcribe-spec-promises
    memory): default scope language is English; en short-circuits
    to raw text; non-en routes through TranslationProvider;
    language-aware summarizer fast path; **source post bodies
    are NEVER translated** (load-bearing invariant — Stage 1
    embeddings and retrieval still operate on the source
    language); deterministic strings come from the bundle, NOT
    the translator (decision D43); 24h translation cache keyed
    by `(hash(text), target_language)`; command parsing is
    English-only in v1.
  - `docs/spec/llm.md` §Pipeline order — the FULL ordered
    pipeline (lines 226–264): LLM prose → sanitizer-1 →
    TranslationProvider (skipped on en) → sanitizer-2 →
    translation cache write → adapter delivery. **The
    cache key is derived from post-sanitizer-1 English text**
    (load-bearing — two callers whose pre-sanitizer outputs
    differ trivially must collide on the same cache key).
    Cache lookup is between step 3 and step 4 (a hit skips
    sanitizer-2 too).
  - `docs/spec/llm.md` §Per-task routing rules — the language-
    aware capability branch for SUMMARIZER + TRANSLATOR; the
    "No fallback chain" commitment.
  - `docs/spec/llm.md` §Prompt-injection-aware prompt shape —
    the `<<<UNTRUSTED_CONTENT id="...">>>` wrapper convention
    that the translator prompt MUST use (translation input is
    LLM-authored prose that already passed sanitizer-1, but is
    still data, not instruction).
  - `docs/spec/security.md` §LLM output sanitizer — the
    durability commitment that audit-log writes MUST land
    before the caller is free to send the sanitized reply.
    Sanitizer-2 inherits this commitment.

**Design references** (read but cite only if locking a behavior):

  - `docs/design/05-llm-and-embeddings.md` §5.6 Translation
    layer — Contract block, `LlmTranslationProvider` default
    impl, prompt body, 24h cache TTL, direct-generation fast
    path. **Diverges from shipped SPI** — see §"Design-vs-spec
    drift" below; lock to the shipped SPI shape.
  - `docs/design/05-llm-and-embeddings.md` §5.7 Profile
    defaults — capability set per profile (which models declare
    `cs` support) is the operator's responsibility; T2-C ships
    the mechanism (config-driven capability), not a per-profile
    cs-capability default.

**Locked decisions for this ticket**:

  - **TranslationProvider SPI signature is FROZEN.** The shipped
    interface at
    `infochat-messaging-adapter/.../TranslationProvider.java` is:
    ```
    String translate(String text, Locale from, Locale to);
    ```
    Do NOT add `supportedTargetLangs()` or `canTranslate()` even
    though `docs/design/05-llm-and-embeddings.md` §5.6 shows
    them. The actual supported-codes list is derivable from the
    bundles directory (T2-C.2's territory); LangCommandHandler
    asks the bundle layer, not the translator. The drift is
    flagged in §"Design-vs-spec drift" below — fix the design
    note in a `spec:` commit AFTER this ticket lands, not
    during.
  - **Translation cache is in-memory only** (Caffeine, 24h TTL,
    bounded size from profile config). NO new table.
    `infochat-llm-adapter` (or a new `infochat-provider` cache
    bean — pick by where the LlmTranslationProvider lives;
    Provider is the consumer so the provider module is the
    natural home) declares the cache bean and the dependency on
    `quarkus-caffeine` (verify presence with `grep
    quarkus-caffeine infochat-*/pom.xml`; add to the consuming
    module's pom if absent).
  - **Cache key is `(sha256(text), toLang)`** per design §5.6.
    SHA-256 not SHA-1 (design specifies sha256). Hex-encoded
    string OR byte-array key — pick at authoring time; either
    is stable.
  - **Cache key text is the post-sanitizer-1 English text** per
    spec lines 250–256. NOT the pre-sanitizer LLM output. NOT
    the post-sanitizer-2 translated text. This is load-bearing
    cache-hit-rate behavior.
  - **Cache value is the post-sanitizer-2 translated text** per
    spec lines 259–260. Cache hits skip sanitizer-2 (the value
    is already sanitized).
  - **Pipeline splice point: `SummaryCommandHandler.formatCluster`
    line 209–211** (at brief authoring time; re-grep at
    authoring time via `grep -n llmOutputSanitizer.sanitize
    infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java`).
    Today: `summaryText = cp.degraded() ? cp.prose() :
    llmOutputSanitizer.sanitize(cp.prose())`. Tomorrow: insert
    `TranslationPipeline.run(sanitized, scopeLanguage)` between
    sanitize-1 and the assignment. `TranslationPipeline` is the
    new ApplicationScoped bean that owns the ordered chain
    (translate-if-not-en → sanitize-2 → cache-write).
  - **Degraded prose path is unchanged.** `cp.degraded() ?
    cp.prose() : ...` — degraded summaries are deterministic
    bundle strings, NOT LLM-authored prose, so they skip the
    translator per spec D43. The branch stands as-is.
  - **Direct-generation summarizer fast path is OUT of v1
    scope for T2-C.** Per spec line 201–204, when the
    summarizer's LlmProvider has the target language as a
    capability, the summarizer is invoked with
    `target_language` directly to save a round trip. The
    LlmRouter already supports this resolution
    (`forTask(SUMMARIZER, scopeLanguage)` consults
    `supportedLanguages()`) — but the summarizer-side prompt
    change (adding `target_language` to the prompt) is a
    SummaryProseGenerator concern, not a TranslationProvider
    concern. Defer the prompt change to a later ticket; T2-C.1
    delivers the safe path (always translate post-hoc when
    scope_lang != en). Note this in the ticket's out_of_scope
    so the reviewer doesn't flag the missing fast path.
  - **LlmRouter capability source moves from code to config.**
    Replace `LlmRouter.supportedLanguagesFor(LlmProvider p)`
    (lines 265–270) with a config lookup keyed on the provider
    name: `infochat.llm.<providerName>.languages` (comma-
    separated ISO 639-1 codes). When unset, default to
    `Set.of("en")` to preserve today's behavior. The
    `buildFromCdi` helper consults the config via the same
    `Config mpConfig` already injected into the @Inject
    constructor. **Test seam stays compatible**: the
    dependency-free constructor still accepts a hand-rolled
    `List<Entry>` — tests can pre-bake the language set per
    entry without touching config.
  - **Sanitizer-2 audit-log durability**: `LlmOutputSanitizer.sanitize`
    is already durable per M1-041 (the audit-log write must
    succeed before the call returns; failure surfaces as an
    exception). T2-C.1's TranslationPipeline calls
    `sanitizer.sanitize(translated)` and inherits the same
    durability — no new audit-log code, the existing sanitizer
    bean is reused.
  - **Pipeline cache write is post-sanitizer-2.** The pipeline's
    final step before returning the string is `cache.put(key,
    sanitized2Translated)`. Cache lookup is the FIRST thing the
    pipeline does after the "is scope_lang == en?" short-
    circuit, so a hit returns immediately without invoking the
    translator or sanitizer-2.
  - **Scope language source**: pipeline reads
    `scope_preferences.language` for the calling
    `(scope_kind, scope_id)` via a per-call SELECT. NO scope-
    language cache in T2-C (per-scope read is a few rows; a
    cache layer here is premature optimization).

**Out-of-scope (template for the ticket's frontmatter)**:

  - any change to the spec — §SPI shape + §Translation flow +
    §Pipeline order are already complete on main HEAD
  - any change to the TranslationProvider SPI interface — the
    shipped signature is FROZEN; design-notes divergence is
    addressed by a separate `spec:` commit AFTER this ticket
  - any /lang handler work — T2-C.2 territory
  - any cs.properties bundle creation — T2-C.2 territory
  - any BundleLoader change — T2-C.2 territory
  - any new DB migration (none needed; cache is in-memory)
  - any change to LlmOutputSanitizer or its audit-log
    durability — M1-041 territory, sanitizer is reused
    unchanged
  - any direct-generation summarizer fast path (deferred — see
    "locked decisions" above)
  - any chat-mode translation path — T2-D territory; chat-agent
    output translation will reuse this same
    TranslationPipeline bean once T2-D lands
  - any digest translation path — T2-F territory; digest writer
    will reuse this same TranslationPipeline bean
  - any /retry interaction — T2-D territory; /retry replays a
    captured summary's prose layer and reuses this pipeline
  - any third language beyond en/cs — adding a language is a
    bundle drop-in (T2-C.2's mechanism) plus a config update
    to widen the provider's `languages` set; not in T2-C.1
  - any InboundRouter edit — no new command in T2-C.1

**Acceptance shape** (transcribe spec promises per the
acceptance-transcribe memory — each separable sentence becomes
one acceptance item):

  - 7–10 acceptance items covering:
    1. en-scope short-circuit: `/summary` in a scope with
       `language='en'` returns prose unchanged from
       sanitizer-1 output; TranslationPipeline is never called.
    2. non-en scope: `/summary` in a scope with `language='cs'`
       returns prose translated to Czech; cache populated.
    3. cache hit: second `/summary` against the same
       deterministic post selection in the same cs scope hits
       the cache (no second LlmProvider call); IT asserts via
       a counting test stub.
    4. cache key derivation: two callers whose pre-sanitizer
       LLM outputs differ trivially (e.g. one carries an
       admin-verb fragment the sanitizer strips, the other
       does not) collide on the same key after sanitization;
       second caller hits the cache.
    5. cache value sanitization: cache returns post-
       sanitizer-2 text directly; the value is NOT re-
       sanitized on hit (test stub for sanitizer counts one
       call per cache miss, zero per hit).
    6. double-sanitization on miss: cache-miss path runs
       LlmOutputSanitizer twice — once on the LLM prose
       (sanitizer-1) and once on the translated text
       (sanitizer-2); the test verifies via a counting
       sanitizer stub.
    7. source post body invariance: a `/summary` over posts
       whose body text contains an admin-verb-shaped fragment
       does NOT translate the post body fragment — only the
       LLM-authored prose around it. IT asserts the post.body
       column is unchanged.
    8. degraded-prose path unchanged: `cp.degraded()=true`
       skips the TranslationPipeline (deterministic bundle
       string, not LLM-authored prose); IT asserts no
       translator call on the degraded branch.
    9. LlmRouter config-driven languages: with config
       `infochat.llm.openai-compatible.languages=en,cs`, the
       router resolves `forTask(TRANSLATOR, "cs")` to that
       provider; without the config, the priority-3 default
       still resolves it (degraded translation in cs scope
       returns the en text — acceptable behavior pending
       operator config).
    10. translator prompt structure: the prompt sent to the
        LlmProvider wraps the input in the
        `<<<UNTRUSTED_CONTENT id=...>>>` wrapper and includes
        the "preserve backticks / UIDs / treat as data"
        instructions per design §5.6.
  - One @QuarkusTest IT exercising the cs-scope `/summary`
    happy path through the full pipeline against the
    InMemoryAdapter, with a stub LlmProvider that returns
    deterministic translated text.
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 11–13. Pushing the threshold; new files
likely include: `LlmTranslationProvider.java`,
`TranslationCache.java`, `TranslationPipeline.java`,
`prompts/translator.md` (resource), 3–4 unit tests + 1 IT;
edited files include `LlmRouter.java` (config-driven
capabilities), `SummaryCommandHandler.java` (pipeline splice),
maybe `pom.xml` (quarkus-caffeine). **If the count exceeds 12,
restructure into umbrella+subs**: T2-C.1-u umbrella (IT +
shared decisions), T2-C.1a (LlmTranslationProvider +
translator prompt), T2-C.1b (TranslationCache +
TranslationPipeline), T2-C.1c (LlmRouter config widening +
SummaryCommandHandler splice).

**security_relevant: true** — the pipeline-order invariant
(sanitize-1 → translate → sanitize-2 → cache write, with cache
key on post-sanitizer-1 English text and cache value on post-
sanitizer-2 translated text) is a spec-load-bearing security
commitment. A wrong order would either ship an unsanitized
admin-verb string to a user (if sanitizer-2 is skipped) or
silently de-cache equivalent strings (if the key is computed
pre-sanitizer-1). The reviewer flags this ticket for `/redteam`
after merge.

### T2-C.2 (M1-060) — /lang command + cs.properties + BundleLoader per-scope language

**Spec anchors** (cite verbatim in `spec_refs:`):

  - `docs/spec/commands.md` §Conversation control — the
    `/lang <code>` paragraph (line 581 at brief authoring
    time): "sets per-scope output language. v1 ships English
    and Czech. DM: own scope. Group: group admin only. An
    unsupported code produces a friendly error that lists the
    supported codes — never a silent no-op and never a fall-
    through to the default."
  - `docs/spec/commands.md` §Permission model — `/lang` in
    groups is in the "Group-admin" closed list; in DM it is
    own-scope-only (not in the bot-admin closed list).
  - `docs/spec/llm.md` §Translation flow — the
    "**Deterministic strings come from a localization bundle,
    not the translator (decision D43)**" paragraph (lines
    208–220), specifically: "v1 ships `en` and `cs` (Czech)
    bundles; adding a third language is a bundle drop-in."
  - `docs/spec/llm.md` §Pipeline order — the
    "Deterministic localization-bundle strings ... are emitted
    **directly to the adapter** with no LLM call, no
    sanitizer pass, no `TranslationProvider` invocation"
    paragraph (lines 230–237) plus the "if the key is missing
    in the scope's language, lookup falls back to `en` (a
    missing `en` key is a startup error, decision D43)" rule.
  - `docs/spec/schema.md` §Per-scope state — the
    `scope_preferences.language` column (default `'en'`).

**Design references**:

  - `docs/design/05-llm-and-embeddings.md` §5.6 Per-scope
    language paragraph (line 429 onward).
  - M1-035c (BundleLoader introduction) and M1-040 (Provider
    bundle key fan-out) as the prior art on bundle-key
    conventions.
  - M1-044c (admin command handlers — permission-check
    pattern) and M1-053 (per-scope source admin commands —
    group-admin enforcement pattern) for /lang's permission
    check shape.

**Locked decisions**:

  - **Supported codes are `en` and `cs` in v1.** The supported
    set is derived from the bundles directory at startup
    (`bundles/<lang>.properties` files on the classpath), NOT
    hard-coded in `LangCommandHandler`. Adding a third
    language stays a bundle drop-in per spec line 213–215; no
    code change to the handler.
  - **`/lang <code>` validates against the bundles-derived
    set.** Unsupported → friendly-error reply listing the
    supported codes verbatim from the same source. NEVER a
    silent no-op; NEVER a fall-through. (Spec line 583–584 is
    explicit on this.)
  - **DM permission**: caller updates their OWN scope only
    (`scope_kind='dm', scope_id=<caller's dm scope id>`). No
    cross-DM mutation possible (the handler reads the scope
    from InboundContext).
  - **Group permission**: group-admin only. Reuse M1-044c's
    `group_membership.is_group_admin = true` query pattern.
    Non-admin caller → friendly permission error before any
    mutation.
  - **NO confirm-gate.** `/lang` is not destructive — changing
    output language does not delete data or escalate
    permission. M1-051's ConfirmStateService is not involved.
  - **NO audit-log entry.** `/lang` is a user-preference
    mutation, not a privileged action. Verify spec §Audit
    invariants at authoring time — if the spec assigns an
    audit verb to `/lang`, escalate via a `spec:` commit
    BEFORE writing this ticket.
  - **BundleLoader refactor preserves the existing API as
    much as possible.** Today: single-arg `get(String key)`,
    hardcoded `en`. After T2-C.2: a per-language lookup with
    en fallback. The simplest shape is a 2-arg `get(String
    key, Locale lang)` PLUS a per-scope convenience accessor
    that resolves the lang from `InboundContext.scope()` or
    an explicit `scope_preferences.language` parameter. Audit
    the existing call sites:
    ```
    grep -rn "bundleLoader.get\b" infochat-provider/src/main/java/
    ```
    and update each to thread the scope's language through.
    The exact API shape is design-tier — decide at authoring
    time after the audit. **Constraint**: the
    `BundleLoaderTest` reflection-completeness check stays
    green (it iterates `BundleKeys` constants and asserts
    every key resolves in every loaded bundle).
  - **`en.properties` stays unchanged in this ticket.** Every
    key in `cs.properties` MUST match a key in `en.properties`;
    the completeness test is bilateral. If T2-C.2's
    implementation discovers a key in en.properties that has
    no sensible Czech translation, escalate via a `spec:`
    commit to fix the key BEFORE landing T2-C.2 — do NOT add
    placeholder/English strings in cs.properties.
  - **UTF-8 encoding for cs.properties** is mandatory. The
    existing BundleLoader load path (line 52) already wraps
    the InputStream in `InputStreamReader(stream,
    StandardCharsets.UTF_8)` — the load code is ready; T2-C.2
    just ships the cs.properties file in UTF-8 with diacritics
    intact (`á`, `ě`, `ž`, etc.).
  - **Missing-key fallback**: on `bundle.get(key, "cs")` when
    `cs` does not have the key, fall back to `en`. A missing
    key in `en` is a startup error (the bundle-completeness
    `BundleLoaderTest` catches it, AND BundleLoader throws
    `IllegalStateException` per its existing line 68
    contract).

**Out-of-scope**:

  - any LlmTranslationProvider work — T2-C.1 territory
  - any translation-cache work — T2-C.1 territory
  - any sanitizer-2 path — T2-C.1 territory
  - any `scope_preferences.language` column change (the
    column already exists with default 'en' in V7; T2-C.2
    just writes to it)
  - any new DB migration
  - any third language beyond en/cs (the mechanism is a
    bundle drop-in — a third language is its own bundle file +
    a config-driven languages widening per T2-C.1's router
    work, but not a T2-C ticket)
  - any change to TranslationProvider SPI
  - any change to the LLM-authored translation pipeline
  - any /forget interaction (T2-E later; /lang preference is
    NOT user-owned data in the spec's purge set)
  - any InboundRouter edit — `/lang` registers as a new
    CommandHandler bean

**Acceptance shape**:

  - 5–7 acceptance items covering:
    1. `/lang cs` in a DM scope writes `scope_preferences
       (scope_kind='dm', scope_id=<caller_dm>, language='cs')`
       (UPSERT shape) and replies in `cs` (using the just-
       written language so the confirmation reply itself
       respects the change — verify the response uses the cs
       bundle).
    2. `/lang xx` (unsupported) replies with a friendly error
       listing `en, cs` (the bundle-derived supported codes)
       and does NOT mutate `scope_preferences`.
    3. `/lang cs` in a group scope by a NON-group-admin
       returns the friendly permission error and does NOT
       mutate `scope_preferences`.
    4. `/lang cs` in a group scope by a group-admin writes the
       group scope's row and replies in cs.
    5. Bundle fallback: requesting a key that exists ONLY in
       `en.properties` and NOT in `cs.properties` (use a test
       bundle for the assertion) returns the `en` value, not
       an exception.
    6. Bundle-completeness CI: BundleLoaderTest still passes
       after cs.properties is added; every key in
       `BundleKeys` resolves in BOTH `en` and `cs`.
    7. UTF-8 round-trip: a cs.properties entry with diacritics
       (e.g. `žluťoučký`) loads and resolves correctly through
       the BundleLoader.
  - One @QuarkusTest IT exercising `/lang cs` in a DM scope
    and verifying the next bot-emitted bundle string lands in
    Czech via the InMemoryAdapter.
  - `mvn -B clean verify` exits 0.

**files_budget hint**: 6–9. New: `LangCommandHandler.java` +
test, `cs.properties` (new bundle file, ~278 lines mirroring
en.properties), BundleKeys additions for /lang reply messages,
bundle-fallback test, IT. Edited: `BundleLoader.java` (per-
scope language refactor), every call site of
`bundleLoader.get(...)` (audit-driven count — likely 10–20
handler files threading the scope's language through).
**Threshold risk**: the call-site sweep might push past 12
files in the "edited" column. If so, restructure: leave the
single-arg `bundleLoader.get(String key)` in place (returns
the `en` value as today, preserving every call site) and add
a NEW 2-arg `get(String key, Locale lang)` as the per-scope
accessor. The /lang handler ALONE uses the 2-arg form for its
confirmation reply; the existing call sites get migrated in
T2-D (chat-mode) and T2-F (groups + digests) when those
handlers are introduced. This shrinks T2-C.2 to a clean
bounded shape; the cross-cutting migration of every handler
to the per-scope accessor becomes T2-F's responsibility
(digests are the load-bearing translation case anyway). **Use
the smaller shape unless you find a reason it can't work.**

**security_relevant: false** — `/lang` mutates a user-
preference column; it does not change authorization state,
audit invariants, or any threat-model commitment. NOT flagged
for `/redteam`.

## Locked decisions (cross-cutting across T2-C)

- **No InboundRouter edits.** T2-C.2 adds `LangCommandHandler`
  as a new CommandHandler bean; the router picks it up via
  the `Instance<CommandHandler>` iteration established by
  M1-035b. Verify at authoring time by reading
  `infochat-provider/src/main/java/.../messaging/InboundRouter.java`
  §handleSlash.
- **T2-C reuses the M1-040 InboundContext.** Handlers `@Inject
  InboundContext` for the `(adapter, contact_id, scope)`
  lookup. Same pattern as M1-036, M1-044c, M1-052/053/054.
- **T2-C consumes M1-041's LlmOutputSanitizer unchanged.** No
  edits to LlmOutputSanitizer itself; T2-C.1's
  TranslationPipeline calls `sanitizer.sanitize(...)` twice
  per the spec pipeline order.
- **Bundle keys**: every new user-visible reply ships through
  a bundle key under
  `infochat-provider/src/main/resources/bundles/en.properties`
  (and `cs.properties` for T2-C.2) + a `public static final
  String` constant on `app.zcat.infochat.provider.bundle.BundleKeys`.
  NO inline string literals in handler code.
- **No migration**: T2-C lands ZERO Flyway migrations. T2-C.1
  uses an in-memory Caffeine cache; T2-C.2 writes to the
  already-present `scope_preferences.language` column.
- **Spec edits are forbidden in T2-C** — the §SPI shape +
  §Translation flow + §Pipeline order + §Conversation control
  (/lang paragraph) + §Permission model + decision D43 sections
  are already complete on main HEAD. Every acceptance item
  must trace to spec text already there. If a sentence is
  missing or ambiguous, escalate to `spec-amend` BEFORE
  implementing.
- **No `--no-verify`, no test disables.** Standard engineering
  rules apply.

## Design-vs-spec drift (flag for future spec: edit, not T2-C work)

Two design-notes-vs-shipped-code drifts that T2-C does NOT fix
in-ticket — they are addressed via separate `spec:` commits
AFTER T2-C lands so this session stays focused on the code
delivery:

1. **TranslationProvider SPI signature drift.**
   `docs/design/05-llm-and-embeddings.md` §5.6 declares:
   ```
   public interface TranslationProvider {
       String translate(String text, String fromLang, String toLang);
       Set<String> supportedTargetLangs();
       boolean canTranslate(String from, String to);
   }
   ```
   The actual shipped SPI at
   `infochat-messaging-adapter/.../TranslationProvider.java`
   has only:
   ```
   String translate(String text, Locale from, Locale to);
   ```
   T2-C.1 locks to the SHIPPED SPI (no method additions). The
   capability-set surface (`supportedTargetLangs`) is unused —
   the user-facing supported-codes list is derived from
   `bundles/<lang>.properties` files on the classpath
   (T2-C.2's mechanism), not from the translator. **After
   T2-C lands**, file a `spec:` commit that aligns
   `docs/design/05-llm-and-embeddings.md` §5.6 with the
   shipped 1-method SPI and notes that supported-codes come
   from the bundle layer.

2. **Design notes say `/help` text is translated** (line
   420: "Translated: Bot's outgoing prose ... `/help` text").
   **Spec is the opposite** (line 208–220: deterministic
   strings come from a localization bundle, NOT the
   translator). Spec wins. T2-C.2 ships `/help` strings via
   the bundle path only — they are NEVER routed through
   TranslationProvider. **After T2-C lands**, file a `spec:`
   commit that fixes `docs/design/05-llm-and-embeddings.md`
   §5.6 to remove `/help` from the "Translated:" list.

These drifts are NOT blockers for T2-C; they are post-T2-C
spec hygiene. Flag them in the chat summary at end of session
so the operator can schedule the spec commits.

## Shared-surface chokepoints (flag for the future session)

- **InboundRouter dispatch**: NOT a chokepoint — the router
  iterates `Instance<CommandHandler>`. T2-C.2's
  LangCommandHandler is a new bean.
- **AuditAction enum**: NOT touched — `/lang` is NOT audit-
  logged in v1 (verify against spec §Audit invariants at
  authoring time).
- **Flyway migrations**: NOT touched — T2-C consumes zero
  migration integers. If M1-058 or T2-H consume integers
  during this session, no collision.
- **BundleKeys + properties files**:
  `infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java`,
  `infochat-provider/src/main/resources/bundles/en.properties`,
  `infochat-provider/src/main/resources/bundles/cs.properties`
  (new in T2-C.2). T2-C.1 adds ~2 keys (translator-error
  friendly reply if a translation call fails); T2-C.2 adds
  ~5 keys (/lang reply patterns) AND mirrors EVERY existing
  en key into cs.
- **LlmRouter** (`infochat-llm-adapter/src/main/java/.../routing/LlmRouter.java`):
  T2-C.1 replaces the hardcoded `supportedLanguagesFor`
  helper with a config lookup. NOT touched by T2-C.2.
- **SummaryCommandHandler** (`infochat-provider/src/main/java/.../command/SummaryCommandHandler.java`):
  T2-C.1 splices the TranslationPipeline call between
  sanitize-1 and adapter send. NOT touched by T2-C.2.
- **BundleLoader** (`infochat-provider/src/main/java/.../bundle/BundleLoader.java`):
  T2-C.2 refactors. NOT touched by T2-C.1.
- **CommandPermissions** (`infochat-provider/src/main/java/.../command/CommandPermissions.java`):
  Verify whether `/lang` is in the slow-start probation
  allowed-set per spec §Slow-start tier at authoring time.
  If yes, add the constant; if no, T2-C.2 does NOT add it
  (probation users are blocked from `/lang` by default).

## Parallel-development collision plan

T2-C runs alone in its session. No other ticket-authoring
session is planned in parallel. M1-058 is `pending` and
runnable but is implementation work, not authoring; if a
parallel implementation session starts M1-058 while T2-C is
being authored, no collision (M1-058 touches the admin-
notification surface; T2-C touches the translation surface).

If M1-058 lands during this session, the only effect on T2-C
is that the M1 id space shifts by zero (M1-058 is already
allocated). Re-grep `ls docs/plan/m1/tickets/ | sort -V | tail`
at the moment of writing each ticket file.

## After authoring all tickets

1. Verify each ticket's `spec_refs:` anchors actually exist
   with `grep -nE '^## |^### ' docs/spec/<file>` (clarity-check
   pre-flight blocks otherwise).
2. Verify each ticket's `files_scope:` paths exist or are
   plausibly new (relative-path under one of the modules).
3. Verify the next-free M1 ticket id by re-grepping the
   worktree — M1-058 implementation may have started, but
   no new ID allocation will collide.
4. Run `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md'
   docs/plan/m1/STATUS.md` and confirm pending count rose by
   exactly the number of new tickets (2 in the default split;
   more if T2-C.1 took the umbrella+subs escape hatch). Both
   should appear as Runnable (independent of each other and
   independent of M1-058's pending work).
5. Leave the new ticket files UNTRACKED on main. Do NOT
   commit them. The workflow rule: drafts ride untracked
   through `/m1-tick start`.
6. Update `docs/plan/m1/drafts/session-grouping-plan.md`
   Tier 2 row for T2-C to record the actual IDs (whichever
   the re-grep step yielded) AND update the count from "1
   ticket" to the actual split (likely "2 tickets"). Commit
   that single edit as `process: Record T2-C ID allocation
   (<actual-IDs>)`.
7. Print a one-screen summary in chat listing the new ticket
   IDs and titles, plus the two design-notes drifts (§"Design-
   vs-spec drift" above) that need follow-up `spec:` commits.
   Recommended start order: T2-C.1 first (load-bearing
   translation pipeline), T2-C.2 second.

## What you do NOT do in this session

- Do NOT author any T2-A/B/D/E/F/G/H tickets. Those are
  separate sessions.
- Do NOT implement any T2-C code. No `src/` edits anywhere.
- Do NOT amend any spec or design file. T2-C's spec is
  already complete on main HEAD. The design-notes drifts are
  flagged for follow-up `spec:` commits AFTER T2-C lands —
  not in this session.
- Do NOT touch M1-058 or T2-H. None of them collide with
  T2-C's surface.
- Do NOT run `mvn verify`. Ticket authoring does not touch
  Java code.
- Do NOT commit the new ticket files; they ride untracked
  into `/m1-tick start`.

## Engineering rules in force

The full rules live in `CLAUDE.md` §Engineering rules and
`docs/process/engineering-rules-verbatim.md`. The ones that
bite for this session:

- **Surgical changes.** Each commit touches only the files
  its task needs. The session-grouping-plan edit in step 6
  is one separate `process:` commit.
- **No defensive code for impossible scenarios.** Validation
  belongs at system boundaries. /lang's code validation is a
  boundary check (user input). Internal pipeline calls
  between TranslationPipeline → LlmTranslationProvider →
  LlmRouter are trusted.
- **No workarounds, no shortcuts.** If a constraint blocks
  ticket authoring, escalate via the workflow — never reach
  for destructive shortcuts or guess at a spec the brief did
  not resolve.
- **Push back when simpler exists.** If the brief's 2-ticket
  default split has a materially simpler alternative (e.g.
  T2-C.2 stays at the existing 1-arg BundleLoader API and
  defers the per-scope migration to T2-F entirely; T2-C.1's
  files cleanly fit a single ticket with no umbrella+subs),
  surface it in chat BEFORE committing the files. The 2-
  ticket default is the conservative shape, not the only one.
- **Read spec files only when something is unclear.** The
  brief cites the spec anchors with section names and line
  numbers; the authoring session reads those sections
  directly rather than re-deriving state from the codebase.
- **Transcribe spec promises into security-ticket acceptance
  items.** T2-C.1 is `security_relevant: true`. The
  pipeline-order paragraph in spec §Translation flow has
  multiple separable invariants (en short-circuit, source
  body invariance, cache-key derivation, double-
  sanitization, etc.) — each becomes its own acceptance
  item, no summarizing.

## Outputs

By the end of this session:

- Two (or more, if umbrella+subs on T2-C.1) new ticket files
  exist UNTRACKED under `docs/plan/m1/tickets/`. They appear
  in STATUS.md as `pending` and Runnable (no `blocked_by`
  from any in-flight ticket; the two T2-C tickets are
  independent of each other).
- One `process:` commit on main updates the session-grouping-
  plan's T2-C row (recording the actual IDs and the actual
  split count).
- Working tree contains the new ticket files (untracked) and
  STATUS.md (committed via the process: commit). No code
  changes.

The natural next step is `/m1-tick start <id>` against
whichever T2-C ticket the operator picks first (recommended:
T2-C.1).
```

---

## Quick-reference checklist for the operator

When you open the fresh session and paste the block above:

- [ ] Two (or more) new ticket files appear UNTRACKED under
      `docs/plan/m1/tickets/`. Status: pending, Runnable.
- [ ] STATUS.md regenerates with pending count up by the new
      ticket count.
- [ ] One `process:` commit on main updates the session-grouping
      plan's T2-C row.
- [ ] No `src/` edits anywhere.
- [ ] No spec or design edits.
- [ ] No interaction with M1-058 or T2-H's pending work.

If the session deviates (touches code, amends the spec, or
authors T2-A/B/D/E/F/G/H tickets), it has misread the brief —
abort and start over with the same prompt.

After T2-C tickets land and merge, the two flagged design-notes
drifts (§"Design-vs-spec drift" in the brief) need follow-up
`spec:` commits to align the design notes with the shipped SPI
and with spec decision D43. Those are NOT this session's work;
they are flagged for a separate, small spec edit.
