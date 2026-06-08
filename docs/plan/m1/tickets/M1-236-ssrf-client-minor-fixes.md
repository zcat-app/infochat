---
id: M1-236
title: "infochat-ssrf: read-buffer churn, wss default port, dead null-check"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 3
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/EffectivePortWssTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The blocklist coverage, DNS-rebind pinning, redirect re-validation, scheme/userinfo gating, and embedded-IPv4 decoding — all reviewed as sound; do NOT touch the validation logic.
  - The latest-wins per-host pin TOCTOU posture — documented and sound; unchanged.
  - The watchdog semantics (per-read budget, total body-read deadline, cancel-on-timeout) — unchanged; only the read buffer size / thread-per-read shape may change.
  - The timeout/cap value-checks in the constructor (connectTimeout/requestTimeout/readTimeout/bodyReadDeadline/bodyCap/redirectCap) — these validate config-boundary VALUES and stay.
acceptance:
  - "S-F1: the body reader no longer spins a fresh virtual thread + FutureTask per 8 KiB read on the hot fetch path — either the read buffer is enlarged (e.g. 64 KiB) to cut per-read thread/allocation churn ~8x, or reads are supervised from one reused thread per call; the watchdog semantics (min(readTimeout, remaining) per read, total deadline) are unchanged."
  - "S-F2: effectivePort returns 443 for wss as well as https (switch expression over the lowercased scheme), so a future WS origin/credential-scrub reuse cannot misjudge wss://h/ vs wss://h:443/ as cross-origin; behavior on the live http/https paths is unchanged."
  - "S-F3: the redundant `if (blocklist == null) throw` guard is removed (blocklist is a non-@Nullable parameter; the null-marked package contract / NullAway is the enforcement per engineering-rules §7/§7a)."
  - "Existing SsrfGuardedHttpClient tests (including the SSRF negative controls and the body-cap/watchdog tests) stay green; a test exercises the wss effectivePort mapping."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/EffectivePortWssTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D48
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-236: infochat-ssrf — read-buffer churn, wss default port, dead null-check

## Context

Three low-severity findings in `SsrfGuardedHttpClient.java`, grouped
because they touch one file and none changes the security validation:

- `deep-code-review/v2.5/opus-48/03-module-infochat-ssrf.md#F1` (PERFORMANCE):
  `readBounded` creates a fresh virtual thread + `FutureTask` per 8 KiB
  read (~1280 per full-cap body) on the Collector's hot fetch path.
- `#F2` (DRIFT): `effectivePort` returns 443 only for `https`, so `wss`
  falls to 80 — latent today (`get()` rejects `wss`) but a credential-leak
  shape if `effectivePort`/`isCrossOrigin` are ever reused on a WS path in
  this WebSocket-aware class.
- `#F3` (DRIFT): `if (blocklist == null) throw` is a defensive null-check
  on a non-`@Nullable` parameter, redundant under the null-marked package
  contract (§7/§7a).

## Acceptance

See frontmatter. In prose: cut the per-read thread/allocation churn without
changing watchdog semantics; make `effectivePort` map `wss`→443 via a
switch expression; delete the redundant `blocklist` null-check; keep all
SSRF negative-control and watchdog tests green; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The validation logic, the pin TOCTOU posture, and the
config-boundary value-checks are untouched. This is hygiene on one file.

## Notes

- Recommended forms for each fix are in the source findings. S-F1's
  simplest path is a 64 KiB buffer (8x fewer reads, same semantics); the
  reused-thread alternative is heavier for marginal extra gain.
- S-F3: the timeout/cap checks beside the removed guard validate config
  VALUES (zero/negative/non-positive) and legitimately stay.
