# 01 — Architecture

## 1.1 Components

```
┌─────────────────────┐         ┌──────────────────────┐
│  Collector Server   │         │  Provider Server     │
│  (headless)         │         │  (user-facing)       │
│                     │         │                      │
│ - Bootstrap loader  │         │ - Messaging adapter  │
│   (sources JSON)    │         │ - Command router     │
│ - Feed schedulers   │         │ - Chat agent         │
│ - Fetchers (RSS,    │         │ - Group summary      │
│   Reddit, Bluesky,  │         │   scheduler (stagger)│
│   Nitter, Nostr,    │         │ - Summary cache      │
│   Odysee, YouTube)  │         │ - Admin guard        │
│ - Eval pipeline     │         │   (bot + group tier) │
│   (Stage1, Stage2,  │         │ - Ban guard          │
│   tagger, entities, │         │ - Confirmation svc   │
│   embeddings)       │         │ - Translation        │
│ - Outbox rehydrator │         │ - Rate limiter       │
│ - Linking job       │         │                      │
│ - TTL pruner        │         │                      │
│ - Admin notifier    │         │                      │
│   (throttled)       │         │                      │
└──────────┬──────────┘         └──────────┬───────────┘
           │                               │
           │       ┌──────────────────┐    │
           └──────▶│  PostgreSQL      │◀───┘
                   │  + pgvector      │
                   │                  │
                   │  LISTEN/NOTIFY:  │
                   │  - new_post      │
                   │  - quarantine    │
                   └──────────────────┘
                           │
                           │  (provider also calls
                           │   LLM and Messaging
                           │   adapters)
                           ▼
            ┌──────────────────────────────┐
            │  External:                   │
            │  - Ollama / llama.cpp / etc. │
            │  - SimpleX Chat CLI (WS bot) │
            └──────────────────────────────┘
```

## 1.2 Module layout (Maven)

```
infochat/
├── pom.xml                          # parent POM, BOM, plugin versions
├── infochat-core/                   # shared DTOs, Panache entities, repos, Flyway
├── infochat-llm-adapter/            # LlmProvider, EmbeddingProvider SPI + impls
├── infochat-messaging-adapter/      # MessagingAdapter SPI + Simplex impl
├── infochat-collector/              # Quarkus app: schedulers, fetchers, eval pipeline
└── infochat-provider/               # Quarkus app: command router, chat agent
```

`infochat-core` deliberately bundles the shared DTOs and the Panache entities/repositories. Splitting them out is a refactor for v2 if a third consumer appears.

## 1.3 Key data flow: ingest

```
Source (RSS/social)
  │
  ▼
Fetcher → INSERT post(status='RAW', body=sanitized_html)
  │       (deterministic Stage 1 security check happens here:
  │        OWASP Java HTML Sanitizer + prompt-injection regex
  │        flags suspicious spans, replaces with placeholder IDs,
  │        stores originals in `quarantine` table.)
  │
  ▼
ENQUEUE post_id on `eval` channel  ──────────┐
                                              │
  (if collector restarts, OutboxRehydrator    │
   on @Startup scans status IN ('RAW',        │
   'EVALUATING') and re-enqueues.)            │
                                              ▼
Eval pipeline workers consume (per-stage failure policy):
  1. SecurityStage2Judge (LLM, only if Stage 1 flagged anything) → may quarantine
       on Stage 2 *verdict* of injection/exfiltration → QUARANTINED.
       on Stage 2 *infrastructure failure* (LLM down, timeout, parse error after
       1 retry) → release as READY with Stage 1 redactions in place.
       Rationale: Stage 1 already substituted suspicious spans with placeholder IDs,
       so the body is safe; downgrading to READY-with-redactions avoids growing a
       quarantine backlog the human admin cannot drain when the LLM is unhealthy.
       Sets post.stage2_failed=true and notifies admin (throttled). See 04-security §4.4.
  2. Tagger LLM → assigns 1+ Tier-1 tags from controlled vocab
       on failure: 1 retry → fallback to source.bootstrap_tags →
       admin notify (throttled). Sets post.tagger_fallback=true for audit.
  3. EntityExtractor LLM → extracts named entities → post_entity rows
       on failure: 1 retry → release without entities (Tier 2 entity-link
       coverage degraded for this post). Admin notify (throttled).
  4. EmbeddingWorker → embeds title+summary → post_embedding row
       on failure: 1 retry → release without embedding (semantic links
       degraded). Admin notify (throttled).
  5. UPDATE post.status='READY', NOTIFY new_post

Admin notifier batches identical failure classes for 15 minutes
before sending one summary message ("Tagger LLM failed for 47 posts in
the last 15 min, last error: connection refused").
  │
  ▼
LinkingJob (scheduled, every N minutes):
  - Walks last 4 days of READY posts.
  - For each post: find candidate links via:
    - shared post_entity rows (exact-match) → link_type='entity', score=#shared
    - cosine_distance < 0.18 within 48h window → link_type='semantic', score=cosine
  - INSERTs into post_reference (capped at N per post)
```

## 1.4 Key data flow: user request

DM, command mode (`/summary security -w 24h`):

```
Messaging adapter receives message
  │
  ▼
CommandParser detects /summary → routes to SummaryCommand
  │
  ▼
AdminGuard checks (no-op for non-destructive command)
  │
  ▼
Deterministic SQL:
  SELECT posts WHERE tag='security' AND fetched_at > now() - 24h
  AND scope subscribes to source(s) of those posts
  │
  ▼
Cluster posts by post_reference graph (connected components)
  │
  ▼
Summarizer LLM prompt:
  - System: "summarize for plain text messaging, no markdown..."
  - User: pre-built clusters with metadata + post bodies in <<<UNTRUSTED>>> wrappers
  │
  ▼
Format response (no markdown), include topic IDs and post UIDs
  │
  ▼
Messaging adapter sends to user
```

DM, chat mode ("Tell me more about that CVE you mentioned"):

```
Messaging adapter receives message
  │
  ▼
CommandParser sees no slash → routes to ChatAgent
  │
  ▼
Memory pre-fetch: SQL keyword match on chat_memory for (user, scope) → load top-N
  │
  ▼
Build agent prompt with:
  - System prompt (with delimiter rules for untrusted content)
  - Active context window (capped per profile: 16K laptop / 8K vps / 4K pi / 32K remote)
  - Pre-fetched memory summaries
  - Available tools: searchByTag, getPostById, getReferences, recallMemory
    (NEVER admin tools, NEVER raw SQL)
  │
  ▼
LLM call. Agent may use tools. Each tool call goes through:
  - Type-checked args (enums, validated ranges)
  - Per-user rate limits
  - Read-only DB role (cannot write)
  │
  ▼
Format response (plain text + backticks for code) → optional
TranslationProvider.translate() if scope language ≠ 'en' →
send via messaging adapter
```

## 1.4.1 Group periodic summary (8am / 8pm)

```
GroupSummaryScheduler (CRON-like, runs every minute):
  for each group with periodic summary enabled:
    if local_now in [target - 30min, target] and not generated_today:
      enqueue generation slot, offset = (group_index * 30s)

GroupSummaryWorker:
  - Same SQL retrieval path as /summary, scoped to group's followed tags
  - LLM summarization (with delimiter-wrapped untrusted content)
  - Optional translation per group's language preference
  - Cache(group_id, slot) for 60 minutes (so a user's /summary
    immediately after the digest is served from cache)
  - Send to messaging adapter

Profile-aware fallback (pi profile):
  - If worker is busy, defer to next slot (max 30-min delay)
  - On second-defer, generate degraded summary:
    headline list + source names, no LLM prose
```

## 1.4.2 Bootstrap loader (Collector startup)

```
@Startup BootstrapLoader (runs after Flyway migrations):
  1. Read infochat.bootstrap.sources-file (default: bootstrap-sources.json)
  2. For each entry, validate schema (name, url, fetcher, category, tags[])
  3. Upsert into source by (fetcher, url):
     - INSERT if absent
     - UPDATE name/category/tags only if entry differs
     - NEVER deletes; admin uses /remove-source for that
  4. Union of tags across all entries → upsert into tag table
     (Tier-1 controlled vocab)
  5. Audit-log the bootstrap run with file hash + entry count
```

### Startup-bean ordering

Both services use `io.quarkus.runtime.Startup` with explicit `@Priority` so a bean
never observes uninitialised state from a peer bean. Lower priority numbers run first.

Collector:

| Priority | Bean              | Purpose                                                       |
|---------:|-------------------|---------------------------------------------------------------|
| 100      | (Flyway)          | Quarkus runs Flyway migrations before any `@Startup` bean.    |
| 200      | BootstrapLoader   | Seeds `source` and `tag` from `bootstrap-sources.json`.       |
| 300      | OutboxRehydrator  | Re-enqueues posts left in `RAW`/`EVALUATING` from prior crash.|
| 400      | FetchScheduler    | Begins per-source polling.                                    |

Provider:

| Priority | Bean              | Purpose                                                       |
|---------:|-------------------|---------------------------------------------------------------|
| 100      | (Flyway)          | Idempotent re-run; same migration set as Collector.           |
| 200      | AdminBootstrap    | Ensures `infochat.admin.contact-id` has `is_admin=true`.      |
| 300      | AdapterRegistry   | Resolves and connects the configured `MessagingAdapter`.      |
| 400      | CommandRouter     | Begins consuming inbound messages from the adapter.           |

If any bean throws during startup, the service refuses to start (Quarkus default).
Health endpoint `/q/health/ready` stays 503 until every priority < 500 bean is up.

## 1.5 Architectural principles

1. **Determinism boundary.** All retrieval (which posts come back) is SQL. LLMs only generate prose or extract structured fields at ingest. The same `/summary security` call returns the same set of posts twice in a row.
2. **Outbox + LISTEN/NOTIFY.** No external message broker in v1. Postgres provides durability (outbox) and push semantics (NOTIFY). Adding Kafka is a v2 swap, not a rewrite.
3. **No LLM in the trust path.** Admin checks, source subscription, quarantine approval — all deterministic Java. LLM influence is downstream of authorization, never upstream.
4. **Per-(user, scope) isolation by construction.** Every data row that holds user state has a `scope_kind` (`'dm'` or `'group'`) and `scope_id` (or equivalent FKs). All queries filter on these. Prevents cross-user leaks at the storage layer.
5. **TTL by partitioning, not DELETE.** `post_embedding` and `post_reference` are partitioned by day. Old partitions are dropped wholesale. No row-level deletes, no index bloat.
6. **Adapters are SPIs.** `LlmProvider`, `EmbeddingProvider`, `MessagingAdapter` are CDI-injected interfaces. Concrete impls picked by config. Test doubles slot in for CI.

## 1.6 Concurrency and rate limiting

- **Per-source HTTP**: each source has a politeness window (configurable, default 5 minutes). Honors `Retry-After` on 429/503. Uses `org.eclipse.microprofile.faulttolerance` for retry+backoff.
- **Per-user command throttle**: token bucket, default 30 commands/minute per user. Configurable. Returns a friendly "slow down" message on overflow.
- **LLM client**: bounded concurrency via Quarkus `vertx` worker pool. Profile defaults: `laptop=4`, `vps=2`, `pi=1`, `remote=8`. Per-task overridable via `infochat.llm.<task>.max-concurrency`.
- **Eval channel**: bounded queue size (configurable, profile-driven). If full, fetcher blocks (back-pressure to feed schedulers, which is the desired behavior — avoids unbounded memory growth on LLM slowness).
- **Periodic summary worker**: count is profile-driven — `laptop=4`, `vps=2`, `pi=1`, `remote=8` (see §1.7 table). Generation requests are enqueued with stagger and processed serially per worker. Operators can override via `infochat.summary.workers`.

## 1.7 Hardware profiles

`infochat.profile=laptop|vps|pi|remote` selects a bundle of defaults applied at startup. Individual properties can still be set explicitly to override.

| Setting | laptop | vps | pi | remote |
|---|---|---|---|---|
| Chat model | `llama3.1:8b` Q4 | `llama3.2:3b` | `llama3.2:1b` | per provider |
| Embedding model | `nomic-embed-text` (768-d) | `nomic-embed-text` | `all-minilm:33m` (384-d) | provider default |
| Context window | 16K | 8K | 4K | 32K |
| Auto-compress at | 12K (75%) | 6K (75%) | 3K (75%) | 24K (75%) |
| Hard limit | 15K (94%) | 7.5K (94%) | 3.8K (94%) | 30K (94%) |
| LLM concurrency | 4 | 2 | 1 | 8 |
| Vector index | `hnsw` | `hnsw` | `ivfflat` | `hnsw` |
| Eval queue size | 1024 | 256 | 64 | 4096 |
| Summary workers | 4 | 2 | 1 | 8 |
| Stage 2 LLM | small judge model | small judge | tiny judge | provider judge |

Profile selection is logged at startup. `/status` (admin) reports the active profile and any property overrides.

## 1.8 Translation flow

```
Provider Server response path (any user-facing text):
  raw_text (English)
    │
    ▼
  scope_lang = scope_preferences.language (default 'en')
  if scope_lang == 'en':
    return raw_text
  else:
    TranslationProvider.translate(raw_text, from='en', to=scope_lang)
      → cached by (sha256(raw_text), to_lang) for 24h to amortize cost
    → return translated_text
    │
    ▼
  MessagingAdapter.send()
```

Notes:
- For supported model + language combinations (Czech via `llama3.1:8b` and larger), the summarizer can be invoked with `target_language=cs` directly to save a round-trip. `Summarizer` exposes `LanguageAware` capability.
- Source post bodies are **never** translated. Embeddings, retrieval, and entity extraction always operate on the original language. Translation is purely a presentation-layer concern.
- Command parsing (`/summary`, `/save`, etc.) is English-only in v1.
