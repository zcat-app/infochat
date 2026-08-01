---
id: M1-742
title: "A quarantine row per non-BENIGN Stage 2 verdict"
status: pending
created: 2026-08-01
last_updated: 2026-08-01
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2/Stage2FirstPassQuarantineRowIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictPersistenceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/QuarantinePendingNotifyIT.java
  - infochat-core/src/main/resources/db/migration/V70__quarantine_one_pending_stage2_row.sql
  - ADMIN_GUIDE.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The `approve_quarantine` owed-verdict guard — M1-741 owns it.
    This ticket's rows land on `stage2_done = TRUE` posts, which that
    guard does not block.
  - >-
    `quarantine_review_view` and the `quarantine` schema shape (V10) —
    the view already projects `flagged_by='stage2'` rows; only the new
    partial unique index is added.
  - >-
    Stage 1 row shapes and `Stage1Pipeline` — the stage1 write path is
    unchanged.
  - >-
    Any per-post grouping change to `/quarantine list`
    (`infochat-provider/**`) — the per-row presentation stays; the
    review convention is documented, not re-tooled.
acceptance:
  - >-
    `Stage2VerdictHandler.applyQuarantineVerdict` inserts the
    whole-body `flagged_by='stage2'` PENDING row on EVERY first-pass
    non-BENIGN verdict — M1-739's dedup predicate AND its FOR UPDATE
    lock are removed (with no check there is nothing to race; the
    insert becomes unconditional). The `stage2_<verdict>` rule_id
    convention stays. Pinned by a named test: a post WITH PENDING
    Stage 1 rows receiving a first-pass INJECTION verdict gets the
    stage2 row in addition (stage1 row untouched PENDING).
  - >-
    Authorized test retargets (new expected behavior, named here per
    the test-modification rule): (a)
    `Stage2FirstPassQuarantineRowIT.malwareWithPendingStage1RowInsertsNothing`
    is replaced by the item-1 test (the dedup it pinned is removed);
    (b) `QuarantinePendingNotifyIT`'s "an unsafe verdict must not
    re-fire PENDING" test now asserts an unsafe verdict fires exactly
    ONE PENDING NOTIFY — the stage2 row's — not zero.
  - >-
    A new migration adds a partial unique index guaranteeing at most
    one PENDING `flagged_by='stage2'` row per post (closes the
    duplicate-verdict phantom insert from redteam
    `docs/plan/m1/redteam/M1-739-2026-08-01-r2.md`: a concurrent
    duplicate Stage 2 evaluation fails its insert instead of
    double-listing the post). Pinned by a named test.
  - >-
    `ADMIN_GUIDE.md` documents the per-post review convention: a
    Stage-2-flagged post shows its Stage 1 rows plus one whole-body
    stage2 row (the verdict record, `rule_id = stage2_<verdict>`);
    approving the stage2 row forces READY without restoring spans (its
    placeholder is never woven into `post.body`), so a post's rows are
    reviewed together.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Quarantine workflow
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-742: A quarantine row per non-BENIGN Stage 2 verdict

## Context

Follow-up to M1-739 (2026-08-01, user decision to file). M1-739 landed
the dedup-guarded half: a first-pass non-BENIGN verdict inserts the
stage2 whole-body row only when NO PENDING row exists. V10's header
(`infochat-core/src/main/resources/db/migration/V10__quarantine.sql:4-7`)
promised the unconditional shape — "a future Stage-2 row per LLM-judge
non-BENIGN verdict (flagged_by = 'stage2', landed by M1-033)" — and
docs/spec/security.md §Quarantine workflow says "Every Stage 1 or
Stage 2 hit creates a quarantine row". This ticket delivers the
contract literally: every non-BENIGN verdict leaves its own
review-trail row carrying the exact body the judge saw — the only
retention-independent primary record of the judged original (post
partitions drop on the retention horizon; `quarantine` is
unpartitioned and survives by design; reconstruction from
`post.body` + stage1 spans dies with the partition). It also deletes a
race class instead of managing it: with no dedup predicate there is no
time-of-check, so M1-739's FOR UPDATE serialization becomes
unnecessary — concurrent paths can only over-report (admin-visible
duplicates, further bounded by the new unique index), never
under-report (a hidden QUARANTINED post).

## Acceptance

See the YAML `acceptance:` list. In prose: make the stage2 row
unconditional on first-pass non-BENIGN verdicts (retargeting the two
M1-739-era tests that pin the dedup), add a partial unique index
bounding duplicates to one PENDING stage2 row per post, and document
the per-post review convention in ADMIN_GUIDE.md.

## Out-of-scope

The `approve_quarantine` guard (M1-741), any view/schema-shape change,
Stage 1's write path, and any provider-side queue re-tooling — the
per-row list presentation stays as-is.

## Notes

- The migration number in `files_scope` is indicative — V70 assumes
  M1-741 takes V69; renumber at implementation (M1-740 is also in
  flight).
- The `span_end` UTF-16 code-unit convention noted in M1-739's ticket
  applies unchanged: harmless while spans are whole-body and no
  DB-side span splice exists; do NOT "fix" the unit row-by-row here.
- The M1-739 redteam rounds (`docs/plan/m1/redteam/M1-739-2026-08-01*.md`)
  are the analysis base: round 1's TOCTOU finding motivated the dedup
  lock this ticket removes, and round 2's out-of-model duplicate-verdict
  phantom insert is what the unique index closes.
- Intermediate-state note for the admin guide: approving rows one at a
  time can publish a partially-redacted post mid-review — that
  semantics predates this ticket (multi-row Stage 1 posts behave the
  same) and is documented, not changed, here.
