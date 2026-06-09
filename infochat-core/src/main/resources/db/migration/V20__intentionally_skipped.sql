-- V20: intentionally skipped — no-op placeholder.
--
-- V20 was reserved for groups/group_membership (ticket M1-079a), but both
-- tables already exist in V5__identity_audit.sql, so M1-079a was rewritten
-- repositories-only and the V20 migration was dropped (commit 424ed48,
-- 2026-05-25). Flyway tolerates version gaps at runtime, so a bare V19 -> V21
-- jump is harmless — but it is a recurring "what happened to V20?" question
-- that every directory listing (human or automated audit) re-raises.
--
-- This placeholder fills the V20 slot so the sequence is contiguous and the
-- gap is not repeatedly re-flagged. It creates/alters nothing.

DO $$ BEGIN END $$;
