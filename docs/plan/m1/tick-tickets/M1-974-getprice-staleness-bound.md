---
id: M1-974
title: "Bound price_snapshot staleness; clamp captured_at at ingest"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  Two tests (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/websearch-grounding-lane.md; both
  to-be-written, converted at /tick start: written first, run RED
  against unmodified code): GetPriceStalenessBoundIT
  #beyondMaxStalenessRowReturnsTypedNoData — seeds one price_snapshot
  row whose captured_at is 90 days before the pinned app Clock, pins
  the Clock via QuarkusMock.installMockForType(Clock.fixed(...))
  (engineering-rules §9), dispatches {"asset": ...} through a REAL
  ChatToolDispatcher, and asserts the typed no-data ValidationError
  naming the (asset, sub-verb) pair; RED today because the spec'd and
  implemented behavior serves the row forever once one exists — the
  row is returned merely flagged stale (AssetSnapshotReader
  .loadLatest's ORDER BY captured_at DESC LIMIT 1 read at
  AssetSnapshotReader.java:156-164 plus the isStale flag at :173; the
  spec promise is double: "A snapshot older than the freshness window
  is still returned, marked stale: true … only a pair with no row at
  all returns the no-data error" (docs/spec/security.md:335) and "the
  Provider serves the most recent row available with an explicit
  'data is N minutes old' line, and degrades to a friendly error only
  when no row exists at all" (docs/spec/commands.md §Asset commands,
  Freshness contract). And PriceSnapshotClampTest
  #futureDatedCapturedAtIsClampedAtIngest — a source-built snapshot
  whose captured_at is 5 minutes AHEAD of the injected Clock (the
  skewed-host-clock stub) is clamped to <= the Clock's now before the
  INSERT; RED today because no clamp exists (grep -rn 'clamp\|skew'
  over infochat-collector/.../assets/ returns nothing — verified
  2026-09-01). Verified premise correction the implementer carries:
  captured_at is NOT upstream-supplied today — all three sources stamp
  Instant.now() at fetch time (KrakenSnapshotSource.java:173,
  CoingeckoSnapshotSource.java:149, BitfinexSnapshotSource.java:158),
  so the clamp guards the FUTURE-DATED-WRITE class (host-clock skew; a
  future source parsing upstream timestamps), the searchPosts
  published_at doctrine's trap class in latent form, not present
  source values.
analysis_ref: docs/plan/m1/tick-analysis/websearch-grounding-lane.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPriceStalenessBoundIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReaderClockTest.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/PriceSnapshotClampTest.java
  - docs/spec/security.md
  - docs/spec/commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The fetch cadence, failure ladder, freshness-window semantics, and
    every other asset-pipeline behavior — the per-host refresh keys,
    the failure-counter park, and the freshness WINDOW (stale-flag)
    are correct as shipped; this ticket only adds the OUTER bound
    (beyond-max-staleness → no-data) and the ingest clamp. A mutation
    refusing all stale rows (collapsing window and bound) fails the
    within-bound arm.
  - >-
    Any getPrice/GetPriceTool surface change — the tool's emission,
    resolution, and error shapes are untouched; the bound changes what
    the READER returns, and the tool's existing typed no-data path
    carries it (the beyond-bound outcome is indistinguishable from
    no-row at the tool boundary — both are the typed no-data error).
  - >-
    The web-lane fallback WIRING — the beyond-bound no-data outcome is
    a typed no-data outcome the P20 fallback ladder (M1-969/M1-972)
    may observe once that lane lands; this ticket wires nothing for it
    and depends on nothing from it (independent either direction).
  - >-
    Migrating or backfilling price_snapshot — no schema change (the
    bound is a read-side property; the clamp a write-side guard); the
    stored rows and grants are untouched.
  - >-
    Weather — M1-973's row states the SAME bounded contract by
    cross-reference; whichever lands second aligns its row text with
    the other (sibling coordination via the spec text, never a shared
    diff).
acceptance:
  - "REPRODUCTION closed (the bound half): GetPriceStalenessBoundIT.beyondMaxStalenessRowReturnsTypedNoData passes — the 90-day-old seeded row with the pinned Clock returns the typed no-data ValidationError naming the (asset, sub-verb) pair through the real dispatcher (today's RED: the row serves flagged stale). Mutations failing it: serving the row with stale:true, or throwing."
  - "THE CONTRACT'S SURVIVING HALF (stale-within-bound still serves): GetPriceStalenessBoundIT.staleWithinBoundServesWithAgeDisclosed passes — a row older than infochat.assets.freshness-window but younger than the max-staleness bound STILL serves, with \"stale\":true and \"age_seconds\" equal to pinnedNow − captured_at (the existing getPrice posture byte-identical inside the bound; a mutation refusing all stale rows fails)."
  - "REPRODUCTION closed (the clamp half, failure-mode): PriceSnapshotClampTest.futureDatedCapturedAtIsClampedAtIngest passes — a snapshot built with captured_at 5 minutes ahead of the injected Clock is clamped to <= the Clock's now before the INSERT reaches PriceSnapshotStore (the store seam stubbed/recording); a snapshot at-or-behind now passes UNCHANGED (no drift introduced); the comparison reads ONLY the injected Clock (engineering-rules §9 — probe: grep -n 'Instant.now()' over the clamp hunk returns nothing; the sources' own Instant.now() stamps are the PRE-clamp input, which is the point)."
  - "READ-SIDE WIRING: AssetSnapshotReader.loadLatest applies the bound as a decision-gate comparison on the already-injected Clock (AssetSnapshotReader.java:71 the §9 pattern; :173 the isStale precedent) — probe: grep -n 'max-staleness' infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java returns the property read; no second Clock is introduced (never split one component across two clocks, §9)."
  - "CONFIG PARITY (M1-708 discipline): infochat.assets.max-staleness lands in the provider's application.properties with per-profile defaults where the freshness-window convention requires them — probe: grep -c 'infochat.assets.max-staleness' infochat-provider/src/main/resources/application.properties returns >= 1 and every profile block that carries freshness-window carries max-staleness."
  - "§8-AUTHORIZED pre-existing-test modification (engineering-rules §8, conditional, the M1-972 budget-test precedent shape): AssetSnapshotReaderClockTest — IF and only if any of its fixtures rely on unbounded stale serving (a row older than the new default bound), the affected arms' FIXTURES move inside the bound with ZERO assertion changes (the stale-flag and age assertions keep passing on the recalibrated fixture); no other pre-existing test is touched — probe: git diff over src/test names at most this one file's fixture lines."
  - "SPEC AMENDMENT rides the diff (engineering-rules §12 — the exact wording goes to the user for approval at implementation; ONE diff amends BOTH spots, the same-section fusion rule): (i) docs/spec/security.md's getPrice row freshness sentence gains the outer bound — within the operator-configured max-staleness window a stale snapshot still serves with its age disclosed; beyond it the typed no-data path fires; only-no-row remains no-data's other trigger (the existing sentence's stale-with-age posture survives inside the bound); (ii) docs/spec/commands.md §Asset commands' Freshness contract paragraph gains the same outer-bound rule (its \"degrades to a friendly error only when no row exists at all\" clause becomes the two-trigger form: no row at all, OR beyond the max-staleness bound). Probes: both amended spots state the two-trigger rule; grep of the added prose for dates/ticket IDs returns nothing; the amendment is tightening-only — no capability, surface, or content class is added."
  - "AMENDMENT-SHAPE RECORD (the analyst's classification, stated for the reviewer): this is a rides-the-diff promise change, NOT a K1-class amendment-first gap — it TIGHTENS an existing freshness promise (removes an over-serving behavior), adds no content class and no new surface, exactly the M1-932/M1-940 precedent class of row-level promise edits that rode their diffs under §12 with user-approved wording; the M1-779 fusion rule is satisfied by amending both spec spots in this one ticket. Probe: git diff docs/spec/security.md docs/spec/commands.md names exactly two amended hunks — the getPrice row's freshness sentence and the §Asset commands Freshness contract paragraph — and grep of the added prose for ticket IDs or dates returns nothing (the classification itself is ticket record, never spec prose)."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPriceStalenessBoundIT.java
      — beyondMaxStalenessRowReturnsTypedNoData (the bound-half
      reproduction), staleWithinBoundServesWithAgeDisclosed.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/assets/PriceSnapshotClampTest.java
      — futureDatedCapturedAtIsClampedAtIngest (the clamp-half
      reproduction, pinned Clock).
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReaderClockTest.java
      — CONDITIONAL §8 authorization (acceptance item 6): fixture-only
      recalibration of any arm whose seeded row now falls beyond the
      default bound; assertions unchanged.
  preserves:
    - >-
      all tests currently green on main — explicitly every asset suite
      (AssetHandlerIT, AssetCommandsRoundtripIT, AssetSnapshotReaderCacheIT,
      GetPriceToolIT's freshness arms: their stale fixtures must sit
      INSIDE the default bound or carry the same authorized fixture
      recalibration), the getPrice tool surface suites, and the
      migration/grant suites (no schema change).
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Asset commands
decision_refs:
  - D19
  - D39
---

# M1-974: Bound price_snapshot staleness; clamp captured_at at ingest

## Context

The freshness contract serves the most recent price row FOREVER once
one exists: a months-long fetch outage keeps answering "what is the
price of zcash?" with a months-old number, merely flagged stale — and
the flag is the only honesty the reply carries (analysis P21). The
owner's change note adds the outer bound: an operator-configurable
max-staleness, beyond which the typed no-data path fires instead of
stale serving — and an ingest-side clamp on future-dated `captured_at`
writes (analysis P22, with the verified premise correction: today's
sources stamp `Instant.now()`, so the clamp guards the future-dated-
WRITE class, not present values). Independent of the web-grounding
lane; its no-data outcome becomes a P20 fallback trigger only once
M1-972 lands. Shared analysis: `analysis_ref:` (this ticket carries
P21, P22, and P20's observation-only interplay).

## Root cause

Verified end to end: `AssetSnapshotReader.loadLatest` reads
`ORDER BY captured_at DESC LIMIT 1`
(AssetSnapshotReader.java:156-164) and flags staleness against the
injected Clock (`:71`, `:173`) with NO outer bound — the isStale flag
changes prose, never the serve/no-data decision. The spec promises the
unbounded behavior twice (security.md:335's row sentence; commands.md
§Asset commands' Freshness contract "degrades to a friendly error only
when no row exists at all"). No ingest-side clamp exists (grep over
the assets package, 2026-09-01); `captured_at` is collector-stamped
`Instant.now()` by all three sources (Kraken:173, Coingecko:149,
Bitfinex:158), so the ORDER BY's future-dated-write exposure is latent
(today) but ungarded — the searchPosts `published_at` head-seizure
doctrine's trap class, cheap to close at the same seam.

## Pitfalls

Carried from the analysis: P21 (the promise change is
tightening-only and must amend BOTH spec spots in one diff — the
M1-779 fusion rule — with user-approved wording; the within-bound
stale-with-age posture survives byte-identical), P22 (the clamp reads
the injected Clock and must not introduce a second clock into the
component — §9's never-split rule; the sources' own `Instant.now()`
stamps are the pre-clamp input), P20 (observation-only: the
beyond-bound outcome is a typed no-data outcome the fallback ladder
may consume later; nothing is wired here and no tool parses anything).
Also: fixture calibration — existing stale-serving fixtures
(GetPriceToolIT's, AssetSnapshotReaderClockTest's) must sit inside the
default bound or carry the authorized fixture-only recalibration.

## Approach

Derived from `spec_refs:` — §Asset commands owns the freshness
contract being bounded; the getPrice row (§Prompt-injection defenses)
carries the same promise and amends in the same diff.

- **Files to touch:** `files_scope` (two collector files + collector
  properties, one provider reader + provider properties, two new test
  classes + one conditional fixture recalibration, two spec files).
- **Pre-decided shapes (implementation is execution):**
  1. **Reader bound:** `AssetSnapshotReader` gains
     `@ConfigProperty infochat.assets.max-staleness` (Duration;
     application.properties the source of truth, mirroring the
     freshness-window convention at `:73-81`); `loadLatest` returns
     null (the existing no-data shape) when the latest row's age
     exceeds the bound — the comparison on the SAME injected Clock the
     isStale verdict uses (`:173`), one decision-gate read, no new
     clock. The short-TTL cache (`:83-101`) caches the null result
     like any other read (a snapshot landing after the miss becomes
     visible on TTL expiry — the existing miss-not-cached rule at
     `:119-121` covers the no-ROW miss; the beyond-bound miss caches
     the same way a served read does, bounded by the TTL).
  2. **Ingest clamp:** at the fetcher/store seam
     (AssetSnapshotFetcher → PriceSnapshotStore), clamp
     `snapshot.capturedAt()` to `<= clock.instant()` before the INSERT
     — injected Clock (§9); an at-or-behind stamp passes unchanged.
  3. **Tests per `test_plan.adds`**, RED first; the conditional
     ClockTest fixture recalibration per `test_plan.modifies`.
  4. **Spec amendments** per acceptance item 7, with the user's
     wording approval at implementation (§12).
- **Steps, in implementation order:** (1) the two RED tests; (2) the
  reader bound + property; (3) the ingest clamp + property; (4) the
  conditional fixture recalibration; (5) the two spec spots with the
  user's approval; (6) full `mvn verify`.
- **Controls to preserve (§10):** the freshness WINDOW's stale-with-age
  posture inside the bound (GetPriceToolIT's stale arm keeps passing
  on a within-bound fixture); the asset failure ladder, cadence keys,
  grants, and the store's ON CONFLICT dedup invariant
  (PriceSnapshotStore.java:39-50); the getPrice tool's emission and
  error shapes byte-identical (the bound changes the reader's return,
  not the tool).
- **Pitfall→mitigation:** P21→items 1/2/7 (the within-bound arm pins
  the surviving half; both spec spots in one diff); P22→item 3 (the
  injected-Clock probes; no Instant.now() in the clamp hunk); P20→
  out_of_scope's no-wiring rule + the diff fence; fixtures→items 2/6.

## Definition of done

Both reproductions pass (beyond-bound → typed no-data;
future-dated stamp clamped); the within-bound stale-with-age arm
passes unchanged in substance; the config keys land with profile
parity; at most the one conditional fixture recalibration touches a
pre-existing test; both spec spots carry the user-approved two-trigger
rule with tightening-only shape; every asset suite passes; `mvn
verify` green from the repo root.

## Verification

- P21 → `GetPriceStalenessBoundIT.beyondMaxStalenessRowReturnsTypedNoData`
  (a serve-stale mutation fails) + `…staleWithinBoundServesWithAgeDisclosed`
  (an over-tight mutation refusing all stale rows fails) + item 7's
  two-spot probes.
- P22 → `PriceSnapshotClampTest.futureDatedCapturedAtIsClampedAtIngest`
  (an unclamped future stamp reaching the store fails; a behind-now
  stamp must NOT be rewritten — a drift-introducing mutation fails)
  + item 4's single-Clock probes.
- P20 → the diff fence: no ChatAgent/websearch file moves; the no-data
  outcome rides the getPrice tool's EXISTING error shape (reviewer
  diff check).
- FAILURE-MODE coverage → the clamp drive feeds the hostile input (a
  skewed future-dated stamp) to this diff's own production code; the
  bound drive feeds the edge (a row exactly at the bound — the
  boundary arm's fixture sits one instant inside, asserted served).
- acceptance items 5-9 → the property greps, the conditional §8 probe,
  the spec probes, mvn verify.

## Out-of-scope

Named in `out_of_scope`: the fetch cadence/failure ladder/freshness
window semantics; any getPrice tool-surface change; the web-lane
fallback wiring; migrations/backfill; weather (cross-referenced via
the shared row wording only). ONE pre-existing test file may carry the
CONDITIONAL fixture-only recalibration recorded in
`test_plan.modifies`; every other pre-existing suite must pass
unmodified.

## Census

Class-scoped: the unbounded-stale-serving promise is stated at a
CLASS of spec sites, all of which must gain the same outer bound in
their own diffs or the rows drift. Re-runnable enumeration: `grep -rn
'no row' docs/spec/ docs/design/` plus the freshness-contract
paragraphs. Sites (states verified at draft time, 2026-09-01):

- `docs/spec/security.md:335` — the getPrice row's freshness sentence
  → **FIX** (this ticket, item 7).
- `docs/spec/commands.md` §Asset commands — the Freshness contract
  paragraph ("degrades to a friendly error only when no row exists at
  all") → **FIX** (this ticket, item 7).
- `docs/spec/commands.md` §Asset commands — the Provider/Collector
  contract paragraph ("a stale read here is acceptable and bounded by
  the freshness contract below") → DISPOSED, still true by reference
  once the contract paragraph is bounded; no edit needed.
- M1-973's weather row (pending ticket) → DISPOSED here by
  cross-reference: its row text states the SAME bounded contract;
  whichever lands second aligns (out_of_scope).
- The web lane's P20 ladder (M1-969/M1-972, pending) → DISPOSED: it
  CONSUMES typed no-data outcomes and is indifferent to which trigger
  produced them; no edit here.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-974-getprice-staleness-bound.md
```
