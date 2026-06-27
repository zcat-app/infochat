---
id: M1-505
title: "SimpleX inbound DM identity (decimal contactId) never matches the configured admin (queue address) — admin unrecognized"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by:
  - M1-504
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/startup/AdminBootstrap.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterAdminIdentityMatchTest.java
  - docs/spec/deployment.md
  - docs/spec/decisions.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - "The queue-address length floor that stops the adapter from starting at all — that is M1-504 and must land first (blocked_by)."
  - "Signal-adapter identity; this is SimpleX-specific (Signal derives a stable ACI, a different model)."
  - "Trusting contact.profile.contactLink as the auth identity — it is self-asserted by the peer (spoofable) and MUST NOT become the authorization key; called out so the fix does not take the easy-but-insecure path."
  - "Replacing the configure-by-address bootstrap model with a claim-token / first-DM-secret admin-invite mechanism (Option B) — deferred as a future enhancement (operator decision 2026-06-27). This ticket RETAINS configure-by-address and fixes only the inbound-identity mapping; the first-admin-invite rework is a separate later ticket."
acceptance:
  - >-
    DESIGN DECISION FIRST (this ticket is blocked on it): the existing
    configure-by-address bootstrap model is RETAINED (operator-configured admin;
    the claim-token first-admin-invite is out of scope — see out_of_scope), so the
    decision is the canonical, cryptographically-sound SimpleX inbound identity
    AND a spoof-resistant binding from an inbound pairwise connection to that
    operator-configured address. Record it as a decision (D-NN) before code. The
    three observed id forms — bot self-identity (32-char full-link queue id),
    inbound DM sender (decimal DB row id), operator admin config (43-char
    short-link fragment) — must be reconciled to ONE consistent, spoof-resistant
    scheme used for self-identity, inbound DM sender, group-mention target, and
    operator-typed admin/ban/grant values.
  - >-
    After the fix, the configured bootstrap admin is actually recognized when
    they DM the bot: an inbound DM from the admin resolves to the same
    users.contact_id the AdminBootstrap row was seeded with, so is_admin
    authorization succeeds. A test proves an inbound frame from the
    bootstrap-admin identity authorizes an admin-only command.
  - >-
    Per-(user, scope) isolation is preserved: two different SimpleX contacts
    still map to two distinct, stable contact_ids, and the identity cannot be
    forged by a peer setting its self-asserted profile fields (verify a peer
    cannot impersonate the admin by claiming the admin's address in its profile).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterAdminIdentityMatchTest.java"
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/spec/deployment.md §Operator inputs"
  - "docs/spec/messaging.md §Per-adapter trust level and identity"
  - "docs/design/06-messaging.md §6.4.4 Event decoding"
decision_refs:
  - D10
  - D32
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-505: SimpleX inbound DM identity never matches the configured admin — admin unrecognized

## Context

Found while diagnosing M1-504 on the live `vps` deployment (2026-06-27). Even
once the adapter starts (M1-504), the bootstrap admin will **not** be recognized
when they DM the bot, because three different identifier forms are in play and
none of them match.

**Empirical evidence (simplex-chat v6.5.4, live data-dir).**

1. **Inbound DM sender identity is a decimal DB row id.** The DM decoder reads
   `chatInfo.contact.contactId` (`SimpleXMessageCodec.java:330`) and uses it as
   `Identity.contactId` / `ScopeRef.Dm(...)`. A live `/contacts` query over the
   bot's WebSocket returns the (auto-accepted) connecting user as
   `contactId = 4` — a JSON **number**, the simplex-chat local row id, not a
   queue address. It passes the charset gate (`"4"` matches
   `^[A-Za-z0-9_=.-]+$`) so it is accepted as-is.
2. **The bootstrap admin row is keyed by the operator's queue address.**
   `AdminBootstrap` seeds `users.contact_id` with the canonicalized
   `infochat.adapters.simplex.admin` value. Live DB: the only `users` row is
   `contact_id = "yyPGSnLVqurN…"` (43 chars, `is_admin=t`).
3. **`InboundRouter.lookupUser` matches by byte-equality** —
   `WHERE adapter=? AND contact_id=?` (`InboundRouter.java:232`,
   `UserRepository.java:25`). `"4"` ≠ `"yyPGSnLVqurN…"`, so the admin's DM
   resolves to **no row** → treated as an un-invited stranger, never as admin.
4. **The matching address IS present in the frame — but only as self-asserted,
   spoofable profile data.** `contact.profile.contactLink` is
   `https://smp17.simplex.im/a#yyPGSn…`, whose fragment matches the configured
   admin. It is NOT read by the codec, and it MUST NOT be trusted as the auth
   identity: a peer can set any `contactLink` in its own profile, so keying
   authorization on it would let any contact impersonate the admin.

**Three non-matching id forms (the core defect):**

| Use | Form | Width | Example |
|---|---|---|---|
| Bot self-identity (derived, M1-504) | full-link smp queue id | 32 | `fxwqIb…` |
| Inbound DM sender (codec today) | decimal DB row id | — | `4` |
| Operator admin config / profile addr | short-link fragment | 43 | `yyPGSn…` |

`docs/spec/deployment.md` §Operator inputs item 2 assumes the operator-supplied
"cryptographic contact id" is matchable against inbound traffic. On v6.5.4 it is
not: inbound DMs surface a local decimal row id, the operator configures a
short-link address fragment, and the bot derives its own identity as a full-link
queue id. The result is that **no SimpleX deployment can have a working admin via
DM**, and `/grant-admin`/`/ban`-by-address have the same defect (they store/
compare an address form the inbound path never produces).

## Why this is design-level, not a one-liner

The naive fix ("read `profile.contactLink` instead of `contactId`") is insecure
— that field is self-asserted (acceptance item 3 / out-of-scope). SimpleX's
trust anchor is the *pairwise connection*, which the local `contactId` is a
handle to; but that id is assigned at connect time and is not knowable
out-of-band, so the operator cannot pre-configure it. Reconciling
operator-configurable admin identity with SimpleX's pairwise model needs a
decision among, e.g.:

- **TOFU / claim-token bootstrap** — drop "configure admin by address"; admit
  the first registrant, or have the admin claim admin via an out-of-band secret
  in their first DM (mirrors the invite-code and group-auto-promote models
  already in the codebase).
- **Bind the configured address to the pairwise connection at accept time** — if
  simplex-chat exposes which advertised address a contact connected through in a
  trustworthy (non-profile) field, record that mapping on contact creation; needs
  verification that such a field exists and is not peer-spoofable.

Pick one, record it as a decision, then implement. A `/redteam` pass on the
chosen mechanism is warranted given the impersonation surface.

## Out-of-scope

See frontmatter. M1-504 (length floor) lands first. Do not key auth on
self-asserted profile fields.

## Notes

- **Scope of the deferral (clarified 2026-06-27):** the ONLY deferred item is the
  *claim-token first-admin-invite* enhancement (Option B — the first admin claims
  admin via an out-of-band secret in their first DM, reusing the invite-code
  machinery). That is a separate future ticket, not part of this one. The
  **identity-mapping fix in this ticket is NOT deferred — it is required**: the
  operator retains the existing **configure-by-address** bootstrap model (good
  enough for now), and this ticket fixes the inbound-identity mapping so an
  inbound DM from the configured admin resolves to the seeded admin row
  spoof-resistantly. Option A (TOFU / first-comer auto-admin) is rejected outright
  (a public bot link would let a stranger seize admin).
- **Current-state caveat (not a working admin until this lands):** once M1-504
  lands and the adapter starts, the configured admin still resolves to a decimal
  `contactId` that never matches the seeded queue-address row, so admin-only
  actions (`/grant-admin`, `/ban`, issuing invite codes) fail for the operator.
  Because DM registration requires an invite code from an admin (D44), no user can
  be invited until this mapping is fixed — so this ticket gates an operable
  deployment (configure-by-address bring-up works only once the mapping lands).
- Source: live diagnosis 2026-06-27 (WebSocket `/contacts` + `/show_address`
  against the prod data-dir; `users` table inspection). Reproduction artifacts
  were transient probe containers; no repo state changed.
- Interaction with M1-504: fixing the length floor makes the adapter *start*; it
  does not make the admin *work*. Both are required for an operable SimpleX
  deployment with an admin.
- Likely also affects **group** sender identity (`memberContactId`,
  `SimpleXMessageCodec.java:445`) and group-mention recognition (bot self-id is a
  32-char full-link queue id; mention targets' form is unverified). Confirm under
  the same decision so all four identity surfaces use one canonical form.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-505-*.md
```
