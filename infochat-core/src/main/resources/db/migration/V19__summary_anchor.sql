-- V19: summary_anchor
-- Implements docs/design/02-schema.md §2.6.5 (D19, D36, Invariant 9).
-- Provider-only: Collector has no grants on this table.

CREATE TABLE summary_anchor (
    user_id      UUID,
    scope_id     UUID        NOT NULL,
    command_kind TEXT        NOT NULL
        CHECK (command_kind IN ('personal','digest')),
    command_name TEXT        NOT NULL,
    arg_hash     TEXT        NOT NULL,
    post_uids    TEXT[]      NOT NULL,
    cluster_map  JSONB,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (command_kind = 'personal' AND user_id IS NOT NULL)
        OR
        (command_kind = 'digest'   AND user_id IS NULL)
    )
);

-- Personal: one anchor per (user, scope, command_kind).
CREATE UNIQUE INDEX summary_anchor_personal
    ON summary_anchor(user_id, scope_id, command_kind)
    WHERE user_id IS NOT NULL;

-- Digest: one anchor per (scope, command_kind='digest').
CREATE UNIQUE INDEX summary_anchor_digest
    ON summary_anchor(scope_id, command_kind)
    WHERE user_id IS NULL AND command_kind = 'digest';

CREATE INDEX idx_summary_anchor_generated_at
    ON summary_anchor(generated_at);

-- Provider writes anchors on /summary and reads them on /retry;
-- /forget deletes personal anchors.
GRANT SELECT, INSERT, UPDATE, DELETE ON summary_anchor TO infochat_provider;
