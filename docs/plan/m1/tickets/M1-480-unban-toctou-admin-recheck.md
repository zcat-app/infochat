---
id: M1-480
title: "/unban skips the in-transaction admin re-check its siblings perform"
status: done
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 3
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "Any change to the sibling admin handlers (Ban/Vouch/Grant/Revoke/Promote/Demote/Approve/Reject) — they already close the TOCTOU; this ticket brings /unban into line with them only."
acceptance:
  - >-
    UnbanCommandHandler re-reads the caller's is_admin inside the mutating
    transaction via the same locking read (findByAdapterAndContactIdForUpdate)
    its sibling admin handlers use, so a contact demoted concurrently with their
    in-flight /unban cannot complete the admin-only action. Both the unban
    execution transaction (UnbanCommandHandler.java:211-226) and the preban leg
    (:196) gate on the in-transaction re-check, not the dispatch-time
    non-locking lookup (:159).
  - >-
    A new test drives a concurrent-demote race against /unban and asserts the
    just-demoted caller's /unban is rejected (mirroring the existing /ban
    TOCTOU-closure test).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnbanCommandHandlerToctouIT.java"
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
      files: 4
      added: 243
      removed: 16
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: origin/main (4edfef05)
    head: working-tree (m1/M1-480 branch)
    verdict_file: docs/plan/m1/redteam/M1-480-2026-06-29.md
    out_of_model_count: 1
    note: |
      Adversarial audit of the in-transaction admin re-gate (both /unban legs)
      ran CLEAN — no findings against the threat model. One out-of-model
      advisory: the preban-vs-non-preban branch reads the target row
      non-locking (only the actor row is locked FOR UPDATE), but preban has no
      legal exit transition in v1 so the branch cannot be raced into the wrong
      leg; pre-existing, not adversary-reachable, advisory only. No lifecycle
      action for this ticket.
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-480: /unban skips the in-transaction admin re-check its siblings perform

## Context

From `/deep-code-review full` (2026-06-27), report
`13-main-infochat-provider-03.md#F1` (rated **medium SECURITY**, verified at
source). Every sibling admin handler — Ban, Vouch, Grant, Revoke (plus
Promote/Demote/Approve/Reject) — re-gates `is_admin` inside its mutating
transaction with a `SELECT ... FOR UPDATE`
(`findByAdapterAndContactIdForUpdate`, carrying verbatim "M1-046 TOCTOU-closure"
comments). `UnbanCommandHandler` is the lone admin handler absent from that set:
its Step-5 transaction (`:211-226`) and the preban leg (`:196`) commit on the
dispatch-time non-locking read (`:159`). A demote racing an in-flight `/unban`
therefore lets a just-demoted contact complete an admin-only action — the exact
race `/ban` closes.

## Acceptance

See frontmatter. Add the in-transaction locking admin re-check to both `/unban`
legs and prove it with a concurrent-demote race test.

## Out-of-scope

See frontmatter. The sibling handlers already do this correctly and stay
untouched.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 13#F1.
- `/ban`'s `findByAdapterAndContactIdForUpdate` usage is the in-repo reference
  shape to copy.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-480-*.md
```
