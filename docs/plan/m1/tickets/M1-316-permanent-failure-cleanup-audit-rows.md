---
id: M1-316
title: "Permanent-failure-driven group soft-removal writes a BOT_REMOVED audit row"
status: done
created: 2026-06-12
last_updated: 2026-06-12
clarity_check:
  date: 2026-06-12
  verdict: WARN
  warnings:
    - "Acceptance item 3's verification method ('comparing the written columns against MembershipEventHandler's existing rows') is a manual inspection step, not an automated assertion; consider a named test asserting actor_user_id/scope/action columns match the native-event path."
  blockers: []
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The native membership-event path (MembershipEventHandler) — it already writes BOT_REMOVED correctly; this ticket only closes the gap on the permanent-failure-driven fallback path that the flagship SimpleX adapter relies on.
  - The retry / cap-escalation / threshold logic itself (M1-284, done) — unchanged; this ticket only adds the audit write to the existing group-soft-remove cleanup effect.
  - New AuditAction enum values — BOT_REMOVED already exists (AuditAction.java); no enum or migration change.
  - MEMBER_LEFT / any member-attributed cleanup. The delivery-failure layer (OutboundDelivery, M1-284) attributes permanent failures only per-group (deliverToGroup → onPermanentGroupFailure(groupId) → markRemoved); it never receives a member/user id, and markMemberRemoved (the group_membership soft-clear) is called only by the native-event path (MembershipEventHandler). There is no permanent member-attributed failure path to audit, so no MEMBER_LEFT row is in scope. (This dropped the original acceptance item 2 — see escalations[0] / revisions[0].)
acceptance:
  - "When repeated permanent group-send failures cross the threshold and OutboundDelivery soft-removes the group (groups.removed_at = NOW()), a BOT_REMOVED audit row (system actor: actor_user_id NULL, actor_contact_id NULL, scope = the group) is written in the SAME transaction as the removed_at mutation, audit-before-effect per Invariant 7 (the MembershipEventHandler.handleBotRemoved / writeAudit pattern). A named test crossing the threshold asserts the BOT_REMOVED row exists; a named test asserts an audit-write failure rolls the removed_at mutation back (no orphan removal without an audit row)."
  - "The system-path BOT_REMOVED row matches the column shape MembershipEventHandler writes for the native BotRemoved event (action = BOT_REMOVED, actor_user_id NULL, actor_contact_id NULL, actor_adapter = the failing channel/adapter, target_kind = 'group', target_id = the group id, scope_id = the group id), so /audit and audit_log_view render system-initiated and native-event bot-removals identically. A named test asserts these column values on the written row (not a manual inspection)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 494
      removed: 63
  - round: 2
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 426
      removed: 67
escalations:
  - date: 2026-06-12
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (premise-fail surfaced during start, before implementation).
      Acceptance item 2 assumes a "permanent member-attributed failure" path
      in the delivery layer that soft-clears a group_membership row. No such
      path exists: OutboundDelivery (M1-284) attributes permanent failures
      only to a groupId (deliverToGroup → onPermanentGroupFailure → markRemoved);
      it never takes a member/user id. markMemberRemoved (the membership
      soft-clear) is called ONLY by MembershipEventHandler (the native-event
      path, this ticket's out_of_scope). The M1-284 commit message asserts a
      member soft-clear, but its OutboundDelivery diff added none; the redteam
      finding that spawned this ticket (M1-284-2026-06-12.md §"System-initiated
      group soft-removal writes no audit row") is scoped to group soft-removal
      (BOT_REMOVED) only. Acceptance item 1 (BOT_REMOVED) is real and runnable.
      Resolved via refine: ticket narrowed to BOT_REMOVED only.
  - date: 2026-06-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (scope-breach surfaced during implementation seam selection, then
      retired by a seam change — no scope expansion in the end).
      A 2-arg @Inject GroupRepository(DataSource, AuditLogWriter) constructor
      was first chosen for the atomic BOT_REMOVED write. On a fuller call-site
      sweep that constructor-arity change rippled to FIVE sites across FOUR test
      files (new GroupRepository + four `extends GroupRepository` subclasses),
      two of them out of scope (DigestWorkerTest/digest, StopCommandHandlerTest/
      command), exceeding files_budget. The seam was therefore switched to
      FIELD injection of AuditLogWriter into GroupRepository (the 1-arg
      constructor and every `super(dataSource)` stay unchanged) — chosen on
      security merit (it always uses the real CDI redacting writer; no
      non-redacting default-writer constructor) and zero ripple, NOT to fit a
      budget. The earlier files_scope widen (DigestWorkerTest) was reverted;
      final diff stays within the original messaging+group scope. The cleanest
      end-state — one shared GroupRemovalService used by BOTH the native and
      failure bot-removed paths — is deferred to follow-up M1-317 (an ~18-file
      cross-cutting refactor; per CLAUDE.md better-alternatives-as-tickets).
      See revisions[1].
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-06-12
    reason: "refine after premise-fail — narrowed scope to the real BOT_REMOVED group-soft-remove gap; dropped MEMBER_LEFT (acceptance item 2) because no member-attributed permanent-failure path exists in the delivery layer."
    snapshot:
      title: "Permanent-failure-driven group/membership cleanup writes BOT_REMOVED / MEMBER_LEFT audit rows"
      files_budget: 6
      acceptance:
        - "When repeated permanent group-send failures cross the threshold and OutboundDelivery soft-removes the group (groups.removed_at = NOW()), a BOT_REMOVED audit row (system actor: actor_user_id NULL, scope = the group) is written in the SAME transaction as the removed_at mutation, audit-before-effect per Invariant 7 (the MembershipEventHandler.writeAudit / BanCommandHandler pattern). A named test crossing the threshold asserts the BOT_REMOVED row exists; a named test asserts an audit-write failure rolls the removed_at mutation back (no orphan removal without an audit row)."
        - "[DROPPED — false premise] When a permanent member-attributed failure soft-clears the group_membership row (removed_at = NOW()), a MEMBER_LEFT audit row (actor = the departing member's user_id + contact_id, scope = the group) is written in the same transaction as the soft-clear. A named test covers it, including the group-admin case (is_group_admin cleared + MEMBER_LEFT row in one tx)."
        - "[NARROWED to BOT_REMOVED only] The audit rows match the shape MembershipEventHandler already writes for the native-event path (same AuditAction, same actor/scope columns), so /audit and audit_log_view render system-initiated and native-event removals identically. Verified by comparing the written columns against MembershipEventHandler's existing rows."
        - "mvn -B clean verify from the repo root exits 0."
  - date: 2026-06-12
    reason: "widen files_scope after budget-breach (escalations[1]) — THEN REVERTED. The 2-arg-constructor seam (which would have required adding DigestWorkerTest, and on a fuller sweep StopCommandHandlerTest too) was abandoned for field injection, which touches no out-of-scope call sites. files_scope was restored to the original 4-entry messaging+group form snapshotted here. Net: no scope change."
    snapshot:
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDelivery.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/group
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
        - infochat-provider/src/test/java/app/zcat/infochat/provider/group
redteam_findings: []
redteam_audits:
  - date: 2026-06-12
    verdict: CLEAN
    base: 1ad5939249f1bd17ff4d028708d11ff8b792f058
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-316-2026-06-12.md
    out_of_model_count: 1
    note: |
      In-progress audit (status in-review, --in-progress) of the
      permanent-failure group-cleanup audit-row implementation. No findings
      against the threat model; the audit-row writes route through the existing
      AuditLogWriter seam and match the native-event removal shape. One
      out-of-model observation recorded (advisory only); no remediation needed.
---

# M1-316: Permanent-failure-driven group soft-removal writes a BOT_REMOVED audit row

## Context

M1-284 (done, commit 2e9ca987) added the permanent-failure-driven cleanup
that soft-removes a group (`groups.removed_at`) after repeated permanent
group-send failures cross a profile-driven threshold. On the flagship
SimpleX adapter (`supportsMembershipEvents=false`) this is the **only**
bot-removed cleanup path — there is no native membership event to fall back
on.

The design defines bot-removal as an auditable system-actor event
(`docs/design/02-schema.md`): `BOT_REMOVED` ("Bot removed from group
(system actor)", scope `group`). The **native** membership-event path
already writes it — `MembershipEventHandler.handleBotRemoved` calls
`writeAudit(conn, AuditAction.BOT_REMOVED, null, null, adapter, "group",
groupId, groupId, null)` then `groupRepository.markRemoved(conn, groupId)`,
audit-before-mutation in one transaction (Invariant 7), with tests asserting
the row exists.

But M1-284's permanent-failure path
(`OutboundDelivery.onPermanentGroupFailure` →
`GroupRepository.markRemoved(groupId)`) sets `removed_at` **without** writing
the corresponding audit row. So on the flagship adapter, system-initiated
bot-removals are **unaudited** — inconsistent with both the design and the
native-event path. An operator reviewing why a group stopped receiving
digests has only a WARN log line, not an audit trail. Surfaced as an
out-of-model note in the M1-284 redteam audit
(`docs/plan/m1/redteam/M1-284-2026-06-12.md` §"System-initiated group
soft-removal writes no audit row") and verified real against source
2026-06-12.

**Scope note (refine, premise-fail).** The original ticket also asked for a
`MEMBER_LEFT` audit row on a "permanent member-attributed failure" that
soft-clears a `group_membership` row. No such path exists in the
delivery-failure layer: `OutboundDelivery` attributes permanent failures
only per-group, never per-member, and `markMemberRemoved` (the membership
soft-clear) is reached only from `MembershipEventHandler` (the native-event
path, out of scope here). Building a member-attributed permanent-failure
mechanism would contradict this ticket's out-of-scope (M1-284's
retry/threshold logic stays unchanged). That acceptance item was therefore
dropped on refine; see frontmatter `escalations[0]` / `revisions[0]`.

## Approach (chosen seam)

`AuditLogWriter` (infochat-core) is the existing audit-write API; the
constraint is audit-before-effect in the SAME transaction (Invariant 7): an
audit-write failure must roll the `removed_at` mutation back.

`GroupRepository` gains `markRemovedAudited(UUID groupId, String adapter)`,
which opens its own connection and, in one transaction, writes the
`BOT_REMOVED` row via `AuditLogWriter` then calls the existing
`markRemoved(Connection, UUID)` (rollback + sanitized failure on error).
`OutboundDelivery.onPermanentGroupFailure` calls it instead of the bare
`markRemoved(groupId)`, threading the failing `channel` (`adapter.name()`)
through as `actor_adapter`. The row mirrors the columns
`MembershipEventHandler.handleBotRemoved` writes — `actor_user_id` /
`actor_contact_id` NULL, `actor_adapter` = the failing channel,
`target_kind = "group"`, `target_id` / `scope_id` = the group id — so the two
paths are indistinguishable downstream.

`AuditLogWriter` reaches `GroupRepository` by **field injection** (not a
constructor-arity change), chosen so the existing 1-arg constructor and every
`super(dataSource)` test double stay untouched (a 2-arg constructor rippled to
five call sites across four test files, two out of scope), and so production
always uses the real CDI redacting writer — no non-redacting default-writer
constructor on a security-relevant path. The recording-double unit tests
(`OutboundDeliveryTest`) keep observing the soft-remove as a `GroupRepository`
method call.

**Better end-state, deferred to M1-317.** The native path
(`MembershipEventHandler.handleBotRemoved`) implements the identical audited
soft-remove. The cleanest design factors it into one shared
`GroupRemovalService` both paths call (single source of truth → guaranteed
identical audit rows). That is an ~18-file cross-cutting refactor
(`OutboundDelivery`'s collaborator is built at 13 sites, plus the native path
and their tests), so per CLAUDE.md §"Better alternatives surface as proposals,
not scope expansion" it is filed as follow-up **M1-317** rather than expanded
into this ticket. This ticket accepts one bounded, test-pinned duplication of
the bot-removed transaction until M1-317 lands.

## Out-of-scope

See frontmatter — native-event path (already correct), M1-284's
retry/threshold logic (unchanged), any AuditAction/migration change (the
enum value already exists), and MEMBER_LEFT / any member-attributed cleanup
(no such failure path exists; dropped on refine).

## Round 1 rework

Reviewer round 1: REWORK, 1 item (SCOPE-DRIFT-CHECK: FAIL). All other checks
PASS; acceptance fully met; `mvn verify` green.

- The follow-up ticket file `M1-317-shared-group-removal-service.md` was bundled
  into this implementation diff but is outside `files_scope` and is not a
  lifecycle-exempt path. Per CLAUDE.md §"Commit prefixes" a new ticket file is a
  pure-doc artifact: removed from this branch and landed as a separate `process:`
  commit on `main`. The M1-317 STATUS.md rows go with that commit's regen, not
  this branch. (M1-316 prose may still reference the M1-317 ID — only the file
  was out of scope, not the reference.)
