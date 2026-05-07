# infochat — Specification

This file is the **map**. It tells you what infochat is, where every part of                                                                                                                                                                          
the spec lives, and how the documentation is organized.

The full specification is split across three layers:

- **`docs/spec/`** — *what & why*. Durable, slow-changing. The authoritative
  source for every behavior the system must exhibit.
- **`docs/design/`** — *how*. Working notes: DDL, class names, package                                                                                                                                                                                
  layout, property keys, regex strings, retry counts, profile values. Allowed
  to change without a spec amendment.
- **`docs/00-mvp.md`** — the smallest end-to-end slice that proves the                                                                                                                                                                                
  architecture works. A strict subset of the spec.

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
  evening summaries, admin-only destructive operations, per-user-within-                                                                                                                                                                              
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
11. **[00-mvp.md](00-mvp.md)** — first slice to build.

For implementation:

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

- Every command listed in [spec/commands.md](spec/commands.md), including                                                                                                                                                                             
  `/ban`, `/unban`, `/promote`, `/demote`, `/lang`.
- SimpleX adapter (first messaging impl) plus an in-memory test adapter.
- OpenAI-compatible LLM provider (covers Ollama, llama.cpp, OpenAI,                                                                                                                                                                                   
  OpenRouter, NanoGPT) and an Anthropic provider.
- Hardware profiles: `laptop`, `vps`, `pi`, `remote`.
- Bootstrap source loader (idempotent, seeds the controlled vocabulary).
- Hybrid Tier 2 linking (entities + pgvector embeddings, profile-aware                                                                                                                                                                                
  index type).
- Layered ingest security with admin chat commands for quarantine review.
- Two admin tiers: bot admin + per-group admin.
- User ban (`/ban`/`/unban`).
- Group periodic morning / evening summaries with per-group timezone,                                                                                                                                                                                 
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

### Deferred to v2 (or later)

- External message broker (Kafka or similar) replacing in-process eval                                                                                                                                                                                
  channels.
- Additional `MessagingAdapter` impls (Telegram, Matrix, Signal, etc.).
- Granular roles (replacing `is_admin` with a `roles` table).
- Per-group bans / `/kick` distinct from bot-wide ban.
- `/recall <keyword>` and `/memories` commands.
- Admin web UI (instead of admin chat commands).
- More sophisticated cross-source linking (topic modeling).
- Concrete `TranslationProvider` impls beyond English + Czech.
- Auto-detect language from user message; v1 requires opt-in via `/lang`.
- Sybil resistance (requires adapter-level features SimpleX does not                                                                                                                                                                                  
  expose).

  ---                                                                                                                                                                                                                                                   

## 5. Glossary

- **Scope**: a user (DM) or a group (group chat). All state and configuration                                                                                                                                                                         
  is per-scope.
- **Tier 1 tag**: controlled vocabulary, exact-match, user-facing. Seeded                                                                                                                                                                             
  from the bootstrap sources file and extended by `/add-source --tags`.
- **Tier 2 tag**: free-form, internal-only. Includes named entities and                                                                                                                                                                               
  embedding vectors. Used to link related posts; never shown to users.
- **Post UID**: stable globally-unique ID for a fetched post. Returned in
  summaries; usable in `/save`, "tell me more about UID X" chat queries, etc.
- **Topic ID**: ID of a post cluster (connected component in the
  `post_reference` graph). Stable only within the periodic-summary cache                                                                                                                                                                              
  window; clusters are recomputed on cache expiry, so topic IDs are                                                                                                                                                                                   
  best-effort breadcrumbs, not durable references.
- **Memory entry**: a `chat_memory` row created by `/compress`. Per-(user,                                                                                                                                                                            
  scope).
- **Bot admin**: user with `is_admin = true`. Globally privileged.                                                                                                                                                                                    
  Bootstrapped from config.
- **Group admin**: user with `group_membership.is_group_admin = true` for a                                                                                                                                                                           
  specific group. Privileged within that group only. Bootstrapped by first                                                                                                                                                                            
  `@mention` in a new group.
- **Banned user**: user with `is_banned = true`. Blocked at message intake;                                                                                                                                                                           
  no LLM/DB invocation; receives one fixed reply.
- **Hardware profile**: named bundle of settings keyed by                                                                                                                                                                                             
  `infochat.profile=laptop|vps|pi|remote`. Picks context-window size,                                                                                                                                                                                 
  default chat / embedding model, eval concurrency, vector index type.
- **Fetcher type**: implementation that ingests a source URL (`rss`,                                                                                                                                                                                  
  `nitter`, `bluesky`, `odysee`, `youtube`, `reddit`, `nostr`).
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