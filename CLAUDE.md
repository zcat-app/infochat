# infochat

Two-service Quarkus application: a news and social-media aggregator chatbot.

## Stack

- Quarkus 3.x / Java 21 / Maven (multi-module)
- PostgreSQL with `pgvector` extension
- `quarkus-langchain4j` for LLM integration
- SmallRye Reactive Messaging (in-memory channels v1, Kafka optional later)
- Quarkus Scheduler for periodic fetching and group summaries
- Pluggable adapter for messaging app (SimpleX Chat first impl)

## Two services

- **Collector Server** — fetches RSS and social feeds, runs LLM evaluation pipeline (security check, tagging, entity extraction, embedding), stores posts. **No user-facing API.**
- **Provider Server** — talks to messaging apps via pluggable adapter. Handles slash commands, chat-mode conversations, periodic group summaries. **Only user-facing component.**

## Where things live

- Spec entry point: [docs/SPEC.md](docs/SPEC.md)
- Architecture and module layout: [docs/01-architecture.md](docs/01-architecture.md)
- Database schema and TTL rules: [docs/02-schema.md](docs/02-schema.md)
- Slash commands reference: [docs/03-commands.md](docs/03-commands.md)
- Security model and quarantine: [docs/04-security.md](docs/04-security.md)
- LLM/embedding routing and prompts: [docs/05-llm-and-embeddings.md](docs/05-llm-and-embeddings.md)
- Messaging adapters: [docs/06-messaging.md](docs/06-messaging.md)
- Deployment and configuration: [docs/07-deployment.md](docs/07-deployment.md)
- Verification and testing: [docs/08-verification.md](docs/08-verification.md)

## Key conventions

- **Slash-prefix only** for commands. No "command mode" toggle.
- **Per-(user, scope) isolation** for state, memory, saves. Never leak across users or between DM and group.
- **Deterministic SQL retrieval; LLM only for ingest evaluation and prose summarization.** The set of posts a command returns must be reproducible.
- **Two admin tiers.** Bot admin (`user.is_admin`) is global; group admin (`group_membership.is_group_admin`) is per-group. Authorization runs in deterministic Java code; admin operations are NEVER exposed as LLM tools.
- **Plain-text formatting** for all bot output. Inline code in single backticks, multi-line in triple backticks; bare URLs (no markdown link syntax). Adapters expose a `supportsMarkdownCode` capability flag for richer rendering where available.
- **English by default**, per-scope `/lang <code>` opts into translation via `TranslationProvider` SPI. Source post bodies are never translated.
- **Outbox pattern** for the evaluation queue: posts are persisted with `status='RAW'` before being enqueued; a startup rehydrator re-enqueues unfinished work.
- **PostgreSQL LISTEN/NOTIFY** for collector→provider events (no Kafka dependency in v1).
- **Hardware profile** drives sizing: `infochat.profile=laptop|vps|pi|remote` picks context window, default chat/embedding models, eval concurrency, and pgvector index type (`hnsw` or `ivfflat`). Individual settings can still be overridden per-property.

## Bootstrap admin & sources

- **Bot admin**: set via `infochat.admin.contact-id` (SimpleX contact ID) in `application.properties`. On startup, an `@Startup` bean ensures that contact has `is_admin=true` (creating the user if needed). Audit log records the bootstrap. Last-admin protection: cannot revoke admin from the only admin; cannot ban self or last admin.
- **Group admin**: first user to `@mention` the bot in a new group is auto-promoted; bot admins can override with `/promote` and `/demote`.
- **Sources**: seeded from `bootstrap-sources.json` (path configurable via `infochat.bootstrap.sources-file`). Loader is idempotent: upsert by `(fetcher, url)`; the union of `tags` across all bootstrap entries seeds the controlled vocabulary. `/add-source` requires `--tags` (≥1 tag) so every source has a deterministic fallback when LLM tagging fails.

## User registration & ban

- Users self-register on first message (auto-create + welcome with `/help`).
- Bot admin can `/ban <contact>` / `/unban <contact>`. Banned users are blocked at message intake; they receive one fixed response and never reach the LLM or any DB query beyond the ban check.

## Build / run quick reference

See [docs/07-deployment.md](docs/07-deployment.md) for full details.

```bash
# build all modules
mvn clean install

# run collector
mvn -pl infochat-collector quarkus:dev

# run provider
mvn -pl infochat-provider quarkus:dev
```

A `docker-compose.yml` will start Postgres+pgvector, Ollama (default LLM), and a stubbed messaging adapter for local development.
