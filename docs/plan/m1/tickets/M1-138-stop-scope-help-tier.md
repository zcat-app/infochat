---
id: M1-138
title: "/stop group/DM scope fix + /help per-tier filtering"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/HelpCommandHandler.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - InboundRouter.java — resolve the group scope via GroupRepository / a small handler-local helper, not by editing InboundRouter (keeps this off the PROV-ROUTER contention lane)
  - the bundle-completeness CI rule itself (only the per-command help keys it checks)
acceptance:
  - "/stop cancels in-flight chat work in group scope (resolveUserId no longer returns empty for non-DM), using a scopeKind/scopeId resolution mirroring InboundRouter.resolveChatScopeId (DM→userId, group→groupId)"
  - "A test asserts a group /stop cancels the per-(user, scope) chat work for that group"
  - "/help filters commands by caller tier (probation, non-admin, non-group-admin) and scope (DM vs group header, probation footer), driven from a closed (command, bundleKey, tier) catalogue rather than a hardcoded three-line list; per-command help keys land in the en/cs bundles"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
  - docs/spec/commands.md §Conversation control
  - docs/spec/commands.md §Permission model
decision_refs:
  - D35
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-138: /stop group/DM scope fix + /help per-tier filtering

## Context

Two provider command-surface gaps:

- **A17** — `StopCommandHandler.resolveUserId` returns empty for any non-DM
  scope, so a group user's in-flight chat work can't be cancelled; the DM path
  hardcodes `scopeKind="dm"`. Spec §Chat mode (D35) commits to per-(user, scope)
  cancellation in groups.
- **A18** — `/help` hardcodes three commands + assets; no per-tier filtering, no
  group header, no probation footer. `commands.md` §Discovery requires per-tier
  filtering and bundle-composition from per-command keys.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. Resolve group scope through `GroupRepository` or a
handler-local helper — **do not edit `InboundRouter.java`**, so this ticket
stays off the serialized PROV-ROUTER lane and runs parallel to M1-125.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A17, §A18; `opus-47-full-handout.md`
  §F-MAINT-19/20; `opus-47-only-handout.md` §S1, M6.
- Loci: `StopCommandHandler.java:62-115`, `HelpCommandHandler.java:46-74`.
