---
id: M1-923
title: "Named chat notice + operator signal on prompt-exceeds-context"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  ChatAgentPromptExceededTest.promptExceededTurnGetsTheNamedNotice
  (to-be-written — converted at /tick start per workflow §0: written
  first, run RED; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md).
  The wrong behavior it states: a context-exceeded backend rejection is
  indistinguishable from every other LLM failure. Verified on this
  checkout (2026-08-23): LlmHttpSupport.sendForBody
  (infochat-llm-adapter/.../impl/LlmHttpSupport.java:235-246) throws an
  UNTYPED LlmCallFailedException for any non-2xx — the HTTP status
  survives only inside the message string, the response body is
  deliberately dropped (:236-239) — and ChatAgent.handleTurn
  (ChatAgent.java:399-422) maps every LlmCallFailedException to the one
  generic bundle string error.chat.unavailable
  (bundles/en.properties:612, "The chat assistant is unavailable right
  now"). Live corroboration: prod's 11,477-token turn 400d and the user
  saw "unavailable right now"; the only trace was a WARN in the
  llama-server log (.agents/memory-local/prod-state-post-upgrade-20260823.md).
analysis_ref: docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md
blocked_by:
  - M1-918
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmCallFailedException.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentPromptExceededTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/LlmHttpSupportTest.java
  - docs/spec/security.md
  - docs/spec/commands.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The budget + compaction ladder itself — sibling M1-918 (blocker) owns
    it. This ticket consumes the assembled-prompt estimate and the
    configured budget; it does not compact.
  - >-
    Retrying the turn after a context-exceeded rejection (a re-compact +
    retry loop) — M1-918's ladder makes the first call fit by
    construction; a retry surface is unmeasured scope, not honesty.
  - >-
    Propagating or pattern-matching the provider error BODY — the
    redaction posture (LlmHttpSupport.java:236-239: bodies can echo
    request fragments or user content) is preserved; the status code is
    the only new signal (P15).
  - >-
    A new AuditAction enum value / Flyway migration — the operator signal
    rides the existing ThrottledAdminNotifier + admin_notification_state
    table (the BreakerOpenedAdminNotifier pattern), no migration.
  - >-
    Any change to the breaker classification — a non-2xx remains
    "reachable" (never trips the breaker), exactly as
    security.md §Failure handling's circuit-breaker rule states; this
    ticket differentiates the DEGRADE, not the failure accounting.
  - >-
    Other endpoints' non-2xx handling (summarizer, translator, ingest
    roles) — the named notice is chat-mode only; the typed status is
    available to them but no consumer changes here.
acceptance:
  - "REPRODUCTION closed: ChatAgentPromptExceededTest.promptExceededTurnGetsTheNamedNotice (test_plan.adds) passes — a chat turn whose LLM call fails with the typed 400 rejection AND whose own assembled-prompt estimate exceeded the configured infochat.chat.prompt-token-budget returns the NEW bundle string (BundleKeys.ERROR_CHAT_PROMPT_EXCEEDED), never error.chat.unavailable; the mutation mapping the subtype to the generic arm fails it (non-vacuity)."
  - "TYPED STATUS, REDACTED BODY (P15): LlmHttpSupportTest.non2xxCarriesTypedStatusAndRedactedMessage (test_plan.adds) passes — the non-2xx paths of BOTH sendForBody and executeStreamingCall throw the new status-carrying subtype; the exception message still names only provider label + status + host (assert the message contains no body fragment fed to the fake 400 response); LlmCallFailedException's ProviderUnreachableException typing and the breaker contract are untouched (CircuitBreakingLlmProvider tests pass UNCHANGED)."
  - "NEGATIVE GATE (P15, failure-mode): ChatAgentPromptExceededTest.underBudgetEstimateKeepsTheGenericNotice passes — a typed 400 whose turn estimate was UNDER budget (a non-context 400, the M1-577 dialect-mismatch class) still returns error.chat.unavailable: the notice never claims 'too large' on evidence the turn does not carry. A non-400 typed rejection (e.g. 500) keeps the generic path regardless of estimate."
  - "OPERATOR SIGNAL (analysis problem statement: 'silent to the operator'): the named path emits a WARN naming the estimated prompt tokens vs the configured budget AND a throttled admin notification via ThrottledAdminNotifier under the new error class chat-prompt-exceeded (the BreakerOpenedAdminNotifier pattern — DB-row throttle, no migration); ChatAgentPromptExceededTest.promptExceededNotifiesOperatorOnce passes (stub notifier captures key/error-class/message; the message names the numbers, never user prose — D37)."
  - "TURN-DISCARD CONTRACT (§10): the named path carries null commit and null provenance notice exactly like the unavailable path — no session advance, no memory write, no model-initiated tool call (security.md §Failure handling); asserted inside the reproduction drive."
  - "LIVE-TEXT FINALIZATION (P17, failure-mode): ChatAgentPromptExceededTest.streamingHeaders400FinalizesWithTheNamedString passes — a live-text-eligible turn whose streaming call rejects at the headers phase (before any chunk) surfaces the named string through the same handleTurn catch → router finalize machinery the unavailable path uses; no partial reveal is left dangling (InboundRouterChatModeIT:276's finalize-never-blank posture, exercised at the agent seam)."
  - "BUNDLE KEY SETS (D43): error.chat.prompt_exceeded lands in ALL FIVE bundles (en/cs/es/ru/tr) with owner-reviewed wording that NAMES the cause (the conversation grew past the model's context; suggest /clear or /compress) — BundleLoaderTest's key-set check passes unchanged."
  - "SPEC AMENDMENT rides the diff (analysis P16; engineering-rules §12 — exact wording to the user at implementation; rule-text drafts in Approach): docs/spec/security.md §Failure handling's chat-mode bullet gains the prompt-exceeds-context degrade as a distinct named localized notice (separate from the generic unavailable) with a throttled operator notification; docs/spec/commands.md §Chat mode's degrade enumeration (the no-provenance-notice list at :1837-1838) gains the new degrade kind — number-free, no dates/IDs. Probe: grep -n 'prompt' docs/spec/security.md returns the §Failure handling mention."
  - "DOCS: docs/design/05-llm-and-embeddings.md §5.4.6 records the named-notice posture, the under-budget gate, and the error class. Probe: git diff --stat docs/ shows exactly docs/spec/security.md, docs/spec/commands.md, docs/design/05-llm-and-embeddings.md."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentPromptExceededTest.java — promptExceededTurnGetsTheNamedNotice, underBudgetEstimateKeepsTheGenericNotice, promptExceededNotifiesOperatorOnce, streamingHeaders400FinalizesWithTheNamedString
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/LlmHttpSupportTest.java — non2xxCarriesTypedStatusAndRedactedMessage
  preserves:
    - all tests currently green on main — explicitly the breaker
      classification tests, ChatAgentTest's existing unavailable-path
      drives, BundleLoaderTest key-set checks, and InboundRouterChatModeIT
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D37
  - D43
---

# M1-923: Named chat notice + operator signal on prompt-exceeds-context

## Context

When a chat prompt exceeds the backend's context, llama-server answers
HTTP 400 and the user sees "The chat assistant is unavailable right now"
— a string that names nothing — while the operator gets one WARN in the
server log and no notification (prod incident 2026-08-23,
`.agents/memory-local/prod-state-post-upgrade-20260823.md`). M1-918
(blocker) makes the assembled prompt fit by construction; this ticket is
the honesty surface for the residual (estimator error band, a backend
re-configured below the floor): a NAMED, localized notice plus an
operator signal that names the numbers. Analysis: `analysis_ref:`.

## Root cause

Verified, two erasures in sequence: (1) at the adapter boundary every
non-2xx becomes an untyped `LlmCallFailedException("… non-2xx status
<status> from <host>")` (LlmHttpSupport.java:235-246 and the streaming
twin :332-337) — the status survives only as message text, and the body
(which carries llama-server's "exceeds the available context size") is
deliberately dropped for redaction (:236-239); (2) ChatAgent.handleTurn
(:399-422) maps every `LlmCallFailedException` to
`BundleKeys.ERROR_CHAT_UNAVAILABLE`. Nothing in the chain can
distinguish "prompt too large" from "endpoint down".

## Pitfalls

Numbered with the analysis document; this ticket carries P15, P16, P17
plus the ticket-local P18.

- P15: never parse or log the provider error body (redaction posture) —
  carry the status as a typed subtype only; and gate the named notice on
  the turn's OWN estimate exceeding the configured budget, so a
  non-context 400 (dialect mismatch, the M1-577 class) never claims
  "too large".
- P16: rides-the-diff amendment shape — number-free rule-text, no
  dates/IDs in spec prose, exact wording to the user at implementation
  (§12).
- P17: a streaming 400 rejects at the headers phase before any chunk —
  the named string must flow the same finalize machinery as the
  unavailable path; no dangling live-text message.
- P18 (ticket-local): the named path must inherit the unavailable arm's
  whole discard contract (null commit, null notice, no tool calls,
  /stop guard order at ChatAgent.java:415-417) — a partial copy is the
  M1-694 relocated-control class in reverse (§10).

## Approach

Derived from `spec_refs:`: security.md §Failure handling owns the
chat-mode degrade rules (this adds a distinct, named degrade kind);
commands.md §Chat mode enumerates the degrade replies that carry no
provenance notice (this joins that list); D43 puts the wording in the
bundles; D37 keeps user prose out of every log/notification.

- **Files to touch:** `files_scope`.
- **Pre-decided shapes (implementation is execution):**
  1. `LlmCallFailedException` gains a final subtype
     `ProviderRequestRejectedException` carrying `int httpStatus()`;
     both non-2xx sites in LlmHttpSupport (:244, :335) throw it with the
     SAME redacted message shape (provider label + status + host; never
     the body).
  2. `ChatAgent.handleTurn` gains a catch arm AHEAD of the generic
     `LlmCallFailedException` arm (after the /stop guard): when the
     exception is a rejection with status 400 AND the turn's
     assembled-prompt estimate (from M1-918's compaction report)
     exceeded `infochat.chat.prompt-token-budget`, return the new
     bundle string + null commit + null notice; log WARN with estimate
     vs budget; fire the throttled admin notification
     (`chat-prompt-exceeded`, naming the numbers, no user prose).
     Everything else falls through to the existing arms unchanged.
  3. `BundleKeys.ERROR_CHAT_PROMPT_EXCEEDED` + the five bundle entries
     (wording owner-reviewed at implementation; it names the cause and
     points at /clear or /compress).
- **Spec amendment rule-text drafts (§12 — exact wording approved by the
  user at implementation):**
  - security.md §Failure handling, chat-mode bullet, append: when the
    chat backend rejects a turn because the assembled prompt exceeds its
    context, the user receives a distinct localized notice naming the
    cause (separate from the generic unavailable error); the turn is
    discarded under the same no-advance contract, and a throttled admin
    notification records the estimated size against the configured
    budget.
  - commands.md §Chat mode degrade enumeration: add the
    prompt-exceeds-context notice to the degrade kinds that carry no
    provenance notice.
- **Steps, in order:**
  1. Write both new test classes RED (reproduction + adapter typing).
  2. Adapter subtype + both throw sites (shape 1).
  3. ChatAgent arm + bundle key + five bundle entries (shapes 2-3).
  4. Land the user-approved spec wording + design 05 note.
  5. Module runs + `mvn verify`.
- **Controls to preserve (§10):** the body-redaction posture (the
  adapter tests pin the message shape); the breaker classification
  (non-2xx = reachable; CircuitBreakingLlmProvider untouched); the
  /stop-guard order and discard contract of the existing arms; the
  BundleLoaderTest key-set check; the unavailable path itself (its
  tests pass unchanged).
- **Pitfall→mitigation:** P15→shapes 1-2 + acceptance items 2-3; P16→
  item 8's approval gate; P17→item 6's streaming drive; P18→step 3
  reuses the existing arm's return shape verbatim + item 5's assertions.

## Definition of done

The named notice reaches the user on a gated context-exceeded 400 and
only then; the adapter carries the typed status with the redacted
message shape on both the unary and streaming paths; the operator
notification fires once per throttle window naming estimate vs budget;
the discard contract, breaker posture, and live-text finalization hold;
all five bundles carry the key; the spec amendment (user-approved) and
design note land; `mvn verify` green from the repo root.

## Verification

- P15 → LlmHttpSupportTest.non2xxCarriesTypedStatusAndRedactedMessage
  (fake 400 with a canary body string; asserts typed status AND the
  canary's absence from the message) +
  ChatAgentPromptExceededTest.underBudgetEstimateKeepsTheGenericNotice.
- P16 → acceptance item 8's approval posture + grep probe after landing.
- P17 → ChatAgentPromptExceededTest.streamingHeaders400FinalizesWithTheNamedString.
- P18 → the reproduction drive asserts null commit + null notice; the
  pre-existing unavailable-path drives pass unchanged.
- FAILURE-MODE coverage (beyond the reproduction) → items 3 and 6
  (under-budget 400 keeps the generic notice; headers-phase streaming
  400 finalizes cleanly).
- acceptance items 4, 5, 7, 9, 10 → the named stub-notifier assertions,
  the discard assertions, the BundleLoaderTest key-set check, the
  diff-stat probe, `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: the ladder (M1-918), any retry surface, body
propagation, a new AuditAction/migration (the throttled notifier covers
the operator signal), breaker reclassification, and non-chat endpoints'
non-2xx handling. No pre-existing test is modified (§8): the unavailable
arm's drives must pass unchanged — if one genuinely conflicts, escalate
rather than edit.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-923-chat-prompt-exceeded-named-refusal.md
```
