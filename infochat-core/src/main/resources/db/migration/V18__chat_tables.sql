-- V18: chat_session, chat_memory, chat_message
-- Implements docs/design/02-schema.md §§2.6.2–2.6.4 (D25, D37, D40).
-- Provider-only: Collector has no grants on these tables.

-- chat_session must be created before chat_message (FK target).
CREATE TABLE chat_session (
    user_id      UUID        NOT NULL REFERENCES users(id),
    scope_kind   TEXT        NOT NULL CHECK (scope_kind IN ('dm','group')),
    scope_id     UUID        NOT NULL,
    next_seq     INT         NOT NULL DEFAULT 0,
    token_count  INT         NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, scope_kind, scope_id)
);

CREATE TABLE chat_memory (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users(id),
    scope_kind        TEXT        NOT NULL CHECK (scope_kind IN ('dm','group')),
    scope_id          UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary           TEXT        NOT NULL,
    keywords          TEXT[]      NOT NULL,
    referenced_posts  TEXT[]      NOT NULL DEFAULT '{}',
    referenced_topics UUID[]      NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_chat_memory_scope
    ON chat_memory(user_id, scope_kind, scope_id, created_at DESC);

CREATE INDEX idx_chat_memory_keywords ON chat_memory USING gin (keywords);

-- LRU cap: at most 200 rows per (user_id, scope_kind, scope_id).
-- Evicts oldest by created_at ASC when count reaches the cap.
CREATE OR REPLACE FUNCTION trg_chat_memory_cap()
RETURNS TRIGGER AS $$
BEGIN
    DELETE FROM chat_memory
     WHERE id IN (
         SELECT id FROM chat_memory
          WHERE user_id    = NEW.user_id
            AND scope_kind = NEW.scope_kind
            AND scope_id   = NEW.scope_id
          ORDER BY created_at ASC
          LIMIT greatest(0,
              (SELECT count(*) FROM chat_memory
                WHERE user_id    = NEW.user_id
                  AND scope_kind = NEW.scope_kind
                  AND scope_id   = NEW.scope_id) - 199)
     );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chat_memory_cap
    BEFORE INSERT ON chat_memory
    FOR EACH ROW EXECUTE FUNCTION trg_chat_memory_cap();

CREATE TABLE chat_message (
    user_id    UUID        NOT NULL,
    scope_kind TEXT        NOT NULL,
    scope_id   UUID        NOT NULL,
    seq        INT         NOT NULL,
    role       TEXT        NOT NULL
        CHECK (role IN ('system','user','assistant','tool')),
    content    TEXT        NOT NULL,
    tokens     INT         NOT NULL,
    ts         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, scope_kind, scope_id, seq),
    FOREIGN KEY (user_id, scope_kind, scope_id)
        REFERENCES chat_session(user_id, scope_kind, scope_id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_message_session_seq
    ON chat_message(user_id, scope_kind, scope_id, seq);

-- Counter trigger: maintains chat_session.token_count and next_seq
-- on INSERT/DELETE of chat_message rows.
CREATE OR REPLACE FUNCTION trg_chat_session_counters()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE chat_session
           SET token_count = token_count + NEW.tokens,
               next_seq    = next_seq + 1,
               updated_at  = now()
         WHERE user_id    = NEW.user_id
           AND scope_kind = NEW.scope_kind
           AND scope_id   = NEW.scope_id;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE chat_session
           SET token_count = token_count - OLD.tokens,
               updated_at  = now()
         WHERE user_id    = OLD.user_id
           AND scope_kind = OLD.scope_kind
           AND scope_id   = OLD.scope_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chat_message_counters
    AFTER INSERT OR DELETE ON chat_message
    FOR EACH ROW EXECUTE FUNCTION trg_chat_session_counters();

-- Provider reads, writes, and purges via /clear and /forget.
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_session TO infochat_provider;
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_memory  TO infochat_provider;
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_message TO infochat_provider;
