# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-06 17:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:310 — `getState` failure log uses the raw `key`, not `safeKey`, re-opening the ADMIN-NOTIFY line-forgery surface `notifyOnce` carefully closes.
- [high] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql:85 — Collector role is granted UPDATE on `price_snapshot`, contradicting spec §Operational ("INSERT-only; no updates") and weakening the SQL-injection blast radius the §DB roles split is designed to bound.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java:62-84 — Class exposes two construction shapes (CDI no-arg + constructor-inject) backing the same `@Inject` field, leaving the field nullable post-construct under the no-arg path and creating two contracts for one class.
- [medium] PERFORMANCE — infochat-core/src/main/resources/db/migration/V18__chat_tables.sql:35-57 — `chat_memory` LRU trigger runs three correlated subqueries inside a `BEFORE INSERT` row trigger with no row-level lock, racing two concurrent INSERTs past the 200-row cap and re-evaluating `COUNT(*)` on every insert when the cap isn't near.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java:24-34 — API signature mimics SLF4J's `error(msg, Throwable)` but silently drops the throwable; future callers reading the signature alone will assume the stack trace lands in the log.
- [low] SIMPLIFICATION — infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java:106-122 — Inline null-branches on `actorUserId` / `scopeId` reimplement what `PreparedStatement.setObject(int, Object, int)` already does in one call.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:276-298 — V5's per-verb line-comment catalogue has gone stale: V12/V13/V27 added enum values that were never appended to V5's authoritative comment block.

## Detail

### F1. `ThrottledAdminNotifier.getState` failure log uses the unsanitized key

- **Category:** SECURITY
- **Severity:** high
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:285-313

**Current code:**

```java
public Optional<AdminNotificationRecord> getState(@NonNull String key) {
    // Sanitize the lookup key the same way notifyOnce does so the
    // two calls with the same caller-supplied key reach the same
    // row (the row was persisted under the sanitized form).
    String safeKey = sanitize(key, MAX_KEY_LENGTH);
    ...
    } catch (SQLException e) {
        LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", key);
        return Optional.empty();
    }
}
```

**Why this is wrong / suboptimal / risky:**

The whole `sanitize()` discipline in this class is dedicated to one
invariant: any string that reaches the log sink (or the persisted
row) is line-boundary-safe, so `grep ADMIN-NOTIFY` cannot be tricked
into picking up an attacker-forged ADMIN-NOTIFY line on a subsequent
line. `notifyOnce` correctly logs `safeKey` on both the EMITTED path
(line 245) and the SQLException fallback (line 271). The `getState`
catch arm regresses that contract by interpolating the raw `key`
parameter — the same caller-supplied string that on the write side
gets stripped of CR/LF/NUL.

A caller passing a key containing `\n` would, on a failed SELECT,
produce a log entry whose second line reads attacker-controlled
text — including a forged `ADMIN-NOTIFY key=…` line indistinguishable
from a real notification to any operator scrape. The threat model
the class commits to ("a future caller forwarding feed-body text or
a driver-supplied error message cannot forge a second ADMIN-NOTIFY
line", line 109-110) is broken at this single call site.

The test corpus would not catch this: `ThrottledAdminNotifierTest`
exercises the notifyOnce sanitization path explicitly
(`notifyOnceStripsControlCharactersFromInputs`) but does not invoke
`getState` with a malicious key.

**Recommended fix:**

```java
} catch (SQLException e) {
    LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", safeKey);
    return Optional.empty();
}
```

**Reasoning:**

`safeKey` is already computed at line 289 for the SELECT bind; using
it in the catch arm too costs nothing and aligns with both
`notifyOnce`'s pattern (sanitize once at entry, use sanitized
everywhere downstream) and the class-level invariant documented at
lines 99-113. The SELECT bind itself is parameterized, so the raw
`key` was never an SQL-injection risk — only a log-injection one
that this fix closes.

**Trade-offs:**

None — the fix is strictly better.

---

### F2. Collector role over-granted UPDATE on `price_snapshot`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql:85

**Current code:**

```sql
GRANT SELECT, INSERT, UPDATE ON price_snapshot TO infochat_collector;
GRANT SELECT                 ON price_snapshot TO infochat_provider;
REVOKE DELETE ON price_snapshot FROM infochat_collector;
REVOKE DELETE ON price_snapshot FROM infochat_provider;
REVOKE DELETE ON price_snapshot FROM PUBLIC;
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/schema.md` §Operational — Price snapshot states
verbatim:

> "Columns: `asset` (FK to `asset_config`), `sub_verb`, `captured_at`,
> `price`, `currency`, `source_url`, `raw_payload` (JSONB — exactly
> the upstream response's relevant fragment, kept for forensic
> replay). **INSERT-only**; no updates."

The V17 grant matrix gives the Collector role UPDATE in addition to
INSERT. The DELETE revoke is in place (correct), but the UPDATE grant
contradicts the spec's "no updates" commitment.

This is the same class of trust-boundary issue that motivates the
whole §DB roles split (security.md §DB roles): "a SQL-injection bug
in the Collector cannot delete posts, mutate price snapshots, alter
quarantine entries…". The "mutate price snapshots" surface is exactly
what an UPDATE grant re-opens.

The asymmetry with `asset_config` (V14, which does need UPDATE for
the failure counter) is what makes the leak easy to miss: an operator
reviewing both V14 and V17 sees parallel structure and assumes
parallel intent, where the spec deliberately separates them
(`asset_config` is mutable bookkeeping; `price_snapshot` is the
audit trail).

V38 (price_snapshot dedup invariant) followed up to add the
`UNIQUE (asset, sub_verb, captured_at)` the V17 PK omitted; the
spec-drift on the GRANT shape went uncaught in the same investigation.

**Recommended fix:**

```sql
GRANT SELECT, INSERT ON price_snapshot TO infochat_collector;
GRANT SELECT         ON price_snapshot TO infochat_provider;
REVOKE UPDATE ON price_snapshot FROM infochat_collector;
REVOKE DELETE ON price_snapshot FROM infochat_collector;
REVOKE DELETE ON price_snapshot FROM infochat_provider;
REVOKE DELETE ON price_snapshot FROM PUBLIC;
```

Since V17 already applied with the wider grant, the fix lands in a
new V-numbered migration that revokes UPDATE explicitly:

```sql
-- V<next>: tighten price_snapshot to spec — INSERT-only.
REVOKE UPDATE ON price_snapshot FROM infochat_collector;
```

**Reasoning:**

The spec phrasing is unambiguous and the design follow-up
(`docs/design/10-asset-commands.md` §"price_snapshot dedup & notify
decisions") reinforces it: "the table is INSERT-only by spec ('no
updates'), so a duplicate write is dropped, never updated." The grant
matrix must match. Adding the REVOKE in a successor migration
preserves V17's applied-checksum integrity while restoring the spec
invariant on existing deployments.

**Trade-offs:**

None — the write path (`PriceSnapshotStore` with `ON CONFLICT … DO
NOTHING`) does not require UPDATE.

---

### F3. `AuditLogWriter` exposes two constructors over one `@Inject` field

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java:62-84

**Current code:**

```java
@Inject
RedactionHook redactionHook;

/**
 * Public no-arg constructor for the CDI runtime. Quarkus ARC
 * instantiates the bean via this constructor and field-injects
 * {@link #redactionHook}.
 */
public AuditLogWriter() {
}

/**
 * Constructor-injection form for non-CDI consumers (plain
 * JUnit tests that do not stand up the Quarkus container).
 * Production code uses the no-arg form and lets CDI inject
 * the hook.
 *
 * @param redactionHook the redaction layer applied to every
 *                      row before INSERT.
 */
public AuditLogWriter(@NonNull RedactionHook redactionHook) {
    this.redactionHook = redactionHook;
}
```

**Why this is wrong / suboptimal / risky:**

The class commits to two shapes simultaneously:

1. A CDI-managed bean with field injection (the package-default
   `@NonNull` on `redactionHook` is escaped via the `@Inject`
   exclusion in the parent POM's NullAway config, so the field is
   effectively nullable between construction and field-injection).
2. A constructor-injected POJO for plain-JUnit tests.

Both work in isolation, but together they encode an implicit rule
("instantiate via no-arg only inside a CDI container, via
constructor outside") that the type signature does not enforce. A
future caller running `new AuditLogWriter()` outside CDI and then
calling `write()` gets a NullPointerException at line 104 — the
exact failure mode JSpecify + NullAway are supposed to make
impossible at compile time.

The cleaner shape (used everywhere else in this module — `Redactor`,
`SafeLog`, etc.) is constructor injection only: Quarkus ARC supports
constructor injection on a single non-default constructor without
the no-arg fallback, and tests then construct the same way
production CDI does.

This sits orthogonal to the engineering rule on defensive code: the
fix is not adding a runtime null check, it's removing the contract
ambiguity at the type level.

**Recommended fix:**

```java
@ApplicationScoped
public class AuditLogWriter {

    private static final String INSERT_SQL = "...";

    private final RedactionHook redactionHook;

    @Inject
    public AuditLogWriter(RedactionHook redactionHook) {
        this.redactionHook = redactionHook;
    }
    ...
}
```

Tests that previously called `new AuditLogWriter(new DefaultRedactionHook())`
keep working unchanged; the only difference is the CDI path now
resolves through the same constructor instead of doing field
injection on a no-arg-constructed instance.

**Reasoning:**

Single construction shape, `final` field, no NullAway-init escape
hatch, no risk of an external caller seeing a half-constructed
instance. Quarkus ARC has supported constructor injection for years;
the no-arg + field-injection shape is a hold-over pattern from older
Quarkus releases.

**Trade-offs:**

None — the fix is strictly better. Test diff is zero (the test
already uses the constructor form).

---

### F4. `chat_memory` LRU trigger races and re-counts on every insert

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** infochat-core/src/main/resources/db/migration/V18__chat_tables.sql:35-57

**Current code:**

```sql
CREATE OR REPLACE FUNCTION trg_chat_memory_cap()
RETURNS TRIGGER AS $$
BEGIN
    DELETE FROM chat_memory
     WHERE id IN (
         SELECT id FROM chat_memory
          WHERE user_id    = NEW.user_id
            AND scope_kind = NEW.scope_kind
            AND scope_id   = NEW.scope_id
          ORDER BY created_at ASC
          LIMIT greatest(0,
              (SELECT count(*) FROM chat_memory
                WHERE user_id    = NEW.user_id
                  AND scope_kind = NEW.scope_kind
                  AND scope_id   = NEW.scope_id) - 199)
     );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chat_memory_cap
    BEFORE INSERT ON chat_memory
    FOR EACH ROW EXECUTE FUNCTION trg_chat_memory_cap();
```

**Why this is wrong / suboptimal / risky:**

Two distinct issues stack:

1. **Race window.** The trigger reads `COUNT(*)` and then issues a
   DELETE without taking any row-level lock against the per-scope
   set. Under READ COMMITTED, two concurrent INSERTs for the same
   `(user_id, scope_kind, scope_id)` arriving when the count is 199
   can both observe 199, both compute `greatest(0, 199 - 199) = 0`
   rows to delete, both INSERT, and end at 201 rows. The cap is
   advisory rather than enforced. This is the exact failure mode the
   V5 `LOCK TABLE … IN SHARE ROW EXCLUSIVE` solves for the last-admin
   trigger; the same discipline is missing here.

2. **`COUNT(*)` per insert.** The trigger runs three indexed reads
   (`COUNT(*)` over the scope, the `ORDER BY created_at ASC LIMIT N`
   subquery, the DELETE) on every single INSERT, including the
   typical case where the scope holds only a handful of rows. The
   trigger could short-circuit the count by checking row existence
   at the cap boundary first.

The spec (`docs/spec/schema.md` §Invariant 9 — Chat-memory TTL) and
design (`docs/design/02-schema.md` §2.6.2) commit to a TTL-pruner
discipline, NOT a per-(user, scope) hard count cap; the 200-row cap
appears to be a design-tier add-on that the trigger here implements
without the locking story the spec attaches to row-count invariants.

**Recommended fix:**

The two issues split cleanly. For correctness:

```sql
CREATE OR REPLACE FUNCTION trg_chat_memory_cap()
RETURNS TRIGGER AS $$
DECLARE
    excess INT;
BEGIN
    -- Lock the scope's rows so concurrent inserts serialize.
    PERFORM 1 FROM chat_memory
     WHERE user_id    = NEW.user_id
       AND scope_kind = NEW.scope_kind
       AND scope_id   = NEW.scope_id
       FOR UPDATE;

    SELECT count(*) - 199 INTO excess
      FROM chat_memory
     WHERE user_id    = NEW.user_id
       AND scope_kind = NEW.scope_kind
       AND scope_id   = NEW.scope_id;

    IF excess > 0 THEN
        DELETE FROM chat_memory
         WHERE id IN (
             SELECT id FROM chat_memory
              WHERE user_id    = NEW.user_id
                AND scope_kind = NEW.scope_kind
                AND scope_id   = NEW.scope_id
              ORDER BY created_at ASC
              LIMIT excess
         );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

For performance: the conditional `IF excess > 0` short-circuits the
DELETE in the common case (scope under the cap), eliminating the
extra ORDER BY + LIMIT + DELETE planning on every typical insert.

**Reasoning:**

The `SELECT … FOR UPDATE` locks every existing row in the scope so
two concurrent INSERTs serialize through the trigger body; the second
INSERT then re-reads the count under its own lock and sees the first
INSERT's effect (the row appears once its trigger commits). The
existing trigger's correctness depends on the implicit assumption
"two concurrent /compress calls for the same scope never happen,"
which the spec does not promise.

**Trade-offs:**

`SELECT … FOR UPDATE` over a per-scope set blocks readers under
SERIALIZABLE but not under READ COMMITTED (Postgres' default). The
expected concurrency for a single (user, scope) is ~1 active
transaction, so the lock contention cost is minimal.

**Alternative options:**

- **Option A** (the recommended fix above — explicit `FOR UPDATE`).
- **Option B** — advisory lock keyed by `hashtext(user_id::text ||
  scope_kind || scope_id::text)`. Cheaper than `FOR UPDATE` but adds
  a new locking mechanism orthogonal to the rest of the schema.

---

### F5. `SafeLog` signature mimics SLF4J but drops the throwable

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java:24-34

**Current code:**

```java
public static void error(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
    logger.error(formatSafe(msg, t));
}

public static void warn(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
    logger.warn(formatSafe(msg, t));
}

public static void info(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
    logger.info(formatSafe(msg, t));
}
```

**Why this is wrong / suboptimal / risky:**

The signature `error(Logger, String, Throwable)` exactly matches
SLF4J's `Logger.error(String, Throwable)`. Every Java developer
reads that signature and expects the stack trace to appear in the
log. Here the throwable is converted to a class-name chain inside
`formatSafe` and the original `Throwable` is **never** passed to the
underlying logger — there is no `t` argument on `logger.error(...)`
at line 25.

The JavaDoc explains the intent ("The original `Throwable` is never
passed to the underlying SLF4J logger — no stack trace, no message
body"), but the JavaDoc is the only signal. A future contributor who
adds a new SafeLog method by copying `error` and then "fixes" it to
also forward the throwable ("the JavaDoc must be wrong, this is
clearly the same shape as SLF4J") silently re-opens the user-content
leak that the class exists to close.

The signature should distinguish itself from SLF4J's:

1. Either name the method to make the distinction visible
   (`errorSanitized`, `errorClassChain`, etc.).
2. Or change the parameter list so the Throwable is presented
   differently (e.g., as a `Class<? extends Throwable>` or pre-
   summarized into a string the caller assembles).

Otherwise, the signature is in tension with itself: it carries the
ergonomic of SLF4J but the semantics of an aggressively-redacting
wrapper.

**Recommended fix:**

```java
public static void errorSanitized(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
    logger.error(formatSafe(msg, t));
}

public static void warnSanitized(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
    logger.warn(formatSafe(msg, t));
}

public static void infoSanitized(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
    logger.info(formatSafe(msg, t));
}
```

Call sites flip mechanically (`SafeLog.error(...)` → `SafeLog.errorSanitized(...)`).
The `Sanitized` suffix telegraphs the difference from `logger.error(msg, t)`
at every read site, not just on the JavaDoc.

**Reasoning:**

Method names are the cheapest possible distinguisher between
look-alike APIs. A 10-character suffix paid once at every call site
saves every future reader from having to remember that "SafeLog
methods drop the throwable" — and saves the codebase from the
contributor who one day notices the dropped throwable and "fixes"
it.

**Trade-offs:**

Call sites grow by 10 characters. The fix is mechanical; no
behavioral change.

**Alternative options:**

- **Option A** (the rename above).
- **Option B** — keep the names, add a static initializer that
  fails fast if the SLF4J Logger interface signature ever changes
  shape so the contract becomes visible at link time. More clever,
  less direct.

---

### F6. `AuditLogWriter.write` open-codes nullable-UUID binding

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java:106-122

**Current code:**

```java
if (redacted.actorUserId() == null) {
    ps.setNull(1, Types.OTHER);
} else {
    ps.setObject(1, redacted.actorUserId());
}
setNullableString(ps, 2, redacted.actorContactId());
setNullableString(ps, 3, redacted.actorAdapter());
ps.setString(4, redacted.action().name());
ps.setString(5, redacted.targetKind());
ps.setString(6, redacted.targetId());
setNullableString(ps, 7, redacted.targetContactId());
if (redacted.scopeId() == null) {
    ps.setNull(8, Types.OTHER);
} else {
    ps.setObject(8, redacted.scopeId());
}
```

**Why this is wrong / suboptimal / risky:**

JDBC provides `PreparedStatement.setObject(int parameterIndex,
Object value, int targetSqlType)` precisely so callers do not need
to branch on null. Passing a null value with `targetSqlType =
Types.OTHER` does the right thing on the PostgreSQL driver, and so
does a non-null `UUID`.

There is also asymmetry with `setNullableString` (a private helper
that wraps `setNull`/`setString` for nullable strings) — the same
shape applied to `String` is extracted to a helper, but for `UUID`
it's inlined. Either both should be helpers or both should be
inlined.

The fix is small but it's representative of the per-call-site noise
the writer carries.

**Recommended fix:**

```java
ps.setObject(1, redacted.actorUserId(), Types.OTHER);
setNullableString(ps, 2, redacted.actorContactId());
setNullableString(ps, 3, redacted.actorAdapter());
ps.setString(4, redacted.action().name());
ps.setString(5, redacted.targetKind());
ps.setString(6, redacted.targetId());
setNullableString(ps, 7, redacted.targetContactId());
ps.setObject(8, redacted.scopeId(), Types.OTHER);
setNullableString(ps, 9, redacted.requestId());
setNullableString(ps, 10, redacted.detailsJson());
```

**Reasoning:**

Single API call per bind, no per-field branching, the same shape
used elsewhere in the codebase for UUID + null. The Postgres JDBC
driver (`org.postgresql:postgresql`) handles the (`UUID`, `Types.OTHER`)
combination natively and treats a null value identically to
`setNull(idx, Types.OTHER)`.

**Trade-offs:**

None — same wire behavior, fewer lines.

---

### F7. V5 verb-catalogue line comments have drifted from the AuditAction enum

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:272-298

**Current code:**

```sql
-- 2.1.8 audit_log.action closed verb catalogue. Per-verb line comments
-- below document the v1 set without pinning it via a SQL CHECK — the
-- catalogue is open-ended for v2 additions and the application-layer
-- audit-write helper is the closure enforcer.
-- BOOTSTRAP_ADMIN
-- BOOTSTRAP_SOURCE_LOAD
-- BOOTSTRAP_ASSET_LOAD
-- GRANT_ADMIN
-- REVOKE_ADMIN
-- BAN
-- UNBAN
-- UNBAN_PREBAN_DELETE
-- VOUCH
-- INVITE_CREATE
-- INVITE_REVOKE
-- INVITE_CONSUME
-- PROMOTE_GROUP_ADMIN
-- DEMOTE_GROUP_ADMIN
-- ADD_SOURCE
-- REMOVE_SOURCE
-- SOURCE_ENABLE
-- SOURCE_DISABLE
-- APPROVE_QUARANTINE
-- REJECT_QUARANTINE
-- FORGET
-- SET_LANG
-- SET_TIMEZONE
```

**Why this is wrong / suboptimal / risky:**

V5 commits to the discipline "per-verb line comments below document
the v1 set" with the explicit purpose "a grep keeps the catalogue
honest" (the rationale appears in V5 lines 27-31). The discipline
held through V12 (which extended via the same line-comment pattern,
V12 line 68: `-- INVITE_BRUTE_FORCE_BREACH`) and V13 (V13 line 20:
`-- LLM_OUTPUT_SANITIZED`), but the `AuditAction` enum has grown
substantially past those:

- `GRANT_ADMIN_INTENT`, `REVOKE_ADMIN_INTENT`, `BAN_INTENT`,
  `INVITE_CREATE_INTENT`, `INVITE_REVOKE_INTENT` (M1-051 intent
  rows).
- `CHAT_MODE` (M1-068).
- `APPROVE_GROUP`, `REJECT_GROUP`, `REJECT_GROUP_INTENT`,
  `D47_GROUP_ONLY_PREBAN_CONVERSION`, `LIST_GROUPS` (D47).
- `MEMBER_LEFT`, `BOT_REMOVED`, `REMOVE_SOURCE_INTENT`,
  `SOURCE_ENABLE_INTENT`, `LIST_SOURCES_ALL`, `AUDIT_READ`,
  `QUARANTINE_LIST`, `EXPORT`, `RE_EVAL_RELEASED`, `DIGEST_RETRY`,
  `DIGEST_SLOT_MISSED`, `QUARANTINE_TTL_REJECT`,
  `STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE`.

None of these have a corresponding `-- <VERB>` line anywhere in the
migration tree. The "grep keeps the catalogue honest" discipline is
no longer honest — a `grep -E '^-- [A-Z_]+$'` across `db/migration/`
omits roughly two-thirds of the live `AuditAction` enum.

Since the application-layer enum IS the closure enforcer (the
migrations explicitly chose NOT to use a CHECK constraint), this is
not a runtime defect — but it's the kind of documentation drift that
breaks the "single grep audit" the spec depends on for v1→v2
catalogue change tracking.

**Recommended fix:**

Add one Vxx migration that lands the missing verbs as line comments
(matching the V12/V13 pattern). The migration body itself stays
minimal (a `DO $$ BEGIN NULL; END $$;` block for Flyway, same shape
as V13), and the comment block enumerates the missing verbs in
enum-declaration order.

```sql
-- Vxx: extend the audit_log.action verb catalogue line-comment
-- block to match the live AuditAction enum.
-- GRANT_ADMIN_INTENT
-- REVOKE_ADMIN_INTENT
-- BAN_INTENT
-- INVITE_CREATE_INTENT
-- INVITE_REVOKE_INTENT
-- APPROVE_GROUP
-- REJECT_GROUP
-- REJECT_GROUP_INTENT
-- D47_GROUP_ONLY_PREBAN_CONVERSION
-- LIST_GROUPS
-- MEMBER_LEFT
-- BOT_REMOVED
-- REMOVE_SOURCE_INTENT
-- SOURCE_ENABLE_INTENT
-- LIST_SOURCES_ALL
-- AUDIT_READ
-- QUARANTINE_LIST
-- EXPORT
-- CHAT_MODE
-- LLM_OUTPUT_SANITIZED (already pinned in V13)
-- RE_EVAL_RELEASED
-- DIGEST_RETRY
-- DIGEST_SLOT_MISSED
-- QUARANTINE_TTL_REJECT
-- STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE
DO $$ BEGIN NULL; END $$;
```

Better yet, add a small build-time check that scans the migration
tree for `-- <VERB>` lines and asserts the set equals the
`AuditAction` enum's `values()` (mirroring the LLM tool-registry
parity check spec'd in `verification.md`).

**Reasoning:**

The line-comment discipline was deliberately chosen over a SQL
CHECK so the catalogue could evolve without DDL churn; the price of
that choice is the comment block has to be maintained. The fix
restores the discipline that was implicitly assumed; the build-time
check (a follow-up) is what makes the discipline self-enforcing.

**Trade-offs:**

A new migration version is consumed for a comment-only update,
which is unusual but matches the V13 precedent.
