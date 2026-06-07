# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-06
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — chat/tool/SearchPostsTool.java + chat/CancellationService.java + chat/InFlightTracker.java:36 — the `/stop` cancellation safety net (statement_timeout + `pg_cancel_backend`) is not wired into the read-only chat-tool or on-demand `/summary` query paths, so the spec's worst-case bound is absent there.
- [medium] MAINTAINABILITY-RULES-DRIFT — digest/DigestScheduler.java:124-128 — a freshly-approved group emits spurious `DIGEST_SLOT_MISSED` audit rows and admin notifications for slot windows that elapsed before the group existed/was approved, contradicting the skip-not-catch-up commitment.
- [medium] PERFORMANCE — chat/tool/SearchPostsTool.java:56-66 — one `searchPosts` tool invocation acquires up to four separate pooled connections (one per helper query) on the chat hot path.
- [low] MAINTAINABILITY-RULES-DRIFT — command/ExportDataCollector.java:191-198 — truncation is flagged with `rows.size() >= maxRowsPerTable` against a query that is `LIMIT maxRowsPerTable`, so a table with exactly the cap is falsely reported truncated.

## Detail

### F1. `/stop` worst-case bound (statement_timeout + pg_cancel_backend) not applied to chat-tool or `/summary` queries

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java:36`; `chat/CancellationService.java:46-69,77-81`; `chat/tool/SearchPostsTool.java`, `GetPostTool.java`, `GetReferencesTool.java`, `RecallMemoryTool.java`, `ListSavesTool.java`; `summary/EligiblePostQuery.java`

**Current code:**

`InFlightTracker.CancellationHandle` (the only place a backend PID could be armed):

```java
public void registerPgBackendPid(int pid) { pgBackendPid.set(pid); }
public int pgBackendPid() { return pgBackendPid.get(); }
public boolean hasPgBackendPid() { return pgBackendPid.get() > 0; }
```

`CancellationService.cancel` — `pg_cancel_backend` only fires when a PID was registered:

```java
handle.workerThread().interrupt();
// Best-effort pg_cancel_backend on any registered tool-call connection.
if (handle.hasPgBackendPid()) {
    cancelPgBackend(handle.pgBackendPid());
}
```

A representative tool query opens a raw pooled connection and runs `executeQuery` with no timeout set and no PID registration (`SearchPostsTool.queryPosts`):

```java
try (Connection conn = dataSource.getConnection();
     PreparedStatement ps = conn.prepareStatement(sql.toString())) {
    bindParams(ps, conn, params);
    try (ResultSet rs = ps.executeQuery()) { ... }
}
```

`applyStatementTimeout` is invoked in exactly one place in the whole module — `RetryCommandHandler.fetchReadyPosts:299`. A grep across `src/main` for `registerPgBackendPid` returns only its definition; it is never called.

**Why this is wrong / suboptimal / risky:**

`docs/spec/commands.md` §`/stop` makes two concrete commitments about cancelling an in-flight read-only request:

1. "the cancellation primitive is `pg_cancel_backend(pid)` at the released connection, best-effort because Postgres may complete the query before the cancel takes effect."
2. "As an additional safety net, every interruptible read-only query (chat-mode tool calls, on-demand `/summary`) runs under a profile-driven `statement_timeout` that bounds the worst case even when `pg_cancel_backend` fails."

Neither is honored for the two paths the spec names by exactly those words. `registerPgBackendPid` is dead code — no production caller ever records a backend PID — so `handle.hasPgBackendPid()` is permanently false and `pg_cancel_backend` is never issued for any tool query. And `applyStatementTimeout` is only called from `/retry`; the five chat tools and `EligiblePostQuery` (the `/summary` post-selection path) all run `executeQuery` on a pool connection with no `statement_timeout`. There is no global `statement_timeout` in `application.properties` to backstop them either.

The remaining cancellation mechanism is `workerThread().interrupt()`. A blocking pgjdbc `executeQuery` does not respond to thread interruption — the JDBC call keeps running until the query returns. So a `/stop` issued while a chat tool's SQL is executing does not stop the query; the worker thread and its DB connection stay busy until the query completes naturally, with no bound. `CancellationService.cancel` releases the in-flight slot (so the user can queue more work) which can actually compound the cost: the abandoned query keeps a pool connection (`max-size=16`) busy while the user starts a new request. This is the precise failure the spec's `statement_timeout` clause exists to prevent, and `/stop` is called out in the spec as "spec-load-bearing."

The presence of `infochat.stop.statement-timeout` with per-profile values, the dead `registerPgBackendPid`, and the `applyStatementTimeout` helper all show the mechanism was designed but only half-wired (only `/retry`).

**Recommended fix:**

Apply the timeout on every interruptible read-only DB path, and arm the backend PID so `pg_cancel_backend` can actually fire. Concretely, in each chat tool and in `EligiblePostQuery`, set the timeout immediately after acquiring the connection and register the backend PID on the active `CancellationHandle`:

```java
try (Connection conn = dataSource.getConnection()) {
    cancellationService.applyStatementTimeout(conn);
    // Arm pg_cancel_backend for this in-flight (user, scope).
    inFlightTracker.getCancellationHandle(userId, scopeKind, scopeId)
            .ifPresent(h -> registerBackendPid(conn, h));
    // ... prepareStatement / executeQuery on conn ...
}
```

where `registerBackendPid` runs `SELECT pg_backend_pid()` on `conn` and calls `handle.registerPgBackendPid(...)`. The cleanest structural fix is to funnel tool DB access through a small shared helper (the tools already share `SearchPostsTool.jsonStr`/`appendJsonArray`) so the timeout-and-arm step cannot be forgotten per tool — see F3, which wants single-connection tool execution anyway.

**Reasoning:**

Wiring `applyStatementTimeout` into the tool and `/summary` connections restores the spec's worst-case bound: even with no working `pg_cancel_backend`, a pathological query is killed by Postgres at the profile timeout. Registering the backend PID restores the named primary cancellation primitive so a `/stop` can actually abort an in-flight tool query promptly rather than waiting for the statement timeout. Both together make `/stop` behave as the spec describes instead of degrading to "interrupt a thread that ignores the interrupt."

**Trade-offs:**

A `SELECT pg_backend_pid()` adds one cheap round-trip per tool connection. If F3's single-connection refactor lands first, that cost is paid once per tool call rather than per sub-query. No behavioral downside otherwise.

---

### F2. Newly-approved groups emit spurious missed-slot audit rows and admin notifications

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java:103-139` (esp. 124-128)

**Current code:**

```java
boolean alreadyFired = summaryCacheRepository.existsByGroupAndSlot(
        group.id, slotKind, windowStart);
if (alreadyFired) {
    return;
}

if (!now.isBefore(windowEnd)) {
    // Past window-end with no cache row: missed slot
    recordMissedSlot(group.id, slotKind, windowStart, windowEnd);
    return;
}
```

`queryActiveGroups` selects every `approval_status = 'approved' AND removed_at IS NULL` group; the `GroupRow` carries only `id` and `timezone` — no creation or approval timestamp.

**Why this is wrong / suboptimal / risky:**

When a group is approved partway through the day, the next scheduler tick evaluates today's morning and evening slots for it. For any slot whose `windowEnd` has already passed (e.g. an evening approval evaluating the 08:00 morning slot), there is no `summary_cache` row, so `recordMissedSlot` fires: it writes a `DIGEST_SLOT_MISSED` audit row, a sentinel cache row, and a throttled admin notification ("Missed digest slot for group …").

`docs/spec/commands.md` §Periodic group digests states the skip-not-catch-up rule explicitly: "no catch-up digest is emitted when a group transitions to `'approved'` (same skip-not-catch-up rule as missed-slot behavior)." The missed-slot mechanism is defined for the case "When the Provider is **down** for the entire slot window of a group" and exists precisely so "sustained misses indicate a deployment problem the operator must see." Recording a miss for a slot that elapsed before the group was even approved conflates "Provider was down" with "group did not yet exist," producing false positives that erode the very operator signal the spec is trying to keep clean.

The blast radius is bounded (at most one row per past slot per newly-approved group, deduped by the sentinel and the per-date throttle key), so this is not critical — but it is a real semantic defect that fires on every group activation that happens after that day's first slot window.

**Recommended fix:**

Skip missed-slot recording for slots whose window ended before the group became eligible. Carry the approval/creation instant into `GroupRow` and gate the miss branch on it:

```java
if (!now.isBefore(windowEnd)) {
    if (windowEnd.isAfter(group.approvedAt())) {
        recordMissedSlot(group.id, slotKind, windowStart, windowEnd);
    } else {
        // Slot elapsed before this group was approved — skip, not a miss.
        // Still insert the sentinel so the next tick does not re-evaluate.
        summaryCacheRepository.insertSkipSentinel(group.id, slotKind, windowStart, windowEnd);
    }
    return;
}
```

`approvedAt` is available on the `groups` row (the approval transition is audit-logged and the row is mutated by `/approve-group`); if no dedicated column exists, `groups.created_at` is a safe lower bound for the same gate.

**Reasoning:**

Gating on the eligibility instant makes the missed-slot signal mean what the spec says it means — "the Provider was down for a slot this group was entitled to" — and removes the false alarms on activation. Still writing a sentinel preserves the existing "do not re-detect on the next tick" property.

**Trade-offs:**

Requires threading one extra column through `queryActiveGroups`/`GroupRow`. If a distinct skip-sentinel write is undesirable, the existing `recordMissedSlot` sentinel insert can be reused without the audit row + notification, but that needs a small split of that method.

---

### F3. `searchPosts` acquires up to four pooled connections per single tool call

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java:56-66, 69-78, 80-94, 116-130, 132-178`

**Current code:**

`execute` calls four helpers, each of which opens its own connection:

```java
for (String tag : tags) {
    if (!isKnownTag(tag)) { ... }          // getConnection() #1 (per tag!)
}
TagMode tagMode = readTagMode(scopeKind, scopeId);        // getConnection() #2
List<String> effectiveTags = computeEffectiveTags(...);   // getConnection() #3 (readScopeTags)
...
return queryPosts(scopeKind, scopeId, effectiveTags, cutoff, limit); // getConnection() #4
```

Each of `isKnownTag`, `readTagMode`, `readScopeTags`, `queryPosts` does its own `try (Connection conn = dataSource.getConnection(); ...)`.

**Why this is wrong / suboptimal / risky:**

A single `searchPosts` invocation acquires the pool connection three-to-four times (and `isKnownTag` re-acquires once **per requested tag**). This is on the chat hot path: the tool loop runs up to `MAX_TOOL_ITERATIONS = 10` LLM turns with a per-turn call cap of 25, and the Agroal pool is deliberately small (`quarkus.datasource.jdbc.max-size=16`, with two connections permanently pinned by `InstanceLockGuard` and `NewPostListener`). Repeated acquire/release churn under concurrent chat users adds latency and pool pressure that compounds exactly when the system is busy. The reads are also spread across separate transactions/snapshots, so the tag-mode and scope-tag reads are not guaranteed mutually consistent.

The module already demonstrates the correct single-connection pattern elsewhere: `SaveCommandHandler.executeSave` opens one connection and threads it through `lookupActorForUpdate`, `lookupReadyPost`, `isAlreadySaved`, `insertSavedPost`. `SearchPostsTool` should do the same.

**Recommended fix:**

Open one connection at the top of `execute` and pass it to every helper:

```java
try (Connection conn = dataSource.getConnection()) {
    cancellationService.applyStatementTimeout(conn);   // also fixes F1 here
    for (String tag : tags) {
        if (!isKnownTag(conn, tag)) throw new IllegalArgumentException("Unknown tag: " + tag);
    }
    TagMode tagMode = readTagMode(conn, scopeKind, scopeId);
    List<String> effectiveTags = computeEffectiveTags(conn, tags, tagMode, scopeKind, scopeId);
    Instant cutoff = Instant.now().minus(window);
    return queryPosts(conn, scopeKind, scopeId, effectiveTags, cutoff, limit);
}
```

Drop the per-method `getConnection()`; the helpers take `Connection conn` as their first parameter.

**Reasoning:**

One acquisition per tool call instead of three-or-more removes the churn and gives all sub-reads a single snapshot. It also creates the natural single place to apply the F1 `statement_timeout` and PID registration, so the two fixes reinforce each other.

**Trade-offs:**

None of substance — fewer allocations and round-trips, same result set. The tool holds one connection for slightly longer per call, but that is strictly less total pool occupancy than acquiring four.

---

### F4. Export truncation flag has an off-by-one false positive

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportDataCollector.java:188-199`

**Current code:**

```java
private void collectTable(LinkedHashMap<String, List<String>> tables,
                          List<String> truncated,
                          String tableName, List<String> rows) {
    if (rows.size() >= maxRowsPerTable) {
        truncated.add(tableName);
    }
    tables.put(tableName, rows);
}

private String withLimit(String sql) {
    return sql + " LIMIT " + maxRowsPerTable;
}
```

**Why this is wrong / suboptimal / risky:**

Every query is capped at `LIMIT maxRowsPerTable`, so `rows.size()` can never exceed `maxRowsPerTable`. The truncation test `rows.size() >= maxRowsPerTable` therefore fires both when the table was genuinely truncated **and** when it has exactly `maxRowsPerTable` rows with nothing cut. A user whose `audit_log_view` holds exactly the cap value is told in their `/export` that the export was truncated when it was complete — a small but real correctness defect in a privacy-facing command where "is my export complete?" is the question that matters.

**Recommended fix:**

Fetch one row beyond the cap to distinguish "exactly full" from "truncated", then trim:

```java
private String withLimit(String sql) {
    return sql + " LIMIT " + (maxRowsPerTable + 1);
}

private void collectTable(LinkedHashMap<String, List<String>> tables,
                          List<String> truncated,
                          String tableName, List<String> rows) {
    if (rows.size() > maxRowsPerTable) {
        truncated.add(tableName);
        rows = rows.subList(0, maxRowsPerTable);
    }
    tables.put(tableName, rows);
}
```

**Reasoning:**

The probe-one-extra-row idiom is the standard way to detect truncation accurately: `size() > cap` is true only when a real overflow row was returned, and the `subList` trims it back to the contract. A table with exactly `maxRowsPerTable` rows is now correctly reported complete.

**Trade-offs:**

One extra row fetched per table (negligible). `subList` returns a view; if the caller mutates the list later, copy it — current callers only read it.

---

## Synthesizer-relevant observations (cross-module — not scored here)

- `LlmOutputSanitizer.CLOSED_LIST` (provider) is hand-maintained to mirror `docs/spec/commands.md` §Closed list of privileged-tier commands, with a CI equality test (`LlmOutputSanitizerTest.matchSetEqualsSpecClosedList`). The architecture pass should confirm this match-set derivation stays consistent with the same closed list consumed by the probation classifier (`CommandPermissions.ALLOWED`) — two independent transcriptions of one spec list.
- The provider implements two independent rate-limit mechanisms: `RateCapBucket` (transport + per-group, `infochat-provider/.../messaging/RateCapBucket.java`) and an inline per-user sliding-window LLM cap inside `InboundRouter.tryAcquireLlmRateCap`. The spec's per-group LLM/command sub-buckets (`security.md` §Rate limiting, D47) appear only partially represented; the architecture lens should check whether the per-group LLM/command caps are realized anywhere.
