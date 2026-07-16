---
id: M1-633
title: "In-band contactId sourcing for --contact invites (D60)"
status: done
created: 2026-07-15
last_updated: 2026-07-16
blocked_by: [M1-632]
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/security.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
decomposed_from: M1-631
decision_refs:
  - D44
spec_refs:
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/security.md §Invite-code registration
spec_amend_for: docs/spec/commands.md §Admin (bot admin)
out_of_scope:
  - >-
    The bare-`/invite create` default-to-open change and the D60 decision
    record — those are M1-632 (this ticket blocks on it). Do not re-touch the
    no-flag / --open dispatch or re-edit decisions.md; D60 already exists when
    this ticket starts.
  - >-
    The --open confirm gate (M1-051), the per-adapter open cap, the global
    --contact cap, and the audit-before-effect transaction shape for
    create/revoke — all UNCHANGED. This ticket's primary surface is read-only;
    beyond it, the only write-path touch is the narrow empty-`--contact=` parse
    hardening (acceptance item 5), which rejects malformed input at the parse
    boundary and does not alter the mint transaction itself. No Flyway
    migration — invite_code_attempt already exists and audit_action is free-text
    (no enum-constrained column).
acceptance:
  - >-
    A new read-only in-band admin surface (a `/invite` subcommand) lists the
    connected-but-unregistered contacts an admin can bind a `--contact` invite
    to — sourced from the existing invite_code_attempt table (adapter,
    contact_id, attempted_at) — showing the FULL copy-pasteable contact_id that
    `/invite create --adapter <name> --contact <id>` accepts. The surface is
    bot-admin-only, DM-only, and scoped to the inbound adapter so every listed
    id resolves against the same (adapter, contact_id) key create matches on
    (D55 posture, matching /pending).
  - >-
    The read is audit-before-effect: a new AuditAction value records the
    privileged contactId disclosure (the same posture as AuditAction.PENDING_LIST
    for /pending), written before the ids are returned. audit_action is free-text
    so no migration is needed.
  - >-
    docs/spec/commands.md §Admin (bot admin) documents the new subcommand and
    reframes `/invite create --contact` as the advanced-but-now-usable path
    (contactId obtained via the new surface); docs/spec/security.md
    §Invite-code registration notes the deliberate contactId disclosure and its
    bot-admin-only + DM-only + inbound-adapter bound (weighed against the
    anti-theft binding benefit, ContactIds.redact elsewhere).
  - >-
    InviteCommandHandlerTest pins: the new surface returns full contact_ids for
    connected-but-unregistered contacts on the inbound adapter, writes the new
    audit action before disclosure, and is rejected for a non-admin caller and
    in group scope. mvn verify is green.
  - >-
    Empty-equals hardening, folded in from the M1-632 redteam out-of-model item
    (docs/plan/m1/redteam/M1-632-2026-07-15-remediation.md): CreateArgs.parse
    treats an empty value after `--contact=` (and, for consistency, `--adapter=`)
    as malformed rather than a present-but-empty flag value, so the existing
    malformed gate returns error.invite.create_malformed and no CONTACT_BOUND
    invite is ever minted bound to "". The fix lives ONLY at the parse/validation
    boundary — no guard is added in createContactBound, because the malformed gate
    makes an empty expected_contact_id unreachable there (a downstream check would
    be forbidden internal defensive code). This makes the code honor M1-632's
    already-shipped security.md promise that a value-less --contact is rejected
    (M1-632 caught the space form; the equals form slipped through). No new bundle
    key (error.invite.create_malformed already exists from M1-632).
    InviteCommandHandlerTest pins the `--contact=` shape: no invite_code row, no
    INVITE_CREATE_INTENT audit row, no pending confirm.
  - >-
    Closed-list coupling (confirmed during the pre-implementation survey; the
    original Note's "CLOSED_LIST should be unaffected" assumption was false —
    the list is subcommand-granular for /invite): the new subcommand joins the
    Bot-admin-only tier of docs/spec/commands.md §Closed list of
    privileged-tier commands, LlmOutputSanitizer.CLOSED_LIST gains the
    matching entry alongside the four existing /invite entries, and
    LlmOutputSanitizerTest gains the per-entry strip test — all in lockstep so
    matchSetEqualsSpecClosedList (spec↔code set equality) stays green. The
    probation classifier (CommandPermissions) needs NO change: it is a
    top-level-name allowlist, so /invite is already blocked during probation
    for every subcommand.
test_plan:
  adds:
    - >-
      InviteCommandHandlerTest empty-`--contact=` scenario — the equals-empty form
      is rejected with error.invite.create_malformed and mints/arms nothing (folds
      in the M1-632 redteam out-of-model item).
    - >-
      InviteCommandHandlerTest pending-contacts scenarios — full-id disclosure for
      connected-but-unregistered contacts on the inbound adapter (registered,
      other-adapter, and duplicate-attempt rows excluded/deduped), audit row
      written, non-admin and group-scope rejection.
    - >-
      LlmOutputSanitizerTest per-entry strip test for the new /invite subcommand
      token (matchSetEqualsSpecClosedList covers the spec↔CLOSED_LIST set equality
      mechanically).
  preserves:
    - all tests currently green on main
reviews:
  - round: 1
    date: 2026-07-16
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 486
      removed: 28
overrides: []
revisions:
  - date: 2026-07-16
    reason: >-
      budget-breach refine (escalation menu option 1, user-approved). The
      pre-implementation survey falsified the Note's assumption that
      LlmOutputSanitizer.CLOSED_LIST holds top-level command names only: the
      list is subcommand-granular for /invite ("/invite create", "/invite
      list", "/invite revoke", "/invite bot-contact" are each entries), the
      spec closed set (commands.md §Closed list of privileged-tier commands)
      is a load-bearing spec-level enumeration whose tier changes are spec
      amendments, and LlmOutputSanitizerTest.matchSetEqualsSpecClosedList
      asserts set equality between the spec enumeration and CLOSED_LIST. A
      new bot-admin-only /invite subcommand therefore forces the spec
      closed-list entry, the CLOSED_LIST entry, and the per-entry strip test
      in lockstep — two files outside the original files_scope. Fixed by
      adding LlmOutputSanitizer.java and LlmOutputSanitizerTest.java to
      files_scope (8 → 10 paths; files_budget 10 unchanged), adding an
      acceptance item for the closed-list coupling, extending test_plan.adds,
      and correcting the Notes bullet. Verified non-couplings recorded: the
      probation classifier (CommandPermissions) is a top-level-name allowlist
      — no change needed. Alternative considered and rejected: hanging the
      surface off /invite list as a flag (already closed-listed via prefix
      match) — contradicts the acceptance's explicit new-subcommand
      requirement and conflates issued-code listing with unregistered-contact
      listing.
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-16
    verdict: CLEAN
    base: f7c598b62c4cde88e9734fd5dae987c126e3a4ad
    head: m1/M1-633-invite-contact-id-sourcing-surface@working-tree
    verdict_file: docs/plan/m1/redteam/M1-633-2026-07-16.md
    out_of_model_count: 3
    note: |
      Pre-commit --in-progress audit of the working-tree diff vs the fork
      point (implementation uncommitted at audit time; the branch tip
      carried only the budget-breach refine commit). CLEAN — zero findings
      at every severity. Three out-of-model observations (Sybil knock
      racing the roster's most-recent-first ordering; invite_code_attempt
      growth now carrying an admin-facing read cost; lenient trailing-token
      parsing on the read-only subcommand) reported to the user with
      follow-up recommendations; none warranted blocking commit.
escalations:
  - date: 2026-07-16
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation scope discovery. Completing the ticket as
      specified requires touching LlmOutputSanitizer.java and
      LlmOutputSanitizerTest.java, both outside files_scope. The ticket
      Note's assumption ("CLOSED_LIST (top-level command names) should be
      unaffected") is falsified on inspection: CLOSED_LIST is
      subcommand-granular for /invite ("/invite create", "/invite list",
      "/invite revoke", "/invite bot-contact" are each entries), the spec
      closed set (commands.md §Closed list of privileged-tier commands) is
      declared load-bearing with subcommand granularity, and
      LlmOutputSanitizerTest.matchSetEqualsSpecClosedList asserts set
      equality between the spec enumeration and CLOSED_LIST — so adding a
      new bot-admin-only /invite subcommand forces the spec closed-list
      entry, the CLOSED_LIST entry, and the per-entry strip test in
      lockstep.
clarity_check:
  date: 2026-07-16
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-633: In-band contactId sourcing for --contact invites (D60)

## Context

Decomposed from M1-631 (operator decision-gate resolved 2026-07-15). The
operator decided to **keep** `/invite create --contact` — whose theft-proof
binding (`InviteCodeConsumer.java:103` checks `expected_contact_id =
<redeemer's own id>`) is real in principle — but it is unusable in v1 because
an admin has no in-band way to obtain the target `contactId` at the moment it
matters:

- Before the person connects, the `contactId` does not exist (it is the SimpleX
  queue address / Signal ACI assigned when the connection forms).
- While connected-but-unregistered (the only useful window), nothing surfaces
  the id: `ProductionAdapterBeans` has "no unified admin-notification surface
  yet", and the intake bounce writes an `invite_code_attempt` row but no command
  exposes it.
- `/pending` shows full copy-pasteable ids but only for **already-registered**
  probation / awaiting-vouch users — too late.

This ticket closes that gap by exposing the `invite_code_attempt` contacts
(connected, attempted to redeem, not yet registered) to an admin, mirroring the
`/pending` D55 pattern. M1-632 lands D60 first; this ticket references it.

## Acceptance

**Surface.** A new read-only `/invite` subcommand lists connected-but-
unregistered contacts from the `invite_code_attempt` table with their FULL
`contact_id`s (what `--contact` accepts), bot-admin-only, DM-only, scoped to the
inbound adapter (D55 posture, exactly like `/pending`).

**Audit.** The privileged read is audit-before-effect: a new `AuditAction`
value records the disclosure before the ids are returned, the same posture as
`AuditAction.PENDING_LIST`. `audit_action` is free-text — no migration.

**Spec.** `docs/spec/commands.md` §Admin documents the subcommand and reframes
`/invite create --contact` as the advanced, now-usable path.
`docs/spec/security.md` §Invite-code registration records the deliberate
contactId disclosure and its bounds.

**Hardening (folded in from the M1-632 redteam out-of-model item).** `CreateArgs.parse`
treats an empty value after `--contact=` (and `--adapter=`) as malformed, so the
existing malformed gate returns `error.invite.create_malformed` and no
`CONTACT_BOUND` invite is minted bound to `""`. This closes the letter-of-spec
gap M1-632's new "value-less `--contact` is rejected" wording left open: M1-632's
parser caught the *space* form (`--contact` with no following token) but the
*equals* form (`--contact=`) set `contact=""` (non-null) and slipped past both the
malformed gate and the bare→open path into `createContactBound`. The fix is
parse-boundary-only — no guard in `createContactBound` (the gate makes an empty id
unreachable there, so a downstream check would be forbidden defensive code); no
new bundle key.

**Tests.** `InviteCommandHandlerTest` pins full-id disclosure for connected-
unregistered contacts on the inbound adapter, the audit-before-disclosure order,
and rejection for non-admin callers and group scope. It also pins the
empty-`--contact=` shape: rejected with `error.invite.create_malformed`, no
invite row, no intent audit row, no pending confirm. `mvn verify` is green.

## Out-of-scope

- The bare-create default-to-open change and the D60 record (M1-632, this
  ticket's blocker). D60 already exists at start; do not re-edit `decisions.md`.
- Invite-minting machinery: the `--open` confirm gate (M1-051), the per-adapter
  open cap, the global `--contact` cap, and the create/revoke audit-before-
  effect transaction shape. Beyond the read-only surface, the only write-path
  touch is the empty-`--contact=` parse hardening (acceptance item 5); the mint
  transaction itself is unchanged.
- No Flyway migration: `invite_code_attempt` exists and `audit_action` is
  free-text.
- `InviteCommandHandlerTest`: existing tests preserved; only additions.

## Notes

- Security posture: the sourcing surface exposes a connection identifier that is
  `ContactIds.redact`'d elsewhere (e.g. in `/invite list`'s CONTACT_BOUND
  rows). The design must weigh that disclosure against the anti-theft benefit
  and stay bot-admin-only + DM-only + inbound-adapter-scoped like `/pending`
  (D55). `security_relevant: true`; the redteam gate audits before commit.
- A new bot-admin PII-read command historically trips a small set of couplings
  (see the project note on new-admin-command couplings): the audit-before-effect
  `AuditAction` for the privileged read (covered above), and — confirmed during
  the 2026-07-16 pre-implementation survey, contra this Note's original
  assumption — `LlmOutputSanitizer.CLOSED_LIST`, which is subcommand-granular
  for `/invite`. The new subcommand joins the spec §Closed list Bot-admin-only
  tier, `CLOSED_LIST`, and the per-entry strip test in lockstep (acceptance
  item 6). The probation classifier (`CommandPermissions`) is a
  top-level-name allowlist and needs no change.
- Adjacent code: `PendingCommandHandler` (the D55 precedent — bounded admin
  roster with full ids, audit-before-effect via `AuditAction.PENDING_LIST`);
  `InviteCodeConsumer` (writes `invite_code_attempt`); `InviteCommandHandler`
  (the `/invite <sub>` dispatch this extends). The subcommand name is the
  implementer's call (e.g. `/invite pending-contacts`) — pin it in the spec
  amendment and the test.
- Empty-`--contact=` hardening (acceptance item 5) folds in the M1-632 redteam
  out-of-model item instead of a standalone `remediates: M1-632` ticket, since
  M1-633 already owns the `--contact` surface and touches these exact files
  (`InviteCommandHandler` + its test) — avoiding a third churn of the invite
  handler. Lineage: `docs/plan/m1/redteam/M1-632-2026-07-15-remediation.md`
  (OUT-OF-MODEL). The threat-actor's second suggestion (reject empty
  `expected_contact_id` at issuance) is intentionally NOT taken — once the parse
  gate rejects `--contact=`, an empty id cannot reach `createContactBound`, so an
  issuance guard would be forbidden defensive code (CLAUDE.md §No defensive code).
- Decision family: D44 (per-adapter invite), D45 (probation), D55 (no general
  /list-users), D60 (created by M1-632).
