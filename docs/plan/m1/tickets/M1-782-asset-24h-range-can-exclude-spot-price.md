---
id: M1-782
title: "Asset 24h range can exclude the current price"
status: pending
created: 2026-08-06
last_updated: 2026-08-07
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    THE COLLECTOR AND `price_snapshot`. The stored rows faithfully
    record what the upstream API returned and must keep doing so —
    rewriting captured data would destroy the audit trail and break
    D39's per-source attribution. This is a RENDER-side fix only.
  - >-
    DROPPING OR SWITCHING THE COINGECKO SOURCE. The sub-verb allowlist
    is operator-configured (D39) and coingecko is the default for both
    assets.
  - >-
    M1-678's delta/spread independence rules and the line ordering it
    fixed (price, then `24h high:`, then `24h low:`). Those stay.
  - >-
    Any bundle file, if the fix needs no new wording. Prefer a
    numeric-only fix so this ticket does not extend the bundle
    serialization chain.
acceptance:
  - >-
    THIS IS NOT A PARSE BUG — THE TICKET MUST NOT "FIX" THE PARSER.
    Verified: coingecko's `low_24h` refreshes more slowly than its spot
    price, so the spot can drift below a stale low. Across stored
    snapshots kraken is 0/1,579 inconsistent (it derives high/low from
    real OHLC) while coingecko zcash is 88/1,574 (5.6 %) and coingecko
    monero shows 73/1,577 on the high side. We render upstream
    faithfully; upstream is internally lagged.
  - >-
    A rendered 24h range always contains the rendered price. The
    recommended fix is to widen the displayed bounds to include the
    spot — if the price has just dropped below a stale low, the true
    rolling-24h low IS the current price, so this is more accurate, not
    less.
  - >-
    Pinned by a test at each boundary: a snapshot with
    `low_24h > price` renders a low equal to the price, and a snapshot
    with `high_24h < price` renders a high equal to the price.
  - >-
    A consistent snapshot renders byte-identically to today. Pinned by a
    test so the common case is provably untouched.
  - >-
    The data-source name and bare source URL still appear on every
    reply (D39 attribution is mandatory per reply).
  - "mvn -B -pl infochat-provider -am verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetReplyRendererTest.java
  preserves:
    - M1-678's delta-independent-of-spread rendering and line order
    - M1-592 / M1-628 asset formatting and indentation
    - D39 source attribution on every reply
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Asset commands
decision_refs:
  - D39
reviews: []
overrides: []
---

## Why

The bot prints a set of numbers that cannot all be true at once. A reader who
notices stops trusting the figures.

Found during the v1.1.0 live test (`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md` §3.3).

## Observed

```
Zcash (coingecko)
  $503.92
  1h:    −0.80%
  24h:   −2.58%
  24h max: $524.75
  24h min: $505.89
```

The 24h low sits \$1.97 ABOVE the current price. Snapshot history shows
`low_24h` frozen at 505.89 across many captures while `price` drifts down
through it.

## Expected

```
Zcash (coingecko)
  $503.92
  1h:    −0.80%
  24h:   −2.58%
  24h max: $524.75
  24h min: $503.92
```

## Relationship to M1-781

`blocked_by: []` is a soft serialization to keep the bundle-touching chain
in one line. If the chosen fix provably needs no bundle edit, this ticket is
independent and can be lifted out of the chain at start.
