> **Status: design notes, not spec.**
> Implementation details below (DDL, class names, package layout, property keys,
> retry counts, regex strings, etc.) are working notes that may change without a
> spec amendment. The authoritative *what & why* lives in `docs/spec/`.

---

# 07 — Deployment and configuration

This file specifies how to deploy and operate the two services. Covers: configuration model, hardware profiles, `docker-compose` for local dev, the bootstrap files, environment variables, secrets, runbook, backup/restore, and upgrade.

The runtime stack is fixed by [../spec/architecture.md](../spec/architecture.md) §Purpose: **Java 25 + Quarkus 3.33 LTS + PostgreSQL with pgvector + LangChain4j** (decision D1). Java 25 is a hard requirement; Quarkus 3.33 LTS is the first LTS line that supports Java 25 end-to-end. Operators MUST install a JDK 25 distribution; running on JDK 21 or earlier is not supported and Provider/Collector startup will fail with a class-format error.

v1 runs **exactly one Collector and exactly one Provider** against a shared Postgres ([../spec/architecture.md](../spec/architecture.md) §Deployment topology, decision D41); see §7.8.5 for the advisory-lock enforcement that makes "exactly one" an invariant rather than a policy. The Provider may run **one or more messaging adapters** in the same process (any non-empty subset of SimpleX, Signal, and the in-memory test adapter — D46; v1 production set is SimpleX + Signal). The system is designed to run on a single host for v1; splitting onto multiple hosts is straightforward but not required.

---

## 7.1 Topology options

### Option A: Single-host (v1 default)

```
┌────────────────────── host ──────────────────────┐
│  ┌──────────┐    ┌──────────────┐                │
│  │ Postgres │◀───│  Collector   │                │
│  │ +pgvector│    └──────────────┘                │
│  │          │    ┌──────────────┐                │
│  │          │◀───│  Provider    │──┐             │
│  └──────────┘    └──────────────┘  │             │
│                                    │             │
│  ┌──────────────┐    ┌──────────┐  │             │
│  │  Ollama      │◀───│ Provider │──┤             │
│  │  llama.cpp   │◀───│ Collector│  │             │
│  └──────────────┘    └──────────┘  │             │
│                                    │             │
│  ┌──────────────┐  ┌──────────────┐│             │
│  │  simplex-cli │◀─│  Provider    ││             │
│  │   (WS bot)   │  │   ↳ SimpleX  ││             │
│  └──────────────┘  │     adapter  ││             │
│                    │   ↳ Signal   │┘             │
│  ┌──────────────┐  │     adapter  │              │
│  │  signal-cli  │◀─└──────────────┘              │
│  │  (JSON-RPC)  │                                │
│  └──────────────┘                                │
└──────────────────────────────────────────────────┘
```

One Provider, one or more adapters in the same JVM (D46). The diagram shows the v1 production shape with both adapters enabled; a SimpleX-only or Signal-only deployment omits the corresponding sidecar. The `signal-cli` JSON-RPC subprocess is the provisional default Signal wire-protocol path ([06-messaging.md §6.5.1](06-messaging.md)).

Recommended for: laptop dev, VPS, Raspberry Pi.

### Option B: Split-host (operator choice; not required)

- Postgres on dedicated host (managed or self-hosted).
- Ollama on a GPU host (laptop with eGPU, or a small home server).
- Collector + Provider colocated on a small VPS.
- `simplex-cli` and `signal-cli` (one per enabled adapter) run alongside Provider.

The schema and code are identical; only `application.properties` URLs differ.

---

## 7.2 Hardware profiles

`infochat.profile=laptop|vps|pi|remote-llm` is the single most important config. It selects defaults for context window, models, eval concurrency, vector index, etc. See [05-llm-and-embeddings.md §5.7](05-llm-and-embeddings.md) for the canonical model/embedding table.

| Profile | Hardware | Local model? | Notes |
|---|---|---|---|
| `laptop` | 16–32 GB RAM, decent CPU/GPU, dev workstation | yes | Development default. |
| `vps` | 8–16 GB RAM, CPU only, cloud VPS | yes | Production-grade for moderate load. |
| `pi` | Raspberry Pi 5 (8 GB) | yes (1B param model) | Best-effort. Czech translation quality limited. Embedding via `all-minilm:33m` (384-d). |
| `remote-llm` | Provider runs anywhere; LLM is OpenAI/Anthropic/NanoGPT/etc. | no | Operator-explicit opt-in for sending post bodies to remote APIs. Local DB and services, remote LLM API ([../spec/architecture.md](../spec/architecture.md) §Hardware profiles). |

### 7.2.1 Per-profile cross-cutting values

Values that other design files forward-reference. The spec commits to the existence of each knob; the per-profile defaults below are tuning. An explicit operator override always wins.

| Setting | `laptop` | `vps` | `pi` | `remote-llm` | Source / forward-ref |
|---|---|---|---|---|---|
| Adapter `maxInboundMessageBytes` (transport-layer cap) | 16 KiB | 32 KiB | 8 KiB | 32 KiB | [06-messaging.md §6.2.2](06-messaging.md) |
| Outbound retry — base delay | 250 ms | 250 ms | 500 ms | 500 ms | [06-messaging.md §6.3.6](06-messaging.md) |
| Outbound retry — growth factor | ×2 | ×2 | ×2 | ×2 | [06-messaging.md §6.3.6](06-messaging.md) |
| Outbound retry — jitter | full | full | full | full | [06-messaging.md §6.3.6](06-messaging.md) |
| Outbound retry — max attempts | 3 | 3 | 3 | 3 | [06-messaging.md §6.3.6](06-messaging.md) |
| Bot-removed-from-group threshold (consecutive `PERMANENT` sends) | 3 | 3 | 5 | 3 | [06-messaging.md §6.3.6](06-messaging.md) |
| Advisory-lock heartbeat interval | 10 s | 10 s | 30 s | 10 s | §7.8.5 (D41) |
| Advisory-lock heartbeat staleness threshold | 60 s | 60 s | 180 s | 60 s | §7.8.5 |
| StreamSource per-relay cooldown | 60 s | 60 s | 300 s | 60 s | [../spec/architecture.md](../spec/architecture.md) §Ingest SPIs |
| StreamSource all-relays-bad cycle cap | 20 | 20 | 10 | 20 | [../spec/architecture.md](../spec/architecture.md) §Ingest SPIs |
| Asset-snapshot refresh interval | 5 min | 5 min | 15 min | 5 min | [10-asset-commands.md](10-asset-commands.md) |
| Periodic-digest morning slot — center hour (24h, group-local) | 08:00 | 08:00 | 08:00 | 08:00 | [../spec/deployment.md](../spec/deployment.md) §Configuration surface — Groups |
| Periodic-digest evening slot — center hour (24h, group-local) | 19:00 | 19:00 | 19:00 | 19:00 | [../spec/deployment.md](../spec/deployment.md) §Configuration surface — Groups |
| Periodic-digest slot-window width | ±15 min | ±15 min | ±30 min | ±15 min | [../spec/deployment.md](../spec/deployment.md) §Configuration surface — Groups |
| `chat_memory` TTL (D40) | 30 d | 30 d | 14 d | 30 d | [../spec/llm.md](../spec/llm.md), [02-schema.md](02-schema.md) |

These are the values bound at startup unless an operator override fires. Forward references from other design files (e.g., 06-messaging.md §6.2.2 / §6.3.6) point here.

### Switching profiles

Profile is read once at startup. To switch:

1. Stop both services.
2. Edit `application.properties`: `infochat.profile=...`.
3. If embedding dimension changes (e.g., laptop→pi), run the embedding migration: `scripts/reembed.sh`.
4. Start collector, then provider.

The collector logs the active profile and any individual overrides at INFO on boot:

```
INFO  Bootstrap – profile=laptop, overrides={infochat.llm.summarizer.model: llama3.1:70b}
```

---

## 7.3 Configuration sources and precedence

Quarkus applies config in standard order; relevant for us:

1. System properties              `-Dinfochat.profile=pi`
2. Environment variables          `INFOCHAT_PROFILE=pi`
3. `application.properties`         (bundled in jar; baseline defaults)
4. `application-{profile}.properties` (bundled; profile overrides)
5. `application.properties` on disk (next to the jar; operator overrides)

Operators override on disk; no rebuild required for ops changes. Secrets always come from env vars, never from disk files in production.

---

## 7.4 Canonical `application.properties`

A single file, used by both services (each ignores keys not relevant to it). The example below is the v1 production multi-adapter shape (SimpleX + Signal in the same Provider, D46); the property keys for the messaging-adapter block are byte-equivalent to the example in [06-messaging.md §6.7](06-messaging.md). Operators running a single-adapter deployment trim the `infochat.adapters=` list and the unused per-adapter blocks.

```properties
# ── Profile ────────────────────────────────────────────────────────────
infochat.profile=laptop                          # laptop|vps|pi|remote-llm

# Java 25 / Quarkus 3.33 LTS — D1. Build artifacts target JDK 25; running
# on an older JDK fails class-loading at startup.

# ── Database ───────────────────────────────────────────────────────────
# Least-privilege role split (security.md §DB roles): the DEFAULT
# datasource connects as the per-service role, so every unqualified
# @Inject DataSource site — including future code that forgets to
# qualify — gets the weak principal (fail-closed). The username/password
# pair is SET PER-SERVICE, NOT SHARED:
#   collector: quarkus.datasource.username=infochat_collector
#              quarkus.datasource.password=${INFOCHAT_COLLECTOR_PASSWORD}
#   provider:  quarkus.datasource.username=infochat_provider
#              quarkus.datasource.password=${INFOCHAT_PROVIDER_PASSWORD}
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/infochat
# Per-service pool sizes — provider holds connections across LLM calls and
# needs more headroom; collector is mostly short writes. SET PER-SERVICE,
# NOT SHARED — see the per-service application.properties blocks below for
# where each value belongs.
#   provider:  quarkus.datasource.jdbc.max-size=30
#   collector: quarkus.datasource.jdbc.max-size=15

# Named `owner` datasource — COLLECTOR ONLY. Flyway migrations and
# partition DDL run as the schema owner; the named Flyway config below
# binds to this datasource. The production Provider declares NO owner
# datasource at all: the user-facing service never holds owner
# credentials (its test profile carries a %test-scoped owner datasource
# for the test-time Flyway and fixture seeding only).
quarkus.datasource.owner.db-kind=postgresql
quarkus.datasource.owner.username=infochat
quarkus.datasource.owner.password=${INFOCHAT_DB_PASSWORD}
quarkus.datasource.owner.jdbc.url=jdbc:postgresql://localhost:5432/infochat

quarkus.flyway.owner.migrate-at-start=true
quarkus.flyway.owner.locations=classpath:db/migration

# ── Bootstrap ──────────────────────────────────────────────────────────
infochat.bootstrap.sources-file=bootstrap-sources.json
# Optional. If unset, asset commands are disabled (§7.6.2 — file-state
# semantics — Path unset). If set, the file MUST exist and parse cleanly.
# infochat.bootstrap.assets-file=bootstrap-assets.json

# ── Messaging adapters (D46; multi-adapter, list closed at startup) ────
# v1 production shape: SimpleX + Signal in the same Provider. The list is
# byte-equivalent to 06-messaging.md §6.7. CI/tests use 'inmemory' alone
# (production deployments MUST NOT mix 'inmemory' with simplex/signal).
infochat.adapters=simplex,signal

# SimpleX
infochat.adapters.simplex.url=ws://localhost:5225
infochat.adapters.simplex.session-token=${SIMPLEX_SESSION_TOKEN}
infochat.adapters.simplex.identity-dir=/var/lib/infochat/simplex
# Optional bootstrap admin contact id for SimpleX. Per-adapter optional;
# only the union across enabled adapters MUST be non-empty (§7.6.3).
infochat.adapters.simplex.bootstrap-admin-contact-id=${INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID}
# infochat.adapters.simplex.allow-low-trust=false   # default

# Signal (provisional default wire-protocol: signal-cli JSON-RPC subprocess —
# see 06-messaging.md §6.5.1)
infochat.adapters.signal.identity-dir=/var/lib/infochat/signal-cli/data/+15551234567
infochat.adapters.signal.bootstrap-admin-contact-id=${INFOCHAT_SIGNAL_ADMIN_CONTACT_ID}
# infochat.adapters.signal.allow-low-trust=false    # default

# ── LLM (per-task; profile fills in defaults if not set) ───────────────
# infochat.llm.security.provider=ollama
# infochat.llm.security.model=llama3.2:3b
# infochat.llm.summarizer.provider=ollama
# infochat.llm.summarizer.model=llama3.1:8b
# infochat.llm.chat-agent.provider=ollama
# infochat.llm.chat-agent.model=llama3.1:8b
# infochat.embeddings.provider=ollama
# infochat.embeddings.model=nomic-embed-text

# Remote provider example (NanoGPT)
# infochat.llm.summarizer.provider=openai-compatible
# infochat.llm.summarizer.base-url=https://nano-gpt.com/api/v1
# infochat.llm.summarizer.api-key=${NANOGPT_API_KEY}
# infochat.llm.summarizer.model=llama-3.1-70b-instruct

# ── Translation ────────────────────────────────────────────────────────
# (defaults reuse llm.translator.* which falls back to summarizer)

# ── Scheduler ──────────────────────────────────────────────────────────
infochat.collector.fetch-interval=PT5M           # per-source default
infochat.collector.linking-interval=PT5M
infochat.collector.partition-prune-cron=0 30 3 * * ?
infochat.collector.ttl-prune-cron=0 0 4 * * ?
infochat.provider.digest-tick-cron=0 * * * * ?   # checks every minute for due groups

# ── Single-instance enforcement (D41; §7.8.5) ──────────────────────────
# Heartbeat tick interval written by the lock-holding instance to
# provider_state / collector_state. Profile default per §7.2.1 fills in
# when unset (laptop/vps/remote-llm: 10s, pi: 30s); explicit operator
# value always wins.
# infochat.heartbeat.interval=PT10S

# ── Groups (deployment-wide defaults; per-group overrides via /group-timezone) ─
# Default timezone assigned to a newly-created group row (spec/deployment.md
# §Configuration surface — Groups). IANA tzdb name; per-group override is the
# /group-timezone command (03-commands.md §3.10).
infochat.groups.default-timezone=UTC

# ── HTTP / observability ───────────────────────────────────────────────
# quarkus.http.port is service-specific and lives in each service's own
# application.properties (see the two blocks below). Setting it here once
# would collide between collector and provider — they cannot share a port
# on a single host.
quarkus.management.enabled=true                  # /q/health, /q/metrics
quarkus.log.level=INFO

# ── Limits ─────────────────────────────────────────────────────────────
infochat.rate.user-commands-per-min=30
infochat.rate.user-add-source-per-hour=5
infochat.rate.user-chat-per-min=60
```

### Per-service `application.properties`

Each service ships its own `application.properties` (in `infochat-collector/src/main/resources/` and `infochat-provider/src/main/resources/`) that imports the canonical settings above and adds the service-specific HTTP port. Using two separate files is the cleanest way to keep ports from colliding when both services run on the same host.

Collector (`infochat-collector/src/main/resources/application.properties`):

```properties
# Inherits keys from the canonical file above; only service-specific overrides here.
quarkus.http.port=8080
quarkus.application.name=infochat-collector
quarkus.datasource.jdbc.max-size=15
```

Provider (`infochat-provider/src/main/resources/application.properties`):

```properties
# Inherits keys from the canonical file above; only service-specific overrides here.
quarkus.http.port=8081
quarkus.application.name=infochat-provider
quarkus.datasource.jdbc.max-size=30
```

### Connection-release discipline (Provider)

The Provider's pool size is intentionally larger than the Collector's because chat-mode and `/summary` invocations call the LLM, and LLM round-trips take 5–30 s. Even at 30 connections, holding a JDBC connection across an LLM call would let ~10 concurrent chats starve every other DB consumer (including the Collector's writes).

**The Provider MUST release the JDBC connection before any LLM call.** The required pattern:

1. Open a transaction; load the context the LLM needs (chat history, scope state, candidate posts).
2. **Close the connection / commit / return it to the pool** — explicitly. Do NOT keep an `EntityManager` or `Connection` reference open across the LLM call.
3. Call the LLM (`LlmProvider.respond(...)`, `LlmProvider.classify(...)`, etc.). This step holds zero DB connections.
4. Re-open a new connection / transaction for the write side (persisting the chat reply, updating memory, audit log).

This is enforced in code by passing typed value objects between the load and call steps — never `EntityManager`, `Connection`, or attached entities. A verification test in `08-verification.md` asserts the pool gauge stays bounded under concurrent chat load (see [08-verification.md §8.4 (F18 connection-pool test)](08-verification.md)).

If the operator prefers a single shared file at deploy time, the per-service port can instead be supplied at startup via system property:

```bash
java -Dquarkus.http.port=8080 -jar infochat-collector.jar
java -Dquarkus.http.port=8081 -jar infochat-provider.jar
```

Either approach works; what is **not** allowed is setting `quarkus.http.port` twice in the same properties file — Quarkus reads the last value wins, so the collector and provider would silently end up on the same port and the second service to start would fail to bind.

Notes:

- The canonical block above is shared keys only; per-service `quarkus.http.port` lives in each service's own `application.properties` (collector=8080, provider=8081) as shown in the two blocks immediately above.
- DB credentials use service-specific roles (infochat_collector, infochat_provider). The infochat superuser is reserved for migrations and admin psql.
- All secrets read from env vars; no plaintext secrets in the file.
- The `infochat.adapters` list is **closed at startup**; adding or removing an adapter is a Provider restart ([06-messaging.md §6.7](06-messaging.md)).

---

## 7.5 Environment variables

| Variable | Required? | Read by | Purpose |
|---|---|---|---|
| `INFOCHAT_PROFILE` | optional | both | Override `infochat.profile` |
| `INFOCHAT_DB_PASSWORD` | yes | collector (owner datasource: migrations + partition DDL) | Superuser DB password |
| `INFOCHAT_COLLECTOR_PASSWORD` | yes | collector | Collector DB role password |
| `INFOCHAT_PROVIDER_PASSWORD` | yes | provider | Provider DB role password |
| `INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID` | optional per-adapter; union across enabled adapters MUST be non-empty (§7.6.3) | provider | Bootstrap bot-admin contact id on the SimpleX adapter |
| `INFOCHAT_SIGNAL_ADMIN_CONTACT_ID` | optional per-adapter; union across enabled adapters MUST be non-empty (§7.6.3) | provider | Bootstrap bot-admin contact id on the Signal adapter (Signal ACI) |
| `SIMPLEX_SESSION_TOKEN` | yes (if `simplex` is in `infochat.adapters`) | provider | SimpleX bot auth |
| `OLLAMA_URL` | optional | both | Override default `http://localhost:11434` |
| `ANTHROPIC_API_KEY` | yes (if Anthropic provider used) | both | Anthropic auth |
| `OPENAI_API_KEY` | optional | both | Used by `openai-compatible` provider when targeting OpenAI |
| `NANOGPT_API_KEY` | optional | both | Used by `openai-compatible` provider when targeting NanoGPT |

Per-adapter identity material (e.g., the `signal-cli` account directory and the SimpleX queue keypair file) lives **on disk** under `infochat.adapters.<name>.identity-dir`, not in env vars; the operator owns its lifecycle (see [06-messaging.md §6.4.1, §6.5.4](06-messaging.md)). Each adapter validates its own identity material at adapter startup and refuses to start that adapter if the directory is missing or unreadable; per-adapter resilience ([06-messaging.md §6.7](06-messaging.md)) means one adapter's identity-store failure does not abort Provider.

The Provider refuses to start if any required variable for the active configuration is missing. The error message names the missing variable. For the bootstrap-admin variables specifically: Provider counts the `bootstrap-admin-contact-id` properties across all enabled adapters; if the union is empty, startup fails with a fatal log message naming the constraint (last-admin protection only works if at least one admin row exists somewhere — [../spec/deployment.md](../spec/deployment.md) §Operator inputs).

---

## 7.6 Bootstrap files

### 7.6.1 `bootstrap-sources.json`

Path resolved relative to the working directory (or absolute via `infochat.bootstrap.sources-file`). The schema follows D38's generalized `(kind, identifier)` source identity ([../spec/deployment.md](../spec/deployment.md) §Operator inputs item 3).

Schema:

```json
[
  {
    "kind": "rss",
    "identifier": "https://www.artificialintelligence-news.com/feed/",
    "name": "AI News",
    "category": "news",
    "tags": ["AI", "Development"]
  },
  {
    "kind": "rss",
    "identifier": "https://huggingface.co/blog/feed.xml",
    "name": "Hugging Face",
    "category": "blog",
    "tags": ["AI", "Research"]
  },
  {
    "kind": "nitter",
    "identifier": "https://rss.xcancel.com/aisearchio/rss",
    "name": "AI Search",
    "category": "social",
    "tags": ["AI"]
  },
  {
    "kind": "odysee",
    "identifier": "https://www.odysee.com/$/rss/@NullSecurityX:0",
    "name": "NullSecX",
    "category": "social",
    "tags": ["Security"]
  },
  {
    "kind": "youtube",
    "identifier": "https://youtube.com/feeds/videos.xml?channel_id=UCCBVCTuk6uJrN3iFV_3vurg",
    "name": "Devoxx",
    "category": "social",
    "tags": ["Development", "Java", "Video"]
  },
  {
    "kind": "bluesky",
    "identifier": "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=langchain4j.dev",
    "name": "LangChain4j",
    "category": "social",
    "tags": ["Java", "Development", "AI"]
  },
  {
    "kind": "nostr",
    "identifier": "{\"kinds\":[1,6],\"authors\":[\"npub1...\"]}",
    "name": "Author X notes",
    "category": "social",
    "tags": ["Nostr", "Security"],
    "config": {
      "relays": [
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band"
      ]
    }
  }
]
```

Field rules:

| Field | Required | Type | Notes |
|---|---|---|---|
| `kind` | yes | enum | `rss`, `bluesky`, `nitter`, `reddit`, `youtube`, `odysee` (Fetcher-shaped); `nostr` (StreamSource-shaped). |
| `identifier` | yes | string | URL for HTTP-shaped sources; **canonicalized JSON** filter spec for `nostr` (object keys sorted lexicographically, no extra whitespace — [../spec/architecture.md](../spec/architecture.md) §Ingest SPIs). Together with `kind` it is the unique key. |
| `name` | yes | string | Display name. Fallback if feed has no title. |
| `category` | yes | enum | `news`, `blog`, `social`. Drives socials auto-tag for social. |
| `tags` | yes, ≥1 | array of strings | Tier-1 controlled vocab. Union across all entries seeds the `tag` table. |
| `config` | optional / per-kind | object or `null` | Omitted or `null` for HTTP-shaped sources. For `nostr`: the relay list and any per-source overrides. The shape is per-`kind`; new kinds add their own `config` schema in this file when they ship. |

Per-kind `config` shape:

- **`rss` / `bluesky` / `nitter` / `reddit` / `youtube` / `odysee`** — `config` MUST be omitted or `null`. Any non-null value is rejected at parse time.
- **`nostr`** — required:
  - `relays`: non-empty array of `wss://` URLs. Operator-configured relay list (D38 — no NIP-65 auto-discovery in v1).
  - Optional per-source overrides (e.g., `since`, additional kinds beyond 1/6) live here and are documented as the StreamSource implementation gains them.

Loader behavior (Collector startup, after Flyway):

1. Read the file. Validate against the schema. Any error halts startup with a clear message.
2. For each entry, upsert into `source` keyed by `(kind, identifier)`:
   - INSERT if absent.
   - UPDATE `name`, `category`, `bootstrap_tags`, `config` if they differ. Never delete; admin uses `/remove-source`.
3. Union of `tags` across all entries is upserted into `tag` with `source_origin='bootstrap'`.
4. Audit row: `BOOTSTRAP_SOURCES`, with file SHA-256 and entry count.
5. Upsert `bootstrap_meta` (single-row table; see [02-schema.md §2.8](02-schema.md)) with `last_loaded_sha256`, `last_loaded_at`, `last_entry_count`, `last_loader_version`. `audit_log` is the historical trail; `bootstrap_meta` is the cheap current-state view that `/status` (admin) exposes — operators can answer "is every instance running the same bootstrap config?" without grepping audit history, and the Provider sanity-checks at startup that the SHA matches the file it sees on disk.

`config` mutation in v1 is restart-only — there is no `/source-config` chat command and no `--config` flag on `/add-source`. Operators rotate Nostr relays by editing this file and restarting the Collector ([../spec/architecture.md](../spec/architecture.md) §Ingest SPIs — Source identity).

Editing the file and restarting updates names/categories/tags/config; sources removed from the file remain in DB until admin `/remove-source`. The `last_loaded_sha256` value provides a stable version handle for the loaded config — a deployment that intends to roll out a new bootstrap-sources.json across multiple hosts can confirm convergence by comparing this SHA across instances.

### 7.6.2 `bootstrap-assets.json` (optional)

Lists the enabled assets and per-asset enabled sub-verbs for the asset commands ([10-asset-commands.md](10-asset-commands.md), decision D39). Loaded by the Collector on startup, idempotent on `(asset)`. The set of enabled assets gates which `/zcash`, `/monero`, … commands the Provider exposes; the per-asset sub-verb allowlist gates which data sources each command will accept.

**File-state semantics** ([../spec/deployment.md](../spec/deployment.md) §Bootstrap behavior — Asset bootstrap; [../spec/SPEC.md](../spec/SPEC.md) §4). Three cases — the opt-out path is distinguished from the two opt-in-but-broken paths so an operator who configured `infochat.bootstrap.assets-file` cannot silently lose asset commands by deleting or moving the file:

- **Path unset.** `infochat.bootstrap.assets-file` is not configured. Operator opted out of asset commands. Asset commands are **disabled** for the deployment; `/help` omits them; the rest of v1 ships normally. Startup logs an info line `BootstrapLoader – assets file not configured; asset commands disabled.` **Not** a startup failure.
- **Path set, file absent.** `infochat.bootstrap.assets-file` is configured but the file is missing (typo, deleted, wrong working directory, mount not attached). Startup **fails fast** with a fatal log message identifying the configured path. Silently disabling asset commands here would mask the misconfiguration; the loader treats a configured-but-missing file as broken intent, not opt-out.
- **Path set, file present but malformed.** `infochat.bootstrap.assets-file` resolves but the file is unparseable JSON, schema-invalid, references an unknown sub-verb, has an `is_default = true` row that is also `enabled = false` (per [02-schema.md §2.7](02-schema.md) — Default-row consistency), etc. Startup **fails fast** with a fatal log message identifying the file path and the parse / validation error. Same rationale as the file-absent case: presence-with-errors is opt-in-but-broken, not opt-out.

Loader behavior (Collector startup, when configured):

1. Read the file. Validate against the schema.
2. Upsert `asset_config` rows by `(asset, sub_verb)`. Entries removed from the file in a later reload are soft-disabled (`asset_config.enabled = false`); rows are never hard-deleted, and historical `price_snapshot` data for a soft-disabled asset is preserved for audit.
3. The asset Fetchers schedule from `asset_config` rows where `enabled = true AND status = 'active'` on the per-profile asset-snapshot refresh interval (§7.2.1).

### 7.6.3 Bootstrap admin (per-adapter; optional per-adapter, union non-empty)

Each enabled adapter has its **own** bootstrap admin contact id, configured via `infochat.adapters.<name>.bootstrap-admin-contact-id` (§7.4 example). The property is **optional per adapter** — an adapter without a configured bootstrap admin still serves users on that adapter, but mints no admin row of its own at startup. The deployment-wide constraint is that **the union of bootstrap admin contacts across all enabled adapters MUST be non-empty**; Provider refuses to start otherwise (last-admin protection only works if at least one admin row exists somewhere — [../spec/deployment.md](../spec/deployment.md) §Operator inputs item 2).

Each value is parseable only by its own adapter — SimpleX contact ids are not Signal ACIs, and vice versa — so each adapter validates its own value at startup and refuses to start that adapter (per-adapter resilience, [06-messaging.md §6.7](06-messaging.md)) on a format mismatch. Provider startup fails only if every adapter with a configured bootstrap admin fails its own validation **and** the union ends up empty.

**Bootstrap-seeded admin row shape.** On startup, Provider ensures, for every enabled adapter that has a configured bootstrap admin, that the contact exists with this row shape ([../spec/deployment.md](../spec/deployment.md) §Bootstrap behavior — Bootstrap-seeded admin row shape; [02-schema.md §2.1.1](02-schema.md)):

| Column | Value |
|---|---|
| `is_admin` | `true` |
| `is_banned` | `false` |
| `probation_until` | `NULL` (bootstrap admins skip the slow-start tier) |
| `registration_state` | `'vouched'` |

The `'vouched'` choice is load-bearing: it satisfies the DM-gate check in the permission step ([04-security.md §4.5](04-security.md)) so the bootstrap admin can DM the bot without minting an invite for themselves, and avoids adding a dedicated `'bootstrap'` enum value (a load-bearing schema change with no semantic gain — the post-startup behavior is identical to a normal vouched user). The `audit_log` row written for the bootstrap records `details_json.cause = 'bootstrap'` so the original cause is greppable for audit.

Each bootstrap row is one `(adapter, contact_id)` per [02-schema.md §2.1.1](02-schema.md) (D46 keying). The same human typically maps to two distinct `users` rows when admin is configured on both adapters; they are admin on each independently per the inbound-adapter-scoped `/grant-admin` / `/revoke-admin` rule ([03-commands.md §3.10](03-commands.md), [04-security.md §4.4](04-security.md)).

**Bootstrap admin drift (rotation behavior).** Per enabled adapter: if the configured bootstrap admin contact id for that adapter does not match an existing `is_admin = true` row at `(adapter, contact_id)`, Provider **creates a new admin row** for that adapter (audit-logged) and **leaves any prior admin rows in place** with their `is_admin = true` flag intact (across this and any other adapter). After a rotation the deployment therefore has both the old and the new admin rows on the rotated adapter, both with `is_admin = true`, until the operator explicitly revokes the old one via `/revoke-admin` from the new admin's chat.

This is the safer default than auto-revoking old admins on every startup: an operator who rotates the bootstrap value for one adapter (e.g., to mitigate a suspected compromise — see [04-security.md §4.4](04-security.md) Per-adapter admin threat profile) gets a working bot on that adapter without cascading effects elsewhere; pruning stale bootstrap admins is an explicit operator action.

**Last-admin protection is global across adapters.** The trigger counts `is_admin = true` rows across the whole `users` table, not per-`(adapter)` ([02-schema.md §2.1.2](02-schema.md), D46). The prior admin row cannot be revoked until at least one other `is_admin = true` row exists anywhere on the deployment.

Per-adapter admin threat profiles (Signal SIM-swap exposure vs. SimpleX cryptographic-queue ephemerality) are documented in [04-security.md §4.4](04-security.md); operators concerned about per-adapter compromise risk should consult that section when choosing where to place admin.

---

## 7.7 Local development with `docker-compose`

A `docker-compose.yml` ships with the repo. Brings up:

- `postgres:16` with pgvector extension, init-loaded from `docker/postgres-init.sql` (creates roles, extensions, empty DB).
- `ollama/ollama:latest` with a volume for downloaded models.
- `infochat-collector` and `infochat-provider` (Quarkus dev or built jars).
- The **in-memory test adapter only** is wired for compose-time tests; a compose deployment that wants to talk to a real messaging app runs `simplex-cli` and/or `signal-cli` **out of band** (see operator note below).

```bash
# Start everything
docker compose up -d

# First-time model pull
docker compose exec ollama ollama pull llama3.1:8b
docker compose exec ollama ollama pull llama3.2:3b
docker compose exec ollama ollama pull nomic-embed-text

# Run the apps in dev mode against compose-managed Postgres + Ollama
mvn -pl infochat-collector quarkus:dev
mvn -pl infochat-provider  quarkus:dev
```

(or use the one-click wrappers in §7.7.1.)

For tests/CI, set `infochat.adapters=inmemory` to bypass SimpleX and Signal. Production deployments MUST NOT mix `inmemory` with `simplex` or `signal` ([06-messaging.md §6.6, §6.7](06-messaging.md)).

### 7.7.1 One-click scripts

A `scripts/` directory at the repo root holds thin wrappers around the raw `mvn` and `docker compose` invocations above. The point is **discoverability**: an operator (or new contributor) who knows nothing about the project can `ls scripts/` and have a working mental map within thirty seconds, without having to assemble the right command for the right phase from prose elsewhere in this document.

The committed set:

| Script | Wraps | Notes |
|---|---|---|
| `scripts/build.sh` | `mvn clean install` from the repo root | Validates that JDK 25 is on `PATH` (§7.8.1) before invoking Maven; fails fast with a friendly message naming the required JDK version if not. |
| `scripts/dev.sh` | `docker compose up -d`, then `mvn -pl infochat-collector quarkus:dev` and `mvn -pl infochat-provider quarkus:dev` in two backgrounded panes (e.g., `tmux` windows, or two `&`-backgrounded shells with PIDs printed for `down.sh` to reap). | Brings up Postgres+pgvector and Ollama, then both Quarkus services in dev mode against them. Idempotent: re-running while the compose stack is already up only restarts the services, leaving compose containers as-is. |
| `scripts/run-collector.sh` | `mvn -pl infochat-collector quarkus:dev` | Assumes the compose stack is already up; useful when iterating on the Collector alone. |
| `scripts/run-provider.sh` | `mvn -pl infochat-provider quarkus:dev` | Same shape, Provider side. |
| `scripts/down.sh` | `docker compose down`, plus killing any background `quarkus:dev` PIDs that `dev.sh` recorded. | Cleanup. |

Two operational scripts referenced elsewhere in this document belong to the same set:

| Script | Wraps | Notes |
|---|---|---|
| `scripts/reembed.sh` | The embedding migration described in §7.2.1 / §7.15. | Already named in §7.2.1 ("Switching profiles") and §7.15 ("Disaster scenarios"); listed here for completeness. |
| `scripts/backup.sh` | The cron commands in §7.10 (`pg_dump` + per-adapter identity-dir tarball). | Wraps the two-line cron pair so an operator's crontab can call a single script; raw commands stay documented in §7.10 as the wrapper's contents. |

Every script in the set obeys the same shape:

- Begins with `set -euo pipefail` for fail-fast semantics.
- Echoes the wrapped command before running it, so the operator can see exactly what is being invoked.
- Returns the wrapped command's exit code unchanged (no rewriting to `0` on success or `1` on any failure).
- Has a one-line `--help` synopsis printed when invoked with `-h` or `--help`.

The scripts themselves are not implemented in Milestone 0 — they ship at Milestone 1 alongside the modules they wrap, since wrappers around POMs that do not yet exist would be dead files. This subsection is the design commitment to the contract.

**Operator note — `simplex-cli` and `signal-cli` are out-of-band.** v1 does not ship containers for the messaging clients. The `simplex-cli` WebSocket bot client and the `signal-cli` JSON-RPC subprocess each require interactive bot-account registration (queue creation for SimpleX; phone-number/captcha for Signal — [06-messaging.md §6.5.1](06-messaging.md)) that doesn't fit cleanly into compose. Operators run them on the host (or in their own dedicated containers) and point the per-adapter `identity-dir` at the on-disk state directory each tool produces. For a SimpleX-only or Signal-only deployment, omit the other client; for the SimpleX + Signal v1 production shape, run both.

`docker/postgres-init.sql`:

Idempotent role/database/extension setup. Runs once on container init. **No literal passwords in this file** — the official `postgres` image substitutes `${VAR}` references in `/docker-entrypoint-initdb.d/*.sql` from the container's environment, and the trailing `:?` makes the substitution **fail-loud at container start** if the variable is unset (the container exits non-zero rather than silently creating a role with an empty or default password).

```sql
CREATE ROLE infochat WITH LOGIN PASSWORD '${INFOCHAT_DB_PASSWORD:?INFOCHAT_DB_PASSWORD is required}' SUPERUSER;
CREATE ROLE infochat_collector WITH LOGIN PASSWORD '${INFOCHAT_COLLECTOR_PASSWORD:?INFOCHAT_COLLECTOR_PASSWORD is required}';
CREATE ROLE infochat_provider WITH LOGIN PASSWORD '${INFOCHAT_PROVIDER_PASSWORD:?INFOCHAT_PROVIDER_PASSWORD is required}';
CREATE DATABASE infochat OWNER infochat;
\c infochat
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;     -- for gen_random_uuid()
-- Grants are applied by Flyway migration V0001__roles.sql
```

`docker-compose.yml` wires those variables to the Postgres container's environment. For local dev convenience, the compose file uses bash-style defaults that **only** apply in dev — production deployments MUST set the variables explicitly:

```yaml
environment:
  INFOCHAT_DB_PASSWORD:        ${INFOCHAT_DB_PASSWORD:-$(openssl rand -hex 24)}
  INFOCHAT_COLLECTOR_PASSWORD: ${INFOCHAT_COLLECTOR_PASSWORD:-$(openssl rand -hex 24)}
  INFOCHAT_PROVIDER_PASSWORD:  ${INFOCHAT_PROVIDER_PASSWORD:-$(openssl rand -hex 24)}
```

Result: a fresh `docker compose up` on a developer laptop generates random per-container passwords (printable in `docker compose logs postgres` once, then irretrievable), while the same compose file on a production-like host with the env vars set picks up the operator's chosen secrets. There is no `'changeme'` baked anywhere in the repo — copy-paste cannot leak a known password.

In production the init script runs once with strong passwords from env-substituted secrets (e.g., a secrets manager, sealed-secret, or `EnvironmentFile` mounted at 0600).

---

## 7.8 Production deployment

### 7.8.1 Single-host (recommended for v1)

A modest Linux box (4 vCPU, 8–16 GB RAM, 50 GB disk) runs everything. Recommended layout:

```
/opt/infochat/
  ├── current/                       # symlink to releases/<version>
  ├── releases/
  │   └── 1.0.0/
  │       ├── infochat-collector.jar
  │       ├── infochat-provider.jar
  │       ├── application.properties
  │       └── scripts/               # one-click wrappers (§7.7.1)
  ├── data/
  │   └── postgres/                  # Postgres data directory (bind mount)
  ├── models/                        # Ollama model cache (bind mount)
  ├── adapters/                      # per-adapter bot identity material (D46)
  │   ├── simplex/                   # SimpleX queue keypair + simplex-cli state
  │   └── signal-cli/                # signal-cli account directory tree
  ├── bootstrap-sources.json
  └── bootstrap-assets.json          # optional (§7.6.2)
```

Both services run as systemd units, started in dependency order:

```
postgresql.service
  → ollama.service
    → simplex-cli.service           (one per enabled messaging client; out-of-band registration)
    → signal-cli.service            (provisional default Signal wire-protocol path)
      → infochat-collector.service
      → infochat-provider.service
```

Both messaging-client units are siblings of Provider; Provider's per-adapter resilience ([06-messaging.md §6.7](06-messaging.md)) means a single client being down on boot does not abort Provider startup.

**JDK 25 prerequisite.** Install a JDK 25 distribution (Temurin, Liberica, Zulu, or Oracle's GraalVM Community 25 build) before deploying. The systemd unit's `ExecStart` invokes `/opt/infochat/jdk-25/bin/java` (or the system `/usr/bin/java` if it is a JDK 25). On a host with multiple JDKs installed, prefer an absolute path to the JDK 25 binary to avoid `update-alternatives` drift bricking the service on a routine apt upgrade.

systemd unit fragment for the provider:

```ini
[Service]
# Run as a dedicated unprivileged service account, NOT root.
User=infochat
Group=infochat

EnvironmentFile=/opt/infochat/secrets.env
WorkingDirectory=/opt/infochat/current
# Absolute path to JDK 25 — D1 requires JDK 25.
ExecStart=/opt/infochat/jdk-25/bin/java -jar infochat-provider.jar
Restart=on-failure
RestartSec=5
StartLimitBurst=10
StartLimitIntervalSec=300
# Fatal-conflict exit code from the pg_try_advisory_lock loser (§7.8.5).
# Without this, systemd would restart-loop the rejected instance against
# the running holder — the loser must stay down so the operator notices.
RestartPreventExitStatus=42

# Hardening — defence in depth on top of running as a non-root user.
NoNewPrivileges=yes              # cannot regain privileges via setuid binaries
ProtectSystem=strict             # /, /usr, /boot mounted read-only for this unit
ProtectHome=true                 # /home, /root invisible
PrivateTmp=true                  # private /tmp and /var/tmp
# ProtectSystem=strict makes the FS read-only; explicitly grant write access
# to the data dirs the service needs (logs, working dir if it writes there,
# and the per-adapter identity directories the adapters mutate at runtime).
ReadWritePaths=/opt/infochat/data /opt/infochat/adapters /var/log/infochat
```

Create the service account once: `useradd --system --home /opt/infochat --shell /usr/sbin/nologin infochat`.

`/opt/infochat/secrets.env` holds env vars (mode 0600, owned by `infochat:infochat`).

### 7.8.2 Native image (optional)

Quarkus supports GraalVM native images. We don't ship them in v1; JVM mode is fine for our footprint. Native may be revisited if we deploy to many small VPSes.

### 7.8.3 Resource sizing

| Profile | CPU | RAM | Disk | Notes |
|---|---|---|---|---|
| `laptop` (dev) | 4 vCPU | 16 GB | 30 GB | Comfortable. |
| `vps` (prod, ~50 users / a few groups) | 4 vCPU | 8 GB | 30 GB | Tight on RAM with 3B model loaded; consider 12 GB. |
| `pi` | Pi 5 (4 cores ARM) | 8 GB | 32 GB SD or SSD | SSD strongly recommended; SD wears out. |
| `remote-llm` | 1 vCPU | 1 GB | 5 GB | Minimal; LLM cost lives at the API provider. |

### 7.8.4 Collector + Provider separation

In v1 the two services are separate JVMs colocated on one host. They communicate only through Postgres (LISTEN/NOTIFY + shared schema). This means either can be restarted independently without affecting the other beyond the duration of the restart.

### 7.8.5 Single-instance enforcement (`pg_advisory_lock` + heartbeat)

[../spec/architecture.md](../spec/architecture.md) §Deployment topology commits to **exactly one Collector and exactly one Provider** per shared database (D41). The invariant is enforced via Postgres advisory locks:

- On startup, each service acquires a named `pg_advisory_lock` on `hashtext('infochat.collector')` (Collector) or `hashtext('infochat.provider')` (Provider). `hashtext` is a Postgres built-in that returns int4; computing the hash server-side guarantees two instances on different hosts always race for the same lock id with no client-side hashing routine required.
- Acquisition uses the **non-blocking** form (`pg_try_advisory_lock`). A second instance attempting to acquire the lock receives `false` and **fails fast** with a fatal log message that points at the running instance's host identifier (read from the heartbeat row, see below). The long-term shape is exit code **42** paired with a systemd unit file pinning `RestartPreventExitStatus=42` so the loser does not flap; v1 lands exit code **1** in the application code and the unit-file refinement rides with the operator-tooling ticket (the exit-code value is then a one-line swap at the call site).
- The lock is **released when the holding Postgres session ends** — on graceful shutdown (`Quarkus.asyncExit`) or hard kill (SIGKILL, OOM-killer, host crash), the backend session terminates and the server releases the lock. The `InstanceLockGuard` therefore holds a dedicated long-lived JDBC connection for the JVM lifetime; pool idle-eviction must not touch it, or the lock would silently release while the JVM is still alive.

**Heartbeat row.** Each holding instance writes a row to the `heartbeat` table — one row per service, keyed by `service` text PRIMARY KEY (values `'collector'` and `'provider'`) — every **`infochat.heartbeat.interval`** (per-profile defaults in §7.2.1). The row carries:

- `service` — text PRIMARY KEY; `'collector'` or `'provider'`.
- `host_id` — host name / container id of the lock-holding instance.
- `pid` — process id, for runbook clarity.
- `last_seen_at` — `NOW()` on each tick; doubles as the heartbeat-recency clock.

The application roles (`infochat_collector`, `infochat_provider`) hold `SELECT`/`INSERT`/`UPDATE` on `heartbeat` but **not** `DELETE` — only `infochat_admin` may delete heartbeat rows (operator path), so an application bug cannot remove the contention fingerprint. The next holder overwrites the prior fingerprint via `INSERT … ON CONFLICT (service) DO UPDATE SET host_id=…, pid=…, last_seen_at=now()`.

The `heartbeat` table is distinct from `provider_state` (the per-channel `LISTEN/NOTIFY` high-water-mark table — see [01-architecture.md §1.5](01-architecture.md)); the two share no rows or schema.

**Staleness threshold.** A holder whose `last_seen_at` is older than the per-profile staleness threshold (§7.2.1 — typically 6× the heartbeat interval) is treated as suspect by the runbook (see §7.14). The advisory lock itself is the authoritative single-instance gate; the heartbeat row is the operator-visible fingerprint that says *who* is currently the holder, so the rejected-on-acquire log message can name them ("`pg_try_advisory_lock` failed; current holder is `<host_id>` PID `<pid>`, last heartbeat `<delta>` ago").

The lock + heartbeat together turn "exactly one" from a deployment policy into an enforced invariant: a misconfigured rolling upgrade that brings up the new Collector before the old one exits cannot produce duplicate fetches; the new instance exits non-zero with a clear error and the operator's deploy script halts.

### 7.8.6 StreamSource asynchronous-startup carve-out

The default rule in [../spec/deployment.md](../spec/deployment.md) §Bootstrap behavior is "a bean failure during startup refuses the service start"; the readiness probe stays unhealthy until every required startup bean is up.

**`StreamSource` connections are an explicit exception** ([../spec/architecture.md](../spec/architecture.md) §Ingest SPIs — Asynchronous startup). Relay reachability is **not** a startup gate:

- The supervised StreamSource worker is registered at Collector boot; its reconnect loop runs in the background.
- The Collector's readiness probe goes healthy when the scheduler has accepted the StreamSource registration, **not** when every relay is connected.
- An unreachable relay surfaces as the ordinary per-relay degradation path (cooldown, throttled admin notification on the all-relays-bad transition) — gating startup on every configured relay would mean a single dead relay blocks the deployment.
- After the absolute cycle cap (per-profile, §7.2.1 — `StreamSource all-relays-bad cycle cap`) the StreamSource transitions to a terminal `failed` state; an admin notification fires and the operator must re-enable via `/source-enable <id>`.

The same shape applies to messaging-adapter startup ([06-messaging.md §6.7](06-messaging.md)): a connection failure on one adapter does not abort Provider startup, the Provider's readiness probe reports ready when **at least one** activated adapter is connected, and per-adapter connection state is exposed separately via metrics.

---

## 7.9 Bootstrap & first-run sequence

1. Install Postgres + pgvector. Create roles via `postgres-init.sql`.
2. Install Ollama (or llama.cpp). Pull required models per profile.
3. Install JDK 25. Verify `/opt/infochat/jdk-25/bin/java -version` reports `25.x`.
4. Place artifacts in `/opt/infochat/current`.
5. Edit `application.properties` + `secrets.env`. Pick the `infochat.adapters` list — `simplex,signal` for the v1 production shape, or a single adapter for a SimpleX-only or Signal-only deployment.
6. For each adapter in the list, complete out-of-band bot-account registration (SimpleX queue creation; `signal-cli register --captcha …` followed by `verify`) and place the resulting identity material under `/opt/infochat/adapters/<name>/`.
7. Place `bootstrap-sources.json` next to the jars. If asset commands are wanted, also place `bootstrap-assets.json` and set `infochat.bootstrap.assets-file` (§7.6.2 — file-state semantics).
8. Set the per-adapter bootstrap admin contact ids (`INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID`, `INFOCHAT_SIGNAL_ADMIN_CONTACT_ID`). At least one MUST be set (the union-non-empty rule, §7.6.3).
9. Start Collector via `scripts/run-collector.sh` (or directly with `mvn -pl infochat-collector quarkus:dev` in dev, or `systemctl start infochat-collector` in prod). It runs Flyway, loads bootstrap files, and idles until Provider starts. The Collector's `pg_advisory_lock` and heartbeat row are taken at this step (§7.8.5).
10. Start Provider via `scripts/run-provider.sh` (or directly with `mvn -pl infochat-provider quarkus:dev` / `systemctl start infochat-provider`). It runs Flyway again (idempotent), takes its own advisory lock, and bootstraps the per-adapter admin rows from the configured `bootstrap-admin-contact-id` properties; then it attaches each enabled messaging adapter (per-adapter resilience — one failing adapter does not block the others).
11. From any configured admin's chat client (on the adapter where they are admin), send `/help` to the bot. Verify response.
12. Add a personal source: `/add-source --kind rss --identifier ... --tags ai`.
13. Wait one fetch interval; run `/summary -w 1h`. If posts arrive, system is up.

---

## 7.10 Backups

What to back up:

- **Postgres data** — full `pg_dump -F c` daily; WAL archiving optional for PITR.
- **`application.properties` and bootstrap files** — keep in operator's config repo (separate from code repo). This includes `bootstrap-sources.json` and (if configured) `bootstrap-assets.json`.
- **Per-adapter bot identity material** — the contents of every `infochat.adapters.<name>.identity-dir` (D46): the SimpleX queue keypair file under `adapters/simplex/` and the `signal-cli` account directory tree under `adapters/signal-cli/`. **This material is unrecoverable on loss** — Signal account-recovery flows are external and SimpleX queue keypairs cannot be regenerated for the same address. Back up at least nightly, encrypted at rest.
- **Audit log** — included in DB backup.
- **Models** — not backed up; Ollama re-pulls them.

Restore:

1. Stop both services.
2. `pg_restore` the most recent backup into a fresh DB.
3. Restore each `adapters/<name>/` directory to its pre-failure state (preserve file modes — both clients are picky about world-readable keys).
4. Start Collector, then Provider.
5. Verify `/audit` shows recent events; verify a `/summary` returns content; verify each enabled adapter reaches `adapter.connection.status=1` per [06-messaging.md §6.12](06-messaging.md).

Typical RPO: 24 hours (one nightly backup). RTO: 30 minutes for a small DB.

Backup script (cron):

The recommended entry point is `scripts/backup.sh` (§7.7.1) so the operator's crontab calls one named wrapper rather than inlining `pg_dump` / `tar` invocations. The wrapper's contents are exactly the two commands shown below; the retention `find` lines are independent of the backup script and stay in the crontab directly.

```
0 3 * * * /opt/infochat/current/scripts/backup.sh
0 4 * * * find /backups -name 'infochat-*.pgc' -mtime +14 -delete
0 4 * * * find /backups -name 'adapters-*.tgz' -mtime +14 -delete
```

`scripts/backup.sh` wraps:

```
pg_dump -U infochat -F c -f /backups/infochat-$(date +%Y%m%d).pgc infochat
tar -C /opt/infochat -czf /backups/adapters-$(date +%Y%m%d).tgz adapters/
```

---

## 7.11 Upgrade procedure

1. Place new jars in `/opt/infochat/releases/<new-version>/`.
2. Diff `application.properties` against the new template; merge any new keys.
3. Stop Provider (`systemctl stop infochat-provider`). The Provider's advisory lock (§7.8.5) is released as the process exits.
4. Stop Collector. The Collector's advisory lock is released as the process exits.
5. Update the `current` symlink to the new version.
6. Start Collector. Flyway runs migrations. Watch for ERROR.
7. Start Provider.
8. Smoke check: `/help`, `/summary -w 1h`, `/status` (admin).
9. Roll back: revert symlink, restart. Schema migrations are forward-compatible — rollback within one minor version is supported by reverse migrations shipped alongside forward ones; cross-major rollbacks require restoring from backup.

A misconfigured rolling upgrade that brings up the new Provider before the old one releases the advisory lock is rejected at step 7 with the fatal-conflict log message from §7.8.5; this is by design.

---

## 7.12 Health checks and probes

Both services expose:

- `GET /q/health/live` — process is up.
- `GET /q/health/ready` — DB reachable; (Provider) **at least one enabled adapter is connected** ([06-messaging.md §6.7](06-messaging.md), [../spec/deployment.md](../spec/deployment.md) §Bootstrap behavior); (Collector) eval queue and scheduler healthy. **Does NOT probe the LLM.** This is deliberate: a slow LLM should degrade summary/chat quality, not flip the pod to NotReady and trigger an orchestrator restart loop that masks the underlying problem.
- `GET /q/health/llm` — **separate** endpoint that probes the configured chat-task LLM with a trivial prompt (e.g., "reply with the literal token `OK`") and a **5 s hard timeout**. Returns 200 on success, 503 otherwise. This endpoint is informational/observability-only and is **NOT wired to orchestrator health**: kubelet, systemd `WatchdogSec`, and load balancers MUST NOT consume it. It exists so Prometheus can blackbox-probe the LLM without that probe being on the restart path.
- `GET /q/metrics` — Micrometer/Prometheus.

**Provider readiness rule.** Ready when the Provider's DB pool is up **and at least one** activated messaging adapter is connected (`adapter.connection.status{adapter}=1` for any one of them). Per-adapter status is exposed separately so an operator can distinguish "fully healthy — every adapter up" from "degraded — one adapter down" without parsing readiness alone.

Recommended monitoring:

- **Liveness:** kill if `/live` fails 3× in 30 s (systemd `WatchdogSec`). Probe `/live` only.
- **Readiness:** alert if `/ready` returns non-200 for > 5 min. Probe `/ready` only — never `/health/llm`.
- **LLM health** (Prometheus alert; explicitly NOT an orchestrator probe):
  ```
  - alert: LlmDown
    expr: probe_success{job="infochat-llm"} == 0
    for:  5m
    annotations:
      summary: "Provider's LLM probe has been failing for 5 minutes"
  ```
  The `for: 5m` window prevents a transient slow LLM from flapping into a restart. Operators get paged; the bot stays up serving non-LLM commands and falls back to the "raw post list" form for `/summary`.
- **Per-adapter status** (Prometheus alert; degraded but not page-worthy on a multi-adapter deployment):
  ```
  - alert: AdapterDown
    expr: adapter.connection.status == 0
    for:  10m
    annotations:
      summary: "Adapter {{ $labels.adapter }} has been disconnected for 10 minutes"
  ```
  Fires per-adapter; Provider stays ready as long as one other adapter is up. On a single-adapter deployment the same condition flips readiness too — that path is covered by the readiness alert above.
- **Bootstrap-assets fatal** (Prometheus alert; operator-must-act, not a flap):
  ```
  - alert: BootstrapAssetsBroken      # E4011
    expr: increase(bootstrap_load_failures_total{file="assets"}[5m]) > 0
    for:  0m
    annotations:
      summary: "bootstrap-assets.json failed to load (configured path absent or malformed) — Provider refused startup"
  ```
  `for: 0m` is deliberate: a configured-but-broken assets file is an opt-in-but-broken signal (§7.6.2 file-state semantics) and the deployment will not come up until the operator fixes it. There is nothing to wait out. See [09-reference.md §9.2.4](09-reference.md) `E4011`.
- **Signal adapter terminal auth failure** (Prometheus alert; operator-must-act, not a flap):
  ```
  - alert: SignalAdapterAuthFailed    # E4012
    expr: adapter_state{adapter="signal", state="AUTH_FAILED"} == 1
    for:  0m
    annotations:
      summary: "Signal adapter terminal AUTH_FAILED — re-register signal-cli per §7.14"
  ```
  `for: 0m` is deliberate: `AUTH_FAILED` is a terminal state ([06-messaging.md §6.5](06-messaging.md)) reached only after `signal-cli` has been rejected enough times to give up; transient connect/reconnect blips never reach this state. The runbook is "Re-register `signal-cli`" in §7.14. See [09-reference.md §9.2.4](09-reference.md) `E4012`.
- Metrics to watch (panel suggestions):
  - `adapter.connection.status{adapter}` — should be 1 for every enabled adapter.
  - `adapter.simplex.auth.fail{adapter}` — non-zero is the cue to check for a revoked SimpleX session token (see §7.14).
  - `llm.calls.total{outcome="fail"}` rate-of-change.
  - `eval.queue.size` near `infochat.eval.queue-size` for too long → fetcher back-pressure.
  - `embedding.calls.total{outcome="fallback"}` non-zero → model down.

---

## 7.13 Logs and observability stack

### 7.13.1 Logs

Quarkus structured JSON logs (`quarkus.log.console.json=true`) recommended in production. Critical event categories:

- `AdminBootstrap` — once at startup, per enabled adapter that has a configured bootstrap admin
- `AdapterRegistry` — adapter activated, connection events, per-adapter resilience retries
- `BootstrapLoader` — sources file loaded; entry count and SHA; assets file state (loaded / not configured / fatal)
- `Stage1Sanitizer` / `Stage2Judge` — flagged spans (with redacted previews)
- `LinkingJob` / `PartitionPruner` / `TtlPruner` — scheduled jobs with row counts
- `LlmRouter` — provider chosen for each task at startup
- `RateLimiter` — overflow events with redacted contact id
- `Heartbeat` — heartbeat tick from the lock-holding instance (DEBUG); fatal-conflict log on rejected acquire (ERROR)

Log retention: 14 days local; ship to centralized log store at operator's discretion (the recommended target is Loki — see §7.13.2).

### 7.13.2 Recommended observability stack

The default v1 self-hosted observability stack is **Prometheus + Alertmanager + Grafana + Loki**. This is a **recommendation**, not a hard requirement: Quarkus emits standards-compliant Prometheus metrics (`/q/metrics`) and JSON logs that any modern observability vendor consumes, so an operator who already runs a different stack still gets a working bot. The recommendation exists so that an operator who has *not* picked a stack yet has a boring-good default to copy.

The stack is operator-deployed alongside Provider/Collector. Nothing in this subsection adds configuration to `application.properties` — the bot already exposes everything the stack needs (Prometheus scrape via the existing `quarkus.management.enabled=true`, JSON logs on stdout). All four components run on the same host as Provider/Collector on `laptop`/`vps`/`pi`/`remote-llm` profiles, and footprints below assume v1 cardinality (one Provider, one Collector, ≤ a few hundred sources).

- **Prometheus** for metrics scrape and storage. The pull model fits a single-host topology — no agent on Provider/Collector beyond the existing `/q/metrics` endpoint. Scrape interval 15 s is fine; v1 cardinality is small (per-adapter, per-source, per-task labels — no per-user labels). Footprint ~100 MB RAM. Local TSDB retention 15–30 days is plenty for a single-operator deployment; longer retention is a remote-write concern, not a v1 default.
- **Alertmanager** for alert routing, grouping, throttling, and silences. Native Prometheus pair. Routes to PagerDuty / Slack / email / webhook depending on what the operator already runs. The `LlmDown`, `AdapterDown`, `BootstrapAssetsBroken`, and `SignalAdapterAuthFailed` rules in §7.12 are the v1 starter set; group on `alertname` and route operator-must-act alerts (`for: 0m`) to the same channel an oncall person actually reads.
- **Grafana** for dashboards, ad-hoc queries, and log↔metric correlation. File-provisioned dashboards check into the operator's config repo alongside `application.properties` and `bootstrap-sources.json` (§7.10) so they version-control with the rest of the deployment. Grafana's native Postgres datasource lets operators query `audit_log` directly from the same UI as metrics — a single pane for "what alerted, what the user did, what the bot did". The dashboards themselves (suggested panels per metric) are out of scope for this file; a starter pack belongs in the operator's repo, not in the spec.
- **Loki** for log aggregation and LogQL queries. Indexes labels only (not full text), so storage cost is roughly 10× cheaper than ELK on the same volume — important on the `pi` profile, where the bot, the LLM, and Postgres already share 8 GB. LogQL syntax mirrors PromQL, lowering the cognitive cost of correlating an alert with the underlying log spans. Single binary, ~256 MB RAM. The Provider's stdout JSON stream goes in via Promtail/Vector/the operator's existing shipper; the JSON event-category names listed in §7.13.1 (`AdminBootstrap`, `AdapterRegistry`, `Stage1Sanitizer`, `Stage2Judge`, …) make natural Loki labels.

**Why not the alternatives.**

- **vs ELK.** Elasticsearch alone needs ≥ 4 GB heap. That doesn't fit on the `pi` profile (8 GB total host) or the `vps` profile (typically 8 GB) without crowding out the bot, the LLM, and Postgres. Loki indexes labels only, which is the right tradeoff for v1 log volume.
- **vs journald + manual `journalctl`.** Fine for `laptop` dev only. Does not correlate with metrics, does not survive `journalctl --vacuum-time` cleanly, does not allow alerting on log patterns (e.g., a sudden burst of `Stage2Judge` `MALWARE` verdicts). Logs that no one reads until something is on fire are not observability.
- **vs cloud-managed (Datadog / New Relic / Honeycomb).** Pulls bot logs and `audit_log` spans across a third-party trust boundary. An operator who has chosen self-hosted SimpleX/Signal probably wants self-hosted observability too; the threat profile in [04-security.md](04-security.md) §Per-adapter admin threat profile is harder to reason about once metrics and logs leave the host. Cloud-managed remains a fine **operator override** for teams whose security model already accepts the boundary; it is just not a fit as the recommended *default*.
- **vs Vector + ClickHouse / OpenObserve / SigNoz.** Newer, smaller community, less battle-tested operator runbooks. Prometheus + Loki + Grafana is the boring choice that just works and that any operator who has done observability before already knows. v1 optimizes for "an operator can stand this up in an afternoon", not for "an operator can save 20% on storage".

---

## 7.14 Operator runbook (common tasks)

**"The bot isn't responding."**

1. `systemctl status infochat-provider` — running?
2. `journalctl -u infochat-provider -n 200` — errors?
3. `curl localhost:8081/q/health/ready` — 200?
4. Per-adapter status in `/status` (admin) or via metrics: which `adapter.connection.status{adapter}` is 0? Provider readiness can be 200 while one adapter is down (the at-least-one-up rule, §7.12).
5. SimpleX side: is `simplex-cli` running? Check `adapter.simplex.auth.fail` — three consecutive auth failures terminate the SimpleX adapter (`AUTH_FAILED`); see "Rotate a SimpleX session token" below.
6. Signal side: is `signal-cli` running? Has the account directory been touched (e.g., re-registration moved files)? See "Re-register `signal-cli`" below.

**"A source is producing junk."**

```
/list-sources --all
/remove-source <id> confirm
```

**"Quarantine queue is growing."**

```
/quarantine list
/quarantine approve <id>
/quarantine reject <id>
```

For raw HTML inspection, `psql` with the admin role:

```sql
SELECT q.id, q.original_html
  FROM quarantine q
 WHERE q.id = '...'
   AND q.status = 'PENDING';
```

**"LLM is down."**

- Check `ollama list` / `ollama ps` (or remote provider health).
- Eval pipeline back-pressures; user-facing `/summary` returns the degraded "raw posts list" form.
- Admin notifications throttle to once per 15 min — check inbox if unsure.

**"Disk filling up."**

- `post` table grows linearly with feed volume × 30 days. Inspect `pg_total_relation_size('post')`.
- `audit_log` grows with admin activity. 365-day TTL handles it.
- Ollama models: 4–8 GB each. Trim unused models from `~/.ollama`.

**"Switch to remote LLM provider for a heavy summary."**

Temporary override (env var, no restart needed if Quarkus is configured for runtime config):

```
INFOCHAT_LLM_SUMMARIZER_PROVIDER=openai-compatible
INFOCHAT_LLM_SUMMARIZER_BASE_URL=https://api.openai.com/v1
INFOCHAT_LLM_SUMMARIZER_API_KEY=...
INFOCHAT_LLM_SUMMARIZER_MODEL=gpt-4o-mini
```

Permanent: edit `application.properties`, restart Provider.

**"Rotate a SimpleX session token / SimpleX bootstrap admin."**

A SimpleX queue address has no third-party recovery path; rotation is the routine mitigation for suspected exposure ([04-security.md §4.4](04-security.md)). Steps:

1. Generate a fresh queue (operator's SimpleX client) and update `INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID` (and/or the underlying property in `application.properties`).
2. Restart Provider. The new admin row is created; the old admin row is **left in place** with `is_admin = true` (bootstrap admin drift, §7.6.3). Check `audit_log` for the `BOOTSTRAP` rows confirming both.
3. From the new admin's chat session, run `/revoke-admin <old-contact-id>`. Last-admin protection (global across adapters; [02-schema.md §2.1.2](02-schema.md)) prevents the revoke if no other admin exists anywhere.
4. If `SIMPLEX_SESSION_TOKEN` was also compromised, rotate it in `secrets.env` and restart.

**"Re-register `signal-cli` (post-`AUTH_FAILED`)."**

The Signal adapter has its own auth-failure terminal state ([06-messaging.md §6.5](06-messaging.md)) reached on repeated `signal-cli` rejection (e.g., the account is no longer valid on the upstream). Recovery is operator-driven:

1. Stop the `signal-cli` daemon and re-register the account out-of-band: `signal-cli -u <number> register --captcha <token>` then `signal-cli -u <number> verify <code>`.
2. Make sure the new account directory at `infochat.adapters.signal.identity-dir` has the same on-disk shape as before; back up any state files first (§7.10).
3. Restart Provider. The Signal adapter takes its lock and re-attaches; the SimpleX adapter is unaffected (per-adapter resilience).

**"Restart a single adapter without restarting the deployment."**

v1 does not support per-adapter hot restart — the activated `infochat.adapters` list is closed at startup ([06-messaging.md §6.7](06-messaging.md)). Cycle Provider; the unaffected adapter goes briefly unavailable along with it. If hot per-adapter restart becomes a pain point, the v2 candidate is in [06-messaging.md §6.13](06-messaging.md).

**"Heartbeat row is stale or `pg_try_advisory_lock` rejected my new instance."**

See §7.8.5. Likely causes: a previous Provider crashed without releasing its advisory lock and Postgres has not yet reaped the backend session (rare; recovery is `SELECT pg_terminate_backend(pid)` against the holder PID printed in the rejection log), or the rolling deploy script raced itself.

---

## 7.15 Disaster scenarios

| Scenario | Recovery |
|---|---|
| DB corruption | Restore from `pg_dump`. Loss of up-to-24h of new posts. Saved posts and admin state preserved. |
| LLM outage > 1 day | Eval pipeline degrades; user-facing summaries become "raw post lists". Restore Ollama / switch provider; the eval queue auto-drains via outbox rehydrator. |
| One adapter wedged, others fine | Per-adapter resilience ([06-messaging.md §6.7](06-messaging.md)): Provider stays ready, remaining adapters continue serving. Diagnose the failing adapter via §7.14 (SimpleX session-token rotation, `signal-cli` re-registration); cycle Provider once the underlying client is healthy again. |
| All adapters wedged | Bot appears offline. Fix at least one adapter; on reconnect, queued outbounds (if any, in-memory only — see §7.16) flush. No DB state loss. |
| Profile mistake (e.g., switched embedding dimension) | Run `scripts/reembed.sh`. 4-day window self-heals. |
| Compromised LLM API key | Rotate the env var. Restart Provider. Add an audit row noting rotation reason. |
| Lost SimpleX bootstrap admin | Edit `application.properties` to point `infochat.adapters.simplex.bootstrap-admin-contact-id` at a different SimpleX contact. Restart Provider (bootstrap admin drift, §7.6.3). The new contact becomes admin; the old admin keeps `is_admin=true` until `/revoke-admin`. |
| Lost Signal bootstrap admin | Same shape on the Signal adapter: rotate `infochat.adapters.signal.bootstrap-admin-contact-id`, restart, `/revoke-admin` the prior. |
| Bot account compromised on one adapter | Per-adapter scope ([04-security.md §4.4](04-security.md)). Rotate the relevant session token / re-register the account; rotate the bootstrap admin per the rows above; reissue invite links to known users. Source data and the other adapter are untouched. Cross-adapter elevation is impossible by design (`/grant-admin` and `/revoke-admin` are inbound-adapter-scoped). |
| Both messaging clients lost simultaneously | Treat each adapter recovery independently; Provider stays down (zero adapters connected → readiness fails) until at least one is restored. Source data is untouched. |
| Duplicate-instance brought up by a misconfigured deploy | The new instance is rejected by `pg_try_advisory_lock` (§7.8.5) and exits non-zero; the running instance is unaffected. Fix the deploy script. |

---

## 7.16 What's intentionally NOT in v1 deployment

- **Persistent outbound queue** — the Provider's outbound message queue is in-memory only. On Provider restart, in-flight outbound messages (replies the bot had accepted but not yet handed to the messaging adapter, or that the adapter had not yet acknowledged to the messaging server) are lost. Users may need to re-issue commands whose replies were dropped. This is acceptable for v1: the inbound side is durable (commands that reached `InboundHandler.onMessage` either completed or will be re-driven by the Collector outbox), and bot output is not safety-critical. **Persistent outbound is a v2 feature**; the design is straightforward (an `outbound_message` table with `status` ∈ `{PENDING, SENT, FAILED}` drained by an adapter worker) but adds a write path on the hot reply loop that we explicitly chose to defer.
- Kubernetes manifests — `docker-compose` and systemd cover v1. K8s is operator-extra-credit.
- Auto-scaling — both services are stateless w.r.t. Postgres; horizontal scale is forbidden in v1 by D41 anyway (single-instance enforcement, §7.8.5).
- Multi-tenant deployments — one Provider serves one operator's user base. Multi-tenant is v2+ and requires schema-level tenant id.
- TLS termination inside the apps — bot ↔ messaging app handles its own encryption; ops puts a reverse proxy in front of `/q/metrics` if exposing externally.
- Centralized logging / SIEM integration — Loki is the recommended ingestion target (§7.13.2); other choices (ELK, cloud-managed, etc.) remain operator's call. The bot ships standards-compliant JSON logs on stdout — what aggregator catches them is not enforced.
- Blue-green deployment — single-host, brief downtime acceptable in v1.
- Automated DB failover — operator's call (managed Postgres or self-managed standby).
- Runtime adapter add/remove — the activated `infochat.adapters` list is closed at startup ([06-messaging.md §6.7](06-messaging.md)); changing the set is a Provider restart.
- Per-`signal-cli` containerization for `docker-compose` — the interactive registration flow makes it operator-managed in v1 (§7.7).

---

## 7.17 Pre-flight checklist for first prod deploy

- DNS / network: Provider can reach Postgres and Ollama.
- DB roles created with strong passwords; passwords in `secrets.env` (mode 0600).
- JDK 25 installed; `/opt/infochat/jdk-25/bin/java -version` confirms `25.x` (D1).
- `infochat.adapters` list set; for each enabled adapter:
  - `infochat.adapters.<name>.identity-dir` exists, is owned by the `infochat` user, and contains valid bot identity material (out-of-band registration completed for SimpleX and/or Signal).
  - `infochat.adapters.<name>.bootstrap-admin-contact-id` is set on **at least one** adapter (the union-non-empty rule, §7.6.3); each value is parseable by its own adapter.
  - For the SimpleX adapter: `SIMPLEX_SESSION_TOKEN` is set.
- `bootstrap-sources.json` validated (per-`kind` config block, especially `nostr` relays); URLs reachable from the host.
- If asset commands are wanted: `infochat.bootstrap.assets-file` set and the file parses cleanly (§7.6.2 — file-state semantics).
- Ollama models pre-pulled.
- Disk has ≥ 30 GB free, swap enabled.
- Backups scheduled (cron + `pg_dump` + per-adapter identity-dir tarball script tested on a non-prod DB first; §7.10).
- systemd units have `Restart=on-failure` and `RestartPreventExitStatus=42` (§7.8.5).
- First boot logs reviewed: profile detected, sources loaded, assets loaded (or info-line opt-out), per-adapter admin bootstrapped, every enabled adapter shows `adapter.connection.status=1` or its retry path.
- Smoke: `/help`, `/add-source`, `/summary -w 1h` all work end-to-end on each adapter where the operator is admin.
- `/q/health/ready` is 200 on Provider and Collector.

---
