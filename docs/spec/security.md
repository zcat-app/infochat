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

- The **Provider** is exposed to the internet through the messaging adapter.                                                                                                                                                                          
  Adversaries can send arbitrary text.
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
  against an allowlist; the body is Unicode-normalized (NFKC, bidi-control                                                                                                                                                                            
  and zero-width stripping); a prompt-injection regex set runs with                                                                                                                                                                                   
  bounded execution time (catastrophic-backtracking inputs are                                                                                                                                                                                        
  fail-closed). Matches are recorded as quarantine spans and replaced in                                                                                                                                                                              
  the body with structured placeholders (`[REDACTED:<id>]`). Stage 1             
  *never* blocks release on its own — it scrubs and routes to review.
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

Every outbound connection from the Collector (feeds, `/add-source` URL
validation, redirects, and `StreamSource` connections) runs through a
fail-closed allowlist (decision D20):

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
  connections the IP check applies on every reconnect.
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
  parsing. **Ordering**: signature verification runs first (decision
  D38 is the trust-boundary commitment — every received event MUST
  pass signature verification against its claimed pubkey before
  Stage 1, and the kind filter is part of Stage 1). The kind filter
  applies after the signature check and before any body
  interpretation; an unverified event of any kind is dropped at the
  signature step and never reaches the kind filter.
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
  inside the wrapper, to refuse action requests with a `[refused-action]`        
  marker, and to treat the content as data.
- The LLM tool surface is a strict allowlist (every name appears
  verbatim in the agent's tool registry; nothing else is callable):
  tag-filtered SQL, single-post fetch, reference lookup,
  `recallMemory(keywords)` (the scope-filtered memory recall agent
  tool, decision D28; not to be confused with the user-facing
  `/recall <keyword>` command which is **v2-deferred** per SPEC.md
  §"Deferred to v2"), per-user saved-list read. Every argument is
  type-checked and bound to enums or validated ranges.
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

## Authorization model

Two admin tiers (decision D9):

- **Bot admin** — global. Bootstrapped from config; `/grant-admin` by                                                                                                                                                                                 
  another bot admin.
- **Group admin** — one group only. Bootstrapped by first `@mention` in a                                                                                                                                                                             
  new group; `/promote` / `/demote` by bot admin.

Invariants (also enforced in `schema.md`):

- **Last-admin protection (bot admin only).** Cannot revoke the only bot
  admin's `is_admin`, cannot ban the only bot admin, cannot ban self.
  Enforced at the trigger layer, not just the command layer, so a buggy
  command cannot bypass it. **Group admin has no last-admin protection** —
  a group can exist with zero admins (a banned or demoted group admin is
  not auto-replaced; the next bot-admin `/promote` or first-mention path
  refills the slot).
- **One group admin per group at any time.** Enforced by partial unique
  index. The "first @mention wins" auto-promote path is `INSERT … ON
  CONFLICT DO NOTHING`; the row that loses the race produces no error and
  no admin row — the user receives the standard non-admin response for
  whatever command they sent. `/promote` demotes the existing group admin
  in the same transaction.
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
5. Parse command (or fall to chat-mode).
6. **Permission check** against the matrix. Probation restrictions (D45)
   are part of the permission matrix: blocked commands return a friendly
   "probation period" reply and never reach execution.
7. Audit-log the intent.
8. Execute.
9. LLM only enters for chat-mode replies, summary prose, and the eval
   pipeline.

Steps 1–8 never call the LLM. This is the determinism boundary that makes
privilege escalation via injection (T3) infeasible.

## User ban

- Bot-wide flag with reason, actor, timestamp.
- Banned-user check is the first thing after identity resolution.
- Banned user receives one fixed reply per inbound message, regardless of                                                                                                                                                                             
  input.
- Banning a user who is a group admin: their `is_group_admin` rows remain                                                                                                                                                                             
  but are unreachable; `/unban` restores the role.
- Banning a bot admin requires `/revoke-admin` first (last-admin protection                                                                                                                                                                           
  applies).
- `/ban` against an unknown contact id creates the user row with
  `is_banned=true` so the user is banned even on first attempt.

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
- **Pre-ban still works.** `/ban <contact>` against an unknown contact creates
  the user row with `is_banned=true` without requiring an invite. The ban check
  (step 4) fires before any command could succeed even if the contact later
  presents a valid invite — but in practice the pre-ban row means the invite
  check (step 2) finds a known contact and routes to the ban path instead.
- `/invite list [--page N]` shows PENDING codes with their target contact,
  adapter, and expiry. `/invite revoke <code>` transitions a PENDING code to
  `REVOKED` immediately.

## Slow-start tier

Every newly registered user enters a probation period (decision D45). The
duration is profile-driven (value in design notes). During probation:

- **Allowed** (read-only subset): `/help`, `/status`, `/get-tags`,
  `/get-sources`, `/list-sources`, `/summary`, `/saved`, asset commands
  (`/zcash`, `/monero`), `/export`.
- **Blocked**: chat mode, `/add-source`, `/save`, `/unsave`, `/follow-tag`,
  `/unfollow-tag`, `/lang`, `/clear`, `/compress`, `/forget`,
  `/group-timezone`, and any admin command.
- Blocked operations return a friendly reply stating when full access unlocks;
  the reply never reaches the LLM or any write path.
- After the probation window elapses, the user is automatically promoted to
  full access — no admin action required.
- A bot admin can issue `/vouch <contact>` at any time to immediately graduate
  a user from probation. The vouch is audit-logged.
- Probation state is a single `probation_until` timestamp on the `users` row.
  `NULL` means full access. Checking it is a single indexed read in the
  permission step, adding no measurable latency.

## Quarantine workflow

- Every Stage 1 or Stage 2 hit creates a quarantine row holding span                                                                                                                                                                                  
  offsets, a placeholder id, the verbatim original, and a review status.
- Posts with PENDING quarantine entries can still be visible to users                                                                                                                                                                                 
  (with redactions in place). A Stage 2 `BENIGN` verdict keeps the post
  visible with Stage 1 redactions retained. A Stage 2 `INJECTION`,
  `MALWARE`, or `UNKNOWN` verdict                                                                                                                                                                             
  hides the entire post.
- Admins review via `/quarantine list` and `/quarantine approve|reject`.                                                                                                                                                                              
  Approve restores the original span and re-NOTIFY's the post; reject                                                                                                                                                                                 
  leaves the placeholder permanently.
- The verbatim original is intentionally **not** displayed in chat (could        
  re-inject in the admin's client). Operators use `psql` with the admin                                                                                                                                                                               
  role on the rare occasions it's needed.
- The placeholder format is structured and per-row randomized so attackers                                                                                                                                                                            
  cannot pre-craft a fake placeholder.

## Failure handling

The split between *verdict* and *infrastructure failure* is the heart of
the policy (decision D22). Per stage:

**Schema-violating LLM output** (wrong JSON shape, unexpected label value,
missing required field) is treated identically to an unparseable reply at
every stage: retry once, then apply the stage-specific failure path below.

- **Stage 2 verdict** of `BENIGN` → post released to the tagger and
  embedding stage; Stage 1 redactions remain in the body (quarantine spans
  are closed, not deleted — the original text is restorable only via admin
  `/quarantine approve`).
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
- **Admin notifications** are coalesced per `(channel, error_class)` for a
  short window so an outage produces one summary message, not 200 individual
  alerts.

A complete LLM outage degrades quality, not safety.

### Re-evaluation job

Posts released with Stage 1 redactions retained (Stage 2 infrastructure
failure path) are placed on a re-evaluation queue. The Collector runs a
background job on a profile-driven cadence (value in design notes) that
re-submits these posts to Stage 2. A per-post attempt counter bounds
retries: after the profile-driven maximum the post is permanently marked
`NEEDS_REVIEW` and admin is notified. On a `BENIGN` re-evaluation verdict
the Stage 1 redactions are lifted (equivalent to a quarantine approve) and
the post continues through the tagger and embedding stages. On `INJECTION`,
`MALWARE`, or `UNKNOWN` the post transitions to `QUARANTINED`.

## Rate limiting

Per-user token buckets bound:

- Parser-only command rate (cheap commands).
- `/add-source` rate (encourage bulk via bootstrap JSON).
- Chat-mode message rate (transport-level).
- **LLM-triggering operations** (chat replies + `/summary`) — capped                                                                                                                                                                                  
  lower, with a profile-driven cap. Transport rate is intentionally              
  higher than the LLM-triggering cap so a flooding user gets quick                                                                                                                                                                                    
  reject replies without burning the only LLM slot.
- **Tool calls per chat turn** — fixed cap. Tool results are cached                                                                                                                                                                                   
  within a single turn so identical calls don't re-query.
- `/quarantine approve` rate per admin.

Exact numbers are profile-driven (decision D27) and live in                                                                                                                                                                                           
`docs/design/04-security.md` §4.9.

## DB roles

Three Postgres roles, least-privilege (decision D34):

- **Collector role** — `INSERT/UPDATE` on ingest-owned tables (including
  `price_snapshot`); `SELECT` on the rest; `INSERT`-only on `audit_log`;
  `LISTEN/NOTIFY`.
- **Provider role** — write access on user-state tables; `SELECT` on
  collector-owned tables (including **`SELECT`-only on `price_snapshot`**:
  the Provider reads the latest snapshot per `(asset, sub-verb)` for
  `/zcash` and `/monero` and never writes to it); `SELECT` on the
  quarantine review *view* (no raw original content); `INSERT`-only on
  `audit_log`; `LISTEN/NOTIFY` (consumes `new_post`,
  `new_price_snapshot`, and quarantine state-change channels).
- **Admin role** — operator psql sessions only. Used for migrations, raw                                                                                                                                                                              
  quarantine inspection, occasional bulk fixes.

The split means a SQL-injection bug in the Provider cannot delete posts,
mutate price snapshots, or alter quarantine entries.

## Secrets handling

- LLM API keys are read from environment variables, not the DB.
- Audit-log writes pass through a redaction hook that masks values                                                                                                                                                                                    
  matching common API-key shapes.
- Contact IDs are logged in redacted form (prefix + ellipsis + suffix)                                                                                                                                                                                
  outside the audit log.
- **User-content logging.** `chat_memory` content, `saved_post` bodies
  and annotations, and the bodies of inbound chat-mode messages never
  appear in non-audit logs, at any log level (decision D37). Stage
  events, request IDs, scope IDs, and counts are loggable; the prose
  itself is not. The audit log records *intent* (command name, actor,
  scope, target), not user-authored prose.

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
- The `[refused-action]` marker convention
- Re-evaluation job cadence, per-post attempt cap, and re-eval status values
- Fetcher consecutive-failure threshold (*N*) and source re-enable procedure
- Invite-code TTL default and the exact drop-counter metric name
- Slow-start tier duration (per profile) and the exact allowed-command list 