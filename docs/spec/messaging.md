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

- **Identity assertion.** Receives a wire message, returns a stable,                                                                                                                                                                                  
  cryptographically-anchored contact id plus optional display name. An                                                                                                                                                                                
  adapter that cannot do this MUST be marked low-trust and the operator                                                                                                                                                                               
  must opt in explicitly.
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
  SimpleX by queue address, Signal by ACI (the UUID Signal binds
  to its identity keys; surfaced by `signal-cli` as
  `mentionUuid`). Display-name matching is
  **never** sufficient — an attacker who can spoof or impersonate
  the bot's display name in a group must not be able to suppress
  legitimate mentions or fake mentions of the bot.
  For adapters whose underlying protocol exposes no mention
  primitive at all (no @-mention payload, no mention metadata),
  the adapter MUST surface this in its capability flags
  (`supportsMentionByContactId = false`), and Provider's intake
  refuses to enable group mode for that adapter — group access
  requires a cryptographic mention anchor in v1. Falling back to
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
- **Capability flags.** A static description of what the adapter                                                                                                                                                                                      
  supports: identity trust level, markdown rendering, message edits,                                                                                                                                                                                  
  edit minimum interval, typing indicator, and any future flag a new                                                                                                                                                                                  
  transport needs.

## Capability flags (minimum set)

- `trustLevel` — `HIGH` for cryptographically anchored ids, `LOW`                                                                                                                                                                                     
  otherwise. Provider rejects identity assertions from `LOW` adapters                                                                                                                                                                                 
  unless the operator explicitly opts in.
- `supportsCodeFormatting` — when true, code spans render as
  monospace. When false, the user sees backticks (still readable,
  decision D30). **Renamed from `supportsMarkdownCode`** because the
  prior name implied broader markdown support; v1 adapters render
  *only* code spans, never markdown links or other markdown
  features. To enforce that the surface does not silently widen, every
  v1 adapter additionally asserts `supportsMarkdownLinks = false` —
  Provider treats this flag as required-false in v1 and a future
  adapter cannot opt in without an explicit spec amendment. URLs are
  always rendered bare (D30).
- `supportsMessageEdit` — required for in-place progress updates.
- `minEditInterval` — adapter-imposed floor between edits on the same                                                                                                                                                                                 
  message; the progress notifier honors `max(adapterMin, system floor)`.
- `supportsTypingIndicator` — drives the typing-on/off pulses around
  long-running requests.
- `supportsMentionByContactId` — true when the adapter's protocol
  carries an `@mention` anchored to the mentioned user's
  cryptographic contact id (SimpleX queue address, Signal ACI).
  Required-true for any adapter that exposes group mode in v1; an
  adapter with this flag false MUST disable its group SPI.
  Display-name string matching is never an acceptable fallback
  for mention recognition (see §Required SPI surface — Receive).

Future flags (richer attachments, voice, reactions, etc.) extend this                                                                                                                                                                                 
list; v1 ships only the above. Provider must treat an unknown flag as                                                                                                                                                                                 
"not supported" by default.

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
  registered phone number (E.164) or, where Signal supports it,
  the username. The Signal protocol's Sealed Sender / sender
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
  (cryptographic where possible). On first sight the Provider creates a
  `groups` row.
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
- **Bot removed from group.** When the adapter detects the bot has
  been removed from a group (via an adapter-specific signal, or
  via repeated permanent send failures past a profile-driven
  threshold), Provider sets `groups.removed_at = NOW()` and
  cancels the periodic-digest scheduler entries for that group.
  The row is preserved for audit; on re-add the adapter signal
  clears `removed_at`. **Group state (subscriptions, `scope_tag`,
  `chat_memory`, `chat_session`, members' saves) is not purged
  automatically** — the row is preserved against accidental
  remove/re-add cycles. Cleanup of long-removed groups is a v2
  admin command.
- **Group deleted upstream.** If a periodic-digest delivery (or
  any other send to the group) fails with a permanent
  "group does not exist" error from the adapter (adapter-specific
  signal — e.g. SimpleX's group-not-found, Signal's
  group-no-longer-exists), Provider treats it identically to
  bot-removed: `groups.removed_at = NOW()`, scheduler entries
  cancelled, group state preserved. The bot-removed and
  group-deleted cases are not distinguished to users or in the
  scheduler, only in the adapter-side log; the same v2 cleanup
  command will sweep both.
- **User left group.** When the adapter exposes a "user X left
  group Y" signal (or surfaces a permanent send failure to a
  specific user), Provider soft-clears the
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
  @mention. Adapters that do not expose a left-group signal
  (some transports cannot tell) MUST NOT synthesise one from
  inactivity; in that case the row stays `removed_at IS NULL`
  and the membership is cleaned up only on explicit
  bot-removed-from-group / group-deleted signals.

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