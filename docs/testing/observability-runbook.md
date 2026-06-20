# Observability runbook (for manual testing)

Status: testing-support notes, not spec. Column and table names below are taken
from the Flyway migrations under
`infochat-core/src/main/resources/db/migration/`; if a migration changes them,
update this file. Queries are written for the **operator/admin DB role** via
`psql` — the least-privilege `infochat_collector` / `infochat_provider` roles
deliberately cannot see everything (e.g. raw `audit_log`, quarantine originals;
see security.md §DB roles).

## Connect

With the default docker-compose Postgres (loopback-only, `127.0.0.1:5432`):

```bash
# owner/admin role — name per your secrets.env; DB is 'infochat'
psql "postgresql://infochat_owner@127.0.0.1:5432/infochat"
```

If you only have the per-service password, you can still read most app tables
with that role; the queries below that need the admin role are marked.

## Where each service logs

- Both services log to stdout (captured by `docker compose logs`):
  ```bash
  docker compose logs -f infochat-provider
  docker compose logs -f infochat-collector
  ```
- Contact ids and secrets are **redacted in logs** (security.md §Secrets
  handling) — a redacted id is expected, not a bug.
- Health/topology (which adapters are up, DB reachable) is on the health
  endpoints (loopback by default): provider `:8081`, collector `:8080`.

## Admin & intake

```sql
-- Who are the bot admins? (last-admin protection counts non-banned admins)
SELECT adapter, contact_id, is_admin, is_banned, registration_state
FROM users WHERE is_admin = true;

-- Live admin count the trigger enforces (must stay >= 1)
SELECT count(*) FROM users WHERE is_admin = true AND is_banned = false;

-- Is a given contact banned, and why?
SELECT adapter, contact_id, is_banned, ban_reason, banned_at
FROM users WHERE contact_id = '<id>';
```

A banned user should reach **no** state past the ban check — if you see new
`chat_session` / `audit_log` rows for a banned contact after a message, that
contradicts trust boundary 2 (security.md).

## Registration & probation

```sql
-- Registration state + probation window for a contact
-- registration_state ∈ preban | group_only | invited | vouched
SELECT adapter, contact_id, registration_state, probation_until, created_at
FROM users WHERE contact_id = '<id>';

-- Effective access: probation is lazy — full access once now() > probation_until
SELECT contact_id,
       (probation_until IS NULL OR probation_until < now()) AS full_access
FROM users WHERE contact_id = '<id>';

-- Outstanding invites (status ∈ PENDING | USED | REVOKED)
SELECT invite_type, expected_contact_id, adapter, status, expires_at, created_at
FROM invite_code ORDER BY created_at DESC LIMIT 20;
```

After a successful invite consume: the `users` row appears with
`registration_state='invited'` and a non-null `probation_until`, and the
matching `invite_code` row flips to `USED`. A `/ban` against an unknown contact
mints a `registration_state='preban'` row; `/unban` on it **deletes** the row.

## Groups

```sql
-- Group approval + digest state (approval_status ∈ pending|approved|rejected)
SELECT adapter, upstream_group_id, approval_status, digest_enabled,
       removed_at, timezone, activated_by
FROM groups ORDER BY created_at DESC;

-- Group admin slot (at most one is_group_admin=true per group)
SELECT g.upstream_group_id, u.contact_id, m.is_group_admin, m.removed_at
FROM group_membership m
JOIN groups g ON g.id = m.group_id
JOIN users  u ON u.id = m.user_id
WHERE m.is_group_admin = true;
```

Digests fire only where `approval_status='approved' AND removed_at IS NULL AND
digest_enabled`.

## Sources & ingest health

```sql
-- Source status (active|failed|disabled) and failure counters
SELECT kind, identifier, status, consecutive_failures, last_success_at,
       deleted_at, bootstrap_tags
FROM source ORDER BY status, kind;

-- Controlled vocabulary (seeded from bootstrap tags + /add-source --tags)
SELECT name FROM tag ORDER BY name;
```

The fetcher only schedules rows where `status='active' AND deleted_at IS NULL`.
A source that crossed the failure threshold sits in `failed` until
`/source-enable`.

## Posts & quarantine (the data the usage phase serves)

```sql
-- Post pipeline state — empty table = why /summary etc. return nothing
SELECT status, count(*) FROM post GROUP BY status;
-- status ∈ RAW | READY | QUARANTINED | NEEDS_REVIEW; only READY is user-visible

-- Posts stuck mid-evaluation (RAW + which stages are done)
SELECT uid, status, stage1_done, stage2_done, tagger_done, embedding_done,
       stage1_flagged, stage2_failed
FROM post WHERE status = 'RAW' ORDER BY fetched_at DESC LIMIT 20;

-- Quarantine queue (PENDING = active admin queue)
SELECT status, count(*) FROM quarantine GROUP BY status;
-- status ∈ PENDING | BENIGN_CLOSED | APPROVED | REJECTED
```

If `/summary` is empty, check `post` for `READY` rows first — no `READY` posts
means the collector hasn't produced visible content yet (fetch + eval pending,
or seed data not applied), not a provider bug.

## Asset commands

```sql
-- Which asset sub-verbs are enabled, and the default per asset
SELECT asset, sub_verb, enabled, is_default, status, last_success_at
FROM asset_config ORDER BY asset, sub_verb;

-- Latest captured price per (asset, sub_verb)
SELECT DISTINCT ON (asset, sub_verb) asset, sub_verb, vs_currency, price,
       captured_at, source_url
FROM price_snapshot ORDER BY asset, sub_verb, captured_at DESC;
```

A bare `/zcash` resolves the `is_default=true` row for `zcash`. Asset commands
read these tables directly and never touch the `post` pipeline.

## Audit log (admin role only)

```sql
-- Recent privileged actions (audit-before-effect: the row precedes the effect)
SELECT created_at, action, target_kind, target_contact_id, scope_id
FROM audit_log ORDER BY created_at DESC LIMIT 40;

-- Every action against a specific target kind (e.g. bans, grants)
SELECT created_at, action, actor_contact_id, target_contact_id, details_json
FROM audit_log WHERE target_kind = 'user' ORDER BY created_at DESC LIMIT 40;

-- LLM output sanitizer hits (admin strings stripped from LLM replies)
SELECT created_at, action, details_json FROM audit_log
WHERE action LIKE '%SANITIZ%' ORDER BY created_at DESC;
```

The Provider service role can only read the redacted `audit_log_view`; full
`audit_log` (with un-redacted `details_json`) needs the admin role. The table is
append-only — you should never see an `UPDATE`/`DELETE` against it.
