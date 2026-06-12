---
id: M1-317
title: "Parallel-worktree IT isolation: random test port + verify lock"
status: done
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/test/resources/application.properties
  - infochat-provider/src/test/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/startup/CollectorReadinessIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health/ProviderReadinessEndpointIT.java
  - scripts/verify-serialized.sh
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Wiring scripts/verify-serialized.sh into the /m1-tick verify-capture step — that edits .claude/skills/m1-tick/ and lands as a separate `process:` commit, not this ticket.
  - The production HTTP ports (provider quarkus.http.port=8081, collector 8080) — only the %test port changes; production binding is unchanged.
  - infochat-core test config — left untouched unless its tests are found to bind the HTTP test port (see Notes).
  - Any test modification other than the two readiness ITs named in Acceptance.
acceptance:
  - "Under the %test profile, infochat-collector and infochat-provider set quarkus.http.test-port=0 so each module's Quarkus test binds an OS-assigned ephemeral port instead of the fixed 8081; two concurrent worktree `mvn verify` runs no longer fail with QuarkusBindException: Port already bound: 8081."
  - "CollectorReadinessIT and ProviderReadinessEndpointIT — which currently hardcode 8081 — read the injected test port (RestAssured's resolved port / @TestHTTPResource / the quarkus.http.test-port value) instead of the literal 8081, and pass against the ephemeral port. These two pre-existing ITs are modified intentionally; the change is authorized by this ticket."
  - "scripts/verify-serialized.sh wraps `mvn -B clean verify` in `flock` on a shared lockfile so concurrent worktree verifies run one at a time (bounding peak memory to a single integration-test suite) rather than racing; the lock is released on exit (normal or error) and the script forwards mvn's exit code."
  - "mvn -B clean verify exits 0 from the repo root."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/startup/CollectorReadinessIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/health/ProviderReadinessEndpointIT.java
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
      files: 7
      added: 68
      removed: 12
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-317: Parallel-worktree IT isolation: random test port + verify lock

## Context

The M1 build runs many per-ticket worktrees, and each runs
`mvn -B clean verify`. Every Quarkus integration test binds the test
HTTP port, which defaults to a FIXED `8081` (`quarkus.http.test-port`).
When two worktree verifies reach their IT phase at the same moment, one
dies with `QuarkusBindException: Port already bound: 8081` — observed
repeatedly (e.g. M1-213, and again during M1-291's round-1 verify, where
`BootstrapLoaderIT` and `Stage1WatchdogIT` both failed at Quarkus boot
with zero relation to the diff under test). The current mitigation is an
ad-hoc poll-loop guard in the verify command, which has a TOCTOU race
(another worktree can grab the port between the guard passing and mvn
binding). This ticket removes the collision class properly.

Two complementary changes, addressing two distinct constraints:
- **Random test port** (`quarkus.http.test-port=0`) removes the *port
  collision* — each suite gets an OS-assigned ephemeral port, so
  concurrent runs cannot clash, and a leaked JVM holding 8081 can no
  longer cause a false failure.
- **`flock`-serialized verify** removes the *memory* pressure — running
  N heavyweight IT JVMs (+ their Dev Services containers) at once is the
  real resource cost; a shared file lock serializes verifies to one at a
  time without a busy-poll.

A unique port costs no memory; it is the parallel JVMs that do. So the
two knobs are independent: the random port fixes correctness, the lock
fixes resource budget.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Grep at filing time found exactly two ITs hardcoding `8081`:
  `CollectorReadinessIT` and `ProviderReadinessEndpointIT`. These are the
  ones that break under `test-port=0` and must move to the injected port.
  Re-grep `8081` across `src/test` before implementing in case more have
  landed.
- The main `application.properties` of both services documents that
  `quarkus.http.port` (8081 provider / 8080 collector) is inert under
  `%test` because `@QuarkusTest` binds `quarkus.http.test-port` instead —
  so the `%test` override is the correct, narrow lever.
- Check whether `infochat-core` integration tests bind the HTTP test port
  (it has a `src/test/resources/application.properties`); include it in
  the `%test` change only if so, otherwise leave it out (kept out of
  `files_scope` deliberately).
- `flock` is a `util-linux` shell utility — not a Maven dependency, not
  in `src/`; the helper script is the only in-repo artifact. Wiring it
  into the `/m1-tick` verify step is a follow-up `process:` edit.
- Alternatives considered: keeping the poll-loop guard (rejected — TOCTOU
  race, busy-poll); per-worktree fixed port offsets (rejected — manual
  bookkeeping, still collides on overlap). Random port + lock dominates
  both.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-317-*.md
```
