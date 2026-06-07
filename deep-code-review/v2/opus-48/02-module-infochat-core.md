# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-06
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — V21__quarantine_admin.sql:24-119 / V32__quarantine_review_notify_completeness.sql:33-148 — `approve_quarantine` / `reject_quarantine` are `SECURITY DEFINER` but never `REVOKE EXECUTE ... FROM PUBLIC`, so every role (including `infochat_collector`) can invoke them and lift quarantine redactions.
- [high] PERFORMANCE — V35__last_admin_errcode.sql:31 (live), V5__identity_audit.sql:100, V24__identity_audit_remediation.sql:66 — the last-admin trigger takes `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE` unconditionally on every `users` UPDATE, serializing the hot `save_count` / `last_seen_at` write paths behind a global table lock.
- [medium] MAINTAINABILITY-RULES-DRIFT — Fetcher.java:25-28, StreamSource.java:27-28 vs NormalizedPost.java:17-21 — the SPI javadoc says `sourceId` "is the `source.id`" while the record it produces says it is "NOT the `source.id` UUID … must not be used to key any persistent state"; the two halves of the public SPI contradict each other.
- [low] SECURITY — ThrottledAdminNotifier.java:310 — `getState`'s failure log emits the raw, unsanitized caller key while every other path sanitizes it, reintroducing the CR/LF log-forgery vector the class otherwise closes.

## Detail

### F1. SECURITY DEFINER quarantine procedures are executable by PUBLIC

- **Category:** SECURITY
- **Severity:** high
- **Location:** V21__quarantine_admin.sql:24-119 (original), V25__quarantine_procedure_remediation.sql:12-103, V32__quarantine_review_notify_completeness.sql:33-148 (live), V5__identity_audit.sql:398 (the contrasting correct pattern)

**Current code:**

```sql
-- V32 (current definition), end of file — no REVOKE accompanies it:
CREATE OR REPLACE FUNCTION approve_quarantine(p_quarantine_id UUID, p_actor_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$ ... $$;
-- (no REVOKE EXECUTE ... FROM PUBLIC anywhere across V21/V25/V32)

-- V21, the only grant ever issued on these functions:
GRANT EXECUTE ON FUNCTION approve_quarantine(UUID, UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION reject_quarantine(UUID, UUID) TO infochat_provider;
```

Compare with the sibling carve-out procedure, which is hardened correctly:

```sql
-- V5__identity_audit.sql:398
REVOKE ALL ON PROCEDURE delete_preban_user(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON PROCEDURE delete_preban_user(UUID, UUID) TO infochat_provider;
```

**Why this is wrong / suboptimal / risky:**

PostgreSQL grants `EXECUTE` to `PUBLIC` by default on every newly created function/procedure. `delete_preban_user` knows this and explicitly `REVOKE ALL ... FROM PUBLIC` before granting to the Provider. `approve_quarantine` and `reject_quarantine` never do, so the explicit `GRANT … TO infochat_provider` is additive noise — `PUBLIC` already had `EXECUTE`, and still does after V21, V25, and V32.

These two functions are `SECURITY DEFINER`: they run as the migration owner and therefore can read `quarantine.original_html` and write it back into `post.body`, the exact capability V10 withholds from the Provider role ("the Provider has NO SELECT on quarantine.original_html … the role isolation is the defense against an LLM-output-injection vector"). The whole D34 role split exists so that a compromise of one service role is contained. With `PUBLIC` `EXECUTE`, the `infochat_collector` role — which V10 deliberately gives no quarantine-approval path — can call `approve_quarantine`, restore the original (possibly injection/malware) span into the post body, and flip the post to `READY`, which the Provider then serves to users.

The actor-admin check (`IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE)`) is a real compensating control, but it validates the *passed* `p_actor_id`, not the *caller*. The collector role holds `SELECT` on `users` (V5:411), so it can read any admin's UUID and pass it. The check does not restore the trust boundary; only the `EXECUTE` grant does, and it is open.

**Recommended fix:**

Add a `REVOKE` next to the grants, in a new migration (CREATE OR REPLACE in V32 preserved the open ACL, so an in-place edit of an applied migration is not an option):

```sql
-- V39__revoke_public_execute_quarantine.sql
REVOKE ALL ON FUNCTION approve_quarantine(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION reject_quarantine(UUID, UUID) FROM PUBLIC;
-- Provider grants from V21 survive CREATE OR REPLACE and are unchanged;
-- re-assert them here only if defense-in-depth auditing wants them co-located.
GRANT EXECUTE ON FUNCTION approve_quarantine(UUID, UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION reject_quarantine(UUID, UUID) TO infochat_provider;
```

**Reasoning:**

After the `REVOKE`, only `infochat_provider` (and the owner) can invoke the procedures, matching the `delete_preban_user` pattern and the D34 least-privilege model. The Provider's existing call path is unaffected. The fix mirrors an in-tree precedent, so it carries no new design risk.

**Trade-offs:**

None — the fix is strictly better. The `PUBLIC` grant has no legitimate consumer.

---

### F2. Last-admin trigger locks the whole `users` table on every row update

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** V35__last_admin_errcode.sql:25-58 (live `trg_last_admin_protection_update`); same pattern in V5__identity_audit.sql:95-115 and V24__identity_audit_remediation.sql:60-91; delete variant V35:60-80

**Current code:**

```sql
CREATE OR REPLACE FUNCTION trg_last_admin_protection_update()
RETURNS TRIGGER AS $$
DECLARE
    remaining INT;
    v_actor   TEXT;
BEGIN
    LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;   -- unconditional, first statement

    v_actor := current_setting('infochat.actor_id', TRUE);

    IF v_actor IS NOT NULL AND v_actor <> ''
       AND v_actor::UUID = NEW.id
       AND OLD.is_banned = FALSE AND NEW.is_banned = TRUE THEN
        RAISE EXCEPTION 'cannot ban self (actor=%)', v_actor;
    END IF;

    IF (OLD.is_admin = TRUE AND NEW.is_admin = FALSE)
       OR (OLD.is_banned = FALSE AND NEW.is_banned = TRUE AND OLD.is_admin = TRUE) THEN
        SELECT count(*) INTO remaining ...
```

**Why this is wrong / suboptimal / risky:**

`trg_users_last_admin_update` is a `BEFORE UPDATE … FOR EACH ROW` trigger on `users`, and the very first statement of its body is `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE`. `LOCK TABLE` holds until the surrounding transaction commits, and `SHARE ROW EXCLUSIVE` conflicts with `ROW EXCLUSIVE` — the lock mode every ordinary `INSERT`/`UPDATE`/`DELETE` on `users` acquires. So *any* update to a `users` row blocks *all* other writers of `users` deployment-wide for the rest of that transaction.

This fires on far more than admin/ban changes. The `save_count` denormalization trigger (V15:90-102) runs `UPDATE users SET save_count = save_count + 1` on **every `/save` and `/unsave`**, and the spec carries a `last_seen_at` column updated per interaction. Each of those hot-path updates now takes a global table lock on `users`, serializing every concurrent save by every user, and blocking new-user registration (`INSERT users` needs `ROW EXCLUSIVE`) behind it. The lock is justified only for the narrow Invariant-2 serialization requirement (concurrent admin revocation), which is a rare admin operation — not the per-message and per-save traffic that now pays for it.

The serialization the spec actually demands (schema.md Invariant 2: "serialize concurrent revocation attempts") is satisfied as long as the lock is held by every *revocation* transaction. It does not require non-revocation updates to participate.

**Recommended fix:**

Take the lock only when the update is actually admin/ban-relevant — i.e. inside the guard, before the count read:

```sql
CREATE OR REPLACE FUNCTION trg_last_admin_protection_update()
RETURNS TRIGGER AS $$
DECLARE
    remaining INT;
    v_actor   TEXT;
BEGIN
    v_actor := current_setting('infochat.actor_id', TRUE);

    -- Row-local check, no lock needed.
    IF v_actor IS NOT NULL AND v_actor <> ''
       AND v_actor::UUID = NEW.id
       AND OLD.is_banned = FALSE AND NEW.is_banned = TRUE THEN
        RAISE EXCEPTION 'cannot ban self (actor=%)', v_actor;
    END IF;

    IF (OLD.is_admin = TRUE AND NEW.is_admin = FALSE)
       OR (OLD.is_banned = FALSE AND NEW.is_banned = TRUE AND OLD.is_admin = TRUE) THEN
        -- Serialize only the revocation/ban-of-admin path (Invariant 2).
        LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
        SELECT count(*) INTO remaining
          FROM users
         WHERE is_admin = TRUE AND is_banned = FALSE AND id <> NEW.id;
        IF remaining < 1 THEN
            RAISE EXCEPTION 'last_admin_protection: cannot leave the deployment with zero bot admins'
                USING ERRCODE = 'IC001';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

Apply the same move to `trg_last_admin_protection_delete` for consistency (the delete path is rarer — only `delete_preban_user` — but the symmetry keeps the two functions readable as one rule).

**Reasoning:**

Two concurrent revocations both enter the `IF` guard, both reach `LOCK TABLE`, and serialize there; the first commits, the second reads `count(*)` under the freshly granted lock against the post-commit state, sees zero, and raises — exactly the behavior `LastAdminConcurrentRevocationTest` asserts. Non-admin updates (`save_count`, `last_seen_at`, `display_name`, `probation_until`) skip the lock entirely and run at normal row-level concurrency. The invariant is preserved; the global bottleneck on the hot path is removed.

**Trade-offs:**

None for correctness. One minor consideration: the lock now sits inside a conditional, so a future editor must understand that the guard condition is the serialization gate. The existing tests (`LastAdminConcurrentRevocationTest`, `LastAdminTriggerTest`, `CannotBanSelfTriggerTest`) cover both the locking and the non-locking branches, so a regression would surface.

---

### F3. SPI javadoc contradicts the record it produces on the `sourceId` contract

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** Fetcher.java:25-28, StreamSource.java:27-28 vs NormalizedPost.java:17-21

**Current code:**

```java
// Fetcher.java
 * @param sourceId   the {@code source.id} this fetch is on behalf
 *                   of; stamped onto every returned post.

// StreamSource.java
 * @param sourceId   the {@code source.id} this stream is on behalf
 *                   of; stamped onto every delivered post.

// NormalizedPost.java
 *   <li>{@code sourceId} — the per-tick opaque dispatch token the
 *       scheduler handed the Fetcher SPI for this fetch; it is NOT
 *       the {@code source.id} UUID, is not stable across ticks, and
 *       must not be used to key any persistent or cross-tick
 *       state.</li>
```

**Why this is wrong / suboptimal / risky:**

These three types form one public SPI: a `Fetcher`/`StreamSource` receives a `long sourceId` and stamps it onto each `NormalizedPost.sourceId` it returns. The `Fetcher`/`StreamSource` javadoc tells an implementer the value *is* `source.id`; the `NormalizedPost` javadoc tells the same implementer it is *not* `source.id`, is unstable across ticks, and must never be used to key persistent state. Both cannot be true.

The collector resolves the ambiguity in favor of `NormalizedPost`: `FetchScheduler.java:230` calls `fetcher.fetch(row.dispatchKey(), …)` (a per-tick dispatch token, not the UUID), `FetchScheduler.java:439` documents the param as "NOT the [source.id]", and `PostPersister.java:45-49` notes "the NormalizedPost's `sourceId` long is not [the UUID]" and resolves the real UUID separately. So the `Fetcher`/`StreamSource` javadoc is the wrong one.

This is a public SPI: every future `Fetcher` implementation reads `Fetcher.fetch`'s javadoc first. An implementer who believes the parameter is a durable `source.id` may legitimately use it to key cross-tick state (cache, dedup, persistent map), which `NormalizedPost` explicitly forbids and which the dispatch-token reality would silently corrupt. The contradiction is exactly the class of leaky-contract hazard the engineering rules call out, and it sits on the module's most-propagated surface.

**Recommended fix:**

Make the SPI javadoc match the record (and reality):

```java
// Fetcher.java
 * @param sourceId   an opaque per-tick dispatch token the scheduler
 *                   hands this fetch; it is stamped verbatim onto
 *                   {@link NormalizedPost#sourceId()} but is NOT the
 *                   durable {@code source.id} UUID and must not be
 *                   used to key any persistent or cross-tick state.
 *                   The pipeline resolves the real {@code source.id}
 *                   separately when persisting the post.
```

Apply the equivalent wording to `StreamSource.start`'s `sourceId` param.

**Reasoning:**

The collector code and the `NormalizedPost` record agree on the dispatch-token semantics; aligning the two SPI javadocs to that single source of truth removes the contradiction and prevents implementers from building persistent state on an unstable token. No code changes, only the contract text that propagates to every implementation.

**Trade-offs:**

None — documentation-only, and the corrected text matches both the record and the existing collector behavior.

---

### F4. `getState` logs the raw caller key on failure, bypassing its own sanitizer

- **Category:** SECURITY
- **Severity:** low
- **Location:** ThrottledAdminNotifier.java:285-313 (specifically line 310)

**Current code:**

```java
public Optional<AdminNotificationRecord> getState(@NonNull String key) {
    String safeKey = sanitize(key, MAX_KEY_LENGTH);          // sanitized for the DB lookup
    ...
        ps.setString(1, safeKey);
    ...
    } catch (SQLException e) {
        LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", key);  // raw key
        return Optional.empty();
    }
}
```

**Why this is wrong / suboptimal / risky:**

`notifyOnce` sanitizes its inputs precisely because "the notifier owns the ADMIN-NOTIFY scrape contract" — `sanitize` strips CR/LF/NUL so a caller-influenced key cannot forge a second log line (lines 99-122, 219-224). `getState` applies the same `sanitize` to build `safeKey` for the DB lookup, then logs the *unsanitized* `key` in its error branch. A key containing CR/LF would inject newlines into the operator log from this path, the very forgery the class is built to prevent. The asymmetry is also a self-inconsistency: either the key is a boundary input that warrants sanitizing (then this line must use `safeKey`) or it is trusted internal input (then `notifyOnce`'s sanitization is the canonical decision and this path should follow it).

Realistic exposure is small — `getState`'s callers in v1 are tests and (future) admin commands passing low-cardinality constant keys, and the line is not `ADMIN-NOTIFY`-prefixed so it cannot fake a notification — which is why this is low, not higher.

**Recommended fix:**

```java
    } catch (SQLException e) {
        LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", safeKey);
    }
```

**Reasoning:**

`safeKey` is already in scope and is the value actually used against the table, so the log line names the row that was looked up and carries no CR/LF. The one-token change makes `getState` consistent with `notifyOnce` and removes the residual log-injection vector.

**Trade-offs:**

None — `safeKey` is strictly the correct value to log here.

---

## Synthesizer-relevant observations

- The schema migrations hand-copy the seven-family secret catalogue into SQL (`redact_secrets_jsonb`, V31/V33) and rely on `RedactorSqlParityIT` to keep it in lock-step with `Redactor.CATALOGUE`. The Java↔SQL textual-identity coupling is a cross-cutting drift surface (write-side filter vs read-side `audit_log_view` mask) that the architecture pass may want to confirm is the only place the catalogue is duplicated.
- Hand-written `@NonNull` (jspecify) appears throughout this module's public API (`Fetcher`, `StreamSource`, `NormalizedPost`, `RedactionHook`, `AuditLogWriter`, `SafeLog`, `ContactIds`, `AbstractInstanceLockGuard`), which contradicts the documented convention "@NonNull is no longer written by hand … non-null is the package default" (CLAUDE.md §7a). It is harmless redundancy and explicitly outside reviewer annotation-checking, so it is not raised as a finding, but the public SPI surface here is the pattern other modules copy.
