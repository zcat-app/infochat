# Developer Guide

How to build infochat from source and run it locally in **Quarkus dev mode**.

This guide is for people **working on the code**. If you just want to *run* a
deployment, use the wizard in **[SETUP_GUIDE.md](SETUP_GUIDE.md)** instead — it
builds and configures containers for you and never asks you to touch Maven.

For the architecture, data model, and design rationale behind the code, see the
technical map at **[docs/SPEC.md](docs/SPEC.md)**.

---

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| **JDK** | 25 | Project targets `maven.compiler.release=25` |
| **Maven** | 3.9+ | Multi-module build (or use the IDE's bundled Maven) |
| **Docker + Compose** | recent | Backing services (PostgreSQL + pgvector, Ollama) for dev mode |

The two messaging programs (`simplex-chat`, `signal-cli`) are **not** needed to
build, test, or run the services against the in-memory test adapter. You only
need them when exercising a real messaging adapter — see
[docs/spec/messaging.md](docs/spec/messaging.md).

---

## 1. Start the backing services

Dev mode runs the two services **on your host** and talks to PostgreSQL and
Ollama in containers. The `dev` Compose profile brings both up:

```bash
docker compose --profile dev up -d
```

This starts PostgreSQL + pgvector (port `5432`, loopback only) and Ollama
(port `11434`, loopback only). The application services themselves stay off in
this profile — you run them from source in step 3.

### 1a. The database

**DB passwords must match the dev defaults.** In the `%dev` profile the services
connect with the password `infochat-dev` (hardcoded in each
`application.properties`). The Compose Postgres init script
(`docker/postgres-init.sh`) creates the roles from environment variables and
**fails loudly if they are unset** rather than baking in a known secret. So
*before the first `up`*, create a repo-root `.env` (Compose reads it
automatically — do **not** commit it):

```dotenv
INFOCHAT_DB_PASSWORD=infochat-dev
INFOCHAT_COLLECTOR_PASSWORD=infochat-dev
INFOCHAT_PROVIDER_PASSWORD=infochat-dev
```

On first start that init script (run once, before any app connects) creates:

- the `infochat` owner role (`LOGIN CREATEROLE`, **not** superuser) and the
  `infochat` database;
- the two least-privilege service roles `infochat_collector` and
  `infochat_provider`;
- the `vector` (pgvector) and `pgcrypto` extensions.

The **schema itself** is created later by Flyway, which the **collector** runs on
its first start (step 3) — Postgres comes up empty until then.

These passwords exist only to match the `%dev` defaults on a throwaway local DB.
Real deployments get generated secrets from the wizard
([docs/spec/deployment.md](docs/spec/deployment.md)).

**Reset the dev database** (wipe roles, schema, and all data — e.g. after you
change the `.env` passwords or a migration mid-development): the init script only
runs on an empty data volume, so you must drop the volume, not just the
container:

```bash
docker compose --profile dev down -v   # -v removes the infochat-pgdata volume
docker compose --profile dev up -d
```

### 1b. The local LLM (Ollama)

The `dev` profile's Ollama starts empty — you must pull the models the `%dev`
config points at (all via the OpenAI-compatible endpoint `localhost:11434/v1`):

```bash
docker compose exec ollama ollama pull llama3.1:8b        # tagging, entities, summaries, chat
docker compose exec ollama ollama pull llama3.2:3b        # the ingest security/safety check
docker compose exec ollama ollama pull nomic-embed-text   # vector embeddings
```

The pull is a one-time download (several GB total) cached in the
`infochat-ollama` volume. `mvn verify` does **not** need these — the test suite
stubs the LLM — but actually running the services in dev mode does.

**Alternatives to Ollama:**

- **llama.cpp** — the `llamacpp` Compose profile runs llama.cpp's server, which
  also speaks the OpenAI-compatible API. Point the `infochat.llm.*.base-url`
  properties at it and supply a GGUF model file. See the wizard's model setup in
  [SETUP_GUIDE.md](SETUP_GUIDE.md#step-4--which-ai-model) and
  [docs/design/05-llm-and-embeddings.md](docs/design/05-llm-and-embeddings.md).
- **A remote API** — any OpenAI-compatible or Anthropic endpoint. Set the
  `infochat.llm.*.base-url`, `*.api-key`, and `*.model` properties (this is what
  the `remote-llm` profile expects).

---

## 2. Build

```bash
mvn clean install
```

Builds and tests every module. To skip the slower integration tests during an
iteration loop, use `mvn install -DskipITs` (never commit code that hasn't
passed the full suite — see step 4).

---

## 3. Run the services from source

Each service runs under Quarkus dev mode (live reload on code changes). Start
the **collector first** — it owns the Flyway database migrations; the provider
expects the schema to already exist:

```bash
# terminal 1 — collector (ingest + LLM evaluation pipeline, runs migrations)
mvn -pl infochat-collector quarkus:dev

# terminal 2 — provider (messaging + slash commands + chat)
mvn -pl infochat-provider quarkus:dev
```

Both pick up the `%dev` profile automatically under `quarkus:dev`.

---

## 4. Run the full test suite

A change is not done when its own new tests pass — run the whole suite from the
repo root and report regressions:

```bash
mvn verify
```

Quarkus integration tests bind an OS-assigned ephemeral port
(`quarkus.http.test-port=0`), so two suites reaching their IT phase at the same
moment won't collide.

---

## Module layout

| Module | Role |
|---|---|
| `infochat-core` | Shared domain types, persistence, and cross-service code |
| `infochat-ssrf` | SSRF-guarded HTTP client (egress hardening) |
| `infochat-llm-adapter` | Pluggable LLM / embedding SPI (Ollama, OpenAI-compatible, Anthropic) |
| `infochat-messaging-adapter` | Pluggable messaging SPI (SimpleX, Signal, in-memory) |
| `infochat-collector` | Headless ingest service — fetch, evaluate, store; owns migrations |
| `infochat-provider` | The only user-facing service — messaging, commands, digests |

When you need to survey several of these to understand an API surface, prefer a
read-only pass over each module's `src/main/java`.

---

## Ports (all loopback in dev)

| Service | Port |
|---|---|
| PostgreSQL | `5432` |
| Ollama | `11434` |
| Collector health | `8080` |
| Provider health | `8081` |

Nothing binds beyond `127.0.0.1` — see
[SETUP_GUIDE.md](SETUP_GUIDE.md#ports-and-the-loopback-rule) for the rule and
why it must stay that way.

---

## Troubleshooting

### A VPN can silently break localhost / container traffic

**This is the first thing to check** when container, database, or Testcontainers
connections mysteriously **reset or time out** even though Docker is running and
the containers look healthy. Some VPN clients intercept loopback / local-subnet
traffic, so the host can't reach a container it just started on `127.0.0.1`.

It is easy to lose hours chasing a `docker-proxy`, firewall (`ufw`), or DNS
hypothesis when the real cause is the VPN. If anything local refuses to connect,
**disable the VPN (or exclude loopback and the Docker bridge subnet) and retry
before debugging anything else.**

### The test suite uses Quarkus Dev Services (Testcontainers)

`%test` doesn't use the Compose database — Quarkus **Dev Services** starts a
throwaway `pgvector/pgvector:pg16` container (via Testcontainers) for the run and
tears it down afterward. So the tests need a working Docker daemon, but **not**
the `dev` Compose stack or the `.env` passwords.

If Dev Services fails to start a container, check, in order:

- Docker is running and your user can reach the daemon (`docker ps` works).
- The VPN issue above — Testcontainers connects to the container over loopback.
- Nothing else is already bound to the ports the run needs (below).

### Known flaky integration tests

A few ITs have rare, timing-related flakes that are **not** caused by your
change — retry the run once before investigating, and only dig in on a **second**
failure against unchanged code:

- `OutboxRehydratorPaginationIT` — an occasional `SRMSG00034` back-pressure race
  in the in-memory reactive-messaging emitter.
- `Stage1WatchdogIT` — a marginal timing assertion that can trip under load.

<!-- TODO: document the unresolved Testcontainers issue here once its exact
     symptom/trigger is confirmed (see DEVELOPER.md follow-up). -->

---

## Where to go next

- **Ready to contribute a change?** See **[CONTRIBUTING.md](CONTRIBUTING.md)** —
  the workflow, conventions, and a worked example of adding a command.
- **[docs/SPEC.md](docs/SPEC.md)** — the technical map (architecture, schema,
  commands, security model, design notes).
- **[docs/spec/deployment.md](docs/spec/deployment.md)** and
  **[docs/design/07-deployment.md](docs/design/07-deployment.md)** — full
  developer and operational detail, profiles, and config wiring.
- **[CLAUDE.md](CLAUDE.md)** — engineering rules, coding style, and the M1
  ticket workflow that govern changes to this repo.
