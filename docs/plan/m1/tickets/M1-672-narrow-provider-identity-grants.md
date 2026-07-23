---
id: M1-672
title: "Narrow Provider grants on identity/authz tables"
status: pending
created: 2026-07-22
last_updated: 2026-07-23
blocked_by: []
files_budget: 26
files_scope:
  - infochat-core/src/main/resources/db/migration/V62__provider_identity_grants.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/**
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/**
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/**
  - infochat-provider/src/main/java/app/zcat/infochat/provider/startup/AdminBootstrap.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/MembershipEventHandlerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/db/ProviderIdentityGrantsIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java
  - docs/spec/security.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The Collector role's grants. The audit found the Collector's grant set
    already least-privilege (V31 column-scoped source UPDATE, no identity-
    table writes needed beyond its pipeline); this ticket narrows only the
    infochat_provider role. Widening the review to a full re-derivation of
    every role's grants is a separate exercise.
  - >-
    Any change to the SECURITY DEFINER procedures that already exist
    (delete_preban_user, approve_quarantine, reject_quarantine — V24/V25/V50).
    They are the pattern to copy, not a surface to modify; callers of those
    procs stay untouched.
  - >-
    Behavioral changes to any command's user-visible replies, gates, or
    audit rows. The refactor changes HOW privilege-escalating writes reach
    the DB, not WHAT the commands do; every existing handler test must pass
    unmodified except the two provider test files named in acceptance item
    5 and test_plan.modifies (GroupMembershipRepositoryTest.java,
    MembershipEventHandlerTest.java), whose authorized edits are limited to
    re-pinning fixture group-admin promote/demote calls to the owner seed
    seam or to procedure CALLs — no assertion about command behavior
    changes (engineering-rules §8).
acceptance:
  - >-
    A new migration V62 revokes the table-level UPDATE/INSERT grants V5
    installed on users, groups, group_membership, invite_code for
    infochat_provider and installs the narrow shape: plain column-scoped
    UPDATE only on the non-privilege columns the Provider legitimately
    writes (users.probation_until; users.save_count — required because
    V15.trg_saved_post_count is SECURITY INVOKER and updates it on
    /save and /unsave; groups.timezone / digest_enabled / removed_at;
    groups.member_count is NOT a column — it is a computed COUNT in
    GroupRepository.LIST_GROUPS_PAGE:106 — so it is not grantable), with
    every privilege-escalating transition (users.is_admin, users.is_banned,
    users.registration_state, groups.approval_status,
    group_membership.is_group_admin, invite_code mint/revoke/consume)
    routed through new SECURITY DEFINER procedures carrying the V50
    live-admin actor-check shape. Row locking currently expressed as
    SELECT ... FOR UPDATE on invite_code in Provider code
    (BanCommandHandler.java:106-110, InviteCommandHandler.java:152-153)
    moves inside the corresponding procedures, because revoking the
    table-level UPDATE also revokes plain row-lock reads.
  - >-
    A new integration test (collector db test cluster, alongside
    DbRoleMatrixIT) proves the weak role can no longer reach the privilege
    columns: connecting as infochat_provider, a direct
    `UPDATE users SET is_admin = TRUE` (and the same shape for is_banned,
    registration_state, groups.approval_status,
    group_membership.is_group_admin, and a raw INSERT INTO invite_code)
    fails with insufficient_privilege. The same test proves the previously
    legitimate non-privilege writes still succeed.
  - >-
    A new procedure-level test proves each new SECURITY DEFINER procedure
    resolves its actor from the infochat.actor_id GUC via
    current_setting('infochat.actor_id', TRUE) — a deliberate departure
    from the V50 procedures, which take p_actor_id as a parameter (the
    GUC shape is what makes refusal-when-unset testable): each procedure
    refuses a caller whose GUC names a non-admin or is unset, and accepts
    a live bot admin (is_admin = TRUE AND is_banned = FALSE, checked
    against the DB at call time, per the V50 shape), with the audit row
    written inside the same transaction as the mutation.
  - >-
    Every command/startup path that performs a privilege-escalating write
    today (the 18-file census below) is routed through the new procedures;
    a grep over infochat-provider main source for direct
    `UPDATE users SET is_admin|is_banned|registration_state`,
    `UPDATE groups SET approval_status`,
    `UPDATE group_membership SET is_group_admin`, and
    `INSERT INTO invite_code` returns no hit outside the procedure call
    sites.
  - >-
    All pre-existing handler, messaging, group, and startup tests pass
    unmodified, with exactly two named exceptions (the only existing-test
    edits this ticket authorizes, per engineering-rules §8):
    infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java
    and
    infochat-provider/src/test/java/app/zcat/infochat/provider/group/MembershipEventHandlerTest.java,
    whose fixture promote/demote call sites
    (GroupMembershipRepository.promoteToAdmin/demoteAdmin — 7 call sites
    across 7 tests) execute the revoked write as the weak role via the
    default datasource and must be re-pinned to the owner @SeedDataSource
    seed seam the provider test cluster already uses
    (MembershipEventHandlerTest.java:39) or to procedure CALLs with a
    seeded live admin plus the GUC set. mvn verify is green.
  - >-
    docs/spec/security.md §DB roles records the narrowed grant shape and
    names the procedure-mediated write model for privilege-escalating
    transitions, closing the inconsistency the audit flagged (the V31
    source-table rationale applied to the identity tables). It also
    records the inherent bootstrap residual: the AdminBootstrap conduit
    procedure carries no intrinsic DB-side gate (any caller holding
    EXECUTE as infochat_provider can mint the first admin), since the
    role must be able to call it at every startup.
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db/ProviderIdentityGrantsIT.java
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/MembershipEventHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §DB roles
decision_refs:
  - D34
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-672: Narrow Provider grants on identity/authz tables

## Context

The 2026-07-22 full-repo security audit (`.scratch/kimi-audit.md`, finding
CORE-1) verified that the `infochat_provider` role holds table-level
`GRANT SELECT, INSERT, UPDATE ON users|groups|group_membership|invite_code`
from `infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:391-400`,
never narrowed by any later migration (the full GRANT/REVOKE matrix V1–V61
was checked). A SQL-injection foothold in the Provider would therefore mint
a bot admin (`UPDATE users SET is_admin = TRUE`), unban, force
`registration_state = 'vouched'`, flip `groups.approval_status` past the D47
gate, or grant group-admin — with no trigger guarding any of those
transitions (the last-admin trigger covers admin *removal* only; the V24
ban-self and audit-actor checks read the `infochat.actor_id` GUC, which a
raw-SQL attacker simply does not set). The repo already treats this exact
scenario as in-model elsewhere: V31 column-scoped the `source` UPDATE so a
foothold "cannot repoint an existing trusted source", V50 added SECURITY
DEFINER actor checks "for an attacker who can call the procedures but not
bypass them", and V10 keeps `original_html` unreachable "even with the
worst-case SQL injection". The identity tables, where the payoff is full
compromise, never received the same treatment. The module-6 audit premise
check found **no injectable Provider SQL** (all 153 provider files read;
every statement constant or bound), so the finding is HIGH conditional, not
CRITICAL — this ticket removes the condition.

## Census

**Class of defect:** every Provider write path that performs a
privilege-escalating mutation on the four identity tables must be
re-routed through the new procedures; every non-privilege write must keep
working on the narrowed grants. Enumeration (re-run at start):

    grep -rlnE "UPDATE users|INSERT INTO users|UPDATE groups|INSERT INTO groups|UPDATE group_membership|INSERT INTO group_membership|UPDATE invite_code|INSERT INTO invite_code" infochat-provider/src/main/java | sort

| Site | Disposition |
|---|---|
| `command/BanCommandHandler.java` | route is_banned write via proc |
| `command/UnbanCommandHandler.java` | route is_banned write via proc (the census's "group-admin restore" is a read-only listing — `selectGroupAdminMemberships` at :233 — no write to route) |
| `command/GrantAdminCommandHandler.java` | route is_admin write via proc |
| `command/RevokeAdminCommandHandler.java` | route is_admin write via proc |
| `command/PromoteCommandHandler.java` | route is_group_admin write via proc |
| `command/DemoteCommandHandler.java` | route is_group_admin write via proc |
| `command/VouchCommandHandler.java` | non-privilege (D47: `/vouch` clears `probation_until` only, never `registration_state`) — keep plain UPDATE |
| `command/InviteCommandHandler.java` | route invite_code mint/revoke via procs |
| `command/DigestCommandHandler.java` | non-privilege (digest_enabled) — keep plain UPDATE on narrowed grant |
| `command/GroupTimezoneCommandHandler.java` | non-privilege (timezone) — keep plain UPDATE |
| `group/GroupAutoPromoteService.java` | route is_group_admin write via proc |
| `group/GroupMembershipRepository.java` | split: privilege writes via proc, membership rows plain. The `PROMOTE`/`DEMOTE` constants (:30-36) have zero production callers (Promote/Demote handlers and GroupAutoPromoteService carry their own SQL) — their only callers are the two test files named in acceptance item 5; delete the constants or convert them to proc CALLs as part of the test re-pin |
| `group/GroupRepository.java` | split: approval_status via proc; removed_at/member_count plain |
| `messaging/InboundRouter.java` | non-privilege — its only matching write is a plain `INSERT INTO group_membership` (no `is_group_admin` column, same shape as `GroupMembershipRepository`'s membership-row half); it performs no direct privilege-column write and delegates all privilege-classed mutation to `InviteCodeConsumer`/`SimpleXAdminClaim`/`GroupAutoPromoteService`/`ProbationCheck` (each separately census'd) — no proc routing needed in this file |
| `messaging/InviteCodeConsumer.java` | route invite consume + registration_state via procs |
| `messaging/ProbationCheck.java` | non-privilege (probation_until) — keep plain UPDATE |
| `messaging/SimpleXAdminClaim.java` | route is_admin claim via proc (claim token = the actor proof) |
| `startup/AdminBootstrap.java` | route bootstrap ensure via proc (system actor, V50 live-admin check exempt per its documented system-actor arm) |

## Acceptance

See the frontmatter. The weak role loses direct write access to every
privilege-escalating column; the new SECURITY DEFINER procedures enforce
live-admin actor checks; every legitimate command behavior is preserved
with its tests unmodified; the grant-matrix IT pins the new shape; the
spec's §DB roles records it.

## Out-of-scope

The Collector role, the pre-existing V24/V25/V50 procedures, and any
user-visible behavior change. See the frontmatter for the full list. The
existing tests expected to need modification are named explicitly: the
grant-matrix assertion cluster (`collector/db/*IT`) — those assertions pin
today's over-wide grants and must be re-pinned to the narrow shape — plus
the two provider test files named in acceptance item 5 and
`test_plan.modifies`, whose fixture group-admin promotes execute the
revoked write as the weak role. Every other existing test must pass
byte-identical.

## Notes

- The V50 procedures (`approve_quarantine`, `reject_quarantine`,
  `delete_preban_user`) are the shape to copy for SECURITY DEFINER, the
  live `is_admin = TRUE AND is_banned = FALSE` DB check, and the
  same-transaction audit row — with one deliberate departure: they take
  `p_actor_id` as a parameter (only the V24/V35 triggers read the
  `infochat.actor_id` GUC today), while the NEW procedures resolve the
  actor from the GUC via `current_setting('infochat.actor_id', TRUE)` so
  refusal-when-unset is testable (acceptance item 3). Every caller of the
  new procs must therefore issue
  `SELECT set_config('infochat.actor_id', ?, true)` in the same
  transaction first: the handlers that already set the GUC for the V24
  triggers (Ban/Unban/GrantAdmin/RevokeAdmin/Vouch) reuse theirs, while
  Promote/Demote/Invite and the group/messaging/startup sites must add
  it. Sites without a human actor (AdminBootstrap, SimpleXAdminClaim,
  InviteCodeConsumer — its only proof is the invite-code match itself,
  not an admin actor) need the same documented system-actor handling the
  existing procs/tests already model.
- AdminBootstrap's conduit procedure has no intrinsic DB-side gate — any
  caller holding EXECUTE as `infochat_provider` can mint the first admin.
  That residual is inherent to a bootstrap path the role must reach at
  every startup; docs/spec/security.md §DB roles records it explicitly
  (acceptance item 6).
- Column-scoped `GRANT UPDATE (col, ...)` is the correct narrowing
  mechanism for the non-privilege columns (the V31 precedent on `source`).
- Finding detail, falsification history, and threat-model reasoning: the
  audit report (`kimi-audit.md` under `.scratch/`) §CORE-1 (module 1) and
  the module-6 CORE-1-premise FINAL verdict (no injectable Provider SQL —
  the grant narrowing is defense-in-depth, not an active-breach fix).

## OUTLINE FAILED — 2026-07-23 (resolved by refine, 2026-07-23)

> Plan-writer gate agent (fresh-context, kimi coder binding per
> `docs/process/harness-mapping.md` §2) returned `OUTLINE FAILED` during
> the planning phase of `/m1-tick start`. Verbatim block below, kept as
> history; claims describe the pre-refine ticket. The refine applied both
> recommendations: (a) `GroupMembershipRepositoryTest.java` and
> `MembershipEventHandlerTest.java` added to `test_plan.modifies`,
> `files_scope`, acceptance item 5, and §Out-of-scope (re-pin fixture
> promotes to the owner `@SeedDataSource` seam or proc CALLs); (b) the
> ground-truth corrections folded in — new procs resolve the actor from
> the GUC (V50 procs take `p_actor_id` as a parameter), `member_count`
> dropped from the grant list (computed, not a column), `users.save_count`
> added (V15 trigger is SECURITY INVOKER), UnbanCommandHandler census row
> corrected (restore listing is read-only), invite_code FOR UPDATE row
> locking moved inside the procs, set_config requirement pinned for
> Promote/Demote/Invite, and the AdminBootstrap conduit residual assigned
> to the spec amendment. Every claim was re-verified against the code
> before being written into the ticket.

REASON: Test-modification authorization missing — escalate via /m1-tick
escalate refine. Acceptance #4 requires that a grep over
`infochat-provider` main source for direct
`UPDATE group_membership SET is_group_admin` return no hit outside
procedure call sites, which forces
`GroupMembershipRepository.PROMOTE`/`DEMOTE`
(`GroupMembershipRepository.java:31-36` — main-source constants with zero
production callers) to be deleted or converted to SECURITY DEFINER proc
CALLs. Under either path, seven call sites in two pre-existing provider
test files execute the revoked write through the CDI bean on the default
datasource, which connects as the weak `infochat_provider` role even under
`%test` (`infochat-provider/src/main/resources/application.properties:45`;
only `%test.quarkus.datasource.owner` is the owner seam). Every call site
was ground-truthed: (1) `GroupMembershipRepositoryTest` — 5 of its 7
`@Test`s break (`isGroupAdmin_returnsTrueForAdminRow` :84,
`promoteToAdmin_setsFlag` :98, `demoteAdmin_clearsFlag` :106-107,
`partialUniqueIndex_rejectsSecondAdmin` :115-116,
`markMemberRemoved_triggersAdminFlagClear` :123): raw write → 42501 after
V62, proc CALL → refused (no `infochat.actor_id` GUC set, no live admin
seeded); (2) `MembershipEventHandlerTest` — 2 tests break
(`userLeft_auditRecordsWasGroupAdmin` :103,
`userLeft_auditWriteFailureRollsBackMutation` :185, both via the
CDI-injected repository). Yet `test_plan.modifies` names only
`DbRoleMatrixIT.java`, and ticket §Out-of-scope asserts "every other
existing test must pass byte-identical" while acceptance #5 demands "all
pre-existing … group … tests pass unmodified". No implementation satisfies
acceptance #4 + #5 simultaneously without editing those two test files,
and the ticket authorizes neither edit — so no implementable outline
exists as written.

SUGGESTED ESCALATION: refine

EVIDENCE: Ticket acceptance #4/#5 and `out_of_scope` item 3 (frontmatter
lines 35-40, 67-78) vs.
`infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupMembershipRepository.java:30-36,118-142`,
`infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java:27-29,84-131`,
`infochat-provider/src/test/java/app/zcat/infochat/provider/group/MembershipEventHandlerTest.java:39-41,100-114,182-193`.
The refine should (a) add both test files to `test_plan.modifies` and
§Out-of-scope (re-pin fixture promotes to the owner `@SeedDataSource`
seam — the pattern `MembershipEventHandlerTest:300-306` already uses — or
to proc CALLs with a seeded live admin + GUC), and (b) fold in these
ground-truth corrections found during the audit: Notes claim "actor
resolution from the `infochat.actor_id` GUC" does not match the actual V50
procs (they take `p_actor_id` as a parameter; the GUC is read only by the
V24 triggers) — pin explicitly that the NEW procs resolve the actor from
the GUC, since acceptance #3's "(or is unset)" refusal is untestable
otherwise; `groups.member_count` (acceptance #1) is not a column — it's a
computed COUNT in `GroupRepository.LIST_GROUPS_PAGE:106`; the census's
UnbanCommandHandler "group-admin restore" write does not exist (read-only
listing at `UnbanCommandHandler.java:233`); `V15.trg_saved_post_count` is
SECURITY INVOKER, so `users.save_count` must be in the column-scoped grant
or `/save`//`/unsave` break; `SELECT ... FOR UPDATE` on `invite_code`
(`BanCommandHandler.java:106-110`, `InviteCommandHandler.java:152-153`)
dies with the full UPDATE revoke — row locking must move inside the
ban/revoke procs; Promote/Demote/Invite handlers never set
`infochat.actor_id` today and must add `set_config` for GUC-resolving
procs; AdminBootstrap's conduit proc has no intrinsic DB-side gate (any
caller can mint an admin) — inherent residual the spec amendment should
record.
