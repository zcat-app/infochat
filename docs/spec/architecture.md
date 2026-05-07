# Architecture

## Purpose

infochat is split into two cooperating services that share a single database.
This file describes *why* the split exists, the responsibilities of each side,
and the contracts between them. Concrete module names, package layout, startup
ordering, and runtime tuning live in `docs/design/01-architecture.md`.

## Service split

- **Collector** is headless. It fetches feeds, runs the evaluation pipeline,
  and stores posts. No user can address it directly.
- **Provider** is the only user-facing component. It owns the messaging
  adapter, the command router, the chat agent, the periodic-summary scheduler,
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

- **Push.** Postgres `LISTEN/NOTIFY` delivers ingest events (`new_post`,
  quarantine state changes) to live Provider instances.
- **Catch-up.** A high-water mark on the Provider side guarantees correctness
  across restarts, since `LISTEN/NOTIFY` is best-effort and does not buffer
  events for disconnected listeners. NOTIFY is the latency optimization; the
  high-water mark is the correctness guarantee.

No external message broker in v1. Replacing the in-process channels with one
later is a swap, not a rewrite (see decisions D3, D4).

## Ingest SPIs

The Collector exposes **two** ingest SPIs, separated because their
lifecycles are fundamentally different (decision D38). Both feed the
same outbox via the same normalized-post shape; downstream of the
outbox, no other code in the Collector can tell which SPI a post came
from.

- **`Fetcher`** — *polled, request/response*. The scheduler ticks on a
  per-source interval; the fetcher issues an HTTP `GET`/`HEAD`, parses
  the response, and returns a list of normalized posts. Used by RSS,
  Bluesky, Nitter, Reddit, YouTube, Odysee. The fetcher is stateless
  between ticks; "what's new since last time" is a query against
  `posts`, not in-memory state.
- **`StreamSource`** — *long-lived, event-driven*. Started once at
  Collector startup; runs as a supervised worker that maintains its
  own connection (typically `wss://`, but the SPI is named around
  event streams, not websockets, so future non-websocket transports
  fit without rename). The implementation owns connection lifecycle,
  reconnect with backoff, per-source trust verification (e.g. Nostr
  signature checks), and dedup by stable upstream id **before** the
  outbox. Used by Nostr in v1.

Picking between the two is deterministic: if the source is "fetch a
URL on a tick," it's a `Fetcher`; if the source is "subscribe and
receive events as they happen," it's a `StreamSource`. Sources MUST
NOT straddle.

**Source identity** (decision D38) is `(kind, identifier, config)`:

- `kind` — the ingest type (`rss`, `bluesky`, `nostr`, …). Picks both
  the SPI shape and the implementation.
- `identifier` — the URL for HTTP-shaped sources, the filter spec for
  stream sources. Together with `kind` it forms the unique key for
  `source` rows.
- `config` — opaque per-kind JSON. Holds Nostr's relay list, kinds
  filter, and any other per-source tuning that doesn't fit the
  identifier.

**Cross-source dedup** is the implementation's responsibility, not the
outbox's. For stream sources where the same event can arrive from N
relays (Nostr), the implementation MUST dedup by stable upstream id
before enqueue; one event = one `posts` row regardless of how many
relays delivered it.

**Per-relay (or per-endpoint) degradation** is a `StreamSource`
commitment: a single misbehaving relay (slow, spamming, repeatedly
disconnecting, returning malformed events) MUST NOT block the
StreamSource. The implementation marks the bad relay as unusable for a
cooldown window and continues on the rest. Concrete failure threshold
and cooldown values live in design notes.

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

A named profile (`laptop`, `vps`, `pi`, `remote`) drives a bundle of
defaults: context-window size, default chat / embedding model, eval
concurrency, vector-index choice, summary worker count, eval queue depth.
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
- Diagrams of every code path

If a question is "what does this number need to be on a Pi?", the answer is
in `docs/design/`. If a question is "do we have a Collector / Provider
split?", the answer is here.
