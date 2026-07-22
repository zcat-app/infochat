---
id: M1-672
title: "Narrow Provider grants on identity/authz tables"
status: pending
created: 2026-07-22
last_updated: 2026-07-22
blocked_by: []
files_budget: 26
files_scope:
  - infochat-core/src/main/resources/db/migration/V62__provider_identity_grants.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/**
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/**
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/**
  - infochat-provider/src/main/java/app/zcat/infochat/provider/startup/AdminBootstrap.java
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
    unmodified (any required edit to an existing test is named explicitly in
    §Out-of-scope of the diff, per engineering-rules §8).
acceptance:
  - >-
    A new migration V62 revokes the table-level UPDATE/INSERT grants V5
    installed on users, groups, group_membership, invite_code for
    infochat_provider and installs the narrow shape: plain UPDATE only on
    the non-privilege columns the Provider legitimately writes (e.g.
    users.probation_until, groups.timezone / digest_enabled / removed_at /
    member_count), with every privilege-escalating transition
    (users.is_admin, users.is_banned, users.registration_state,
    groups.approval_status, group_membership.is_group_admin, invite_code
    mint/revoke/consume) routed through new SECURITY DEFINER procedures
    carrying the V50 live-admin actor-check shape.
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
    A new procedure-level test in the V50-test shape proves each new
    SECURITY DEFINER procedure refuses a caller whose infochat.actor_id
    GUC names a non-admin (or is unset) and accepts a live bot admin, with
    the audit row written inside the same transaction as the mutation.
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
    unmodified; mvn verify is green.
  - >-
    docs/spec/security.md §DB roles records the narrowed grant shape and
    names the procedure-mediated write model for privilege-escalating
    transitions, closing the inconsistency the audit flagged (the V31
    source-table rationale applied to the identity tables).
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db/ProviderIdentityGrantsIT.java
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java
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
| `command/UnbanCommandHandler.java` | route is_banned + group-admin restore via procs |
| `command/GrantAdminCommandHandler.java` | route is_admin write via proc |
| `command/RevokeAdminCommandHandler.java` | route is_admin write via proc |
| `command/PromoteCommandHandler.java` | route is_group_admin write via proc |
| `command/DemoteCommandHandler.java` | route is_group_admin write via proc |
| `command/VouchCommandHandler.java` | route registration_state write via proc |
| `command/InviteCommandHandler.java` | route invite_code mint/revoke via procs |
| `command/DigestCommandHandler.java` | non-privilege (digest_enabled) — keep plain UPDATE on narrowed grant |
| `command/GroupTimezoneCommandHandler.java` | non-privilege (timezone) — keep plain UPDATE |
| `group/GroupAutoPromoteService.java` | route is_group_admin write via proc |
| `group/GroupMembershipRepository.java` | split: privilege writes via proc, membership rows plain |
| `group/GroupRepository.java` | split: approval_status via proc; removed_at/member_count plain |
| `messaging/InboundRouter.java` | route registration/probation writes via procs where privilege-classed |
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
only existing test expected to need modification is the grant-matrix
assertion cluster (`collector/db/*IT`) — those assertions pin today's
over-wide grants and must be re-pinned to the narrow shape; every other
existing test must pass byte-identical.

## Notes

- The V50 procedures (`approve_quarantine`, `reject_quarantine`,
  `delete_preban_user`) are the shape to copy: SECURITY DEFINER, actor
  resolution from the `infochat.actor_id` GUC with a live `is_admin` DB
  check, audit row in the same transaction. Sites without a human actor
  (AdminBootstrap, SimpleXAdminClaim) need the same documented system-actor
  handling the existing procs/tests already model.
- Column-scoped `GRANT UPDATE (col, ...)` is the correct narrowing
  mechanism for the non-privilege columns (the V31 precedent on `source`).
- Finding detail, falsification history, and threat-model reasoning: the
  audit report (`kimi-audit.md` under `.scratch/`) §CORE-1 (module 1) and
  the module-6 CORE-1-premise FINAL verdict (no injectable Provider SQL —
  the grant narrowing is defense-in-depth, not an active-breach fix).
