---
id: M1-713
title: "Opt-in -Pmutation profile: PIT over the four pure-Java modules"
status: pending
created: 2026-07-27
last_updated: 2026-07-27
blocked_by: []
files_budget: 2
files_scope:
  - pom.xml
  - docs/design/08-verification.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Making mutation testing a gate. No `mutationThreshold`, no binding to
    the `verify` lifecycle, no `/m1-tick` hook. A score gate would
    pressure authors into assertions written to kill mutants rather than
    to state intent, which cuts against CLAUDE.md §Engineering rules
    §"No workarounds, no shortcuts". This stays in the advisory tier
    beside `/deep-code-review` and `/redteam`.
  - >-
    Running PIT over `infochat-collector` and `infochat-provider`. Those
    two modules hold 294 of the repo's 299 Quarkus-bootstrapped test
    classes, and PIT re-runs the covering tests once per mutant — every
    mutant would pay a Quarkus boot. The plugin is declared for all
    modules so they *can* be measured deliberately; the documented
    invocation must not include them.
  - >-
    Any change to `mvn verify`. The profile carries no `<activation>`
    and the plugin declares no `<executions>`, so the default build
    cannot see it. A diff that changes surefire, failsafe, the compiler
    plugin, the NullAway configuration or any module POM has left scope.
  - >-
    Acting on the findings. The 2026-07-27 sweep's actionable survivors
    are already filed as M1-710, M1-711 and M1-712; this ticket lands
    the tool, not the fixes.
  - >-
    Arcmutate's licensed git plugin and PIT's `scmMutationCoverage`
    diff-scoped goal. Diff-scoped runs need an `<scm>` block the POM does
    not have; whether to add per-diff mutation runs is a separate
    decision that should follow evidence from advisory sweeps.
  - any source file, any test file, any other module POM
acceptance:
  - >-
    `mvn -Pmutation -pl infochat-core,infochat-ssrf,infochat-llm-adapter,infochat-messaging-adapter
    -am test-compile org.pitest:pitest-maven:mutationCoverage` completes
    green and writes HTML+XML reports to each module's
    `target/pit-reports/`.
  - >-
    The run starts no container. `*IT` and the two `@QuarkusTest`
    classes in these modules (`InstanceLockLivenessTest`,
    `ThrottledAdminNotifierTest`) are excluded, so the sweep is safe to
    run while the live stack is up — verify with `docker ps` before and
    after.
  - >-
    `mvn verify` from the repo root is byte-for-byte unaffected: the
    profile has no `<activation>` and the pitest plugin declares no
    `<executions>`.
  - >-
    `docs/design/08-verification.md` §8.11 no longer lists mutation
    testing under "What's intentionally NOT in v1 testing". It records
    what actually ships: an opt-in advisory profile scoped to the four
    pure-Java modules, explicitly not a gate and explicitly not run over
    the Quarkus-bootstrapped tier.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  preserves:
    - >-
      The M1-445 surefire version pin and the M1-446 `failIfNoTests`
      tripwire. PIT reads surefire configuration by default
      (`parseSurefireConfig`); neither may be altered to accommodate it.
    - all tests currently green on main
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-713: Opt-in -Pmutation profile: PIT over the four pure-Java modules

## Context

`docs/design/08-verification.md` §8.11 lists "Mutation testing — PIT etc."
as deferred. A 2026-07-27 spike ran it anyway to settle the question with
numbers rather than argument, and it found three real defects that the
green suite, code review and `/redteam` had all missed — now filed as
M1-710, M1-711 and M1-712. This ticket lands the tooling that found them.

**Why scoped to four modules.** PIT re-runs the covering tests once per
mutant. That is affordable against a plain-JUnit tier and unaffordable
against a `@QuarkusTest` tier, where every mutant pays a Quarkus boot:

| module group | main classes | plain JUnit | `@QuarkusTest` |
|---|---|---|---|
| core + ssrf + llm-adapter + messaging-adapter | 89 | 150 | 2 |
| collector + provider | 230 | 122 | 294 |

Measured results from the spike, four modules, `threads=4` on a 4-core
box with the live stack running:

| module | mutants | score | test strength | survived | no-coverage | PIT time |
|---|---|---|---|---|---|---|
| core | 305 | 60% | 89% | 23 | 100 | 62s |
| ssrf | 302 | 76% | 87% | 34 | 37 | 255s |
| llm-adapter | 418 | 78% | 86% | 52 | 40 | 55s |
| messaging-adapter | 1125 | 71% | 82% | 175 | 154 | 443s |

Total wall-clock 13:57 min including compiling all four modules. Note
that **test strength**, not mutation score, is the meaningful number: the
two differ by mutants with no coverage at all, and `core`'s 100 of those
are largely an artifact of excluding its 12 `*IT` classes, not a
statement about its tests.

**Why advisory and not a gate.** Two reasons, both structural. A score
threshold would tax every ticket for a measurement that is noisy
(equivalent mutants are real and unavoidable — the spike found at least
one, `SimpleXLoopbackProbe.isReachable:112`, where the mutated line
already returns the mutated value). More importantly it inverts the
incentive the test-integrity rules depend on: a gate rewards assertions
that kill mutants, which is not the same thing as assertions that state
intent. Mutation score belongs where `/deep-code-review` and `/redteam`
live — occasional, deliberate, human-read.

## Acceptance

- The documented invocation runs green and writes reports under each
  module's `target/pit-reports/`.
- The run starts no container and is safe alongside the live stack.
- `mvn verify` is unaffected — no `<activation>`, no `<executions>`.
- §8.11 records what ships instead of listing mutation testing as
  deferred.
- `mvn verify` from the repo root is green.

## Out-of-scope

No gate, no lifecycle binding, no collector/provider sweep, no
diff-scoped mode, no source or test changes. The three findings from the
spike are owned by M1-710/711/712, not by this ticket.

## Reference implementation (spike-verified)

The spike's `<profiles>` block, appended after `</build>` in the reactor
`pom.xml`, ran the acceptance invocation green. Reproduced here because
the spike was reverted to leave a clean tree; treat it as a starting
point, not a mandate.

```xml
    <profiles>
        <profile>
            <id>mutation</id>
            <properties>
                <pitest.threads>4</pitest.threads>
            </properties>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-maven</artifactId>
                        <version>1.25.8</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.pitest</groupId>
                                <artifactId>pitest-junit5-plugin</artifactId>
                                <version>1.2.3</version>
                            </dependency>
                        </dependencies>
                        <configuration>
                            <targetClasses><param>app.zcat.infochat.*</param></targetClasses>
                            <targetTests><param>app.zcat.infochat.*</param></targetTests>
                            <excludedTestClasses>
                                <param>*IT</param>
                                <param>app.zcat.infochat.core.startup.InstanceLockLivenessTest</param>
                                <param>app.zcat.infochat.core.notifier.ThrottledAdminNotifierTest</param>
                            </excludedTestClasses>
                            <threads>${pitest.threads}</threads>
                            <outputFormats>
                                <param>HTML</param>
                                <param>XML</param>
                            </outputFormats>
                            <timestampedReports>false</timestampedReports>
                            <failWhenNoMutations>false</failWhenNoMutations>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

The three `excludedTestClasses` entries are load-bearing, not tidiness:
`*IT` needs Testcontainers/Dev Services and the two named classes are the
only genuine `@QuarkusTest` classes in these four modules. Excluding them
is what keeps the run container-free and therefore safe alongside the
live stack. Per CLAUDE.md §"Comment important, crucial, or complex code",
the shipped block should carry comments explaining the module scoping,
the exclusions and the deliberate absence of a threshold — the spike's
version did.

## Notes

- **Blocks M1-711 and M1-712.** Both have acceptance criteria that
  re-run a census against `target/pit-reports/mutations.xml`, which
  requires this profile. M1-710's acceptance is verified by deleting
  `acquire()` calls by hand and needs no report, so it is not blocked —
  though its §Context cites the same sweep.

- **Java 25 is not a constraint.** PIT 1.25.8 (2026-07-20) supports
  bytecode through Java 26 (upstream `hcoles/pitest#1439`); the
  `pitest-junit5-plugin` pin of 1.2.3 is the version that added Quarkus
  support, which matters only if collector/provider are ever measured
  deliberately.

- **No §Census, deliberately.** `scripts/lint-ticket.py` WARNs
  (CENSUS-PRESENT-IF-CLASS-SCOPED) on the phrase "each module" in the
  acceptance criteria. There is no class to enumerate: the diff is one
  profile block in one POM plus one design-note paragraph, and the module
  list is fixed and named in full in the documented invocation. The WARN
  is a false positive; adding a ceremonial census grep would satisfy the
  linter without informing anyone.

- **Slowest module is not the biggest one.** `ssrf` took 255s for 302
  mutants while `llm-adapter` took 55s for 418 — the SSRF tests spend
  their time in socket and DNS timeouts. `messaging-adapter` logged 64
  `TIMED_OUT` minions, which PIT counts as killed; that is a weaker
  signal than an assertion failure and is worth remembering when reading
  its 71%.
