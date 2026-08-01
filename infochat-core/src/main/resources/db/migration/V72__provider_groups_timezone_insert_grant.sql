-- V72: widen the provider role's column-scoped INSERT grant on groups to
-- include timezone (M1-707).
--
-- GroupRepository's two creation statements now write the configured
-- infochat.groups.default-timezone into groups.timezone at INSERT time.
-- V62 narrowed infochat_provider to column-level INSERT (adapter,
-- upstream_group_id, activated_by), so the new column would fail at
-- runtime with "permission denied for table groups" under the real role
-- without this grant — invisibly to any test running as a superuser.
-- Column-level GRANTs are additive, so the three existing columns are
-- untouched and nothing else is widened.

GRANT INSERT (timezone) ON groups TO infochat_provider;
