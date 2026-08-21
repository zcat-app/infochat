---
id: M1-901
title: "Liveness IT: subs responder survives reconnect swap"
status: done
created: 2026-08-21
last_updated: 2026-08-21
flow: tick
reproduction: >-
  SimpleXSmpSessionLivenessTest.sustainedZeroSessionsLatchesAndRestarts —
  observed failing on the v2.0.0 gate run at RC 421fab0a
  (.scratch/verify-1314665.log:1303-1306, :1614-1616; summary
  .scratch/verify-v2.0.0-c12.log:7,13): "expected a zero-session poll
  outcome, got FAULT" at pollExpectingZero(SimpleXSmpSessionLivenessTest.java:446)
  called from :122 (the second-episode poll, after restart #1), elapsed
  10.46 s — one full SESSION_POLL_ACK_TIMEOUT burn (SimpleXAdapter.java:135).
  The wrong behavior it states: the test harness's own subs-responder thread
  can die silently during the fake's client-generation swap, after which
  every poll faults unanswered and the test reports a misleading poll FAULT
  10 s late instead of failing fast on the harness fault. INTERMITTENT: 3×
  isolated single-class re-runs on the same RC all green
  (.scratch/d12-repro-r{1,2,3}.log, .scratch/d12-repro-summary.txt), so the
  original failure cannot be run RED on demand; the deterministic RED drives
  are the two failure-mode tests named in acceptance.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSmpSessionLivenessTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any src/main change. The product poll behaved correctly in the failing
    run — an unanswered `/get subs` is a transport fault and FAULT is the
    honest outcome, never a false zero-session latch (SimpleXAdapter.java
    :1393-1401; docs/spec/messaging.md:429-447). No production change is
    justified by the evidence (M1-655's fix-is-test-side precedent).
  - >-
    Rework of FakeSimpleXProcess semantics — awaitFrame/sendFrame/runReader
    and the `__READER_ERROR__` marker channel stay byte-identical; the only
    fake change is one additive test seam. The marker channel is the fake's
    documented contract (FakeSimpleXProcess.java:303-310); the consumer bug
    lives in the responder.
  - >-
    Hardening the sibling responder copies in other simplex test suites
    (SimpleXReconnectTest.startSendResponder :613-637, the pacing/fallback/
    address-query/attachment ackers). Grep-verified: none of them drive an
    adapter-side reconnect without a preceding fake.killClientConnection()
    (which sets killedSocket and suppresses the marker,
    FakeSimpleXProcess.java:114-120), so the trigger is unreachable there
    today. If one flakes later, file a sibling ticket citing this one.
  - >-
    Any spec edit (docs/spec/messaging.md §Failure handling is untouched —
    no promise changes), and any ControllableProbeScheduler-style rework
    (M1-889's pattern is not applicable: the nondeterminism is socket
    teardown timing at the fake, not scheduler pacing; the poll is already
    driven synchronously with a pinned clock).
acceptance:
  - "REPRODUCTION, hardened: SimpleXSmpSessionLivenessTest.sustainedZeroSessionsLatchesAndRestarts
    passes, and its second-episode poll can no longer fail 10 s late with a
    misleading \"got FAULT\" caused by a dead responder — a dead responder
    fails the drive IMMEDIATELY naming the harness cause. Verified by the
    two failure-mode tests below plus a green full-suite run. NOTE: the
    original failure is intermittent (3 green isolated re-runs,
    .scratch/d12-repro-summary.txt) — green re-runs alone are NOT the
    evidence of the fix; the deterministic failure-mode tests are."
  - "FAILURE-MODE (P3/P5): SimpleXSmpSessionLivenessTest.subsResponderSurvivesFakeReaderErrorMarker
    passes — injects the fake's recorded `__READER_ERROR__` marker
    (FakeSimpleXProcess.java:309) into the frame queue mid-test via the new
    seam, then drives a poll and asserts it is answered ZERO_SESSIONS (the
    responder survived the marker and kept serving). Written and run RED
    first (workflow §0): on main the responder's blind readTree
    (SimpleXSmpSessionLivenessTest.java:419) dies on the marker and the poll
    burns the full 10 s SESSION_POLL_ACK_TIMEOUT into FAULT. Mutation it
    catches: a responder that skips the marker but dies on the next send."
  - "FAILURE-MODE (P3/P6): SimpleXSmpSessionLivenessTest.deadSubsResponderFailsFastNamingTheHarnessFault
    passes — kills the responder with a malformed JSON frame and asserts the
    poll drive throws an AssertionError whose message/cause names the
    responder death, never \"expected a zero-session poll outcome, got
    FAULT\". Mutation it catches: reverting pollExpectingZero's fail-fast
    check (the test then fails on the message assertion after the 10 s burn,
    exactly the D-12 symptom)."
  - "§8 test-modification authorization + no weakened assertions (P2/P4),
    verified by probe: git diff -U0 on the test file shows no changed
    assert*/await*/count lines inside the six pre-existing methods — their
    diff is confined to threading the responder-failure handle through the
    startSubsResponder / pollExpectingZero call sites and every pre-existing
    assertion is byte-identical. This item IS the engineering-rules §8
    authorization, stated plainly: the responder helper now (a) skips the
    fake's documented non-JSON marker channel instead of parsing it, (b)
    drops a single answer whose send fails on a superseded client socket
    instead of dying, and (c) records any other death (parse failure, await
    timeout) and has pollExpectingZero surface it as the primary failure —
    because a harness fault must never masquerade as a hung poll.
    pollExpectingZero still throws on FAULT whenever the responder is alive;
    no product assertion is weakened."
  - "Shared-fake additive seam only (P7), verified by grep probe and
    unmodified consumers: the FakeSimpleXProcess diff adds the injection
    seam (a package-private method enqueueing a raw string onto the
    received-frame queue) and changes NOTHING else — awaitFrame / sendFrame
    / runReader / the marker semantics byte-identical;
    SimpleXWebSocketClientTest, SimpleXReconnectTest and every other fake
    consumer pass UNMODIFIED (grep-verified 2026-08-21: no test asserts on
    `__READER_ERROR__` content — only FakeSimpleXProcess.java references the
    string)."
  - "mvn verify from repo root is green; plus SimpleXSmpSessionLivenessTest
    run repeatedly (10 consecutive module-scoped runs, e.g. mvn -pl
    infochat-messaging-adapter test -Dtest=SimpleXSmpSessionLivenessTest)
    all green, with the counts reported in the commit message —
    intermittent-fix confidence per engineering-rules §5, not proof by a
    single green."
test_plan:
  adds:
    - >-
      infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSmpSessionLivenessTest.java
      — subsResponderSurvivesFakeReaderErrorMarker,
      deadSubsResponderFailsFastNamingTheHarnessFault (map to acceptance
      items 2 and 3)
  modifies:
    - >-
      infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSmpSessionLivenessTest.java
      — startSubsResponder (:414-431) hardened, pollExpectingZero (:435-453)
      gains the fail-fast check, the six existing methods thread the
      failure handle. Authorized explicitly by acceptance item 4: no
      existing assertion changes.
    - >-
      infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
      — one additive package-private test seam; no behavioral change.
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-21
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "4 files changed, 133 insertions(+), 35 deletions(-)"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-901-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-901: Liveness IT — subs responder survives reconnect swap

## Context

D-12 from the v2.0.0 verification campaign: on the full-suite gate run at RC
421fab0a (2026-08-20 22:09), M1-890's own liveness IT
`SimpleXSmpSessionLivenessTest.sustainedZeroSessionsLatchesAndRestarts`
failed with "expected a zero-session poll outcome, got FAULT" at
pollExpectingZero(:446) called from :122 — the second-episode poll after
restart #1 — after a 10.46 s burn (.scratch/verify-1314665.log:1303-1306,
:1614-1616; messaging-adapter tally 404 tests / 1 failure, reactor BUILD
FAILURE before collector/provider, .scratch/verify-v2.0.0-c12.log:7,13).
Three isolated single-class re-runs on the same RC all went green (10/10
each, .scratch/d12-repro-summary.txt): a load/timing-dependent intermittent
race in the test harness, not a deterministic regression and not a product
defect. It still blocks a trustworthy v2.0.0 release gate until fixed or
owner-overridden (.scratch/V2.0.0-DEFECTS-AND-SMELLS-LOG.md D-12;
.scratch/V2.0.0-VERIFICATION-PLAN-2026-08-20.md §5). Test-only scope. This
is a single-ticket decomposition; this body is the analysis
(`analysis_ref: self`).

## Root cause

Verified against the code on 2026-08-21; the candidate mechanism in the
brief is CONFIRMED in its end state (silent responder death → unanswered
polls → FAULT 10 s later), with two precision corrections to its kill path.

The chain:

1. The zero-session latch fires `sub.restartHung()`
   (SimpleXAdapter.java:1426); the supervised respawn fires the restart
   listener → `onSubprocessRestart()` (:540-543) → `reconnect()`, which
   closes the superseded WS client (`old.close()`, :565).
   `SimpleXWebSocketClient.close()` calls `ws.abort()`
   (SimpleXWebSocketClient.java:273-281) — an abrupt teardown with NO WS
   close frame.
2. At the fake, the superseded socket's reader thread
   (FakeSimpleXProcess.runReader, :260-312) therefore never sees an opcode
   0x8 close frame from an adapter-driven swap; it exits on either a clean
   EOF (`in.read() == -1` → silent return) or an IOException such as
   connection reset. Which one is OS/timing-dependent — that is the
   nondeterminism, and why isolated re-runs (no load) go green.
3. On IOException the reader enqueues `__READER_ERROR__:<class>` into the
   frame queue (:303-310). The `killedSocket` suppression (:56-59, :114-120)
   does NOT apply: it is set only by the test-choreographed
   `killClientConnection()`, never by an adapter-side close. This is exactly
   why SimpleXReconnectTest — whose responder has the identical shape
   (:613-637, verified: same blind `readTree` at :618, same blanket
   swallow-only catch at :633-635) — is not exposed: every reconnect there
   is preceded by `fake.killClientConnection()` (:168, :198, :237, :273,
   :307, :357, :408).
4. The liveness test's single responder thread
   (SimpleXSmpSessionLivenessTest.startSubsResponder, :414-431) blindly runs
   `MAPPER.readTree(envelope)` (:419) on every queued envelope. The marker
   is not JSON → JsonProcessingException → swallowed by the blanket
   `catch (Exception e)` at :427-429 → the thread exits SILENTLY. A
   secondary path with the same end state: `fake.sendFrame` (:425) writing
   to the stale `clientSocket` during the swap window throws IOException →
   same silent catch.
5. `fake.awaitClientGeneration(2, WAIT)` (:121) still passes — it observes
   the fake's handshake bookkeeping (FakeSimpleXProcess.java:96-105,
   incremented at :207), not the responder's health.
6. Every episode-2 poll's `/get subs` frame is queued but never answered →
   `ws.sendCommand(..., SESSION_POLL_ACK_TIMEOUT)` (SimpleXAdapter.java:135,
   :1395-1397) times out after 10 s → MessagingException →
   `SessionPollOutcome.FAULT` (:1398-1401) → pollExpectingZero throws at
   :446 on the FIRST episode-2 poll. Elapsed 10.46 s = one full ack-timeout
   burn plus ~0.4 s of episode 1. Matches the log exactly
   (.scratch/verify-1314665.log:1303-1306).

The product behaved correctly throughout: an unanswered poll IS a transport
fault and FAULT is the honest report (messaging.md:429-447) — no false
zero-session latch. **No production change is warranted** (this would be the
hurdle-shaped finding if it were; it is not).

Discrepancies between the brief/defect-log and the code (verified facts
win):

- Brief/defect log say "catch-and-print at ~:427-429" — the catch PRINTS
  nothing; it swallows with only a comment (:427-429). The silence is worse
  than described; the mechanism is unaffected.
- Brief says "an awaitFrame IOException can kill that thread" — awaitFrame
  does no socket IO (it polls a BlockingQueue, FakeSimpleXProcess.java
  :148-155); the killing input is the enqueued `__READER_ERROR__` marker
  hitting the blind `readTree`, or a sendFrame IOException on the stale
  socket. Same end state.
- Brief cites "latch at :1412" — the latch assignment is :1415 (:1412 is
  the threshold comparison). Cosmetic.
- Not directly observable from the captured log: which kill input fired on
  the failing run (marker parse vs stale send). What is proven: the harness
  CAN die silently by both paths, both are timing-dependent, and the
  symptom (10.46 s burn, FAULT at :122, generation barrier green) admits no
  other code path. The fix covers the class, so the ambiguity does not
  block the ticket.

## Pitfalls

- P1: Touching production — the failing run showed CORRECT product behavior
  (FAULT on a genuinely unanswered poll; messaging.md:429-447). Any
  src/main "hardening" is scope drift (§1) and would re-open M1-890's
  settled semantics. The defect is entirely in the test harness (M1-655:
  "the fix is test-side" precedent).
- P2: Tolerance instead of removal — making pollExpectingZero tolerate or
  retry FAULT (like SKIPPED) would hide the symptom AND destroy the
  assertion's discriminating power: a live, answering harness that faults
  is a real product regression signal. §8 semantic (weakened assertion) and
  M1-655's rejected "retry-looping the probes" lesson: remove the
  nondeterminism, never add tolerance to it.
- P3: Whack-a-mole on one kill path — skipping only the non-JSON marker
  leaves the sendFrame-to-stale-socket IOException and the 15 s awaitFrame
  timeout as silent deaths under different timing. The fix must cover the
  class: per-frame resilience for the two transient shapes PLUS a recorded,
  loudly-surfaced death for everything else.
- P4: Weakening pre-existing assertions while threading the new handle —
  six existing methods change call sites; every assertion they contain
  (latch, TRANSIENT latched-window send, once-per-episode restart, boot
  grace, WS-dead skip, crash-cap accounting, D37 WARN hygiene) stays
  byte-identical. §8 semantic + test-modification authorization (carried
  by acceptance item 4).
- P5: Vacuous failure-mode tests — §8 assertion-adequacy. The marker test
  must assert the poll is ANSWERED after the marker (ZERO_SESSIONS), not
  merely that nothing threw: a responder that skips the marker but dies on
  the next send must still fail it. The fail-fast test must assert on the
  failure MESSAGE/cause, not just that some AssertionError flew.
- P6: Teardown false positives — `responder.interrupt()` in each finally
  (:127, :164, :201, :236, :293, :332) is the NORMAL exit and must record
  no failure, or every green run fails loud. Interrupt is distinguished
  from a wedge (await timeout) and from a fault (parse/IO): only the
  latter two record.
- P7: Shared-fake blast radius + proof-by-green — FakeSimpleXProcess is
  consumed by 11 test suites in its package (SimpleXWebSocketClientTest,
  SimpleXReconnectTest, SimpleXAdapterAttachmentTest, ...); the seam must
  be purely additive (§5 full-suite green is the gate). And the original
  failure is intermittent: "it passed N times" proves nothing — the
  deterministic hostile-input tests are the evidence (§2/§5).

## Approach

No spec_refs (legally empty on a defect ticket): no spec promise changes —
the ticket repairs the harness that VERIFIES messaging.md §Failure handling;
the spec text itself is untouched and the product already honors it.

- **Files to touch:** `files_scope:` (two test-side files).
- **Steps, in order:**
  1. FakeSimpleXProcess: add ONE additive package-private seam that
     enqueues a raw string onto the received-frame queue (the queue
     awaitFrame polls, :148-155). No other line changes.
  2. Write the two failure-mode tests (acceptance items 2-3) against the
     CURRENT responder and run them RED on main (workflow §0): the marker
     test burns ~10 s into FAULT; the fail-fast test fails its message
     assertion. This is the deterministic stand-in for the un-runnable
     intermittent reproduction.
  3. Harden startSubsResponder (:414-431), keeping its loop shape:
     - `awaitFrame` InterruptedException → return (teardown, records
       nothing — P6);
     - `awaitFrame` timeout (IllegalStateException) → record into an
       `AtomicReference<Throwable>` and return: 15 s without a poll frame
       mid-test is a wedge, not teardown, and must surface loud;
     - envelope not starting with `{` → `continue`: the fake's documented
       `__READER_ERROR__` marker channel (:303-310) is reconnect
       choreography, not a poll frame (comment cites the fake line);
     - not a `/get subs` frame → `continue` (unchanged);
     - `sendFrame` IOException → `continue`: the answer raced the
       client-generation swap; the dead generation's command future was
       already drained by close() (SimpleXWebSocketClient.java:267-289)
       and the new generation re-polls — one dropped answer, thread lives;
     - any other RuntimeException (e.g. a genuinely malformed client
       frame) → record and return (loud, not silent).
  4. pollExpectingZero (:435-453) gains the responder handle: at each loop
     iteration and before throwing on an unexpected outcome, a recorded
     failure or a dead responder throws AssertionError naming the harness
     fault with the recorded cause — immediate, instead of a 10 s burn and
     a misleading "got FAULT". A FAULT with a LIVE responder still throws
     exactly as today (P2 preservation).
  5. Thread the `AtomicReference<Throwable>` through the six existing
     methods (mechanical signature change only — P4).
  6. `mvn verify` from repo root; then 10 consecutive module-scoped runs of
     the class, counts into the commit message (P7).
- **Controls to preserve (engineering-rules §10):** this diff reroutes no
  production path, so the controls are the PRODUCT assertions the harness
  pins: the threshold latch (:91-93), the latched-window TRANSIENT send
  (:97-100, pinning messaging.md:466-469), the once-per-episode restart and
  WARN (:101-115), boot grace (:152-162), WS-dead skip (:191-199), FAULT-
  is-not-zero (:226-234), crash-cap accounting + throttled admin notify
  (:269-291), D37 WARN hygiene (:322-330), and the codec wire fixtures
  (:342-381). None of these lines change. The generation barrier at :121
  stays — it guards a real, separate race (poll frame vs clientSocket swap;
  the M1-540/M1-541 barrier lineage) that this ticket does not remove.
- **Pitfall→mitigation:** P1→out_of_scope + no src/main in files_scope;
  P2→step 4's live-responder FAULT throw + acceptance item 4; P3→step 3's
  per-shape handling + recorded death; P4→acceptance item 4's byte-identical
  probe; P5→acceptance items 2-3's mutation notes; P6→step 3's interrupt
  arm; P7→step 1's additive seam + step 6.

## Definition of done

The reproduction test passes and can no longer fail 10 s late with a
misleading message: a dead subs responder fails the drive immediately,
naming the harness cause. The two deterministic failure-mode tests (marker
survival; loud death) pass and were each seen RED on main first. Every
pre-existing assertion in the class is byte-identical; FakeSimpleXProcess
gains only the additive seam; no src/main file, no spec file, and no other
test file is touched. `mvn verify` from the repo root is green and 10
consecutive class runs are green with counts in the commit message.

## Verification

- P1 → files_scope/out_of_scope + review: the diff contains no src/main
  path; `git diff --stat` probe.
- P2 → SimpleXSmpSessionLivenessTest.deadSubsResponderFailsFastNamingTheHarnessFault
  (acceptance 3) plus acceptance item 4's byte-identical `git diff -U0`
  probe — a FAULT-tolerant pollExpectingZero would fail both (failure-mode:
  the reverted check must produce the D-12 message, never pass).
- P3 → SimpleXSmpSessionLivenessTest.subsResponderSurvivesFakeReaderErrorMarker
  (acceptance 2) covers the marker path; its post-marker poll assertion
  covers the send path (mutation: die-on-next-send); the timeout/parse
  paths are covered by the record-and-fail-fast arm exercised in
  acceptance 3.
- P4 → acceptance item 4's `git diff -U0` probe + full-suite green.
- P5 → the mutation notes inside acceptance items 2-3 (each names the
  mutation it catches, per §8 assertion-adequacy).
- P6 → all six existing tests green UNCHANGED: their finally-interrupt
  teardown is the normal-exit proof (a recording interrupt arm would fail
  every one of them).
- P7 → acceptance items 5-6: additive-seam probe (SimpleXWebSocketClientTest
  / SimpleXReconnectTest unmodified and green), full `mvn verify`, 10×
  class runs reported.
- Reproduction → acceptance item 1.

## Out-of-scope

Named in `out_of_scope:` — any production change (the product behaved
correctly; P1); any FakeSimpleXProcess semantic rework beyond the additive
seam (the marker channel is its documented contract, and grep-verified no
test asserts on marker content); hardening the sibling responder copies in
other simplex suites (the exposure trigger — adapter-side reconnect without
a preceding fake.killClientConnection() — is unreachable in every one of
them today, grep-verified; a future flake gets a sibling ticket citing this
one, the M1-655 disposition); any spec edit; any scheduler-determinism
rework. This ticket MODIFIES two pre-existing test artifacts — the helper
methods and call sites of SimpleXSmpSessionLivenessTest and the additive
seam on FakeSimpleXProcess — authorized by acceptance items 4-5 with the
new behavior stated there (engineering-rules §8).

## Census

Class: background responder/acker threads over FakeSimpleXProcess whose
blanket `catch (Exception)` lets them die silently mid-test. Re-runnable:
`grep -rn 'awaitFrame' infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/`
(12 files: 11 consumer suites plus FakeSimpleXProcess.java itself, the
definition site) and, for the exposure trigger,
`grep -rn 'awaitClientGeneration\|killClientConnection' <same dir>`.

| Site | Disposition |
|---|---|
| SimpleXSmpSessionLivenessTest.startSubsResponder (:414-431) | FIXED (this ticket) — the only site where an adapter-side reconnect (abort, no close frame) swaps the client generation with no killedSocket suppression |
| SimpleXReconnectTest.startSendResponder (:613-637) | out-of-scope: every reconnect is preceded by fake.killClientConnection() (:168, :198, :237, :273, :307, :357, :408) → killedSocket suppression makes the marker unreachable; same silent-catch shape noted — sibling ticket if it ever flakes |
| SimpleXOutboundPacingTest (:166), SimpleXEditFallbackMetricsTest (:109), SimpleXEditFallbackTest (:194), SimpleXAddressQueryTest (:138, :164) | out-of-scope: single-generation choreography; no reconnect trigger (grep-verified) |
| SimpleXAdapterAttachmentTest ackers (:67, :115, :147, :179, :217, :261, :310), SimpleXInboundDispatchTest (:82), SimpleXWebSocketClientTest (:106), SimpleXAdapterChunkedSendTest (:66) | out-of-scope: single-connection suites; the marker requires a reader IOException on a non-choreographed death, which these never drive |
| SimpleXSendSerializationTest ack pumps (:65, :138) | out-of-scope: NOT the class shape — the pumps read ThrowingWebSocket.awaitTransmitted (:68), never the fake's frame queue, and their catch rethrows as RuntimeException (:72-74): loud, not silent |
| SimpleXTransportClassificationTest (:33, :56) | out-of-scope: direct awaitFrame assertions, no background responder |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-901-simplex-liveness-test-responder-race.md
```
