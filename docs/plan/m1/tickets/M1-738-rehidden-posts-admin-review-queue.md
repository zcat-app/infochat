---
id: M1-738
title: "Re-hidden posts bypass the admin review queue"
status: done
created: 2026-07-31
last_updated: 2026-08-01
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/QuarantineDao.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The Stage 2 infrastructure-failure release-as-READY policy itself
    (`security.md` §Failure handling). That release is a deliberate
    availability decision; this ticket closes the review-queue gap it
    opens, it does not relitigate the decision.
  - >-
    `AdminReviewTtlJob`'s NEEDS_REVIEW→QUARANTINED transition. Those
    posts already carry quarantine rows — NEEDS_REVIEW implies a Stage
    1 flag wrote one — so they reach the queue.
  - >-
    Any change to `quarantine_review_view` or the `quarantine` schema
    (V10). The view is a faithful projection of the table; the gap is
    the missing ROW, not the view.
  - >-
    infochat-provider/** — the `/quarantine list` read side
    (`QuarantineCommandHandler`) is correct.
  - >-
    The analogous first-pass gap: `Stage2VerdictHandler` inserts no
    quarantine row on a first-pass INJECTION/MALWARE verdict for a
    post with no Stage 1 rows (same admin-queue invisibility, first
    flagged in this ticket's 2026-08-01 redteam round-table). Owned
    by the follow-up ticket filed after this ticket merges; this
    ticket touches only the re-evaluation path.
acceptance:
  - >-
    Premise re-verified at start: `quarantine_review_view`
    (V10__quarantine.sql:72) projects `quarantine` rows only, so a
    QUARANTINED post with no row never enters the admin queue. If the
    view turns out to also source `post` directly, re-scope before
    implementing.
  - >-
    A named `ReEvaluationJobTest` test passes: a post released READY
    with NO quarantine row that the re-evaluation job re-hides to
    QUARANTINED gets a PENDING quarantine row inserted (flagged_by the
    re-evaluation actor), so it appears in `quarantine_review_view`
    and in the `/quarantine list` admin queue.
  - >-
    The existing open-row path is preserved: when a PENDING quarantine
    row already exists for the post, the re-hide keeps the
    `updated_at` bump + re-announce
    (`reAnnouncePendingQuarantineRows`) and inserts NO duplicate row —
    pinned by a named test.
  - >-
    The insert emits the same quarantine-review NOTIFY the Stage 1
    path emits on PENDING insert, so a live Provider sees the row
    without waiting for the catch-up scan.
  - >-
    The inserted row's lifecycle is symmetric with Stage 1 rows: a
    re-hidden post whose re-eval later rolls BENIGN auto-releases AND
    its stage2 row transitions PENDING→BENIGN_CLOSED (with the
    BENIGN_CLOSED NOTIFY) — `ReEvaluationJob.closeQuarantineRows`
    covers `flagged_by='stage2'` rows, so the admin queue never holds
    a PENDING "judge said hostile" row for a released post. Pinned by
    a named test (re-hide → insert → later BENIGN → row closed) and
    by the updated U-25 predicate test (see ## Notes for the
    test-modification authorization).
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 538
      removed: 31
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-08-01
    verdict: FINDINGS
    base: ed5fd36a2aea625204c063dee7ecdf528b581172
    head: working tree (uncommitted, branch m1/M1-738-re-hidden-posts-bypass-the-adm)
    verdict_file: docs/plan/m1/redteam/M1-738-2026-08-01.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      One low AUDIT-EVASION finding: the inserted stage2 row stayed
      PENDING after a later BENIGN re-eval auto-released the post.
      Remediated via the redteam-finding refine (f0b75017) — folded
      into acceptance as the close-symmetry item, superseding the
      start-time deferral. The finding's stale-comment sub-point was
      fixed on the branch (comment-only). The out-of-model
      UTF-16-vs-Postgres span-unit note is forwarded to the follow-up
      ticket as a design note. Auditor: kimi threat-actor via
      run-gate.sh, contamination=none.
  - date: 2026-08-01
    verdict: CLEAN
    base: ed5fd36a2aea625204c063dee7ecdf528b581172
    head: working tree + refine commit f0b75017 (re-audit, round 2)
    verdict_file: docs/plan/m1/redteam/M1-738-2026-08-01-r2.md
    out_of_model_count: 0
    note: |
      Re-audit of the post-refine diff with explicit re-audit
      framing. The round-1 finding is verified remediated
      (closeQuarantineRows covers stage1+stage2; U-25 retargeted;
      lifecycle test added); the adjudicated out-of-model note was
      not re-reported. CLEAN. Auditor: kimi threat-actor via
      run-gate.sh, contamination=none.
clarity_check:
  date: 2026-07-31
  verdict: WARN
  warnings:
    - >-
      lint: clean (0 blockers, 0 warnings).
    - >-
      Self-check: V10's flagged_by CHECK admits only 'stage1'/'stage2',
      so "the re-evaluation actor" lands as flagged_by='stage2' (the
      re-judge runs through stage2Worker.judgeBody) — no migration,
      consistent with migration_touch: false.
    - >-
      Self-check: a re-hidden post that later rolls BENIGN auto-releases
      while its new stage2 row stays PENDING until TTL auto-reject
      (ReEvaluationJobTest U-25 pins stage1-only close). User decision
      2026-07-31: keep this ticket insert-only; the stage2-row lifecycle
      and the analogous first-pass Stage2VerdictHandler insert gap go to
      a follow-up ticket filed after merge.
  blockers: []
escalation_reason:
---

# M1-738: Re-hidden posts bypass the admin review queue

## Context

Side-discovery from the M1-730 redteam disposition (2026-07-31); the
r2 audit's INFO-LEAK repro depends on `ReEvaluationJob` re-hiding a
released post to QUARANTINED, and tracing that path surfaced a gap
arguably worse than the one under audit. `/quarantine list` reads
`quarantine_review_view` (`QuarantineCommandHandler.java:61-80`), a
straight projection of the `quarantine` table (V10__quarantine.sql:72).
Quarantine rows are inserted ONLY by Stage 1 (`Stage1Pipeline`, three
`QuarantineDao.insert` sites). A post released READY during a Stage 2
outage (§Failure handling) never had a row; when `ReEvaluationJob`
later returns INJECTION and re-hides it to QUARANTINED,
`reAnnouncePendingQuarantineRows` (ReEvaluationJob.java:~382-395) only
bumps `updated_at` on EXISTING PENDING rows — for this post there are
none. Result: a post the system decided to hide sits at QUARANTINED
with no admin ever seeing it, contradicting §Quarantine workflow's
"stays QUARANTINED until admin review." Pre-existing and independent
of M1-730, hence no `blocked_by`.

## Acceptance

See the YAML `acceptance:` list. In prose: when the re-evaluation job
re-hides a post that has no quarantine row, insert one (PENDING,
re-evaluation actor, NOTIFY on insert) so the post enters the admin
review queue; when a row exists, keep today's bump-and-announce and
insert nothing.

## Out-of-scope

The outage release policy, the TTL job's transition (rows exist
there), the view, the schema, and the Provider read side — see the
YAML list.

## Notes

- The `quarantine` table carries `post_id`, `post_uid` and
  `post_fetched_at`, so the re-hide path already holds everything an
  insert needs; no migration (`migration_touch: false`).
- The insert-on-absence must commit in the same transaction as the
  post's status UPDATE — the atomicity rule `QuarantineDao`'s javadoc
  states for the Stage 1 insert grouping with the parent post update
  applies here for the same reason.
- Verified while filing: only `Stage1Pipeline` inserts quarantine rows
  (grep `QuarantineDao.insert` / `INSERT INTO quarantine`); the
  re-hide path updates only `status = 'PENDING'` rows. The view
  definition was checked from the migration file — acceptance item 1
  makes the developer re-confirm it before implementing.

## Redteam refine (2026-08-01)

The `/m1-tick run` redteam gate returned FINDINGS (1 low
AUDIT-EVASION): the inserted stage2 row stayed PENDING after a later
BENIGN re-eval auto-released the post, leaving the admin queue
asserting "awaiting review" about a live post. The user chose
**refine and fix**: the close-symmetry acceptance item above was
added (the earlier start-time decision to defer this to a follow-up
is superseded; only the first-pass `Stage2VerdictHandler` gap remains
deferred, per the out_of_scope entry).

**Test-modification authorization** (engineering rules §8): this
ticket changes the behavior of `ReEvaluationJob.closeQuarantineRows`
— it now closes `flagged_by='stage2'` rows written by the re-eval
job's own re-hide insert, not only `flagged_by='stage1'` rows. That
requires updating the pre-existing test
`benignReEval_closesOnlyStage1QuarantineRows_leavesNonStage1Pending`
in `ReEvaluationJobTest`: the "future non-stage1 quarantine writer"
its stage1-only predicate guarded against (U-25) now exists and IS
the re-evaluation job itself, so a re-eval BENIGN release must close
the re-eval job's own rows. The test is rewritten to seed one
`flagged_by='stage2'` PENDING row alongside the stage1 row and to
assert BOTH transition to BENIGN_CLOSED on a BENIGN re-eval (the
stage1 assertion is unchanged).
