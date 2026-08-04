---
id: M1-764
title: "Pin the LLM transport's interrupt contract with a test"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: []
files_budget: 2
files_scope:
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/HttpProviderSharedPipelineTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY PRODUCTION CHANGE. This ticket adds a characterization test and
    nothing else. `LlmHttpSupport.java`, the three HTTP providers and the
    decorators stay byte-identical. If the test reveals the contract is
    ALREADY broken — the flag is not re-armed, or a request goes out on an
    interrupted thread — that is a premise-fail: ESCALATE rather than
    fixing in place, because the fix would be a live-spend defect worth its
    own ticket and its own redteam, not a silent rider on a test ticket.
  - >-
    THE CALLER SWEEP. This pins ONE contract at the transport floor: an
    already-armed interrupt stops the outbound call and survives it.
    Auditing every `sanitize()` / LLM caller for code that CLEARS the flag
    (the other half of the property — see §Notes) is a wider census across
    two modules and is deliberately not attempted here.
  - >-
    `LlmOutputSanitizer.emitAuditRows`' park-and-restore. That shipped in
    M1-763 with its own IT
    (`LlmOutputSanitizerAuditRowIT.auditRowStillLandsWhenTheCallingVirtualThreadIsInterrupted`)
    and is not re-tested here.
  - >-
    `DigestWorker`'s cancellation path. M1-763 shipped it with
    `DigestWorkerTest.execute_renderOverrunningWindow_stopsSpendingProviderCalls`.
    This ticket tests the layer BENEATH that one, not that one again.
  - >-
    THE AGGREGATE SYSTEM LLM BUDGET. `docs/spec/security.md` §Rate limiting
    claims one exists as the backstop for digest cost; it does not exist in
    code. Pre-existing gap recorded at the spec site under M1-758. This
    ticket does not supply it.
acceptance:
  - >-
    A new test in `HttpProviderSharedPipelineTest` — suggested name
    `interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed` — drives the
    shared `sendForBody` pipeline (via either HTTP provider's `generate`,
    the way the existing tests in that class do) on a thread whose
    interrupt flag is ALREADY SET, and asserts all three of:
    (a) the call throws `LlmCallFailedException`;
    (b) `Thread.currentThread().isInterrupted()` is STILL true after the
    throw — this is the `LlmHttpSupport.sendForBody` catch re-arming the
    flag rather than swallowing it;
    (c) the class's `HttpServer` received ZERO requests for the call —
    the no-spend half. The existing harness serves fixed responses via
    `respondToBothEndpoints`; count invocations in the handler (or assert
    on a request counter) so "no request" is asserted, not assumed.
  - >-
    The test clears the interrupt flag before it returns (a
    `Thread.interrupted()` in a `finally`, or run the body on its own
    thread), so an armed flag cannot leak into whichever test JUnit runs
    next in the same JVM. A leaked flag would surface as an unrelated
    flake elsewhere in the module.
  - >-
    STATE THE FALSIFICATION IN THE COMMIT. Confirm the test actually fails
    when the contract is removed — temporarily delete the
    `Thread.currentThread().interrupt()` re-arm in
    `LlmHttpSupport.sendForBody`'s `InterruptedException` catch, observe
    assertion (b) fail, then restore. A characterization test that passes
    against both states pins nothing, which is the entire risk this ticket
    exists to retire.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/HttpProviderSharedPipelineTest.java
  preserves:
    - >-
      Every existing test in `HttpProviderSharedPipelineTest` (non-2xx
      surfacing, body-cap clamp, malformed-base-url and non-positive-timeout
      startup scans) and its `@BeforeEach`/`@AfterEach` server lifecycle.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/llm.md §Failure handling
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
escalation_reason:
---

# M1-764: Pin the LLM transport's interrupt contract

## Context

M1-763 made a timed-out digest render actually stop spending LLM calls, by
switching the render's submission to `renderExecutor.submit(...)` so
`cancel(true)` delivers a real interrupt. Its round-2 red-team recorded, as
an out-of-model item, that the fix's efficacy rests on an **external
contract nothing in this repo tests**:

1. `java.net.http.HttpClient.send` fails fast when the calling thread's
   interrupt flag is already set — it throws `InterruptedException` without
   opening a socket.
2. `LlmHttpSupport.sendForBody`'s `InterruptedException` catch calls
   `Thread.currentThread().interrupt()` **before** rethrowing as an
   unchecked `LlmCallFailedException`, RE-ARMING the flag that step 1's
   `Thread.interrupted()` cleared.

Together those two make every remaining generative call in an interrupted
render a no-op that costs nothing. Break either and the render silently
resumes full-speed spend, with **no test failing** — the M1-763 worker test
would still pass, because it asserts on a stub, not on the transport.

Both facts were verified by hand at M1-763 time (a scratch probe on this
project's JDK 25: flag set → `InterruptedException` in 0 ms, zero sockets
opened). Nothing captured that verification in the suite. This ticket does.

## Why `security_relevant: true` on a test-only diff

The property being pinned is a cost/DOS control: an unbounded orphaned
render is the exposure M1-763 closed, and `docs/spec/security.md` §Rate
limiting has no aggregate LLM budget to catch it if the ceiling regresses.
A test-only diff introduces no new attack surface, so a CLEAN verdict is the
expected outcome — the flag is set so the gate is not skipped on a control
whose regression is invisible, not because the diff is suspected.

## Thread type: platform is fine here

M1-763's audit test had to run on a **virtual** thread, because that case
depended on in-flight socket I/O aborting (`SocketException: Closed by
interrupt`), which platform threads do not do. **This test does not.** The
`HttpClient.send` entry check is thread-type independent — verified on a
platform thread (0 ms, no socket). Write it as an ordinary unit test; do not
copy the virtual-thread machinery from `LlmOutputSanitizerAuditRowIT`, which
is there for a different reason.

## The other half, deliberately not in scope

The full property is "an armed interrupt reaches the spend AND nothing on
the render path clears it." This ticket pins the first clause at the
transport floor. The second clause is a census across the provider and
llm-adapter modules for code that consumes the flag without restoring it —
M1-763 already hardened the one such site it found
(`LlmOutputSanitizer.emitAuditRows`, which now parks and restores). A
systematic sweep is a separate ticket if it is ever judged worth the cost;
`out_of_scope` records the boundary so a future reader does not mistake this
ticket's green test for the wider guarantee.

## Notes

- Implementation lives entirely in
  `infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/HttpProviderSharedPipelineTest.java`,
  which already owns the harness this needs: a real `com.sun.net.httpserver.HttpServer`
  on an ephemeral port with `@BeforeEach` setup / `@AfterEach` teardown, and
  a `respondToBothEndpoints` helper. No new test class, no new fixture.
- The ID `M1-764` appears once in
  `docs/plan/m1/redteam/M1-761-2026-08-04.md` as a ticket that was planned
  and then **never filed** (that file's own disposition note says so, and
  the M1-761 finding was fixed in-ticket instead). This ticket is unrelated
  to that abandoned plan; the ID was free and allocated normally.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-764-llm-transport-interrupt-contract.md`
