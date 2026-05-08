> **Status: design notes, not spec.**
> Implementation details below (DDL, class names, package layout, property keys,
> retry counts, regex strings, etc.) are working notes that may change without a
> spec amendment. The authoritative *what & why* lives in `docs/spec/`.

  ---                                                                                                                                                                                          
                                
  # 02 — Database schema
                                                                                   
  PostgreSQL 16+ with `pgvector` 0.7+ extension.
                                             
  Migrations live in `infochat-core/src/main/resources/db/migration/` and are applied by Flyway on collector + provider startup. Both services share one schema; they differ only in DB role:
                                                                
  - `infochat_collector` — writes to `post`, `post_entity`, `post_embedding`, `post_reference`, `quarantine`, `tag`, `source`. Reads everything.
  - `infochat_provider` — writes to `users`, `group_membership`, `source_subscription`, `scope_tag`, `scope_preferences`, `saved_post`, `chat_memory`, `audit_log`. Reads everything except
                                                             
  Both services share `infochat_listen` (LISTEN/NOTIFY only, no DML).
                                                                                                                                                                                               
  ---                                                                                                                                                                                          
                                                                                                                                                                                               
  ## 2.1 Identity & access
                                                                                                                                                                                               
  ### `users`
                                                                                                                                                                                               
  ```sql
  CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id      TEXT NOT NULL UNIQUE,           -- SimpleX contact ID (cryptographic)
    display_name    TEXT,                            -- last-seen display name from adapter (informational; sanitized at write time per 04-security.md §4.8)
    is_admin        BOOLEAN NOT NULL DEFAULT FALSE,
    is_banned       BOOLEAN NOT NULL DEFAULT FALSE,
    banned_at       TIMESTAMPTZ,
    banned_by       UUID REFERENCES users(id),
    ban_reason      TEXT,
    -- Registration provenance. Drives the DM invite gate (§4.4 step 6) and
    -- the pre-ban /unban delete rule (§4.5). Set on INSERT, never demoted.
    --   'preban'      -- row minted by /ban against unknown contact; deleted on /unban
    --   'group_only'  -- auto-registered via group @mention; DM blocked until invite or /vouch
    --   'invited'     -- DM invite accepted; full DM access (subject to probation + ban)
    --   'bootstrap'   -- created by @Startup admin bootstrap; exempt from invite gate
    registration_state TEXT NOT NULL,
    probation_until TIMESTAMPTZ,                     -- D45 slow-start; NULL = full access
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ,
    save_count      INT NOT NULL DEFAULT 0,         -- denormalized COUNT(*) of saved_post rows for this user;
                                                    -- maintained by trigger; powers the 1000-save cap check
                                                    -- in O(1) instead of a SELECT COUNT(*).
    CONSTRAINT users_registration_state_chk
      CHECK (registration_state IN ('preban','group_only','invited','bootstrap'))
  );
                                                                                                                                                                                               
  CREATE INDEX idx_users_admin    ON users(is_admin)  WHERE is_admin;
  CREATE INDEX idx_users_banned   ON users(is_banned) WHERE is_banned;
                                                                                                                                                                                               
  -- Last-admin protection (cannot revoke last admin, cannot ban last admin):
  -- Enforced by trigger on UPDATE.
  --
  -- IMPORTANT: the trigger body MUST serialize concurrent revocations.
  -- A naive `SELECT COUNT(*) FROM users WHERE is_admin = true` under
  -- READ COMMITTED is unsafe: two simultaneous /revoke-admin (or ban)
  -- transactions targeting different admin rows both read the pre-state
  -- count of 2, both proceed, both commit, leaving zero admins.
  --
  -- Implementation (trg_last_admin_protection on users BEFORE UPDATE):
  --
  --   CREATE OR REPLACE FUNCTION trg_last_admin_protection()
  --   RETURNS TRIGGER AS $$
  --   DECLARE
  --     remaining INT;
  --   BEGIN
  --     -- Lock the admin rows for the duration of this transaction so
  --     -- a concurrent revoke against a different admin row sees this
  --     -- transaction's pending change before computing its own count.
  --     LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
  --
  --     IF (OLD.is_admin = true AND NEW.is_admin = false)
  --        OR (OLD.is_banned = false AND NEW.is_banned = true AND OLD.is_admin = true) THEN
  --       SELECT count(*) INTO remaining
  --       FROM users
  --       WHERE is_admin = true
  --         AND is_banned = false
  --         AND id <> NEW.id;
  --       IF remaining < 1 THEN
  --         RAISE EXCEPTION 'last_admin_protection: cannot leave the deployment with zero bot admins';
  --       END IF;
  --     END IF;
  --     RETURN NEW;
  --   END;
  --   $$ LANGUAGE plpgsql;
  --
  -- The lock is released on transaction commit/rollback; held for the duration
  -- of a single revoke (microseconds in practice) so it does not interfere with
  -- normal read traffic. SHARE ROW EXCLUSIVE blocks other writers but allows
  -- concurrent SELECT, which is the right trade-off for a trigger on the rare
  -- privileged-mutation path.
  --
  -- The matching DELETE-path trigger uses the same lock + count pattern.
                                                                                                                                                                                               
  groups
                                          
  CREATE TABLE groups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    adapter_group_id TEXT NOT NULL,                  -- e.g., SimpleX group ID
    display_name    TEXT,
    timezone        TEXT NOT NULL DEFAULT 'UTC',     -- IANA tz, drives 8am/8pm slots
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (adapter_group_id)
  );
                                                                                   
  group_membership
                                                                                   
  CREATE TABLE group_membership (
    group_id        UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    is_group_admin  BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
  );
                                                                                   
  CREATE INDEX idx_group_membership_user ON group_membership(user_id);

  -- Auto-promote race protection: at most one group admin can be created via the
  -- "first @mention wins" bootstrap path. Two simultaneous @mention messages can
  -- both pass the "no admin yet" check before either INSERT lands; this partial
  -- unique index makes the second INSERT no-op on conflict instead of producing
  -- two admins. Bot admins can still set is_group_admin=true via /promote because
  -- the previous admin is demoted first inside the same transaction (see
  -- 04-security §4.4).
  CREATE UNIQUE INDEX one_admin_per_group
    ON group_membership(group_id) WHERE is_group_admin = true;
                               
  audit_log
                            
  CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id   UUID REFERENCES users(id),
    action          TEXT NOT NULL,                   -- 'BOOTSTRAP_ADMIN','GRANT_ADMIN','BAN','PROMOTE_GROUP_ADMIN','REMOVE_SOURCE','APPROVE_QUARANTINE',...
    target_kind     TEXT NOT NULL,                   -- 'user','group','source','quarantine','tag'
    target_id       TEXT NOT NULL,
    scope_kind      TEXT,                            -- 'global','group:<id>'
    details_json    JSONB
  );
                                       
  CREATE INDEX idx_audit_ts            ON audit_log(ts DESC);
  CREATE INDEX idx_audit_actor         ON audit_log(actor_user_id, ts DESC);
  CREATE INDEX idx_audit_action_target ON audit_log(action, target_kind, target_id);
                                                                                                                                                                                               
  ---                                                                                                                                                                                          
  2.2 Sources & tags
                                                                                   
  source
                                                                                                                                                                                               
  CREATE TABLE source (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fetcher         TEXT NOT NULL,                   -- 'rss','nitter','bluesky','odysee','youtube','reddit','nostr'
    url             TEXT NOT NULL,
    display_name    TEXT NOT NULL,
    category        TEXT NOT NULL,                   -- 'news','blog','social'
    bootstrap_tags  TEXT[] NOT NULL DEFAULT '{}',    -- fallback tags when LLM tagger fails
    status          TEXT NOT NULL DEFAULT 'active',  -- 'active','failed','disabled'
    added_by        UUID REFERENCES users(id) ON DELETE SET NULL,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_fetch_at   TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    consecutive_failures INT NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMPTZ,                     -- soft-delete; NULL = active
    deleted_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE (fetcher, url)
  );

  -- Soft-delete semantics: /remove-source sets deleted_at and stops the fetcher
  -- for that source. Existing post rows remain (post.source_id is ON DELETE
  -- RESTRICT, so saved_post references still resolve). Hard delete is forbidden;
  -- the (fetcher, url) UNIQUE constraint means re-adding via /add-source clears
  -- deleted_at on the existing row instead of inserting. Bootstrap loader skips
  -- rows where deleted_at IS NOT NULL.
                                                                                                                                                                                               
  CREATE INDEX idx_source_status ON source(status) WHERE deleted_at IS NULL;
                                                                                                                                                                                               
  tag (Tier-1 controlled vocab)
                                                                                                                                                                                               
  CREATE TABLE tag (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL UNIQUE,                -- normalized lowercase
    display     TEXT NOT NULL,                       -- original casing for output
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID REFERENCES users(id),
    source_origin TEXT NOT NULL DEFAULT 'bootstrap' -- 'bootstrap','user'
  );
                                                                                                                                                                                               
  source_subscription (which scopes follow which sources)
                              
  CREATE TABLE source_subscription (
    scope_kind  TEXT NOT NULL,                       -- 'dm' or 'group'; for 'dm', scope_id = users.id
    scope_id    UUID NOT NULL,
    source_id   UUID NOT NULL REFERENCES source(id) ON DELETE CASCADE,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (scope_kind, scope_id, source_id)
  );
                                                                                                                                                                                               
  CREATE INDEX idx_source_sub_source ON source_subscription(source_id);
                                                                                                                                                                                               
  scope_tag (which tags appear in periodic summaries)
                                                                                                                                                                                               
  CREATE TABLE scope_tag (
    scope_kind  TEXT NOT NULL,                       -- 'dm' or 'group'
    scope_id    UUID NOT NULL,
    tag_id      UUID NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    PRIMARY KEY (scope_kind, scope_id, tag_id)
  );

  scope_preferences

  CREATE TABLE scope_preferences (
    scope_kind  TEXT NOT NULL,                       -- 'dm' or 'group'
    scope_id    UUID NOT NULL,
    language    TEXT NOT NULL DEFAULT 'en',          -- ISO 639-1; 'en','cs',...
    timezone    TEXT,                                -- override; defaults to groups.timezone or UTC
    digest_enabled BOOLEAN NOT NULL DEFAULT TRUE,    -- send 8am/8pm digest?
    tag_follow_mode TEXT NOT NULL DEFAULT 'implicit_source_tags',
                                                     -- 'implicit_source_tags' = digest covers tags
                                                     -- carried by subscribed sources;
                                                     -- 'explicit_scope_tags' = only tags listed in
                                                     -- scope_tag are included.
    tag_subscription_version    BIGINT NOT NULL DEFAULT 0,
                                                     -- monotonically incremented in the same
                                                     -- transaction as /follow-tag, /unfollow-tag,
                                                     -- and any direct mutation of scope_tag.
                                                     -- Folded into the periodic-digest cache key
                                                     -- (group_id, slot, tag_v, src_v) so a tag-
                                                     -- subscription change yields a fresh cache
                                                     -- miss without an explicit invalidation pass.
                                                     -- See [01-architecture.md §1.4.1](01-architecture.md).
    source_subscription_version BIGINT NOT NULL DEFAULT 0,
                                                     -- same pattern, incremented on /add-source,
                                                     -- /remove-source, /unfollow-source for this scope.
    PRIMARY KEY (scope_kind, scope_id)
  );

  ---
  2.3 Posts (ingest)

  post

  CREATE TABLE post (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id       UUID NOT NULL REFERENCES source(id) ON DELETE RESTRICT,
    external_id     TEXT NOT NULL CHECK (length(external_id) <= 2048),
                                                     -- guid, item link, tweet id, etc.
                                                     -- 2KB cap protects against malicious feeds
                                                     -- pushing multi-MB GUIDs (TOAST DoS).
                                                     -- Real-world GUIDs are URLs <256 chars.
                                                     -- Beyond the cap, the fetcher hashes the
                                                     -- raw value (sha256-hex) and stores the digest.
    url             TEXT,
    title           TEXT NOT NULL,
    body            TEXT,                            -- always plain text (HTML stripped at ingest);
                                                     -- length(body) measures characters of plain text.
    body_summary    TEXT,                            -- LLM-generated abstract; populated when length(body) > 2000 chars (see 05-llm §5.4)
    author          TEXT,                            -- account or byline
    published_at    TIMESTAMPTZ,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at        TIMESTAMPTZ,                     -- set when status transitions to READY;
                                                     -- drives Provider startup reconciliation
                                                     -- (see 01-architecture §1.5 / provider_state).
    last_linked_at  TIMESTAMPTZ,                     -- LinkingJob cursor: NULL => never linked.
                                                     -- LinkingJob processes posts where
                                                     -- last_linked_at IS NULL OR last_linked_at < fetched_at.
    status          TEXT NOT NULL DEFAULT 'RAW',     -- 'RAW','EVALUATING','READY','QUARANTINED','FAILED'
    stage1_flagged  BOOLEAN NOT NULL DEFAULT FALSE,
    stage2_failed   BOOLEAN NOT NULL DEFAULT FALSE,  -- true if Stage 2 LLM errored after retry; post released READY-with-redactions (when release-on-stage2-failure is true; otherwise stays QUARANTINED)
    tagger_fallback BOOLEAN NOT NULL DEFAULT FALSE,  -- true if tags came from source.bootstrap_tags
    is_saved        BOOLEAN NOT NULL DEFAULT FALSE,  -- maintained by trigger on saved_post insert/delete;
                                                     -- enables index-friendly TTL pruner (see 2.6 below).
    social_score    INT,                             -- see 05-llm §5.4 for the formula (currently 2*reposts + likes)
    likes           INT,
    reposts         INT,
    UNIQUE (source_id, external_id)
  );

  CREATE INDEX idx_post_status_fetched   ON post(status, fetched_at DESC);
  CREATE INDEX idx_post_source           ON post(source_id, fetched_at DESC);
  CREATE INDEX idx_post_published        ON post(published_at DESC);
  -- Provider startup reconciler scan (see 01-architecture §1.5):
  CREATE INDEX idx_post_ready_at         ON post(ready_at) WHERE status = 'READY';
  -- LinkingJob driving-set scan (see 01-architecture §1.3):
  CREATE INDEX idx_post_link_cursor      ON post(fetched_at)
    WHERE status = 'READY' AND (last_linked_at IS NULL OR last_linked_at < fetched_at);
  -- TTL pruner: index-only scan for non-saved posts past retention.
  CREATE INDEX idx_post_prune_candidate  ON post(fetched_at) WHERE is_saved = false;

  post_user_tag (Tier-1 assignment, many-to-many)

  CREATE TABLE post_user_tag (
    post_id     UUID NOT NULL REFERENCES post(id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
  );

  CREATE INDEX idx_post_user_tag_tag ON post_user_tag(tag_id);

  The `socials` tag is a Tier-1 controlled-vocabulary tag (seeded by the bootstrap loader,
  same as any other tag). It is auto-assigned by the tagger to every post whose
  `source.category = 'social'`, in addition to whatever other tags the LLM tagger or
  source.bootstrap_tags produce. Users and groups can follow/unfollow `socials` like any
  other tag (e.g. `/follow socials`, `/unfollow socials`).

  ---
  2.4 Tier-2 cross-linking (TTL 4 days, partitioned)
                                          
  post_entity
                                       
  CREATE TABLE post_entity ( 
    post_id     UUID NOT NULL, 
    entity_text TEXT NOT NULL,                       -- normalized (lowercased, stripped)
    entity_type TEXT NOT NULL,                       -- 'cve','product','org','person','location','project'
    fetched_at  TIMESTAMPTZ NOT NULL,                -- duplicates post.fetched_at for partition pruning
    PRIMARY KEY (post_id, entity_text, entity_type)
  ) PARTITION BY RANGE (fetched_at);
                                                                                                                                                                                                                                                        
  -- Daily partitions; create_post_entity_partitions() runs nightly to add tomorrow,
  -- and DROP PARTITION on partitions older than 4 days.
                                          
  CREATE INDEX idx_post_entity_text ON post_entity(entity_text, entity_type);
                                                                                                                                                                                                                                                        
  post_embedding
                                                                                                                                                                                                                                                        
  Dimension is fixed per profile. The migration creates the column matching the active profile's embedding model dimension. Switching profiles requires a manual migration that adds a new column or table (see §2.7 below).
                                                                                                                                                                                                                                                        
  -- For laptop/vps/remote profile (768-d):
  CREATE TABLE post_embedding (
    post_id          UUID NOT NULL,
    embedding        vector(768) NOT NULL,
    embedding_model  TEXT NOT NULL,                  -- e.g., 'nomic-embed-text:v1'
    fetched_at       TIMESTAMPTZ NOT NULL,
    -- Postgres requires the partition key column in every unique constraint on a
    -- partitioned table. post_id remains globally unique by virtue of post.id PK,
    -- so (post_id, fetched_at) is effectively per-post within the active 4-day window.
    PRIMARY KEY (post_id, fetched_at)
  ) PARTITION BY RANGE (fetched_at);
                                                                                                                                                                                                                                                        
  -- Profile-driven index (created in same migration based on infochat.profile):
  -- HNSW (laptop/vps/remote):
  CREATE INDEX ON post_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
                                                                                                                                                                                                                                                        
  -- IVFFlat (pi):
  -- CREATE INDEX ON post_embedding USING ivfflat (embedding vector_cosine_ops)
  --   WITH (lists = 100);
                                                                                                                                                                                                                                                        
  For pi profile, the column is vector(384) (matching all-minilm).
                                                                                                                                                                                                                                                        
  post_reference
                                                                                                                                                                                                                                                        
  CREATE TABLE post_reference (
    from_post   UUID NOT NULL,
    to_post     UUID NOT NULL,
    link_type   TEXT NOT NULL,                       -- 'entity','semantic'
    score       REAL NOT NULL,                       -- shared-entity count or cosine similarity
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Partition key column required in PK; one row per (from,to,type) per day
    -- is acceptable since LinkingJob runs once per partition window.
    PRIMARY KEY (from_post, to_post, link_type, created_at)
  ) PARTITION BY RANGE (created_at);
                                          
  CREATE INDEX idx_post_ref_from ON post_reference(from_post, link_type);
  CREATE INDEX idx_post_ref_to   ON post_reference(to_post);
                                       
  References are directional but always written in both directions by LinkingJob (A→B and B→A rows) to keep cluster-walk queries simple. Cap N=10 outbound links per post (highest score wins).
                               
  Partition lifecycle
                             
  A nightly partition_pruner job:
  1. Creates _yyyymmdd partition for tomorrow on all three partitioned tables.
  2. Drops partitions whose end date is older than 4 days.
                                                                                                                                                                                                                                                        
  No row-level deletes. Drop is O(1), no index bloat.
                                          
  ---                                                                                                                                                                                                                                                   
  2.5 Quarantine
                                                                                                                                                                                                                                                        
  quarantine
                                                                                                                                                                                                                                                        
  CREATE TABLE quarantine (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id         UUID NOT NULL REFERENCES post(id) ON DELETE CASCADE,
    flagged_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    flagged_by      TEXT NOT NULL,                   -- 'stage1','stage2'
    rule_id         TEXT,                            -- which Stage 1 rule, or model name
    span_start      INT,                             -- byte offset in original body
    span_end        INT,
    original_html   TEXT NOT NULL,                   -- the suspicious span; ONLY readable via admin role
    placeholder_id  TEXT NOT NULL,                   -- inserted into post.body in place of suspicious span
    status          TEXT NOT NULL DEFAULT 'PENDING', -- 'PENDING','APPROVED','REJECTED'
    reviewed_at     TIMESTAMPTZ,
    reviewed_by     UUID REFERENCES users(id),
    review_note     TEXT
  );
                                                                                                                                                                                                                                                        
  CREATE INDEX idx_quarantine_status ON quarantine(status, flagged_at);
                                                                                                                                                                                                                                                        
  The infochat_provider role has SELECT on a quarantine_review view (excluding original_html) so admins can list/approve via chat commands. Reading the raw HTML is restricted to a manual psql session with the admin DB role.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  2.6 User-scoped state
                                                                                                                                                                                                                                                        
  saved_post (the /save library — per-user, never per-group)
                                                                                                                                                                                                                                                        
  CREATE TABLE saved_post (
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id          UUID NOT NULL REFERENCES post(id) ON DELETE RESTRICT,  -- prevents pruning while saved
    saved_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    personal_tags    TEXT[] NOT NULL DEFAULT '{}',
    note             TEXT,
    PRIMARY KEY (user_id, post_id)
  );

  CREATE INDEX idx_saved_user_tags ON saved_post USING gin (personal_tags);
  CREATE INDEX idx_saved_user_at   ON saved_post(user_id, saved_at DESC);

  -- Denormalized counters maintained by trigger:
  --   ON saved_post INSERT: UPDATE post SET is_saved = true WHERE id = NEW.post_id;
  --                         UPDATE users.save_count = save_count + 1
  --                                  WHERE id = NEW.user_id.
  --   ON saved_post DELETE: UPDATE post SET is_saved = false IFF no other saved_post
  --                         row references this post; UPDATE users.save_count = save_count - 1.
  -- Cap of 1000 saves per user is enforced BEFORE INSERT by checking users.save_count
  -- (O(1) instead of COUNT(*) on saved_post).
  -- users.save_count is added to the users table (NOT NULL DEFAULT 0).

  Note on TTL pruning: post.is_saved (denormalized via the trigger above) is what makes the
  TTL pruner skip saved posts. The pruner runs:

      DELETE FROM post
       WHERE fetched_at < now() - interval '30 days'
         AND is_saved = false;

  This uses the partial index `idx_post_prune_candidate ON post(fetched_at) WHERE is_saved = false`
  for an index-only scan. The previous `NOT IN (SELECT post_id FROM saved_post)` form
  defeated the partial index and degraded to a full anti-join across the (eventually
  partitioned) post table. ON DELETE RESTRICT on saved_post.post_id remains as a
  belt-and-suspenders safety net so a stale `is_saved = false` row cannot be pruned
  while a saved_post row still references it.
                                                                                   
  chat_memory (/compress long-term memory)
                                                                                                                                                                                                                                                        
  CREATE TABLE chat_memory (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope_kind  TEXT NOT NULL,                       -- 'dm' or 'group'
    scope_id    UUID NOT NULL,                       -- user_id for dm, group_id for group
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary     TEXT NOT NULL,                       -- 8-10 sentences
    keywords    TEXT[] NOT NULL,                     -- up to 15
    referenced_posts UUID[] NOT NULL DEFAULT '{}',
    referenced_topics UUID[] NOT NULL DEFAULT '{}'
  ); 
                               
  CREATE INDEX idx_chat_memory_scope    ON chat_memory(user_id, scope_kind, scope_id, created_at DESC);
  CREATE INDEX idx_chat_memory_keywords ON chat_memory USING gin (keywords);
                                                                                                                                                                                                                                                        
  Per-(user, scope) isolation: every chat_memory row is tied to one user. In a group, each user has their own memory of their own conversation with the bot. Users never see each other's memory.

  Cap: at most 200 chat_memory rows per (user_id, scope_kind, scope_id). Enforced
  by a BEFORE INSERT trigger that, when the cap is reached, deletes the oldest
  row by created_at ASC (LRU eviction). Mirrors the 1000-save cap pattern for
  saved_post. Documented as v1; richer eviction (recency-weighted, manual /forget)
  is a v2 feature.

  referenced_topics is intentionally ephemeral: topic IDs are computed at query
  time from post_reference connected components (see §2.11) and are valid only
  within the active 4-day partition window. A chat_memory row written today may
  reference topic IDs that no longer resolve in five days. Consumers must treat
  any unresolved entry as a soft miss (skip it) rather than an error.
                                                                                                                                                                                                                                                        
  chat_session (active context window)
                                                                                                                                                                                                                                                        
  CREATE TABLE chat_session (
    user_id      UUID NOT NULL,
    scope_kind   TEXT NOT NULL,                      -- 'dm' or 'group'
    scope_id     UUID NOT NULL,                      -- user_id for dm, group_id for group
    next_seq     INT NOT NULL DEFAULT 0,             -- monotonic seq counter; assigned to each new chat_message row
    token_count  INT NOT NULL DEFAULT 0,             -- denormalized SUM(chat_message.tokens) for this session
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, scope_kind, scope_id)
  );

  chat_message (one row per turn — replaces the v0 JSONB messages array)

  CREATE TABLE chat_message (
    user_id     UUID NOT NULL,
    scope_kind  TEXT NOT NULL,
    scope_id    UUID NOT NULL,
    seq         INT  NOT NULL,                       -- assigned from chat_session.next_seq at insert time
    role        TEXT NOT NULL,                       -- 'system','user','assistant','tool'
    content     TEXT NOT NULL,
    tokens      INT  NOT NULL,
    ts          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, scope_kind, scope_id, seq),
    FOREIGN KEY (user_id, scope_kind, scope_id)
      REFERENCES chat_session(user_id, scope_kind, scope_id) ON DELETE CASCADE
  );

  CREATE INDEX idx_chat_message_session_seq
    ON chat_message(user_id, scope_kind, scope_id, seq);

  Rationale: the previous JSONB-array design rewrote the entire row (TOAST round-trip)
  on every append. Sessions routinely sit at 50–100 messages × ~2KB each, so each
  chat reply paid an O(n) cost in bytes. The child-table form makes append O(1),
  /clear becomes a single DELETE on (user_id, scope_kind, scope_id), and token
  bookkeeping is a SUM maintained by trigger. Auto-compress at 75% of context
  still works the same — it reads the last N rows ORDER BY seq DESC, asks the LLM
  to compress, and writes the result back into chat_memory.

  Triggers:
    ON chat_message INSERT:
      UPDATE chat_session
         SET token_count = token_count + NEW.tokens,
             next_seq    = next_seq + 1,
             updated_at  = now()
       WHERE (user_id, scope_kind, scope_id) =
             (NEW.user_id, NEW.scope_kind, NEW.scope_id);
    ON chat_message DELETE (e.g., compaction):
      UPDATE chat_session SET token_count = token_count - OLD.tokens ... ;
                                                                                                                                                                                                                                                        
  /clear deletes chat_message rows and resets chat_session.token_count + next_seq for
  the (user, scope). It does NOT touch chat_memory.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  2.7 Embedding model migration
                                                                                                                                                                                                                                                        
  When the infochat.embeddings.model (or profile that selects it) changes:
                                                                                                                                                                                                                                                        
  1. Add a new column embedding_v2 vector(N) matching new dimension.
  2. Re-embed all post rows where status='READY' and fetched_at > now() - interval '4 days'. The 4-day window self-heals fast.
  3. Switch the LinkingJob to read embedding_v2.
  4. After 4 days, drop the old column and rename embedding_v2 → embedding.
                                                                                   
  A migration script scripts/reembed.sh automates steps 1–3.
                                       
  ---                                                                                                                                                                                                                                                   
  2.8 Notification channels
                                                                                                                                                                                                                                                        
  PostgreSQL LISTEN/NOTIFY only — no Kafka in v1.
                                                                                                                                                                                                                                                        
  ┌───────────────┬─────────────────────────────────────────────┬──────────────────────────────────────────┬───────────────────────────────────────────────┐
  │    Channel    │                   Sent by                   │               Listened by                │                    Payload                    │
  ├───────────────┼─────────────────────────────────────────────┼──────────────────────────────────────────┼───────────────────────────────────────────────┤
  │ new_post      │ Collector (after READY)                     │ Provider (chat agent cache invalidation) │ {post_id, source_id, tags[]}                  │
  ├───────────────┼─────────────────────────────────────────────┼──────────────────────────────────────────┼───────────────────────────────────────────────┤
  │ quarantine    │ Collector (Stage 1 or 2 flag)               │ Provider (admin notifier)                │ {quarantine_id, post_id, flagged_by, rule_id} │
  ├───────────────┼─────────────────────────────────────────────┼──────────────────────────────────────────┼───────────────────────────────────────────────┤
  │ eval_failure  │ Collector (per-stage failure after retries) │ Provider (admin notifier, throttled)     │ {stage, post_id, error_class}                 │
  ├───────────────┼─────────────────────────────────────────────┼──────────────────────────────────────────┼───────────────────────────────────────────────┤
  │ source_failed │ Collector (consecutive_failures ≥ N)        │ Provider (admin notifier)                │ {source_id, consecutive_failures}             | 
  └───────────────┴─────────────────────────────────────────────┴──────────────────────────────────────────┴───────────────────────────────────────────────┘
                                                                                   
  Provider's admin notifier coalesces messages of the same (channel, error_class) for 15 minutes before sending one summary message to all bot admins.

  ### provider_state (high-water mark for missed `new_post` NOTIFY events)

  ```sql
  CREATE TABLE provider_state (
    provider_instance    TEXT PRIMARY KEY,            -- e.g., "infochat-provider-0"; from infochat.provider.instance-id
    last_ready_post_at   TIMESTAMPTZ NOT NULL DEFAULT 'epoch',
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  ```

  Postgres LISTEN/NOTIFY does not buffer events for disconnected listeners. If the
  Provider is down or restarting when `NOTIFY new_post` fires, the event is gone.
  The high-water mark closes the gap:

  - On `@Startup` (priority 250) the Provider's `NewPostReconciler` reads
    `last_ready_post_at` and runs:

    ```sql
    SELECT id FROM post
     WHERE status = 'READY' AND ready_at > :last_ready_post_at
     ORDER BY ready_at;
    ```

    Each row is fed into the same handler that processes live `NOTIFY new_post`
    payloads. Reconciliation is bounded by partition retention (≤ 4 days).
  - The live listener and the reconciler both advance `last_ready_post_at` to the
    `ready_at` of the most recently processed row.
  - Multiple Provider instances each have their own `provider_state` row keyed by
    `provider_instance`; they do not interfere.

  Note that the durability promise is "no missed `new_post` events," not "exactly-once
  delivery to the user." Cache-invalidation and digest-prefetch handlers must be
  idempotent, which they already are (the cache key includes the post id).

  ### bootstrap_meta (last successful bootstrap-sources load)

  ```sql
  CREATE TABLE bootstrap_meta (
    id                  SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                                                     -- single-row table; CHECK (id = 1)
                                                     -- forbids accidental second row.
    last_loaded_sha256  TEXT        NOT NULL,        -- hex digest of bootstrap-sources.json
    last_loaded_at      TIMESTAMPTZ NOT NULL,
    last_entry_count    INT         NOT NULL,
    last_loader_version TEXT        NOT NULL         -- the Collector build version that loaded it
  );
  ```

  Recorded by `BootstrapLoader` after every successful idempotent load
  (see [07-deployment.md §7.6](07-deployment.md)). `audit_log` already
  carries the historical trail; `bootstrap_meta` is the cheap, current-state
  view that `/status` (admin) reads — answering "are all instances running
  the same bootstrap config?" without scanning audit history. The SHA also
  lets a Provider sanity-check at startup that the Collector loaded the
  same file the operator deployed.
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  2.9 TTL policy summary
                                                                                                                                                                                                                                                        
┌────────────────────────────────────────────────────────────────────────────────────────────┬────────────────────────────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────────┐
│                                           Table                                            │                       Retention                        │                                         Mechanism                                          │ 
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ post                                                                                       │ 30 days unless saved                                   │ Nightly DELETE excluding saved_post.post_id. ON DELETE RESTRICT enforces.                  │ 
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ post_user_tag                                                                              │ follows post                                           │ CASCADE                                                                                    │ 
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ post_entity                                                                                │ 4 days                                                 │ Drop partition                                                                             │
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ post_embedding                                                                             │ 4 days                                                 │ Drop partition                                                                             │ 
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ post_reference                                                                             │ 4 days                                                 │ Drop partition                                                                             │ 
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ quarantine                                                                                 │ 30 days after APPROVED/REJECTED; PENDING never expires │ Cron job                                                                                   │ 
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ audit_log                                                                                  │ 365 days                                               │ Cron job                                                                                   │ 
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤ 
│ chat_session                                                                               │ 60 days inactivity                                     │ Cron job (the active context for stale users is wiped; long-term chat_memory is preserved) │
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤ 
│ chat_memory                                                                                │ indefinite (manual /forget planned for v2)             │ none in v1                                                                                 │
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ saved_post                                                                                 │ indefinite                                             │ none                                                                                       │
├────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤ 
│ users, groups, group_membership, source, source_subscription, scope_tag, scope_preferences │ indefinite                                             │ none                                                                                       │  └────────────────────────────────────────────────────────────────────────────────────────────┴────────────────────────────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────────┘ 
                                                                                   
  ---                                                                                                                                                                                                                                                   
  2.10 Indexes & expected query plans
                                                                                                                                                                                                                                                        
  The hot paths are documented here so reviewers can verify the right indexes exist.
                                                                                                                                                                                                                                                        
  ┌─────────────────────────────────────┬────────────────────────────────────────────────────────────────┐
  │                Query                │                           Index used                           │
  ├─────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
  │ /summary {tag} -w 24h (scope-aware) │ idx_post_user_tag_tag → idx_post_status_fetched                │
  ├─────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
  │ Cluster lookup by post graph        │ idx_post_ref_from and idx_post_ref_to                          │
  ├─────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
  │ Tier-2 entity match                 │ idx_post_entity_text (within 4-day partition)                  │
  ├─────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
  │ Tier-2 semantic kNN                 │ profile-specific (hnsw or ivfflat) on post_embedding.embedding │
  ├─────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
  │ /saved filter by personal tag       │ idx_saved_user_tags (GIN on personal_tags)                     │
  ├─────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
  │ Memory pre-fetch by keyword         │ idx_chat_memory_keywords (GIN)                                 │
  ├─────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
  │ Admin /audit scan by actor          │ idx_audit_actor                                                │
  └─────────────────────────────────────┴────────────────────────────────────────────────────────────────┘
                                                                                   
  ---                                                                                                                                                                                                                                                   
  2.11 What's NOT in the schema (intentional)
                                                                                                                                                                                                                                                        
  - No is_read per post. Read state would multiply rows × users. Out of scope for v1.
  - No tier2_topic table. Topic IDs are computed at query time from post_reference connected components and cached in memory. Persisting them would require recomputation when the partition rolls.
  - No granular role table. v1 uses two booleans (is_admin, is_group_admin). v2 may introduce roles with manage_sources, moderate_security, manage_tags.
  - No per-group bans. Bot ban is global. Per-group /kick is v2.
                                                                                   
  --- 
