---
id: M1-603
title: "One LLM service by default: shared infochat.llm.default.{base-url,api-key} inherited by every ModelTask; remove the per-task baked base-urls so a task absent from operator config inherits the operator's endpoint — or refuses boot loudly"
status: pending
created: 2026-07-10
last_updated: 2026-07-11
blocked_by: []
files_budget: 18
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - prod/scripts/4-llm.sh
  - prod/switch-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard*Test.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/*WiringTest.java
  - docs/spec/llm.md
  - docs/spec/decisions.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Embeddings. infochat.embeddings.base-url is a separate SPI surface with its
    own resolution (one provider per deployment, D54: embeddings NEVER route
    remote) and is NOT swept into the new shared default. The embedder is not a
    ModelTask; its key, baked default, and wizard lines stay exactly as they
    are.
  - >-
    Per-task MODEL keys. infochat.llm.<task>.model stays per-task with baked
    per-task defaults (profiles tune models per task — e.g. vps reuses one model
    for security+chat, pi runs smaller models). Model is task tuning, not
    service identity; it does not inherit from a default.model key and no such
    key is introduced. A stale operator config whose NEW task inherits the
    operator's remote default.base-url while its baked model is a local-runtime
    name (llama3.1:8b) is caught by the EXISTING M1-577 mismatch scan
    (local-runtime model prefix + remote host → WARN / opt-in fail-fast) — that
    scan and its localOllamaShapeIsNotFlagged carve-out are unchanged.
  - >-
    The per-task base-url/provider OVERRIDE keys themselves. Per-task overrides
    remain fully supported (highest precedence) — the %remote-llm profile's
    three per-task Anthropic base-url/provider/model lines
    (chat/summarizer/translator) stay byte-for-byte as they are, and old
    wizard-generated operator files that fan the endpoint out per-task remain
    valid. This ticket changes what a task with NO per-task value falls back to;
    it does not deprecate the per-task axis (docs/spec/llm.md Goal 2).
  - >-
    Reconciling the "ingest tasks stay on the local model even under the
    remote-llm profile" comment (baked classifier block) with the D54 wizard
    behavior that routes ALL generative tasks to the remote endpoint — a
    checked-in design contradiction surfaced during this re-scope (2026-07-11).
    This ticket preserves the operative behavior (per-task %remote-llm
    overrides stay; wizard-generated configs win at ordinal 260) and the
    contradiction is left for a spec follow-up. Also out of scope: making
    docker-compose.yml inject a compose-correct default base-url
    (http://ollama:11434/v1) — compose environment entries sit at ordinal 300,
    ABOVE the operator's mounted file (260), so they would silently override
    operator intent; no clean static answer exists and it is not attempted
    here.
  - >-
    The operator runtime config on any host (prod/runtime/application.properties)
    and the already-completed 2026-07-10 corpus reclassification — host-local
    operator actions, gitignored, not repo artifacts. Startup-guard detection
    approaches (config-source ordinal introspection, reachability probing,
    loopback-outlier heuristics) considered in earlier drafts of this ticket are
    all superseded: this ticket removes the misconfiguration CLASS instead of
    detecting instances of it.
acceptance:
  - >-
    Two new shared config keys exist: infochat.llm.default.base-url and
    infochat.llm.default.api-key, declared as constants next to the existing
    LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER (same naming family,
    infochat.llm.default.*). Both concrete providers (OpenAiCompatibleProvider,
    AnthropicProvider) resolve a task's effective base-url as: per-task
    infochat.llm.<task>.base-url when set, else infochat.llm.default.base-url —
    and effective api-key the same way (per-task, else default, else empty
    string as today). The existing per-call validation
    (LlmHttpSupport.requireHttpBaseUrl) runs against the effective value.
  - >-
    A task with NO effective base-url (neither per-task nor default) refuses
    startup through the existing assertAllTasksResolve /
    assertTaskConfigResolvable path, with an error message naming BOTH keys the
    operator can set (the per-task key and infochat.llm.default.base-url) — a
    loud boot failure instead of the 2026-07-10 silent 100%-call-failure. No
    new guard scan is added for this; the required-config machinery already
    fails boot, it just gains the default-key fallback and the two-key message.
  - >-
    The baked per-task base-url and api-key lines (7 tasks × 2 keys, in BOTH
    infochat-collector and infochat-provider application.properties) are
    REMOVED and replaced by profile-scoped shared defaults:
    %laptop/%vps/%pi.infochat.llm.default.base-url=http://localhost:11434/v1
    (their Ollama is genuinely on-host), and NO default for %remote-llm — on
    that profile every task must be routed explicitly (per-task key or an
    operator-set default), so a stale operator config that predates a NEW
    ModelTask fails boot naming the missing keys instead of silently inheriting
    a dead loopback address. The %remote-llm per-task Anthropic lines
    (chat/summarizer/translator) are kept as-is. Quarkus test/dev launch modes
    keep booting: the test/dev profiles get an explicit default.base-url so
    every existing @QuarkusTest and quarkus:dev boot still resolves (concrete
    mechanism — %test./%dev. lines or equivalent — is the implementer's
    choice).
  - >-
    LlmRouterStartupGuard consumes EFFECTIVE per-task base-urls (per-task key,
    else the new default key) in all three existing scans: the local-only
    conflict scan treats an off-host infochat.llm.default.base-url as an
    offender for every task without a per-task override (mirroring how it
    already treats a cloud-only default.provider), the non-local-only
    disclosure WARN discloses tasks made remote via the default, and the
    M1-577 mismatch scan judges effective (provider, base-url, model) triples.
    snapshotConfig snapshots the two new keys. Every existing verdict on
    configs that set per-task keys explicitly is unchanged (the existing guard
    tests stay green, with only additive new cases).
  - >-
    The wizard writes the shared default instead of fanning out per task:
    prod/scripts/4-llm.sh (set_all_base_urls / set_llm_base_urls and the api-key
    loops) and prod/switch-llm.sh write infochat.llm.default.base-url +
    infochat.llm.default.api-key once, keep writing per-task model lines, and
    leave infochat.embeddings.* handling unchanged. The wiring tests
    (SwitchLlmWiringTest, RemoteLlmWiringTest, LlamacppWiringTest) are updated
    to assert the new written keys (note: switch-llm's positional stdin
    consumption changes when the per-task loop collapses — see the M1-599
    lesson).
  - >-
    New tests: (a) provider-level — a task with only the default keys set
    resolves to the default endpoint/key, a task with both set resolves to the
    per-task value, a task with neither fails with the two-key message; (b)
    guard-level — off-host default.base-url under local-only is fatal and names
    the default key, the M1-597 incident shape (operator default =
    remote endpoint, task with no per-task keys) resolves to the remote
    endpoint rather than any loopback fallback. docs/spec/llm.md §Per-task
    routing rules documents the base-url/api-key resolution order,
    docs/design/05-llm-and-embeddings.md documents the key table change, and
    docs/spec/decisions.md gains a decision entry (next free D-number): "one
    LLM service by default — per-task base-url/api-key inherit from
    infochat.llm.default.*; per-task overrides remain; %remote-llm has no
    baked default so unrouted tasks refuse boot." mvn verify green from the
    repo root.
test_plan:
  adds:
    - >-
      OpenAiCompatibleProviderTest + AnthropicProviderTest: default-only
      inheritance, per-task-beats-default precedence, neither-set → config
      error naming both keys (per-task + default).
    - >-
      LlmRouterStartupGuardTest: local-only + off-host default.base-url →
      fatal naming infochat.llm.default.base-url; incident shape (remote
      default, no per-task keys for one task) → that task's effective route is
      the remote endpoint (asserted via the mismatch-scan/disclosure surfaces);
      mismatch scan judges effective triples (local-runtime model + remote
      DEFAULT base-url → flagged).
  modifies:
    - >-
      SwitchLlmWiringTest / RemoteLlmWiringTest / LlamacppWiringTest — assert
      infochat.llm.default.base-url + default.api-key written once instead of
      seven per-task lines; positional stdin expectations updated where the
      per-task prompt loop collapsed.
    - >-
      Existing provider/guard tests that seeded per-task base-urls keep
      passing unchanged (per-task keys keep highest precedence).
  preserves:
    - >-
      Every existing guard verdict for explicitly per-task-configured
      snapshots; the M1-577 scan and its localOllamaShapeIsNotFlagged
      carve-out; the %remote-llm per-task Anthropic overrides; embeddings
      resolution (D54) untouched; old wizard-format operator files (per-task
      fan-out) remain fully valid.
    - >-
      The eval workers' graceful {unknown} fallback and all runtime routing
      priorities (per-task override → language capability → default provider)
      — only the config-value fallback for base-url/api-key changes.
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Goals
decision_refs:
  - D54
redteam_findings: []
redteam_audits: []
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-603: one LLM service by default — shared endpoint config every task inherits

## The problem, in plain English

The app runs seven LLM "tasks" (security judge, tagger, entity extractor,
classifier, summarizer, chat, translator). Today each task carries its **own**
server address (`infochat.llm.<task>.base-url`) and API key. There is no shared
"here is my LLM service" setting — even though in practice every deployment
points all seven tasks at **one** service (the wizard literally writes the same
URL seven times: `4-llm.sh` — "The seven per-task LLM config families share one
endpoint; only the model differs").

Because the address is stored per-task, each task also has its own **baked-in
fallback** (`http://localhost:11434/v1`, 7× in both the collector's and the
provider's `application.properties`). That produced the 2026-07-10 incident:

- The classifier task was added in M1-597. The operator's config file predated
  it, so it configured the six older tasks (→ DeepSeek) and said nothing about
  the classifier.
- With no per-task line and no shared default to inherit, the classifier fell
  through to its baked `localhost:11434` — a **dead address inside the collector
  container** (the local Ollama is the `ollama:11434` compose sibling; loopback
  points at the collector container itself).
- Every classifier call failed to connect; the worker gracefully stamped each
  post `unknown`. The app booted clean. ~4,700 posts silently rotted before
  anyone noticed.

Earlier drafts of this ticket tried to **detect** that state with a new startup
guard scan. Each detection approach was either misaligned with the guard's
value-based character (config-source ordinal introspection) or heuristic with
false positives (loopback-outlier). The re-scope conclusion: the misconfiguration
class exists only because base-url is per-task-with-per-task-baked-fallbacks. Fix
the config model and the class disappears — no detector needed.

## The fix, in plain English

Give the endpoint the same shared-default-with-optional-override treatment the
**provider** already has (`infochat.llm.default.provider`):

1. **New keys** `infochat.llm.default.base-url` and
   `infochat.llm.default.api-key`. A task with no per-task value inherits them.
   Per-task values still win when present (the `%remote-llm` profile's
   Anthropic chat/summarizer/translator lines, and old wizard-generated
   operator files, keep working untouched).
2. **Delete the 7× baked per-task base-url/api-key lines** (both services).
   The bare-metal profiles (`laptop`/`vps`/`pi`) get one profile-scoped
   default (`localhost:11434` — genuinely on-host there). `%remote-llm` gets
   **no default on purpose**: on the profile where a wrong guess silently costs
   money or silently dies, every task must be routed explicitly.
3. **A task with no effective base-url refuses boot**, through the
   *already-existing* required-config startup check, with a message naming both
   keys the operator can set.
4. **The wizard writes one default line instead of seven**, so the operator
   surface finally matches the "one LLM service" reality.

What each deployment shape gets after this:

- **Laptop/vps/pi out of the box** — profile default → works, same as today.
- **Wizard-generated config, any backend** — one `default.base-url` line; a
  **future new task inherits the operator's real endpoint automatically**. The
  incident becomes structurally impossible for wizard configs.
- **Old-format operator file (per-task lines) + a future new task on
  remote-llm** — the new task finds no per-task line and no default → **loud
  boot refusal naming the exact keys**, instead of a dead loopback and silent
  `unknown`s. This is the guard the original M1-603 wanted, obtained for free
  from the required-config machinery.
- **Stale model on an inherited remote endpoint** (baked `llama3.1:8b` against
  DeepSeek) — already caught by the existing M1-577 mismatch scan
  (local-runtime model + remote host).

Every path is either *working* or *loud*. Nothing is silent anymore.

## Guard alignment (why no new scan)

`LlmRouterStartupGuard`'s three scans stay value-based and just consume the
*effective* base-url (per-task, else default) — exactly parallel to how the
local-only scan already treats a cloud-only `default.provider` as an offender
for every task without an override. The `%remote-llm`-boot-refusal behavior is
not a guard feature; it is the existing `assertAllTasksResolve` /
`assertTaskConfigResolvable` required-key check gaining the default-key
fallback.

## Findings surfaced during re-scope (reported, out of scope)

- The checked-in `%remote-llm` profile leaves **all four ingest tasks**
  (security/tagger/entity/classifier) on baked `localhost:11434` — dead in the
  compose deployment. The 2026-07-10 incident was classifier-only only because
  the wizard file happened to override the other three. This ticket's removal
  of the baked per-task lines converts that shape from silent-failure to loud
  boot refusal on `%remote-llm`.
- The baked classifier comment ("ingest stays on the local model even under
  the remote-llm profile") contradicts the D54 wizard behavior (all generative
  tasks → remote endpoint). Left for a spec follow-up; this ticket preserves
  operative behavior.
- Out-of-box `%remote-llm` chat/summarizer/translator were never functional
  anyway (Anthropic base-url with no API key → 401 per call), so the new loud
  boot posture on that profile is strictly an improvement, not a regression of
  a working shape.

## Scope

Four production Java files (two providers, router constants, guard), two
`application.properties`, two wizard scripts, the provider/guard/wiring tests,
and three docs (spec, design, decisions). Embeddings, per-task model keys, the
per-task override axis itself, docker-compose defaults, and the
ingest-local-vs-all-remote design contradiction are all explicitly out of scope
(see `out_of_scope`).
