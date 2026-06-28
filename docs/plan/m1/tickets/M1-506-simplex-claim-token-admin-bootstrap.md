---
id: M1-506
title: "SimpleX: claim-token bot-admin bootstrap (drop by-address)"
status: done
created: 2026-06-27
last_updated: 2026-06-28
blocked_by: []
files_budget: 12
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
replaces: M1-505
out_of_scope:
  - "Reintroducing any inbound-advertised-address identity mapping (contact.profile.contactLink as the contact_id / authz key). That is the discarded M1-505 approach and the source of its critical PERM-ESCAL + high INFO-LEAK findings. The DM identity MUST stay the connection-based contactId."
  - "Signal-adapter bootstrap. Signal's ACI is a real cryptographic account id, so configure-by-ACI (pre-seed an admin row) stays exactly as-is for Signal. This ticket changes ONLY the SimpleX bootstrap path."
  - "The by-address bot-admin COMMANDS (/grant-admin <contact>, /ban <contact>, etc.). They share the same 'a SimpleX user has a stable public address' assumption and need the same connection/handle-based rework, but that is a separate pre-existing defect (not introduced here) — file/track as a follow-up, do not fix it in this ticket."
  - "Claim-token rotation, multi-admin token issuance, or any token lifecycle beyond a single-use first-admin bootstrap secret. v1 is one operator-configured single-use bootstrap token; richer flows are future work."
  - "Changing the users schema. is_admin already exists; the claim sets it on a (new or existing) connection-keyed row — no Flyway migration."
acceptance:
  - >-
    SimpleX bootstrap admin is established by a single-use secret CLAIM-TOKEN,
    not by a configured address. A new operator config key
    (infochat.adapters.simplex.admin-token) holds the secret; NO SimpleX admin
    row is pre-seeded at startup (AdminBootstrap no longer seeds for SimpleX).
    Named test proves: the FIRST DM whose normalized body equals the configured
    token registers the sending contact and sets is_admin=true on that contact's
    (simplex, contact_id) row, where contact_id is the connection-based id the
    codec already surfaces.
  - >-
    The claim-token is SINGLE-USE: once a contact has claimed admin via the
    token, a later DM presenting the same token (from the same OR a different
    contact) does NOT grant admin to anyone else. Named test proves a second
    presentation grants nothing and surfaces the same fixed response an
    invalid/again-presented secret would (no oracle on token validity beyond the
    existing invite-style reply).
  - >-
    DM sender identity remains the connection-based contactId; an advertised
    address in the inbound frame does NOT influence the resolved identity. Named
    codec test proves a direct newChatItem frame carrying a
    contact.profile.contactLink still resolves Identity.contactId /
    ScopeRef.Dm.contactId to the connection contactId (the M1-505 mapping is not
    reintroduced).
  - >-
    Signal bootstrap is UNCHANGED: a configured Signal admin (ACI) is still
    validated and pre-seeded with is_admin=true at startup, and the existing
    Signal bootstrap tests stay green. The deployment-wide "at least one
    bootstrap admin path across enabled adapters" constraint is preserved — a
    configured SimpleX admin-token counts as SimpleX's bootstrap-admin path for
    that union check.
  - >-
    Spec updated: docs/spec/deployment.md §Operator inputs and
    docs/spec/security.md (§Authorization model / admin bootstrap / trust
    boundaries) describe the SimpleX claim-token model and record the protocol
    fact that SimpleX exposes no pre-configurable cryptographic sender address
    (so configure-by-address is impossible for SimpleX; the advertised profile
    address is self-asserted per the SMP spec). A decision id is added for the
    SimpleX-token-vs-Signal-ACI per-adapter bootstrap split.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/SimpleXAdminClaimTokenTest.java
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/startup/AdminBootstrapIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BootstrapAdminParseGateTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  preserves:
    - all tests currently green on main EXCEPT the SimpleX-specific AdminBootstrapIT case(s) this ticket updates (see modifies and Notes "AdminBootstrapIT")
    - existing Signal bootstrap-admin tests (AdminBootstrap / parse-gate) and all non-SimpleX AdminBootstrapIT cases stay green
spec_refs:
  - "docs/spec/deployment.md §Operator inputs"
  - "docs/spec/security.md §Authorization model"
  - "docs/spec/security.md §Per-adapter admin threat profile"
  - "docs/design/06-messaging.md §6.4.4 Event decoding"
decision_refs:
  - D9
  - D10
  - D32
  - D44
reviews:
  - round: 1
    date: 2026-06-28
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 899
      removed: 118
  - round: 2
    date: 2026-06-28
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 1100
      removed: 122
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-28
    category: PERM-ESCAL
    severity: medium
    promise: |
      D50 / security.md §Authorization model + §Per-adapter admin threat
      profile: the token is single-use — once any contact has claimed SimpleX
      admin, a later presentation grants nothing; "a leaked token cannot be
      replayed once the real admin has claimed."
    gap: |
      Single-use is gated on row-existence (WHERE NOT EXISTS (… is_admin=TRUE)),
      not token-spent. A /revoke-admin of the SimpleX admin (allowed in
      multi-adapter deployments — the global last-admin counter is kept >= 1 by
      a Signal admin) re-arms the still-configured token, so a leaked token can
      re-claim admin. Also makes the security.md rotation paragraph describe
      behavior the code cannot do.
    repro: |
      SimpleX+Signal deployment; SimpleX admin claimed; /revoke-admin the
      SimpleX admin (global count stays >=1 via Signal); SimpleX is_admin count
      is now 0; attacker holding the leaked token sends one DM with the token →
      claim succeeds again.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-06-28
    verdict: FINDINGS
    base: working-tree fork point (M1-506 refine commit)
    head: working-tree (uncommitted M1-506 implementation)
    verdict_file: docs/plan/m1/redteam/M1-506-2026-06-28.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      One medium PERM-ESCAL (single-use row-existence-based, not token-spent).
      RESOLVED (round 2, user-delegated) by spec-alignment + operator
      mitigation, NOT by a code gate change. The durable-audit-log gate that
      would make single-use survive a revoke is infeasible: the application DB
      role is write-only on audit_log (audit-integrity least-privilege, D34),
      so the claim path cannot read the claim record, and a durable token-spent
      marker would need a schema migration this ticket's out_of_scope forbids
      (verified: an audit_log-read gate failed with "permission denied for
      table audit_log"). Instead the over-promising spec wording was corrected
      to the actual guarantee (single-use while a SimpleX admin exists), the
      operator mitigation that fully closes the attack is documented (unset
      infochat.adapters.simplex.admin-token once the first admin is
      established — with no token, nothing can re-arm), and
      permanent-single-use-without-unsetting is filed as a follow-up (needs the
      durable marker + migration). Removing the promise-vs-delivery gap is what
      resolves the finding. Out-of-model token-length side channel: not
      actioned (body is attacker-chosen; spec does not commit to hiding length).
clarity_check:
  date: 2026-06-27
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-506.md
---

# M1-506: SimpleX — claim-token bot-admin bootstrap (drop by-address)

## Context

This replaces M1-505 (deferred). The SimpleX bootstrap-admin model was
"configure the admin by their SimpleX address; seed an `is_admin` row keyed by
that address; match inbound DMs to it." That is **incompatible with the SimpleX
protocol**: SimpleX has no long-term sender identifier — identity is the
per-connection id — and a sender's advertised address (`profile.contactLink`)
is **not cryptographically verified** ("the sender's own published address is
not verified … out of scope of SMP protocol",
https://github.com/simplex-chat/simplexmq/blob/stable/protocol/simplex-messaging.md).
M1-505 tried to close the recognition gap by trusting that advertised address
as the identity, which the in-progress redteam (and the protocol spec) showed
lets any contact spoof the admin and breaks per-user isolation for everyone.

The right model keeps the connection-based identity SimpleX intends and proves
admin via a **secret only the operator holds**: a single-use claim-token, the
same shape as the existing invite-code mechanism (D44 — a secret presented in a
first DM grants access), extended to also set `is_admin`. Signal is unaffected:
its ACI is a real cryptographic account id, so configure-by-ACI stays.

## Acceptance

See the YAML `acceptance:` list. In prose:

1. SimpleX admin is bootstrapped by `infochat.adapters.simplex.admin-token`
   (a secret), not an address; nothing is pre-seeded for SimpleX. The first DM
   carrying the exact token registers that connection's contact and flips
   `is_admin`.
2. The token is single-use — a second presentation grants nothing and gives the
   same fixed reply path an invalid secret would (no validity oracle).
3. The DM identity stays the connection-based `contactId`; an advertised address
   in the frame never influences it (M1-505's mapping is not reintroduced).
4. Signal bootstrap (ACI pre-seed) is unchanged; the union "≥1 bootstrap-admin
   path" constraint is preserved, with a configured SimpleX token counting as
   SimpleX's path.
5. `deployment.md` and `security.md` document the token model and the protocol
   rationale; a decision id records the per-adapter split.
6. `mvn -B verify` green.

## Out-of-scope

See frontmatter. The load-bearing boundaries: **never reintroduce the
advertised-address-as-identity mapping**; **do not touch Signal's bootstrap**;
**do not fix the by-address admin commands here** (separate follow-up); **no
token lifecycle beyond single-use first-admin**; **no schema migration**.

## Notes

- **Reuse, don't reinvent.** The invite-code path (`InviteCodeConsumer`,
  consumed on the first DM from an unknown contact in `InboundRouter` step 2) is
  the existing "secret-in-first-DM grants access" machinery. The cleanest design
  is likely an admin-claim that rides this path (token recognized → register +
  set `is_admin`, single-use), rather than a parallel subsystem. The plan-writer
  should verify the exact integration point against the real code before
  committing to it — this Notes pointer is a hypothesis, not a spec.
- **AdminBootstrap becomes per-adapter.** It currently seeds an `is_admin` row
  for every adapter with a configured `…admin`. After this ticket it seeds for
  Signal (ACI) but NOT for SimpleX (no pre-seedable identity; the SimpleX row is
  created at claim time). The `AdapterRegistry` union-non-empty gate (gate 7)
  must treat a configured SimpleX token as satisfying SimpleX's side.
- **Why the token is secure where the address was not.** The connection is
  cryptographically authenticated by SMP; the token proves "I am the intended
  admin." An attacker without the token cannot claim it, and cannot influence
  their own connection-based `contact_id`. Single-use means a leaked-then-rotated
  token cannot be replayed after the real admin claims.
- **Single-use semantics.** "Used" means an admin has been established via this
  token. Consider storing consumption durably (so a restart cannot re-open the
  claim) — design decision for the plan/implementation; call it out explicitly.
- **Secret handling.** The token is a secret: never log it raw (security.md
  §Secrets handling), compare without leaking timing/validity beyond the fixed
  invite-style reply, and source it from config/env, not the DB.
- **AdminBootstrapIT (authorized test change).** Making AdminBootstrap stop
  seeding for SimpleX (acceptance item 1) breaks the pre-existing green case
  `AdminBootstrapIT.adminGivenAsFullContactLinkSeedsBareQueueIdRow` (M1-465),
  which seeds `simplex` from a configured full contact link and asserts a
  by-address `is_admin` row is created — the exact behavior this ticket removes.
  That test (and the `infochat.adapters.simplex.admin = SIMPLEX_ADMIN_FULL_LINK`
  entry in `AdminBootstrapIT.Profile`, plus the now-dead SIMPLEX_* link
  constants it feeds) is authorized to change: its NEW expected behavior is that
  `seed("simplex")` creates NO admin row (SimpleX has no pre-seedable address).
  Delete-vs-invert is the implementer's call; the assertion must reflect item 1.
  All other AdminBootstrapIT cases (inmemory boot, fake-x/fake-y/fake-promote
  seed/rotate/idempotency, the gate-7 union case) are unaffected and stay green.
- **Additional pre-existing tests modified (authorized 2026-06-28, user
  directive "ultrathink and recommend" after mid-implementation full-verify
  surfaced them — same class as the AdminBootstrapIT conflict).**
  - `BootstrapAdminParseGateTest`: its two SimpleX cases
    (`gateAcceptsWellFormedSimplexAdminAndProceeds`,
    `adminGivenAsFullContactLinkIsAcceptedAfterCanonicalization`) assert that
    `infochat.adapters.simplex.admin` is a valid bootstrap admin that passes
    the startup gates — exactly the by-address behavior D50 removes (gate 7's
    union no longer counts SimpleX `.admin`). New expected behavior: those two
    cases are DELETED as obsolete; the Signal reject case stays. The M1-465
    gate-7b canonicalization coverage they provided is preserved by the new
    `AdminBootstrapIT.simplexAdminTokenSatisfiesGate7Union` (its profile sets
    `simplex.admin` = full link + a token, so `start("simplex")` still runs
    gate-7b canonicalization on the link).
  - `InboundRouterIntakeOrderingTest`: pure scaffolding — the hand-constructed
    router gains a log-silent `SimpleXAdminClaim` stub returning `NotClaimed`
    so step 2 reaches the invite consumer as before. No assertion changes.
- **Follow-up (not this ticket).** The by-address admin commands
  (`/grant-admin <contact>`, `/ban <contact>`) carry the same broken
  "stable public SimpleX address" assumption and need a connection/handle-based
  rework. File a separate ticket. Also file: gate 7b
  (`AdapterRegistry`) still format-validates a now-inert
  `infochat.adapters.simplex.admin` if present; for full per-adapter
  consistency it could skip SimpleX (like gate 7 and AdminBootstrap now do).
  Deferred here as a non-acceptance consistency improvement (the residual
  behavior is harmless fail-fast on stale config, not a security gap). Also
  file: **permanent SimpleX claim-token single-use** (M1-506 redteam medium
  PERM-ESCAL) — make single-use survive a `/revoke-admin` without relying on
  the operator unsetting the token. This needs a durable token-spent marker;
  the application DB role is write-only on `audit_log` (so the claim path
  cannot read a claim record), so the marker requires a schema migration
  (a `users` column or a small `bootstrap_token_claim` table) — out of scope
  here (no-migration). v1 relies on the documented operator hygiene (unset the
  token after the first admin is established).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-506-*.md
```
