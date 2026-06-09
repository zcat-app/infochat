---
id: M1-280
title: "Provider mediums: Gate 4, usage replies, price label, bucket"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 18
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The group-SPI feature surface itself (SignalGroupHandler, membership events) — only Gate 4's truth source changes.
  - Language threading of the new usage bundle key (M1-268 owns /lang threading; add the key in both en+cs bundles here, resolve per current convention).
  - Asset price data sources and snapshot semantics — only the reply label changes.
  - Rate-cap policy values — only the constructor shape changes.
acceptance:
  - "Gate 4 is no longer vacuous: isGroupSpiWired derives from the adapter's real group-SPI wiring (the group SPI shipped long ago — SignalGroupHandler, membership events) or an honest config key, and the hidden infochat.adapters.<name>.test-group-spi-wired property is removed; a named test exercises the gate through the real mechanism. If investigation concludes the gate is dead scaffolding instead, delete it with its test property and document why — one of the two, pinned by test."
  - "The eight handlers fable5-07#F6 enumerates reply to missing required arguments with a usage/missing-argument bundle message instead of a semantically wrong error (e.g. /ban with no args no longer returns ERROR_ADMIN_ONLY to an admin); a parameterized or per-handler named test covers each."
  - "AssetReplyRenderer labels non-USD vs-currencies correctly: formatPrice no longer hardcodes '$' as the default; a named test asserts eur and czk render with the right symbol/code."
  - "RateCapBucket's five telescoping constructors are replaced by a settings record; all construction sites (including tests) updated."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-280: Provider mediums: Gate 4, usage replies, price label, bucket

## Context

Deep-review v4 verified mediums **M-P2**, **M-P5**, **M-P7**, **M-P12** —
the provider mediums the report's §5 ticket-cut table left unassigned
(`deep-code-review/v4/UNIFIED-REPORT.md` §2; sources
`deep-code-review/v4/fable5/01-architecture.md#F2`,
`deep-code-review/v4/fable5/07-module-infochat-provider.md#F6/#F7`,
`deep-code-review/v4/deepseek/report.md` #F3):

- **M-P2:** `AdapterRegistry.GROUP_SPI_WIRED = false` constant with a "T2-F
  flips this when the group SPI lands" comment — the group SPI landed long
  ago. The gate never enforces in production; only a hidden test property
  exercises it.
- **M-P5:** missing-argument replies are semantically wrong across 8
  handlers; spot-verified `BanCommandHandler:196-202` returns
  `ERROR_ADMIN_ONLY` (with an apologetic comment) to an admin who typed
  `/ban` with no args.
- **M-P7:** `AssetReplyRenderer.formatPrice` `default -> "$" + …` labels
  every non-BTC vs-currency as dollars.
- **M-P12:** `RateCapBucket` has 5 telescoping constructors; the report
  endorses a settings record and warns about the test fan-out.

## Acceptance

See frontmatter. Gate 4's acceptance carries an investigate-then-pick fork
(wire-for-real vs delete-dead-scaffolding) because the gate's original
purpose — refusing group traffic on an adapter without group support —
may now be vacuously true for every shipping adapter; the diff must pick one
and pin it.

## Out-of-scope

See frontmatter — particularly the M1-268 boundary: this ticket adds the
usage bundle keys, M1-268 makes all keys language-aware.

## Notes

- For M-P5, enumerate the 8 handlers from the fable5 provider report's #F6
  (full path cited in §Context) at start and verify each before editing
  (only BanCommandHandler was spot-verified).
- For M-P7, the vs-currency set is small and closed in v1; an ISO-code
  suffix ("123.45 CZK") is the simplest correct form when no symbol mapping
  exists — plain-text formatting rules apply.
- RateCapBucket: per the recorded call-site rule, grep construction sites
  including test doubles before finalizing; the budget (18) carries headroom
  for that fan-out.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-280-*.md
```
