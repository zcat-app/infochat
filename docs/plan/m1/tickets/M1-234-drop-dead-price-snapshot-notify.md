---
id: M1-234
title: "Drop the dead new_price_snapshot NOTIFY channel (+ spec)"
status: done
created: 2026-06-08
last_updated: 2026-06-09
blocked_by: []
files_budget: 8
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
spec_amend_for: "docs/spec/architecture.md §Inter-service communication; docs/spec/commands.md §Asset commands — Provider/Collector contract; docs/spec/security.md §DB roles"
out_of_scope:
  - The price_snapshot table, the dedup constraint (V38), and AssetSnapshotReader's direct indexed read — the read path is already correct and cheap (single (asset, sub_verb, captured_at DESC) lookup) and is the single source of truth; do NOT add a cache.
  - The new_post and quarantine_review channels — unchanged; only new_price_snapshot is removed from the closed channel list.
  - Building a Provider-side in-process cache + listener (the rejected Option B) — explicitly NOT done; v1 has no measured need.
acceptance:
  - "The pg_notify emit on the new_price_snapshot channel is removed from PriceSnapshotStore.store and the NEW_PRICE_SNAPSHOT_CHANNEL constant is deleted; every snapshot write no longer pays a pg_notify round-trip with no consumer."
  - "docs/spec/architecture.md §Inter-service communication is amended to a two-channel closed list (new_post, quarantine_review); the new_price_snapshot channel and its 'Provider in-process cache flushed on reconnect' guarantee are removed (or recorded as a v2 candidate), so spec and code agree."
  - "docs/spec/commands.md §Asset commands — Provider/Collector contract is amended: the 'emit NOTIFY new_price_snapshot with (asset, source)' clause and the in-process-cache 'warm/invalidate from the NOTIFY payload' + 'flushed entirely on every Postgres reconnect' guarantee are removed (or recorded as a v2 candidate); the contract states the table read is the sole correctness path, so this prose (which architecture.md §Inter-service communication cross-references) agrees with the code."
  - "docs/spec/security.md §DB roles is amended: new_price_snapshot is removed from the Provider role's LISTEN/NOTIFY channel list, leaving 'consumes new_post and quarantine_review channels', so the documented grant matches the two-channel closed list."
  - "docs/spec/schema.md §Operational is reconciled with the amended channel list at BOTH sites that reference the channel: the 'Provider state' per-channel bullet (the new_price_snapshot provider_state cursor-exemption entry) and the 'Price snapshot' bullet's 'NOTIFY new_price_snapshot is the latency optimization' sentence."
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
  - docs/spec/commands.md §Asset commands — Provider/Collector contract
  - docs/spec/security.md §DB roles
  - docs/spec/schema.md §Operational
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 126
      removed: 282
escalations:
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (budget-breach). Reference sweep for `new_price_snapshot` found the
      channel asserted in FOUR spec files, but acceptance + spec_refs name only
      two (architecture.md, schema.md). The two unnamed files —
      commands.md §Asset commands — Provider/Collector contract (L283-298,
      "emit NOTIFY new_price_snapshot" + the in-process cache + flush-on-reconnect
      guarantee, which architecture.md:49 cross-references as the authority) and
      security.md §DB roles (L1049, Provider LISTEN/NOTIFY channel list) — both
      assert the channel. Touching them is required to meet acceptance item 2's
      own goal ("so spec and code agree"), but pushes the file count to 7,
      exceeding files_budget: 6, and amends spec sections absent from
      spec_refs/spec_amend_for.
revisions:
  - date: 2026-06-08
    reason: budget-breach refine — reference sweep found new_price_snapshot
      asserted in 4 spec files, but acceptance/spec_refs named only 2.
      Add commands.md §Asset commands — Provider/Collector contract (the
      canonical NOTIFY-emit + in-process-cache + flush-on-reconnect prose that
      architecture.md:49 cross-references) and security.md §DB roles (Provider
      LISTEN/NOTIFY channel-list grant) to acceptance + spec_refs + spec_amend_for;
      tighten acceptance item 3 to cover BOTH schema.md sites (the provider_state
      cursor-exemption bullet AND the latency-optimization sentence). files_budget
      6→8.
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      files_budget_at_snapshot: 6
      spec_amend_for_at_snapshot: docs/spec/architecture.md §Inter-service communication
      spec_refs_at_snapshot:
        - docs/spec/architecture.md §Inter-service communication
        - docs/spec/schema.md §Operational
      acceptance_at_snapshot:
        - "The pg_notify emit on the new_price_snapshot channel is removed from PriceSnapshotStore.store and the NEW_PRICE_SNAPSHOT_CHANNEL constant is deleted; every snapshot write no longer pays a pg_notify round-trip with no consumer."
        - "docs/spec/architecture.md §Inter-service communication is amended to a two-channel closed list (new_post, quarantine_review); the new_price_snapshot channel and its 'Provider in-process cache flushed on reconnect' guarantee are removed (or recorded as a v2 candidate), so spec and code agree."
        - "docs/spec/schema.md §Operational (the 'NOTIFY new_price_snapshot is the latency optimization' sentence) is reconciled with the amended channel list."
        - "Tests that asserted the channel emit are updated to the table-read-is-truth behavior (PriceSnapshotStoreTest; AssetCommandsRoundtripIT) — the asset read path still returns the latest snapshot correctly."
        - "mvn -B clean verify from the repo root exits 0."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-09
    verdict: CLEAN
    base: c378bcf
    head: working-tree (in-review, uncommitted; --in-progress audit)
    verdict_file: docs/plan/m1/redteam/M1-234-2026-06-09.md
    findings_count: 0
    out_of_model_count: 1
    note: |
      Pre-APPROVE --in-progress audit of the dead-channel removal. CLEAN: the
      diff deletes the new_price_snapshot pg_notify emit + channel constant and
      narrows the Provider DB role's documented LISTEN/NOTIFY grant (a tightening,
      not a loosening). Falsifier check confirmed no consumer ever depended on the
      channel — AssetSnapshotReader does a direct indexed table read — so no
      orphaned consumer, no broken freshness guarantee, no finding to remediate.
      One advisory OUT-OF-MODEL note (price-oracle clock-skew / freshness-window
      trust) is NOT introduced by this diff; the user decides whether the threat
      model should gain an explicit price-oracle-freshness entry.
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
  blockers: []
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
amend `commands.md` §Asset commands — Provider/Collector contract (the
canonical NOTIFY-emit + in-process-cache + flush-on-reconnect prose that
`architecture.md` cross-references) and `security.md` §DB roles (drop
`new_price_snapshot` from the Provider's LISTEN/NOTIFY channel list),
reconcile both `schema.md` §Operational sites (the `provider_state`
cursor-exemption bullet and the latency-optimization sentence), update the
two tests that asserted the emit, and confirm the asset read path still
returns the latest snapshot; `mvn verify` is 0.

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
