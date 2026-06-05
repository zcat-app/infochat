-- V31: make the per-service DB role split load-bearing + implement the
-- audit_log_view redactors.
--
-- Two coupled changes living on the same trust boundary (docs/spec/security.md
-- §DB roles, §Secrets handling):
--
--   (1) Grant LOGIN to infochat_collector and infochat_provider so each service
--       can connect as its least-privileged role via the named-datasource
--       wiring this ticket lands. infochat_admin stays NOLOGIN — it is the
--       operator psql / admin-procedure principal, not a service login
--       (security.md §DB roles). V2 created all three NOLOGIN; V4 re-asserted
--       NOLOGIN. This migration flips LOGIN on the two service roles only.
--
--   (2) Replace the V5 redact_contact_id / redact_secrets_jsonb RETURN-input
--       stubs (V5__identity_audit.sql:324-336) with the real redaction policy
--       so audit_log_view stops surfacing raw contact ids and unredacted
--       details_json. The view already routes both columns through these
--       function names, so CREATE OR REPLACE FUNCTION swaps the bodies in
--       place with no view DDL.

-- (1) Service-role LOGIN. Idempotent attribute flip; admin untouched.
ALTER ROLE infochat_collector LOGIN;
ALTER ROLE infochat_provider  LOGIN;

-- (1b) Grants the V6/V23 matrix forgot, surfaced the moment the roles became
-- load-bearing connection principals:
--
--   * summary_cache.id is BIGSERIAL (V23) and the Provider holds INSERT
--     (V23:21), but nextval() on the backing sequence needs USAGE — without it
--     every digest-cache INSERT fails under the service role.
GRANT USAGE ON SEQUENCE summary_cache_id_seq TO infochat_provider;

--   * The Provider's source-management commands (/add-source, /enable-source,
--     /disable-source, /remove-source — deterministic admin-command Java, never
--     LLM-reachable, so D34's read-only-where-possible emphasis does not apply)
--     write source and tag, but V6 granted the Provider SELECT-only. The UPDATE
--     grant is COLUMN-SCOPED to the closed set those commands legitimately
--     touch: identity columns (kind, identifier, display_name, category,
--     added_by) stay read-only, so a SQL-injection foothold in the Provider
--     cannot repoint an existing trusted source at attacker content or rewrite
--     its display identity. DELETE stays revoked (Invariant 4: soft-delete
--     only — the V6 REVOKEs remain in force).
GRANT INSERT ON source TO infochat_provider;
GRANT UPDATE (status, consecutive_failures, deleted_at, deleted_by, bootstrap_tags)
    ON source TO infochat_provider;
GRANT INSERT ON tag TO infochat_provider;

--   * Read-only schema-history introspection: the default Flyway bean is
--     bound to the (weak-role) default datasource and exposes migration
--     state via info(). The roles may READ migration metadata (version
--     strings, checksums, timestamps — not sensitive); schema-history
--     writes stay owner-only (no INSERT/UPDATE/DELETE granted — Flyway's
--     migrate runs on the named owner datasource).
GRANT SELECT ON flyway_schema_history TO infochat_collector, infochat_provider;

-- (2a) Contact-id redaction: first 6 chars + … + last 4 chars
-- (docs/design/04-security.md §4.11; docs/spec/security.md §Secrets handling).
-- A contact id of 10 chars or fewer leaks in full if split into a 6-char
-- prefix and 4-char suffix: at length 10 the two halves tile the whole value
-- (6 + 4 = 10) leaving no character hidden, and shorter ids make the halves
-- overlap. Such ids are therefore fully masked. NULL passes through (audit
-- rows without an actor/target contact id store NULL in these columns).
CREATE OR REPLACE FUNCTION redact_contact_id(input TEXT)
RETURNS TEXT AS $$
BEGIN
    IF input IS NULL THEN
        RETURN NULL;
    END IF;
    IF char_length(input) <= 10 THEN
        RETURN '…';
    END IF;
    RETURN left(input, 6) || '…' || right(input, 4);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- (2b) Secret masking: render the JSONB as text, apply the closed seven-family
-- API-key catalogue (mirrors app.zcat.infochat.core.log.Redactor.CATALOGUE so
-- the read-side SQL mask cannot drift from the write-side Java filter), then
-- parse back. The catalogue families' character classes exclude the JSON
-- string delimiter (") so a match always stops at the closing quote and never
-- corrupts JSON structure; matched secrets shorter than each family's minimum
-- length are left untouched. Ordering follows the Java catalogue: Anthropic
-- before OpenAI (strict-prefix overlap), provider-pinned families before the
-- generic keyword-adjacent shape (broadest). This is a defense-in-depth
-- read-side mask; the primary write-time filter is DefaultRedactionHook.
CREATE OR REPLACE FUNCTION redact_secrets_jsonb(input JSONB)
RETURNS JSONB AS $$
DECLARE
    rendered TEXT;
BEGIN
    IF input IS NULL THEN
        RETURN NULL;
    END IF;
    rendered := input::text;
    rendered := regexp_replace(rendered, 'sk-ant-[A-Za-z0-9_-]{20,}', '[REDACTED]', 'g');
    rendered := regexp_replace(rendered, 'sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}', '[REDACTED]', 'g');
    rendered := regexp_replace(rendered, 'gh[opusr]_[A-Za-z0-9]{20,}', '[REDACTED]', 'g');
    rendered := regexp_replace(rendered, '(?:AKIA|ASIA)[0-9A-Z]{16}', '[REDACTED]', 'g');
    rendered := regexp_replace(rendered, 'AIza[0-9A-Za-z_-]{35}', '[REDACTED]', 'g');
    rendered := regexp_replace(rendered, 'xox[abprs]-[A-Za-z0-9-]{10,}', '[REDACTED]', 'g');
    rendered := regexp_replace(
        rendered,
        '(?i)((?:api[_-]?key|secret|token|password|bearer)["''\s:=]{0,5})[A-Za-z0-9+/=_-]{32,}',
        '\1[REDACTED]',
        'g');
    RETURN rendered::jsonb;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
