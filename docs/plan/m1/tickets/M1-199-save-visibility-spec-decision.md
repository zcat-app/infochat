---
id: M1-199
title: "/save visibility sentence: adjudicate the scope-visibility reading"
status: done
created: 2026-06-07
last_updated: 2026-06-07
escalations:
  - date: 2026-06-07
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (escalation from budget-breach during implementation planning,
      before any code change). User chose direction (b'): /save admits
      posts visible in ANY of the caller's scopes (DM subscriptions ∪
      approved, non-removed group memberships). Call-site sweep found
      4 pre-existing test files that drive /save through the production
      handler against posts with NO source_subscription seeded —
      SaveCommandHandlerTest, SaveCapConcurrencyIT, SavedLibraryIT,
      UnsaveCommandHandlerTest — all of whose happy paths fail under
      the (b') filter. Total footprint: 6 files vs files_budget: 4,
      and test_plan has no modifies: authorization for the 4 files.
clarity_check:
  date: 2026-06-07
  verdict: WARN
  warnings:
    - "Acceptance item 1: either/or decision-tier structure — reviewer must first identify which branch was taken, then verify that branch's outcome"
    - "Acceptance item 3: commit-message verification is manual inspection, not a runnable check (standard for decision tickets)"
  blockers: []
blocked_by: []
files_budget: 7
files_scope:
  - docs/spec/commands.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCapConcurrencyIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnsaveCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the save-cap atomicity machinery (SELECT … FOR UPDATE counter) — implemented and tested, untouched
  - /saved retrieval filters and /unsave — unchanged either way
  - the chat tools' per-(user, scope) visibility implementation (searchPosts/getPost) — referenced as the definitional contrast only; their code is M1-193/M1-194/M1-197 territory
  - re-litigating D13 (saves are per-user-globally) — the carve-out stands; only the save-TIME visibility check is in question
acceptance:
  - "Direction (b') — chosen by the user at start (2026-06-07): /save admits a READY post iff its source is visible in at least one of the caller's scopes, defined as: the caller's own DM source_subscription rows (scope_kind='dm', scope_id = caller's users.id) UNION source_subscription rows (scope_kind='group') of groups where the caller holds an active membership (group_membership.removed_at IS NULL) in an approved, non-removed group (groups.approval_status='approved' AND groups.removed_at IS NULL)"
  - "The sentence in docs/spec/commands.md §Content — \"The `/save` flow never lets a user bookmark content they cannot see.\" — is expanded to define this any-caller-scope visibility filter (the union above) in addition to the existing status rules"
  - "SaveCommandHandler's target-post lookup enforces the filter; a READY post outside the caller's visibility union returns error.save.unknown_uid through the same path as an unknown UID (the existence-vs-no-access distinction is never exposed, mirroring getPost in security.md §Prompt-injection defenses)"
  - "Named tests: save succeeds via a DM subscription; save succeeds via an approved-group membership's subscription; save of a READY post with no subscription in any caller scope → error.save.unknown_uid and no row written; save via a departed membership (group_membership.removed_at set) → error.save.unknown_uid; save via a non-approved group → error.save.unknown_uid"
  - "The three already-implemented status legs stay intact — the existing named tests asserting QUARANTINED → error.save.unknown_uid, NEEDS_REVIEW → error.save.unknown_uid, and unknown UID → error.save.unknown_uid stay green"
  - "The choice and its argument (the D13 per-user-globally carve-out, schema.md §Invariants Invariant 1's documented saved_post exception, and the chat tools' \"visible in the calling (user, scope)\" definition in security.md §Prompt-injection defenses) are recorded in the commit message"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java — fixture-only: happy-path tests gain a seeded DM source_subscription for the acting user; assertions unchanged"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCapConcurrencyIT.java — fixture-only: seed a DM source_subscription; assertions unchanged"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java — fixture-only: seed a DM source_subscription; assertions unchanged"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnsaveCommandHandlerTest.java — fixture-only: seed a DM source_subscription for the cap-saturation path that drives /save; assertions unchanged"
  preserves:
    - all tests currently green on main (the four modified files change fixtures only, never assertions)
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D13
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
      files: 8
      added: 355
      removed: 35
revisions:
  - date: 2026-06-07
    reason: budget-breach rework (direction (b') chosen; call-site sweep found 4 pre-existing test files driving /save with no subscription fixtures)
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      files_budget_at_snapshot: 4
      acceptance_at_snapshot:
        - "A decision is recorded and applied: EITHER (a) the sentence in docs/spec/commands.md §Content — \"The `/save` flow never lets a user bookmark content they cannot see.\" — is amended to bind exactly the enumerated status rules that precede it (READY visible-and-snapshotted; QUARANTINED \"treated as an unknown UID\"; NEEDS_REVIEW \"treated as an unknown UID\"), pinning the current implementation; OR (b) /save gains the wider per-(user, scope) visibility filter, with the filter's definition written into the spec sentence and implemented in SaveCommandHandler with named tests"
        - "Whichever direction: the three already-implemented status legs stay intact — the existing named tests asserting QUARANTINED → error.save.unknown_uid, NEEDS_REVIEW → error.save.unknown_uid, and unknown UID → error.save.unknown_uid stay green"
        - "The choice and its argument (the D13 per-user-globally carve-out, schema.md §Invariants Invariant 1's documented saved_post exception, and the chat tools' \"visible in the calling (user, scope)\" definition in security.md §Prompt-injection defenses) are recorded in the commit message"
        - "mvn -B clean verify from the repo root exits 0"
      test_plan_at_snapshot:
        adds:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/command
        preserves:
          - all tests currently green on main
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-07
    verdict: CLEAN
    base: 93f1eb2 (fork point of m1/M1-199-save-visibility-sentence-adjud)
    head: working tree @ m1/M1-199-save-visibility-sentence-adjud (pre-commit, post-APPROVE round 1)
    verdict_file: docs/plan/m1/redteam/M1-199-2026-06-07.md
    out_of_model_count: 2
    note: |
      Pre-commit audit; no findings, nothing to fix on the branch. Two
      advisory OUT-OF-MODEL items: (1) membership-departure signal
      fidelity (already an accepted residual per the Refinement section;
      candidate messaging.md adapter-contract amendment if leave-event
      delivery should become a promise); (2) /save has no per-command
      rate bucket and no audit row — pre-existing posture, unchanged.
---

# M1-199: /save visibility sentence: adjudicate the scope-visibility reading

## Context

Unified finding P10 (`deep-code-review/v2/UNIFIED.md` §2): SaveCommandHandler
selects `WHERE p.uid = ? AND p.status = 'READY'` (:99-104) — any
registered DM user can bookmark any READY post by uid, regardless of
subscriptions or scope.

**Re-anchoring result (the batch-2 drafting prompt flagged this as the
canonical trap):** the opus-47 quote DOES exist verbatim —
commands.md §Content closes the `/save` visibility-of-target rules with
"The `/save` flow never lets a user bookmark content they cannot see."
However, every rule that sentence summarizes is **status-based** (READY /
QUARANTINED / NEEDS_REVIEW), and all three legs are already implemented
and covered by named tests in SaveCommandHandlerTest (verified on main
2026-06-07: tests at :133-170 pin QUARANTINED, NEEDS_REVIEW, and
unknown-UID all → error.save.unknown_uid with no row written).

What remains is a genuine **spec ambiguity**, not a confirmed code bug:
does "content they cannot see" also mean the per-(user, scope)
visibility that security.md's tool catalogue defines for getPost
("returns null for a UID not visible in the calling scope")? Points
against the wider reading: saves are per-user-globally by design (D13;
schema.md Invariant 1 names saved_post as the only documented carve-out
from per-(user, scope) isolation), so a save deliberately transcends the
scope it was made in — a save-time subscription filter sits oddly beside
that. Points for: a UID obtained out-of-band names content no retrieval
surface would show the caller.

This is a decision-tier ticket (user call on direction at start), per
the same pattern as the audit's other decision groupings.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T22 under `deep-code-review/v2/` (opus-47
  prov F5; re-anchored against the current spec text at draft time).
- If direction (a) is chosen this is a near-pure spec edit bundled with
  (at most) a pinning test — the code change footprint is zero.

## Refinement (2026-06-07, budget-breach rework)

The user adjudicated direction (b'): any-caller-scope visibility — wider
than the original option (b)'s calling-scope-only filter, chosen so a
post seen in a group can still be bookmarked privately from DM. Security
evaluation at decision time: strictly tighter than the status-based
status quo; discloses nothing beyond what searchPosts/getPost already
grant scope-by-scope; one low-impact residual (a stale
group_membership.removed_at extends DM-side save-visibility after the
user leaves the group, bounded by the adapter's user_left_group signal
and D13's snapshot semantics, which already let saves outlive
membership).

Budget grew 4 → 7 because the call-site sweep found four pre-existing
test files that drive /save through the production handler against
posts with no source_subscription seeded; under the (b') filter their
happy paths fail without a fixture-only subscription seed (authorized
in test_plan.modifies; assertions unchanged).
