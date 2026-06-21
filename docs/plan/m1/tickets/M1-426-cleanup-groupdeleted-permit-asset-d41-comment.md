---
id: M1-426
title: "cleanup: drop speculative GroupDeleted permit; fix asset D41 comment"
status: done
created: 2026-06-21
last_updated: 2026-06-22
blocked_by: []
files_budget: 2
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MembershipEvent.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The three retained MembershipEvent permits (UserJoined, UserLeft, BotRemoved) and their producers/consumers — unchanged.
  - MembershipEventHandler.handle and its switch — already has a `default ->` arm, so removing a permit needs no change there; do not edit it.
  - The AssetSnapshotFetcher D42 failure-ladder SQL, the atomic increment itself, and the row-vanished no-op branch — all correct; only the misleading comment text changes.
  - docs/spec/messaging.md and docs/design/06-messaging.md — both already state group-deleted has no v1 carrier (deferred to v2); no doc edit needed.
acceptance:
  - "MembershipEvent.GroupDeleted is removed: the permit is dropped from the sealed interface's permits clause and its record declaration is deleted, leaving UserJoined, UserLeft, BotRemoved."
  - "The MembershipEvent type javadoc is corrected to state three lifecycle permits and to note that group-deleted is handled via the permanent-send-failure path with no distinct adapter→Provider carrier in v1 (deferred to v2), per docs/spec/messaging.md §Failure handling and docs/design/06-messaging.md §6.x — i.e. it no longer claims the spec defines a group-deleted event signal."
  - "The AssetSnapshotFetcher.recordFailure comment (~lines 221-225) no longer justifies the atomic increment as 'defensive in case of operator-side concurrent runs' — it is reworded to state the increment is simply the correct/atomic form, with the single-instance invariant (D41) noted as the reason concurrent ticks do not occur, removing the contradiction with the no-defensive-code rule."
  - "No test references MembershipEvent.GroupDeleted (verified by grep); the full suite compiles and mvn -B clean verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D41
reviews:
  - round: 1
    date: 2026-06-22
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 25
      removed: 22
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-22
  verdict: WARN
  warnings:
    - 'Acceptance item 2 cites "docs/design/06-messaging.md §6.x" as a javadoc target; "§6.x" is an undefined placeholder, not a real anchor. Context section supplies the operative prose, so implementation is not blocked; use the real subsection heading when writing the javadoc.'
  blockers: []
---

# M1-426: drop speculative GroupDeleted permit; fix asset D41 comment

## Context

Two low-severity rules-drift items from deep-review full (2026-06-21), grouped as a
single cleanup sweep (precedent: M1-412). Both verified at source 2026-06-21.

**Item A — messaging finding F1 (speculative SPI).**
`MembershipEvent` (`infochat-messaging-adapter/.../messaging/MembershipEvent.java`)
seals four permits, but `GroupDeleted` has **zero producers and zero consumers**
anywhere in the repo (verified by grep — the only references are its own
definition). Both `docs/spec/messaging.md` §Failure handling (lines 341-360) and
`docs/design/06-messaging.md` §6 (line ~410) state explicitly that group-deleted is
handled via the permanent-send-failure threshold path and that **"no adapter→Provider
carrier for that failure sub-class exists today"** — the distinct signal is
**deferred to v2**. So `GroupDeleted` is speculative SPI surface that contradicts the
spec, the same forbidden shape the module's own `MessagingAdapter` javadoc cites when
deferring `groupExists`. The type javadoc also wrongly claims the spec *defines* a
group-deleted signal. Removal is safe: `MembershipEventHandler.handle`'s switch has a
`default ->` arm (handles UserJoined and any other type), and no test references the
permit.

**Item B — collector finding F1 (D41-contradicting comment).**
`AssetSnapshotFetcher.recordFailure` (~lines 221-225) justifies its atomic
`consecutive_failures = consecutive_failures + 1 ... RETURNING` as "defensive in case
of operator-side concurrent runs." The D41 single-instance invariant forbids that
scenario (and the `@Scheduled` ticks use `ConcurrentExecution.SKIP`), so the comment
mis-teaches the trust boundary by implying multi-instance operation is a
supported/defended mode — a §7 "no defensive code for impossible scenarios"
contradiction. The atomic increment is the natural correct SQL form regardless; only
the *comment's justification* is wrong. Comment-only fix.

## Acceptance

See frontmatter. Item A removes the `GroupDeleted` permit + record and corrects the
type javadoc; Item B rewords the asset comment. No new tests (Item A is a dead-code
removal proven by a green compile + suite; Item B is comment-only).

## Out-of-scope

See frontmatter. No edits to `MembershipEventHandler`, the retained permits, the
asset failure-ladder SQL, or the spec/design docs (which already say the right
thing).

## Notes

- Adjacent precedent for the "atomic increment is just correct, not defensive"
  framing: the row-vanished branch in the same method already documents a *real*
  runtime window (operator disables a source between enumerate and bump) — keep that;
  only the multi-instance justification is wrong.
- `MembershipDispatchShapeTest` and other MembershipEvent tests reference only
  UserLeft/UserJoined/BotRemoved — confirm they still compile after the removal.
