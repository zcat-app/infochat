---
id: M1-446
title: "build: pin the Maven toolchain (wrapper) and add a non-empty-unit-suite tripwire so a silent test-skip can never recur"
status: pending
created: 2026-06-24
last_updated: 2026-06-24
clarity_check:
  date:
  verdict:
  warnings: []
  blockers: []
blocked_by:
  - M1-445
files_budget: 8
files_scope: []
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Do NOT change the surefire version pin itself (that is M1-445) or any test/production code."
  - "Do NOT introduce a hosted CI system (no GitHub Actions account/secrets in scope); the tripwire must be a build-time check that runs inside `mvn verify` itself, so it protects every local and future-CI run equally."
  - "Do NOT broaden into a suite-wide time-bomb sweep or a general dependency-version audit."
acceptance:
  - "A Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`) is committed, pinning a Maven version whose super-pom is JUnit-5-capable (Maven 3.9.x). Rationale: the root enabler of the silent skip (M1-445) was that nothing pinned the Maven version, so test execution depended on the ambient `mvn` (3.8.7 here → super-pom surefire 2.12.4). The wrapper makes the toolchain reproducible; combined with M1-445's explicit surefire pin it is belt-and-suspenders."
  - "The repo's build entrypoints invoke the wrapper, not a bare `mvn`, so the pinned toolchain is actually used: `scripts/verify-serialized.sh` (the /m1-tick verify gate) and `dev/scripts/build.sh`, plus any build instructions in docs (DEVELOPER.md / docs/design/07-deployment.md) that currently say `mvn ...`. Confirm each referenced path exists before editing; leave any that already use `./mvnw` unchanged."
  - "A build-time tripwire fails `mvn verify` if a module that HAS JUnit 5 unit (`*Test`) sources executes zero unit tests — so a future surefire-version regression or Maven downgrade can never again skip the suite while staying green. Implementer's choice of mechanism (e.g. surefire `failIfNoTests` scoped to modules with unit tests, or a maven-enforcer/groovy check); document the chosen mechanism and why it does not false-positive on modules that legitimately have only `*IT` tests or no tests."
  - "With the wrapper + tripwire in place, `./mvnw -B clean verify` from the repo root exits 0 and the tripwire demonstrably trips (in a scratch experiment, not committed) when surefire is forced back to a JUnit-4-only version."
  - "mvn -B clean verify (and ./mvnw -B clean verify) from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main (including the unit suite activated by M1-445)
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-446: pin the Maven toolchain + non-empty-unit-suite tripwire

## Context

Hardening follow-up to M1-445. The silent unit-suite skip had two root enablers:

1. **No surefire version pinned** → super-pom default (2.12.4 on Maven 3.8.x) →
   JUnit 5 unit tests discovered as 0. (Fixed directly by M1-445.)
2. **No pinned Maven version** (no wrapper) and **no CI** → whether the suite ran
   depended on the ambient `mvn` binary, and nothing ever asserted the suite was
   non-empty. So the skip was invisible: `Tests run: 0` plus a large green `*IT`
   suite reads as a healthy build.

M1-445 fixes (1) and is sufficient to make the suite run. This ticket closes (2)
so the failure mode cannot recur by a different route (a Maven downgrade, the pin
being dropped in a refactor, a new module forgetting the activation): the wrapper
makes the toolchain reproducible, and the tripwire turns "0 unit tests ran" from
a silent green into a hard build failure.

## Why a tripwire, not just the pin

The pin (M1-445) is a value that a future edit can remove or a new module can
miss. The whole class of bug here is "tests silently not executing while the build
stays green." The only durable defense is a check that FAILS when the expected
tests don't run — the same philosophy as the anti-fakery posture in the
engineering rules, applied to the build config itself.

## Notes / design considerations

- **Tripwire false-positive hazard:** a blanket surefire `failIfNoTests=true`
  would break any module that legitimately has only `*IT` tests or no tests. Scope
  the check to modules that actually contain `*Test` sources, or assert at the
  reactor level. Document the choice.
- **Wrapper version:** pick a current Maven 3.9.x. Verify `./mvnw -version`
  resolves it offline-friendly (the wrapper downloads on first use).
- This ticket does NOT block M1-441 (M1-441 needs only M1-444 + M1-445). It can
  proceed in parallel once M1-445 lands.
- May be decomposed at `start` if the wrapper and the tripwire warrant separate
  tickets; size accordingly.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-446-pin-maven-toolchain-and-empty-suite-tripwire.md
```
