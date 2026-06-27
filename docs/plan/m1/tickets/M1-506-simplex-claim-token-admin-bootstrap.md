---
id: M1-506
title: "SimpleX: claim-token bot-admin bootstrap (drop by-address)"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
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
  preserves:
    - all tests currently green on main
    - existing Signal bootstrap-admin tests (AdminBootstrap / parse-gate)
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
- **Follow-up (not this ticket).** The by-address admin commands
  (`/grant-admin <contact>`, `/ban <contact>`) carry the same broken
  "stable public SimpleX address" assumption and need a connection/handle-based
  rework. File a separate ticket.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-506-*.md
```
