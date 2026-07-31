---
id: M1-738
title: "Re-hidden posts bypass the admin review queue"
status: pending
created: 2026-07-31
last_updated: 2026-07-31
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
