---
  # 07 — Deployment and configuration                                                                                                                                                                                                                   
                                     
  This file specifies how to deploy and operate the two services. Covers: configuration model, hardware profiles, `docker-compose` for local dev, the bootstrap-sources file, environment variables, secrets, runbook, backup/restore, and upgrade.     
                                                                                                                                                                                                                                                        
  The system is designed to run on a single host for v1 — Postgres + Ollama + Collector + Provider + a SimpleX bot client all colocated. Splitting onto multiple hosts is straightforward but not required.                                             
                                                                                                                                                                                                                                                        
  ---                                                                              
                                                                                                                                                                                                                                                        
  ## 7.1 Topology options                                                          
                                                                                                                                                                                                                                                        
  ### Option A: Single-host (v1 default)
                                                                                                                                                                                                                                                        
  ┌─────────────── host ────────────────────┐                                      
  │  ┌──────────┐    ┌──────────────┐       │                                                                                                                                                                                                           
  │  │ Postgres │◀───│  Collector   │       │
  │  │ +pgvector│    └──────────────┘       │                                                                                                                                                                                                           
  │  │          │    ┌──────────────┐       │                                                                                                                                                                                                           
  │  │          │◀───│  Provider    │──┐    │                                                                                                                                                                                                           
  │  └──────────┘    └──────────────┘  │    │                                                                                                                                                                                                           
  │                                    │    │                                                                                                                                                                                                           
  │  ┌──────────────┐    ┌──────────┐  │    │                                                                                                                                                                                                           
  │  │  Ollama      │◀───│ Provider │──┤    │
  │  │  llama.cpp   │◀───│ Collector│  │    │                                                                                                                                                                                                           
  │  └──────────────┘    └──────────┘  │    │                                                                                                                                                                                                           
  │                                    │    │                                                                                                                                                                                                           
  │  ┌──────────────┐                  │    │                                                                                                                                                                                                           
  │  │  simplex-cli │◀─────────────────┘    │                                      
  │  │   (WS bot)   │                       │                                                                                                                                                                                                           
  │  └──────────────┘                       │                                                                                                                                                                                                           
  └─────────────────────────────────────────┘                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  Recommended for: laptop dev, VPS, Raspberry Pi.                                  
                                                                                                                                                                                                                                                        
  ### Option B: Split-host (operator choice; not required)                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  - Postgres on dedicated host (managed or self-hosted).                                                                                                                                                                                                
  - Ollama on a GPU host (laptop with eGPU, or a small home server).               
  - Collector + Provider colocated on a small VPS.                                                                                                                                                                                                      
  - `simplex-cli` runs alongside Provider.                                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  The schema and code are identical; only `application.properties` URLs differ.                                                                                                                                                                         
                                                                                   
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 7.2 Hardware profiles

  `infochat.profile=laptop|vps|pi|remote` is the single most important config. It selects defaults for context window, models, eval concurrency, vector index, etc. See [05-llm-and-embeddings.md §5.7](05-llm-and-embeddings.md) for the canonical     
  table.
                                                                                                                                                                                                                                                        
  | Profile | Hardware | Local model? | Notes |                                    
  |---|---|---|---|
  | `laptop` | 16–32 GB RAM, decent CPU/GPU, dev workstation | yes | Development default. |
  | `vps` | 8–16 GB RAM, CPU only, cloud VPS | yes | Production-grade for moderate load. |                                                                                                                                                              
  | `pi` | Raspberry Pi 5 (8 GB) | yes (1B param model) | Best-effort. Czech translation quality limited. Embedding via `all-minilm:33m` (384-d). |                                                                                                     
  | `remote` | Provider runs anywhere; LLM is OpenAI/Anthropic/NanoGPT/etc. | no | Operator-explicit opt-in for sending post bodies to remote APIs. |                                                                                                   
                                                                                                                                                                                                                                                        
  ### Switching profiles                                                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  Profile is read once at startup. To switch:                                                                                                                                                                                                           
   
  1. Stop both services.                                                                                                                                                                                                                                
  2. Edit `application.properties`: `infochat.profile=...`.                        
  3. If embedding dimension changes (e.g., laptop→pi), run the embedding migration: `scripts/reembed.sh`.                                                                                                                                               
  4. Start collector, then provider.                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  The collector logs the active profile and any individual overrides at INFO on boot:                                                                                                                                                                   
                                                                                   
  INFO  Bootstrap – profile=laptop, overrides={infochat.llm.summarizer.model: llama3.1:70b}                                                                                                                                                             
                                                                                   
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 7.3 Configuration sources and precedence                                                                                                                                                                                                           
   
  Quarkus applies config in standard order; relevant for us:                                                                                                                                                                                            
                                                                                   
  1. System properties              -Dinfochat.profile=pi                                                                                                                                                                                               
  2. Environment variables          INFOCHAT_PROFILE=pi                            
  3. application.properties         (bundled in jar; baseline defaults)                                                                                                                                                                                 
  4. application-{profile}.properties (bundled; profile overrides)                                                                                                                                                                                      
  5. application.properties on disk (next to the jar; operator overrides)                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  Operators override on disk; no rebuild required for ops changes. Secrets always come from env vars, never from disk files in production.                                                                                                              
                                                                                                                                                                                                                                                        
  ---                                                                              
                                                                                                                                                                                                                                                        
  ## 7.4 Canonical `application.properties`                                                                                                                                                                                                             
   
  A single file, used by both services (each ignores keys not relevant to it).                                                                                                                                                                          
                                                                                   
  ```properties                                                                                                                                                                                                                                         
  # ── Profile ────────────────────────────────────────────────────────────        
  infochat.profile=laptop                          # laptop|vps|pi|remote                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  # ── Database ───────────────────────────────────────────────────────────                                                                                                                                                                             
  quarkus.datasource.db-kind=postgresql                                                                                                                                                                                                                 
  quarkus.datasource.username=infochat                                             
  quarkus.datasource.password=${INFOCHAT_DB_PASSWORD}                                                                                                                                                                                                   
  quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/infochat
  quarkus.datasource.jdbc.max-size=20                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  # Service-specific role overrides (recommended for least-privilege)                                                                                                                                                                                   
  quarkus.datasource.collector.username=infochat_collector                                                                                                                                                                                              
  quarkus.datasource.collector.password=${INFOCHAT_COLLECTOR_PASSWORD}                                                                                                                                                                                  
  quarkus.datasource.provider.username=infochat_provider                                                                                                                                                                                                
  quarkus.datasource.provider.password=${INFOCHAT_PROVIDER_PASSWORD}                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  quarkus.flyway.migrate-at-start=true                                             
  quarkus.flyway.locations=classpath:db/migration                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  # ── Bootstrap ──────────────────────────────────────────────────────────
  infochat.bootstrap.sources-file=bootstrap-sources.json                                                                                                                                                                                                
  infochat.admin.contact-id=${INFOCHAT_ADMIN_CONTACT_ID}                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  # ── Messaging adapter ──────────────────────────────────────────────────                                                                                                                                                                             
  infochat.adapter=simplex                         # simplex|inmemory                                                                                                                                                                                   
  infochat.adapter.bot-mention-name=@infochat-bot                                                                                                                                                                                                       
  infochat.adapter.allow-low-trust=false                                           
                                                                                                                                                                                                                                                        
  infochat.adapter.simplex.url=ws://localhost:5225
  infochat.adapter.simplex.session-token=${SIMPLEX_SESSION_TOKEN}                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  # ── LLM (per-task; profile fills in defaults if not set) ───────────────                                                                                                                                                                             
  # infochat.llm.security.provider=ollama                                                                                                                                                                                                               
  # infochat.llm.security.model=llama3.2:3b                                                                                                                                                                                                             
  # infochat.llm.summarizer.provider=ollama                                        
  # infochat.llm.summarizer.model=llama3.1:8b                                                                                                                                                                                                           
  # infochat.llm.chat-agent.provider=ollama                                        
  # infochat.llm.chat-agent.model=llama3.1:8b                                                                                                                                                                                                           
  # infochat.embeddings.provider=ollama                                            
  # infochat.embeddings.model=nomic-embed-text                                                                                                                                                                                                          
                                                                                   
  # Remote provider example (NanoGPT)                                                                                                                                                                                                                   
  # infochat.llm.summarizer.provider=openai-compatible                             
  # infochat.llm.summarizer.base-url=https://nano-gpt.com/api/v1                                                                                                                                                                                        
  # infochat.llm.summarizer.api-key=${NANOGPT_API_KEY}                                                                                                                                                                                                  
  # infochat.llm.summarizer.model=llama-3.1-70b-instruct                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  # ── Translation ────────────────────────────────────────────────────────                                                                                                                                                                             
  # (defaults reuse llm.translator.* which falls back to summarizer)                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  # ── Scheduler ──────────────────────────────────────────────────────────                                                                                                                                                                             
  infochat.collector.fetch-interval=PT5M           # per-source default                                                                                                                                                                                 
  infochat.collector.linking-interval=PT5M                                                                                                                                                                                                              
  infochat.collector.partition-prune-cron=0 30 3 * * ?                                                                                                                                                                                                  
  infochat.collector.ttl-prune-cron=0 0 4 * * ?                                                                                                                                                                                                         
  infochat.provider.digest-tick-cron=0 * * * * ?   # checks every minute for due groups                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  # ── HTTP / observability ───────────────────────────────────────────────
  # quarkus.http.port is service-specific and lives in each service's own
  # application.properties (see the two blocks below). Setting it here once
  # would collide between collector and provider — they cannot share a port
  # on a single host.
  quarkus.management.enabled=true                  # /q/health, /q/metrics
  quarkus.log.level=INFO                                                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  # ── Limits ─────────────────────────────────────────────────────────────
  infochat.rate.user-commands-per-min=30
  infochat.rate.user-add-source-per-hour=5
  infochat.rate.user-chat-per-min=60
  ```

  ### Per-service `application.properties`

  Each service ships its own `application.properties` (in `infochat-collector/src/main/resources/` and `infochat-provider/src/main/resources/`) that imports the canonical settings above and adds the service-specific HTTP port. Using two separate files is the cleanest way to keep ports from colliding when both services run on the same host.

  Collector (`infochat-collector/src/main/resources/application.properties`):

  ```properties
  # Inherits keys from the canonical file above; only service-specific overrides here.
  quarkus.http.port=8080
  quarkus.application.name=infochat-collector
  ```

  Provider (`infochat-provider/src/main/resources/application.properties`):

  ```properties
  # Inherits keys from the canonical file above; only service-specific overrides here.
  quarkus.http.port=8081
  quarkus.application.name=infochat-provider
  ```

  If the operator prefers a single shared file at deploy time, the per-service port can instead be supplied at startup via system property:

  ```bash
  java -Dquarkus.http.port=8080 -jar infochat-collector.jar
  java -Dquarkus.http.port=8081 -jar infochat-provider.jar
  ```

  Either approach works; what is **not** allowed is setting `quarkus.http.port` twice in the same properties file — Quarkus reads the last value wins, so the collector and provider would silently end up on the same port and the second service to start would fail to bind.

  Notes:                                                                           
                                                                                                                                                                                                                                                        
  - The canonical block above is shared keys only; per-service `quarkus.http.port` lives in each service's own `application.properties` (collector=8080, provider=8081) as shown in the two blocks immediately above.                                                                
  - DB credentials use service-specific roles (infochat_collector, infochat_provider). The infochat superuser is reserved for migrations and admin psql.
  - All secrets read from env vars; no plaintext secrets in the file.                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.5 Environment variables                                                                                                                                                                                                                             
                                                                                   
  ┌─────────────────────────────┬──────────────────────────────────┬───────────────────┬───────────────────────────────────────────────────────────┐
  │          Variable           │            Required?             │      Read by      │                          Purpose                          │
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ INFOCHAT_PROFILE            │ optional                         │ both              │ Override infochat.profile                                 │
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ INFOCHAT_DB_PASSWORD        │ yes                              │ both (migrations) │ Superuser DB password                                     │                                                                                                    
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ INFOCHAT_COLLECTOR_PASSWORD │ yes                              │ collector         │ Collector DB role password                                │                                                                                                    
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ INFOCHAT_PROVIDER_PASSWORD  │ yes                              │ provider          │ Provider DB role password                                 │
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ INFOCHAT_ADMIN_CONTACT_ID   │ yes                              │ provider          │ Bootstrap bot-admin contact id                            │
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ SIMPLEX_SESSION_TOKEN       │ yes (if simplex adapter)         │ provider          │ SimpleX bot auth                                          │
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ OLLAMA_URL                  │ optional                         │ both              │ Override default http://localhost:11434                   │
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ ANTHROPIC_API_KEY           │ yes (if Anthropic provider used) │ both              │ Anthropic auth                                            │
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ OPENAI_API_KEY              │ optional                         │ both              │ Used by openai-compatible provider when targeting OpenAI  │
  ├─────────────────────────────┼──────────────────────────────────┼───────────────────┼───────────────────────────────────────────────────────────┤                                                                                                    
  │ NANOGPT_API_KEY             │ optional                         │ both              │ Used by openai-compatible provider when targeting NanoGPT │
  └─────────────────────────────┴──────────────────────────────────┴───────────────────┴───────────────────────────────────────────────────────────┘                                                                                                    
                                                                                   
  The Provider refuses to start if any required variable for the active configuration is missing. The error message names the missing variable.                                                                                                         
                                                                                   
  ---                                                                                                                                                                                                                                                   
  7.6 bootstrap-sources.json                                                       
                                                                                                                                                                                                                                                        
  Path resolved relative to the working directory (or absolute via config).
                                                                                                                                                                                                                                                        
  Schema                                                                           
                                                                                                                                                                                                                                                        
  [                                                                                
    {
      "name": "AI News",
      "url": "https://www.artificialintelligence-news.com/feed/",                                                                                                                                                                                       
      "fetcher": "rss",                                                                                                                                                                                                                                 
      "category": "news",                                                                                                                                                                                                                               
      "tags": ["AI", "Development"]                                                                                                                                                                                                                     
    },                                                                                                                                                                                                                                                  
    {
      "name": "Hugging Face",                                                                                                                                                                                                                           
      "url": "https://huggingface.co/blog/feed.xml",                               
      "fetcher": "rss",                                                                                                                                                                                                                                 
      "category": "blog",
      "tags": ["AI", "Research"]                                                                                                                                                                                                                        
    },                                                                             
    {
      "name": "AI Search",                                                                                                                                                                                                                              
      "url": "https://rss.xcancel.com/aisearchio/rss",
      "fetcher": "nitter",                                                                                                                                                                                                                              
      "category": "social",                                                        
      "tags": ["AI"]                                                                                                                                                                                                                                    
    },                                                                             
    {                                                                                                                                                                                                                                                   
      "name": "NullSecX",                                                          
      "url": "https://www.odysee.com/$/rss/@NullSecurityX:0",                                                                                                                                                                                           
      "fetcher": "odysee",                                                                                                                                                                                                                              
      "category": "social",                                                                                                                                                                                                                             
      "tags": ["Security"]                                                                                                                                                                                                                              
    },                                                                                                                                                                                                                                                  
    {                                                                              
      "name": "Devoxx",
      "url": "https://youtube.com/feeds/videos.xml?channel_id=UCCBVCTuk6uJrN3iFV_3vurg",                                                                                                                                                                
      "fetcher": "youtube",                                                                                                                                                                                                                             
      "category": "social",                                                                                                                                                                                                                             
      "tags": ["Development", "Java", "Video"]                                                                                                                                                                                                          
    },                                                                                                                                                                                                                                                  
    {
      "name": "LangChain4j",                                                                                                                                                                                                                            
      "url": "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=langchain4j.dev",                                                                                                                                                      
      "fetcher": "bluesky",                                                                                                                                                                                                                             
      "category": "social",                                                                                                                                                                                                                             
      "tags": ["Java", "Development", "AI"]                                                                                                                                                                                                             
    }                                                                                                                                                                                                                                                   
  ]
                                                                                                                                                                                                                                                        
  Field rules                                                                      

  ┌──────────┬──────────┬──────────────────┬────────────────────────────────────────────────────────────────────────┐
  │  Field   │ Required │       Type       │                                 Notes                                  │
  ├──────────┼──────────┼──────────────────┼────────────────────────────────────────────────────────────────────────┤
  │ name     │ yes      │ string           │ Display name. Fallback if feed has no title.                           │
  ├──────────┼──────────┼──────────────────┼────────────────────────────────────────────────────────────────────────┤
  │ url      │ yes      │ string           │ Validated as well-formed URL; per-fetcher format checks.               │                                                                                                                                   
  ├──────────┼──────────┼──────────────────┼────────────────────────────────────────────────────────────────────────┤                                                                                                                                   
  │ fetcher  │ yes      │ enum             │ rss, nitter, bluesky, odysee, youtube, reddit, nostr.                  │                                                                                                                                   
  ├──────────┼──────────┼──────────────────┼────────────────────────────────────────────────────────────────────────┤                                                                                                                                   
  │ category │ yes      │ enum             │ news, blog, social. Drives socials auto-tag for social.                │
  ├──────────┼──────────┼──────────────────┼────────────────────────────────────────────────────────────────────────┤                                                                                                                                   
  │ tags     │ yes, ≥1  │ array of strings │ Tier-1 controlled vocab. Union across all entries seeds the tag table. │
  └──────────┴──────────┴──────────────────┴────────────────────────────────────────────────────────────────────────┘                                                                                                                                   
                                                                                   
  Loader behavior                                                                                                                                                                                                                                       
                                                                                   
  On Collector startup (after Flyway):                                                                                                                                                                                                                  
   
  1. Read the file. Validate against the schema. Any error halts startup with a clear message.                                                                                                                                                          
  2. For each entry, upsert into source keyed by (fetcher, url):                   
    - INSERT if absent.                                                                                                                                                                                                                                 
    - UPDATE name, category, bootstrap_tags if differ. Never delete; admin uses /remove-source.
  3. Union of tags across all entries is upserted into tag with source_origin='bootstrap'.                                                                                                                                                              
  4. Audit row: BOOTSTRAP_SOURCES, with file SHA-256 and entry count.                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Editing the file and restarting updates names/categories/tags; sources removed from the file remain in DB until admin /remove-source.                                                                                                                 
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.7 Local development with docker-compose                                        
                                                                                                                                                                                                                                                        
  A docker-compose.yml ships with the repo. Brings up:
                                                                                                                                                                                                                                                        
  - postgres:16 with pgvector extension, init-loaded from docker/postgres-init.sql (creates roles, extensions, empty DB).                                                                                                                               
  - ollama/ollama:latest with a volume for downloaded models.                                                                                                                                                                                           
  - A stub simplex-cli container (or a placeholder; in v1 the operator runs SimpleX locally).                                                                                                                                                           
  - infochat-collector (Quarkus dev or built jar).                                                                                                                                                                                                      
  - infochat-provider.                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  # Start everything                                                                                                                                                                                                                                    
  docker compose up -d                                                             
                                                                                                                                                                                                                                                        
  # First-time model pull
  docker compose exec ollama ollama pull llama3.1:8b                                                                                                                                                                                                    
  docker compose exec ollama ollama pull llama3.2:3b                                                                                                                                                                                                    
  docker compose exec ollama ollama pull nomic-embed-text
                                                                                                                                                                                                                                                        
  # Run the apps in dev mode against compose-managed Postgres + Ollama                                                                                                                                                                                  
  mvn -pl infochat-collector quarkus:dev
  mvn -pl infochat-provider  quarkus:dev                                                                                                                                                                                                                
                                                                                   
  For tests/CI, swap infochat.adapter=inmemory to bypass SimpleX.                                                                                                                                                                                       
                                                                                   
  docker/postgres-init.sql                                                                                                                                                                                                                              
                                                                                   
  Idempotent role/database/extension setup. Runs once on container init.                                                                                                                                                                                
   
  CREATE ROLE infochat WITH LOGIN PASSWORD 'changeme' SUPERUSER;                                                                                                                                                                                        
  CREATE ROLE infochat_collector WITH LOGIN PASSWORD 'changeme';                                                                                                                                                                                        
  CREATE ROLE infochat_provider WITH LOGIN PASSWORD 'changeme';                                                                                                                                                                                         
  CREATE DATABASE infochat OWNER infochat;                                                                                                                                                                                                              
  \c infochat                                                                                                                                                                                                                                           
  CREATE EXTENSION IF NOT EXISTS vector;                                                                                                                                                                                                                
  CREATE EXTENSION IF NOT EXISTS pgcrypto;     -- for gen_random_uuid()            
  -- Grants are applied by Flyway migration V0001__roles.sql                                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  In production the init script runs once with strong passwords from env-substituted secrets.                                                                                                                                                           
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.8 Production deployment                                                        
                           
  7.8.1 Single-host (recommended for v1)
                                                                                                                                                                                                                                                        
  A modest Linux box (4 vCPU, 8–16 GB RAM, 50 GB disk) runs everything. Recommended layout:                                                                                                                                                             
                                                                                                                                                                                                                                                        
  /opt/infochat/                                                                                                                                                                                                                                        
    ├── current/                       # symlink to releases/<version>             
    ├── releases/                                                                                                                                                                                                                                       
    │   └── 1.0.0/
    │       ├── infochat-collector.jar                                                                                                                                                                                                                  
    │       ├── infochat-provider.jar                                              
    │       └── application.properties                                                                                                                                                                                                                  
    ├── data/                                                                                                                                                                                                                                           
    │   └── postgres/                  # Postgres data directory (bind mount)
    ├── models/                        # Ollama model cache (bind mount)                                                                                                                                                                                
    └── bootstrap-sources.json                                                     
                                                                                                                                                                                                                                                        
  Both services run as systemd units, started in dependency order:                                                                                                                                                                                      
   
  postgresql.service                                                                                                                                                                                                                                    
    → ollama.service                                                               
      → infochat-collector.service                                                                                                                                                                                                                      
      → infochat-provider.service                                                  
          → simplex-cli.service       (or operator runs SimpleX manually)                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  systemd unit fragment for the provider:                                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  [Service]                                                                                                                                                                                                                                             
  EnvironmentFile=/opt/infochat/secrets.env                                        
  WorkingDirectory=/opt/infochat/current
  ExecStart=/usr/bin/java -jar infochat-provider.jar                                                                                                                                                                                                    
  Restart=on-failure
  RestartSec=5                                                                                                                                                                                                                                          
  StartLimitBurst=10                                                               
  StartLimitIntervalSec=300                                                                                                                                                                                                                             
                                                                                   
  /opt/infochat/secrets.env holds env vars (mode 0600, owned by service user).                                                                                                                                                                          
   
  7.8.2 Native image (optional)                                                                                                                                                                                                                         
                                                                                   
  Quarkus supports GraalVM native images. We don't ship them in v1; JVM mode is fine for our footprint. Native may be revisited if we deploy to many small VPSes.                                                                                       
   
  7.8.3 Resource sizing                                                                                                                                                                                                                                 
                                                                                   
  ┌──────────────────────────────────────┬────────────────────┬───────┬─────────────────┬────────────────────────────────────────────────────┐
  │               Profile                │        CPU         │  RAM  │      Disk       │                       Notes                        │
  ├──────────────────────────────────────┼────────────────────┼───────┼─────────────────┼────────────────────────────────────────────────────┤                                                                                                          
  │ laptop (dev)                         │ 4 vCPU             │ 16 GB │ 30 GB           │ Comfortable.                                       │
  ├──────────────────────────────────────┼────────────────────┼───────┼─────────────────┼────────────────────────────────────────────────────┤                                                                                                          
  │ vps (prod, ~50 users / a few groups) │ 4 vCPU             │ 8 GB  │ 30 GB           │ Tight on RAM with 3B model loaded; consider 12 GB. │                                                                                                          
  ├──────────────────────────────────────┼────────────────────┼───────┼─────────────────┼────────────────────────────────────────────────────┤                                                                                                          
  │ pi                                   │ Pi 5 (4 cores ARM) │ 8 GB  │ 32 GB SD or SSD │ SSD strongly recommended; SD wears out.            │                                                                                                          
  ├──────────────────────────────────────┼────────────────────┼───────┼─────────────────┼────────────────────────────────────────────────────┤                                                                                                          
  │ remote                               │ 1 vCPU             │ 1 GB  │ 5 GB            │ Minimal; LLM cost lives at the API provider.       │
  └──────────────────────────────────────┴────────────────────┴───────┴─────────────────┴────────────────────────────────────────────────────┘                                                                                                          
                                                                                   
  7.8.4 Collector + Provider separation                                                                                                                                                                                                                 
                                                                                   
  In v1 the two services are separate JVMs colocated on one host. They communicate only through Postgres (LISTEN/NOTIFY + shared schema). This means either can be restarted independently without affecting the other beyond the duration of the       
  restart.
                                                                                                                                                                                                                                                        
  ---                                                                              
  7.9 Bootstrap & first-run sequence
                                                                                                                                                                                                                                                        
  1. Install Postgres + pgvector. Create roles via postgres-init.sql.
  2. Install Ollama (or llama.cpp). Pull required models per profile.                                                                                                                                                                                   
  3. Place artifacts in /opt/infochat/current.                                                                                                                                                                                                          
  4. Edit application.properties + secrets.env.                                                                                                                                                                                                         
  5. Place bootstrap-sources.json next to the jars.                                                                                                                                                                                                     
  6. Start collector. It runs Flyway, loads bootstrap, idle until provider starts. 
  7. Start provider. It runs Flyway again (idempotent), bootstraps the bot admin                                                                                                                                                                        
     from INFOCHAT_ADMIN_CONTACT_ID, attaches to messaging adapter.                                                                                                                                                                                     
  8. From the configured admin's chat client, send `/help` to the bot. Verify response.                                                                                                                                                                 
  9. Add a personal source: `/add-source --type rss --url ... --tags ai`.                                                                                                                                                                               
  10. Wait one fetch interval; run `/summary -w 1h`. If posts arrive, system is up.                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.10 Backups                                                                                                                                                                                                                                          
                                                                                   
  What to back up

  - Postgres data: full pg_dump -F c daily; WAL archiving optional for PITR.                                                                                                                                                                            
  - application.properties and bootstrap-sources.json: keep in operator's config repo (separate from code repo).
  - Models: not backed up; Ollama re-pulls them.                                                                                                                                                                                                        
  - Audit log: included in DB backup.                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Restore                                                                                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  1. Stop both services.                                                           
  2. pg_restore the most recent backup into a fresh DB.
  3. Start collector, then provider.                                                                                                                                                                                                                    
  4. Verify /audit shows recent events; verify a /summary returns content.
                                                                                                                                                                                                                                                        
  Typical RPO: 24 hours (one nightly backup). RTO: 30 minutes for a small DB.                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  Backup script (cron)                                                                                                                                                                                                                                  
                                                                                   
  0 3 * * * pg_dump -U infochat -F c -f /backups/infochat-$(date +\%Y\%m\%d).pgc infochat
  0 4 * * * find /backups -name 'infochat-*.pgc' -mtime +14 -delete                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.11 Upgrade procedure                                                                                                                                                                                                                                
                                                                                   
  1. Place new jars in /opt/infochat/releases/<new-version>/.
  2. Diff application.properties against the new template; merge any new keys.                                                                                                                                                                          
  3. Stop provider (systemctl stop infochat-provider).                                                                                                                                                                                                  
  4. Stop collector.                                                                                                                                                                                                                                    
  5. Update the current symlink to the new version.                                                                                                                                                                                                     
  6. Start collector. Flyway runs migrations. Watch for ERROR.                                                                                                                                                                                          
  7. Start provider.                                                                                                                                                                                                                                    
  8. Smoke check: /help, /summary -w 1h, /status (admin).                                                                                                                                                                                               
  9. Roll back: revert symlink, restart. Schema migrations are forward-compatible — rollback within one minor version is supported by reverse migrations shipped alongside forward ones; cross-major rollbacks require restoring from backup.           
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.12 Health checks and probes                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  Both services expose:
                                                                                                                                                                                                                                                        
  - GET /q/health/live — process is up                                                                                                                                                                                                                  
  - GET /q/health/ready — DB reachable; (provider) adapter connected; (collector) eval queue and scheduler healthy
  - GET /q/metrics — Micrometer/Prometheus                                                                                                                                                                                                              
                                                                                   
  Recommended monitoring:                                                                                                                                                                                                                               
                                                                                   
  - Liveness: kill if /live fails 3× in 30s (systemd WatchdogSec).                                                                                                                                                                                      
  - Readiness: alert if /ready returns non-200 for > 5 min.                        
  - Metrics to watch (panel suggestions):                                                                                                                                                                                                               
    - adapter.connection.status{adapter="simplex"} should be 1.                    
    - llm.calls.total{outcome="fail"} rate-of-change.                                                                                                                                                                                                   
    - eval.queue.size near infochat.eval.queue-size for too long → fetcher back-pressure.                                                                                                                                                               
    - embedding.calls.total{outcome="fallback"} non-zero → model down.                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.13 Logs                                                                                                                                                                                                                                             
                                                                                   
  Quarkus structured JSON logs (quarkus.log.console.json=true) recommended in production. Critical event categories:
                                                                                                                                                                                                                                                        
  - AdminBootstrap — once at startup                                                                                                                                                                                                                    
  - AdapterRegistry — adapter selected, connection events                                                                                                                                                                                               
  - BootstrapLoader — sources file loaded; entry count and SHA                                                                                                                                                                                          
  - Stage1Sanitizer / Stage2Judge — flagged spans (with redacted previews)                                                                                                                                                                              
  - LinkingJob / PartitionPruner / TtlPruner — scheduled jobs with row counts                                                                                                                                                                           
  - LlmRouter — provider chosen for each task at startup                                                                                                                                                                                                
  - RateLimiter — overflow events with redacted contact id                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  Log retention: 14 days local; ship to centralized log store at operator's discretion.                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.14 Operator runbook (common tasks)                                             
                                                                                                                                                                                                                                                        
  "The bot isn't responding."
                                                                                                                                                                                                                                                        
  1. systemctl status infochat-provider — running?                                                                                                                                                                                                      
  2. journalctl -u infochat-provider -n 200 — errors?
  3. curl localhost:8081/q/health/ready — 200?                                                                                                                                                                                                          
  4. Adapter status in /status (admin) or via metrics: adapter.connection.status = 1?                                                                                                                                                                   
  5. SimpleX side: is simplex-cli running?                                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  "A source is producing junk."                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  /list-sources --all                                                              
  /remove-source <id> confirm                                                                                                                                                                                                                           
   
  "Quarantine queue is growing."                                                                                                                                                                                                                        
                                                                                   
  /quarantine list                                                                                                                                                                                                                                      
  /quarantine approve <id>                                                         
  /quarantine reject <id>

  For raw HTML inspection, psql with the admin role:                                                                                                                                                                                                    
   
  SELECT q.id, q.original_html                                                                                                                                                                                                                          
    FROM quarantine q                                                              
   WHERE q.id = '...'
     AND q.status = 'PENDING';                                                                                                                                                                                                                          
   
  "LLM is down."                                                                                                                                                                                                                                        
                                                                                   
  - Check ollama list / ollama ps (or remote provider health).                                                                                                                                                                                          
  - Eval pipeline back-pressures; user-facing /summary returns the degraded "raw posts list" form.
  - Admin notifications throttle to once per 15 min — check inbox if unsure.                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  "Disk filling up."                                                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  - post table grows linearly with feed volume × 30 days. Inspect pg_total_relation_size('post').                                                                                                                                                       
  - audit_log grows with admin activity. 365-day TTL handles it.
  - Ollama models: 4–8 GB each. Trim unused models from ~/.ollama.                                                                                                                                                                                      
                                                                                                                                                                                                                                                        
  "Switch to remote LLM for a heavy summary."                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  Temporary override (env var, no restart needed if Quarkus is configured for runtime config):                                                                                                                                                          
                                                                                   
  INFOCHAT_LLM_SUMMARIZER_PROVIDER=openai-compatible                                                                                                                                                                                                    
  INFOCHAT_LLM_SUMMARIZER_BASE_URL=https://api.openai.com/v1                                                                                                                                                                                            
  INFOCHAT_LLM_SUMMARIZER_API_KEY=...
  INFOCHAT_LLM_SUMMARIZER_MODEL=gpt-4o-mini                                                                                                                                                                                                             
                                                                                   
  Permanent: edit application.properties, restart provider.                                                                                                                                                                                             
   
  ---                                                                                                                                                                                                                                                   
  7.15 Disaster scenarios                                                          

  ┌──────────────────────────────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │                 Scenario                 │                                                                                               Recovery                                                                                               │
  ├──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ DB corruption                            │ Restore from pg_dump. Loss of up-to-24h of new posts. Saved posts and admin state preserved.                                                                                                         │
  ├──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ LLM outage > 1 day                       │ Eval pipeline degrades; user-facing summaries become "raw post lists". Restore Ollama / switch provider; the eval queue auto-drains via outbox rehydrator.                                           │   
  ├──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
  │ Adapter outage > 1 day                   │ Bot appears offline. Fix adapter; on reconnect, queued outbounds (if any) flush. No state loss.                                                                                                      │   
  ├──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
  │ Profile mistake (e.g., switched          │ Run scripts/reembed.sh. 4-day window self-heals.                                                                                                                                                     │
  │ embedding dimension)                     │                                                                                                                                                                                                      │   
  ├──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ Compromised API key                      │ Rotate the env var. Restart provider. Add an audit row noting rotation reason.                                                                                                                       │   
  ├──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ Lost admin contact id                    │ Edit application.properties to point to a different SimpleX contact. Restart provider. The new contact becomes admin on first message. Old admin keeps is_admin=true; bot admin can /revoke-admin    │   
  │                                          │ after sanity-check.                                                                                                                                                                                  │
  ├──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
  │ Bot account compromised                  │ Rotate SIMPLEX_SESSION_TOKEN. If the account is fully lost, the operator creates a new bot account in SimpleX, updates infochat.admin.contact-id to a new admin (or keeps the same if they           │
  │                                          │ reconnect), and reissues invite links to known users. Source data is untouched.                                                                                                                      │   
  └──────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.16 What's intentionally NOT in v1 deployment
                                                                                                                                                                                                                                                        
  - Kubernetes manifests — docker-compose and systemd cover v1. K8s is operator-extra-credit.
  - Auto-scaling — both services are stateless w.r.t. Postgres; horizontal scale is possible but unneeded for v1 footprint.                                                                                                                             
  - Multi-tenant deployments — one Provider serves one operator's user base. Multi-tenant is v2+ and requires schema-level tenant id.                                                                                                                   
  - TLS termination inside the apps — bot ↔ messaging app handles its own encryption; ops puts a reverse proxy in front of /q/metrics if exposing externally.                                                                                           
  - Centralized logging / SIEM integration — JSON logs are emitted; ingestion is operator's choice.                                                                                                                                                     
  - Blue-green deployment — single-host, brief downtime acceptable in v1.                                                                                                                                                                               
  - Automated DB failover — operator's call (managed Postgres or self-managed standby).                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  7.17 Pre-flight checklist for first prod deploy                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  - DNS / network: Provider can reach Postgres and Ollama.
  - DB roles created with strong passwords; passwords in secrets.env (mode 0600).                                                                                                                                                                       
  - INFOCHAT_ADMIN_CONTACT_ID set to your real SimpleX contact id.                                                                                                                                                                                      
  - bootstrap-sources.json validated; URLs reachable from the host.                                                                                                                                                                                     
  - Ollama models pre-pulled.                                                                                                                                                                                                                           
  - Disk has ≥ 30 GB free, swap enabled.                                                                                                                                                                                                                
  - Backups scheduled (cron + pg_dump script tested on a non-prod DB first).                                                                                                                                                                            
  - systemd units have Restart=on-failure.                                                                                                                                                                                                              
  - First boot logs reviewed: profile detected, sources loaded, admin bootstrapped, adapter connected.                                                                                                                                                  
  - Smoke: /help, /add-source, /summary -w 1h all work end-to-end.                                                                                                                                                                                      
  - /q/health/ready is 200.                                                                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  --- 
