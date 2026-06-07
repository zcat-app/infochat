---
id: M1-216
title: "Collector lows: TTL-job partition independence, saturation counter, sha256 dedup, zero-width escapes"
status: done
created: 2026-06-07
last_updated: 2026-06-08
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: AdminReviewTtlJob's TTL auto-reject is part of the quarantine enforcement path (Invariant 6). The K10 fix closes a gap where partition-dropped posts silently exempted their PENDING quarantine rows from TTL rejection. Consider security_relevant: true. Not a blocker; the existing false setting is defensible since the security policy itself is unchanged."
  blockers: []
blocked_by: []
files_budget: 12
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/AdminReviewTtlJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoader.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - docs/design/01-architecture.md
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the ?::INTERVAL string-param nit (audit K14) — owned by M1-202: its mandatory fetched_at-predicate acceptance leg rewrites the PerSourceUnknownTracker statement, and its out_of_scope says K14 rides along exactly then
  - ReEvaluationJob and the re-eval verdict flow — M1-182's (in-flight)
  - PerSourceUnknownTracker, Bluesky encoding/parse, Registrar CDI, poller overlap — M1-202's
  - the Bluesky identifier-semantics question — M1-220's investigation
  - cross-tick UID dedup — M1-179's
  - Stage1Pipeline's sanitizer semantics — only the literal zero-width characters become visible escapes; behavior identical (Tier-A: mimo's CRITICAL rating on this was adjudicated inflated, severity LOW is binding)
acceptance:
  - "AdminReviewTtlJob does not depend on a live post row to find expired reviews: a PENDING quarantine row aged past the TTL whose post partition no longer exists is still enumerated and transitions to REJECTED — named test (today enumerateExpired inner-joins post to read p.fetched_at, which by the join condition equals the denormalized q.post_fetched_at carried for exactly this partition-drop survival; the post-side UPDATE may legitimately no-op when the post is gone, argued in the commit message)"
  - "Per docs/spec/architecture.md §Ingest SPIs — \"Fetchers expose a per-tick 'pagination cap hit per source' counter. When a single source consistently saturates the cap across multiple ticks (operators choose the threshold; design notes), a throttled admin notification fires once per saturation transition\" — the counter exists, the threshold is operator-configurable with its value documented in design notes (design 01-architecture already reserves the slot at :591), and named tests pin: cap-hit increments the counter; crossing the consecutive-tick threshold fires exactly one throttled notification; the notification names the source (today grep finds no saturation counter anywhere in the collector)"
  - "Exactly one SHA-256-hex helper exists across main sources: BootstrapAssetsLoader's private sha256Hex duplicate is gone and its checksum behavior is unchanged (existing bootstrap-assets tests stay green; the core helper is already used by PostPersister and BootstrapLoader)"
  - "Stage1Pipeline's zero-width-character comparisons are written as visible unicode escapes instead of raw invisible literals, with behavior identical: the existing sanitizer tests stay green and a named test pins ZWSP/ZWNJ/ZWJ/BOM handling if none already does (today :302-304 carry raw U+200B/U+200C/U+200D/U+FEFF literals in char comparisons — confirmed by byte-level inspection — reviewable only with a hex dump)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D42
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 480
      removed: 32
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: cd72ce9^ (ea400e1)
    head: cd72ce9
    verdict_file: docs/plan/m1/redteam/M1-216-2026-06-08.md
    out_of_model_count: 1
    note: |
      CLEAN. K10 TTL-job change strengthens Invariant-6 enforcement
      (partition-dropped PENDING quarantine rows now rejected, not
      exempted); saturation counter leaks no identifier URL and is
      throttle-bounded; sha256/zero-width changes are behavior-
      identical. One advisory OUT-OF-MODEL note (latent ThreadLocal
      cap-hit coupling, future-fetcher only) — no action this ticket.
---

# M1-216: Collector lows

## Context

Four collector members of the audit's misc-lows bucket (unified K10,
K13, K12, K15 — `deep-code-review/v2/UNIFIED.md` §2), re-grounded
2026-06-07 (K12/K13 were ACCEPTED-tier in the audit; this draft is
their first independent verification — both held):

1. **K10 (low).** enumerateExpired joins post on
   `p.fetched_at = q.post_fetched_at` — the denormalized column exists
   so quarantine TTL processing survives partition drops, and the
   inner join defeats it: a dropped post partition silently exempts
   its PENDING reviews from Invariant 6's TTL auto-reject.
2. **K13 (low).** The spec's per-tick pagination-cap saturation
   counter is unimplemented (grep "saturat" → nothing in collector
   code). Re-anchored at draft time: the commitment is **spec-tier**
   (architecture.md §Ingest SPIs), with only the threshold value
   delegated to design notes. Three fetchers paginate within a tick
   today (Bluesky, Reddit, Nitter).
3. **K12 (low).** BootstrapAssetsLoader carries a private sha256Hex
   duplicating the core helper used by PostPersister and
   BootstrapLoader.
4. **K15 (low — Tier-A binding).** Raw zero-width literals in
   Stage1Pipeline's char comparisons; the adjacent comment names them,
   but the code is unreviewable without `cat -A`.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T33 under `deep-code-review/v2/` (collector
  members; kimi-folder coll F8/F10/F12, mimo coll F1).
- **Serialization:** AdminReviewTtlJob and the fetcher package overlap
  M1-202's files_scope (its poller-overlap and Bluesky legs) —
  serialize against M1-202. Stage1Pipeline is free (checked against
  M1-182's files_scope at draft time).
- The saturation-counter leg is the only feature-shaped leg; its seam
  (per-fetcher counter vs FetchScheduler-side counting) is the
  implementer's call — the spec sentence is the contract, and
  FetchScheduler is where per-source dispatch already lives.

## Suggested direction (unverified hypothesis)

The audit (kimi-folder coll F8) suggested `Sha256.hex` from core as
the replacement for the private sha256Hex duplicate.

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
