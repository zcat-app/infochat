---
id: M1-288
title: "Group-scope /summary resolves its scope instead of returning no_posts_yet"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The group-wide cached-digest /summary flow's TTL/cache mechanics (commands.md ~:1148) — only scope RESOLUTION is fixed; if the digest-anchor routing already works once a scope id resolves, don't touch it.
  - /retry — it replays from the anchor; it inherits the fix through the anchor row.
  - Group approval/membership gating — the router has already enforced D47 by the time the handler runs.
acceptance:
  - "Group-scope /summary is functional: a named test invokes /summary as a registered, approved-group member and asserts the summary flow runs (anchor row written, reply produced) instead of the no_posts_yet reply that every group invocation produces today (SummaryCommandHandler.resolveScopeId returns Optional.empty() for every non-DM scope at ~:363-368)."
  - "Scope resolution matches the spec's anchor model (commands.md ~:779, verbatim: 'per-member personal anchors (one per (user, group) from the user's last /summary)'): the resolved scope is (caller users.id via the inbound (adapter, contact_id), group id already resolved by the router at step 4.1); a named test asserts two different members of the same group get distinct personal anchors."
  - "Per-(user, scope) isolation holds (project key convention): a named test asserts a user's DM anchor and the same user's group anchor never read or overwrite each other."
  - "The 'T2-F territory' comments in SummaryCommandHandler are removed or rewritten to describe the implemented state; the matching stale-behavior documentation on the test side is updated too: SummaryCommandHandlerTest.groupScopeReturnsNoPostsYet (which pins the old group->no_posts_yet behavior acceptance item 1 reverses) is replaced by the group-scope-functional test, and the SummaryCommandHandlerTest class-javadoc bullet asserting 'Group scope: handler returns no_posts_yet' is rewritten to describe the implemented group flow."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main EXCEPT SummaryCommandHandlerTest.groupScopeReturnsNoPostsYet,
      which acceptance item 1 reverses — that test (and the matching class-javadoc bullet) is
      REPLACED by the group-scope-functional test, not preserved (premise-fail refine 2026-06-12).
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
      files: 6
      added: 565
      removed: 60
escalations:
  - date: 2026-06-12
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — surfaced by the developer at start, before implementation.
      test_plan.preserves ("all tests currently green on main") conflicts
      with acceptance item 1: SummaryCommandHandlerTest.groupScopeReturnsNoPostsYet
      (lines 302-316) is green on main and asserts group scope returns the
      "No posts to summarize" reply — the exact behavior item 1 reverses.
      The same test class's javadoc (lines 76-77) documents the old
      behavior as an invariant. The clarity pre-flight's
      TEST-CHANGES-AUTHORIZED: NOT-APPLICABLE was a false negative: it
      grepped the snake_case "no_posts_yet" and missed the camelCase test
      name + its "No posts to summarize" assertion string.
revisions:
  - date: 2026-06-12
    reason: premise-fail refinement
    summary: |
      - test_plan.preserves: was "all tests currently green on main". Narrowed
        to carve out SummaryCommandHandlerTest.groupScopeReturnsNoPostsYet, which
        is green on main but asserts the exact group->no_posts_yet behavior that
        acceptance item 1 reverses. That test is REPLACED (not preserved); the
        blanket "preserve all green" clause otherwise contradicted acceptance.
      - acceptance item 4: was "The 'T2-F territory' comments in
        SummaryCommandHandler are removed or rewritten ...". Extended to also
        cover the test-side stale documentation — replacing
        groupScopeReturnsNoPostsYet with the group-scope-functional test and
        rewriting the SummaryCommandHandlerTest class-javadoc bullet that
        documents "Group scope: handler returns no_posts_yet".
      - clarity_check (PASS, 2026-06-12) is left as the historical record; its
        TEST-CHANGES-AUTHORIZED: NOT-APPLICABLE line was a false negative
        (snake_case grep missed the camelCase test) — documented in the
        escalations entry above. Clarity is not re-run for a mid-implementation
        premise-fail refine.
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

# M1-288: Group-scope /summary resolves its scope instead of returning no_posts_yet

## Context

Deep-review v5 verified HIGH **U-05** (`deep-code-review/v5/UNIFIED-REPORT.md`
§2; source `deep-code-review/v5/deepseek/07-module-infochat-provider.md#F1`,
unique find — gitignored; all load-bearing facts inlined):

`SummaryCommandHandler.resolveScopeId` returns `Optional.empty()` for every
non-DM scope (verified 2026-06-11 — ":363 // Group scope is T2-F territory;
v1 has no actor seam"), and the caller maps the empty to `no_posts_yet`. So
**every** group invocation of /summary answers "no posts yet" regardless of
data. The spec commits group /summary flows: per-member personal anchors
(commands.md ~:779) and the group cache-TTL flow (~:1148).

Premise-verification caveat carried from the unified report: deepseek's
"DM and group; any non-banned user" availability sentence is a paraphrase,
not a spec quote — the two real spec anchors above are what acceptance
binds to.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **⚠ User decision at start:** default = **implement** (the spec commits
  the flows; the router already resolves the group at step 4.1 and the
  inbound (adapter, contact_id) is in InboundContext, so the actor seam
  exists). The alternative is a spec amendment marking /summary DM-only in
  v1 — if the user picks that, refine to a doc-only ticket amending
  commands.md and pinning the no_posts_yet group reply with a test.
- Coordination: M1-303 (localization) and M1-307 (provider lows) also edit
  SummaryCommandHandler. Check the worktree landscape at start; this
  ticket's region is resolveScopeId + anchor wiring, theirs are rendering
  helpers — overlap is mergeable but sequence consciously.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-288-*.md
```
