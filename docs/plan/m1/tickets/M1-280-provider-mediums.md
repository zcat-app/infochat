---
id: M1-280
title: "Provider mediums: Gate 4, usage replies, price label, bucket"
status: done
created: 2026-06-09
last_updated: 2026-06-10
blocked_by: []
files_budget: 20
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/StartupGatesTest.java
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
  - "Gate 4 is no longer vacuous: isGroupSpiWired derives from the adapter's real group-SPI wiring (the group SPI shipped long ago — SignalGroupHandler, membership events) or an honest config key, and the hidden infochat.adapters.<name>.test-group-spi-wired property is removed; a named test exercises the gate through the real mechanism. If investigation concludes the gate is dead scaffolding instead, delete it with its test property and document why — one of the two, pinned by test. The existing StartupGatesTest.gate4RejectsMentionByIdFalseWithGroupSpiWired test exercises the removed property and is rewritten (real mechanism) or deleted (gate removed) to match the fork taken."
  - "Each of the eight handlers replies to missing required arguments with a usage/missing-argument bundle message instead of the semantically wrong error it returns today; a parameterized or per-handler named test covers each. The eight (wrong reply today): BanCommandHandler (ERROR_ADMIN_ONLY), GrantAdminCommandHandler (ERROR_ADMIN_ONLY), RevokeAdminCommandHandler (ERROR_ADMIN_ONLY), UnbanCommandHandler (ERROR_ADMIN_ONLY), VouchCommandHandler (ERROR_ADMIN_ONLY), PromoteCommandHandler (ERROR_ADMIN_ONLY), DemoteCommandHandler (ERROR_ADMIN_ONLY), UnfollowTagCommandHandler (ERROR_INTERNAL) — e.g. /ban with no args no longer tells an admin they lack admin rights."
  - "AssetReplyRenderer labels non-USD vs-currencies correctly: formatPrice no longer hardcodes '$' as the default; a named test asserts eur and czk render with the right symbol/code."
  - "RateCapBucket's five telescoping constructors are replaced by a settings record; all construction sites (including tests) updated."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/StartupGatesTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 20
      added: 347
      removed: 155
revisions:
  - date: 2026-06-10
    reason: clarity-fail refine — acceptance item 2 and §Notes delegated the 8-handler list to a gitignored deep-review report unreadable from worktrees; premise verification also found BundleKeys.java (required for the new bundle key) missing from files_scope with zero budget headroom
    prior_values: |
      files_budget: 18
      files_scope: no bundle/BundleKeys.java entry
      acceptance item 2: "The eight handlers fable5-07#F6 enumerates reply to
        missing required arguments with a usage/missing-argument bundle message
        instead of a semantically wrong error (e.g. /ban with no args no longer
        returns ERROR_ADMIN_ONLY to an admin); a parameterized or per-handler
        named test covers each."
      acceptance item 1: ended at "— one of the two, pinned by test." (no
        StartupGatesTest.gate4RejectsMentionByIdFalseWithGroupSpiWired clause)
      test_plan.modifies: directory entries only (no explicit StartupGatesTest.java)
      Notes M-P5: "enumerate the 8 handlers from the fable5 provider report's
        #F6 (full path cited in §Context) at start and verify each before
        editing (only BanCommandHandler was spot-verified)."
      Notes M-P12: "per the recorded call-site rule, grep construction sites
        including test doubles before finalizing; the budget (18) carries
        headroom for that fan-out."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations:
  - date: 2026-06-10
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SELF-CONTAINED-CHECK: FAIL — Acceptance item 2 and §Notes delegate the
      list of 8 handlers to deep-code-review/v4/fable5/07-module-infochat-provider.md#F6,
      which does not exist on disk in the worktree (deep-code-review/v4/ is
      gitignored; present only in the primary checkout). The implementer has
      no way to enumerate the 8 handlers from this ticket alone. Fix: inline
      the complete list of 8 handler class names (and the wrong error each
      currently returns for no-args) directly in the ticket.
clarity_check:
  date: 2026-06-10
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: The diff will touch 7 admin/ban command handlers. The change is to usage-reply paths only (no-args path), not authorization gates, so security_relevant: false is acceptable. Reviewer should confirm authorization checks in those handlers are unchanged."
  blockers: []
---

# M1-280: Provider mediums: Gate 4, usage replies, price label, bucket

## Context

Deep-review v4 verified mediums **M-P2**, **M-P5**, **M-P7**, **M-P12** —
the provider mediums the report's §5 ticket-cut table left unassigned
(`deep-code-review/v4/UNIFIED-REPORT.md` §2; sources
`deep-code-review/v4/fable5/01-architecture.md#F2`,
`deep-code-review/v4/fable5/07-module-infochat-provider.md#F6/#F7`,
`deep-code-review/v4/deepseek/report.md` #F3 — gitignored, primary
checkout only; provenance, not needed for implementation: everything
load-bearing is inlined in this ticket):

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

- For M-P5, the 8 handlers are inlined in acceptance item 2, verified
  against the code 2026-06-10 (branch points: BanCommandHandler:196,
  GrantAdminCommandHandler:206, RevokeAdminCommandHandler:205,
  UnbanCommandHandler:166, VouchCommandHandler:161,
  PromoteCommandHandler:93, DemoteCommandHandler:85,
  UnfollowTagCommandHandler:214). Non-binding fix shape (transplanted
  from the report, which worktrees cannot read): one shared bundle key
  with a {0} usage-string slot, added to both en.properties and
  cs.properties; exact key name and wording are design-tier.
- For M-P7, the vs-currency set is small and closed in v1; an ISO-code
  suffix ("123.45 CZK") is the simplest correct form when no symbol mapping
  exists — plain-text formatting rules apply.
- RateCapBucket: construction-site sweep done 2026-06-10 — all
  `new RateCapBucket(` sites live in RateCapBucketTest.java (14
  occurrences, one file); constructors at RateCapBucket.java:146
  (public no-arg), 158/169/187/206 (package-private telescoping).
  files_budget (20) carries headroom for a standalone settings-record
  file and an AdapterRegistryTest touch if needed.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-280-*.md
```
