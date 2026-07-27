> **Status: design notes, not spec.**
> Implementation details below (DDL, class names, package layout, property keys,
> retry counts, regex strings, etc.) are working notes that may change without a
> spec amendment. The authoritative *what & why* lives in `docs/spec/`.

# 01 — Architecture

This file is the design-tier companion to [`spec/architecture.md`](../spec/architecture.md).
It does not restate the spec; it fills in module names, package layout, bean
priorities, profile-specific numeric values, and ASCII diagrams of every code
path the spec commits to. Where a spec section is the source of truth for a
rule, this file links upward and only adds the implementation specifics.

## 1.1 Components

```
┌─────────────────────┐         ┌──────────────────────┐
│  Collector Server   │         │  Provider Server     │
│  (headless)         │         │  (user-facing)       │
│                     │         │                      │
│ - Bootstrap loader  │         │ - Messaging adapters │
│   (sources JSON,    │         │   (SimpleX, Signal,  │
│    assets JSON)     │         │    any non-empty     │
│ - Fetch scheduler   │         │    subset, D46)      │
│ - Fetchers (RSS,    │         │ - Command router     │
│   Reddit, Bluesky,  │         │ - Chat agent         │
│   Nitter, YouTube,  │         │ - Periodic-digest    │
│   Odysee, asset     │         │   scheduler (stagger)│
│   feeds)            │         │ - Summary cache      │
│ - StreamSources     │         │ - Admin guard        │
│   (Nostr)           │         │   (bot + group tier) │
│ - Eval pipeline     │         │ - Ban guard          │
│   (Stage 1, Stage 2,│         │ - Confirmation svc   │
│   tagger, entities, │         │ - Translation        │
│   embeddings)       │         │ - Rate limiter       │
│ - Outbox rehydrator │         │ - Progress notifier  │
│ - Linking job       │         │   (coalesces in-     │
│ - TTL pruner        │         │    flight progress   │
│ - Admin notifier    │         │    updates per req.) │
│   (throttled)       │         │ - NOTIFY reconcilers │
│                     │         │   (per channel)      │
└──────────┬──────────┘         └──────────┬───────────┘
           │                               │
           │       ┌──────────────────┐    │
           └──────▶│  PostgreSQL      │◀───┘
                   │  + pgvector      │
                   │                  │
                   │  LISTEN/NOTIFY:  │
                   │  - new_post      │
                   │  - quarantine_   │
                   │      review      │
                   └──────────────────┘
                           │
                           │  (provider also calls
                           │   LLM and messaging
                           │   adapters; collector
                           │   also calls LLM and
                           │   external feeds)
                           ▼
            ┌──────────────────────────────┐
            │  External:                   │
            │  - Ollama / llama.cpp /      │
            │    OpenAI / Anthropic / etc. │
            │  - SimpleX Chat CLI (WS bot) │
            │  - signal-cli (JSON-RPC)     │
            │  - HTTP feeds (RSS, Bluesky, │
            │    YouTube, Reddit, asset    │
            │    APIs, …)                  │
            │  - Nostr relays (wss://)     │
            └──────────────────────────────┘
```

The **in-memory test adapter** exists for CI and local development; it is
exercised in a separate test-time deployment shape and never runs alongside
production adapters in the same Provider (decision D46;
[`spec/deployment.md`](../spec/deployment.md) §Deployment scenarios). It is
omitted from the diagram above because the diagram is the v1 production shape.

The closed list of LISTEN/NOTIFY channels is committed in
[`spec/architecture.md`](../spec/architecture.md) §Inter-service communication
(`new_post`, `quarantine_review`). Adding a channel
requires a spec amendment. The `quarantine_review` channel carries a tagged
payload `(target_kind, target_id, new_status)` with `target_kind ∈
{'quarantine', 'post'}`; payloads are bounded to the cursor key and the
receiver always reads the row from the base table.

## 1.2 Module layout (Maven)

```
infochat/
├── pom.xml                          # parent POM, BOM, plugin versions
├── infochat-core/                   # shared DTOs, Panache entities, repos, Flyway
├── infochat-ssrf/                   # SSRF-gated outbound HTTP/WS client (shared)
├── infochat-llm-adapter/            # LlmProvider, EmbeddingProvider SPI + impls
├── infochat-messaging-adapter/      # MessagingAdapter SPI + SimpleX, Signal, in-memory impls
├── infochat-collector/              # Quarkus app: schedulers, fetchers, eval pipeline
└── infochat-provider/               # Quarkus app: command router, chat agent
```

`infochat-core` deliberately bundles the shared DTOs and the Panache
entities/repositories. Splitting them out is a refactor for v2 if a third
consumer appears.

`infochat-ssrf` exists because both Collector (every outbound feed fetch,
redirect, and `StreamSource` connect) and Provider (every `/add-source` URL
probe) must run through the same fail-closed allowlist — IP blocklist,
DNS-rebind defense, redirect cap, scheme allowlist, timeout caps. The spec's
"DB-only inter-service communication" rule is about runtime data, not
compile-time code sharing, so a Maven sibling module both services depend on
is the right shape ([`spec/security.md`](../spec/security.md) §SSRF and
outbound connections). There is no Provider→Collector RPC for SSRF checks.

`infochat-messaging-adapter` ships three implementations in v1: SimpleX,
Signal, and an in-memory test adapter (decision D32, D46). A single Provider
process can host any non-empty subset of the production adapters
simultaneously; the in-memory adapter is test-only.

## 1.3 Key data flow: ingest

The Collector exposes **two** ingest SPIs that feed the same outbox shape
(decision D38, [`spec/architecture.md`](../spec/architecture.md) §Ingest SPIs).
Asset Fetchers are a third, distinct path that **bypasses** the post outbox
entirely (decision D39).

### 1.3.1 Polled `Fetcher` → outbox → eval pipeline

```
Source row (kind, identifier, config)
  │
  ▼
Per-kind tick (FetchScheduler)
  │
  ▼
Fetcher (kind-specific impl) issues HTTP GET/HEAD via infochat-ssrf
  │ paginates within a single tick up to per-source max-page cap
  │ (profile-driven; see §1.6)
  ▼
Normalize → INSERT post(status='RAW', body=sanitized_html)
  │  Stage 1 deterministic security check happens here:
  │  OWASP Java HTML Sanitizer + prompt-injection regex.
  │  Suspicious spans replaced with [REDACTED:<id>] placeholders,
  │  originals stored in `quarantine` table.
  │  (Stage 1 wording is owned by spec/security.md §Ingest pipeline.)
  ▼
Enqueue post_id on the eval channel  ──────────┐
                                                │
  (if Collector restarts, OutboxRehydrator      │
   on @Startup scans status='RAW' and           │
   re-enqueues. Invariant 5: in-flight          │
   evaluation = RAW + per-stage *_done flags,   │
   no 'EVALUATING' status.)                     │
                                                ▼
                                         eval workers (§1.3.4)
```

**Per-host outbound pacing.** When a kind is due, the scheduler does
not blast every active source of that kind in one pass: several sources often
share a host (e.g. the ~22 nitter feeds all on `rss.xcancel.com`), and bursting
them trips the host's rate limit (a `403` whose HTML body fails the RSS parser,
tripping each source to `failed` via the D42 ladder). The throttle is keyed on
**host**, not kind: `infochat.fetch.host-min-interval` (a Quarkus duration, or
`off` to disable — mirroring `infochat.linking.interval`) sets the minimum gap
between two outbound requests to the same host. A due source whose host was
requested within the window is **deferred** to a later heartbeat — held in an
in-memory per-kind pending queue, drained one-per-host-per-window across
successive heartbeats — and is never dropped nor postponed a whole
kind-interval (the kind's `last_tick` stamp is withheld until its pending queue
drains, so dueness is preserved across the deferral). Sources on distinct hosts
are unaffected: a heartbeat whose due sources are all on different hosts
dispatches them all. Pacing is **heartbeat-quantized** — because every source
in one tick shares a single `now`, the effective floor is one dispatch per host
per heartbeat when the window ≤ the heartbeat; with the 1m heartbeat a crowded
host drains at ~1/min, exactly the burst-avoidance intended. True sub-heartbeat
spacing would need delayed async dispatch, which the synchronous tick model
deliberately does not do. The window decision reads the injected `Clock` (§9
injectable-time rule), so it is pinnable in tests.

### 1.3.2 `StreamSource` → outbox

```
Source row (kind='nostr', identifier=<canonical filter spec>, config={relays:[…]})
  │
  ▼
Supervised worker (started at Collector boot, asynchronous;
  failure to connect a relay does NOT fail readiness — see §1.4.3)
  │
  ▼
Per-relay subscribe (wss:// via infochat-ssrf; signature verification
  against claimed pubkey BEFORE Stage 1; failed sigs dropped + counted)
  │
  ▼
Cross-relay dedup by stable upstream id (one event = one posts row)
  │
  ▼
Normalize → INSERT post(status='RAW', …) → enqueue (same outbox as §1.3.1)
```

Per-relay degradation, the all-relays-bad cooldown wait, and the absolute
cycle-cap → terminal `failed` transition are spec-level commitments
([`spec/architecture.md`](../spec/architecture.md) §Ingest SPIs); the numeric
values per profile live in §1.6 below.

### 1.3.3 Asset `Fetcher` → `price_snapshot` (direct write, no outbox)

```
asset_config row (asset, sub_verb, enabled, status='active', is_default?)
  │
  ▼
Per-`(asset, sub_verb)` tick (AssetFetchScheduler, profile-driven cadence)
  │
  ▼
Asset Fetcher issues HTTP GET via infochat-ssrf
  (CoinGecko free / Kraken public REST / Bitfinex public REST, v1 set)
  │
  ▼
Parse → INSERT price_snapshot(asset, sub_verb, captured_at, payload, source_url)
     NEVER through Stage 1/2, tagger, entity extractor, or embedding.
     Failure increments asset_config.consecutive_failures; D42-style
     threshold-based active → failed transition.
     The write is the terminal step — no NOTIFY, no outbox.
```

Provider serves `/zcash`, `/monero`, etc. by reading the latest
`price_snapshot` row for the `(asset, sub_verb)` triple with a single SQL
query on each command invocation. The table read is the sole correctness
path ([`spec/commands.md`](../spec/commands.md) §Asset commands); there is
no NOTIFY channel and no in-process cache for asset snapshots.

### 1.3.4 Eval pipeline workers

Per-stage failure policy below; the architectural rules (Stage 1 always runs,
Stage 2 only on Stage 1 hits, infrastructure-failure-vs-verdict distinction,
fail-open-with-redactions on Stage 2 outage) are spec
([`spec/security.md`](../spec/security.md) §Ingest pipeline).

1. **SecurityStage2Judge** (LLM, only if Stage 1 flagged anything).
   - Stage 2 *verdict* of injection/exfiltration → QUARANTINED.
   - Stage 2 *infrastructure failure* (LLM down, timeout, parse error after
     1 retry) → release as READY with Stage 1 redactions in place. Sets
     `post.stage2_failed=true` and notifies admin (throttled). Rationale
     lives in [`spec/security.md`](../spec/security.md) §Failure handling.
2. **Tagger** LLM → assigns ≥1 Tier-1 tags from controlled vocab.
   On failure: 1 retry → fallback to `source.bootstrap_tags` → admin
   notify (throttled). Sets `post.tagger_fallback=true` for audit.
3. **EntityExtractor** LLM → extracts named entities → `post_entity` rows.
   On failure: 1 retry → release without entities (Tier 2 entity-link
   coverage degraded for this post). Admin notify (throttled).
4. **EmbeddingWorker** → embeds title+summary → `post_embedding` row.
   On failure: 1 retry → release without embedding. Admin notify
   (throttled).
5. UPDATE `post.status='READY'`, `post.ready_at=now()`, NOTIFY `new_post`
   with payload `(ready_at, post_id)`.

Admin notifier batches identical failure classes (same `(channel,
error_class)` per [`spec/security.md`](../spec/security.md) §Failure handling)
for 15 minutes before sending one summary message ("Tagger LLM failed for 47
posts in the last 15 min, last error: connection refused").

### 1.3.5 LinkingJob (scheduled, every N minutes)

- Driving set: posts where `last_linked_at IS NULL OR last_linked_at <
  fetched_at`, bounded to the last 4 days. New runs no longer re-walk the
  full 4-day window; they only process posts that arrived (or were
  re-evaluated) since the previous run.
- Candidate window for each driving post is still the last 4 days (so a fresh
  post can link backward to older READY posts), but the bidirectional INSERT
  pattern ensures both endpoints are written without a second pass.
- For each driving post: find candidate links via:
  - shared `post_entity` rows (exact-match) → `link_type='entity'`,
    `score=#shared`
  - `cosine_distance < infochat.linking.semantic-threshold` within 48h
    window → `link_type='semantic'`, `score=cosine`
- INSERTs into `post_reference` (capped at N per post).
- On success: UPDATE `post.last_linked_at = now()` for each driving post.
- Kind-6 Nostr reposts use the original event's `upstream_identifier` as the
  join key, not the derived post UID
  ([`spec/architecture.md`](../spec/architecture.md) §Ingest SPIs).

## 1.4 Key data flow: user request

DM, command mode (`/summary security -w 24h`):

```
Messaging adapter receives message
  │
  ▼
Identity resolution (lookup users by (adapter, contact_id))
  │
  ▼
Ban check (intake-blocked users get the fixed reply, never reach LLM/DB)
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
Format response (plain text, single-backtick inline code, triple-backtick
  blocks, bare URLs — adapter capability flags assert
  supportsMarkdownLinks=false in v1), include topic IDs and post UIDs
  │
  ▼
Optional translation per scope language (see §1.8)
  │
  ▼
Messaging adapter sends to user
```

DM, chat mode ("Tell me more about that CVE you mentioned"):

```
Messaging adapter receives message
  │
  ▼
Identity / ban / probation checks (probation blocks chat mode entirely,
  decision D45)
  │
  ▼
CommandParser sees no slash → routes to ChatAgent
  │
  ▼
Memory pre-fetch: SQL keyword match on chat_memory for (user, scope) →
  load top-N
  │
  ▼
Build agent prompt with:
  - System prompt (with delimiter rules for untrusted content)
  - Active context window (capped per profile: see §1.7)
  - Pre-fetched memory summaries
  - The closed read-only tool surface defined in
    spec/security.md §Prompt-injection defenses (read-only DB role;
    NEVER admin tools, NEVER raw SQL). The exact tool names and arities
    are CI-load-bearing and live in spec/security.md; this file does not
    duplicate the list.
  │
  ▼
LLM call. Agent may use tools. Each tool call goes through:
  - Type-checked args (enums, validated ranges)
  - Per-user rate limits
  - Read-only DB role (cannot write)
  - LLM output sanitizer (closed match-set derived from the privileged
    command tier, spec/security.md §LLM output sanitizer)
  │
  ▼
Format response (plain text + backticks for code) → optional
TranslationProvider.translate() if scope language ≠ 'en' →
send via messaging adapter
```

For long-running paths (`/summary`, periodic digest, chat-mode generation),
the command handler acquires a `ProgressContext` from `ProgressNotifier`
*before* the LLM call. The notifier:

1. Calls `MessagingAdapter.send()` with a localized placeholder
   (e.g., "Working on it…") and captures the returned `MessageHandle`.
2. Calls `setTyping(scope, true)` if the adapter declares the capability.
3. Receives stage events from the handler (`STARTED`, `RETRIEVING`,
   `GENERATING`, `TRANSLATING`, `FINALIZING`) and renders each as a
   localized string via `update(handle, text)`. Events are coalesced
   per `(scope, requestId)` to honor `max(adapter.minEditInterval, 600ms)`;
   the latest event wins.
4. On terminal `COMPLETED` or `FAILED`, calls `finalize(handle, finalText)`
   and `setTyping(scope, false)`. Both calls are guaranteed via
   try/finally so the placeholder is never left dangling.

Stage strings are looked up by enum from a localization bundle; user input
is NEVER interpolated into progress strings (security: prevents reflective
injection in screenshots / logs). Short-running deterministic SQL commands
bypass `ProgressNotifier` entirely.

`/stop` cancellation interrupts the in-flight `pg_cancel_backend(pid)` of the
handler's DB session and is final-text-safe through the same finalize path
(decision D35; [`spec/commands.md`](../spec/commands.md) §Conversation
control — `/stop`).

### 1.4.1 Periodic group digest (8am / 8pm)

```
PeriodicDigestScheduler (CRON-like, runs every minute):
  for each group with periodic digest enabled:
    if local_now in [target - 30min, target] and not generated_today:
      enqueue generation slot, offset = (group_index * 30s)

PeriodicDigestWorker:
  - Same SQL retrieval path as /summary, scoped to group's followed tags
  - LLM summarization (with delimiter-wrapped untrusted content)
  - Optional translation per group's language preference
  - Cache(group_id, slot, tag_subscription_version,
          source_subscription_version) for 60 minutes (so a user's /summary
    immediately after the digest is served from cache).
    Including the two `*_subscription_version` counters in the cache key
    means /follow-tag, /unfollow-tag, /add-source, /remove-source, and
    /unfollow-source on the same scope yield a fresh cache miss without an
    explicit invalidation pass. Stale entries age out naturally via the
    existing 60-min TTL. The counters live on `scope_preferences` (see
    [02-schema.md §Per-scope state](02-schema.md)); each is incremented
    atomically in the same transaction as the subscription change.
  - Send to messaging adapter

Profile-aware fallback (pi profile):
  - If worker is busy, defer to next slot (max 30-min delay)
  - On second-defer, generate degraded summary:
    headline list + source names, no LLM prose
```

### 1.4.2 Bootstrap loader (Collector startup)

```
@Startup BootstrapLoader (runs after Flyway migrations):
  1. Read infochat.bootstrap.sources-file (default: bootstrap-sources.json)
  2. For each entry, validate schema (name, kind, identifier, category,
     tags[] with ≥1 entry, optional config object)
  3. For Nostr entries: canonicalize the filter-spec identifier (sort JSON
     object keys lexicographically, compact whitespace) BEFORE upsert so
     equivalent specs do not produce duplicate rows
     (spec/architecture.md §Ingest SPIs)
  4. Upsert into source by (kind, identifier):
     - INSERT if absent
     - UPDATE name/category/tags/config in place if entry differs
       (config mutation is restart-only in v1, decision D38)
     - NEVER deletes; admin uses /remove-source for that
  5. Union of tags across all entries → upsert into tag table
     (Tier-1 controlled vocab)
  6. Read infochat.bootstrap.assets-file (default: bootstrap-assets.json)
  7. Validate each asset entry (asset, sub_verb, enabled, default_quote_currency,
     attribution_url, is_default?). Reject is_default=true with enabled=false
     at startup with a fatal log identifying the (asset, sub_verb)
     (spec/schema.md §Operational — Asset config).
  8. Upsert asset_config rows by (asset, sub_verb).
  9. Audit-log the bootstrap run with file hash + entry counts
```

The bootstrap admin row is seeded by a separate `AdminBootstrap` bean on the
Provider side (see §1.4.3). Per-adapter bootstrap admin contact ids are
configured in `application.properties`; the union across enabled adapters
must be non-empty (decision D46;
[`spec/deployment.md`](../spec/deployment.md) §Operator inputs).

### 1.4.3 Startup-bean ordering and single-instance enforcement

Both services use `io.quarkus.runtime.Startup` with explicit `@Priority` so a
bean never observes uninitialised state from a peer bean. Lower priority
numbers run first.

Collector:

| Priority | Bean              | Purpose                                                                                                     |
|---------:|-------------------|-------------------------------------------------------------------------------------------------------------|
| 50       | InstanceLockGuard | Acquires `pg_advisory_lock(hash('infochat.collector'))`. Failure to acquire → fatal log + refuse to start.  |
| 100      | (Flyway)          | Quarkus runs Flyway migrations before any `@Startup` bean.                                                  |
| 200      | BootstrapLoader   | Seeds `source`, `tag`, and `asset_config` from JSON.                                                        |
| 300      | OutboxRehydrator  | Re-enqueues posts left in `status='RAW'` from prior crash (Invariant 5: no `'EVALUATING'` status — in-flight eval = RAW + per-stage `*_done` flags).         |
| 400      | FetchScheduler    | Begins per-kind polling for `Fetcher` sources and asset Fetchers.                                           |
| 450      | StreamSourceSupervisor | Registers each `StreamSource` worker with the supervised pool. **Asynchronous startup**: the supervisor returns immediately once registration is accepted; per-relay connect runs in background. A relay unreachable at boot does NOT fail readiness ([`spec/architecture.md`](../spec/architecture.md) §Ingest SPIs). |

Provider:

| Priority | Bean              | Purpose                                                                                                                              |
|---------:|-------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| 50       | InstanceLockGuard | Acquires `pg_advisory_lock(hash('infochat.provider'))`. Failure to acquire → fatal log + refuse to start.                            |
| 100      | (Flyway)          | Idempotent re-run; same migration set as Collector.                                                                                  |
| 200      | AdminBootstrap    | For every enabled adapter with a configured bootstrap admin contact id, ensures the row exists with `is_admin=true`. Audit-logged.   |
| 250      | NewPostReconciler | Replays `READY` posts since the high-water mark on the `new_post` channel (see §1.5) to recover from missed `NOTIFY new_post` events.|
| 300      | AdapterRegistry   | Resolves and connects each enabled `MessagingAdapter` (any non-empty subset of SimpleX / Signal / in-memory, decision D46).          |
| 400      | CommandRouter     | Begins consuming inbound messages from every connected adapter.                                                                      |

If any bean throws during startup, the service refuses to start (Quarkus
default). Health endpoint `/q/health/ready` stays 503 until every priority
< 500 bean is up. The asynchronous-startup carve-out for `StreamSource` is
the explicit exception to this rule
([`spec/architecture.md`](../spec/architecture.md) §Ingest SPIs).

**Single-instance enforcement** (spec/architecture.md §Deployment topology).
The `InstanceLockGuard` beans implement the
"exactly one Collector and exactly one Provider" invariant from decision D41:

- Each service acquires a named `pg_advisory_lock` at startup, using
  Postgres' built-in `hashtext('infochat.collector')` /
  `hashtext('infochat.provider')` so both instances always race for the
  same lock id regardless of host (the hash is computed server-side, no
  client routine required).
- A heartbeat row in `heartbeat(service, host_id, pid, last_seen_at)` —
  one row per service, keyed by `service` text PRIMARY KEY (values
  `'collector'` and `'provider'`) — is refreshed every
  `infochat.heartbeat.interval` (per-profile defaults in
  [07-deployment.md §7.2.1](07-deployment.md)). The `heartbeat` table
  is distinct from `provider_state` (§1.5 — the per-channel
  `LISTEN/NOTIFY` high-water-mark table); the two share no rows.
- A second instance attempting to acquire the lock fails fast with a fatal
  log message that names the running instance's `host_id`, `pid`, and
  `last_seen_at` read from the heartbeat row, so the operator can
  diagnose without hunting.
- The lock is released when the holding Postgres session ends — on
  graceful shutdown or hard kill (the session terminates and the server
  releases the lock). The heartbeat row persists across restarts and is
  overwritten by the next holder via UPSERT on `service`; staleness of
  `last_seen_at` is an operator-visible signal, not part of the
  lock-release path.

### 1.4.4 `StreamSource` supervised-worker lifecycle (design specifics)

The architectural rules — async startup, per-relay degradation cooldown,
all-relays-bad wait-on-earliest-cooldown, absolute cycle cap → terminal
`failed` state, drain-on-shutdown — are committed in
[`spec/architecture.md`](../spec/architecture.md) §Ingest SPIs. Per-profile
numeric values:

| Setting                              | laptop | vps | pi  | remote-llm |
|--------------------------------------|-------:|----:|----:|-----------:|
| Per-relay cooldown initial           | 60s    | 60s | 60s | 60s        |
| Per-relay cooldown max (exp backoff) | 30m    | 30m | 30m | 30m        |
| All-relays-bad cycle cap → `failed`  | 20     | 10  | 5   | 20         |
| Graceful shutdown drain timeout      | 10s    | 10s | 5s  | 10s        |
| Per-relay "events lost" counter      | exposed via `/q/metrics` on every profile             |

Re-enable a `failed` source via `/source-enable <id>`; that command resets
the cycle counter and re-registers the supervised worker
([`spec/commands.md`](../spec/commands.md) §Source management).

`since=last_persisted_event_at` is issued per relay on reconnect; relays that
support `since` filters replay missed events, relays that do not may produce
permanent gaps. This is a Nostr-protocol reality, not a bug
([`spec/architecture.md`](../spec/architecture.md) §Ingest SPIs).

## 1.5 Architectural principles (design-tier additions)

The seven principles themselves are committed in
[`spec/architecture.md`](../spec/architecture.md) §Architectural principles.
This section adds only the implementation specifics for principle 2
(outbox + LISTEN/NOTIFY + high-water mark) — the rest of the principles
have no design-only addenda worth duplicating here.

**Principle 2 — high-water-mark catch-up implementation.** Postgres
`LISTEN/NOTIFY` does not buffer messages for disconnected listeners — if the
Provider is restarting when `NOTIFY new_post` fires, the event is lost. The
correctness mechanism is a per-channel high-water mark stored in
`provider_state` (spec/schema.md §Operational — Provider state), one row
per channel keyed by `channel`, holding the channel-agnostic shape
`(channel, cursor_high, cursor_low_kind, cursor_low_id, updated_at)`.

For the `new_post` channel:
- `cursor_high = ready_at`, `cursor_low_kind = 'post'`, `cursor_low_id = post_id`.
- On `@Startup` (priority 250 — see §1.4.3) the Provider runs:

  ```sql
  SELECT id, ready_at FROM post
   WHERE status='READY'
     AND (ready_at, id) > (:cursor_high, :cursor_low_id)
   ORDER BY ready_at, id;
  ```

  and feeds those rows into the same handler the `NOTIFY new_post` listener
  uses.
- The listener and the catch-up loop both advance the cursor via
  compare-and-swap **in the same DB transaction** as the side effect they
  trigger (spec/schema.md §Operational — Provider state). A duplicate
  `NOTIFY` or a repeated catch-up pass for the same row produces no
  additional side effect.
- First-boot insert uses `INSERT … ON CONFLICT (channel) DO NOTHING` so two
  fresh Provider instances starting concurrently both attempt the insert and
  exactly one wins.

The `quarantine_review` channel uses the same cursor mechanism on its own
`provider_state` row, with `cursor_high = reviewed_at` and
`cursor_low_kind ∈ {'quarantine', 'post'}` matching the channel's tagged
payload. M1 ships both the `new_post` and `quarantine_review`
reconcilers; the `quarantine_review` reconciler landed alongside the admin
quarantine-review commands.

## 1.6 Concurrency and rate limiting

- **Per-source HTTP**: each source has a politeness window (configurable,
  default 5 minutes for RSS, shorter for higher-cadence kinds; per-kind
  values in this file's profile table or in a per-kind config). Honors
  `Retry-After` on 429/503. Uses
  `org.eclipse.microprofile.faulttolerance` for retry+backoff. D42
  threshold-based `active → failed` transition applies after N consecutive
  failures.
- **Per-source Fetcher pagination cap (single tick)**:

  | Source kind | laptop | vps | pi | remote-llm |
  |-------------|-------:|----:|---:|-----------:|
  | Bluesky / Reddit / Nitter | 5 | 5 | 2 | 5 |
  | YouTube / Odysee          | 3 | 3 | 1 | 3 |
  | RSS                       | 1 (no pagination) | 1 | 1 | 1 |

  The existence of a per-source cap is spec
  ([`spec/architecture.md`](../spec/architecture.md) §Ingest SPIs); the
  exact numbers are design and may be retuned without a spec amendment. The
  Fetcher exposes a per-tick "pagination cap hit per source" counter; a
  throttled admin notification fires once per saturation transition. The
  saturation threshold (consecutive saturated ticks before the
  notification fires) is `infochat.fetch.saturation-threshold`, default
  3 — single global tunable, operator-overridable; a non-saturated or
  failed tick resets the streak.
- **Asset Fetcher cadence**: per-`(asset, sub_verb)` refresh interval,
  defaults: CoinGecko free 60s (the free tier's documented rate cap),
  Kraken/Bitfinex public 30s. Operators can override via
  `infochat.asset.<asset>.<sub_verb>.refresh-interval`.
- **Per-user command throttle**: token bucket, default 30 commands/minute
  per user. Configurable. Returns a friendly "slow down" message on
  overflow.
- **LLM client**: bounded concurrency via Quarkus `vertx` worker pool.
  Profile defaults: `laptop=4`, `vps=2`, `pi=1`, `remote-llm=8`. Per-task
  overridable via `infochat.llm.<task>.max-concurrency`. The cap is
  per-process; D46's "one Provider may run multiple adapters" preserves
  this single shared pool.
- **Eval channel**: bounded queue size (configurable, profile-driven). If
  full, fetcher blocks (back-pressure to feed schedulers, which is the
  desired behavior — avoids unbounded memory growth on LLM slowness).
  **GAP** (audit 2026-07-27, `.scratch/doc-audit.md` §A5): neither half
  ships. The depth is SmallRye's default 128-item buffer — not configurable,
  not profile-driven (`infochat.eval.queue-size` does not exist) — and a
  full buffer makes the next `Emitter.send` **throw** `SRMSG00034` rather
  than block, so there is no back-pressure to the feed schedulers. Two
  mid-drain occurrences (2026-07-03/04) drove `OutboxRehydrator`'s per-emit
  readiness poll, which guards only its own emits. The design stands; the
  depth key and the blocking semantics are both owed.
  See [05-llm-and-embeddings.md](05-llm-and-embeddings.md) §5.7.
- **Periodic-digest worker**: count is profile-driven —
  `laptop=4`, `vps=2`, `pi=1`, `remote-llm=8` (see §1.7 table). Generation
  requests are enqueued with stagger and processed serially per worker.
  Operators can override via `infochat.digest.workers`.
  **GAP** (audit 2026-07-27, `.scratch/doc-audit.md` §A5): the key does not
  exist and no per-profile digest worker count is enforced. The design
  stands; the knob is owed.
- **Progress edits**: `ProgressNotifier` enforces
  `max(adapter.minEditInterval, 600ms)` between edits per
  `(scope, requestId)`. Excess events are coalesced; only the latest
  unsent text is transmitted at the next eligible tick. The terminal
  `finalize` is always sent regardless of the coalescing window so the
  placeholder is never left mid-flight. The 600ms floor is a deliberate
  UX choice — faster edits feel jittery and inflate transport cost
  without improving perceived responsiveness.

**Adapter configuration** is per-adapter (decision D46): each enabled
adapter has its own property namespace
(`infochat.adapters.simplex.*`, `infochat.adapters.signal.*`, etc.). Concrete
property keys live in the adapter's design notes (06-messaging.md). The
Provider does not assume a single adapter at runtime; `AdapterRegistry`
activates exactly the adapters named in the `infochat.adapters` list, which
is closed at startup (there is no per-adapter `enabled` flag).

## 1.7 Hardware profiles

The active Quarkus profile (`QUARKUS_PROFILE` / `quarkus.profile=laptop|vps|pi|remote-llm`) selects a bundle of defaults
applied at startup. Individual properties can still be set explicitly to
override.

| Setting | laptop | vps | pi | remote-llm |
|---|---|---|---|---|
| Chat model | `llama3.1:8b` Q4 | `llama3.2:3b` | `llama3.2:1b` | per provider |
| Embedding model | `nomic-embed-text` (768-d) | `nomic-embed-text` (768-d) | `nomic-embed-text` (768-d) | `nomic-embed-text` (768-d) |
| Context window | 16K | 8K | 4K | 32K |
| Auto-compress at | 12K (75%) | 6K (75%) | 3K (75%) | 24K (75%) |
| Hard limit | 15K (94%) | 7.5K (94%) | 3.8K (94%) | 30K (94%) |
| LLM concurrency | 4 | 2 | 1 | 8 |
| Vector index | `hnsw` | `hnsw` | `hnsw` | `hnsw` |
| Eval queue size | 1024 | 256 | 64 | 4096 |
| Periodic-digest workers | 4 | 2 | 1 | 8 |
| Stage 2 LLM | small judge model | small judge | tiny judge | provider judge |

Embeddings in v1 are 768-d `nomic-embed-text` with an HNSW index on **every**
profile (`infochat.embeddings.allow-model-change=false` keeps it fixed). The
per-profile embedding model / dimension / index design (pi `all-minilm` 384-d
/ IVFFlat, remote-llm 1536-d) is deferred beyond v1 — see
[05-llm-and-embeddings.md §5.5](05-llm-and-embeddings.md).

`remote-llm` means "local DB and local services, remote LLM API (OpenAI,
Anthropic, OpenRouter, etc.)" — distinct from `vps`, which means "everything
on a VPS" ([`spec/architecture.md`](../spec/architecture.md) §Hardware
profiles, decision D27).

Profile selection is logged at startup. `/status` (admin) reports the active
profile and any property overrides.

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
- For supported model + language combinations (Czech via `llama3.1:8b` and
  larger), the summarizer can be invoked with `target_language=cs` directly
  to save a round-trip. `Summarizer` exposes `LanguageAware` capability.
- Source post bodies are **never** translated. Embeddings, retrieval, and
  entity extraction always operate on the original language. Translation is
  purely a presentation-layer concern.
- Command parsing (`/summary`, `/save`, etc.) is English-only in v1.
