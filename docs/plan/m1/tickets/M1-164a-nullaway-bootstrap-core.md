---
id: M1-164a
title: "NullAway/Error Prone build wiring + onboard infochat-core"
status: done
created: 2026-06-03
last_updated: 2026-06-04
blocked_by: []
files_budget: 13
files_scope:
  - pom.xml
  - infochat-core
  - infochat-ssrf/pom.xml
  - infochat-llm-adapter/pom.xml
  - infochat-messaging-adapter/pom.xml
  - infochat-collector/pom.xml
  - infochat-provider/pom.xml
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-164
out_of_scope:
  - onboarding (enabling NullAway analysis + resolving findings) for any module other than infochat-core — b–f receive ONLY a temporary Error-Prone opt-out stanza here, removed by their own subtickets M1-164b..f
  - resolving any NullAway/Error-Prone finding in a module other than infochat-core
  - retiring scripts/lint-contracts.py or rewriting the §7a docs (the umbrella M1-164 does that after every module is onboarded)
  - Tier-1 Error Prone check promotions (M1-165)
  - production code behavior change (annotations are compile-time; dead-guard removal is M1-146)
acceptance:
  - "Parent pom.xml adds a maven-compiler-plugin <pluginManagement> entry pinning error_prone_core 2.42.0 + nullaway 0.10.25 on <annotationProcessorPaths>, with compilerArgs: -XDcompilePolicy=simple, --should-stop=ifError=FLOW, a single -Xplugin arg carrying `-Xplugin:ErrorProne -XepOpt:NullAway:AnnotatedPackages=app.zcat.infochat` plus -Xep:NullAway:ERROR, the jdk.compiler --add-exports/--add-opens set, and <fork>true</fork>; NullAway ExcludedFieldAnnotations covers framework-initialized fields (jakarta.inject.Inject, jakarta.annotation.PostConstruct, org.eclipse.microprofile.config.inject.ConfigProperty) and generated sources are excluded from analysis. This config lives in <pluginManagement> and Maven therefore applies it to the lifecycle compile execution of EVERY reactor module (it is NOT per-module opt-in); module-level gating is achieved by the opt-out stanza in acceptance item 2"
  - "Because the item-1 config is global, every module NOT being onboarded by this ticket (infochat-ssrf, infochat-llm-adapter, infochat-messaging-adapter, infochat-collector, infochat-provider) gets a temporary Error-Prone opt-out stanza in its own pom.xml that overrides the inherited compilerArgs to empty (combine.self=\"override\") so Error Prone / NullAway does not run there and the module compiles exactly as before. Each opt-out is removed by that module's own subticket (M1-164b..f) when the module is onboarded. No other change is made to those modules' poms or sources"
  - "infochat-core/pom.xml records (in a pom comment) the chosen test-source policy — main sources gated by NullAway:ERROR; test sources either excluded for now or onboarded, whichever the implementer validates green"
  - "infochat-core's genuinely-nullable parameters/returns/fields carry @Nullable (JSpecify); no @NonNull is added (non-null is the AnnotatedPackages default); the AuditLogWriter @Nullable-into-@NonNull findings, the ThrottledAdminNotifier / AbstractInstanceLockGuard field-init findings (handled via @SuppressWarnings(\"NullAway.Init\") since @PostConstruct is a method annotation ExcludedFieldAnnotations cannot match on a field), and the Sha256 NullablePrimitiveArray finding observed in the D48 validation spike are resolved"
  - "Whole-reactor `mvn -B clean verify` exits 0: infochat-core compiles + tests green with NullAway:ERROR active on its main sources, and every opted-out module compiles + tests exactly as it did on main (no Error Prone / NullAway findings leak into them)"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
decision_refs:
  - D48
reviews:
  - round: 1
    date: 2026-06-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 296
      removed: 27
escalations:
  - date: 2026-06-04
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise-fail surfaced during implementation, before any review.
      Maven applies a parent <pluginManagement> <configuration> for
      maven-compiler-plugin to the lifecycle-bound default-compile execution
      in EVERY reactor module, with or without a per-module "activation
      stanza". Putting the full Error Prone config (annotationProcessorPaths +
      the -Xplugin:ErrorProne compilerArg) in parent <pluginManagement> — as
      acceptance item 1 mandates — therefore activates NullAway reactor-wide
      immediately. Empirically: `mvn -pl infochat-ssrf clean compile` (a
      module with NO activation stanza, onboarding deferred to M1-164b) fails
      with NullAway findings. The ticket's "inherit managed config + opt in
      with a minimal activation stanza" model is mechanically void: there is
      nothing to opt into because the config is already live everywhere.
      Result: acceptance item 4 (`mvn -pl infochat-core clean verify`) passes
      in isolation, but the whole-reactor `mvn verify` required by the
      "run full suite before done" rule fails in out-of-scope modules b–f.
revisions:
  - date: 2026-06-04
    reason: premise-fail refine (round 1) — global <pluginManagement> config is reactor-wide, so per-module opt-in is impossible; switch to global-config + per-module Error-Prone opt-out (chosen approach A)
    snapshot:
      files_budget: 10
      files_scope:
        - pom.xml
        - infochat-core
      out_of_scope:
        - onboarding any module other than infochat-core (separate subtickets M1-164b..f)
        - retiring scripts/lint-contracts.py or rewriting the §7a docs (the umbrella M1-164 does that after every module is onboarded)
        - Tier-1 Error Prone check promotions (M1-165)
        - production code behavior change (annotations are compile-time; dead-guard removal is M1-146)
      acceptance:
        - "Parent pom.xml adds a maven-compiler-plugin <pluginManagement> entry pinning error_prone_core 2.42.0 + nullaway 0.10.25 ... (full config in pluginManagement, per-module activation stanza)"
        - "infochat-core/pom.xml activates the managed plugin, records test-source policy"
        - "infochat-core nullable cases carry @Nullable; named D48 findings resolved"
        - "mvn -B -pl infochat-core clean verify exits 0 with NullAway:ERROR"
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-04
  verdict: PASS
  warnings: []
  blockers: []
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
