---
id: M1-303
title: "D43 localization gaps: cluster labels, /help asset lines, source label + shared renderer"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetReplyRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The /lang threading machinery (M1-268 landed it) — these are the call sites it missed.
  - Source post bodies and LLM-authored summary prose — never translated (spec rule); only the LABELS around them.
  - The CI guard against literal-append on reply paths (opus-47 CT1 sketch) — optional follow-up, not this ticket.
acceptance:
  - "U-47 first (the structural half): /retry's appendClusterBlock/joinedTags verbatim copies of /summary's collapse into one shared ClusterBlockRenderer; the spec's byte-identical replay requirement holds (the copy includes the sanitize→translate ordering — preserve it); existing /summary and /retry rendering tests stay green through the extraction."
  - "U-43: the cluster-block labels — 'covered by: ' (SummaryCommandHandler:322), 'score: N source(s)' (:334, binary pluralization), 'summary:', 'classification:', 'tags:' — are bundle-resolved per design 05:418 ('Cluster headers, classification labels in summaries' listed as Translated); pluralization uses {0,choice,…} shapes that support Czech three-form plurals; named tests assert en and cs renderings (cs: 1 zdroj / 2 zdroje / 5 zdrojů)."
  - "U-44: /help's asset lines stop concatenating inline English (' [sub-verb] [--vs <currency>] — ', ' market data' in HelpCommandHandler.appendEnabledAssets ~:213-224) and resolve through the bundle like every other help line; named test in cs."
  - "U-45: AssetReplyRenderer's '  source: ' (:106) — the only non-bundled label in the asset reply — is bundle-resolved; named test."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 413
      removed: 116
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-303: D43 localization gaps: cluster labels, /help asset lines, source label + shared renderer

## Context

Deep-review v5 verified **U-43** (HIGH per opus-47), **U-44** (HIGH per
opus-47), **U-45** (MEDIUM), **U-47** (MEDIUM)
(`deep-code-review/v5/UNIFIED-REPORT.md` §3/§4; sources
`opus-47/07#F1/#F2/#F3` (unique localization cluster), `fable-5/07#F3` +
`gpt-55#M-16` (U-47) — gitignored; all load-bearing facts inlined; literals
re-verified 2026-06-11 at the line numbers in acceptance, post-M1-268).

The report's ticket note says U-43 should be implemented together with
U-47 (shared renderer) so the bundle keys land once — hence one ticket:
extract the renderer first, then localize it in place; /summary and /retry
both inherit.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- The byte-identical-replay property is the trap: /retry replays the
  anchored reply. Extraction must not reorder sanitize→translate or alter
  whitespace — the named tests around replay are the guard.
- Coordination: M1-288 (group /summary), M1-306 (/retry counter), and
  M1-307 (serializeClusterMap escape) also touch these files in different
  regions. Check the worktree landscape at start; land this one before or
  after, not interleaved.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-303-*.md
```
