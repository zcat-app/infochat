# infochat

Two-service Quarkus application: a news and social-media aggregator chatbot.

## Stack

- Quarkus 3.x / Java 21 / Maven (multi-module)
- PostgreSQL with `pgvector` extension
- `quarkus-langchain4j` for LLM integration
- SmallRye Reactive Messaging (in-memory channels v1, Kafka optional later)
- Quarkus Scheduler for periodic fetching and group digests
- Pluggable adapter for messaging apps (SimpleX + Signal in v1; one Provider can run any non-empty subset of them simultaneously, decision D46)

## Two services

- **Collector Server** — fetches RSS and social feeds, runs LLM evaluation pipeline (security check, tagging, entity extraction, embedding), stores posts. **No user-facing API.**
- **Provider Server** — talks to messaging apps via one or more pluggable adapters (decision D46). Handles slash commands, chat-mode conversations, periodic group digests. **Only user-facing component.**

## Where things live

- Spec entry point (the map): [docs/SPEC.md](docs/SPEC.md)
- Cross-cutting decisions log: [docs/spec/decisions.md](docs/spec/decisions.md)
- Architecture (service split, pipelines, principles): [docs/spec/architecture.md](docs/spec/architecture.md)
- Security model (threat model, trust boundaries, failure handling): [docs/spec/security.md](docs/spec/security.md)
- Data model (entities, invariants — no DDL): [docs/spec/schema.md](docs/spec/schema.md)
- Commands and chat (surface, catalogue, permissions): [docs/spec/commands.md](docs/spec/commands.md)
- LLM and embeddings (SPI, routing, translation, determinism boundary): [docs/spec/llm.md](docs/spec/llm.md)
- Messaging adapters (contract, capabilities, progress): [docs/spec/messaging.md](docs/spec/messaging.md)
- Asset commands (`/zcash`, `/monero` etc., price/market data): [docs/spec/commands.md](docs/spec/commands.md) §"Asset commands" + design [docs/design/10-asset-commands.md](docs/design/10-asset-commands.md)
- Deployment and configuration (operator inputs, bootstrap, runtime): [docs/spec/deployment.md](docs/spec/deployment.md)
- Verification strategy (what the test suite must prove): [docs/spec/verification.md](docs/spec/verification.md)
- MVP slice (smallest end-to-end build, design-tier): [docs/design/00-mvp.md](docs/design/00-mvp.md)

**Implementation details** (DDL, class names, package layout, property keys,                                                                                                                                                                          
retry counts, regex strings, per-profile values) live under                                                                                                                                                                                           
[docs/design/](docs/design/) — one file per spec section. Design notes carry                                                                                                                                                                          
a "Status: design notes, not spec" banner and may change without a spec                                                                                                                                                                               
amendment.

## Key conventions

- **Slash-prefix only** for commands. No "command mode" toggle.
- **Per-(user, scope) isolation** for state, memory, saves. Never leak across users or between DM and group.
- **Deterministic SQL retrieval; LLM only for ingest evaluation and prose summarization.** The set of posts a command returns must be reproducible.
- **Two admin tiers.** Bot admin (`user.is_admin`) is global; group admin (`group_membership.is_group_admin`) is per-group. Authorization runs in deterministic Java code; admin operations are NEVER exposed as LLM tools.
- **Plain-text formatting** for all bot output. Inline code in single backticks, multi-line in triple backticks; bare URLs (no markdown link syntax). Adapters expose a `supportsCodeFormatting` capability flag for richer rendering where available; v1 adapters additionally assert `supportsMarkdownLinks=false` so the rendering surface cannot silently widen.
- **English by default**, per-scope `/lang <code>` opts into translation via `TranslationProvider` SPI. Source post bodies are never translated.
- **Outbox pattern** for the evaluation queue: posts are persisted with `status='RAW'` before being enqueued; a startup rehydrator re-enqueues unfinished work.
- **PostgreSQL LISTEN/NOTIFY** for collector→provider events (no Kafka dependency in v1).
- **Hardware profile** drives sizing: `infochat.profile=laptop|vps|pi|remote` picks context window, default chat/embedding models, eval concurrency, and pgvector index type (`hnsw` or `ivfflat`). Individual settings can still be overridden per-property.
- **Asset commands are not posts.** `/zcash`, `/monero` and future per-asset commands store snapshots in a dedicated `price_snapshot` table outside the ingest pipeline — no Stage 1/2, no tagging, no embedding. Every reply names its data source   
  and includes the source URL bare (per-source ToS attribution). Public no-auth endpoints only in v1.

## Bootstrap admin & sources

- **Bot admin**: configured **per enabled adapter** in `application.properties` (one bootstrap admin contact id per adapter; the property is keyed by adapter — concrete keys in design notes — and is **optional per adapter** as long as the union across enabled adapters is non-empty). Each value is parsed by its own adapter (SimpleX queue address, Signal ACI, etc.). On startup, an `@Startup` bean ensures, for every adapter that has a configured admin, that the contact exists with `is_admin=true` (creating the user if needed). Audit log records each bootstrap. `/grant-admin` and `/revoke-admin` are scoped to the inbound adapter; last-admin protection counts `is_admin=true` rows globally across adapters (cannot leave the deployment with zero admins; cannot ban self or last admin). See `docs/spec/security.md` §Per-adapter admin threat profile for the SimpleX-vs-Signal threat surface and operator-side mitigations.
- **Group admin**: first user to `@mention` the bot in a new group is auto-promoted; bot admins can override with `/promote` and `/demote`.
- **Sources**: seeded from `bootstrap-sources.json` (path configurable via `infochat.bootstrap.sources-file`). Loader is idempotent: upsert by `(kind, identifier)` — `kind` is the source type (`rss`, `bluesky`, `nostr`, etc.), `identifier` is the URL for HTTP-shaped sources or the filter spec for stream sources (decision D38). The union of `tags` across all bootstrap entries seeds the controlled vocabulary. `/add-source` requires `--tags` (≥1 tag) so every source has a deterministic fallback when LLM tagging fails.

## User registration & ban

- DM access requires an invite code issued by a bot admin (D44). Group access registers on first non-banned `@mention`. All newly registered users start in slow-start probation (D45).
- Bot admin can `/ban <contact>` / `/unban <contact>`. Banned users are blocked at message intake; they receive one fixed response and never reach the LLM or any DB query beyond the ban check.

## Build / run quick reference

See [docs/spec/deployment.md](docs/spec/deployment.md) for the spec-level overview and [docs/design/07-deployment.md](docs/design/07-deployment.md) for full operational details.

```bash
# build all modules
mvn clean install

# run collector
mvn -pl infochat-collector quarkus:dev

# run provider
mvn -pl infochat-provider quarkus:dev
```

A `docker-compose.yml` will start Postgres+pgvector, Ollama (default LLM), and the in-memory test adapter for local development. Production deployments enable one or more of SimpleX / Signal in the same Provider; the in-memory adapter is exercised in a separate test-time deployment shape and never alongside production adapters (decision D46, `docs/spec/deployment.md` §Deployment scenarios).
