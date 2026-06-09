---
id: M1-260
title: "Build/doc-truth lows: sibling DAG enforcer, Flyway dup, gate javadoc"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 5
files_scope:
  - infochat-ssrf/pom.xml
  - infochat-llm-adapter/pom.xml
  - infochat-messaging-adapter/pom.xml
  - infochat-core/pom.xml
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any runtime behavior — all three findings are build-config or javadoc only; no .java logic and no SQL changes, no new runtime code path.
  - The collector→messaging-adapter bannedDependencies execution (infochat-collector/pom.xml) — already present; it is the pattern being copied, not modified.
  - The actual startup gates in AdapterRegistry.start(csv) and their inline per-gate citations — correct and unchanged; only the class-level/method javadoc count+attribution prose is corrected.
  - flyway-core removal — only remove it if verification confirms no schema test uses the standalone Flyway.configure() API; otherwise keep it. Do not change Flyway versions or scopes.
acceptance:
  - "Each of the three sibling shared poms (infochat-ssrf, infochat-llm-adapter, infochat-messaging-adapter) gains a maven-enforcer bannedDependencies execution banning the other two siblings, mirroring the proven collector→messaging-adapter execution, so the docs/design/09-reference.md §9.1 'siblings MUST NOT depend on each other' DAG rule is build-enforced (a sibling-on-sibling dependency fails mvn validate) rather than convention-only."
  - "infochat-core/pom.xml declares org.flywaydb:flyway-database-postgresql exactly once (the duplicate is removed); the remaining test Flyway dependency set still compiles and runs the schema/Testcontainers tests."
  - "AdapterRegistry's class-level and start(csv) javadoc no longer claim 'the six startup gates ... in §6.7's documented order'; the prose drops the hardcoded count and the single-section attribution while keeping the accurate inline per-gate citations (the body applies eight numbered gates plus a duplicate-name check, two sourced from deployment.md §Operator inputs)."
  - "mvn -B clean verify from the repo root exits 0 (which also exercises the new enforcer executions)."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/09-reference.md §9.1
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 123
      removed: 11
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-260: Build/doc-truth lows

## Context

Three low-severity build-config / documentation-truth findings from the v3.5 deep
review, grouped because none changes runtime behavior and all concern "the build
and the docs telling the truth about themselves." Source reports:
`deep-code-review/v3.5/opus-48/01-architecture.md#F1` (sibling DAG),
`02-module-infochat-core.md#F3` (Flyway dup),
`01-architecture.md#F2` (AdapterRegistry gate javadoc). All three verified live on
main.

- **Sibling DAG (01#F1).** `docs/design/09-reference.md` §9.1 states the three
  sibling shared modules MUST NOT depend on each other, and the file claims "the
  build enforces the DAG." That is literally true for the collector→messaging
  edge (a real `bannedDependencies` enforcer) but only aspirational for the three
  sibling edges — grep for `bannedDependencies` in each sibling pom returns
  nothing. A doc that says "the build enforces the DAG" while one class of edges
  is unenforced misleads a future reader.
- **Flyway dup (02#F3).** `infochat-core/pom.xml` declares
  `org.flywaydb:flyway-database-postgresql` twice (lines ~124-127 and ~162-166).
  Maven dedups it harmlessly, but a duplicate entry is a divergence hazard (a
  future version/scope change applied to one copy and not the other).
- **AdapterRegistry javadoc (01#F2).** The class advertises "the six startup
  gates ... in §6.7's documented order," but the body applies eight numbered
  gates plus a duplicate-name check, two of them (gate 7 / 7b) sourced from
  `deployment.md §Operator inputs`, not §6.7. The gates and their inline
  citations are correct; only the count+attribution prose is stale.

## Acceptance

See frontmatter. In prose: add the sibling `bannedDependencies` executions
(copied from the collector pattern), de-duplicate the core Flyway dependency, and
correct the AdapterRegistry gate javadoc to drop the false count/attribution.
`mvn verify` is 0 and exercises the new enforcer rules.

## Out-of-scope

See frontmatter. No runtime behavior, no Flyway version/scope change, no change to
the actual gates or their inline citations. `flyway-core` is removed only if
verified unused by the standalone Flyway API.

## Notes

- The enforcer plugin is already in parent `pluginManagement` (per the collector
  pom comment), so each sibling needs only an `<execution>` block (~20 lines),
  not a new plugin declaration.
- Before deleting `flyway-core`: check whether `PostgresSchemaTestBase`-derived
  tests or `ThrottledAdminNotifierTest` use `quarkus.flyway.migrate-at-start`
  (the Quarkus extension brings flyway-core transitively → standalone line
  redundant) versus the standalone `Flyway.configure()` API (keep it). If unsure,
  keep `flyway-core` and only de-dup `flyway-database-postgresql`.
- AdapterRegistry: the fix is documentation-only and strictly removes a false
  claim; keep "each gate has a dedicated @Test in StartupGatesTest" if accurate.
</content>
