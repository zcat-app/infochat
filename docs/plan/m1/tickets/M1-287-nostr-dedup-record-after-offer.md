---
id: M1-287
title: "Nostr dedup records event id only after a successful queue offer"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrDedupFilter.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The queue sizing / drain-rate tuning that makes the burst drop likely — only the poisoned-dedup consequence is fixed here.
  - The coalesced queue-full WARN itself (it stays; it is the operator signal).
  - DB-side dedup (ON CONFLICT + uid pre-filter) — unchanged; it is what absorbs the small double-enqueue window the fix introduces.
acceptance:
  - "Spec invariant restored (docs/spec/architecture.md ~:183, verbatim: 'Delivery to the outbox is at-least-once: an event is written to the outbox before the implementation considers it processed'): a named test fills the inbound queue, delivers an event (offer returns false, event dropped), then replays the same event id and asserts it is ACCEPTED — the drop did not poison the dedup set."
  - "NostrDedupFilter's single accept(id) (put-under-lock, true-once) is split into a check ('seen') and a commit ('record') — or an equivalent API — such that NostrStreamSource records the id only after inbound.offer(event) returned true; ordering pinned by a named test."
  - "The pre-existing dedup behaviour is preserved, not weakened: a successfully-enqueued event id is still rejected on second delivery (existing tests stay green; the two-relay double-enqueue window is documented in a comment as absorbed by the DB ON CONFLICT + uid pre-filter)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
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

# M1-287: Nostr dedup records event id only after a successful queue offer

## Context

Deep-review v5 verified HIGH **U-04** (`deep-code-review/v5/UNIFIED-REPORT.md`
§2; source `deep-code-review/v5/opus-48/06-module-infochat-collector.md#F1`,
unique find — gitignored; all load-bearing facts inlined):

The NostrStreamSource enqueue path calls `dedupFilter.accept(id)` (which
`put`s under the lock and returns true exactly once — `NostrDedupFilter`
~:66-74) and only then `inbound.offer(event)`. When the offer returns false
(queue full), the event is dropped but the id stays recorded — so the relay
reconnect replay that the code's own comment promises is exactly what the
poisoned dedup set rejects. A relay sustaining a burst above drain rate
creates targeted permanent coverage gaps with only a coalesced WARN as
signal.

opus-48's report also analyses why the naive alternative — rolling the id
back out of the dedup set on a failed offer — races against a concurrent
second relay delivering the same id between record and rollback; the
record-after-offer split is the correct shape, and the residual
double-enqueue window it opens is absorbed downstream (`ON CONFLICT` + uid
pre-filter).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Coordination: M1-286 also edits `NostrStreamSource` (registrar/stop
  wiring). Different regions, but check the worktree landscape at start and
  sequence to avoid a needless rebase.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-287-*.md
```
