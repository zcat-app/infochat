---
id: M1-884
title: "Reconcile tag-tree source and unfollow rules"
status: pending
created: 2026-08-18
last_updated: 2026-08-18
flow: tick
reproduction: >-
  Probe: before this ticket, the merged specification has no rule that marks
  source-eligible leaves in /get-tags or makes ALL-mode top unfollow subtract
  descendants; `rg -n "source-eligible|descendant|subtree exclusion" docs/spec/commands.md docs/spec/schema.md` has no matching contract.
analysis_ref: docs/plan/m1/tick-analysis/tag-top-source-and-unfollow-subtree.md
blocked_by:
  - M1-882
  - M1-883
files_scope:
  - docs/spec/schema.md
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
  - "The reproduction probe is resolved: approved rule text in docs/spec/schema.md §Sources and tags and docs/spec/commands.md §Source management says source/bootstrap tags are leaves only, while tops remain valid follows/search filters — verification: exact approved wording plus the probe has the required contracts (analysis P1/P4)."
  - "docs/spec/commands.md §Discovery documents the localized /get-tags distinction between source-eligible leaves and top nodes, and docs/spec/security.md §Friendly errors preserves the no-unvalidated-inbound-text constraint for restricted-dictionary replies — verification: exact approved wording and bundle-key parity probe (analysis P3/P4)."
  - "docs/spec/commands.md §Per-scope tag preferences records that ALL-mode `/unfollow-tag <top>` excludes the top's complete descendant-leaf subtree, while a top follow remains a read-time subtree wildcard — verification: exact approved wording and `rg` probe for both contracts (analysis P5)."
  - "docs/spec/llm.md §Failure handling (recap) and docs/spec/security.md §Failure handling describe the bootstrap fallback consistently with leaf-only source inputs; no prose claims a top may reach post.tags — verification probe: `grep -n bootstrap_tags docs/spec/llm.md docs/spec/security.md` returns only the approved leaf-only contract (analysis P1)."
  - "Failure mode: the spec-review probe fails if any amended source-management error example echoes a raw supplied tag or if it calls a top source-eligible — verification: `rg -n` probe plus user approval of exact rule text under engineering-rules §12 (analysis P3)."
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
spec_amend_for: docs/spec/schema.md §Sources and tags
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-884: Reconcile tag-tree source and unfollow rules

## Context

After M1-882 and M1-883 make behavior true, the spec must make the restricted source dictionary and top-unfollow subtree meaning truthful and discoverable. The escalated M1-869 draft is not carried forward; this ticket derives a compact record from verified behavior and obtains approval for exact prose. Shared analysis: P1, P3–P5.

## Root cause

The current specification describes controlled vocabulary membership but does not separately define source eligibility, `/get-tags` role display, or descendant subtraction from an ALL-mode seed. Those omissions allowed code and proposed prose to disagree.

## Pitfalls

- P1: prose that allows top source tags contradicts the leaf-only fallback invariant.
- P3: examples or errors that echo supplied tag text reopen the friendly-error reflection defect.
- P4: a source restriction must not imply tops are invalid follows/search filters.
- P5: saying “minus the top” without descendant language repeats the wrong ALL-transition semantics.

## Approach

- **Files to touch:** only the four cited spec files, subject to user approval of exact rule text.
- First re-read the landed M1-882/M1-883 tests and code; then draft rule text that records them. Keep history, ticket IDs, and review claims out of spec prose per engineering-rules §12.
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

Class-scoped specification reconciliation. Re-run `rg -n "bootstrap_tags|source-eligible|unfollow-tag|subtree|post.tags" docs/spec/schema.md docs/spec/commands.md docs/spec/llm.md docs/spec/security.md` and dispose each matching normative source/bootstrap, fallback, discovery, and top-unfollow rule here; unrelated ingest/security matches remain unchanged because this ticket changes no implementation.
