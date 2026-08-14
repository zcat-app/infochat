---
id: M1-831
title: "Doctor fails rootless Docker hosts without linger"
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  Probe (RED on main): `grep -n 'loginctl' prod/scripts/0-doctor.sh` prints
  nothing — the wizard's preflight never checks linger, so on a rootless-
  Docker host with Linger=no the wizard passes a host whose stack dies
  wholesale on the next user logout (logout stops user@<uid>.service →
  rootless dockerd SIGKILLed → every container down; setup-hurdles.md item
  H, 2026-08-12, both digest windows missed and unrecoverable). Test:
  DoctorWiringTest.rootlessDockerWithoutLingerFailsWithEnableLingerRemedy
  (to-be-written — `start` adds the method and runs it RED on main before
  any fix code, workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/lifecycle-reboot-resilience.md
blocked_by: []
files_scope:
  - prod/scripts/0-doctor.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/DoctorWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any WARN tier or interactive prompt in 0-doctor.sh — doctor's contract
    is prompt-free aggregate FAIL reporting (`--defaults` is a no-op,
    0-doctor.sh:35-36); the FAIL's remedy line is the offer (analysis
    options 3/4).
  - Enabling linger on the operator's behalf from any script — the remedy
    names the one host command; running it stays an operator action.
  - Restart-policy changes (M1-830) and the full-stack verb (M1-832).
  - The 2026-08-13 ollama/circuit-breaker surfacing addendum (batch E).
  - docs/spec/** — no spec promise changes (spec-first analysis: compose
    and wizard internals live in design notes).
acceptance:
  - "REPRODUCTION, now passing: DoctorWiringTest.rootlessDockerWithoutLingerFailsWithEnableLingerRemedy (to-be-written at start) — controlled-PATH drive (existing harness, DoctorWiringTest.java:161-194) with a fake docker reporting rootless and a fake loginctl reporting Linger=no: asserts non-zero exit, a failure entry naming linger AND the `loginctl enable-linger` remedy, and the absence of the all-passed line (FAILURE-MODE: a doctor that reports nothing on this host class fails the test — that silence is exactly the 2026-08-12 incident's enabler)."
  - "FAILURE-MODE, never-silently-pass (analysis P3; M1-439's load-bearing rule, in-repo precedent 0-doctor.sh:96-104): DoctorWiringTest.rootlessDockerWithoutLoginctlReportsCheckUnverifiable — rootless daemon, loginctl absent from the controlled PATH: asserts a failure entry reporting the linger check UNVERIFIABLE (not a pass, not a skip) with its own remedy (install loginctl / a systemd host), and non-zero exit."
  - "No false positive on rootful hosts (analysis P3 — the VPS scenario of docs/spec/deployment.md §Deployment scenarios runs system dockerd, which survives logout): DoctorWiringTest.rootfulDockerSkipsTheLingerCheck — non-rootless daemon, Linger=no: asserts the all-passed line and exit 0 with NO linger entry."
  - "Aggregate contract preserved (M1-439): DoctorWiringTest.lingerFailureAggregatesWithOtherFailuresInOneReport — rootless + Linger=no + port 5432 busy: ONE run reports BOTH failures, each with its remedy. The three pre-existing DoctorWiringTest drives stay green UNMODIFIED (§8: no pre-existing assertion moves; the new fakes are additive only — analysis P8)."
  - "Remedy honesty (analysis P7): the failure text states the mechanism — logout stops the user session, which kills rootless dockerd and every container — not just the command; probe: the reproduction drive's output assertion includes the mechanism phrase (e.g. 'logout' and 'rootless')."
  - "Design-note sync: docs/design/07-deployment.md §7.7.2's step-0 table row (:792) names the rootless/linger check among doctor's checks. Probe: `sed -n '790,794p' docs/design/07-deployment.md | grep -n 'linger'` hits."
  - "`bash -n prod/scripts/0-doctor.sh` clean; mvn verify from repo root is green."
test_plan:
  adds:
    - >-
      DoctorWiringTest.rootlessDockerWithoutLingerFailsWithEnableLingerRemedy
      (reproduction), .rootlessDockerWithoutLoginctlReportsCheckUnverifiable,
      .rootfulDockerSkipsTheLingerCheck,
      .lingerFailureAggregatesWithOtherFailuresInOneReport — all on the
      existing controlled-PATH harness plus a new additive fake `loginctl`
      and a rootless knob on the fake docker.
  preserves:
    - all tests currently green on main
    - >-
      DoctorWiringTest's three existing drives and its existing fakes are
      NOT modified — the new scenarios add a fake loginctl and a new
      FAKE_ROOTLESS-style env knob; no pre-existing assertion, fake body,
      or env var is edited (§8 test-modification: nothing to authorize
      because nothing pre-existing moves).
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-831: Doctor fails rootless Docker hosts without linger

## Context

The prod stack runs on rootless Docker inside a desktop user session. With
`Linger=no`, logout stops `user@<uid>.service` and SIGKILLs rootless dockerd
— every container dies, and on 2026-08-12 that ate both group-digest windows
(skip-not-catch-up by design, so the day's digest was unrecoverable;
setup-hurdles.md item H). The host fix (`loginctl enable-linger`) is a
one-liner, but linger is invisible until it bites: nothing in the wizard
checks it, so a first-time operator on a rootless host gets a green doctor
and a time-bomb. Shared analysis: `analysis_ref:`.

## Root cause

Verified: `grep -n 'loginctl\|linger' prod/scripts/0-doctor.sh` returns
nothing — the preflight checks OS, daemon, compose, tools, ports, disk
(:59-136) but never the user-session property that decides whether a
rootless stack survives logout. The host-side fix is already applied and
verified on prod (`Linger=yes`); the repo-side gap is this check. Two
ASSUMPTIONS the implementor pins at start (analysis P3): A1 — the rootless
marker in `docker info` output (SecurityOptions / DockerRootDir under
/run/user); pick one probe shape and steer the fake docker to it. A2 —
`loginctl show-user "$USER" -p Linger` prints `Linger=yes|no`, and
enable-linger for one's own user usually needs no sudo (remedy may name the
sudo fallback).

## Pitfalls

Numbered consistently with the analysis document.

- P3: conditionality + never-silently-pass — an unconditional FAIL
  false-positives the rootful VPS class (system dockerd survives logout;
  spec/deployment.md §Deployment scenarios); a rootless host WITHOUT
  loginctl (non-systemd) must report the check UNVERIFIABLE as a failure
  entry, never a silent pass (M1-439's rule; the `ss`-absent precedent at
  0-doctor.sh:96-104).
- P7: remedy honesty — the text names the mechanism (logout → user session
  stops → rootless dockerd dies → all containers die) per the wizard
  contract's actionable-remedy rule (07-deployment.md §7.7.2 step 0, :792);
  and it never promises digest recovery (missed windows are
  skip-not-catch-up by design — spec behavior, not relitigated).
- P8: additive test harness only — the existing three drives, fakes, and
  env knobs stay untouched (§8); new coverage arrives as a new fake
  loginctl + a new rootless knob.

## Approach

- **Files to touch:** `files_scope` (one script, one test class, one
  design-doc table row).
- **Steps, in order:**
  1. Write the four new DoctorWiringTest drives (the reproduction's
     to-be-written method plus the three failure-mode drives) — run RED on
     main (workflow §0). Add the fake `loginctl` (env-steered Linger value,
     presence/absence by file creation — the existing fakes' pattern) and a
     rootless knob on the fake docker's `info` output.
  2. Add the check to 0-doctor.sh in the M1-439 accumulate-and-report
     shape: skip silently when the daemon is unreachable (already its own
     failure) or rootful; when rootless and loginctl absent → record the
     UNVERIFIABLE failure with remedy; when rootless and Linger≠yes →
     record the FAIL with the mechanism-named `loginctl enable-linger`
     remedy. Joins `FAILURES[]` — never short-circuits (P8 contract).
  3. Update the §7.7.2 step-0 table row (:792) to name the new check.
  4. `bash -n`, then `mvn verify`.
- **Controls to preserve (§10):** the aggregate-report contract (every
  check runs; all failures in one report; exit non-zero iff any failed) and
  the never-silently-pass rule are the controls of this path — pinned by
  the three existing drives plus the new aggregation drive, all green.
- **Pitfall→mitigation:** P3→step 2's three-way branch + acceptance items
  2–3; P7→step 2's remedy text + acceptance item 5; P8→step 1's
  additive-only harness + acceptance item 4.

## Definition of done

On a rootless host with Linger=no the doctor fails, naming linger, the
mechanism, and the enable-linger remedy; on a rootless host without
loginctl the check reports UNVERIFIABLE as a failure; on a rootful host the
check is silent and the doctor passes; multiple failures still aggregate in
one run; the step-0 design row names the check; no pre-existing test moved;
suite green.

## Verification

- P3 → acceptance item 2 — FAILURE-MODE: a rootless host whose linger state
  cannot be probed (loginctl absent) must fail as unverifiable; a
  skip-and-pass mutation fails the test. And item 3 — FAILURE-MODE: an
  unconditional linger check false-positives the rootful VPS class; the
  rootful + Linger=no drive must exit 0 with the all-passed line.
- P7 → acceptance item 5 — FAILURE-MODE: a remedy that never names the
  logout → rootless-dockerd mechanism fails the output assertion.
- P8 → acceptance item 4 (aggregation drive — the linger failure must
  appear alongside, never replace, the other failures — plus the three
  pre-existing drives unmodified).
- Reproduction → item 1 (a doctor with no linger check fails it RED).
- Docs sync → item 6's scoped grep over the step-0 row.
- Regression → item 7.

## Out-of-scope

Named in `out_of_scope`: any warn tier or prompt (doctor is prompt-free by
contract — analysis options 3/4 rejected); auto-enabling linger (host
config stays an operator action; the remedy names the command); the
restart-policy port (M1-830) and the stack verb (M1-832); the circuit-
breaker surfacing addendum (batch E); spec text (no promise changes).
No pre-existing test is modified — the harness extension is additive fakes
only, so no §8 authorization is needed.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-831-lifecycle-reboot-resilience-2.md
```
