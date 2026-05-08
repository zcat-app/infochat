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
- The LLM tool surface is a strict allowlist: tag-filtered SQL, single-post                                                                                                                                                                           
  fetch, reference lookup, scope-filtered memory recall, per-user saved-list
  read. Every argument is type-checked and bound to enums or validated                                                                                                                                                                                
  ranges.
- **Never exposed (forever):** any tool that mutates `users`,                                                                                                                                                                                         
  `group_membership`, `is_admin`, `is_banned`, `audit_log`, `source`,                                                                                                                                                                                 
  `source_subscription`; any tool running arbitrary SQL; any tool sending                                                                                                                                                                             
  messages outside the current conversation; any tool fetching arbitrary                                                                                                                                                                              
  URLs.

### Chat output sanitizer

Before any chat-mode reply is sent, the candidate text is passed through a                                                                                                                                                                            
deterministic outbound regex pass that strips or refuses replies containing                                                                                                                                                                           
admin command strings (`/grant-admin`, `/ban`, `/promote`, `/remove-source`,                                                                                                                                                                          
etc.). Admin commands are dispatched only by the deterministic command                                                                                                                                                                                
path, so a copy-pasted reply still requires `is_admin=true` to do anything;                                                                                                                                                                           
the sanitizer closes the social-engineering surface where a small LLM emits                                                                                                                                                                           
plausible-looking admin commands. Every match is audit-logged                    
(per-occurrence, not throttled).

## Authorization model

Two admin tiers (decision D9):

- **Bot admin** — global. Bootstrapped from config; `/grant-admin` by                                                                                                                                                                                 
  another bot admin.
- **Group admin** — one group only. Bootstrapped by first `@mention` in a                                                                                                                                                                             
  new group; `/promote` / `/demote` by bot admin.

Invariants (also enforced in `schema.md`):

- **Last-admin protection.** Cannot revoke the only admin's `is_admin`,                                                                                                                                                                               
  cannot ban the only admin, cannot ban self. Enforced at the trigger            
  layer, not just the command layer, so a buggy command cannot bypass it.
- **One group admin per group at any time.** Enforced by partial unique                                                                                                                                                                               
  index. The "first @mention wins" auto-promote path is `INSERT … ON                                                                                                                                                                                  
    CONFLICT DO NOTHING`; `/promote` demotes the existing admin in the same                                                                                                                                                                             
  transaction.

Authorization evaluation order on every inbound message:

1. Resolve identity from the adapter.
2. Auto-register if absent (DM only; group: only on @mention).
3. **Ban check.** If banned, fixed reply and stop. No parser, no DB query                                                                                                                                                                             
   past the ban check, no LLM.
4. Parse command (or fall to chat-mode).
5. Permission check against the matrix.
6. Audit-log the intent.
7. Execute.
8. LLM only enters for chat-mode replies, summary prose, and the eval            
   pipeline.

Steps 1–7 never call the LLM. This is the determinism boundary that makes                                                                                                                                                                             
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

## Quarantine workflow

- Every Stage 1 or Stage 2 hit creates a quarantine row holding span                                                                                                                                                                                  
  offsets, a placeholder id, the verbatim original, and a review status.
- Posts with PENDING quarantine entries can still be visible to users                                                                                                                                                                                 
  (with redactions in place). A Stage 2 INJECTION/MALWARE/UNKNOWN verdict                                                                                                                                                                             
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

- **Stage 2 verdict** of `INJECTION`, `MALWARE`, or `UNKNOWN` → post                                                                                                                                                                                  
  stays `QUARANTINED` until admin review. The judge model treating                                                                                                                                                                                    
  `UNKNOWN` as a soft injection signal is intentional: a degraded judge                                                                                                                                                                               
  must never auto-release.
- **Stage 2 infrastructure failure** (LLM unreachable, timeout, unparseable                                                                                                                                                                           
  reply after retry) → release as `READY` with the **Stage 1 redactions
  retained**, mark the post for re-evaluation when the LLM returns,                                                                                                                                                                                   
  notify admin via the throttled channel. A profile-driven flag lets                                                                                                                                                                                  
  production profiles invert this default and keep the post quarantined.
- **Tagger** failure → fall back to `source.bootstrap_tags`, mark the post,                                                                                                                                                                           
  throttled admin notify. (This is why `/add-source --tags` is mandatory:                                                                                                                                                                             
  every source must have a deterministic fallback.)
- **Entity / embedding** failure → release without that artifact;                                                                                                                                                                                     
  cross-source linking is degraded for that post.
- **Admin notifications** are coalesced per `(channel, error_class)` for a                                                                                                                                                                            
  short window so an outage produces one summary message, not 200 individual                                                                                                                                                                          
  alerts.

A complete LLM outage degrades quality, not safety.

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

- **Collector role** — `INSERT/UPDATE` on ingest-owned tables; `SELECT` on                                                                                                                                                                            
  the rest; `INSERT`-only on `audit_log`; `LISTEN/NOTIFY`.
- **Provider role** — write access on user-state tables; `SELECT` on                                                                                                                                                                                  
  collector-owned tables; `SELECT` on the quarantine review *view* (no
  raw original content); `INSERT`-only on `audit_log`.
- **Admin role** — operator psql sessions only. Used for migrations, raw                                                                                                                                                                              
  quarantine inspection, occasional bulk fixes.

The split means a SQL-injection bug in the Provider cannot delete posts          
or quarantine entries.

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
- CAPTCHAs / human verification — adapter-level identity is the gate.
- Heuristic/anomaly-based banning — admin acts manually.
- Sybil resistance — not feasible without adapter changes; operators                                                                                                                                                                                  
  control invite distribution.
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