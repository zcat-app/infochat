---
id: M1-164a
title: "NullAway/Error Prone build wiring + onboard infochat-core"
status: pending
created: 2026-06-03
last_updated: 2026-06-03
blocked_by: []
files_budget: 10
files_scope:
  - pom.xml
  - infochat-core
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-164
out_of_scope:
  - onboarding any module other than infochat-core (separate subtickets M1-164b..f)
  - retiring scripts/lint-contracts.py or rewriting the §7a docs (the umbrella M1-164 does that after every module is onboarded)
  - Tier-1 Error Prone check promotions (M1-165)
  - production code behavior change (annotations are compile-time; dead-guard removal is M1-146)
acceptance:
  - "Parent pom.xml adds a maven-compiler-plugin <pluginManagement> entry pinning error_prone_core 2.42.0 + nullaway 0.10.25 on <annotationProcessorPaths>, with compilerArgs: -XDcompilePolicy=simple, --should-stop=ifError=FLOW, a single -Xplugin arg carrying `-Xplugin:ErrorProne -XepOpt:NullAway:AnnotatedPackages=app.zcat.infochat` plus -Xep:NullAway:ERROR, the jdk.compiler --add-exports/--add-opens set, and <fork>true</fork>; NullAway ExcludedFieldAnnotations covers framework-initialized fields (jakarta.inject.Inject, jakarta.annotation.PostConstruct) and generated sources are excluded from analysis"
  - "infochat-core/pom.xml activates the managed plugin, and the implemented config records (in a pom comment) the chosen test-source policy — main sources gated by NullAway:ERROR; test sources either excluded for now or onboarded, whichever the implementer validates green"
  - "infochat-core's genuinely-nullable parameters/returns/fields carry @Nullable (JSpecify); no @NonNull is added (non-null is the AnnotatedPackages default); the AuditLogWriter @Nullable-into-@NonNull findings, the ThrottledAdminNotifier / AbstractInstanceLockGuard field-init findings, and the Sha256 NullablePrimitiveArray finding observed in the D48 validation spike are resolved"
  - "mvn -B -pl infochat-core clean verify exits 0 with NullAway:ERROR and Error Prone default checks active"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
decision_refs:
  - D48
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-164a: NullAway/Error Prone build wiring + onboard infochat-core

## Context

First subticket of the M1-164 umbrella (decision D48). Establishes the shared
Maven build wiring for NullAway + Error Prone (in parent-pom
`<pluginManagement>`, so every module inherits the managed config and opts in
with a minimal activation stanza) and onboards the foundational `infochat-core`
module as the first consumer. The remaining module subtickets (b–f) depend only
on the wiring landed here.

## Acceptance

See frontmatter. Land the parent-pom pluginManagement, activate it in
`infochat-core`, annotate `infochat-core`'s genuine nullable cases with
`@Nullable`, and get `mvn -pl infochat-core verify` green with `NullAway:ERROR`.

## Out-of-scope

See frontmatter. Only `infochat-core` is onboarded here. The lint retirement and
§7a doc rewrite are reserved for the umbrella. No `@NonNull` is written
(non-null is the default).

## Notes

- Validated config from the D48 spike (worked on JDK 25.0.3):
  ```
  error_prone_core 2.42.0 ; nullaway 0.10.25 ; jspecify 1.0.0
  compilerArgs:
    -XDcompilePolicy=simple
    --should-stop=ifError=FLOW
    -Xplugin:ErrorProne -XepOpt:NullAway:AnnotatedPackages=app.zcat.infochat
    -Xep:NullAway:ERROR
    -J--add-exports=jdk.compiler/com.sun.tools.javac.{api,file,main,model,parser,processing,tree,util}=ALL-UNNAMED
    -J--add-opens=jdk.compiler/com.sun.tools.javac.{code,comp}=ALL-UNNAMED
  <fork>true</fork>
  ```
- The ErrorProne + NullAway flags MUST share a single `<arg>` element
  (`-Xplugin:ErrorProne -XepOpt:...`) — splitting them makes the compiler plugin
  pass them as separate arguments and NullAway is not picked up.
- Framework-init findings (e.g. `ThrottledAdminNotifier.upsertSql`,
  `AbstractInstanceLockGuard.heldConnection`) are the `@PostConstruct`/lifecycle
  pattern — handle via `-XepOpt:NullAway:ExcludedFieldAnnotations=...` rather
  than weakening contracts.
- Generated sources: exclude via `-XepExcludedPaths` (or
  `-XepOpt:NullAway:TreatGeneratedAsUnannotated=true`) so Quarkus-generated code
  does not produce findings — relevant for the Quarkus-app modules (e/f) but set
  the policy here.
- Consider scoping Error Prone to a `verify`/CI Maven profile if it slows the
  `quarkus:dev` recompile loop noticeably.
- Decision D48 is recorded in `docs/spec/decisions.md` as part of this rollout.
