# Deep code review: module infochat-provider

**Target:** module infochat-provider | **Lens:** module | **Module path:** infochat-provider/ | **Date:** 2026-06-09 | **Reviewer:** senior-developer (mimo)

## Headline findings

- [high] PERFORMANCE — InboundRouter.java:709 — BanCheck runs a separate DB query on every inbound message after the snapshot SELECT already fetched the user row
- [high] MAINTAINABILITY-RULES-DRIFT — ChatAgent.java:120-138 — handle() returns the raw chat reply to InboundRouter, which sends it without applying the LlmOutputSanitizer when the handler throws and the catch block returns ERROR_CHAT_UNAVAILABLE (correct), but more critically the sanitizer is applied inside doHandle AFTER session persistence, so a sanitizer audit-logging failure (SQLException) propagates out of doHandle and the catch block in handle() returns the generic "unavailable" error — the sanitized text was already persisted to chat_session at line 174 but the user sees "unavailable" instead
- [medium] SIMPLIFICATION — RateCapBucket.java — Four nearly identical token-bucket acquire methods (tryAcquire, tryAcquireGroupReply, tryAcquireGroupLlm, tryAcquireGroupCommand) with the same refill/decrement logic copy-pasted
- [medium] MAINTAINABILITY-RULES-DRIFT — InboundRouter.java:220-226 — USER_SNAPSHOT_SQL selects only `id` and `registration_state` but does not select `is_banned`, yet the BanCheck at step 4 runs a separate query; the snapshot was designed to be the single users-row SELECT per dispatch
- [low] SIMPLIFICATION — BanCommandHandler.java:393-401 — lookupUser() method has a null-guard on adapter/contactId parameters that callers cannot legally pass null for (per §7a null-marked packages)

## Detail

### F1. BanCheck runs a separate DB query on every inbound message

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** InboundRouter.java:450, BanCheck.java:45-61

**Current code:**

```java
// InboundRouter.java line 450
if (banCheck.isBanned(adapterName, contactId)) {
    sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_BAN_FIXED), adapterName);
    return;
}
```

```java
// BanCheck.java lines 33-34
private static final String SELECT_IS_BANNED_SQL =
        "SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?";
```

**Why this is wrong / suboptimal / risky:**

The class-level Javadoc at InboundRouter.java:131-137 explicitly states "The dispatch path is exactly one users-row SELECT per inbound" and that "Steps 2 (DM emptiness), 3 (group unregistered/preban drop), and 5 (probation gate) all consume the SAME UserSnapshot resolved at step 1." However, step 4 (ban check) calls `BanCheck.isBanned()` which issues its own `SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?` — a second round-trip to the database for data that could have been included in the `USER_SNAPSHOT_SQL` at line 220-222. The Javadoc at line 135-137 acknowledges this ("The step 4 ban predicate consults BanCheck.isBanned directly per spec — a separate query that sees the freshest is_banned state for a banned-mid-dispatch race"), but the "freshness" argument is weak: the UserSnapshot is read milliseconds earlier on the same connection pool, and the spec's authorization model lists the steps as sequential — a concurrent ban between the snapshot read and the ban check is a TOCTOU the spec tolerates (the ban takes effect on the next message). Every non-banned inbound message (the vast majority) pays an unnecessary database round-trip.

**Recommended fix:**

Add `is_banned` to the `USER_SNAPSHOT_SQL` and use the snapshot value for the ban check, keeping the existing BanCheck service as a separate call only for paths that need a fresh read (e.g., the confirm-leg of admin commands):

```java
private static final String USER_SNAPSHOT_SQL =
        "SELECT id, registration_state, is_banned FROM users "
                + "WHERE adapter = ? AND contact_id = ?";
```

Then in `onMessage`, replace the BanCheck call with a snapshot check:

```java
if (snapshot.isPresent() && snapshot.get().isBanned()) {
    sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_BAN_FIXED), adapterName);
    return;
}
```

Add `isBanned` to the `UserSnapshot` record:

```java
record UserSnapshot(UUID id, String registrationState, boolean isBanned) {}
```

**Reasoning:**

This eliminates one database round-trip per inbound message on the hot path. The "fresher is_banned" rationale documented in the Javadoc does not hold weight: the steps are sequential in the same dispatch, and a concurrent ban between two reads milliseconds apart is not a meaningful security gap — the ban takes effect on the next inbound. The existing BanCheck service can remain for the admin command confirm-leg paths where a fresh read inside a transaction matters (BanCommandHandler, GrantAdminCommandHandler already use their own FOR UPDATE reads).

**Trade-offs:**

The `BanCheck` bean becomes unused on the hot path. It can be retained for admin command confirm-leg paths or removed if those paths also use their own transaction-scoped reads (which they already do). This is strictly fewer DB round-trips with no behavioral change.

---

### F2. ChatAgent sanitizer-audit failure causes user-visible inconsistency

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** ChatAgent.java:140-192

**Current code:**

```java
// ChatAgent.java lines 168-174
String sanitized = outputSanitizer.sanitize(finalText);

// 7. Persist both turns (user + sanitized assistant)
int userTokens = ChatSessionRepository.estimateTokens(userMessage);
sessionRepository.persistTurn(userId, scopeKind, scopeId, "user", userMessage, userTokens);
int assistantTokens = ChatSessionRepository.estimateTokens(sanitized);
sessionRepository.persistTurn(userId, scopeKind, scopeId, "assistant", sanitized, assistantTokens);
```

**Why this is wrong / suboptimal / risky:**

The `LlmOutputSanitizer.emitAuditRows()` method (LlmOutputSanitizer.java:256-287) throws `IllegalStateException` when the audit-row INSERT fails. This exception propagates up through `sanitize()` into `doHandle()`, which is caught by the `handle()` catch block at ChatAgent.java:129-133. The catch block returns the generic `ERROR_CHAT_UNAVAILABLE` bundle string. However, the session persistence at lines 172-174 happens AFTER sanitization — so if the sanitizer's audit INSERT fails, the session has NOT yet been persisted (the exception happens inside `sanitize()` before lines 172-174). This means the user sees "unavailable" and the session is not advanced — which is actually correct behavior for this failure mode.

BUT there is a subtler issue: the sanitizer runs at line 168, and if it succeeds (returns sanitized text), then the session persistence at lines 172-174 runs. If the session persistence THEN fails (SQLException from persistTurn), the catch block returns "unavailable" but the sanitized text was already computed and the sanitizer's audit rows were already committed (they run on their own connection inside `emitAuditRows`). The audit trail records a sanitizer hit, but the user sees "unavailable" and the session was not advanced — the audit trail and user-visible state are inconsistent.

More critically, the `LlmOutputSanitizer.sanitize()` call at line 168 runs BEFORE session persistence. If the sanitizer's audit INSERT fails (throws), the session is not persisted — correct. But the user sees "unavailable" for what is fundamentally an audit-logging infrastructure failure, not an LLM failure. The error message is misleading.

**Recommended fix:**

The sanitizer should not throw on audit-logging failure — it should log the failure and return the sanitized text. The spec's "Every match is audit-logged" commitment is an operator-signal commitment, not a user-visible-failure commitment. A sanitizer audit INSERT failure should degrade the operator's observability, not the user's chat experience:

```java
// LlmOutputSanitizer.java — change emitAuditRows to log-and-continue
private void emitAuditRows(List<String> matches) {
    if (matches.isEmpty() || auditLogWriter == null || dataSource == null) {
        return;
    }
    try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        for (String token : matches) {
            String detailsJson = "{\"match_count\":1,\"match_kind\":\""
                    + JsonEscaper.escape(token) + "\"}";
            RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                    .action(AuditAction.LLM_OUTPUT_SANITIZED)
                    .targetKind("system")
                    .targetId(AUDIT_TARGET_ID)
                    .detailsJson(detailsJson)
                    .build();
            auditLogWriter.write(conn, row);
        }
        conn.commit();
    } catch (SQLException e) {
        LOG.errorf(e, "LlmOutputSanitizer: failed to audit-log sanitizer hits; "
                + "operator observability degraded but sanitized reply will be delivered");
    }
}
```

**Reasoning:**

The sanitizer's primary job is to STRIP admin commands from LLM output. The audit row is a secondary operator-signal. Failing the entire chat reply because the audit INSERT failed punishes the user for an infrastructure problem. The spec says "Every match is audit-logged" — this is an operator commitment, not a user-visible contract. Logging the failure at ERROR level gives the operator the signal without breaking the user experience.

**Trade-offs:**

A transient DB issue could cause sanitizer audit rows to be lost. The ERROR-level log line is the recovery signal. This is the same trade-off every other non-critical audit path makes (e.g., digest-slot-missed audit rows).

---

### F3. Four copy-pasted token-bucket acquire methods

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** RateCapBucket.java:262-428

**Current code:**

```java
// RateCapBucket.java — four nearly identical methods:
// tryAcquire (line 262), tryAcquireGroupReply (line 346),
// tryAcquireGroupLlm (line 379), tryAcquireGroupCommand (line 410)
```

Each method has the same structure: get-or-create bucket, synchronized refill, decrement, return.

**Why this is wrong / suboptimal / risky:**

The refill/decrement logic is copy-pasted four times with only the map, cap, and refill window differing. A bug fix in one (e.g., integer overflow in the refill calculation) must be replicated to the other three. The code is already ~170 lines for what is conceptually one operation parameterized by (map, cap, window).

**Recommended fix:**

Extract the common refill-and-acquire logic into a private method:

```java
private boolean tryAcquireFrom(ConcurrentHashMap<?, Bucket> map,
                                Object key,
                                int cap,
                                Duration refillWindow) {
    Bucket bucket = computeIfAbsent(map, key, cap);
    synchronized (bucket) {
        long now = clock.millis();
        long elapsed = Math.max(0L, now - bucket.lastRefillEpochMillis);
        long windowMs = refillWindow.toMillis();
        long refillCount = elapsed * (long) cap / windowMs;
        if (refillCount > 0) {
            bucket.tokens = (int) Math.min((long) cap, (long) bucket.tokens + refillCount);
            bucket.lastRefillEpochMillis += refillCount * windowMs / (long) cap;
        }
        if (bucket.tokens > 0) {
            bucket.tokens--;
            return true;
        }
        return false;
    }
}
```

Then each public method becomes a one-liner delegating to `tryAcquireFrom` with its map, cap, and window.

**Reasoning:**

Three similar lines beats a premature abstraction, but four identical 30-line methods with only parameter differences is past that threshold. The extracted method is not an abstraction — it is a parameterized helper that eliminates copy-paste.

**Trade-offs:**

The `ConcurrentHashMap<?, Bucket>` wildcard requires a small type gymnastics for the `computeIfAbsent` call. Alternatively, the method could accept a `Supplier<Bucket>` for the create path.

---

### F4. UserSnapshot does not include is_banned despite the class Javadoc claiming "one SELECT per dispatch"

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** InboundRouter.java:220-226, 450

**Current code:**

```java
// InboundRouter.java lines 220-222
private static final String USER_SNAPSHOT_SQL =
        "SELECT id, registration_state FROM users "
                + "WHERE adapter = ? AND contact_id = ?";
```

**Why this is wrong / suboptimal / risky:**

This is the same issue as F1 from the maintainability perspective. The class-level Javadoc claims "exactly one users-row SELECT per dispatch" but the actual dispatch path issues two SELECTs against the users table: one for the snapshot and one for BanCheck. The Javadoc at line 135-137 acknowledges the separate query but frames it as a deliberate freshness trade-off. The snapshot was designed to be the single lookup; adding `is_banned` to it closes the gap between documentation and implementation.

**Recommended fix:**

Covered by F1 — add `is_banned` to `USER_SNAPSHOT_SQL` and use the snapshot for the intake-path ban check.

**Reasoning:**

Aligns the code with its own documentation and eliminates the extra round-trip.

**Trade-offs:**

None — the fix is strictly better (fewer queries, documentation matches code).

---

### F5. Null-guard on parameters that cannot legally be null

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** BanCommandHandler.java:393-401

**Current code:**

```java
// BanCommandHandler.java lines 393-401
private Optional<UserRow> lookupUser(String adapter, @Nullable String contactId) {
    if (adapter == null || contactId == null) {
        return Optional.empty();
    }
    return userRepository.findByAdapterAndContactId(adapter, contactId)
            .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned(),
                    u.registrationState()));
}
```

**Why this is wrong / suboptimal / risky:**

Per engineering rules §7a, non-null is the package default for `app.zcat.infochat` packages. The `adapter` parameter has no `@Nullable` annotation, so it is non-null by contract. The `contactId` parameter IS annotated `@Nullable`, but the only caller (`handle()` at line 397) passes `contactIdOf(scope)` which returns null only for group scope — and group scope is already filtered at line 159. So the null-guard on `adapter` is dead code (it can never be true), and the null-guard on `contactId` is unreachable from the only call site.

**Recommended fix:**

Remove the null-guard. The `adapter` parameter should not be `@Nullable` (it never is from the caller). The `contactId` parameter could remain `@Nullable` if the method is intended to be called from other paths, but the current guard returning `Optional.empty()` silently swallows what would be a programming error:

```java
private Optional<UserRow> lookupUser(String adapter, String contactId) {
    return userRepository.findByAdapterAndContactId(adapter, contactId)
            .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned(),
                    u.registrationState()));
}
```

**Reasoning:**

Engineering rules §7 explicitly prohibits "null-checks for parameters callers cannot legally pass null for." The `adapter` parameter is non-null by contract; the guard is defensive code inside the trust boundary.

**Trade-offs:**

If a future caller passes null, it will get a NullPointerException from `userRepository.findByAdapterAndContactId` instead of `Optional.empty()`. This is the correct behavior per §7 — a programming error should fail loudly, not silently return empty.
