---
id: M1-678
title: "Render the asset 24h delta independently of the 24h spread"
status: done
created: 2026-07-22
last_updated: 2026-07-23
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
  - docs/design/10-asset-commands.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Rendering `volume_24h` or `change_7d_pct`. Both are collected, stored
    and selected into `AssetSnapshotReader.Snapshot`, and neither reaches
    a reply line — design §10.5 records them as unbuilt. Adding either is
    a feature with its own copy and bundle-key decisions, not this fix.
  - >-
    The BTC-denominated companion price and the multi-currency fetch it
    needs (design §10.9). Unrelated to this coupling.
  - >-
    The delta formatting itself. Fixed 2-dp HALF_UP and the U+2212 minus
    (M1-592) stay exactly as they are.
acceptance:
  - >-
    A snapshot carrying `change_24h_pct` but no `high_24h` / `low_24h`
    renders the 24h delta line. Today `AssetReplyRenderer.render()` gates
    the delta on all three being non-null, and the `else if` spread branch
    is equally unreachable for that row, so such a snapshot renders
    NEITHER line and the delta the source did return is silently lost.
  - >-
    A snapshot carrying `high_24h` / `low_24h` but no `change_24h_pct`
    still renders the spread alone — today's Kraken path, unchanged.
  - >-
    Byte-identical output for every snapshot carrying all three: delta
    line, then `24h high:`, then `24h low:`. The coingecko and bitfinex
    example replies in design §10.5 must not move.
  - >-
    New AssetReplyRendererTest cases pin all three shapes above.
  - >-
    User directive 2026-07-23, folded in during implementation: a fiat
    price renders at a fixed 2 dp (HALF_UP), so a round 41.00 shows
    `$41.00` and 961.30 shows `961.30 CZK`. Today `formatPrice` calls only
    `stripTrailingZeros()`, which emits `$41` and `961.3 CZK` — output no
    §10.5 example reply shows, every one of which is written at 2 dp.
    Surfaced by the round-1 verify: a new test fixture using 41.00 failed
    on exactly this. Pre-existing, not introduced by the delta/spread split.
  - >-
    BTC-quoted prices are EXCLUDED from that scale and keep the source's
    own precision. `formatPrice` serves every vs-currency, and BTC quotes
    are sub-unit (0.000651), so a blanket 2 dp would round every one of
    them to `0.00 BTC`.
  - >-
    Design §10.5's price-line rendering rule states the fiat-2dp / BTC
    -full-precision split, so the drift cannot silently recur. The
    bitfinex example's `24h low:  $41.00` becomes producible and stays
    byte-identical.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Asset commands
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 217
      removed: 39
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-23
  verdict: PASS
  warnings:
    - >-
      lint-ticket.py: 0 blockers, 0 warnings. Self-check confirmed the
      ticket's code claims verbatim (AssetReplyRenderer.java:82-96 is the
      quoted branch; en/cs delta_24h and spread key values match §Expected
      shape).
    - >-
      The M1-658 inbound-reflection baseline does NOT need to change:
      its header scopes it to ERROR templates only, and AssetReplyRenderer
      appears nowhere in it (only AssetHandler error keys do). Per the
      ticket body's instruction ("if it does not, drop it from the scope
      rather than touching it"), the baseline path was removed from
      files_scope at start. files_budget stays 6 (a ceiling).
  blockers: []
---

# M1-678: Render the asset 24h delta independently of the 24h spread

## Context

`AssetReplyRenderer.render()` couples two independent facts:

```java
if (snap.change24hPct() != null && snap.high24h() != null && snap.low24h() != null) {
    // REPLY_ASSET_DELTA_24H — emits delta + high + low
} else if (snap.high24h() != null && snap.low24h() != null) {
    // REPLY_ASSET_SPREAD — emits high + low
}
```

The coupling exists only because one bundle key carries three values:
`reply.asset.delta_24h=\ \ 24h:   {0}\n  24h high: {1}\n  24h low:  {2}`.
A snapshot with a 24h delta but no spread satisfies neither branch, so the
delta is dropped without trace. That contradicts the rule design §10.5
states for every other field — "any field absent from the snapshot row is
silently omitted" — which is field-by-field, not in groups.

Reachability is narrow but real:

- `BitfinexSnapshotSource` validates the array shape (`root.size() < 10`
  rejects) and reads indices 5 / 8 / 9 out of one response, so its three
  values arrive together or not at all. It cannot hit this.
- `CoingeckoSnapshotSource` reads each field from an independent JSON path
  through `JsonNumbers.readBigDecimal`, which returns null per field. A
  degraded `market_data` carrying
  `price_change_percentage_24h_in_currency` but not `high_24h` / `low_24h`
  produces exactly this row. `PriceSnapshot`'s own javadoc names the case:
  "degraded responses may drop fields the upstream normally populates".

**Severity is low and this ticket should not oversell it.** Nothing is
corrupted, no wrong number is displayed, and the common path is
unaffected — it is a silent-omission bug on a degraded-input path. It was
found while correcting design §10.5 against the renderer (commit
`40f3b7db`), and is worth closing because that doc now has to describe the
coupling as deliberate behaviour when it is an accident of bundle-key
shape.

## Expected shape

Splitting the delta out needs **no new bundle key**, which matters: a new
key needs a `cs.properties` twin and shifts the D43 bilateral keyset.

- `reply.asset.delta_24h` keeps only its own line (`\ \ 24h:   {0}`),
  dropping args `{1}` and `{2}`.
- The existing `reply.asset.spread` already renders high + low and becomes
  the single spread emitter for both paths.
- `render()` emits the delta when `change24hPct() != null`, and the spread
  when both bounds are non-null, independently.

`cs.properties` carries the same three-value shape
(`reply.asset.delta_24h=\ \ 24h:   {0}\n  24h max: {1}\n  24h min:  {2}`)
and needs the identical split.

Changing `delta_24h`'s interpolation arity may invalidate the M1-658
inbound-reflection baseline — M1-671 hit exactly that when it renamed
interpolation arguments — which is why
`infochat-provider/src/test/resources/inbound-reflection-error-baseline.txt`
is in `files_scope`. Confirm before assuming it must change; if it does
not, drop it from the scope rather than touching it.

## Out of scope

See the YAML `out_of_scope:` list.

## Notes

- Design §10.5 currently documents the coupling as a rendering rule ("the
  24h delta and the spread share a single bundle key, so the delta prints
  only when `high_24h` and `low_24h` are present too — a snapshot carrying
  a 24h delta but no spread renders neither line"). When this ships, that
  sentence must go; `docs/design/10-asset-commands.md` is in `files_scope`
  for that reason and no other.
- **Alternatives considered:** keep the combined key and add a second
  delta-only key for the degraded case. Rejected — it adds a bundle key
  (plus its cs twin) to serve a path the split already covers, and leaves
  two keys able to render the same line.
