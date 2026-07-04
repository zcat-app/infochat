---
id: M1-558
title: Prometheus metrics export — registry dependency makes /q/metrics real (F-live-7)
status: pending
created: 2026-07-04
last_updated: 2026-07-04
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/pom.xml
  - infochat-provider/pom.xml
  - infochat-provider/src/test/java/app/zcat/infochat/provider/metrics/MetricsEndpointIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/metrics/MetricsEndpointIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "GET /q/health/llm (design 07-deployment.md:1058) — a separate endpoint
    with its own probe semantics and orchestrator-isolation rules; needs its
    own ticket and possibly a design refinement, not a rider here"
  - shipping Prometheus/Alertmanager/Grafana/Loki containers in
    docker-compose.yml — design 07-deployment.md §7.13 explicitly makes the
    observability stack operator-deployed ("Nothing in this subsection adds
    configuration"); changing that is a design amendment, not this ticket
  - adding, renaming, or wiring any meter — AdapterMetrics/LlmMetrics and
    the collector eval counters already register everything; this ticket
    only makes the existing registry scrapeable
  - alert rules, dashboards, scrape configs (operator-repo material per
    design §7.13)
acceptance:
  - io.quarkus:quarkus-micrometer-registry-prometheus is declared in
    infochat-provider/pom.xml and infochat-collector/pom.xml (BOM-managed,
    no explicit version), each with the same one-line WHY comment style the
    existing quarkus-micrometer entries carry — naming that micrometer-core
    alone registers meters nothing exports (F-live-7).
  - A new provider IT asserts GET /q/metrics on the management interface
    returns 200 with a Prometheus text body containing
    adapter_connection_status (the gauge AdapterMetrics.bindAdapter
    registers; the §7.14 runbook and live-e2e README already reference it
    by name).
  - A new collector IT asserts GET /q/metrics returns 200 with a
    non-empty Prometheus text body (llm_* meters are lazily registered per
    call, so the collector assertion pins the endpoint + format, not a
    specific meter).
  - mvn verify is green.
test_plan:
  adds:
    - MetricsEndpointIT (provider) — endpoint exists, prometheus format,
      adapter_connection_status present
    - MetricsEndpointIT (collector) — endpoint exists, prometheus format
  preserves:
    - the full pre-existing suite (dependency addition only; no meter or
      code-path changes)
spec_refs:
  - docs/spec/deployment.md §Health and observability
  - docs/spec/llm.md §Bounded concurrency and observability
decision_refs: []
---

## Context

Found live 2026-07-04 (F-live-7, live-e2e HANDOFF): `/q/metrics` returns
404 on both services. Every module ships `quarkus-micrometer` (the CDI
`MeterRegistry` that `AdapterMetrics`, `LlmMetrics`, and the collector eval
counters register into), but no module declares
`quarkus-micrometer-registry-prometheus` — in Quarkus the export endpoint
comes from the registry extension, so the meters are write-only. Confirmed
in the running provider image: `quarkus-app-dependencies.txt` lists
micrometer-core/commons/observation and no prometheus artifact.

The endpoint is promised by design (`07-deployment.md:1059` "GET /q/metrics
— Micrometer/Prometheus") and load-bearing for spec deployment.md §Health
and observability: per-adapter connection state "is exposed separately via
metrics so an operator can distinguish 'fully healthy' from 'degraded'" —
currently there is no way to read it. The §7.14 runbook's very first
diagnostic ("which `adapter.connection.status{adapter}` is 0?") is
unanswerable today. Green in CI because no test asserts the endpoint
exists — which is why each IT here pins it.

## Exposure note (why security_relevant stays false)

The metrics payload discloses topology (adapter names, per-source labels)
to any caller who can reach the port — the same class of disclosure spec
deployment.md §Health and observability already analyzes for the health
endpoints, with the same lever: the management interface binds to loopback
by default (design 07-deployment.md:1121 pins `quarkus.management.*` to
loopback in the canonical compose file). This ticket adds no new exposure
beyond what the spec already documents and accepts for that interface.
