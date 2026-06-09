---
id: M1-259
title: "searchPosts: EXPLICIT empty tag-intersection yields zero posts"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - EligiblePostQuery (the deterministic /summary sibling) — already correct (its SQL array_agg && p.tags yields zero rows on an empty followed-set); used here only as the parity reference, not modified.
  - The ALL-mode no-tags path (returns the unconstrained subscribed feed) — that behavior is correct and must be preserved; only the EXPLICIT empty-intersection case changes.
  - The source_subscription scope binding, the unknown-tag rejection, the window/limit, and the result JSON shape — unchanged.
  - The cross-user / cross-scope isolation — already enforced by the source_subscription clause; this is an intra-scope policy fix, not an isolation fix.
acceptance:
  - "When the caller is in EXPLICIT tag mode and the requested tags do not intersect the scope's followed tags (empty intersection), searchPosts returns zero posts (an empty result), NOT the unfiltered subscribed feed. The fix distinguishes 'no tag constraint requested' (ALL mode, no tags) from 'a tag constraint that resolved to the empty set' (EXPLICIT, empty intersection) so the two are no longer conflated behind one empty List."
  - "A named test in the chat tool test package asserts: (a) EXPLICIT mode requesting a tag the scope does NOT follow returns empty; (b) EXPLICIT mode requesting a tag the scope DOES follow returns only matching posts; (c) ALL mode with no tags still returns the full subscribed feed (unchanged); the EXPLICIT searchPosts result for a given scope state matches the EligiblePostQuery/summary result for the same state."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-259: searchPosts: EXPLICIT empty tag-intersection yields zero posts

## Context

`queryPosts` applies the `p.tags && ?` filter only when `effectiveTags` is
non-empty. In EXPLICIT mode, when the caller asks for tags not in the scope's
followed set, `computeEffectiveTags` returns an empty intersection list — which
is indistinguishable from "no tag constraint requested," so the query runs with
no tag clause and returns every `READY` post the scope is subscribed to. Per
`docs/spec/commands.md` §Per-scope tag preferences, EXPLICIT mode "uses only the
tags whose `scope_tag` rows exist for that scope," so an EXPLICIT scope asking for
a tag it does not follow has an empty intersection and must yield zero posts. The
deterministic sibling `EligiblePostQuery.selectPosts` already gets this right
(`p.tags && (SELECT COALESCE(array_agg(...), ARRAY[]::TEXT[]) ...)` matches
nothing on an empty followed-set). This is an intra-scope policy bypass — an
EXPLICIT-mode user (or the LLM acting for them) sees posts for tags they
explicitly narrowed away — and a determinism-boundary parity gap with `/summary`.
Source: `deep-code-review/v3.5/opus-48/07-module-infochat-provider.md#F1`
(verified live against `SearchPostsTool.java:107-124, 162-165`).

## Acceptance

See frontmatter. In prose: separate "filter not requested" from "filter resolved
to nothing" (e.g. carry a `constrained` flag alongside the tag list, per the
report's recommended fix, or push the intersection into SQL as `EligiblePostQuery`
does), so an EXPLICIT empty intersection returns zero posts while ALL-mode no-tags
stays unconstrained. A named test pins all three cases plus the `/summary` parity;
`mvn verify` is 0.

## Out-of-scope

See frontmatter. `EligiblePostQuery`, the ALL-mode no-tags path, the scope
binding, unknown-tag rejection, window/limit, and result shape are untouched.

## Notes

- Two implementation shapes are acceptable: (Option A) a `constrained` flag on the
  effective-tags result so an empty list under a constraint yields no posts; or
  (Option B) push the intersection into SQL mirroring `EligiblePostQuery`. Option
  A is the smaller diff and keeps the existing Java-side known-tag validation;
  Option B is a single source of truth for the EXPLICIT semantics but a larger
  `queryPosts` rewrite. Implementer's choice.
- Adjacent code / parity reference: `EligiblePostQuery.selectPosts` lines ~207-216.
</content>
