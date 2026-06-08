---
id: M1-193
title: "/stop wiring: pg backend pid + statement timeouts + tool conns"
status: done
created: 2026-06-07
last_updated: 2026-06-08
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-193.md
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - admission control for /summary and /retry (rate bucket + single-slot) — M1-183's; both tickets touch SummaryCommandHandler, so coordinate rather than serialize
  - RetryCommandHandler's existing applyStatementTimeout call — already correct; the gap is everywhere else
  - the /stop slot-release race and misleading fallback replies (audit P22, accepted-low tier)
  - EligiblePostQuery's missing SQL LIMIT — M1-194's; this ticket only adds the timeout/cancellation guard to its connection
  - modifying any pre-existing test other than the two authorized under §Authorized test changes (SearchPostsToolTest, CancellationServiceTest); StopCommandHandlerTest and SummaryCommandHandlerTest specifically stay green unmodified (the /summary statement_timeout lives inside EligiblePostQuery — Option A — covered by a net-new provider/summary test, so no handler wiring change)
acceptance:
  - "Per docs/spec/commands.md §Conversation control — \"the cancellation primitive is `pg_cancel_backend(pid)` at the released connection\" — an in-flight chat tool query is actually cancellable: a named IT starts a slow tool query, issues /stop, and asserts the backend query aborts (today InFlightTracker.registerPgBackendPid has a definition and zero callers — the safety net exists but is never armed)"
  - "Per docs/spec/commands.md §Conversation control — \"As an additional safety net, every interruptible read-only query (chat-mode tool calls, on-demand `/summary`) runs under a profile-driven `statement_timeout` that bounds the worst case even when `pg_cancel_backend` fails.\" — named tests assert the statement_timeout GUC is applied on chat-tool connections and on EligiblePostQuery's /summary connection (today CancellationService.applyStatementTimeout's only caller is RetryCommandHandler:299)"
  - "SearchPostsTool acquires at most one pooled connection per tool call: a named test counts acquisitions through an instrumented DataSource (today it opens four per call — isKnownTag, readTagMode, readScopeTags, queryPosts — against a pool of 16 with 2 pinned, so a handful of concurrent chat turns can starve the pool)"
  - "A /stop issued when nothing is in flight, and a /stop racing normal completion, both keep their current friendly no-op semantics — existing /stop tests stay green"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/CancellationServiceTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D35
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 636
      removed: 53
escalations:
  - date: 2026-06-08
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL — test_plan.modifies lists two test dirs
      (provider/chat, provider/command) indicating pre-existing tests will be
      structurally modified, but the ticket body has no "Authorized test
      changes" section enumerating each modified test class, the reason
      (e.g. SearchPostsTool constructor/API change, InFlightTracker wiring),
      and post-change expected behavior. Acceptance item 4 ("existing /stop
      tests stay green") is a no-behavior-change assertion, not an
      authorization of modifications.
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — developer-initiated before any implementation. Resolving
      acceptance item 2's /summary leg via Option A (apply statement_timeout
      inside EligiblePostQuery, where the connection lives, mirroring
      RetryCommandHandler:321) requires a test under provider/summary — a path
      outside the original files_scope. Escalated to widen scope via refine
      rather than silently expand files_scope.
revisions:
  - date: 2026-06-08
    reason: clarity-fail rework (TEST-CHANGES-AUTHORIZED blocker — test_plan.modifies named two test dirs with no §Authorized test changes section; warning — dangling UNIFIED.md §2 / §3-T17 cross-refs)
    snapshot:
      status: escalated
      escalation_reason: clarity-fail
      files_budget_at_snapshot: 10
      test_plan_modifies_at_snapshot:
        - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
      out_of_scope_at_snapshot:
        - admission control for /summary and /retry (rate bucket + single-slot) — M1-183's; both tickets touch SummaryCommandHandler, so coordinate rather than serialize
        - RetryCommandHandler's existing applyStatementTimeout call — already correct; the gap is everywhere else
        - the /stop slot-release race and misleading fallback replies (audit P22, accepted-low tier)
        - EligiblePostQuery's missing SQL LIMIT — M1-194's; this ticket only adds the timeout/cancellation guard to its connection
  - date: 2026-06-08
    reason: budget-breach refine (Option A for /summary statement_timeout — apply inside EligiblePostQuery; widen files_scope + test_plan to add provider/summary test dir; drop SummaryCommandHandlerTest from authorized modifies since the timeout no longer threads through the handler)
    snapshot:
      status: in-progress
      escalation_reason: budget-breach
      files_budget_at_snapshot: 10
      files_scope_summary_test_dir_added: infochat-provider/src/test/java/app/zcat/infochat/provider/summary
      test_plan_modifies_at_snapshot:
        - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/CancellationServiceTest.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-193: /stop wiring: pg backend pid + statement timeouts + tool conns

## Context

The /stop safety net is specified but unarmed (unified findings P2 + P14,
`deep-code-review/v2/UNIFIED.md` §2): `InFlightTracker.registerPgBackendPid`
(InFlightTracker.java:36) has zero callers, so /stop's
`pg_cancel_backend(pid)` primitive never has a pid to cancel;
`CancellationService.applyStatementTimeout` is called exactly once, in
RetryCommandHandler (:299) — chat-mode tool calls and the /summary
EligiblePostQuery run with no statement_timeout, so a pathological query is
bounded by nothing (thread interrupt() does not stop pgjdbc). Adjacent and
folded in: SearchPostsTool acquires four pooled connections per call
(:71, :83, :120, :156), multiplying pool pressure under the very load /stop
exists to relieve.

The concrete code locations named above (InFlightTracker.java:36,
RetryCommandHandler:299, SearchPostsTool :71/:83/:120/:156) are the
load-bearing facts; the `UNIFIED.md` §-references are provenance pointers,
not required reading to implement this ticket.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. M1-183 owns admission control on the same handlers;
coordinate, don't serialize.

## Authorized test changes

Arming the cancellation/timeout safety net and consolidating
`SearchPostsTool`'s connections touches two pre-existing test files.
Each is authorized here with its new expected behavior:

- **SearchPostsToolTest** (`provider/chat/tool`). NEW assertion (acceptance
  items 3 + 2 chat-tool leg + 1 pid-registration): through one
  counting/recording `DataSource` that delegates to the seed DB, assert
  `execute()` acquires exactly one pooled connection, that `SET
  statement_timeout` ran on it, and — with an in-flight slot held — that the
  tool registered the pg backend pid on the handle. `SearchPostsTool` gains a
  `CancellationService` constructor param (it arms the single connection), so
  the test constructs the tool directly with its instrumented `DataSource`
  plus a real `CancellationService` + `InFlightTracker` rather than relying on
  the CDI-injected `@SeedDataSource`. Existing search-result assertions are
  unchanged and stay green.
- **CancellationServiceTest** (`provider/chat`). ADDITIVE: this is today the
  only caller of `applyStatementTimeout` and `registerPgBackendPid`; once
  those are armed in the chat-tool / `/summary` paths, assertions are added
  covering the now-live behavior (statement_timeout GUC applied; pid
  registered on the handle). Existing `cancel()`, timeout-parse, and
  handle-lifecycle assertions are unchanged.

NOT modified (kept green per acceptance item 4 and the trust boundary):
**StopCommandHandlerTest** — the `/stop` no-op-when-nothing-in-flight and
race-with-completion assertions stay exactly as today;
`CancellationService.applyStatementTimeout(Connection)` and
`InFlightTracker.CancellationHandle.registerPgBackendPid(int)` keep their
current signatures (they gain callers, not new shapes), so no construction
change ripples into this test. **RetryCommandHandlerTest** (its
`applyStatementTimeout` call is already correct — out of scope) and
**InFlightTrackerTest** (handle API unchanged) are likewise untouched.
**SummaryCommandHandlerTest** is also untouched: under the Option-A seam the
`/summary` `statement_timeout` is applied inside `EligiblePostQuery`
(mirroring `RetryCommandHandler`), so no wiring change reaches the handler —
the GUC is asserted by a net-new `provider/summary` test instead.

The cancellation IT (acceptance item 1) and the chat-tool
`statement_timeout` assertion (acceptance item 2) are net-new files under
`provider/chat`; the `/summary` `statement_timeout` assertion (acceptance
item 2) is a net-new file under `provider/summary` (e.g.
`EligiblePostQueryStatementTimeoutTest`). All are test_plan.adds, so they
need no authorization listing.

## Notes

- Source: `UNIFIED.md` §3 T17 under `deep-code-review/v2/` (opus-48 prov
  F1/F3, kimi-folder prov F5).
- The single-connection SearchPostsTool change is also the natural place to
  register the backend pid once per tool call — one connection means one
  pid to track.
- statement_timeout values are profile-driven per the spec sentence; the
  property shape is the implementer's choice (CancellationService already
  reads one for /retry).
