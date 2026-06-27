---
id: M1-498
title: "Test fidelity & coverage gaps: copied lambdas, wrong arms, untested paths"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
