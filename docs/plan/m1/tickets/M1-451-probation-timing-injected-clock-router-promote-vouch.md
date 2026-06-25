---
id: M1-451
title: "Move probation-timing decision gates onto the injected Clock in InboundRouter / PromoteCommandHandler / VouchCommandHandler (close the audit-missed probation_until split)"
status: done
created: 2026-06-25
last_updated: 2026-06-25
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PromoteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/PromoteCommandHandlerProbationClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerProbationClockTest.java
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - "ProbationCheck — already converted by M1-450; do NOT re-touch it. This ticket only converts the three OTHER call paths that read probation_until inline and that the M1-447 audit's Cross-component note never enumerated (InboundRouter step-5 snapshot read, PromoteCommandHandler, VouchCommandHandler)."
  - "GroupAutoPromoteService and InviteCodeConsumer — already converted by M1-447; not in scope."
  - "Any change to the probation window SIZE, the slow-start tier semantics (D45), or who is placed in probation. This is a determinism refactor: under the production Clock.systemUTC() the gate decision is byte-for-byte identical to today. Changing probation behaviour is a separate ticket."
  - "The remaining low-severity inline-Instant.now() retrieval-window reads in the provider (SearchPostsTool, ListSavesTool, EligiblePostQuery, SavedCommandHandler, DigestWorker, AssetSnapshotReader) — those are the M1-447 (A) follow-up backlog, NOT probation gates, and are out of scope here."
acceptance:
  - "InboundRouter, PromoteCommandHandler, and VouchCommandHandler each obtain the current instant for their probation_until decision gate from an injected java.time.Clock (the app-wide @Produces @ApplicationScoped Clock in ThrottledAdminNotifier.systemUtcClock(); field initialised `= Clock.systemUTC()` so hand-constructed test instances stay non-null and CDI overrides at runtime, mirroring ProbationCheck from M1-450). The inline `Instant.now()` currently feeding each gate (InboundRouter step-5 at InboundRouter.java:666 passed into UserSnapshot.inProbation(Instant); PromoteCommandHandler TargetRow.inProbation() at :322; VouchCommandHandler isAlreadyPastProbation() at :274) is replaced by `clock.instant()`. The pure audit/display `reply()` timestamps (PromoteCommandHandler.java:317, VouchCommandHandler.java:382) are LEFT on their current source — they record, they do not gate (engineering-rules §9 exemption)."
  - "A new deterministic unit test InboundRouterProbationClockTest pins the Clock via QuarkusMock.installMockForType(Clock.fixed(...), Clock.class) and asserts the step-5 probation block-vs-allow decision flips exactly at the probation_until boundary against the injected instant."
  - "A new deterministic unit test PromoteCommandHandlerProbationClockTest pins Clock.fixed(...) and asserts the target's in-probation gate is decided against the injected instant at a fixed boundary."
  - "A new deterministic unit test VouchCommandHandlerProbationClockTest pins Clock.fixed(...) and asserts the past-probation permission pre-check is decided against the injected instant at a fixed boundary."
  - "All three new tests are ADDITIVE; this ticket modifies the assertions of NO pre-existing test (there is no test_plan.modifies). Under the production Clock.systemUTC() every existing provider test stays green and the gate behaviour is byte-for-byte preserved."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "InboundRouterProbationClockTest.java — pins Clock.fixed(...) and asserts the step-5 probation gate decision at a fixed probation_until boundary against the injected instant (unit; no DB needed if the snapshot is built in-test)."
    - "PromoteCommandHandlerProbationClockTest.java — pins Clock.fixed(...) and asserts TargetRow.inProbation() resolves against the injected instant at a fixed boundary."
    - "VouchCommandHandlerProbationClockTest.java — pins Clock.fixed(...) and asserts the past-probation permission pre-check resolves against the injected instant at a fixed boundary."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 518
      removed: 14
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-25
    verdict: CLEAN
    base: main
    head: m1/M1-451-probation-timing-injected-clock-router-promote-vouch
    verdict_file: docs/plan/m1/redteam/M1-451-2026-06-25.md
    out_of_model_count: 2
    note: |
      CLEAN — no threat-model gaps. Two OUT-OF-MODEL items, both pre-existing and
      unchanged by this diff (it only swaps Instant.now() for the injected clock):
      ProbationCheck.clearIfPromoted's housekeeping UPDATE still uses SQL NOW()
      (pure null-write, not a gate; out-of-scope file, M1-447 backlog), and
      isAlreadyPastProbation's equality-at-boundary semantics (pre-existing isAfter,
      harmless). Neither warrants a follow-up ticket here.
clarity_check:
  date: 2026-06-25
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-451: Probation-timing decision gates onto the injected Clock (audit-missed paths)

## Context

The 2026-06-24 injectable-`Clock` design discussion (root-caused in M1-444) requires
that any time which drives a **decision** be read from the injected
`java.time.Clock`, never inline `Instant.now()` / SQL `now()`, so it can be pinned
in tests and is never split across two clocks (app vs DB). M1-450 moved
`ProbationCheck`'s read of `probation_until` onto the injected `Clock` to "close the
probation_until app/DB split."

The M1-447 classification audit (`docs/plan/m1/now-clock-audit.md`) enumerated the
`probation_until` readers in its **Cross-component note** as only
`GroupAutoPromoteService.isEligible` and `ProbationCheck`. A deep code review on
2026-06-25 found **three additional decision-gating readers the audit missed**, each
gating the *same* `probation_until` field on inline `Instant.now()`:

- **`InboundRouter.java:666`** — step-5 reads `Instant probationNow = Instant.now()`
  and gates the block/allow decision at `:668` via
  `UserSnapshot.inProbation(probationNow)`. Step-5 deliberately **bypasses**
  `ProbationCheck` and reads the snapshot directly (comment at `:654-655`), so
  M1-450 did not cover it. `UserSnapshot.inProbation(Instant now)` is already
  parameterised on the instant.
- **`PromoteCommandHandler.java:322`** — `TargetRow.inProbation()` =
  `probationUntil != null && probationUntil.isAfter(Instant.now())`.
- **`VouchCommandHandler.java:274`** — `isAlreadyPastProbation()` =
  `!row.probationUntil.isAfter(Instant.now())`.

`probation_until` enforces the D45 slow-start tier — an authorization control —
so leaving these three on the wall clock re-opens the app/DB skew the rule forbids,
on three live authorization paths, and leaves the gates un-pinnable in tests
(the date-boundary time-bomb class M1-398 / M1-400 / M1-444 each fixed one of).

## Acceptance

See the YAML `acceptance:` list. In short: the three classes read the
probation-gate instant from the injected `Clock` (whole-path, no two-clock split);
audit/record `reply()` timestamps stay where they are; each path gains a new
fixed-`Clock` test asserting the gate boundary; full suite green; behaviour
byte-for-byte preserved under `Clock.systemUTC()`.

## Out-of-scope

See the YAML `out_of_scope:` list. `ProbationCheck` (M1-450),
`GroupAutoPromoteService` / `InviteCodeConsumer` (M1-447) are already done. No
probation-window behaviour change. The provider's low-severity retrieval-window
`Instant.now()` reads are the separate M1-447 (A) follow-up backlog.

## Notes

- Reference implementation: M1-450 (`ProbationCheck`) and M1-444
  (`ReEvaluationJob`). Pattern: `@Inject Clock clock = Clock.systemUTC();` (the
  initializer keeps hand-built test instances non-null; CDI injection overrides
  it), sample `clock.instant()` once where the gate is evaluated, pass it in; pin a
  fixed `Clock` in the test via
  `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`.
- The Clock producer is `ThrottledAdminNotifier.systemUtcClock()`
  (`@Produces @ApplicationScoped`, returns `Clock.systemUTC()`).
- These are three independent call paths, not one shared component — but each one
  individually splits an app-side `probation_until` write (now produced under the
  app clock by the converted writers) against an app-side read on the wall clock,
  which is exactly the skew §9 prohibits.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-451-probation-timing-injected-clock-router-promote-vouch.md
```
