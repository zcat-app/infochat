-- V7: §2.2.3..§2.2.5 per-scope joins + §2.3.1 partitioned post.
--
-- Lands the per-scope join surface (source_subscription, scope_tag,
-- scope_preferences) plus the partitioned post table from
-- docs/design/02-schema.md §§2.2.3-2.3.1. Together with V5 (identity
-- and audit) and V6 (sources / tags catalogue), this completes the
-- §2 schema floor up to and including the post table; later T1
-- tickets land Tier-2 derivatives (post_reference, post_embedding,
-- quarantine) and the per-user state carve-out (saved_post,
-- chat_memory, chat_session, summary_anchor).
--
-- Atomic Flyway migration: the whole file applies in one
-- transaction. A partial failure rolls back cleanly so the schema
-- cannot half-apply.
--
-- FK chain: source_subscription/scope_tag/scope_preferences and
-- post FK into V5's users(id) and V6's source(id) / tag(id). V5 and
-- V6 must apply before V7; the migration version ordering encodes
-- that and the M1-008c ticket's blocked_by chain documents the
-- practical wait-for-M1-008a-and-M1-008b constraint.

-- ---------------------------------------------------------------------
-- 2.2.3 source_subscription (Invariant 1, Invariant 4)
--
-- The (scope_kind, scope_id, source_id) PRIMARY KEY is Invariant 1's
-- schema-level enforcement plus dedup of the (scope, source) pair: a
-- row cannot exist without both the scope discriminator and the scope
-- id (PK columns are NOT NULL by definition), so an accidental scope-
-- agnostic INSERT from a buggy command handler fails at the storage
-- layer. The FK to source(id) intentionally carries NO ON DELETE
-- CASCADE — soft-delete (source.deleted_at) is the only path per
-- Invariant 4 (docs/design/02-schema.md §2.2.3 — cascade on hard-
-- delete is the operator's manual problem). The added_by FK to
-- users(id) uses ON DELETE SET NULL so the subscription row outlives
-- the user who created it (e.g., after an /unban + delete_preban_user
-- carve-out).
-- The idx_source_sub_source reverse-lookup index backs the
-- "who is subscribed to this source?" fan-out the Collector uses on
-- ingest.
-- ---------------------------------------------------------------------

CREATE TABLE source_subscription (
    scope_kind TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
    scope_id   UUID NOT NULL,
    source_id  UUID NOT NULL REFERENCES source(id),
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (scope_kind, scope_id, source_id)
);

CREATE INDEX idx_source_sub_source ON source_subscription(source_id);

-- ---------------------------------------------------------------------
-- 2.2.4 scope_tag (Invariant 1)
--
-- Per-scope follow / unfollow preference. The PK enforces Invariant 1
-- plus dedup on /follow-tag. The default for a fresh scope is "all
-- tags from subscribed sources" — the absence of rows for a scope
-- means "all tags" when scope_preferences.tag_mode = 'ALL'; the
-- presence of rows + tag_mode = 'EXPLICIT' narrows the digest to the
-- listed tags (docs/spec/commands.md §Per-scope tag preferences).
-- ---------------------------------------------------------------------

CREATE TABLE scope_tag (
    scope_kind TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
    scope_id   UUID NOT NULL,
    tag_id     UUID NOT NULL REFERENCES tag(id),
    PRIMARY KEY (scope_kind, scope_id, tag_id)
);

-- ---------------------------------------------------------------------
-- 2.2.5 scope_preferences (Invariant 1)
--
-- One settings row per scope; the 2-column PK is intentional (no
-- per-tag or per-source axis here — the join tables above carry
-- those). tag_subscription_version and source_subscription_version
-- are monotonic counters incremented in the SAME transaction as a
-- /follow-tag / /unfollow-tag / /add-source / /remove-source
-- mutation; they fold into the digest cache key so a subscription
-- change yields a fresh cache miss without an explicit invalidation
-- pass (docs/design/02-schema.md §2.2.5).
-- ---------------------------------------------------------------------

CREATE TABLE scope_preferences (
    scope_kind                  TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
    scope_id                    UUID NOT NULL,
    language                    TEXT NOT NULL DEFAULT 'en',
    timezone                    TEXT,
    digest_enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    tag_mode                    TEXT NOT NULL DEFAULT 'ALL'
                                CHECK (tag_mode IN ('ALL','EXPLICIT')),
    tag_subscription_version    BIGINT NOT NULL DEFAULT 0,
    source_subscription_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (scope_kind, scope_id)
);

-- ---------------------------------------------------------------------
-- 2.3.1 post — partitioned by fetched_at (Invariant 5, Invariant 6)
--
-- PARTITION BY RANGE (fetched_at) commits the table to Invariant 6's
-- TTL-by-partition-drop lifecycle: the future pruner drops the oldest
-- partition past the retention horizon rather than DELETE-ing rows.
-- No DEFAULT partition: a fallback bucket would silently accumulate
-- rows outside every named partition's range and could never be
-- dropped without losing data — exactly the property Invariant 6
-- forbids. The application-tier scheduler that creates the next
-- partition before it is needed lands in a later T1 ticket; this
-- migration creates ONE bootstrap partition so the schema is
-- queryable on day one.
--
-- status CHECK closes ('RAW','READY','QUARANTINED','NEEDS_REVIEW')
-- per Invariant 5 — there is NO 'EVALUATING' status. In-flight
-- evaluation is status='RAW' plus the seven per-stage *_done /
-- *_flagged / *_failed / *_fallback BOOLEAN flags below, all
-- defaulting FALSE.
--
-- PRIMARY KEY (id, fetched_at): Postgres requires the partition key
-- to participate in every unique constraint on a partitioned table.
-- UNIQUE (uid, fetched_at) is per-window dedup; cross-window dedup
-- (a re-fetched item landing in a later partition) is the fetcher's
-- responsibility per docs/spec/schema.md §UID derivation.
-- UNIQUE (source_id, upstream_identifier, fetched_at) is belt-and-
-- suspenders for sources that supply stable upstream identifiers.
--
-- tags TEXT[] is inline rather than via a partitioned post_tag join:
-- the parent is partitioned and a partitioned M2M would have to share
-- the partition key, multiplying complexity for no query advantage.
-- The GIN index on tags keeps the tag-filtered /summary query plan
-- fast.
--
-- source_id FK carries NO ON DELETE CASCADE (Invariant 4 — same
-- rationale as source_subscription above).
-- ---------------------------------------------------------------------

CREATE TABLE post (
    id                  UUID NOT NULL DEFAULT gen_random_uuid(),
    uid                 TEXT NOT NULL,
    source_id           UUID NOT NULL REFERENCES source(id),
    upstream_identifier TEXT,
    url                 TEXT,
    title               TEXT NOT NULL,
    body                TEXT,
    body_summary        TEXT,
    author              TEXT,
    published_at        TIMESTAMPTZ,
    fetched_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at            TIMESTAMPTZ,
    status_changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_linked_at      TIMESTAMPTZ,
    status              TEXT NOT NULL DEFAULT 'RAW'
                        CHECK (status IN ('RAW','READY','QUARANTINED','NEEDS_REVIEW')),
    stage1_done         BOOLEAN NOT NULL DEFAULT FALSE,
    stage2_done         BOOLEAN NOT NULL DEFAULT FALSE,
    tagger_done         BOOLEAN NOT NULL DEFAULT FALSE,
    embedding_done      BOOLEAN NOT NULL DEFAULT FALSE,
    stage1_flagged      BOOLEAN NOT NULL DEFAULT FALSE,
    stage2_failed       BOOLEAN NOT NULL DEFAULT FALSE,
    tagger_fallback     BOOLEAN NOT NULL DEFAULT FALSE,
    tags                TEXT[] NOT NULL DEFAULT '{}',
    social_score        INT,
    likes               INT,
    reposts             INT,
    PRIMARY KEY (id, fetched_at),
    UNIQUE (uid, fetched_at),
    UNIQUE (source_id, upstream_identifier, fetched_at)
) PARTITION BY RANGE (fetched_at);

-- Bootstrap partition: covers May 2026 (the month the schema lands).
-- The application-tier partition scheduler will create the next
-- partition before it is needed and drop the oldest past the
-- retention horizon. Naming convention: post_YYYYMM (six-digit
-- suffix = monthly cadence). A wrong cadence here would not break
-- the schema — the parent's PARTITION BY RANGE is the durable
-- commitment — but the scheduler ticket will follow this naming.
CREATE TABLE post_202605 PARTITION OF post
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');

-- Indexes are declared on the parent and propagated to all
-- partitions (Postgres handles the fan-out automatically).
CREATE INDEX idx_post_status_fetched ON post(status, fetched_at DESC);
CREATE INDEX idx_post_source         ON post(source_id, fetched_at DESC);
CREATE INDEX idx_post_published      ON post(published_at DESC);
-- Provider startup reconciler cursor (architecture.md §Catch-up):
CREATE INDEX idx_post_ready_at       ON post(ready_at, id) WHERE status = 'READY';
-- LinkingJob driving-set scan: covers READY rows that have never been
-- linked or whose last_linked_at is older than the post itself.
CREATE INDEX idx_post_link_cursor    ON post(fetched_at)
    WHERE status = 'READY' AND (last_linked_at IS NULL OR last_linked_at < fetched_at);
-- Tag-filtered /summary query plan:
CREATE INDEX idx_post_tags_gin       ON post USING gin (tags);
-- Quarantine-review NOTIFY cursor (post → NEEDS_REVIEW transitions):
CREATE INDEX idx_post_status_changed ON post(status_changed_at, id)
    WHERE status = 'NEEDS_REVIEW';

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles and
-- docs/design/04-security.md §infochat_collector / §infochat_provider).
--
-- Per-scope joins (source_subscription / scope_tag / scope_preferences)
-- are Provider-write: /add-source, /remove-source, /follow-tag,
-- /unfollow-tag, /lang, /digest-on, /digest-off, etc. all mutate
-- through the Provider. Collector reads them (the fan-out path reads
-- source_subscription to dispatch new posts to scopes).
--
-- post is Collector-write: the fetcher writes RAW rows on ingest,
-- the evaluator transitions status through the eval pipeline.
-- Provider reads post to render /summary and /saved. NEITHER role
-- holds DELETE on post — Invariant 6 commits TTL to partition drop,
-- not row delete; partition-drop is operator-only (infochat_admin)
-- and lives in a later scheduler ticket.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON source_subscription TO infochat_provider;
GRANT SELECT                         ON source_subscription TO infochat_collector;

GRANT SELECT, INSERT, UPDATE, DELETE ON scope_tag           TO infochat_provider;
GRANT SELECT                         ON scope_tag           TO infochat_collector;

GRANT SELECT, INSERT, UPDATE, DELETE ON scope_preferences   TO infochat_provider;
GRANT SELECT                         ON scope_preferences   TO infochat_collector;

GRANT SELECT, INSERT, UPDATE ON post TO infochat_collector;
GRANT SELECT                 ON post TO infochat_provider;
REVOKE DELETE ON post FROM infochat_collector;
REVOKE DELETE ON post FROM infochat_provider;
REVOKE DELETE ON post FROM PUBLIC;
