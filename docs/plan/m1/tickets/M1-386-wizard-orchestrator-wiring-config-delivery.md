---
id: M1-386
title: "wizard 6e: orchestrator step wiring (0-8) + container config delivery (compose mounts + secrets.env)"
status: pending
created: 2026-06-15
last_updated: 2026-06-15
blocked_by:
  - M1-382
  - M1-379
  - M1-383
  - M1-384
files_budget: 2
files_scope:
  - prod/setup.sh
  - docker-compose.yml
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The apps-up/verify subscripts 7-apps.sh / 8-verify.sh (M1-385) — this ticket wires the orchestrator and the container config seams those scripts depend on, not the scripts themselves.
  - The subscript internals of steps 0-6 (M1-382/383/384) — unchanged; this ticket only registers them in the orchestrator's step list and mounts the config they generate.
  - Any Java, per-module application.properties, or Dockerfile change — the bridge is a compose mount + an env source only; the §7.12.1 health-port shape is unchanged (no management interface enabled).
acceptance:
  - "prod/setup.sh's step list enumerates all wizard steps 0-8 in dependency order (steps 3-8 added to the orchestrator), so `prod/setup.sh --help` lists every step and a run drives each in turn; the stale 'Steps 3-8 ... extend this list' comment is removed (07-deployment.md §7.7.2 'Structure' — the orchestrator owns the full step list)."
  - "prod/setup.sh sources the runtime secrets.env into its environment before the step loop so each subscript's `docker compose up` resolves the compose ${INFOCHAT_*_PASSWORD} / ${INFOCHAT_LLM_API_KEY} interpolations; secrets.env is never mounted as a file into a container and no secret value is written into any committed or mounted config file (07-deployment.md §7.7.2 'Runtime config delivery', §7.3/§7.5)."
  - "docker-compose.yml mounts the wizard's runtime application.properties read-only at config/application.properties in both the infochat-collector and infochat-provider services, so the operator's quarkus.profile / infochat.llm.* / infochat.adapters / per-adapter blocks reach the running services — the Provider, whose image declares no production infochat.adapters (only %test), boots past AdapterRegistry gate 1 only via this mount (07-deployment.md §7.7.2 'Runtime config delivery')."
  - "docker-compose.yml bind-mounts each chosen adapter's identity-dir into the infochat-provider service so the adapters can read the on-disk identity material captured in step 6 and connect (07-deployment.md §7.5, §7.7.2); the M1-379 startup ordering (Collector healthy -> Provider) is preserved."
  - "`docker compose --profile prod config` is valid and shows the application.properties mount on both app services plus the identity-dir mount on the provider (grep on the config output); prod/setup.sh passes `bash -n`; mvn -B verify from the repo root exits 0."
  - "Manual end-to-end: with a runtime application.properties supplying infochat.adapters (single adapter) and secrets.env present, `prod/setup.sh --defaults` brings the prod stack up and the Provider container starts past gate 1 and reports ready once its adapter connects (pausing only for the human adapter-registration step); commit-message evidence."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/design/07-deployment.md §7.12.1 Ops-posture surfaces
  - docs/spec/deployment.md §Topology
decision_refs:
  - D46
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-386: wizard 6e — orchestrator wiring + container config delivery

## Context

The wizard was sliced one ticket per subscript file (M1-382..M1-385), and two
pieces of integration glue fell into the seams between those tickets because
each lives in a file an earlier ticket owns and every later ticket scoped that
file out:

1. **Orchestrator step registration.** `prod/setup.sh`'s step list still stops
   at steps 0-2 (M1-382). The subscripts for steps 3-6 (M1-383/384) exist on
   disk but the orchestrator never invokes them, and M1-385's 7-8 would be the
   same. The orchestrator is the single place the step sequence is registered
   (07-deployment.md §7.7.2 "Structure"), so the full 0-8 list must be wired in
   here.
2. **Container config delivery.** The `prod` app services (M1-379) receive only
   the DB-password env vars; nothing carries the wizard's generated
   `runtime/application.properties` (profile / LLM / `infochat.adapters` /
   per-adapter blocks) or the adapter identity-dirs into the containers, and
   nothing sources `secrets.env` so the compose `${INFOCHAT_*_PASSWORD}`
   interpolations resolve. As built, the Provider refuses to boot
   (`AdapterRegistry` gate 1, no `infochat.adapters`) and Postgres aborts
   (`postgres-init.sh` `${VAR:?}`, empty role passwords). The three delivery
   seams are specified in 07-deployment.md §7.7.2 "Runtime config delivery to
   the containers".

This ticket implements that design: the full step list in `prod/setup.sh`, the
`secrets.env` env source, and the compose mounts (runtime
`application.properties` + adapter identity-dirs). It is the wiring that makes
the containerized wizard boot a working stack; M1-385's `8-verify.sh` then has
a green target to probe.

Blocked on the orchestrator (M1-382), the app compose services (M1-379), and
the subscripts whose outputs it mounts and whose steps it registers (M1-383/384).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **Health-port shape is decided (no management interface).** Per the §7.7.2
  "Runtime config delivery" / §7.12.1 resolution, the containerized v1
  deployment serves `/q/health` on the main loopback HTTP port; the wizard's
  `application.properties` does NOT set `quarkus.management.enabled`. Do not add
  a management interface or change the M1-379 healthcheck.
- **Config ordinal.** The mount lands in the image working directory's external
  Quarkus config location (a `config/` dir holding the runtime properties) so it
  is read above the image-baked per-service defaults and below the explicit
  compose `environment:` overrides (datasource URL, role password) — verify the
  layering rather than assuming it.
- **Identity-dir scoping risk.** Step 6 records each adapter's `identity-dir`
  (defaults `/var/lib/infochat/simplex`, `/var/lib/infochat/signal-cli`). The
  cleanest 2-file path is a static bind-mount of those documented locations into
  the Provider. If supporting operator-overridden identity-dir paths turns out
  to require the wizard to write a compose-consumable variable (a 6-adapter.sh
  change), that exceeds files_scope — escalate rather than silently widen.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-386-*.md
```
