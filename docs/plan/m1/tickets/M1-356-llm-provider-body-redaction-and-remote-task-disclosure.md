---
id: M1-356
title: "llm-adapter: stop echoing provider body on 2xx parse-failure; add the remote LLM-task startup disclosure WARN"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 7
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The host-only redaction posture on the non-2xx path (U-13) — unchanged; this brings the 2xx-parse-failure path into line with it.
  - The local-only conflict (fatal) scan — unchanged; this ticket consumes the same per-task off-host detection in the non-fatal disclosure branch.
  - The §5.9 observability metrics catalogue ("scheduled, not yet built") — unrelated.
acceptance:
  - "On a 2xx response whose JSON is unparseable / missing choices[] / missing content, OpenAiCompatibleProvider and OpenAiCompatibleEmbeddingProvider no longer append LlmHttpSupport.preview(responseBody) to the LlmCallFailedException; the message names the provider label, host, and the specific shape failure but not the body. The preview helper is either removed (no remaining caller) or gated behind an explicit operator-controlled DEBUG diagnostic, not on by default."
  - "BaseUrlCredentialRedactionTest (or a sibling) is extended to assert the 2xx-parse-failure exception message contains no provider body bytes, closing the gap the path-only assertion left open."
  - "LlmRouterStartupGuard's non-local-only branch emits one WARN per remote LLM-task route (summarizer/tagger/entity whose base-url is off-host or whose provider is a cloud provider), reusing the same per-task off-host detection the local-only branch already computes (factored into a shared helper), matching the existing remote-embedding WARN and design §5.10."
  - "A startup-guard test pins that a remote summarizer (or remote default provider) produces the per-task disclosure WARN while an all-local config produces none."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl (no-body-in-2xx-failure assertion)
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing (per-task disclosure WARN assertion)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 198
      removed: 70
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: be64c431
    head: working-tree (uncommitted M1-356 changes)
    verdict_file: docs/plan/m1/redteam/M1-356-2026-06-14.md
    out_of_model_count: 2
    note: |
      Run between review APPROVE (round 1) and commit, on the uncommitted
      working tree (the branch carries no commit yet). CLEAN — no findings.
      Two OUT-OF-MODEL advisory observations recorded in the verdict file;
      no remediation ticket required.
---

# M1-356: provider-body redaction + remote LLM-task disclosure

## Context

Two deep-review v6 findings on `infochat-llm-adapter`, grouped (both are
"post/prompt content or its host posture leaving the module"):

- **opus-47 `04-module-infochat-llm-adapter.md` F1** (medium, SECURITY) — the
  2xx-but-malformed path echoes up to 200 chars of provider body via
  `LlmHttpSupport.preview`, asymmetric with the non-2xx host-only posture.
  **Verified 2026-06-14:** `preview(responseBody)` is used at
  `OpenAiCompatibleProvider.java:214,220` and
  `OpenAiCompatibleEmbeddingProvider.java:200,208,223`; the only definition is
  `LlmHttpSupport.java:261`. A hostile/buggy endpoint can return 2xx + JSON whose
  textual fields reflect prompt/user content, landing it in
  `LlmCallFailedException.getMessage()`.
- **opus-48 `04-module-infochat-llm-adapter.md` F1** (medium,
  MAINTAINABILITY-RULES-DRIFT) — remote *embedding* gets a startup disclosure
  WARN but remote *LLM-task* providers (which send full post bodies) get none.
  **Verified 2026-06-14:** `LlmRouterStartupGuard.java:196-208` — the `!localOnly`
  branch logs only the `embeddingRemote` WARN and returns; the per-task
  `offenders` scan runs only in the fatal local-only branch. design §5.10
  mandates the per-task disclosure line.

The reports come from different runs but name the same module's privacy posture;
fixing both in one diff keeps the "what counts as remote" detection single-source.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The shape-failure message ("missing choices[]") still tells the operator what
  was wrong; the raw body is recoverable via packet capture / provider logs.
- If the team prefers to drop §5.10 rather than implement it, the honest
  alternative is deleting that design paragraph — but the embedding half already
  ships, so the asymmetry is the smell.
