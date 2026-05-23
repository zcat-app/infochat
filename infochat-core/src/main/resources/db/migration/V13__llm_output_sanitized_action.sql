-- V13: §Audit-log verb catalogue addition — LLM_OUTPUT_SANITIZED.
--
-- Per docs/spec/security.md §LLM output sanitizer: "Every match is
-- audit-logged (per-occurrence, not throttled)." LlmOutputSanitizer
-- writes one audit_log row per closed-list / markdown-link match
-- via AuditLogWriter (M1-041); each row carries
-- action='LLM_OUTPUT_SANITIZED'.
--
-- V5 §2.1.8 documents the verb catalogue as per-line comments
-- WITHOUT a SQL CHECK on audit_log.action (V5 lines 28-29 and
-- 272-273 — "The set is NOT pinned with a SQL CHECK on
-- audit_log.action because the verb catalogue is open-ended for v2
-- additions and the application-layer audit-write helper is the
-- closure enforcer."). V12 added INVITE_BRUTE_FORCE_BREACH via a
-- pure line-comment addition (V12 line 68); this migration follows
-- the same pattern. The closure is enforced at the application
-- layer by the AuditAction enum referenced from AuditLogWriter.
-- ---------------------------------------------------------------------

-- LLM_OUTPUT_SANITIZED

-- No-op execution body so Flyway has at least one statement to run
-- on the row (some Flyway versions reject pure-comment migrations).
-- The DO block embeds the verb name verbatim so the migration's
-- effect (a flyway_schema_history row pinning the catalogue
-- addition) and its searchable text are coupled.
DO $$
BEGIN
    -- Catalogue addition pinned at V13: LLM_OUTPUT_SANITIZED.
    NULL;
END $$;
