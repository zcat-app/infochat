---
id: M1-271
title: "Design/spec reconciliation + comment-truth sweep"
status: done
created: 2026-06-09
last_updated: 2026-06-10
blocked_by: []
files_budget: 22
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any behavior change — every code edit in this ticket is comment/javadoc/string-literal-metadata only (the one exception is the UA version string; see Notes).
  - The AdminBootstrap stale comment — owned by M1-270 alongside its code fix.
  - Implementing the Fetcher output-type discriminator — the spec is amended to the shipped reality instead (report M-D1 verdict).
  - The last-admin trigger SQL — code is the safer side; only the spec formula is amended.
acceptance:
  - "No design file still describes the removed new_price_snapshot channel: docs/design/01-architecture.md (:83,:201,:566), 02-schema.md (:1446,:1481+), 04-security.md (:702), and 10-asset-commands.md (:142-159, incl. the superseded keep-as-seam verdict) are updated to the two-channel reality M1-234 shipped."
  - "docs/spec/architecture.md:159-174 no longer commits to a Fetcher output-type discriminator the SPI explicitly defers; the amendment matches the shipped design (asset fetchers as a separate path)."
  - "The spec's last-admin formula includes the is_banned=FALSE narrowing the trigger implements (code unchanged — spec amended to the safer shipped behavior)."
  - "Design §2.1.8 command catalogue lists DIGEST_ENABLE/DIGEST_DISABLE."
  - "Design 06's adapter capability table is NOT touched here (M1-274 owns it); design 04 §150's explicit-blocklist-entry sentence is NOT touched here (M1-277 owns it)."
  - "The stale code comments/metadata enumerated in the v4 T-DOC sweep are corrected: HostInterfaceSet 'module init' comment; SsrfGuardedHttpClient UA version string; AuditAction '(this ticket)'/'M1-228'/cross-module @link; NotifyOutcome 'once the AdminNotificationDelivery SPI lands'; RssFetcher javadoc's nonexistent fault-tolerance retry claim; ticket-ID comments in LlmRouter/guard/provider; application.properties:107-110 stale security comment; SourceUpsertService stale comment; CollectorSsrfClientProducer scope-comment wording; the inline jspecify version vs the pom's own no-inline-versions comment."
  - "mvn -B clean verify from the repo root exits 0 (comment-only edits compile; the UA string change passes existing tests or their authorized update)."
test_plan:
  modifies:
    - "infochat-ssrf/src/test/** — any test pinning the old UA string infochat/0.0.1-SNAPSHOT; authorized update to the corrected version source"
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
      files: 18
      added: 100
      removed: 127
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-10
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-271: Design/spec reconciliation + comment-truth sweep

## Context

Deep-review v4 verified HIGH **H10**, medium **M-D1**, and the **T-DOC** low
sweep (`deep-code-review/v4/UNIFIED-REPORT.md` §1/§2/§3; sources
`deep-code-review/v4/fable5/01-architecture.md#F3/#F4/#F7`,
`deep-code-review/v4/opus-48/01-architecture.md#F1`, plus the per-item T-DOC
sources listed in the report). M1-234 (done) removed the
`new_price_snapshot` channel from spec and code but four design files still
assert it. Separately, a dozen comments/javadoc/metadata items state things
that are no longer (or never were) true — each individually verified verbatim
by the unified report (two marked spot-trusted: `application.properties`
:107-110 and `SourceUpsertService`; re-verify those two before editing).

## Acceptance

See frontmatter. Every item is a doc-truth alignment; none changes behavior.

## Out-of-scope

See frontmatter. Items owned by sibling v4 tickets (AdminBootstrap comment →
M1-270; capability table → M1-274; explicit blocklist entries → M1-277) are
excluded so no file is contested across two in-flight tickets.

## Notes

- This ticket mixes `.java` comment edits with `docs/design`/`docs/spec`
  edits, so it rides the ticket flow (code+docs = ticket, per the
  commit-prefix rules) even though no behavior changes. If the user prefers,
  the pure-doc legs (H10 design files, spec amendments, design catalogue) can
  be split off and landed directly as `spec:` commits — say so before start
  and shrink this ticket to the code-comment sweep.
- The UA string (`infochat/0.0.1-SNAPSHOT` vs pom `1.0.0-SNAPSHOT`) is the
  one observable-bytes change; the simplest truth-preserving fix is deriving
  it from the build version or updating the literal. If a test pins the old
  string, its update is authorized (test_plan.modifies).
- Doc cross-reference rules apply: no new spec→design references, no
  review-report citations inside spec/design files.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-271-*.md
```
