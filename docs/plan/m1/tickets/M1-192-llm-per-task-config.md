---
id: M1-192
title: "LLM per-task config completion (configFor, remote-llm, guard)"
status: done
created: 2026-06-07
last_updated: 2026-06-07
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 14
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/application.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the router's unknown-default fallback posture (entries.get(0) + one-shot WARN) — deliberate documented M1-042 constraint per the audit (L3 PARTIAL); revisiting it is a decision ticket, not this one
  - joinPath/preview triplication and LlmHttpSupport consolidation (audit L7) — simplification tier, UNIFIED.md T33
  - AnthropicProvider.parseContentText content[0] handling (audit L8, low/theoretical)
  - worker-side fallback behavior (TaggerWorker etc. catching RuntimeException) — correct once the provider stops throwing; do not touch the workers
  - reintroducing Retry-After honoring / sleep-before-retry (redteam F2, DOS/low) — this ticket keeps the deletion branch of the Retry-After acceptance item; the backoff redesign (header-honoring vs fixed sleep, which workers) is a follow-up ticket with the worker files properly in scope
acceptance:
  - "OpenAiCompatibleProvider serves all six ModelTasks: a named test exercises generate() for TAGGER, ENTITY, SUMMARIZER, CHAT_AGENT, and TRANSLATOR against a fake endpoint and asserts no UnsupportedOperationException (today configFor throws UOE for 5/6 tasks — 'M1-033 wires SECURITY_JUDGE only' — while live call sites exist for all six and the workers' RuntimeException catch turns every call into a silent permanent fallback)"
  - "The per-task config keys already shipped in collector application.properties (infochat.llm.tagger.*, infochat.llm.entity.*) actually drive the calls: a named test points a task's base-url at a fake endpoint via config and asserts the call lands there"
  - "%remote-llm declares model keys for chat and summarizer: a named test resolves AnthropicProvider's per-task config for CHAT_AGENT and SUMMARIZER under remote-llm-shaped properties and asserts it succeeds (today no llm.chat.model / llm.summarizer.model key exists anywhere while AnthropicProvider requires prefix+'model' via getValue — first remote-llm chat or summary call dies on NoSuchElementException)"
  - "No dead Retry-After machinery remains: either a consumer sleeps on retryAfterMs (with the parsed value clamped to a sane ceiling) or the machinery is deleted — the unclamped parse (3-year sleep / overflow on a hostile header) is gone either way, and a named test pins the surviving behavior"
  - "The local-only guard snapshots infochat.llm.default.provider: a named test sets local-only=true with default.provider=anthropic and asserts startup fails with the same conflict error a per-task remote provider produces (today the guard snapshot covers per-task and embedding keys only)"
  - "Missing per-task config fails startup, not call time (redteam F1, INJECTION/medium): LlmProvider gains a default no-op assertTaskConfigResolvable(ModelTask); OpenAiCompatibleProvider and AnthropicProvider override it to run their private configFor for the task; LlmRouter.assertAllTasksResolve() invokes it on each task's resolved provider. A named test asserts that with a task's model key absent the startup scan throws (today that deployment boots cleanly and every Stage 2 call silently degrades to INFRA_FAILURE release), and a named test asserts a provider that does not override the default still passes the scan (test-stub compatibility)"
  - "Both services' default profiles declare the per-task blocks the eager scan needs for every task: collector application.properties adds summarizer/chat/translator base-url+model, provider application.properties adds tagger/entity/chat/summarizer/translator base-url+model (local-Ollama posture, llama3.1:8b per docs/design/05-llm-and-embeddings.md §5.1) — with the eager scan active, no shipped profile in either service can boot with a task whose keys don't resolve, which mvn verify proves through the modules' @QuarkusTest boots"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Routing
decision_refs: []
reviews:
  - round: 2
    date: 2026-06-07
    verdict: OVERRIDE-APPROVE
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 679
      removed: 187
    override_ref: 0
  - round: 2
    date: 2026-06-07
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 679
      removed: 187
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 363
      removed: 177
overrides:
  - date: 2026-06-07
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — must-shrink fails mechanically: round 2
      grew along ALL THREE dimensions vs round 1 (files 17 > 13, added
      679 > 363, removed 187 > 177). The rule's only exception requires
      a round-1 REWORK citation, but round 1 was APPROVE.
    user_justification: |
      The round-2 growth implements the redteam-finding refine recorded
      in escalations[]/revisions[] (F1 fix, files_budget 11 -> 14,
      acceptance items 6-7) — must-shrink's REWORK-citation exception
      cannot apply because round 1 was APPROVE, making the mechanical
      rule unsatisfiable rather than the diff divergent. Established
      resolution for redteam-refine growth on an approved ticket
      (M1-131 precedent). All other checks PASS.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-07
    category: INJECTION
    severity: medium
    promise: |
      "Collector ingest → user-visible store. No post becomes user-visible
      without passing the layered ingest checks (§ Ingest pipeline)." and
      "Stage 1 is a coarse filter, not a complete defense. ... Stage 2 is
      the actual security boundary."
    gap: |
      The diff replaces fail-at-boot config binding with lazy per-call
      resolution for SECURITY_JUDGE. Pre-diff, @ConfigProperty with no
      default refused startup on a missing/typoed key. Post-diff,
      OpenAiCompatibleProvider.configFor throws NoSuchElementException
      only at call time; LlmRouterStartupGuard validates provider names
      only, never per-task base-url/model key presence. Stage2Worker
      catches bare RuntimeException, so the config-missing exception is
      indistinguishable from a transient outage: both attempts fail
      instantly → INFRA_FAILURE → post released READY with Stage 1
      redactions only — permanently, where pre-diff the process would
      not boot.
    repro: |
      Deploy Collector with infochat.llm.security.model misspelled.
      Service boots cleanly (guard passes — provider names resolve).
      Adversary controlling any subscribed feed publishes posts crafted
      to slip the Stage 1 regex catalogue. Every Stage 2 invocation
      throws NoSuchElementException on both attempts → INFRA_FAILURE →
      post user-visible with only Stage 1 applied, indefinitely, for
      every post from every source. The judge never runs for the
      deployment's lifetime.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-06-07
    category: DOS
    severity: low
    promise: |
      "Schema-violating LLM output ... retry once, then apply the
      stage-specific failure path" — the single retry keeps transient
      provider failures from converting posts into the degraded-security
      release path.
    gap: |
      The diff deletes the entire Retry-After machinery (retryAfterMsFor/
      parseRetryAfterMs in LlmHttpSupport, retryAfterMs field/accessor in
      both exception types). No production consumer ever slept on the
      value (no regression), but the deletion forecloses the only path by
      which the spec-mandated single retry could succeed against a
      rate-limited (429/503) endpoint: Stage2Worker.invokeWithRetryOnce
      re-issues immediately, converting every rate-limit burst into
      INFRA_FAILURE → degraded release (Stage 1 only).
    repro: |
      Stage 2 endpoint returns 429 with Retry-After: 2 during a burst.
      Attempt 1 fails; immediate attempt 2 fails milliseconds later; the
      post takes the infra-failure path even though waiting 2 s would
      have produced a healthy verdict. An adversary correlating feed
      publishing with provider rate-limit windows gets content judged by
      Stage 1 alone more often than the retry-once design intends.
    suggested_fix_class: rate-limit
redteam_audits:
  - date: 2026-06-07
    verdict: FINDINGS
    base: 93d027de3586683d2f4686cdb34dc1abed047b85
    head: working tree of branch m1/M1-192-llm-per-task-config (pre-commit audit)
    verdict_file: docs/plan/m1/redteam/M1-192-2026-06-07.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Pre-commit audit after round-1 APPROVE. One medium (INJECTION:
      configFor's lazy per-call config resolution turns a typoed per-task
      key into a silent permanent Stage-2 bypass where the pre-diff
      @ConfigProperty binding refused boot — guard validates provider
      names, not key presence) and one low (DOS: Retry-After deletion
      makes the spec's retry-once near-certain to fail under provider
      rate-limiting). Two out-of-model advisories: local-only guard is
      point-in-time vs per-call dynamic reads (TOCTOU), and the new test
      harness binds the wildcard address (pre-existing pattern).
      Disposition: F1 fixed in-branch via redteam-finding refine
      (acceptance items 6-7, round-2 OVERRIDE-APPROVE); F2 deferred to
      a follow-up ticket; out-of-model items advisory.
revisions:
  - date: 2026-06-07
    reason: redteam-finding refine (F1 fix folded in, F2 deferred to follow-up)
    snapshot:
      status: escalated
      files_budget: 11
      files_scope:
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
        - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
        - infochat-provider/src/main/resources/application.properties
        - infochat-collector/src/main/resources/application.properties
      acceptance_items: 6
      out_of_scope_items: 4
  - date: 2026-06-07
    reason: clarity-fail rework
    snapshot:
      status: escalated
      files_budget: 8
      security_relevant: false
      clarity_check:
        date: 2026-06-07
        verdict: FAIL
        blockers:
          - "TEST-CHANGES-AUTHORIZED: test_plan.modifies declares pre-existing tests in infochat-llm-adapter/src/test/java/app/zcat/infochat/llm will be modified, but the ticket body has no 'Authorized test changes' section naming the specific pre-existing test classes/methods and the new expected behavior for each."
        warnings:
          - "FILES-BUDGET-PLAUSIBLE: files_budget of 8 is tight for 5 acceptance items each requiring a named test, plus 5 production files."
          - "SECURITY-FLAG-CONSISTENT: L5 (local-only guard gap) is a data-leakage surface characterized as a privacy commitment in the spec; security_relevant: false may be under-claimed."
escalations:
  - date: 2026-06-07
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — must-shrink fails mechanically: round 2
      grew along ALL THREE dimensions vs round 1 (files 17 > 13, added
      679 > 363, removed 187 > 177). The rule's only exception requires
      a round-1 REWORK citation, but round 1 was APPROVE. [...] The
      growth is exactly the F1 fix plus the lifecycle byproducts the
      audit itself mandates. [...] Shrinking is impossible without
      abandoning acceptance items 6-7; abandoning them violates
      ACCEPTANCE-CHECK. This is a ticket-vs-canonical-rules conflict.
      Resolution options: (a) user override of the must-shrink failure
      (the established resolution for redteam-refine growth on an
      approved ticket); or (b) revert to the round-1 APPROVED diff,
      deferring F1 to a follow-up ticket. Only the user can choose.
      Every other check PASS (test-integrity, out-of-scope,
      negative-space, acceptance items 1-8, spec-conformance).
  - date: 2026-06-07
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS (2026-06-07 pre-commit audit,
      docs/plan/m1/redteam/M1-192-2026-06-07.md)
      - INJECTION / medium: configFor's lazy per-call config resolution
        turns a missing/typoed per-task base-url/model key into a silent
        permanent Stage-2 bypass (NoSuchElementException at call time →
        INFRA_FAILURE → READY with Stage 1 only) where the pre-diff
        @ConfigProperty binding refused boot. LlmRouterStartupGuard
        validates provider names only, never per-task key presence.
        SUGGESTED-FIX-CLASS: trust-boundary-tightening
      - DOS / low: Retry-After machinery deletion forecloses the only
        path by which the spec-mandated retry-once could succeed against
        a 429/503 rate-limited endpoint; every rate-limit burst becomes
        INFRA_FAILURE → degraded release.
        SUGGESTED-FIX-CLASS: rate-limit
  - date: 2026-06-07
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED (FAIL): test_plan.modifies declares that
      pre-existing tests in infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
      will be modified, but the ticket body has no "Authorized test changes"
      section and names no specific pre-existing test class, method, or
      assertion being changed. Add an "Authorized test changes" subsection to
      the body listing each pre-existing test file/class that will be touched
      and the new expected behavior for each.
---

# M1-192: LLM per-task config completion (configFor, remote-llm, guard)

## Context

Four config-completion gaps in the LLM adapter (unified findings L1, L2,
L4, L5 — `deep-code-review/v2/UNIFIED.md` §2):

1. **configFor UOE (L1, high).** OpenAiCompatibleProvider.configFor
   (OpenAiCompatibleProvider.java:157-165) throws
   UnsupportedOperationException for TAGGER/ENTITY/SUMMARIZER/CHAT_AGENT/
   TRANSLATOR. Production call sites exist for all six tasks (TaggerWorker,
   EntityExtractorWorker, SummaryProseGenerator, ChatAgent,
   LlmTranslationProvider), the corresponding config keys ship in collector
   application.properties (:339-356) and are read by nothing, and the
   workers catch RuntimeException — so on any deployment routing these
   tasks to the openai-compatible provider (the local/Ollama default), the
   eval pipeline silently degrades to permanent fallback.
2. **%remote-llm missing model keys (L2, high).** The remote-llm profile
   declares chat/summarizer provider, base-url, and max-tokens but no
   `.model`; AnthropicProvider.configFor requires `prefix+"model"`
   (AnthropicProvider.java:108, getValue, no default) — first call fails.
3. **Dead Retry-After machinery (L4).** `retryAfterMs` has ~20 references
   inside the adapter and zero consumers outside; the parse is unclamped.
4. **Local-only guard gap (L5, low-sec).** The startup guard snapshots
   per-task and embedding provider keys but never
   `infochat.llm.default.provider` — `default.provider=anthropic` passes a
   local-only deployment.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Authorized test changes

Pre-existing tests that this ticket is authorized to modify, and the new
expected behavior for each (everything not listed here is preserved
verbatim):

- `OpenAiCompatibleEmbeddingProviderTest` — the Retry-After test
  (503 + `Retry-After: 3` → `retryAfterMs() == 3000`): per the acceptance
  item 4 outcome, either rewritten to assert the clamped/consumed behavior
  or deleted together with the machinery.
- `AnthropicProviderTest` — the Retry-After test (429 + `Retry-After: 2` →
  `retryAfterMs() == 2000`): same two outcomes as above.
- `AnthropicProviderTest` — additive only beyond the Retry-After test: new
  methods for the remote-llm CHAT_AGENT/SUMMARIZER config resolution
  (acceptance item 3) may join the class; no other existing assertion
  changes.
- `LlmRouterStartupGuardLocalOnlyTest` — additive only: a new test for the
  `infochat.llm.default.provider` snapshot key (acceptance item 5); all
  existing tests preserved (they pass hand-rolled snapshot maps, and an
  absent key means no conflict).
- `LlmRouterTest` — additive only (redteam F1 refine): new test(s) for
  `assertAllTasksResolve()` invoking `assertTaskConfigResolvable` on each
  task's resolved provider (missing-key throw + default-no-op pass); all
  existing tests preserved.

## Notes

- files_budget 14 accounting: `retryAfterMs` lives in four impl classes
  (LlmHttpSupport, OpenAiCompatibleProvider, AnthropicProvider,
  OpenAiCompatibleEmbeddingProvider) + guard + two application.properties
  = 7 production files; plus one new test class
  (OpenAiCompatibleProviderTest) and the three modified test classes
  listed under §Authorized test changes; plus three redteam-F1-refine
  touches: LlmProvider.java (SPI default method), LlmRouter.java (eager
  invocation in assertAllTasksResolve), LlmRouterTest.java (additive).
- Redteam F1 fix shape (2026-06-07 refine): SPI eager resolution chosen
  over a guard-side presence check because the provider's own configFor
  is the single source of truth for required keys (no duplication, no
  drift, catches wrong-typed values) and over @ConfigMapping because the
  dynamic-read pattern was already chosen and APPROVED this ticket. The
  default no-op is load-bearing: test stub providers (e.g.
  Stage2WorkerIT.TestStubLlmProvider) don't override it, so module ITs
  keep booting. Audit record:
  docs/plan/m1/redteam/M1-192-2026-06-07.md.
- security_relevant: true because the local-only guard (L5) is the
  enforcement point of the "post bodies must not leave the host" privacy
  commitment; the audit's "low-sec" label describes severity, not surface.
- Source: `UNIFIED.md` §3 T16 under `deep-code-review/v2/` (opus-48 llm
  F1/F3/F8, gpt R1/CQ2).
- AnthropicProvider's dynamic per-call MicroProfile config read is the
  in-repo pattern for the configFor fix (suggested by opus-48; Tier B —
  note opus-47 flagged per-call config lookups as a perf nit, so a cached
  variant also satisfies the acceptance; pick one and say why).
- Picking the %remote-llm default chat/summarizer model values is an
  operator-visible choice — keep them consistent with the documented
  remote-llm posture in docs/design/05 and flag the chosen models in the
  commit message.
