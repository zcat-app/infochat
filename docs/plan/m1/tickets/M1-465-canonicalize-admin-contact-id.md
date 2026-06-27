---
id: M1-465
title: Canonicalize bootstrap admin contact id from full link
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 11
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/startup/AdminBootstrap.java
  - prod/scripts/6-adapter.sh
  - docs/design/06-messaging.md
  - docs/design/07-deployment.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXCanonicalizeContactIdTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BootstrapAdminParseGateTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/startup/AdminBootstrapIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  # Signal / in-memory adapters: no contact-link form exists, so they keep the
  # default identity canonicalization — do NOT add link parsing there.
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/**
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/**
  # The bot's OWN identity derivation at startup is already correct; not touched.
  # The inbound byte-equality matching logic is not touched.
  # No reimplementation of the extraction in bash (6-adapter.sh prompt text only).
acceptance:
  - SimpleXCanonicalizeContactIdTest.extractsBareQueueIdFromFullContactLink passes
  - SimpleXCanonicalizeContactIdTest.passesThroughAnAlreadyBareQueueId passes
  - SimpleXCanonicalizeContactIdTest.returnsInputUnchangedWhenLinkHasNoExtractableQueueId passes
  - BootstrapAdminParseGateTest.adminGivenAsFullContactLinkIsAcceptedAfterCanonicalization passes
  - AdminBootstrapIT.adminGivenAsFullContactLinkSeedsBareQueueIdRow passes
  - 6-adapter.sh SimpleX admin prompt instructs the operator to paste the full SimpleX address link (or the bare id); no bash extraction is added
  - mvn verify is green
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXCanonicalizeContactIdTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BootstrapAdminParseGateTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/startup/AdminBootstrapIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/messaging.md §Per-adapter trust level and identity
decision_refs:
  - D32
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-465: Canonicalize bootstrap admin contact id from full link

## Context

The per-adapter bootstrap admin contact id (`infochat.adapters.<name>.admin`)
must byte-equal the contact id the adapter reports for that contact's inbound
messages, or the seeded admin row is one "no real contact can ever claim"
(`docs/spec/deployment.md` §Operator inputs item 2). For SimpleX that id is the
bare **queue address** (URL-safe base64), which the operator must currently
extract *by hand* from their SimpleX contact link — the wizard prompt gives no
guidance and accepts the raw string verbatim. Hand-extraction is error-prone:
the queue id is a path segment buried inside the percent-encoded `smp=`
parameter of the link's fragment (`docs/design/06-messaging.md` §6.10), not
simply "the part after `#`", so an operator easily pastes the wrong base64
token. It passes the shape gate (`SimpleXIdentity.isWellFormed` — length + char
set only) yet never matches inbound, leaving a deployment that looks
bootstrapped but has an unreachable admin.

The bot already solves this exact problem for its **own** identity: at
`start()` it queries simplex-chat for its address and extracts the bare queue id
via `SimpleXMessageCodec.extractQueueAddressId(contactLink)` — documented as
"the same identifier an operator extracts manually from their address". This
ticket reuses that one extraction so an operator can paste their **full** SimpleX
address link and have the system canonicalize it to the matching bare id —
eliminating the hand-extraction mismatch class. Doing it in Java (not bash) is
deliberate: the extraction is real URI parsing, and a bash reimplementation
would duplicate the grammar and drift.

## Acceptance

- New SPI method `MessagingAdapter.canonicalizeContactId(String raw)` returns the
  canonical bare contact id for an operator-supplied value. The interface
  provides a **default** that returns `raw` unchanged (correct for Signal ACIs
  and the in-memory adapter, which have no link form). `SimpleXAdapter` overrides
  it: when `raw` is a SimpleX contact link (carries an `smp=` parameter) it
  extracts the bare queue id by reusing the existing
  `SimpleXMessageCodec.extractQueueAddressId`; an already-bare value, or a link
  with no extractable queue id, is returned unchanged so the existing
  `isWellFormedContactId` gate still makes the accept/reject decision.
- The bootstrap-admin consumption path canonicalizes **before** validating and
  seeding: `AdapterRegistry`'s parse gate (gate 7b) runs
  `canonicalizeContactId` then `isWellFormedContactId` on the result, and
  `AdminBootstrap` seeds the **canonicalized** value — so a value supplied as a
  full link both passes startup and seeds the bare queue id that inbound
  messages byte-match.
- `SimpleXCanonicalizeContactIdTest` proves: full link → bare queue id (equal to
  what `extractQueueAddressId` yields for that link); already-bare id →
  unchanged; link with no extractable queue id → unchanged (so the well-formed
  gate then rejects it).
- `BootstrapAdminParseGateTest.adminGivenAsFullContactLinkIsAcceptedAfterCanonicalization`
  proves a full-link admin value no longer trips the parse gate.
- `AdminBootstrapIT.adminGivenAsFullContactLinkSeedsBareQueueIdRow` proves the
  seeded `is_admin=true` row carries the bare queue id, not the raw link.
- `prod/scripts/6-adapter.sh`'s SimpleX admin prompt tells the operator to paste
  the full SimpleX address link (or the bare id) — prompt text only, no bash
  extraction.
- `mvn verify` is green.

## Out-of-scope

- **Signal and in-memory adapters** keep the default identity canonicalization —
  they have no contact-link form, so adding link parsing there is scope drift.
- The bot's **own** identity derivation at startup
  (`SimpleXAdapter#deriveAndAdoptIdentity`) is already correct and is not
  changed; this ticket only adds canonicalization of the **operator-supplied
  admin** value, reusing that path's existing extractor.
- The inbound **byte-equality matching** logic (§6.2.3 / §6.4.4) is unchanged.
- No bash reimplementation of the extraction — `6-adapter.sh` changes are prompt
  wording only.
- `AdminBootstrapValidationOrderIT` is expected to stay green unchanged
  (canonicalization is a new step *inside* gate 7b, before the well-formed
  check; it does not reorder the gates).

## Notes

- Reused extractor: `SimpleXMessageCodec.extractQueueAddressId` (currently
  `private static`) — exposing it package-private to `SimpleXAdapter`, or adding
  a thin package-private wrapper, is the intended minimal change. Keep it the
  single source of extraction truth (the §Context drift argument).
- **Default method vs abstract SPI method.** The existing
  `isWellFormedContactId` is abstract (a missing impl = no validation = a
  security gap, so it must not be skippable). `canonicalizeContactId` is the
  opposite: a missing impl = identity = "operator must supply the bare id", which
  is exactly today's safe behavior. So a **default** (identity) method is the
  safer, smaller choice and keeps Signal/in-memory out of the diff. If the
  reviewer prefers the abstract-for-all-adapters shape for consistency, that is a
  files_budget + Signal/in-memory scope change to negotiate via
  `escalate → refine`, not a silent expansion.
- Canonicalize at **both** the registry gate and `AdminBootstrap` via the same
  SPI call (idempotent on a bare id), so the validated value and the seeded value
  cannot diverge.
- Relevant design: `docs/design/06-messaging.md` §6.10 (bot-identity extraction),
  §6.4.4 (id formats); `docs/design/07-deployment.md` §7.6.3 (bootstrap admin).
  Update 06-messaging to note the admin value accepts a full link (canonicalized
  via the same extractor), and 07-deployment §7.6.3 likewise.
- Adjacent code to match: `AdapterRegistry` gate 7b dispatch already calls the
  `isWellFormedContactId` SPI polymorphically — `canonicalizeContactId` slots in
  immediately before it the same way.
