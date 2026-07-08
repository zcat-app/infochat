---
id: M1-592
title: "Asset reply renderer: split the 24h high/low onto their own lines (mobile wrap) and fix inconsistent Δ% precision"
status: done
created: 2026-07-08
last_updated: 2026-07-08
clarity_check:
  date: 2026-07-08
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Every asset-reply line OTHER than the two 24h lines and the delta
    formatting. The header, price line, 1h delta template text, capture/cache
    line, stale marker, and bare source URL are untouched — this ticket changes
    only (a) the reply.asset.delta_24h / reply.asset.spread templates and (b)
    AssetReplyRenderer.formatDelta.
  - >-
    Resyncing the design §10.5 "Default reply examples" code block (which still
    shows the old single-line `24h: −2.4% (high $43.91 / low $41.07)` form) to
    the new split layout. That is a pure-doc `spec:` edit outside this ticket's
    code+bundle scope; flagging it here so the drift is a known, deliberate
    deferral rather than an oversight.
  - >-
    Changing formatPrice, the vs-currency symbol logic, or the U+2212 minus-sign
    convention. formatDelta keeps emitting the leading `+` / U+2212 minus; only
    the numeric precision (fixed scale) changes.
  - >-
    The verbose form (`/zcash --verbose`) and any other AssetHandler / snapshot
    plumbing. The renderer is the sole surface changed.
  - >-
    Adding a new bundle key. Both affected keys (reply.asset.delta_24h,
    reply.asset.spread) already exist in en.properties + cs.properties; their
    VALUES change, the keyset does not (so D43 bilateral parity is preserved and
    BundleLoaderTest stays green).
acceptance:
  - >-
    The 24h coingecko delta reply no longer packs percent + high + low onto one
    long line. reply.asset.delta_24h renders the 24h Δ% on its own line and the
    24h high and 24h low each on their own subsequent line (embedded `\n` in the
    template value; the renderer already appends each resolved template followed
    by a single `\n`, so the multi-line template needs no renderer change beyond
    keeping the existing append). The existing 2-space layout indent is
    preserved on every line. Concretely, for a coingecko snapshot the rendered
    body contains three distinct lines conveying the 24h Δ%, the 24h high price,
    and the 24h low price — not one combined `(high $X / low $Y)` parenthetical.
  - >-
    The exchange path (reply.asset.spread — no Δ% available) is split the same
    way: the 24h high and 24h low print on separate lines instead of the current
    single `24h: high $X / low $Y` line. Applies to every exchange sub-verb
    (kraken, bitfinex) since they share this one template.
  - >-
    Both templates are changed in en.properties AND cs.properties (D43 twin) with
    identical layout/placeholder structure ({0}/{1}/{2} for delta_24h,
    {0}/{1} for spread), so no MessageFormat argument-index drift and no bundle
    keyset divergence. BundleLoaderTest stays green.
  - >-
    formatDelta emits a FIXED 2-decimal-place percentage (HALF_UP), replacing the
    current `stripTrailingZeros().toPlainString()` which printed raw API
    precision (the live bug: `1h: -0.345%` at 3 dp next to `24h: +6.4774%` at
    4 dp). Concretely: a change value of 6.4774 renders `+6.48%`, and a 3-dp API
    value such as −0.345 renders with EXACTLY two decimal places (not three).
    Sign handling is unchanged: leading `+` for non-negative, U+2212 minus for
    negative.
  - >-
    NAMED TEST: AssetReplyRendererTest gains (a) a coingecko case asserting the
    24h Δ%, 24h high, and 24h low each appear on their own line, and asserting
    formatDelta's fixed 2-dp output (e.g. a snapshot whose change_24h_pct is
    6.4774 renders `+6.48%`, and a change_1h_pct of −0.345 renders with exactly
    two decimals); and (b) the exchange case updated to assert the split
    high/low lines. The pre-existing `contains("high $43.91")` /
    `contains("low $41.07")` style assertions (which the layout change breaks)
    are updated to the new split-line form — an orphan this change creates, not
    scope drift.
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
      — update coingeckoLayout + exchangeAsymmetricFields to the split high/low
      lines, and add fixed-2-dp precision assertions on formatDelta output.
  preserves:
    - all tests currently green on main
    - >-
      BundleLoaderTest (D43 bilateral keyset parity) — the keyset is unchanged;
      only two existing values are edited in both bundles.
    - >-
      the AssetReplyRendererTest cases unrelated to the 24h lines (staleMarker,
      nonStaleDoesNotShowMarker, nonUsdVsCurrenciesRenderIsoCodeSuffix,
      sourceLabelIsResolvedFromBundle, btcQuoteCurrencyOmitsDollarSign) stay
      green — they exercise formatPrice / header / stale paths this ticket does
      not touch.
spec_refs:
  - docs/design/10-asset-commands.md §10.5 Reply layout
  - docs/spec/commands.md §Asset commands
decision_refs:
  - D30
  - D43
reviews:
  - round: 1
    date: 2026-07-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 47
      removed: 22
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-592: split the 24h high/low lines (mobile wrap) and fix inconsistent Δ% precision

## Context

Found 2026-07-08 during live testing (`/zcash`). Two cosmetic-but-real defects
in the shared asset-command reply renderer. Because `AssetReplyRenderer` is the
single renderer behind every asset command, both defects affect `/zcash`,
`/monero`, and every future per-asset command identically.

1. **Mobile wrapping.** The 24h line is the longest in the reply:

   ```
   24h:   +6.4774%  (high $509.05 / low $444.51)
   ```

   On a phone this wraps mid-parenthetical and reads badly. Splitting the high
   and low onto their own lines keeps every line short:

   ```
   24h:   +6.4774%
   24h high: $509.05
   24h low:  $444.51
   ```

   This is a **bundle-only** change to the two templates that carry the
   high/low: `reply.asset.delta_24h` (coingecko path) and `reply.asset.spread`
   (exchange path). The renderer appends each resolved template followed by a
   single `\n`, and a properties value may itself carry an embedded `\n`, so the
   split lives entirely in the template value — no `render()` restructuring. The
   existing 2-space layout indent is kept on each line.

2. **Inconsistent Δ% precision.** The change percentages print at raw API
   precision because `formatDelta` uses
   `pct.stripTrailingZeros().toPlainString()` with no fixed rounding. Live this
   produced `1h: -0.345%` (3 dp) next to `24h: +6.4774%` (4 dp) in the same
   reply. Fixing `formatDelta` to a fixed 2-dp scale (HALF_UP) makes both read
   consistently (`+6.48%`, and the 1h value at two decimals).

## The fix

- **Bundles.** In `en.properties` and `cs.properties` (D43 twin), rewrite the
  values of `reply.asset.delta_24h` and `reply.asset.spread` so the 24h high and
  24h low sit on their own indented lines (embedded `\n`), keeping the same
  `{0}/{1}/{2}` (delta_24h) and `{0}/{1}` (spread) placeholder positions so
  `MessageFormat` argument order in `AssetReplyRenderer.render()` is unchanged.
- **Renderer.** In `AssetReplyRenderer.formatDelta`, replace
  `stripTrailingZeros().toPlainString()` with a fixed 2-dp `setScale(2,
  RoundingMode.HALF_UP)` (adding the `java.math.RoundingMode` import as an
  orphan of this change), preserving the leading `+` / U+2212-minus sign logic.
- **Test.** Update the two `AssetReplyRendererTest` cases whose assertions the
  layout change breaks and add the fixed-precision assertions.

## Out-of-scope

See frontmatter. Notably: no new bundle key (both keys already exist — values
change only), no touch to `formatPrice` / header / capture / source-URL lines,
no verbose-form change, and NO resync of the design §10.5 example code block
(deferred to a separate `spec:` edit so this ticket stays code+bundle only).

## Notes

- **Provenance.** Live-test finding 2026-07-08 (`/zcash` walkthrough). Not a
  red-team finding.
- **Why the renderer barely changes.** `render()` already does
  `sb.append(MessageFormat.format(...)); sb.append('\n');` per template, and a
  `.properties` value parses `\n` into a real newline, so a multi-line template
  needs no renderer edit for the split; only `formatDelta`'s precision changes.
- **D43 parity.** Editing the two values in both `en.properties` and
  `cs.properties` keeps the keyset identical, so `BundleLoaderTest`'s bilateral
  keyset check stays green and no new twin is introduced.
