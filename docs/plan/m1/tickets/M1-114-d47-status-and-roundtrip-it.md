---
id: M1-114
title: "D47 /status pending count + group authorization roundtrip IT"
status: pending
created: 2026-05-27
last_updated: 2026-05-31
blocked_by:
  - M1-113
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StatusCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupAuthorizationRoundtripIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-collector/** — no collector changes
  - infochat-core/** — no SPI changes
  - any migration file — frozen
  - GroupApprovalService — M1-112 is frozen
  - InboundRouter — M1-112 is frozen
  - /approve-group, /reject-group, /list-groups — M1-113 is frozen
  - adapter-layer group support — M1-104, M1-108
acceptance:
  - "StatusCommandHandler's admin view includes a count of pending groups (approval_status='pending'). The count is shown as 'Pending groups: N' (or equivalent localized string) in the /status output for bot admins. Non-admin users do not see the count"
  - "GroupAuthorizationRoundtripIT exercises the full D47 flow end-to-end: (1) register a user via DM invite-code consume; (2) send a group @mention from the registered user in a new group → group created as pending, fixed reply returned, admin notified; (3) send another @mention in the same pending group → fixed reply, no re-notification; (4) send a group @mention from an unregistered contact → silent drop; (5) admin runs /approve-group → approval_status transitions to approved, group message sent; (6) registered user sends a group @mention → command is processed normally; (7) admin runs /reject-group → approval_status transitions to rejected; (8) registered user sends a group @mention → fixed 'rejected' reply"
  - "GroupAuthorizationRoundtripIT verifies per-group reply rate cap: after exhausting the bucket in a pending group, subsequent @mentions are silently dropped (no reply)"
  - "GroupAuthorizationRoundtripIT verifies per-user activation cap: user activates groups up to the cap → next activation returns fixed 'limit reached' error"
  - "GroupAuthorizationRoundtripIT verifies digest scheduling: only approved groups with removed_at IS NULL are eligible for digest selection (assert via query, not by running the full digest scheduler)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupAuthorizationRoundtripIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
  - docs/spec/security.md §Authorization model
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D47
reviews: {}
escalations:
  - date: 2026-05-31
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      N/A
revisions:
  - date: 2026-05-31
    reason: clarity-fail rework — trim spec_refs to real heading anchors; raise risk to medium per clarity WARN (security_relevant: true ticket described as D47 acceptance gate)
    prior_values: |
      risk: low
      spec_refs:
        - docs/spec/commands.md §Discovery /status
        - docs/spec/security.md §Authorization model steps 3, 3.5
        - docs/spec/commands.md §Periodic group digests
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-114: D47 /status pending count + group authorization roundtrip IT

## Context

The final D47 ticket: adds the pending-groups count to `/status` for
bot admins (passive discovery) and provides an end-to-end integration
test that exercises the full D47 group authorization flow.

`security_relevant: true` — the roundtrip IT is the acceptance gate
for the D47 security boundary.

## Acceptance

See frontmatter.

## Out-of-scope

- All D47 implementation code — frozen in M1-110..M1-113.
- Adapter-layer group support — M1-104, M1-108.

## Notes

- **StatusCommandHandler.** If StatusCommandHandler does not yet exist
  as a dedicated handler (it may be part of HelpCommandHandler or
  a generic route), the ticket creates it. The pending-groups count
  is a single `SELECT COUNT(*) FROM groups WHERE approval_status =
  'pending' AND removed_at IS NULL` added to the admin section.
- **Roundtrip IT scope.** The IT uses the InMemoryAdapter and
  pre-seeds the admin via bootstrap. It does NOT require SimpleX or
  Signal adapters. The test exercises the Provider-side D47 logic
  end-to-end through InboundRouter → GroupApprovalService →
  CommandHandlers.
- **Digest eligibility.** The IT asserts digest-group selection via
  a query (`SELECT ... FROM groups WHERE approval_status = 'approved'
  AND removed_at IS NULL`), not by running the full DigestScheduler.
  This keeps the IT focused on D47 without pulling in the digest
  worker dependency chain.
