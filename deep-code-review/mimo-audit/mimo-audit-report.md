# Deep Code Audit Report — infochat

**Auditor:** Claude (mimo-v2.5-pro)
**Date:** 2026-06-02
**Scope:** Full codebase — all 7 Maven modules, all production Java source, Flyway migrations, configuration, and test quality
**Method:** Parallel multi-agent deep review (security, performance, architecture, data layer)

---

## Executive Summary

The infochat codebase is **well-engineered** overall. SQL is fully parameterized, the SSRF guard is comprehensive, LLM prompt injection is mitigated with fresh UUIDs and closed-set parsing, and the intake pipeline ordering matches the spec. The test suite is extensive (260 files, 68 integration tests).

However, **two critical issues** require immediate attention:

1. **No partitions exist for June 2026 or later.** The system will hard-fail on any new data insertion starting now (today is June 2, 2026).
2. **Zombie instance lock-holding.** The advisory lock heartbeat is written once at startup and never refreshed; a silent connection drop enables split-brain operation.

Additionally, **one high-severity bug** (Signal adapter half-death from unhandled handler exception) and **several medium-severity issues** (OOM from unbounded LLM response bodies, TOCTOU in SSRF body-read deadline, TOOLL_CALL leak to user) need resolution before production deployment.

---

## Findings Index

| # | Severity | Category | Component | Finding |
|---|----------|----------|-----------|---------|
| 1 | CRITICAL | Data | Flyway | No partitions for June 2026+ — all inserts will fail |
| 2 | CRITICAL | Concurrency | InstanceLockGuard | Zombie instance split-brain — heartbeat never refreshed |
| 3 | HIGH | Reliability | SignalAdapter | Handler exception kills JSON-RPC reader thread — adapter half-dead |
| 4 | HIGH | Security | ChatAgent | Multi-line TOOL_CALL leaks JSON arguments to user |
| 5 | MEDIUM | Security | LLM Providers | Unbounded `BodyHandlers.ofString()` can OOM |
| 6 | MEDIUM | Security | SsrfGuardedHttpClient | Body-read deadline TOCTOU overshoot by up to 30s |
| 7 | MEDIUM | Reliability | NewPostListener | NOTIFY loss during reconnect not recoverable without restart |
| 8 | MEDIUM | Reliability | SignalSubprocess | No hung-process detection — alive but unresponsive |
| 9 | MEDIUM | Data | V28 migration | Unbatched UPDATE on partitioned post table |
| 10 | MEDIUM | Security | InviteCodeConsumer | Brute-force counter is per-contact, not per-code |
| 11 | MEDIUM | Reliability | SimpleXWebSocketClient | `sendCommand` race with `close()` throws raw RuntimeException |
| 12 | MEDIUM | Reliability | LLM Providers | No 429/503/Retry-After handling |
| 13 | MEDIUM | Concurrency | DigestWorker | No concurrency guard for same-group duplicate processing |
| 14 | MEDIUM | Performance | Both services | No explicit connection pool sizing — relies on Agroal defaults |
| 15 | MEDIUM | Performance | InboundRouter | 5 sequential connection acquire/release cycles per inbound message |
| 16 | LOW | Reliability | DigestScheduler | Invalid/null timezone silently skipped — no log |
| 17 | LOW | Security | InboundRouter, Stage1Pipeline | Unicode bidi control coverage gap (U+061C, U+200E, U+200F) |
| 18 | LOW | Security | Redactor | Generic pattern bypassable with >5 separator chars |
| 19 | LOW | Correctness | ReadyPromoter | `@Transactional` + explicit `getConnection()` — fragile boundary |
| 20 | LOW | Security | BootstrapLoader | Path traversal — no canonicalization on config-supplied file path |
| 21 | LOW | Data | V22 migration | Missing CHECK constraint on `post.stage2_verdict` |
| 22 | LOW | Concurrency | TaggerWorker, EmbeddingWorker | `acquireUninterruptibly()` swallows interrupt |
| 23 | LOW | Security | AnthropicProvider | Error message leaks into exception without truncation |
| 24 | LOW | Correctness | GroupAutoPromoteService | Eligibility check outside transaction boundary |
| 25 | LOW | Security | LlmOutputSanitizer | Bypasses via Unicode obfuscation (no normalization on LLM output) |
| 26 | LOW | Architecture | Both services | InfochatProfile duplication persists despite infochat-core existing |
| 27 | LOW | Architecture | MessagingAdapter | SPI lacks `start()`/`stop()` — reflective dispatch with `catch(Throwable)` |
| 28 | LOW | Architecture | ProgressNotifier | SPI declared with zero production implementations |
| 29 | LOW | Architecture | Adapter configs | SignalConfig vs SimpleXConfig inconsistent lifecycle |
| 30 | LOW | Code quality | Collector | Five TODO(T1-D) comments remain in production code |
| 31 | LOW | Test quality | Provider tests | 6 test files exceed project's 3-inner-class guideline (up to 13) |
| 32 | LOW | Performance | GroupTimezoneCommandHandler | Levenshtein recomputes distances — O(N*M*logN) |
| 33 | LOW | Performance | FetchScheduler, DigestScheduler | Unbounded result sets (no LIMIT) |
| 34 | LOW | Performance | DigestScheduler | 2*N DB queries per tick (one per group per slot) |
| 35 | LOW | Performance | NostrDedupFilter | 10K entries per source × ~1MB each |
| 36 | LOW | Resilience | Entire codebase | No circuit breakers — only manual D42 failure-counter |

---

## CRITICAL Findings

### C1. No partitions exist for June 2026+ — all inserts will fail

**Files:**
- `infochat-core/src/main/resources/db/migration/V7__joins_post.sql` (lines 175-176)
- `infochat-core/src/main/resources/db/migration/V11__post_embedding.sql`
- `infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql`
- `infochat-core/src/main/resources/db/migration/V28__post_entity.sql`
- `infochat-core/src/main/resources/db/migration/V29__post_reference.sql`

**Code:**
```sql
CREATE TABLE post_202605 PARTITION OF post
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');
```

**Impact:** All five partitioned tables (`post`, `post_embedding`, `post_entity`, `post_reference`, `price_snapshot`) have only a May 2026 partition. Today is June 2, 2026. Any INSERT with `fetched_at` >= 2026-06-01 fails with:
```
ERROR: no partition of relation "post" found for row
```
The Collector cannot persist new posts. The entire ingest pipeline is dead.

**Root cause:** The spec says "the application-tier partition scheduler will create the next partition before it is needed" but no such scheduler exists in the codebase.

**Fix (immediate):** Add migration V30 creating June and July 2026 partitions for all five tables. Example for `post`:
```sql
CREATE TABLE post_202606 PARTITION OF post
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE post_202607 PARTITION OF post
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');
```
Repeat for all five partitioned tables.

**Fix (long-term):** Implement a `@Scheduled` partition-creation bean that runs monthly and creates the next month's partition for all tables. This should be an early M1 ticket.

---

### C2. Zombie instance lock-holding — heartbeat never refreshed

**File:** `infochat-collector/src/main/java/app/zcat/infochat/collector/startup/InstanceLockGuard.java` (lines 69-85, 175-188)

**Code:**
```java
// Line 84 — heartbeat written ONCE at startup, never updated again
upsertHeartbeat(heldConnection, hostId, pid);
lockHeld = true;
```

**Impact:** The advisory lock is held via a long-lived JDBC connection, but the heartbeat row is written only once at startup. If the PostgreSQL server restarts, the network partitions, or the server terminates the session via `idle_in_transaction_session_timeout` / `tcp_keepalives`, the lock is silently released server-side while the Collector JVM continues running as a zombie. A second Collector instance starts, acquires the lock, and now two instances run simultaneously — violating the single-instance invariant (D41).

Additionally, `heldConnection.setAutoCommit(true)` (line 76) means the connection is not in a transactional state. PostgreSQL can terminate idle sessions, and the connection pool's keepalive settings do not apply since this connection is borrowed outside the pool.

The same issue exists in the Provider's `InstanceLockGuard`.

**Fix:**
1. Add a `@Scheduled(every = "30s")` method that calls `upsertHeartbeat` and verifies the connection is alive (`SELECT 1`).
2. If the connection is dead, call `Quarkus.asyncExit(1)` to trigger a clean restart.
3. Periodically re-verify the advisory lock is still held (`SELECT pg_try_advisory_lock(...)` returns `true` if this session still holds it, `false` if lost).
4. Set TCP keepalives on the held connection: `conn.setNetworkTimeout(executor, 60_000)` and configure `tcp_keepalives_idle`, `tcp_keepalives_interval`, `tcp_keepalives_count` on the connection.

---

## HIGH Findings

### H1. Signal inbound handler exception kills JSON-RPC reader thread — adapter half-dead

**Files:**
- `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java` (line 433)
- `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java` (line 167)

**Code:**
```java
// SignalJsonRpcClient.dispatchNotification, line 433:
handler.onMessage(inbound);

// SignalGroupHandler, line 167:
handler.onMessage(inbound);
```

**Impact:** The handler call has no try-catch. If `onMessage` throws any `RuntimeException` (database constraint violation, NPE, serialization failure), the exception propagates up through `dispatchNotification` → `handleLine` → the `readerLoop` while-loop, killing the `signal-jsonrpc-reader` thread. After the thread dies:
- No more inbound messages are delivered.
- No more JSON-RPC responses are demuxed, so `send()`/`update()` block until timeout (15s) then fail with TRANSIENT.
- The signal-cli subprocess is still alive, so no restart is triggered.
- The adapter is stuck in a half-dead state indefinitely.

**Contrast:** `SimpleXAdapter.onInbound` (lines 316-322) correctly wraps the handler call in try-catch.

**Fix:**
```java
try {
    handler.onMessage(inbound);
} catch (RuntimeException e) {
    LOG.warnf(e, "Signal inbound handler threw %s; message dropped",
            e.getClass().getSimpleName());
}
```

---

### H2. Multi-line TOOL_CALL leaks JSON arguments to user

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java` (lines 49-51, 159-160)

**Code:**
```java
// Line 50-51: strip pattern without DOTALL
private static final Pattern TOOL_CALL_STRIP_PATTERN = Pattern.compile(
        "TOOL_CALL:.*");

// Lines 159-160: strip applied after tool loop
finalText = TOOL_CALL_PATTERN.matcher(finalText).replaceAll("");
finalText = TOOL_CALL_STRIP_PATTERN.matcher(finalText).replaceAll("");
```

**Impact:** The `TOOL_CALL_STRIP_PATTERN` uses `.*` without `Pattern.DOTALL`, matching only to end-of-line. If the LLM emits a tool call where JSON arguments spill to the next line (e.g., `TOOL_CALL: searchPosts\n{"tags": ["crypto"]}`), the strip removes only the first line and leaves the raw JSON visible in user-facing output. This exposes the bot's internal tool-call mechanism, violating the spec's plain-text-output convention and aiding prompt-injection reconnaissance.

**Fix:** Use the same pattern as `TOOL_CALL_PATTERN` (line 43) which already handles multi-line:
```java
private static final Pattern TOOL_CALL_STRIP_PATTERN = Pattern.compile(
        "TOOL_CALL:\\s*\\w+\\s+\\{.*?\\}", Pattern.DOTALL);
```
Or add DOTALL to the existing pattern and anchor it to avoid eating everything after the first TOOL_CALL.

---

## MEDIUM Findings

### M1. Unbounded response body can cause OOM in LLM providers

**Files:**
- `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java` (line 189)
- `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java` (line 148)
- `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java`

**Code:**
```java
response = http.send(request, HttpResponse.BodyHandlers.ofString());
```

**Impact:** `BodyHandlers.ofString()` reads the entire HTTP response into a single `String` with no size limit. A misconfigured, buggy, or compromised LLM endpoint can send a multi-gigabyte response, causing `OutOfMemoryError` that crashes the JVM.

**Fix:** Use a custom `BodySubscriber` that caps the body at a configurable maximum (e.g., 1 MiB). Alternatively, check `Content-Length` header before reading and reject responses exceeding the limit.

---

### M2. Body-read deadline TOCTOU overshoot in SSRF guard

**File:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java` (lines 430-441)

**Code:**
```java
while (true) {
    long elapsedNanos = System.nanoTime() - bodyReadStartNanos;  // line 431
    if (elapsedNanos > bodyReadDeadline.toNanos()) {              // line 432
        // ... throw
    }
    Future<Integer> readFuture = readerExecutor.submit(() -> in.read(buf)); // line 437
    int n;
    try {
        n = readFuture.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS); // line 440
```

**Impact:** The deadline check fires at the top of the loop. After it passes, `readFuture.get(readTimeout)` blocks for up to 30s (default). A drip attacker delivering one byte per (readTimeout - ε) keeps each read under the per-read timeout, but total elapsed time overshoots the bodyReadDeadline by up to one full `readTimeout`. Worst-case: 150s instead of 120s.

**Fix:** Compute remaining time before each `get()`:
```java
long remainingMs = TimeUnit.NANOSECONDS.toMillis(
    bodyReadDeadline.toNanos() - (System.nanoTime() - bodyReadStartNanos));
if (remainingMs <= 0) throw new SsrfPolicyException("body read deadline exceeded");
n = readFuture.get(Math.min(readTimeout.toMillis(), remainingMs), TimeUnit.MILLISECONDS);
```

---

### M3. NOTIFY loss during reconnect not recoverable without restart

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostListener.java` (lines 164-211)

**Impact:** When `getNotifications` throws, the code closes the dead connection and enters a backoff loop before reconnecting. During this window, NOTIFYs from the Collector are lost. The `NewPostReconciler` catches up missed NOTIFYs — but only at Provider restart. If the Provider stays running through a transient Postgres blip, the reconciler does NOT re-run, and NOTIFYs are permanently lost until the next restart. The Provider's live cursor can lag behind the Collector indefinitely.

**Fix:** Run the reconciler after a successful reconnect (not just at startup). Add a `reconcile()` call immediately after re-issuing `LISTEN` in the reconnect path.

---

### M4. No signal-cli hung-process detection

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocess.java`

**Impact:** The watchdog only detects process exits via `Process.onExit()`. If signal-cli enters a pathological state (deadlocked, stuck on I/O), the watchdog never fires. Individual JSON-RPC calls timeout after 15s, but there is no counter tracking consecutive timeouts and no escalation to restart.

**Fix:** Add a consecutive-timeout counter. After N consecutive JSON-RPC timeouts (e.g., 3), trigger a subprocess restart. Optionally, add a periodic heartbeat probe (`listAccounts`) to detect unresponsiveness even when no user commands are in flight.

---

### M5. V28 unbatched UPDATE on partitioned post table

**File:** `infochat-core/src/main/resources/db/migration/V28__post_entity.sql` (line 32)

```sql
UPDATE post SET entity_done = TRUE WHERE tagger_done = TRUE;
```

**Impact:** Full-table UPDATE on the partitioned `post` table in one transaction. For large deployments, this acquires row locks on all matching rows and generates significant WAL.

**Fix:** Use a batched approach, or at minimum document the expected row count. For production: `UPDATE post SET entity_done = TRUE WHERE tagger_done = TRUE AND entity_done = FALSE LIMIT 1000` in a loop.

---

### M6. Invite code brute-force counter is per-contact, not per-code

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java` (lines 74-76)

```java
private static final String COUNT_ATTEMPTS_SQL =
    "SELECT count(*) FROM invite_code_attempt "
        + "WHERE adapter = ? AND contact_id = ? AND attempted_at > ?";
```

**Impact:** The counter is keyed on `(adapter, contact_id)`, not the invite code. An attacker with 10 different contact IDs can brute-force a specific code 10 times from each (100 total attempts). The in-memory `breachAudited` set is also unbounded.

**Fix:** Add a per-code attempt counter. Add periodic eviction of stale `breachAudited` entries.

---

### M7. SimpleX WebSocket sendCommand race with close()

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java` (lines 162-198)

**Impact:** Between the `closed` check (line 165) and `ws.sendText()` (line 177), another thread can call `close()`, aborting the WebSocket. The resulting `IllegalStateException` is not caught by sendCommand's catch blocks (which only handle `InterruptedException`, `TimeoutException`, `ExecutionException`), propagating as an unhandled RuntimeException.

**Fix:** Add `catch (RuntimeException e)` that translates to `MessagingException(PERMANENT, ...)`.

---

### M8. No 429/503/Retry-After handling in LLM providers

**Files:** `OpenAiCompatibleProvider.java`, `AnthropicProvider.java`

**Impact:** Both providers treat all non-2xx responses identically — throw `LlmCallFailedException`. 429 (Rate Limited) and 503 (Service Unavailable) receive no special handling. Callers retry once immediately, hitting the same rate limit.

**Fix:** Parse `Retry-After` header on 429/503 and include the delay in `LlmCallFailedException` (add a `retryAfterMs` field). Callers sleep before retrying.

---

### M9. DigestWorker has no concurrency guard for same-group duplicates

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java` (lines 69-75)

**Impact:** Under normal conditions the scheduler is single-threaded, but if `tick()` takes longer than the interval, the next tick could fire while the previous `execute()` is still running. The `recordMissedSlot` method inserts a sentinel cache row after the audit log commit with no transaction wrapping both — a crash between them causes duplicate audit rows.

**Fix:** Add an in-flight tracking map (`ConcurrentHashMap<String, Boolean>` keyed by `groupId + ":" + slotKind`).

---

### M10. No explicit connection pool sizing

**Files:** Both `application.properties` files

**Impact:** Neither service declares `quarkus.datasource.jdbc.max-size`. The default Agroal pool is 20 connections. The Collector has 5+ concurrent scheduled workers plus the InstanceLockGuard (1 long-lived connection) and the Provider has InstanceLockGuard + NewPostListener (2 long-lived connections). Under load, pool saturation is plausible.

**Fix:** Declare explicit pool sizes in `application.properties`. Collector: `quarkus.datasource.jdbc.max-size=30`. Provider: `quarkus.datasource.jdbc.max-size=15`. Adjust based on profile (`%laptop.` vs `%vps.`).

---

### M11. InboundRouter N+1 connection pattern

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java`

**Impact:** A single inbound group message triggers up to 5 sequential connection acquire/release cycles: `lookupUser`, `BanCheck.isBanned`, `GroupApprovalCheck.check`, `lookupGroupId`, `ensureGroupMembership`. Each is a separate pool checkout.

**Fix:** Consider passing a single connection through the intake pipeline (acquire once at the top, release at the bottom), or batch the lookups into a single query.

---

## LOW Findings

### L1. DigestScheduler silently skips groups with invalid/null timezones

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java` (lines 85-86, 189-195)

`parseTimezone` catches exceptions and returns null; the group is silently skipped on every tick with no log output.

**Fix:** Log WARN when timezone parsing fails.

---

### L2. Unicode bidi control coverage gap

**Files:** `InboundRouter.java` (lines 962-965), `Stage1Pipeline.java` (lines 283-294)

The `isBidiControl` function strips 9 bidi control codepoints but misses U+061C (ARABIC LETTER MARK), U+200E (LTR MARK), and U+200F (RTL MARK).

**Fix:** Extend to cover: `(cp == 0x061C || cp == 0x200E || cp == 0x200F)`.

---

### L3. Redactor generic pattern bypassable with >5 separator chars

**File:** `infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java` (lines 52-53)

The separator class `[\"'\\s:=]{0,5}` allows at most 5 separator characters between keyword and value. Keys with more separators evade the generic catch-all pattern.

**Fix:** Increase to `{0,20}` or use `{0,}+` (possessive).

---

### L4. ReadyPromoter `@Transactional` + explicit `getConnection()` interaction

**File:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java` (lines 143-191)

The method is `@Transactional` but acquires its own connection. The transaction boundary depends on Agroal returning the transaction-scoped connection, which is fragile.

**Fix:** Either use the `@Transactional`-managed connection properly, or remove `@Transactional` and manage commit/rollback explicitly.

---

### L5. BootstrapLoader path traversal

**File:** `infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java` (lines 105-106, 124)

The config value is passed directly to `Paths.get()` with no canonicalization or containment check.

**Fix:** Call `path.toAbsolutePath().normalize()` and verify it starts with an expected root.

---

### L6. Missing CHECK constraint on `post.stage2_verdict`

**File:** `infochat-core/src/main/resources/db/migration/V22__post_stage2_verdict.sql` (line 9)

The comment documents a closed set (`BENIGN`, `INJECTION`, `MALWARE`, `UNKNOWN`) but no CHECK constraint enforces it.

**Fix:** Add `CHECK (stage2_verdict IS NULL OR stage2_verdict IN ('BENIGN', 'INJECTION', 'MALWARE', 'UNKNOWN'))`.

---

### L7. `acquireUninterruptibly()` swallows interrupt

**Files:** `TaggerWorker.java` (line 214), `EmbeddingWorker.java`, `EntityExtractorWorker.java`

`acquireUninterruptibly()` consumes the interrupt flag without restoring it, preventing clean shutdown.

**Fix:** Use `acquire()` with `catch (InterruptedException)` that restores the flag.

---

### L8. Anthropic error message leaks into exception without truncation

**File:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java` (lines 158-164)

The error message from the Anthropic API is included verbatim in the exception and log, unlike the OpenAI provider which uses `preview()` truncation.

**Fix:** Apply `preview()` to `errorMsg` before including in exception and log.

---

### L9. GroupAutoPromoteService eligibility check outside transaction

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupAutoPromoteService.java` (lines 71-83)

The `isEligible` check runs before `setAutoCommit(false)`. A user could be banned between the check and the INSERT. The `one_admin_per_group` unique index prevents double-promotion but doesn't prevent a banned user from being promoted.

**Fix:** Move `isEligible` inside the transaction boundary.

---

### L10. LLM output sanitizer bypasses via Unicode obfuscation

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java` (lines 87-118, 187-209)

The closed-list strip matches exact ASCII strings. Fullwidth characters (U+FF0F) or zero-width spaces (U+200B) evade the match. The InboundRouter's normalization pass does not run on LLM output.

**Risk:** Low — the LLM would need to intentionally obfuscate (prompt injection), and the closed-list tokens are admin commands that won't be executed from output.

---

### L11. InfochatProfile duplication persists

**Files:**
- `infochat-collector/src/main/java/app/zcat/infochat/collector/config/InfochatProfile.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/config/InfochatProfile.java`

The Provider copy explicitly documents intent to consolidate "once infochat-core lands in M1-007a" but infochat-core already exists.

**Fix:** Move to `infochat-core` and remove both duplicates.

---

### L12. MessagingAdapter SPI lacks `start()`/`stop()` lifecycle methods

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java`

`MessagingStartup` uses reflective `Class.getMethod("start")` with `catch (Throwable)` because the SPI doesn't declare lifecycle methods.

**Fix:** Add `default void start() {}` and `default void stop() {}` to the interface.

---

### L13. ProgressNotifier has zero production implementations

**File:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressNotifier.java`

The SPI is declared and tested for loadability but no bean implements it. Long-running operations cannot publish progress events.

---

### L14. SignalConfig vs SimpleXConfig inconsistent lifecycle

`SignalConfig` is an eager CDI bean that self-validates at `@Startup`; `SimpleXConfig` is a plain value object validated lazily. A misconfigured SimpleX adapter only fails at adapter-start time.

**Fix:** Make `SimpleXConfig` a CDI bean with `@Startup` validation, matching `SignalConfig`.

---

### L15. Five TODO(T1-D) comments in production code

All related to a `TagNormalizer` helper consolidation that has not happened:
- `TagVocabulary.java:125`, `TaggerWorker.java:423`, `BootstrapLoader.java:92`, `BootstrapLoader.java:265`
- Plus `InviteCodeConsumer.java:182` (Micrometer metric)

---

### L16. Test inner class proliferation

Six test files exceed the project's 3-inner-class guideline:
- `InboundRouterProbationOrderingTest.java` — 13 inner classes
- `InboundRouterIntakeOrderingTest.java` — 11 inner classes
- `InboundRouterContactIdRedactionTest.java` — 10 inner classes
- `InboundRouterConfirmCancelTest.java` — 8 inner classes
- `DigestWorkerTest.java` — 8 inner classes
- `InboundRouterNormalizeTest.java` — 7 inner classes

---

### L17. Levenshtein recomputes distances

**File:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandler.java` (lines 206-210)

The filter calls `levenshtein()` once per zone (~600), then the sorted comparator calls it AGAIN per comparison. Total: ~6,600 calls × O(600) each.

**Fix:** Compute distances once into a `Map<String, Integer>`, then sort by the precomputed values.

---

### L18. Unbounded result sets

- `FetchScheduler.enumerateActiveSources()` — no LIMIT, loads all active sources
- `DigestScheduler.queryActiveGroups()` — no LIMIT, loads all active groups every 60s

Both are fine for small deployments but would need pagination at scale.

---

### L19. No circuit breakers

The D42 failure-counter state machine (`active` → `failed`) is the only degradation mechanism. No Hystrix, Resilience4j, or MicroProfile Fault Tolerance annotations.

---

## Positive Findings (things done well)

1. **All SQL is parameterized.** Zero SQL injection vectors found across 50+ query sites.
2. **SSRF guard is comprehensive.** DNS pinning, IP blocklist (including IPv4-mapped IPv6), scheme allowlist, userinfo gate, redirect cap, body-size cap, body-read deadline. Verified effective on JDK 25.
3. **LLM prompt injection is well-mitigated.** Fresh UUIDs as delimiters, exact-match response parsing, OWASP HTML sanitization, untrusted-content markers on tool results, output sanitization before persistence.
4. **Intake pipeline ordering matches the spec exactly.** Ban check, invite-code, group approval, probation, rate cap — all in correct order.
5. **Invite code race safety is correct.** The conditional `UPDATE ... WHERE status = 'PENDING' ... RETURNING id` serializes concurrent consumes.
6. **Admin operations are well-protected.** `SELECT ... FOR UPDATE` serializes concurrent grant/revoke. V5 trigger provides last-admin protection at the DB level.
7. **Rate cap is correctly shaped.** Token-bucket with per-bucket locking prevents amplification attacks.
8. **Regex watchdog works on JDK 25.** Verified that `Matcher.find()` calls `charAt()` on the `InterruptibleCharSequence`.
9. **Error isolation is consistent.** All scheduled workers catch per-tick/per-post exceptions and continue.
10. **Log redaction engine is comprehensive.** Covers Anthropic, OpenAI, GitHub, AWS, Google, Slack keys.
11. **Test suite is extensive.** 260 test files, 68 integration tests covering critical paths.

---

## Recommended Priority Order

1. **C1** — Add June/July 2026 partitions (V30 migration). **Do this today.**
2. **C2** — Add heartbeat refresh + connection liveness check to InstanceLockGuard.
3. **H1** — Add try-catch around Signal handler invocation (one-line fix).
4. **H2** — Fix TOOL_CALL_STRIP_PATTERN to use DOTALL.
5. **M1** — Add body-size cap to LLM response reading.
6. **M3** — Run reconciler after reconnect, not just at startup.
7. **M2** — Fix body-read deadline TOCTOU.
8. **M4** — Add consecutive-timeout counter for signal-cli.
9. Everything else is low-priority hardening or tech debt.
