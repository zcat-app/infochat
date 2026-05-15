---
id: M1-029
title: Loosen wall-clock tolerance on bodyReadTimeoutFiresOnSlowUpstream
status: pending
created: 2026-05-15
last_updated: 2026-05-15
blocked_by: []
files_budget: 1
files_scope:
  - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any change to infochat-ssrf production code (SsrfGuardedHttpClient,
    IpBlocklist, UrlRedactor, etc.) — this is a test-only tolerance fix;
    the SUT's body-read-timeout behavior is correct, the assertion's
    wall-clock window is the bug
  - any other test in SsrfGuardedHttpClientTest (the other 13 tests
    pass cleanly; only bodyReadTimeoutFiresOnSlowUpstream is flaky)
  - any infochat-ssrf module config, pom.xml, or dependency edit
  - any other module's tests (the flake is scoped to one assertion)
  - any change to the body-read-deadline test (M1-026 total
    bodyReadDeadline test, immediately below the flaky one — separate
    surface, has its own tolerance discipline)
acceptance:
  - "infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java::bodyReadTimeoutFiresOnSlowUpstream still asserts the exception message starts with the literal 'body read timeout' (the correctness assertion — grep -E 'startsWith\\(\\\"body read timeout\\\"' SsrfGuardedHttpClientTest.java returns at least one match within the bodyReadTimeoutFiresOnSlowUpstream method body)"
  - "The wall-clock upper-bound assertion no longer uses the 500ms tolerance — grep -E 'readTimeout\\.toMillis\\(\\)\\s*\\+\\s*500' SsrfGuardedHttpClientTest.java returns zero matches inside the bodyReadTimeoutFiresOnSlowUpstream method body"
  - "The replacement upper bound is the request-level timeout (30s) — the meaningful correctness boundary is 'the per-read watchdog fires before the request-level timeout would have'; grep -E 'requestTimeout|Duration\\.ofSeconds\\s*\\(\\s*30\\s*\\)|30_000|30000' SsrfGuardedHttpClientTest.java returns at least one match inside the bodyReadTimeoutFiresOnSlowUpstream method body's wall-clock assertion"
  - "mvn -B -pl infochat-ssrf test -Dtest=SsrfGuardedHttpClientTest exits 0 on three consecutive runs (the test is the per-read watchdog assertion; stability under scheduler jitter is the whole point of the change)"
  - "mvn -B clean verify from the repo root exits 0; no other test regresses"
test_plan:
  adds: []
  preserves:
    - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java (the other 13 tests in this class)
    - infochat-ssrf/src/test/java/io/infochat/ssrf/UrlRedactorTest.java (M1-024)
    - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java (M1-024)
    - all prior M1-024, M1-025, M1-026 tests
spec_refs:
  - docs/spec/security.md §Network controls (only as the threat-surface
    that the SUT defends; this ticket touches the test, not the SUT)
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-029: Loosen wall-clock tolerance on bodyReadTimeoutFiresOnSlowUpstream

## Context

During M1-027's `mvn verify` run, `SsrfGuardedHttpClientTest.bodyReadTimeoutFiresOnSlowUpstream`
failed with `timeout must fire within readTimeout + 500ms;
elapsed=1549ms (readTimeout=1000ms)` — 49ms over the assertion's
500ms tolerance window. A retry passed cleanly. The flake is
scheduler/JVM jitter, not a bug in the SUT: the per-read timeout
DID fire (the exception is `SsrfPolicyException` with the literal
`body read timeout` prefix, as the prior assertion confirms);
the wall-clock window was just too tight.

This ticket loosens the wall-clock upper bound to the meaningful
correctness boundary (the request-level timeout, 30s) so the test
is stable across loaded machines / GC pauses / kernel scheduler
jitter without weakening the actual correctness check.

## Definition of Done

- `bodyReadTimeoutFiresOnSlowUpstream` continues to assert that
  the exception message starts with `body read timeout` (this is
  the SUT-behavior assertion and is unchanged — the per-read
  watchdog must fire, not the request-level timeout).
- The wall-clock upper-bound assertion no longer uses
  `readTimeout.toMillis() + 500` as the bound. The replacement
  bound is the configured request-level timeout (30s in this
  test) — the test now asserts "the per-read watchdog fires
  before the request-level timeout would have", which is the
  actual correctness boundary the watchdog is meant to enforce.
- The infochat-ssrf `bodyReadTimeoutFiresOnSlowUpstream` test
  passes on three consecutive `mvn -B -pl infochat-ssrf test
  -Dtest=SsrfGuardedHttpClientTest` runs.
- `mvn -B clean verify` from the repo root exits 0 with no
  regressions in any other test.

## Implementation notes

- Current line (approximate, in
  `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java`
  around line 348):
  ```java
  assertTrue(elapsed < (readTimeout.toMillis() + 500),
      "timeout must fire within readTimeout + 500ms; elapsed="
      + elapsed + "ms (readTimeout=" + readTimeout.toMillis() + "ms)");
  ```
- Replacement shape (the request timeout is `Duration.ofSeconds(30)`
  in this test per the `SsrfGuardedHttpClient` constructor call
  ~line 335; reference that value, don't hardcode `30_000`):
  ```java
  assertTrue(elapsed < (readTimeout.toMillis() + 5_000),
      "per-read watchdog must fire well before the request-level "
      + "timeout (30s) — if elapsed is near the request timeout, "
      + "it's the wrong code path firing; elapsed="
      + elapsed + "ms (readTimeout=" + readTimeout.toMillis() + "ms)");
  ```
  5s tolerance leaves abundant headroom for scheduler jitter,
  GC pauses, and CI load while still falsifying "the per-read
  watchdog never fired and the request-level timeout eventually
  triggered instead" (which would yield elapsed ~ 30000ms, well
  above 5s).
- Alternative: drop the wall-clock assertion entirely and rely
  on the exception-type + message-prefix assertions to confirm
  the per-read watchdog fired (the request-level timeout would
  surface a different exception or a different prefix). This is
  more robust but loses the "early termination" signal. The
  loosened-tolerance approach above keeps the signal at a value
  that's stable under realistic conditions.

## Big-picture notes

- The flake will recur on any sufficiently-loaded machine
  (VPN/Docker activity, container scheduling latency, GC
  pauses, virtual-thread carrier-pool contention from the
  rest of the test suite). The 500ms tolerance was always
  going to be marginal at 1000ms readTimeout — a 50% overhead
  is well within scheduler jitter.
- Per [[feedback_vpn_localhost]] in author-memory: localhost
  Docker traffic can be filtered/delayed by VPN; the test
  uses a real loopback `ServerSocket` (per the
  `LoopbackPermitting` policy), so VPN filtering doesn't
  block but scheduling jitter can still inflate the elapsed
  time.

## Out-of-scope expansion

- **Any change to SsrfGuardedHttpClient or other infochat-ssrf
  production code.** The SUT is correct — the per-read
  watchdog fires within the timeout's natural jitter window;
  this ticket only adjusts the test's wall-clock assertion.
- **Any other test in the class.** Only
  `bodyReadTimeoutFiresOnSlowUpstream` has demonstrated
  flakiness; the other 13 tests pass cleanly.
- **The M1-026 total `bodyReadDeadline` test** (the one
  immediately below the flaky test). Different correctness
  surface, different tolerance discipline, not flaky.

## Authorized test changes

- `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java::bodyReadTimeoutFiresOnSlowUpstream`
  — replace the `readTimeout.toMillis() + 500` upper bound
  with a bound rooted in the request-level timeout (per
  Implementation notes above). This is the only test
  modification authorized. The exception-type assertion and
  the message-prefix assertion remain untouched.

## Alternatives considered

- **Drop the wall-clock assertion entirely.** Cleanest in the
  abstract but loses the "early termination" signal — without
  it, a regression where the per-read watchdog silently waits
  for the request-level timeout (30s) instead of firing at
  ~1s would still pass the exception-prefix check (the
  `SsrfGuardedHttpClient` may set the same prefix on either
  path) and the test would silently weaken. The loosened
  tolerance keeps the signal while removing the jitter
  sensitivity.
- **Raise `readTimeout` from 1s to 5s and keep the 500ms
  tolerance.** Equivalent stability gain but makes the test
  ~4s slower; the test runs in CI on every push, so the
  budget matters. Loosening the tolerance is the cheaper fix.
- **File the flake under M1-026 (the ticket that landed the
  surrounding hardening).** Rejected — M1-026 is `done`;
  per `CLAUDE.md` §M1 workflow ("Never amend a passed
  commit. Defects found after a passed review become a new
  ticket and a new commit"), the fix lands in a new ticket.
