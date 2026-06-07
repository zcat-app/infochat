---
id: M1-201
title: "Ops hardening: drop infochat-dev password fallbacks + readiness probes"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/pom.xml
  - infochat-collector/pom.xml
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health
  - infochat-collector/src/test/java/app/zcat/infochat/collector/startup
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "the second half of the readiness rule — \"Per-adapter connection state is exposed separately via metrics\" — the metrics surface (micrometer) is a separate lift, deliberately deferred to the verification/observability backlog (no ticket filed yet); name it in the readiness check's javadoc so the gap is visible"
  - the LLM probe ("a failing provider surfaces as a degraded readiness signal but does not fail readiness outright") — same deferral
  - the bootstrap-admin @Startup bean, its fail-fast legs, and startup ordering — M1-178's; this ticket's readiness check must not duplicate or race its gates
  - docker-compose's POSTGRES_PASSWORD dev default — the compose file is the spec-sanctioned local-development shape, untouched
  - liveness endpoints and alerting beyond readiness
acceptance:
  - "The production-shaped datasource password keys in both services resolve only from the environment: with the corresponding env var unset, the service fails startup fast instead of connecting with a baked-in dev password — named tests (config-shape or startup IT) assert no default fallback remains on the base-profile keys (today provider application.properties:23 and collector :16/:22 default to infochat-dev; docs/design/07-deployment.md §Environment variables marks INFOCHAT_DB_PASSWORD / INFOCHAT_COLLECTOR_PASSWORD / INFOCHAT_PROVIDER_PASSWORD as required with no default, and its reference properties show ${VAR} with no fallback). %dev and %test profiles may carry explicit dev-only values"
  - "Per docs/spec/deployment.md §Bootstrap behavior on startup — \"The Provider's readiness probe reports ready when **at least one** enabled adapter is connected (because Provider can serve traffic via that adapter); not-ready when zero adapters are connected.\" — named tests assert the Provider readiness check reports DOWN with zero connected adapters and UP with one of two connected"
  - "Per docs/spec/deployment.md §Health and observability — \"**Readiness** — service is fully bootstrapped (Flyway done, all required startup beans up).\" — both services expose a readiness endpoint: a named IT per service asserts the readiness path responds and reflects bootstrap state"
  - "Per docs/spec/deployment.md §Bootstrap behavior on startup — \"A bean failure during startup refuses the service start (Quarkus default). The readiness probe stays unhealthy until every required startup bean is up.\" — the readiness wiring does not mask startup-bean failures: existing startup-failure tests stay green"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/health
    - infochat-collector/src/test/java/app/zcat/infochat/collector/startup
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/deployment.md §Health and observability
decision_refs:
  - D46
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-201: Ops hardening: drop infochat-dev password fallbacks + readiness probes

## Context

Two ops gaps (unified findings P20, P21 —
`deep-code-review/v2/UNIFIED.md` §2; both gpt-55 uniques):

1. **Dev password fallbacks in production-shaped keys (P20, med).**
   `${INFOCHAT_PROVIDER_PASSWORD:infochat-dev}` (provider :23) and
   `${INFOCHAT_COLLECTOR_PASSWORD:infochat-dev}` /
   `${INFOCHAT_DB_PASSWORD:infochat-dev}` (collector :16/:22) mean a
   deployment that forgets an env var silently runs with a publicly
   known password instead of failing fast. The design env-var table
   marks all three required-with-no-default. (Provider :48 is already
   %test-scoped — fine.)
2. **No readiness implementation (P21, med).** Zero hits for
   smallrye-health / HealthCheck / @Readiness across both services
   (re-verified at draft time), despite the spec's readiness rule
   (Provider ready iff ≥1 adapter connected) and §Health and
   observability committing both services to a readiness probe.

`files_scope` includes a new `health` package in the Provider for the
adapter-aware check; the Collector side is expected to need only the
extension default (Flyway/startup-beans readiness) plus an IT.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T24 under `deep-code-review/v2/` (gpt S1, R2).
- **Dependency approval required:** the natural readiness mechanism is
  the `quarkus-smallrye-health` extension in both service poms. Per the
  project rule, dependency additions need explicit user approval before
  implementation — this is flagged in the batch summary; the alternative
  is a hand-rolled endpoint (more code, no new dep). Do not start the
  ticket before the dependency question is settled.
- Adapter "connected" state: M1-185 (transport reconnect) is
  reshaping adapter connection lifecycle — if both tickets are in
  flight, coordinate the definition of "connected"; M1-185 owns the
  reconnect semantics.
- The readiness rule quotes were re-anchored against
  docs/spec/deployment.md at draft time (the batch prompt requires
  fresh quotes, not the report's paraphrase).
