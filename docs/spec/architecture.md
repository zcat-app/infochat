# Architecture

## Purpose

infochat is split into two cooperating services that share a single database.
This file describes *why* the split exists, the responsibilities of each side,
and the contracts between them. The stack itself (Quarkus, PostgreSQL with
pgvector, LangChain4j, Java 25, Maven multi-module) is fixed by decision
D1. Concrete module names, package layout, startup ordering, and runtime
tuning live in `docs/design/01-architecture.md`.

## Service split

The two-service split is decision D2. Concretely:

- **Collector** is headless. It fetches feeds, runs the evaluation pipeline,
  and stores posts. No user can address it directly.
- **Provider** is the only user-facing component. It owns the messaging
  adapter, the command router, the chat agent, the periodic-digest scheduler,
  and all per-user state (subscriptions, saves, memory, preferences).

The split exists for three reasons:

1. **Blast radius.** A compromised or malfunctioning fetcher cannot reach
   users directly; everything user-visible passes through Provider's
   deterministic authorization layer first.
2. **Independent scaling.** Ingest load (feed polling, LLM evaluation) and
   user load (chat, summaries) move on different schedules and benefit from
   independent process boundaries.
3. **Restartable Collector.** The Collector can be redeployed without
   dropping user conversations.

## Inter-service communication

Collector and Provider communicate only through the shared database:

- **Push.** Postgres `LISTEN/NOTIFY` delivers ingest events to live
  Provider instances. The **closed list of v1 channels** is:
  - `new_post` — fires on `post.status → READY`. Payload carries
    the cursor key `(ready_at, post_id)` only. Correctness
    mechanism: high-water mark on Provider side (see Catch-up
    below).
  - `new_price_snapshot` — fires on a successful Fetcher write to
    `price_snapshot`. Payload carries `(asset, sub_verb)`.
    Correctness mechanism: best-effort; the Provider's in-process
    cache is **flushed entirely on every Postgres reconnect** so
    a missed NOTIFY during a connection blip cannot serve a stale
    row past the reconnect (`commands.md` §Asset commands —
    Provider/Collector contract).
  - `quarantine_review` — fires on quarantine state-machine
    transitions reachable by Provider (`PENDING` insert,
    `BENIGN_CLOSED`, `APPROVED`, `REJECTED`) and on a `post.status
    → NEEDS_REVIEW` transition (`security.md` §Re-evaluation job).
    Payload is a **tagged shape** `(target_kind, target_id,
    new_status)` where `target_kind ∈ {'quarantine', 'post'}`
    discriminates: a quarantine state-machine move emits
    `('quarantine', quarantine_id, new_status)`; the
    `post.status → NEEDS_REVIEW` transition emits
    `('post', post_id, 'NEEDS_REVIEW')`. The discriminator is
    required so a single listener can route both event families
    without ambiguity. Correctness mechanism: high-water mark
    on Provider side, keyed on the channel's monotonic cursor
    `(reviewed_at, target_kind, target_id)` — `reviewed_at` is the
    quarantine row's `updated_at` for `'quarantine'` events and the
    `post.status_changed_at` for `'post'` events; the
    `(target_kind, target_id)` tail breaks ties so two events with
    identical `reviewed_at` cannot lose one to the cursor.
    **Consumer behavior:** the Provider drives
    the throttled admin notifier (`security.md` §Failure handling)
    on `PENDING` inserts and on `→ NEEDS_REVIEW` transitions —
    these are the two transitions that require admin attention.
    `BENIGN_CLOSED`, `APPROVED`, and `REJECTED` transitions
    advance the Provider's cursor (so the high-water mark stays
    accurate) but produce no user-visible effect in v1; they are
    on the channel because the channel's contract is "all
    quarantine state-machine moves visible to the Provider role,"
    not "only the ones the Provider acts on" — keeping the
    channel comprehensive lets v2 add behavior to a transition
    without a schema-level NOTIFY change.
  Adding a channel is a spec amendment.
- **Payload-size bound.** NOTIFY payloads are bounded to the
  cursor key for the channel; large payloads MUST NOT be
  transmitted via NOTIFY. (Postgres NOTIFY has an 8KB hard
  ceiling, but the spec-level rule is "cursor only" so a future
  channel cannot grow the payload beyond what fits in a cursor
  shape.) The receiving side reads the row from its base table
  for the actual data — NOTIFY is purely the wake-up signal.
- **Catch-up.** A high-water mark on the Provider side guarantees correctness
  across restarts, since `LISTEN/NOTIFY` is best-effort and does not buffer
  events for disconnected listeners. NOTIFY is the latency optimization; the
  high-water mark is the correctness guarantee. For the `new_post`
  channel the cursor is the `(ready_at, post_id)` pair (not
  `ready_at` alone) so two posts with identical `ready_at` cannot
  lose one to the cursor. The catch-up query
  is `WHERE (ready_at, post_id) > (:last_ready_at, :last_post_id)
  ORDER BY ready_at, post_id`; the high-water mark advances both fields
  **in the same DB transaction** as the side effect it triggers,
  making processing idempotent. The advance uses compare-and-swap
  (`schema.md` §Provider state) so a slow processor cannot roll back
  a fast one's mark; a duplicate NOTIFY or a repeated catch-up pass
  for the same row produces no additional side effect. The
  `quarantine_review` channel uses the same cursor mechanism on
  its own `provider_state` row, with channel-specific cursor
  semantics (per-channel `provider_state` keying — the row shape
  is channel-agnostic, the interpretation is per-channel;
  `schema.md` §Operational).

No external message broker in v1. Replacing the in-process channels with one
later is a swap, not a rewrite (see decisions D3, D4).

## Deployment topology (v1)

v1 runs **exactly one Collector and exactly one Provider** against a shared
Postgres (decision D41). The `LISTEN/NOTIFY` + outbox + high-water-mark design
is correct for that topology; running more than one of either service is **not
supported in v1** and will produce duplicate fetches, duplicate periodic
digests, and contention on `provider_state`. Multi-instance horizontal scaling
is a v2 spec amendment that requires per-source leasing (or partitioned
fetcher assignment), per-group digest leasing, and a coordination story for
the high-water mark.

**Enforced via Postgres advisory lock.** Each service acquires a
named `pg_advisory_lock` at startup (`infochat.collector` and
`infochat.provider`, hashed to int8 per Postgres convention). A
second instance attempting to acquire the lock fails fast with a
fatal log message that points at the running instance's host
identifier — recorded in a heartbeat row updated every N seconds
(value in design notes). The lock is released on graceful shutdown;
on hard kill the heartbeat staleness eventually invalidates the
prior holder. This makes "exactly one" an enforced invariant, not
a policy.

The "independent scaling" benefit of the service split (above) is about
deploying the two services on different hosts with different resource shapes,
not about running N copies of either service. Operators who need more
throughput in v1 pick a heavier hardware profile.

## Ingest SPIs

The Collector exposes **two** ingest SPIs, separated because their
lifecycles are fundamentally different (decision D38). Both feed the
same outbox via the same normalized-post shape; downstream of the
outbox, no other code in the Collector can tell which SPI a post came
from.

- **`Fetcher`** — *polled, request/response*. The scheduler ticks on a
  **per-kind, profile-driven interval** (each `source.kind` carries
  its own poll cadence — RSS, Bluesky, Reddit etc. each have their
  own value in design notes); the fetcher issues an HTTP `GET`/`HEAD`,
  parses the response, and returns a list of normalized posts. Used
  by RSS, Bluesky, Nitter, Reddit, YouTube, Odysee. v1 has **no
  per-source interval override** — the source row carries no
  `refresh_interval` column; an operator who wants a faster cadence
  for one feed adjusts the per-kind profile value, which applies to
  every source of that kind. Per-source override is a v2 candidate.
  The fetcher is stateless between ticks; "what's new since last
  time" is a query against `posts`, not in-memory state. **Pagination.** When the upstream
  exposes a paginated feed (Bluesky, Reddit, Nitter, etc.), the
  Fetcher paginates **within a single tick** up to a per-source
  max-page cap (profile-driven, value in design notes). The cap
  bounds the worst-case per-tick work; backlog beyond the cap is
  picked up on subsequent ticks via the same "what's new since last
  time" query against `posts`. RSS feeds typically have no pagination
  and are a single request per tick.

- **Output type.** A Fetcher is shaped around what it produces. The
  default output is normalized posts that flow into the post outbox.
  Asset Fetchers (decision D39) produce `price_snapshot` rows
  instead and write **directly** to the `price_snapshot` table —
  they never hit the post outbox, never go through Stage 1/2,
  tagger, or embedding. The Fetcher SPI carries an output-type
  discriminator so the Collector's per-tick dispatch routes the
  result to the right sink. There are no `source` rows for asset
  feeds: scheduling, status, and per-`(asset, sub_verb)` enable
  state live in **`asset_config`** (`schema.md` §Operational, the
  asset-side parallel to `source`). The Collector's asset
  scheduler reads `asset_config` rows where `enabled = true AND
  status = 'active'`; D42's per-source failure-counter model
  applies to `asset_config.consecutive_failures` and the
  `active → failed` transition on threshold crossing. The
  Provider has `SELECT` on both `asset_config` and `price_snapshot`.
- **`StreamSource`** — *long-lived, event-driven*. Started once at
  Collector startup; runs as a supervised worker that maintains its
  own connection (typically `wss://`, but the SPI is named around
  event streams, not websockets, so future non-websocket transports
  fit without rename). The implementation owns connection lifecycle,
  reconnect with backoff, per-source trust verification (e.g. Nostr
  signature checks), and dedup by stable upstream id **before** the
  outbox. Delivery to the outbox is **at-least-once**: an event is
  written to the outbox before the implementation considers it processed;
  duplicate deliveries (same event-id from multiple relays, or reconnect
  replays) are deduplicated by stable upstream id.

  **Asynchronous startup.** StreamSource startup is asynchronous:
  the supervised worker is registered at Collector boot and begins
  its reconnect loop in the background. **A relay unreachable at
  boot does not fail Collector startup or the readiness probe** —
  it surfaces as the ordinary per-relay degradation path
  (cooldown, throttled admin notification on the all-relays-bad
  transition). The Collector readiness probe goes healthy when
  the scheduler has accepted the StreamSource registration, not
  when every relay is connected. This is the StreamSource-specific
  exception to `deployment.md` §Bootstrap behavior's "bean failure
  during startup refuses the service start" default — connection
  failures are the normal failure mode for this SPI and gating
  startup on every configured relay would mean a single dead
  relay blocks the deployment.

  **Drain on shutdown.** On graceful shutdown the StreamSource
  implementation MUST aggressively flush in-flight events to the
  outbox before acknowledging the shutdown signal. Events not
  drained within a profile-driven hard timeout are dropped and
  **not guaranteed to reappear** — the previous "events will
  reappear on the next relay connection" wording was protocol-false:
  Nostr relays do not universally replay history. On reconnect, the
  implementation issues `since=last_persisted_event_at` per relay;
  relays that support `since` filters will replay missed events,
  relays that do not may produce permanent gaps. A per-relay
  "events lost on shutdown" counter is exposed for operator
  monitoring. **Non-graceful shutdown** (OOM, SIGKILL): same
  outcome — events in-flight at the SIGKILL moment are lost; the
  counter increments based on the gap between last-acknowledged
  and re-delivered. Used by Nostr in v1.

Picking between the two is deterministic: if the source is "fetch a
URL on a tick," it's a `Fetcher`; if the source is "subscribe and
receive events as they happen," it's a `StreamSource`. Sources MUST
NOT straddle.

**Source identity** (decision D38). The unique key is `(kind,
identifier)`. The per-kind `config` block is a mutable value
attached to that key. **In v1, `config` mutation is restart-only**:
the bootstrap loader runs at Collector startup, is idempotent on
`(kind, identifier)`, and updates `config` in place on existing
rows. There is no v1 chat command that mutates `config` (no
`/source-config`, no `--config` flag on `/add-source`); operators
who need to rotate Nostr relays edit `bootstrap-sources.json` and
restart the Collector. Runtime mutation via a chat command is a v2
candidate. `config` is **not** part of the unique key — making it
part of the key would cause every relay-list edit to create a
duplicate `source` row at the next bootstrap reload.

- `kind` — the ingest type (`rss`, `bluesky`, `nostr`, …). Picks both
  the SPI shape and the implementation.
- `identifier` — the URL for HTTP-shaped sources, the filter spec for
  stream sources. Together with `kind` it forms the unique key.
  **Nostr filter-spec identifiers are canonicalized before unique-key
  comparison**: JSON object keys are sorted lexicographically and the
  JSON is compact (no extra whitespace). Two filter specs that are
  semantically identical but differ only in key order or whitespace
  are treated as the same identifier and do not create a duplicate
  source row. The canonicalization rule is applied at bootstrap load
  time and at `/add-source` parse time; the stored identifier is
  always the canonical form.
- `config` — opaque per-kind JSON value (mutable). Holds Nostr's
  relay list, kinds filter, and any other per-source tuning that
  doesn't fit the identifier.

**Cross-source dedup** is the implementation's responsibility, not the
outbox's. For stream sources where the same event can arrive from N
relays (Nostr), the implementation MUST dedup by stable upstream id
before enqueue; one event = one `posts` row regardless of how many
relays delivered it.

**Kind-6 cross-source linking** uses the original event's
`upstream_identifier` (the Nostr event id — a SHA-256 hash of the
event's canonical JSON) as the join key for the `post_reference` edge.
The link is written as `(kind-6 post UID) →repost→ (original
upstream_identifier)`, resolved to a post UID if and when the
original event is also seen and stored. The derived post UID of the
original may not yet exist at the time the kind-6 is processed
(the original may arrive later or never); the `upstream_identifier`
is the stable, protocol-level key that survives this ordering.
Implementations MUST NOT use the derived UID as the join key in the
`post_reference` edge for kind-6 reposts — a UID-based join would
fail for cross-relay deliveries where the original arrives after the
repost.

**Per-relay (or per-endpoint) degradation** is a `StreamSource`
commitment: a single misbehaving relay (slow, spamming, repeatedly
disconnecting, returning malformed events) MUST NOT block the
StreamSource. The implementation marks the bad relay as unusable for a
cooldown window and continues on the rest. Concrete failure threshold
and cooldown values live in design notes.

**All relays in cooldown.** When every configured relay is in
cooldown the StreamSource waits until the **earliest cooldown
expires** rather than tight-looping reconnect attempts; an admin
notification fires once per all-relays-bad transition (throttled
per `(channel, error_class)` like every other admin notification).
The notification's recovery counterpart fires when the first relay
returns to healthy.

**Absolute cycle cap → terminal failed state.** After a
profile-driven number of consecutive all-relays-bad cycles (cap
value in design notes), the StreamSource transitions to a **terminal
`failed` state** and stops attempting reconnects entirely. The
Collector emits a one-time admin notification distinct from the
per-cycle throttled notification — e.g., "StreamSource for source
`<id>` permanently stopped: all-relays-bad cycle cap exhausted."
An operator must explicitly re-enable the source via
`/source-enable <id>` to restart the StreamSource. This prevents
an infinite reconnect storm against a permanently unreachable relay
set and bounds outbound connection cost when the operator has not
noticed the source is permanently dead.

**Pagination cap saturation.** Fetchers expose a per-tick
"pagination cap hit per source" counter. When a single source
consistently saturates the cap across multiple ticks (operators
choose the threshold; design notes), a throttled admin notification
fires once per saturation transition — the source is correctly
producing posts, just faster than a single tick can drain, and
operators may want to lift the per-source cap or increase the
fetch frequency.

## Pipelines

Two pipelines must be reasoned about end-to-end:

- **Ingest** (Collector) — Source → Fetcher *or* StreamSource → persist
  as `RAW` → enqueue → Stage 1 → (Stage 2 only on hits) → tagger →
  entity extraction → embedding → mark `READY` → NOTIFY. Each stage
  has its own failure policy (see `security.md` and decision D22). The
  persist-before-enqueue step is the outbox: a startup rehydrator
  re-enqueues anything left in `RAW` after a crash.
- **User request** (Provider) — Adapter → identity resolution → ban check →
  parse → permission check → execute. Slash commands run deterministic SQL
  (and may invoke the summarizer LLM). Chat-mode messages run the chat agent
  with a strict, read-only tool surface (see `security.md`).

`security.md` is the source of truth for the *trust path*. This file
intentionally describes the data flow without restating the trust rules.

## Architectural principles

1. **Determinism boundary.** Retrieval is always SQL; LLMs only generate
   prose or extract structured fields at ingest. The set of posts a command
   returns is reproducible.
2. **Outbox + LISTEN/NOTIFY + high-water mark.** Postgres provides durability
   and push semantics without an external broker.
3. **No LLM in the trust path.** Authorization, admin actions, ban checks,
   quarantine approval are deterministic Java. The LLM is downstream of every
   security decision.
4. **Per-(user, scope) isolation by construction.** Every row that holds user
   state is keyed by a scope tuple; queries always filter on it. Cross-user
   leakage is a schema-level invariant, not a query-level convention.
5. **TTL by partitioning, not DELETE.** Post-derived bulk data ages out via
   partition drops. No row-level deletes, no index bloat.
6. **Adapters are SPIs.** LLM, embedding, messaging, translation,
   `Fetcher`, and `StreamSource` integrations are pluggable interfaces.
   Test doubles slot in for CI.
7. **Progress is a presentation-layer concern.** Long-running handlers
   publish stage events to a cross-cutting notifier; business logic does not
   reference the messaging adapter or the concept of an editable message.

## Hardware profiles

A named profile (`laptop`, `vps`, `pi`, `remote-llm`) drives a bundle of
defaults: context-window size, default chat / embedding model, eval
concurrency, vector-index choice, summary worker count, eval queue depth.
The `remote-llm` profile is named to distinguish it from the `vps` profile:
`vps` is "run everything on a VPS"; `remote-llm` is "local DB and services,
remote LLM API (OpenAI, Anthropic, OpenRouter, etc.)".
The profile concept is the spec-level commitment; the specific values per
profile are tuning and live in `docs/design/01-architecture.md` §1.7.

The intent: an operator picks one profile and gets a working system. Anything
they need to override is a single property change, not a code change.

## What lives in design notes

- Maven module names and package layout
- Bean startup ordering and `@Priority` numbers
- Concrete property keys (`infochat.*`)
- Per-profile numeric values (context window sizes, queue depths, worker
  counts, retry counts, intervals)
- Per-source Fetcher max-page cap (the existence of a single-tick
  pagination cap is spec; the exact value is design)
- StreamSource graceful-shutdown drain timeout
- Diagrams of every code path

If a question is "what does this number need to be on a Pi?", the answer is
in `docs/design/`. If a question is "do we have a Collector / Provider
split?", the answer is here.
