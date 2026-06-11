---
id: M1-285
title: "Edit-failure fallback to fresh send in both production adapters"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Provider-side retry/escalation layer (M1-284) — this ticket is adapter-internal fallback on the edit path only.
  - Fresh-send chunking internals — M1-283 landed SimpleXOutboundChunker; reuse it, don't extend it.
  - The design §6.3.8 metrics counter adapter.outbound.update.total{outcome=fallback_send} — no metrics surface exists (M1-305); the design's fallback BEHAVIOUR is in scope, its counter is not.
  - Inbound caps and capability flags.
acceptance:
  - "SimpleX: an update/finalize that fails permanently (over-cap PERMANENT from SimpleXMessageCodec.requireWithinCap, or message-deleted rejection) falls back to sending a NEW message with the original correlationId, per design 06-messaging.md §6.3.8 (verbatim: 'the adapter MUST fall back to sending a NEW message via send, with correlationId matching the original'); a named test finalizes a >4000-UTF-8-byte body and asserts the full content reaches the transport via chunked sends and no PERMANENT MessagingException escapes."
  - "After a fallback, the handle is marked so subsequent update/finalize calls skip the doomed in-place edit and go straight to the fallback path; a named test issues two updates after a fallback and asserts no further edit attempt hits the transport."
  - "Signal: an editMessage failure (edit-window expiry or message deleted) falls back to a fresh send with the original correlationId per design §6.4.5; a named test."
  - "The placeholder-freeze loss path is closed: a named test drives a StageProgressNotifier-shaped sequence (placeholder, updates, over-cap finalize) against the SimpleX adapter and asserts the final content is delivered."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-285: Edit-failure fallback to fresh send in both production adapters

## Context

Deep-review v5 verified HIGH **U-02** (`deep-code-review/v5/UNIFIED-REPORT.md`
§2; sources `deep-code-review/v5/fable-5/05-module-infochat-messaging-adapter.md#F1`,
`deep-code-review/v5/gpt-55/report.md#H-03` — gitignored; all load-bearing
facts inlined):

Design 06-messaging.md §6.3.8 (~:419) commits, for unrecoverable update
failures: "the adapter MUST fall back to sending a NEW message via `send`,
with `correlationId` matching the original" — §6.4.5 restates it per adapter.
Neither `SimpleXAdapter.update`/`finalizeMessage` (~:438-465) nor the
`SignalJsonRpcClient.editMessage` path implements any fallback.
`SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES = 4_000` (:60) throws PERMANENT
from the encoder, and M1-283's chunking covers `send()` only — **the
edit/finalize path still rejects**. Reachable in normal operation: any
`/summary` final body over 4 000 UTF-8 bytes on SimpleX freezes the
placeholder and the output is never delivered (`StageProgressNotifier
.terminate()` only logs). Same loss path for user-deleted placeholders and
Signal edit-window expiry.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — Provider-side retry (M1-284) and the §6.12 metrics counter
(M1-305) are explicitly excluded.

## Notes

- Coordination (fable-5 CT1): M1-284 and this ticket are the two halves of
  one silent-reply-loss story. If M1-284 has landed, the fallback send goes
  through the same adapter SPI surface the chokepoint wraps — no Provider
  change needed here either way; just check the worktree landscape at start
  (both tickets touch the simplex package: M1-284 only MessagingException's
  javadoc, so overlap is one file at most).
- The "fallback" flag lives on the adapter-internal handle
  (SimpleXMessageHandle); no SPI shape change.
- M1-283's `SimpleXOutboundChunker` already solves over-cap fresh sends —
  the fallback path routes the body through it.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-285-*.md
```
