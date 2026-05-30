---
id: M1-112
title: "GroupApprovalService + per-group rate cap + step 3.5"
status: done
created: 2026-05-27
last_updated: 2026-05-30
blocked_by:
  - M1-111
files_budget: 17
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
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopGroupApprovalCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupLifecycleIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
complexity: high
risk: high
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
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopGroupApprovalCheck.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupLifecycleIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
  preserves:
    - all tests currently green on main except those listed in modifies
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Rate limiting
  - docs/spec/schema.md §Identity and access
  - docs/spec/messaging.md §Identity and groups
decision_refs:
  - D47
reviews:
  - round: 1
    date: 2026-05-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 19
      added: 1421
      removed: 27
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
  - date: 2026-05-30
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended

      REASON: The ticket's acceptance item 3 mandates inserting a new step 3.5
      (GroupApprovalCheck → GroupApprovalService) between step 3 (registered-user
      check) and step 4 (ban check) in InboundRouter.onMessage, but this change
      breaks pre-existing tests that are NOT authorized for modification in
      out_of_scope, Notes, or test_plan.modifies. The plan-writer named 4 tests
      (InboundRouterChatModeIT, GroupLifecycleIT, InboundRouterIntakeOrderingTest,
      InboundRouterProbationOrderingTest); a follow-up ground-truth pass found
      a fifth: DigestRoundtripIT line 199 calls
      `adapter.deliverGroupMention(UPSTREAM_G1, ADMIN_CONTACT, "/retry --digest")`
      and its seedGroup at lines 321-332 INSERTs without `approval_status`,
      so the V26 default 'pending' triggers the same step-3.5 short-circuit.
      The ticket's `files_budget: 12` and `files_scope` enumeration of 11 paths
      cannot absorb the 5 collateral test modifications plus a top-level
      NoopGroupApprovalCheck fake (corpus pattern per NoopProbationCheck.java).
      "preserves: all tests currently green on main" is unsatisfiable as written.

      SUGGESTED ESCALATION: refine

      EVIDENCE:
        - Ticket acceptance item 3 (InboundRouter step 3.5 wiring mandate).
        - V26 default 'pending' at
          infochat-core/src/main/resources/db/migration/V26__d47_group_authorization.sql
          line 16.
        - seedGroup helpers without approval_status at
          infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
          lines 277-293,
          infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupLifecycleIT.java
          lines 210-224, and
          infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
          lines 321-332.
        - Call-order pins at
          infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
          lines 325-333 and
          infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
          lines 252-263.
        - Top-level Noop fake corpus pattern at
          infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopProbationCheck.java.

      REFINE APPLIED:
        - files_budget 12 → 17 (11 production-side files unchanged + 5 modified
          tests + 1 new NoopGroupApprovalCheck.java fake).
        - test_plan.modifies enumerates the 5 collateral test files.
        - test_plan.adds gains NoopGroupApprovalCheck.java.
        - §Notes "Authorized test changes" block enumerates per-file rationale.
        - risk: medium → risk: high (already flagged by clarity-WARN; the
          InboundRouter step-3.5 wiring is on the authorization-evaluation
          path, which the team's calibration convention treats as risk: high).
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
  - date: 2026-05-30
    reason: outline-fail rework — authorize collateral test modifications + raise risk
    prior_values: |
      files_budget: 12 → 17 (room for 5 modified tests + 1 new fake).
      files_scope: appended 6 entries:
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopGroupApprovalCheck.java (NEW)
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java (MODIFY)
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java (MODIFY)
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java (MODIFY)
        - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupLifecycleIT.java (MODIFY)
        - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java (MODIFY)
      test_plan: gained `modifies:` field listing the 5 collateral tests; `adds:` gained NoopGroupApprovalCheck.java.
      risk: medium → high (clarity-WARN flagged InboundRouter step-3.5 wiring as authorization-path; refine consolidates the calibration with the test-scope expansion).
      §Notes: appended "Authorized test changes" block with per-file rationale.

      Tests verified NOT affected (search-completeness audit, recorded here
      so the next reviewer can confirm the scope is exhaustive):
        - DM-only onMessage callers: InboundRouterContactIdRedactionTest,
          InboundRouterNormalizeTest, InboundRouterConfirmCancelTest.
        - INSERT INTO groups without router invocation: SummaryCacheRepositoryTest,
          DigestSchedulerTest, DigestSchedulerMissedSlotTest (digest scheduler
          path bypasses InboundRouter, not affected by step 3.5).
        - Schema-level: GroupAdminUniqueIndexTest, PerScopeIsolationIT
          (core/schema; no InboundRouter dependency).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-30
    category: PERM-ESCAL
    severity: medium
    promise: |
      docs/spec/security.md §User ban — "Banned-user check is the first thing after
      identity resolution... Banned user receives one fixed reply per inbound message,
      regardless of input." Decision D11 — "Banned users get one fixed reply and never
      reach the parser, the chat agent, or any DB query past the ban check."
    gap: |
      InboundRouter.java:379-409 invokes groupApprovalCheck.check() BEFORE the step-4
      ban check. GroupApprovalCheck.check (GroupApprovalCheck.java:107-126) calls
      GroupApprovalService.evaluate which (GroupApprovalService.java:118-160) executes
      findApprovalRow / countGroupsActivatedBy / countActiveGroups / tryInsertPending
      and emits an admin notification via ThrottledAdminNotifier.notifyOnce — all of
      which §User ban / D11 say a banned user must never reach. The reply a banned
      user receives in group scope is GROUP_PENDING/REJECTED/ACTIVATION_LIMIT/
      GLOBAL_LIMIT rather than the documented fixed ban reply. Spec is internally
      inconsistent (§Authorization model orders 3.5 before 4); diff implements the
      §Authorization model ordering literally.
    repro: |
      (1) Admin /ban U; U remains registered.
      (2) U @-mentions bot in fresh group G (no groups row).
      (3) Inbound chain steps 1.5→1.7→3 pass; step 3.5 fires.
      (4) GroupApprovalService.evaluate INSERTs groups row with activated_by=U.id,
          notifies admin (leaks U's redacted contact id + /approve-group <uuid>).
      (5) U receives GROUP_PENDING (not the ban fixed reply), confirming bot remains
          interactive.
      (6) U repeats up to the per-user activation cap (3 on laptop); the step-4 ban
          check never fires.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-30
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §Secrets handling — "Contact IDs are logged in redacted
      form (prefix + ellipsis + suffix) outside the audit log." Implicit promise that
      admin-facing system notifications are deterministic system-authored text, not
      shaped by attacker-controlled fields (LLM output sanitizer covers the LLM
      surface; admin notifications are downstream of it).
    gap: |
      GroupApprovalService.java:166-173 builds the admin notification message and
      ThrottledAdminNotifier dedup key by raw string concatenation of `adapter`,
      `upstreamGroupId`, and `groupId`. upstream_group_id is adapter-asserted but
      docs/spec/messaging.md §Identity and groups does not constrain its character
      set; newline or colon characters in upstream_group_id can forge a multi-line
      admin notification (e.g. a fake /approve-group <attacker-uuid> line) or
      collapse two distinct group keys onto one throttle bucket and suppress an
      admin notification.
    repro: |
      Attacker triggers a fresh groups row whose upstream_group_id contains
      "real-gid\napprove_command=/approve-group <attacker-uuid>\nlegit_line:" (or
      a colon-bearing value that collides with a sibling throttle key). The admin
      sees a forged /approve-group hint they could copy-paste or a missing
      notification for the colliding sibling group.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-05-30
    verdict: FINDINGS
    base: ad1af6d
    head: f6bfe32
    verdict_file: docs/plan/m1/redteam/M1-112-2026-05-30.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Audit ran post-/m1-tick commit, pre-/m1-tick merge (branch tip f6bfe32 on
      m1/M1-112-group-approval-service, not yet squash-merged to main). Findings:
      one medium PERM-ESCAL (banned users reach step 3.5 / DB writes / admin
      notification before the ban check fires — spec is internally inconsistent
      between §Authorization model and §User ban / D11) and one low INJECTION
      (admin-notification message + dedup key built by raw concatenation of
      upstreamGroupId, character set unconstrained by spec). Out-of-model items:
      spec contradiction surfaced separately; TOCTOU cap overshoot within spec
      tolerance per the Service Javadoc. Recommended dispositions live in the
      verdict file's `disposition:` block. Merge step must verify the canonical
      commit subject is f6bfe32 (the implementation commit) and not this audit
      commit, per the redteam-postcommit-merge-pitfall memory.
clarity_check:
  date: 2026-05-30
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-112.md
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

## Authorized test changes

The step 3.5 wiring (acceptance item 3) inserts a new check between step 3
(registered-user check) and step 4 (ban check) in `InboundRouter.onMessage`.
The following 5 pre-existing tests are authorized for the listed
minimum-viable modifications; no scenario additions, no removed assertions
beyond those the wiring strictly invalidates.

- **`InboundRouterChatModeIT.java`** — `seedGroup(...)` (lines 277-293)
  INSERTs without `approval_status` so V26 default `'pending'` would
  short-circuit the chat-mode tests at step 3.5. Add `approval_status`
  column with value `'approved'` to the INSERT (and the `ON CONFLICT DO
  UPDATE` clause if needed). No test-method changes.
- **`InboundRouterIntakeOrderingTest.java`** — the `registeredGroupSenderDispatchesNormally`
  call-order pin (lines 325-333) must gain a new entry (e.g.
  `"groupApprovalCheck.check"`) between `"lookupUser"` and
  `"banCheck.isBanned"`. The test helper that builds the router must
  also set `router.groupApprovalCheck = new NoopGroupApprovalCheck()`
  (or equivalent recording fake that returns "approved").
- **`InboundRouterProbationOrderingTest.java`** — same pattern as the
  intake-ordering test: pin (lines 252-263) gains a step-3.5 entry; helper
  sets the new field. Probation-gate assertions remain intact.
- **`GroupLifecycleIT.java`** — `seedGroup(...)` (lines 210-224) INSERTs
  without `approval_status`; the lifecycle test exercises
  `deliverGroupMention(...)` ~10 times across auto-promote / admin-gate /
  `/promote` / `/group-timezone` paths, all of which would short-circuit
  at step 3.5. Add `approval_status='approved'` to the INSERT. No
  test-method changes.
- **`DigestRoundtripIT.java`** — `seedGroup` (lines 321-332) INSERTs
  without `approval_status`; line 199 calls
  `adapter.deliverGroupMention(UPSTREAM_G1, ADMIN_CONTACT, "/retry --digest")`
  which would short-circuit at step 3.5. Add `approval_status='approved'`
  to the INSERT for both `GROUP_1` and `GROUP_2`.

### `NoopGroupApprovalCheck` placement

A top-level package-private (or public, if the package boundary requires
it) test fake `NoopGroupApprovalCheck.java` is added in the
`infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/`
package to mirror the existing `NoopProbationCheck.java` corpus pattern.
If the production `GroupApprovalCheck` interface lives in
`provider/group/` and package-private visibility prevents reuse from
`messaging/`, the implementer may relocate the fake to the matching
production package; `files_scope` lists the messaging-package path but
the move is permitted without re-escalation.
