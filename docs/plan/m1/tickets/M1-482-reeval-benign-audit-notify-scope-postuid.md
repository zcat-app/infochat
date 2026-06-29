---
id: M1-482
title: "Re-eval BENIGN over-audits/notifies infra-failure releases; uses post.id"
status: pending
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 5
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "The orphan-rescue fix for the Stage-1/Stage-2 strand (that is M1-501)."
acceptance:
  - >-
    applyBenignReEval writes the RE_EVAL_RELEASED audit row and emits the release
    NOTIFY only for the UNKNOWN→BENIGN transition (a post that was actually held)
    — not for the infra-failure class, which was already visible and is not a
    release. This matches docs/spec/security.md, which scopes the audit +
    notification to the UNKNOWN→BENIGN case only.
  - >-
    The RE_EVAL_RELEASED audit row's target_id carries the post_uid, not the
    internal post.id (ReEvaluationJob.java:459,589 currently emit
    candidate.postId()); the candidate enumeration selects the uid it needs.
  - >-
    Tests assert (a) the infra-failure re-eval path writes no RE_EVAL_RELEASED
    audit and emits no release NOTIFY, and (b) the UNKNOWN→BENIGN audit row's
    target_id equals the post_uid.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationBenignAuditScopeIT.java"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-06-29
    reason: >-
      budget-breach refine (pre-start): files_budget 4→5. Acceptance item 2
      mandates the ReEvalCandidate record carry the post_uid ("the candidate
      enumeration selects the uid it needs"), and switching the audit target_id
      from post.id→post.uid reverses a behavior three existing tests pin. The
      record-signature change ripples to all 3 ReEvalCandidate construction
      sites and the behavior-reversal sweep touches ReEvaluationJobTest's audit
      helpers — 5 files total (ReEvaluationJob.java + new IT + 3 preserves-
      mandated test edits). The +1 over the original 4 is entirely mandated by
      `preserves: all tests currently green on main` (mechanical compile/assert
      fixes of orphans this change creates), matching the M1-517 precedent.
    snapshot:
      files_budget: 4
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-482: Re-eval BENIGN over-audits/notifies infra-failure releases; uses post.id

## Context

From `/deep-code-review full` (2026-06-27), report
`03-main-infochat-collector-01.md#F2` and `#F3` (both medium/low, verified at
source). `applyBenignReEval` (`ReEvaluationJob.java:219-236`) unconditionally
writes a `RE_EVAL_RELEASED` audit row and emits a release NOTIFY for **both** the
UNKNOWN→BENIGN case and the infra-failure case, but `docs/spec/security.md`
scopes the audit + notification to UNKNOWN→BENIGN only — the infra-failure post
was already visible, so "release" is a phantom event there (F2). Separately, the
audit row's `target_id` uses the internal `post.id`
(`ReEvaluationJob.java:459,589`) where the spec mandates `post_uid` (F3).

## Acceptance

See frontmatter. Scope the benign audit/notify to UNKNOWN→BENIGN and switch the
audit `target_id` to `post_uid`; cover both with tests.

## Out-of-scope

See frontmatter. The Stage-1/Stage-2 orphan-rescue gap is M1-501.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 03#F2 + 03#F3.
- `ReEvaluationJob` is the project's §9 injected-Clock reference; keep that
  intact while making these audit-scope changes.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-482-*.md
```
