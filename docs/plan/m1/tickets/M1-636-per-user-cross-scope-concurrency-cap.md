---
id: M1-636
title: "Per-user cap on concurrent interruptible requests across scopes"
status: pending
created: 2026-07-16
last_updated: 2026-07-16
blocked_by: [M1-638]
files_budget: 14
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    A per-user FAIR SCHEDULER. Design 06-messaging.md §6.3 defers one to a later
    revision and that deferral STANDS: this ticket adds a BOUND (a ceiling on a
    sender's concurrent share), never an ordering policy. Queue order remains
    arrival order with no fairness ordering of its own.
  - >-
    The per-(user, scope) in-flight guard itself (M1-634). Its one-request-per-
    scope semantics, its reject text and its /stop cancellation-handle role are
    unchanged. This ticket adds a coarser, per-user bound DERIVED from the M1-638
    turn-lifecycle registry — a count of the sender's non-terminal turns across
    scopes — never a second structure carrying its own state to keep in sync.
  - >-
    Changing infochat.chat.dispatch.max-concurrency's VALUE (stays 4, per the
    2026-07-16 operator decision), the CallerRunsPolicy saturation path, and
    LlmRateCap / RateCapBucket values or windows.
  - >-
    Feedback for a QUEUED request (M1-635). This ticket's new reject path is a
    terminal reply, not a progress placeholder.
acceptance:
  - >-
    A test drives one user's interruptible requests across MORE THAN ONE distinct
    scope (e.g. a DM plus a group) concurrently, and asserts that requests beyond
    the configured per-user cap are rejected with fixed guidance rather than
    admitted — while a DIFFERENT user's request in its own scope is still
    admitted concurrently (the cap binds per sender, never globally).
  - >-
    A test asserts a cap rejection consumes NO LlmRateCap token and NO in-flight
    slot, so the sender's next permitted request still succeeds — matching the
    existing check-order discipline documented at SummaryCommandHandler.java:271.
  - >-
    A test asserts the per-user count is released when a request completes, when
    it fails, and when it is cancelled by /stop, so a user cannot leak its own
    budget to zero across repeated turns.
  - >-
    docs/design/06-messaging.md §6.3's sentence "one sender's share is bounded by
    its per-minute rate-cap budget rather than by a fair scheduler" is amended to
    state the concurrency dimension this ticket adds, and still records the
    fair-scheduler deferral as standing.
  - >-
    infochat.chat.dispatch.max-concurrency is documented in the Provider's
    application.properties with its default (4), its >= 2 boot validation, and
    the caller-runs ceiling (pool + equal-depth queue, then inline on the
    transport thread), so an operator can discover and tune it without reading
    InterruptibleDispatcher's source.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/InFlightTrackerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D35
  - D43
  - D46
clarity_check:
  date: 2026-07-17
  verdict: PASS
  warnings:
    - >-
      Two design decisions are explicitly left open for /m1-tick start (§Notes:
      the cap's value/overflow shape, and whether admission is checked at submit
      time vs on the worker). Not a clarity defect — same pattern M1-638 shipped
      with — but the outline/start step must actually resolve both before
      implementation, since acceptance items 1-3 depend on which fork is taken.
  blockers: []
---

# M1-636: Per-user cap on concurrent interruptible requests across scopes

## Context

`InFlightTracker` keys its slot on `(userId, scopeKind, scopeId)`
(the `ScopeKey` record), which bounds a sender to one in-flight
interruptible request **per scope** — not per sender. A user who is a member of
several groups holds one slot in each, plus one in their DM. With
`InterruptibleDispatcher`'s pool at its default of 4, a single user present in
three groups plus a DM can occupy every worker on their own, and the next
sender's turn waits behind them.

Nothing collapses when that happens — request 9 degrades to caller-runs, the
transport's bounded inbound queue back-pressures exactly as M1-634 documented,
and `LlmRateCap` still bounds the sender to 10/min (20 under `%remote-llm`). The
cost is latency borne by other users, and the ceiling is low enough (4 workers)
that ordinary group membership reaches it without any hostile intent.

**Blocked on M1-638.** M1-638 replaces the thread-keyed slot with a turn
lifecycle that exists from submit, keyed by turn identity and indexed by scope.
This ticket's cap becomes a *query* over that registry — count a sender's
non-terminal turns across scopes — rather than a third structure standing beside
`InFlightTracker`. Filing order is the only reason this ticket predates that
model; the 2026-07-16 design review that produced M1-638 established it, and two
of the notes below were written against the pre-M1-638 constraints and are
revised accordingly.

This is a known boundary, not a discovered bug: M1-634's `out_of_scope` states
"a sender's share stays bounded by `LlmRateCap`", and `06-messaging.md` §6.3
defers a fair scheduler. The 2026-07-16 concurrency review re-opened it because
`LlmRateCap` bounds **rate**, not **concurrency**, and the pool made concurrency
a scarce shared resource for the first time. A per-minute budget does not stop
one sender from holding every worker at one instant.

The distinction this ticket rests on: a **cap** is not a **scheduler**. Adding a
ceiling on a sender's concurrent share leaves arrival order untouched and does
not implement fairness — so §6.3's deferral of a per-user-fair scheduler stands,
while its claim about what bounds a sender's share needs the concurrency
dimension added.

This ticket also carries the operator-facing documentation of the concurrency
bounds (see §Acceptance): `infochat.chat.dispatch.max-concurrency` currently
exists **only** as a `@ConfigProperty` default at
`InterruptibleDispatcher.java:79` — it appears in neither the Provider's
`application.properties` nor `prod/runtime/application.properties`, so an
operator cannot discover it without reading the source. Per the 2026-07-16
operator decision the value stays 4; it must become tunable-by-discovery.

## Acceptance

See the YAML `acceptance:` list. In prose: requests beyond a per-user cap across
scopes are rejected with fixed guidance while other senders stay admitted; a
rejection consumes neither an LLM token nor a slot; the count is released on
completion, failure and `/stop`; §6.3 is amended; the concurrency knob is
documented with its default, its boot validation and the caller-runs ceiling;
and `mvn -pl infochat-provider -am verify` is green.

## Out-of-scope

Covered in the YAML `out_of_scope:`. Most importantly this is **not** the fair
scheduler §6.3 defers, and it does not touch the per-scope guard's semantics or
its `/stop` cancellation-handle role. The `max-concurrency` VALUE stays 4 — this
ticket documents that knob, it does not retune it.

## Notes

**Open-decision (resolve with the operator at `/m1-tick start`): the cap's value
and its overflow behaviour.**

- *Reject with guidance* (Lean) — consistent with the per-scope guard's existing
  "request already in progress" reply, cheap, and it tells the user something
  true. Needs a new bundle key plus its cs twin (D43 bilateral keyset — a missing
  cs key fails `BundleLoaderTest`).
- *Queue instead* — invisible to the sender, but it re-creates the silent wait
  M1-635 exists to remove, and it needs a fairness policy to decide who goes
  first, which is exactly what §6.3 defers. Not recommended.

**Lean: reject, default cap 2, configurable.** A cap of 2 lets a user hold a DM
turn and one group turn concurrently while leaving half the default pool for
other senders. The value should be a config knob documented alongside
`max-concurrency`, not a constant.

**Check-order discipline is load-bearing.** `SummaryCommandHandler.java:271`
documents why the in-flight slot is checked *before* the LLM bucket: "neither
check consumes anything on a rejection — an already-in-flight rejection takes no
bucket token, and a rate-cap rejection records no timestamp, so the next
permitted request still succeeds." A cross-scope cap is a third check and must
join that order without consuming anything on its own rejection. The second
acceptance item pins this.

**Release discipline is where this will break if it breaks.** A standalone
counter has no identity check, so a double-decrement or a missed decrement on the
`/stop` path silently corrupts the budget in the sender's favour — or locks the
sender out permanently. Deriving the count from M1-638's registry removes that
hazard by construction: the turn reaching a terminal state IS the release, so
there is no second number that can drift from the first. The third acceptance
item still pins all three release paths (complete, fail, `/stop`), because
"derived" is a design intention until a test proves it. The stale-safety pattern
to match wherever state is freed remains `InFlightTracker.release`'s identity
comparison — post-M1-638 it reads the held handle back inside a `computeIfPresent`
and clears it only when it is the same object (`entry.running == handle`), so a
late release by a superseded worker cannot evict a newer holder. (M1-638 replaced
the former two-arg `ConcurrentHashMap.remove(key, value)` idiom: the map's value
is now the per-scope entry rather than the handle itself, so identity is compared
inside the lambda instead of by the map.)

**Where the check runs is now OPEN — M1-638 removed the constraint.** This note
previously read that admission "must run on the worker", because `tryAcquire`
captures `Thread.currentThread()` as the cancellation target; the consequence was
that a rejected request still spent a pool slot to discover its own rejection,
and the cap bounded concurrent *LLM work* rather than concurrent *submissions*.
M1-638 gives a turn an identity at submit that does not depend on a thread, so
the count is readable on the transport thread and this cap MAY reject before a
slot is spent. Resolve at `/m1-tick start`: rejecting at submit is the lean (it
spends nothing and bounds submissions), but it must not drag the per-SCOPE
guard's reject off the worker with it — M1-638's `out_of_scope` pins that reject
to its current timing and text.

**Test-plan shape.** `InFlightTrackerTest` is modified ADD-ONLY: new methods
pinning the per-user cross-scope count query and its release on completion,
failure and `/stop`. No existing assertion changes — `out_of_scope` entry 2 pins
the per-(user, scope) guard's observable semantics, so nothing already asserted
there may move. `test_plan.adds` is deliberately empty: the file carrying
acceptance items 1-2's two-sender admission test depends on the submit-vs-worker
fork above, so it is named at `/m1-tick start` once that fork is resolved rather
than guessed at filing time.

**Alternative considered: do nothing.** `LlmRateCap` at 10–20/min already bounds
a sender's total cost, and with the current 4-user population the ceiling is
unreachable in practice. The case for acting now is that the exposure scales with
group membership rather than with malice, and this is the control that has to
exist before the bot is opened to senders who are not known personally.

- Adjacent code: the M1-638 turn-lifecycle registry (its post-merge shape) —
  this ticket's per-user count is a query over it, so read it before designing
  the cap rather than reaching for a counter of your own.
- Adjacent code: `LlmRateCap.java:14` — the javadoc explaining why chat,
  `/summary` and `/retry` deliberately share ONE bucket ("a caller cannot bypass
  the cap by switching surfaces"). Any new cap must not reopen that bypass by
  binding to only one surface.
- Design note to amend: `docs/design/06-messaging.md` §6.3, the "Per-user
  fairness is **not** implemented in v1" paragraph.
- Whether `docs/spec/security.md` §Rate limiting must also enumerate the new
  control is for the clarity gate / start prompt to settle; the design-note
  amendment is required either way because the ticket makes its current sentence
  false.
