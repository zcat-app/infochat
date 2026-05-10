---
id: M1-003
title: Quarkus app skeleton and first test
status: done
created: 2026-05-10
last_updated: 2026-05-10
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/pom.xml
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java
  - infochat-provider/pom.xml
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any file under infochat-collector/src/main/java/ or infochat-provider/src/main/java/ (no production Java yet)
  - any Quarkus extension other than quarkus-arc (no rest, no jdbc, no langchain4j, no scheduler, no messaging)
  - any src/main/resources/db/ directory (Flyway migrations)
  - any <dependency> with a hard-coded <version> — the BOM must supply versions
  - the root pom.xml (M1-001 already wired the BOM; this ticket changes only module poms)
  - docker-compose.yml at the repo root
  - README.md at the repo root
  - any third module subdirectory or extra <module> entry
  - any application.properties content beyond what is required to silence Quarkus startup warnings on an empty app
acceptance:
  - "mvn -B clean verify from repo root exits 0"
  - "surefire reports show at least one test executed in infochat-collector and at least one in infochat-provider (grep -rE 'Tests run: [1-9]' infochat-collector/target/surefire-reports infochat-provider/target/surefire-reports returns matches in both)"
  - "grep -E '<artifactId>quarkus-arc</artifactId>' infochat-collector/pom.xml returns at least one match; same for infochat-provider/pom.xml"
  - "grep -E '<artifactId>quarkus-junit5</artifactId>' infochat-collector/pom.xml returns at least one match; same for infochat-provider/pom.xml"
  - "grep -E '<artifactId>quarkus-maven-plugin</artifactId>' infochat-collector/pom.xml returns at least one match; same for infochat-provider/pom.xml"
  - "grep -rn '@QuarkusTest' infochat-collector/src/test/java returns at least one match; same for infochat-provider/src/test/java"
  - "infochat-collector/src/main/resources/application.properties exists; same for infochat-provider"
  - "grep -rEn '<version>' infochat-collector/pom.xml infochat-provider/pom.xml returns zero matches inside <dependency> blocks (BOM supplies versions)"
test_plan:
  adds:
    - infochat-collector/src/test/java/**/*Test.java (one @QuarkusTest proving the Quarkus context boots)
    - infochat-provider/src/test/java/**/*Test.java (one @QuarkusTest proving the Quarkus context boots)
  preserves:
    - all tests currently green on main (suite is empty pre-ticket; post-ticket it has exactly the two sanity tests added here)
spec_refs:
  - docs/spec/architecture.md §Service split
decision_refs:
  - D1
  - D2

reviews:
  - round: 1
    date: 2026-05-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 223
      removed: 4
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-10
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-003: Quarkus app skeleton and first test

## Context

M1-001 built a multi-module Maven skeleton whose `mvn verify` is
trivially green (no tests exist). That validated the build wiring but
not the *test runner* end of the workflow — round caps, test-integrity
rules, and the "regressions in the existing suite" reviewer check all
need a real test surface to bite on. This ticket adds the minimum
Quarkus app skeleton to both modules — `quarkus-arc`, `quarkus-junit5`,
the `quarkus-maven-plugin`, an `application.properties`, and one
`@QuarkusTest` per module that proves Quarkus boots. It is the second
and final calibration ticket before structural work begins.

## Definition of Done

- Each module pom declares the `quarkus-arc` extension (CDI bootstrap) and the `quarkus-junit5` test dependency, both with BOM-managed versions (no explicit `<version>` elements in the `<dependency>` blocks).
- Each module pom wires the `quarkus-maven-plugin` so Quarkus augmentation runs during `mvn verify`.
- Each module has an `application.properties` under `src/main/resources/` — contents may be empty or only what Quarkus requires to start without warnings.
- Each module has at least one `@QuarkusTest`-annotated test class under `src/test/java/`. The test body may be empty; the assertion is that the Quarkus context starts.
- `mvn -B clean verify` from the repo root exits 0 and surefire reports show ≥1 executed test per module.
- No production Java (`src/main/java/**`) is added in this ticket. Quarkus auto-generates the main; no `Application.java` is required.

## Implementation notes

- The `quarkus-maven-plugin` coordinates are `io.quarkus.platform:quarkus-maven-plugin` with the BOM supplying the version. Bind it to the standard goals (`build`, `generate-code`, `generate-code-tests`).
- `quarkus-junit5` must be `<scope>test</scope>`.
- An empty `application.properties` is acceptable. If Quarkus emits noisy warnings at test startup, add the minimum config to silence them (e.g. `quarkus.banner.enabled=false`) — but only as much as needed; this is not the place to set HTTP ports or DB config.
- Package names for the test classes are an implementation choice; suggested `io.infochat.collector` and `io.infochat.provider` to leave room for a future `io.infochat.shared` module without renaming.
- The test class name is up to the implementer; `QuarkusBootstrapTest` or similar is fine. The acceptance check grep for `@QuarkusTest`, not for a specific class name.

## Big-picture notes

- This is the last calibration ticket. After M1-003 the workflow is considered validated and structural tickets (schema, adapters, LLM SPI, scheduler) start. Treat any unexpected friction here as workflow signal, not as ticket failure — same posture as M1-001.
- The next ticket will likely be the first Flyway migration (initial schema baseline) on the collector module. That ticket sets `migration_touch: true` and will block parallelism until it lands. Do NOT pre-empt schema work here.
- The `application.properties` files added here are intentionally minimum. Real config (DB URL, LLM endpoint, profile keys) lands in later tickets tied to the components that need them.
- No HTTP, no REST resources, no `quarkus-resteasy*` extensions. Both modules are non-HTTP from this skeleton's perspective; the Provider will eventually expose adapter callbacks via its messaging SPI, not via JAX-RS.

## Out-of-scope expansion

This ticket adds **only** the bootstrap surface needed to make `mvn verify`
exercise the test runner. The reviewer should treat any of the following
as scope drift:

- Any file under `src/main/java/` in either module. The Quarkus context boot is proven via `@QuarkusTest` alone; production classes are not required.
- Any Quarkus extension other than `quarkus-arc` — no `quarkus-rest`, no `quarkus-jdbc-postgresql`, no `quarkus-flyway`, no `quarkus-langchain4j`, no `quarkus-scheduler`, no `quarkus-messaging-*`. Each of those is a deliberate follow-up ticket tied to the component that needs it.
- Any explicit `<version>` element inside a `<dependency>` block. The Quarkus BOM (wired in M1-001) supplies versions; an explicit version here would silently override BOM management.
- A Flyway migration under `src/main/resources/db/`.
- Edits to the root `pom.xml`. M1-001 wired the BOM there; module-level additions go in module poms.
- A `docker-compose.yml`, a root `README.md`, a third module, or any additional `<module>` entry.
- Application config beyond what is needed to silence startup warnings. No HTTP ports, no DB URLs, no profile keys.

## Authorized test changes

- This ticket adds two new test classes (one per module). It modifies no pre-existing tests because the test suite is empty pre-ticket.

## Alternatives considered

- Alt A: Add the skeleton to one module only (collector), defer provider to a follow-up. Rejected because the symmetry matches M1-001's "both poms in one ticket" pattern and the follow-up would be pure churn.
- Alt B: Include `quarkus-rest` plus a trivial `@Path` endpoint and an HTTP-asserting test. Rejected as scope creep — neither service is HTTP-shaped in the spec (collector has no user-facing API; provider talks to messaging adapters, not HTTP clients). Adding REST here would commit to a surface neither service needs.
- Alt C: Add `quarkus-jdbc-postgresql` + a Testcontainers-backed test. Rejected because (a) it triples the ticket surface, (b) Testcontainers requires Docker in the test environment which is a separate decision, and (c) the first DB-touching ticket will be the Flyway baseline, which is its own design call.
- Alt D: Generate an `Application.java` `public static void main` stub in each module. Rejected because Quarkus auto-generates the runner main; an explicit `Application.java` adds production Java that this ticket explicitly excludes.
