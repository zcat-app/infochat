---
id: M1-710
title: "SimpleX outbound rate-limit draws are untested; the Signal twin is pinned"
status: pending
created: 2026-07-27
last_updated: 2026-07-27
blocked_by: []
files_budget: 3
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXTestHarness.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXOutboundPacingTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The pacing behaviour itself. `CAPABILITIES.maxSendsPerSecond`, the
    token-bucket algorithm, `OutboundRateLimiter.reserveWaitNanos`, the
    nanosecond accounting (M1-359) and the "one token per wire frame"
    rule all keep their current semantics. This ticket adds the test that
    holds the existing behaviour down; a diff that changes what the
    limiter does has left its scope.
  - >-
    Migrating `SimpleXAdapter`'s `Clock.systemUTC()` at `:135` to an
    injected `Clock`. The rate-limit window is decision logic under
    CLAUDE.md §"Injectable time in decision logic", so that site is a
    genuine migration target — but it is pre-existing inline time, which
    belongs to the M1-447 backlog, not to a ticket whose job is to add
    coverage. The seam this ticket adds takes an `OutboundRateLimiter`,
    not a `Clock`; a test supplies a limiter it constructed with whatever
    clock it wants.
  - >-
    The Signal side. `SignalJsonRpcClient.pacedCall` and
    `SignalEditFallbackTest.fallenBackUpdateDrawsTwoTokens()` are the
    working reference this ticket mirrors, not something to change.
  - >-
    Chunking, edit-fallback, transport classification and reconnect
    behaviour. `SimpleXAdapterChunkedSendTest`, `SimpleXEditFallbackTest`
    and `SimpleXReconnectTest` keep their current assertions; the new
    coverage lands in its own file rather than widening theirs.
  - >-
    `SimpleXAdapter.finalizeMessage`'s wider no-coverage problem. The
    §Census below records that `:860` is NO_COVERAGE, not merely
    unpinned; this ticket covers the acquire draw on that path, not the
    rest of the method's untested surface.
  - any other module, any other adapter
acceptance:
  - >-
    `SimpleXAdapter` takes its `OutboundRateLimiter` through a
    package-private constructor seam, following the existing
    `wsReconnectBackoff` test-seam precedent at `SimpleXAdapter.java:226`.
    The public constructors keep their current signatures and supply the
    same `new OutboundRateLimiter(CAPABILITIES.maxSendsPerSecond(),
    Clock.systemUTC())` they build today, so production wiring is
    unchanged.
  - >-
    A new `SimpleXOutboundPacingTest` asserts `limiter.acquiredCount()`
    across all three draw sites named in the §Census: one token per
    transmitted chunk (`transmitChunk`), one per `update`, and one per
    `finalizeMessage`.
  - >-
    Each of the three assertions fails if its `outboundRate.acquire()`
    call is deleted. Verify mechanically, not by reading: delete one
    call, confirm the new test goes red, restore it. Repeat per site.
  - >-
    A multi-chunk send draws one token per chunk, not one per send —
    the SimpleX analogue of the Signal twin's "two tokens for a
    fallen-back update".
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXOutboundPacingTest.java
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXTestHarness.java
  preserves:
    - >-
      SignalEditFallbackTest.fallenBackUpdateDrawsTwoTokens() — the
      reference this mirrors, unchanged.
    - >-
      SimpleXAdapterChunkedSendTest and SimpleXEditFallbackTest in full.
      The new pacing assertions live in a new file precisely so these
      keep their current shape.
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Capability flags (minimum set)
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-710: SimpleX outbound rate-limit draws are untested; the Signal twin is pinned

## Context

`docs/spec/messaging.md` §"Capability flags (minimum set)" makes
`maxSendsPerSecond` a spec-level commitment: a token-bucket cap on
outbound `send` calls, which "the adapter transmits to". Both v1
adapters implement it with the same `OutboundRateLimiter`. Only one of
them has a test that would notice if the implementation stopped.

Found by the 2026-07-27 PIT mutation sweep over `infochat-messaging-adapter`
(`-Pmutation`, see the profile comment in the reactor `pom.xml`). Deleting
`outboundRate.acquire()` survives on every SimpleX call site and is killed
on the Signal one:

| Site | PIT status | Killed by |
|---|---|---|
| `SignalJsonRpcClient.pacedCall:650` | KILLED | `SignalEditFallbackTest.fallenBackUpdateDrawsTwoTokens()` |
| `SimpleXAdapter.transmitChunk:807` | **SURVIVED** (7 covering tests ran) | — |
| `SimpleXAdapter.update:830` | **SURVIVED** (3 covering tests ran) | — |
| `SimpleXAdapter.finalizeMessage:860` | **NO_COVERAGE** | — |

Seven tests execute `transmitChunk` and none of them notices when the
pacing disappears entirely. That is the definition of an assertion that
cannot fail for the property it appears to cover.

**Why the gap exists, structurally.** `SignalJsonRpcClient` takes its
limiter as a constructor parameter (`:335`), which is exactly what lets
`SignalEditFallbackTest` hand in a limiter and count `acquiredCount()`.
`SimpleXAdapter` builds its own as an inline field initializer:

```java
    private final OutboundRateLimiter outboundRate =
            new OutboundRateLimiter(CAPABILITIES.maxSendsPerSecond(), Clock.systemUTC());
```

No seam, so no test *can* count draws on the SimpleX path — the gap is
not an oversight in one test file, it is unreachable from the outside.
`OutboundRateLimiter.acquiredCount()` already exists and documents itself
as "visible for tests that pin the 'one token per wire frame' contract by
counting draws across a send/update/fallback sequence (M1-359)"; that
affordance is simply unusable here.

The fix has an in-repo precedent to copy: `SimpleXAdapter` already
carries a package-private test-seam constructor at `:226` for
`wsReconnectBackoff`, with a comment explaining that production wiring
always takes the public constructor above it. Same shape, one more
parameter.

## Census

The class is "every site that draws an outbound token in
`SimpleXAdapter`". Enumerated, not assumed:

```bash
grep -n "outboundRate.acquire()" \
  infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
```

Run 2026-07-27 this returns exactly three sites — `:807` (`transmitChunk`),
`:830` (`update`), `:860` (`finalizeMessage`) — and the acceptance
criteria name all three. Re-run at `start`: a fourth site means the class
grew and this ticket's scope is wrong.

For the parity claim, the counterpart enumeration is:

```bash
grep -rn "OutboundRateLimiter" \
  infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
```

which shows the constructor-parameter form this ticket ports.

## Acceptance

- `SimpleXAdapter` accepts an `OutboundRateLimiter` via a package-private
  constructor seam; the public constructors are unchanged and build the
  same limiter they build today.
- `SimpleXOutboundPacingTest` pins one token per chunk, one per `update`,
  and one per `finalizeMessage` via `acquiredCount()`.
- Each assertion is verified to fail when its `acquire()` call is deleted
  — checked by actually deleting it, not by reading the test.
- A multi-chunk send draws one token per chunk.
- `mvn verify` from the repo root is green.

## Out-of-scope

The limiter's behaviour, the cap value, and the nanosecond accounting are
untouched — this ticket adds the test, it does not change the control.
The `Clock.systemUTC()` at `:135` stays as it is: it is pre-existing
inline time owned by the M1-447 migration backlog, and the seam added
here takes a limiter rather than a clock, so nothing forces the two to be
resolved together. The Signal path, the chunker, and the edit-fallback
tests keep their current shape.

## Notes

- **`security_relevant: false`, deliberately.** The limiter is an
  availability control (design §6.3.6: keep the Provider from driving
  simplex-chat fast enough to trip its server-side rate limit), not an
  authorization or untrusted-input control, and per
  `docs/spec/messaging.md` §"Failure handling" the Provider's own
  per-user rate limiter — not this one — is "the single source of truth
  for slow this user down". The diff adds a constructor seam and a test
  file. A `/redteam` pass over that is ceremony. If the reviewer
  disagrees, flip the flag rather than widening the diff.

- **Shares a root cause with M1-711.** Both tickets are the same
  structural failure: a contract implemented in both adapters, with test
  coverage on the Signal side only. They touch disjoint files and neither
  blocks the other, so no `blocked_by` is set — but whoever runs the
  second one should read the first's outcome, because "add the missing
  half of an adapter-parity pair" is the pattern, not a coincidence. This
  is CLAUDE.md §"Preserve the controls of a path you replace" seen from
  the other end: the control was never carried onto the second path in
  the first place.

- **Method, for the record.** Neither this nor M1-711/M1-712 was found by
  reading code or by a failing test — the suite is green and stays green
  with all three `acquire()` calls deleted. They came out of a mutation
  run, which is the only tool in the repo that measures assertion
  strength rather than execution coverage.
