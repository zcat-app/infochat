---
id: M1-478
title: "Group personal /retry never matches the group anchor /summary writes"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Any change to /summary anchor write semantics beyond what is needed to make read and write agree."
  - "DM-scope /retry (already works)."
acceptance:
  - >-
    A group member who runs /summary in a group and then runs /retry in the same
    group re-renders their own group summary instead of receiving NO_ANCHOR. The
    /retry anchor read key matches the key /summary writes: the scope kind is
    derived from the actual inbound scope (group → "group"), not hardcoded to
    "dm", and the user-id resolution returns the member's id in group scope.
  - >-
    A new test exercises the group path end-to-end (write a group summary anchor,
    then resolve the same member's /retry) and asserts a non-NO_ANCHOR result;
    the existing DM-scope /retry behavior is unchanged.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerGroupScopeIT.java"
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

# M1-478: Group personal /retry never matches the group anchor /summary writes

## Context

From `/deep-code-review full` (2026-06-27), report
`12-main-infochat-provider-02.md#F1` (rated **high**, verified at source). A
documented spec feature — a group member re-running their own group `/summary`
via `/retry` — is unreachable. `RetryCommandHandler.resolveUserId` returns empty
for non-DM scope (`RetryCommandHandler.java:332`) and the anchor read hardcodes
the scope kind `"dm"` (`:153`), while `SummaryCommandHandler` writes the anchor
with `scopeKindOf(scope)` = `"group"` (`:274`). The read key can therefore never
match the written key, so every group `/retry` returns
`ERROR_RETRY_NO_ANCHOR`.

## Acceptance

See frontmatter. Make the `/retry` anchor read derive its scope kind and user id
from the actual inbound scope so it matches what `/summary` writes; add an IT
covering the group path.

## Out-of-scope

See frontmatter. DM `/retry` already works and must stay unchanged.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 12#F1.
- Per-(user, scope) isolation must be preserved — the fix scopes the read to the
  member's own group anchor, never another user's.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-478-*.md
```
