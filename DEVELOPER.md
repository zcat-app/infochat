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

There are two ways to run infochat from source in dev, and they need different
backing services:

- **Inner loop — one service at a time (no Compose DB needed).** Bare
  `./mvnw -pl <module> quarkus:dev` runs under the `%dev` profile, which declares
  **no** JDBC URL. Quarkus reacts to that by starting a throwaway **Dev
  Services** pgvector container automatically — a fresh, random-port database
  per run. Zero DB setup, nothing to configure; ideal for editing one module
  with live reload. Each module gets its **own** ephemeral DB, so this is not a
  full two-service bot (see step 3).
- **Full two-service bot — collector + provider sharing one DB.** Here you point
  both host services at the loopback Compose PostgreSQL so the provider sees the
  schema the collector migrates. This is the mode that needs the `dev` Compose
  profile and a repo-root `.env`.

Bring up the backing containers for the full run (and for any feature that calls
the LLM, in either mode):

```bash
docker compose --profile dev up -d
```

This starts PostgreSQL + pgvector (port `5432`, loopback only), which has no
Compose profile and so comes up on any `docker compose up`, plus Ollama
(port `11434`, loopback only), which the `dev` profile adds. The application
services themselves stay off — they belong to the `prod` profile, so you run
them from source in step 3.

### 1a. The database (only for the full two-service run)

The inner loop needs nothing here — Dev Services owns its throwaway DB. The
Compose PostgreSQL and the `.env` below matter only for the **full two-service
run** (step 3b).

**Where the passwords are consumed.** The Compose Postgres init script
(`docker/postgres-init.sh`) reads the role passwords from environment variables
**at container init** and creates the roles, **failing loudly if any are unset**
rather than baking in a known secret. So *before the first `up`*, create a
repo-root `.env` (Compose reads it automatically — do **not** commit it):

```dotenv
INFOCHAT_DB_PASSWORD=infochat-dev
INFOCHAT_COLLECTOR_PASSWORD=infochat-dev
INFOCHAT_PROVIDER_PASSWORD=infochat-dev
```

The values are `infochat-dev` to match the `%dev` profile's hardcoded datasource
passwords (each `application.properties`): in the full two-service run the host
JVMs connect to the Compose Postgres with those `%dev` passwords, so they must
equal what the init script set. In the inner loop the host JVM instead talks to
its own Dev Services container under trust auth and consumes neither the Compose
Postgres nor these passwords.

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
`infochat-ollama` volume. `./mvnw verify` does **not** need these — the test suite
stubs the LLM — but actually running the services in dev mode does.

**Alternatives to Ollama:**

- **llama.cpp** — the `llamacpp` and `llamacpp-embeddings` Compose profiles run
  llama.cpp's servers (one for generation, one for embeddings), which also speak
  the OpenAI-compatible API. Point the `infochat.llm.*.base-url`
  properties at it and supply a GGUF model file. See the wizard's model setup in
  [SETUP_GUIDE.md](SETUP_GUIDE.md#step-4--which-ai-model) and
  [docs/design/05-llm-and-embeddings.md](docs/design/05-llm-and-embeddings.md).
- **A remote API** — any OpenAI-compatible or Anthropic endpoint. Set the
  `infochat.llm.*.base-url`, `*.api-key`, and `*.model` properties (this is what
  the `remote-llm` profile expects).

---

## 2. Build

```bash
./mvnw clean install
```

Builds and tests every module. To skip the slower integration tests during an
iteration loop, use `./mvnw install -DskipITs` (never commit code that hasn't
passed the full suite — see step 4).

---

## 3. Run the services from source

Each service runs under Quarkus dev mode (live reload on code changes). How you
run depends on which of the two modes from step 1 you want.

### 3a. Inner loop — one service, throwaway DB

For iterating on a single module:

```bash
./mvnw -pl infochat-collector quarkus:dev   # or infochat-provider
```

This activates `%dev`, which declares no JDBC URL, so Quarkus spins a throwaway
Dev Services pgvector container — no Compose Postgres or `.env` needed. Two
consequences: each module gets its **own** ephemeral DB (so the provider does
**not** see the collector's schema — this is not a full bot), and the provider
additionally needs an adapter (see 3b) because `infochat.adapters` is
`%test`-only and `%dev` leaves it empty.

### 3b. Full two-service bot — shared Compose DB

To run both services against the **one** Compose PostgreSQL (step 1), pass the
datasource URL explicitly so they bypass Dev Services and share the schema. The
`%dev` profile already supplies the matching passwords (`infochat-dev`), so you
override only the URLs. Start the **collector first** — it owns the Flyway
migrations; the provider expects the schema to already exist.

```bash
# terminal 1 — collector: migrates the shared DB, then runs the ingest pipeline.
# The bootstrap-sources path MUST be absolute — quarkus:dev's working directory
# is the module dir, not the repo root.
./mvnw -pl infochat-collector quarkus:dev \
  -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/infochat \
  -Dquarkus.datasource.owner.jdbc.url=jdbc:postgresql://localhost:5432/infochat \
  -Dinfochat.bootstrap.sources-file=$PWD/prod/config/bootstrap-sources.json

# terminal 2 — provider: same shared DB; supply an adapter (the in-memory one
# here) because %dev configures none. --admin seeds the bootstrap bot admin.
./mvnw -pl infochat-provider quarkus:dev \
  -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/infochat \
  -Dinfochat.adapters=inmemory \
  -Dinfochat.adapters.inmemory.allow-low-trust=true \
  -Dinfochat.adapters.inmemory.admin=admin
```

Collector comes up on `http://127.0.0.1:8080`, provider on `:8081`; confirm each
with `curl localhost:8080/q/health` and `:8081/q/health` (both report
`"status":"UP"`). To drive the in-memory adapter from a terminal, enable the dev
terminal harness with `-Dinfochat.dev.harness.enabled=true` plus
`-Dinfochat.dev.harness.input-file=...` / `-Dinfochat.dev.harness.output-file=...`
(append messages to the input file, read replies from the output file).

> **Heads-up:** these per-module `quarkus:dev` runs resolve the sibling modules
> (e.g. `infochat-core`) from `~/.m2`, **not** the reactor (`-am` does not work
> with the `quarkus:dev` goal). If another worktree has installed a stale
> SNAPSHOT, run `./mvnw -q install -DskipTests` once at the repo root first.

---

## 4. Run the full test suite

A change is not done when its own new tests pass — run the whole suite from the
repo root and report regressions:

```bash
./mvnw verify
```

Quarkus integration tests bind an OS-assigned ephemeral port
(`quarkus.http.test-port=0`), so two suites reaching their IT phase at the same
moment won't collide.

The suite is the floor, not the whole story. For the end-to-end testing plan —
what's automatable vs. manual, plus the observability runbook and the
adversarial input kit — see
**[docs/testing/USER_TEST_PLAN.md](docs/testing/USER_TEST_PLAN.md)**.

---

## Module layout

| Module | Role |
|---|---|
| `infochat-core` | Shared domain types, persistence, and cross-service code |
| `infochat-ssrf` | SSRF-guarded HTTP client (egress hardening) |
| `infochat-llm-adapter` | Pluggable LLM / embedding SPI (OpenAI-compatible — which covers Ollama and llama.cpp — plus DeepSeek and Anthropic) |
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

### Random ITs die with "address already in use" at container startup

**Different test each run, failing before any test logic runs**
(`ContainerLaunchException` → `RootlessKit PortManager.AddPort(): … bind:
address already in use`) is an environment failure, not a regression. Rootless
Docker publishes container ports from an internal allocator whose band is
effectively fixed at ~40000–60999; when the host kernel hands outbound sockets
from the same band, container publishes race them.

The fix is the **host** kernel range (the only live lever — sysctl into the
docker daemon's network namespace is a no-op for the publish band):

```bash
sudo sysctl -w net.ipv4.ip_local_port_range="32768 39999"
```

Verify empirically by asking docker (never by reading sysctls — allocator
behavior is the ground truth):

```bash
id=$(docker run --rm -d -P pgvector/pgvector:pg16)
docker port "$id" | head -1   # healthy = a 40000-band draw while host is 32768-39999
docker rm -f "$id"
```

Durable fix: `/etc/sysctl.d/99-docker-port-split.conf` must carry the SAME
direction (`32768 39999`) — its original `40000 60999` direction re-arms the
race at reboot. `scripts/verify-serialized.sh` warns at verify start when the
host band overlaps the docker publish band, and fails fast when the host band
itself is saturated by LISTEN sockets (2026-08-16: an agent's userspace
"drain-ports" squatter held all 7,232 host ports and every `bind(0)` in the
test JVMs died with `BindException` — same environment class, different
mechanism; the guard names the offending pid so you can kill it and re-run).

### Known flaky integration tests

A few ITs have rare, timing-related flakes that are **not** caused by your
change — retry the run once before investigating, and only dig in on a **second**
failure against unchanged code:

- `OutboxRehydratorPaginationIT` — an occasional `SRMSG00034` back-pressure race
  in the in-memory reactive-messaging emitter.
- `Stage1WatchdogIT` — a marginal timing assertion that can trip under load.

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
- **[docs/testing/USER_TEST_PLAN.md](docs/testing/USER_TEST_PLAN.md)** — the
  end-to-end test plan (setup → admin → usage), the observability runbook, and
  the adversarial input kit.
