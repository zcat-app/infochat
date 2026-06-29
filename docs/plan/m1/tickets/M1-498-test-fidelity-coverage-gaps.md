---
id: M1-498
title: "Test fidelity & coverage gaps: copied lambdas, wrong arms, untested paths"
status: done
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 9
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Changing the production code under test; this ticket makes tests exercise the real production path and fills the named coverage gaps."
acceptance:
  - >-
    (22#F1) the Kind-6 integration tests exercise the actual production deliver
    lambda rather than a copy of it (Kind6LinkingIT.java:103-117,
    Kind6RepostResolutionIT.java:148-165 currently re-implement the dispatch);
    the production lambda in NostrStreamSource is the code under test.
  - >-
    (21#F1) FetchSchedulerClockIT and FetchSchedulerHostPacingIT no longer reach
    private FetchScheduler fields by reflection. NOTE (falsification correction):
    no package-private seam exists today — only `clock` is package-private; the
    lastTickByKind / hostNextAllowed / pendingByKind / fetchersByKind fields are
    private. So the fix is EITHER add narrow package-private test seams on
    FetchScheduler and drive through them, OR document the reflection as the
    accepted test seam. (FetchSchedulerClockIT.java:67-110,
    FetchSchedulerHostPacingIT.java:94-219.)
  - >-
    (23#F1) the live peer-IP-watcher test covers the spec's connection-migration
    arm (allowed-but-changed IP / set-intersection), not only the redundant
    blocklist arm (NostrSsrfIT.java:86-141).
  - >-
    (26#F1) OpenAiCompatibleProvider's token-usage/model response parse is tested:
    a canned reply WITH usage + model is asserted to populate response.usage() and
    response.model() (OpenAiCompatibleProviderTest.java:53-96 currently asserts
    only request-side routing and text).
  - >-
    (36#F1) the public resolveForWebSocket has direct in-module test coverage
    (SsrfGuardedHttpClient.java:908) — at least one test calls it and asserts its
    contract.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/ResolveForWebSocketTest.java"
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6LinkingIT.java — invoke the production deliver lambda."
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6RepostResolutionIT.java — invoke the production deliver lambda."
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/FetchSchedulerClockIT.java — drop field reflection (via a new seam or documented)."
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/FetchSchedulerHostPacingIT.java — drop field reflection (via a new seam or documented)."
    - "infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/FetchScheduler.java — IF the seam route is chosen, add narrow package-private accessors (production file; otherwise untouched)."
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java — cover the connection-migration arm."
    - "infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderTest.java — assert usage()/model() parse."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 265
      removed: 167
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: fd566507 (branch fork point)
    head: working tree (uncommitted, APPROVED r1)
    verdict_file: docs/plan/m1/redteam/M1-498-2026-06-29.md
    out_of_model_count: 0
    note: |
      Advisory audit run to honor the clarity SECURITY-FLAG-CONSISTENT WARN
      despite security_relevant:false. Diff is test-only plus two behavior-
      preserving package-private test seams; no new production behavior or
      attack surface. CLEAN — nothing feeds a future ticket.
clarity_check:
  date: 2026-06-29
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 2: criterion says 'no longer reach private fields by reflection' but the Note's path B keeps reflection with documentation; the two halves contradict. Resolved by taking path A (the seam) so the literal criterion is met."
    - "ACCEPTANCE-RUNNABLE item 5: 'asserts its contract' for resolveForWebSocket is unspecified; pinned concretely as allowed-host→returns validated set, blocked-host→BLOCKED_IP, non-ws scheme→SCHEME_NOT_ALLOWED."
    - "OUT-OF-SCOPE-SPECIFIC: 'no production code change' conflicts with test_plan including FetchScheduler.java; the seam-only package-private accessors are behavior-preserving and permitted (same carve-out the ticket already lists)."
    - "SECURITY-FLAG-CONSISTENT: security_relevant:false but the diff adds SSRF-guard test coverage; will run /redteam --in-progress to honor the WARN rather than flip the flag."
  blockers: []
---

# M1-498: Test fidelity & coverage gaps

## Context

From `/deep-code-review full` (2026-06-27), reports `21#F1`, `22#F1`, `23#F1`,
`26#F1`, `36#F1` (verified at source) — tests that pass without exercising the
real production path, cover the wrong arm of a defense, or leave a public
contract untested. A regression in the production lambda, the connection-
migration SSRF arm, the OpenAI usage/model parse, or `resolveForWebSocket` would
ship green today.

## Acceptance

See frontmatter — make each test bind to the real production code and fill the
two named coverage gaps.

## Out-of-scope

See frontmatter. No production-code change; existing tests modified deliberately
(authorized per engineering-rules §8).

## Notes

- Source: `/deep-code-review full` (2026-06-27), reports 21#F1, 22#F1, 23#F1,
  26#F1, 36#F1.
- Falsification correction: the report claimed a package-private seam "already
  exists" for the reflected fields — it does NOT (those existing package-private
  methods cover different concerns). Adding a seam is real production work, hence
  the conditional FetchScheduler.java entry in files_scope.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-498-*.md
```
