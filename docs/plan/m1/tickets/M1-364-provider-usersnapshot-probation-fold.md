---
id: M1-364
title: "provider: fold probation_until into the per-dispatch UserSnapshot to remove the per-inbound probation queries"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 5
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
acceptance:
  - "USER_SNAPSHOT_SQL projects probation_until alongside id, registration_state, is_banned; the UserSnapshot record carries a nullable probationUntil and an inProbation(now) helper."
  - "Step 5 of InboundRouter decides probation from the snapshot: no inProbation() SELECT on the hot path, no probationExpiry() SELECT on the blocked path (the expiry is the snapshot value), and clearIfPromoted is called only when probation_until is non-null and in the past (skipped for the NULL steady state)."
  - "ProbationCheck is reduced to the methods still needed (clearIfPromoted); the dropped methods and their tests are removed/updated."
  - "A test pins that a steady-state non-probation inbound issues no probation SELECT/UPDATE beyond the single user-snapshot SELECT, and that a probation-blocked inbound still replies with the correct unlock time sourced from the snapshot."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging (snapshot-fold + query-count assertions)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
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
