# infochat — MVP Scope

This file defines the **smallest end-to-end slice** of infochat that proves the architecture works on a single laptop. It is **not** a replacement for [SPEC.md](SPEC.md); it is a strict subset.

The goal of the MVP is to demonstrate, on the `laptop` profile, that:

1. RSS posts can be fetched, evaluated by the LLM pipeline, and stored.
2. A user can subscribe to a source via chat and request a summary.
3. The two-service split (Collector + Provider) and the outbox/notify wiring actually work end-to-end.

Anything not strictly required to prove those three points is **deferred** — see §5 below.

---

## 1. Profile and runtime

- **Single profile**: `laptop` only. `vps`, `pi`, and `remote` are not exercised in the MVP, but the profile-selection plumbing must already be in place (so adding them later is config, not refactor).
- **Single LLM provider**: OpenAI-compatible HTTP (pointed at local Ollama by default). Anthropic provider is deferred.
- **Single embedding model**: whatever the `laptop` profile picks (default Ollama embedding). Pi-only fallbacks are deferred.
- **Single host**: `docker-compose` brings up Postgres+pgvector, Ollama, Collector, Provider, plus the test/in-memory adapter shell. No remote deployment, no TLS, no reverse proxy.

---

## 2. Schema (MVP tables only)

Only the tables required by the MVP fetch → evaluate → store → query path. All other tables from [02-schema.md](02-schema.md) are deferred.

In scope:

- `user` — minimal columns: `id`, `contact_id`, `created_at`, `is_admin`, `is_banned`. Ban columns (`banned_at`, `banned_by`, `ban_reason`) can stay NULL but the columns must exist so the schema doesn't have to be re-migrated.
- `source` — `id`, `name`, `kind` (must equal `'rss'` in the MVP), `identifier` (URL for `rss`), `config` (JSONB, NULL in MVP — reserved for per-kind options), `category`, `tags[]`, `created_at`, `deleted_at` (nullable; soft-delete column reserved even though `/remove-source` is deferred). Identity is `(kind, identifier)` per decision D38; the legacy `(fetcher, url)` shape is **not** used.
- `source_subscription` — links a `(scope, source)` pair. DM scope only in MVP (no groups).
- `post` — `id`, `uid`, `source_id` (FK with `ON DELETE RESTRICT`), `fetched_at`, `published_at`, `title`, `body`, `url`, `status` (`RAW` | `READY` | `QUARANTINED`), `stage2_failed` (bool), `tags[]`, `embedding` (vector).
- `scope_preferences` — minimal: a row per DM scope keyed by `user.id`. "Scope" itself is a discriminator (`'dm'` / `'group'`) on user-state rows, **not** a separate table. Language column defaults to `'en'` and is read-only in MVP.
- `tag` — controlled-vocabulary table seeded from `bootstrap-sources.json`.
- `audit_log` — append-only table; only the bot-admin bootstrap and `/add-source` events are written in MVP.

Deferred (not created in MVP):

- `group`, `group_membership`
- `saved_post`, `chat_memory`, `chat_session`
- `post_reference`, `post_entity`
- `quarantine_review`, `admin_notification`
- `summary_cache`

Indices: just enough to make MVP queries cheap. `pgvector` index is `hnsw` (laptop profile), built on `post.embedding`. `post.status` and `post.fetched_at` get btree indices. Day-partitioning of `post_reference` is deferred along with the table itself.

---

## 3. Pipeline (MVP)

Only one fetcher, only the necessary eval stages.

**Fetcher**: `rss` only. The fetcher SPI must be in place so adding `nitter`, `bluesky`, etc. is a class drop-in, but **no other fetcher is implemented in MVP**.

**Eval pipeline**: SmallRye in-memory channels with the outbox pattern (post inserted with `status='RAW'` before enqueue; rehydrator on startup re-enqueues unfinished work).

Stages in MVP, in order:

1. **Security Stage 1** (deterministic) — HTML sanitization, prompt-injection regex with ReDoS protection (RE2/J or 100 ms watchdog), Unicode bidi/zero-width strip, SSRF guard on outbound fetches.
2. **Security Stage 2** (LLM judge) — only invoked on Stage 1 hits. Verdict outcomes (`INJECTION` / `MALWARE` / `UNKNOWN`) → `QUARANTINED`. Infrastructure failure (after 1 retry) → release as `READY` with Stage 1 redactions retained and `post.stage2_failed=true`; throttled admin notify; re-evaluate when the LLM returns.
3. **Tagger** — emits Tier 1 tags only, drawn from the controlled vocab. 1 retry → fallback to the source's bootstrap tags → admin notify.
4. **Embedding** — single embedding per post. 1 retry → release without embedding (skip any future Tier 2 linking).

Deferred from the pipeline:

- Entity extraction (Tier 2 tags)
- Cross-source linking (`post_reference` builder, named-entity match, cosine similarity over pgvector)
- Topic clustering / topic IDs
- Translation pipeline (`TranslationProvider` SPI is **not** implemented in MVP — English only, hardcoded)
- Periodic 8am/8pm group digests, staggered scheduler, summary cache
- Auto-compress / `chat_memory` / hybrid memory retrieval
- Quarantine review workflow (admin chat commands, admin notification throttling beyond a stub log line)

---

## 4. Messaging adapter and commands

**Adapter**: `InMemoryAdapter` only. SimpleX adapter is deferred. The `MessagingAdapter` SPI (with `supportsMarkdownCode` capability flag) must already be in place so SimpleX is a later add-on, not a refactor.

**Commands** — only the three needed to prove the slice works end-to-end:

- `/help` — static text listing the three MVP commands.
- `/add-source <url> --tags tag1,tag2[,...]` — DM only; non-banned user; `--tags` mandatory; idempotent on `(kind='rss', identifier=<url>)` (decision D38).
- `/summary [-w 1h|24h|7d]` — DM only; on-the-fly summarization (no cache); deterministic SQL select of READY posts in the time window for the user's subscriptions; LLM produces prose; topic IDs are **not** included in MVP output.

Everything else from [03-commands.md](03-commands.md) is deferred — see §5.

**Onboarding**: auto-register on first DM message; reply with `/help`. No group onboarding (groups are deferred).

**Output formatting**: plain text, backticks for inline/multi-line code, bare URLs. Adapter capability flag is honored (the `InMemoryAdapter` reports `supportsMarkdownCode=false` so the test transcripts stay readable).

---

## 5. What is NOT in MVP

This is the explicit deferred list. Each item below is fully specified elsewhere in the docs but **must not be built in the MVP**.

### Fetchers
- `nitter`, `bluesky`, `odysee`, `youtube`, `reddit`, `nostr`

### Pipeline
- Entity extraction / Tier 2 tags
- Cross-source linking (`post_reference`, named-entity match, cosine similarity)
- Topic IDs / topic clustering
- `TranslationProvider` SPI and `/lang`
- Periodic 8am/8pm group summaries, staggered scheduler, summary cache, Pi-profile degraded fallback
- Auto-compress at 75% context, `chat_memory`, hybrid memory retrieval, `recall_memory()` agent tool

### Schema
- `group`, `group_membership`
- `saved_post`, `chat_memory`, `chat_session`
- `post_reference`, `post_entity`
- `quarantine_review`, `admin_notification`
- `summary_cache`
- `scope_tag` follow/unfollow rows

### Commands
- `/save`, `/saved`, `/unsave`
- `/list-sources`, `/remove-source` (the soft-delete column exists; the command does not)
- `/follow-tag`, `/unfollow-tag`
- `/lang`
- `/compress`, `/clear`
- `/ban`, `/unban`
- `/promote`, `/demote`
- `/grant-admin`, `/revoke-admin`
- Quarantine review commands (admin-side)
- Group `@mention` reply path

### Adapters / providers
- SimpleX adapter
- Anthropic LLM provider (only OpenAI-compatible in MVP)
- Concrete `TranslationProvider` impls
- `vps`, `pi`, `remote` hardware profiles (config plumbing in place, not exercised)

### Operations
- Quarantine review UI (chat or web)
- Admin notification throttling (a stub log line is fine for MVP)
- Audit-log entries beyond bot-admin bootstrap and `/add-source`
- Backups, rotation, secret management beyond `application.properties`

---

## 6. MVP exit criteria

The MVP is "done" when, on a fresh `docker-compose up` with the laptop profile:

1. The bootstrap loader seeds `source` rows and the controlled-vocab `tag` rows from `bootstrap-sources.json`.
2. The bot admin (from `infochat.admin.contact-id`) exists in `user` with `is_admin=true` and an `audit_log` row records the bootstrap.
3. A non-admin user, sending their first DM via `InMemoryAdapter`, is auto-registered and receives `/help`.
4. `/add-source <rss-url> --tags news,tech` inserts a `source` row, a `source_subscription`, and emits a `LISTEN/NOTIFY` event the Collector picks up.
5. The Collector fetches the RSS feed, runs Stage 1 → (Stage 2 only on hits) → tagger → embedding, persists `post` rows with `status='READY'` (or `QUARANTINED` for malicious fixtures), and notifies the Provider.
6. `/summary -w 24h` returns LLM prose covering only that user's subscribed posts in the window, plus bare-URL citations.
7. A fixture with a known prompt-injection payload lands in `QUARANTINED` (not `READY`).
8. Killing the Collector mid-evaluation and restarting it re-enqueues unfinished `RAW` posts (outbox rehydrator works).

When all eight pass on a clean checkout, the MVP is complete and the deferred items in §5 become the v1 backlog.
