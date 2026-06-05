> **Status: design notes, not spec.**
> Implementation details below (DDL, class names, package layout, property keys,
> retry counts, regex strings, etc.) are working notes that may change without a
> spec amendment. The authoritative *what & why* lives in `docs/spec/`.

---

# 02 — Database schema

PostgreSQL 16+ with `pgvector` 0.7+ extension.

Migrations live in `infochat-core/src/main/resources/db/migration/` and are
applied by Flyway on Collector + Provider startup. Both services share one
schema; they differ only in DB role (decision D34, see `docs/spec/security.md`
§DB roles and `docs/design/04-security.md` for the full grant matrix).

Roles referenced below:

- `infochat_collector` — ingest writer. Writes ingest-owned tables; `INSERT`-only
  on `audit_log`.
- `infochat_provider` — user-facing writer. Writes user-state tables; `SELECT`
  on collector-owned tables (read-only for `price_snapshot`, `asset_config`);
  `INSERT`-only on `audit_log`; `SELECT` on `audit_log_view` only (never on
  `audit_log` directly); `EXECUTE` on `approve_quarantine` /
  `reject_quarantine` (no `SELECT` on `quarantine.original_html`).
- `infochat_listen` — `LISTEN/NOTIFY`-only (no DML); used by both services for
  the wake-up channel.
- `infochat_admin` — operator psql sessions. Only role with `UPDATE` / `DELETE`
  on `audit_log`, hard-delete on `source`, and `SELECT` on
  `quarantine.original_html`.

`DELETE` on `users` is revoked from `infochat_collector` and
`infochat_provider`; the **only** application-issued DELETE permitted by
spec invariant 2 is the `preban` carve-out (`/unban` against an unknown
contact), which the Provider executes via the `delete_preban_user` stored
procedure (§2.1.6) — the procedure has elevated rights so the Provider role
itself never carries raw `DELETE` on `users`.

---

## 2.1 Identity & access

### 2.1.1 `users` (D44, D45, D46, Invariant 2)

```sql
CREATE TABLE users (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  adapter            TEXT NOT NULL,                  -- 'simplex','signal','inmemory'
                                                     -- contact_id format is adapter-specific
                                                     -- (SimpleX queue address, Signal ACI, …)
  contact_id         TEXT NOT NULL,
  display_name       TEXT,                           -- last-seen display name from adapter
                                                     -- (informational; sanitized at write time
                                                     --  per docs/design/04-security.md §4.8)
  is_admin           BOOLEAN NOT NULL DEFAULT FALSE,
  is_banned          BOOLEAN NOT NULL DEFAULT FALSE,
  banned_at          TIMESTAMPTZ,
  banned_by          UUID REFERENCES users(id),
  ban_reason         TEXT,
  -- Registration provenance (drives the DM invite gate per spec §Identity
  -- and access). Set on INSERT, mutated only by the closed transition set
  -- in spec/schema.md §Registration-state transitions.
  --   'preban'      row minted by /ban against unknown contact; deleted on /unban
  --   'group_only'  auto-registered via group @mention; DM blocked until invite or /vouch
  --   'invited'     registered via invite-code consume (or group_only advanced by /invite create --contact)
  --   'vouched'     group_only advanced by /vouch <contact>, OR bootstrap-seeded admin row
  registration_state TEXT NOT NULL,
  probation_until    TIMESTAMPTZ,                    -- D45 slow-start; NULL = full access
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at       TIMESTAMPTZ,
  save_count         INT NOT NULL DEFAULT 0,         -- denormalized COUNT(*) of saved_post rows
                                                     -- for this user; maintained by trigger
                                                     -- on saved_post INSERT/DELETE; powers the
                                                     -- 1000-save cap in O(1) instead of COUNT(*).
  CONSTRAINT users_adapter_contact_unique
    UNIQUE (adapter, contact_id),                    -- D46: same human on two adapters is two rows
  CONSTRAINT users_registration_state_chk
    CHECK (registration_state IN ('preban','group_only','invited','vouched'))
);

CREATE INDEX idx_users_admin   ON users(is_admin)  WHERE is_admin;
CREATE INDEX idx_users_banned  ON users(is_banned) WHERE is_banned;
-- (adapter, contact_id) lookup index is satisfied by the UNIQUE constraint.
```

### 2.1.2 Last-admin protection trigger (Invariant 2)

The trigger MUST serialize concurrent revocation attempts on **both** the
UPDATE path (revoke `is_admin`, set `is_banned` on the only admin) and the
DELETE path (a hard delete that would leave zero `is_admin = true` rows).
A naive `SELECT count(*) WHERE is_admin = true` under READ COMMITTED is
unsafe: two simultaneous `/revoke-admin` (or `/ban`) transactions targeting
different admin rows both read the pre-state count of 2, both proceed, both
commit, and the deployment ends with zero admins. Counting is **global
across adapters** (D46) — there is no per-adapter `is_admin` slot.

```sql
CREATE OR REPLACE FUNCTION trg_last_admin_protection_update()
RETURNS TRIGGER AS $$
DECLARE
  remaining INT;
BEGIN
  -- Lock the admin rows for the duration of this transaction so a concurrent
  -- revoke against a different admin row sees this transaction's pending
  -- change before computing its own count. SHARE ROW EXCLUSIVE blocks other
  -- writers but allows concurrent SELECT — the right trade-off for a
  -- privileged-mutation path.
  LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

  IF (OLD.is_admin = TRUE  AND NEW.is_admin  = FALSE)
     OR (OLD.is_banned = FALSE AND NEW.is_banned = TRUE AND OLD.is_admin = TRUE) THEN
    SELECT count(*) INTO remaining
      FROM users
     WHERE is_admin = TRUE
       AND is_banned = FALSE
       AND id <> NEW.id;
    IF remaining < 1 THEN
      RAISE EXCEPTION 'last_admin_protection: cannot leave the deployment with zero bot admins';
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_last_admin_update
  BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION trg_last_admin_protection_update();

-- DELETE path: only reachable for the `preban` carve-out (Invariant 2). The
-- guard below is defense-in-depth — a preban row never has is_admin = true,
-- so the count check always passes for the carve-out path. The same SHARE
-- ROW EXCLUSIVE serialization is applied so an operator running raw SQL
-- under the Admin role concurrent with a /revoke-admin still cannot leave
-- the deployment with zero admins.
CREATE OR REPLACE FUNCTION trg_last_admin_protection_delete()
RETURNS TRIGGER AS $$
DECLARE
  remaining INT;
BEGIN
  LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

  IF OLD.is_admin = TRUE THEN
    SELECT count(*) INTO remaining
      FROM users
     WHERE is_admin = TRUE
       AND is_banned = FALSE
       AND id <> OLD.id;
    IF remaining < 1 THEN
      RAISE EXCEPTION 'last_admin_protection: cannot delete the last bot admin';
    END IF;
  END IF;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_last_admin_delete
  BEFORE DELETE ON users
  FOR EACH ROW EXECUTE FUNCTION trg_last_admin_protection_delete();
```

### 2.1.3 `groups` (D46)

```sql
CREATE TABLE groups (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  adapter           TEXT NOT NULL,                   -- matches users.adapter values
  upstream_group_id TEXT NOT NULL,                   -- adapter-native group id
                                                     -- (SimpleX group id, Signal group v2 id, …)
  display_name      TEXT,
  timezone          TEXT NOT NULL DEFAULT 'UTC',     -- IANA tz; drives 8am/8pm digest slots;
                                                     -- mutated by /group-timezone
  removed_at        TIMESTAMPTZ,                     -- nullable; set when bot is removed from the
                                                     -- group; cleared on re-add. Group state
                                                     -- (subscriptions, scope_tag, chat_memory,
                                                     --  members' saves) is preserved across
                                                     -- remove/re-add cycles (spec §Failure
                                                     -- handling — Bot removed from group).
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (adapter, upstream_group_id)
);
```

### 2.1.4 `group_membership`

```sql
CREATE TABLE group_membership (
  group_id        UUID NOT NULL REFERENCES groups(id),
  user_id         UUID NOT NULL REFERENCES users(id),
  is_group_admin  BOOLEAN NOT NULL DEFAULT FALSE,
  joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  removed_at      TIMESTAMPTZ,                       -- nullable; set on user_left_group adapter
                                                     -- event or permanent send failure; cleared
                                                     -- on rejoin. Per-(user, group) chat_memory,
                                                     -- chat_session, summary_anchor rows are
                                                     -- preserved across the soft-clear.
  PRIMARY KEY (group_id, user_id)
);

CREATE INDEX idx_group_membership_user ON group_membership(user_id);

-- D9 / Invariant 3: at most one group admin per group. A trigger on
-- group_membership clears is_group_admin in the same transaction that sets
-- removed_at on the previous admin row, freeing the partial unique index
-- slot (spec §Identity and access — Group membership user-departure
-- lifecycle).
CREATE UNIQUE INDEX one_admin_per_group
  ON group_membership(group_id) WHERE is_group_admin = TRUE;

CREATE OR REPLACE FUNCTION trg_clear_group_admin_on_remove()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.removed_at IS NOT NULL AND OLD.removed_at IS NULL
     AND OLD.is_group_admin = TRUE THEN
    NEW.is_group_admin := FALSE;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_group_membership_clear_admin
  BEFORE UPDATE ON group_membership
  FOR EACH ROW EXECUTE FUNCTION trg_clear_group_admin_on_remove();
```

`ON DELETE CASCADE` is **not** set on `group_membership.user_id`: invariant
2 forbids application-path `DELETE` against `users`. The membership row
follows the user-departure soft-clear lifecycle instead.

### 2.1.5 `invite_code` (D44)

```sql
CREATE TABLE invite_code (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code                UUID NOT NULL UNIQUE,
  invite_type         TEXT NOT NULL
    CHECK (invite_type IN ('CONTACT_BOUND','OPEN_ADAPTER')),
  adapter             TEXT NOT NULL,
  expected_contact_id TEXT,                            -- non-null iff CONTACT_BOUND
  status              TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','USED','REVOKED')),    -- no stored EXPIRED;
                                                       -- intake treats PENDING + expires_at < NOW()
                                                       -- as expired without writing a transition.
  created_by          UUID NOT NULL REFERENCES users(id),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at          TIMESTAMPTZ,                     -- NULL = no expiry
  used_at             TIMESTAMPTZ,
  used_by_contact_id  TEXT,
  CONSTRAINT invite_type_contact_iff CHECK (
    (invite_type = 'CONTACT_BOUND' AND expected_contact_id IS NOT NULL)
    OR
    (invite_type = 'OPEN_ADAPTER'  AND expected_contact_id IS NULL)
  )
);

-- Hot path: /invite consume by code under (adapter, status='PENDING').
CREATE INDEX idx_invite_code_pending
  ON invite_code(adapter, code) WHERE status = 'PENDING';
CREATE INDEX idx_invite_code_creator
  ON invite_code(created_by, created_at DESC);
```

The race-safe single-use consume is a conditional UPDATE
(spec §Identity and access — Invite code):

```sql
UPDATE invite_code
   SET status = 'USED',
       used_at = NOW(),
       used_by_contact_id = $contact_id
 WHERE code = $code
   AND adapter = $adapter
   AND status = 'PENDING'
   AND (expires_at IS NULL OR expires_at > NOW())
   AND (invite_type = 'OPEN_ADAPTER' OR expected_contact_id = $contact_id)
 RETURNING id;
```

`USED` and `REVOKED` are terminal — no application path transitions back to
`PENDING`. Hard-delete is forbidden (audit artefact) and is enforced via
role grants (`infochat_collector` and `infochat_provider` lack `DELETE`).

### 2.1.6 `delete_preban_user` stored procedure (Invariant 2 carve-out)

```sql
CREATE OR REPLACE PROCEDURE delete_preban_user(p_user_id UUID, p_actor_id UUID)
LANGUAGE plpgsql AS $$
DECLARE
  v_state TEXT;
BEGIN
  SELECT registration_state INTO v_state FROM users WHERE id = p_user_id FOR UPDATE;
  IF v_state IS DISTINCT FROM 'preban' THEN
    RAISE EXCEPTION 'delete_preban_user: row % is not in preban state (%)', p_user_id, v_state;
  END IF;

  -- Audit-before-effect (Invariant 7).
  INSERT INTO audit_log (
    actor_user_id, actor_contact_id, actor_adapter,
    action, target_kind, target_id, target_contact_id,
    scope_id, request_id, details_json
  )
  SELECT p_actor_id,
         a.contact_id, a.adapter,
         'UNBAN_PREBAN_DELETE', 'user', u.id::TEXT, u.contact_id,
         NULL, current_setting('infochat.request_id', TRUE), '{}'::JSONB
    FROM users u
    JOIN users a ON a.id = p_actor_id
   WHERE u.id = p_user_id;

  DELETE FROM users WHERE id = p_user_id AND registration_state = 'preban';
END;
$$;

REVOKE ALL ON PROCEDURE delete_preban_user(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON PROCEDURE delete_preban_user(UUID, UUID) TO infochat_provider;
```

The procedure runs with `SECURITY DEFINER` so the Provider role retains no
direct `DELETE` privilege on `users`. The `BEFORE DELETE` last-admin
trigger still fires (defense-in-depth — a preban row never has `is_admin =
true`).

### 2.1.7 `audit_log` (Invariant 7, Invariant 10)

```sql
CREATE TABLE audit_log (
  id                BIGSERIAL PRIMARY KEY,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_user_id     UUID REFERENCES users(id),       -- nullable for bootstrap rows
  actor_contact_id  TEXT,                            -- denormalized at write time so a future
                                                     --   contact-id rotation does not corrupt
                                                     --   historical audit lookup
  actor_adapter     TEXT,                            -- denormalized adapter name
  action            TEXT NOT NULL,                   -- closed verb enum (see §2.1.8)
  target_kind       TEXT NOT NULL
    CHECK (target_kind IN
      ('user','group','source','post','invite','quarantine','asset','memory','system')),
  target_id         TEXT NOT NULL,                   -- type varies by target_kind; FK or natural key
  target_contact_id TEXT,                            -- denormalized when target is a user
  scope_id          UUID,                            -- group id for group-scoped actions, NULL otherwise
  request_id        TEXT,                            -- correlation id assigned at command intake
                                                     --   or bootstrap
  details_json      JSONB                            -- free-form JSON; subject to redaction in view
);

CREATE INDEX idx_audit_created_at    ON audit_log(created_at DESC);
CREATE INDEX idx_audit_actor         ON audit_log(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_action_target ON audit_log(action, target_kind, target_id);
CREATE INDEX idx_audit_scope         ON audit_log(scope_id, created_at DESC) WHERE scope_id IS NOT NULL;
CREATE INDEX idx_audit_request       ON audit_log(request_id) WHERE request_id IS NOT NULL;
```

**Append-only enforcement (Invariant 10).** Two layers:

1. **Role grants** — both `infochat_collector` and `infochat_provider` are
   `INSERT`-only on `audit_log`. `UPDATE` and `DELETE` are revoked. Only the
   `infochat_admin` role can execute either, and that path is reserved for
   operator-controlled retention runs.
2. **Trigger guard** — defense-in-depth against a misconfigured grant:

   ```sql
   CREATE OR REPLACE FUNCTION trg_audit_log_append_only()
   RETURNS TRIGGER AS $$
   BEGIN
     RAISE EXCEPTION 'audit_log is append-only (Invariant 10)';
   END;
   $$ LANGUAGE plpgsql;

   CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
     FOR EACH ROW EXECUTE FUNCTION trg_audit_log_append_only();
   CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
     FOR EACH ROW EXECUTE FUNCTION trg_audit_log_append_only();
   ```

   The retention sweep runs as `infochat_admin` and uses `DROP / CREATE
   TRIGGER` to disable the guard for the duration of its single-batch
   delete.

Backups must respect Invariant 10: a soft-deletable archive that copies
audit rows into a mutable target table is forbidden (spec §Invariants — 10).

### 2.1.8 `audit_log.action` closed verb enum

V1 verbs (extending the catalogue is a design-note edit, but the matching
test corpus in `infochat-core` keeps the enforcement honest):

| Verb                       | Emitted by                                             | `target_kind`   |
| -------------------------- | ------------------------------------------------------ | --------------- |
| `BOOTSTRAP_ADMIN`          | `@Startup` admin bootstrap (one row per enabled adapter) | `user`        |
| `BOOTSTRAP_SOURCE_LOAD`    | Bootstrap loader (one row per load run)                | `system`        |
| `BOOTSTRAP_ASSET_LOAD`     | Bootstrap loader (one row per load run)                | `system`        |
| `GRANT_ADMIN`              | `/grant-admin`                                         | `user`          |
| `REVOKE_ADMIN`             | `/revoke-admin`                                        | `user`          |
| `BAN`                      | `/ban`                                                 | `user`          |
| `UNBAN`                    | `/unban` (registered row)                              | `user`          |
| `UNBAN_PREBAN_DELETE`      | `/unban` against preban row (carve-out path)           | `user`          |
| `VOUCH`                    | `/vouch`                                               | `user`          |
| `INVITE_CREATE`            | `/invite create`                                       | `invite`        |
| `INVITE_REVOKE`            | `/invite revoke`                                       | `invite`        |
| `INVITE_CONSUME`           | DM intake on first message                             | `invite`        |
| `PROMOTE_GROUP_ADMIN`      | `/promote` (or first-mention auto-promote)             | `user`          |
| `DEMOTE_GROUP_ADMIN`       | `/demote`                                              | `user`          |
| `ADD_SOURCE`               | `/add-source`                                          | `source`        |
| `REMOVE_SOURCE`            | `/remove-source`                                       | `source`        |
| `SOURCE_ENABLE`            | `/source-enable`                                       | `source`        |
| `SOURCE_DISABLE`           | `/source-disable`                                      | `source`        |
| `APPROVE_QUARANTINE`       | `/quarantine approve` (via stored proc)                | `quarantine`    |
| `REJECT_QUARANTINE`        | `/quarantine reject`  (via stored proc)                | `quarantine`    |
| `FORGET`                   | `/forget` (skipped on verified no-op per Invariant 7)  | `memory`        |
| `SET_LANG`                 | `/lang`                                                | `memory`        |
| `SET_TIMEZONE`             | `/group-timezone`                                      | `group`         |

Non-privileged commands (`/summary`, `/saved`, `/save`, `/unsave`,
`/follow-tag`, `/unfollow-tag`, asset commands, `/help`, `/status`,
`/get-tags`, `/get-sources`, `/list-sources`, `/clear`, `/compress`,
`/retry`, `/stop`) do **not** write audit rows in v1 — Invariant 7 covers
*privileged* actions, and the `chat_message` table already records the
content for the user's own scope.

### 2.1.9 `audit_log_view` (DB roles + Secrets handling)

```sql
CREATE OR REPLACE VIEW audit_log_view AS
SELECT
  id,
  created_at,
  actor_user_id,
  redact_contact_id(actor_contact_id) AS actor_contact_id,
  actor_adapter,
  action,
  target_kind,
  target_id,
  redact_contact_id(target_contact_id) AS target_contact_id,
  scope_id,
  request_id,
  redact_secrets_jsonb(details_json) AS details_json
FROM audit_log;

REVOKE ALL ON audit_log_view FROM PUBLIC;
GRANT SELECT ON audit_log_view TO infochat_provider;
-- infochat_provider has NO direct SELECT on audit_log; the view is the
-- single read path. /audit reads through the view.
```

`redact_contact_id(text)` and `redact_secrets_jsonb(jsonb)` are
`IMMUTABLE LANGUAGE plpgsql` functions whose bodies live in
`docs/design/04-security.md` §4.10 (the same closed catalogue used by the
log redactor — OpenAI `sk-*`, Anthropic `sk-ant-*`, GitHub `gh{p,o,u,s,r}_*`,
AWS `AKIA…`/`ASIA…`, Google `AIza…`, Slack `xox*`, generic 32+ char
hex/base64 adjacent to `api_key|secret|token|password|bearer`). The
spec-level commitment is the redacted-column set
(`actor_contact_id`, `target_contact_id`, `details_json`); the regexes are
design.

---

## 2.2 Sources & tags

### 2.2.1 `source` (D7, D38, D42)

```sql
CREATE TABLE source (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  kind            TEXT NOT NULL,                    -- 'rss','bluesky','nostr','reddit',
                                                    --   'youtube','odysee','nitter', …
  identifier      TEXT NOT NULL,                    -- URL for HTTP-shaped sources
                                                    --   filter spec for stream sources (D38)
  config          JSONB NOT NULL DEFAULT '{}'::JSONB, -- opaque per-kind config; restart-only
                                                    --   mutation in v1 (D38)
  display_name    TEXT NOT NULL,
  category        TEXT NOT NULL,                    -- 'news','blog','social'
  bootstrap_tags  TEXT[] NOT NULL DEFAULT '{}',     -- tagger fallback (D22). /add-source --tags ≥1.
  status          TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','failed','disabled')),
  added_by        UUID REFERENCES users(id) ON DELETE SET NULL,
  added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_fetch_at   TIMESTAMPTZ,
  last_success_at TIMESTAMPTZ,
  consecutive_failures INT NOT NULL DEFAULT 0,
  deleted_at      TIMESTAMPTZ,                      -- soft-delete; NULL = active
  deleted_by      UUID REFERENCES users(id) ON DELETE SET NULL,
  UNIQUE (kind, identifier)
);

CREATE INDEX idx_source_status ON source(status) WHERE deleted_at IS NULL;
```

**Soft-delete semantics (Invariant 4).** `/remove-source` sets `deleted_at`
and stops the fetcher / stream worker for that source. `saved_post` rows
snapshot the body at `/save` time (§2.6.1) so post bodies survive the
soft-delete *and* the eventual partition-drop of `post`. Hard-delete is
revoked from both Collector and Provider roles; only `infochat_admin` can
issue raw `DELETE FROM source`. The `(kind, identifier)` UNIQUE means
re-adding via `/add-source` clears `deleted_at` on the existing row instead
of inserting; the bootstrap loader skips rows where `deleted_at IS NOT
NULL` (operator intent gone wrong is not silently overridden).

**Status state machine.** `active ↔ disabled` via bot-admin command;
`active → failed` set by the worker on threshold crossing (D42); `failed →
active` by `/source-enable` or successful manual probe; `disabled →
failed` is structurally impossible (the worker doesn't schedule disabled
rows). All admin transitions are bot-admin only — source rows are global
(D7), so a group admin cannot pause a source that the deployment shares.

### 2.2.2 `tag` (Tier-1 controlled vocabulary, D5)

```sql
CREATE TABLE tag (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name          TEXT NOT NULL UNIQUE,                -- normalized form (NFC, Locale.ROOT lower-case,
                                                     --   character class [a-z0-9][a-z0-9-]{0,47})
  display       TEXT NOT NULL,                       -- original casing for output
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by    UUID REFERENCES users(id),
  source_origin TEXT NOT NULL DEFAULT 'bootstrap'    -- 'bootstrap','user'
    CHECK (source_origin IN ('bootstrap','user')),
  CONSTRAINT tag_name_normalized
    CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,47}$')
);
```

The `socials` tag is auto-assigned by the tagger to every post whose
`source.category = 'social'`, in addition to whatever other tags the LLM
tagger or `bootstrap_tags` produce.

**Vocabulary lifecycle (v1).** Append-only. `/follow-tag`, `/unfollow-tag`,
`/remove-source`, and bootstrap reductions never remove a tag row (spec
§Sources and tags — Vocabulary lifecycle). v2 candidate: `/vocab prune`.

### 2.2.3 `source_subscription`

```sql
CREATE TABLE source_subscription (
  scope_kind  TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
  scope_id    UUID NOT NULL,                          -- users.id for dm, groups.id for group
  source_id   UUID NOT NULL REFERENCES source(id),
  added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  added_by    UUID REFERENCES users(id) ON DELETE SET NULL,
  PRIMARY KEY (scope_kind, scope_id, source_id)
);

CREATE INDEX idx_source_sub_source ON source_subscription(source_id);
```

`ON DELETE CASCADE` on `source_id` is **not** set — soft-delete (`deleted_at`)
is the only path; cascade on hard-delete is the operator's manual problem.

### 2.2.4 `scope_tag` (per-scope follow / unfollow preference)

```sql
CREATE TABLE scope_tag (
  scope_kind  TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
  scope_id    UUID NOT NULL,
  tag_id      UUID NOT NULL REFERENCES tag(id),
  PRIMARY KEY (scope_kind, scope_id, tag_id)
);
```

Default for a fresh scope is "all tags from subscribed sources" (D15) — the
*absence* of any rows for a scope means "all tags," not "no tags." The
mode toggle that distinguishes "absent = all" from "absent = none" lives
on `scope_preferences.tag_mode` below.

### 2.2.5 `scope_preferences`

```sql
CREATE TABLE scope_preferences (
  scope_kind  TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
  scope_id    UUID NOT NULL,
  language    TEXT NOT NULL DEFAULT 'en',             -- ISO 639-1
  timezone    TEXT,                                   -- override; defaults to groups.timezone or UTC
  digest_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  tag_mode    TEXT NOT NULL DEFAULT 'ALL'
    CHECK (tag_mode IN ('ALL','EXPLICIT')),           -- ALL: digest covers tags carried by
                                                      --   subscribed sources (default)
                                                      -- EXPLICIT: digest covers only tags listed in
                                                      --   scope_tag (commands.md §Per-scope tag prefs)
  tag_subscription_version    BIGINT NOT NULL DEFAULT 0,
                                                      -- monotonic counter incremented in the same
                                                      --   transaction as /follow-tag, /unfollow-tag,
                                                      --   /add-source, /remove-source. Folded into
                                                      --   the digest cache key
                                                      --   (group_id, slot, tag_v, src_v) so a
                                                      --   subscription change yields a fresh cache
                                                      --   miss without an explicit invalidation pass.
  source_subscription_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (scope_kind, scope_id)
);
```

---

## 2.3 Posts (ingest)

### 2.3.1 `post` — partitioned by `fetched_at` (Invariant 5, Invariant 6)

```sql
CREATE TABLE post (
  id                  UUID NOT NULL DEFAULT gen_random_uuid(),
  uid                 TEXT NOT NULL,                  -- spec §UID derivation: sha256(source_id ||
                                                      --   '|' || upstream_identifier) lower-case hex.
                                                      --   Stable globally; computed BEFORE Stage 1
                                                      --   against the raw fetched upstream identifier.
                                                      --   v1 SPI requires non-null upstream_identifier;
                                                      --   no content-hash fallback (spec §UID derivation).
  source_id           UUID NOT NULL REFERENCES source(id),
  upstream_identifier TEXT,                           -- guid, AT-URI, Nostr event id, …
                                                      --   Non-null in v1 (every Fetcher must produce a
                                                      --   non-null identifier per spec §UID derivation;
                                                      --   the column is left nullable in the schema so
                                                      --   a future SPI loosening for ID-less sources
                                                      --   doesn't require a migration).
                                                      --   Cap 2048 chars (TOAST-DoS guard for
                                                      --   malicious feeds pushing multi-MB GUIDs);
                                                      --   beyond the cap the fetcher hashes the
                                                      --   raw value (sha256-hex) and stores the digest.
  url                 TEXT,
  title               TEXT NOT NULL,
  body                TEXT,                           -- always plain text (HTML stripped at ingest)
  body_summary        TEXT,                           -- LLM abstract; populated when body length
                                                      --   exceeds the body-summary threshold
                                                      --   (see docs/design/05-llm.md §5.4)
  author              TEXT,
  published_at        TIMESTAMPTZ,
  fetched_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  ready_at            TIMESTAMPTZ,                    -- set when status transitions → READY;
                                                      --   the new_post NOTIFY cursor (architecture.md
                                                      --   §Inter-service communication).
  status_changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                                                      -- updated on every status transition; the
                                                      --   quarantine_review NOTIFY cursor for
                                                      --   post.status → NEEDS_REVIEW transitions.
  last_linked_at      TIMESTAMPTZ,                    -- LinkingJob cursor: NULL ⇒ never linked.
                                                      --   Process where last_linked_at IS NULL OR
                                                      --   last_linked_at < fetched_at.
  status              TEXT NOT NULL DEFAULT 'RAW'
    CHECK (status IN ('RAW','READY','QUARANTINED','NEEDS_REVIEW')),
                                                      -- Invariant 5: NO 'EVALUATING' status.
                                                      --   In-flight evaluation = status='RAW' +
                                                      --   the per-stage *_done flags below.
  -- Per-stage durable cursor (Invariant 5):
  stage1_done     BOOLEAN NOT NULL DEFAULT FALSE,
  stage2_done     BOOLEAN NOT NULL DEFAULT FALSE,
  tagger_done     BOOLEAN NOT NULL DEFAULT FALSE,
  embedding_done  BOOLEAN NOT NULL DEFAULT FALSE,
  stage1_flagged  BOOLEAN NOT NULL DEFAULT FALSE,    -- Stage 1 produced ≥1 quarantine span
  stage2_failed   BOOLEAN NOT NULL DEFAULT FALSE,    -- Stage 2 LLM errored after retry
                                                     --   (release-on-stage2-failure path may set
                                                     --    status=READY with redactions; profile-driven)
  tagger_fallback BOOLEAN NOT NULL DEFAULT FALSE,    -- tags came from source.bootstrap_tags
  tags            TEXT[] NOT NULL DEFAULT '{}',      -- normalized tag names (Tier-1 vocabulary).
                                                     --   Inline rather than via a join table because
                                                     --   the parent is partitioned and a partitioned
                                                     --   M2M join was strictly more complexity for
                                                     --   no query advantage.
  social_score    INT,                               -- 2 * reposts + likes; see docs/design/05-llm.md §5.4
  likes           INT,
  reposts         INT,
  PRIMARY KEY (id, fetched_at),                      -- Postgres requires partition key in PK
  UNIQUE (uid, fetched_at),                          -- per-window dedup; cross-window dedup is the
                                                     --   fetcher's responsibility (see UID derivation
                                                     --   in spec/schema.md)
  UNIQUE (source_id, upstream_identifier, fetched_at)
                                                     -- belt-and-suspenders for sources that supply
                                                     --   stable upstream identifiers
) PARTITION BY RANGE (fetched_at);

-- Daily partitions, created by partition_pruner (see §2.4.4).
-- Indexes are created on the parent and propagated to partitions.
CREATE INDEX idx_post_status_fetched ON post(status, fetched_at DESC);
CREATE INDEX idx_post_source         ON post(source_id, fetched_at DESC);
CREATE INDEX idx_post_published      ON post(published_at DESC);
-- Provider startup reconciler scan (architecture.md §Catch-up):
CREATE INDEX idx_post_ready_at       ON post(ready_at, id) WHERE status = 'READY';
-- LinkingJob driving-set scan:
CREATE INDEX idx_post_link_cursor    ON post(fetched_at)
  WHERE status = 'READY' AND (last_linked_at IS NULL OR last_linked_at < fetched_at);
-- Tag-filtered summary query plan (replaces the old idx_post_user_tag_tag join):
CREATE INDEX idx_post_tags_gin       ON post USING gin (tags);
-- Quarantine-review cursor (post → NEEDS_REVIEW transition):
CREATE INDEX idx_post_status_changed ON post(status_changed_at, id)
  WHERE status = 'NEEDS_REVIEW';
-- Repost-edge resolution: "is the event this kind-6 reposts already
-- stored?" (§2.4.3). The UNIQUE (source_id, upstream_identifier,
-- fetched_at) constraint is unusable without the leading source_id.
CREATE INDEX idx_post_upstream_identifier ON post(upstream_identifier);
```

**Post status state machine.** `RAW → READY` (clean Stage 1 / Stage 2 BENIGN);
`RAW → QUARANTINED` (Stage 2 INJECTION/MALWARE/UNKNOWN, or Stage 1
infrastructure failure — fail-closed); `QUARANTINED → NEEDS_REVIEW` when the
re-eval queue exhausts its per-post attempt cap; `NEEDS_REVIEW → READY` only
via `/quarantine approve`. The post body inside `QUARANTINED` carries Stage
1 redactions (`[REDACTED:<id>]` placeholders); the verbatim original lives
in `quarantine.original_html` and is reachable only via the
`approve_quarantine` stored procedure.

**Why no `is_saved` denormalization.** The previous design carried
`post.is_saved` so the TTL pruner could skip saved posts via a partial
index. With `post` partitioned (Invariant 6) and `saved_post` carrying a
body snapshot (§2.6.1), the post row is dropped by partition cadence
regardless of save state — the bookmark survives in `saved_post`. Saving a
post **does not** keep the parent row alive.

### 2.3.2 Where tags live

Tags are stored inline as `post.tags TEXT[]`. The `tag` table remains the
controlled vocabulary; ingest validates against it before assignment. A
post that reaches `READY` without successful tagging carries
`source.bootstrap_tags` instead and `tagger_fallback = TRUE` (D22).

---

## 2.4 Tier-2 cross-linking (TTL 4 days, partitioned)

### 2.4.1 `post_entity`

```sql
CREATE TABLE post_entity (
  post_id     UUID NOT NULL,                          -- references post.id (no FK; cross-partition
                                                      --   FK is impractical, partition cadence
                                                      --   keeps the rows aligned)
  entity_text TEXT NOT NULL,                          -- normalized (lower-cased, stripped)
  entity_type TEXT NOT NULL,                          -- 'cve','product','org','person','location','project'
  fetched_at  TIMESTAMPTZ NOT NULL,                   -- duplicates post.fetched_at for partition pruning
  PRIMARY KEY (post_id, entity_text, entity_type, fetched_at)
) PARTITION BY RANGE (fetched_at);

CREATE INDEX idx_post_entity_text ON post_entity(entity_text, entity_type);
```

### 2.4.2 `post_embedding`

Dimension is fixed per profile. The migration creates the column matching
the active profile's embedding model dimension. Switching profiles requires
a manual migration that adds a new column or table (see §2.8 below).

```sql
-- For laptop / vps / remote-llm profile (768-d):
CREATE TABLE post_embedding (
  post_id          UUID NOT NULL,
  embedding        vector(768) NOT NULL,
  embedding_model  TEXT NOT NULL,                     -- e.g. 'nomic-embed-text:v1'
  fetched_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (post_id, fetched_at)
) PARTITION BY RANGE (fetched_at);

-- Profile-driven index, created in the same migration:
-- HNSW (laptop / vps / remote-llm):
CREATE INDEX ON post_embedding USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

-- IVFFlat (pi):
-- CREATE INDEX ON post_embedding USING ivfflat (embedding vector_cosine_ops)
--   WITH (lists = 100);
```

For the `pi` profile the column is `vector(384)` (matching `all-minilm`).
**Embeddings are optional** — a post may reach `READY` with no embedding
row when the embedding stage exhausted retries and was released per D22
(spec §Posts and derivatives). Semantic-similarity queries MUST tolerate
the absence (`LEFT JOIN` and ignore `NULL`).

### 2.4.3 `post_reference`

```sql
CREATE TABLE post_reference (
  from_post   UUID NOT NULL,
  to_post     UUID,                                    -- NULL = repost edge not yet resolved (V34);
                                                       --   entity/semantic edges always set
  to_upstream_identifier TEXT,                         -- original event id, verbatim; set only for
                                                       --   link_type='repost' edges (V34)
  link_type   TEXT NOT NULL                            -- 'entity','semantic','repost' ('repost' written by Nostr kind-6, M1-100)
    CHECK (link_type IN ('entity','semantic','repost')),
  score       REAL NOT NULL,                           -- shared-entity count or cosine similarity
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);

-- The V29 PRIMARY KEY (from_post, to_post, link_type, created_at) was
-- replaced in V34 by a unique index over the same column set: PK columns
-- are implicitly NOT NULL, which a nullable to_post cannot satisfy. No
-- INSERT relies on the constraint as an ON CONFLICT arbiter, so only the
-- enforcement mechanism changed.
CREATE UNIQUE INDEX idx_post_ref_unique_edge
    ON post_reference(from_post, to_post, link_type, created_at);

CREATE INDEX idx_post_ref_from ON post_reference(from_post, link_type);
CREATE INDEX idx_post_ref_to   ON post_reference(to_post);
-- Resolver lookup: "which unresolved repost edges point at this
-- newly-persisted original?" Partial — resolved edges leave the index.
CREATE INDEX idx_post_ref_unresolved_target
    ON post_reference(to_upstream_identifier) WHERE to_post IS NULL;
```

Entity and semantic references are directional but always written in both
directions by LinkingJob (A→B and B→A) to keep cluster-walk queries
simple. Cap N=10 outbound links per post (highest score wins).

Repost edges (architecture.md §Ingest SPIs) are written single-direction
by the Nostr kind-6 handler with `to_post = NULL` and the original event
id in `to_upstream_identifier` — the stable, protocol-level join key that
survives the original arriving later or never. If and when the original
event is stored as a post, the resolver flips `to_post` to its `post.id`;
this is the one `UPDATE` the collector performs on this table (`DELETE`
stays revoked per Invariant 6).

### 2.4.4 Partition lifecycle

A nightly `partition_pruner` job:

1. Creates `_yyyymmdd` partitions for tomorrow on `post`, `post_entity`,
   `post_embedding`, `post_reference`, and `price_snapshot` (§2.7.2).
2. `DROP PARTITION` on partitions whose end date is older than the
   per-table retention horizon:
   - `post` — 30 days (laptop/vps/remote-llm), 14 days (pi)
   - `post_entity`, `post_embedding`, `post_reference` — 4 days (all profiles)
   - `price_snapshot` — 7 days (all profiles)

No row-level deletes. Drop is O(1), no index bloat. Saved posts survive
partition drop because their bodies are snapshotted in `saved_post`
(§2.6.1).

---

## 2.5 Quarantine

### 2.5.1 `quarantine` (Invariant 6 carve-out — admin-review TTL)

```sql
CREATE TABLE quarantine (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id         UUID NOT NULL,                       -- partition-aware lookup uses
                                                       --   (post_id, post_fetched_at)
  post_uid        TEXT NOT NULL,                       -- denormalized for survival past partition drop
  post_fetched_at TIMESTAMPTZ NOT NULL,                -- partition locator (no cross-partition FK)
  flagged_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  flagged_by      TEXT NOT NULL                        -- 'stage1','stage2'
    CHECK (flagged_by IN ('stage1','stage2')),
  rule_id         TEXT,                                -- which Stage 1 rule, or model name
  span_start      INT,                                 -- byte offset in original body
  span_end        INT,
  original_html   TEXT NOT NULL,                       -- the suspicious span; READ via stored proc
                                                       --   only — infochat_provider has NO SELECT.
  placeholder_id  TEXT NOT NULL,                       -- inserted into post.body in place of suspicious span
  status          TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','BENIGN_CLOSED','APPROVED','REJECTED')),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),  -- the quarantine_review NOTIFY cursor
                                                       --   for state-machine moves
                                                       --   (architecture.md §Inter-service comm.)
  reviewed_by     UUID REFERENCES users(id),
  review_note     TEXT
);

CREATE INDEX idx_quarantine_status ON quarantine(status, flagged_at);
CREATE INDEX idx_quarantine_post   ON quarantine(post_uid);
CREATE INDEX idx_quarantine_review_cursor
  ON quarantine(updated_at, id);

-- Provider role view: redacted (no original_html).
CREATE OR REPLACE VIEW quarantine_review_view AS
SELECT id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,
       rule_id, placeholder_id, status, updated_at, reviewed_by, review_note
  FROM quarantine;

REVOKE ALL ON quarantine_review_view FROM PUBLIC;
GRANT SELECT ON quarantine_review_view TO infochat_provider;
-- infochat_provider has NO SELECT on quarantine.original_html.
```

**State machine** (spec §Posts and derivatives — Quarantine):

- `PENDING` → `BENIGN_CLOSED` — Stage 2 returns `BENIGN` (first-pass or re-eval).
  Redactions remain in the post body. Only `/quarantine approve` lifts them.
- `PENDING` → `APPROVED` *or* `BENIGN_CLOSED` → `APPROVED` — `/quarantine approve`.
  The original span is restored in `post.body`; `NOTIFY new_post` fires so the
  Provider re-renders the unredacted body via the standard high-water-mark path.
- `PENDING` → `REJECTED` — `/quarantine reject` *or* the admin-review TTL
  auto-reject (Invariant 6 — 14-day cap on PENDING rows). The placeholder
  becomes permanent.
- `BENIGN_CLOSED` → `REJECTED` — only via explicit `/quarantine reject`
  (forensic action). `BENIGN_CLOSED` rows are NOT subject to TTL auto-reject.

**Admin-review TTL.** A nightly cron runs:

```sql
UPDATE quarantine
   SET status = 'REJECTED',
       updated_at = now(),
       review_note = 'auto-rejected: admin-review TTL'
 WHERE status = 'PENDING'
   AND flagged_at < now() - interval '14 days';
```

The job runs as `infochat_admin` and emits one `audit_log` row per
auto-rejected quarantine (action `REJECT_QUARANTINE`, target_kind
`quarantine`, `actor_user_id IS NULL`, `details_json = {"reason":"ttl"}`).
No NOTIFY fires (the post body is unchanged). The throttled admin notifier
already paged when the post entered `NEEDS_REVIEW`, so no second alert.

### 2.5.2 `approve_quarantine` and `reject_quarantine` stored procedures

Both run with `SECURITY DEFINER` so the Provider role retains no `SELECT`
on `quarantine.original_html`. They are the **only** path the Provider has
to lift or finalize redactions.

```sql
CREATE OR REPLACE PROCEDURE approve_quarantine(
  p_quarantine_id UUID,
  p_actor_id      UUID
) LANGUAGE plpgsql AS $$
DECLARE
  q quarantine%ROWTYPE;
  v_post_partition_pred TEXT;
BEGIN
  SELECT * INTO q FROM quarantine WHERE id = p_quarantine_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'approve_quarantine: id % not found', p_quarantine_id;
  END IF;
  IF q.status NOT IN ('PENDING','BENIGN_CLOSED') THEN
    RAISE EXCEPTION 'approve_quarantine: id % is in terminal state %',
      p_quarantine_id, q.status;
  END IF;

  -- Audit-before-effect (Invariant 7).
  INSERT INTO audit_log (
    actor_user_id, actor_contact_id, actor_adapter,
    action, target_kind, target_id, scope_id, request_id, details_json
  )
  SELECT p_actor_id, a.contact_id, a.adapter,
         'APPROVE_QUARANTINE', 'quarantine', q.id::TEXT,
         NULL, current_setting('infochat.request_id', TRUE),
         jsonb_build_object('post_uid', q.post_uid, 'rule_id', q.rule_id)
    FROM users a WHERE a.id = p_actor_id;

  -- Restore the original span in the post body (replace placeholder).
  UPDATE post
     SET body = replace(body, q.placeholder_id, q.original_html),
         status_changed_at = now()
   WHERE id = q.post_id AND fetched_at = q.post_fetched_at;

  -- Lift any QUARANTINED / NEEDS_REVIEW status to READY.
  UPDATE post
     SET status = 'READY',
         ready_at = COALESCE(ready_at, now()),
         status_changed_at = now()
   WHERE id = q.post_id AND fetched_at = q.post_fetched_at
     AND status IN ('QUARANTINED','NEEDS_REVIEW');

  -- Mark the quarantine row.
  UPDATE quarantine
     SET status = 'APPROVED',
         updated_at = now(),
         reviewed_by = p_actor_id
   WHERE id = p_quarantine_id;

  -- Fire NOTIFY for the standard high-water-mark path (architecture.md
  -- §Inter-service communication). Payload is cursor key only.
  PERFORM pg_notify('new_post',
    jsonb_build_object(
      'ready_at', (SELECT ready_at FROM post WHERE id = q.post_id AND fetched_at = q.post_fetched_at),
      'post_id',  q.post_id
    )::TEXT);
  PERFORM pg_notify('quarantine_review',
    jsonb_build_object(
      'target_kind', 'quarantine',
      'target_id',   q.id,
      'new_status',  'APPROVED'
    )::TEXT);
END;
$$;

CREATE OR REPLACE PROCEDURE reject_quarantine(
  p_quarantine_id UUID,
  p_actor_id      UUID
) LANGUAGE plpgsql AS $$
DECLARE
  q quarantine%ROWTYPE;
BEGIN
  SELECT * INTO q FROM quarantine WHERE id = p_quarantine_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'reject_quarantine: id % not found', p_quarantine_id;
  END IF;
  IF q.status IN ('APPROVED','REJECTED') THEN
    RAISE EXCEPTION 'reject_quarantine: id % is in terminal state %',
      p_quarantine_id, q.status;
  END IF;

  INSERT INTO audit_log (
    actor_user_id, actor_contact_id, actor_adapter,
    action, target_kind, target_id, scope_id, request_id, details_json
  )
  SELECT p_actor_id, a.contact_id, a.adapter,
         'REJECT_QUARANTINE', 'quarantine', q.id::TEXT,
         NULL, current_setting('infochat.request_id', TRUE),
         jsonb_build_object('post_uid', q.post_uid, 'rule_id', q.rule_id)
    FROM users a WHERE a.id = p_actor_id;

  UPDATE quarantine
     SET status = 'REJECTED',
         updated_at = now(),
         reviewed_by = p_actor_id
   WHERE id = p_quarantine_id;

  -- Placeholder is already in post.body; no NOTIFY new_post needed
  -- (post body unchanged). Surface the review-cursor advance:
  PERFORM pg_notify('quarantine_review',
    jsonb_build_object(
      'target_kind', 'quarantine',
      'target_id',   q.id,
      'new_status',  'REJECTED'
    )::TEXT);
END;
$$;

REVOKE ALL ON PROCEDURE approve_quarantine(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON PROCEDURE reject_quarantine(UUID, UUID)  FROM PUBLIC;
GRANT EXECUTE ON PROCEDURE approve_quarantine(UUID, UUID) TO infochat_provider;
GRANT EXECUTE ON PROCEDURE reject_quarantine(UUID, UUID)  TO infochat_provider;
```

The verbatim original is intentionally **not** displayed in chat (could
re-inject into the admin's client). Operators use `psql` with the admin
role on the rare occasions they need to read raw.

---

## 2.6 User-scoped state

### 2.6.1 `saved_post` (Invariant 1 carve-out, Invariant 6 carve-out)

`/save` is per-user-globally (D13, spec §Per-user state) — a save in DM is
visible in every group the user is in, and vice versa. Saved bodies are
**snapshotted** so post retention TTL on the underlying partition does not
break the bookmark.

```sql
CREATE TABLE saved_post (
  user_id       UUID NOT NULL REFERENCES users(id),
  post_uid      TEXT NOT NULL,                          -- spec UID; survives post partition drop
  saved_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- Snapshot fields (Invariant 6 — copied at /save time, never re-resolved
  -- against post). source_id stays as a soft FK so /list-sources --include-deleted
  -- can join sources for the bookmark display.
  source_id     UUID NOT NULL REFERENCES source(id),
  title         TEXT NOT NULL,
  body          TEXT,
  url           TEXT,
  author        TEXT,
  published_at  TIMESTAMPTZ,
  snapshot_tags TEXT[] NOT NULL DEFAULT '{}',           -- bot's tags at /save time

  -- User annotations:
  personal_tags TEXT[] NOT NULL DEFAULT '{}',
  note          TEXT,

  PRIMARY KEY (user_id, post_uid)
);

CREATE INDEX idx_saved_user_personal_tags ON saved_post USING gin (personal_tags);
CREATE INDEX idx_saved_user_at            ON saved_post(user_id, saved_at DESC);

-- save_count denormalization on users (powers the 1000-save cap in O(1)).
CREATE OR REPLACE FUNCTION trg_saved_post_count()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE users SET save_count = save_count + 1 WHERE id = NEW.user_id;
    RETURN NEW;
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE users SET save_count = save_count - 1 WHERE id = OLD.user_id;
    RETURN OLD;
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_saved_post_count_ins
  AFTER INSERT ON saved_post
  FOR EACH ROW EXECUTE FUNCTION trg_saved_post_count();
CREATE TRIGGER trg_saved_post_count_del
  AFTER DELETE ON saved_post
  FOR EACH ROW EXECUTE FUNCTION trg_saved_post_count();
```

The 1000-save cap is enforced BEFORE INSERT by checking `users.save_count`.

### 2.6.2 `chat_memory` (D40, Invariant 9)

```sql
CREATE TABLE chat_memory (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id),
  scope_kind        TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
  scope_id          UUID NOT NULL,                       -- user_id for dm, group_id for group
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  summary           TEXT NOT NULL,                       -- 8–10 sentences
  keywords          TEXT[] NOT NULL,                     -- up to 15
  referenced_posts  TEXT[] NOT NULL DEFAULT '{}',        -- post UIDs (stable across partitions)
  referenced_topics UUID[] NOT NULL DEFAULT '{}'         -- ephemeral topic ids; soft-miss on resolve
);

CREATE INDEX idx_chat_memory_scope    ON chat_memory(user_id, scope_kind, scope_id, created_at DESC);
CREATE INDEX idx_chat_memory_keywords ON chat_memory USING gin (keywords);
```

**Per-(user, scope) isolation (Invariant 1).** Every chat_memory row is
tied to one user. In a group, each user has their own memory of their own
conversation with the bot. Users never see each other's memory.

**Cap.** At most 200 chat_memory rows per `(user_id, scope_kind, scope_id)`.
A BEFORE INSERT trigger evicts the oldest row by `created_at ASC` (LRU)
when the cap is reached. Mirrors the 1000-save cap pattern.

**TTL (D40, Invariant 9).** Profile-driven retention horizon, removed by a
scheduled pruner:

| Profile      | `chat_memory` retention |
| ------------ | ----------------------- |
| `laptop`     | 90 days                 |
| `vps`        | 90 days                 |
| `remote-llm` | 90 days                 |
| `pi`         | 30 days                 |

The same pruner clears `chat_session` and `summary_anchor` rows aged past
the same horizon (Invariant 9). `/save`d posts are stored in `saved_post`
(D13) and are not affected. `/forget` is the user-initiated immediate
purge and runs independently.

### 2.6.3 `chat_session` (active context window, Invariant 9)

```sql
CREATE TABLE chat_session (
  user_id      UUID NOT NULL REFERENCES users(id),
  scope_kind   TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
  scope_id     UUID NOT NULL,
  next_seq     INT NOT NULL DEFAULT 0,                   -- monotonic seq counter for chat_message
  token_count  INT NOT NULL DEFAULT 0,                   -- denormalized SUM(chat_message.tokens)
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, scope_kind, scope_id)
);
```

A stale `chat_session` row past the chat-memory TTL is treated as absent on
the next user message (equivalent to `/clear`).

### 2.6.4 `chat_message` (one row per turn)

```sql
CREATE TABLE chat_message (
  user_id     UUID NOT NULL,
  scope_kind  TEXT NOT NULL,
  scope_id    UUID NOT NULL,
  seq         INT  NOT NULL,
  role        TEXT NOT NULL
    CHECK (role IN ('system','user','assistant','tool')),
  content     TEXT NOT NULL,
  tokens      INT  NOT NULL,
  ts          TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, scope_kind, scope_id, seq),
  FOREIGN KEY (user_id, scope_kind, scope_id)
    REFERENCES chat_session(user_id, scope_kind, scope_id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_message_session_seq
  ON chat_message(user_id, scope_kind, scope_id, seq);

-- Triggers maintain chat_session counters:
CREATE OR REPLACE FUNCTION trg_chat_session_counters()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE chat_session
       SET token_count = token_count + NEW.tokens,
           next_seq    = next_seq + 1,
           updated_at  = now()
     WHERE (user_id, scope_kind, scope_id) =
           (NEW.user_id, NEW.scope_kind, NEW.scope_id);
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE chat_session
       SET token_count = token_count - OLD.tokens,
           updated_at  = now()
     WHERE (user_id, scope_kind, scope_id) =
           (OLD.user_id, OLD.scope_kind, OLD.scope_id);
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chat_message_counters
  AFTER INSERT OR DELETE ON chat_message
  FOR EACH ROW EXECUTE FUNCTION trg_chat_session_counters();
```

**Why a child table** rather than a JSONB array on `chat_session`: append
becomes O(1) instead of an O(n) TOAST round-trip on every chat reply
(sessions routinely sit at 50–100 messages × ~2KB each). `/clear` becomes a
single `DELETE` on `(user_id, scope_kind, scope_id)`. Auto-compress reads
the last N rows `ORDER BY seq DESC`, asks the LLM to compress, and writes
the result into `chat_memory`.

`/clear` deletes `chat_message` rows and resets `chat_session.token_count`
and `next_seq`. It does NOT touch `chat_memory` (D25).

### 2.6.5 `summary_anchor` (D19, D36, Invariant 9)

```sql
CREATE TABLE summary_anchor (
  user_id      UUID,                                     -- NULL for digest rows
  scope_id     UUID NOT NULL,
  command_kind TEXT NOT NULL
    CHECK (command_kind IN ('personal','digest')),
  command_name TEXT NOT NULL,                            -- '/summary', '/digest', etc.
  arg_hash     TEXT NOT NULL,                            -- sha256 hex of normalized args
  post_uids    TEXT[] NOT NULL,                          -- ordered selection
  cluster_map  JSONB,                                    -- cluster id → [post_uid, …]
  generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (
    (command_kind = 'personal' AND user_id IS NOT NULL)
    OR
    (command_kind = 'digest'   AND user_id IS NULL)
  )
);

-- Two partial unique indexes so the personal vs. digest row shapes do not
-- collide (spec §Per-scope state — Summary anchor):
CREATE UNIQUE INDEX summary_anchor_personal
  ON summary_anchor(user_id, scope_id, command_kind)
  WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX summary_anchor_digest
  ON summary_anchor(scope_id, command_kind)
  WHERE user_id IS NULL AND command_kind = 'digest';

CREATE INDEX idx_summary_anchor_generated_at
  ON summary_anchor(generated_at);
```

Cleared by any non-`/retry` input from the same `(user, scope)` (personal
rows) or by the next periodic-digest write (digest rows). Subject to the
chat-memory pruner (Invariant 9) so a user who walks away does not leave
anchors behind indefinitely.

`/retry --digest` from a group admin or bot admin matches the digest row
by `(scope_id, command_kind = 'digest')` without referencing `user_id`.

---

## 2.7 Asset commands (D39)

### 2.7.1 `asset_config`

```sql
CREATE TABLE asset_config (
  asset                  TEXT NOT NULL,                  -- 'zcash','monero', …
  sub_verb               TEXT NOT NULL,                  -- 'coingecko','kraken','bitfinex'
  enabled                BOOLEAN NOT NULL DEFAULT TRUE,
  is_default             BOOLEAN NOT NULL DEFAULT FALSE, -- bare /zcash → row with is_default=true
  default_quote_currency TEXT NOT NULL DEFAULT 'USD',
  attribution_url        TEXT NOT NULL,                  -- per-source ToS attribution
  consecutive_failures   INT  NOT NULL DEFAULT 0,
  last_success_at        TIMESTAMPTZ,
  last_failure_at        TIMESTAMPTZ,
  status                 TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','failed','disabled')),
  PRIMARY KEY (asset, sub_verb)
);

-- At most one default sub-verb per asset.
CREATE UNIQUE INDEX one_default_per_asset
  ON asset_config(asset) WHERE is_default = TRUE;
```

**Default-row consistency.** A row with `is_default = TRUE AND enabled =
FALSE` is rejected by the bootstrap loader with a fatal log message
(operator intent gone wrong — they meant to enable the default or move the
flag elsewhere). At runtime, bare `/zcash` / `/monero` against an
inconsistent row returns the friendly "default sub-verb is currently
disabled" error with the enabled sub-verbs listed (defense-in-depth).

The bootstrap loader upserts entries from `bootstrap-assets.json`; entries
absent from the latest bootstrap are `enabled = FALSE` (soft-disable),
never hard-deleted, so prior `price_snapshot` rows remain queryable.

### 2.7.2 `price_snapshot` — partitioned by `captured_at`

```sql
CREATE TABLE price_snapshot (
  asset       TEXT        NOT NULL,
  sub_verb    TEXT        NOT NULL,
  captured_at TIMESTAMPTZ NOT NULL,
  price       NUMERIC(38, 18) NOT NULL,
  currency    TEXT        NOT NULL,
  source_url  TEXT        NOT NULL,
  raw_payload JSONB       NOT NULL,                      -- upstream response fragment for forensic replay
  PRIMARY KEY (asset, sub_verb, captured_at),
  FOREIGN KEY (asset, sub_verb) REFERENCES asset_config(asset, sub_verb)
) PARTITION BY RANGE (captured_at);

-- Latest-snapshot lookup:
CREATE INDEX idx_price_snapshot_latest
  ON price_snapshot(asset, sub_verb, captured_at DESC);
```

**INSERT-only.** No UPDATE / DELETE paths; partitioned and aged by
partition drop on a 7-day retention horizon (long enough that "the latest
snapshot for an enabled `(asset, sub_verb)`" is always present, short
enough that the table does not unbounded-grow).

NOTIFY `new_price_snapshot` is the latency optimization; the table read is
the correctness guarantee. Provider's in-process cache is **flushed
entirely on every Postgres reconnect** so a missed NOTIFY during a
connection blip cannot serve a stale row past the reconnect.

---

## 2.8 Embedding model migration

When `infochat.embeddings.model` (or the `infochat.profile` value that
selects it) changes:

1. Add a new column `embedding_v2 vector(N)` on `post_embedding` matching
   the new dimension.
2. Re-embed all `post` rows where `status = 'READY'` and `fetched_at >
   now() - interval '4 days'`. The 4-day window self-heals fast.
3. Switch the LinkingJob to read `embedding_v2`.
4. After 4 days, drop the old column and rename `embedding_v2 → embedding`.

A migration script `scripts/reembed.sh` automates steps 1–3. The migration
is profile-driven (laptop / vps / remote-llm share 768-d; pi is 384-d).

---

## 2.9 Notification channels & operational state

### 2.9.1 LISTEN / NOTIFY channels (closed list)

PostgreSQL `LISTEN/NOTIFY` only — no Kafka in v1. The closed v1 set
(spec/architecture.md §Inter-service communication; adding a channel is a
spec amendment):

| Channel              | Sent by                                          | Listened by                                | Payload (cursor only)                                          |
| -------------------- | ------------------------------------------------ | ------------------------------------------ | -------------------------------------------------------------- |
| `new_post`           | Collector (after `post.status → READY`)          | Provider (cache invalidation, digest)      | `{ready_at, post_id}`                                          |
| `new_price_snapshot` | Collector Fetcher (after `price_snapshot` write) | Provider (asset-command cache, best-effort) | `{asset, sub_verb}`                                            |
| `quarantine_review`  | Collector / proc (state-machine transitions)     | Provider (admin notifier + cursor advance) | `{target_kind, target_id, new_status}` where target_kind ∈ `{quarantine, post}` |

**Payload size bound.** NOTIFY payloads are bounded to the cursor key.
Postgres NOTIFY has an 8KB hard ceiling, but the spec-level rule is
"cursor only" so a future channel cannot grow the payload beyond a cursor
shape. The receiving side reads the row from the base table for the
actual data — NOTIFY is purely the wake-up signal.

The previous `eval_failure` and `source_failed` channels were removed in
this revision: they are not in the spec's closed list, and the throttled
admin notifier already pages on the equivalent state transitions
(`quarantine_review` `PENDING` insert and `→ NEEDS_REVIEW` for ingest
failures; `source.status → 'failed'` is admin-visible via `/list-sources`).

### 2.9.2 `provider_state` (per-channel high-water mark)

```sql
CREATE TABLE provider_state (
  channel         TEXT        NOT NULL,
  cursor_high     TIMESTAMPTZ NOT NULL,
  cursor_low_kind TEXT        NOT NULL,
  cursor_low_id   TEXT        NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (channel)
);
```

**One row per channel** (singleton enforced by `UNIQUE (channel)`). The
cursor interpretation is per-channel (spec §Operational — Provider state):

| Channel              | `cursor_high`                        | `cursor_low_kind`         | `cursor_low_id` |
| -------------------- | ------------------------------------ | ------------------------- | --------------- |
| `new_post`           | `post.ready_at`                      | `'post'`                  | `post.id`       |
| `quarantine_review`  | `quarantine.updated_at` *or* `post.status_changed_at` (whichever transition fired) | `'quarantine'` *or* `'post'` | quarantine id *or* post id |
| `new_price_snapshot` | (no row — best-effort only)          | —                         | —               |

**First-boot insert** races safely:

```sql
INSERT INTO provider_state (channel, cursor_high, cursor_low_kind, cursor_low_id, updated_at)
VALUES (:channel, 'epoch'::TIMESTAMPTZ, '', '', now())
ON CONFLICT (channel) DO NOTHING;
```

Two fresh Provider instances starting concurrently both attempt the
insert; exactly one wins, and the winning instance owns the cursor. (The
single-instance advisory lock from `architecture.md` §Deployment topology
will reject the second instance shortly thereafter, but the
ON-CONFLICT-DO-NOTHING guard is the schema-layer defense.)

**Compare-and-swap update** so a slow processor cannot roll back a fast
one's mark:

```sql
UPDATE provider_state
   SET cursor_high     = :new_high,
       cursor_low_kind = :new_kind,
       cursor_low_id   = :new_id,
       updated_at      = now()
 WHERE channel = :ch
   AND (cursor_high, cursor_low_kind, cursor_low_id)
       < (:new_high, :new_kind, :new_id);
```

The compound cursor (not `cursor_high` alone) ensures two events sharing a
high-key value are both processed on catch-up — the earlier event advances
the mark to itself, the later event advances it to itself in the same
transaction as its side effect.

The Provider startup reconciler runs:

```sql
SELECT id FROM post
 WHERE status = 'READY'
   AND (ready_at, id) > (:cursor_high, :cursor_low_id)
 ORDER BY ready_at, id;
```

…and feeds each row into the same handler that processes live `NOTIFY
new_post` payloads. The advance happens **in the same DB transaction** as
the side effect, so processing is idempotent — a duplicate NOTIFY or a
repeated catch-up pass for the same row produces no additional effect.

### 2.9.3 `summary_cache` (D17)

Pre-generated periodic-digest output keyed by group, slot, and subscription
versions; short TTL.

```sql
CREATE TABLE summary_cache (
  group_id                    UUID NOT NULL REFERENCES groups(id),
  slot                        TEXT NOT NULL,                       -- '08:00','20:00'
  tag_subscription_version    BIGINT NOT NULL,                     -- snapshot from scope_preferences
  source_subscription_version BIGINT NOT NULL,                     -- snapshot from scope_preferences
  generated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at                  TIMESTAMPTZ NOT NULL,                -- generated_at + slot interval
  rendered_body               TEXT NOT NULL,                       -- already rendered (plain-text)
  post_uids                   TEXT[] NOT NULL,                     -- selection that fed the render
  PRIMARY KEY (group_id, slot, tag_subscription_version, source_subscription_version)
);

CREATE INDEX idx_summary_cache_expires ON summary_cache(expires_at);
```

The cache key folds the two subscription-version counters from
`scope_preferences` (§2.2.5), so a `/follow-tag`, `/unfollow-tag`,
`/add-source`, or `/remove-source` advances the relevant counter and the
next read is a fresh miss — no explicit invalidation pass is needed. A
nightly cron deletes rows past `expires_at`.

### 2.9.4 `admin_notification_state` (D22)

Backing store for the throttled admin notifier. Coalesces alerts of the
same `(channel, error_class)` for a profile-driven window so an outage
produces one summary message, not 200 individual alerts.

```sql
CREATE TABLE admin_notification_state (
  channel       TEXT NOT NULL,                                    -- e.g. 'quarantine_review',
                                                                  --      'source_failed_status',
                                                                  --      'eval_failure'
                                                                  --      (the *internal* notifier
                                                                  --       categories — distinct from
                                                                  --       the closed NOTIFY list)
  error_class   TEXT NOT NULL,                                    -- e.g. 'STAGE2_TIMEOUT','RSS_5XX'
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  occurrence_count INT NOT NULL DEFAULT 1,
  last_payload  JSONB,                                            -- the most recent event details
  notified_at   TIMESTAMPTZ,                                      -- NULL until we've sent a summary
  PRIMARY KEY (channel, error_class)
);

CREATE INDEX idx_admin_notification_pending
  ON admin_notification_state(last_seen_at)
  WHERE notified_at IS NULL OR notified_at < last_seen_at;
```

The notifier worker reads pending rows, sends one summary per
`(channel, error_class)`, and updates `notified_at`. The `channel`
column here names the *internal* notifier category — it is a superset
of the closed `LISTEN/NOTIFY` channel list and includes purely-internal
alert categories (`source_failed_status`, `eval_failure`) that surface
admin alerts but do not have a dedicated `NOTIFY` channel.

### 2.9.5 `bootstrap_meta` (operational helper, design-only)

Spec is silent on this table; it is a design-only operational helper that
records the last successful `bootstrap-sources.json` load so the admin
`/status` view can answer "are all instances running the same bootstrap
config?" without scanning audit history. The audit trail itself lives in
`audit_log` (action `BOOTSTRAP_SOURCE_LOAD`).

```sql
CREATE TABLE bootstrap_meta (
  id                  SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),  -- single-row guard
  last_loaded_sha256  TEXT        NOT NULL,                           -- hex digest of bootstrap-sources.json
  last_loaded_at      TIMESTAMPTZ NOT NULL,
  last_entry_count    INT         NOT NULL,
  last_loader_version TEXT        NOT NULL                            -- Collector build version
);
```

Recorded by `BootstrapLoader` after every successful idempotent load (see
`docs/design/07-deployment.md`).

---

## 2.10 TTL policy summary

| Table                | Retention                                                 | Mechanism                                                                 |
| -------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------- |
| `post`               | 30 d (laptop/vps/remote-llm), 14 d (pi)                   | Drop partition (Invariant 6)                                              |
| `post_entity`        | 4 d                                                       | Drop partition                                                            |
| `post_embedding`     | 4 d                                                       | Drop partition                                                            |
| `post_reference`     | 4 d                                                       | Drop partition                                                            |
| `price_snapshot`     | 7 d                                                       | Drop partition (Invariant 6)                                              |
| `quarantine` `PENDING`        | 14 d (admin-review TTL → auto-`REJECTED`)         | Cron job (§2.5.1)                                                         |
| `quarantine` `BENIGN_CLOSED`/`APPROVED`/`REJECTED` | indefinite (audit artefact)  | none (forensic record)                                                    |
| `audit_log`          | 365 d                                                     | Cron job, `infochat_admin` only (Invariant 10)                            |
| `chat_memory`        | 90 d (laptop/vps/remote-llm), 30 d (pi)                   | Cron pruner (D40, Invariant 9)                                            |
| `chat_session`       | shares `chat_memory` retention                            | Same pruner (Invariant 9)                                                 |
| `summary_anchor`     | shares `chat_memory` retention                            | Same pruner (Invariant 9)                                                 |
| `saved_post`         | indefinite                                                | none — snapshots are user-owned (D13, Invariant 6 carve-out)              |
| `invite_code`        | indefinite (`USED`/`REVOKED` rows are audit artefacts; `PENDING` rows past `expires_at` are treated as expired without writing a transition) | none |
| `users`, `groups`, `group_membership`, `source`, `source_subscription`, `scope_tag`, `scope_preferences`, `tag`, `asset_config`, `provider_state`, `bootstrap_meta` | indefinite | none |

`/forget` is the user-initiated immediate purge of the caller's
`(user, scope)` chat_memory and saved-list (D37). `/save`d posts are
unaffected by the chat-memory pruner.

---

## 2.11 Indexes & expected query plans

The hot paths are documented here so reviewers can verify the right indexes
exist.

| Query                                              | Index used                                                     |
| -------------------------------------------------- | -------------------------------------------------------------- |
| `/summary {tag} -w 24h` (scope-aware)              | `idx_post_tags_gin` → `idx_post_status_fetched`                |
| Cluster lookup by post graph                       | `idx_post_ref_from` and `idx_post_ref_to`                      |
| Tier-2 entity match                                | `idx_post_entity_text` (within 4-day partition)                |
| Tier-2 semantic kNN                                | profile-specific (HNSW or IVFFlat) on `post_embedding.embedding` |
| `/saved` filter by personal tag                    | `idx_saved_user_personal_tags` (GIN)                           |
| Memory pre-fetch by keyword                        | `idx_chat_memory_keywords` (GIN)                               |
| Admin `/audit` scan by actor                       | `idx_audit_actor` (read through `audit_log_view`)              |
| Provider startup reconcile (`new_post`)            | `idx_post_ready_at`                                            |
| Provider startup reconcile (`quarantine_review`)   | `idx_quarantine_review_cursor` + `idx_post_status_changed`     |
| Bare `/zcash` / `/monero` default sub-verb         | `one_default_per_asset` partial unique                         |
| Latest `price_snapshot` for `(asset, sub_verb)`    | `idx_price_snapshot_latest`                                    |
| `/invite consume` race-safe UPDATE                 | `idx_invite_code_pending`                                      |

---

## 2.12 Invariant coverage map

A reverse index from spec invariant → design enforcement, used to validate
the acceptance criterion ("every invariant from spec/schema.md §Invariants
is implemented either as a constraint, a trigger, or a documented test").

| # | Invariant                                       | Enforcement                                                                              |
| - | ----------------------------------------------- | ---------------------------------------------------------------------------------------- |
| 1 | Per-(user, scope) isolation                     | `scope_kind` + `scope_id` columns on `chat_memory`, `chat_session`, `chat_message`, `summary_anchor`, `scope_preferences`, `scope_tag`, `source_subscription`. `saved_post` is the documented carve-out (per-user-globally). |
| 2 | Last-admin protection                           | `trg_users_last_admin_update` + `trg_users_last_admin_delete` (§2.1.2) with `LOCK TABLE … SHARE ROW EXCLUSIVE`. App-issued `DELETE` on `users` only via `delete_preban_user` proc (§2.1.6); `DELETE` revoked from app roles. |
| 3 | At most one group admin per group               | `one_admin_per_group` partial unique index (§2.1.4). Soft-clear trigger frees the slot on `removed_at`. |
| 4 | Soft-delete only for sources                    | `source.deleted_at` column; `DELETE` revoked from `infochat_collector` and `infochat_provider` (`docs/design/04-security.md`). |
| 5 | Outbox + per-stage flags                        | `post.status` enum + `stage1_done`, `stage2_done`, `tagger_done`, `embedding_done`, `stage1_flagged`, `stage2_failed`, `tagger_fallback`. No `EVALUATING` status. |
| 6 | TTL by partitioning                             | `post`, `post_entity`, `post_embedding`, `post_reference`, `price_snapshot` are `PARTITION BY RANGE (…)`. `saved_post` snapshots bodies (carve-out). `quarantine` exempt; admin-review TTL via cron in §2.5.1. |
| 7 | Audit-before-effect                             | Stored procs (`approve_quarantine`, `reject_quarantine`, `delete_preban_user`) `INSERT` into `audit_log` *before* the side effect. Documented test: cancel mid-tx leaves audit row, leaves no side effect. `/forget` no-op carve-out is application-level (skip audit when `RETURNING` count = 0). |
| 8 | No LLM-writable rows                            | Application-level (LLM tool surface SPI in `infochat-core`). Not a schema constraint; documented in `docs/design/05-llm.md` and `docs/design/04-security.md`. |
| 9 | Chat-memory TTL                                 | Cron pruner over `chat_memory`, `chat_session`, `summary_anchor` per the retention table in §2.10. |
| 10 | Audit log append-only                          | `infochat_collector` / `infochat_provider` are `INSERT`-only on `audit_log`. `trg_audit_log_no_update` and `trg_audit_log_no_delete` (§2.1.7) are defense-in-depth. |

---

## 2.13 What's NOT in the schema (intentional)

- **No `is_read` per post.** Read state would multiply rows × users. Out of
  scope for v1.
- **No `tier2_topic` table.** Topic ids are computed at query time from
  `post_reference` connected components and cached in memory. Persisting
  them would require recomputation when a partition rolls.
- **No `post_user_tag` join table.** Tags are inline as `post.tags
  TEXT[]`; the `tag` table is the controlled vocabulary. Removed in this
  revision because cross-partition M2M FK enforcement was strictly more
  complexity for no query advantage (the GIN index on `tags` covers the
  hot path).
- **No granular role table.** v1 uses two booleans (`users.is_admin`,
  `group_membership.is_group_admin`). v2 may introduce roles with
  `manage_sources`, `moderate_security`, `manage_tags`.
- **No per-group bans.** Bot ban is global. Per-group `/kick` is v2.
- **No stored `EXPIRED` invite status.** The intake path treats a
  `PENDING` row whose `expires_at` has passed as expired without writing
  a transition (spec §Invite code).
- **No `eval_failure` / `source_failed` NOTIFY channels.** Removed in
  this revision; the spec's closed list is `new_post`,
  `new_price_snapshot`, `quarantine_review`. Adding a channel is a spec
  amendment.

---
