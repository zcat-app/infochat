# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-01 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] SECURITY — infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:305 — `getState` error path logs the raw, unsanitized `key`, bypassing the class's own `sanitize()` CRLF/forgery guard.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/V19__summary_anchor.sql:5-20 — `summary_anchor` carries `scope_id` with no `scope_kind` discriminator, unlike every other per-(user, scope) table, so isolation rests on UUID-namespace non-collision rather than an explicit discriminator.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/V27__d47_remove_group_only.sql:51-52 — the migration writes an `audit_log.action` verb (`D47_GROUP_ONLY_PREBAN_CONVERSION`) that does not exist in the `AuditAction` enum the codebase declares as the closed application-layer verb set.

## Detail

### F1. `getState` logs the raw caller-supplied key on the read-failure path, defeating the notifier's own line-injection guard

- **Category:** SECURITY
- **Severity:** medium
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:280-307

**Current code:**

```java
public Optional<AdminNotificationRecord> getState(@NonNull String key) {
    // Sanitize the lookup key the same way notifyOnce does so the
    // two calls with the same caller-supplied key reach the same
    // row (the row was persisted under the sanitized form).
    String safeKey = sanitize(key, MAX_KEY_LENGTH);
    final String sql = ...;
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, safeKey);
        ...
    } catch (SQLException e) {
        LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", key);
        return Optional.empty();
    }
}
```

**Why this is wrong / suboptimal / risky:**

The class documents (and `notifyOnce` faithfully applies) a single-point sanitization contract: every caller-supplied string that reaches the log sink is passed through `sanitize()`, which strips `\r`, `\n`, and `\0` so "a future caller forwarding feed-body text or a driver-supplied error message cannot forge a second ADMIN-NOTIFY line" (lines 99-113). `getState` computes `safeKey` for the SQL bind but then logs the **raw** `key` parameter, not `safeKey`, on the `SQLException` path. A key containing a CRLF (e.g. a key derived from a feed-influenced source identifier or asset name — the spec's own example key shape is `asset-source-failed:zcash:price`, and V16's comment lists `tagger-fallback:<source-uuid>`) can inject a forged line into the operator log when this read happens to hit a DB error. The JBoss console `Redactor` filter does not close this gap: `Redactor.redact` only masks API-key shapes and never strips control characters. This is the exact forgery surface the `sanitize()` method exists to prevent; one call site silently opts out of it.

**Recommended fix:**

```java
} catch (SQLException e) {
    LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", safeKey);
    return Optional.empty();
}
```

**Reasoning:**

`safeKey` is already in scope and is the value that was used for the query and that all other log lines about this key use. Logging it instead of `key` makes the read-failure path obey the same line-boundary contract as `notifyOnce`, with no behavioral change for well-formed keys.

**Trade-offs:**

None — the fix is strictly better. `safeKey` is the value that was actually queried, so it is also the more accurate thing to log.

---

### F2. `summary_anchor` omits the `scope_kind` discriminator carried by every other per-(user, scope) table

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-core/src/main/resources/db/migration/V19__summary_anchor.sql:5-30

**Current code:**

```sql
CREATE TABLE summary_anchor (
    user_id      UUID,
    scope_id     UUID        NOT NULL,
    command_kind TEXT        NOT NULL
        CHECK (command_kind IN ('personal','digest')),
    ...
);

CREATE UNIQUE INDEX summary_anchor_personal
    ON summary_anchor(user_id, scope_id, command_kind)
    WHERE user_id IS NOT NULL;
```

**Why this is wrong / suboptimal / risky:**

Schema invariant 1 (`docs/spec/schema.md` §Invariants) requires every user-state row to carry "a scope discriminator (`'dm'` or `'group'`) **and** a scope id". Every other per-(user, scope) table in this module enforces that pattern with a `scope_kind TEXT CHECK (scope_kind IN ('dm','group'))` column plus `scope_id`: `source_subscription` (V7), `scope_tag` (V7), `scope_preferences` (V7), `chat_session`, `chat_memory`, `chat_message` (V18). `summary_anchor` is the lone exception — it has `scope_id` but no `scope_kind`. The personal anchor's uniqueness and `/retry` lookup key are therefore `(user_id, scope_id, command_kind)` with no scope-kind component. Isolation between a user's DM anchor and that same user's anchor in a group then depends entirely on DM and group `scope_id` values never colliding in the UUID space, rather than on an explicit discriminator the way the rest of the schema guarantees it. This is both a consistency hazard (a reader who has internalised the `(scope_kind, scope_id)` shape everywhere else will mis-model this table) and a latent correctness risk if the DM scope-id convention is ever anything other than a globally-unique UUID.

**Recommended fix:**

```sql
CREATE TABLE summary_anchor (
    user_id      UUID,
    scope_kind   TEXT        NOT NULL CHECK (scope_kind IN ('dm','group')),
    scope_id     UUID        NOT NULL,
    command_kind TEXT        NOT NULL
        CHECK (command_kind IN ('personal','digest')),
    ...
);

CREATE UNIQUE INDEX summary_anchor_personal
    ON summary_anchor(user_id, scope_kind, scope_id, command_kind)
    WHERE user_id IS NOT NULL;

-- digest rows are group-scope by construction
CREATE UNIQUE INDEX summary_anchor_digest
    ON summary_anchor(scope_kind, scope_id, command_kind)
    WHERE user_id IS NULL AND command_kind = 'digest';
```

**Reasoning:**

Adding `scope_kind` brings the table in line with invariant 1's literal wording and with the discriminator pattern used by the other six per-scope tables. The lookup and uniqueness keys then isolate by `(scope_kind, scope_id)` explicitly instead of trusting UUID-namespace separation. This is a schema migration, so it must be a forward migration (e.g. V30) rather than an edit to V19 if V19 has already been applied anywhere; on a greenfield M1 build editing V19 in place is also acceptable.

**Trade-offs:**

One extra column and a slightly wider index. The digest index gains `scope_kind` which is always `'group'` for digest rows, so it is informationally redundant for that partial index but keeps the two indexes structurally parallel. If the column layout is judged to be purely design-tier (it lives in `docs/design/02-schema.md`), the minimum action is to record explicitly, in the design note and a why-comment on the table, that `summary_anchor` deliberately drops `scope_kind` and why that is safe given the DM scope-id convention — so the divergence is a documented decision rather than a silent gap.

---

### F3. V27 writes an `audit_log.action` verb that is absent from the `AuditAction` closed set

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-core/src/main/resources/db/migration/V27__d47_remove_group_only.sql:51-52

**Current code:**

```sql
INSERT INTO audit_log (action, target_kind, target_id, details_json)
SELECT 'D47_GROUP_ONLY_PREBAN_CONVERSION',
       'system',
       'users.registration_state',
       jsonb_build_object('affected_rows', count(*))
  FROM updated
HAVING count(*) > 0;
```

**Why this is wrong / suboptimal / risky:**

`AuditAction`'s javadoc states it "IS that application-layer closure: every audit row written through `AuditLogWriter` must name its verb via an `AuditAction` constant," and the V5 design comment names the application-layer helper as "the closure enforcer" for the otherwise un-CHECK-constrained `action` column. The SECURITY DEFINER procedures already carve out a documented exception and their verbs (`UNBAN_PREBAN_DELETE`, `APPROVE_QUARANTINE`, `REJECT_QUARANTINE`) are mirrored in the enum precisely so application read-paths can reference one symbol. V27 introduces a fourth migration-direct verb, `D47_GROUP_ONLY_PREBAN_CONVERSION`, that is **not** present in `AuditAction`. Any current or future read-path that maps `audit_log.action` text to an `AuditAction` constant (e.g. `/audit` rendering, audit analytics) will throw `IllegalArgumentException` on this row. The drift is small today because the verb appears only on a one-off deployment-migration row, but it contradicts the enum's stated role as the complete closed set.

**Recommended fix:**

```java
// in AuditAction.java
    STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE,
    D47_GROUP_ONLY_PREBAN_CONVERSION
}
```

**Reasoning:**

Adding the constant restores the invariant that every verb appearing in `audit_log.action` has a corresponding `AuditAction` symbol, so read-paths can map every historical row without a special case. The enum is a no-DDL change; it does not require a migration.

**Trade-offs:**

None of substance. The alternative is to deliberately exclude migration-only verbs from the enum and document that read-paths must tolerate unknown action strings — but that weakens the "closed set" contract the enum currently advertises and pushes defensive parsing onto every consumer.

## Synthesizer-relevant observations

- The audit-write actor-integrity trigger `trg_audit_log_actor_check` (V24) enforces that any `audit_log` INSERT naming a non-null `actor_user_id` must match the session GUC `infochat.actor_id` when that GUC is set. The SECURITY DEFINER procedures `approve_quarantine` / `reject_quarantine` (V21/V25) and `delete_preban_user` (V5/V24) insert audit rows with `actor_user_id = p_actor_id`. This couples a cross-module behavioral contract: the Provider must either leave `infochat.actor_id` unset or set it equal to the `p_actor_id` it passes these procedures, on the same connection. Whether the Provider honors that is outside this module; flag it for the architecture lens.
- `price_snapshot.asset` (V17) is declared as a bare `TEXT NOT NULL` with no FK to `asset_config`, while `docs/spec/schema.md` §Price snapshot describes `asset` as "(FK to `asset_config`)". A composite-key FK from a partitioned table is not expressible against `asset_config`'s `(asset, sub_verb)` PK, so the omission is defensible at the design tier, but the spec-vs-implementation wording gap is worth one synthesizer note across the schema surface.
