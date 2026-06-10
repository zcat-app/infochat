---
id: M1-283
title: "Over-cap outbound digest dropped on SimpleX"
status: pending
created: 2026-06-10
last_updated: 2026-06-10
blocked_by:
  - M1-274
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  - docs/design/06-messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The capability-flag surface reconciliation (maxMessageBytes / speculative flags) — that is M1-274; this ticket addresses the underlying delivery gap, not the flag declarations.
  - Signal outbound sizing — the confirmed drop path is the SimpleX 4000-byte cap; Signal has no adapter-side outbound cap today. Touch the Signal adapter only if the chosen mechanism is shared codec code.
  - Inbound byte caps (MAX_INBOUND_TEXT_BYTES on either adapter) — unrelated to outbound delivery.
acceptance:
  - "An over-cap outbound digest no longer triggers a PERMANENT drop: a named test in infochat-messaging-adapter constructs an outbound text whose UTF-8 length exceeds the SimpleX adapter outbound cap (MAX_OUTBOUND_TEXT_BYTES) and asserts the adapter does NOT raise a PERMANENT MessagingException and that a non-empty message reaches the transport (across one or more ordered sends, or as a bounded summary that fits the cap)."
  - "docs/design/06-messaging.md documents the chosen delivery mechanism (outbound chunking, or pre-send summary length-bounding) consistent with the post-M1-274 state of §6.3.4; design and code agree."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
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

# M1-283: Over-cap outbound digest dropped on SimpleX

## Context

Surfaced while implementing M1-274 (capability-flag reconciliation). The
SimpleX adapter enforces an outbound text cap by *rejecting*, not chunking:
`SimpleXMessageCodec.requireWithinCap` throws a PERMANENT `MessagingException`
when the UTF-8 length exceeds `MAX_OUTBOUND_TEXT_BYTES` (4000). PERMANENT
means no retry. There is no chunking or truncation anywhere on the send path
— `DigestWorker` builds one `OutboundMessage` and calls `adapter.send(msg)`
directly. A group digest summarises up to `cluster-cap` (200) posts and can
plausibly exceed 4000 bytes, so a long digest fails the send outright and the
**recipient receives nothing** (not a truncated message — the whole send is
dropped). Signal has no adapter-side outbound cap today, so this is a SimpleX
delivery gap.

M1-274 deletes the declared-but-unread `maxMessageBytes` capability flag and
amends design §6.3.4 to stop committing to outbound chunking. That cleanup is
correct on its own (the flag is wired to nothing), but it leaves this delivery
gap unaddressed and removes the design's prior chunking promise. This ticket
exists to decide and implement the actual delivery behaviour.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — the capability-flag declarations (M1-274), Signal outbound
sizing, and inbound caps are excluded.

## Notes

- **⚠ Product decision embedded:** two mechanisms meet the goal —
  1. **Chunking:** split an over-cap message across multiple ordered sends.
     If chosen, code-block fences MUST be preserved across the split (close
     before a chunk boundary, reopen after) — pin that with a named test.
     This is the behaviour design §6.3.4 *used* to promise before M1-274.
  2. **Length-bounding:** generate/trim the digest summary to fit under the
     cap before send, with a truncation indicator. Simpler; loses content.
  Decide at start and refine this ticket to the chosen mechanism before
  implementing — do not implement both.
- `blocked_by: M1-274` so the design §6.3.4 edits don't collide: M1-274 lands
  the "no chunking commitment" reconciliation first, then this ticket
  re-introduces the chosen delivery guarantee on top of the reconciled state.
- Evidence (verified 2026-06-10): `SimpleXMessageCodec.java` `requireWithinCap`
  / `MAX_OUTBOUND_TEXT_BYTES`; `DigestWorker.java` `adapter.send(msg)` with no
  split; `SignalMessageCodec.encodeSend` has no outbound byte cap.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-283-*.md
```
