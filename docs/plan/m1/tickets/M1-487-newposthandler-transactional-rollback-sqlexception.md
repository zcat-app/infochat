---
id: M1-487
title: "NewPostHandler @Transactional does not roll back on SQLException"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Adding the T1-F pre-cursor side-effect write that would make the gap live — only the rollback annotation is corrected."
acceptance:
  - >-
    NewPostHandler.handle's @Transactional declares rollbackOn=SQLException.class
    (it currently throws SQLException but omits rollbackOn, so Jakarta's default
    commit-on-checked-exception semantics would commit a partial transaction),
    matching the deliberate annotation on the sibling QuarantineReviewListener
    (QuarantineReviewListener.java:141). The documented atomicity invariant is
    then enforced by the annotation, not merely by today's single-write body.
  - >-
    A test asserts that a SQLException raised inside the handler's transaction
    rolls the transaction back (no partial commit).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostHandlerRollbackIT.java"
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

# M1-487: NewPostHandler @Transactional does not roll back on SQLException

## Context

From `/deep-code-review full` (2026-06-27), report
`15-main-infochat-provider-05.md#F1` (medium, latent — verified at source).
`NewPostHandler.handle` (`:97-98`) is `@Transactional` and `throws
SQLException`, but omits `rollbackOn=SQLException.class`. Under Jakarta's default
semantics a checked exception commits, so a future partial write would commit
inconsistently. The sibling `QuarantineReviewListener.java:141` sets `rollbackOn`
deliberately, proving the project knows the distinction. Latent today (the body
has one write — `advanceCursor`), but the documented atomicity invariant is
unenforced and the gap opens the moment T1-F adds a pre-cursor write.

## Acceptance

See frontmatter. Add `rollbackOn=SQLException.class`; prove rollback with a test.

## Out-of-scope

See frontmatter. The T1-F side-effect write is not added here.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 15#F1.
- `QuarantineReviewListener` is the in-repo reference.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-487-*.md
```
