# Deep code review — kimi-k (self-run, no subagents)

**Date:** 2026-06-01  
**Scope:** Full repo — architecture + all 6 modules  
**Method:** Direct Read/Grep analysis against spec contracts and engineering rules §1–§8

---

## Headline findings

- **[high] MAINTAINABILITY-RULES-DRIFT** — `infochat-provider/pom.xml` declares `infochat-messaging-adapter` twice (duplicate dependency element). Maven tolerates it, but it is a maintenance hazard and signals copy-paste drift in the POM.
- **[high] MAINTAINABILITY-RULES-DRIFT** — Widespread missing `@NonNull`/`@Nullable` on public methods across both services. `scripts/lint-contracts.py` baseline is empty, but the corpus has hundreds of unannotated public methods (e.g. `InboundContext.adapterName()`, `ProviderStateDao.readCursor(String)`, `HelpCommandHandler.handle(ScopeRef, String)`, `ClusterTraversal.cluster(List<Post>)`, `EligiblePostQuery.fetch(...)`). This violates §7a which mandates explicit parameter contracts on every public/protected method.
- **[medium] SECURITY** — `AuditCommandHandler.java:155-156` uses String concatenation to build SQL: `"SELECT count(*) FROM audit_log_view" + where`. While `where` is currently built with `?` placeholders, the concatenation pattern is fragile; a future refactor that inlines a user-controlled value into `where` would create SQL injection. The same pattern repeats at line 180 for the data query. Both should use a static SQL template with appended clauses, or a query builder.
- **[medium] MAINTAINABILITY-RULES-DRIFT** — `InstanceLockGuard` is duplicated almost byte-for-byte in `infochat-collector` and `infochat-provider`. Only the `SERVICE` constant differs. This violates DRY and means a security fix to the single-instance enforcement (e.g. heartbeat race, lock release logic) must be applied in two places.
- **[medium] SECURITY** — `AnthropicProvider.java:201` catches `Exception ignored` in `extractErrorMessage`. This is overly broad: a `JsonProcessingException` or `IOException` from the JSON parser is expected, but `Exception` also swallows `OutOfMemoryError`, `StackOverflowError`, and runtime exceptions that should propagate. The method should catch `IOException` specifically.
- **[medium] MAINTAINABILITY-RULES-DRIFT** — `QuarantineReviewListener.getUpsertSql()` builds SQL with string concatenation: `"INTERVAL '" + ms + " milliseconds'"`. While `ms` is a local `long`, this is unnecessary string concatenation in SQL and could be replaced with a `?` parameter and `ps.setString()` for the interval expression, or better, built with `PreparedStatement` and `setObject` using `Duration`.
- **[low] MAINTAINABILITY-RULES-DRIFT** — `DigestWorker.java:120` has a defensive null check `if (adapter == null)` after `findAdapter()`. `findAdapter()` returns null only when the adapter name is not in the registry. This is a legitimate system-boundary outcome (group row references an adapter that was deactivated), so the null check is arguably acceptable, but the method signature of `findAdapter` does not declare `@Nullable`, making the contract unclear.
- **[low] SIMPLIFICATION** — `AdapterRegistry` carries a `GROUP_SPI_WIRED = false` constant with a comment saying "MVP has no group SPI wired; gate 4 is vacuously satisfied." This is dead-branch code that will require a future refactor; it could be a feature-flag-style property instead of a compile-time constant, but the spec forbids feature flags (§7). The constant is acceptable but noted as simplification debt.
- **[medium] PERFORMANCE** — `ProductionAdapterBeans.java:138` and `NostrStreamSource.Registrar.java:282` create `HttpClient.newHttpClient()` with no connect timeout, no request timeout, and no redirect policy configured. A hung or malicious endpoint can stall the calling thread indefinitely. The `NostrRelayConnection` does pass a `connectTimeout` to `buildAsync`, but the `HttpClient` itself has no default timeout, so other adapter operations (SimpleX HTTP calls) are unbounded.
- **[low] MAINTAINABILITY-RULES-DRIFT** — `DigestScheduler.parseTimezone(String)` catches `Exception e` and returns `null`. `ZoneId.of()` throws `DateTimeException`, not generic `Exception`. The catch is overly broad and would also swallow programming errors.
- **[low] MAINTAINABILITY-RULES-DRIFT** — `InboundContext.adapterName()` and `InboundContext.senderContactId()` return `String` with no nullability annotation, but the javadoc explicitly states they can return `null` when invoked outside an inbound dispatch. The return types should be `@Nullable`.
- **[low] SECURITY** — `NewPostListener.parsePayload()` and `QuarantineReviewListener.parsePayload()` use regex matchers on raw JSON strings rather than a JSON parser. This is defensively correct (avoids parser bombs), but the regex patterns (`READY_AT_PATTERN`, `POST_ID_PATTERN`, etc.) are not anchored and could match against attacker-controlled substrings inside a larger malicious payload. The patterns should be anchored to the start of the string or validated with a JSON structure check. Currently a payload like `{"extra":"123e4567-e89b-12d3-a456-426614174000","post_id":"..."}` could match the UUID pattern against the `extra` field if it happens to contain a UUID-shaped string.

---

## Detail

### F1. Duplicate dependency in infochat-provider pom

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-provider/pom.xml`

**Current code:**

```xml
  <dependency>
      <groupId>app.zcat.infochat</groupId>
      <artifactId>infochat-messaging-adapter</artifactId>
  </dependency>
  <!-- ... other deps ... -->
  <dependency>
      <groupId>app.zcat.infochat</groupId>
      <artifactId>infochat-messaging-adapter</artifactId>
  </dependency>
```

**Why this is wrong:** Duplicate dependency declarations are a maintenance hazard. They suggest the POM was edited by copy-paste without review, and they create confusion about whether the two declarations might diverge in the future (e.g. one gets an exclusion and the other doesn't). Maven ignores the duplicate, but the signal to future maintainers is bad.

**Recommended fix:** Remove the duplicate `<dependency>` block.

**Trade-offs:** None — strictly better.

---

### F2. Missing JSpecify nullability annotations on public methods

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** Cross-cutting — infochat-provider and infochat-collector main source

**Current code (representative samples):**

```java
// InboundContext.java:48
public String adapterName() {
    return adapterName;
}

// ProviderStateDao.java:53
public Optional<Cursor> readCursor(String channel) throws SQLException {

// HelpCommandHandler.java:47
public OutboundMessage handle(ScopeRef scope, String rawText) {

// ClusterTraversal.java:78
public List<Cluster> cluster(List<Post> posts) {

// EligiblePostQuery.java:118
public Result fetch(String scopeKind, UUID scopeId,
                    Instant notBefore, Instant notAfter,
                    Set<String> tags, String tagMode,
                    int limit) {
```

**Why this is wrong:** Engineering rules §7a require every reference-type parameter on a public method to declare nullability via `@NonNull`/`@Nullable` or javadoc `@param`. The codebase has JSpecify on the classpath (`org.jspecify:jspecify:1.0.0` in parent POM), and some methods already use it (e.g. `PromoteCommandHandler.handle(@NonNull ScopeRef scope, @NonNull String rawText)`), but the majority do not. The `scripts/lint-contracts.py` baseline is empty, meaning no grandfathering is in effect, yet the linter is not being run in CI or is being ignored.

**Recommended fix:** Run `scripts/lint-contracts.py`, annotate every public method it flags, and add the script to CI so regressions are blocked.

**Trade-offs:** Mechanical effort; no behavior change.

---

### F3. SQL string concatenation in AuditCommandHandler

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java:155-156` and `:180`

**Current code:**

```java
StringBuilder where = new StringBuilder();
// ... where.append(" WHERE actor_user_id = ?") ...

long totalCount;
try (PreparedStatement ps = conn.prepareStatement(
        "SELECT count(*) FROM audit_log_view" + where)) {
```

**Why this is wrong:** While `where` is currently built with `?` placeholders, the String concatenation into the SQL template is a latent injection risk. A future refactor that appends a raw user value (e.g. `where.append(" AND action = '" + args.action + "'")`) would bypass the prepared-statement defense. The pattern itself is a code-smell that security reviewers flag.

**Recommended fix:** Use a query builder or at minimum keep the static template and append clause fragments that are themselves compile-time constants:

```java
String sql = "SELECT count(*) FROM audit_log_view" + where.toString();
```

Better: use jOOQ or a small internal builder that prevents literal insertion.

**Trade-offs:** Slight verbosity; safety improvement.

---

### F4. InstanceLockGuard duplicated across Collector and Provider

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/.../InstanceLockGuard.java` and `infochat-provider/src/main/java/.../InstanceLockGuard.java`

**Current code:** The two files are ~200 lines each and differ only in the `SERVICE` constant (`"collector"` vs `"provider"`) and the `LOCK_KEY_HASH_INPUT` derived from it.

**Why this is wrong:** DRY violation. A bug fix to the lock-acquisition logic, heartbeat upsert, or shutdown path must be applied in two places. The spec says the two services share a single database and use the same advisory-lock mechanism; the implementation should share the code.

**Recommended fix:** Move `InstanceLockGuard` to `infochat-core` as a generic class parameterized by the service name, or extract a shared base class. Both modules already depend on `infochat-core`.

**Trade-offs:** Requires moving the class and updating imports; no runtime change.

---

### F5. Overly broad catch (Exception ignored) in AnthropicProvider

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:201`

**Current code:**

```java
private static String extractErrorMessage(String body) {
    try {
        JsonNode root = JSON.readTree(body);
        if ("error".equals(root.path("type").asText())) {
            return root.path("error").path("message").asText("(no message)");
        }
    } catch (Exception ignored) {
        // Fall through to preview
    }
    return preview(body);
}
```

**Why this is wrong:** Catching `Exception` swallows `OutOfMemoryError`, `StackOverflowError`, and any unexpected runtime exception. This is a diagnostic method; if the JSON parser fails catastrophically, the error should propagate so the operator sees it. The legitimate failures are `IOException` from `readTree`.

**Recommended fix:**

```java
catch (IOException ignored) {
    // Fall through to preview
}
```

**Trade-offs:** None — strictly better.

---

### F6. SQL interval built via string concatenation in QuarantineReviewListener

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:82-94`

**Current code:**

```java
private String getUpsertSql() {
    String sql = upsertSql;
    if (sql == null) {
        long ms = throttleWindow.toMillis();
        String interval = "INTERVAL '" + ms + " milliseconds'";
        sql = "INSERT INTO admin_notification_state "
                + "(notification_key, error_class, last_notified_at, notification_count, "
                + "suppressed_count, first_seen_at) "
                + "VALUES (?, ?, " + interval + ", 1, 0, " + interval + ") "
                ...
```

**Why this is wrong:** String concatenation into SQL, even for a local `long`, is unnecessary. Postgres supports interval parameters via `?::interval` or `setObject` with `PGInterval`. The current code creates a new SQL string on every call until cached.

**Recommended fix:** Use a `PreparedStatement` with `?` placeholders for the interval values and bind them as strings or use `PSQLState` interval syntax via `setString`.

---

### F7. NOTIFY payload regexes are not anchored

- **Category:** SECURITY
- **Severity:** low
- **Location:** `NewPostListener.java:311` and `QuarantineReviewListener.java:270`

**Current code (NewPostListener):**

```java
static Payload parsePayload(String json) {
    Matcher readyAtMatcher = READY_AT_PATTERN.matcher(json);
    Matcher postIdMatcher = POST_ID_PATTERN.matcher(json);
    if (!readyAtMatcher.find() || !postIdMatcher.find()) {
        throw new IllegalArgumentException(...);
    }
    return new Payload(
        UUID.fromString(postIdMatcher.group(1)),
        Instant.parse(readyAtMatcher.group(1)));
}
```

**Why this is wrong:** The regex patterns use `find()` rather than `matches()` or anchored patterns. A malicious payload with extra fields before the real ones could cause the regex to match against an attacker-controlled substring. While the payload comes from a trusted Postgres NOTIFY channel, defense-in-depth demands stricter parsing. The spec says NOTIFY is a system boundary.

**Recommended fix:** Anchor the patterns to require the field to appear as a top-level JSON key, or use a strict JSON parser with a size limit.

---

### F8. DigestWorker broad catch (Exception e)

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `DigestWorker.java:69-74`

**Current code:**

```java
public void execute(@Observes @NonNull DigestSlot slot) {
    try {
        executeSlot(slot);
    } catch (Exception e) {
        LOG.errorf(e, "Digest failed for group %s slot %s", slot.groupId(), slot.slotKind());
    }
}
```

**Why this is wrong:** Catching `Exception` at a high-level event observer suppresses all failures including programming errors (`NullPointerException`, `IllegalStateException`). The spec's failure handling says digest failures should degrade, but this catch is so broad it also hides bugs. It should catch `SQLException` and `MessagingException` specifically, plus maybe `RuntimeException` for the degraded path, but not raw `Exception`.

**Recommended fix:**

```java
catch (SQLException | MessagingException e) {
    LOG.errorf(e, "Digest failed ...");
}
```

---

### F9. HttpClient instances lack default timeouts

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `ProductionAdapterBeans.java:138` and `NostrStreamSource.Registrar.java:282`

**Current code:**

```java
// ProductionAdapterBeans.java:138
HttpClient httpClient = HttpClient.newHttpClient();

// NostrStreamSource.Registrar.java:282
private final HttpClient httpClient = HttpClient.newHttpClient();
```

**Why this is wrong:** `HttpClient.newHttpClient()` creates a client with no default connect timeout, no request timeout, and no redirect policy. For SimpleX, this means a hung or malicious WebSocket endpoint can stall the adapter indefinitely. For Nostr, while `NostrRelayConnection` passes a per-build `connectTimeout`, other operations using the same client (if any) are unbounded. The spec's SSRF section says "an unset timeout is a configuration error."

**Recommended fix:** Configure the client with explicit timeouts:

```java
HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build();
```

**Trade-offs:** None — strictly better.

---

### F10. DigestScheduler.parseTimezone swallows Exception

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `DigestScheduler.java:189-195`

**Current code:**

```java
private static ZoneId parseTimezone(String timezone) {
    try {
        return ZoneId.of(timezone);
    } catch (Exception e) {
        return null;
    }
}
```

**Why this is wrong:** `ZoneId.of()` throws `DateTimeException` for invalid inputs. Catching `Exception` is overly broad and would also swallow programming errors or unexpected runtime exceptions inside the call. The method should catch `DateTimeException` specifically.

**Recommended fix:**

```java
catch (DateTimeException e) {
    return null;
}
```

**Trade-offs:** None — strictly better.

---

### F11. InboundContext return types lack @Nullable

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `InboundContext.java:48` and `:65`

**Current code:**

```java
public String adapterName() {
    return adapterName;
}

public String senderContactId() {
    return senderContactId;
}
```

**Why this is wrong:** The javadoc explicitly states these methods "Return null only if a caller invokes a handler outside an inbound dispatch." Without `@Nullable` on the return type, callers cannot see this contract statically. JSpecify supports return-type annotations (`public @Nullable String adapterName()`), and the project already uses JSpecify elsewhere.

**Recommended fix:** Annotate both return types with `@Nullable`.

**Trade-offs:** None — strictly better.

---

## Summary

| Category | Count |
|---|---|
| SECURITY | 3 |
| PERFORMANCE | 1 |
| SIMPLIFICATION | 1 |
| MAINTAINABILITY-RULES-DRIFT | 6 |

**By severity:**
- critical: 0
- high: 2
- medium: 5
- low: 4

## Synthesizer-relevant observations

- The architecture inventory for SPI files under `*/src/main/java/**/spi/*.java` is empty. The project does have SPI interfaces (`LlmProvider`, `EmbeddingProvider`, `MessagingAdapter`, `Fetcher`, `StreamSource`), but they are not in `spi/` packages. This is a naming drift from the spec's canonical directory convention.
- The `scripts/lint-contracts.py` baseline is empty, suggesting the project intended to enforce JSpecify annotations but has not done a retroactive pass. A ticket for this retroactive pass would be mechanical and high-value.
- No `@MockBean` usage was found in integration tests, and no H2/in-memory DB substitution was found — the test-integrity rules around these are being honored.
- No `@Disabled` or `@Ignore` tests were found (only `@DisabledOnOs(OS.WINDOWS)` which is acceptable).
