---
id: M1-672
title: "Narrow Provider grants on identity/authz tables"
status: done
created: 2026-07-22
last_updated: 2026-07-24
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
    9 and test_plan.modifies (GroupMembershipRepositoryTest.java,
    MembershipEventHandlerTest.java), whose authorized edits are limited to
    re-pinning fixture group-admin promote/demote calls to the owner seed
    seam or to procedure CALLs — no assertion about command behavior
    changes (engineering-rules §8).
acceptance:
  - >-
    A new migration V62 revokes the table-level INSERT and UPDATE grants
    V5 installed on users, groups, group_membership, invite_code for
    infochat_provider (SELECT is retained on all four) and installs the
    narrow shape below. Column-scoped UPDATE on exactly the non-privilege
    columns the Provider legitimately writes — the complete inventory,
    re-derived from every UPDATE statement in infochat-provider main
    source: users.probation_until (VouchCommandHandler.java:122,
    ProbationCheck.java:55) and users.save_count (no Java writer — required
    because V15.trg_saved_post_count is SECURITY INVOKER and updates it
    under the caller's role on /save and /unsave); groups.timezone
    (GroupTimezoneCommandHandler.java:55), groups.digest_enabled
    (DigestCommandHandler.java:53), groups.removed_at
    (GroupRepository.java:42,45); group_membership.removed_at
    (GroupMembershipRepository.java:38-40, the user-left path pinned by
    MembershipEventHandlerTest.userLeft_marksGroupMemberRemoved).
    groups.member_count is NOT a column — it is a computed COUNT in
    GroupRepository.LIST_GROUPS_PAGE:106 — so it is not grantable.
    Column-scoped INSERT on exactly: groups (adapter, upstream_group_id,
    activated_by) and group_membership (group_id, user_id). NO INSERT
    re-grant on users — all four users-INSERT sites set a privilege column
    at insert time (BanCommandHandler.java:115-117 preban,
    SimpleXAdminClaim.java:131-135, InviteCodeConsumer.java:107-110,
    AdminBootstrap.java:105-109) and therefore route through procedures —
    and none on invite_code, whose only INSERT is the mint
    (InviteCommandHandler.java:116-119), also proc-routed.
    GroupRepository.INSERT_PENDING_RETURNING (:52-55) drops
    approval_status from its explicit column list: V26 declares the column
    NOT NULL DEFAULT 'pending', so omitting it is behaviour-neutral and
    keeps approval_status ungrantable.
  - >-
    Every privilege-escalating transition (users.is_admin, users.is_banned
    with its banned_at/banned_by/ban_reason metadata,
    users.registration_state, groups.approval_status,
    group_membership.is_group_admin, invite_code mint/revoke/consume) is
    routed through new SECURITY DEFINER procedures whose actor model is
    pinned by acceptance items 4-6. Row locking currently expressed as
    SELECT ... FOR UPDATE on invite_code in Provider code
    (BanCommandHandler.java:106-110, InviteCommandHandler.java:152-153)
    moves inside the corresponding procedures, because revoking the
    table-level UPDATE also revokes plain row-lock reads.
  - >-
    A new integration test (ProviderIdentityGrantsIT, collector db test
    cluster, alongside DbRoleMatrixIT) proves the weak role can no longer
    reach the privilege columns: connecting as infochat_provider, a direct
    `UPDATE users SET is_admin = TRUE` (and the same shape for is_banned,
    registration_state, groups.approval_status,
    group_membership.is_group_admin, plus a raw INSERT INTO invite_code
    and a raw INSERT INTO users) fails with insufficient_privilege. The
    same test proves every retained write still succeeds: the six
    column-scoped UPDATEs and the two column-scoped INSERTs enumerated in
    acceptance item 1. All new grant pins live in this new file only — no
    existing grant-matrix IT asserts anything about the four identity
    tables (DbRoleMatrixIT carries 2 tests: role LOGIN attributes and
    price_snapshot; DbGrantsRevocationIT and AdminRoleGrantsIT likewise),
    so none is expected to need an edit. DbRoleMatrixIT stays in
    files_scope as a standing authorization in case a narrowed grant trips
    an assertion this audit did not foresee; touching it is permitted, not
    required.
  - >-
    The new procedures fall into two classes, and the split is pinned
    here because a uniform actor gate is not implementable (round-2
    outline-fail). ADMIN-GATED procedures — the ones backing
    /ban, /unban, /grant-admin, /revoke-admin, /promote, /demote,
    /invite mint+revoke, and the groups.approval_status transition —
    resolve their actor from the infochat.actor_id GUC via
    current_setting('infochat.actor_id', TRUE), a deliberate departure
    from the V50 procedures which take p_actor_id as a parameter (the GUC
    shape is what makes refusal-when-unset testable). SYSTEM-ACTOR
    procedures — AdminBootstrap (runs at every boot, so a refuse-on-unset
    gate would fail provider startup and the whole test cluster),
    SimpleXAdminClaim (the claim token is the proof), InviteCodeConsumer
    (the invite-code match is the proof), and GroupAutoPromoteService
    (whose actor is BY DEFINITION a non-admin — D47 first-mention
    auto-promote writes the promoted user as the actor,
    GroupAutoPromoteService.java:120-131) — carry NO admin gate. The
    in-repo precedent for that arm is V24's trg_audit_log_actor_check
    (V24__identity_audit_remediation.sql:94-115), whose comments document
    the GUC-unset and NULL-actor allowances; it is NOT V50, which has no
    system-actor arm.
  - >-
    The admin-gated procedures check `is_admin = TRUE` against the DB at
    call time — deliberately NOT the V50 live-admin conjunction
    (`is_admin = TRUE AND is_banned = FALSE`). Rationale, and the reason
    this is pinned as an acceptance criterion rather than left to the
    implementer: each of these handlers gates on is_admin ALONE today, and
    the V5/V24 last-admin-protection trigger path is reachable through
    BanCommandHandler and RevokeAdminCommandHandler ONLY with an
    is_admin=TRUE AND is_banned=TRUE actor (with a non-banned actor the
    trigger's `count(is_admin AND NOT is_banned AND id <> NEW.id) >= 1`
    can never raise, and self-ban/self-revoke are guarded earlier — the
    production comment at RevokeAdminCommandHandler.java:282-289 states
    this). Two pre-existing tests pin exactly that path
    (BanCommandHandlerTest.banOfOnlyAdminSurfacesLastAdminError:417-471,
    RevokeAdminCommandHandlerTest.revokeLastAdminTriggerFiresAndRollsBack:232-289).
    A live-admin conjunction would refuse the banned actor BEFORE the
    UPDATE, so IC001 would never surface, both handlers' LAST_ADMIN_SQLSTATE
    catches would fall through to IllegalStateException, and both tests
    would fail — a user-visible behaviour change §Out-of-scope forbids.
    The dropped conjunction costs no defence against the ticket's own
    threat model: an injected foothold that sets the GUC retains SELECT on
    users and can name a non-banned admin's UUID as readily as a banned
    one. Two further constraints on the refusal path: the procedures MUST
    NOT raise SQLSTATE IC001 (last_admin_protection) on an actor-check
    failure — mislabelling an authorization refusal as last-admin
    protection would make the two tests above pass for the wrong reason —
    and they MUST NOT issue COMMIT or ROLLBACK, matching the V50 routines,
    since they run inside the caller's transaction.
  - >-
    A new procedure-level test proves the actor model: each admin-gated
    procedure refuses a caller whose GUC names a non-admin and refuses one
    whose GUC is unset, and accepts a bot admin; the refusal SQLSTATE is
    not IC001; and each system-actor procedure succeeds with the GUC
    unset. Audit rows are NOT written by the procedures: every affected
    caller already pre-writes its audit row through AuditLogWriter +
    RedactionHook (Invariant 7, audit-before-effect), and the CALL joins
    that same transaction, so the existing row and the mutation stay
    atomic. A proc-side audit INSERT would double the rows existing
    handler tests count and would bypass the Java redaction hook.
  - >-
    Every command/startup path that performs a privilege-escalating write
    today (the 18-file census below) is routed through the new procedures;
    a grep over infochat-provider main source for direct
    `UPDATE users SET is_admin|is_banned|registration_state`,
    `UPDATE groups SET approval_status`,
    `UPDATE group_membership SET is_group_admin`,
    `INSERT INTO invite_code`, and `INSERT INTO users` returns no hit
    outside the procedure call sites.
  - >-
    Each replaced statement carries a control-flow signal its procedure
    must reproduce, because callers branch on it:
    AdminBootstrap.ENSURE_ADMIN_SQL:104-110 (RETURNING id gates the
    BOOTSTRAP_ADMIN audit row), SimpleXAdminClaim.CLAIM_ADMIN_SQL:130-137,
    InviteCodeConsumer.CONSUME_INVITE_SQL:98-104 and
    INSERT_USER_SQL:106-110 (both RETURNING id),
    GroupAutoPromoteService.AUTO_PROMOTE_SQL:51-57 (a row count != 1 must
    still yield the false/rollback leg, AND SQLSTATE 23505 from the
    one_admin_per_group partial index must propagate out of the CALL to
    the caller's race-guard), and
    GroupRepository.UPDATE_APPROVAL_STATUS:95-96 (executeUpdate() == 1
    distinguishes a real transition from a no-op).
  - >-
    All pre-existing handler, messaging, group, and startup tests pass
    unmodified, with exactly two named exceptions (the only existing-test
    edits this ticket authorizes, per engineering-rules §8):
    infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java
    and
    infochat-provider/src/test/java/app/zcat/infochat/provider/group/MembershipEventHandlerTest.java,
    whose fixture promote/demote call sites
    (GroupMembershipRepository.promoteToAdmin/demoteAdmin — 9 call sites
    across 7 tests: 7 sites / 5 tests in GroupMembershipRepositoryTest,
    2 sites / 2 tests in MembershipEventHandlerTest) execute the revoked
    write as the weak role via the
    default datasource and must be re-pinned to the owner @SeedDataSource
    seed seam the provider test cluster already uses
    (MembershipEventHandlerTest.java:39) or to procedure CALLs with a
    seeded bot admin plus the GUC set. mvn verify is green.
  - >-
    THE RULE THAT MAKES THAT LIST CLOSED (stated because two consecutive
    planning rounds failed on it): the ONLY legitimate reason a
    pre-existing test needs an edit is that it performs a now-revoked
    write AS THE WEAK ROLE through the default datasource, and the only
    authorized repair is re-pinning that write to the owner
    @SeedDataSource seam or to a procedure CALL — never an assertion
    change. Every OTHER pre-existing test in the four affected packages
    already performs its raw privilege writes through the owner
    @SeedDataSource seam (verified: all 17 provider test files carrying
    raw privilege-column SQL inject @SeedDataSource; the 4 core schema
    tests run on the core cluster's owner connection), so the narrowed
    grants cannot reach them. If a pre-existing test nevertheless fails,
    that is evidence the implementation changed OBSERVABLE BEHAVIOUR —
    the thing this ticket exists not to do. Escalate (premise-fail);
    do NOT edit the test, do NOT widen this list, and do NOT reshape the
    procedure's error handling to make the assertion pass.
  - >-
    docs/spec/security.md §DB roles records the narrowed grant shape and
    names the procedure-mediated write model for privilege-escalating
    transitions, closing the inconsistency the audit flagged (the V31
    source-table rationale applied to the identity tables). It records
    both residuals honestly rather than overstating the control: (a) the
    AdminBootstrap conduit procedure carries no intrinsic DB-side gate
    (any caller holding EXECUTE as infochat_provider can mint the first
    admin), inherent to a bootstrap path the role must reach at every
    startup — and the same is true of the other three system-actor
    procedures; (b) the admin-gated procedures resolve their actor from a
    GUC the calling role sets itself, and check is_admin only, so against
    an attacker who already controls Provider SQL the gate raises the bar
    (they must name some admin's UUID) without being a boundary — the
    real control is that the privilege COLUMNS are no longer directly
    writable.
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db/ProviderIdentityGrantsIT.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/MembershipEventHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §DB roles
decision_refs:
  - D34
reviews:
  - round: 1
    date: 2026-07-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 41
      added: 8629
      removed: 167
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-23
  verdict: PASS
  warnings:
    - >-
      lint-ticket.py clean (0 blockers, 0 warnings). Self-check re-ran the
      census grep live — 18 files, exactly the disposition table. Spot-checks
      of the cited path:line claims all held. Fixed inline (prose, no scope
      change): §Notes claimed the groups.approval_status site "must add"
      set_config, but ApproveGroupCommandHandler:164 and
      RejectGroupCommandHandler:217 already set the GUC — Promote/Demote/Invite
      are the only sites that must add it. Also confirmed /promote and /demote
      gate on BOT admin (PromoteCommandHandler:119, DemoteCommandHandler:101),
      so the acceptance-item-5 is_admin=TRUE proc gate matches today's
      behaviour, and that all 128 provider test files carrying raw
      identity-table write SQL inject @SeedDataSource (acceptance item 10's
      closed-list premise holds).
outline_file: target/m1-tick-outline-M1-672.md
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
| `group/GroupAutoPromoteService.java` | route is_group_admin write via a SYSTEM-ACTOR proc — its actor is by definition a non-admin (D47 first-mention auto-promote writes the promoted user as actor, :120-131), so an admin gate here breaks group activation outright. Preserve both control-flow signals (row count != 1, and 23505 from `one_admin_per_group`) |
| `group/GroupMembershipRepository.java` | split: privilege writes via proc, membership rows plain. The `PROMOTE`/`DEMOTE` constants (:30-36) have zero production callers (Promote/Demote handlers and GroupAutoPromoteService carry their own SQL) — their only callers are the two test files named in acceptance item 9; delete the constants or convert them to proc CALLs as part of the test re-pin |
| `group/GroupRepository.java` | split: approval_status via proc; removed_at/member_count plain |
| `messaging/InboundRouter.java` | non-privilege — its only matching write is a plain `INSERT INTO group_membership` (no `is_group_admin` column, same shape as `GroupMembershipRepository`'s membership-row half); it performs no direct privilege-column write and delegates all privilege-classed mutation to `InviteCodeConsumer`/`SimpleXAdminClaim`/`GroupAutoPromoteService`/`ProbationCheck` (each separately census'd) — no proc routing needed in this file |
| `messaging/InviteCodeConsumer.java` | route invite consume + registration_state via procs |
| `messaging/ProbationCheck.java` | non-privilege (probation_until) — keep plain UPDATE |
| `messaging/SimpleXAdminClaim.java` | route is_admin claim via proc (claim token = the actor proof) |
| `startup/AdminBootstrap.java` | route bootstrap ensure via a SYSTEM-ACTOR proc (no admin gate — it runs at every boot, before any admin need exist). The system-actor precedent is V24's `trg_audit_log_actor_check` (V24:94-115, documented GUC-unset and NULL-actor allowances), NOT V50 — V50 has no system-actor arm, all three of its routines raise unconditionally on a non-admin actor. Preserve the `RETURNING id` signal that gates the BOOTSTRAP_ADMIN audit row |

## Acceptance

See the frontmatter. The weak role loses direct write access to every
privilege-escalating column; the new SECURITY DEFINER procedures enforce
live-admin actor checks; every legitimate command behavior is preserved
with its tests unmodified; the grant-matrix IT pins the new shape; the
spec's §DB roles records it.

## Out-of-scope

The Collector role, the pre-existing V24/V25/V50 procedures, and any
user-visible behavior change. See the frontmatter for the full list. The
existing tests expected to need modification are exactly the two provider
test files named in acceptance item 9 and `test_plan.modifies`, whose
fixture group-admin promotes execute the revoked write as the weak role.
No grant-matrix IT edit is expected: none of `DbRoleMatrixIT`,
`DbGrantsRevocationIT`, or `AdminRoleGrantsIT` asserts anything about
`users` / `groups` / `group_membership` / `invite_code`, so there are no
over-wide grant assertions to re-pin — the new pins live solely in the new
`ProviderIdentityGrantsIT`. Every other existing test must pass
byte-identical; acceptance item 10 states the rule and what to do if one
does not.

## Notes

- The V50 procedures (`approve_quarantine`, `reject_quarantine`,
  `delete_preban_user`) are the shape to copy for SECURITY DEFINER
  structure and for staying inside the caller's transaction (none of the
  three issues COMMIT or ROLLBACK) — but NOT for two things. First, they
  take `p_actor_id` as a parameter (only the V24/V35 triggers read the
  `infochat.actor_id` GUC today), while the NEW procedures resolve the
  actor from the GUC via `current_setting('infochat.actor_id', TRUE)` so
  refusal-when-unset is testable (acceptance items 5-6). Second, their
  `is_admin = TRUE AND is_banned = FALSE` predicate is deliberately NOT
  copied — acceptance item 5 pins `is_admin = TRUE` alone and states why
  (the banned-admin last-admin-trigger path two pre-existing tests pin).
- Every caller of an admin-gated proc must issue
  `SELECT set_config('infochat.actor_id', ?, true)` in the same
  transaction first: the handlers that already set the GUC for the V24
  triggers reuse theirs — Ban (:297), Unban (:229, :314), GrantAdmin
  (:279), RevokeAdmin (:298), Vouch (:227), and BOTH
  `groups.approval_status` callers, ApproveGroup (:164) and RejectGroup
  (:217) — while Promote/Demote/Invite are the only sites that must add
  it. The four system-actor sites (AdminBootstrap, SimpleXAdminClaim,
  InviteCodeConsumer, GroupAutoPromoteService) do not set it and their
  procs must not require it — acceptance item 4 names them and points at
  the V24 `trg_audit_log_actor_check` precedent for that arm.
- The system-actor procedures have no intrinsic DB-side gate — any caller
  holding EXECUTE as `infochat_provider` can invoke them, so a foothold
  can still mint the first admin through the AdminBootstrap conduit or
  claim admin through the SimpleXAdminClaim conduit. That residual is
  inherent: the role must reach these paths without a human actor.
  docs/spec/security.md §DB roles records it explicitly, together with
  the weaker-than-it-looks nature of the GUC-resolved actor check
  (acceptance item 11). What the migration actually buys is that the
  privilege COLUMNS become unwritable by the role directly — the
  procedures are the only remaining route, and each one is a narrow,
  audited, single-purpose transition rather than an arbitrary UPDATE.
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

## OUTLINE FAILED — 2026-07-23 (round 2; resolved by refine, 2026-07-23)

> **Resolution applied.** The blocking pair was resolved via the
> plan-writer's option (b), not (a): the admin-gated procedures now pin
> `is_admin = TRUE` alone instead of the V50 live-admin conjunction, which
> preserves both handlers' existing behaviour exactly and authorizes NO
> additional test edits (acceptance item 5 carries the rationale, the
> no-IC001 and no-COMMIT constraints). All seven additional ground-truth
> corrections were folded in and independently re-verified against the code
> before being written: `group_membership.removed_at` added to the grant
> list; column-scoped INSERT re-grants pinned (and `users`/`invite_code`
> INSERT confirmed fully revocable — every site sets a privilege column);
> the admin/system-actor procedure split pinned with `GroupAutoPromoteService`
> named as a non-admin actor; the false "V50 system-actor arm" census
> premise replaced with the V24 `trg_audit_log_actor_check` precedent; the
> false grant-matrix claim removed from §Out-of-scope; the five
> return-signal contracts made an acceptance item; and the audit-row
> ambiguity pinned to "handlers keep their AuditLogWriter row, procs write
> none". Acceptance item 10 was added to state the closed-list RULE for
> authorized test edits, so a third round cannot fail the same way.
> Verbatim round-2 verdict below; claims describe the pre-refine ticket
> (its acceptance numbering, #1-#6, is the OLD one).

> Second consecutive plan-writer failure. Fresh-context `plan-writer` gate
> agent (Claude Code native binding per `docs/process/harness-mapping.md`
> §2) re-run against the REFINED ticket; it confirmed the round-1 blocker
> is resolved (the two group test files are now authorized) and every
> round-1 ground-truth correction landed correctly, then found a DIFFERENT
> acceptance-vs-acceptance collision at a new site. Verbatim below; absolute
> worktree path prefixes normalized to repo-relative, no other change. The
> blocking claim was independently re-verified in the main session against
> `BanCommandHandlerTest.java:417-471` and
> `RevokeAdminCommandHandlerTest.java:232-289` before this escalation was
> filed.

REASON: Test-modification authorization missing — escalate via /m1-tick
escalate refine. Acceptance #1 requires every `users.is_admin` /
`users.is_banned` transition to route through a new SECURITY DEFINER
procedure "carrying the V50 live-admin actor-check shape", and acceptance #3
spells that shape out as `is_admin = TRUE AND is_banned = FALSE`, checked
against the DB at call time. Two pre-existing handler tests pin the V5
last-admin-protection trigger path, and that path is reachable through these
handlers **only** with an `is_admin=TRUE AND is_banned=TRUE` actor — a fact
the production source states in its own comment at
`infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java:282-289`
("the trigger-fire path … is unreachable through this handler when the actor
itself qualifies — kept as defense-in-depth for the banned-admin-bypass-intake
edge case (the only path that drives a single-qualifying-admin target through
this handler)"), and which I re-derived from the trigger body: with a
non-banned actor, `remaining = count(is_admin AND NOT is_banned AND id <>
NEW.id) >= 1`, so the trigger can never raise (self-ban/self-revoke are
guarded earlier). A procedure carrying the live-admin conjunction refuses that
banned actor with `P0001` **before** the UPDATE, so `IC001` never surfaces,
both handlers' `LAST_ADMIN_SQLSTATE` catches fall through to
`IllegalStateException`, and both tests fail. The fixtures cannot be re-pinned
the way the two authorized group tests can — flipping the actor to non-banned
makes the asserted scenario unreachable by construction, so the only repairs
are assertion changes, which acceptance #5 and §Out-of-scope forbid ("no
assertion about command behavior changes (engineering-rules §8)"). Acceptance
#1/#3 and acceptance #5 therefore cannot both be satisfied, and neither test
file appears in `test_plan.modifies` or `files_scope`, so no implementable
outline exists as written.

SUGGESTED ESCALATION: refine

EVIDENCE:

**Blocking pair** (ticket acceptance #1/#3 vs #5 + §Out-of-scope item 3,
frontmatter lines 47-66 / 80-85 / 95-110 / 40-46):

- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java:417-471`
  — `banOfOnlyAdminSurfacesLastAdminError`; comment at :422 "Seed actor as
  is_admin=TRUE AND is_banned=TRUE — passes our handler's admin-only gate
  (which checks only is_admin)"; asserts `ERROR_BAN_LAST_ADMIN` at :469.
  Handler gate `BanCommandHandler.java:291`, `IC001`-only catch at `:358`.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java:232-289`
  — `revokeLastAdminTriggerFiresAndRollsBack`; banned-admin caller at :251;
  asserts `ERROR_REVOKE_ADMIN_LAST_ADMIN` at :272. Handler gate
  `RevokeAdminCommandHandler.java:292`, `IC001`-only catch at `:351`.
- Trigger body: `infochat-core/src/main/resources/db/migration/V24__identity_audit_remediation.sql:78-88`.
  V50 actor predicate: `V50__banned_admin_actor_checks.sql:33, 80, 152`.
- The refine must either (a) add both files to `test_plan.modifies` /
  `files_scope` / acceptance #5 with an explicit statement of what the
  repurposed assertions become (the trigger path is superseded by the proc's
  actor check), or (b) pin the new procs' predicate to `is_admin = TRUE` only
  and say so in acceptance #1/#3 in place of "the V50 live-admin actor-check
  shape". Do **not** let the implementer reach for `RAISE … USING ERRCODE
  'IC001'` on actor-check failure: it would make both tests pass by
  mislabelling an authorization refusal as last-admin protection.

**Additional ground-truth corrections to fold into the same refine** (each
verified; each would otherwise cost a further round):

1. **`group_membership.removed_at` missing from the grant list.** Acceptance
   #1 enumerates `users.probation_until`, `users.save_count`,
   `groups.timezone/digest_enabled/removed_at` only.
   `GroupMembershipRepository.java:38-40` + `:146-163` runs
   `UPDATE group_membership SET removed_at = now()` on the weak default
   datasource (user-left path; pinned by
   `MembershipEventHandlerTest.userLeft_marksGroupMemberRemoved`). The census
   row already implies it ("membership rows plain"); the grant list must say
   it.
2. **No INSERT re-grant is specified.** Acceptance #1 revokes "the
   table-level UPDATE/INSERT grants" and then installs UPDATE columns only,
   but the census requires the weak role to keep inserting membership rows
   (`InboundRouter.java:255-257`, `GroupMembershipRepository.java:23-24`) and
   group rows (`GroupRepository.java:32-35` and `:51-55`). 13 test files drive
   `GroupRepository.findOrCreateByAdapterAndUpstreamId` / `tryInsertPending`
   through the CDI bean on the weak datasource. Pin column-scoped INSERT
   (`group_membership (group_id, user_id)`;
   `groups (adapter, upstream_group_id, activated_by)`) and state whether
   `INSERT_PENDING_RETURNING` may keep naming `approval_status` explicitly
   (V26 already defaults it to `'pending'`, so dropping it from the column
   list is behaviour-neutral and keeps the column ungrantable).
3. **Acceptance #3's "refuses … or is unset" cannot apply to every proc.**
   The census assigns procs to four no-human-actor sites: `AdminBootstrap`
   (runs at every boot — a refuse-on-unset proc fails provider startup and the
   whole test cluster), `SimpleXAdminClaim`, `InviteCodeConsumer`, and — not
   listed in §Notes — `GroupAutoPromoteService`, whose actor is by definition
   a **non-admin** (D47 first-mention auto-promote,
   `GroupAutoPromoteService.java:120-131` writes the actor as the promoted
   user). A live-admin gate there breaks group activation outright
   (`GroupAutoPromoteServiceTest`, `GroupAutoPromoteServiceClockIT`,
   `GroupAuthorizationRoundtripIT`). Scope the refusal requirement to the
   admin-gated procs and name the system-actor procs explicitly.
4. **§Census line 203's premise is false.** "V50 live-admin check exempt per
   its documented system-actor arm" — V50 has no system-actor arm; all three
   procs raise unconditionally when the actor is not a live admin. The only
   in-repo system-actor precedent is `trg_audit_log_actor_check`
   (`V24…sql:94-115`), whose comments document the GUC-unset and NULL-actor
   allowances. The refine should point at V24, not V50, for that arm.
5. **§Out-of-scope's grant-matrix claim is false.** "the grant-matrix
   assertion cluster (`collector/db/*IT`) — those assertions pin today's
   over-wide grants" — none of `DbRoleMatrixIT` (2 `@Test`s: role LOGIN
   attributes + `price_snapshot`), `DbGrantsRevocationIT`, or
   `AdminRoleGrantsIT` asserts anything about
   `users`/`groups`/`group_membership`/`invite_code`. Nothing forces a
   `DbRoleMatrixIT` edit; say whether the new pins live solely in the new
   `ProviderIdentityGrantsIT`.
6. **Return-signal contracts.** Each replaced statement carries a control-flow
   signal the procedure must reproduce: `AdminBootstrap.ENSURE_ADMIN_SQL:104-110`
   (`RETURNING id` gates the `BOOTSTRAP_ADMIN` audit row),
   `SimpleXAdminClaim.CLAIM_ADMIN_SQL:130-137`,
   `InviteCodeConsumer.CONSUME_INVITE_SQL:98-104` + `INSERT_USER_SQL:106-110`,
   `GroupAutoPromoteService.AUTO_PROMOTE_SQL:51-57` (row-count `!= 1` **and**
   SQLSTATE `23505` from `one_admin_per_group` must propagate out of the
   CALL), `GroupRepository.UPDATE_APPROVAL_STATUS:94-96`
   (`executeUpdate() == 1` distinguishes no-op from mutation). Acceptance
   should state that these are preserved.
7. **Acceptance #3's audit-row clause is ambiguous.** "with the audit row
   written inside the same transaction as the mutation" reads either as "the
   proc writes it" (the V50 precedent) or "the caller's existing
   `AuditLogWriter` write shares the transaction". Every affected handler
   already pre-writes its audit row through `AuditLogWriter` + `RedactionHook`
   (Invariant 7, audit-before-effect); a proc-side duplicate doubles the rows
   existing handler tests count, and moving the write into SQL bypasses the
   Java redaction hook. Pin the intended reading.

**Sizing note.** `files_budget: 26` is **not** the binding constraint: the
full plan lands at ~21 files (V62 + 13 provider main + 5 test +
`docs/spec/security.md`), so the refine can add the two handler test files
without a budget change.

**Verified-correct ticket claims that need no change** (round-1 refine
landed these correctly): the 18-file census reproduces exactly;
`promoteToAdmin`/`demoteAdmin` have zero production callers; the 9 fixture
call sites across 7 tests in the two authorized group test files are exact
(`GroupMembershipRepositoryTest.java:84, 98, 106, 107, 115, 116, 123`;
`MembershipEventHandlerTest.java:103, 185`); `groups.member_count` is indeed
a computed `COUNT` at `GroupRepository.java:106`; `V15.trg_saved_post_count`
is SECURITY INVOKER; `UnbanCommandHandler`'s group-admin restore is a
read-only listing; V61 is the highest existing migration, so V62 is free;
`docs/spec/security.md` §DB roles resolves (line 1494).
