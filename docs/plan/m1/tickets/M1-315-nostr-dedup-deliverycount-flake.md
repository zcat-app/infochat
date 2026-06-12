---
id: M1-315
title: "NostrDedupIT.multiRelayDedup over-asserts deliveryCount==1 (flake)"
status: done
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - NostrDedupFilter / NostrStreamSource production behaviour — M1-287's record-after-offer design is correct as-shipped; this ticket only corrects a test that asserts more than the design guarantees.
acceptance:
  - "NostrDedupIT.multiRelayDedup no longer asserts deliveryCount == 1 (an assertion stricter than M1-287's documented design, which leaves a residual two-relay window where both arrivals pass seen() before either records and both deliver, absorbed downstream by PostPersister). The firm single-post-row assertion is preserved; the delivery assertion is relaxed to the bound the design guarantees: 1 <= deliveryCount <= relays.size()."
  - "mvn -B clean verify from the repo root exits 0 (collector full-suite run no longer flakes on the deliveryCount assertion under contention)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions: []
---

# M1-315: NostrDedupIT.multiRelayDedup over-asserts deliveryCount==1 (flake)

## Context

`NostrDedupIT.multiRelayDedup` fires the same Nostr event from two relays
and asserts both (a) exactly one post row and (b) `deliveryCount == 1`
("in-memory dedup short-circuited the second arrival before deliver").

Assertion (b) is stricter than the implementation guarantees. M1-287
(commit 401ed220, "Nostr dedup records event id only after a successful
queue offer") deliberately records an event id only *after* a successful
`offer()`, and its own commit message documents the consequence: "The
residual two-relay window (both pass `seen()` and both enqueue the same id
before either records) is absorbed downstream by PostPersister's WHERE NOT
EXISTS uid pre-filter + ON CONFLICT DO NOTHING." So when the two relay
arrivals race into that window, **both** deliver callbacks fire
(`deliveryCount == 2`); the single-post-row guarantee comes from downstream
DB dedup, not from a single in-memory short-circuit.

The `deliveryCount == 1` assertion therefore only holds when the two
arrivals happen to be processed sequentially (first `record()` before
second `seen()`). It passed in M1-287's quieter dedicated worktree but
flakes reliably under full-suite contention (observed 2026-06-12: fails in
full `mvn clean verify`, passes in isolation). This is a latent flake the
M1-287 review missed.

## Fix

Relax assertion (b) to the bound the design guarantees — `1 <=
deliveryCount <= relays.size()` — so a real regression past best-effort
(3+ deliveries, or zero) still fails, but the documented two-relay race no
longer flakes. The single-post-row and correct-id assertions are unchanged.

## Out-of-scope

Production dedup behaviour is correct as-shipped (M1-287); this is a
test-only correction.
