-- V36: schema hardening — stage2_verdict CHECK + Nostr since-cursor index.
--
-- (1) V22 added post.stage2_verdict and documented the closed set
--     ('BENIGN','INJECTION','MALWARE','UNKNOWN') in a comment only; no
--     CHECK enforced it. The constraint below is the DB-boundary
--     closure for the Stage 2 security-verdict label: NULL stays legal
--     ("Stage 2 hasn't run or had an infra failure", V22), every
--     non-NULL value must come from the closed set.
--     Stage2VerdictHandler.Verdict's INFRA_FAILURE member is
--     deliberately absent — that path never writes the column.
--     Naming follows users_registration_state_chk (V27).
--
-- (2) NostrStreamSource builds its reconnect `since` cursor from
--     SELECT MAX(published_at) FROM post WHERE source_id = ?. Neither
--     existing index serves it: idx_post_source is
--     (source_id, fetched_at DESC) and idx_post_published carries no
--     source_id column, so the cursor read scans every row of the
--     source. The composite below answers it at the first index entry
--     per source.
--
-- The companion fix for the V27 audit verb
-- (D47_GROUP_ONLY_PREBAN_CONVERSION was absent from the AuditAction
-- closure) is Java-only: audit_log.action carries no SQL CHECK by
-- design (V5 — the application-layer enum is the closure enforcer),
-- so no DDL lands here for it.

ALTER TABLE post ADD CONSTRAINT post_stage2_verdict_chk
    CHECK (stage2_verdict IS NULL
           OR stage2_verdict IN ('BENIGN', 'INJECTION', 'MALWARE', 'UNKNOWN'));

-- Declared on the parent; Postgres fans the index out to every
-- partition (same pattern as the V7 index block).
CREATE INDEX idx_post_source_published ON post(source_id, published_at DESC);
