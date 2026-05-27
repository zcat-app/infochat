---
id: M1-111
title: "Remove group_only registration path + simplify /vouch"
status: pending
created: 2026-05-27
last_updated: 2026-05-27
blocked_by:
  - M1-110
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AutoRegisterService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-collector/** — no collector changes
  - infochat-core/** — no SPI changes
  - GroupApprovalService or any new service — M1-112
  - /approve-group, /reject-group, /list-groups — M1-113
  - per-group rate caps — M1-112
  - any migration file — M1-110 is frozen
  - GroupAutoPromoteService — not modified (D47 priority logic is M1-112)
acceptance:
  - "InboundRouter step 3 is replaced: a group @mention from an unregistered contact (no users row, or registration_state='preban') produces a silent drop — no reply, no DB write, no registration. Verify: grep -E 'resolveOrRegisterGroup|REGISTRATION_STATE_GROUP_ONLY' InboundRouter.java returns ZERO matches"
  - "InboundRouter step 4.7 / the DM-gate carve-out for group_only is removed. Verify: grep -iE 'group.only.*dm|dm.*gate.*group' InboundRouter.java returns ZERO matches"
  - "AutoRegisterService.resolveOrRegisterGroup is removed or the entire class is deleted. If the class is retained for the DM-side resolveOrRegister call (compatibility), the group path is gone. Verify: grep -E 'resolveOrRegisterGroup' AutoRegisterService.java returns ZERO matches (or the file does not exist)"
  - "VouchCommandHandler no longer changes registration_state. The UPDATE sets only probation_until=NULL. Verify: grep -E 'group_only|vouched|registration_state' VouchCommandHandler.java returns ZERO matches for group_only and vouched state transitions"
  - "VouchCommandHandler is a no-op with friendly reply when the user is already past probation (probation_until IS NULL OR probation_until < NOW())"
  - "VouchCommandHandlerTest covers: (a) non-admin → error.admin_only; (b) unknown contact → error.contact_not_registered; (c) user in probation → probation_until cleared, reply.vouch.success; (d) user already past probation → no-op reply.vouch.noop; grep -E '@Test' VouchCommandHandlerTest.java returns ≥4 matches"
  - "InboundRouterIntakeOrderingTest: scenario (g) for DM-gate-for-group_only is removed or replaced with the D47 silent-drop scenario for unregistered group contacts. grep -iE 'groupOnlyDmGate|group_only' InboundRouterIntakeOrderingTest.java returns ZERO matches"
  - "All test files that previously seeded registration_state='group_only' are updated to use 'invited' or to test the new silent-drop behavior instead"
  - "No remaining production-code references to 'group_only' as a literal string or as a constant. Verify: grep -rE \"'group_only'|GROUP_ONLY\" infochat-provider/src/main/ returns ZERO matches"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AutoRegisterServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java
  preserves:
    - all tests currently green on main (after adapting group_only references)
spec_refs:
  - docs/spec/security.md §Authorization model step 3
  - docs/spec/security.md §Invite-code registration
  - docs/spec/commands.md §Admin /vouch
  - docs/spec/schema.md §Identity and access — User entity
decision_refs:
  - D44
  - D45
  - D47
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-111: Remove group_only registration path + simplify /vouch

## Context

D47 removes the `group_only` registration state and the group
auto-registration path. This is a large deletion ticket that guts the
pre-D47 group registration logic from InboundRouter, removes or
simplifies AutoRegisterService, and simplifies VouchCommandHandler to
probation-only (no registration_state transition).

`complexity: high` because 12 files are affected and the InboundRouter
step 3 replacement must maintain the silent-drop invariant while
preserving the rest of the intake pipeline. `round_cap: 3` for margin.

`security_relevant: true` — the silent-drop for unregistered group
contacts is a security boundary (D47 gate #1).

## Acceptance

See frontmatter.

## Out-of-scope

- Schema migration — M1-110 is frozen.
- GroupApprovalService / step 3.5 wiring — M1-112.
- New admin commands — M1-113.

## Notes

- **AutoRegisterService fate.** The class has two paths:
  `resolveOrRegisterGroup` (removed by D47) and a DM-side
  `resolveOrRegister` pass-through used by InboundRouter for the
  invite-code consumer compatibility seam. If the DM-side path has
  callers, the class is retained but the group method is deleted.
  If no callers remain, the class can be deleted entirely.
- **Test fixture migration.** Many existing tests seed users with
  `registration_state='group_only'`. After M1-110's migration removes
  the CHECK value, these fixtures must change to 'invited'. The
  tests themselves may need logic changes (e.g., the DM-gate test
  scenario is replaced with a D47 silent-drop scenario).
- **Bundle keys.** Remove or update bundle keys related to the old
  group-first-mention welcome and the DM-gate-for-group_only reply
  if they become orphaned. Add no new keys — new keys are M1-112's
  scope.
