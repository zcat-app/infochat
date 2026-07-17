---
id: M1-628
title: "Asset commands (/zcash, /monero): inconsistent leading indentation on some reply lines"
status: done
created: 2026-07-15
last_updated: 2026-07-17
blocked_by: []
files_budget: 4
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The price data, sources, caching, or attribution. Purely the reply's line
    formatting.
  - >-
    The reply.asset.stale_marker template's own leading-space handling. It is a
    header-line suffix (appended after the header text), not a standalone reply
    line, so it is outside this ticket's per-line leading-indent scope even
    though it shares the same java.util.Properties leading-space mechanism; do
    not touch it here.
  - >-
    AssetReplyRenderer.java production logic. The Java renderer already owns only
    the source-line indent (its explicit two-space prefix) and needs no change;
    the fix, if any, is bundle-template-VALUES-only (en.properties +
    cs.properties). No new bundle key is added — existing values only gain the
    "\ \ " leading-space escape — so the D43 bilateral keyset is unchanged and
    BundleLoaderTest stays green. (AssetReplyRendererTest IS authorized — see
    test_plan.)
acceptance:
  - >-
    Confirm at the raw-byte level — via a new assertion in AssetReplyRendererTest
    over the String returned by AssetReplyRenderer.render(...), not just CLI
    display — whether the inconsistent 2-space indent is real in the emitted
    string or a client-render artifact. (Root cause to confirm: java.util.
    Properties.load() strips UNescaped leading whitespace from a value, so
    "key=  text" loads flush as "text" while "key=\ \ text" keeps the indent.)
  - >-
    If real, every non-header asset reply line (price, 1h delta, 24h delta,
    spread, capture/as-of) shares the uniform 2-space leading indent that
    docs/design/10-asset-commands.md §10.5 "Reply layout" shows for its worked
    examples — i.e. the fix ADDS the missing indent to the currently flush-left
    lines (price, 1h, capture); it does NOT strip indent from the already-
    indented 24h/spread/source lines, and the header keeps column 0.
    AssetReplyRendererTest asserts the uniform indent at the raw-byte level.
  - >-
    If it is a client-render artifact only (the emitted bytes are already
    uniform), the ticket is closed with that finding recorded in the commit
    message body and the ticket §Notes — no code or test change.
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
      — add a raw-byte assertion that every non-header line of a rendered reply
      begins with the 2-space indent (covering the price, 1h, and capture lines
      that currently render flush-left). Pre-existing .contains(...) substring
      assertions survive the indent fix unchanged — none are rewritten or
      removed; this is a strengthening addition, not a rewrite.
  preserves:
    - all tests currently green on main
    - >-
      BundleLoaderTest (D43 bilateral keyset parity) — only existing bundle
      VALUES gain the "\ \ " leading-space escape; the keyset is unchanged.
    - >-
      the existing AssetReplyRendererTest cases (coingeckoLayout,
      exchangeAsymmetricFields, staleMarker, nonStaleDoesNotShowMarker,
      nonUsdVsCurrenciesRenderIsoCodeSuffix, sourceLabelIsResolvedFromBundle,
      btcQuoteCurrencyOmitsDollarSign) — their substring assertions do not pin
      the absence of leading whitespace on the affected lines, so the fix does
      not break them.
spec_refs:
  - docs/design/10-asset-commands.md §10.5 Reply layout
  - docs/spec/commands.md §Asset commands
decision_refs:
  - D30
  - D43
clarity_check:
  date: 2026-07-17
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 49
      removed: 16
---

Found in the 2026-07-14/15 isolated live test (and previously noted 2026-07-08,
memory live-test-findings): /zcash and /monero replies show a 2-space leading indent on
some lines (24h, high/low, source) but not others ($price, 1h, as of). Suspected a
possible CLI-display artifact — verify raw bytes first, per acceptance.

## Notes

Raw-byte root cause (confirmed during ticket refinement; the implementation
formalizes it as an AssetReplyRendererTest assertion): the inconsistency IS real
in the emitted string, not a client-render artifact. The lines that render
flush-left use UNescaped leading spaces in the bundle values —
`reply.asset.price_line=  {0}`, `reply.asset.delta_1h=  1h: ...`,
`reply.asset.capture_line=  as of ...` — which `java.util.Properties.load()`
strips. The lines that render indented either use the escaped form
(`reply.asset.delta_24h=\ \ 24h: ...`, `reply.asset.spread=\ \ ...`) or get their
indent added in Java (the source line's explicit two-space prefix). So the fix
direction is: give the three flush keys the same `\ \ ` leading escape in BOTH
en.properties and cs.properties (D43 parity). The Java renderer needs no change.

- Relevant design note: `docs/design/10-asset-commands.md` §10.5 "Reply layout"
  — its worked examples indent every non-header line uniformly by 2 spaces.
- Adjacent prior ticket: M1-592 (same renderer + bundles; split the 24h high/low
  onto their own lines) edited these same two `.properties` files values-only —
  the model this ticket follows.
