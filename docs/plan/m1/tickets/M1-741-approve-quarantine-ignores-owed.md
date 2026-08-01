---
id: M1-741
title: "approve_quarantine ignores an owed Stage 2 verdict"
status: pending
created: 2026-08-01
last_updated: 2026-08-01
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/resources/db/migration/V69__approve_quarantine_verdict_owed_guard.sql
  - infochat-collector/src/test/java/app/zcat/infochat/collector/notify/QuarantineProcedureNotifyIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/notify/ApproveQuarantinePhantomNotifyIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    `reject_quarantine` — it never publishes (no READY write, no
    `new_post` NOTIFY). Its mid-flight race was the M1-739 dedup
    TOCTOU, closed there by the FOR UPDATE serialization; nothing
    remains to guard on the reject side.
  - >-
    The per-verdict stage2 row shape and its partial unique index —
    M1-742 owns both. This ticket's guard predates and is independent
    of that change.
  - >-
    Any change to the eval pipeline (`Stage2VerdictHandler`,
    `Stage1Worker`, `Stage2Worker`, `Stage1Pipeline`) — the guard
    lives entirely in the DB function and its error surfacing.
  - >-
    `quarantine_review_view` and the `quarantine` schema (V10) — the
    guard needs no new column; the predicate reads existing `post`
    flags.
acceptance:
  - >-
    Premise re-verified at start: `approve_quarantine` (V21, as
    amended by V48 / V50 / V53) sets `post.status='READY'`
    unconditionally with no check of the post's eval state
    (`infochat-core/src/main/resources/db/migration/V21__quarantine_admin.sql:60-65`
    on the pre-M1-741 base). If a guard has since appeared, re-scope
    before implementing.
  - >-
    A new migration replaces `approve_quarantine` so it raises a
    clear exception and performs NO write (no row transition, no post
    UPDATE, no audit row, no `new_post` NOTIFY) when the row's post
    has `stage1_flagged = TRUE AND stage2_done = FALSE` — a first-pass
    Stage 2 verdict is owed or in flight. Pinned by a named test
    driving the procedure directly (in `QuarantineProcedureNotifyIT`
    or a new IT).
  - >-
    The legitimate approve paths keep working, pinned by named tests:
    a watchdog/fail-closed QUARANTINED post (`stage1_flagged = FALSE`,
    `stage2_done = FALSE`) approves normally, and a post with the
    verdict recorded (`stage2_done = TRUE`) approves normally.
  - >-
    The guard's refusal reaches the admin as a clean localized command
    error via the existing `mapStoredProcError` path in
    `QuarantineCommandHandler` — pinned by a named test; extending the
    mapping with a new bundle key is in scope if the current mapper
    cannot distinguish the guard's exception.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-741: approve_quarantine ignores an owed Stage 2 verdict

## Context

Surfaced as an out-of-model item in both M1-739 redteam rounds
(`docs/plan/m1/redteam/M1-739-2026-08-01.md`, `-r2.md`):
`approve_quarantine` sets `post.status='READY'` and fires the
`new_post` NOTIFY unconditionally. An admin working the review queue
can approve a Stage 1 row while the post's first-pass Stage 2 verdict
is still in flight — the window is seconds normally and stretches
under semaphore queue-wait + retry backoff (`Stage2Worker`). The post
is then READY and announced to users until the verdict transaction
commits and re-hides it: judge-condemned content is user-visible for
the remainder of the window, against the intent of
docs/spec/security.md §Failure handling ("a degraded judge must never
auto-release" — here a trusted admin releases it unknowingly, before
the judgment exists). M1-739 made the END state self-healing (the
verdict re-hides and the post now always carries a PENDING queue row),
so the residual is exactly the transient exposure. The guard predicate
is verified against the schema: `post.stage1_flagged` is
`BOOLEAN NOT NULL DEFAULT FALSE` (V7__joins_post.sql:156) and only the
regex path sets it TRUE (`Stage1Pipeline.updatePostBodyAndFlags`); the
watchdog / match-overflow / sanitizer fail-closed paths never set it,
so `stage1_flagged = TRUE AND stage2_done = FALSE` means precisely
"a Stage 2 verdict is owed or in flight" — nothing legitimate is
blocked.

## Acceptance

See the YAML `acceptance:` list. In prose: amend `approve_quarantine`
in a new migration to refuse (clear exception, zero writes) when the
row's post is still owed a Stage 2 verdict; keep watchdog-quarantined
and verdict-recorded posts approvable; surface the refusal to the
admin as a clean localized error.

## Out-of-scope

`reject_quarantine` (never publishes; its race closed in M1-739), the
per-verdict stage2 row work (M1-742), any eval-pipeline change, and
any schema/view change — the guard reads existing `post` flags only.

## Notes

- The migration number in `files_scope` is indicative — V69 is next
  free at drafting (2026-08-01), but M1-740 is in flight and other
  migration tickets may land first; renumber at implementation.
- Alternatives considered: a Java-side pre-check in
  `QuarantineCommandHandler.handleApprove` — rejected: the function is
  the atomic boundary (row lock + transition + post UPDATE in one
  transaction); a handler pre-check would reintroduce a
  check-then-act window between the handler's read and the function's
  write.
- The stage2 row M1-739 inserts in the race case is unaffected:
  approving THAT row happens on a `stage2_done = TRUE` post, which the
  guard does not block.
- `mapStoredProcError` already maps stored-procedure exceptions to
  localized admin replies; the guard's exception should ride that path
  (new bundle key only if the mapper cannot distinguish it).
