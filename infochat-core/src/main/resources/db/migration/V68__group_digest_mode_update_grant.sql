-- V68: grant the provider role UPDATE on groups.digest_mode (M1-733).
--
-- /digest brief|normal|full writes digest_mode directly (the same
-- posture /digest on|off has for digest_enabled), but V62 narrowed
-- infochat_provider to column-level grants (timezone, digest_enabled,
-- removed_at) and V67 added the column without extending them — the
-- runtime UPDATE fails with "permission denied for table groups"
-- without this grant. Column-level GRANTs are additive, so the three
-- existing columns are untouched. No routine indirection: the closed
-- value set is V67's CHECK constraint's job, the same division of
-- labour digest_enabled uses.

GRANT UPDATE (digest_mode) ON groups TO infochat_provider;
