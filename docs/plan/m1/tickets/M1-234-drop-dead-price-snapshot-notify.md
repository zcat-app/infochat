---
id: M1-234
title: "Drop the dead new_price_snapshot NOTIFY channel (+ spec)"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 6
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
spec_amend_for: docs/spec/architecture.md §Inter-service communication
out_of_scope:
  - The price_snapshot table, the dedup constraint (V38), and AssetSnapshotReader's direct indexed read — the read path is already correct and cheap (single (asset, sub_verb, captured_at DESC) lookup) and is the single source of truth; do NOT add a cache.
  - The new_post and quarantine_review channels — unchanged; only new_price_snapshot is removed from the closed channel list.
  - Building a Provider-side in-process cache + listener (the rejected Option B) — explicitly NOT done; v1 has no measured need.
acceptance:
  - "The pg_notify emit on the new_price_snapshot channel is removed from PriceSnapshotStore.store and the NEW_PRICE_SNAPSHOT_CHANNEL constant is deleted; every snapshot write no longer pays a pg_notify round-trip with no consumer."
  - "docs/spec/architecture.md §Inter-service communication is amended to a two-channel closed list (new_post, quarantine_review); the new_price_snapshot channel and its 'Provider in-process cache flushed on reconnect' guarantee are removed (or recorded as a v2 candidate), so spec and code agree."
  - "docs/spec/schema.md §Operational (the 'NOTIFY new_price_snapshot is the latency optimization' sentence) is reconciled with the amended channel list."
  - "Tests that asserted the channel emit are updated to the table-read-is-truth behavior (PriceSnapshotStoreTest; AssetCommandsRoundtripIT) — the asset read path still returns the latest snapshot correctly."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStoreTest.java (drop the NOTIFY-emit assertion; keep the INSERT/dedup assertions — authorized: this ticket removes the channel)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandsRoundtripIT.java (drop any LISTEN/NOTIFY round-trip assertion; the table-read result assertions remain — authorized: this ticket removes the channel)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/schema.md §Operational
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-234: Drop the dead new_price_snapshot NOTIFY channel (+ spec)

## Context

Deep-review finding `deep-code-review/v2.5/opus-48/01-architecture.md#F1`
(medium SIMPLIFICATION / contract-surface mismatch). `architecture.md`
§Inter-service communication declares `new_price_snapshot` as one of three
closed v1 channels and ties it to a Provider in-process cache "flushed
entirely on every Postgres reconnect." That cache was never built:
`AssetSnapshotReader` reads `price_snapshot` directly with `ORDER BY
captured_at DESC LIMIT 1` on every `/zcash`/`/monero` invocation, and there
is no production `LISTEN new_price_snapshot` consumer (verified: zero refs
in `infochat-provider/src/main`; no cache field in `AssetSnapshotReader`).
As built, the channel is dead weight — every Collector snapshot write pays
an extra `pg_notify` round-trip whose only subscribers are tests — and the
spec promises infrastructure that does not exist. The producer's own
javadoc concedes "no production consumer yet."

## Acceptance

See frontmatter. In prose: delete the `pg_notify` emit and the channel
constant from `PriceSnapshotStore`, amend `architecture.md` to a
two-channel closed list and remove the cache/reconnect-flush guarantee,
reconcile the `schema.md` §Operational sentence, update the two tests that
asserted the emit, and confirm the asset read path still returns the latest
snapshot; `mvn verify` is 0.

## Out-of-scope

See frontmatter. This is the recommended Option A (drop the channel). The
rejected Option B (build the cache + listener to honor the prose) adds a
third long-lived LISTEN connection, a cache, and invalidation logic for a
single-row indexed lookup that does not measurably need it. The read path
and table are unchanged.

## Notes

- This is a code+spec change, so it is a ticket (CLAUDE.md §Commit
  prefixes). The spec amendment is part of the deliverable, not a separate
  `spec:` commit.
- No runtime behavior is lost: the reader was never cache-backed; staleness
  is already bounded by `captured_at` arithmetic.
