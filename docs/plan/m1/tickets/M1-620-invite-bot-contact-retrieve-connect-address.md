---
id: M1-620
title: "Admin subcommand /invite bot-contact: retrieve the bot's own connect contact in-band (SimpleX current URL / Signal number) so admins onboard new contacts without server access"
status: done
created: 2026-07-13
last_updated: 2026-07-13
blocked_by: []
files_budget: 15
complexity: high
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The invite-CODE flow (`/invite create` / `/invite list` / `/invite revoke`)
    and the D44 registration gate. This subcommand returns the bot's own
    shareable CONTACT (a SimpleX contact URL or a Signal number); it is NOT an
    invite code and grants no access by itself. The existing create/list/revoke
    behaviour, argument parsing, and PENDING caps are untouched.
  - >-
    Enumerating multiple adapters' contacts in one reply (`--adapter all`, or
    any no-arg listing of every adapter). Each invocation returns exactly ONE
    adapter's contact: the inbound adapter by default, or the single activated
    adapter named via `--adapter <name>` (the override itself is IN scope per
    operator decision 2026-07-13).
  - >-
    Exposing any other identity or secret. NOT the SimpleX queue keypair, NOT
    signal-cli credentials, NOT other users' contact ids, NOT the admin roster —
    only the bot's OWN onboarding contact. No general adapter-introspection or
    diagnostics surface: add exactly one SPI method for the bot's connect
    contact, not a broader capability/metadata framework.
  - >-
    Non-admin or group-scope access, and any push of the contact into digests,
    welcome messages, or broadcasts. Retrieval is pull-only, bot-admin-only,
    DM-only — reusing the gates already in InviteCommandHandler.
  - >-
    Relaxing D37 for USER/contact data. The bot's OWN connect address is not
    user data; D37 user-data minimization (chat_memory TTL, /forget, /export,
    no-log of user content) is unchanged. The only D37 surface here is that the
    returned value must never be written to a log (see acceptance).
  - >-
    Restoring the M1-518-removed self-address derivation for the SimpleX
    group-mention anchor (D51). The mention anchor stays exactly as it is; the
    address query this ticket adds serves ONLY the new command and has its own
    consumer.
acceptance:
  - >-
    A new DM-only, bot-admin-only subcommand `/invite bot-contact` on the
    existing InviteCommandHandler. In a DM from a bot admin it returns the bot's
    shareable onboarding contact for the INBOUND adapter — or, with an optional
    `--adapter <name>` argument, for the named activated adapter — displayed
    once in the reply. A non-admin caller gets the existing admin-only refusal
    (BundleKeys.ERROR_ADMIN_ONLY); a group-scope caller gets the existing
    `/invite` DM-only refusal. Because it is a subcommand of the already-indexed
    `/invite` handler (no new CommandHandler bean), CommandCatalogueParityTest is
    unaffected.
  - >-
    Per-adapter resolution via ONE new MessagingAdapter SPI method (default
    returns "unsupported"/empty so adapters need not implement it):
    (a) SimpleX returns its LIVE CURRENT contact URL — fetched at command time by
    querying the running simplex-chat over the existing loopback WebSocket
    (SimpleXWebSocketClient.sendCommand, synchronous corrId/ack), which requires
    a new SimpleXMessageCodec encoder for the address query and a new
    response/ack decode variant. "Live" means the value reflects the address as
    it currently is, not a boot-time snapshot.
    (b) Signal returns its registered account/number, already held in-process
    (SignalAdapter `account` / `botAci`) — add an accessor; no new round-trip.
    (c) An adapter that supports neither, or whose live query fails/times out,
    yields a clear friendly reply ("no shareable contact for <adapter>" or a
    transient "address unavailable, try again"), never a crash, a stack trace, or
    a blank line.
    (d) The target adapter is the inbound one by default, or the activated
    adapter whose name() matches `--adapter <name>` (resolved against
    AdapterRegistry's activated set, which InviteCommandHandler already
    injects). An unknown or non-activated name yields a clear friendly reply
    naming the valid adapter names — never a crash.
  - >-
    D37 no-log: the returned contact value is surfaced in the command reply ONLY
    and is never written to any log at any level. The existing outbound path
    (InboundRouter.sendReply / OutboundDelivery) already omits reply bodies from
    logs — no new log statement introduced by this ticket may emit the address,
    and the SimpleX URL is not persisted to application.properties, secrets.env,
    or any file. (Verifiable: no logger call in the new code takes the address as
    an argument.)
  - >-
    Spec updated: a prose entry for `/invite bot-contact` under
    docs/spec/commands.md §Admin (bot admin), and a row added to the
    bot-admin-only permissions / closed-set enumeration in commands.md so the
    LlmOutputSanitizer closed-set and slow-start classifier stay in parity. If
    the design adds an SPI method, docs/spec/messaging.md §Required SPI surface
    gains it. All parity tests (CommandCatalogueParityTest, the sanitizer
    closed-set test) stay green.
  - >-
    Tests: a handler test proving admin+DM returns the contact and both
    non-admin and group-scope are refused; a SimpleX test that the WS address
    query encodes and its response decodes to the expected URL (use a REAL
    captured simplex-chat v6.5.4.1 response frame as the fixture, per the
    live-frame-capture tooling); a Signal test that the accessor returns the
    configured account. Reply strings added as bundle keys in BOTH en.properties
    AND cs.properties (D43 bilateral keyset — a one-sided key fails
    BundleLoaderTest). mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      InviteCommandHandler `bot-contact` subcommand tests: admin+DM returns the
      contact; non-admin refused (ERROR_ADMIN_ONLY); group-scope refused
      (DM-only).
    - >-
      A SimpleX address-query test: the new SimpleXMessageCodec encode produces
      the expected command envelope, and a captured v6.5.4.1 response frame
      decodes to the contact URL; the adapter's SPI method returns it.
    - >-
      A Signal test that the new self-account accessor returns the configured
      account/number.
    - >-
      Handler `--adapter` override tests: admin+DM with `--adapter <other>`
      returns the NAMED activated adapter's contact (not the inbound one); an
      unknown or non-activated adapter name gets the friendly unknown-adapter
      reply.
    - >-
      en.properties + cs.properties keys for the bot-contact reply and the
      no-shareable-contact / unavailable messages (bilateral, D43).
  modifies:
    - >-
      InviteCommandHandler.java (new subcommand branch) and its test.
    - >-
      MessagingAdapter.java (one new SPI method with an unsupported/empty
      default), and the SimpleX + Signal implementations
      (SimpleXAdapter/SimpleXWebSocketClient/SimpleXMessageCodec;
      SignalAdapter accessor).
    - >-
      docs/spec/commands.md (prose entry + permissions/closed-set row) and, if an
      SPI method is added, docs/spec/messaging.md §Required SPI surface.
    - >-
      BundleKeys.java for the new reply keys.
  preserves:
    - all tests currently green on main
    - >-
      the D44 invite-CODE flow (create/list/revoke) — argument parsing, caps, and
      replies unchanged
    - >-
      D37 USER/contact-data minimization (chat_memory TTL, /forget, /export,
      no-log of user content) — unchanged
    - >-
      the D51 / M1-518 SimpleX group-mention anchor — this ticket adds a NEW
      address-query path with its own consumer and does not restore the removed
      mention-anchor derivation
    - >-
      CommandCatalogueParityTest and the LlmOutputSanitizer closed-set parity
spec_refs:
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D37
  - D44
  - D46
  - D51
reviews:
  - round: 1
    date: 2026-07-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 692
      removed: 19
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-13
    verdict: CLEAN
    base: 31d5791914068ac297f660568e67d0fe081e1c04
    head: working tree (pre-commit, branch m1/M1-620-invite-bot-contact-retrieve-connect-address)
    verdict_file: docs/plan/m1/redteam/M1-620-2026-07-13.md
    out_of_model_count: 1
    note: |
      Pre-commit audit (between review APPROVE and commit) per operator
      directive 2026-07-13. CLEAN — zero findings at every severity. One
      advisory out-of-model observation recorded in the verdict file; no
      remediation ticket needed.
outline_file: target/m1-tick-outline-M1-620.md
clarity_check:
  date: 2026-07-13
  verdict: WARN
  warnings:
    - >-
      files_budget (12) is plausibly ~2 files short of the ticket's own
      test_plan-implied file count (~14: handler+test, 5 production adapter/SPI
      files, 2 spec docs, BundleKeys, en+cs bundles, plus the new SimpleX and
      Signal test additions). Not severe enough to block, but worth bumping to
      avoid a mid-implementation files-budget escalation.
  blockers: []
---

# M1-620: /invite bot-contact — retrieve the bot's own connect contact in-band

## Context

Onboarding a new person to the bot needs two things: (1) the bot's **connect
contact** — a SimpleX contact URL (`https://smp…/a#…`) or, on Signal, the bot's
number — so the person's app can reach the bot; and (2) an **invite code**
(`/invite create`, D44) that opens the DM registration gate. Item (2) is already
an in-band admin command. Item (1) is **not**: the SimpleX contact URL is only
printed to the operator terminal at provisioning (`6b-simplex-provision.sh`,
D37), so an admin who wants to onboard a new SimpleX contact must have shell
access to the server to read it. That is an operability gap surfaced during the
v1-release handoff review.

The contact address is **not classified as a secret** (`security.md` has no such
classification). D37's constraint on it is specifically about **not persisting
it** to logs / application.properties / secrets.env — it says nothing against a
bot admin viewing the bot's own shareable link in-band. So exposing it to an
authenticated admin over an already-established DM closes the gap without
weakening D37.

The blocker that makes this `complexity: high`: there is currently **no runtime
path** to the SimpleX self-address. The `/show_address` startup derivation was
removed as consumer-less in M1-518 (D51), the MessagingAdapter SPI has no
self-identity method, and the SimpleX WS codec has no encoder/decoder for an
address query/response. Signal, by contrast, already holds its `account`/`botAci`
in-process (just without a getter). So SimpleX needs a new SPI method + a new WS
request/response codec path; Signal needs only an accessor.

## Acceptance

See the YAML `acceptance:` list. In prose: add a DM-only, bot-admin-only
`/invite bot-contact` subcommand to InviteCommandHandler (reusing its existing
gates, so no new command bean and no command-index change); resolve the contact
for the target adapter — inbound by default, or the activated adapter named via
an optional `--adapter <name>` argument, with a friendly reply naming the valid
choices when the name matches nothing — through one new MessagingAdapter SPI
method — SimpleX returns
its **live current** contact URL by querying the running simplex-chat over the
existing loopback WS (new codec encode + response decode), Signal returns its
registered number via a new accessor, and an unsupported/unavailable adapter
replies with a friendly message, never a crash. The returned value is shown once
and never logged (D37). Update commands.md (prose + permissions/closed-set row)
and, if an SPI method is added, messaging.md; keep all parity tests green. Reply
strings are bilateral en+cs bundle keys.

## Out-of-scope

No change to the invite-CODE flow or the D44 gate; no multi-adapter enumeration
in one reply (the single-target `--adapter <name>` override IS in scope); no
exposure of any other identity/secret and no general adapter-introspection
surface; no non-admin/group access and no pushing the contact into
digests/welcome/broadcasts; no relaxation of D37 for USER data; no restoration
of the M1-518-removed mention-anchor derivation.

## Notes

- **Reuse the existing gates.** InviteCommandHandler already rejects group scope
  (DM-only) and checks `!actorOpt.get().isAdmin` before acting. The new
  subcommand slots into the existing subcommand switch and copies the same admin
  pattern — there is no shared `@Admin` guard to reuse (every admin handler
  inlines the check).
- **"Live/current" semantics** (per the operator's stated expectation): fetch the
  SimpleX URL at command time so it reflects the address as it currently is, not
  a boot-time snapshot. The SimpleX WS client's `sendCommand` is synchronous
  (registers a `CompletableFuture` keyed by corrId, blocks on the ack) — a
  blocking SPI call on the inbound-handling thread is acceptable. If the query
  fails or times out, reply with a friendly transient error.
- **Real wire format.** simplex-chat v6.5.4.1 response shapes changed (D51). Use a
  REAL captured `/show_address` response frame as the decode fixture — capture it
  with the live-frame probe tooling rather than guessing the JSON shape.
- **M1-518 relationship.** Re-adding a *consumed* address query is not undoing
  M1-518 (which removed a *vestigial, consumer-less* derivation). It now has a
  real consumer (this command); the D51 group-mention anchor is not touched.
- **Signal is cheap.** `SignalAdapter` already derives `botAci` at start and
  holds the configured `account`; expose it via an accessor — no signal-cli
  round-trip.
- **Not a hard release blocker** (the SimpleX URL is stable — an operator can
  read it once from provisioning), but filed as a v1 ticket per operator
  decision: in-band retrieval is the expected onboarding UX and shell access
  should not be required per invite.
- **files_budget bumped 12→15 pre-start (operator-approved, 2026-07-13):** the
  clarity pre-flight enumerated ~14 test_plan-implied files against the filed
  budget of 12; bumped with headroom for a captured-frame fixture resource.
- **Design forks RESOLVED (operator, 2026-07-13, pre-start):** subcommand shape
  (`/invite bot-contact`), Signal-returns-the-number, and live per-call SimpleX
  query all CONFIRMED as filed. The `--adapter <name>` override was PROMOTED
  from deferral to v1 scope by operator choice — it falls out cheaply because
  InviteCommandHandler already injects AdapterRegistry and iterates
  activatedAdapters(), and adapters expose name(); the override is a name-match
  over that set, with a friendly unknown-name reply. Multi-adapter enumeration
  in one reply stays out of scope.
- **Design fork for the plan phase, already narrowed:** the live-query approach
  above is the chosen path (it honors D37 without persisting anything and
  delivers the "current" value). The alternative — persist the address at
  provisioning into a provider-readable file and read it back — is simpler code
  but would require an explicit D37 clarification (it persists the address) and
  could serve a stale value; it is recorded here as considered-and-not-chosen.
