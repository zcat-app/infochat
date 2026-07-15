---
id: M1-628
title: "Asset commands (/zcash, /monero): inconsistent leading indentation on some reply lines"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
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
acceptance:
  - >-
    First confirm at the raw-byte level (not just CLI display) whether the
    inconsistent 2-space indent is real in the emitted string or a client-render
    artifact. If real, the asset reply lines share consistent leading whitespace.
  - >-
    If it is a client-render artifact only, the ticket is closed with that finding
    recorded (no code change).
---

Found in the 2026-07-14/15 isolated live test (and previously noted 2026-07-08,
memory live-test-findings): /zcash and /monero replies show a 2-space leading indent on
some lines (24h, high/low, source) but not others ($price, 1h, as of). Suspected a
possible CLI-display artifact — verify raw bytes first, per acceptance.
