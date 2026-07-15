---
id: M1-626
title: "/invite create --open: default to the sole enabled adapter; replace confusing empty-adapter error"
status: done
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 6
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
clarity_check:
  date: 2026-07-15
  verdict: WARN
  warnings:
    - >-
      COMPLEXITY-RISK-CALIBRATED: risk:low is defensible given the narrow
      scope, but the touched surface (bot-admin invite-issuance) would also
      support risk:medium.
    - >-
      SECURITY-FLAG-CONSISTENT: security_relevant:false while modifying
      /invite create adapter resolution — narrowed scope (no auth/cap/audit
      change) is the stated justification for keeping it false.
  blockers: []
reviews:
  - round: 1
    date: 2026-07-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 123
      removed: 7
out_of_scope:
  - >-
    The per-adapter invite semantics (D44) and the confirm-gate. Only the adapter-
    resolution default and the error message when it can't be resolved change.
  - >-
    The --contact path (which is already adapter-scoped by the target contact).
acceptance:
  - >-
    In a single-adapter deployment, /invite create --open resolves to that sole
    enabled adapter without requiring --adapter (mirrors how other adapter-scoped
    commands treat a single enabled adapter).
  - >-
    When the adapter genuinely cannot be resolved (multi-adapter, none given), the
    error names the requirement — "specify --adapter <name> (one of: …)" — not the
    current confusing "Unknown adapter ``" (empty backticks).
  - >-
    A test pins both: single-adapter default resolves; multi-adapter with no
    --adapter yields the actionable message.
---

Found in the 2026-07-14/15 isolated live test (SimpleX-only deployment): `/invite create
--open` failed with **"Unknown adapter ``. Use one of the currently-enabled adapters."** —
the empty backticks are confusing, and a single-adapter deployment should not need
--adapter at all. `/invite create --open --adapter simplex` worked (then confirm-gated).
