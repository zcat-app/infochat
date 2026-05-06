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
- **Provider service.** User-facing. Owns the messaging adapter, command                                                                                                                                                                              
  router, chat agent.
- **One LLM provider** (local Ollama / llama.cpp by default; remote                                                                                                                                                                                   
  optional per task).
- **One messaging adapter backend** (SimpleX in v1).

Both services connect to the same DB but use different DB roles                                                                                                                                                                                       
(`security.md` and decision D34). Both services run Flyway on startup;
the migration set is identical and idempotent on second-run. There is                                                                                                                                                                                 
**no shared file state** between the two services — restarts and rolling                                                                                                                                                                              
upgrades are coordinated through the DB only.

## Operator inputs

An operator must provide:

1. **A hardware profile choice** (`laptop` / `vps` / `pi` / `remote`,                                                                                                                                                                                 
   decision D27). One property setting picks a working configuration.
2. **A bot-admin contact id.** The cryptographic contact id of the                                                                                                                                                                                    
   user who will be the first bot admin. On startup, Provider ensures                                                                                                                                                                                 
   this user exists with `is_admin = true` (creating the user if                 
   needed) and writes a bootstrap row to `audit_log` (decision D9).
3. **A bootstrap sources file.** A JSON document listing the initial
   set of feeds (`name`, `url`, `fetcher`, `category`, `tags[]`).                                                                                                                                                                                     
   Loaded by the Collector on startup, idempotent on `(fetcher, url)`.           
   The union of `tags[]` across all entries seeds the Tier-1 controlled                                                                                                                                                                               
   vocabulary (decisions D5, D8).
4. **DB credentials** for the three Postgres roles.
5. **LLM provider configuration.** Endpoint URL, API key (from env                                                                                                                                                                                    
   var, not the DB), model names per task (or rely on profile defaults).
6. **Messaging adapter configuration.** Adapter selection plus its                                                                                                                                                                                    
   transport-specific settings.

Everything else has a profile default.

## Bootstrap behavior on startup

Both services run Flyway migrations first. Then:

- **Collector** loads the bootstrap sources file and upserts `source`                                                                                                                                                                                 
  rows by `(fetcher, url)`; never deletes; updates name/category/tags                                                                                                                                                                                 
  in place when entries differ. Then runs the outbox rehydrator                  
  (re-enqueues anything left in `RAW`/intermediate states from a prior                                                                                                                                                                                
  crash). Then starts the fetch scheduler.
- **Provider** ensures the bot-admin user exists and has `is_admin =                                                                                                                                                                                  
    true` (audit-logged). Then runs the new-post reconciler — replays
  any `READY` posts since `last_ready_post_at` (the `LISTEN/NOTIFY`                                                                                                                                                                                   
  catch-up high-water mark, see `architecture.md`). Then connects the            
  messaging adapter. Then starts the command router.

A bean failure during startup refuses the service start (Quarkus                                                                                                                                                                                      
default). The readiness probe stays unhealthy until every required                                                                                                                                                                                    
startup bean is up. Exact priorities live in design notes.

## Configuration surface (spec level)

The spec commits to *categories* of configurable settings; specific                                                                                                                                                                                   
property keys live in design notes:

- **Profile.** One name selects context window, model defaults, eval                                                                                                                                                                                  
  concurrency, vector index type, summary worker count, eval queue                                                                                                                                                                                    
  depth.
- **LLM routing.** Per-task provider + model overrides; embedding                                                                                                                                                                                     
  provider; translator provider.
- **Messaging adapter.** Adapter id + adapter-specific settings.
- **Source bootstrap.** Path to the JSON file.
- **Admin bootstrap.** Bot-admin contact id.
- **Security.** Release-on-Stage-2-failure default; SSRF allowlist (not
  user-tunable; see `security.md`); fetch caps (size, timeouts).
- **Rate limits.** Per-user buckets (capped at profile defaults; the             
  operator can lower, not raise).
- **Translation.** Cache TTL, default language.
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
- Contact ids appear redacted in logs outside the audit log.

Specific backup tooling, retention policies, and key rotation                                                                                                                                                                                         
procedures are operator concerns and live in design notes / runbook.

## Local development

A `docker-compose.yml` brings up Postgres+pgvector, an Ollama instance,                                                                                                                                                                               
the Collector, the Provider, and the in-memory test messaging adapter.           
The bootstrap sources file points at a small set of feeds suitable for                                                                                                                                                                                
a laptop. The MVP exit criteria (`00-mvp.md`) define the smallest                
end-to-end slice that proves the topology works.

## Deployment scenarios

Operator picks one of:

- **Laptop / dev.** Single host, local Ollama, in-memory adapter for                                                                                                                                                                                  
  tests + SimpleX adapter for real use. Default profile: `laptop`.
- **VPS.** Single host, smaller models, production-like Stage-2-failure                                                                                                                                                                               
  handling. Default profile: `vps`.
- **Raspberry Pi.** Single host, tiny models, more aggressive degraded                                                                                                                                                                                
  fallbacks (digest fallback, IVFFlat vector index). Default profile:            
  `pi`.
- **Remote LLM.** Local DB and services, remote LLM provider. Default                                                                                                                                                                                 
  profile: `remote`.

The set of supported profiles is the spec-level commitment; the values                                                                                                                                                                                
behind each profile are tuning.

## What lives in design notes

- Concrete property keys
- Default values per profile (queue depths, worker counts, timeouts)
- `docker-compose.yml`
- Example `application.properties`
- Bootstrap sources JSON schema and example file
- Startup-bean priorities
- Health endpoint paths and probe timeouts
- Metrics names, labels, dashboard examples
- Backup runbook
- Rolling-upgrade runbook
- Disaster-recovery procedure