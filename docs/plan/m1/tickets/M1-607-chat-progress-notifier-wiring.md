---
id: M1-607
title: "Wire chat-mode replies into the ProgressNotifier (D31) so slow turns show live progress"
status: done
created: 2026-07-12
last_updated: 2026-07-12
blocked_by: []
files_budget: 12
complexity: medium
risk: medium
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
    The companion chat max-tokens default (1024, resolved in
    OpenAiCompatibleProvider/AnthropicProvider configFor) — the other half of
    the F-live-6 "reply lost / truncated" failure mode. This ticket bundles ONLY
    the chat request-timeout raise (see acceptance "BUNDLED TIMEOUT RAISE");
    raising the chat max-tokens default is a RELATED but separate follow-up, out
    of scope here.
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
  - >-
    BUNDLED TIMEOUT RAISE. A committed infochat.llm.chat.timeout-ms default
    (> 30000) is added to infochat-provider application.properties so a
    slow-but-working chat generation is no longer cut at the in-code 30000ms
    floor (OpenAiCompatibleProvider/AnthropicProvider configFor .orElse(30000L),
    applied as the JDK HttpClient request timeout on each generate call) and the
    reply lost. No Java change is required — infochat.llm.chat.timeout-ms is a
    real per-task key (ModelTask.CHAT_AGENT) whose read path already exists
    (M1-548/M1-603); this only supplies a committed default above the 30s floor,
    following the existing per-task/per-profile config convention. A NAMED test
    asserts the resolved chat timeout (infochat.llm.chat.timeout-ms) is > 30000.
  - mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      Chat progress-lifecycle test (stage sequence, finalize carries the reply,
      no-user-input-in-stage-strings, supportsMessageEdit=false collapse,
      /stop terminal path).
    - >-
      Chat timeout config test asserting the committed
      infochat.llm.chat.timeout-ms default resolves to > 30000 (guards against
      regressing to the in-code 30s cancel floor).
  modifies:
    - >-
      InboundRouterChatModeIT — the dispatched chat reply now lands via the
      notifier's finalize, not the router's plain send (self-delivery mirroring
      SummaryCommandHandler). Update the reply-reading cases
      (chatModeDispatchesToAgent, llmUnreachableReturnsFriendlyError) to read
      adapter.finalizedBodies() instead of sentMessages().getLast() (mirroring
      SummaryIT). Pre-dispatch fixed-error replies (body_too_large,
      probation_blocked, llm_rate_cap) stay plain sends and are unchanged.
    - >-
      InboundRouterChatDeliveryOrderingIT — pins chat-message persistence on
      delivery outcome (0 rows on permanent delivery failure; 2 rows + next_seq
      on success). Preserve those row-count invariants under the notifier-driven
      self-delivery path (acceptance item 4); adjust the always-failing-adapter
      injection and/or delivery-gating assertions so they still hold.
    - >-
      InboundRouterClearCompressIT — reads the reply via sentMessages().getLast()
      and asserts the auto-compress notice / compress-failed error. The
      auto-compress notice stays a separate plain send (unaffected); if the
      compress-failed-as-reply branch delivers via the notifier, move that read
      to finalizedBodies().
    - >-
      InboundRouterAcquisitionCountTest — its dispatchChat override + single-send
      assertion on a send-only CapturingAdapter (which throws on update/finalize)
      reflects the old "router sends the returned reply" model. Update to the
      self-delivery contract — swap CapturingAdapter for RecordingMessagingAdapter
      (supports update/finalize) or keep the override intercepting the whole chat
      sub-call — preserving its pool-acquisition-count focus.
    - >-
      InboundRouterChatPersistFailureTest — its dispatchChat override +
      assertEquals(1, target.sends.size()) on RecordingMessagingAdapter reflects
      the old router-send model. Update to the self-delivery contract; the
      persist-failure behavior it pins is preserved (acceptance item 4).
  preserves:
    - all tests currently green on main
    - >-
      the M1-589 chat behaviour (deterministic pre-fetch, shared-TurnContext
      cache) and the M1-606 breaker skip — progress publication wraps them,
      never alters them.
    - >-
      ChatAgentTest, ChatAgentAuditActorTest, ChatAgentRefusalInterceptTest stay
      unchanged — the chat COMPUTE path and ChatAgent's constructor are untouched
      (out_of_scope: publication wraps the dispatch, not ChatAgent), so no
      13-arg-constructor test breaks. RouterNoDoubleSendTest (slash-only) also
      stays green — it is the no-double-send precedent the chat self-delivery
      mirrors.
spec_refs:
  - docs/spec/messaging.md §Progress notifications
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D31
  - D43
clarity_check:
  date: 2026-07-12
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 660
      removed: 66
escalations: []
overrides: []
revisions:
  - date: 2026-07-12
    reason: >-
      clarity-fail refine via /m1-tick run (bounded self-refine) PLUS a
      user-directed scope change decided at start. Cleared the
      TEST-CHANGES-AUTHORIZED blocker: test_plan.modifies deferred the required
      file enumeration ("enumerate the exact files at start"). A ground-truth
      pass over the chat/InboundRouter test tree replaced the deferral with the
      verified impact — 5 named InboundRouter chat-delivery tests change because
      the reply migrates from one plain router send to a notifier placeholder +
      finalize (self-delivery mirroring SummaryCommandHandler); the three
      ChatAgent constructor tests are confirmed UNCHANGED (compute path untouched
      per out_of_scope). Also, per the ticket's "decide at start" note and an
      explicit user decision, BUNDLED the chat request-timeout raise:
      files_budget 10 -> 12, risk low -> medium, out_of_scope item 3 (timeout
      knob) replaced with the companion max-tokens exclusion, plus a new
      acceptance item + adds test for a committed infochat.llm.chat.timeout-ms
      default > 30000.
    snapshot: |
      files_budget (pre-refine): 10.  risk (pre-refine): low.
      test_plan.modifies (verbatim, pre-refine): "Existing ChatAgent /
        InboundRouter chat-dispatch tests, to the extent the progress hand-off
        changes their construction or the dispatch return contract — enumerate
        the exact files at start."
      out_of_scope item 3 (verbatim, pre-refine): "A chat-specific request
        timeout knob. Chat currently inherits the 30s
        infochat.llm.chat.timeout-ms default, so a long generation is cancelled
        and the reply lost regardless of progress UX. Raising that default (or
        adding a separate general-chat timeout) is a RELATED but distinct
        one-line-config follow-up — progress UX does not stop the 30s cancel.
        Decide at start whether to bundle it here or split it; it is out of
        scope as written."
      clarity_check 2026-07-12: FAIL, 1 blocker (TEST-CHANGES-AUTHORIZED) + 1
        warning (COMPLEXITY-RISK-CALIBRATED: risk low is light given the
        load-bearing M1-589 persist-ordering guarantee). Ground truth behind the
        enumeration: ChatAgent has a 13-arg @Inject constructor (break
        candidates ChatAgentTest/ChatAgentAuditActorTest/
        ChatAgentRefusalInterceptTest) but out_of_scope keeps the compute path
        unchanged; InboundRouter is field-injected (no constructor break); the
        reply currently read via adapter.sentMessages().getLast() migrates to
        finalizedBodies() under self-delivery (SummaryIT pattern). Timeout
        premise verified real: 30s is the in-code
        OpenAiCompatible/AnthropicProvider .orElse(30000L) default on the
        per-task key infochat.llm.chat.timeout-ms, not a mis-citation of
        infochat.llm.security.timeout-ms.
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-12
    verdict: CLEAN
    base: 193f572f (merge-base main)
    head: working-tree@m1/M1-607-wire-chat-mode-replies-into-th (pre-commit branch audit)
    verdict_file: docs/plan/m1/redteam/M1-607-2026-07-12.md
    out_of_model_count: 0
    note: >-
      Pre-commit branch audit (/m1-tick run step 5). CLEAN across all five
      sensitive surfaces; no findings, no out-of-model items. Audit file
      folds into the ticket commit per the lifecycle-path exemption.
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

- **Bundled (decided at start, 2026-07-12):** the chat request-timeout raise is
  now IN scope (see acceptance "BUNDLED TIMEOUT RAISE"). The 30s cut is the
  in-code `.orElse(30000L)` default in `OpenAiCompatibleProvider` /
  `AnthropicProvider` `configFor`, applied as the JDK `HttpClient` request
  timeout on each `generate` call — `infochat.llm.chat.timeout-ms` is a real
  per-task key (`ModelTask.CHAT_AGENT`), just not committed to
  `application.properties`. Add a committed default above 30000 (the read path
  already exists; no Java change). The companion chat `max-tokens` 1024 default
  is the other half of the F-live-6 failure mode and stays out of scope.
- **Placement:** `SummaryCommandHandler` is the reference implementation — it
  owns its `ProgressNotifier` lifecycle (placeholder → coalesced stage updates
  → finalize/fail) via the injected notifier. Mirror that shape for the chat
  dispatch rather than inventing a new coordinator unless the tool loop's
  multi-call structure demands one.
