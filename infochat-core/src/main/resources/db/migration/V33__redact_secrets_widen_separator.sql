-- V33: widen redact_secrets_jsonb's generic separator run.
--
-- The generic keyword-adjacent pattern's separator run was ["'\s:=]{0,5}:
-- a key written with a longer or differently-punctuated separator run
-- (column-aligned config dumps, `token -> <key>`, NBSP from copy-pasted
-- rich text) evaded the catch-all, and \s itself is an engine-dependent
-- shorthand — Java's is ASCII-only while PostgreSQL's is [[:space:]] —
-- so the write-side Java filter and this read-side mask could diverge on
-- non-ASCII whitespace despite the regex strings being textually
-- identical.
--
-- The replacement pattern spells whitespace explicitly (ASCII \s spelled
-- out, plus NBSP via \u00A0), adds the punctuation set , | < > ( ) - to
-- the separator class, and raises the bound to {0,64}. The bound stays
-- finite so "adjacent" (docs/spec/security.md §Secrets handling) keeps
-- meaning and backtracking stays capped per position; a 65+-char
-- pure-separator run is no longer plausibly a key/value gap — a
-- deliberate cliff, pinned by RedactorSqlParityIT's negative sample.
--
-- A NEW migration rather than an in-place V31 edit: a database that
-- already executed V31 would keep the old function permanently if the
-- edit landed in place (`flyway repair` after the checksum mismatch
-- updates the checksum without re-running the migration). The body below
-- is the complete current definition — V31's redact_secrets_jsonb with
-- only the generic (last) pattern changed. Mirrors
-- app.zcat.infochat.core.log.Redactor.CATALOGUE; textual identity of the
-- regex strings is load-bearing (guarded by RedactorSqlParityIT).
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
        '(?i)((?:api[_-]?key|secret|token|password|bearer)["'' \t\n\x0B\f\r\u00A0:=,|<>()-]{0,64})[A-Za-z0-9+/=_-]{32,}',
        '\1[REDACTED]',
        'g');
    RETURN rendered::jsonb;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
