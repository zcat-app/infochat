---
id: M1-283
title: "Over-cap outbound digest dropped on SimpleX"
status: done
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
  - The in-place edit path (SimpleXMessageCodec.encodeUpdateCommand / encodeFinalizeCommand, the progress-notifier live-edit sequence) — chunking applies to fresh sends; an in-place edit of one existing message cannot be split across messages, so the edit path keeps its over-cap rejection.
acceptance:
  - "An over-cap outbound digest no longer triggers a PERMANENT drop: a named test in infochat-messaging-adapter constructs an outbound text whose UTF-8 length exceeds the SimpleX adapter outbound cap (MAX_OUTBOUND_TEXT_BYTES) and asserts the adapter does NOT raise a PERMANENT MessagingException and that the full text content reaches the transport across multiple ordered sends, each send's UTF-8 length within the cap."
  - "Chunk boundaries preserve code-block fences: a named test constructs an over-cap outbound text in which a triple-backtick code block spans a chunk boundary and asserts every emitted chunk carries balanced fences (the open fence is closed before the boundary and reopened at the start of the next chunk)."
  - "Chunk boundaries never split a code point: a named test constructs an over-cap outbound text of multi-byte characters (e.g. Cyrillic or CJK) positioned so a naive byte-offset split would cut a character, and asserts every emitted chunk's UTF-8 byte length is within the cap and no chunk begins or ends with an unpaired UTF-16 surrogate."
  - "docs/design/06-messaging.md §6.3.4 documents outbound chunking (split policy, fence preservation, ordering) consistent with the post-M1-274 reconciled state; design and code agree."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 508
      removed: 29
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-10
  verdict: WARN
  warnings:
    - "Acceptance item 4 (design-and-code-agree) has no machine-checkable verification form; reviewer verifies by inspection that §6.3.4 enumerates the byte-split algorithm, the code-point boundary rule (no surrogate splitting), and the code-block fence open/close protocol across chunk boundaries."
  blockers: []
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

- **Decision (2026-06-10, user-confirmed at start): chunking** — split an
  over-cap outbound text across multiple ordered sends at the adapter send
  path. Rationale: (a) no content loss; (b) the cap is enforced in the codec
  for every outbound send, so an adapter-level fix covers all callers, not
  just digests; (c) the cap is bytes, not characters — UTF-8 charges 2–3
  bytes per non-Latin character, so per-scope /lang translation halves or
  thirds the effective budget and "4000 is plenty" does not hold for
  non-Latin scripts. Length-bounding (trim-to-fit with truncation indicator)
  was considered and rejected: it fixes only the digest caller and loses
  content.
- Code-block fences MUST be preserved across the split (close before a chunk
  boundary, reopen after) — pinned by a named test. This restores the
  behaviour design §6.3.4 promised before M1-274.
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
