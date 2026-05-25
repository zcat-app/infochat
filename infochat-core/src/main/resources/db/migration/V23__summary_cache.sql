-- Digest summary cache: stores rendered digest content per group per slot.
-- Provider reads cached digests and DigestWorker (M1-080b) writes them;
-- expired rows are filtered out by the application layer (TTL via expires_at).

CREATE TABLE summary_cache (
    id                            BIGSERIAL PRIMARY KEY,
    group_id                      UUID NOT NULL REFERENCES groups(id),
    slot_kind                     TEXT NOT NULL,
    slot_fired_at                 TIMESTAMPTZ NOT NULL,
    tag_subscription_version      BIGINT NOT NULL,
    source_subscription_version   BIGINT NOT NULL,
    content                       TEXT NOT NULL,
    is_degraded                   BOOLEAN NOT NULL DEFAULT false,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at                    TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_summary_cache_group_slot_fired
    ON summary_cache (group_id, slot_kind, slot_fired_at);

GRANT SELECT, INSERT, DELETE ON summary_cache TO infochat_provider;
