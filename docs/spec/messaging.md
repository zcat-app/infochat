# Messaging adapters

This file specifies the contract between Provider and the messaging                                                                                                                                                                                   
backend. Concrete adapter implementations (SimpleX wire protocol, the                                                                                                                                                                                 
in-memory test adapter), property keys, and capability defaults live in                                                                                                                                                                               
`docs/design/06-messaging.md`.

## Goals

1. **Adapter pluggability.** A new transport (Telegram, Matrix, etc.)
   is a class drop-in, not a rewrite. v1 ships SimpleX and Signal
   (decision D32).
2. **Identity is the trust anchor.** The adapter's cryptographic contact                                                                                                                                                                              
   id is the *only* identity the system trusts (decision D10). Display                                                                                                                                                                                
   names are informational.
3. **Capability negotiation, not feature flags.** Provider asks the              
   adapter what it supports; adapter says yes/no. No transport-specific                                                                                                                                                                               
   conditionals leak into business logic.
4. **Adapter is a thin transport.** All policy (commands, permissions,                                                                                                                                                                                
   ban handling, rate limits, formatting, translation) lives in                  
   Provider. Adapters move bytes and assert identity.
5. **Consistent UX across adapters.** Plain-text formatting (decision                                                                                                                                                                                 
   D30) means the bot reads the same way whether the user is on SimpleX,
   Signal, or a future text-only transport.

## Required SPI surface

Every adapter implements:

- **Identity assertion.** Each adapter asserts the sender's stable,
  cryptographically-anchored contact id (plus optional display name) at
  wire-decode time — the point where the transport's verified identity
  material lives — and carries the result on every inbound message it
  delivers to Provider. There is no separate identity-assertion SPI
  method: identity is bound to inbound-message construction. A message
  whose identity cannot be asserted is dropped at decode, before
  delivery. An adapter that cannot anchor the id to a keypair MUST be
  marked low-trust (`trustLevel = LOW`); Provider rejects a low-trust
  adapter at registration unless the operator opts in explicitly (see
  §Capability flags).
- **Receive.** Pushes inbound `(scope, contact_id, body)` to Provider.
  Group messages arrive only when the bot is `@mentioned`; the
  mention is stripped before delivery (the adapter may do the strip,
  or Provider may do it consistently across adapters — see design
  notes).
  **Mention-recognition rule.** Whether a group message counts as
  an `@mention` of the bot is decided **only** by the cryptographic
  contact id of the mention target (decision D10): the adapter's
  mention payload references the bot's contact id, and the
  comparison is byte-equality against the bot's
  **per-adapter contact id** for the inbound adapter (one Provider
  may have multiple adapters configured per `deployment.md`
  §Topology, and each adapter has its own bot identity). Both v1
  adapters expose mention anchoring at the protocol layer —
  SimpleX by the bot's **per-group member id** (decision D51; the
  queue address the mention payload carried before simplex-chat
  v6.5.4.1 is gone, so the anchor is the cryptographic group
  member id the mention resolves to, compared against the bot's
  own `groupInfo.membership.memberId`), Signal by ACI (the UUID
  Signal binds to its identity keys; surfaced by `signal-cli` as
  `mentionUuid`). Display-name matching is
  **never** sufficient — an attacker who can spoof or impersonate
  the bot's display name in a group must not be able to suppress
  legitimate mentions or fake mentions of the bot.
  For adapters whose underlying protocol exposes no mention
  primitive at all (no @-mention payload, no mention metadata),
  the adapter MUST surface this in its capability flags
  (`supportsMentionByContactId = false`). v1 has no per-deployment
  groups-off toggle — group routing is wired unconditionally for
  every activated adapter — so there is no isolated "group mode"
  to leave disabled: Provider rejects such an adapter at startup
  rather than running it DM-only. Group access requires a
  cryptographic mention anchor in v1. Falling back to
  string matching on the bot's display name in the message body
  is explicitly out of v1 (see `security.md` §"What's intentionally
  NOT in v1").
- **Send.** Provider sends a (scope, body); adapter returns an opaque
  message handle.
- **Update.** Given a handle, replace the message body. Optional —                                                                                                                                                                                    
  adapters that don't support edits return a "not supported" signal so                                                                                                                                                                                
  the progress notifier can fall back gracefully.
- **Finalize.** Given a handle, mark the message complete (the                                                                                                                                                                                        
  notifier's terminal state). For adapters with edit support this is                                                                                                                                                                                  
  one last `update`; for others it's the only `send` that ever happened.
- **Typing indicator.** `setTyping(scope, bool)`. Optional.
- **Membership events.** Optional. Adapters that can detect
  group-membership changes surface them as
  `user_joined_group(group_id, contact_id)` and
  `user_left_group(group_id, contact_id)` events to Provider.
  Provider applies the soft-clear / re-add semantics in
  `schema.md` §Identity and access (Group membership) and §Failure
  handling below. Adapters without a native left-group signal MUST
  set `supportsMembershipEvents = false` (capability flag) and
  MUST NOT synthesise a left-group event from inactivity. On such
  adapters — **both v1 production adapters** — per-user leave
  cleanup is an explicit **non-commitment** in v1: a group-scope
  send is addressed to the group, not to an individual member, so a
  departure produces no per-user permanent delivery failure to fall
  back on, and the permanent-delivery-failure-driven cleanup path
  (§Failure handling — "User left group") covers only the
  bot-removed-from-group and group-deleted cases. The bot-removed
  and group-deleted events are separate from per-user membership
  events and continue to flow through their existing adapter
  signals.
- **Inbound message size cap.** Adapters SHOULD enforce a
  **transport-layer maximum inbound message size** that is tighter
  than the application-level chat-mode body cap (`commands.md`
  §Surface conventions). A message exceeding the transport cap is
  dropped by the adapter before delivery to Provider; the
  application-level cap then applies as a second defense. The
  transport-layer cap value is adapter-specific and lives in design
  notes. An adapter that cannot enforce a transport-layer cap (e.g.,
  because the underlying protocol provides no such mechanism) MUST
  rely solely on the application-level cap and document this in its
  design note.
- **Bot connect contact.** Optional. Returns the bot's own shareable
  onboarding contact for this adapter — the value a new person enters
  into their app to reach the bot (SimpleX: the **live current**
  contact URL, queried from the running transport at command time;
  Signal: the registered number). Default answer is "no shareable
  contact", so adapters without one need no change. The value is
  display-only: surfaced once in the `/invite bot-contact` admin reply
  (`commands.md` §Admin) and never logged at any level or persisted to
  any file (D37). Exactly one SPI method — this is not a general
  adapter-introspection or metadata surface, and it exposes no other
  identity material (not the SimpleX queue keypair, not signal-cli
  credentials).
- **Send attachment (optional).** Provider hands the adapter a
  `(scope, file path, MIME type, display filename)` tuple; the adapter
  transmits the file as a native file/image message on transports that
  support one (decision D74). On SimpleX the recipient's client
  receives the file as an XFTP file invitation and decides itself when
  to download — a delivered attachment appearing as an item the
  recipient must accept is the transport's normal behaviour, not a
  delivery defect. On SimpleX, an image attachment is sent as an image
  message carrying a small inline preview; the full-resolution file
  behind it still arrives as an XFTP file invitation the recipient's
  client accepts itself. Non-image attachments, and images without a
  preview, are sent as plain file messages. The payload is a **file path**, not
  bytes: signal-cli attaches by path and SimpleX file transfer
  completes asynchronously past `send()`'s return, so the file MUST
  remain readable by the adapter for the whole transmit, and the
  adapter reports delivery completion (success, or a classified
  failure per §Failure handling) so Provider's spool lifecycle can
  reclaim it — the adapter itself MUST NOT retain or copy the payload
  beyond delivery. Provider invokes this method only when the
  adapter's `supportsOutboundAttachments` flag is true, and refuses
  payloads above `maxOutboundAttachmentBytes` before invoking it.
  Attachment sends obey the same transient/permanent classification,
  the same bounded retry ladder, and the same at-least-once delivery
  non-guarantee (D64) as text sends; an ambiguous attachment transmit
  may duplicate exactly as a text one may.
- **Capability flags.** A static description of what the adapter
  supports: identity trust level, markdown rendering, message edits,
  edit minimum interval, typing indicator, membership events, and
  any future flag a new transport needs.

## Capability flags (minimum set)

- `trustLevel` — `HIGH` for cryptographically anchored ids, `LOW`
  otherwise. Exposed as an adapter-*instance* property (a
  `trustLevel()` accessor on the adapter), not a member of the
  capability-flags structure, so a single adapter class can present
  two trust postures from different instances (e.g. the in-memory
  test double). Provider rejects a `LOW`-trust adapter at
  registration (startup), before any message is processed, unless
  the operator explicitly opts in via
  `infochat.adapters.<name>.allow-low-trust`.
- `supportsCodeFormatting` — when true, code spans render as
  monospace. When false, the user sees backticks (still readable,
  decision D30). **Renamed from `supportsMarkdownCode`** because the
  prior name implied broader markdown support; v1 adapters render
  *only* code spans, never markdown links or other markdown
  features. URLs are always rendered bare (D30).
- `supportsMarkdownLinks` — **MUST be false for every v1 adapter.**
  Provider **validates this flag at adapter registration (startup)**:
  if any registered adapter declares `supportsMarkdownLinks = true`,
  Provider fails fast at startup with a fatal log message identifying
  the adapter. This is a startup-fail-fast check, not a per-message
  check — widening the render surface is a spec amendment, not a
  configuration choice, and a per-message check would miss adapters
  that silently upgrade their capabilities.
- `supportsMessageEdit` — required for in-place progress updates.
- `minEditInterval` — adapter-imposed floor between edits on the same                                                                                                                                                                                 
  message; the progress notifier honors `max(adapterMin, system floor)`.
- `supportsTypingIndicator` — drives the typing-on/off pulses around
  long-running requests.
- `supportsMentionByContactId` — true when the adapter's protocol
  carries an `@mention` anchored to the mentioned user's
  cryptographic contact id (SimpleX per-group member id per D51,
  Signal ACI).
  Required-true for any adapter that exposes group mode in v1; an
  adapter with this flag false MUST disable its group SPI.
  Display-name string matching is never an acceptable fallback
  for mention recognition (see §Required SPI surface — Receive).
- `supportsMembershipEvents` — true when the adapter exposes a
  native `user_joined_group` / `user_left_group` signal at the
  protocol layer. When false, per-user leave cleanup is a v1
  non-commitment (§Failure handling — "User left group"): Provider
  does not synthesise membership events from inactivity, and the
  permanent-delivery-failure-driven cleanup path covers only the
  bot-removed / group-deleted cases, not per-user leaves.
- `maxInboundMessageBytes` — transport-layer first-defense cap on
  inbound message size. Tighter than the application-level chat-mode
  body cap (§Required SPI surface — Receive discusses the inbound cap;
  this flag is where its value is negotiated): the adapter drops an
  over-size message before it reaches Provider, so the transport bound
  is enforced by the component that owns the transport.
- `maxSendsPerSecond` — token-bucket cap on outbound `send` calls per
  second, averaged over a one-second window. Adapters pace their own
  transmits to this. Outbound *concurrency* is bounded separately by
  the transport's one-outstanding-send rule, not by a capability
  field.
- `supportsOutboundAttachments` — true when the adapter can transmit a
  binary attachment as a native file message (decision D74). Defaults
  to false; a caller with attachment work (e.g. `/image`) gates on the
  flag and, when it is false, answers with the text fallback its own
  failure contract specifies — it never invokes the send-attachment
  SPI on a false-flag adapter.
- `maxOutboundAttachmentBytes` — the transport's ceiling on a single
  outbound attachment. Meaningless when `supportsOutboundAttachments`
  is false. Provider refuses over-ceiling payloads before invoking the
  adapter; the value bounds user-facing size flags (e.g. `/image
  --resolution`) however the bytes are produced.

Outbound-attachment flags joined this list with the `/image` feature
(D74). Further flags (voice, reactions, etc.) extend this list.
Provider must treat an unknown flag as "not supported" by default.

## Message handles

A message handle is an opaque token returned by `send()`. It lets the                                                                                                                                                                                 
caller subsequently `update` or `finalize` the same visible message.

- Contents are adapter-defined.
- Callers **MUST NOT** persist a handle to the database, **MUST NOT**
  pass it between service instances, and **MUST NOT** inspect or
  rely on the contents. It is valid only within the originating
  adapter, in-process. Holding it in memory for a single request's
  processing (placeholder → updates → finalize) is the intended use.
- Handles are how the progress notifier turns a stream of stage events
  into a single visibly-evolving message.

## Progress notifications

Long-running handlers (`/summary`, periodic digest, chat agent) publish                                                                                                                                                                               
stage events to a cross-cutting `ProgressNotifier` (decision D31). The                                                                                                                                                                                
notifier:

1. Acquires a placeholder message via `send()` and captures the handle.
2. Turns on typing if the adapter supports it.
3. Receives stage events (`STARTED`, `RETRIEVING`, `GENERATING`,                                                                                                                                                                                      
   `TRANSLATING`, `FINALIZING`) and renders each as a *localized* string                                                                                                                                                                              
   via `update(handle, text)`. Edits are coalesced; only the latest
   pending text is transmitted at the next eligible tick.
4. On terminal `COMPLETED` / `FAILED`, calls `finalize(handle, text)` and        
   turns off typing. Both are guaranteed via try/finally — placeholders                                                                                                                                                                               
   are never left dangling.

Constraints:

- Stage strings are looked up by enum from the deterministic
  localization bundle (decision D43; `llm.md` §Translation flow).
  **User input is never interpolated into progress strings** —
  security requirement, prevents reflective injection in screenshots
  and logs. Stage strings are template-parameterized only with
  **deterministic, sanitized scalar values** (post counts,
  controlled-vocabulary tag names, fixed enum labels). Free-form
  user-authored text (custom personal tags, free-form chat) is
  **never** interpolated, even via a "safe" placeholder.
- Adapters without `supportsMessageEdit` collapse to a single final                                                                                                                                                                                   
  `send` of the completed text. Business logic does not change. The                                                                                                                                                                                   
  caller does not know which transport it has.
- Short, deterministic SQL commands bypass the notifier entirely.

The exact event names, edit interval floor, and localization-bundle
structure live in design notes.

## Per-adapter trust level and identity

Every v1 adapter has a documented trust level and a documented
contact-id shape. Operators picking an adapter know exactly what
kind of identity assertion they are getting.

- **SimpleX.** Trust level: **HIGH**. The contact id is a
  cryptographic queue address — there is no human-readable user
  name layer; the address itself is the identity. Display name is
  informational only. Adapter asserts identity via SimpleX's
  cryptographic message-routing layer; the bot trusts it as the
  D10 anchor.
- **Signal.** Trust level: **HIGH**. The contact id is the user's
  ACI (the UUID Signal binds to its identity keys, surfaced by
  `signal-cli` as `mentionUuid`) — not the phone number (E.164) or
  username, which are discovery identifiers only and may change
  without changing the ACI. The Signal protocol's Sealed Sender / sender
  certificate provides cryptographic identity assertion at the
  message layer; display name is informational. The Signal
  identity assertion is the D10 anchor on Signal. **Cross-adapter
  isolation invariant** (`security.md` §Invite-code registration):
  a Signal contact id is **never** matched against a SimpleX
  contact id even on byte-equality — the `(adapter, contact_id)`
  tuple is always the join key, so `users.contact_id` plus
  `users.adapter` together identify a row. Invite codes scoped to
  Signal cannot be consumed by a SimpleX contact and vice versa.
- **InMemory** (test only). Trust level: **configurable, defaults
  to LOW**. Tests that exercise admin paths opt into HIGH
  explicitly; the default-LOW makes accidental privilege escalation
  in a test harness impossible by default.

A future adapter (Telegram, Matrix, etc.) lands its trust-level and
identity-assertion shape in this section before being enabled in
production.

## Identity and groups

- Inbound message → adapter resolves to `(contact_id, scope)`. DM scope                                                                                                                                                                               
  is just the user; group scope is `(group_id, user_id)`.
- The adapter is responsible for surfacing a stable per-group id
  (cryptographic where possible). The Provider creates a `groups` row
  on the first @mention from a registered user
  (`registration_state IN ('invited', 'vouched')`) per D47. The row
  is created with `approval_status = 'pending'` and `activated_by` =
  the registered user's id. The bot does not create a `groups` row on
  "first sight" — only on first registered-user interaction. Concurrent
  first-@mentions in the same group are handled via
  `INSERT ... ON CONFLICT (adapter, upstream_group_id) DO NOTHING`;
  the loser re-reads the row.
- Groups behave per `commands.md` and decision D16: bot replies only on
  `@mention`; group destructive ops require group admin; periodic
  summaries fire on per-group local time.

## Output formatting (transport view)

Provider produces plain text per decision D30. The adapter:

- May choose to render single-backtick spans as inline code if
  `supportsCodeFormatting = true`.
- MUST leave URLs bare (no markdown link wrapping) regardless of                                                                                                                                                                                      
  capability — the URL is the citation; obscuring it would defeat the                                                                                                                                                                                 
  point.
- MUST NOT mutate command output (e.g. `/help`). Only LLM-authored                                                                                                                                                                                    
  output is subject to the chat output sanitizer (`security.md`); the            
  adapter doesn't know which is which.

## Failure handling

- Send/update/finalize failures are reported to Provider as exceptions
  with a category (transient vs. permanent). The categorisation rule
  is the spec-level commitment below; adapters MUST classify each
  failure cause into one bucket or the other before raising:
  - **Transient.** Network timeout, TCP/TLS reset, transport
    rate-limit response, transport's "try again later" / 5xx-style
    signal, ephemeral signing-server unavailability. Retried per
    the policy below.
  - **Permanent.** User has blocked the bot, group no longer exists
    or the bot is no longer a member, recipient identity has been
    rotated/revoked, message rejected as a policy violation by the
    transport. Aborts the affected reply, never retried.
  An adapter that cannot tell the two apart MUST default to
  permanent — silently looping a permanent failure is a worse
  failure mode than aborting an occasionally-transient one.
- **Transient retry policy** is **uniform across adapters and
  channels** so the Provider's per-user rate limiter remains the
  single source of truth for "slow this user down":
  - **Maximum attempts:** 3 (the original send plus two retries).
  - **Schedule:** exponential back-off with full jitter; the base
    delay between attempts doubles each iteration (start delay,
    growth factor, and jitter window are profile-driven and live
    in design notes — the spec commits to the *shape*: bounded
    attempt count, exponential growth, full jitter).
  - **Terminal action on cap exhaustion:** the failure is escalated
    to **permanent** for the rest of this reply's lifecycle: the
    affected reply is aborted, the adapter does not enqueue another
    retry, and the throttled-admin-notification path
    (`security.md` §Failure handling) fires per
    `(channel, error_class)`. The user is not pinged about the
    failed delivery; the next inbound from the same scope reuses
    the standard intake path.
  - **No silent extension.** An adapter MUST NOT add its own retry
    wrapper on top — the policy above is the only retry layer
    between Provider and the transport. Transport-internal
    back-pressure (e.g. an HTTP 429 returned by a Signal proxy)
    surfaces as a transient failure and counts toward the same
    attempt budget.
- An adapter cannot silently drop messages. Either delivery succeeds,
  the caller learns it didn't (after the bounded retry budget), or
  the failure is permanent and surfaced immediately.
- **A peer-initiated connection close is a transport-death event.**
  When the peer closes (or a transport error kills) the connection an
  adapter depends on — the SimpleX bot WebSocket, the Signal JSON-RPC
  channel — the adapter MUST latch that connection as dead and drive
  recovery of the transport, paced per the design-tier reconnect
  cadence (design notes §6.4.6 / §6.5.8), not merely drain the
  in-flight command futures on the dead connection. The recovery route
  is adapter-specific and each adapter states its own: SimpleX rebuilds
  the WebSocket in place against the still-running daemon, while Signal
  restarts the daemon, routing recovery through the same supervised
  backoff path a crash takes (design notes §6.5.8). Signal's restart
  fires from either of two detectors: the
  reader-side death latch (the JSON-RPC channel died while signal-cli
  keeps running — detected from the channel's own read side, with no
  dependence on outbound traffic) or the consecutive-response-timeout
  escalation (a daemon that is alive but not answering, which a live
  reader cannot detect). The escalation's restart kills the daemon,
  which kills the channel, which exits the reader — so for that one
  death the latch defers to the restart the escalation already
  requested rather than forcing a second one. The outage stays
  operator-visible for as long as it lasts (`connected()` /
  `adapter.connection.status` report the dead transport — no false
  green), and sends attempted after the latch, while recovery is
  pending, classify transient — unless the supervising component has
  terminally failed, which classifies permanent per the default above.
  That transient classification covers only sends *attempted after*
  the death is latched. Commands already in flight when the connection
  dies are outside it: their futures drain with the closed-before-ack
  PERMANENT both adapters stamp — retrying an unacked command against
  the dead socket cannot succeed, and whether the transport later
  recovers is unknowable at drain time — so a send that raced the
  close can learn permanent even though recovery completes moments
  later. Queued-but-undelivered *inbound* is likewise dropped at the
  latch (at-most-once inbound), but never silently: the discarded
  depth is added to the adapter's dropped-inbound readiness field and
  logged.
- **Outbound delivery is at-least-once.** A retry after an *ambiguous*
  send failure — the transport accepted the frame but the ack timed
  out — MAY deliver the message twice, and **no component suppresses
  that duplicate**: no adapter deduplicates, and the Provider-side
  delivery chokepoint keeps no ledger. The recipient may occasionally
  see a repeated message; bot output is not safety-critical, and v1
  accepts this rather than claiming a guarantee it does not provide.
  The reason it is not fixed at the chokepoint is structural: only the
  adapter or the transport can observe whether an ambiguous transmit
  actually landed, and the chokepoint learns of success only *after*
  the adapter reports it — so it is blind to precisely the case that
  produces the duplicate. At-most-once would require adapter- or
  transport-level support and is out of scope for v1 (**D64**).
- Adapter-internal back-pressure (e.g. rate limits enforced by the
  transport) surfaces as transient failures so the Provider's
  per-user rate limiter is the single source of truth for "slow
  this user down". Cumulative cost is bounded by the attempt cap
  above.
- **Permanent delivery failure cleanup.** Permanent failures (the
  user blocked the bot, the group is gone, credentials revoked)
  abort the affected reply **without advancing chat session state**
  — the context window remains as if the message was never
  generated, and `chat_memory` is not written. For periodic group
  digests, the failure is logged; the next slot retries. Sustained
  permanent failure on a group triggers the bot-removed-from-group
  handler below. The retry queue does not re-attempt permanent
  failures.
- **Per-category digest delivery attribution (D63).** A
  non-degraded periodic digest is delivered as a sequence of N
  category messages through the chokepoint primitive
  `OutboundDelivery.deliverSequenceToGroup(adapter, messages, groupId)`.
  Each message runs the existing per-message TRANSIENT-retry /
  PERMANENT-abort ladder unchanged; the per-group permanent-failure
  counter that drives the bot-removed-from-group handler below
  receives at most ONE aggregate outcome per slot — **any success
  resets the counter, all-permanent increments once, an interrupt
  that stops the sequence early attributes nothing.** This is not
  optional hardening: the counter's documented "always > 1"
  invariant (below) was calibrated for one message per slot, and
  SimpleX still stamps instant PERMANENTs faster than one per slot:
  a command in flight when the WebSocket dies drains
  closed-before-ack PERMANENT (`onClose` fails every pending future
  that way; `SimpleXWebSocketClient.sendCommand` on a closed or
  not-yet-started client the same — new sends during peer-close
  recovery classify transient per the transport-death bullet above,
  but a terminally failed supervisor classifies every send
  PERMANENT), so naive per-category `deliverToGroup` calls would
  let one simplex-chat outage during the sequential loop yield ≥3
  instant PERMANENTs in milliseconds — soft-removing a healthy
  group with no admin notification, where the same blip today costs
  a single increment. Per-message failure classification, backoff,
  max-attempts, and the threshold value are untouched; single-
  message callers (`deliverToGroup`) keep today's per-call
  attribution. Redelivery may duplicate (D63, D64): nothing in v1
  records which categories were delivered, so `/retry --digest`
  re-posts every category, including any that already landed.
- **Bot removed from group.** When the adapter detects the bot has
  been removed from a group (via an adapter-specific signal, or
  via repeated permanent send failures past a profile-driven
  threshold), Provider sets `groups.removed_at = NOW()` and
  cancels the periodic-digest scheduler entries for that group.
  **The permanent-failure threshold is always greater than 1** — a
  single permanent failure does not trigger group cleanup, because
  permanent vs. transient misclassification can occur at the adapter
  layer and one failure is insufficient evidence the bot has been
  removed. The profile-driven threshold value lives in design notes.
  The row is preserved for audit; on re-add the adapter signal
  clears `removed_at`. **Group state (subscriptions, `scope_tag`,
  `chat_memory`, `chat_session`, members' saves) is not purged
  automatically** — the row is preserved against accidental
  remove/re-add cycles. Cleanup of long-removed groups is a v2
  admin command.
- **Group deleted upstream.** If a periodic-digest delivery (or
  any other send to the group) fails permanently because the group
  no longer exists upstream, **v1 handles it via the same
  permanent-failure threshold path as bot-removed**: the failure
  counts as a permanent send failure on the group, and when the
  streak reaches the profile-driven threshold (always greater than
  1) the bot-removed-from-group handler above fires —
  `groups.removed_at = NOW()`, scheduler entries cancelled, group
  state preserved. v1 does **not** treat a definitive single
  "group does not exist" adapter signal (e.g. SimpleX's
  group-not-found, Signal's group-no-longer-exists) as a distinct
  immediate-cleanup trigger: there is no adapter→Provider carrier
  for that failure sub-class today, and firing cleanup a few digest
  cycles sooner is not worth acting on a single signal that may be
  misclassified (a wrongly soft-removed live group does not
  self-heal — recovery needs a re-add signal that never comes).
  **Immediate cleanup on a definitive single group-not-found signal
  is deferred to v2.** The bot-removed and group-deleted cases are
  not distinguished to users or in the scheduler, only in the
  adapter-side log; the same v2 cleanup command will sweep both.
- **User left group.** When the adapter exposes a "user X left
  group Y" signal, Provider soft-clears the
  `group_membership` row by setting `removed_at = NOW()` per
  `schema.md` §Identity and access. The row, the user's
  per-(user, group) `chat_memory` / `chat_session` /
  `summary_anchor`, and any subscription rows attributed to that
  membership are preserved against accidental rejoin. If the
  departing user was the group admin, the same transaction
  clears `is_group_admin` so the partial unique index slot is
  freed; the group is admin-less until the next `/promote` or
  the next eligible first-mention auto-promote. A row with
  `removed_at IS NOT NULL` is **not eligible** as a first-mention
  auto-promote winner — the user must rejoin (clearing
  `removed_at`) before the slot can be refilled by their
  @mention.
  **On adapters with `supportsMembershipEvents = false` there is no
  such signal, and per-user leave cleanup is an explicit
  non-commitment in v1.** Bot output to a group is addressed to the
  group scope, so a member's departure generates no per-user
  permanent send failure that Provider could use as a proxy for a
  leave — the permanent-delivery-failure-driven cleanup path covers
  only the bot-removed-from-group and group-deleted cases above,
  never a per-user leave. Such adapters MUST NOT synthesise a
  left-group event from inactivity; the row stays `removed_at IS
  NULL`, and both the membership and any `is_group_admin` flag
  persist. A **departed group admin therefore still counts as the
  active admin** (auto-promote does not fire) and silently resumes
  admin on rejoin; the documented remediation is a bot-admin
  `/demote` of the stale group admin (`security.md`
  §Authorization model).

## What lives in design notes

- The SimpleX adapter wire protocol (chat CLI / WebSocket framing)
- The "live message" rendering mode the SimpleX adapter uses internally
- The Signal adapter wire protocol (signal-cli / JSON-RPC framing) and capability defaults
- The in-memory test adapter's API and assertion helpers
- Concrete property keys for adapter selection
- Per-adapter default capability values
- Edit-coalesce minimum interval value
- Group `@mention` stripping responsibility (adapter vs. Provider)
- Localization-bundle key naming 
- The attachment payload record shape and the Provider-side spool
  lifecycle (tmpfs directory, delete-on-completion, age sweeper)
- Per-adapter outbound attachment size ceilings and wire encoding
  (SimpleX XFTP, signal-cli file-path attach)