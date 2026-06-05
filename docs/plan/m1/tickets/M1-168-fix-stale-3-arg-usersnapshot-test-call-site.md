---
id: M1-168
title: "Fix stale 3-arg UserSnapshot test call site"
status: done
created: 2026-06-05
last_updated: 2026-06-05
blocked_by: []
files_budget: 1
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - every other UserSnapshot call site (all verified 2-arg already, including InboundRouterProbationOrderingTest)
  - InboundRouter.java and the UserSnapshot record itself (the M1-146 two-component shape is correct)
  - any assertion or behavioral change in InboundRouterIntakeOrderingTest beyond the single constructor argument
  - the workflow merge-gate amendment closing the parallel-merge verification gap (separate process commit)
acceptance:
  - "InboundRouterIntakeOrderingTest.groupChatMessageWithVanishedGroupRowIsSilentlyDroppedNotThrown constructs UserSnapshot with the two surviving components (UUID id, String registrationState) — the stale boolean argument M1-146 obsoleted is dropped, constructor-argument-only"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  preserves:
    - all tests on main (constructor-argument-only repair; no assertion edits)
spec_refs:
  - docs/spec/architecture.md §Architectural principles
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 10
      removed: 7
overrides:
  - date: 2026-06-05
    objection: |
      Parallel-start precondition: M1-143 is in-progress and its files_scope
      blankets infochat-provider/src/{main,test}/java/app/zcat/infochat/provider,
      which contains this ticket's single target file — files_scope disjointness
      is not mechanically provable, so /m1-tick start would refuse.
    user_justification: |
      User-authorized 2026-06-05. Main's tip (96d327f) does not compile —
      M1-146 removed UserSnapshot.isBanned while parallel-in-flight M1-155
      added a 3-arg call site M1-146's fork could not see; every ticket
      including M1-143 is blocked on this repair. Actual disjointness is
      verified at the diff level: M1-143's working diff touches only
      provider/group/* plus its own lifecycle files; this ticket touches one
      line in provider/messaging/ test code M1-143 never modifies. Backstop:
      M1-143 fast-forwards onto post-merge main and re-runs full mvn verify
      before its review, closing the exact unverified-combination gap that
      broke main.
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
---

# M1-168: Fix stale 3-arg UserSnapshot test call site

## Context

Main's tip (96d327f) fails `mvn verify` at provider test-compile: M1-146
dropped the dead `UserSnapshot.isBanned` record component and updated every
call site visible in its worktree, but M1-155 — merged 4 minutes earlier from
a sibling worktree forked off the same pre-M1-155 base — had added one more
3-arg call site at `InboundRouterIntakeOrderingTest.java:467`. The textual
merge was clean (non-overlapping hunks); the combination does not compile.
Every in-flight ticket is blocked on a green root verify until this lands.

## Acceptance

See frontmatter. Drop the stale middle `false` argument from the single
`new InboundRouter.UserSnapshot(UUID.randomUUID(), false, "vouched")` call in
`groupChatMessageWithVanishedGroupRowIsSilentlyDroppedNotThrown` so it matches
`record UserSnapshot(UUID id, String registrationState)`; repo-root
`mvn -B clean verify` exits 0.

## Out-of-scope

See frontmatter. This ticket modifies the pre-existing test
`InboundRouterIntakeOrderingTest` — **authorized modification**:
constructor-argument-only at the single stale call site, restoring compilation
against the M1-146 record shape. No assertion, setup, or behavioral edit of
any kind; the test's M1-155 intent (vanished-group-row silent drop) is
untouched. The record definition, all other call sites (verified 2-arg), and
the process-level merge-gate fix stay out.

## Notes

- Root cause is a semantic merge conflict between sanctioned-parallel tickets,
  not a defect in either diff: each was green against its own base, and no
  gate re-verified the combined tree (M1-155 merged 15:26, M1-146 15:30 —
  less than one verify wall apart).
- `javac` reported exactly one error; `InboundRouterProbationOrderingTest`'s
  multiline `UserSnapshot` call already passes 2 args. The full root verify
  this ticket requires doubles as the audit that no second latent collision
  hides in modules the failed build skipped.
