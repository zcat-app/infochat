---
id: M1-275
title: "DB hygiene: SET LOCAL timeouts, per-dispatch conn reuse"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/CancellationService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportDataCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - Holding one connection across the LLM call — explicitly forbidden by the report ("would recreate H3's defect"); the dispatch context splits at the LLM boundary.
  - DigestPostCollector timeout (M1-263 owns the digest slice).
  - The dispatch pipeline's permission/ban/confirm logic — only how many connections it borrows changes, not what it decides.
  - Connection-pool sizing configuration.
acceptance:
  - "CancellationService applies statement_timeout transaction-locally (SET LOCAL inside a tx, or an unconditional reset on release): a named test asserts a connection borrowed after a timeout-bearing call observes the pool's default statement_timeout."
  - "The timeout value reaching the SET statement is a validated integer (no raw string concatenation of caller input), and the pg_cancel_backend return value is checked and logged on the cancellation path."
  - "Group inbound dispatch resolves user, groupId, membership, confirm-state, and session persistence through a shared per-dispatch context instead of 4–5 independent pool acquisitions: the pre-LLM read phase borrows one connection, and the context carries groupId forward instead of re-looking it up. A named test (counting datasource/pool spy) asserts the acquisition count for a group dispatch drops to the designed number and never spans the LLM call."
  - "ExportDataCollector queries run under the standard statement timeout."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
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

# M1-275: DB hygiene: SET LOCAL timeouts, per-dispatch conn reuse

## Context

Deep-review v4 verified mediums **M-P3**, **M-P4**, **M-P13** (export slice)
plus two deepseek lows on the same surface
(`deep-code-review/v4/UNIFIED-REPORT.md` §2/§3; sources
`deep-code-review/v4/fable5/07-module-infochat-provider.md#F8`,
`deep-code-review/v4/deepseek/report.md` #F2/#F4/#F6,
`deep-code-review/v4/mimo/report.md` HIGH-001/MED-013,
`deep-code-review/v4/opus-47/07-module-infochat-provider.md#F4`,
`deep-code-review/v4/gpt-55/report.md` L-04):

- **M-P3:** `CancellationService.applyStatementTimeout` runs a session-level
  `SET` (not `SET LOCAL`, never reset) on pool connections — subsequent
  borrowers inherit the timeout.
- **M-P4** (4 independent runs): each group inbound dispatch opens 4–5
  separate pool connections (`lookupUser`, `lookupGroupId`,
  `ensureGroupMembership`, confirm-state, session persist). gpt-55's
  "carry groupId forward in a dispatch context" suggestion is folded in.
- **M-P13/L:** `ExportDataCollector` lacks statement-timeout hygiene; the
  `SET statement_timeout` string-concat precedent and the ignored
  `pg_cancel_backend` return ride along (same files).

## Acceptance

See frontmatter. The hard boundary is in out_of_scope: the dispatch context
must split at the LLM boundary — one connection for the pre-LLM read phase,
nothing held during the LLM call, fresh borrow after.

## Out-of-scope

See frontmatter.

## Notes

- `risk: high` (hence round_cap 3 and the commit-time verify re-run): this
  refactors the provider's hottest path. The acceptance deliberately pins
  observable properties (acquisition count, no-span-LLM, identical dispatch
  decisions via existing green tests) rather than a specific context shape.
- For the acquisition-count test, a counting DataSource wrapper in
  testsupport is the established pattern to check for before inventing one
  (look in infochat-provider/src/test/.../testsupport).
- PostgreSQL does not allow bind parameters in SET; "validated integer"
  means `Integer.parseInt`/range-check then format — not a prepared
  statement.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-275-*.md
```
