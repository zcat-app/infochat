---
id: M1-364
title: "provider: fold probation_until into the per-dispatch UserSnapshot to remove the per-inbound probation queries"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: security_relevant is false but step 5 of InboundRouter is the probation gate on the inbound message path; no access-control behavior changes (only the data source is refactored), so the flag stays false but the reviewer should double-check the gate logic."
    - "COMPLEXITY-RISK-CALIBRATED: risk: medium is defensible, but InboundRouter step 5 is the hot path for every inbound message; reviewer should explicitly check the probation gate logic."
  blockers: []
blocked_by: []
files_budget: 15
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProbationCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The probation policy itself (slow-start duration, allowed-during-probation command set) — unchanged.
  - The TOCTOU posture — the snapshot already decides is_banned the same way; probation gets the identical documented microsecond-window trade-off.
  - The just-graduated lazy-clear UPDATE — kept, but now invoked only when the snapshot shows a non-null probation_until in the past (no UPDATE for the NULL steady state).
  - ProbationCheck.inProbation and its three out-of-scope callers (GrantAdminCommandHandler, RevokeAdminCommandHandler, HelpCommandHandler) — retained and unchanged; only probationExpiry (InboundRouter-only) is dropped. These command handlers run downstream of dispatch and have no UserSnapshot, so they keep using the live query.
acceptance:
  - "USER_SNAPSHOT_SQL projects probation_until alongside id, registration_state, is_banned; the UserSnapshot record carries a nullable probationUntil and an inProbation(now) helper."
  - "Step 5 of InboundRouter decides probation from the snapshot: no inProbation() SELECT on the hot path, no probationExpiry() SELECT on the blocked path (the expiry is the snapshot value), and clearIfPromoted is called only when probation_until is non-null and in the past (skipped for the NULL steady state)."
  - "ProbationCheck drops probationExpiry — its sole main caller was InboundRouter (the blocked-path expiry read), now replaced by the snapshot's probation_until value. inProbation and clearIfPromoted are RETAINED: inProbation is still consumed by the out-of-scope GrantAdminCommandHandler / RevokeAdminCommandHandler / HelpCommandHandler, and clearIfPromoted by the step-5 lazy clear. The dropped method's tests (probationExpiry coverage in ProbationCheckTest, and the probationExpiry knob in InboundRouterProbationOrderingTest's RecordingProbationCheck / NoopProbationCheck) are removed/updated."
  - "A test pins that a steady-state non-probation inbound issues no probation SELECT/UPDATE beyond the single user-snapshot SELECT, and that a probation-blocked inbound still replies with the correct unlock time sourced from the snapshot."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging (snapshot-fold + query-count assertions)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 289
      removed: 184
escalations:
  - date: 2026-06-14
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (premise-fail surfaced during implementation, pre-review).
      Acceptance item 3 reads "ProbationCheck is reduced to the methods
      still needed (clearIfPromoted)", implying inProbation is dropped.
      But ProbationCheck.inProbation has 5 live call sites in 3 production
      handlers OUTSIDE files_scope: GrantAdminCommandHandler:225,288 /
      RevokeAdminCommandHandler:228,304 / HelpCommandHandler:250. Only
      probationExpiry is exclusively InboundRouter's. Dropping inProbation
      would break the build and force out-of-scope edits.
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (budget-breach surfaced during implementation, pre-review).
      UserSnapshot gains a 4th record component (probationUntil); the
      arity change ripples to 9 construction sites across 9 test files
      (8 mechanical ", null" + the probation rework), plus
      CountingDispatchDataSource (getTimestamp handling) and the 4
      probation-method files = 14 files total vs files_budget: 5.
      files_scope already covers all 14 (the messaging test dir + the two
      named main files); only the numeric budget is breached.
revisions:
  - date: 2026-06-14
    reason: premise-fail-refine
    snapshot:
      acceptance_item_3: "ProbationCheck is reduced to the methods still needed (clearIfPromoted); the dropped methods and their tests are removed/updated."
  - date: 2026-06-14
    reason: budget-breach-refine
    snapshot:
      files_budget: 5
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-364: fold probation_until into UserSnapshot

## Context

Deep-review v6 finding **opus-47 `07-module-infochat-provider.md` F1** (medium,
PERFORMANCE). The `InboundRouter` class javadoc commits to "one users-row SELECT
feeds steps 2-5", but step 5 issues a second `users` SELECT (`inProbation`) on
every non-banned inbound, a third (`probationExpiry`) on the blocked path, and an
UPDATE (`clearIfPromoted`) on every non-probation inbound that matches zero rows
in the NULL steady state.

**Verified at source 2026-06-14:** `USER_SNAPSHOT_SQL`
(`InboundRouter.java:219-220`) selects only `id, registration_state, is_banned`
— `probation_until` is absent; `ProbationCheck` issues the separate
`inProbation` / `probationExpiry` / `clearIfPromoted` queries. Folding
`probation_until` in is the same shape the code already applied for `is_banned`.

opus-48's provider pass surfaced different (lower) items and did not contradict
this.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The `is_banned` TOCTOU rationale in the class javadoc applies verbatim to
  probation; the timer is hours-scale, the SELECT→check window is microseconds.
