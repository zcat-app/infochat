---
id: M1-477
title: "upgrade.sh health gate: accept a running service that declares no healthcheck"
status: done
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 2
files_scope:
  - prod/scripts/upgrade.sh
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # Adding a healthcheck to the infochat-provider compose service is the
  # REJECTED alternative (it ties upgrade success to /ready=adapter-connected,
  # which can flap, and touches §7.12 readiness/liveness design). This ticket
  # fixes the gate to tolerate a no-healthcheck service instead.
  - "Adding a healthcheck to the infochat-provider compose service"
  # Probing /q/health/ready from the host for a no-healthcheck service (curl +
  # mapped-port assumptions) — out of scope; the §7.11 smoke step is the
  # operator's functional provider check.
  - "Host-side HTTP readiness probing of the Provider"
  # The ADMIN_GUIDE §Upgrade section is written AFTER this fix lands and a clean
  # end-to-end upgrade re-test passes (the originating goal). Not in this diff.
  - "ADMIN_GUIDE.md upgrade section"
  # Unchanged from M1-473/M1-476: forward-only Flyway, schema rollback stays a
  # printed manual restore step.
  - "DB schema downgrade / reverse-migration automation"
acceptance:
  - >-
    upgrade.sh wait_healthy() no longer spins to WAIT_TIMEOUT for a service that
    declares no healthcheck. When `docker inspect` reports health state "none"
    (no healthcheck), the function accepts the service (returns 0) as soon as its
    container `State.Status` is "running"; a service that DOES declare a
    healthcheck is still gated on state "healthy" exactly as before; a
    no-healthcheck service whose container is not "running" keeps polling and
    fails at the deadline (returns 1).
  - >-
    The stale wait_healthy comment asserting "both app services DO declare
    healthchecks, so this is the normal path" is corrected: the Collector
    declares a /q/health/ready healthcheck (gated on healthy) while the Provider
    declares none (gated on running), and the comment explains why a
    no-healthcheck service is accepted on running-state rather than health.
  - >-
    docs/design/07-deployment.md §7.11 step 7 (the health gate) wording is
    updated to match: the gate confirms the Collector reports healthy (its
    /q/health/ready healthcheck) and the Provider's container is running (it
    declares no compose healthcheck), rather than implying both report healthy.
  - >-
    Verification (no shell-test harness exists; M1-473): `bash -n
    prod/scripts/upgrade.sh` is clean, and the fixed wait_healthy logic is
    exercised against the live deployment — a running no-healthcheck service
    (infochat-provider) is accepted, the healthy Collector is accepted, and a
    stopped service is not accepted — recorded in the round's verification notes.
  - >-
    Full pre-existing suite (`mvn verify` from repo root) is green — script +
    doc only, no Java and no migration, so this is the no-regression baseline
    check (inert-diff, same posture as M1-476).
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/design/07-deployment.md §7.11"
  - "docs/design/07-deployment.md §7.12"
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-27
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 32
      removed: 16
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-27
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-477: upgrade.sh health gate — accept a running service that declares no healthcheck

## Context

Running the M1-476 upgrade end-to-end surfaced a latent M1-473 bug. The final
health gate is `wait_healthy infochat-collector && wait_healthy infochat-provider`,
but the `infochat-provider` compose service declares **no healthcheck** (only the
Collector does, on `/q/health/ready`). `wait_healthy` reads the Provider's health
state as "none" on every poll, never returns 0, spins the full `WAIT_TIMEOUT`
(300s), and then false-fails — triggering an unnecessary code rollback on a
deployment where both apps actually started fine. M1-476 preserved this gate
unchanged (it was in scope to preserve, not to fix), so the fix is this new
ticket. Contract: [docs/design/07-deployment.md](../../../design/07-deployment.md)
§7.11 (health gate) and §7.12 (health endpoints).

## Acceptance

See the YAML `acceptance:` list. In prose: `wait_healthy` gates a service that
declares a healthcheck on "healthy" (unchanged — the Collector), and gates a
service that declares none on "container running" (the Provider), so the upgrade
completes instead of false-failing into a rollback. The stale "both declare
healthchecks" comment is corrected and §7.11's health-gate wording is brought
into line. Verified by `bash -n` plus a live exercise of the fixed logic against
the running deployment.

## Out-of-scope

Covered by the YAML `out_of_scope:` list. Notably, adding a Provider healthcheck
is the rejected alternative — it would tie upgrade success to adapter-connection
readiness (which can flap) and pull in §7.12 orchestrator-probe design; the
surgical gate fix avoids that. The ADMIN_GUIDE §Upgrade section is deferred until
this lands and a clean end-to-end upgrade re-test passes.

## Notes

- Tradeoff: accepting the Provider on "running" means the gate confirms the
  process started, not that an adapter connected. That is acceptable — the bot is
  allowed to run degraded (an adapter may reconnect), the Collector `--wait` gate
  already covers the migration, and §7.11 step 8's smoke check (`/help`,
  `/summary`, `/status`) is the operator's functional verification.
- Adjacent code: the existing `wait_healthy` in `prod/scripts/upgrade.sh`; the
  `running_image_id` helper (M1-476) already reads `State.Status`/`compose ps -q`
  the same way.
- Ground truth observed on the live deployment 2026-06-27: `docker inspect` of
  infochat-infochat-provider-1 returns no `.State.Health` (NO-HEALTHCHECK);
  docker-compose.yml defines a healthcheck for infochat-collector (line 104,
  `/q/health/ready`) but none under infochat-provider.
