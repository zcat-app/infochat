# infochat — Specification

Canonical entry point for the infochat project specification. This file is the **map and overview**. Detailed sections live in companion files.

---

## 1. Purpose

infochat aggregates news (RSS) and social-media posts, runs them through an LLM evaluation pipeline (security check, tagging, entity extraction, embedding), and serves them to users through a chat application. Users interact with the system via:

- **Direct messages** (DM): one user, full feature set, private state and memory.
- **Group chats**: bot replies only when `@mentioned`, periodic 8am/8pm summaries, admin-only destructive operations, per-user-within-group state isolation.

The system is two services:

- **Collector Server** — ingests, evaluates, stores. Headless. No user can interact with it directly.
- **Provider Server** — handles user interaction through a pluggable messaging adapter. Connects to the same database the Collector writes to. Coordinates with Collector via Postgres `LISTEN/NOTIFY` and the shared schema.

---

## 2. Reading order

1. [01-architecture.md](01-architecture.md) — components, data flow, module layout, runtime
2. [02-schema.md](02-schema.md) — every table, indices, partitioning, TTL rules
3. [03-commands.md](03-commands.md) — every slash command, args, errors, examples
4. [04-security.md](04-security.md) — layered ingest checks, quarantine workflow, admin model, prompt-injection defenses
5. [05-llm-and-embeddings.md](05-llm-and-embeddings.md) — `LlmProvider`/`EmbeddingProvider` SPI, model routing, prompt templates
6. [06-messaging.md](06-messaging.md) — `MessagingAdapter` SPI, SimpleX impl notes, in-memory test adapter
7. [07-deployment.md](07-deployment.md) — `docker-compose`, configuration, bootstrap, ops runbook
8. [08-verification.md](08-verification.md) — test strategy, fixtures, end-to-end smoke

---

## 3. Cross-cutting decisions (already settled)

These decisions inform every section. They are documented in detail in the linked files; this is the index.

| Decision | Choice | Detail in |
|---|---|---|
| Stack | Quarkus + Postgres+pgvector + LangChain4j + Java 21 + Maven | [01](01-architecture.md) |
| Modules | 5: `core`, `llm-adapter`, `messaging-adapter`, `collector`, `provider` | [01](01-architecture.md) |
| Eval queue | SmallRye in-memory channels + outbox pattern (post.status='RAW' before enqueue) | [01](01-architecture.md), [02](02-schema.md) |
| Collector→Provider events | Postgres LISTEN/NOTIFY (no Kafka in v1) | [01](01-architecture.md) |
| Tag tiers | Tier 1 controlled vocab (exact match, user-facing) + Tier 2 entities + embeddings (internal linking only) | [02](02-schema.md), [05](05-llm-and-embeddings.md) |
| Cross-source linking | Hybrid: named-entity match (high precision) + cosine similarity (recall) | [05](05-llm-and-embeddings.md) |
| Linking storage | `post_reference` with link_type and score, TTL 4 days, day-partitioned | [02](02-schema.md) |
| Source model | Global `source` + per-scope `source_subscription` + `scope_tag`. DM sources private to user; group sources visible to group, writable only by group admin. | [02](02-schema.md) |
| Source bootstrap | `bootstrap-sources.json` loaded by `@Startup` bean, idempotent upsert by `(fetcher, url)`. Seeds controlled-vocab tags. Schema: `name`, `url`, `fetcher` (rss/nitter/bluesky/odysee/youtube/reddit/nostr), `category` (news/blog/social), `tags[]`. | [02](02-schema.md), [07](07-deployment.md) |
| Admin tiers | **Bot admin** (`user.is_admin`, global) + **Group admin** (`group_membership.is_group_admin`, per-group). Bot admin = ban, /grant-admin, /remove-source globally, quarantine review. Group admin = /add-source, /follow-tag, /clear within their group only. Bootstrap: bot admin from config; first @mention in a new group becomes group admin (bot admins can override via /promote, /demote). | [04](04-security.md) |
| User identity | SimpleX contact ID (cryptographic, not display name). Trust the adapter's identity assertion. | [04](04-security.md), [06](06-messaging.md) |
| User ban | `user.is_banned` + `banned_at`/`banned_by`/`ban_reason`. `/ban`, `/unban` (bot admin only). Banned user gets one fixed response, no LLM/DB access. Cannot ban self or last admin. | [04](04-security.md), [03](03-commands.md) |
| `/save` | Per-user only (private even in groups), free-form personal tags, retention exemption (snapshot copy), 1000/user cap | [03](03-commands.md), [02](02-schema.md) |
| `/add-source` permissions | DM: any non-banned user (must pass `--tags` flag, ≥1 tag). Group: group admin only (must pass `--tags`). User-added sources have no LLM-failure fallback unless tags are explicit, hence `--tags` is mandatory. | [03](03-commands.md), [04](04-security.md) |
| Command UX | Slash-prefix only; single `-w` time flag (1h/24h/7d); friendly errors with fuzzy match; `<command> confirm` for destructive | [03](03-commands.md) |
| Per-scope tag prefs | `/follow-tag`, `/unfollow-tag` control which tags appear in periodic summaries | [03](03-commands.md) |
| Group bot behavior | Replies only on @mention; group-admin-only destructive ops; group members get read-only/chat access. Periodic 8am/8pm summary by scope timezone (default UTC). | [03](03-commands.md) |
| Periodic summary scheduling | Staggered start (30s/group offset) finishing before the slot wall-clock, results cached 60 min. On `pi` profile, defer to next slot if worker busy; max 30-min delay before degraded fallback (headlines + sources, no LLM prose). | [01](01-architecture.md), [03](03-commands.md) |
| Summary mode | On-the-fly for `/summary` (user request); pre-generated + cached for periodic group digests. | [03](03-commands.md), [05](05-llm-and-embeddings.md) |
| Security at ingest | Layered: deterministic Stage 1 (HTML sanitization, prompt-injection regex) + LLM Stage 2 (only on Stage 1 hits). Quarantine via admin chat commands. | [04](04-security.md) |
| Prompt-injection defense | Stage 1 + delimited untrusted-content wrappers in summarizer prompts; LLM never has admin tools | [04](04-security.md), [05](05-llm-and-embeddings.md) |
| Eval failure policy | **Per-stage handling, with Stage 2 split between verdict and infrastructure failure.** Stage 2 *verdict* (judge replied `INJECTION`/`MALWARE`/`UNKNOWN`): post stays `QUARANTINED` until admin review — never auto-released. Stage 2 *infrastructure failure* (LLM unreachable, timeout, unparseable response after 1 retry): release as `READY` with **Stage 1 redactions retained**, set `post.stage2_failed=true`, throttled admin notify, re-evaluate when LLM returns. Tagger stage: 1 retry → fallback to source's bootstrap tags → admin notify. Embedding stage: 1 retry → release post without embedding (skip Tier 2 linking). Admin notifications throttled: 1 per failure-class per 15 min, with count. | [04](04-security.md), [05](05-llm-and-embeddings.md) |
| Onboarding | Auto-register on first message; welcome with `/help`. Banned users blocked at message intake. | [03](03-commands.md) |
| `/compress` | Summary (8-10 sentences) + keywords (≤15) + referenced post/topic IDs, per (user, scope). Auto-trigger at 75% of context limit. | [03](03-commands.md), [02](02-schema.md) |
| Hardware profiles | Named profiles in config: `infochat.profile=laptop\|vps\|pi\|remote`. Each profile sets context size, default models, embedding model, eval concurrency, vector index type. Individual settings can be overridden per-property. | [05](05-llm-and-embeddings.md), [07](07-deployment.md) |
| Context window per profile | `laptop`: 16K (compress at 12K, hard 15K, model `llama3.1:8b`). `vps`: 8K (compress 6K, hard 7.5K, model `llama3.2:3b`). `pi`: 4K (compress 3K, hard 3.8K, model `llama3.2:1b`, embedding `all-minilm`). `remote`: 32K (compress 24K, hard 30K, model per provider). | [05](05-llm-and-embeddings.md), [07](07-deployment.md) |
| Vector index | Profile-driven: `hnsw` on laptop/vps/remote; `ivfflat` on pi (cheaper build, acceptable recall at small live set). | [02](02-schema.md), [05](05-llm-and-embeddings.md) |
| Memory retrieval | Hybrid: deterministic keyword pre-fetch (cheap, always) + `recall_memory()` agent tool (deep digs) | [05](05-llm-and-embeddings.md) |
| `/clear` semantics | Wipes only active context window. Long-term `chat_memory` is independent. | [03](03-commands.md) |
| Group memory | Per-(user, group) — same privacy model as `/save` | [02](02-schema.md), [03](03-commands.md) |
| Translation | `TranslationProvider` SPI. Default English everywhere; `/lang <code>` sets per-scope language (stored in `scope_preferences.language`). Direct generation in target language preferred (one LLM call) where the model supports it; post-translate fallback. Source post bodies are NEVER translated (deterministic retrieval and embeddings stay coherent). | [05](05-llm-and-embeddings.md), [03](03-commands.md) |
| Output formatting | Plain text default. Inline code in single backticks (`` `CVE-2026-1234` ``); multi-line code in triple backticks. URLs bare (no markdown link syntax). `MessagingAdapter` exposes a `supportsMarkdownCode` capability flag — adapters that render markdown get nicer output, others show backticks (still readable). | [06](06-messaging.md) |

---

## 4. v1 Scope vs Future

### In scope for v1

- All commands listed in [03-commands.md](03-commands.md), including `/ban`, `/unban`, `/promote`, `/demote`, `/lang`
- SimpleX adapter (first messaging impl) + `InMemoryAdapter` for tests
- OpenAI-compatible LLM provider (covers Ollama, llama.cpp, OpenAI, OpenRouter, NanoGPT) + Anthropic provider
- Hardware profiles: `laptop`, `vps`, `pi`, `remote`
- Bootstrap source loader from `bootstrap-sources.json`
- Hybrid Tier 2 linking (entities + pgvector embeddings, profile-aware index type)
- Layered security with admin chat commands for quarantine review
- Two admin tiers: bot admin + per-group admin
- User ban (`/ban`/`/unban`)
- Group periodic 8am/8pm summaries with per-group timezone, staggered scheduling, cache, Pi-profile fallback
- Auto-compress at 75% of profile-defined context window
- `TranslationProvider` SPI; English by default, opt-in per-scope language via `/lang`
- Code-formatting convention (backticks) with adapter capability flag

### Deferred to v2 (or later)

- Kafka-based eval queue (replace in-memory if scale demands)
- Additional `MessagingAdapter` impls (Telegram, Matrix, Signal)
- Granular roles (`is_admin` → `roles` table with `manage_sources`, `moderate_security`, etc.)
- Per-group bans / `/kick` (separate from bot-wide ban)
- `/recall <keyword>` and `/memories` commands
- Admin web UI (instead of admin chat commands)
- Cross-source linking via more sophisticated topic modeling
- Concrete `TranslationProvider` impls beyond English+Czech (configurable language is supported, but only English and Czech are shipped/tested in v1)
- Auto-detect language from user message; user must opt-in with `/lang`

---

## 5. Glossary

- **Scope**: a user (DM) or a group (group chat). All state and configuration is per-scope.
- **Tier 1 tag**: controlled vocabulary, exact-match, user-facing. Seeded from `bootstrap-sources.json` and extended by `/add-source --tags`.
- **Tier 2 tag**: free-form, internal-only. Includes named entities and embedding vectors. Used to link related posts; never shown to users.
- **Post UID**: stable globally-unique ID for a fetched post. Returned in summaries; usable in `/save`, "tell me more about UID X" chat queries, etc.
- **Topic ID**: ID of a post cluster (connected component in `post_reference`). Stable only within the 60-minute summary cache window — clusters are recomputed on cache expiry, so topic IDs are best-effort breadcrumbs, not durable references. See [05-llm-and-embeddings.md §5.4.4](05-llm-and-embeddings.md).
- **Memory entry**: a `chat_memory` row created by `/compress`. Per-(user, scope).
- **Bot admin**: user with `is_admin = true`. Globally privileged. Bootstrapped from config (SimpleX contact ID).
- **Group admin**: user with `group_membership.is_group_admin = true` for a specific group. Privileged within that group only. Bootstrapped by first @mention in a new group.
- **Banned user**: user with `is_banned = true`. Blocked at message intake; no LLM/DB invocation; receives one fixed reply.
- **Hardware profile**: named bundle of settings keyed by `infochat.profile=laptop|vps|pi|remote`. Picks context-window size, default chat model, embedding model, eval concurrency, vector index type.
- **Fetcher type**: implementation that ingests a source URL (`rss`, `nitter`, `bluesky`, `odysee`, `youtube`, `reddit`, `nostr`).
- **Category**: coarse classification of a source (`news`, `blog`, `social`). Displayed in `/list-sources`. Distinct from tags.
