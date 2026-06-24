---
id: M1-445
title: "build: pin maven-surefire-plugin so the JUnit 5 unit suite actually runs (super-pom default 2.12.4 silently skips it)"
status: pending
created: 2026-06-24
last_updated: 2026-06-24
clarity_check:
  date:
  verdict:
  warnings: []
  blockers: []
blocked_by:
  - M1-444
files_budget: 3
files_scope:
  - pom.xml
  - infochat-provider/pom.xml
  - infochat-collector/pom.xml
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Do NOT change any test code or production code. This ticket only pins the surefire plugin version; if a newly-activated unit test were to fail (none do as of 2026-06-24 — verified 0 failures), that is a separate ticket, not a reason to weaken/disable the test or skip the pin."
  - "Do NOT add a Maven wrapper, CI config, or a zero-tests tripwire here — that toolchain hardening is M1-446 (blocked_by this ticket). This ticket is the minimal functional fix: pin surefire so the suite runs on every Maven version."
  - "Do NOT change the failsafe plugin (already pinned to 3.5.4 and working) or the surefire <configuration> blocks (collector's vertx.disableWebsockets systemPropertyVariables stays); only the surefire VERSION is being supplied."
  - "Do NOT bump other plugin versions or touch the NullAway/Error Prone compiler config."
acceptance:
  - "maven-surefire-plugin is pinned (version 3.5.4, matching the already-pinned failsafe) in the parent pom.xml <build><pluginManagement>. Root cause: no pom pins a surefire version, so it falls back to the Maven super-pom default, which on this project's Maven (3.8.7) is 2.12.4 — a version predating the JUnit Platform that discovers 0 JUnit 5 (@org.junit.jupiter.api.Test) tests and reports `Tests run: 0` while the build stays green. Failsafe is pinned to 3.5.4, so *IT tests run and mask the gap. Pinning surefire in our pom overrides the super-pom default regardless of the ambient Maven version."
  - "After the pin, `mvn -B clean verify` from the repo root RUNS the JUnit 5 unit suite (surefire test phase, non-zero count) across all modules and exits 0. Verified blast radius on 2026-06-24: provider 933 unit tests, collector 59 unit test classes, plus the SPI modules — 0 failures, 0 errors. The previously-dormant unit suite is all green-as-written."
  - "Tests that were silently never executing now execute — in particular infochat-provider's wiring tests (e.g. DoctorWiringTest, shipped by M1-439 but never run until now) appear in the surefire report with a non-zero run count."
  - "Any pom comment rendered inaccurate by the pin is corrected to match reality: infochat-collector/pom.xml's 'Surefire (test phase) remains bound by the super-pom default and picks up *Test.java automatically' (the super-pom default did NOT pick them up — that was the bug) and infochat-provider/pom.xml's failsafe comment referencing a non-existent 'surefire pin from the Maven super POM'. Comment-only edits, scoped to making the now-false statements true."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - "all tests currently green on main (the *IT failsafe suite is unaffected)"
    - "the ~1000 JUnit 5 unit tests that this pin ACTIVATES must all pass (verified 0 failures on 2026-06-24); if any fail, escalate — do not weaken them"
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-445: pin maven-surefire-plugin so the JUnit 5 unit suite actually runs

## Context

Discovered 2026-06-24 while working M1-441. The project's entire JUnit 5 **unit**
test suite (the `*Test` classes — hundreds of them) has been silently NOT running
under `mvn verify`. Mechanism:

- Every test uses JUnit 5 (`org.junit.jupiter.api.Test`).
- **No pom pins a `maven-surefire-plugin` version.** Surefire falls back to the
  Maven super-pom default. On Maven 3.8.x (this environment runs 3.8.7) that
  default is **surefire 2.12.4** (circa 2012, pre-JUnit-Platform). 2.12.4 has no
  JUnit 5 provider, so it discovers 0 tests in every `*Test` class, prints
  `Tests run: 0`, and the build passes having executed nothing.
- The `*IT` suite runs fine only because **failsafe IS pinned to 3.5.4** (in the
  provider/collector poms), which supports JUnit 5. The large green IT output
  masks the missing unit suite.

Proof it is version-caused: the same `CommandTokenizerTest` runs 0 tests under
2.12.4 and 13 (all pass) under 3.5.4. `mvn help:effective-pom` shows surefire
resolved to `<version>2.12.4</version>`.

This is environment-dependent: on a machine with Maven 3.9+ (whose super-pom
default is surefire 3.x) the unit suite DID run — commit 17a618a3 (2026-05-25)
fixes a real runtime failure in `ExportCommandHandlerTest.singlePageNoMarker`,
which can only be observed if that unit test executed. So the suite is real and
maintained; the defect is that nothing pins the toolchain, so whether the unit
suite runs depends on the ambient Maven version. Pinning surefire removes that
dependence.

## Verified blast radius

With surefire pinned to 3.5.4, a full project `mvn clean test` ran the unit suite
across all 6 modules: **0 failures, 0 errors** (provider alone: 933). The
~1000 previously-dormant unit tests are green-as-written. So this is a low-risk
activation, not a triage project. (Risk is marked `medium` only because the change
is build-wide and turns previously-inert tests into gating tests.)

## Notes

- The fix is a single plugin stanza in the parent `pom.xml`
  `<build><pluginManagement>` (sibling of the existing enforcer/compiler pins):
  `maven-surefire-plugin` version `3.5.4`. pluginManagement version applies to the
  lifecycle-bound surefire:test execution in every module.
- This also retroactively activates M1-439's `DoctorWiringTest` (a wiring test
  shipped but never run) and is the prerequisite that lets M1-441's
  `AdapterAdminPromptWiringTest` actually execute (verified 3/3 green under 3.5.4).
- `blocked_by: M1-444` — the full `mvn verify` cannot go green (this ticket's
  acceptance) while the collector reeval time-bomb is red; fix that first.
- Follow-up M1-446 hardens the toolchain (Maven wrapper + a non-empty-suite
  tripwire) so a silent skip can never recur; it is NOT required to fix the bug
  (the pin alone does that) and does not block M1-441.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-445-pin-surefire-junit5-unit-suite.md
```
