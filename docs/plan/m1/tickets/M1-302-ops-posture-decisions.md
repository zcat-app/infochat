---
id: M1-302
title: "Ops posture: Stage-2 fail-open default, readiness topology exposure (decisions)"
status: done
created: 2026-06-11
last_updated: 2026-06-13
escalations:
  - date: 2026-06-12
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Round-1 review verdict was APPROVE (all checks PASS). Escalation
      trigger is the pre-commit /redteam audit (verdict file
      docs/plan/m1/redteam/M1-302-2026-06-12.md): 2 low INFO-LEAK
      findings — (1) binding guidance is docs-only, no shipped loopback
      bind default or pin; (2) the "nothing else" payload-pin claim
      covers only the messaging-adapters check while the unauthenticated
      readiness aggregate also carries the auto-registered datasource
      check. User opened the lifecycle escalation to remediate both
      in-ticket.
revisions:
  - date: 2026-06-12
    reason: redteam-finding refine (RT-1 shipped loopback bind + pin; RT-2 aggregate payload truth + pin; out-of-model security.md health-surface extension)
    snapshot:
      files_budget: 8
      files_scope:
        - infochat-collector/src/main/resources/application.properties
        - infochat-provider/src/main/java/app/zcat/infochat/provider/health
        - docs/spec/deployment.md
        - docs/design/07-deployment.md
        - infochat-provider/src/test/java/app/zcat/infochat/provider/health
        - infochat-collector/src/test/java/app/zcat/infochat/collector
      acceptance:
        - "U-15 decided: the release-on-stage2-failure=true (fail-open) default in base/laptop/pi profiles is either (a) kept, with the trade-off documented in deployment docs and releasedStage2FailedCount surfaced to operators (log line or health payload) so fail-open releases are visible, or (b) flipped to fail-closed with explicit per-profile opt-in documented; one of the two, recorded with rationale; a named test pins whichever default ships."
        - "U-16 addressed: docs gain explicit binding guidance for the health port (localhost/private-interface bind; the readiness payload enumerates adapter topology, which is reconnaissance data if the port is externally reachable), and the unauthenticated readiness payload either drops adapter names or the doc records why they stay; a named test pins the shipped payload shape."
        - "mvn -B clean verify from the repo root exits 0."
      test_plan:
        adds:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/health
        preserves:
          - all tests currently green on main
      out_of_scope:
        - The Stage-2 judge itself and the releasedStage2FailedCount counter mechanics — only its default policy and operator visibility.
        - Auth on the health endpoint — v1 keeps it unauthenticated; binding guidance + payload trimming are the levers.
blocked_by: []
files_budget: 10
files_scope:
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health
  - docs/spec/deployment.md
  - docs/spec/security.md
  - docs/design/07-deployment.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health/ProviderReadinessEndpointIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector/config/HttpBindDefaultConfigTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Stage-2 judge itself and the releasedStage2FailedCount counter mechanics — only its default policy and operator visibility.
  - Auth on the health endpoint — v1 keeps it unauthenticated; the levers are the shipped loopback bind default (with documented operator override) and payload accuracy/pinning.
  - Disabling the auto-registered datasource readiness check (quarkus.datasource.health.enabled=false) — DB-down MUST gate readiness; the remediation is doc truth + an aggregate pin, not check removal.
acceptance:
  - "U-15 decided: the release-on-stage2-failure=true (fail-open) default in base/laptop/pi profiles is either (a) kept, with the trade-off documented in deployment docs and releasedStage2FailedCount surfaced to operators (log line or health payload) so fail-open releases are visible, or (b) flipped to fail-closed with explicit per-profile opt-in documented; one of the two, recorded with rationale; a named test pins whichever default ships."
  - "U-16 addressed: docs gain explicit binding guidance for the health port (localhost/private-interface bind; the readiness payload enumerates adapter topology, which is reconnaissance data if the port is externally reachable), and the unauthenticated readiness payload either drops adapter names or the doc records why they stay; a named test pins the shipped payload shape."
  - "RT-1 remediated (redteam 2026-06-12 finding 1, shipped loopback bind): both services' main application.properties set quarkus.http.host=127.0.0.1 as the base (un-profiled) default, so health endpoints bind loopback out of the box in prod mode (dev mode already defaults to localhost; docker-compose containerizes only Postgres, so no port-mapping impact); docs/design/07-deployment.md §7.12.1 guidance flips from 'bind it yourself' to 'the shipped default is loopback; override quarkus.http.host per-profile/env (or firewall the port) when the prober is remote'; named tests pin the shipped 127.0.0.1 base default by reading each service's main application.properties (one test per service)."
  - "RT-2 remediated (redteam 2026-06-12 finding 2, aggregate payload truth + pin): docs/design/07-deployment.md §7.12.1 scopes the payload-shape pin claim to the messaging-adapters check's data map and records that the unauthenticated readiness aggregate ALSO carries the auto-registered Agroal datasource check (deliberately kept: DB-down must gate readiness); docs/spec/deployment.md §Health and observability 'Endpoint exposure' bullet mentions the DB-connectivity disclosure alongside adapter topology; a named endpoint-level @QuarkusTest pins the exact set of readiness check names returned by /q/health/ready (the messaging-adapters check plus the Agroal datasource check, nothing else) so any future auto-contributed check widens the unauthenticated payload loudly."
  - "Out-of-model addressed: docs/spec/security.md gains the health/management HTTP endpoint as a named surface (unauthenticated in v1; discloses adapter topology and DB connectivity state; mitigation is the shipped loopback bind default plus documented operator override/firewall) so future diffs touching this surface are auditable against the threat model rather than deployment prose."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/health
    - infochat-collector/src/test/java/app/zcat/infochat/collector/config/HttpBindDefaultConfigTest.java
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/health/ProviderReadinessEndpointIT.java — ADD new test methods only (aggregate readiness check-name-set pin; provider loopback-bind default pin — reuses the class's existing main-properties read pattern and already-booted %test container); existing test methods unchanged."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 172
      removed: 15
  - round: 2
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 473
      removed: 19
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-12
    category: INFO-LEAK
    severity: low
    promise: |
      "Endpoint exposure — the health endpoints are unauthenticated in v1, and the readiness payload names each enabled adapter with its up/down state: a topology disclosure (which messaging transports the deployment runs) to any caller that can reach the port. Deployments bind the health port to loopback or a private interface, or firewall it to the prober's address." (docs/spec/deployment.md §Observability, added by this diff; the diff's own spec-level mitigation commitment for the disclosure)
    gap: |
      The promise is delivered as documentation only. Neither service's shipped config sets the binding: a grep for `quarkus.http.host` / `quarkus.management` across every `application.properties` in the tree returns zero matches, so the shipped per-module default is Quarkus's production default `quarkus.http.host=0.0.0.0` — health (riding the service HTTP port per the diff's own design note, docs/design/07-deployment.md §7.12.1 "Shipped per-module defaults") is served on all interfaces out of the box. The diff pins the Stage-2 fail-open default with a config test (infochat-collector/src/test/java/app/zcat/infochat/collector/config/Stage2FailOpenDefaultConfigTest.java) but ships no analogous default or pin for the binding, the other half of the same ops-posture ticket. "The exposure lever is network reachability" (diff line 14/23, 07-deployment.md) names the lever and then leaves it un-pulled in the shipped artifact.
    repro: |
      Operator deploys Collector + Provider on an internet-facing VPS following the shipped defaults (no `quarkus.http.host` override — the doc bullet is the only place the binding is mentioned, and nothing fails or warns if it is skipped). Adversary scans the host, finds port 8081, requests `GET /q/health/ready` unauthenticated, and reads the activated adapter names (`simplex`, `signal`) plus per-adapter up/down and `<adapter>.dropped-inbound` counters — exactly the reconnaissance the new spec bullet says deployments must prevent by binding. Nothing in the shipped system enforces or verifies the binding the spec now promises.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-06-12
    category: INFO-LEAK
    severity: low
    promise: |
      "The payload shape itself (adapter names + up/down booleans + conditional `<adapter>.dropped-inbound` counts, nothing else) is pinned by `ReadinessPayloadShapeTest` so it cannot widen silently." (docs/design/07-deployment.md §7.12.1, diff line 23; backing the deployment.md spec bullet's characterization of the readiness payload as adapter-topology-only)
    gap: |
      ReadinessPayloadShapeTest (infochat-provider/src/test/java/app/zcat/infochat/provider/health/ReadinessPayloadShapeTest.java:26-46) pins only the `messaging-adapters` check's data map via the static seam `AdapterReadinessCheck.evaluate(...)` (infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java:77). The unauthenticated `/q/health/ready` endpoint aggregates ALL registered readiness checks, not just this one. The provider depends on `quarkus-jdbc-postgresql` (infochat-provider/pom.xml:78) alongside `quarkus-smallrye-health` (pom.xml:109), and no `quarkus.datasource.health.enabled=false` exists anywhere (grep over all application.properties: zero matches), so Quarkus auto-registers the Agroal "Database connections health check" in readiness. The live payload therefore already carries datasource connectivity state (DB up/down, datasource name) beyond the "nothing else" claim, and any future extension that auto-contributes a readiness check widens the unauthenticated payload with no test failing — the exact silent widening the pin claims to prevent.
    repro: |
      Adversary who can reach the health port (see finding 1) requests `GET /q/health/ready` and receives the aggregate JSON: the pinned `messaging-adapters` check PLUS the auto-registered database check — learning whether the internal Postgres ("The DB is internal — only the two services and the operator reach it", security.md §Threat model) is up and what the deployment's datasource is named, and gaining a per-dependency widening channel the test suite never observes. The shipped test passes while the shipped endpoint payload is already wider than the documented-and-pinned shape.
    suggested_fix_class: other
  - date: 2026-06-13
    category: INFO-LEAK
    severity: low
    promise: |
      "Health/management HTTP surface → network. The health endpoints are unauthenticated in v1 and disclose operational topology: which messaging adapters are enabled and up, and whether the DB is reachable. The shipped default binds them to loopback; exposing them beyond the host is an explicit operator action (widen the bind, firewall the port to the prober), never a default." (docs/spec/security.md §Trust boundaries item 6, added by this diff)
    gap: |
      The "never a default" promise holds only for the per-module shape. The diff's own design note (docs/design/07-deployment.md:847-849) concedes that under the canonical composed config (§7.4) `quarkus.management.enabled=true` moves `/q/health` + `/q/metrics` to the separate management interface and "`quarkus.http.host` on the main listener no longer covers it" — but the canonical composed template itself (docs/design/07-deployment.md:254) sets `quarkus.management.enabled=true` WITHOUT `quarkus.management.host=127.0.0.1`. Quarkus's management interface defaults its bind to `0.0.0.0`, so an operator who builds from the repo's own canonical config gets health + metrics published on all interfaces by framework default, with no explicit widening action taken. The remediation pattern this round applied to the main listener (shipped key + per-service pin: infochat-provider/src/main/resources/application.properties:23, infochat-collector/src/main/resources/application.properties:18, HttpBindDefaultConfigTest, ProviderReadinessEndpointIT.baseConfigBindsHttpListenerToLoopback) has no counterpart for the management shape — the management bind exists only as a "bind that instead" doc bullet, the exact docs-only delivery that redteam finding 1 (docs/plan/m1/redteam/M1-302-2026-06-12.md) flagged for the main listener.
    repro: |
      Operator assembles a production deployment from the canonical composed file at docs/design/07-deployment.md §7.4 (it is labeled canonical and includes `quarkus.management.enabled=true` for the §7.13 Prometheus stack), bakes it into the build, and skips the separate §7.12.1 bullet. The management interface comes up on `0.0.0.0:9000` by Quarkus default. Adversary scans the VPS, finds port 9000, requests `GET /q/health/ready` (and `/q/metrics`) unauthenticated, and reads adapter topology, DB connectivity, and metric content — despite the threat model now promising exposure beyond the host is "never a default."
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-06-13
    category: INFO-LEAK
    severity: low
    promise: |
      "Payload truth is pinned at two levels: `ReadinessPayloadShapeTest` pins the `messaging-adapters` check's data map ..., and `ProviderReadinessEndpointIT` pins the aggregate `/q/health/ready` check-name set ... so neither the per-check data map nor the unauthenticated aggregate can widen silently." (docs/design/07-deployment.md §7.12.1, added by this diff, inside the health-exposure bullet that covers BOTH services — "collector 8080 / provider 8081"; backing security.md trust boundary 6's characterization of what the health endpoints disclose)
    gap: |
      The aggregate check-name pin is delivered for the Provider only (infochat-provider/src/test/java/app/zcat/infochat/provider/health/ProviderReadinessEndpointIT.java:96 `readinessAggregateCarriesExactlyTheMessagingAdaptersAndDatasourceChecks`). The Collector serves an equally unauthenticated `/q/health/ready` on port 8080 (infochat-collector/pom.xml carries quarkus-smallrye-health + quarkus-jdbc-postgresql, so the Agroal datasource check is auto-registered there too; grep over infochat-collector/src/main/java finds zero custom HealthCheck/Readiness classes, and no test pins the collector aggregate). Any future Collector dependency that auto-contributes a readiness check widens an unauthenticated payload with no test failing — the exact silent-widening channel the RT-2 remediation (acceptance item "RT-2 remediated", docs/plan/m1/tickets/M1-302-ops-posture-decisions.md:221) was opened to close, delivered for one of the two services the bullet covers.
    repro: |
      A future ticket adds a Quarkus extension to the Collector that contributes a readiness check (the way quarkus-jdbc-postgresql contributes the Agroal check). The full suite stays green. A caller who can reach port 8080 (operator widened the bind for a remote prober per the documented override, or finding 1's management shape) requests `GET /q/health/ready` and receives the new check's name and data — payload wider than the documented-and-pinned shape, with the design doc still claiming the aggregate "cannot widen silently."
    suggested_fix_class: other
  - date: 2026-06-13
    category: INFO-LEAK
    severity: low
    promise: |
      "The shipped loopback default is pinned per service (`HttpBindDefaultConfigTest` on the Collector; `ProviderReadinessEndpointIT.baseConfigBindsHttpListenerToLoopback` on the Provider), so widening it is a deliberate, reviewed change." (docs/design/07-deployment.md §7.12.1, added by this diff)
    gap: |
      The Collector pin has a profile hole the Provider pin does not. ProviderReadinessEndpointIT.baseConfigBindsHttpListenerToLoopback (infochat-provider/src/test/java/app/zcat/infochat/provider/health/ProviderReadinessEndpointIT.java:631-647) asserts the literal line list — ANY `quarkus.http.host=` line other than the loopback base default fails it, including profile-prefixed overrides. HttpBindDefaultConfigTest (infochat-collector/src/test/java/app/zcat/infochat/collector/config/HttpBindDefaultConfigTest.java:437-450) instead resolves the key under the explicit profile set {base, laptop, vps, pi, remote-llm} — but NOT under `prod`, Quarkus's default runtime profile for a packaged service launched without `quarkus.profile`. A future `%prod.quarkus.http.host=0.0.0.0` line in the Collector's application.properties passes every shipped test (the no-profile and named-profile resolutions all still return 127.0.0.1) while the actual production runtime resolves the %prod override and binds all interfaces — a silent widening of exactly the kind the pin promises to make loud.
    repro: |
      A later change (convenience commit for a remote prober) adds `%prod.quarkus.http.host=0.0.0.0` to infochat-collector/src/main/resources/application.properties. `mvn verify` stays green; review sees passing pins. Every packaged-jar Collector deployment (runtime profile `prod`) now serves the unauthenticated readiness payload on all interfaces by default, violating security.md trust boundary 6's "never a default" with no failing test.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-06-12
    verdict: FINDINGS
    base: 99ac7719
    head: working-tree@m1/M1-302-ops-posture-stage-2-fail-open (pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-302-2026-06-12.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      Pre-commit audit after round-1 APPROVE. Two low INFO-LEAK findings,
      both fact-checked against the tree: (1) the new binding guidance is
      docs-only — no shipped loopback bind or pin (provider properties are
      outside this ticket's files_scope; candidate follow-up ticket);
      (2) the design-doc "nothing else" pin claim is scoped to the
      messaging-adapters check while the unauthenticated readiness
      aggregate also carries the auto-registered Agroal datasource check
      (small in-branch doc-precision fix possible). Out-of-model:
      security.md has no health-endpoint commitment at all — candidate
      threat-model extension to ride the same follow-up ticket.
  - date: 2026-06-13
    verdict: FINDINGS
    base: main
    head: m1/M1-302-ops-posture-stage-2-fail-open
    verdict_file: docs/plan/m1/redteam/M1-302-2026-06-13.md
    findings_count: 3
    out_of_model_count: 1
    note: |
      Post-commit/pre-merge audit of the branch tip (a427be3f) carrying
      the RT-1/RT-2 remediations. Three low INFO-LEAK follow-ups, all
      fact-checked against the tree: (1) the canonical composed config
      (design §7.4 line 254) enables the Quarkus management interface
      without `quarkus.management.host=127.0.0.1` — framework default
      binds 0.0.0.0, contradicting trust boundary 6's "never a default";
      (2) the aggregate readiness check-name pin exists for the Provider
      only — CollectorReadinessIT asserts containment, not the exact
      check-name set, leaving the Collector's unauthenticated aggregate
      free to widen silently; (3) HttpBindDefaultConfigTest resolves
      {base, laptop, vps, pi, remote-llm} but not `prod`, so a
      %prod.quarkus.http.host override would pass all pins while
      widening the packaged-jar bind. Disposition: all three fixed on
      the branch before merge (user-confirmed small-fix flow) — §7.4
      template now pins quarkus.management.host=127.0.0.1; CollectorReadinessIT
      gains the exact-set aggregate pin; HttpBindDefaultConfigTest gains
      the literal-line pin closing every profile-prefix override, not
      just %prod. Out-of-model: boundary 6 covers health only, not
      /q/metrics content — candidate threat-model extension before the
      metrics backend lands; left open.
clarity_check:
  date: 2026-06-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-302: Ops posture: Stage-2 fail-open default, readiness topology exposure (decisions)

## Context

Deep-review v5 carried **U-15** (policy decision, unique gpt-55 #M-04) and
**U-16** (LOW, deployment-conditional, unique gpt-55 #M-18)
(`deep-code-review/v5/UNIFIED-REPORT.md` §3 — gitignored; all load-bearing
facts inlined):

- U-15: base/laptop/pi profiles ship `release-on-stage2-failure=true` —
  posts whose Stage-2 security judgement failed are released fail-open.
  Not a code defect; a policy default worth an explicit decision.
- U-16: the readiness payload exposes adapter topology when the health
  port is reachable beyond the host. The unified report downgraded this to
  deployment-conditional — the fix is doc/binding guidance, possibly
  payload trimming.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **⚠ U-15 is a user decision at start.** Default if the user just says
  "go": option (a) — keep fail-open, document, surface the counter. Flipping
  to fail-closed changes ingest behaviour during LLM outages (posts held,
  queues grow) and shouldn't ride in silently. M1-278 (ops posture) is the
  precedent ticket shape for this kind of item.
- gpt-55's recommendation was fail-closed; the report deliberately
  reframed it as a decision item, not a defect — don't treat (b) as the
  "correct" answer in review.
- **Redteam refine (2026-06-12).** U-15 was decided (a) keep fail-open and
  U-16 implemented docs+pins in round 1 (APPROVE); the pre-commit /redteam
  audit (docs/plan/m1/redteam/M1-302-2026-06-12.md) returned two low
  INFO-LEAK findings, remediated in-ticket as acceptance items RT-1 and
  RT-2 plus the out-of-model security.md extension. Round-2 diff growth
  vs round 1 is mandated by these findings — cite them in the commit
  message per the redteam carve-out to must-shrink.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-302-*.md
```
