---
id: M1-349
title: "Spec/design: correct the asset-fetch SPI name (AssetDataSource, not the post Fetcher)"
status: done
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 2
files_scope:
  - docs/spec/commands.md
  - docs/design/10-asset-commands.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any code change. The implementation already follows architecture.md (the dedicated AssetDataSource SPI, AssetSnapshotFetcher implements no SPI); this ticket only corrects the two drifted doc artifacts so spec, design, and code state ONE contract.
  - architecture.md §Ingest SPIs — it is already correct (the spec-of-record for SPI shape) and is the target the other two are aligned to.
  - The asset freshness-window decoupling (M1-340) — a separate, code-touching concern in a different part of commands.md.
acceptance:
  - "docs/spec/commands.md §Asset commands no longer states asset polling 'reuse[s] the existing Fetcher SPI'. The sentence is corrected to name the dedicated asset-fetch SPI (per architecture.md §Ingest SPIs — Output type), separate from the post Fetcher: snapshots write directly to price_snapshot and never enter the post outbox or Stage 1/2. The 'refresh interval keyed per-data-source-host' wording is preserved."
  - "docs/design/10-asset-commands.md no longer claims 'AssetSnapshotFetcher implements Fetcher (polled)' (line 34) or 'each (asset, source, vs_currency) triple is a Fetcher tick' (line 160). Both are corrected to describe the real shape: AssetSnapshotFetcher is a @Scheduled bean that drives AssetDataSource implementations; each triple is one AssetDataSource fetch per asset-fetch tick."
  - "After the edits, the three artifacts (architecture.md, commands.md, design/10) agree with each other and with the code: grep for 'reuse the existing Fetcher SPI' and 'implements Fetcher' in docs/spec/commands.md and docs/design/10-asset-commands.md returns no asset-path match. No code is touched."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/commands.md §Asset commands
decision_refs:
  - D39
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-349: Correct the asset-fetch SPI name in spec + design

## Context

Deep-review v5.5 (opus-48, `01-architecture.md` F1 + F2 — one root cause across
two artifacts) found that the spec contradicts itself on the asset-fetch SPI.
**Verified at source 2026-06-14:**

- `docs/spec/commands.md:270-271` says polled asset data sources "reuse the
  existing `Fetcher` SPI."
- `docs/spec/architecture.md:161-163` (the SPI-shape source of truth) mandates a
  "separate ingest path ... a dedicated asset-fetch SPI" that writes directly to
  `price_snapshot`, never the post outbox.
- The code follows architecture.md: `AssetDataSource` is the interface
  (AssetDataSource.java:25); `AssetSnapshotFetcher` implements no SPI
  (AssetSnapshotFetcher.java:89, a `@Scheduled` bean). The post `Fetcher` returns
  `List<NormalizedPost>` (the outbox shape) — which asset snapshots must never
  enter.
- `docs/design/10-asset-commands.md:34,160` doubles down on the wrong name
  ("implements Fetcher", "each triple is a `Fetcher` tick").

A `Fetcher` literally reused for assets would route snapshots through the post
pipeline — the exact thing architecture.md and CLAUDE.md §Key conventions ("Asset
commands are not posts") forbid. The code already resolved the conflict in favor
of architecture.md, so the risk is forward-looking: a future ticket scoped from
commands.md would re-encode the wrong contract or burn a planning round.

## Delivery note

This is a **pure-doc** change (no code), so per CLAUDE.md §"Commit prefixes" it
MAY land as a plain `spec:` commit (`spec: name the asset-fetch SPI AssetDataSource
in commands.md + design/10`), bypassing the clarity/reviewer/`mvn verify` gates.
The ticket exists for tracking and to record the deep-review provenance; the user
may elect the `spec:` shortcut or the full ticket flow. architecture.md is
already correct and is the alignment target — do not edit it.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.
