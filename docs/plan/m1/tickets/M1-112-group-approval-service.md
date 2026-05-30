---
id: M1-112
title: "GroupApprovalService + per-group rate cap + step 3.5"
status: pending
created: 2026-05-27
last_updated: 2026-05-30
blocked_by:
  - M1-111
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupApprovalService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupApprovalCheck.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupApprovalServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupApprovalCheckTest.java
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-collector/** — no collector changes
  - infochat-core/** — no SPI changes
  - any migration file — M1-110 is frozen
  - /approve-group, /reject-group, /list-groups command handlers — M1-113
  - /status changes — M1-114
  - GroupAutoPromoteService activated_by priority — deferred to M1-113 or a follow-up; the auto-promote continues with the existing first-eligible rule until the priority logic is wired
  - VouchCommandHandler — M1-111 is frozen
  - adapter-layer group support — M1-104, M1-108
acceptance:
  - "GroupApprovalService encapsulates the D47 step 3.5 logic: approval_status check, group creation with INSERT...ON CONFLICT DO NOTHING, activated_by assignment, per-user activation cap enforcement, global max-groups cap enforcement, throttled admin notification on group creation"
  - "GroupApprovalCheck is a lightweight check object called by InboundRouter at step 3.5: looks up groups row by (adapter, upstream_group_id), checks per-group reply rate bucket, delegates to GroupApprovalService for approval logic"
  - "InboundRouter.onMessage at step 3.5 (between the registered-user check at step 3 and the ban check at step 4): calls GroupApprovalCheck for group-scope inbound from registered users; pending/rejected → fixed reply + stop; approved → proceed to step 4"
  - "Per-group reply rate bucket (shared across approval states) is implemented as a RateCapBucket keyed by groups.id. When the bucket is exhausted, the group @mention is silently dropped — no reply, no processing. Bucket window and cap are profile-driven via infochat.ratelimit.group-reply-per-15min"
  - "Per-user group activation cap: GroupApprovalService counts rows where activated_by=user.id AND approval_status IN ('pending','approved','rejected') AND removed_at IS NULL. Exceeding the cap returns a fixed 'group activation limit reached' reply. Cap is profile-driven via infochat.groups.per-user-activation-cap"
  - "Global max-groups cap: GroupApprovalService counts rows where removed_at IS NULL AND approval_status IN ('pending','approved'). Exceeding the cap returns a fixed error. Cap is profile-driven via infochat.groups.global-max-groups"
  - "Throttled admin notification fires exactly once per group creation (not per subsequent @mention in the same pending group). Notification includes adapter, upstream_group_id, activating user's contact id (redacted), and a copy-pasteable /approve-group <uuid> command string"
  - "Bundle keys added for: group.pending, group.rejected, group.activation_limit, group.global_limit — in both en.properties and cs.properties"
  - "GroupApprovalServiceTest covers: (a) first registered @mention creates pending group + admin notification; (b) second @mention in same pending group → fixed reply, no re-notification; (c) rejected group → fixed reply; (d) approved group → proceeds (returns approved); (e) per-user activation cap exceeded → error; (f) global max-groups cap exceeded → error; (g) concurrent INSERT race → loser re-reads existing row. grep -E '@Test' GroupApprovalServiceTest.java returns ≥7 matches"
  - "GroupApprovalCheckTest covers: (a) per-group reply rate bucket exhausted → silent drop; (b) bucket not exhausted → delegates to approval logic. grep -E '@Test' GroupApprovalCheckTest.java returns ≥2 matches"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupApprovalServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupApprovalCheckTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Rate limiting
  - docs/spec/schema.md §Identity and access
  - docs/spec/messaging.md §Identity and groups
decision_refs:
  - D47
reviews: {}
escalations:
  - date: 2026-05-30
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID FAIL — three spec_refs cite headings that do not exist:
        1. `docs/spec/security.md §Authorization model step 3.5` — heading is
           `## Authorization model` (line 300); "step 3.5" is body text within
           that section (numbered list item 3.5 at line 108).
        2. `docs/spec/security.md §Rate limiting — per-group buckets` — heading
           is `## Rate limiting` (line 959); per-group bucket content is a
           bullet under that section, not a sub-heading.
        3. `docs/spec/schema.md §Identity and access — Group entity` — heading
           is `### Identity and access` (line 13); Group entity is a bullet
           under that section, not a sub-heading.
revisions:
  - date: 2026-05-30
    reason: clarity-fail rework — fix non-existent spec_refs headings
    prior_values: |
      spec_refs (replaced three of four entries, fourth was already valid):
        - docs/spec/security.md §Authorization model step 3.5
            → docs/spec/security.md §Authorization model
            (step 3.5 is body text at line 108; the parent heading still
             bounds the content the ticket cites)
        - docs/spec/security.md §Rate limiting — per-group buckets
            → docs/spec/security.md §Rate limiting
            (per-group reply/command/LLM caps are bullets at lines
             985–1005 under the parent heading)
        - docs/spec/schema.md §Identity and access — Group entity
            → docs/spec/schema.md §Identity and access
            (Group entity columns approval_status / activated_by are
             bullets at lines 71–110 under the parent heading)
      Verified each replacement preserves the load-bearing reference by
      grepping for `step 3\.5|approv|group` / `per-group|bucket` /
      `approval_status|activated_by` under the parent headings; all hits
      land within the parent section's body.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-112: GroupApprovalService + per-group rate cap + step 3.5

## Context

D47 gate #2: admin-approved groups. This ticket implements the core
approval-check logic (step 3.5) and wires it into InboundRouter. It
also adds the per-group reply rate bucket and the per-user/global
group caps.

`complexity: high` because the service encapsulates concurrent group
creation (INSERT...ON CONFLICT), cap enforcement, admin notification
integration, and a new rate-cap bucket keyed by group. `round_cap: 3`.

`security_relevant: true` — the approval gate and rate caps are
security boundaries.

## Acceptance

See frontmatter.

## Out-of-scope

- The `/approve-group`, `/reject-group`, `/list-groups` commands — M1-113.
- `/status` pending-groups count — M1-114.
- GroupAutoPromoteService `activated_by` priority wiring — the
  auto-promote continues with the existing first-eligible rule for
  now. The priority logic can be wired in M1-113 alongside the
  approve command (approval is the trigger for the first auto-promote
  in a new group).

## Notes

- **RateCapBucket reuse.** The existing `RateCapBucket` class
  (per-user transport cap) can be parameterized by key type. The
  per-group bucket uses `groups.id` as the key. If the existing class
  is not generic enough, a parallel `GroupRateCapBucket` is acceptable.
- **Admin notification.** Reuses the `ThrottledAdminNotifier`
  infrastructure from M1-082 (relocated to infochat-core). The
  notification fires in the same coalesced summary as source failures.
- **Profile-driven defaults.** See `docs/design/04-security.md` §4.9
  for the per-profile values. The properties are:
  `infochat.ratelimit.group-reply-per-15min`,
  `infochat.groups.per-user-activation-cap`,
  `infochat.groups.global-max-groups`.
