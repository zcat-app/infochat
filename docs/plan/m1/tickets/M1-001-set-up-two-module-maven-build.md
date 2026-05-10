---
id: M1-001
title: Set up two-module Maven build
status: done
created: 2026-05-10
last_updated: 2026-05-10
blocked_by: []
files_budget: 5
files_scope:
  - pom.xml
  - infochat-collector/pom.xml
  - infochat-provider/pom.xml
  - .gitignore
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any *.java file in any module (including package-info.java or empty marker classes)
  - any <dependency> entry in a module pom (the BOM manages versions; nothing should be pulled)
  - any application.properties or application.yaml under src/main/resources/
  - any src/main/resources/db/ directory (Flyway migrations)
  - any src/test/ directory or test class
  - docker-compose.yml at the repo root
  - any third module subdirectory or extra <module> entry beyond infochat-collector and infochat-provider
  - the quarkus-maven-plugin (it belongs to the per-module Quarkus app skeleton ticket)
  - README.md at the repo root (separate concern)
acceptance:
  - "mvn -B clean install -DskipTests from repo root exits 0"
  - "mvn -B verify from repo root exits 0 (suite is trivially green; no tests yet)"
  - "after install, infochat-collector/target/infochat-collector-*.jar exists"
  - "after install, infochat-provider/target/infochat-provider-*.jar exists"
  - "grep -E '<artifactId>quarkus-bom</artifactId>' pom.xml returns at least one match inside <dependencyManagement>"
  - "grep -E '<maven.compiler.release>25</maven.compiler.release>' pom.xml returns at least one match"
  - "grep -E '<module>infochat-collector</module>' pom.xml returns exactly one match; same for infochat-provider"
  - "grep -rEn '^\\s*<dependency>' infochat-collector/pom.xml infochat-provider/pom.xml returns zero matches"
  - "grep -E 'target/' .gitignore returns at least one match"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (the suite is empty pre-ticket; mvn verify must remain trivially green post-ticket)
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
      files: 6
      added: 99
      removed: 8
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

# M1-001: Set up two-module Maven build

## Context

First M1 ticket. The repo has no Maven build yet — only `docs/` and
`CLAUDE.md`. M1 cannot start until the two-service multi-module shape
committed in `docs/spec/architecture.md` §Service split is buildable.
This ticket creates the bare Maven skeleton (root parent pom + two
child module poms + `.gitignore`) so every subsequent ticket has a
working `mvn verify` to extend. It also serves as the calibration run
for the `/m1-tick` workflow itself: smallest meaningful diff, runnable
acceptance, real `out_of_scope` list, real reviewer surface — without
any LLM-pipeline or schema risk.

## Definition of Done

- A multi-module Maven build exists at the repo root, with two child modules: `infochat-collector` and `infochat-provider`.
- Root `pom.xml` declares `packaging=pom`, lists both modules in `<modules>`, declares the Quarkus 3.33.x platform BOM in `<dependencyManagement>`, and sets `<maven.compiler.release>25</maven.compiler.release>`.
- Each module pom declares its parent reference, its `<artifactId>`, and `<packaging>jar</packaging>` — and nothing else (no `<dependency>` entries, no Quarkus extensions, no plugin overrides).
- `mvn -B clean install -DskipTests` from the repo root exits 0 and produces a jar artifact in each module's `target/` directory.
- `mvn -B verify` from the repo root exits 0. The suite is trivially green because no tests exist yet.
- `.gitignore` at the repo root excludes at least `target/`, `.quarkus/`, `.idea/`, and `*.iml`.

## Implementation notes

- Quarkus platform BOM coordinates: `io.quarkus.platform:quarkus-bom:3.33.<latest-LTS-patch>` — pin the patch version explicitly. Quarkus 3.33 LTS is the committed line (CLAUDE.md §Stack; the user-memory note `project_quarkus_jdk25.md` records 2026-03-25 as the LTS release date).
- Java 25 release flag goes via the standard `<maven.compiler.release>25</maven.compiler.release>` property at the root; module poms inherit it.
- Module artifact IDs MUST be `infochat-collector` and `infochat-provider` exactly. CLAUDE.md §Build / run quick reference assumes those names (`mvn -pl infochat-collector quarkus:dev`).
- No `application.properties`, no `src/` populated. An empty module pom produces an empty jar; that is the expected output for this ticket.
- `.gitignore` should also cover the working tree's already-untracked `.idea/` directory (visible in `git status` at session start).

## Big-picture notes

- The next ticket will likely add a Quarkus extension (`quarkus-arc`) plus a single sanity test, exercising the test-runner end of `mvn verify`. Don't pre-empt that here.
- A future ticket may introduce a third module for shared SPI interfaces / DTOs / Flyway migrations. **Do not pre-create it now** — the share-vs-duplicate design call hasn't been made and pre-creating an `infochat-shared` module would commit to it implicitly.
- The `quarkus-maven-plugin` is intentionally NOT wired in this ticket. It belongs to the per-module Quarkus app skeleton ticket, where `application.properties` and at least one Quarkus extension exist for it to act on.
- This is the calibration run. The reviewer subagent, the clarity preflight, the commit-time test-log mtime check, the squash-merge step — all run for the first time on this ticket. Treat any unexpected workflow friction as workflow signal, not as ticket failure.

## Out-of-scope expansion

This ticket is structural only. No Java code, no Quarkus extensions
beyond the BOM-managed inheritance, no `application.properties`, no
Flyway migrations, no `docker-compose.yml`, no third module, no test
classes. These are all real follow-on tickets in the M1 backlog;
folding any of them in would expand this calibration run beyond its
purpose.

The reviewer should treat any of the following as scope drift:

- A `.java` file in any module (including `package-info.java` or empty marker classes).
- Any `<dependency>` entry in a module pom — the parent BOM manages versions; nothing should actually be pulled at this stage.
- An `application.properties` (or YAML equivalent) anywhere under `src/main/resources/`.
- Any `src/main/resources/db/` directory.
- Any test source under `src/test/`.
- A `docker-compose.yml` at the repo root.
- An additional module subdirectory or `<module>` entry beyond the two named.
- The `quarkus-maven-plugin` in any pom.
- A `README.md` at the repo root — separate concern, not blocking on this skeleton.

## Authorized test changes

- (none — this ticket adds no tests and modifies none. The test suite is empty pre-ticket and remains empty post-ticket; `mvn verify` is trivially green.)

## Alternatives considered

- Alt A: Single-module Maven build, defer the split. Rejected because the two-service split is a spec commitment (decision D2; `docs/spec/architecture.md` §Service split) and folding two modules in here costs only one extra trivial pom.
- Alt B: Three modules — add `infochat-shared` for SPI interfaces / DTOs / migrations. Rejected because the share-vs-duplicate design call hasn't been made; pre-creating a shared module would commit to it implicitly. A later ticket can introduce it deliberately if the design lands that way.
- Alt C: Include `quarkus-arc` + a generated `Application.java` so each module is a real Quarkus app from day one. Rejected as scope creep — it pulls in `application.properties`, the `quarkus-maven-plugin`, and a startup wiring choice that should each be their own ticket.
- Alt D: Root `pom.xml` only (no module subdirectories yet). Rejected because the two-service split is already committed; doing both poms together costs nothing extra and the immediate next ticket would otherwise be "add the modules," which is a workflow split with no value.
