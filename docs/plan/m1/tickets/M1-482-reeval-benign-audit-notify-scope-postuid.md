---
id: M1-482
title: "Re-eval BENIGN over-audits/notifies infra-failure releases; uses post.id"
status: done
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
    NOTIFY iff the BENIGN re-eval releases a post that was HELD
    (status='QUARANTINED') at re-eval time — the genuine hidden→visible
    transition docs/spec/security.md §Re-evaluation job guards. This covers every
    UNKNOWN→BENIGN release (an UNKNOWN candidate is always QUARANTINED) AND the
    infra-failure release-on-stage2-failure=false / re-hidden-by-prior-non-BENIGN
    sub-cases. An infra-failure post that was already user-visible (READY) or
    still in the normal pipeline (RAW) is not a held-pending-review release and is
    neither audited nor paged. (Refines the original class-based scoping, which
    would have dropped the audit on a genuine QUARANTINED→released infra-failure
    exposure that M1-182 deliberately audits — see the 2026-06-29 premise-fail
    escalation.)
  - >-
    The RE_EVAL_RELEASED audit row's target_id carries the post_uid, not the
    internal post.id (ReEvaluationJob.java currently emits candidate.postId());
    the candidate enumeration selects the uid it needs.
  - >-
    Tests assert (a) a non-held infra-failure BENIGN re-eval (RAW/READY) writes no
    RE_EVAL_RELEASED audit and emits no release NOTIFY, while a QUARANTINED→
    released infra-failure BENIGN re-eval DOES audit and page; and (b) the
    UNKNOWN→BENIGN audit row's target_id equals the post_uid.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationBenignAuditScopeIT.java"
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
      files: 7
      added: 458
      removed: 61
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: 41a489ffce11af5d476cb43f41b2afadb5308339
    head: m1/M1-482-reeval-benign-audit-notify-scope-postuid (working tree)
    verdict_file: docs/plan/m1/redteam/M1-482-2026-06-29.md
    out_of_model_count: 0
    note: |
      Pre-commit in-progress audit of the visibility-based audit/notify
      rescoping. CLEAN, no findings, no out-of-model items. The audit/page
      remains gated on a genuine hidden→visible release (status QUARANTINED at
      re-eval time), so the threat model's silent-auto-release signal is intact.
escalations:
  - date: 2026-06-29
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (developer-surfaced during implementation). The acceptance's
      class-based scoping ("not for the infra-failure class, which was already
      visible") is false for a subset of infra-failure posts: a QUARANTINED
      infra-failure post (release-on-stage2-failure=false, or re-hidden by a
      prior non-BENIGN roll) released via BENIGN re-eval IS a genuine
      hidden→visible transition. M1-182's quarantinedInfraFailureBenign_
      requeuesToRaw_singleReleaseAuditRow deliberately audits it (count=1), and
      security.md's UNKNOWN audit rationale ("posts auto-released from
      QUARANTINED reach users with no human reviewer having seen the row")
      applies equally. Dropping that audit would be a security regression.
      Resolved by refine to visibility-based scoping (user decision 2026-06-29):
      audit/notify iff the post was QUARANTINED (held) at re-eval time.
revisions:
  - date: 2026-06-29
    reason: >-
      premise-fail refine: acceptance re-scoped from class-based ("not for the
      infra-failure class") to visibility-based ("iff the post was QUARANTINED/
      held at re-eval time"). Preserves M1-182's audit of a genuine
      QUARANTINED→released infra-failure exposure while still dropping the
      phantom audit on already-visible (READY) / in-pipeline (RAW) infra-failure
      posts. Acceptance items 1 and 3 rewritten; item 2 (post_uid) unchanged.
    snapshot:
      acceptance_item_1: >-
        applyBenignReEval writes the RE_EVAL_RELEASED audit row and emits the
        release NOTIFY only for the UNKNOWN→BENIGN transition — not for the
        infra-failure class, which was already visible and is not a release.
      acceptance_item_3a: >-
        the infra-failure re-eval path writes no RE_EVAL_RELEASED audit and emits
        no release NOTIFY
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
