---
id: M1-505
title: "SimpleX: map inbound admin DM to the configured admin address"
status: abandoned
created: 2026-06-27
last_updated: 2026-06-27
deferred_on: M1-506
abandoned_reason: superseded
blocked_by:
  - M1-504
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/startup/AdminBootstrap.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterAdminIdentityMatchTest.java
  - docs/spec/deployment.md
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "Any claim-token / first-DM-secret / out-of-band-secret admin bootstrap mechanism. The operator has decided (2026-06-27) NOT to build this now; it is a possible FUTURE improvement only. This ticket retains configure-by-address and does ONLY the inbound-address mapping. Do not introduce a token here."
  - "The queue-address length floor that stopped the adapter starting — that is M1-504 (blocked_by), already landed."
  - "Signal-adapter identity; this is SimpleX-specific (Signal derives a stable ACI, a different model)."
  - "Replacing/redesigning the bootstrap model. The model stays configure-by-address; only the inbound mapping that makes it actually match is added."
acceptance:
  - >-
    The advertised admin address present in an inbound SimpleX DM frame is read
    by the codec and reconciled to the SAME canonical form the operator
    configures and AdminBootstrap seeds (infochat.adapters.simplex.admin). An
    inbound DM from the configured bootstrap admin therefore resolves to the same
    users.contact_id the AdminBootstrap row was seeded with, so is_admin
    authorization succeeds. Named test: InboundRouterAdminIdentityMatchTest proves
    an inbound frame carrying the configured admin's advertised address authorizes
    an admin-only command.
  - >-
    Per-(user, scope) isolation is preserved: two different SimpleX contacts
    still map to two distinct, stable contact_ids; a non-admin contact does NOT
    resolve to the admin row. A test case in the same class proves a second,
    distinct contact is recognized as a separate (non-admin) user.
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
reviews:
  - round: 1
    date: 2026-06-27
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 314
      removed: 13
escalations:
  - date: 2026-06-27
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS (critical=1 high=1).
      critical PERM-ESCAL: DM identity derived from self-asserted
      contact.profile.contactLink and used as the (adapter, contact_id) authz
      join key → any contact who knows the admin's public address can spoof it
      and /grant-admin themselves. security.md trust boundary 1 / D10 (identity
      must be cryptographically anchored, never self-asserted) is unamended.
      high INFO-LEAK: the self-asserted address becomes the DM partition key for
      ALL users → cross-user impersonation / data theft (saved_post, chat_memory,
      /export, /forget) — broader than the ticket's admin-only documented
      acceptance. Full verdict: docs/plan/m1/redteam/M1-505-2026-06-27.md
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-27
    category: PERM-ESCAL
    severity: critical
    promise: |
      security.md §Trust boundaries (1): the adapter asserts identity via a
      stable, cryptographically anchored ID; display names / self-asserted
      fields are never used for authorization (D10). §Per-adapter admin threat
      profile: the SimpleX admin's identity is a cryptographic queue address.
    gap: |
      SimpleXMessageCodec.decodeNewChatItem derives the DM identity from
      contact.profile.contactLink — a peer-supplied, SELF-ASSERTED field — via
      canonicalizeAdvertisedAddress, and uses it as BOTH Identity.contactId and
      ScopeRef.Dm.contactId, i.e. the (adapter, contact_id) authorization join
      key. security.md (the authoritative trust-path doc) retains the
      cryptographic-anchor commitment unamended; the acceptance caveat lives
      only in deployment.md.
    repro: |
      Adversary sets their own profile contactLink to the admin's published
      queue address (a public value) and DMs /grant-admin <self>. The codec
      canonicalizes to the admin's bare queue id; the seeded is_admin row
      already exists for that contact_id, so the invite gate never fires and
      the admin-only command executes → full bot-admin compromise.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-06-27
    category: INFO-LEAK
    severity: high
    promise: |
      Per-(user, scope) isolation (CLAUDE.md key conventions / security.md):
      never leak across users or between DM and group. LLM tools (recallMemory,
      listSaves) bind isolation to the resolved (user, scope) identity.
    gap: |
      The DM partition key is now the self-asserted contact.profile.contactLink,
      so the per-(user, scope) key is attacker-controlled for EVERY user, not
      just the admin — broader than the documented admin-only acceptance.
    repro: |
      Adversary advertises a victim's published address, DMs the bot, and is
      resolved to the victim's (adapter, contact_id) — reading the victim's
      saved_post / chat_memory / exportable state and able to /forget (destroy)
      it. Breaks the cross-user isolation invariant the spec commits to "never"
      violate.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-06-27
    verdict: FINDINGS
    base: main (dde389dd)
    head: working-tree (m1/M1-505 branch, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-505-2026-06-27.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      Audited at branch tip before commit (security_relevant gate). HALTED — no
      commit. Critical is the operator-accepted admin-spoof tradeoff but
      security.md is unamended (diff contradicts trust boundary 1 / D10). High
      exceeds the documented acceptance: self-asserted address becomes the DM
      partition key for ALL users → cross-user impersonation / data theft.
      Resolution requires a user-owned spec/design decision; surfaced via the
      five-way escalation menu (redteam-finding).
clarity_check:
  date: 2026-06-27
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-505: SimpleX — map inbound admin DM to the configured admin address

> **DEFERRED 2026-06-27 — premise invalid, superseded by M1-506.** Implementation
> passed review (APPROVE) but the in-progress redteam audit found a critical
> PERM-ESCAL + high INFO-LEAK (see `redteam_findings:` and
> `docs/plan/m1/redteam/M1-505-2026-06-27.md`). Verified against the SimpleX
> Messaging Protocol spec
> (https://github.com/simplex-chat/simplexmq/blob/stable/protocol/simplex-messaging.md):
> a sender's advertised address (`contact.profile.contactLink`) is **not**
> cryptographically verified — "the sender's own published address is not
> verified … out of scope of SMP protocol." SimpleX has no long-term sender
> identifier; identity is the per-connection id. So "map the inbound advertised
> address to the configured admin" is unfixable as framed — any matched value is
> self-asserted. The whole approach is dropped. M1-506 replaces it: keep the
> connection-based DM identity (revert this change, already discarded) and
> bootstrap the SimpleX admin via a single-use secret claim-token instead of an
> address. Branch changes discarded; codec/spec returned to `main`.

## Context

The operator configures the bootstrap admin by
SimpleX address (`infochat.adapters.simplex.admin`), AdminBootstrap seeds a
`users` row keyed by that address, but inbound DMs never match it: the codec
reads `chatInfo.contact.contactId` (`SimpleXMessageCodec.java:330`), a decimal
local DB row id (e.g. `4`), which can never byte-equal the configured address.
So the admin's DMs resolve to no row and the operator cannot manage the bot.

The configured admin's actual address **is present in the inbound frame** — as
`contact.profile.contactLink` (e.g. `https://smp17.simplex.im/a#yyPGSn…`, whose
fragment is the same form the operator configured) — but the codec does not read
it. This ticket reads it and reconciles it to the configured form so the
admin's inbound DMs match the seeded admin row. That is the whole change: the
mapping. The bootstrap model (configure-by-address) is unchanged.

## Acceptance

See the YAML `acceptance:` list. In prose:

1. The codec reads the advertised address from the inbound DM frame and
   canonicalizes it to the same form `infochat.adapters.simplex.admin` /
   AdminBootstrap use, so the configured admin's inbound DM resolves to the
   seeded admin `users` row and an admin-only command authorizes. Proven by
   `InboundRouterAdminIdentityMatchTest`.
2. Two distinct contacts map to two distinct, stable `contact_id`s; a non-admin
   contact does not resolve to the admin row. Proven by a second case in the
   same test class.
3. `mvn -B verify` is green from the repo root.

## Out-of-scope

See frontmatter. The single most important boundary: **no token / claim-secret /
out-of-band-secret mechanism.** The operator has explicitly decided not to build
that now (2026-06-27); it is at most a future improvement and is NOT part of this
ticket. Keep configure-by-address; add only the inbound-address mapping.

M1-504 (length floor) already landed. Do not touch Signal identity. Do not
redesign the bootstrap model.

## Notes

- **Accepted v1 tradeoff (operator decision 2026-06-27).** `profile.contactLink`
  is self-asserted by the peer, so in principle a contact could advertise the
  admin's address in its own profile. The operator has accepted this tradeoff for
  v1 to keep admin management simple; hardening it (e.g. a claim-secret) is a
  deferred future improvement, deliberately out of scope here. Document the
  tradeoff where the mapping is read so a future reader sees it was a choice, not
  an oversight — but do NOT add the mitigation in this ticket.
- **Same canonical form is what makes this work.** The operator value is a
  ~43-char short-link fragment; the inbound `profile.contactLink` fragment is the
  same form. Match on that fragment, canonicalized identically to how
  AdminBootstrap canonicalizes the configured value (`canonicalizeContactId` /
  `canonicalizeAndValidateContactId`, AdminBootstrap.java:192). The decimal
  `contactId` and the bot's own 32-char full-link self-id are different forms and
  are not the match key here.
- **Charset / command-injection boundary stays intact.** Whatever identity bytes
  the codec surfaces must still pass `isValidQueueAddressId` before reaching any
  outbound command string — do not relax that gate (design §6.4.4).
- **Isolation.** The join key remains `(adapter, contact_id)`; the inbound match
  site is `InboundRouter.lookupUser` (`SimpleXMessageCodec.java:330` feeds it).
  If the implementation makes the advertised address the DM identity, ensure a
  contact without an advertised address still gets a stable, distinct id (do not
  collapse distinct contacts onto one row).
- **Citations (verified):** inbound byte-equality lookup is
  `InboundRouter.lookupUser` (`USER_SNAPSHOT_SQL`, `WHERE adapter=? AND
  contact_id=?`), not `provider/messaging/UserRepository`. The DM `contactId`
  read is `SimpleXMessageCodec.java:330`; the group sender path is `:445`.
- `docs/spec/deployment.md` §Operator inputs item 2 currently assumes the
  operator-supplied contact id is matchable against inbound traffic. After this
  fix it is — via the advertised-address mapping. Update that section's wording
  to describe the mapping if it currently overclaims a byte-match on the raw
  inbound id.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-505-*.md
```
