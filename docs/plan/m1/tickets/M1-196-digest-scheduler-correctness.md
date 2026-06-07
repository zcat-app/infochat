---
id: M1-196
title: "Digest scheduler: no missed-slot records for pre-approval windows + async slot dispatch"
status: done
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the digest renderer/worker and summary prose generation — only scheduling and slot dispatch change
  - the DIGEST_SLOT_MISSED audit+sentinel same-transaction atomicity (recordMissedSlot) — correct today, preserved
  - per-group slot-hour overrides (explicitly v2 per commands.md §Periodic group digests)
  - /summary rate-cap coverage — M1-183's
  - groups schema changes — no approved_at column exists (V26 added only approval_status); if the chosen mechanism requires one, that is a files_scope/migration escalation, not a silent expansion
acceptance:
  - "Per docs/spec/commands.md §Periodic group digests — \"no catch-up digest is emitted when a group transitions to 'approved' (same skip-not-catch-up rule as missed-slot behavior)\" — slot windows that ended while the group was not yet approved are not recorded as missed: a named test approves a group after a slot window has already passed and asserts no DIGEST_SLOT_MISSED audit row and no admin notification is produced for that window (today queryActiveGroups filters only on approval_status = 'approved' with no eligibility-time check, so the first post-approval tick flags every pre-approval window as missed)"
  - "Genuinely missed windows for already-approved groups still produce the DIGEST_SLOT_MISSED audit row and sentinel — DigestSchedulerMissedSlotTest stays green (or is extended, not weakened)"
  - "Slot processing does not serialize the scheduler tick: a named test shows a slow consumer of one group's DigestSlot does not delay another group's slot emission past the tick (today digestSlotEvent.fire() is synchronous CDI dispatch on the scheduler tick thread)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D16
  - D47
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 434
      removed: 33
overrides:
  - date: 2026-06-07
    objection: |
      PARALLEL-START precondition: FAIL — in-flight ticket M1-221 declares
      files_scope: [] (empty), so disjointness with M1-196 cannot be proven
      mechanically; the parallel rule requires every in-flight ticket to
      declare a non-empty files_scope.
    user_justification: |
      De facto disjointness verified two independent ways before the
      override: (1) M1-221's actual working-tree diff at start time is
      entirely infochat-collector (RetryBackoff.java + Stage2/Tagger/
      EntityExtractor workers + their tests); (2) M1-221's acceptance
      remit names only collector eval workers and optionally LLM-adapter
      exception classes — nothing under infochat-provider digest/ or
      command/. All other in-flight tickets (M1-195, M1-222) have provably
      disjoint files_scope. Additionally the branch is created from
      current main d0340b7 (not the stale worktree fork point dd989fa) to
      pick up M1-181's ThrottledAdminNotifier API change, which
      DigestScheduler injects.
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-196: Digest scheduler: no missed-slot records for pre-approval windows + async slot dispatch

## Context

Two scheduler defects (unified finding P12, VALID ×2 —
`deep-code-review/v2/UNIFIED.md` §2):

1. **Spurious DIGEST_SLOT_MISSED for pre-approval windows.**
   DigestScheduler.queryActiveGroups (:190-191) selects
   `approval_status = 'approved' AND removed_at IS NULL` with no notion
   of *when* the group became eligible; the missed-slot path (:124-126 →
   recordMissedSlot) then back-fills a DIGEST_SLOT_MISSED audit row plus
   admin notification for every window that elapsed before approval. The
   spec explicitly assigns these windows the skip-not-catch-up rule (re-
   anchored 2026-06-07: the sentence quoted in the acceptance item exists
   verbatim in commands.md §Periodic group digests).
2. **Synchronous slot dispatch on the tick thread.**
   `digestSlotEvent.fire(...)` (:132) is synchronous CDI dispatch — a
   slow LLM render for one group delays every later group's slot in the
   same tick.

Re-grounding note: the `groups` table has no `approved_at` column
(V5 creates it with created_at only; V26 adds approval_status). The
eligibility-time mechanism is the implementer's choice — e.g. a sentinel
written at approval time by ApproveGroupCommandHandler (which is in
files_scope for exactly that option), or an eligibility marker derived
at first post-approval tick. A schema change is NOT pre-authorized.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T20 under `deep-code-review/v2/`
  (opus-48 prov F2, kimi-folder prov F7).

## Suggested direction (unverified hypothesis)

The kimi-folder run suggested `fireAsync` or per-group virtual threads
for the dispatch leg.

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
