# Deployment and configuration

This file describes what an operator must provide, what gets bootstrapped                                                                                                                                                                             
from that input, and the runtime shape of a deployment. Concrete                                                                                                                                                                                      
property keys, `docker-compose.yml`, file paths, default values, and                                                                                                                                                                                  
runbook procedures live in `docs/design/07-deployment.md`.

## Topology

A v1 deployment runs:

- **PostgreSQL with pgvector.**
- **Collector service.** Headless. Polls feeds, runs the eval pipeline,                                                                                                                                                                               
  writes posts.
- **Provider service.** User-facing. Owns the configured messaging
  adapters, command router, chat agent.
- **One LLM provider** (local Ollama / llama.cpp by default; remote                                                                                                                                                                                   
  optional per task).
- **One Provider, one or more messaging adapters.** v1 supports
  **SimpleX** and **Signal** as production adapters (decision D32);
  a single Provider process can run any non-empty subset of them
  simultaneously, sharing the DB, the LLM worker pool, and the
  per-user rate-limit budget. Running multiple adapters in one
  Provider — rather than one Provider per adapter — is the v1
  topology because (a) the LLM concurrency cap is a per-process
  value (`llm.md` §Bounded concurrency) and splitting it across
  Provider instances would multiply the load on a shared local
  model, and (b) the schema's `(adapter, contact_id)` keying and
  the cross-adapter isolation invariants (`messaging.md`
  §Per-adapter trust level, `security.md` §Invite-code registration)
  are load-bearing only when one Provider sees both adapters. The
  in-memory test adapter is for tests, not production; production
  deployments MUST NOT enable it. Each adapter's identity space is
  isolated by the `(adapter, contact_id)` join key — a Signal
  user and a SimpleX user are distinct `users` rows even at
  byte-equal contact ids.

Both services connect to the same DB but use different DB roles
(`security.md` and decision D34). Only the Collector runs Flyway on
startup — `quarkus-flyway` is test-scoped in the Provider, so the
production Provider never migrates; the operator starts the Collector
(which applies the migration set) before the Provider. The migration
set is idempotent on second-run. There is **no shared file state**
between the two services — restarts and rolling upgrades are
coordinated through the DB only.

**Startup ordering.** Because only the Collector migrates in
production, the operator starts the Collector first — it applies the
migration set under Flyway's `schema_history` table and
`pg_advisory_lock` — and the Provider second, against an
already-migrated schema. The Provider's bundled `quarkus-flyway` is
test-scoped: in production it does nothing, and under test the in-JVM
Provider boot applies the migration set itself (the advisory lock
keeps any concurrent apply correct).

## Operator inputs

An operator must provide:

1. **A hardware profile choice** (`laptop` / `vps` / `pi` / `remote-llm`,                                                                                                                                                                                 
   decision D27). One property setting picks a working configuration.
2. **A bot-admin contact id, optional per adapter, required in
   union.** The cryptographic contact id of the user who will be
   the first bot admin **on a given enabled adapter**. The
   property is keyed by adapter (concrete keys in design notes)
   and **may be omitted for individual adapters** — an adapter
   without a configured bootstrap admin still serves users, but
   has no admin row of its own at startup. The deployment-wide
   constraint is that **the union of bootstrap admin contacts
   across all enabled adapters MUST be non-empty**; Provider
   refuses to start otherwise (last-admin protection,
   `security.md` §Authorization model, only works if at least one
   admin exists somewhere). The contact-id string format is
   **adapter-specific** — SimpleX contact ids are not Signal
   ACI/UUIDs — so each value MUST be parseable by its own adapter;
   Provider validates each at startup and refuses to start on a
   mismatch. On startup Provider ensures, for every adapter that
   does have a bootstrap admin, that the configured contact exists
   with `is_admin = true` (creating the user if needed) and writes
   a bootstrap row to `audit_log` (decision D9). The same human
   typically maps to two distinct `users` rows — one per
   `(adapter, contact_id)` — and is admin on each independently
   per the inbound-adapter-scoped grant rule
   (`commands.md` §Admin). Operators concerned about per-adapter
   compromise risk should consult `security.md`
   §Per-adapter admin threat profile when choosing where to
   place admin.
3. **A bootstrap sources file.** A JSON document listing the initial
   set of feeds. Each entry uses the v1 generalized identity
   `(kind, identifier)` (decision D38) plus `name`, `category`,
   `tags[]`, and an optional per-kind `config` object whose shape
   depends on `kind`. Loaded by the Collector on startup, idempotent
   on `(kind, identifier)`. The union of `tags[]` across all entries
   seeds the Tier-1 controlled vocabulary (decisions D5, D8). The
   spec-level shape of an entry:

   - `kind` — required. One of the supported source kinds
     (Fetcher-shaped: `rss`, `bluesky`, `nitter`, `reddit`, `youtube`,
     `odysee`; StreamSource-shaped: `nostr`).
   - `identifier` — required. URL for HTTP-shaped sources; filter
     spec (e.g. a Nostr filter) for stream sources.
   - `name` — required, human-readable.
   - `category` — required, one of `news` / `blog` / `social`.
   - `tags` — required, ≥1 entry (decision D14).
   - `config` — optional, omitted or `null` for HTTP-shaped sources;
     a per-kind JSON object for stream sources. For `nostr` the
     `config` block carries the relay list and any per-source
     overrides; the exact shape lives in design notes.

   The exact JSON Schema, including the per-kind `config` shape for
   each `kind`, lives in `docs/design/07-deployment.md`.
4. **A bootstrap assets file** (optional). A JSON document listing the
   set of enabled assets and per-asset enabled sub-verbs for the asset
   commands (decision D39). Absent file → asset commands disabled.
   Loaded by the Collector on startup, idempotent on `(asset)`. The
   set of enabled assets gates which `/zcash`, `/monero`, … commands
   the Provider exposes; the per-asset sub-verb allowlist gates which
   data sources each command will accept.
5. **DB credentials** for the three Postgres roles.
6. **LLM provider configuration.** Endpoint URL, API key (from env                                                                                                                                                                                    
   var, not the DB), model names per task (or rely on profile defaults).
7. **Messaging adapter configuration.** A list of one or more enabled
   adapters and their transport-specific settings. Each enabled
   adapter has its own connection settings, capability flag
   defaults, (per item 2) its own bot-admin contact id, and **its
   own bot identity material** — the cryptographic state by which
   the adapter authenticates as the bot to its transport (e.g. a
   SimpleX queue keypair file, a `signal-cli` account directory).
   The shape and on-disk layout of this material is
   adapter-specific and lives in design notes; the spec-level
   commitment is that **each adapter owns and validates its own
   bot identity at startup**: Provider does not synthesize bot
   identity, and a misconfigured or unreadable identity store
   fails the adapter's startup (the per-adapter resilience rule
   in §Bootstrap behavior on startup applies — one adapter's
   identity-store failure does not abort Provider). The
   per-adapter bot contact id (the value mention recognition
   compares against, `messaging.md` §Required SPI surface) has
   adapter-specific provenance: the **Signal** bot contact id (the
   ACI) is **derived from the adapter's own identity store at
   adapter startup** — it is not an operator-typed property, so it
   cannot be mistyped and rotating the bootstrap-admin contact
   cannot move it; the **SimpleX** bot contact id remains an
   operator-configured per-adapter property, distinct from the
   bootstrap-admin contact id and validated at adapter startup —
   deriving it from the adapter's identity material too is planned
   hardening, and its operator-typed-anchor risk is recorded in
   `security.md` §Per-adapter admin threat profile. The list is
   closed at startup — adding or removing an adapter is a restart.
   The exact property keys (and the multi-adapter list shape) live
   in design notes.

Everything else has a profile default.

## Bootstrap behavior on startup

The Collector runs Flyway migrations first (the production Provider does
not migrate — `quarkus-flyway` is test-scoped there; see the
startup-ordering note above). Then:

- **Collector** loads the bootstrap sources file and upserts `source`
  rows by `(kind, identifier)` (decision D38); never deletes; updates
  name/category/tags/config in place when entries differ. Loads the
  bootstrap assets file if                                                                                                                                                                                    
  configured and upserts the per-asset enabled-sub-verb allowlist                                                                                                                                                                                     
  (decision D39); never deletes assets, so removing an asset from the                                                                                                                                                                                 
  file is a soft-disable in the operator's runbook, not an automatic                                                                                                                                                                                  
  drop. Then runs the outbox rehydrator (re-enqueues anything left in                                                                                                                                                                                 
  `RAW`/intermediate states from a prior crash). Then starts the fetch                                                                                                                                                                                
  scheduler — including the asset-snapshot fetchers, on the                                                                                                                                                                                           
  profile-driven refresh interval.
- **Provider** ensures, for every enabled adapter, that its
  bootstrap-admin user exists and has `is_admin = true`
  (one bootstrap row per `(adapter, contact_id)`, all audit-logged).
  Then runs the new-post reconciler — replays any `READY` posts
  since the `new_post` channel's `provider_state` cursor (the
  `LISTEN/NOTIFY` catch-up high-water mark, see `architecture.md`).
  Then connects each enabled messaging adapter. **Per-adapter
  resilience.** A connection failure on one adapter does not
  prevent the others from coming up and does not abort Provider
  startup — the multi-adapter design (D46) exists precisely to
  deliver per-adapter resilience. Each failed adapter is logged
  at error severity and retries on a profile-driven backoff.
  **Readiness rule.** The Provider's readiness probe reports
  ready when **at least one** enabled adapter is connected
  (because Provider can serve traffic via that adapter);
  not-ready when zero adapters are connected. Per-adapter
  connection state is exposed separately via metrics so an
  operator can distinguish "fully healthy" from "degraded —
  one adapter down" without parsing readiness alone. Then
  starts the command router.

**Bootstrap admin drift.** Per enabled adapter: if the configured
bootstrap admin contact id for that adapter does not match an
existing `is_admin = true` row at `(adapter, contact_id)`,
Provider creates a new admin row for that adapter (audit-logged)
and **leaves any prior admin rows in place** with their
`is_admin = true` flag intact (across this and any other
adapter). After a rotation the deployment therefore has both the
old and the new admin rows on the rotated adapter, both with
`is_admin = true`, until the operator explicitly revokes the
old one via `/revoke-admin` from the new admin's chat. This is
the safer default than auto-revoking old admins on every startup:
an operator who rotates the bootstrap value for one adapter gets
a working bot on that adapter without cascading effects
elsewhere; pruning stale bootstrap admins is an explicit operator
action. Last-admin protection (invariant 2) is **global across
adapters** — the prior admin row cannot be revoked until at
least one other `is_admin = true` row exists anywhere on the
deployment.

**Bootstrap-seeded admin row shape.** A bootstrap-seeded admin row
is created with `is_admin = true`, `is_banned = false`,
`probation_until = NULL` (bootstrap admins skip the slow-start
tier), and `registration_state = 'vouched'`
(`schema.md` §Identity and access — User entity). The `vouched`
state satisfies the DM-gate check in the permission step
(`security.md` §Invite-code registration) so the bootstrap admin
can DM the bot without minting an invite for themselves;
`'vouched'` rather than a dedicated `'bootstrap'` value because
the post-startup behavior is identical to a normal vouched user
and adding an enum value is a load-bearing schema change with no
semantic gain. The `audit_log` row written for the bootstrap
records the original cause under `details_json.cause = 'bootstrap'`.

**Asset bootstrap.** The Collector loads `bootstrap-assets.json`
(when configured) and upserts `asset_config` rows by
`(asset, sub_verb)` (`schema.md` §Operational). Entries removed
from the file in a later reload are soft-disabled
(`asset_config.enabled = false`); rows are never hard-deleted, and
historical `price_snapshot` data for a soft-disabled asset is
preserved for audit. The asset Fetchers schedule from
`asset_config` rows where `enabled = true AND status = 'active'`.
**File-state semantics** (three cases — opt-out vs. opt-in-broken
must be distinguished so an operator who configured the path
cannot silently lose asset commands by deleting or moving the
file):
- *Path unset.* Operator opted out of asset commands. Asset
  commands are disabled for the deployment; `/help` omits them;
  the rest of v1 ships normally (per `commands.md` §Asset
  commands). Startup logs an info line. **Not** a startup
  failure.
- *Path set, file absent.* Operator opted in but the file is
  missing (typo, deleted, wrong working directory, mount not
  attached). Startup **fails fast** with a fatal log message
  identifying the configured path. Silently disabling asset
  commands here would mask the misconfiguration; the loader
  treats a configured-but-missing file as broken intent, not
  opt-out.
- *Path set, file present but malformed* (unparseable JSON,
  schema-invalid, references an unknown sub-verb, an
  `is_default = true` row that is also `enabled = false` per
  `schema.md` §Operational — Default-row consistency, etc.).
  Startup **fails fast** with a fatal log message identifying
  the file path and the parse / validation error. Same
  rationale as the file-absent case: presence-with-errors is
  opt-in-but-broken, not opt-out.

A bean failure during startup refuses the service start (Quarkus
default). The readiness probe stays unhealthy until every required
startup bean is up. **Exception: `StreamSource` connections** —
relay reachability is not a startup gate; the supervised worker
starts in the background and unreachable relays surface as
per-relay degradation rather than a startup failure
(`architecture.md` §Ingest SPIs — Asynchronous startup). Exact
priorities live in design notes.

## Configuration surface (spec level)

The spec commits to *categories* of configurable settings; specific                                                                                                                                                                                   
property keys live in design notes:

- **Profile.** One name selects context window, model defaults, eval                                                                                                                                                                                  
  concurrency, vector index type, summary worker count, eval queue                                                                                                                                                                                    
  depth.
- **LLM routing.** Per-task provider + model overrides; embedding                                                                                                                                                                                     
  provider; translator provider.
- **Messaging adapters.** A non-empty list of enabled adapter ids,
  each with its own adapter-specific settings and bootstrap-admin
  contact id (per Operator inputs item 2). The list is closed at
  startup; runtime add/remove is a v2 candidate.
- **Source bootstrap.** Path to the JSON file.
- **Asset bootstrap.** Path to the JSON file (optional; absent =                                                                                                                                                                                       
  asset commands disabled).
- **Admin bootstrap.** Bot-admin contact id.
- **Security.** Release-on-Stage-2-failure default; SSRF allowlist (not
  user-tunable; see `security.md`). Guarded outbound egress ignores
  ambient JVM proxy settings (`http.proxyHost` / `https.proxyHost` /
  `socksProxyHost`): the guard pins DNS to the validated peer IPs, so a
  proxy that re-resolved the target would void that pin — guarded
  clients are built with proxying disabled. Fetch caps (size, timeouts);
  re-evaluation cadence and per-post attempt cap (separate caps for
  Stage-2-infra-failure class and UNKNOWN-verdict class — both
  profile-driven, both overridable per-property); admin-review TTL
  for quarantine rows (`schema.md` invariant 6).
- **Memory retention.** `chat_memory` TTL is profile-driven *and*
  overridable per-property; both the profile-default key and the
  per-property override key live in design notes. Users do not tune
  this (D40); operators do.
- **Rate limits.** Per-user buckets (capped at profile defaults; the             
  operator can lower, not raise).
- **Translation.** Cache TTL, default language.
- **Groups.** Default group timezone for newly-created groups (`UTC`
  by default; an operator may override). `/group-timezone` mutates
  the per-group value at runtime (commands.md). **Periodic-digest
  morning slot center hour** and **periodic-digest evening slot
  center hour** — two operator-configured 24-hour local-time values
  (defaults profile-driven, in design notes) that apply uniformly
  across every group on the deployment, each interpreted in that
  group's own timezone. v1 has no per-group override; the slot
  window width centered on each hour is also profile-driven and
  in design notes.
- **DB.** Per-role JDBC URLs + credentials.
- **Operational.** Health endpoints, metrics endpoint, log level.

Profile values can be overridden per-property; an explicit operator                                                                                                                                                                                   
setting always wins. Switching the embedding provider to a remote                                                                                                                                                                                     
service emits an explicit confirmation log line on startup                                                                                                                                                                                            
(`security.md`).

## Health and observability

- **Liveness** — service is running.
- **Readiness** — service is fully bootstrapped (Flyway done, all                                                                                                                                                                                     
  required startup beans up).
- **LLM probe** — periodic ping against each configured provider; a              
  failing provider surfaces as a degraded readiness signal but does                                                                                                                                                                                   
  *not* fail readiness outright (the eval pipeline is allowed to                 
  degrade per `security.md` failure handling).
- **Endpoint exposure** — the health endpoints are unauthenticated
  in v1, and the readiness payload names each enabled adapter with
  its up/down state and reports DB connectivity: a topology
  disclosure (which messaging transports the deployment runs,
  whether its database is reachable) to any caller that can reach
  the port. The shipped default binds the health port to loopback;
  probing from another host is an explicit operator action — widen
  the bind and firewall the port to the prober's address. The
  per-adapter names stay in the payload because they are the
  operator's degraded-vs-healthy signal; the exposure lever is
  network reachability, not payload trimming.
- **Metrics** — eval-stage counters, Stage-1 hits per rule, Stage-2              
  verdicts and infra failures, LLM latency/tokens per task/provider,
  fetch success/fail per source, rate-limit overflows. Exact metric                                                                                                                                                                                   
  names and recommended alerts (`Stage2UnknownRateHigh`,                                                                                                                                                                                              
  `Stage2FailureSpike`, `LlmDown`, source-level alerts) are in                                                                                                                                                                                        
  `docs/design/04-security.md` and `docs/design/07-deployment.md`.

## Backups, rotation, secrets

Spec-level commitments:

- Audit log is append-only; it must be backed up on the same cadence                                                                                                                                                                                  
  as the rest of the DB.
- Quarantine original content is reachable only by the admin DB role;                                                                                                                                                                                 
  backups must respect that boundary (encrypted-at-rest at the                                                                                                                                                                                        
  operator level, decision D34).
- LLM API keys come from environment variables, not the DB.
- Audit-log write hook redacts API-key-shaped strings.
- Stdout log redaction hook redacts API-key-shaped strings before any
  console output (fail-closed on regex timeout).
- Contact ids appear redacted in logs outside the audit log.

Specific backup tooling, retention policies, and key rotation                                                                                                                                                                                         
procedures are operator concerns and live in design notes / runbook.

## Local development

A `docker-compose.yml` brings up Postgres+pgvector, an Ollama instance,                                                                                                                                                                               
the Collector, the Provider, and the in-memory test messaging adapter.           
The bootstrap sources file points at a small set of feeds suitable for                                                                                                                                                                                
a laptop. The MVP exit criteria (`docs/design/00-mvp.md`) define
the smallest end-to-end slice that proves the topology works.

## Deployment scenarios

Operator picks one of:

- **Laptop / dev.** Single host, local Ollama. Default profile:
  `laptop`. The production deployment runs one or more of
  SimpleX / Signal (enabled per the multi-adapter rule in
  §Topology) — both can run together in the one Provider, sharing
  the LLM worker pool. The **in-memory adapter is exercised by
  the test harness in a separate, test-time deployment shape**
  (its own DB, its own Provider process), never alongside the
  production adapters in the same running deployment.
- **VPS.** Single host, smaller models, production-like Stage-2-failure                                                                                                                                                                               
  handling. Default profile: `vps`.
- **Raspberry Pi.** Single host, tiny models, more aggressive degraded                                                                                                                                                                                
  fallbacks (digest fallback, IVFFlat vector index). Default profile:            
  `pi`.
- **Remote LLM.** Local DB and services, remote LLM provider. Default                                                                                                                                                                                 
  profile: `remote-llm`.

The set of supported profiles is the spec-level commitment; the values                                                                                                                                                                                
behind each profile are tuning.

## What lives in design notes

- Concrete property keys
- Default values per profile (queue depths, worker counts, timeouts)
- `docker-compose.yml`
- Example `application.properties`
- Bootstrap sources JSON schema and example file (including the
  per-`kind` `config` block shape — the spec commits to the
  top-level entry shape; the per-kind config shape lives here)
- Bootstrap assets JSON schema and example file
- Startup-bean priorities
- Health endpoint paths and probe timeouts
- Metrics names, labels, dashboard examples
- Backup runbook
- Rolling-upgrade runbook
- Disaster-recovery procedure