# infochat — Specification

This file is the **map**. It tells you what infochat is, where every part of                                                                                                                                                                          
the spec lives, and how the documentation is organized.

The full specification is split across three layers:

- **`docs/spec/`** — *what & why*. Durable, slow-changing. The authoritative
  source for every behavior the system must exhibit.
- **`docs/design/`** — *how*. Working notes: DDL, class names, package                                                                                                                                                                                
  layout, property keys, regex strings, retry counts, profile values. Allowed
  to change without a spec amendment. The MVP slice
  (`docs/design/00-mvp.md`) lives here too — it picks the smallest
  end-to-end set of behaviours from the spec to build first; it is a
  design-tier reading, not a spec commitment.

If a documentation change would alter the system's *commitments* (a new          
command, a different trust boundary, a new SPI), it lives in `docs/spec/`.                                                                                                                                                                            
If it changes *values* (a new column, a tighter regex, a different default                                                                                                                                                                            
model), it lives in `docs/design/`.
                                                                                                                                                                                                                                                        
---                                                                                                                                                                                                                                                   

## 1. Purpose

infochat aggregates news (RSS) and social-media posts, runs them through an                                                                                                                                                                           
LLM evaluation pipeline (security check, tagging, entity extraction,                                                                                                                                                                                  
embedding), and serves them to users through a chat application. Users                                                                                                                                                                                
interact with the bot via:

- **Direct messages** (DM): one user, full feature set, private state.
- **Group chats**: bot replies only when `@mentioned`, periodic morning /
  evening digests, admin-only destructive operations, per-user-within-                                                                                                                                                                              
  group state isolation.

The system is two services:

- **Collector** — ingests, evaluates, stores. Headless. No user can address                                                                                                                                                                           
  it directly.
- **Provider** — handles user interaction through a pluggable messaging                                                                                                                                                                               
  adapter. Coordinates with Collector via Postgres `LISTEN/NOTIFY` on a                                                                                                                                                                               
  shared schema.

  ---                                                                              

## 2. Reading order

For new contributors / planners:

1. This file (the map).
2. **[spec/decisions.md](spec/decisions.md)** — every cross-cutting                                                                                                                                                                                   
   choice, in one place.
3. **[spec/architecture.md](spec/architecture.md)** — service split,                                                                                                                                                                                  
   pipelines, principles.
4. **[spec/security.md](spec/security.md)** — threat model, trust                                                                                                                                                                                     
   boundaries, ingest pipeline, authorization, failure handling.
5. **[spec/schema.md](spec/schema.md)** — entities and invariants.
6. **[spec/commands.md](spec/commands.md)** — surface conventions,
   command catalogue, permission model.
7. **[spec/llm.md](spec/llm.md)** — LLM SPI, per-task routing, embeddings,       
   translation, determinism boundary.
8. **[spec/messaging.md](spec/messaging.md)** — adapter contract,                                                                                                                                                                                     
   capability flags, progress notifications.
9. **[spec/deployment.md](spec/deployment.md)** — operator inputs,                                                                                                                                                                                    
   bootstrap behavior, configuration surface.
10. **[spec/verification.md](spec/verification.md)** — what the test                                                                                                                                                                                  
    suite must prove.

For implementation:

11. **[design/00-mvp.md](design/00-mvp.md)** — first slice to build
    (design-tier; picks the smallest end-to-end slice from the spec).
12. **[design/](design/)** — read the design note matching the spec section                                                                                                                                                                           
    you are working on. Each file carries a `Status: design notes, not spec`                                                                                                                                                                          
    banner.

  ---                                                                                                                                                                                                                                                   

## 3. Cross-cutting decisions

The full decision log lives in **[spec/decisions.md](spec/decisions.md)**.
SPEC.md does not duplicate it; treat the decisions file as the index of                                                                                                                                                                               
choices that shape every section.
                                                                                                                                                                                                                                                        
---                                                                              

## 4. v1 scope vs future

### In scope for v1

- Every command listed in [spec/commands.md](spec/commands.md).
- SimpleX and Signal adapters plus an in-memory test adapter.
- Invite-code registration (decision D44): DM access requires a UUID invite
  code generated by a bot admin and bound to a specific (contact\_id, adapter)
  pair; unknown contacts without a valid code receive a fixed rejection reply
  and are never registered.
- Slow-start tier (decision D45): newly registered users enter a probation
  period with a restricted read-only command subset; chat mode and write
  operations are blocked until the period elapses or a bot admin issues
  `/vouch`.
- OpenAI-compatible LLM provider (covers Ollama, llama.cpp, OpenAI,                                                                                                                                                                                   
  OpenRouter, NanoGPT) and an Anthropic provider.
- Hardware profiles: `laptop`, `vps`, `pi`, `remote`.
- Bootstrap source loader (idempotent, seeds the controlled vocabulary).
- Hybrid Tier 2 linking (entities + pgvector embeddings, profile-aware                                                                                                                                                                                
  index type).
- Layered ingest security with admin chat commands for quarantine review.
- Two admin tiers: bot admin + per-group admin.
- User ban (`/ban`/`/unban`).
- Group periodic morning / evening digests with per-group timezone,                                                                                                                                                                                 
  staggered scheduling, cache, and degraded fallback for low-power                                                                                                                                                                                    
  profiles.
- Auto-compress near the profile-defined context window ceiling.
- `TranslationProvider` SPI; English by default; opt-in per-scope language                                                                                                                                                                            
  via `/lang`. Concrete impls: English + Czech.
- Code-formatting convention (backticks) with adapter capability flag.
- In-place progress updates for long-running requests, with                                                                                                                                                                                           
  adapter-capability-gated fallback.
- `/stop` to cancel an in-flight chat-mode reply or user-issued
  `/summary`; `/retry` to regenerate the prose of the last
  summary-producing command (deterministic selection reused, LLM
  prose re-rolled).
- User data minimization (decision D37): fixed TTL on chat memory,
  `/forget` for user-initiated purge, `/export` for self-export, and a
  log policy that keeps user-authored prose out of non-audit logs.
  Application-layer per-user encryption is explicitly deferred with
  rationale (server must read plaintext to do its job).
- Nostr ingestion (decision D38): read-only, kinds 1 (text notes) and
  6 (reposts) only, operator-configured relay list (no NIP-65
  auto-discovery in v1), per-event signature verification before
  Stage 1, cross-relay dedup, per-relay degradation handling.
  Forever-no key handling: no signing, no publishing, no key
  storage.
- New `StreamSource` SPI (decision D38) alongside the existing
  `Fetcher`: long-lived event-stream sources (Nostr in v1) with their
  own connection lifecycle and per-source trust verification. Both
  feed the same outbox.
- Asset commands (decision D39): per-asset top-level commands                                                                                                                                                                                         
  (`/zcash`, `/monero` in v1) with sub-verbs that select the data                                                                                                                                                                                     
  source (`coingecko`, `kraken`, `bitfinex`). Operator-configured via                                                                                                                                                                                 
  `bootstrap-assets.json`; per-asset sub-verb allowlist (so                                                                                                                                                                                           
  `/monero binance` is correctly rejected — XMR is not listed on                                                                                                                                                                                      
  Binance). Public no-auth endpoints only in v1. Mandatory data-source
  attribution per reply. Polled-and-cached via the existing `Fetcher`                                                                                                                                                                                 
  SPI; data is **not** posts. Live websocket ticker mode and on-chain
  verbs are explicitly deferred to v2. **Asset commands are
  operator-configurable: a v1 deployment is conformant whether or
  not `bootstrap-assets.json` is provided.** When the file is
  absent, asset commands are disabled and do not appear in `/help`
  (`commands.md` §Asset commands "Enable / disable lifecycle"); the
  rest of v1 ships as normal.

### Deferred to v2 (or later)

- External message broker (Kafka or similar) replacing in-process eval                                                                                                                                                                                
  channels.
- Additional `MessagingAdapter` impls (Telegram, Matrix, etc.).
- Granular roles (replacing `is_admin` with a `roles` table).
- Per-group bans / `/kick` distinct from bot-wide ban. Note: in v1 a
  group admin cannot kick a misbehaving member from the bot's
  perspective — escalate to a bot admin for `/ban`.
- `/recall <keyword>` and `/memories` commands. v1 covers the
  underlying need with the chat agent's `recallMemory` tool
  (memory recall during conversation; security.md
  §Prompt-injection defenses) and the global `/forget` privacy
  lever; promoting these to first-class user commands is deferred
  because v1 has no user request for explicit memory listing /
  search outside a chat-mode prompt, and adding them now would
  multiply the rate-limit and translation surface without
  evidence of demand.
- Admin web UI (instead of admin chat commands).
- More sophisticated cross-source linking (topic modeling).
- Concrete `TranslationProvider` impls beyond English + Czech.
- Auto-detect language from user message; v1 requires opt-in via `/lang`.
- Sybil resistance (adapter-dependent; deferred to v2).
- Live ticker mode for asset commands (websocket-driven, in-place                                                                                                                                                                                     
  edits). Needs a new `TickerStream` SPI and a "background                                                                                                                                                                                            
  subscription" cross-cutting concept (decision D39).
- On-chain verbs for asset commands (`/zcash blocknumber`,                                                                                                                                                                                            
  `/monero hashrate`, etc.). Needs an explorer-adapter SPI.
- Auth-gated price sources (KuCoin, Gemini for most endpoints,                                                                                                                                                                                        
  CoinGecko Pro). Needs the operator-secret SPI. 
- Public IPFS/IPNS publication of periodic digests as a static
  JS-free page, regenerated on the existing 12h cadence, intended as
  an uncensorable demo of what the bot does. Design notes:
  [design/future/public-ipfs-publishing.md](design/future/public-ipfs-publishing.md). 

## 5. Glossary

- **Scope**: a user (DM) or a group (group chat). All state and configuration                                                                                                                                                                         
  is per-scope.
- **Tier 1 tag**: controlled vocabulary, exact-match, user-facing. Seeded                                                                                                                                                                             
  from the bootstrap sources file and extended by `/add-source --tags`.
- **Tier 2 tag**: free-form, internal-only. Includes named entities and                                                                                                                                                                               
  embedding vectors. Used to link related posts; never shown to users.
- **Post UID**: stable globally-unique ID for a fetched post. Returned in
  summaries; usable in `/save`, "tell me more about UID X" chat queries, etc.
- **Cluster**: a connected component in the `post_reference` graph —
  the unit of summary granularity. The summary surface (one prose
  block per cluster) and the determinism boundary (cluster set
  computed by deterministic SQL traversal before any LLM call) are
  both stated in terms of clusters.
  **Cluster ID**: the identifier of a cluster within a single
  computation. Stable only within the periodic-digest cache
  window; clusters are recomputed on cache expiry, so cluster IDs
  are best-effort breadcrumbs, not durable references.
- **Memory entry**: a `chat_memory` row created by `/compress`. Per-(user,                                                                                                                                                                            
  scope).
- **Bot admin**: user with `is_admin = true`. Globally privileged.                                                                                                                                                                                    
  Bootstrapped from config.
- **Group admin**: user with `group_membership.is_group_admin = true` for a                                                                                                                                                                           
  specific group. Privileged within that group only. Bootstrapped by first                                                                                                                                                                            
  `@mention` in a new group.
- **Banned user**: user with `is_banned = true`. Blocked at message intake;                                                                                                                                                                           
  no LLM/DB invocation; receives one fixed reply.
- **Hardware profile**: named profile (`laptop`, `vps`, `pi`,
  `remote`). Picks context-window size, default chat / embedding
  model, eval concurrency, vector index type. The property key that
  selects it is design-level and lives in
  `docs/design/07-deployment.md`.
- **Source kind**: the ingest type discriminator on a `source` row,
  picking both the SPI shape and the implementation. Fetcher-shaped
  kinds (polled): `rss`, `nitter`, `bluesky`, `odysee`, `youtube`,
  `reddit`. StreamSource-shaped kinds (long-lived): `nostr`. See
  decision D38.
- **StreamSource**: SPI for long-lived, event-driven ingest sources
  (decision D38). Distinct from `Fetcher`; both feed the same outbox.
  v1 implementations: Nostr.
- **Category**: coarse classification of a source (`news`, `blog`,                                                                                                                                                                                    
  `social`). Distinct from tags.
- **Progress notifier**: cross-cutting Provider component that turns                                                                                                                                                                                  
  business-logic stage events into messaging-adapter calls. Rate-limits and                                                                                                                                                                           
  coalesces edits per `(scope, requestId)`; never interpolates user input                                                                                                                                                                             
  into rendered strings.
- **Message handle**: opaque token returned by a messaging adapter's `send()`.                                                                                                                                                                        
  Lets a caller subsequently `update` or `finalize` the same visible message.                                                                                                                                                                         
  Adapter-defined contents — callers MUST NOT inspect or persist it.

  ---                                                                                                                                                                                                                                                   

## 6. How to evolve this spec

- **Adding a behavior commitment** (a new command, a new trust boundary, a                                                                                                                                                                            
  new SPI) → edit the relevant `docs/spec/` file and, if it's cross-cutting,                                                                                                                                                                          
  add a row to `spec/decisions.md`.
- **Refining a value** (column, regex, retry count, default model, profile                                                                                                                                                                            
  parameter) → edit `docs/design/` only. The spec stays unchanged.
- **Discovering an inconsistency between spec and design** → fix the design      
  to match the spec, or, if the spec is wrong, propose the spec change first
  and update design afterward.
- **Adding a new design note** → put it under `docs/design/` and reference it
  from the matching spec section's "What lives in design notes" trailer.  