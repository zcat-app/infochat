---
id: M1-219
title: "searchPosts window/ordering semantics: bind the spec to a timestamp column"
status: done
created: 2026-06-07
last_updated: 2026-06-08
blocked_by: [M1-197]
files_budget: 4
files_scope:
  - docs/spec/security.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the ready_at JSON VALUE emitted by the tools — M1-197 fixes the mislabeled column (this ticket is blocked on it precisely so the value leg is settled first)
  - getPost — single-row lookup, no window or ordering to adjudicate
  - SearchPostsTool's connection count (M1-193's) and result paging/caps (M1-194's/M1-197's)
  - the per-(user, scope) visibility filter itself — unchanged either way
acceptance:
  - "A decision is recorded and applied: the spec's searchPosts tool-catalogue row — per docs/spec/security.md §Prompt-injection defenses, inputs include \"`window: duration`\" and the output is a \"list of `{uid, title, url, ready_at, tags}`\" with no sentence binding the window filter or result ordering to a column — EITHER (a) is amended to bind window and ordering explicitly to published_at, pinning today's implementation (ready_at stays a display field in the result shape); OR (b) is amended to bind them to ready_at with SearchPostsTool's window predicate and ORDER BY changed to match, pinned by named tests seeding posts whose published_at and ready_at order differently"
  - "The argument is recorded in the commit message: deterministic-retrieval implications (the same window must return the same set on re-invocation — ready_at is assigned by the pipeline and stable once READY; published_at is claimed by the source), the user-facing meaning of \"posts from the last N hours\", and late-readied posts (old published_at, fresh ready_at) appearing or not appearing in short windows"
  - "Whichever direction: the existing named tests for tag filtering, visibility, and the ready_at value (post-M1-197) stay green except where they pin the corrected ordering"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
decision_refs: []
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
      files: 4
      added: 71
      removed: 14
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 3: 'existing named tests for tag filtering, visibility, and the ready_at value' does not name specific test classes; reviewer must discover them."
    - "COMPLEXITY-RISK-CALIBRATED: risk: low is mildly under-stated for a security_relevant ticket touching an LLM tool-call predicate; consider risk: medium."
    - "TEST-CHANGES-AUTHORIZED: acceptance item 3's 'except where they pin the corrected ordering' carve-out signals conditional pre-existing test modification; add an 'Authorized test changes' section once the direction is chosen, before the first commit modifying an existing test."
  blockers: []
---

# M1-219: searchPosts window/ordering semantics

## Context

Leftover from batch 2 (named in M1-197's out_of_scope as "a separate
spec judgment nobody has filed", now filed): SearchPostsTool filters
its window and sorts by published_at while the spec's result shape
names ready_at — and the spec never says which timestamp the
`window: duration` input or the result ordering binds to. M1-197 fixes
only the VALUE leg (the JSON ready_at field carrying the published_at
column); the window/ordering question is independent and unresolved.

Re-anchored 2026-06-07: the tool-catalogue row in docs/spec/security.md
§Prompt-injection defenses carries `window: duration` as an input and
ready_at in the output shape, with no binding sentence — genuine spec
ambiguity, decision-tier (user call at start), M1-199's pattern.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: batch-2 leftover (M1-197 out_of_scope); the underlying audit
  observation is part of P9 in `UNIFIED.md` §2 under
  `deep-code-review/v2/` (opus-47 prov F2's window/ordering remainder
  after the value leg went to M1-197).
- blocked_by M1-197: the value leg must land first so this decision
  adjudicates ordering against a correctly-labeled field; M1-197 also
  edits SearchPostsTool — same file.
