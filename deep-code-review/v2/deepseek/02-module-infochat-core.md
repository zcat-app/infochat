# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-07
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [medium] PERFORMANCE — `ThrottledAdminNotifier.java:177-190` — `@PostConstruct` builds UPSERT SQL via `String.formatted()` inlining `Duration.toMillis()`; SQL injection risk is zero but the concatenation pattern is fragile
- [low] MAINTAINABILITY-RULES-DRIFT — `Redactor.java:64-65` — generic adjacent-to-keyword pattern uses a 65-char separator bound with explicit ASCII whitespace class to match SQL `[[:space:]]`; `RedactorSqlParityIT` guards parity but the dual-regex maintenance burden is documented nowhere in the class Javadoc
- [low] MAINTAINABILITY-RULES-DRIFT — `AbstractInstanceLockGuard.java` — heartbeat and advisory-lock guard is an abstract class consumed by both services; the contract (subclass provides `lockName()`, `heartbeatInterval()`, `hostIdentifier()`) is documented in Javadoc but not enforced by a sealed type hierarchy

## Detail

### F1. SQL concatenation in ThrottledAdminNotifier.init() is fragile

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:177-190`

**Current code:**

```java
@PostConstruct
void init() {
    long ms = throttleWindow.toMillis();
    String interval = "INTERVAL '" + ms + " milliseconds'";
    this.upsertSql = """
        INSERT INTO admin_notification_state
            (notification_key, error_class, last_notified_at, notification_count, suppressed_count, first_seen_at)
        VALUES (?, ?, ?, 1, 0, ?)
        ON CONFLICT (notification_key) DO UPDATE SET
            last_notified_at = EXCLUDED.last_notified_at,
            notification_count = admin_notification_state.notification_count + 1,
            error_class = EXCLUDED.error_class
        WHERE admin_notification_state.last_notified_at + %s <= EXCLUDED.last_notified_at
        RETURNING notification_key
        """.formatted(interval);
}
```

**Why this is wrong / suboptimal / risky:**

The code comment at line 137-139 correctly notes that `Duration.toMillis()` returns a `long`, so SQL injection is impossible. The issue is maintainability: a future maintainer changing this to pass a string parameter through `PreparedStatement` would need to understand why the current code uses concatenation. A `PreparedStatement` with an `INTERVAL ?` bind would be cleaner and would make the safety argument structural (the JDBC driver separates the value from the SQL text) rather than dependent on a comment.

The actual risk is zero for the current code. The finding is about fragility: the pattern is unusual and the justification (comment at lines 136-139) is longer than the fix would be.

**Recommended fix:**

Replace string concatenation with a `?` placeholder and bind the interval via `PreparedStatement.setObject()`:

```java
private static final String UPSERT_SQL = """
    INSERT INTO admin_notification_state
        (notification_key, error_class, last_notified_at, notification_count, suppressed_count, first_seen_at)
    VALUES (?, ?, ?, 1, 0, ?)
    ON CONFLICT (notification_key) DO UPDATE SET
        last_notified_at = EXCLUDED.last_notified_at,
        notification_count = admin_notification_state.notification_count + 1,
        error_class = EXCLUDED.error_class
    WHERE admin_notification_state.last_notified_at + ?::interval <= EXCLUDED.last_notified_at
    RETURNING notification_key
    """;
```

And in `notifyOnce`:

```java
ps.setObject(5, throttleWindow); // or a PGInterval / Duration binding
```

**Reasoning:**

The `?::interval` cast makes the parameter binding safe structurally. The `@PostConstruct` field assignment disappears, and the SQL constant becomes a true constant. The `Duration` → Postgres `INTERVAL` binding is supported by the Postgres JDBC driver.

**Trade-offs:**

- The current code works correctly and has zero injection risk.
- A `?::interval` bind requires testing the Postgres JDBC driver's Duration→INTERVAL conversion.

---

### F2. Dual-regex maintenance burden undocumented in Redactor

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java:64-65`

**Current code:**

```java
Pattern.compile(
    "(?i)((?:api[_-]?key|secret|token|password|bearer)[\"' \\t\\n\\x0B\\f\\r\\u00A0:=,|<>()-]{0,64})[A-Za-z0-9+/=_-]{32,}")
```

**Why this is wrong / suboptimal / risky:**

The Javadoc at lines 52-62 explains that the separator class is spelled explicitly (no `\s` shorthand) to match the SQL mirror in migration V33's `redact_secrets_jsonb` function. This is correct and the parity is guarded by `RedactorSqlParityIT`. However, the maintenance burden — any addition, removal, or reordering of characters in this class must be replicated exactly in the SQL function — is documented only in the inline Javadoc of this one pattern, not at the class level. A future developer adding a separator character to the Java regex and forgetting the SQL mirror would be caught by the parity IT, but only at migration-test time, potentially after a deployment.

**Recommended fix:**

Add a class-level Javadoc note: "Any change to the separator character class in the generic adjacent-to-keyword pattern MUST be replicated in the SQL function `redact_secrets_jsonb` in the corresponding Flyway migration. `RedactorSqlParityIT` enforces this at test time."

**Reasoning:**

Makes the dual-maintenance contract visible at the class level, where a developer modifying the pattern would see it before changing the code.

**Trade-offs:**

- None — the fix is strictly better.

---

### F3. AbstractInstanceLockGuard contract is convention-only, not type-enforced

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-core/src/main/java/app/zcat/infochat/core/startup/AbstractInstanceLockGuard.java`

**Why this is wrong / suboptimal / risky:**

The abstract class documents three abstract methods (`lockName()`, `heartbeatInterval()`, `hostIdentifier()`) that subclasses in `infochat-collector` and `infochat-provider` must implement. The contract is enforced only by Javadoc and `abstract` method signatures — there is no sealed type hierarchy preventing a third subclass from being added in a new module with different semantics. This is the correct v1 choice (only two subclasses exist, both in separate modules), but the pattern is fragile if a third service is ever added.

**Recommended fix:**

If the two-service topology is permanent (per decision D41), seal the hierarchy: declare `AbstractInstanceLockGuard` as `sealed` permitting `InstanceLockGuard` in collector and provider. If a third service is a future possibility, leave as-is and add a class-level comment noting the two allowed subclasses.

**Reasoning:**

Sealed types make the "exactly two services" rule compiler-enforced.

**Trade-offs:**

- Java `sealed` requires listing permitted subclasses in the `permits` clause, which means `infochat-core` would need to know about types in `infochat-collector` and `infochat-provider` — a circular dependency at the type level.
- The two subclasses already exist and no third is planned. The `abstract` contract with documentation is sufficient for v1.
