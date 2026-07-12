---
id: M1-611
title: "Per-operation ProgressNotifier state: concurrent same-scope publishers must not clobber each other's placeholder"
status: pending
created: 2026-07-12
last_updated: 2026-07-12
blocked_by: []
files_budget: 6
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
provenance: >-
  M1-607 implementation 2026-07-12 (squash commit 52e4d1f1 records this as a
  known limitation; the M1-607 reviewer and redteam audit both saw it and it
  is outside that ticket's scope). Pre-existing since M1-212 for concurrent
  /summary in one scope; M1-607 wired chat-mode turns into the same notifier,
  adding a second publisher class to the same keyspace and making the
  collision reachable by ordinary traffic — two different users chatting
  concurrently in one approved group pass the per-(user, scope)
  InFlightTracker and publish into the same group ScopeRef, as does chat
  concurrent with /summary.
out_of_scope:
  - >-
    Changing the ProgressNotifier SPI (publish/complete/fail signatures) or
    anything in the infochat-messaging-adapter module. The collision is
    provider-internal state keying in StageProgressNotifier; the fix stays
    behind the existing SPI so no caller or adapter contract moves.
  - >-
    Caller-side changes beyond zero-or-trivial wiring. SummaryCommandHandler
    and the InboundRouter chat block keep publishing through exactly the
    calls they make today; if the design needs a per-operation identity, the
    notifier derives it itself (e.g. from the request-scoped InboundContext
    it already injects), not from new caller-passed arguments.
  - >-
    The periodic-digest progress wiring (still a named follow-up per
    design/06-messaging.md §6.3.8). This ticket must not preclude it, but
    wiring the digest publisher is not part of it.
  - >-
    Coalescing cadence, the min-edit-interval floor, the
    supportsMessageEdit=false collapse, or InFlightTracker semantics — all
    unchanged; this ticket is only about which per-operation state a publish
    resolves to.
acceptance:
  - >-
    Two operations publishing concurrently in the SAME scope never edit or
    finalize each other's placeholder: every placeholder's terminal text
    comes from the operation that acquired that placeholder. It is
    acceptable for the second concurrent operation to degrade to a single
    final send with no placeholder/edits (the same degraded shape the
    supportsMessageEdit=false collapse produces) — what is NOT acceptable is
    cross-operation edits, a placeholder finalized with another operation's
    text, or either operation's final text failing to reach the scope
    exactly once.
  - >-
    The M1-607 delivery-gating contract is preserved per operation:
    StageProgressNotifier.completeDelivered reports the delivery outcome of
    THAT operation's final text (a concurrent operation's terminal cannot
    flip it), so the chat deferred-persist gate stays correct under
    concurrency.
  - >-
    The abandoned-operation safety net (M1-334 registerProgressCleanup /
    terminateAbandoned) follows the new keying: an abandoned operation's
    cleanup terminates only its own placeholder, never a live concurrent
    operation's.
  - >-
    Single-operation behavior is unchanged: StageProgressNotifierTest,
    InboundRouterChatProgressTest, and SummaryIT stay green UNMODIFIED.
  - >-
    NAMED TEST. A plain-JUnit test drives two interleaved operations against
    one StageProgressNotifier in the same scope (A publishes, B publishes, A
    terminates, B terminates — and the reverse order) over a
    RecordingMessagingAdapter, asserting: no update/finalize lands on the
    other operation's handle, each placeholder's finalize carries its own
    operation's text (or the degraded op produced exactly one plain send and
    no placeholder), and both final texts reach the adapter exactly once.
  - mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      Concurrent same-scope progress test (the NAMED interleaving test
      above; two operation identities — e.g. two request contexts — against
      one notifier instance, both orders).
  modifies: []
  preserves:
    - all tests currently green on main
    - >-
      StageProgressNotifierTest, InboundRouterChatProgressTest, SummaryIT
      unmodified — the single-operation lifecycle, coalescing, collapse, and
      delivery-gating contracts are untouched.
spec_refs:
  - docs/spec/messaging.md §Progress notifications
decision_refs:
  - D31
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-611: Per-operation ProgressNotifier state

## Context

`StageProgressNotifier` keys its per-operation state by `ScopeRef` alone:

```java
private final ConcurrentHashMap<ScopeRef, ScopeState> states = ...;
```

One scope therefore holds at most ONE `ScopeState`. When two operations
publish concurrently into the same scope — two different users' chat turns
in one approved group (each passes the per-(user, scope) `InFlightTracker`),
or a chat turn concurrent with `/summary` — the second operation's first
`publish` finds the first operation's state and takes the UPDATE path
against the first operation's placeholder. Whichever terminal lands first
`states.remove(scope)`s the shared entry and finalizes the FIRST placeholder
with ITS text; the other operation's terminal then takes the no-handle
branch and fresh-sends.

Observable damage (no reply is ever lost — the fresh-send fallback
delivers): a placeholder finalized with the wrong operation's text,
interleaved stage labels from two operations on one message, premature
typing-off, and a `completeDelivered` outcome that can be computed against
the wrong handle (the M1-607 persist gate then keys off another operation's
delivery result).

## Shape (refine at start)

Two candidate designs; decide at start, leaning to the first:

- **Per-operation keying.** Key `states` by an operation identity instead of
  the bare scope — e.g. a token the notifier derives from the request-scoped
  `InboundContext` it already injects (one dispatch = one operation), falling
  back to the scope for non-request publishers if any exist. Placeholder
  acquisition, coalescing, terminal, and the M1-334 cleanup all follow the
  key. Full progress UX for every concurrent operation.
- **Single-active-per-scope.** Keep the scope key but make acquisition
  exclusive: the first operation owns the placeholder; a concurrent second
  operation publishes nothing and its terminal degrades to one plain final
  send (exactly the supportsMessageEdit=false collapse shape). Smaller diff,
  honest UX (one live progress message per scope), no new identity concept.

Either satisfies the acceptance; the choice is a start-time design decision
inside the same files. The SPI does not move in either design.

## Notes

- Group-scope confidentiality is NOT at stake: all participants of a scope
  see all messages in it, and per-scope isolation is untouched — this is a
  correctness/UX defect, not a trust-boundary one (hence
  `security_relevant: false`).
- `InboundRouterChatProgressTest` (M1-607) and `StageProgressNotifierTest`
  document the single-operation contract this ticket must not disturb; the
  new test is additive.
