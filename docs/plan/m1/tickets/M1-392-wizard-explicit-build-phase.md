---
id: M1-392
title: "wizard: build the app images in an explicit phase before the step-7 readiness wait; raise the doctor disk floor for the build"
status: done
created: 2026-06-16
last_updated: 2026-06-17
clarity_check:
  date: 2026-06-17
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 2
files_scope:
  - prod/scripts/7-apps.sh
  - prod/scripts/0-doctor.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Collector-before-Provider startup ordering and the healthcheck definitions — unchanged.
  - The doctor port / tool-preflight changes (M1-390) — separate ticket touching the same file; coordinate but do not duplicate.
acceptance:
  - "7-apps.sh runs an explicit `docker compose --profile prod build infochat-collector infochat-provider` (or `up --build` issued separately from the readiness `--wait`) before the `up -d --wait` gate, so a build failure surfaces as a build error distinct from a health-check timeout."
  - "0-doctor.sh's MIN_FREE_DISK_GB is raised to a value that accounts for the JDK build image + the Maven dependency cache + at least one local model; the chosen number and its rationale are stated in a comment."
  - "prod/scripts/7-apps.sh and prod/scripts/0-doctor.sh pass `bash -n`; `mvn -B verify` exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 33
      removed: 10
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-392: explicit image-build phase before the readiness wait

## Context

Re-verified at source 2026-06-16. `7-apps.sh`'s first command is
`docker compose --profile prod up -d --wait --wait-timeout 300
infochat-collector` — and on first run this is also the **first time the
images build**. The build is a cold multi-module Maven reactor build (two
`mvn -am clean install` runs in the Dockerfiles, downloading the full
dependency tree), which can take well over five minutes on a fresh host.

Building implicitly underneath a readiness `--wait` conflates a build failure
with a health-check timeout and makes step 7 fragile on slow/cold hosts.
Splitting an explicit `build` phase from the `up --wait` gate makes failures
attributable and the wait meaningful. The doctor's 10 GB free-disk floor
(`MIN_FREE_DISK_GB=10`) also predates this build (JDK build image + Maven cache
+ model pulls).

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-392-*.md
```
