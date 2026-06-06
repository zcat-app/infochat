# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-01 20:57
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [HIGH] MAINTAINABILITY-RULES-DRIFT — QuarantineReviewListener.java:96 — SQL constructed via string concatenation in `getUpsertSql()`
- [HIGH] MAINTAINABILITY-RULES-DRIFT — QuarantineReviewListener.java:82-101 — Unsynchronized field read/write of `upsertSql` (no memory barrier)
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — Multiple files — Defensive null-checks on CDI-injected fields violate engineering rule §7
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — ChatToolDispatcher.java:69-75 — CDI constructor does not validate that all registry tools have implementations
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — DigestScheduler.java:130-158 — Transaction atomicity gap in `recordMissedSlot`
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — GrantAdminCommandHandler.java:196 — `SET LOCAL` SQL constructed via string concatenation
- [LOW] MAINTAINABILITY-RULES-DRIFT — InboundRouter.java:601 — `UserSnapshot.isBanned` field is dead code (declared, never read)
- [LOW] MAINTAINABILITY-RULES-DRIFT — LlmOutputSanitizer.java:191 — Non-standard whitespace can bypass multi-word closed-list token matching

## Detail

### F1. QuarantineReviewListener: SQL constructed via string concatenation in getUpsertSql

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** HIGH
- **Location:** QuarantineReviewListener.java:86-97

**Current code:**

```java
private String getUpsertSql() {
    String sql = upsertSql;                              // unsynchronized read
    if (sql == null) {
        long ms = throttleWindow.toMillis();
        String interval = "INTERVAL '" + ms + " milliseconds'";
        sql = "INSERT INTO admin_notification_state "
                + "(notification_key, error_class, last_notified_at, notification_count, "
                + "suppressed_count, first_seen_at) "
                + "VALUES (?, ?, ?, 1, 0, ?) "
                + "ON CONFLICT (notification_key) DO UPDATE SET "
                + "last_notified_at = EXCLUDED.last_notified_at, "
                + "notification_count = admin_notification_state.notification_count + 1, "
                + "error_class = EXCLUDED.error_class "
                + "WHERE admin_notification_state.last_notified_at + " + interval   // line 96
                + " <= EXCLUDED.last_notified_at "
                + "RETURNING notification_key";
        upsertSql = sql;                                 // unsynchronized write
    }
    return sql;
}
```

**Why this is wrong / suboptimal / risky:**

Two distinct problems in one method:

**Problem 1 — SQL construction via string concatenation (line 96).** The `interval` string is spliced directly into the SQL text via `+ " " + interval + " " +`. While `ms` is a numeric `long` from config parsing (not directly user-controllable), the pattern violates the principle that SQL text should never be assembled from concatenated fragments. A future refactoring that moves the throttle-window source to a user-configurable value would unknowingly introduce an injection vector, because the maintainer would not expect concatenation in a SQL string that otherwise uses `?` bind parameters. The rest of the query correctly uses bind parameters; this one fragment breaks the pattern.

**Problem 2 — Unsynchronized field read/write of `upsertSql` (lines 83, 99).** The `upsertSql` field is a plain `String` accessed without `volatile`, `synchronized`, or `AtomicReference`. The read at line 83 and the write at line 99 are unsynchronized. If this method is ever called from more than one thread (e.g., a future code path that dispatches quarantine notifications from a different executor), one thread could see a stale `null` (or a partially-constructed object due to JVM reordering) and either recompute the SQL needlessly or, in pathological cases, produce a corrupted string via the JMM's lack of happens-before guarantees.

**Recommended fix:**

Replace string concatenation with a bind parameter, and protect the field with an `AtomicReference`:

```java
private static final String UPSERT_SQL_TEMPLATE =
        "INSERT INTO admin_notification_state "
                + "(notification_key, error_class, last_notified_at, notification_count, "
                + "suppressed_count, first_seen_at) "
                + "VALUES (?, ?, ?, 1, 0, ?) "
                + "ON CONFLICT (notification_key) DO UPDATE SET "
                + "last_notified_at = EXCLUDED.last_notified_at, "
                + "notification_count = admin_notification_state.notification_count + 1, "
                + "error_class = EXCLUDED.error_class "
                + "WHERE admin_notification_state.last_notified_at + ?::interval "
                + "<= EXCLUDED.last_notified_at "
                + "RETURNING notification_key";

private final AtomicReference<String> upsertSql = new AtomicReference<>();

private String getUpsertSql() {
    String sql = upsertSql.get();
    if (sql == null) {
        // Postgres accepts a milliseconds-as-decimal interval string.
        // Construct once per JVM lifetime; the throttle-window is fixed
        // at config-read time, so this is safe to cache.
        String interval = throttleWindow.toMillis() + " milliseconds";
        sql = UPSERT_SQL_TEMPLATE;  // no concatenation — interval goes as bind param
        upsertSql.set(sql);
    }
    return sql;
}
```

Then update the `PreparedStatement` call to set the interval as a bind parameter.

**Reasoning:**

Moving the interval value to a bind parameter eliminates the concatenation pattern entirely — a future maintainer adding a new bind parameter sees only `?` placeholders and cannot accidentally inject SQL into this method. The `AtomicReference` provides both thread-safe lazy initialization and a clear memory barrier without reducing performance (the field is written once and read many times).

**Trade-offs:**

The SQL now has one more bind parameter, and the calling code must set it. This is a minor mechanical change. No downsides versus the current code.

---

### F2. Multiple files: Defensive null-checks on CDI-injected fields violate engineering rule §7

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** cross-cutting (see each file below)

**Current code:**

Four production classes contain null-guards for `@Inject`-ed CDI beans that the container always provides. These exist exclusively because test code constructs instances without CDI.

**HelpCommandHandler.java:60-61**
```java
List<AssetRegistry.AssetEntry> enabledAssets = assetRegistry != null
        ? assetRegistry.getEnabledAssets() : List.of();
```

**InboundRouter.java:417, 446**
```java
if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()
        && groupApprovalCheck != null) {    // line 417

if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()
        && groupAutoPromoteService != null) {   // line 446
```

**LlmOutputSanitizer.java:229**
```java
private void emitAuditRows(List<String> matches) {
    if (matches.isEmpty() || auditLogWriter == null || dataSource == null) {
        return;
    }
```

**AssetCommandFamilyOracle.java:44**
```java
return assetRegistry != null && assetRegistry.containsEnabledAsset(slashCommand);
```

**Why this is wrong / suboptimal / risky:**

Engineering rule §7 ("No defensive code for impossible scenarios") states: "Don't add error handling, fallbacks, or validation for scenarios that cannot happen given the trust boundary the code lives in. ... Inside [the trust] boundaries, internal code calling internal code is trusted."

These are `@Inject` fields on `@ApplicationScoped` CDI beans. In production, the CDI container always resolves them. The null-checks are defensive only against test code that constructs instances via `new` without wiring CDI. The rule explicitly prohibits such checks: validation belongs at system boundaries, not between two internal classes.

The test code that motivated these checks should be fixed instead — either by wiring the injected fields or by using a test-specific CDI configuration. Adding null branches to production code to accommodate test shortcuts normalizes a pattern that should not exist.

**Recommended fix:**

Remove each null-guard and let the CDI-injected field be unconditionally dereferenced. Fix the affected test code to wire the dependency.

For `HelpCommandHandler`:
```java
List<AssetRegistry.AssetEntry> enabledAssets = assetRegistry.getEnabledAssets();
```

For `InboundRouter`:
```java
if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()) {
    GroupApprovalCheck.Outcome outcome = groupApprovalCheck.check(...);
```
(and similarly for `groupAutoPromoteService`)

For `LlmOutputSanitizer`:
```java
private void emitAuditRows(List<String> matches) {
    if (matches.isEmpty()) {
        return;
    }
```

For `AssetCommandFamilyOracle`:
```java
return assetRegistry.containsEnabledAsset(slashCommand);
```

**Reasoning:**

The production contract is: CDI resolves every `@Inject` point. A null-dereference in production would correctly reveal a configuration error (e.g., a missing CDI bean) rather than silently degrading behavior. Removing the defensive checks makes the code simpler and more honest about its assumptions.

**Trade-offs:**

The affected tests (`HelpCommandHandlerTest`, `InboundRouter*Test`, `LlmOutputSanitizerTest`, `AssetCommandFamilyOracleTest`) must be updated to provide the injected dependency rather than relying on the null path. This work is scoped to the test files only. The production code becomes strictly simpler.

---

### F3. ChatToolDispatcher: CDI constructor does not validate registry-tool completeness

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** ChatToolDispatcher.java:69-75

**Current code:**

```java
@Inject
public ChatToolDispatcher(@NonNull ChatToolRegistry registry, ...) {
    ...
    this.tools = Map.of(
            "searchPosts", searchPostsTool,
            "getPost", getPostTool,
            "getReferences", getReferencesTool,
            "recallMemory", recallMemoryTool,
            "listSaves", listSavesTool
    );
}
```

Compare with the package-private constructor (used only by tests):

```java
ChatToolDispatcher(@NonNull ChatToolRegistry registry,
                   @NonNull Map<String, ChatToolRegistry.ChatTool> tools, ...) {
    ...
    for (String name : registry.toolNames()) {
        if (!this.tools.containsKey(name)) {
            throw new IllegalStateException("Missing tool implementation: " + name);
        }
    }
}
```

**Why this is wrong / suboptimal / risky:**

The CDI constructor builds `tools = Map.of(...)` entirely by convention — the developer must remember to add the entry here when adding a new tool to `ChatToolRegistry`. If a tool is added to `ChatToolRegistry.TOOL_NAMES` but omitted from this `Map.of(...)`, the `dispatch()` method at line 136 calls `tools.get(toolName)` which returns `null`, and line 138 throws a `NullPointerException` at runtime.

The test-only constructor contains a validation loop (lines 87-91) that catches exactly this mismatch at construction time; the CDI constructor skips it entirely. Validation that exists only in test code is not validation.

**Recommended fix:**

Add the same registry-completeness check to the CDI constructor:

```java
@Inject
public ChatToolDispatcher(@NonNull ChatToolRegistry registry,
                           @NonNull SearchPostsTool searchPostsTool,
                           @NonNull GetPostTool getPostTool,
                           @NonNull GetReferencesTool getReferencesTool,
                           @NonNull RecallMemoryTool recallMemoryTool,
                           @NonNull ListSavesTool listSavesTool,
                           @ConfigProperty(name = "infochat.chat.tool.input-max-length",
                                   defaultValue = "500") int inputMaxLength,
                           @ConfigProperty(name = "infochat.chat.tool.limit-cap",
                                   defaultValue = "200") int limitCap,
                           @ConfigProperty(name = "infochat.chat.tool.list-max-size",
                                   defaultValue = "20") int listMaxSize) {
    this.registry = registry;
    this.inputMaxLength = inputMaxLength;
    this.limitCap = limitCap;
    this.listMaxSize = listMaxSize;
    this.tools = Map.of(
            "searchPosts", searchPostsTool,
            "getPost", getPostTool,
            "getReferences", getReferencesTool,
            "recallMemory", recallMemoryTool,
            "listSaves", listSavesTool
    );
    // Validate that every registry-listed tool has an implementation.
    // Without this check, a registry addition without a dispatcher entry
    // would produce a confusing NullPointerException at runtime.
    for (String name : registry.toolNames()) {
        if (!this.tools.containsKey(name)) {
            throw new IllegalStateException("Missing tool implementation: " + name);
        }
    }
}
```

**Reasoning:**

The validation loop makes the CDI path fail fast with a clear message instead of producing a confusing NPE at the first tool invocation. It also keeps the two constructors (CDI and test) symmetric, reducing the surprise for future readers.

**Trade-offs:**

None — the check is a one-time O(n) overhead at startup, and the loop already exists in the test constructor.

---

### F4. DigestScheduler: transaction atomicity gap in recordMissedSlot

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** DigestScheduler.java:130-158

**Current code:**

```java
private void recordMissedSlot(UUID groupId, String slotKind,
                              Instant windowStart, Instant windowEnd) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        try {
            auditLogWriter.write(conn, ...);
            conn.commit();                           // Transaction T1 commits here
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }
    // Outside T1 — separate connection, separate transaction:
    summaryCacheRepository.insert(groupId, slotKind, windowStart,
            0L, 0L, "", true, windowEnd);          // Transaction T2
    throttledAdminNotifier.notifyOnce(...);          // Out-of-tx side effect
}
```

**Why this is wrong / suboptimal / risky:**

The audit write (T1) commits before the sentinel cache insert and the admin notification run. These three operations should be atomic: if the cache insert or the admin notification fails after T1 commits, the audit row persists without a corresponding sentinel. On the next scheduler tick, `tickAt` will find no cache row, re-detect the same missed slot, and write another audit row — duplicating the log trail for the same absence.

The spec's Invariant 7 (audit-before-effect) is satisfied here (the audit row correctly precedes any user-visible effect), but the sentinel-insert is part of the "effect": without it, the system is not idempotent, and the operator sees redundant audit rows.

**Recommended fix:**

Move the cache-insert and admin-notification into the same transaction as the audit write:

```java
private void recordMissedSlot(UUID groupId, String slotKind,
                              Instant windowStart, Instant windowEnd) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        try {
            auditLogWriter.write(conn, ...);
            // Insert sentinel inside the same transaction
            insertMissedSentinel(conn, groupId, slotKind, windowStart, windowEnd);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }
    // Throttled admin notification is best-effort; it runs outside the
    // transaction because notification_state is SERIALIZABLE-safe by key.
    throttledAdminNotifier.notifyOnce(...);
}
```

Where `insertMissedSentinel` runs the same SQL as `summaryCacheRepository.insert` but on the passed connection.

**Reasoning:**

Moving the sentinel insert into T1 ensures that every audit row has a matching sentinel (or the entire operation rolls back). The admin notification stays outside the transaction because it is best-effort and independent of the cursor-invariant guarantee.

**Trade-offs:**

The method now needs direct access to the sentinel INSERT SQL instead of delegating to `summaryCacheRepository.insert()`. This is either a minor duplication or requires adding a package-private `insert(Connection, ...)` overload to `SummaryCacheRepository`. The duplication is acceptable (4 lines) versus the cost of the gap.

---

### F5. GrantAdminCommandHandler: SQL constructed via string concatenation

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** GrantAdminCommandHandler.java:195-197

**Current code:**

```java
try (Statement st = conn.createStatement()) {
    st.execute("SET LOCAL infochat.actor_id = '" + actor.id + "'");
}
```

**Why this is wrong / suboptimal / risky:**

SQL text is assembled by concatenating a UUID value into a string. While a `java.util.UUID` can never contain a SQL-special character (its string form is hex digits and dashes), the pattern is indistinguishable from an injection sink. A future maintainer extending this pattern with a user-supplied string would not see the difference, because the code looks like every SQL-injection antipattern.

Additionally, using `Statement` instead of `PreparedStatement` for the `SET LOCAL` call means the database cannot cache the execution plan. For a one-shot statement at handler runtime this is negligible, but the pattern normalizes `Statement` usage in a codebase that otherwise correctly uses `PreparedStatement` everywhere.

**Recommended fix:**

Use a `PreparedStatement` with a bind parameter:

```java
try (PreparedStatement ps = conn.prepareStatement(
        "SET LOCAL infochat.actor_id = ?")) {
    ps.setObject(1, actor.id);
    ps.execute();
}
```

**Reasoning:**

This changes the code from the injection-unsafe pattern (`Statement` + concatenation) to the standard `PreparedStatement` + bind-parameter pattern used everywhere else in the codebase. A future reader copying this snippet will copy the safe version.

**Trade-offs:**

None — the behavior is identical.

---

### F6. InboundRouter: UserSnapshot.isBanned field is dead code

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** LOW
- **Location:** InboundRouter.java:601

**Current code:**

```java
record UserSnapshot(UUID id, boolean isBanned, String registrationState) {}
```

The `isBanned` field is declared and populated in the `lookupUser` method (line 582: `rs.getBoolean("is_banned")`), but it is never read anywhere in production code. Steps 3 and 5 read `id` and `registrationState` from the snapshot; step 4 consults `BanCheck.isBanned` directly (by design, per spec §Authorization model).

**Why this is wrong / suboptimal / risky:**

The column is fetched from the database and stored in the record but never used. This is dead data: it wastes a result-set column read (negligible cost) and, more importantly, creates confusion for future readers who will wonder why `isBanned` is in the snapshot if it is never consulted. The javadoc comment on `UserSnapshot` says "TOCTOU-paired with BanCheck.isBanned at step 4" but since the field is never read, it is not paired with anything.

**Recommended fix:**

Remove the `isBanned` field from `UserSnapshot` and from the `lookupUser` SELECT and ResultSet read:

```java
private static final String USER_SNAPSHOT_SQL =
        "SELECT id, registration_state FROM users "
                + "WHERE adapter = ? AND contact_id = ?";
```

And:

```java
record UserSnapshot(UUID id, String registrationState) {}
```

With the corresponding adjustment in `lookupUser`:

```java
return Optional.of(new UserSnapshot(
        rs.getObject("id", UUID.class),
        rs.getString("registration_state")));
```

**Reasoning:**

Dead code invites "why is this here?" questions and adds to cognitive load when reading the intake dispatch. Removing it makes the snapshot's contract clear: it contains only the columns the router actually uses.

**Trade-offs:**

If a future ticket needs `isBanned` at the snapshot level (e.g., if BanCheck integration is moved from a separate query to the snapshot), the field must be re-added. The cost of that re-addition is one line, versus the ongoing confusion cost of keeping dead code.

---

### F7. LlmOutputSanitizer: non-standard whitespace can bypass multi-word closed-list tokens

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** LOW
- **Location:** LlmOutputSanitizer.java:191

**Current code:**

```java
for (String token : CLOSED_LIST) {
    Pattern p = Pattern.compile(Pattern.quote(token) + "(?=$|[^a-zA-Z0-9\\-])");
    Matcher m = p.matcher(current);
```

The `token` entries include multi-word strings such as `"/invite create"`, `"/list-sources --all"`. `Pattern.quote` produces a literal pattern for the exact byte sequence, including the single ASCII space between words.

**Why this is wrong / suboptimal / risky:**

If the LLM produces `/invite  create` (with two spaces) or `/invite\tcreate` (tab), the regex `\/invite\ create(?=$|[^a-zA-Z0-9\\-])` requires exactly one space and will not match. The multi-word command passes through the sanitizer unredacted. While the LLM cannot actually execute these commands (they are not in the tool set), the sanitizer is the last line of defense against admin-command tokens appearing in user-visible output. A bypass here means a prompt-injection that tricks the LLM into emitting `/invite  create` (non-standard whitespace) sends the command to the user unredacted.

**Recommended fix:**

Collapse whitespace in the input before the closed-list pass, or make the regex accept variable whitespace between words. The cleanest approach is to normalize whitespace in the input before matching:

```java
// In sanitize(), before the closed-list pass:
String afterMarkdown = applyMarkdownLinkStrip(llmOutput);
// Collapse runs of whitespace for closed-list matching so multi-word
// tokens match regardless of spacing.
String wsCollapsed = afterMarkdown.replaceAll("[\\s\\p{Z}]+", " ");
ClosedListStripResult result = applyClosedListStripWithMatches(wsCollapsed);
```

**Reasoning:**

Collapsing whitespace before the closed-list pass is a simple, one-line addition that makes the sanitizer robust against non-standard whitespace in multi-word tokens. The user-facing output is already plain text (no significant whitespace in command tokens), so collapsing does not alter semantics.

**Trade-offs:**

The collapse could join words that were separated by a newline inside the LLM output, potentially creating false-positive matches. For example, `"/invite\ncreate"` becomes `"/invite create"` which would then be redacted. This is a false positive in the direction of safety (redacts more text) and is acceptable. If false positives become a real problem, the collapse can be scoped to the closed-list matching only and leave the original text unchanged.
