---
id: M1-288
title: "Group-scope /summary resolves its scope instead of returning no_posts_yet"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
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
  - "The 'T2-F territory' comments in SummaryCommandHandler are removed or rewritten to describe the implemented state."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
