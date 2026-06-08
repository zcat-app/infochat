---
id: M1-225
title: "Arm /stop timeout + pid on the four non-search chat tools"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/ListSavesToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPostToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetReferencesToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/RecallMemoryToolTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - SearchPostsTool + SearchPostsToolTest — already armed and consolidated by M1-193; do not re-touch
  - CancellationService.armToolConnection — already correct (M1-193); this ticket only adds callers, no signature change
  - EligiblePostQuery / the /summary statement_timeout — M1-193's; this ticket is chat-tool-only
  - the /stop slot-release race and misleading fallback replies (audit P22, accepted-low tier)
  - any pre-existing test other than the three authorized tool tests (GetPostToolTest, GetReferencesToolTest, RecallMemoryToolTest)
acceptance:
  - "Per docs/spec/commands.md §Conversation control — \"In v1 every tool in the closed allowlist (… `searchPosts`, `getPost`, `getReferences`, `recallMemory`, `listSaves`) is a read-only DB query, so the cancellation primitive is `pg_cancel_backend(pid)` at the released connection\" — getPost, getReferences, recallMemory and listSaves each arm their pooled connection (register the backend pid on the in-flight handle) so an in-flight call to any of them is cancellable by /stop; a named test per tool asserts the pg_backend_pid is registered on the in-flight handle (today only searchPosts arms, via M1-193's CancellationService.armToolConnection)"
  - "Per docs/spec/commands.md §Conversation control — \"As an additional safety net, every interruptible read-only query (chat-mode tool calls, on-demand `/summary`) runs under a profile-driven `statement_timeout` that bounds the worst case even when `pg_cancel_backend` fails.\" — a named test per tool asserts the statement_timeout GUC is applied (SET statement_timeout ran) on each of getPost / getReferences / recallMemory / listSaves's connection"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/ListSavesToolTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPostToolTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetReferencesToolTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/RecallMemoryToolTest.java
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

# M1-225: Arm /stop timeout + pid on the four non-search chat tools

## Context

M1-193 armed the `/stop` safety net for `searchPosts` only: it added
`CancellationService.armToolConnection(conn, userId, scopeKind, scopeId)`
(applies the profile-driven `statement_timeout` and registers the
connection's `pg_backend_pid()` on the in-flight handle) and wired
`SearchPostsTool` to call it. The other four read-only tools in the closed
allowlist — `getPost`, `getReferences`, `recallMemory`, `listSaves` — were
deliberately scoped out (M1-193 §Out-of-scope, Q1 follow-up).

Per `docs/spec/commands.md` §Conversation control, the spec names **all
five** allowlist tools as read-only DB queries whose cancellation primitive
is `pg_cancel_backend(pid)`, and promises "every interruptible read-only
query (chat-mode tool calls, …) runs under a profile-driven
`statement_timeout`". Until those four tools arm their connections, an
in-flight `getPost` / `getReferences` / `recallMemory` / `listSaves` query
is neither cancellable by `/stop` nor bounded by `statement_timeout` — a
gap the spec does not allow. This ticket closes it by extending M1-193's
existing seam to the remaining four tools.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. The arming seam (`CancellationService.armToolConnection`),
`SearchPostsTool`, and the `/summary` leg are all M1-193's and are done —
this ticket only adds the four missing callers and their tests.

## Notes

- Each of the four tools already opens **exactly one** pooled connection in
  `execute(...)` (verified: one `dataSource.getConnection()` per tool), so —
  unlike `SearchPostsTool` — no connection consolidation is needed. The
  change per tool is mechanical: inject `CancellationService`, and call
  `cancellationService.armToolConnection(conn, userId, scopeKind, scopeId)`
  at the top of the existing try-with-resources, before the tool's query.
  Each tool's `execute(userId, scopeKind, scopeId, args)` already receives
  the scope key, so the handle lookup keys on the same `(user, scope)` the
  chat turn holds (per-(user,scope) isolation — never a global slot).
- Adjacent precedent to match: `SearchPostsTool` (the armed call site) and
  `SearchPostsToolTest`'s `CountingRecordingDataSource` (a delegating
  recorder that observes the `SET statement_timeout` SQL and connection
  acquisitions). It is an inner class of `SearchPostsToolTest` today; the
  implementer may extract it to a top-level package-private helper in
  `provider/chat/tool` (test) to share across the four tool tests rather than
  duplicating the proxy boilerplate four times.
- `ListSavesToolTest` does not exist yet (the other three tool tests do), so
  the listSaves coverage is a net-new test file (test_plan.adds); the other
  three are additive assertions on existing tests (test_plan.modifies).
- The tool constructors gain a `CancellationService` param; production
  wiring is automatic (CDI constructs them, `ChatToolDispatcher` injects the
  beans). Sweep for any non-CDI construction site before finalizing, as
  M1-193 did for `SearchPostsTool` (grep found only the CDI path + the one
  direct test).
- `security_relevant: false` for the same reason as M1-193: the timeout and
  pid arming are operational-resilience (resource-exhaustion) mechanisms,
  not an auth/ban/admin boundary.
