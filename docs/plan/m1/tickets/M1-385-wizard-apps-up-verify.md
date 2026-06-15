---
id: M1-385
title: "wizard 6d: 7-apps.sh (ordered prod up) + 8-verify.sh (health smoke)"
status: pending
created: 2026-06-15
last_updated: 2026-06-15
blocked_by:
  - M1-382
  - M1-379
  - M1-386
files_budget: 2
files_scope:
  - prod/scripts/7-apps.sh
  - prod/scripts/8-verify.sh
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The orchestrator/steps 0-2 (M1-382), postgres/llm/bootstrap (M1-383), adapter (M1-384).
  - Declaring the app compose services — they already exist (M1-379); these subscripts only start and probe them.
acceptance:
  - "7-apps.sh runs `docker compose --profile prod up -d` for the Collector, waits until it is healthy, then starts the Provider — encoding the §Topology startup ordering (only the Collector migrates in production)."
  - "8-verify.sh polls /q/health on each app's main loopback HTTP port (collector 8080 / provider 8081; the §7.12.1 shipped-default shape) via `docker compose exec` until ready or a timeout, then prints a green/red summary naming any unhealthy component, exiting non-zero on timeout (manual: run against an up stack reaches green — commit-message evidence)."
  - "Running `prod/setup.sh --defaults` end-to-end (laptop profile, Ollama, a single adapter) reaches a green 8-verify (manual end-to-end procedure; commit-message evidence)."
  - "Both scripts obey the §7.7.1 shape (set -euo pipefail, echo, exit-code passthrough, -h/--help) and pass `bash -n`. mvn -B verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/spec/deployment.md §Topology
decision_refs:
  - D41
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-385: wizard 6d — apps up + verify

## Context

Final wizard slice (`07-deployment.md` §7.7.2 steps 7–8). `7-apps.sh` brings up
the containerized Collector and Provider in the §Topology order (Collector
healthy — it runs Flyway — then Provider). `8-verify.sh` polls `/q/health` on
each app's main loopback HTTP port (via `docker compose exec`, §7.12.1) and
prints a green/red summary so a tester sees the deployment came up. Reaching a
green verify via `prod/setup.sh --defaults` is the end-to-end proof the whole
wizard works.

Blocked on the orchestrator (M1-382), the app compose services (M1-379), and
the container config-delivery wiring (M1-386).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- The Provider's readiness reports ready when at least one adapter is connected
  (`07-deployment.md` §Bootstrap behavior); 8-verify should treat that as green
  and surface per-adapter degradation separately rather than failing outright.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-385-*.md
```
