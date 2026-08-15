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

The active Quarkus profile (`QUARKUS_PROFILE` / `quarkus.profile=laptop|vps|pi|remote-llm`) is the single most important config. It selects defaults for context window, models, eval concurrency, vector index, etc. See [05-llm-and-embeddings.md §5.7](05-llm-and-embeddings.md) for the canonical model/embedding table.

| Profile | Hardware | Local model? | Notes |
|---|---|---|---|
| `laptop` | 16–32 GB RAM, decent CPU/GPU, dev workstation | yes | Development default. |
| `vps` | 8–16 GB RAM, CPU only, cloud VPS | yes | Production-grade for moderate load. |
| `pi` | Raspberry Pi 5 (8 GB) | yes (1B param model) | Best-effort. Czech translation quality limited. Embedding is 768-d `nomic-embed-text` — same as every profile in v1; the per-profile `all-minilm` 384-d embedder is deferred beyond v1 ([05-llm-and-embeddings.md §5.5](05-llm-and-embeddings.md)). |
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
| `chat_memory` TTL (D40) | 90 d | 90 d | 30 d | 90 d | [../spec/llm.md](../spec/llm.md), [02-schema.md](02-schema.md) |
| `post` partition retention (`infochat.partitions.retention-days.post`) | 30 d | 30 d | 14 d | 30 d | [02-schema.md §2.4.4](02-schema.md) |

These are the values bound at startup unless an operator override fires. Forward references from other design files (e.g., 06-messaging.md §6.2.2 / §6.3.6) point here.

### Switching profiles

Profile is read once at startup. To switch:

1. Stop both services.
2. Set the active profile: `quarkus.profile=...` in `application.properties` (or `QUARKUS_PROFILE=...` in the environment).
3. Start collector, then provider.

There is **no embedding-migration step in v1**: the embedding dimension is fixed at 768-d on every profile, so switching profiles never changes it. Per-profile embedding dimensions — and the dimension-change migration that would accompany them — are deferred beyond v1; see [05-llm-and-embeddings.md §5.5](05-llm-and-embeddings.md) and [02-schema.md §2.8](02-schema.md).

The collector logs the active profile and any individual overrides at INFO on boot:

```
INFO  Bootstrap – profile=laptop, overrides={infochat.llm.summarizer.model: llama3.1:70b}
```

---

## 7.3 Configuration sources and precedence

Quarkus applies config in standard order; relevant for us:

1. System properties              `-Dquarkus.profile=pi`
2. Environment variables          `QUARKUS_PROFILE=pi`
3. `application.properties`         (bundled in jar; baseline defaults)
4. `application-{profile}.properties` (bundled; profile overrides)
5. `application.properties` on disk (next to the jar; operator overrides)

Operators override on disk; no rebuild required for ops changes. Secrets always come from env vars, never from disk files in production.

---

## 7.4 Canonical `application.properties`

A single file, used by both services (each ignores keys not relevant to it). The example below is the v1 production multi-adapter shape (SimpleX + Signal in the same Provider, D46); the property keys for the messaging-adapter block follow the same key shape as the example in [06-messaging.md §6.7](06-messaging.md). Operators running a single-adapter deployment trim the `infochat.adapters=` list and the unused per-adapter blocks.

```properties
# ── Profile ────────────────────────────────────────────────────────────
quarkus.profile=laptop                           # laptop|vps|pi|remote-llm

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
# Per-service pool sizes. SET PER-SERVICE, NOT SHARED — see the per-service
# application.properties blocks below for where each value belongs. Both are
# profile-driven, and each is derived from explicit demand arithmetic spelled
# out in that service's own application.properties (the authoritative
# derivation; reproduced here only as the shipped totals):
#   collector: max-size=24 base (laptop 20, vps 16)
#              = 1 pinned advisory-lock session + up to
#                infochat.embeddings.max-concurrency embedding-write
#                connections + 13 single-connection @Scheduled jobs
#   provider:  max-size=16 base (laptop 12, vps 16)
#              = 2 pinned sessions (advisory lock + LISTEN/NOTIFY)
#                + 3 @Scheduled jobs + concurrent inbound handling,
#                itself bounded by the per-user rate caps
# The collector's ceiling is the larger of the two: it has more scheduled
# jobs and the concurrent embedding writers. The provider's inbound demand
# is bounded rather than pool-bound precisely BECAUSE it releases its
# connection before every LLM call (see "Connection-release discipline"
# below).

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

# SimpleX — the Provider spawns simplex-chat as a subprocess and speaks its
# loopback WebSocket bot API; bot identity lives in the data-dir (no session
# token). Keys read by SimpleXConfig / ProductionAdapterBeans. The wizard
# defaults this to the operator-owned runtime dir (prod/runtime/simplex,
# resolved absolute on $PROD_DIR), not /var/lib, so 6b's own mkdir -p succeeds
# as the non-root operator; container-root still writes root-owned files there
# (pre-existing ownership wrinkle, uid-mapping deferred).
infochat.adapters.simplex.binary=/usr/local/bin/simplex-chat
infochat.adapters.simplex.data-dir=prod/runtime/simplex
infochat.adapters.simplex.ws-port=5225
# Optional bootstrap admin CLAIM-TOKEN for SimpleX (D50). SimpleX has no
# pre-seedable cryptographic sender address, so its bootstrap is a single-use
# secret: the first DM whose body equals this token becomes the bot admin
# (SimpleXAdminClaim). A stray infochat.adapters.simplex.admin is inert here —
# gate 7 counts only the token. Per-adapter optional; only the union across
# enabled adapters MUST be non-empty (§7.6.3). Unset once the first admin is
# claimed (operator hygiene — §7.6.3, security.md §Per-adapter admin threat
# profile).
infochat.adapters.simplex.admin-token=${INFOCHAT_SIMPLEX_ADMIN_TOKEN}
# infochat.adapters.simplex.allow-low-trust=false   # default

# Signal (signal-cli JSON-RPC subprocess — see 06-messaging.md §6.5.1). Keys
# read by SignalConfig / ProductionAdapterBeans; .account is the registered
# phone number, .endpoint defaults to the loopback signal-cli daemon.
infochat.adapters.signal.binary=/usr/local/bin/signal-cli
infochat.adapters.signal.data-dir=prod/runtime/signal-cli
infochat.adapters.signal.account=+15551234567
# infochat.adapters.signal.endpoint=127.0.0.1:7654  # default (loopback daemon)
infochat.adapters.signal.admin=${INFOCHAT_SIGNAL_ADMIN_CONTACT_ID}
# infochat.adapters.signal.allow-low-trust=false    # default

# ── LLM (per-task; profile fills in defaults if not set) ───────────────
# infochat.llm.security.provider=ollama
# infochat.llm.security.model=llama3.2:3b
# infochat.llm.summarizer.provider=ollama
# infochat.llm.summarizer.model=llama3.1:8b
# infochat.llm.chat.provider=ollama
# infochat.llm.chat.model=llama3.1:8b
# Embeddings have no provider-name key — one EmbeddingProvider impl per
# deployment, selected by endpoint. base-url NEVER inherits the LLM shared
# default (D54: embeddings are always local nomic-768).
# infochat.embeddings.base-url=http://localhost:11434/v1
# infochat.embeddings.model=nomic-embed-text

# Remote provider example (NanoGPT — the generic openai-compatible dialect)
# infochat.llm.summarizer.provider=openai-compatible
# infochat.llm.summarizer.base-url=https://nano-gpt.com/api/v1
# infochat.llm.summarizer.api-key=${INFOCHAT_LLM_API_KEY}
# infochat.llm.summarizer.model=llama-3.1-70b-instruct

# Remote provider example (DeepSeek — the dedicated `deepseek` dialect).
# The wizard (4-llm.sh step 4 / switch-llm.sh) writes the shared-default form:
# one endpoint + key for every task (D56), provider=deepseek so deepseek-v4-flash
# runs thinking-off (deepseek-chat is deprecated 2026-07-24). No reasoning-effort
# key — thinking stays off. Set the model on every generative task.
# infochat.llm.default.provider=deepseek
# infochat.llm.default.base-url=https://api.deepseek.com
# infochat.llm.default.api-key=${INFOCHAT_LLM_API_KEY}
# infochat.llm.security.model=deepseek-v4-flash
# infochat.llm.tagger.model=deepseek-v4-flash
# infochat.llm.entity.model=deepseek-v4-flash
# infochat.llm.classifier.model=deepseek-v4-flash
# infochat.llm.summarizer.model=deepseek-v4-flash
# infochat.llm.chat.model=deepseek-v4-flash
# infochat.llm.translator.model=deepseek-v4-flash

# ── Translation ────────────────────────────────────────────────────────
# (defaults reuse llm.translator.* which falls back to summarizer)

# ── Scheduler ──────────────────────────────────────────────────────────
# Fetch cadence is PER SOURCE KIND (architecture.md §Ingest SPIs: no
# per-source override in v1), not one global interval.
infochat.fetch.rss.interval=5m
infochat.fetch.bluesky.interval=10m
infochat.fetch.nitter.interval=10m
infochat.fetch.reddit.interval=15m
infochat.fetch.odysee.interval=30m
infochat.fetch.youtube.interval=30m
infochat.fetch.host-min-interval=20s             # per-host politeness floor
infochat.linking.interval=5m                     # LinkingJob tick (profile-driven)
infochat.partitions.check-interval=24h           # PartitionCreator: current+next month
infochat.partitions.prune-interval=24h           # PartitionPruner: aged-partition drop
# Per-table partition retention horizons in days (02-schema.md §2.4.4);
# post is profile-driven (30 laptop/vps/remote-llm, 14 pi). The pruner's
# floor guard keeps the current and next month regardless of these values.
infochat.partitions.retention-days.post=30
infochat.partitions.retention-days.post-embedding=4
infochat.partitions.retention-days.post-entity=4
infochat.partitions.retention-days.post-reference=4
infochat.partitions.retention-days.price-snapshot=7
# Partition-drop TTL is driven by infochat.partitions.prune-interval above
# (PartitionPruner); there is no separate ttl-prune cron key.
infochat.digest.tick-interval=60s                # DigestScheduler: due-group check cadence
# Digest size bound (M1-721). A digest's section count tracks the tag
# vocabulary, which grows with every source an operator adds, so this key is
# what bounds how long a digest gets. Sections are dropped off the tail
# (order is assigned-cluster count descending, so the smallest go first) and
# the Other bucket is never dropped — when the cap binds it takes the last
# slot and one more real category yields. Clusters in a dropped section are
# not redistributed; one overflow line names how many categories were
# omitted. Digest broadcast only: /summary caps no sections.
infochat.digest.max-categories=8
# Prominence weights (M1-724, D71): the within-section cluster order is a
# weighted sum of four integer-percentile terms — corroboration, reposts,
# likes, source scarcity — gated by the urgent classification, tie-broken by
# recency. The denominator is the sum of the weights of the terms PRESENT on
# a cluster (NULL social columns drop out, so an editorial cluster is not
# structurally beaten by a social one). Hand-chosen and uncalibrated: tune
# against the live corpus by reading the per-term components
# ClusterProminence returns (docs/design/03-commands.md §3.12). Retuning is
# a config edit, not a code change; no fitting, no per-deployment variation.
infochat.digest.weight.corroboration=7
infochat.digest.weight.reposts=2
infochat.digest.weight.likes=1
infochat.digest.weight.scarcity=2
# Digest lead (M1-725): a non-brief digest with at least lead-minimum
# clusters opens with a lead section — the top lead-size clusters by the
# prominence order across the whole digest, rendered with full per-cluster
# prose as its own first message (a normal digest is then two messages:
# lead, then the batched categories). Promoted clusters leave their home
# sections (section counts drop with them; a category gutted below the
# qualifying threshold folds into Other). Below lead-minimum there is no
# lead at all: a header over nearly the whole digest says nothing and
# costs an extra message. Keep lead-minimum ABOVE lead-size so a body
# remains under the lead; a misconfigured lead-minimum <= lead-size is
# clamped render-locally to leave at least one cluster in the body.
infochat.digest.lead-size=3
infochat.digest.lead-minimum=6
# Category roll-up prompt scale (M1-728): the roll-up prompt carries post
# titles only (bounded via DisplayHeadline — no bodies, no URLs), so a
# 300-cluster category is ~6K tokens instead of ~90K. The requested
# synthesis length scales with the section's cluster count:
# rollup-sentence-bands is comma-separated <ceiling>:<sentences> bands
# evaluated in order ('*' = open-ended top band) — the default asks for
# 1 sentence up to 5 clusters, 2 up to 20, 3 up to 75, 5 above; a
# multi-sentence request additionally asks for 2-4 distinct threads and
# forbids filler and any stated quantity. When the truncated titles
# still exceed rollup-prompt-char-budget, whole clusters drop from the
# END of the section order until the prompt fits, logged at INFO with
# the section tag and dropped count (a truncated LLM input is never
# silent).
infochat.digest.rollup-sentence-bands=5:1,20:2,75:3,*:5
infochat.digest.rollup-prompt-char-budget=50000

# ── Single-instance enforcement (D41; §7.8.5) ──────────────────────────
# Heartbeat tick interval written by the lock-holding instance to
# provider_state / collector_state. Profile default per §7.2.1 fills in
# when unset (laptop/vps/remote-llm: 10s, pi: 30s); explicit operator
# value always wins.
# infochat.heartbeat.interval=PT10S

# ── Groups (deployment-wide defaults; per-group overrides via /group-timezone) ─
# Default timezone assigned to a newly-created group row (spec/deployment.md
# §Configuration surface — Groups). IANA tzdb name, validated at boot;
# per-group override is the /group-timezone command (03-commands.md §3.10).
# The DDL DEFAULT 'UTC' (V5) stays the last resort for any writer that omits
# the column.
infochat.groups.default-timezone=UTC
infochat.groups.global-max-groups=10             # profile-driven (pi 5, vps 50, remote-llm 100)
infochat.groups.per-user-activation-cap=3        # profile-driven (vps 5, remote-llm 10)

# ── HTTP / observability ───────────────────────────────────────────────
# quarkus.http.port is service-specific and lives in each service's own
# application.properties (see the two blocks below). Setting it here once
# would collide between collector and provider — they cannot share a port
# on a single host.
# Opt-in canonical-composed shape (§7.12.1): a SEPARATE management interface
# for /q/health + /q/metrics. The v1 containerized wizard does NOT enable this
# — no metrics backend is wired and the app port serves only the health probes,
# so it serves health on the main loopback HTTP port instead (§7.7.2 "Runtime
# config delivery to the containers"). Enable these two only when wiring a
# metrics backend or an off-host prober.
quarkus.management.enabled=true                  # /q/health, /q/metrics
# The management interface binds 0.0.0.0 by Quarkus default — without this
# pin, enabling it above publishes health + metrics on all interfaces.
quarkus.management.host=127.0.0.1                # health/metrics reachable on loopback only
quarkus.log.level=INFO

# ── Limits ─────────────────────────────────────────────────────────────
# These are the keys that EXIST. §4.9's table is the designed rate-limit
# set and marks which of its rows are not yet implemented (notably the
# separate per-command bucket, the per-user /add-source-per-hour cap, and
# the lower-not-raise clamp); do not infer from this block that the
# designed set has shrunk.
#
# Per-user inbound transport cap — one bucket over ALL inbound, commands
# and chat alike.
infochat.rate-cap.inbound-per-minute=60
# Per-user LLM-triggering cap (chat replies + /summary + /retry re-rolls);
# profile-driven (pi 5, remote-llm 20).
infochat.chat.llm-rate-cap-per-minute=10
# Per-group backstops (D47); all three profile-driven.
infochat.ratelimit.group-reply-per-15min=10
infochat.ratelimit.group-llm-per-15min=5
infochat.ratelimit.group-commands-per-15min=20
```

### Per-service `application.properties`

Each service ships its own `application.properties` (in `infochat-collector/src/main/resources/` and `infochat-provider/src/main/resources/`) that imports the canonical settings above and adds the service-specific HTTP port. Using two separate files is the cleanest way to keep ports from colliding when both services run on the same host.

Collector (`infochat-collector/src/main/resources/application.properties`):

```properties
# Inherits keys from the canonical file above; only service-specific overrides here.
quarkus.http.port=8080
quarkus.application.name=infochat-collector
quarkus.datasource.jdbc.max-size=24
```

Provider (`infochat-provider/src/main/resources/application.properties`):

```properties
# Inherits keys from the canonical file above; only service-specific overrides here.
quarkus.http.port=8081
quarkus.application.name=infochat-provider
quarkus.datasource.jdbc.max-size=16
```

### Connection-release discipline (Provider)

The Provider's pool is deliberately modest (16) even though chat-mode and `/summary` invocations call the LLM and LLM round-trips take 5–30 s. That sizing is only sound because the connection is released before the call: holding a JDBC connection across an LLM call would let ~16 concurrent chats exhaust the pool outright and starve every other DB consumer.

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
- DB credentials use service-specific roles (infochat_collector, infochat_provider). The `infochat` owner role (CREATEROLE, not superuser — §7.7 "Database role bootstrap") is reserved for migrations; admin psql connects as the cluster bootstrap superuser.
- All secrets read from env vars; no plaintext secrets in the file.
- The `infochat.adapters` list is **closed at startup**; adding or removing an adapter is a Provider restart ([06-messaging.md §6.7](06-messaging.md)).

---

## 7.5 Environment variables

| Variable | Required? | Read by | Purpose |
|---|---|---|---|
| `QUARKUS_PROFILE` | optional | both | Selects the infochat profile (`laptop`/`vps`/`pi`/`remote-llm`). There is **no** `INFOCHAT_PROFILE` / `infochat.profile` key — the profile is Quarkus' own mechanism (`InfochatProfile`, §7.2); the wizard writes `quarkus.profile` into the runtime properties file instead of setting this var |
| `INFOCHAT_DB_PASSWORD` | yes | collector (owner datasource: migrations + partition DDL); `docker/postgres-init.sh` | Migration-owner DB-role password (`infochat`; CREATEROLE, not superuser — see §7.7 "Database role bootstrap") |
| `INFOCHAT_COLLECTOR_PASSWORD` | yes | collector; `docker/postgres-init.sh` | Collector DB role password |
| `INFOCHAT_PROVIDER_PASSWORD` | yes | provider; `docker/postgres-init.sh` | Provider DB role password |
| `INFOCHAT_LLM_API_KEY` | optional (required for a remote LLM backend) | both | The one remote-provider credential. Referenced from the runtime properties as `infochat.llm.default.api-key=${INFOCHAT_LLM_API_KEY}` (D56 shared default); written by `4-llm.sh` / `switch-llm.sh` into `secrets.env`. There are **no** per-vendor key vars (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `NANOGPT_API_KEY` are not read by anything) |
| `INFOCHAT_SIMPLEX_ADMIN_TOKEN` | optional per-adapter; union across enabled adapters MUST be non-empty (§7.6.3) | provider | Bootstrap bot-admin CLAIM-TOKEN on the SimpleX adapter (D50): a secret; first DM whose body equals it becomes admin. Unset once claimed (§7.6.3) |
| `INFOCHAT_SIGNAL_ADMIN_CONTACT_ID` | optional per-adapter; union across enabled adapters MUST be non-empty (§7.6.3) | provider | Bootstrap bot-admin contact id on the Signal adapter (Signal ACI) |
| `INFOCHAT_SIMPLEX_DATA_DIR` / `INFOCHAT_SIGNAL_DATA_DIR` | optional | `docker-compose.yml` | Host paths bind-mounted at the same in-container path for each adapter's identity data-dir; default `/var/lib/infochat/simplex` and `/var/lib/infochat/signal-cli`. The wizard points them at `prod/runtime/<adapter>` |
| `INFOCHAT_LLAMACPP_GGUF` / `INFOCHAT_LLAMACPP_EMBED_GGUF` | required for the `llamacpp` / `llamacpp-embeddings` compose profiles | `docker-compose.yml` | GGUF filenames inside the model-cache volume, passed to `llama-server` as `LLAMA_ARG_MODEL` |
| `INFOCHAT_BACKUP_DIR` | optional | `prod/scripts/backup.sh` | Backup destination when no positional argument is given; default `prod/runtime/backups` (§7.10) |

There is no `OLLAMA_URL` override: the Ollama/llama.cpp endpoint is an ordinary
config value (`infochat.llm.default.base-url`, per-task `base-url` overrides,
and `infochat.embeddings.base-url`), written into the runtime properties by the
wizard.

Per-adapter identity material (e.g., the `signal-cli` account directory and the SimpleX queue keypair file) lives **on disk** under `infochat.adapters.<name>.data-dir`, not in env vars; the operator owns its lifecycle (see [06-messaging.md §6.4.1, §6.5.4](06-messaging.md)). Each adapter validates its own identity material at adapter startup and refuses to start that adapter if the directory is missing or unreadable; per-adapter resilience ([06-messaging.md §6.7](06-messaging.md)) means one adapter's identity-store failure does not abort Provider.

The Provider refuses to start if any required variable for the active configuration is missing. The error message names the missing variable. For the bootstrap-admin variables specifically: Provider counts each adapter's bootstrap-admin **path** across all enabled adapters (`AdapterRegistry.hasBootstrapAdminPath` — for SimpleX the `infochat.adapters.simplex.admin-token`, D50; for every other adapter the `infochat.adapters.<name>.admin` address); if the union is empty, startup fails with a fatal log message naming the constraint (last-admin protection only works if at least one admin row exists somewhere — [../spec/deployment.md](../spec/deployment.md) §Operator inputs).

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
| `tags` | yes, ≥1 | array of strings | Tier-1 controlled vocab. Union across all entries seeds the `tag` table. Each tag must match `^[a-z0-9][a-z0-9-]{0,47}$` — lowercase letters, digits, and hyphens only, no spaces (a multi-word tag uses a hyphen, e.g. `glm-ai`). Values are auto-lowercased (so `"AI"` → `ai`), but spaces and other characters are **not** rewritten and fail-fast at startup. This is the source-`name` (free-form display string) vs `tags` (filter tokens) distinction — only `tags` are constrained. |
| `language` | optional | string | Declared source language, ISO 639-1 (default `en` — the V74 column default; the pre-M1-750 corpus is all English). Validated at parse time against the reviewed `SourceLanguageRegistry` constant set (initially `{en, cs}` — the set the ingest translator, M1-749, can serve); an unknown code fails boot with the supported list named. D29: the language is DECLARED by the operator, never inferred over post bodies. Matched case-insensitively and stored lower-case. On re-list the value overwrites an existing row's `source.language` (operator intent — the correction path for a misdeclared language); the Provider `/add-source --lang` path is INSERT-only by grant (V31). |
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

**Wizard default.** The `5-bootstrap.sh` wizard step ships asset commands **enabled by default**: a committed `prod/config/bootstrap-assets.json` (zcash + monero, the §10.1 v1 set) is copied into the runtime dir and `infochat.bootstrap.assets-file` is wired to that copy on a plain Enter. The operator can instead point the property at their own file, or answer "no" — which removes the property, returning the deployment to the *Path unset* state below. The wizard always wires an existing-and-valid default file, so a wizard deployment never lands in the two fail-fast states by default. The app-level file-state semantics below are unchanged — they govern whatever value the property ends up holding, regardless of how it was set (wizard, manual edit, or container env).

**File-state semantics** ([../spec/deployment.md](../spec/deployment.md) §Bootstrap behavior — Asset bootstrap; [../spec/SPEC.md](../spec/SPEC.md) §4). Three cases — the opt-out path is distinguished from the two opt-in-but-broken paths so an operator who configured `infochat.bootstrap.assets-file` cannot silently lose asset commands by deleting or moving the file:

- **Path unset.** `infochat.bootstrap.assets-file` is not configured. Operator opted out of asset commands. Asset commands are **disabled** for the deployment; `/help` omits them; the rest of v1 ships normally. Startup logs an info line `BootstrapLoader – assets file not configured; asset commands disabled.` **Not** a startup failure.
- **Path set, file absent.** `infochat.bootstrap.assets-file` is configured but the file is missing (typo, deleted, wrong working directory, mount not attached). Startup **fails fast** with a fatal log message identifying the configured path. Silently disabling asset commands here would mask the misconfiguration; the loader treats a configured-but-missing file as broken intent, not opt-out.
- **Path set, file present but malformed.** `infochat.bootstrap.assets-file` resolves but the file is unparseable JSON, schema-invalid, references an unknown sub-verb, has an `is_default = true` row that is also `enabled = false` (per [02-schema.md §2.7](02-schema.md) — Default-row consistency), etc. Startup **fails fast** with a fatal log message identifying the file path and the parse / validation error. Same rationale as the file-absent case: presence-with-errors is opt-in-but-broken, not opt-out.

Loader behavior (Collector startup, when configured):

1. Read the file. Validate against the schema.
2. Upsert `asset_config` rows by `(asset, sub_verb)`. Entries removed from the file in a later reload are soft-disabled (`asset_config.enabled = false`); rows are never hard-deleted, and historical `price_snapshot` data for a soft-disabled asset is preserved for audit.
3. The asset Fetchers schedule from `asset_config` rows where `enabled = true AND status = 'active'` on the per-profile asset-snapshot refresh interval (§7.2.1).

### 7.6.3 Bootstrap admin (per-adapter; optional per-adapter, union non-empty)

Each enabled adapter has its **own** bootstrap-admin path, but the two v1 production adapters use **different shapes** because they prove sender identity differently (decision D50):

- **Address-based adapters (Signal, in-memory):** the operator configures the admin's contact id via `infochat.adapters.<name>.admin` (§7.4 example) — a Signal ACI. On startup `AdminBootstrap` **pre-seeds** an `is_admin = true` row for that contact (the row shape below).
- **SimpleX (claim-token):** SimpleX has no pre-seedable cryptographic sender address — inbound identity is the per-connection contact id, and a sender's advertised profile address is self-asserted, not verified (out of scope of the SMP protocol) — so there is nothing to pre-seed by address, and the discarded by-address approach let any contact spoof the admin. Instead the operator configures a secret `infochat.adapters.simplex.admin-token`, and the **first DM whose body equals the token** registers that connection's contact id and flips `is_admin = true` on it (`SimpleXAdminClaim`, [06-messaging.md §6.4.4](06-messaging.md)). The token is **single-use while a SimpleX admin exists**, and `AdminBootstrap` deliberately **skips** SimpleX (a stray `infochat.adapters.simplex.admin` is ignored — the protocol-unsound by-address mapping cannot be reintroduced).

The configured value (address or token) is **optional per adapter** — an adapter without one still serves users on that adapter, but establishes no admin row of its own. The deployment-wide constraint is that **the union of bootstrap-admin paths across all enabled adapters MUST be non-empty** (`AdapterRegistry.hasBootstrapAdminPath`: SimpleX → the `admin-token`, every other adapter → the `.admin` address); Provider refuses to start otherwise (last-admin protection only works if at least one admin row exists somewhere — [../spec/deployment.md](../spec/deployment.md) §Operator inputs item 2).

**Operator hygiene — unset the SimpleX token after claim.** Single-use is gated on the live presence of a `(simplex, is_admin = true)` row, not a durable token-spent marker, so a `/revoke-admin` of the claimed SimpleX admin (possible in a multi-adapter deployment, since last-admin protection is global) would re-arm a still-configured token and let a leaked token re-claim. The v1 mitigation is operator hygiene: **unset `infochat.adapters.simplex.admin-token` once the first admin is established** ([../spec/security.md](../spec/security.md) §Per-adapter admin threat profile) — with no token configured, nothing can re-arm. Permanent single-use independent of unsetting needs a durable token-spent marker + schema migration and is tracked as a follow-up.

For **address-based** adapters, each value is parseable only by its own adapter — a Signal ACI is not an in-memory id — so each validates its own value at startup and refuses to start that adapter (per-adapter resilience, [06-messaging.md §6.7](06-messaging.md)) on a format mismatch; the adapter **canonicalizes** the value to the bare contact id inbound messages byte-match before validating and seeding it (idempotent for ACIs, which have no link form). Provider startup fails only if every adapter with a configured bootstrap admin fails its own validation **and** the union ends up empty. The SimpleX token is an opaque secret with no address format to validate or canonicalize.

**Bootstrap admin row shape.** Whether pre-seeded (address adapters, at startup) or claim-minted (SimpleX, at first-DM claim), the admin contact exists with this row shape ([../spec/deployment.md](../spec/deployment.md) §Bootstrap behavior — Bootstrap-seeded admin row shape; [02-schema.md §2.1.1](02-schema.md)):

| Column | Value |
|---|---|
| `is_admin` | `true` |
| `is_banned` | `false` |
| `probation_until` | `NULL` (bootstrap admins skip the slow-start tier) |
| `registration_state` | `'vouched'` |

The `'vouched'` choice is load-bearing: it satisfies the DM-gate check in the permission step ([04-security.md §4.5](04-security.md)) so the bootstrap admin can DM the bot without minting an invite for themselves, and avoids adding a dedicated `'bootstrap'` enum value (a load-bearing schema change with no semantic gain — the post-startup behavior is identical to a normal vouched user). The `audit_log` row written for the bootstrap records `details_json.cause = 'bootstrap'` so the original cause is greppable for audit.

Each bootstrap row is one `(adapter, contact_id)` per [02-schema.md §2.1.1](02-schema.md) (D46 keying). The same human typically maps to two distinct `users` rows when admin is configured on both adapters; they are admin on each independently per the inbound-adapter-scoped `/grant-admin` / `/revoke-admin` rule ([03-commands.md §3.10](03-commands.md), [04-security.md §4.4](04-security.md)).

**Bootstrap admin drift (rotation behavior) — address adapters.** Per enabled **address-based** adapter: if the configured bootstrap admin contact id for that adapter does not match an existing `is_admin = true` row at `(adapter, contact_id)`, Provider **creates a new admin row** for that adapter (audit-logged) and **leaves any prior admin rows in place** with their `is_admin = true` flag intact (across this and any other adapter). After a rotation the deployment therefore has both the old and the new admin rows on the rotated adapter, both with `is_admin = true`, until the operator explicitly revokes the old one via `/revoke-admin` from the new admin's chat.

This is the safer default than auto-revoking old admins on every startup: an operator who rotates the bootstrap value for one adapter (e.g., to mitigate a suspected compromise — see [04-security.md §4.4](04-security.md) Per-adapter admin threat profile) gets a working bot on that adapter without cascading effects elsewhere; pruning stale bootstrap admins is an explicit operator action.

**SimpleX has no startup drift.** The claim-token is consumed at claim time, not re-seeded on each startup, so changing `infochat.adapters.simplex.admin-token` and restarting does **not** mint a new admin — once a `(simplex, is_admin = true)` row exists the token is spent (single-use gate). To rotate the SimpleX admin, the new contact must claim a token while no SimpleX admin exists: `/revoke-admin` the current SimpleX admin (only possible while another global admin remains), set a fresh token, restart, claim, then unset the token (§7.14).

**Last-admin protection is global across adapters.** The trigger counts `is_admin = true` rows across the whole `users` table, not per-`(adapter)` ([02-schema.md §2.1.2](02-schema.md), D46). The prior admin row cannot be revoked until at least one other `is_admin = true` row exists anywhere on the deployment.

Per-adapter admin threat profiles (Signal SIM-swap exposure vs. SimpleX cryptographic-queue ephemerality) are documented in [04-security.md §4.4](04-security.md); operators concerned about per-adapter compromise risk should consult that section when choosing where to place admin.

---

## 7.7 Local and containerized stack (`docker-compose`)

A single `docker-compose.yml` ships at the repo root and serves **both** audiences through Compose **profiles** — one file, two shapes, so the Postgres / pgvector / LLM service definitions stay a single source of truth and cannot drift apart:

- **`dev` profile** (`docker compose --profile dev up -d`) — Postgres+pgvector and Ollama only. The developer runs the Quarkus services on the host via `quarkus:dev` (live reload, source on disk). The *developer inner loop*; see the `dev/scripts/` wrappers in §7.7.1. Note that a **bare** per-module `quarkus:dev` runs under `%dev`, which declares no JDBC URL and so uses a throwaway **Dev Services** database (one per module), *not* this Compose Postgres; a two-service run that shares this Postgres passes the datasource URL explicitly (the exact recipe lives in [DEVELOPER.md](../../DEVELOPER.md) §3).
- **`prod` profile** (`docker compose --profile prod up -d`) — Postgres+pgvector, the chosen LLM service(s) (Ollama, **or** llama.cpp with a separate embeddings backend per D49 — see §below), and the Collector and Provider as **built container images**. This is what the §7.7.2 wizard drives and what a public-test or simple single-host install runs.

The Postgres service carries no `profiles:` key, so it starts under both; every other service is tagged `dev` or `prod` and starts only under its profile. For tests/CI, set `infochat.adapters=inmemory` to bypass SimpleX and Signal; production deployments MUST NOT mix `inmemory` with `simplex` or `signal` ([06-messaging.md §6.6, §6.7](06-messaging.md)).

```bash
# Developer inner loop: infra in containers, apps on the host via quarkus:dev.
# Bare quarkus:dev uses a throwaway Dev Services DB per module; to share THIS
# Compose Postgres across both services, pass the datasource URL explicitly
# (see DEVELOPER.md §3 for the full two-service recipe).
docker compose --profile dev up -d
./mvnw -pl infochat-collector quarkus:dev
./mvnw -pl infochat-provider  quarkus:dev

# Full containerized stack (what the §7.7.2 wizard drives)
docker compose --profile prod up -d

# First-time Ollama model pulls (either profile)
docker compose exec ollama ollama pull llama3.1:8b
docker compose exec ollama ollama pull llama3.2:3b
docker compose exec ollama ollama pull nomic-embed-text
```

### Repo layout — operator vs developer assets

So the operator-facing entry point is not buried under developer tooling, scripts and config are split by audience:

```
docker-compose.yml        # single file, dev + prod profiles
docker/
  postgres-init.sh        # service-role password bootstrap (below)
prod/
  setup.sh                # the first-run wizard (§7.7.2) — the one command an operator runs
  scripts/                # wizard subscripts + ops scripts (apps.sh lifecycle, backup.sh; reembed.sh deferred beyond v1, §2.8)
  config/                 # COMMITTED TEMPLATES only:
                          #   application.properties.example, secrets.env.example,
                          #   bootstrap-sources.json
dev/
  scripts/                # developer inner-loop wrappers (build/dev/run-*/down) — §7.7.1
```

`prod/config/` holds only the `*.example` templates and the default `bootstrap-sources.json`. The wizard's **generated** output — a real `secrets.env`, the filled `application.properties`, the per-adapter identity directories — is written to a **git-ignored runtime directory** and never committed; a production install relocates that runtime state out of the checkout entirely (§7.8.1). Version-controlled templates and per-host secrets stay cleanly separated, so a stray `git add` cannot publish a tester's credentials.

### 7.7.1 Developer inner-loop scripts (`dev/scripts/`)

`dev/scripts/` holds thin wrappers around the raw `./mvnw` and `docker compose` invocations a **developer** uses for the `quarkus:dev` inner loop. These are **not** for operators or testers — anyone standing up a real deployment uses the §7.7.2 wizard, which runs built containers, never `quarkus:dev`. Keeping the dev wrappers under `dev/` is what stops them from being mistaken for the operator entry point.

The committed set:

| Script | Wraps | Notes |
|---|---|---|
| `dev/scripts/build.sh` | `./mvnw clean install` from the repo root | Validates that JDK 25 is on `PATH` (§7.8.1) before invoking Maven; fails fast with a friendly message naming the required JDK version if not. |
| `dev/scripts/dev.sh` | `docker compose --profile dev up -d`, then both `quarkus:dev` services in backgrounded panes (e.g. `tmux` windows, or two `&`-backgrounded shells with PIDs printed for `down.sh` to reap). | Brings up Postgres+pgvector and Ollama, then both Quarkus services in dev mode pointed at the shared Compose Postgres via explicit `-Dquarkus.datasource.jdbc.url` overrides (without them, bare `quarkus:dev` would give each service its own throwaway Dev Services DB — see DEVELOPER.md §3). Idempotent: re-running while the stack is up only restarts the services. |
| `dev/scripts/run-collector.sh` | `./mvnw -pl infochat-collector quarkus:dev` | Assumes the dev stack is already up; iterate on the Collector alone. |
| `dev/scripts/run-provider.sh` | `./mvnw -pl infochat-provider quarkus:dev` | Same shape, Provider side. |
| `dev/scripts/down.sh` | `docker compose --profile dev down`, plus killing any background `quarkus:dev` PIDs that `dev.sh` recorded. | Cleanup. Developer-only — the wizard's own reset is plain `docker compose down` (§7.7.2). |

The **operator** ops scripts live under `prod/scripts/`, not here (they are production upkeep, not the dev inner loop):

| Script | Wraps | Notes |
|---|---|---|
| `prod/scripts/apps.sh {start\|stop\|restart\|status}` | `docker compose --profile prod` `stop` / `up -d --wait` on `infochat-collector` + `infochat-provider` (passing `--env-file` the runtime `secrets.env`). | Post-setup lifecycle control for the two app services — the day-2 analogue of wizard step 7 (`7-apps.sh`); leaves Postgres + the LLM service(s) running, and `stop` preserves containers and data volumes (it is **not** a `down`). `restart` is the supported way to apply an edited mounted `application.properties` or bootstrap JSON: a bind-mounted single file is only re-read when the container restarts, so a bare `up -d` on an already-running container does not pick the change up. |
| `prod/scripts/backup.sh` | The §7.10 backup commands (`pg_dump` of the compose DB + per-adapter identity-dir tarball). | The cron entry point so an operator's crontab calls one named wrapper. |
| `prod/scripts/reembed.sh` *(deferred beyond v1)* | The embedding-dimension migration of [02-schema.md §2.8](02-schema.md). | Not shipped in v1 — the embedding dimension is fixed at 768-d on every profile, so there is no migration to run. Listed as the intended post-v1 tool. |

Every script in both sets obeys the same shape:

- Begins with `set -euo pipefail` for fail-fast semantics.
- Echoes the wrapped command before running it, so the reader can see exactly what is being invoked.
- Returns the wrapped command's exit code unchanged (no rewriting to `0` on success or `1` on any failure).
- Has a one-line `--help` synopsis printed when invoked with `-h` or `--help`.

The scripts themselves are not implemented in Milestone 0 — they ship at Milestone 1 alongside the modules and compose services they wrap. This subsection is the design commitment to the contract.

### Database role bootstrap — `docker/postgres-init.sh`

Flyway already creates the application roles and the pgvector extension: `V1__init.sql` runs `CREATE EXTENSION vector`, `V2__roles.sql` creates `infochat_collector` / `infochat_provider` / `infochat_admin`, and `V31` grants `LOGIN` to the two service roles. What Flyway **cannot** do is set the service-role *passwords* — a SQL migration cannot read the container's environment, and the passwords live in env vars (§7.5). `docker/postgres-init.sh` fills exactly that gap. It runs at container init (before the Collector's first Flyway pass), so it creates the roles **with** their passwords; Flyway's `V2` `DO`-block `IF NOT EXISTS` role guard then idempotently no-ops, and the `V4`/`V31` `ALTER ROLE … NOLOGIN`/`LOGIN` toggling leaves the password untouched.

**Owner privilege — CREATEROLE, not SUPERUSER.** The Collector holds the `infochat` owner credentials to run Flyway, so that role's privilege set is the blast radius of a credential leak. The owner is created `CREATEROLE` (not `SUPERUSER`), the **minimum** the migration set needs. The full `V1..V51` set was replayed against `pgvector/pgvector:pg16` as a `LOGIN`/`CREATEROLE`-only owner (no `SUPERUSER`, no `CREATEDB`) and applied cleanly. The determination:

- **`SUPERUSER` — not required.** No migration creates a superuser role, runs `ALTER SYSTEM`, accesses another database, or uses a superuser-only path (`COPY … FROM PROGRAM`, untrusted PL, event triggers). The schema enforces its trust split with `GRANT`/`REVOKE` and `SECURITY DEFINER` functions, not RLS, so there is no `BYPASSRLS` to preserve. Dropping `SUPERUSER` contains a leaked owner credential to this one database: no cluster takeover, no reading of other databases, no OS-level access.
- **`CREATEDB` — not required.** The database is created here by the bootstrap superuser (`CREATE DATABASE infochat OWNER infochat`); no migration issues `CREATE DATABASE`.
- **`CREATEROLE` — required.** `V2` creates `infochat_admin`, and `V4`/`V31` toggle the two service roles' `LOGIN` attribute.
- **`ADMIN OPTION` on the two service roles — required.** On PG16 a `CREATEROLE` (non-superuser) role may only `ALTER` roles it administers. Because `infochat_collector` / `infochat_provider` are created here by the **bootstrap superuser** (not by the owner), `V4`/`V31`'s `ALTER ROLE … NOLOGIN`/`LOGIN` fail with *"permission denied to alter role"* unless the script grants the owner `ADMIN OPTION` on them. The grant changes only the owner's administrative reach over those roles — it does not alter the service roles' own attributes, privileges, or passwords (so the least-privilege service-role split is preserved). `infochat_admin` needs no such grant: the owner creates it (`V2`) and so administers it automatically.
- **`CREATE EXTENSION` — not the owner's privilege.** `vector` is an untrusted extension whose install normally needs `SUPERUSER`, but it (and `pgcrypto`) are created here by the image's bootstrap superuser before Flyway runs, so `V1`'s `CREATE EXTENSION IF NOT EXISTS vector` is a no-op skip under the owner.

**Why a `.sh`, not a `.sql`** — the official `postgres` image runs `/docker-entrypoint-initdb.d/*.sql` files through `psql`, which does **not** expand shell `${VAR}` references; only `*.sh` init files are shell-evaluated. A `.sql` would therefore store the literal string `${INFOCHAT_DB_PASSWORD:?…}` as the password and complete init silently. The `.sh` pipes a here-doc to `psql`, so the **shell** performs the `${VAR:?…}` expansion: an unset *or* empty variable (the `:?` colon form fails on both) aborts the script and exits the container non-zero rather than creating an unusable role. (Verified against `pgvector/pgvector:pg16`.)

**No literal passwords in this file.**

```bash
#!/bin/bash
set -euo pipefail
# Role attributes (NOLOGIN/LOGIN) and per-table grants are managed by Flyway
# migrations V2__roles.sql / V31, run by the Collector as the owner role; this
# script only creates the owner + roles WITH their env-driven passwords. The
# owner is CREATEROLE (not SUPERUSER) — the minimum the migration set needs;
# see the privilege determination above.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE ROLE infochat WITH LOGIN CREATEROLE PASSWORD '${INFOCHAT_DB_PASSWORD:?INFOCHAT_DB_PASSWORD is required}';
CREATE ROLE infochat_collector WITH LOGIN PASSWORD '${INFOCHAT_COLLECTOR_PASSWORD:?INFOCHAT_COLLECTOR_PASSWORD is required}';
CREATE ROLE infochat_provider WITH LOGIN PASSWORD '${INFOCHAT_PROVIDER_PASSWORD:?INFOCHAT_PROVIDER_PASSWORD is required}';
-- PG16: a CREATEROLE owner may only ALTER roles it administers, so grant it
-- ADMIN OPTION on the two superuser-created service roles (else V4/V31 fail).
GRANT infochat_collector TO infochat WITH ADMIN OPTION;
GRANT infochat_provider  TO infochat WITH ADMIN OPTION;
CREATE DATABASE infochat OWNER infochat;
\c infochat
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
EOSQL
```

`docker-compose.yml` wires those variables to the Postgres container's environment as **empty pass-throughs** — Compose performs no command substitution in `${VAR:-…}` defaults (a `${VAR:-$(openssl rand -hex 24)}` default renders the *literal* `$(openssl rand -hex 24)`, not a random value), and any non-empty compose default would also keep the init script's `${VAR:?}` guard from ever firing:

```yaml
environment:
  INFOCHAT_DB_PASSWORD:        ${INFOCHAT_DB_PASSWORD:-}
  INFOCHAT_COLLECTOR_PASSWORD: ${INFOCHAT_COLLECTOR_PASSWORD:-}
  INFOCHAT_PROVIDER_PASSWORD:  ${INFOCHAT_PROVIDER_PASSWORD:-}
```

Result: `docker compose --profile prod up` with the three variables exported (the wizard's `secrets.env`, a secrets manager, or an `EnvironmentFile` mounted at 0600) creates the roles with the operator's chosen secrets; with a variable unset it resolves to empty, the init script's `${VAR:?}` aborts, and the container exits non-zero rather than starting with a guessable credential. There is no `'changeme'` or other known password baked anywhere in the repo — randomness, where wanted, is generated by the wizard's `2-secrets.sh` via `openssl rand` (a shell, where command substitution works), not by the compose file. Developers running `--profile dev up` export the three variables (or keep a local git-ignored `.env`).

**Operator note — the client binaries ship in the Provider image; only registration is out-of-band.** The containerized `prod` profile bakes both messaging-client binaries into the Provider image (`infochat-provider/src/main/docker/Dockerfile.jvm`, pinned release tags): the Provider drives `simplex-chat` and `signal-cli` as OS subprocesses inside its own container (D46), so this path needs no separate client container and no host-installed binary. What stays out-of-band differs per adapter. **SimpleX** bot identity is now *provisioned by the wizard itself*: step 7 (§7.7.2), after building the Provider image and before starting the Provider, runs the baked `simplex-chat` against the mounted `data-dir` to create the bot profile (from an operator-supplied display name), create the contact address, and enable auto-accept — so the operator types no SimpleX-internal commands. **Signal** registration (phone-number + captcha) stays interactive and out-of-band ([06-messaging.md §6.5.4](06-messaging.md) "Account registration ... is an out-of-band operator step") — the captcha cannot be one-shot scripted — and the wizard's step 6 only captures its on-disk `data-dir`. Both point the per-adapter `data-dir` at the on-disk state directory, which the Provider container bind-mounts (§7.7.2 "Runtime config delivery"). The runtime adapter still only *reads* its identity at startup and fails if absent ([06-messaging.md §6.4.1](06-messaging.md) "Bot identity"); provisioning is the separate, operator-run, pre-startup step authorized by [../spec/deployment.md](../spec/deployment.md) §Operator inputs item 7. For a SimpleX-only or Signal-only deployment, omit the other client; for the SimpleX + Signal v1 production shape, run both. (The bare-metal runtime, §7.8.1, host-installs the binaries under systemd instead — that path is unchanged.)

---

### 7.7.2 First-run setup wizard (`prod/setup.sh`)

The §7.7.1 dev wrappers assume a developer with the source tree and `quarkus:dev`. An operator or public-beta tester wants the opposite: no source build, no Maven, just a running deployment. The **first-run setup wizard** is that path — a single interactive entry point, `prod/setup.sh`, that walks an operator from a bare Linux host to a running, verified **containerized** deployment, asking one question at a time with a sensible default pre-filled for every prompt so a tester can accept the defaults by pressing Enter.

This subsection is the design commitment to the wizard's contract. Like the §7.7.1 wrappers, the wizard ships at Milestone 1, not Milestone 0 — there is nothing to stand up before the container images and compose services it drives exist.

First-phase scope:

- **Linux only.** macOS/Windows are out of scope for the first phase; `0-doctor.sh` checks the host OS and refuses elsewhere with a clear message.
- **Containerized apps.** The wizard drives the `prod` compose profile (§7.7): Collector and Provider run as built container images, so the only host prerequisites are Docker and Docker Compose v2 — no host JDK 25 (contrast §7.8.1's host-jar/systemd shape, which does require one).
- **Local LLM is operator-chosen (D49):** Ollama (the design default — [05-llm-and-embeddings.md §5.7](05-llm-and-embeddings.md) model table) **or** llama.cpp via the `openai-compatible` provider (§7.4); plus the remote-API path for the `remote-llm` profile. llama.cpp serves **one model per `llama-server` instance** — started with `LLAMA_ARG_MODEL` (the operator's GGUF) + `LLAMA_ARG_HOST=0.0.0.0` so it is reachable on the compose network as `llamacpp:8080`, plus `LLAMA_ARG_REASONING=off`: the default `--reasoning auto` detects a thinking-capable template (Gemma 4, the pinned default included) and turns thinking ON, so the per-task `max-tokens` caps — sized for *visible* output — get consumed by thought tokens, yielding empty or format-broken replies (F-live-8) — so embeddings run on a **separate** backend the operator chooses: a *second* llama.cpp instance (nomic GGUF, `LLAMA_ARG_EMBEDDINGS`, own port + healthcheck) or the Ollama nomic embedder running **alongside** the generative service (D49 relaxes "one local backend" for that co-run). Both shapes keep embeddings 768-dim nomic-class and never point at the generative GGUF. The GGUF filenames flow to Compose via `secrets.env` (`INFOCHAT_LLAMACPP_GGUF` / `INFOCHAT_LLAMACPP_EMBED_GGUF`, fed by `--env-file`); the generative model defaults to a curated checksum-pinned GGUF (operator-overridable, SHA-256 enforced), and an embeddings override must stay 768-dim.
- **Both v1 production adapters (SimpleX + Signal)** are offered. At least one MUST be configured: the in-memory adapter is test-only (§7.7), and a deployment needs a non-empty bootstrap-admin union to start (§7.6.3).

#### Relationship to §7.7.1

The wizard and the §7.7.1 wrappers serve **different audiences and runtimes** and do not overlap. The §7.7.1 wrappers drive `quarkus:dev` — the *developer* inner loop, source on disk, live reload — and are never a runtime for a tester or production host. The wizard brings up the **built container images** via the `prod` compose profile. Consequently the wizard does **not** call the §7.7.1 wrappers: it manages its own compose deployment directly (e.g. `--reset` is `docker compose down`, not `dev/scripts/down.sh`, which would additionally try to reap non-existent `quarkus:dev` PIDs).

#### Structure

The orchestrator `prod/setup.sh` drives a set of single-purpose subscripts under `prod/scripts/` (the `N-name.sh` files below), run in dependency order. It records completed steps in a **git-ignored** `.setup-state` file in the runtime directory, so a re-run resumes from the first incomplete step rather than restarting, and never regenerates a value that already exists.

The orchestrator is the **single place the step sequence is registered**: `prod/setup.sh` enumerates the full list (steps 0–8) itself, and the leaf subscripts under `prod/scripts/` never self-register. A subscript file existing on disk does not put it in the run — the orchestrator's step list does. Adding a step is therefore a two-part change (the script *and* its entry in the orchestrator's list); a change that adds only the script leaves the step orphaned, invoked by nothing.

The right-hand column maps each step to the operator inputs enumerated in [../spec/deployment.md](../spec/deployment.md) §Operator inputs — the table is the audit that the wizard covers all seven and drops none.

| Step | Subscript | Does | Operator input |
|---|---|---|---|
| 0 | `0-doctor.sh` | Preflight: Linux host; Docker daemon reachable; Compose v2; TCP ports 5432 / 8080 / 8081 (and 11434 for Ollama) free; minimum free disk; linger enabled on rootless Docker hosts (a rootful daemon survives logout, so it skips the check). Runs every check and reports all unmet ones at once, each with an actionable remedy (a check it cannot verify — e.g. the port check when `ss` is absent, or the linger check when `loginctl` is — is reported unverifiable, never silently passed). Exits non-zero iff any check failed. | — |
| 1 | `1-profile.sh` | Pick `laptop`\|`vps`\|`pi`\|`remote-llm` (§7.2). Default `laptop`. Writes `quarkus.profile`. | 1 (profile) |
| 2 | `2-secrets.sh` | `openssl rand` the three DB-role passwords; prompt for any LLM API key. Writes the runtime `secrets.env` mode 0600 (§7.3 — secrets never enter a committed file). Skips any value already present. | 5 (DB creds), 6 (API key) |
| 3 | `3-postgres.sh` | `docker compose --profile prod up -d postgres`; the service-role password bootstrap runs from `docker/postgres-init.sh` (§7.7) on first container init. | 5 (DB creds) |
| 4 | `4-llm.sh` | Branch on the choice. **Ollama:** start the ollama service and `ollama pull` the profile's chat / security / embedding models. **llama.cpp (D49):** fetch the chosen generative GGUF (curated checksum-pinned default or operator override), mint its filename plus its download URL + SHA into `secrets.env` (the URL/SHA let a host clone recover a *custom* GGUF on restore), and start the `llamacpp` service (`LLAMA_ARG_MODEL`/`LLAMA_ARG_HOST`), probing `/dev/dri` render nodes and applying `docker-compose.gpu.yml` itself into both llama.cpp bring-ups when present (override: `INFOCHAT_LLAMACPP_GPU=on|off`; the decision is printed, never prompted); then wire embeddings to the operator-chosen backend — a second `llamacpp-embeddings` instance (nomic GGUF, `--embeddings`) or the co-running Ollama nomic embedder — never the generative GGUF, dimension 768. **Remote:** collect base-URL + key only. All backends then prompt for chat/summarizer `timeout-ms` + `max-tokens` with backend/profile-sized recommended defaults (F-live-5; `--defaults` takes them unchanged). Writes `infochat.llm.*` and `infochat.embeddings.*` (§7.4). | 6 (LLM config) |
| 4b | `4b-image.sh` | Optional `/image` backend (D73/D77), off by default. **Local** (offered only on `laptop`/`remote-llm` — an AMD ROCm GPU is required; validated on Strix Halo gfx1151 alone): curated 3×2 model picker printing container-measured latencies + disk before commit, Krea decode-pipeline choice (spacepxl 2× default / krea2RealVae / stock), community-asset licence disclosure, preflight (HEAD every asset URL incl. Krea's two community VAEs, disk, memory) before any download, qwen3vl_4b encoder dedupe, write the per-model API-format template (baked steps/VAE/fit stage, one prompt placeholder, one numeric-seed KSampler — the ComfyUIClient boot contract), bring up the `docker-compose.comfyui.yml` overlay, `/system_stats` healthcheck, then probe the ETA constant (warm-up + five timed runs of the written template, unique seeds — never a table lookup). **Remote** (the only enable path on `pi`/`vps`): print the D77 firewall requirement before the URL prompt, write the template + base-url — and NO ETA constant (nothing is measured against the entered URL). Writes `infochat.image.*`; re-run offers keep\|switch\|disable. | (image config — outside the seven enumerated inputs; the feature is optional and absent by default) |
| 5 | `5-bootstrap.sh` | Seed the runtime `bootstrap-sources.json` — from the `prod/config/` template (plain Enter, never clobbering an existing runtime file) or from an operator-supplied custom path; enable asset commands by default — copy the bundled `bootstrap-assets.json` (zcash + monero) into the runtime dir and wire `infochat.bootstrap.assets-file`, unless the operator supplies a custom path or opts out (§7.6.2 — Wizard default). | 3 (sources), 4 (assets) |
| 6 | `6-adapter.sh` | For each chosen adapter capture the `binary` path + `data-dir`, and collect that adapter's bootstrap-admin credential — **SimpleX** a secret `admin-token` (read with no echo; first DM claims admin — D50), **Signal** the `admin` contact id (ACI). **SimpleX** additionally prompts for the bot **display name** (consumed by the step-7 provisioning below); **Signal** captures its `account` and stays interactive (phone+captcha out-of-band — §7.7 operator note, [06-messaging.md §6.5.1](06-messaging.md)). Enforces a non-empty admin union before proceeding (§7.6.3). Writes `infochat.adapters` and the per-adapter blocks (§7.4). | 2 (bootstrap admin), 7 (adapters) |
| 7 | `7-apps.sh` | Build both images, then — when `simplex` is enabled — provision the SimpleX bot identity via `6b-simplex-provision.sh` (below) before any app container serves traffic, then `docker compose --profile prod up -d` the Collector (which runs Flyway), wait until it is healthy, then the Provider — encoding the [../spec/deployment.md](../spec/deployment.md) §Topology startup ordering (only the Collector migrates in production). | — |
| 8 | `8-verify.sh` | Poll `/q/health` on each app's **main loopback HTTP port** (collector 8080 / provider 8081; the §7.12.1 shipped-default shape, not a management interface), reached inside the container via `docker compose exec` — the same loopback bind the Collector's own compose healthcheck uses — until ready or timeout; probe the embedding backend and scan the Provider's health body for degraded `help-corpora` entries (a dead embedder is a WARN, never a failure — the supported degraded mode); print a green/red summary naming any unhealthy component. | — |

#### Behavior contract

Every subscript obeys the §7.7.1 script shape (`set -euo pipefail`, echoes the command it runs, returns the wrapped exit code unchanged, prints a one-line `-h`/`--help` synopsis) and additionally:

- **Prefilled defaults.** Every prompt shows its default in brackets; an empty answer takes the default. A `laptop`-profile, Ollama, single-adapter setup is completable by pressing Enter at every prompt except the adapter-registration interaction that genuinely needs a human (below).
- **Idempotent / resumable.** Re-running `prod/setup.sh` reads `.setup-state` and offers to resume from the first incomplete step. No generated secret is overwritten and no DB role is re-created.
- **Non-interactive escape hatch.** `prod/setup.sh --defaults` runs end-to-end taking every default (still pausing only where adapter registration needs a human), for scripted or CI smoke use.
- **Reset.** `prod/setup.sh --reset` first *detects* what the project actually has up and tears down only that (`docker compose down` across the four backend profiles) — on a clean host it removes nothing and prints nothing — then clears `.setup-state` and **continues into the wizard**, so a reset is "clean up if needed, then set up fresh" (combine with `--defaults` to re-setup non-interactively). The default **keeps all volumes**; dropping the database is an explicit opt-in via `prod/setup.sh --reset --hard`, and even then only when the pgdata volume exists. `--hard` is scoped to the **database (pgdata) volume only**: the teardown is always plain `docker compose down` (containers + network), and `--hard` removes `<project>_infochat-pgdata` explicitly afterwards — it deliberately does **not** `down -v`, because that would also delete the LLM model caches (`infochat-llamacpp-models` and the ollama cache), forcing a multi-GB GGUF re-download. The model caches are reused across resets via the wizard's presence check (`4-llm.sh` `fetch_gguf` skips an already-present GGUF; `ollama pull` is idempotent), so a reset never re-downloads them. An operator who genuinely wants them gone passes `--wipe-models` (only valid with `--reset`): after the `down`, it `docker volume rm`s `infochat-llamacpp-models` (shared by the llama.cpp server + embeddings services, pinned name) and `<project>_infochat-ollama` — only the volumes that exist, so it stays silent when there is nothing to wipe. `--wipe-models` is **independent of `--hard`**: model caches and the database volume are deleted by separate explicit opt-ins, never one riding along with the other, because re-downloading multi-GB GGUFs is its own deliberate cost. This replaces the earlier interactive `[y/N]` data-volume confirmation with explicit flags — still the repo's confirm-before-delete posture, since losing data or models requires a deliberate `--hard` / `--wipe-models` rather than a default or a mistyped prompt answer (it never auto-removes a generated secret, data, or model volume without one).

#### What stays manual

**SimpleX** bot-identity provisioning is fully automated. After building the Provider image, `7-apps.sh` runs `prod/scripts/6b-simplex-provision.sh`, which executes the baked `simplex-chat` (via `docker compose run --rm --no-deps --entrypoint /usr/local/bin/simplex-chat infochat-provider …`) against the mounted `data-dir` at the **`<data-dir>/simplex_v1` prefix** `SimpleXSubprocess.commandFor` launches with. It issues, in order, `--create-bot-display-name <operator-name>` (profile), `-e "/ad"` (contact address), and `-e "/auto_accept on"`, then re-queries `/show_address` to surface the contact link. Each step is idempotent — a second run rotates neither profile nor address (a fresh `--create-bot-display-name` is a no-op when a profile exists; a second `/ad` reports "you already have chat address") — so the step is safe to re-run. Success/failure is decided by **parsing stdout for `bad chat command` / error markers, not the exit code** (a malformed `simplex-chat` command still exits 0); a failure aborts the wizard before the Provider starts. The provisioned contact link is shown to the operator transiently and is **never** written to `application.properties`, `secrets.env`, or any log (D37). Provisioning runs as the same uid as the Provider's own `simplex-chat` subprocess (the Provider image runs as root), so the identity DBs it writes are usable at runtime; it does not touch the runtime adapter, which still reads its identity read-only at startup and fails if absent ([../spec/deployment.md](../spec/deployment.md) §Operator inputs item 7).

What still requires a human is **Signal** account registration — the phone-number + captcha enrolment cannot be one-shot scripted (§7.7 operator note). For Signal the wizard's contribution remains to capture the on-disk `data-dir` and wire the resulting properties, not to remove the human from the loop.

#### Runtime config delivery to the containers

The wizard writes its generated config to the git-ignored runtime directory (above); the containerized Collector and Provider consume it through three seams the `prod` compose profile wires up. All three are **load-bearing — without them the prod stack does not start**: the Provider's `AdapterRegistry` gate 1 fails fast when `infochat.adapters` is unset, and `docker/postgres-init.sh`'s `${VAR:?}` guard aborts the Postgres container when the role passwords are empty.

- **`secrets.env` → compose's `--env-file`.** Each wizard subscript that runs `docker compose` passes the runtime `secrets.env` to compose's own dotenv parser — `docker compose --env-file "$SECRETS_FILE" …` — so the compose file's `${INFOCHAT_*_PASSWORD}` / `${INFOCHAT_LLM_API_KEY}` / `${INFOCHAT_*_ADMIN_CONTACT_ID}` interpolations and the Provider `environment:` passthroughs resolve (§7.5). The orchestrator does **not** `source` `secrets.env` into its own shell environment: operator-pasted values are hostile to shell evaluation — a SimpleX bootstrap-admin queue address carries `#` / `&` / `?` / `+` / `=`, an API key arbitrary bytes — so sourcing would truncate a value at its first `#` (silently corrupting the configured id) or execute an embedded `$(…)` / backtick at wizard runtime; compose's dotenv parser instead reads each quoted `KEY="value"` line as data and never evaluates it. Compose auto-loads only a repo-root `.env`, never the runtime-dir `secrets.env`, so the wizard names it explicitly on each invocation. Secrets stay in the environment, never in a mounted config file (§7.3).
- **`runtime/application.properties` → mounted at `config/application.properties` in each app container.** The fast-jar runs from the image's working directory, so Quarkus reads `config/application.properties` relative to it at a higher config ordinal than the image-baked per-service defaults and a lower one than the explicit compose `environment:` overrides (datasource URL, role password). This is how the operator's `quarkus.profile`, `infochat.llm.*`, `infochat.adapters`, and the per-adapter blocks reach the running services. The `application.properties` baked into each image carries only the profile-independent defaults — and the Provider's image declares **no production `infochat.adapters`** (only `%test`), so that key MUST arrive via this mount or the Provider refuses to boot.
- **Adapter `data-dir`s → bind-mounted into the Provider.** The out-of-band identity material the operator registered in step 6 lives on the host at each adapter's `data-dir`; the Provider container bind-mounts those paths so the running adapters can read the queue keypair / `signal-cli` account directory they validate at startup (§7.5, [06-messaging.md §6.4.1, §6.5.4](06-messaging.md)).
- **`runtime/bootstrap-{sources,assets}.json` → mounted at the fast-jar workdir.** Step 5 seeds these into the runtime dir; compose bind-mounts each at `/app/<basename>` so the loaders resolve them via the **basename** values `5-bootstrap.sh` writes for `infochat.bootstrap.{sources,assets}-file` — never a host path, which does not exist inside the container. The Collector mounts both (`BootstrapLoader` + `BootstrapAssetsLoader`); the Provider mounts only `bootstrap-assets.json` (`AssetRegistry` reads it; sources are Collector-only). The runtime assets file is always seeded even when asset commands are disabled, so the unconditional mount never materialises an empty directory; the `assets-file` property, not the file's presence, gates the feature.
- **`runtime/comfyui-workflow.json` → mounted read-only into the Provider.** Step 4b writes this per-model API-format template; the Provider's `ComfyUIClient` loads it at boot iff `infochat.image.base-url` is set, validating the one-placeholder / one-numeric-seed-KSampler contract. The mount is unconditional in `docker-compose.yml`, but the file exists only once 4b has enabled `/image` — a compose up before then auto-creates a DIRECTORY at the source, which 4b's write side rmdirs before writing the file. An unset `base-url` means the client never reads the path, so the absent file gates the feature exactly like the property (D73).

**Health surface.** The containerized v1 deployment serves `/q/health` on each service's **main HTTP port** (collector 8080 / provider 8081), bound to container loopback (`quarkus.http.host=127.0.0.1`) — the §7.12.1 *shipped per-module defaults* shape, **not** a separate management interface. In v1 the app port serves only the health probes (no other HTTP consumer) and no metrics backend is wired (§7.12.1), so the management interface the §7.4 canonical example shows (`quarkus.management.enabled=true`) buys nothing here; the wizard's generated `application.properties` leaves it unset. `8-verify.sh` therefore probes health with `docker compose exec <service> curl 127.0.0.1:<port>/q/health`, inside the container's loopback namespace — the same bind the Collector's compose healthcheck already uses. A deployment that later wires a metrics backend and an off-host prober opts into the management interface per §7.12.1; the v1 wizard does not.

#### Dependencies

The wizard is a thin layer over artifacts that must exist first. None are new spec commitments; they are the concrete pieces the wizard orchestrates:

- App container images and the `prod` compose profile carrying Collector and Provider services (§7.7).
- `docker/postgres-init.sh` setting the service-role passwords from env (§7.7).
- The default `bootstrap-sources.json` template under `prod/config/` (§7.6.1).

---

## 7.8 Production deployment

### 7.8.1 Single-host (recommended for v1)

This section describes the **bare-metal** production runtime — Collector and Provider as host JVM processes under systemd. It is the alternative to the wizard-driven **containerized** runtime (the `prod` compose profile, §7.7.2); an operator picks one. Bare-metal suits operators who do not want Docker for the application JVMs; the wizard's containerized path is the simpler default for a public-test or first install.

A modest Linux box (4 vCPU, 8–16 GB RAM, 50 GB disk) runs everything. Recommended layout:

```
/opt/infochat/
  ├── current/                       # symlink to releases/<version>
  ├── releases/
  │   └── 1.0.0/
  │       ├── infochat-collector.jar
  │       ├── infochat-provider.jar
  │       ├── application.properties
  │       └── scripts/               # ops script: backup.sh (from prod/scripts/, §7.7.1; reembed.sh deferred beyond v1)
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

The llama.cpp container caps in `docker-compose.yml` are operator-settable (M1-744) — the right values are a property of the operator's model and host, not of the code. Set the keys in the same `--env-file secrets.env` the wizard drives; a deployment that sets nothing renders the defaults, which are exactly the M1-512 starting points:

| Key | Default | Caps |
|---|---|---|
| `INFOCHAT_LLAMACPP_CPUS` | `3.0` | generative `llamacpp` CPU limit |
| `INFOCHAT_LLAMACPP_MEMORY` | `7g` | generative memory limit |
| `INFOCHAT_LLAMACPP_MEMORY_RESERVATION` | `3g` | generative memory reservation |
| `INFOCHAT_LLAMACPP_EMBED_CPUS` | `1.5` | `llamacpp-embeddings` CPU limit |
| `INFOCHAT_LLAMACPP_EMBED_MEMORY` | `2g` | embeddings memory limit |
| `INFOCHAT_LLAMACPP_EMBED_MEMORY_RESERVATION` | `512m` | embeddings memory reservation |

The postgres / collector / provider caps are deliberately NOT settable this way: their memory limits are coupled to `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=60.0`, which must stay strictly below the container limit so the JVM hits a managed heap OOM before the cgroup killer (M1-512).

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

### 7.8.7 Host resource hardening (swap, container caps, build isolation)

Root cause of the 2026-06-28 resource-exhaustion incident was operational, not a code defect: the 4 vCPU / 15 GB **zero-swap** host ran `mvn verify` (which forks a test JVM per module) and `docker` image builds at the same time the live LLM inference stack (two llama.cpp servers + the eval pipeline) and several interactive sessions were running. The RAM overshoot had nowhere to page and the host thrashed. The measures below make a repeat degrade gracefully and stay contained to one container.

**Host swap (required).** A production host MUST have swap configured so a memory overshoot degrades to paging instead of an OOM kill or a whole-host stall — the zero-swap state is what turned the incident's overshoot into a meltdown. Provision a swapfile as part of first-run bootstrap, sized relative to RAM: roughly **0.5× RAM on a RAM-rich box** (≥ 8 GB), **1× RAM on a smaller one**.

```bash
sudo fallocate -l 8G /swapfile          # ~0.5× the 15 GB reference host
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
# Persist across reboots:
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
# Prefer RAM; lean on swap only under real pressure (kernel default is 60):
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-infochat-swap.conf
sudo sysctl --system
```

Swap is a safety margin, not a runtime the app should live in: the per-container caps below keep steady-state memory well under RAM, and swap only absorbs a transient overlap.

**Per-container caps.** Every long-running prod service in `docker-compose.yml` (llamacpp, llamacpp-embeddings, collector, provider, postgres) declares a `deploy.resources` memory limit + reservation and a CPU limit; the sizing basis is commented in the Compose file. The memory limits are **blast-radius caps**: a runaway hits its own container's limit and is OOM-killed-and-restarted (`restart: unless-stopped`) rather than dragging down the host. The CPU limits **throttle** (never kill) — capping the generative llama.cpp server below the core count leaves a core for Postgres and the JVMs, directly countering the incident's all-cores-pegged inference. The two JVM services additionally pin a heap ceiling (`-XX:MaxRAMPercentage` via `JAVA_TOOL_OPTIONS`, **not** `JAVA_OPTS` — the `Dockerfile.jvm` ENTRYPOINT is a bare `java -jar` that does not expand `$JAVA_OPTS`) strictly **below** their container memory limit, so the JVM hits a managed, catchable heap limit before the cgroup OOM-killer SIGKILLs the container.

**GPU (Vulkan) overlay — opt-in.** The base `docker-compose.yml` runs the CPU build of llama.cpp and declares no `devices:` passthrough, so a host without an iGPU (the VPS scenario in `docs/spec/deployment.md`) still starts — Docker fails container creation when a `devices:` path is absent, which is why the keys cannot live in the base file unconditionally. The §7.7.2 wizard owns this application: `4-llm.sh` probes `/dev/dri` render nodes and merges the overlay into both llama.cpp bring-ups itself when present (override: `INFOCHAT_LLAMACPP_GPU=on|off`; the decision is printed, never prompted). Non-wizard flows keep the manual second `-f` file form (M1-744):

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml \
    --profile llamacpp --profile llamacpp-embeddings up -d
```

The overlay swaps only the two llama.cpp services to the Vulkan build of the same pinned release (`server-vulkan-b9776`) and passes `/dev/dri` through with `group_add` for the host's `render`/`video` GIDs (the shipped `990`/`44` are the Debian/Ubuntu values — check with `getent group render video`). A GPU-resident model still needs RAM headroom: GTT pages are pinned system memory charged to the container's cgroup, so the `deploy.resources` caps above bind a GPU model just as firmly as a CPU one — raise them via the §7.8.3 keys when the model is GPU-resident.

**Rootless-Docker prerequisite (the trap that makes GPU look broken when it is not).** Under rootless Docker, container-root maps to the host user, so the host user's `render`/`video` group membership does NOT reach into the container — those GIDs are not mapped into the user namespace, `group_add` is ineffective, the device node appears as `65534:65534`, and `llama-server --list-devices` prints an empty list with no error (measured 2026-08-01). The fix is host-side: grant the user container-root maps to an ACL on the render nodes, and persist it with a udev rule so it survives reboot:

```bash
setfacl -m u:<user>:rw /dev/dri/renderD128 /dev/dri/card1
# ROCm additionally needs the compute kernel device (ComfyUI overlay below):
setfacl -m u:<user>:rw /dev/kfd
# Persist across reboots (adjust KERNEL names to `udevadm info -a /dev/dri/renderD128`):
echo 'KERNEL=="renderD128", RUN+="/usr/bin/setfacl -m u:<user>:rw /dev/dri/renderD128"' | sudo tee /etc/udev/rules.d/99-infochat-dri.rules
echo 'KERNEL=="card1", RUN+="/usr/bin/setfacl -m u:<user>:rw /dev/dri/card1"' | sudo tee -a /etc/udev/rules.d/99-infochat-dri.rules
echo 'KERNEL=="kfd", RUN+="/usr/bin/setfacl -m u:<user>:rw /dev/kfd"' | sudo tee /etc/udev/rules.d/99-infochat-kfd.rules
sudo udevadm control --reload
```

**ComfyUI (ROCm) overlay — opt-in.** The `/image` backend (D73/D77) runs as a purpose-built ComfyUI service that exists only when `docker-compose.comfyui.yml` is applied as a second `-f` file — the same shape as the Vulkan overlay above, for the same reason: the service needs `/dev/kfd` + `/dev/dri` passed through, and a `devices:` entry in the base file would break every host without an AMD GPU.

```bash
docker compose -f docker-compose.yml -f docker-compose.comfyui.yml up -d
```

The overlay publishes NO host port (the llamacpp item-8 precedent, `docs/spec/security.md` §Trust boundaries): ComfyUI has no authentication and its API executes submitted workflow graphs, so the Provider reaches it only over the compose network as `comfyui:8188` — that is what `infochat.image.base-url` points at (D73). The two-box form (ComfyUI on a second GPU host) is an explicit operator action under D77 with the port firewalled to the single Provider host, never a default. Model assets are downloaded into the `infochat-comfyui-models` named volume by the setup wizard's image step; the image's output/ and temp/ directories are tmpfs-backed and swept by an in-image janitor after `INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES` (default 15) — the image half of the D75 backend no-retention end state, and the value the Provider-side spool sweeper must exceed. On a rootless-Docker host the ACL runbook above must be applied INCLUDING the `/dev/kfd` line before the service can see the GPU.

**No second, unused local LLM runtime.** The host systemd `ollama.service` used by the `quarkus:dev` inner loop (D49, §7.7) MUST NOT run on a box whose production deployment is the **pure-llama.cpp shape** (shape a: `--profile llamacpp --profile llamacpp-embeddings`). It is a second local LLM runtime that needlessly reserves RAM and resident model weights with no consumer — a footgun under memory pressure. Tear it down on such a host:

```bash
sudo systemctl disable --now ollama.service
```
One-line check that no enabled local runtime is unused by the active Compose profile set — on a pure-llama.cpp box the running services do not include `ollama`, so an active host `ollama.service` is the unused second runtime:

```bash
systemctl is-active --quiet ollama.service \
  && ! docker compose ps --services --filter status=running 2>/dev/null | grep -qx ollama \
  && echo "WARN: host ollama.service is up but no running Compose service uses it — disable it"
```

(The Ollama-generative and llama.cpp-plus-Ollama-embeddings shapes legitimately run an `ollama` service; the check only flags Ollama running with no matching active profile.)

**Do not build on the live prod host.** Builds (`mvn verify`, `docker` image builds) MUST NOT run on the prod host while the LLM stack is live: `mvn verify` forks a test JVM per module and an image build pegs all cores, stacking on the resident model weights with no swap to absorb the overshoot — exactly the 2026-06-28 trigger. Build elsewhere (a separate build host or CI runner) and ship only the built images / jars to the prod box. Standing up that separate build runner is an operator choice, out of scope here — this is the runbook prohibition only.

---

## 7.9 Bootstrap & first-run sequence

1. Install Postgres + pgvector. Create roles via `postgres-init.sh`.
2. Install Ollama (or llama.cpp). Pull required models per profile.
3. Install JDK 25. Verify `/opt/infochat/jdk-25/bin/java -version` reports `25.x`.
4. Place artifacts in `/opt/infochat/current`.
5. Edit `application.properties` + `secrets.env`. Pick the `infochat.adapters` list — `simplex,signal` for the v1 production shape, or a single adapter for a SimpleX-only or Signal-only deployment.
6. For each adapter in the list, complete out-of-band bot-account registration (SimpleX queue creation; `signal-cli register --captcha …` followed by `verify`) and place the resulting identity material under `/opt/infochat/adapters/<name>/`.
7. Place `bootstrap-sources.json` next to the jars. If asset commands are wanted, also place `bootstrap-assets.json` and set `infochat.bootstrap.assets-file` (§7.6.2 — file-state semantics).
8. Set the per-adapter bootstrap admin credentials — SimpleX the secret claim-token `INFOCHAT_SIMPLEX_ADMIN_TOKEN`, Signal the contact id `INFOCHAT_SIGNAL_ADMIN_CONTACT_ID`. At least one MUST be set (the union-non-empty rule, §7.6.3).
9. Start Collector via `scripts/run-collector.sh` (or directly with `./mvnw -pl infochat-collector quarkus:dev` in dev, or `systemctl start infochat-collector` in prod). It runs Flyway, loads bootstrap files, and idles until Provider starts. The Collector's `pg_advisory_lock` and heartbeat row are taken at this step (§7.8.5).
10. Start Provider via `scripts/run-provider.sh` (or directly with `./mvnw -pl infochat-provider quarkus:dev` / `systemctl start infochat-provider`). It runs Flyway again (idempotent), takes its own advisory lock, and bootstraps the per-adapter admin rows from the configured `infochat.adapters.<name>.admin` properties; then it attaches each enabled messaging adapter (per-adapter resilience — one failing adapter does not block the others).
11. From any configured admin's chat client (on the adapter where they are admin), send `/help` to the bot. Verify response.
12. Add a personal source: `/add-source --kind rss --identifier ... --tags ai`.
13. Wait one fetch interval; run `/summary -w 1h`. If posts arrive, system is up.

---

## 7.10 Backups

What to back up:

- **Postgres data** — full `pg_dump -F c` daily; WAL archiving optional for PITR.
- **`application.properties` and bootstrap files** — keep in operator's config repo (separate from code repo). This includes `bootstrap-sources.json` and (if configured) `bootstrap-assets.json`.
- **Per-adapter bot identity material** — the contents of every `infochat.adapters.<name>.data-dir` (D46): the SimpleX queue keypair file under `adapters/simplex/` and the `signal-cli` account directory tree under `adapters/signal-cli/`. **This material is unrecoverable on loss** — Signal account-recovery flows are external and SimpleX queue keypairs cannot be regenerated for the same address. Back up at least nightly, encrypted at rest.
- **Audit log** — included in DB backup.
- **Models** — not backed up; Ollama re-pulls them.

Restore:

1. Stop both services.
2. `pg_restore` the most recent backup into a fresh DB.
3. Restore each `adapters/<name>/` directory to its pre-failure state (preserve file modes — both clients are picky about world-readable keys). When restoring from the `adapters-*.tgz` tarball with `tar -C / -xzpf …`, name ONLY the configured adapter data-dir paths as extraction targets so a tampered archive's out-of-allowlist members (e.g. system paths) are ignored rather than written under `/`; `restore.sh` does this automatically (§7.10.1).
4. Start Collector, then Provider.
5. Verify `/audit` shows recent events; verify a `/summary` returns content; verify each enabled adapter reaches `adapter.connection.status=1` per [06-messaging.md §6.12](06-messaging.md).

Typical RPO: 24 hours (one nightly backup). RTO: 30 minutes for a small DB.

Backup script (cron):

The recommended entry point is `prod/scripts/backup.sh` (§7.7.1) so the operator's crontab calls one named wrapper rather than inlining `pg_dump` / `tar` invocations. The script targets the shipped docker-compose deployment; the retention `find` lines are independent of it and stay in the crontab directly.

```
# Run by absolute path — backup.sh locates docker-compose.yml and
# prod/runtime/secrets.env relative to its own location, so cwd does not matter.
# With no argument it writes into its default dir, prod/runtime/backups (here
# /srv/infochat/prod/runtime/backups); the retention find lines below prune that
# same dir. Pass an explicit dir (or set $INFOCHAT_BACKUP_DIR) to write off-host
# — recommended for real disaster recovery, since the default sits on the same
# disk as the data it backs up.
0 3 * * * /srv/infochat/prod/scripts/backup.sh
0 4 * * * find /srv/infochat/prod/runtime/backups -name 'infochat-*.pgc' -mtime +14 -delete
0 4 * * * find /srv/infochat/prod/runtime/backups -name 'adapters-*.tgz' -mtime +14 -delete
```

`prod/scripts/backup.sh` writes two date-stamped artifacts into the backup directory (default `prod/runtime/backups` — gitignored and writable by the runtime user, so the no-arg invocation works with zero operator config; override with a positional argument or `$INFOCHAT_BACKUP_DIR`):

- **`infochat-YYYYMMDD.pgc`** — `pg_dump -F c infochat` run *inside* the `postgres` compose service (`docker compose exec`), so it needs no host-side Postgres client and reads the `infochat` owner password from the container environment, never the host.
- **`adapters-YYYYMMDD.tgz`** — a tar of every *configured* adapter identity data-dir (`INFOCHAT_SIMPLEX_DATA_DIR` / `INFOCHAT_SIGNAL_DATA_DIR`, read from `prod/runtime/secrets.env`), stored relative to `/` with modes preserved so the restore step above reconstructs each dir in place. An enabled adapter whose data-dir is missing fails the run loudly rather than producing an empty archive.

The artifact names are exactly what the retention `find` patterns above match.

### 7.10.1 Migrating to another device (host clone)

`backup.sh` + the manual restore steps above are the *upkeep* path (cron dumps,
in-place recovery). Moving a running deployment to **another machine as an exact
clone** is a distinct task with sharp edges the manual sequence hits blind:
config + secrets live outside the backup, the DB must be restored *before*
Flyway, identities are stored at absolute paths, and models must be re-pulled.
`prod/scripts/pack.sh` + `prod/scripts/restore.sh` are the supported turnkey pair
for it; the manual steps 1-5 above remain the under-the-hood description of what
`restore.sh` automates.

- **`pack.sh [OUT_DIR]`** — READ-ONLY on the source, but **stop-first is the
  recommended order** (`apps.sh stop`; Postgres stays up — `pg_dump` needs it and
  the dump is MVCC-consistent either way): a LIVE pack can tar the
  SimpleX/signal-cli identity stores mid-write, yielding a spurious tar failure
  ("file changed as we read it") or a torn SQLite/ratchet snapshot discovered only
  after cutover, on the unrecoverable identity. `pack.sh` prints a loud WARN when
  the Provider is running — it never refuses (a live pack stays legitimate for
  periodic precaution bundles). Bundles everything needed to
  reconstruct the deployment into ONE archive (`infochat-migration-YYYYMMDD.tgz`,
  default `prod/runtime/migration`): the `infochat` DB (`pg_dump -F c`, audit log
  included), every configured adapter identity data-dir (modes preserved),
  `application.properties`, `secrets.env`, and the bootstrap files. This is a
  **superset** of `backup.sh` — it adds the config + secrets `backup.sh`
  deliberately excludes, because a clone needs the DB passwords and admin/LLM
  config. The archive is the single highest-value artifact the system emits
  (every secret at once); it is written `0600` and `pack.sh` warns loudly —
  encryption for transfer and storage stays the operator's responsibility (D34).
- **`restore.sh <bundle>`** — run on the FRESH target (Docker + a clean checkout
  at the SAME absolute repo path). It fails loud and early at each precondition —
  missing/corrupt bundle, an already-configured target, a pre-existing Postgres
  data volume, or an identity path that does not match the bundle — rather than
  half-restoring. It places config/secrets and reconstructs each adapter identity
  dir by extracting ONLY the configured (allowlisted) data-dir paths from the
  bundle's identity tar — so a tampered bundle carrying extra members that name
  system paths is ignored, never written under the privileged `tar -C /` — then
  brings Postgres up ALONE
  and `pg_restore`s into the fresh DB **before** the Collector's first Flyway pass
  (a Flyway-migrated empty DB would collide with the dump's schema), re-provisions
  models from the restored backend config (idempotent `ollama pull` / GGUF fetch;
  a pinned-default GGUF is re-fetched from its known URL and a custom GGUF from the
  URL + SHA `4-llm.sh` persisted into `secrets.env` at setup — only a custom
  GGUF from an OLDER bundle (one with no persisted URL) fails loud; a remote
  backend needs no model step), then
  starts the Collector and **gates the Provider start on single-owner consent**:
  the Provider is the messaging-identity consumer, so before
  `compose up -d infochat-provider` the script prints the single-owner invariant
  and requires either an interactive y/N confirmation (default No, TTY-checked —
  the `shred-bundle.sh` consent shape) or the explicit `--source-stopped` flag.
  Declining — or a non-TTY run without the flag — stops after the Collector with
  instructions to start the Provider manually once the source host is stopped;
  unattended/scripted runs (e.g. a recovery round-trip re-run) must pass
  `--source-stopped` to reach the Provider start. With consent it starts the
  Provider and runs the §7.10 step-5 health verification.
- **`shred-bundle.sh [-y|--yes] <target>`** — the closing step of the migration
  lifecycle: pack → transfer → restore → verify → **dispose**. Once the
  clone is verified healthy and the source decommissioned, the bundle — and any
  independent safety copy of it — is the last remaining every-secret-at-once
  artifact; a plain `rm` leaves that material in freed blocks. The helper
  standardizes overwrite-then-remove: `shred -uz` on a bundle file, or
  `find <dir> -type f -exec shred -uz {} +` then `rm -rf` on a recovery
  directory, so no empty tree is left. Because it is irreversibly destructive it
  is guarded: it acts only on a `*.tgz` or `*.pgc` regular file (the bundle, or
  the recovery convention's independent safety-copy dump) or a directory shaped
  like bundle/recovery material (a `*.tgz` or `*.pgc` at its top level, a
  `db/*.pgc` one level down or a `.infochat-pack.*` name — both mark the
  interrupted-pack staging remnant that a SIGKILL/OOM/power loss during
  `pg_dump` leaves behind, beyond the reach of `pack.sh`'s EXIT trap — or a
  `raw-config/` subdir); it refuses nonexistent paths, `/`, the invoking
  user's `$HOME`, the repo root, and anything not matching those shapes; and it
  destroys nothing without explicit consent (`--yes`, or an interactive y/N
  prompt defaulting to No) after printing the resolved absolute target and a
  file-count/size inventory. Disposal is **deliberately not automated** —
  `restore.sh` never invokes it and no cron should: the bundle is the
  disaster-recovery fallback, kept until the operator has verified the clone
  healthy, and auto-destruction at restore time would remove the only backup
  exactly when a subtly-broken restore reveals itself. Destroying it stays an
  operator-timed act; the helper only makes that act safe and one-command. On a
  copy-on-write or journaled filesystem `shred` cannot guarantee the old blocks
  are unrecoverable; hardlinks cut both ways (shredding one name zeroes the
  shared inode, so other directory entries survive pointing at zeroed content —
  and conversely a hardlinked "safety copy" IS destroyed by shredding any one
  of its names); and on SSDs wear-leveling/FTL remapping means the overwrite
  may never reach the original NAND cells — overwrite-then-remove is
  best-effort; full-disk encryption of the storage medium is the real guarantee
  (the same class of caveat as the D34 transfer/storage-encryption
  responsibility).

**Root-owned identity dirs.** The Provider container runs as root, so both
adapters' identity stores are root-owned — and `signal-cli` locks its account store
to mode `0700`, unreadable to a non-root host `tar`. So `pack.sh` and `restore.sh`
run the identity tar (pack) and untar (restore) as **root inside a throwaway
container** (the same in-container-privilege pattern as `pg_dump`/`pg_restore`),
bind-mounting each configured data-dir at its absolute path — no interactive `sudo`
on the host. This is adapter-agnostic: SimpleX and Signal dirs go through the
identical privileged path (no reliance on SimpleX's incidental `0644`), and the
restore untar preserves `root:root` ownership + modes so each daemon accepts the
restored identity as its own. Because the restore untar now runs as root, the
identity-extraction allowlist — extract ONLY the configured data-dir members — is **load-bearing** for an
honest-config bundle's EXTRA members, not merely defense-in-depth; the untar mounts
only the configured data-dirs writable. This does NOT stop a COHERENTLY tampered
bundle: the writable mount target is derived from the same operator-controlled
`INFOCHAT_<NAME>_DATA_DIR` the allowlist is built from, so a tamper naming a system dir
(with a matching tar member) would let the root untar write there — an out-of-model,
supply-chain case (security.md keeps the bundle trusted). Running the untar as root
also removed an earlier incidental EACCES backstop against root-owned system dirs;
`pack.sh` and `restore.sh` restore an explicit equivalent by **refusing**, before any
mount is built and naming the offending key, a `data-dir` that resolves under a
clearly-system prefix (`/etc /root /boot /bin /sbin /lib /lib64 /dev /proc /sys
/var/lib/docker`) or that contains a `:` — docker's `-v` mount-spec separator.
`backup.sh` (§7.10) uses the SAME root-privileged in-container tar, so its
step-1 identity backup succeeds when run as the non-root deploy user — the way
`upgrade.sh` invokes it — not only under a root cron. backup.sh only READS the identity
dirs (its bind-mounts are `:ro`) and streams the tgz to a host file, so it carries the
same colon / system-prefix `data-dir` guard but none of the write-side allowlist
the restore untar needs.

**Flyway-created principal roles.** `pack.sh` dumps the database with a
single-database `pg_dump -F c`, which does NOT carry cluster-global roles. But
`infochat_admin` — the NOLOGIN principal the service roles are GRANTed against — is
created by **Flyway V2** (`V2__roles.sql`), not by `postgres-init.sh` (which mints
only `infochat` + `infochat_collector` + `infochat_provider`). On a fresh target the
role is therefore absent, and every ACL entry in the dump that grants to
`infochat_admin` fails `role does not exist`. Because `pg_dump` emits each object's
whole GRANT/REVOKE set as ONE atomic multi-statement command, that failure also rolls
back the co-located `infochat_collector` / `infochat_provider` grants bundled in the
same entry (`heartbeat`, `source`, `quarantine`, `invite_code_attempt`,
`audit_log_view`, the quarantine functions) — so the Collector dies on its first
`heartbeat` write and restore exits non-zero (loud, not a subtly-broken clone). Flyway
then runs as a no-op over the restored (already-V56) history and never repairs it. So
`restore.sh` reconstructs `infochat_admin` (idempotent `CREATE ROLE ... NOLOGIN`,
guarded by a `pg_roles` NOT EXISTS check, mirroring V2) **after** Postgres is up but
**before** `pg_restore`, so the dump's ACL entries apply cleanly on the first restore —
the repair happens at restore time, not via a later migration pass. `infochat` is
`CREATEROLE`, so no superuser is needed; NOLOGIN carries no password. (The live
 deployment was recovered the same way during the round trip that surfaced this.)
One residue is NOT repaired and cannot be: personal operator LOGIN roles and their
`GRANT infochat_admin TO …` memberships (the V43-documented `ops_alice` workflow) are
likewise cluster-global and absent from the single-DB dump — their password hashes
were deliberately never in the bundle — so they must be re-created by hand on the
clone. This is the one silent divergence from the exact-clone promise (everything
else fails loud); `restore.sh` prints this reminder at the end of every run.

**Bounded pg_restore error tolerance.** `postgres-init.sh` pre-creates the
`vector` and `pgcrypto` extensions at first volume init, owned by the bootstrap
superuser — so the dump's two `COMMENT ON EXTENSION` statements, replayed by the
non-owner `infochat` role, always fail `must be owner of extension pgcrypto` /
`… vector`. Those two notices are EXPECTED on every healthy restore (the extensions
themselves exist; only the cosmetic comments are skipped), which is why `restore.sh`
does not pass `--exit-on-error`. The tolerance is bounded to exactly that set: on a
non-zero `pg_restore` exit, every `pg_restore: error:` stderr line must match one of
those two notices, and the ignored lines are always printed with their count — never
silenced. Any other error line (disk full mid-data-load, invalid data, a failed index
build — or a non-zero exit with no recognizable error line at all, e.g. the compose
transport died) fails the restore loud BEFORE the app image build and bring-up,
naming the failing lines and stating the clone is INCOMPLETE. The "at least one table
present" check stays as a backstop for the one shape the error gate cannot see: a
restore that populated nothing yet exited 0. *Recovering from a failed (partial)
restore:* the target then holds placed runtime files (config/secrets/identities) and
a partially-populated database, and `restore.sh`'s fresh-host gates will refuse a
plain re-run. Return the target to fresh — remove the Postgres data volume
(`docker volume rm <project>_infochat-pgdata`), the placed `prod/runtime` files, and
the restored identity dirs (root-owned; remove via a root container) — `restore.sh`
prints this exact recipe on any post-mutation failure. `prod/setup.sh
--reset --hard` is NOT a substitute: it keeps `secrets.env`, so the fresh-host gate
still refuses, and it falls through into the interactive setup wizard. Fix the
underlying cause, then re-run `restore.sh` with the bundle.

**Flyway-history validation against the checkout.** After the schema-presence
backstop and BEFORE model rehydration, `restore.sh` validates the dump's
applied-migration history against this checkout: it reads
`flyway_schema_history` (version, script, checksum of every successful SQL
migration) from the restored database and recomputes each checksum from the
checkout's migration files with a dependency-free reimplementation of Flyway's
checksum (CRC32 over the file content, line terminators excluded). Any drift —
the dump applied migration files whose content this checkout no longer carries,
even comment-only edits — fails the restore loud, naming every drifted version
and printing both recovery options: re-run from a checkout at the source host's
revision, or deliberately apply the printed `flyway_schema_history` checksum
UPDATE (flyway-repair equivalent) as an operator act. An applied version with
no matching checkout file is the distinct newer-bundle-into-older-checkout
case and says so. Without this gate the first drift detector is the Collector's
Flyway validate at boot — a crash loop minutes and a model download later. The
gate never repairs anything itself: a mismatch can mean a genuine semantic
change, which auto-repair would silently bless.

**Same-absolute-path constraint (v1).** The identity tar is stored relative to
`/`, so the clone reconstructs each data-dir at its original absolute path.
Relocating to a *different* absolute path (rewriting the `data-dir` config) is a
follow-up, not v1; `restore.sh` fails loud on a path mismatch rather than
silently half-restoring.

**Single-owner cutover — the binding constraint.** Exactly ONE instance may own
each messaging identity at a time. This is **not** enforced by the database
advisory lock: that lock is per-*database* (§7.8.5), and the clone restores into
its OWN database, so the two hosts hold two independent locks and both would
start. Signal treats `signal-cli` as the account's single primary device and a
SimpleX queue has one legitimate owner — two live consumers corrupt
session/ratchet state. The operator must observe the ordering:

```
stop-source → pack → transfer → restore + verify → decommission-source
```

Two pieces of tooling friction back the ordering: `pack.sh` WARNs — never
refuses — when the Provider is still running (read-only, so a live pack cannot hurt
the source, but it can bundle a torn identity snapshot; see the `pack.sh` bullet
above), and `restore.sh` will not start the Provider without operator consent
(`--source-stopped`, or the interactive default-No prompt). Decommissioning the old
host (`apps.sh stop`, then optionally `setup.sh --reset --hard`) happens only AFTER
the clone is verified healthy, so a corrupt bundle or a failed bring-up never leaves
zero working copies.

---

## 7.11 Upgrade procedure

The shipped deployment is the containerized `docker compose --profile prod`
stack (§7.4, §7.7.2): the two app services **build their source into the image**,
while Postgres and the LLM backends are pulled `image:`s. So upgrading the app is
"rebuild the two app images from the current source and restart them" — there is
no jar to place and no `current` symlink. The `git pull` that fetches new commits
is **best-effort, not the trigger**: this deployment is operated by committing to
the local checkout, so the checkout is routinely already at `origin/main` while
the running images are stale — and a bare `prod/scripts/upgrade.sh` still rebuilds
and redeploys those stale images with no operator-supplied env var and no git
surgery. `prod/scripts/upgrade.sh` wraps the whole flow; the steps below are what
it does (and the manual equivalent).

**What is preserved across an upgrade.** Everything that holds state lives
outside the rebuild path and is never touched:

- the database (the `infochat-pgdata` named volume),
- the LLM model caches (`infochat-ollama` / `infochat-llamacpp-models`),
- config, secrets, bootstrap files, and the SimpleX/Signal identities (the
  `prod/runtime/` bind-mounts — and `prod/runtime/` is gitignored, so `git pull`
  cannot clobber operator config).

The upgrade never runs `down` and never passes `-v`; it is `build` (old
containers keep serving) then `up -d` (compose recreates only the two app
services whose image changed). Downtime is the recreate, not the multi-minute
build.

**Precondition.** Upgrade applies only to a deployment that has already been
through `prod/setup.sh`. `prod/scripts/upgrade.sh` aborts up front if
`prod/runtime/secrets.env` is absent and tells the operator to run setup first.

Procedure (`prod/scripts/upgrade.sh`; `-y` runs it unattended, otherwise it
confirms before each irreversible gate):

1. **Preflight.** Abort if the deployment is not configured (no `secrets.env`),
   if tracked files have uncommitted changes (a `git pull --ff-only` would
   refuse), or if docker / the compose file are missing.
2. **Backup first** via `prod/scripts/backup.sh` — the DB dump is the real
   rollback path for a schema change (migrations are forward-only, see step 7).
3. **Record** the current commit for code-rollback, then
   `git fetch origin && git pull --ff-only origin main`. The pull is best-effort:
   it advances the checkout when it is behind `origin/main`, but a no-op pull
   (the checkout is already current) does **not** end the run — whether to
   rebuild/restart is decided in step 6, not by whether the pull moved `HEAD`.
4. **Show what config changed this release** (informational; never edits the
   runtime file). Config is two-layer (§7.6.2): the baked module defaults carry
   every key with a working default, and the mounted `prod/runtime/` file
   overrides a subset — so a *new* key ships with its default and needs no merge.
   The real risk is a key the operator overrides that a release *renamed or
   removed*, whose override then silently stops taking effect. The script
   surfaces this with `git diff <pre>..<post>` over the baked
   `src/main/resources/application.properties` files and lists the operator's
   own override keys to reconcile. (There is no `prod/config/application.properties`
   template — `application.properties` is image-baked, not a `prod/config/`
   file like the bootstrap JSONs.)
5. **Rebuild** the two app images from the current source
   (`docker compose --profile prod build infochat-collector infochat-provider`),
   always — a full cache-hit rebuild is cheap and yields the same image id — while
   the old containers keep serving. A compile failure here stops before anything is
   recreated, so the running bot is unaffected.
6. **Restart in §Topology order via `docker compose up -d`.** `up -d` is itself
   the change-detector: it recreates only a service whose resolved image differs
   from its running container (the source changed → a rebuilt image) or whose
   container is stopped, and leaves an unchanged service running — so a cache-hit
   rebuild that yields byte-identical images is a no-op with zero downtime, with
   no separate image-id bookkeeping. It `up -d --wait` the Collector (it runs
   Flyway under the §7.8.5 advisory lock) so its healthcheck must pass before the
   Provider starts against the migrated schema, then `up -d` the Provider. (The
   healthcheck used here replaced an earlier hand-rolled image-id comparison that read `docker compose
   images -q` — which reports the running container's image, not the freshly-built
   tag — and so never redeployed a rebuilt app while it was running.)
7. **Health gate**: confirm the Collector reports `healthy` (it declares a
   `/q/health/ready` compose healthcheck) and the Provider's container is
   `running` (it declares no compose healthcheck, so it cannot be health-gated —
   its functional check is the step-8 smoke test). On a build failure (step 5) or a failed health gate,
   the script **auto-rolls back the code** — `git checkout` the recorded
   pre-upgrade commit, rebuild, restart — and exits non-zero. Schema rollback is
   **not** automated: Flyway migrates forward only, so if a migration already
   applied, restore the database from the step-2 backup (see §7.10 restore).
8. **Smoke check**: `/help`, `/summary -w 1h`, `/status` (admin).

A misconfigured rolling upgrade that brings up the new Provider before the old
one releases the advisory lock is rejected with the fatal-conflict log message
from §7.8.5; this is by design. Within a single-host compose deployment the
`up -d` recreate is stop-then-start of one service at a time, so the old
Collector releases the lock (process exit) before the new one acquires it.

---

## 7.12 Health checks and probes

Both services expose:

- `GET /q/health/live` — process is up.
- `GET /q/health/ready` — DB reachable; (Provider) **at least one enabled adapter is connected** ([06-messaging.md §6.7](06-messaging.md), [../spec/deployment.md](../spec/deployment.md) §Bootstrap behavior); (Collector) eval queue and scheduler healthy. **Does NOT probe the LLM.** This is deliberate: a slow LLM should degrade summary/chat quality, not flip the pod to NotReady and trigger an orchestrator restart loop that masks the underlying problem.
- `GET /q/health/llm` — **separate** endpoint that probes the configured chat-task LLM with a trivial prompt (e.g., "reply with the literal token `OK`") and a **5 s hard timeout**. Returns 200 on success, 503 otherwise. This endpoint is informational/observability-only and is **NOT wired to orchestrator health**: kubelet, systemd `WatchdogSec`, and load balancers MUST NOT consume it. It exists so Prometheus can blackbox-probe the LLM without that probe being on the restart path.
- `GET /q/metrics` — Micrometer/Prometheus.

**Provider readiness rule.** Ready when the Provider's DB pool is up **and at least one** activated messaging adapter is connected (`adapter.connection.status{adapter}=1` for any one of them). Per-adapter status is exposed separately so an operator can distinguish "fully healthy — every adapter up" from "degraded — one adapter down" without parsing readiness alone.

"Connected" folds two signals: the adapter's transport `start()` returned at boot, **and** its subprocess supervisor has not since reached its terminal FAILED state (crash-restart cap exhausted). A supervisor that gives up after a clean start flips that adapter's readiness datum to down — without this, a deployment would read "ready" with a permanently dead adapter, because the boot-time start snapshot never observes the later failure. Mid-session reconnect blips that the supervisor is still retrying do **not** flip readiness (only the terminal give-up does).

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
  - `llm.calls.total{outcome="fail"}` rate-of-change.
  - `eval.queue.size` near `infochat.eval.queue-size` for too long → fetcher back-pressure.
  - `embedding.calls.total{outcome="fallback"}` non-zero → model down.

### 7.12.1 Ops-posture surfaces (v1, pre-metrics)

The Micrometer panel names above (`adapter.connection.status{adapter}`, …) are the target observability surface. v1 ships **no metrics backend wired** (no `quarkus-micrometer` dependency yet — adding one is an explicit dependency decision, not assumed). Until that lift lands, the readiness payload `data` map and a few in-process counters are the status surfaces an operator reads:

- **Health-endpoint exposure — bind to loopback.** `GET /q/health/ready` is **unauthenticated**, and its per-adapter `data` entries **enumerate the activated adapter names** (`simplex`, `signal`, …). That is reconnaissance data — which messaging transports this deployment runs — for any caller that can reach the port. The names stay in the payload deliberately: per-adapter up/down (plus the drop counters below) is the v1 status surface, and trimming the names would blind the operator to *which* adapter is degraded; the exposure lever is network reachability, not payload content. Where health is served depends on which config shape is deployed:
  - **Shipped per-module defaults** (no `quarkus.management.enabled` key in either service's `application.properties`): health rides the service HTTP port (collector 8080 / provider 8081). Neither service serves any other HTTP consumer in v1 (users interact via messaging adapters, never HTTP), so each service's `application.properties` ships the listener bound to loopback:
    ```
    quarkus.http.host=127.0.0.1              # shipped default; health reachable on loopback only
    ```
    A deployment whose prober runs on another host explicitly widens the bind per-profile/env (`QUARKUS_HTTP_HOST` / `-Dquarkus.http.host`) and firewalls the port to the prober's address. The shipped loopback default is pinned per service (`HttpBindDefaultConfigTest` on the Collector; `ProviderReadinessEndpointIT.baseConfigBindsHttpListenerToLoopback` on the Provider), so widening it is a deliberate, reviewed change.
  - **Canonical composed file (§7.4)** sets `quarkus.management.enabled=true`, which moves `/q/health` + `/q/metrics` to the separate management interface — a listener whose bind defaults to `0.0.0.0`, and which `quarkus.http.host` on the main listener no longer covers. The canonical file therefore pins the management bind to loopback alongside the enable flag:
    ```
    quarkus.management.host=127.0.0.1        # health/metrics reachable on loopback only
    ```
    A deployment widening it for a remote prober does so explicitly (`QUARKUS_MANAGEMENT_HOST` / `-Dquarkus.management.host`), same as the main-listener override above.
  On a split-host deployment where the prober is on another host, restrict the health/management port to the prober's address with a host firewall rather than publishing it on `0.0.0.0`. The same posture applies to the local `docker-compose` Postgres, which binds `127.0.0.1:5432` for the identical reason (§7.7). Payload truth is pinned at two levels: `ReadinessPayloadShapeTest` pins the `messaging-adapters` check's data map (adapter names + up/down booleans + conditional `<adapter>.dropped-inbound` counts, nothing else *in that check*), and each service's aggregate `/q/health/ready` check-name set is pinned exactly — Provider (`ProviderReadinessEndpointIT`): the adapter check, the auto-registered Agroal datasource check (deliberately kept: DB-down must gate readiness), and the `help-corpora` check (the informational boot-time corpus-build-outcome surface; its data map — one boolean per corpus, never exception text — is pinned by `HelpCorpusReadinessCheckTest`); Collector (`CollectorReadinessIT`): the auto-registered SmallRye Reactive Messaging channels check (deliberately kept: a dead in-memory channel must gate readiness) plus the datasource check — so neither the per-check data map nor either unauthenticated aggregate can widen silently.
- **Inbound drop-newest counters.** Each transport adapter's inbound dispatch queue is bounded and drops the newest message on overflow (06-messaging.md §6.5). The drop is no longer log-only: the readiness payload carries a `<adapter>.dropped-inbound` datum (cumulative since process start) whenever an adapter has dropped at least one inbound message, so a silently saturating queue is visible on `/q/health/ready` without log scraping.
- **Stage-2 fail-open posture (Collector).** On the `base`/`laptop`/`pi` profiles `infochat.security.release-on-stage2-failure=true` (04-security.md §4.7): when the Stage-2 LLM judge cannot run, posts are released with Stage-1 redactions only rather than quarantined. This is a deliberate availability-over-strictness trade for resource-constrained profiles; `vps`/`remote-llm` leave it false. Two surfaces make the posture auditable: a boot-time WARN + `audit_log` row (`STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE`) records that it is **armed**, and `Stage2VerdictHandler.releasedStage2FailedCount()` counts how often it actually **fired** (posts released with `stage2_failed=true`) so an operator can size the exposure. **Decision: the fail-open default stays.** On `base`/`laptop`/`pi` the judge is a co-located local model whose unavailability is routine (process not started, model evicted, thermal throttling past the timeout); failing closed would quarantine the entire ingest stream on every such hiccup — a worse failure mode for those profiles' single-operator deployments than the exposure it removes. The exposure window is itself bounded: the worst-case release is the Stage-1-redacted body (never the original), and the re-evaluation job re-judges `stage2_failed` posts once the judge recovers, re-hiding any that come back hostile (04-security.md §Re-evaluation job). Hosted shapes (`vps`/`remote-llm`) keep `false`. The per-profile defaults are pinned by `Stage2FailOpenDefaultConfigTest`; an operator can still invert either default per-property.
- **Adapter config bean activation.** `SignalConfig`/`SimpleXConfig` are eager `@Startup` validation beans living in the `infochat-messaging-adapter` library jar. The provider does **not** CDI-index that jar today, so they stay dormant; their `@PostConstruct` is gated on the adapter appearing in `infochat.adapters` so that a future `quarkus.index-dependency` on the jar cannot make the eager filesystem validation fire — and fail boot — for a deployment that never enabled that adapter (e.g. an `inmemory`-only or single-real-adapter deployment).

---

## 7.13 Logs and observability stack

### 7.13.1 Logs

Quarkus structured JSON logs (`quarkus.log.console.json=true`) recommended in production. Critical event categories:

- `AdminBootstrap` — once at startup, per enabled adapter that has a configured bootstrap admin
- `AdapterRegistry` — adapter activated, connection events, per-adapter resilience retries
- `BootstrapLoader` — sources file loaded; entry count and SHA; assets file state (loaded / not configured / fatal)
- `Stage1Pipeline` / `Stage1Worker` / `Stage2Worker` — flagged spans (with redacted previews)
- `LinkingJob` / `PartitionPruner` / `ChatMemoryPruner` — scheduled jobs with row counts
- `LlmRouter` — provider chosen for each task at startup
- `LlmRateCap` / `RateCapBucket` / `OutboundRateLimiter` — overflow events with redacted contact id
- `HeartbeatScheduler` — heartbeat tick from the lock-holding instance (DEBUG); fatal-conflict log on rejected acquire (ERROR)

Log retention: 14 days local; ship to centralized log store at operator's discretion (the recommended target is Loki — see §7.13.2).

### 7.13.2 Recommended observability stack

The default v1 self-hosted observability stack is **Prometheus + Alertmanager + Grafana + Loki**. This is a **recommendation**, not a hard requirement: Quarkus emits standards-compliant Prometheus metrics (`/q/metrics`) and JSON logs that any modern observability vendor consumes, so an operator who already runs a different stack still gets a working bot. The recommendation exists so that an operator who has *not* picked a stack yet has a boring-good default to copy.

The stack is operator-deployed alongside Provider/Collector. Nothing in this subsection adds configuration to `application.properties` — the bot already exposes everything the stack needs (Prometheus scrape via the existing `quarkus.management.enabled=true`, JSON logs on stdout). All four components run on the same host as Provider/Collector on `laptop`/`vps`/`pi`/`remote-llm` profiles, and footprints below assume v1 cardinality (one Provider, one Collector, ≤ a few hundred sources).

- **Prometheus** for metrics scrape and storage. The pull model fits a single-host topology — no agent on Provider/Collector beyond the existing `/q/metrics` endpoint. Scrape interval 15 s is fine; v1 cardinality is small (per-adapter, per-source, per-task labels — no per-user labels). Footprint ~100 MB RAM. Local TSDB retention 15–30 days is plenty for a single-operator deployment; longer retention is a remote-write concern, not a v1 default.
- **Alertmanager** for alert routing, grouping, throttling, and silences. Native Prometheus pair. Routes to PagerDuty / Slack / email / webhook depending on what the operator already runs. The `LlmDown`, `AdapterDown`, `BootstrapAssetsBroken`, and `SignalAdapterAuthFailed` rules in §7.12 are the v1 starter set; group on `alertname` and route operator-must-act alerts (`for: 0m`) to the same channel an oncall person actually reads.
- **Grafana** for dashboards, ad-hoc queries, and log↔metric correlation. File-provisioned dashboards check into the operator's config repo alongside `application.properties` and `bootstrap-sources.json` (§7.10) so they version-control with the rest of the deployment. Grafana's native Postgres datasource lets operators query `audit_log` directly from the same UI as metrics — a single pane for "what alerted, what the user did, what the bot did". The dashboards themselves (suggested panels per metric) are out of scope for this file; a starter pack belongs in the operator's repo, not in the spec.
- **Loki** for log aggregation and LogQL queries. Indexes labels only (not full text), so storage cost is roughly 10× cheaper than ELK on the same volume — important on the `pi` profile, where the bot, the LLM, and Postgres already share 8 GB. LogQL syntax mirrors PromQL, lowering the cognitive cost of correlating an alert with the underlying log spans. Single binary, ~256 MB RAM. The Provider's stdout JSON stream goes in via Promtail/Vector/the operator's existing shipper; the JSON event-category names listed in §7.13.1 (`AdminBootstrap`, `AdapterRegistry`, `Stage1Pipeline`, `Stage2Worker`, …) make natural Loki labels.

**Why not the alternatives.**

- **vs ELK.** Elasticsearch alone needs ≥ 4 GB heap. That doesn't fit on the `pi` profile (8 GB total host) or the `vps` profile (typically 8 GB) without crowding out the bot, the LLM, and Postgres. Loki indexes labels only, which is the right tradeoff for v1 log volume.
- **vs journald + manual `journalctl`.** Fine for `laptop` dev only. Does not correlate with metrics, does not survive `journalctl --vacuum-time` cleanly, does not allow alerting on log patterns (e.g., a sudden burst of `Stage2Worker` `MALWARE` verdicts). Logs that no one reads until something is on fire are not observability.
- **vs cloud-managed (Datadog / New Relic / Honeycomb).** Pulls bot logs and `audit_log` spans across a third-party trust boundary. An operator who has chosen self-hosted SimpleX/Signal probably wants self-hosted observability too; the threat profile in [04-security.md](04-security.md) §Per-adapter admin threat profile is harder to reason about once metrics and logs leave the host. Cloud-managed remains a fine **operator override** for teams whose security model already accepts the boundary; it is just not a fit as the recommended *default*.
- **vs Vector + ClickHouse / OpenObserve / SigNoz.** Newer, smaller community, less battle-tested operator runbooks. Prometheus + Loki + Grafana is the boring choice that just works and that any operator who has done observability before already knows. v1 optimizes for "an operator can stand this up in an afternoon", not for "an operator can save 20% on storage".

---

## 7.14 Operator runbook (common tasks)

**"The bot isn't responding."**

1. `systemctl status infochat-provider` — running?
2. `journalctl -u infochat-provider -n 200` — errors?
3. `curl localhost:8081/q/health/ready` — 200?
4. Per-adapter status in `/status` (admin) or via metrics: which `adapter.connection.status{adapter}` is 0? Provider readiness can be 200 while one adapter is down (the at-least-one-up rule, §7.12).
5. SimpleX side: is the `simplex-chat` subprocess running? The transport is loopback IPC to a co-located subprocess (no session token), so a wedged SimpleX adapter is a subprocess/connection problem, not an auth one — check `adapter.connection.status{adapter="simplex"}` and the subprocess logs.
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

**"Rotate the SimpleX bootstrap admin (claim-token model, D50)."**

SimpleX admin is established by claim, not by address (§7.6.3), so there is no by-address drift to rotate through — a new contact must **claim** a fresh token while no SimpleX admin exists. (There is no SimpleX session token to rotate either — the transport is loopback IPC to a co-located subprocess, §6.4.1.) Steps:

1. From an existing admin's chat (on any adapter — last-admin protection is global), run `/revoke-admin <current-simplex-admin-contact-id>`. This requires another `is_admin = true` row to remain somewhere; if SimpleX is the **only** adapter and its admin is the sole admin, you cannot revoke it in-chat — grant a second admin first (or use the break-glass DB path in the migration runbook below).
2. Set a fresh `infochat.adapters.simplex.admin-token` (a new secret) in `secrets.env` / `application.properties` and restart Provider. With no `(simplex, is_admin = true)` row remaining, the token is armed again.
3. Have the new admin DM the bot the fresh token. The claim mints the new `(simplex, is_admin = true)` row (audit `BOOTSTRAP`, `cause = 'claim'`). Then **unset the token** (operator hygiene, §7.6.3).

**"An admin row exists but no one can act as it (phantom / unreachable admin)."**

Last-admin protection counts `is_admin = true AND is_banned = false` rows with no reachability check ([../spec/security.md](../spec/security.md) §Authorization model), so a bootstrap-seeded admin whose `contact_id` never byte-matches inbound messages — a **phantom admin** — counts as a live admin even though no message can ever be attributed to it. The hazard is that a reachable co-admin can `/revoke-admin` the other reachable admins down to only the phantom, leaving the deployment locked out of admin while the trigger still believes one admin remains. This is operator misconfiguration, not an adversary path. Two sources, by adapter: for **address-based** adapters a mistyped bare contact id (Signal ACI); for **SimpleX**, a phantom is no longer produced by the current claim-token bootstrap — the admin row is claim-minted from the actual inbound connection's `contact_id`, which byte-matches inbound by construction (§7.6.3) — but a deployment **bootstrapped by the legacy by-address path** carries a leftover phantom `(simplex, is_admin = true)` row that ALSO blocks the token claim; recover that case via the **migration runbook** below, not the drift step here.

1. **Detect.** Enumerate live admin rows with `psql`: `SELECT adapter, contact_id, is_admin, is_banned FROM users WHERE is_admin = true AND is_banned = false`. Confirm each `contact_id` matches what its adapter reports for an actual inbound message from that person — a row whose id no inbound message has ever carried is the phantom. (There is no in-chat admin-roster command in v1; this check is operator-side.)
2. **Recover via bootstrap-admin drift — address adapters (works even when the phantom is the only admin left).** Point the address adapter's `infochat.adapters.<name>.admin` at the intended reachable contact id and restart Provider. Drift **adds** the corrected admin row and leaves the phantom in place (§7.6.3), so the deployment now has a reachable admin alongside the phantom — there is no in-chat dead end even if the phantom was the sole admin before the restart. From the now-reachable admin's chat, run `/revoke-admin <phantom-contact-id>`; last-admin protection is satisfied because the drift-added reachable admin remains. (A **SimpleX** phantom has no drift path — the token claim is blocked while the phantom row exists; use the migration runbook below.)
3. **Direct-DB last resort (only if the drift path is unavailable).** With the services stopped, set `is_admin = true` on the intended reachable contact's `users` row (creating it if absent) via `psql`, restart, then revoke the phantom in-chat. Write an `audit_log` row recording the manual intervention and its cause. This is the same shape of direct-DB intervention an operator would use for any zero-reachable-admin lockout; in the phantom case step 2 normally suffices, so this is rarely needed.

**"Migrate a legacy by-address SimpleX bootstrap."**

Before the claim-token build the SimpleX bootstrap was configured **by address** (`infochat.adapters.simplex.admin=<SimpleX address>`) and **seeded at startup**, which minted a `(simplex, <address>, is_admin = true)` `users` row. The DB volume survives `upgrade.sh` (state is preserved), so after upgrading to the claim-token build that row persists — and it is a problem on two counts: (a) it was **never reachable** (inbound SimpleX DMs resolve to a per-connection `contact_id`, never the advertised address — the prior root cause for replacing the by-address path), and (b) it now **blocks the token claim**, because the single-use gate is `WHERE NOT EXISTS (… adapter = 'simplex' AND is_admin = TRUE)` — with the phantom present, the first token DM never claims. **Symptom:** you set `admin-token`, DM the token, and get the fixed `error.invite.required` reply instead of becoming admin.

Recovery — **set the token → clear the phantom → claim → unset the token**:

1. **Set the token, drop the stale address.** Set a fresh `infochat.adapters.simplex.admin-token` and remove the now-inert `infochat.adapters.simplex.admin` line. (The claim stays blocked until step 2 clears the phantom, so the order of restart vs. clear does not matter for correctness.)
2. **Clear the phantom row** (break-glass DB action, **not** a routine command — the by-address admin commands cannot reach this row). With `psql` as the **table-owner** DB role, delete the phantom `(simplex, is_admin = true)` row identified via the phantom detect query above, and write an `audit_log` row recording the intervention and its cause.
   - **Last-admin caveat (SimpleX-only deployments).** If SimpleX is the only enabled adapter, the phantom is the deployment's **only** `is_admin = true` row, so the delete trips last-admin protection (`trg_last_admin_protection_delete`, `ERRCODE = 'IC001'`). Temporarily disable the trigger, clear the row, re-enable:
     ```sql
     ALTER TABLE users DISABLE TRIGGER trg_last_admin_protection_delete;
     DELETE FROM users WHERE adapter = 'simplex' AND is_admin = TRUE;
     ALTER TABLE users ENABLE TRIGGER trg_last_admin_protection_delete;
     ```
     `DISABLE TRIGGER` requires table ownership, so run it as the migration-owner role (`infochat`), not the Provider role. In a **multi-adapter** deployment another admin remains, so no trigger disable is needed — the delete succeeds directly.
3. **Claim.** With no `(simplex, is_admin = true)` row remaining the token is armed; have the intended admin **DM the bot the token**. The claim mints a reachable `(simplex, <real contact_id>, is_admin = true)` row (audit `BOOTSTRAP`, `cause = 'claim'`).
4. **Unset the token.** Blank `infochat.adapters.simplex.admin-token` and restart (operator hygiene, §7.6.3) so a leaked token cannot re-claim.

**"Re-register `signal-cli` (post-`AUTH_FAILED`)."**

The Signal adapter has its own auth-failure terminal state ([06-messaging.md §6.5](06-messaging.md)) reached on repeated `signal-cli` rejection (e.g., the account is no longer valid on the upstream). Recovery is operator-driven:

1. Stop the `signal-cli` daemon and re-register the account out-of-band: `signal-cli -u <number> register --captcha <token>` then `signal-cli -u <number> verify <code>`.
2. Make sure the new account directory at `infochat.adapters.signal.data-dir` has the same on-disk shape as before; back up any state files first (§7.10).
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
| One adapter wedged, others fine | Per-adapter resilience ([06-messaging.md §6.7](06-messaging.md)): Provider stays ready, remaining adapters continue serving. Diagnose the failing adapter via §7.14 (SimpleX subprocess/connection check, `signal-cli` re-registration); cycle Provider once the underlying client is healthy again. |
| All adapters wedged | Bot appears offline. Fix at least one adapter; on reconnect, queued outbounds (if any, in-memory only — see §7.16) flush. No DB state loss. |
| Profile mistake (a misset profile or overridden tuning value) | Stop services, correct `quarkus.profile` / the offending key, restart (§7.2.1). The embedding dimension is fixed at 768-d in v1, so a profile switch never changes it — there is no embedding migration to run. |
| Compromised LLM API key | Rotate the env var. Restart Provider. Add an audit row noting rotation reason. |
| Lost SimpleX bootstrap admin | Rotate via the claim-token model (§7.14, "Rotate the SimpleX bootstrap admin"): `/revoke-admin` the current SimpleX admin (needs another global admin to remain), set a fresh `infochat.adapters.simplex.admin-token`, restart, have the new admin DM the token to claim, then unset the token. There is **no** by-address drift for SimpleX (D50). |
| Lost Signal bootstrap admin | Rotate `infochat.adapters.signal.admin` to a different ACI, restart (bootstrap admin drift, §7.6.3), `/revoke-admin` the prior. |
| Phantom (unreachable) bootstrap admin | A seeded admin whose `contact_id` never byte-matches inbound counts toward last-admin protection but no one can act as it; a co-admin can then revoke the reachable admins down to only the phantom ([../spec/security.md](../spec/security.md) §Authorization model). Operator misconfiguration, not an adversary path. Detect via the `psql` admin-row check (§7.14, "phantom / unreachable admin"). **Address adapters:** re-seed the intended contact through bootstrap-admin drift (§7.6.3) and `/revoke-admin` the phantom. **SimpleX (legacy by-address bootstrap):** the phantom also blocks the token claim — recover via the migration runbook (§7.14, "Migrate a legacy by-address SimpleX bootstrap"). |
| Bot account compromised on one adapter | Per-adapter scope ([04-security.md §4.4](04-security.md)). Recover the relevant client (rotate the SimpleX bootstrap admin via the claim-token model / re-register Signal per §7.14); rotate the bootstrap admin per the rows above; reissue invite links to known users. Source data and the other adapter are untouched. Cross-adapter elevation is impossible by design (`/grant-admin` and `/revoke-admin` are inbound-adapter-scoped). |
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
  - `infochat.adapters.<name>.data-dir` exists, is owned by the `infochat` user, and contains valid bot identity material (out-of-band registration completed for SimpleX and/or Signal); `infochat.adapters.<name>.binary` points at the installed `simplex-chat` / `signal-cli` executable.
  - `infochat.adapters.<name>.admin` is set on **at least one** adapter (the union-non-empty rule, §7.6.3); each value is parseable by its own adapter.
- `bootstrap-sources.json` validated (per-`kind` config block, especially `nostr` relays); URLs reachable from the host.
- If asset commands are wanted: `infochat.bootstrap.assets-file` set and the file parses cleanly (§7.6.2 — file-state semantics).
- Ollama models pre-pulled.
- Disk has ≥ 30 GB free, swap enabled.
- Backups scheduled (cron + `pg_dump` + per-adapter data-dir tarball script tested on a non-prod DB first; §7.10).
- systemd units have `Restart=on-failure` and `RestartPreventExitStatus=42` (§7.8.5).
- First boot logs reviewed: profile detected, sources loaded, assets loaded (or info-line opt-out), per-adapter admin bootstrapped, every enabled adapter shows `adapter.connection.status=1` or its retry path.
- Smoke: `/help`, `/add-source`, `/summary -w 1h` all work end-to-end on each adapter where the operator is admin.
- `/q/health/ready` is 200 on Provider and Collector.

---
