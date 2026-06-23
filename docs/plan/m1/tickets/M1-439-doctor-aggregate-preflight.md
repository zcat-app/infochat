---
id: M1-439
title: "Aggregate, self-remediating preflight: report all unmet checks at once"
status: done
created: 2026-06-23
last_updated: 2026-06-24
blocked_by: []
files_budget: 5
files_scope:
  - prod/scripts/0-doctor.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/DoctorWiringTest.java
  - SETUP_GUIDE.md
  - docs/design/07-deployment.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "No version floors/pins for Docker, Compose, or any tool. Gating stays presence/capability-based: the Compose gate remains `docker compose version` succeeding, which already excludes the legacy v1 `docker-compose` (hyphen). The only version-sensitive feature used (`docker compose up --wait --wait-timeout`, 7-apps.sh) shipped in Compose 2.1.1 / 2021 and is universal on any v2 install, so a numeric floor adds brittle output-parsing for ~zero real-world benefit (operator-confirmed direction)."
  - "No NEW checks beyond the existing set made dependency-aware. No memory/CPU check, no not-running-as-root check — possible follow-ups, not this ticket."
  - "Does not change WHERE the doctor runs (stays setup.sh step 0) or the orchestrator's resume/state model (a failed doctor is still never mark_done'd, so a fixed-and-rerun re-checks)."
  - "Does not de-duplicate the SETUP_GUIDE.md troubleshooting table; only the one prereq sentence (line 44) is updated. The actionable remediation now also lives in the doctor output, but collapsing the table is a separate follow-up."
acceptance:
  - "prod/scripts/0-doctor.sh runs ALL of its checks (Linux host, Docker daemon reachable, Docker Compose v2 plugin, required tools openssl/ss/curl/df, port 5432 free, >=15 GB free disk) and ACCUMULATES failures rather than exiting at the first FAIL. It prints every failure, then exits non-zero iff at least one check failed; on all-pass it prints the existing success line `doctor: all preflight checks passed.` and exits 0."
  - "Each failure line carries an actionable remediation (the fix, not just the symptom): Docker daemon -> install (https://docs.docker.com/engine/install/) + start (e.g. sudo systemctl start docker) + the docker-group fix (sudo usermod -aG docker $USER, then log out/in); Compose -> install the v2 plugin (e.g. sudo apt-get install docker-compose-plugin) and note the legacy v1 `docker-compose` hyphen form is insufficient; missing tool -> an install hint; port 5432 busy -> how to free it (e.g. a host Postgres); low disk -> free space or move the Docker root."
  - "Dependency ordering is preserved WITHOUT false passes: if `ss` is absent the port check is reported as unverifiable (NOT silently passed — the 0-doctor.sh:67 hazard); if the Docker daemon is unreachable the disk check still runs against `/` and states the Docker root is unknown rather than silently passing."
  - "No version pinning is introduced for Docker, Compose, or any tool (per out_of_scope item 1); the Compose check remains presence/capability via `docker compose version`."
  - "0-doctor.sh remains wizard step 0: setup.sh STEPS is unchanged and the doctor still has no side effects, so a resume re-runs it from the top until it passes."
  - "A new DoctorWiringTest drives the REAL prod/scripts/0-doctor.sh via ProcessBuilder with fake docker/ss/df binaries on PATH (the SwitchLlmWiringTest / SimpleXProvisioningWiringTest precedent), asserting: (a) multiple simultaneous failures are ALL reported in one run; (b) each failure carries its remediation string; (c) an all-good environment exits 0 with the success line; (d) an absent `ss` reports the port check as unverifiable, not passed."
  - "SETUP_GUIDE.md:44 is updated so the prereq sentence reflects the all-at-once report (it currently says the wizard 'tells you exactly which one is missing'; after this change it lists everything that is missing)."
  - "docs/design/07-deployment.md §7.7.2 step-0 description notes the doctor reports all unmet checks at once with remediation."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/DoctorWiringTest.java — drives prod/scripts/0-doctor.sh with fake docker/ss/df on PATH; asserts all-failures-at-once, remediation strings present, all-good exit 0, and ss-absent => port check unverifiable not passed."
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 320
      removed: 43
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-23
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-439: aggregate, self-remediating preflight

## Context

The 2026-06-23 setup audit found that `0-doctor.sh` (wizard step 0) is a
real gate, but with two operator-facing weaknesses that caused real
pushback:

1. **Fail-on-first.** It exits at the first unmet check, so an operator
   missing Docker *and* the Compose plugin *and* a tool fixes one,
   re-runs, hits the next, re-runs — the round-trip loop. It should run
   every check and report all failures in one pass.
2. **Symptom, not remedy.** Messages name what is wrong ("Docker Compose
   v2 not available") but not what to type. The actionable fixes live
   only in the `SETUP_GUIDE.md` troubleshooting table, disconnected from
   the failure. The doctor itself should print the remedy.

The Compose-plugin check already exists (`0-doctor.sh:60-64`,
`docker compose version`); the gap an operator hit was it being masked
behind an earlier fail-first exit and carrying no install instruction —
both fixed here.

**No version pinning.** Operator-confirmed: do not pin a Docker version,
and apply the same stance to Compose and the tools. Presence/capability
gating only. The existing `docker compose version` check already rejects
legacy v1; the one version-sensitive feature (`--wait-timeout`) is
universal on any v2 install. See out_of_scope item 1.

## Implementation notes (verified 2026-06-23)

- `0-doctor.sh` currently exits at the first FAIL; rewrite to collect
  failures into a list and print a consolidated report at the end.
- Preserve the two real dependencies the current ORDER encodes: the port
  check greps `ss` with `2>/dev/null` (absent `ss` => false "ports free",
  flagged at `0-doctor.sh:67`); the disk check derives its filesystem
  from `docker info --format DockerRootDir` (falls back to `/`). In
  aggregate mode these must become explicit skips/unverifiable states,
  never false passes.
- `0-doctor.sh` has **no** existing test (grep verified: no Java source
  references `0-doctor.sh`/`preflight`/`MIN_FREE_DISK`). The new
  DoctorWiringTest follows the established prod-script wiring precedent —
  SwitchLlmWiringTest and SimpleXProvisioningWiringTest both drive a real
  `prod/` shell script via `ProcessBuilder` with fakes on `PATH`. Module
  placement: infochat-provider (the user-facing service the doctor
  guards); the implementer may relocate via escalate->refine if a cleaner
  home exists.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-439-doctor-aggregate-preflight.md
```
