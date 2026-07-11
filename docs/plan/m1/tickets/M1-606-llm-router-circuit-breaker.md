---
id: M1-606
title: "LLM router circuit breaker: fail-fast + pre-fetch skip on unreachable provider"
status: pending
created: 2026-07-11
last_updated: 2026-07-11
blocked_by: []
files_budget: 12
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
provenance: M1-589 redteam 2026-07-11 (r2 low DOS/spec-drift finding, promoted to a proper cross-cutting feature)
out_of_scope:
  - >-
    Actual provider fallback / retry to a SECOND endpoint. v1 has no
    router-side fallback by design (security.md §Failure handling, "No
    router-side fallback in v1"). This ticket adds fail-FAST (short-circuit a
    known-unreachable provider), NOT fail-OVER. If a task's only provider is
    OPEN, the consumer still degrades exactly as it does today for that task
    (chat → friendly error; summary → headlines fallback; ingest workers →
    their existing backoff/RAW retention). Do not add a second-provider
    concept.
  - >-
    Any change to M1-589's semantic-retrieval SQL, the SemanticSearchTool
    query, or the deterministic pre-fetch's behaviour WHEN the breaker is
    CLOSED. This ticket only adds the CLOSED→OPEN skip of the pre-fetch; the
    retrieval path itself is unchanged.
  - >-
    Per-ModelTask breaker granularity when multiple tasks share one endpoint.
    v1 keys the breaker by resolved provider ENDPOINT (base-url), so all tasks
    routed to one endpoint share its breaker state — matches the D56
    one-LLM-service-by-default topology. Per-task breakers are a future
    refinement only if a deployment routes tasks to distinct endpoints AND
    that granularity proves necessary.
  - >-
    Persisting breaker state across process restarts. The breaker is
    in-memory; a restart resets it to CLOSED (the first post-restart call
    re-probes). No new table, no migration.
acceptance:
  - >-
    A per-endpoint circuit breaker guards LlmRouter.forTask(...)'s resolved
    LlmProvider (infochat-llm-adapter): CLOSED by default; after N consecutive
    provider-unreachable failures on that endpoint it trips to OPEN; an OPEN
    breaker short-circuits generate()/embed() with a typed
    "provider-unreachable" signal WITHOUT attempting the HTTP call; after a
    configured cooldown it goes HALF-OPEN and lets exactly one probe through;
    a successful probe closes it, a failed probe re-opens it. N (failure
    threshold) and the cooldown are config-driven (infochat.llm.breaker.*)
    with documented per-profile defaults.
  - >-
    Failure attribution is precise: only TRANSPORT/timeout failures
    (connection refused, DNS failure, read timeout — the provider is
    unreachable) trip the breaker. A provider that responds with an
    application error (schema violation, HTTP 4xx/5xx body) does NOT trip it,
    and a downstream non-LLM exception (e.g. a DB error inside a chat tool)
    never reaches the breaker. This requires distinguishing the failure class
    at the generate()/embed() call boundary rather than a blanket
    catch(Exception) — ChatAgent's current broad catch is narrowed to
    classify LLM-transport failures.
  - >-
    Time that drives the cooldown/HALF-OPEN decision is read from an injected
    java.time.Clock (engineering-rules §9), pinned in tests via
    QuarkusMock.installMockForType(Clock.fixed(...), Clock.class). No inline
    Instant.now() in the breaker's decision logic. ReEvaluationJob (M1-444) is
    the reference implementation.
  - >-
    ChatAgent consults the breaker before the deterministic semanticSearch
    pre-fetch (M1-589 doHandle step 3): when the chat endpoint's breaker is
    OPEN, the pre-fetch is SKIPPED — no embed HTTP round-trip, no pgvector
    probe — closing the M1-589 residual DOS (a doomed turn spends nothing once
    the breaker has tripped). The first failure of an outage window still pays
    one pre-fetch (that is how OPEN is discovered) and that residual is
    documented as bounded.
  - >-
    docs/spec/security.md §Failure handling is updated: the "No router-side
    fallback in v1" bullet is refined to describe fail-fast (a known-
    unreachable provider short-circuits without an HTTP attempt — distinct
    from fallback), and the M1-589 "a router-side circuit breaker ... is a
    v1-follow-up, tracked separately" note is turned into the delivered
    mechanism. The chat-mode failure bullet is updated to state the pre-fetch
    is skipped when the breaker is OPEN.
  - >-
    NAMED TESTS. A breaker unit/IT with a fixed Clock asserts: CLOSED→OPEN
    after the threshold of transport failures; OPEN short-circuits without an
    HTTP attempt (observable via a stub provider whose call-count stays flat);
    HALF-OPEN after cooldown admits exactly one probe; success closes,
    failure re-opens; an application-error response does NOT trip the breaker.
    A ChatAgent test asserts the semanticSearch pre-fetch is skipped (tool
    dispatch count 0) when the chat breaker is OPEN, and runs normally when
    CLOSED. Red-before/green-after on the OPEN-skip and the transport-vs-
    application attribution.
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds:
    - Breaker unit/IT (fixed-Clock state-machine + short-circuit assertions).
  modifies:
    - ChatAgent tests (pre-fetch skip when breaker OPEN; attribution narrowing).
  preserves:
    - all tests currently green on main
    - >-
      the M1-589 chat tests (the deterministic pre-fetch still runs on every
      turn when the breaker is CLOSED; shared-TurnContext cache behaviour
      unchanged).
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D54
  - D56
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-606: LLM router circuit breaker — fail-fast + pre-fetch skip

## Context

Promoted from the M1-589 redteam audit (2026-07-11, r2 low DOS/spec-drift
finding). M1-589 made the provider chat agent run a deterministic semantic
pre-fetch on **every** turn, before the chat LLM call. On an
LLM-unreachable turn that pre-fetch (one local embed + one pgvector probe)
runs before the failure surfaces — and more broadly, **every** LlmRouter
consumer spends work on turns doomed by a provider that is already known to
be down, because there is no fail-fast state. M1-589 amended
`security.md §Failure handling` to make the bounded pre-fetch cost
spec-accurate and recorded this ticket as the proper fix.

## Why router-level, not chat-only

`LlmRouter.forTask(ModelTask, scopeLanguage)` (`infochat-llm-adapter`,
around line 183) is the single choke point through which **all eight**
ModelTask consumers resolve a provider:

- Collector: `Stage2Worker`, `TaggerWorker`, `EntityExtractorWorker`,
  `ClassifierWorker`.
- Provider: `SummaryProseGenerator`, `CompressCommandHandler`,
  `LlmTranslationProvider`, `ChatAgent`.

A breaker glued into `ChatAgent` would protect one of eight and duplicate
state per consumer — the wrong altitude. The breaker belongs where every
consumer already funnels: wrapping the resolved provider (or decorating it)
so a known-unreachable endpoint fails fast for all of them, and so
`ChatAgent` can additionally *skip* the M1-589 pre-fetch when its endpoint
is OPEN.

## Shape (refine at start / plan)

- Per-**endpoint** (resolved base-url) breaker state: CLOSED → OPEN after N
  consecutive transport failures; OPEN short-circuits without an HTTP
  attempt; HALF-OPEN after a cooldown admits one probe; success closes,
  failure re-opens.
- Injected `Clock` for the cooldown window (§9; `ThrottledAdminNotifier`
  producer + `QuarkusMock` `Clock.fixed(...)` in tests; ref: `ReEvaluationJob`).
- Failure attribution at the `generate()`/`embed()` boundary: only
  transport/timeout failures trip the breaker; application errors and
  downstream non-LLM exceptions do not. `ChatAgent`'s blanket
  `catch (Exception)` is narrowed accordingly.
- `ChatAgent` skips the deterministic semanticSearch pre-fetch when the chat
  endpoint's breaker is OPEN (closes the M1-589 residual).
- `security.md §Failure handling` updated (fail-fast vs fallback distinction;
  the M1-589 "tracked separately" note becomes the mechanism).

## Notes

- **Not fail-over.** v1 keeps "no router-side fallback" — this is fail-FAST
  (short-circuit), not a second-provider retry. See out_of_scope.
- **Wording nit inherited from M1-589:** `security.md §Failure handling`
  cites `statement_timeout` as the pre-fetch bound, which caps only the
  pgvector probe; the embed HTTP call is bounded by
  `infochat.embeddings.timeout-ms`. Fix that citation here while the section
  is open (the "bounded" claim itself is correct; only the mechanism name is
  imprecise).
- **Provenance:** M1-589 redteam records
  `docs/plan/m1/redteam/M1-589-2026-07-11-r2.md` (finding) and `-r3.md`
  (CLEAN, out-of-model item 3 = the embed-timeout wording nit).
