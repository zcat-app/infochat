---
id: M1-607
title: "Wire chat-mode replies into the ProgressNotifier (D31) so slow turns show live progress"
status: pending
created: 2026-07-12
last_updated: 2026-07-12
blocked_by: []
files_budget: 10
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
provenance: >-
  M1-606 discussion 2026-07-12. design/06-messaging.md §6.3.8 records chat-agent
  progress wiring as a named follow-up ("Digest and chat-agent wiring through the
  same notifier remain named follow-ups"); M1-212 wired /summary but left chat
  unwired. Surfaced while analysing the M1-606 redteam out-of-model item on chat
  read-timeouts: a slow chat turn today shows the user nothing until the whole
  reply lands (or the 30s timeout discards it), so a working-but-slow inference
  looks frozen.
out_of_scope:
  - >-
    Token-level / "weblike" streaming reveal of the answer. The LlmProvider SPI
    is single-string non-streaming by design (docs/spec/llm.md §SPI shape), and
    the adapter message-edit floor (messaging.md §Progress notifications,
    minEditInterval / coalesced edits) precludes smooth per-token updates. This
    ticket adds COARSE stage progress (placeholder -> stage labels -> finalized
    answer), not a token stream. A streaming SPI is a separate, larger decision.
  - >-
    Any change to the LlmProvider / EmbeddingProvider SPI, the M1-606 circuit
    breaker, or the M1-589 deterministic semantic pre-fetch behaviour. This
    ticket only adds progress-event publication around the existing chat compute
    path; the compute path itself is unchanged.
  - >-
    A chat-specific request timeout knob. Chat currently inherits the 30s
    infochat.llm.chat.timeout-ms default, so a long generation is cancelled and
    the reply lost regardless of progress UX. Raising that default (or adding a
    separate general-chat timeout) is a RELATED but distinct one-line-config
    follow-up — progress UX does not stop the 30s cancel. Decide at start whether
    to bundle it here or split it; it is out of scope as written.
  - >-
    New localization-bundle keys. The ProgressStage labels are already
    enum-keyed and localized (D43); chat reuses the existing stage strings, so no
    en/cs bilateral-keyset twin (D43) is added.
acceptance:
  - >-
    A chat-mode turn publishes its lifecycle through the existing
    ProgressNotifier (StageProgressNotifier, D31): the chat path acquires a
    placeholder message, publishes STARTED, then RETRIEVING around the M1-589
    deterministic semantic pre-fetch (only when the pre-fetch actually runs — it
    is skipped when the chat breaker is OPEN, M1-606), GENERATING around the LLM
    tool loop, and FINALIZING, and calls finalize() to REPLACE the placeholder
    with the sanitized/translated reply. The single-Provider outbound path
    (OutboundDelivery) still owns the actual send/update/finalize.
  - >-
    NO user-authored text is ever interpolated into a progress string — not the
    chat message, not retrieved post titles/URLs, not tool output. Progress
    strings are looked up by ProgressStage enum from the D43 bundle only
    (messaging.md §Progress notifications, "User input is never interpolated into
    progress strings" — a stated security requirement; prevents reflective
    injection in screenshots/logs). This is a NAMED test assertion.
  - >-
    Adapters without supportsMessageEdit collapse to a single final send of the
    completed reply (no placeholder, no intermediate edits) with business logic
    unchanged — the caller does not branch on transport (messaging.md §Progress
    notifications). v1 adapters currently all declare supportsMessageEdit=true,
    so this is the degraded-path contract, asserted with a
    supportsMessageEdit=false double.
  - >-
    The progress lifecycle respects the existing chat control flow: a /stop
    cancellation or an LLM failure drives the notifier to its terminal
    stop/fail path (no stale finalized answer), and the M1-589 deferred
    post-delivery persist/auto-compress ordering is preserved — the placeholder
    and finalize happen around compute+deliver, never around the persist, so a
    permanent delivery failure still leaves the window "as if the message was
    never generated" (messaging.md §Failure handling).
  - >-
    NAMED TESTS. A test asserts the STARTED -> (RETRIEVING) -> GENERATING ->
    FINALIZING stage sequence is published for a normal turn and that finalize
    carries the reply; a test asserts NO published stage string contains any
    substring of the user's message or of a retrieved title; a test asserts the
    supportsMessageEdit=false path collapses to exactly one final send; a test
    asserts a /stop-cancelled turn does not finalize a stale reply.
  - mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      Chat progress-lifecycle test (stage sequence, finalize carries the reply,
      no-user-input-in-stage-strings, supportsMessageEdit=false collapse,
      /stop terminal path).
  modifies:
    - >-
      Existing ChatAgent / InboundRouter chat-dispatch tests, to the extent the
      progress hand-off changes their construction or the dispatch return
      contract — enumerate the exact files at start.
  preserves:
    - all tests currently green on main
    - >-
      the M1-589 chat behaviour (deterministic pre-fetch, shared-TurnContext
      cache) and the M1-606 breaker skip — progress publication wraps them,
      never alters them.
spec_refs:
  - docs/spec/messaging.md §Progress notifications
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D31
  - D43
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-607: Wire chat-mode replies into the ProgressNotifier

## Context

M1-212 built the concrete `StageProgressNotifier` (D31) and made `/summary`
publish through it, but `design/06-messaging.md` §6.3.8 explicitly left
"digest and chat-agent wiring through the same notifier" as named
follow-ups. `messaging.md` §Progress notifications already lists the **chat
agent** as an intended publisher alongside `/summary` and the periodic
digest.

Today the chat path (`InboundRouter.dispatchChat` → `ChatAgent.handleTurn`)
computes the whole reply and returns it in one blocking step — the user sees
nothing until the full answer arrives, and on a slow local/remote inference
the 30s timeout can discard a reply that was still being generated, with no
signal that anything was happening. Wiring the existing stage notifier gives
the turn a "Working on it…" placeholder that is edited through
`RETRIEVING` / `GENERATING` and finally **replaced** by the answer — the
UX D31 was designed for.

## Shape (refine at start)

- The chat path publishes `STARTED` → (`RETRIEVING` only when the M1-589
  pre-fetch runs — it is skipped when the M1-606 breaker is OPEN) →
  `GENERATING` (around the tool loop) → `FINALIZING`, then `finalize()` with
  the sanitized/translated reply. Reuse the existing `ProgressStage` enum and
  its localized strings — **no new bundle keys** (avoids the D43 en/cs twin).
- The security invariant is load-bearing: **user input is never interpolated
  into a progress string** (reflective-injection defense). Stage strings are
  enum-keyed, parameterized only with deterministic sanitized scalars.
- Honor `/stop` (terminal stop, no stale finalize), LLM failure (`fail()`),
  and the M1-589 deferred-persist ordering (placeholder/finalize wrap
  compute+deliver, not the persist).
- Adapters without `supportsMessageEdit` collapse to one final `send`.

## Notes

- **Related but separate:** chat inherits the 30s `infochat.llm.chat.timeout-ms`
  default, so a genuinely slow generation is still cancelled and lost even with
  a progress placeholder showing. A chat-specific (higher) timeout is a
  one-line-config follow-up worth bundling here or splitting — it is
  out_of_scope as written; decide at start.
- **Placement:** `SummaryCommandHandler` is the reference implementation — it
  owns its `ProgressNotifier` lifecycle (placeholder → coalesced stage updates
  → finalize/fail) via the injected notifier. Mirror that shape for the chat
  dispatch rather than inventing a new coordinator unless the tool loop's
  multi-call structure demands one.
