---
id: M1-193
title: "/stop wiring: pg backend pid + statement timeouts + tool conns"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
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
acceptance:
  - "Per docs/spec/commands.md §Conversation control — \"the cancellation primitive is `pg_cancel_backend(pid)` at the released connection\" — an in-flight chat tool query is actually cancellable: a named IT starts a slow tool query, issues /stop, and asserts the backend query aborts (today InFlightTracker.registerPgBackendPid has a definition and zero callers — the safety net exists but is never armed)"
  - "Per docs/spec/commands.md §Conversation control — \"As an additional safety net, every interruptible read-only query (chat-mode tool calls, on-demand `/summary`) runs under a profile-driven `statement_timeout` that bounds the worst case even when `pg_cancel_backend` fails.\" — named tests assert the statement_timeout GUC is applied on chat-tool connections and on EligiblePostQuery's /summary connection (today CancellationService.applyStatementTimeout's only caller is RetryCommandHandler:299)"
  - "SearchPostsTool acquires at most one pooled connection per tool call: a named test counts acquisitions through an instrumented DataSource (today it opens four per call — isKnownTag, readTagMode, readScopeTags, queryPosts — against a pool of 16 with 2 pinned, so a handful of concurrent chat turns can starve the pool)"
  - "A /stop issued when nothing is in flight, and a /stop racing normal completion, both keep their current friendly no-op semantics — existing /stop tests stay green"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D35
reviews: []
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

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. M1-183 owns admission control on the same handlers;
coordinate, don't serialize.

## Notes

- Source: `UNIFIED.md` §3 T17 under `deep-code-review/v2/` (opus-48 prov
  F1/F3, kimi-folder prov F5).
- The single-connection SearchPostsTool change is also the natural place to
  register the backend pid once per tool call — one connection means one
  pid to track.
- statement_timeout values are profile-driven per the spec sentence; the
  property shape is the implementer's choice (CancellationService already
  reads one for /retry).
