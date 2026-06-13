---
id: M1-313
title: "Chat-memory write ordering on permanent delivery failure"
status: pending
created: 2026-06-11
last_updated: 2026-06-13
blocked_by:
  - M1-284
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: high
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - The outbound-delivery chokepoint, retry policy, cap escalation, and membership cleanup — all owned by M1-284. This ticket consumes the chokepoint's delivery outcome; it does not build or alter the retry/cleanup behavior.
  - Translation, sanitize, and tool-loop behavior inside ChatAgent.handle — only the ORDER of turn persistence + auto-compress relative to send-success changes, not what those steps compute.
  - Non-chat outbound paths (digest, command replies, stage-progress) — they have no chat_memory to advance, so the ordering question does not apply to them.
acceptance:
  - "On PERMANENT outbound-delivery failure of a chat reply, neither the user turn nor the assistant turn is persisted to chat_message/chat_session and auto-compress does not run, so the next inbound from the same scope reuses the prior context window (spec docs/spec/messaging.md §Failure handling: 'the context window remains as if the message was never generated, and chat_memory is not written'): a named test drives a chat reply whose delivery fails PERMANENTLY through the M1-284 chokepoint and asserts zero chat_message rows were written for that turn."
  - "On successful delivery, chat-turn persistence and auto-compress occur exactly as today: a named test asserts both the user and assistant turns persist and chat_session.next_seq advances after a delivered reply."
  - "ChatAgent.handle no longer commits chat_message/chat_session before the reply is delivered; persistence (and the auto-compress step that depends on it) is ordered after the chokepoint reports delivery success. The send-succeeded-but-persist-failed fork is logged and does NOT re-send (the user already received the reply); a named test pins that a persist failure after a delivered reply does not trigger a resend."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java — the reorder moves persistTurn + auto-compress out of ChatAgent.handle to a post-delivery site, so the methods that assert persistence runs INSIDE a direct handle(...) call must be updated to drive the new shape (ChatAgent.handle returns the pending turn data without persisting; the caller persists after delivery success). Specifically: orchestrationSequenceIsCorrect (sessionPersistCalls / persistedRoles ordering), persistsSanitizedOutput (persistedTexts count), distinctGroupsProduceDistinctSessions (sessionPersistCalls count), and turnOnCeilingStuckSessionRejectedWithFailureNoticeUntilCompressSucceeds (sessionPersistCalls advance). The behavior they pin — both turns persist, sanitized text persisted, distinct sessions, compress gating — is preserved; only the call site that triggers persistence moves."
  preserves:
    - all OTHER tests currently green on main (everything except the ChatAgentTest methods named under modifies)
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
reviews: {}
escalations:
  - date: 2026-06-13
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended

      REASON: The reorder this ticket mandates cannot be implemented without
      modifying pre-existing tests that the ticket body does not authorize.
      The chosen implementation shape — named in the ticket's own §Notes
      ("ChatAgent.handle computes the reply ... WITHOUT persisting, returns
      the reply plus the pending turn data; the chokepoint persists both
      turns and runs auto-compress") — necessarily removes the
      ChatSessionRepository.persistTurn calls and the auto-compress step from
      inside ChatAgent.handle (currently ChatAgent.java:201-218). At least
      four methods in the plain-JUnit ChatAgentTest.java assert that
      persistence runs inside a direct agent.handle(...) call, with no
      delivery step: orchestrationSequenceIsCorrect (assertEquals(2,
      sessionPersistCalls) + persistedRoles ordering, lines 83-85),
      persistsSanitizedOutput (assertEquals(2, persistedTexts.size()), lines
      276-278), distinctGroupsProduceDistinctSessions (assertEquals(4,
      sessionPersistCalls), line 295), and
      turnOnCeilingStuckSessionRejectedWithFailureNoticeUntilCompressSucceeds
      (asserts sessionPersistCalls advances to 2 across handle calls, line
      392). Once persist+compress move out of handle(), these assertions go
      to 0 and fail; they must be rewritten to drive the new "return pending
      turn data, persist after delivery" shape. The ticket's test_plan
      carries only adds: and preserves: entries — there is no
      test_plan.modifies entry, and neither §Out-of-scope nor §Notes names
      ChatAgentTest. This is precisely the clarity round's
      TEST-CHANGES-AUTHORIZED WARN. Falsified the escape hatch: because
      ChatAgentTest invokes agent.handle(...) directly with no router/delivery
      step, no deferred-callback shape lets the persist run within that single
      call, so the test modification is unavoidable, not a style choice.

      SUGGESTED ESCALATION: refine

      EVIDENCE: ticket test_plan (lines 29-33) has no modifies: entry; clarity
      WARN names the exact gap and remediation ("add a test_plan.modifies
      entry if any need updating"); the forced-modify assertions are at
      ChatAgentTest.java:83-85, 276-278, 295, 380, 392, contradicted by the
      chosen shape in §Notes (lines 84-91) against current
      ChatAgent.java:201-218. Refinement should add a test_plan.modifies entry
      listing ChatAgentTest (and state the new expected behavior: persist/
      compress assertions move to the post-delivery site), after which a fresh
      Plan pass can produce an implementable outline.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-06-13
    reason: outline-fail rework — authorize the forced ChatAgentTest modifications. The chosen reorder shape (named in §Notes) moves persistTurn + auto-compress out of ChatAgent.handle, breaking four ChatAgentTest methods that assert persistence runs inside a direct handle(...) call. The original test_plan had no modifies: entry (only adds:/preserves:), so those edits were unauthorized — the exact clarity TEST-CHANGES-AUTHORIZED WARN that the plan-writer treats as a hard gate. Added test_plan.modifies listing ChatAgentTest and a §Notes bullet stating the preserved behavior; spec/premise/budget were sound and unchanged.
    prior_values: |
      test_plan:
        adds:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
        preserves:
          - all tests currently green on main
        (no modifies: entry)
clarity_check: {}
---

# M1-313: Chat-memory write ordering on permanent delivery failure

## Context

Peeled off from M1-284 (the outbound delivery failure layer). M1-284 builds
the send-site chokepoint that classifies TRANSIENT/PERMANENT failures, but it
cannot honor one clause of the spec's §Failure handling contract: on a
PERMANENT failure, "the context window remains as if the message was never
generated, and `chat_memory` is not written." Today `ChatAgent.handle` calls
`ChatSessionRepository.persistTurn` for both the user and assistant turns —
each opens its own connection and `COMMIT`s — and runs the auto-compress step
BEFORE it returns the reply string. The reply only reaches the outbound send
site (`InboundRouter.sendReply` → the M1-284 chokepoint) afterward. So by the
time the chokepoint learns the send failed permanently, `chat_memory` is
already committed. Honoring the spec requires reordering chat-turn persistence
(and auto-compress) to run only after the chokepoint reports delivery success.
Verified at source 2026-06-11: `ChatAgent.java` persistTurn calls precede the
`return reply`; `ChatSessionRepository.persistTurn` does `conn.commit()`.

## Acceptance

See frontmatter. This ticket gates on M1-284 because the reorder hangs off the
chokepoint's per-reply delivery outcome — there is nothing to key persistence
on until that chokepoint exists.

## Out-of-scope

See frontmatter. The chokepoint, retry, cap escalation, and membership cleanup
are M1-284's; this ticket only changes WHEN chat-turn state is committed
relative to send-success, and how the (now-possible) send-ok-but-persist-fails
fork is handled.

## Notes

- The natural shape: `ChatAgent.handle` computes the reply (LLM loop,
  sanitize, translate) WITHOUT persisting, returns the reply plus the pending
  turn data; the chokepoint (or `InboundRouter` after a successful chokepoint
  send) persists both turns and runs auto-compress; on PERMANENT failure it
  skips persistence entirely. Confirm whether the user turn as well as the
  assistant turn must be withheld — the spec phrase "as if the message was
  never generated" reads as: roll back to the pre-turn context, i.e. neither
  turn persists.
- New failure fork introduced by the reorder: send succeeds, then persist
  fails. The user already has the reply, so a resend would duplicate it —
  log and move on; do NOT re-enter the send path. Pin this with a test.
- Auto-compress (`ChatAgent` step 9) currently fires after persist and
  depends on persisted session state; moving persist also moves auto-compress.
  Keep the relative order of persist-then-compress intact; only the position
  of that pair relative to send changes.
- Coordination: M1-285 (edit/finalize fallback) and M1-284 are the other
  parts of the outbound-failure story; this ticket is the chat-state-ordering
  slice that M1-284's refine (2026-06-11, outline-fail rework) deferred.
- Authorized pre-existing test modification (see `test_plan.modifies`):
  moving persist + auto-compress out of `ChatAgent.handle` to a post-delivery
  site breaks the `ChatAgentTest` methods that assert persistence runs inside
  a direct `handle(...)` call (`orchestrationSequenceIsCorrect`,
  `persistsSanitizedOutput`, `distinctGroupsProduceDistinctSessions`,
  `turnOnCeilingStuckSessionRejectedWithFailureNoticeUntilCompressSucceeds`).
  Update them to drive the new shape — the behavior they pin (both turns
  persist, sanitized text persisted, distinct sessions, compress gating) is
  preserved; only the call site that triggers persistence moves.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-313-*.md
```
