# Security model

This file defines the threats infochat defends against, the trust boundaries                                                                                                                                                                          
that make those defenses possible, and the invariants the rest of the system
must uphold. Concrete regex strings, exact prompt wrappers, retry counts,                                                                                                                                                                             
table column names, and Postgres role grants live in `docs/design/04-security.md`.

`security.md` is the source of truth for the *trust path*. When `architecture.md`
or `commands.md` mention authorization, ban handling, or quarantine, this file                                                                                                                                                                        
is the document that constrains them.

## Threat model

We assume:

- The **Provider** is exposed to the internet through every enabled
  messaging adapter (one Provider may run multiple adapters per
  `deployment.md` §Topology). Adversaries can send arbitrary text on
  any of them; the cross-adapter isolation invariant
  (`messaging.md` §Per-adapter trust level) prevents identity bleed
  between adapters.
- The **Collector** is exposed to arbitrary feed content. Every RSS publisher,   
  Reddit poster, Bluesky user, etc. is untrusted.
- The **DB** is internal — only the two services and the operator reach it.
- **LLMs** (local or remote) are black boxes that can be coaxed into emitting                                                                                                                                                                         
  attacker-chosen output. Local and remote LLMs have the same trust level.
- **Operator-set config** (properties files, bootstrap JSON) is trusted.

Out of scope for v1: side-channel attacks against the LLM host, supply-chain                                                                                                                                                                          
attacks on operator infrastructure, TLS/MITM (assumed handled by the adapter                                                                                                                                                                          
and HTTPS), Sybil resistance against an adapter that exposes no                                                                                                                                                                                       
fingerprinting hooks (see `docs/design/04-security.md` §4.12 for what this                                                                                                                                                                            
buys us and what it doesn't).

The threats we explicitly defend against are catalogued (T1–T9) in design                                                                                                                                                                             
notes. The spec-level commitments below cover all of them.

## Trust boundaries

1. **Adapter → Provider.** The adapter asserts identity via a stable,                                                                                                                                                                                 
   cryptographically anchored ID. Display names are informational and never                                                                                                                                                                           
   used for authorization (decision D10).
2. **Provider intake → command/chat router.** Identity resolution and the        
   ban check run *before* parsing. Banned users get one fixed reply and                                                                                                                                                                               
   never reach the parser, the chat agent, or any DB query past the ban          
   check (decision D11).
3. **Authorization → execution.** Permission checks run in deterministic                                                                                                                                                                              
   Java. The LLM is downstream of every authorization decision; it never
   participates in deciding who can do what (architecture principle 3).
4. **Collector ingest → user-visible store.** No post becomes user-visible                                                                                                                                                                            
   without passing the layered ingest checks (§ Ingest pipeline).
5. **LLM ↔ system state.** The LLM's tool surface is a fixed allowlist of                                                                                                                                                                             
   read-only, scope-filtered functions. There is no path from any LLM tool                                                                                                                                                                            
   to mutating authorization state, sources, subscriptions, or audit rows.

## Ingest pipeline (security side)

Every post goes through two stages before it can reach a user (decision                                                                                                                                                                               
D20):

- **Stage 1 — deterministic.** Runs on every post. HTML is sanitized
  against an allowlist; the body is Unicode-normalized (NFKC,
  bidi-control and zero-width stripping); a prompt-injection regex
  set runs with bounded execution time (catastrophic-backtracking
  inputs are fail-closed). Matches are recorded as quarantine spans
  and replaced in the body with a **structured placeholder
  committed at spec level**: the literal sequence
  `[REDACTED:<id>]`, where `<id>` is a per-row random opaque token
  (hex- or base32-encoded; the encoding choice and the token-byte
  length are profile-driven and live in design notes, but the
  surrounding `[REDACTED:` and `]` brackets are fixed). The
  brackets and `REDACTED:` literal are byte-identical across every
  implementation so user-facing prose, snapshot bodies, and tests
  recognise the marker by exact-match; the per-row `<id>`
  randomization is what stops attackers from pre-crafting a fake
  placeholder that would survive the Stage 1 `<<<UNTRUSTED>>>`
  marker strip (`llm.md` §Prompt-injection-aware prompt shape).
  Stage 1 *never* blocks release on its own — it scrubs and routes
  to review.
- **Stage 2 — LLM judge.** Only invoked when Stage 1 flagged something.                                                                                                                                                                               
  The judge sees the *original* (pre-redaction) content inside an
  untrusted-content wrapper and returns one of a fixed label set. See                                                                                                                                                                                 
  Failure handling below for the verdict-vs-infrastructure split.

The Provider's chat intake mirrors the Stage 1 Unicode steps (NFKC + bidi                                                                                                                                                                             
strip + zero-width strip outside fenced code) so a homoglyph or RTL                                                                                                                                                                                   
override cannot disguise a slash command. The Provider does *not* run the                                                                                                                                                                             
Stage 1 regex set on chat input — chat-input safety relies on the                                                                                                                                                                                     
delimiter convention plus the LLM tool boundary.

**Stage 1 is a coarse filter, not a complete defense.** It exists to                                                                                                                                                                                  
(a) skip Stage 2 on the ~95%+ clean majority and (b) provide a degraded                                                                                                                                                                               
mode (Stage-1-redacted-but-released) when the judge can't run. Stage 2 is                                                                                                                                                                             
the actual security boundary.

## SSRF and outbound connections

Every outbound connection from the Collector (feeds, redirects, and
`StreamSource` connections) and from the Provider (`/add-source` URL
validation HEAD/GET probes per `commands.md` §Source management) runs
through a fail-closed allowlist (decision D20). Both services use the
**same shared library module** (`infochat-ssrf`) which carries the
IP blocklist, DNS-rebind defense, redirect cap, and timeout caps —
the architecture's "DB-only inter-service communication" rule is
about runtime data, not compile-time code sharing, so a Maven
sibling module both services depend on is the right shape. There
is no Provider→Collector RPC for SSRF checks.

- Allowed schemes: `http`, `https`, `ws`, `wss`. The IP-blocklist and
  DNS-rebind defenses are **transport-agnostic** — a `wss://` relay
  connection is gated by the same checks as an `https://` feed fetch
  (decision D38).
- DNS-resolved IPs are checked against a blocklist of private, loopback,
  link-local, multicast, CGNAT, and cloud-metadata ranges (notably
  `169.254.169.254` and IPv6 equivalents) plus the host's own non-loopback
  interfaces.
- DNS is re-resolved after every redirect (TOCTOU defense); the IP
  blocklist re-applies each hop. For long-lived `StreamSource`
  connections the IP check applies on every reconnect, and **any
  peer-IP change observed at the socket layer is a hard close** —
  the implementation does not transparently accept it as a connection
  migration. A reconnect must re-pass the full allowlist before any
  event is emitted on the new socket.
- Redirect, body-size, connect-timeout, and read-timeout caps are
  enforced; an unset timeout is a configuration error.
- HTTP-shaped fetchers: `GET` and `HEAD` only. WebSocket-shaped stream
  sources have no method concept; trust commitments instead live in
  the per-source trust boundaries section below.

The allowlist is not user-configurable. Operators with a legitimate need
to scrape an internal feed run a separate ingestion pipeline.

## Per-source trust boundaries

Some ingest sources sign their own payloads at the protocol layer.
Verification of those signatures is a per-source trust boundary that
runs **before** Stage 1 (decision D38). The SPI does not know about
signatures; each implementation enforces its own boundary and is
responsible for never emitting an unverified event into the outbox.

### Nostr (StreamSource, v1)

- **Signature verification.** Every received event MUST pass
  signature verification against its claimed pubkey before reaching
  Stage 1. The pubkey is the only identity the Collector trusts for
  that event; the relay that delivered it is *not* a trust anchor.
  This is the ingest-layer mirror of decision D10 (cryptographic
  identity is the trust anchor).
- **Failure mode.** Failed verification → drop, increment a counter,
  never enqueue. Never released as `READY`, never visible to users.
  No admin notification per failure (a hostile or buggy relay can
  produce many) — the counter is the audit surface.
- **Forever read-only.** The Collector never holds a Nostr private
  key, never signs an event, never publishes. There is no
  key-handling code path in the codebase, even disabled. Lifting this
  requires a spec amendment and is out of scope for v1.
- **Kind allowlist.** Only kinds 1 (text notes) and 6 (reposts) are
  parsed in v1. All other kinds — including kind 4 (DMs), kind 7
  (reactions), and any encrypted-content NIPs — are dropped without
  parsing. **Ordering at the StreamSource trust boundary:**
  signature verification → kind allowlist → outbox write. Stage 1
  (HTML sanitization, regex set, Unicode normalization) begins at
  outbox-write time and applies to the body of allowed kinds. The
  kind allowlist is **not** part of Stage 1 — it is a Nostr-specific
  protocol gate that prevents disallowed event types from reaching
  the pipeline at all. This ordering means an unverified event of
  any kind is dropped at the signature step and never reaches the
  kind filter; an event of a disallowed kind is dropped at the kind
  filter and never reaches the outbox; only events that pass both
  gates enter Stage 1.
- **Repost handling.** Kind 6 reposts are stored with a reference to
  the original event id; the original event is **not** auto-resolved
  in v1 (no extra fetches, no relay round-trips). If the original is
  later seen via a separate Nostr event delivery, normal cross-source
  linking applies.
- **Operator-configured relay list.** The relay set is configured via
  `application.properties` and the bootstrap JSON; **NIP-65
  auto-discovery is explicitly out of v1**. Trade-off: content posted
  only to relays outside the operator's list is invisible to the bot.
  This is a deliberate v1 simplification — the operator picks which
  slice of the Nostr network the bot listens to. Surfacing this in
  user-facing help is design-notes territory.

## Prompt-injection defenses (LLM call sites)

Even after Stage 1+2, post bodies reaching the summarizer or chat agent are                                                                                                                                                                           
considered untrusted (decision D21):

- Every prompt that includes user-derived text is wrapped in a delimiter                                                                                                                                                                              
  block whose marker contains a per-call random value. Attackers cannot                                                                                                                                                                               
  pre-guess the marker and therefore cannot forge a closing tag inside the                                                                                                                                                                            
  body.
- The system prompt instructs the model to never follow instructions
  inside the wrapper, to refuse action requests with a **structured
  refusal marker** (the literal token used in v1 lives in design notes),
  and to treat the content as data.
- The LLM tool surface is a strict allowlist. Every name appears
  verbatim in the agent's tool registry; nothing else is callable.
  The v1 list is **closed at spec level** (additions or removals
  are spec amendments, not design tweaks):

  | Name | Inputs | Output | Notes |
  |---|---|---|---|
  | `searchPosts` | `tags: list<Tier-1 tag>` (each value validated against the controlled vocabulary), `window: duration`, `limit: int ≤ profile-driven cap` | list of `{uid, title, url, ready_at, tags}` | Returns `READY` posts visible in the calling `(user, scope)` only. Tag filter intersects with the scope's `tag_mode` rules (commands.md §Per-scope tag preferences). |
  | `getPost` | `uid: string` | `{uid, title, body, url, ready_at, tags}` or `null` | Scope-filtered: returns null for a UID not visible in the calling scope (the same path as a UID that does not exist; the existence-vs-no-access distinction is never exposed). |
  | `getReferences` | `uid: string`, `limit: int ≤ profile-driven cap` | list of `{uid, title, url, link_type, score}` | Edges from the `post_reference` graph. Scope-filtered the same way as `searchPosts`. |
  | `recallMemory` | `keywords: list<string>` (each ≤ a profile-driven length cap) | list of `{compressed_at, summary, references}` | Reads `chat_memory` for the calling `(user, scope)` only — D28. **Not** the user-facing `/recall <keyword>` command, which is v2-deferred per SPEC.md §"Deferred to v2". |
  | `listSaves` | `tags: list<personal tag>` (free-form, but length-capped), `window: duration` | list of `{uid, saved_at, personal_tags, snapshot_title, snapshot_url}` | Reads the caller's `saved_post` rows globally (D13: per-user across scopes); never returns another user's saves. |

  Every argument is type-checked and bound to enums, validated
  ranges, or length caps before the underlying SQL runs. Every output
  is a typed structured value, never a passthrough of free-form
  upstream text outside the post body / saved snapshot already vetted
  by the ingest pipeline. Verification (`verification.md` §Security)
  asserts the registry's name set equals the table above
  byte-for-byte; CI fails on a mismatch in either direction (a name
  added to the registry without a matching spec row, or a spec row
  with no matching registry entry).
- **Never exposed (forever):** any tool that mutates `users`,                                                                                                                                                                                         
  `group_membership`, `is_admin`, `is_banned`, `audit_log`, `source`,                                                                                                                                                                                 
  `source_subscription`; any tool running arbitrary SQL; any tool sending                                                                                                                                                                             
  messages outside the current conversation; any tool fetching arbitrary                                                                                                                                                                              
  URLs.

### LLM output sanitizer

Before any **LLM-generated** text is delivered to a user, the candidate
output is passed through a deterministic outbound regex pass that
strips or refuses output containing admin command strings
(`/grant-admin`, `/ban`, `/promote`, `/remove-source`, etc.). The
sanitizer applies to the **full set of LLM-authored output surfaces**:
chat-mode replies, on-the-fly `/summary` prose, periodic group
digests, `/retry` re-rolls, and any future LLM-emitted text. It does
**not** apply to deterministic command output (`/help`, `/status`,
`/list-sources`, etc.) because that text never passes through an LLM.
Admin commands are dispatched only by the deterministic command path,
so a copy-pasted reply still requires `is_admin=true` to do anything;
the sanitizer closes the social-engineering surface where a small LLM
emits plausible-looking admin commands across any of the surfaces above.
Every match is audit-logged (per-occurrence, not throttled).

**Match-set derivation.** The sanitizer's match set is **derived
from the closed privileged-tier list at spec level**
(`commands.md` §Permission model — "Closed list of privileged-tier
commands"), not from the design-tier per-actor matrix and not
hand-maintained. Every command in the bot-admin and group-admin
tiers of that closed list is in the sanitizer set. CI fails on a
mismatch (a new admin command added without a matching sanitizer
entry, or a sanitizer entry that no longer corresponds to a
listed command). Because the closed list is spec, adding or
removing a privileged-tier command is a spec amendment that
forces a paired sanitizer update; this makes "admin commands
never leak through LLM output" a structural property of the
codebase rather than a discipline.

## Authorization model

Two admin tiers (decision D9):

- **Bot admin** — global. Bootstrapped from config; `/grant-admin` by                                                                                                                                                                                 
  another bot admin.
- **Group admin** — one group only. Bootstrapped by first `@mention` in a                                                                                                                                                                             
  new group; `/promote` / `/demote` by bot admin.

Invariants (also enforced in `schema.md`):

- **Last-admin protection (bot admin only).** Cannot revoke the only
  bot admin's `is_admin`, cannot ban the only bot admin, cannot ban
  self. **The "only bot admin" check is global across adapters** —
  the count is `SELECT COUNT(*) FROM users WHERE is_admin = true`,
  not per-adapter — so a deployment with admins on multiple adapters
  may demote any single admin row as long as at least one
  `is_admin = true` row remains anywhere. This pairs with
  `/grant-admin` / `/revoke-admin` being inbound-adapter-scoped
  (`commands.md` §Admin): the per-adapter scoping bounds the
  blast radius of a single-adapter compromise, and the global
  last-admin counter prevents that scoping from being weaponised
  to lock the deployment out of admin entirely.
  Enforced at the trigger layer, not just the command layer, so a
  buggy command cannot bypass it. **Group admin has no last-admin
  protection** — a group can exist with zero admins (a banned or
  demoted group admin is not auto-replaced; the next bot-admin
  `/promote` or first-mention path refills the slot).
- **One group admin per group at any time.** Enforced by partial unique
  index. The "first non-banned, non-probation `@mention` wins"
  auto-promote path applies whenever the group has **zero**
  `is_group_admin` rows — covering both newly-created groups and
  groups left without an admin due to demotion or ban. Banned and
  probation users are ineligible (probation users cannot run admin
  commands by D45; promoting one would be a footgun). The promote
  is `INSERT … ON CONFLICT DO NOTHING` against the partial unique
  index; the row that loses a race produces no error and no admin
  row — the user receives the standard non-admin response for
  whatever command they sent. `/promote` demotes the existing group
  admin in the same transaction.
- **Banned-admin lockout escape hatch.** If the existing group admin is
  banned (their `is_group_admin` row remains but is unreachable per §User
  ban), a bot admin can `/promote` a different group member; the demote
  side of the swap clears `is_group_admin` on the banned row in the same
  transaction. This avoids a permanent group-admin lockout when the
  current admin is banned and `/unban` is not desired.

Authorization evaluation order on every inbound message:

1. Resolve identity from the adapter.
2. **DM — unknown contact.** If no user row exists for this (contact\_id,
   adapter): check whether the full message body is a valid PENDING invite
   code bound to this exact (contact\_id, adapter) pair (decision D44).
   - Valid: create user row (probation start), mark code USED, send welcome,
     stop. No further processing of this message.
   - Invalid / expired / absent: fixed "access requires an invitation" reply,
     drop. No registration, no LLM, no DB write beyond the drop counter.
3. **Group — unknown contact.** If no user row exists and this is a group
   `@mention`, auto-register (start probation per D45).
4. **Ban check.** If `is_banned=true`: fixed reply, stop. No parser, no DB
   query past the ban check, no LLM.
5. **Unicode-normalize the body** (NFKC + bidi-control strip +
   zero-width strip outside fenced code) **before parsing**, so a
   `/` cannot be disguised by homoglyphs or bidi overrides. This
   mirrors the Stage 1 ingest normalization (§Ingest pipeline) and
   is the chat-input parity step. Normalization runs after the ban
   check (the ban check uses the cryptographic contact id, not the
   message body, so order does not matter for ban) and before parse
   so the slash detector sees the normalized form.
6. Parse command (or fall to chat-mode).
7. **Permission check** against the matrix. Probation restrictions (D45)
   are part of the permission matrix: blocked commands return a friendly
   "probation period" reply and never reach execution.
8. Audit-log the intent.
9. Execute.
10. LLM only enters for chat-mode replies, summary prose, and the eval
    pipeline.

Steps 1–9 never call the LLM. This is the determinism boundary that makes
privilege escalation via injection (T3) infeasible.

## Per-adapter admin threat profile

Each enabled adapter has a different real-world compromise surface,
and admin rows are per-`(adapter, contact_id)` (one Provider may
run multiple adapters per `deployment.md` §Topology). Operators
should pick admin placement deliberately:

- **Signal admin.** The admin's identity is anchored cryptographically
  to the ACI, but that ACI is bound to a phone number / username
  recoverable through carrier and account-recovery flows. SIM-swap,
  port-out fraud, and account-recovery social engineering are real
  threats. A Signal admin compromise gives an attacker bot-admin
  powers on the Signal adapter only (per the inbound-adapter-scoped
  grant rule above), but that includes invite issuance, ban,
  source mutation, and audit access for that adapter.
- **SimpleX admin.** The admin's identity is a cryptographic queue
  address with no phone number, no username layer, and no
  third-party recovery path. The address can be **rotated**
  (operator generates a fresh queue, updates the bootstrap
  property, restarts; the prior admin row is left in place per
  `deployment.md` §Bootstrap admin drift and can be `/revoke-admin`'d
  from the new admin). This is the recommended high-assurance
  admin placement.

**Operator-side mitigations:**

- Run admin only on the higher-trust adapter (typically SimpleX),
  even when both adapters serve users. The bootstrap admin contact
  id is configured per adapter and is **optional per adapter** —
  an adapter may be enabled for users with no bootstrap admin
  configured; only the union of admin rows across adapters must be
  non-empty.
- Treat ephemeral SimpleX queue rotation as the routine mitigation
  for suspected exposure. Rotation is a property change plus
  restart; the audit log records the bootstrap of the new admin
  contact.
- Cross-adapter elevation is impossible by design (`/grant-admin`
  is inbound-adapter-scoped). A compromised Signal admin cannot
  grant admin on SimpleX without also compromising a SimpleX
  admin's chat session.

## User ban

- Bot-wide flag with reason, actor, timestamp.
- Banned-user check is the first thing after identity resolution.
- Banned user receives one fixed reply per inbound message, regardless of                                                                                                                                                                             
  input.
- **Transport-level rate cap fires before the ban check.** The
  per-`(adapter, contact_id)` inbound rate cap (§Rate limiting,
  "Chat-mode message rate (transport-level)") is evaluated
  **before** the banned-user check — a banned user hitting the
  cap receives no reply at all (including no fixed ban reply)
  until the cap resets. This bounds outbound cost from a hostile
  banned user driving inbound floods that would otherwise produce
  a fixed reply per inbound message.
- **Banning a user who is a group admin.** Their `is_group_admin` rows
  remain but are unreachable. **On `/unban`, restored group-admin roles
  are explicitly disclosed** in the command's reply and in the
  audit-log entry: the reply lists every `(group_id, group_label)` for
  which `is_group_admin = true` is being reinstated, with a hint
  pointing at `/demote <contact>` for cases where the executing admin
  did not intend to restore elevated privileges. The audit row
  carries the same list under `details_json.restored_group_admin`.
  Without this disclosure, an admin who issues `/unban` for a routine
  reason can silently re-grant group-admin powers across every group
  the unbanned user previously administered, with no signal in the
  command output that this happened.
- Banning a bot admin requires `/revoke-admin` first (last-admin protection                                                                                                                                                                           
  applies).
- **Pre-ban against unknown contact.** `/ban <contact>` against a
  contact id with no existing user row creates a row with
  `is_banned = true` and **`registration_state = 'preban'`** (the
  `users.registration_state` enum is the structural marker that the
  row was minted purely for the ban and never carried a registration
  ceremony). The contact is banned even on first attempt.
- **Pre-ban → unban does NOT grant DM access.** `/unban` against a
  `registration_state = 'preban'` row **deletes the row entirely**
  rather than flipping `is_banned = false` on it. The contact's next
  DM is therefore an unknown-contact DM and routes through the
  invite-code gate (authorization step 2), as it would have without
  the pre-ban. Without this rule a pre-ban → unban sequence would
  silently bypass the invite gate, because step 2 fires only when no
  `users` row exists — once a pre-ban has minted a row, a subsequent
  `/unban` would leave the row in place and the contact would reach
  the bot on next DM with no invite ever presented. The `/unban`
  reply surfaces the deletion (`"Pre-ban-only row removed; contact
  will require a fresh invite to DM."`) so the executing admin
  understands the post-condition; the deletion is audit-logged as
  `UNBAN_DELETED_PREBAN_ROW`. Pre-ban rows that have a non-`preban`
  `registration_state` (i.e. an already-registered user later
  banned, then unbanned) are **not** affected by this rule — their
  ban flag is cleared in place and the group-admin restoration
  rule above applies.

## Invite-code registration

The invite-code system (decision D44) is the application-level entry gate for
DM access, applied uniformly across all adapters:

- A bot admin issues `/invite create --adapter <name>` with exactly one of two
  mutually exclusive flags:
  - `--contact <id>` — strict invite, bound to a specific (contact\_id,
    adapter) pair. No confirmation required; risk is bounded to one identity.
  - `--open` — adapter-bound invite, not pre-bound to a contact\_id; the
    first unknown contact on that adapter to present the code is registered.
    Requires confirm (broader blast radius).
  Providing neither flag returns a hint listing both options; no code is
  created. Providing both is an error; no code is created.
  The code is shown to the admin once in the reply and stored with status
  `PENDING`.
- An unknown DM contact's first message is checked against the invite table.
  For a `--contact` invite: contact\_id, adapter, and code value must all
  match, and status must be `PENDING` and not expired. For an `--open` invite:
  only adapter and code value must match; any unknown contact on that adapter
  may consume it.
- On success: user row created (probation begins per D45), code marked `USED`,
  welcome sent. The invite-acceptance is audit-logged.
- On failure: fixed "access requires an invitation" reply. No registration, no
  further processing. The drop is counted but not individually audit-logged
  (a hostile actor can trigger many drops).
- **Invite codes are single-use.** A `USED` code cannot be replayed.
- **Codes carry a TTL.** An expired code is treated as absent. The TTL value
  is operator-configured and lives in design notes.
- **Cross-adapter isolation.** An invite bound to `(contact-id-A, simplex)`
  cannot be consumed from `(contact-id-A, signal)` — the adapter field is part
  of the match key. This prevents a code intercepted on one platform from being
  used on another.
- **Bot admin and bootstrap-seeded users are exempt** from the invite
  requirement; they are created directly by config at startup.
- **Group-registered users do not get free DM access.** A user
  auto-registered via the group `@mention` path (authorization step 3)
  has a `users` row with `registration_state = 'group_only'` (see §User
  ban for the enum) and is **subject to the DM invite gate** on first
  DM interaction. The intake check is layered: step 2 fires only for
  contacts with no `users` row, but the permission step (step 7) adds
  a DM-only gate that rejects any DM from a `group_only` user with the
  same fixed `Access requires an invitation.` reply as step 2's
  invalid path. The user remains a registered group member; only DM
  initiation is blocked. The two paths to lift the gate are: (a) a
  bot admin issues `/invite create --contact <id>`, the user accepts
  it, and the row's `registration_state` advances to `'invited'`; or
  (b) a bot admin `/vouch <contact>` clears probation **and** lifts
  the DM gate (the vouch is the explicit "I trust this contact"
  signal, with audit). Without this rule the DM invite gate would be
  trivially bypassable: any contact reachable in any group the bot
  serves could pivot to DM at will, since group membership is
  typically less tightly controlled than DM access.
- **Pre-ban still works.** `/ban <contact>` against an unknown contact creates
  the user row with `is_banned=true` without requiring an invite. The ban check
  (step 4) fires before any command could succeed even if the contact later
  presents a valid invite — but in practice the pre-ban row means the invite
  check (step 2) finds a known contact and routes to the ban path instead.
- `/invite list [--page N]` shows PENDING codes with their target contact,
  adapter, and expiry. `/invite revoke <code>` transitions a PENDING code to
  `REVOKED` immediately. `/invite revoke` requires confirm.
- **Brute-force rate limit.** A per-`(adapter, contact_id)` rate limit
  applies to invite-code attempts. Failed attempts increment a counter;
  when the counter exceeds a profile-driven threshold within a
  profile-driven window, further attempts from that
  `(adapter, contact_id)` are rejected without checking the code, and
  an audit row records the threshold breach. The limit prevents a
  patient brute-force search of the UUID space; it does not change
  the per-failure user-visible reply.
- **Caps on simultaneous PENDING invites.** The system enforces two
  caps on outstanding `PENDING` codes (exact values are profile-driven
  and live in design notes):
  - A **per-adapter cap on `--open` invites**: an admin attempting to
    mint an `--open` code while the cap is met receives a friendly
    error listing the current open codes and a hint pointing at
    `/invite revoke`. Open codes have the broadest blast radius (any
    unknown contact on the adapter can consume them), so the cap is
    deliberately small.
  - A **global cap on `--contact` invites**: contact-bound codes are
    safer (one identity each) but unbounded creation is still a
    footgun. The global cap is set high enough that legitimate bulk
    onboarding works and low enough that an accidental loop cannot
    quietly create thousands of pending codes.
  Codes that are `USED`, `REVOKED`, or whose `expires_at` has
  passed do not count toward either cap. There is no stored
  `EXPIRED` status (`schema.md` §Identity and access — Invite code):
  the active-pending count query filters
  `status = 'PENDING' AND (expires_at IS NULL OR expires_at > NOW())`,
  so codes free their cap slot the instant their `expires_at`
  elapses without a state transition ever being written. The two
  caps prevent code-leakage attacks (a leaked open code consumed
  by an adversary) from compounding through bulk issuance and
  bound the operator's exposure if a single admin account is
  compromised.
- **`/invite list` disclosure.** The list output **must visually
  distinguish `--open` codes from `--contact` codes** (e.g., a
  prominent `OPEN` marker on open rows). Open codes are the
  higher-blast-radius primitive and should not blend into a long
  contact-bound list; an admin auditing exposure must be able to spot
  them at a glance.
- **Pre-banned contact + invite.** `/invite create --contact <id>`
  against a contact whose `users` row already has `is_banned=true`
  returns a friendly error pointing the admin at `/unban`; **no
  invite is created**. The intake-side ban check (authorization
  step 4) is the second line of defense — even if a stale invite
  exists, the ban check fires first — but refusing to mint the
  invite at all keeps the audit trail clean.

## Slow-start tier

Every newly registered user enters a probation period (decision D45). The
duration is profile-driven (value in design notes). During probation:

- **Allowed** (read-only subset plus the user's own privacy/locale
  levers): `/help`, `/status`, `/get-tags`, `/get-sources`,
  `/list-sources`, `/summary`, `/saved`, asset commands (`/zcash`,
  `/monero`), `/export`, **`/forget`** (the user's privacy lever —
  blocking it during probation would undermine D37), **`/lang`** (a
  single-row UPDATE with no LLM cost — blocking it means a non-English
  new user cannot get help in their language during the window when
  they most need it).
- **Blocked**: chat mode, `/add-source`, `/save`, `/unsave`,
  `/follow-tag`, `/unfollow-tag`, `/clear`, `/compress`,
  `/group-timezone`, `/retry` (LLM-invoking write), and any admin
  command. `/stop` is **not blocked** — it returns the standard
  idempotent no-op reply during probation regardless of in-flight
  state, because it has no side effect (a probation user has no
  in-flight LLM work to cancel since chat mode and `/retry` are
  blocked, and the no-op reply is the same whether probation is
  in effect or not).
- Blocked operations return a friendly reply stating when full access unlocks;
  the reply never reaches the LLM or any write path.
- After the probation window elapses, the user is automatically promoted to
  full access — no admin action required. The mechanism is **lazy**: the
  permission check is `probation_until IS NULL OR probation_until < NOW()`.
  The user is promoted at the instant `NOW() > probation_until`, regardless
  of whether the column has been nulled. A passive sweep clears the column
  on the next request from a promoted user; no background job is required.
- A bot admin can issue `/vouch <contact>` at any time to immediately graduate
  a user from probation. The vouch is audit-logged.
- Probation state is a single `probation_until` timestamp on the `users` row.
  `NULL` means full access. Checking it is a single indexed read in the
  permission step, adding no measurable latency.

## Quarantine workflow

- Every Stage 1 or Stage 2 hit creates a quarantine row holding span
  offsets, a placeholder id, the verbatim original, and a review
  status `∈ {PENDING, BENIGN_CLOSED, APPROVED, REJECTED}`
  (`schema.md` §Posts and derivatives).
- Posts with PENDING quarantine entries can still be visible to users
  (with Stage 1 redactions in place). A Stage 2 `BENIGN` verdict keeps
  the post visible with **Stage 1 redactions retained** — the verdict
  transitions the quarantine row from `PENDING` to `BENIGN_CLOSED` but
  does **not** lift redactions. `BENIGN_CLOSED` is the durable signal
  for "Stage 2 cleared this; redactions remain until admin chooses to
  approve." A Stage 2 `INJECTION`, `MALWARE`, or `UNKNOWN` verdict
  hides the entire post (`QUARANTINED` status); the quarantine row
  stays `PENDING` (subject to admin review and the admin-review TTL).
- **Redactions are lifted only by `/quarantine approve`.** This rule
  applies uniformly to first-pass and re-evaluation BENIGN verdicts:
  a re-eval BENIGN does not auto-lift first-pass redactions either.
  An admin reviewing the quarantine row is the only path that
  restores the original span. This is the safer of the two
  verdict-vs-redaction interpretations and avoids the "first pass
  keeps redactions, re-eval lifts them" inconsistency.
- Admins review via `/quarantine list` and `/quarantine approve|reject`.
  `/quarantine list` defaults to `PENDING` rows only — the active
  review queue; `BENIGN_CLOSED` rows are not surfaced unless an admin
  passes `--all` (forensic / audit view). Approve transitions
  `PENDING → APPROVED` (or `BENIGN_CLOSED → APPROVED`), restores the
  original span, and re-NOTIFY's the post; reject transitions
  `PENDING → REJECTED` (and on `BENIGN_CLOSED` rows the same forensic
  rejection is reachable, leaving the placeholder permanent). The Provider DB role
  (`security.md` §DB roles) does not have `SELECT` on the raw
  original column; approve and reject run as **stored procedures**
  (`approve_quarantine(quarantine_id, actor_id)` and
  `reject_quarantine(quarantine_id, actor_id)`) that internally
  read the original under the procedure's elevated rights and
  perform the restore + audit-log + NOTIFY in one transaction. The
  Provider role has `EXECUTE` on these procedures, never `SELECT`
  on the underlying raw-original column.
- The verbatim original is intentionally **not** displayed in chat
  (could re-inject in the admin's client). Operators use `psql` with
  the admin role on the rare occasions it's needed.
- The placeholder format is the spec-committed marker
  `[REDACTED:<id>]` (`security.md` §Ingest pipeline). The
  surrounding brackets and `REDACTED:` literal are fixed; the
  `<id>` token is per-row randomized so attackers cannot pre-craft
  a fake placeholder that would survive the Stage 1 marker strip.

## Failure handling

The split between *verdict* and *infrastructure failure* is the heart of
the policy (decision D22). Per stage:

**Schema-violating LLM output** (wrong JSON shape, unexpected label value,
missing required field) is treated identically to an unparseable reply at
every stage: retry once, then apply the stage-specific failure path below.

- **Stage 2 verdict** of `BENIGN` → post released to the tagger and
  embedding stage; Stage 1 redactions remain in the body (quarantine
  rows transition `PENDING → BENIGN_CLOSED`, not deleted — the
  original text is restorable only via admin `/quarantine approve`).
  **Re-evaluation BENIGN follows the same rule:** redactions are not
  auto-lifted on re-eval, only on `/quarantine approve`. See
  §Quarantine workflow and §Re-evaluation job.
- **Stage 2 verdict** of `INJECTION`, `MALWARE`, or `UNKNOWN` → post
  stays `QUARANTINED` until admin review. The judge model treating
  `UNKNOWN` as a soft injection signal is intentional: a degraded judge
  must never auto-release.
- **Stage 2 infrastructure failure** (LLM unreachable, timeout, unparseable
  or schema-violating reply after retry) → release as `READY` with the
  **Stage 1 redactions retained**, mark the post for re-evaluation (see
  Re-evaluation job below), notify admin via the throttled channel. A
  profile-driven flag lets production profiles invert this default and keep
  the post quarantined.
- **Stage 1 infrastructure failure** (regex watchdog crash, HTML sanitizer
  exception) → fail-closed: the post is immediately `QUARANTINED` and never
  auto-released. Admin is notified via the throttled channel. Stage 1
  infrastructure failure must never default to release — the deterministic
  guard failing is a safety-critical event.
- **Fetcher failure** (HTTP error, connection timeout, feed parse failure on
  an HTTP-shaped source) → retry on the next scheduled tick (decision D42).
  After *N* consecutive per-source failures (profile-driven), the source
  `status` transitions to `'failed'` and the scheduler skips it; a
  throttled admin notification is sent with the error class and source
  id. Other sources are unaffected. An admin must explicitly re-enable
  the source. D42 is the HTTP-shaped mirror of D38's per-relay
  degradation commitment for stream sources.
- **Tagger** failure → fall back to `source.bootstrap_tags`, mark the post,
  throttled admin notify. (This is why `/add-source --tags` is mandatory:
  every source must have a deterministic fallback.)
- **Entity** failure → release without entities; cross-source linking
  degrades to embedding-only for that post (or skipped entirely if
  embedding also failed).
- **Embedding** failure → release without a vector; the post is otherwise
  normal and fully visible.
- **Compression failure (manual `/compress` or auto-compress).** LLM
  unreachable, timeout, or schema-violating reply after retry → the
  chat session is **held at the ceiling**: the user's next chat-mode
  message returns a localized friendly error
  ("memory checkpoint pending; please `/compress` manually or try
  again later"), and the session is never silently truncated.
  Manual `/compress` failure surfaces the same error and leaves the
  session unchanged. The escape hatch is `/clear` (which discards
  the live window — the user's choice, not the system's). Auto-compress
  fires when the chat session occupies a profile-driven percentage of
  the context-window ceiling (value in design notes).
- **Admin notifications** are coalesced per `(channel, error_class)` for a
  short window so an outage produces one summary message, not 200 individual
  alerts.

**Provider-side (user-facing) LLM failures.** The Provider's
LLM-invoking surfaces — chat-mode replies, on-demand `/summary`
prose generation, `/retry` re-rolls — degrade with the following
rules:

- **`/summary` (and `/retry --digest`) summarizer unreachable** →
  fall back to the headlines + URLs + post UIDs degraded form (the
  same fallback as a saturated periodic digest per decision D17).
  No prose, deterministic post selection unchanged. The friendly
  notice is a localization-bundle string (D43); the user is not
  shown a hung response. `/retry` after recovery re-rolls the
  prose with the original frozen post selection. See
  `commands.md` §Content (`/summary`).
- **Chat-mode replies** with the chat-agent LLM unreachable →
  return a localized "chat assistant is unavailable, try again
  later" friendly error from the bundle (D43); the message never
  reaches the chat agent loop, no `chat_session` advance, no
  `chat_memory` write, no tool invocation.
- **No router-side fallback in v1.** When an operator configures
  per-task providers (e.g. Anthropic for SUMMARIZER, Ollama for
  SECURITY_JUDGE), a per-task provider that is unreachable
  degrades **only that task** to its task-specific failure path
  above; the router does NOT silently switch to a different
  configured provider. Operators who require high availability
  for a per-task LLM must over-provision that provider; v1's
  per-task routing is a single resolution per call, not a
  fallback chain. Adding a fallback chain is a v2 candidate
  (`llm.md` §Per-task routing rules).

A complete LLM outage degrades quality, not safety.

### Re-evaluation job

Two kinds of posts feed the re-evaluation queue:

1. Posts released with Stage 1 redactions retained because of a
   **Stage 2 infrastructure failure** — these are `READY` and visible
   with redactions, awaiting a healthy verdict that may close the
   quarantine cleanly.
2. Posts marked **UNKNOWN** by Stage 2 — these are `QUARANTINED`
   (hidden) but the verdict is "judge couldn't classify," not
   "judge classified as hostile." Periodic re-eval gives a
   recovered or improved judge a chance to produce a definitive
   verdict before admin-review escalation.

The Collector runs a background job on a profile-driven cadence
(value in design notes) that re-submits these posts to Stage 2.
A per-post attempt counter bounds retries; the **infra-failure**
class and the **UNKNOWN** class carry **separate, independent
caps** (UNKNOWN's cap is the lower of the two so an UNKNOWN-flooding
model exhausts attempts faster than infrastructure failures).
After cap exhaustion the post transitions to `NEEDS_REVIEW`
(per `schema.md` §Posts and derivatives) and the admin notifier
fires.

Re-eval verdict handling:

- `BENIGN` on a Stage-2-infra-failure post → quarantine row
  transitions `PENDING → BENIGN_CLOSED`, **Stage 1 redactions are
  not lifted** (only `/quarantine approve` lifts them — this matches
  the §Quarantine workflow rule above), the post continues through
  tagger and embedding if those stages had not already run. The
  `stage2_failed` cursor flag is **cleared** on this transition:
  the post now has a clean Stage 2 verdict and the cursor returns
  to its non-failed state. (Schema invariant 5: per-stage flags
  are the durable cursor for in-flight evaluation.)
- `BENIGN` on an UNKNOWN post → post transitions
  `QUARANTINED → READY` with Stage 1 redactions retained and the
  quarantine row transitions `PENDING → BENIGN_CLOSED`; same rule
  as above for lifting (admin only). **The transition is
  audit-logged** as `RE_EVAL_RELEASED` with `actor='re_eval_job'`,
  `target_kind='post'`, `target_id=<post_uid>`, and
  `details_json={ prior_verdict, new_verdict='BENIGN', attempt }`,
  and a throttled admin notification fires (coalesced per
  `(channel, 're_eval_released')` on the same window as other admin
  notifications). Without this, posts auto-released from
  `QUARANTINED` after an UNKNOWN-then-BENIGN re-eval reach users
  with no human reviewer ever having seen the row — an attacker who
  crafts content that initially looks UNKNOWN to the judge but
  flips to BENIGN on a model swap or warm-up could otherwise quietly
  harvest user-visible state without an admin signal.
- `INJECTION`, `MALWARE`, or `UNKNOWN` on either class → post stays
  `QUARANTINED`, the `stage2_failed` flag is **preserved** (or set,
  if the prior verdict was UNKNOWN) alongside the new verdict, and
  the attempt counter increments.

**Throttled NEEDS_REVIEW notifications.** Admin notifications for
`NEEDS_REVIEW` transitions are coalesced per
`(channel, error_class)` over a profile-driven window so a Stage-2
outage that exhausts retries on hundreds of posts produces one
summary notification, not hundreds — mirroring the throttling
already in place for Stage 2 infra-failure notifications. Sustained
high UNKNOWN rate also triggers the operator alert
`Stage2UnknownRateHigh` defined in design notes.

**Per-source UNKNOWN auto-disable.** A source whose Stage 2 UNKNOWN
rate exceeds a profile-driven threshold over a profile-driven rolling
window has its `source.status` transitioned to `'failed'` (the same
terminal status used for consecutive HTTP failures, decision D42),
the scheduler skips it on subsequent ticks, and a throttled admin
notification fires citing the source id, the observed UNKNOWN rate,
and the threshold. This bounds the **quarantine-exhaustion** attack
surface: an adversary controlling a feed (or able to inject into
one) cannot drown admin review capacity by crafting borderline
content that consistently triggers UNKNOWN — the system shifts the
cost from "admins must triage every post indefinitely" to "admins
re-enable a single source after diagnosis." The per-source cap is
independent of the global `Stage2UnknownRateHigh` alert: the global
alert fires on aggregate ratio (and can be evaded by mixing
attack content with legitimate content from other sources), while
the per-source cap fires on per-source ratio (which the attacker
cannot dilute without losing control of their own input). An admin
explicitly re-enables the source via `/source-enable <id>` after
diagnosis, the same recovery path used for HTTP-failure sources.

**Absolute NEEDS_REVIEW depth alert.** Operators also see an
absolute-depth alert when the `NEEDS_REVIEW` queue exceeds a
profile-driven threshold, **independent of any per-source ratio**.
This guarantees the operator notices a sustained backlog even if
the per-source UNKNOWN rate stays below the auto-disable threshold
across many sources simultaneously (the "many small fountains"
attack shape that ratio-based alerts miss).

## Rate limiting

Per-user token buckets bound, grouped explicitly so commands that
share a cost profile share a bucket:

- **Parser-only + DB-read paginated commands** — `/help`,
  `/status`, `/list-sources`, `/get-sources`, `/get-tags`,
  `/saved`, `/audit`, `/export`, `/quarantine list` and similar.
  One bucket; high cap; cheap.
- **Asset commands** — `/zcash`, `/monero` and friends. Share a
  cache-hit bucket (most calls within a freshness window are
  served from cache, so the limit guards against a flood that
  forces refetches).
- **`/add-source`** — its own bucket (encourages bulk via
  bootstrap JSON; surface for adding many sources in a short
  window).
- **Chat-mode message rate (transport-level)** — bounds inbound
  message volume regardless of cost.
- **LLM-triggering operations** (chat replies + on-demand
  `/summary` + `/retry` re-rolls) — its own bucket, capped lower,
  profile-driven. Transport rate is intentionally higher than this
  cap so a flooding user gets quick reject replies without burning
  the only LLM slot.
- **Tool calls per chat turn** — fixed cap. Tool results are cached
  within a single turn so identical calls don't re-query.
- **`/quarantine approve`** — per-admin bucket.

Exact numbers are profile-driven (decision D27) and live in
`docs/design/04-security.md` §4.9.

## DB roles

Three Postgres roles, least-privilege (decision D34):

- **Collector role** — `INSERT/UPDATE` on ingest-owned tables
  (including `price_snapshot` and `asset_config`); `SELECT` on the
  rest; `INSERT`-only on `audit_log`; `LISTEN/NOTIFY`.
- **Provider role** — write access on user-state tables; `SELECT` on
  collector-owned tables (including **`SELECT`-only on
  `price_snapshot`** and **`SELECT`-only on `asset_config`**: the
  Provider reads the latest snapshot per `(asset, sub_verb)` for
  `/zcash` and `/monero` and reads `asset_config` to gate `/help`,
  parse sub-verbs, and surface stale-data warnings; never writes to
  either); `SELECT` on the quarantine review *view* (no raw
  original content); **`SELECT` on the redacted `audit_log_view`,
  not on `audit_log` itself** (`/audit` reads through the view, see
  below); `INSERT`-only on `audit_log`; `EXECUTE` on the
  `approve_quarantine` and `reject_quarantine` stored procedures
  (no `SELECT` on the raw-original quarantine column);
  `LISTEN/NOTIFY` (consumes `new_post`, `new_price_snapshot`, and
  `quarantine_review` channels per `architecture.md`
  §Inter-service communication).
- **Admin role** — operator psql sessions only. Used for migrations,
  raw quarantine inspection, occasional bulk fixes.

**`audit_log_view`** is a Postgres view that exposes the same columns
as `audit_log` minus any redacted fields (raw secrets, full contact
ids — replaced with the redacted form per §Secrets handling).
`SELECT` on `audit_log_view` is granted to the Provider role only;
this is the path `/audit` uses. Granting `SELECT` directly on
`audit_log` to the Provider would expose unredacted columns; the
view is the single read path for the Provider role.

The split means a SQL-injection bug in the Provider cannot delete
posts, mutate price snapshots, alter quarantine entries, read
unredacted audit rows, or read raw quarantine originals.

**Invariant 4 enforcement.** `DELETE` on `source` is **revoked**
from both Collector and Provider roles; only the Admin role
(operator psql) can hard-delete a source row, and that path is the
manual escape hatch that backs invariant 4 (soft-delete only for
sources). Application code uses the soft-delete column.

## Secrets handling

- LLM API keys are read from environment variables, not the DB.
- Audit-log writes pass through a redaction hook that masks values
  matching a **closed catalogue of API-key shapes**. The catalogue's
  v1 baseline (spec-level commitment) is:
  - OpenAI-style `sk-…` (and the long-form `sk-proj-…`, `sk-svcacct-…`).
  - Anthropic `sk-ant-…`.
  - GitHub `ghp_…`, `gho_…`, `ghu_…`, `ghs_…`, `ghr_…`.
  - AWS access keys: `AKIA[0-9A-Z]{16}` and `ASIA[0-9A-Z]{16}`.
  - Google API keys: `AIza[0-9A-Za-z_-]{35}`.
  - Slack `xox[abprs]-…`.
  - Generic 32+-character hex / base64 strings adjacent to the
    case-insensitive substrings `api[_-]?key`, `secret`, `token`,
    `password`, `bearer`.
  The exact regexes, locale-folding rules, and the test corpus that
  feeds the redactor unit tests live in
  `docs/design/04-security.md` — adding a shape to the catalogue
  is a design-note edit, **removing** a shape from the spec
  baseline is a spec amendment so the audit redactor cannot silently
  weaken across versions. The redactor is fail-closed on regex
  timeout (the same RE2/timeout discipline as Stage 1): a timed-out
  match treats the whole field as redacted rather than emitting it
  raw.
- Contact IDs are logged in redacted form (prefix + ellipsis + suffix)                                                                                                                                                                                
  outside the audit log.
- **User-content logging.** `chat_memory` content, `saved_post` bodies
  and annotations, and the bodies of inbound chat-mode messages never
  appear in non-audit logs, at any log level (decision D37). Stage
  events, request IDs, scope IDs, and counts are loggable; the prose
  itself is not. The audit log records *intent* (command name, actor,
  scope, target), not user-authored prose.

## Source URL visibility

Source rows are global state (decision D7) — there is no per-user
source row. As a consequence, every URL added via `/add-source`
(DM or group) is visible to bot admins through `/list-sources --all`.
Users adding private feeds should treat the URL as visible to
operators. Hiding this would be dishonest to users; documenting it
explicitly lets users make an informed choice. v2 may add a
"private sources" feature with a per-user row and additional
operational complexity; v1 commits to global source rows.

## What's intentionally NOT in v1

(Catalogued in `docs/design/04-security.md` §4.12; spec-level summary:)

- DB-at-rest encryption — operator's responsibility (LUKS, managed-DB
  transparent encryption, etc.).
- **Per-user encryption with a user-supplied key.** Deferred (decision
  D37). The Provider must read plaintext to generate periodic digests,
  run the chat agent over `chat_memory`, and produce on-demand
  summaries; encrypting under a server-held key is obfuscation against
  casual DB dumps, not a real confidentiality boundary. Doing it
  honestly (key derived from user secret, server cannot reconstruct)
  would require disabling asynchronous features for opted-in users and
  is gated on a future product decision. v1 relies on minimization
  (chat-memory TTL, `/forget`, `/export`) instead.
- Per-group bans — only bot-wide ban in v1.
- User-controllable retention values — the chat-memory TTL itself is
  fixed (configured per profile, not per user). Users control purge
  via `/forget` (decision D37), not by tuning TTL.
- Two-factor confirmation for ban — single-step confirm-within-window                                                                                                                                                                                 
  is enough for v1.
- CAPTCHAs / human verification — invite-code registration and the slow-start
  tier are the v1 gates; CAPTCHA-style puzzles are not added on top.
- Heuristic/anomaly-based banning — admin acts manually.
- **Sybil resistance across adapters.** A user banned on one adapter can
  present a fresh identity on another adapter; the bot has no cross-adapter
  correlation signal. The v1 levers are: invite codes (every new identity on
  every adapter needs its own admin-issued invite), the slow-start tier (bounds
  early resource damage per identity), and manual `/ban`. Full Sybil
  resistance is deferred to v2.
- **Nostr publishing / signing.** Forever out of scope for v1 (decision
  D38). The Collector is read-only at the Nostr protocol layer: no key
  storage, no signing, no `EVENT` publishes. A future posting bot is a
  separate service with its own threat model.
- **NIP-65 relay-list auto-discovery.** Out of v1 (decision D38). The
  bot only sees content on the operator-configured relay list; content
  posted exclusively to relays outside that list is invisible. This is
  a deliberate trade-off, not a bug.
- **Nostr kinds beyond 1 and 6.** Out of v1: DMs (kind 4), reactions
  (kind 7), encrypted-content NIPs, relay-list events, and every other
  kind are dropped without parsing.
- **Translation cache cross-scope timing side-channel.** The
  presentation-layer translation cache (`llm.md` §Translation flow)
  is keyed by `(hash(text), target_language)` and is **shared across
  scopes** so a digest sent to multiple group members translates
  once. A user observing translation latency could in principle infer
  that another scope translated the same string moments earlier
  (cache hit vs. cache miss). v1 accepts this as a minor trade-off:
  the cached strings are presentation prose generated by the bot
  (cluster summaries, headers, status lines), not user-authored
  content; the translation key is a hash, not the plaintext; and the
  alternative — a per-scope cache — would multiply translation cost
  by the number of subscribers without a meaningful confidentiality
  benefit. Per-scope cache partitioning is a v2 candidate if a
  concrete attack surfaces.
- **Display-name-based `@mention` recognition.** v1 mention
  recognition is anchored to the cryptographic contact id only
  (`messaging.md` §Required SPI surface). Adapters whose protocol
  carries no mention primitive at all must disable group mode;
  string-matching the bot's display name in inbound message
  bodies is forever out of v1 because an attacker who spoofs or
  impersonates the bot's display name could otherwise suppress
  or fake mentions.
- **Boundless growth of soft-deleted source rows.** v1 never
  hard-deletes a `source` row (invariant 4). Across years of
  operation an operator can accumulate thousands of soft-deleted
  rows. The cleanup path is operator-side `psql` under the Admin
  role; the spec accepts this as bounded operational cost rather
  than introducing an automatic GC. A future v2 admin command may
  surface this in chat.

## What lives in design notes

- The Stage 1 regex catalogue and ReDoS mitigation specifics
- Stage 2 prompt template and label set
- Quarantine table columns and review-view shape
- Per-tier rate-limit numbers
- Per-profile "release on Stage 2 failure" defaults
- Nostr default relay list, per-relay rate cap, mark-bad threshold
  and cooldown values, reconnect backoff schedule
- Nostr config-block JSON shape inside `bootstrap-sources.json`
- The websocket library choice and the fake-relay test harness
- The exact NIP subset and the kind-filter implementation
- Prometheus counter names and recommended alert expressions
- DB role grant statements
- The structured refusal marker convention (literal token, prompt phrasing)
- Re-evaluation job cadence, per-post attempt cap, and re-eval status values
- Fetcher consecutive-failure threshold (*N*) and source re-enable procedure
- Invite-code TTL default and the exact drop-counter metric name
- Slow-start tier duration (per profile) and the exact allowed-command list 