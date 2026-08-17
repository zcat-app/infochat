---
id: M1-878
title: "Coordinate the fallback seed with M1-866 and M1-869"
status: done
created: 2026-08-17
last_updated: 2026-08-17
flow: tick
reproduction: >-
  Probe (verified 2026-08-17): grep -c 'fallback'
  docs/plan/m1/tick-tickets/M1-866-tag-tree-migration-seed.md returns 0 and
  grep -c 'TagVocabulary' over the same file's files_scope returns 0 —
  observed wrong output: the pending M1-866 text plans the V84 seed with NO
  fallback column, NO world marking, and NO TagVocabulary SELECT edit, so
  when M1-866 lands, world seeds unmarked, the M1-876 resolver tiebreak
  finds no fallback designation, and the world co-tag inflation the M1-864
  record demanded be fixed (record:355-359) ships WITH the v2 seed —
  the data half of the fix falls into the same M1-865/M1-866 gap the
  analysis's Root cause documents. The same grep over M1-869's ticket shows
  its Tagger-SPI amendment wording (acceptance 2) carries no fallback
  sub-order.
analysis_ref: docs/plan/m1/tick-analysis/news-world-below-regions.md
blocked_by: []
files_scope:
  - docs/plan/m1/tick-tickets/M1-866-tag-tree-migration-seed.md
  - docs/plan/m1/tick-tickets/M1-869-tag-tree-spec-amendments.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    IMPLEMENTING the column, the seed value, or the SELECT — that is
    M1-866's migration work; this ticket amends M1-866's pending TEXT so the
    work is in its scope before it starts.
  - >-
    THE RESOLVER CODE — M1-876 owns the mechanism; this ticket only
    pre-authorizes the shared `fallback` name so the two halves cannot
    drift.
  - >-
    THE MEASUREMENT — M1-877 owns the re-run and its record section; this
    ticket edits no record file.
  - >-
    ANY DONE TICKET — M1-865 stays as merged (workflow.md:291, never amend);
    this ticket edits PENDING ticket files only.
  - >-
    ANY docs/spec/** edit here — spec prose is M1-869's charter (single
    user-approvable diff, engineering-rules §12); this ticket only adds the
    sub-order flag to M1-869's own acceptance wording so the amendment
    drafted at implementation time covers it.
acceptance:
  - "M1-866's text gains the data half: files_scope adds infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagVocabulary.java; its V84 acceptance gains ALTER TABLE tag ADD COLUMN fallback BOOLEAN NOT NULL DEFAULT false (no GRANT change — the column rides the tag table's existing per-role grants); the seed marks world fallback = true and NO other leaf; the TagVocabulary SELECT reads the column — probe: grep -n 'fallback' docs/plan/m1/tick-tickets/M1-866-tag-tree-migration-seed.md shows the column, the world marking (the only fallback-marked leaf), and the SELECT sentence; grep -n 'TagVocabulary' over the file shows the files_scope entry (spec: docs/spec/schema.md §Sources and tags; analysis P2, P4, P11)."
  - "M1-866's acceptance 8 probe is scoped so the tagger MECHANISM's tree-aware constants don't trip it: the name-agnostic contract binds the consumer surfaces (digest/search/sweep), while the mechanism surface (TagTreeResolver/TagVocabulary/MiscShareMonitor — TOP_PRIORITY, MISC_LEAF, the fallback component) is tree-aware by design; the amendment text states this scoping explicitly because the probe as worded ALREADY matches M1-865's shipped MiscShareMonitor.MISC_LEAF ('misc' is a seeded leaf) — a family inconsistency this amendment resolves, named, not silently widened (analysis P2; the Ground-truth discrepancy)."
  - "M1-869's acceptance 2 (the Tagger-SPI amendment) gains the sub-order wording flag: the resolution contract records that within the News top, non-fallback leaves outrank the fallback leaf — the fallback leaf stores only when it is the only proposed News leaf — probe: grep -n 'fallback' docs/plan/m1/tick-tickets/M1-869-tag-tree-spec-amendments.md shows the flagged wording; the exact spec prose still goes to the user at implementation time (engineering-rules §12) (spec: docs/spec/llm.md §SPI shape; analysis P3, P5)."
  - "The shared name is pre-authorized: both amended tickets and M1-876's acceptance 1 name the same component/column ('fallback', boolean) so the TagNode component M1-876 ships and the V84 column M1-866 writes cannot drift apart — probe: grep -n 'fallback' over M1-876, M1-866, M1-869 tickets shows the same boolean 'fallback' designation in all three (analysis P8 — the fixture-calibration discipline applied across the family)."
  - "No production code, no migration, no spec file, no record file is touched by this ticket's own diff (it amends two pending ticket files only) — probe: the diff's file list; mvn verify from repo root stays green (doc-only)."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Doc-only ticket: the diff is two pending ticket files. The probes in
      the acceptance items are the mechanical check; mvn verify covers the
      no-regression leg (nothing compiled changes).
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/schema.md §Sources and tags
decision_refs:
  - D5
  - D19
  - D22
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-17
    verdict: APPROVE
    checks: {SPEC-TRUTHNESS: PASS, SECURITY: PASS, TEST-ADEQUACY: NOT-APPLICABLE, MAINTAINABILITY: PASS, SCOPE: PASS}
    diff_stats: "4 files, +11/-10 (M1-866 +5/-2 fallback column + world marking + SELECT + probe scoping, M1-869 +1/-1 sub-order wording flag, STATUS-TICK regen, M1-878 status flip)"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-878-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-878: coordinate the fallback seed with M1-866 and M1-869

## Context

M1-876 ships the resolver mechanism that reads a `fallback` designation from
the tree; the designation itself is DATA that only the M1-866 seed can
write. M1-866 is PENDING (blocked_by M1-864/M1-865/M1-868) and its text
carries nothing fallback-shaped (verified: zero grep hits; its files_scope
lacks TagVocabulary.java). If it starts as drafted, world seeds unmarked and
the fix's data half never lands — the same mis-track that lost the demand
between M1-864 and M1-865 (analysis Root cause). This ticket closes that gap
by amending M1-866's pending text (column + world marking + SELECT + probe
scoping) and flagging M1-869's amendment wording, BEFORE either starts.
Shared context: `analysis_ref:` (analysis doc, Pitfalls P2, P3, P8, P11).

## Root cause

Verified: the demand (record:355-359) lives in neither pending ticket's
text — M1-866's files_scope/acceptance carry no fallback column, world
marking, or TagVocabulary edit (read in full), and M1-869's acceptance 2
wording mentions no within-News sub-order (read in full). The data half
therefore has no owner; this ticket is that ownership, in the cheapest
possible form: a ticket-text amendment that lands the column inside the
migration that seeds its only user (V84), with zero migration renumbering
(V83=M1-868, V84=M1-866 stay pinned).

## Pitfalls

- P2: name-agnostic discipline — M1-866's acceptance-8 probe is scoped to
  consumer surfaces in the same amendment, reconciling the pre-existing
  MISC_LEAF match; no leaf name enters main code from this family.
- P3: silent-rot asymmetry — the designation is DATA (a boolean column +
  seed value), so its behavior travels with the row that declares it.
- P8: name pre-authorization — the `fallback` component/column name is
  fixed here so M1-876's TagNode and V84 cannot drift.
- P11: ordering — the amendment must be in M1-866's text before M1-866
  starts; otherwise the data half becomes a retrofit.

## Approach

- **Files to touch:** the two pending ticket files only
  (M1-866-tag-tree-migration-seed.md, M1-869-tag-tree-spec-amendments.md).
- **Steps, in order:**
  1. Amend M1-866: files_scope + acceptance (V84 column, world marking,
     SELECT) — acceptance 1; scope acceptance 8's probe — acceptance 2.
  2. Flag M1-869's acceptance 2 wording — acceptance 3.
  3. Confirm the shared `fallback` name across M1-876/M1-866/M1-869 —
     acceptance 4; run the greps.
- **Controls to preserve (engineering-rules §10):** the amended text must
  not weaken any M1-866/M1-869 acceptance — the column rides existing
  grants (no GRANT change), the seed's ON CONFLICT/collision discipline is
  untouched, and M1-869's §12 approval shape is unchanged (the flag names
  the wording to add; the prose itself is drafted and approved at
  implementation time).
- **Pitfall→mitigation:** P2→acceptance 2; P3→acceptance 1's data shape;
  P8→acceptance 4; P11→this ticket's `blocked_by: []` + the analysis's
  ordering note (land before M1-866 starts; parallel-safe with M1-876).

## Definition of done

Every acceptance item holds: M1-866's text carries the column + world
marking + SELECT + TagVocabulary files_scope entry; acceptance 8 is scoped
with the MISC_LEAF reconciliation named; M1-869's wording flag exists; the
`fallback` name matches across all three tickets; the diff touches no
production code/spec/record file; `mvn verify` green.

## Verification

- P2 → acceptance 2 (the scoped probe text, with the MISC_LEAF
  reconciliation explicitly stated).
- P3 → acceptance 1 (the designation is a column + seed value, never a
  name in code).
- P8 → acceptance 4 (grep 'fallback' across the three tickets shows one
  boolean designation).
- P11 → acceptance 1's probes + the ordering note in the analysis's
  Decomposition (M1-878 lands before M1-866 starts; M1-876 runs in
  parallel, disjoint files).
- Failure mode → the reproduction probe re-run inverted: grep -c 'fallback'
  M1-866's ticket now returns the column + marking + SELECT hits — an
  amended M1-866 that lost the world marking would leave grep showing the
  column without the world sentence, which acceptance 1's probe catches by
  requiring all three.
- acceptance 5 → the diff file list + `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — no implementation (M1-866's), no resolver code
(M1-876's), no measurement (M1-877's), no done-ticket edits (M1-865 stays
merged), no docs/spec/** edit (M1-869's charter; this ticket only flags its
wording). If M1-866 has already started when this ticket runs, escalate —
the amendment then rides M1-866's own escalation menu rather than this
ticket's diff.

## Census

Not class-scoped: two pending ticket files, four acceptance edits.

## Merge note (2026-08-17)

Post-approval, main advanced one commit (cbcd1114, board-only regen after
M1-879's filing). The branch was rebased onto it; the conflict set was
exactly the STATUS-TICK.md board regen (the expected pseudo-conflict), and
`git diff <r1-review-tree> HEAD --name-only -- ':(exclude)docs/plan'` is
empty — the build input is byte-identical to the r1-verified tree. The
post-rebase full re-verify was SKIPPED — MERGE-VERIFY-SKIP, driver-directed
(M1-873 precedent; the same board-only conflict set).
