---
id: M1-884
title: "Reconcile tag-tree source and unfollow rules"
status: done
created: 2026-08-18
last_updated: 2026-08-18
flow: tick
reproduction: >-
  Probe on main after M1-882 and M1-883: the existing leaf-only source rule is
  present at docs/spec/schema.md:310-313 and docs/spec/commands.md:948-952,
  but docs/spec/commands.md §Discovery only says that /get-tags lists the
  controlled vocabulary and followed markers, without distinguishing tops
  from source-eligible leaves; §Per-scope tag preferences still says ALL-mode
  unfollow seeds tags “minus the unfollowed tag”, without descendant language;
  and `rg -n "bootstrap_tags" docs/spec/llm.md` returns no contract. The
  expected remaining documentation contract is an explicit top/leaf display
  distinction, ancestor-subtree exclusion for top unfollow, and consistent
  leaf-only fallback wording.
analysis_ref: docs/plan/m1/tick-analysis/tag-top-source-and-unfollow-subtree.md
blocked_by:
  - M1-882
  - M1-883
files_scope:
  - docs/spec/commands.md
  - docs/spec/llm.md
  - docs/spec/security.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Production code, tests, migrations, bundles, or command-catalogue changes.
  - Adopting or resurrecting the escalated M1-869 spec-amendment diff.
  - Changing top follow/filter wildcard semantics.
acceptance:
  - "The refined reproduction probe is resolved: the already-landed leaf-only source/bootstrap contract remains authoritative, while the amended text closes the missing /get-tags, top-unfollow, and fallback wording gaps — verification: exact approved wording and the reproduction probe (analysis P1/P4)."
  - "docs/spec/commands.md §Discovery documents the localized /get-tags distinction between source-eligible leaves and top nodes, while preserving tops as valid follows and filters; restricted-dictionary replies remain trusted suggestions and do not echo inbound tag text — verification: exact approved wording and the existing bundle-key parity probe (analysis P3/P4)."
  - "docs/spec/commands.md §Per-scope tag preferences records that ALL-mode `/unfollow-tag <top>` excludes the top's complete descendant-leaf subtree, while a top follow remains a read-time subtree wildcard — verification: exact approved wording and `rg` probe for both contracts (analysis P5)."
  - "docs/spec/llm.md §Failure handling (recap) and docs/spec/security.md §Failure handling describe the bootstrap fallback consistently with leaf-only source inputs; no prose claims a top may reach post.tags — verification probe: run `rg -n \"bootstrap_tags\" docs/spec/llm.md docs/spec/security.md`; it returns only the approved leaf-only contract (analysis P1)."
  - "Failure mode: the spec-review probe fails if amended prose calls a top source-eligible, describes top unfollow as removing only the top id, or echoes a raw supplied tag in a restricted-dictionary example — verification: `rg -n` probe plus user approval of exact rule text under engineering-rules §12 (analysis P3/P5)."
  - "No non-doc paths change and mvn verify from the repository root remains green — verification: diff file list and command exit 0."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/commands.md §Source management
  - docs/spec/commands.md §Discovery
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/security.md §Failure handling
decision_refs:
  - D5
  - D22
  - D59
decomposed_from: M1-869
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for: docs/spec/commands.md §Discovery
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-18
    verdict: APPROVE
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: NOT-APPLICABLE
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: "round-1 full diff: 4 files changed, 61 insertions(+), 32 deletions(-); docs-only — §Discovery /get-tags role labels, ALL-mode top-unfollow subtree exclusion, llm.md leaf-only fallback recap; approved wording applied verbatim including the retained D59 parenthetical; security.md/schema.md deliberately untouched"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-18
  result: >-
    Pass. tick-lint reports 0 findings; M1-882 and M1-883 are done; all
    acceptance items are executable as documentation probes, exact-text
    review, diff inspection, or mvn verify. The refined root-cause claims
    were verified against docs/spec/commands.md:410-411 and :1113-1118,
    docs/spec/schema.md:310-313, and the absence of bootstrap_tags in
    docs/spec/llm.md. The census returned docs/spec/commands.md and
    docs/spec/security.md, both in files_scope; llm.md has no matching rows.
    Analysis pitfalls P1 and P3-P5 are carried into the ticket. Blocked-by
    tests added only executable source/bootstrap and unfollow behavior; no
    test changes cross this documentation-only seam, so all are preserved
    unchanged. No ambiguity blocks start; exact spec wording will be shown
    for approval before docs/spec is edited.
escalation_reason:
---

# M1-884: Reconcile tag-tree source and unfollow rules

## Context

After M1-882 and M1-883 make behavior true, the remaining spec gaps are the restricted source dictionary's discoverability, top-unfollow subtree meaning, and cross-document fallback wording. M1-882 already landed the core leaf-only source contract; this ticket must preserve it rather than restate it as new behavior. The escalated M1-869 draft is not carried forward; this ticket derives a compact record from verified behavior and obtains approval for exact prose. Shared analysis: P1, P3–P5.

## Root cause

M1-882 corrected the core source/bootstrap contract, but the specification still does not separately define `/get-tags` role display or descendant subtraction from an ALL-mode seed, and `llm.md` does not restate the leaf-only fallback. Those remaining omissions leave the documentation behind the shipped behavior.

## Pitfalls

- P1: new fallback prose must preserve the already-landed leaf-only source/bootstrap invariant without contradicting the existing schema and source-management clauses.
- P3: examples or errors that echo supplied tag text reopen the friendly-error reflection defect.
- P4: a source restriction must not imply tops are invalid follows/search filters.
- P5: saying “minus the top” without descendant language repeats the wrong ALL-transition semantics.

## Approach

- **Files to touch:** only the three remaining spec files cited in `files_scope`, subject to user approval of exact rule text. Preserve the existing leaf-only clauses in `schema.md` and `commands.md` rather than rewriting them.
- First re-read the landed M1-882/M1-883 tests, code, and already-landed spec clauses; then draft rule text for only the remaining gaps. Keep history, ticket IDs, and review claims out of spec prose per engineering-rules §12.
- Preserve all existing security, command-catalogue, localization, and D59 commitments; this ticket changes no executable behavior.
- P1→leaf-only rule; P3→trusted-suggestion/no-echo rule; P4→explicit top follow/filter carve-out; P5→descendant-exclusion wording.

## Definition of done

The approved text gives one consistent contract for source/bootstrap leaves, discoverability, safe errors, fallback storage, and top-unfollow subtree exclusion. The diff is docs/spec-only and the full build stays green.

## Verification

- P1 → acceptance 1 and 4 wording/probes.
- P3 → acceptance 2 and 5's no-echo probe.
- P4 → acceptance 1 and 2 wording/probes.
- P5 → acceptance 3 wording/probe.
- Failure-mode P3 → the no-echo probe must not find a raw supplied tag in a restricted-dictionary reply example.
- Acceptance 6 → diff file list and `mvn verify`.

## Out-of-scope

No implementation or test changes, no renewed M1-869 draft, and no change to top follow/search semantics. If re-reading the landed behavior contradicts this analysis, stop and raise a SPEC-GAP rather than writing compensating prose.

## Census

Class-scoped specification reconciliation. Re-run `rg -n "bootstrap_tags|source-eligible|unfollow-tag|subtree|post.tags" docs/spec/commands.md docs/spec/llm.md docs/spec/security.md` and dispose each matching normative fallback, discovery, and top-unfollow rule here; the already-landed source/schema clauses remain unchanged, and unrelated ingest/security matches remain unchanged because this ticket changes no implementation.
