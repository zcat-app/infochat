---
id: M1-313
title: "Chat-memory write ordering on permanent delivery failure"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
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
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
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

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-313-*.md
```
