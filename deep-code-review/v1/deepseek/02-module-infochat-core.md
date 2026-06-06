# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-01 20:57
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/test/java/app/zcat/infochat/core/schema/PostgresSchemaTestBase.java:80 — `truncateAll()` omits several tables that tests write to (`source`, `tag`, `source_subscription`, `scope_tag`, `scope_preferences`, `post`), relying on random UUIDs for cross-test isolation instead of deterministic cleanup.
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:115 — `sanitize()` and all `sanitize` call sites are documented as "system boundary" defense but the method is package-private and called from two production methods inside the same class with no caller that overrides the boundary stack; the sanitize logic is actually cross-sectional (applied against the same threat in both internal-method paths) but placed at the method body start rather than as a reusable interceptor or wrapper, creating a traceability gap when a future method forgets to call it.
- [LOW] SECURITY — infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:305 — `getState()` exception log uses raw `key` without sanitization, while `notifyOnce()` sanitises all three inputs before logging or persisting; inconsistent log-boundary treatment.
- [LOW] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java:45 — `CATALOGUE` generic pattern with `{32,}` quantifier on the value fragment and `{0,5}` on the separator allows extended backtracking on near-miss inputs (e.g., 31 matching characters after a keyword); the watchdog timeout at 100ms bounds the cost but the pattern will burn up to 100ms of CPU per timeout event on the logging hot path before the watchdog fires.

## Detail

### F1. PostgresSchemaTestBase.truncateAll() omits key tables from cleanup

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** infochat-core/src/test/java/app/zcat/infochat/core/schema/PostgresSchemaTestBase.java:80-84

**Current code:**

```java
@BeforeEach
void truncateAll() throws SQLException {
    try (Connection c = newConnection(); Statement s = c.createStatement()) {
        s.execute("TRUNCATE TABLE audit_log, invite_code, group_membership, groups, users "
                + "RESTART IDENTITY CASCADE");
    }
}
```

**Why this is wrong / suboptimal / risky:**

The TRUNCATE statement resets only five tables (the V5 identity/audit cluster). Tests like `PerScopeIsolationIT`, `PostPartitioningTest`, `SoftDeletedSourceFkTest`, `SourceTableTest`, `TagTableTest`, `SourceSubscriptionTableTest`, `ScopeTagTableTest`, and `ScopePreferencesTableTest` extend this base and INSERT into `source`, `tag`, `source_subscription`, `scope_tag`, `scope_preferences`, and `post` tables — none of which are truncated between tests.

All these tests use randomly-generated identifiers to avoid cross-contamination (e.g., `"rss-iso-" + UUID.randomUUID()` for source identifiers, random UUIDs for scope IDs). This works by accident: as long as every subclass follows the convention AND uses fresh random values for every test method, rows from previous tests never collide with current test predicates. But:

1. The convention is implicit — a subclass that adds a new test method without using fresh random IDs (e.g., relying on a fixed identifier shared across methods) will fail intermittently when test ordering changes.
2. The accumulated rows grow the tables unboundedly over the lifetime of the shared container. While this is a disposable Testcontainers instance, long IDE sessions running individual tests against the same container could accumulate thousands of orphan rows.
3. Flyway migrations apply once at container start. The partitioned `post` table's bootstrap partition (`post_202605`) is fixed — orphan rows in that partition never get cleaned up between test runs.

This violates the principle of deterministic test isolation. `@BeforeEach` should reset ALL tables that subclass tests can reach, or the base class should document an opt-in cleanup contract that subclasses explicitly acknowledge.

**Recommended fix:**

Extend `truncateAll()` to cover all tables referenced across the schema-test hierarchy, or add a protected hook method that subclasses override to declare additional tables:

```java
@BeforeEach
void truncateAll() throws SQLException {
    try (Connection c = newConnection(); Statement s = c.createStatement()) {
        s.execute("TRUNCATE TABLE audit_log, invite_code, group_membership, groups, users "
                + "RESTART IDENTITY CASCADE");
        truncateAdditional(c);
    }
}

/**
 * Hook for subclasses that write to tables outside the V5 identity
 * cluster. Override to TRUNCATE additional tables.
 */
protected void truncateAdditional(Connection c) throws SQLException {
    // default no-op
}
```

Each subclass overrides `truncateAdditional` to truncate the tables its tests use. For example, `PerScopeIsolationIT` would add:

```java
@Override
protected void truncateAdditional(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
        s.execute("TRUNCATE TABLE source, tag, source_subscription, scope_tag, "
                + "scope_preferences, post CASCADE");
    }
}
```

**Reasoning:**

Explicit per-subclass cleanup makes the test isolation contract visible and enforceable at the subclass level. The hook pattern keeps the base class clean while letting each subclass declare its own cleanup scope. A subclass that forgets to override the hook still gets the base TRUNCATE, so it is not worse off than today — but a subclass that DOES override makes the dependency visible in code review.

**Trade-offs:**

- More boilerplate in each subclass (the override method).
- Tables like `source` and `tag` that are referenced from multiple tests would have their TRUNCATE statement duplicated across subclass overrides.

**Alternative options:**

- **Option A** (recommended above) — hook method pattern, each subclass truncates what it touches.
- **Option B** — single monolithic TRUNCATE in the base class covering every table every schema test could ever touch. Fragile: adding a new table in a migration requires updating the base class even if no test exercises it. Also slower (more CASCADE cascading).

---

### F2. ThrottledAdminNotifier.sanitize() — cross-sectional risk with no enforcement boundary

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:115-122 (sanitize), 217-219 (notifyOnce call site), 284 (getState call site), 305 (getState log miss)

**Current code (sanitize):**

```java
private static String sanitize(String s, int maxLen) {
    String stripped = s.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
    if (stripped.length() <= maxLen) {
        return stripped;
    }
    int keep = Math.max(0, maxLen - TRUNCATION_SUFFIX.length());
    return stripped.substring(0, keep) + TRUNCATION_SUFFIX;
}
```

**Current code (notifyOnce sanitizes all three inputs at entry):**

```java
String safeKey = sanitize(key, MAX_KEY_LENGTH);
String safeErrorClass = sanitize(errorClass, MAX_ERROR_CLASS_LENGTH);
String safeMessage = sanitize(message, MAX_MESSAGE_LENGTH);
```

**Current code (getState sanitizes only the DB lookup, not the log):**

```java
String safeKey = sanitize(key, MAX_KEY_LENGTH);
// ... uses safeKey in DB lookup ...
} catch (SQLException e) {
    LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", key); // raw key!
```

**Why this is wrong / suboptimal / risky:**

The `sanitize` method is documented with a clear rationale ("Applied at the boundary between caller-supplied strings and the log/DB sinks") but the enforcement of "when to sanitize" is manual and dispersed across two production entry points (`notifyOnce` and `getState`). There is no type-level or AOP-level mechanism that guarantees every future caller sanitises before using the value in a log or DB sink.

Currently, `notifyOnce()` sanitises all three inputs at method entry (lines 217-219) and uses the sanitised forms everywhere — DB bind, ADMIN-NOTIFY log line, and return. `getState()` sanitises only the DB lookup key (line 284) but uses the raw `key` in the exception log (line 305). This inconsistency means:

1. A caller who passes a key with embedded CR/LF/NUL characters to `getState` will see those control characters in the operator log if the query fails (line 305), potentially injecting fake log lines into the `WARN` output. The same key passed to `notifyOnce` would have the control characters stripped.
2. A future contributor adding a third method to this class must read every usage site to know whether to sanitize before logging — there is no static safety net.

**Recommended fix:**

Apply sanitization at the method entry of `getState` for the log path as well, and also create a wrapper type or centralized bind helper so the sanitization is structurally enforced rather than manually duplicated.

```java
public Optional<AdminNotificationRecord> getState(@NonNull String key) {
    String safeKey = sanitize(key, MAX_KEY_LENGTH);
    // ... same as today for DB lookup ...
    } catch (SQLException e) {
        LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", safeKey);
        return Optional.empty();
    }
}
```

Additionally, consider extracting the sanitize-and-bind pattern into a small inner helper that takes the raw key and a `BiConsumer<PreparedStatement, String>` for the DB bind, so the sanitization is always applied before both the DB path and the log path:

```java
private static record SanitizedKey(String raw, String safe, int maxLen) {
    SanitizedKey(String raw) {
        this(raw, sanitize(raw, MAX_KEY_LENGTH), MAX_KEY_LENGTH);
    }
}
```

**Reasoning:**

The fix is a one-character change (replace `key` with `safeKey` on line 305) and brings `getState` into consistency with `notifyOnce`. The helper extraction is optional but would prevent the same gap from recurring in future methods.

**Trade-offs:**

None for the simple fix (using `safeKey` in the log). The helper type adds indirection that may be over-engineered for two call sites.

---

### F3. getState() exception log uses unsanitized key

- **Category:** SECURITY
- **Severity:** LOW
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java:305

**Current code:**

```java
} catch (SQLException e) {
    LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", key);
    return Optional.empty();
}
```

**Why this is wrong / suboptimal / risky:**

The `key` parameter is logged raw (unsanitized). A caller that passes a key with embedded control characters (\r, \n, \0) could inject fake log-line boundaries into the operator's log file. This is inconsistent with `notifyOnce()`, which always runs `sanitize()` on every input before any log or DB use.

The realistic exploit is low — the key is caller-chosen and internal callers are trusted to use low-cardinality, stable strings. But the inconsistency sets a bad pattern, and if a future caller derives keys from user-supplied prefix + error class, the `getState` path would become a log-injection vector while `notifyOnce` would remain protected.

**Recommended fix:**

Replace `key` with `safeKey` in the log call (safeKey is already computed at line 284):

```java
} catch (SQLException e) {
    LOG.warnf(e, "ThrottledAdminNotifier: failed to read state for key=%s", safeKey);
    return Optional.empty();
}
```

**Reasoning:**

`safeKey` is already in scope (computed at line 284). The fix is a single variable-name change.

**Trade-offs:**

None — the fix is strictly better.

---

### F4. Redactor.CATALOGUE generic pattern allows extended backtracking before watchdog fires

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** LOW
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java:52-54

**Current code:**

```java
Pattern.compile(
    "(?i)((?:api[_-]?key|secret|token|password|bearer)[\"'\\s:=]{0,5})[A-Za-z0-9+/=_-]{32,}")
```

**Why this is wrong / suboptimal / risky:**

The generic pattern combines a `{0,5}` quantifier on the separator character class with a `{32,}` quantifier on the value fragment. A `java.util.regex` (backtracking) engine processes this pattern against near-miss inputs such as:

- `api_key=` followed by exactly 31 characters from `[A-Za-z0-9+/=_-]` (one short of the 32 minimum): the engine tries every possible way to split the separator across the 31 characters before giving up on the last character.
- A long non-matching string that contains many instances of the trigger keywords: each instance triggers a fresh attempt to find the `{32,}` tail across subsequent bind characters.

The `InterruptibleCharSequence` watchdog fires after `DEFAULT_TIMEOUT_MS = 100ms` by checking `nanoTime()` on every `charAt()` call. For a 1000-character near-miss input, the regex engine can make thousands of `charAt()` calls in fractions of a millisecond before reaching the deadline, burning measurable CPU. On the `isLoggable()` filter path, this blocks the logging thread for up to 100ms per record before the watchdog aborts.

The watchdog correctly bounds the damage (fail-closed, per the spec), but 100ms per log line is expensive for a production log path. The project's Stage 1 pipeline uses the same `InterruptibleCharSequence` + watchdog pattern for the same reason, but Stage 1 runs on the ingest thread where 100ms per post is acceptable. The logging filter runs on every JBoss LogManager `isLoggable()` call — for warning/error patterns that hit the generic redactor, every redact call that times out costs 100ms of thread time.

**Recommended fix:**

Two mitigations, implementable independently:

1. Reduce `DEFAULT_TIMEOUT_MS` for the log filter path. The default 100ms is generous for a logging filter. A 10ms cap would still prevent ReDoS while reducing worst-case blocking from 100ms to 10ms.

2. Add an atomic-`LongAdder` counter for watchdog timeouts so operators can observe (via metrics) how often the generic pattern triggers backtracking. A count increment in the `catch (RegexInterruptedException)` block is cheap and signals whether the catalogue needs refinement.

```java
private static final long LOG_FILTER_TIMEOUT_MS = 10L; // separate cap for log filter

@Override
public boolean isLoggable(@NonNull LogRecord record) {
    String msg = record.getMessage();
    if (msg != null) {
        String redacted = redact(msg, LOG_FILTER_TIMEOUT_MS); // use tighter cap
        // ... rest unchanged
    }
    // ...
}
```

**Reasoning:**

The 100ms timeout was likely chosen to match Stage 1's timeout, but the execution context is different (logging hot path vs. ingest pipeline). A tighter cap for the log filter is a safe change because the watchdog is only engaged when backtracking has already begun — aborting 10ms into it is just as safe as 100ms.

**Trade-offs:**

A tighter cap could cause more sentinel replacements on unusually complex legitimate inputs (e.g., a log message with many long base64 strings adjacent to `token=`). In practice, such inputs are rare in operator logs. The metrics counter lets operators tune the cap empirically.

**Alternative options:**

- **Option A** (recommended) — tighter cap for log filter + metrics counter.
- **Option B** — replace `{32,}` with an `{32,128}` hard upper bound plus a simplifying possessive quantifier `{32,}+` (Java 9+ supports possessive quantifiers for possessive matching, which prevents backtracking into the value fragment). However, possessive quantifiers change semantics if the regex needs to backtrack across the value for a later group or overall match — and the generic pattern has no groups after the value, so `[A-Za-z0-9+/=_-]{32,}+` would be safe and would eliminate the backtracking source entirely. This is the stronger fix.
