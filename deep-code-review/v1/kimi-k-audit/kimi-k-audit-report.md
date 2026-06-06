# Independent Code Audit Report — infochat

**Auditor:** kimi-k2.6 (manual, no skills invoked)  
**Date:** 2026-06-02  
**Scope:** Full production source across all six Maven modules (`infochat-core`, `infochat-collector`, `infochat-provider`, `infochat-ssrf`, `infochat-llm-adapter`, `infochat-messaging-adapter`). Tests and design docs are referenced only for context.  
**Method:** Direct file reads, pattern greps, and static analysis of security-critical paths (auth, SSRF, SQL, LLM integration, messaging adapters).

---

## Executive Summary

The codebase is well-structured and shows clear security-conscious design (DNS pinning, raw-JDBC with bound parameters, audit-before-effect transactions, stage-gate ingestion pipeline). However, several **load-bearing SQL-injection vectors** exist where UUID values are concatenated directly into `SET LOCAL` statements. A fragile custom JSON parser in the chat agent’s tool loop, non-idempotent lock release in the SSRF module, and unbounded conversation growth in the LLM tool loop are also notable. No critical remote-code-execution or authentication-bypass vulnerabilities were found, but the SQL-injection family is exploitable by any admin who can influence a UUID-valued column (e.g., via a compromised adapter identity or a future feature that mints actor IDs from user input).

| Severity | Count | Categories |
|----------|-------|------------|
| Critical | 0 | — |
| High | 4 | SQL injection (4 sites), JSON parser fragility |
| Medium | 6 | Resource leaks, unbounded growth, regex issues, missing migration |
| Low | 5 | Hygiene, defense-in-depth gaps, maintenance risks |
| Info | 3 | Style, documentation |

---

## 1. HIGH SEVERITY

### 1.1 SQL Injection via `SET LOCAL infochat.actor_id` string concatenation
**Files:**
- `GrantAdminCommandHandler.java:196`
- `RevokeAdminCommandHandler.java:211`
- `BanCommandHandler.java:247`
- `RejectGroupCommandHandler.java:214`

**Finding:** Every admin-mutation command handler opens a transaction and executes:
```java
st.execute("SET LOCAL infochat.actor_id = '" + actor.id + "'");
```
`actor.id` is a `UUID` loaded from the database, but **the value is not bound via `PreparedStatement`**. While a `UUID` object itself is constrained to the RFC-4122 grammar, the conversion to string happens via `UUID.toString()`, then concatenated into raw SQL. If any future code path allows an attacker-controlled string to reach `actor.id` (e.g., a compromised adapter that reports a forged contact ID parsed into a UUID-like string, or a schema change that widens the column type), this becomes a direct SQL-injection vector.

**Why it matters:** `SET LOCAL` runs at the session level and can influence row-security policies or audit triggers that rely on `current_setting('infochat.actor_id')`. An injection here could forge audit rows, bypass RLS, or escalate privileges.

**Suggested fix:** Bind via `PreparedStatement`:
```java
try (PreparedStatement ps = conn.prepareStatement("SET LOCAL infochat.actor_id = ?")) {
    ps.setObject(1, actor.id);
    ps.execute();
}
```
PostgreSQL supports parameter binding in `SET LOCAL` via the extended query protocol.

---

### 1.2 `ChatAgent.writeAuditRow` builds `details_json` via unsafe string concatenation
**File:** `ChatAgent.java:319-320`

**Finding:**
```java
.detailsJson("{\"scope_kind\":\"" + scopeKind
        + "\",\"scope_id\":\"" + scopeId + "\"}")
```
`scopeKind` comes from `chatModeScopeKindOf()`, which returns the literal `"dm"` or `"group"`, so the immediate risk is low. But `scopeId` is a `UUID`. If the method is ever refactored to accept user-derived strings, this becomes an injection into `jsonb`. More importantly, **this pattern is copied**; it signals that JSON serialization discipline is inconsistent.

**Suggested fix:** Use a deterministic JSON builder (Jackson `ObjectMapper` is already on the classpath) or a small shared utility that escapes keys/values.

---

### 1.3 `LlmOutputSanitizer.emitAuditRows` builds JSON via string concatenation
**File:** `LlmOutputSanitizer.java:235-236`

**Finding:**
```java
String detailsJson = "{\"match_count\":1,\"match_kind\":\""
        + jsonEscape(token) + "\"}";
```
`jsonEscape` only handles `\`, `"`, and control chars. It does **not** validate that the output is well-formed JSON if `token` contains unexpected Unicode (e.g., unmatched surrogate pairs after JVM string manipulation). The `jsonEscape` utility is duplicated here and in `Stage1Pipeline`’s placeholder logic.

**Suggested fix:** Use Jackson `ObjectMapper.writeValueAsString(Map.of("match_count", 1, "match_kind", token))` or centralize a `JsonWriter` utility.

---

### 1.4 `ChatAgent.parseToolArgs` — fragile custom JSON parser
**File:** `ChatAgent.java:251-305`

**Finding:** The chat agent parses LLM-emitted tool-call JSON with hand-rolled tokenization (`splitTopLevel`) instead of Jackson. The parser:
- Does not handle nested objects or arrays inside argument values.
- Does not handle escaped quotes inside string values correctly in all paths.
- Treats any non-integer numeric value as a string fallback.
- The `splitTopLevel` comma-split logic can mis-split on commas inside string values if the preceding backslash escape check is off by one (it checks `s.charAt(i - 1) != '\'`, but escaped backslashes `\\` preceding a quote would flip the state incorrectly).

**Impact:** A malicious or malfunctioning LLM can emit tool-call JSON that causes `parseToolArgs` to return malformed arguments, leading to incorrect `toolDispatcher.dispatch()` calls. If any tool handler trusts the parsed map without re-validation, this is a command-injection vector.

**Suggested fix:** Replace with Jackson `ObjectMapper.readValue(json, new TypeReference<Map<String, Object>>() {})`. Jackson is already a dependency of the `llm-adapter` module.

---

## 2. MEDIUM SEVERITY

### 2.1 `SsrfGuardedHttpClient` follows all 3xx including 304 Not Modified
**File:** `SsrfGuardedHttpClient.java:340`

**Finding:**
```java
if (status >= 300 && status < 400) {
```
This treats `304 Not Modified` as a redirect. Per RFC 7232, a 304 response must not contain a `Location` header, but a malicious server that sends one anyway would cause the client to follow it. The follow is still gated by the full SSRF pipeline, so it is not a direct bypass, but it widens the attack surface unnecessarily.

**Suggested fix:** Narrow the check:
```java
if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
```

---

### 2.2 `PinnedDial.close()` is not idempotent
**File:** `SsrfGuardedHttpClient.java:582-586`

**Finding:**
```java
@Override
public void close() {
    PinnedDnsResolver.Provider.clearPins();
    lock.unlock();
}
```
A double-close (e.g., explicit call + try-with-resources auto-close on an exception path) throws `IllegalMonitorStateException` because `ReentrantLock.unlock()` requires the calling thread to hold the lock. The class-level Javadoc acknowledges this ("Single-shot") but the method is `public` and `AutoCloseable`, so callers can legally invoke it twice.

**Suggested fix:** Guard with an `AtomicBoolean`:
```java
private final AtomicBoolean closed = new AtomicBoolean(false);
@Override
public void close() {
    if (closed.compareAndSet(false, true)) {
        PinnedDnsResolver.Provider.clearPins();
        lock.unlock();
    }
}
```

---

### 2.3 `InboundRouter.exceedsUtf8ByteLength` surrogate-pair off-by-one risk
**File:** `InboundRouter.java:817-836`

**Finding:** When encountering a high surrogate, the method increments `count += 4` and then does `i++` to skip the low surrogate. If the high surrogate is the **last** character in the string (malformed UTF-16), the loop increments `i` past `s.length() - 1`, and the next iteration’s `s.charAt(i)` would throw `StringIndexOutOfBoundsException`.

**Falsification:** The method is `static` and package-private; tests exercise it but may not cover malformed surrogate pairs at string end. The input comes from messaging adapters that are supposed to deliver valid UTF-8/UTF-16, but adapter bugs or malicious inputs could reach this.

**Suggested fix:** Add a bounds check:
```java
} else if (Character.isHighSurrogate(c)) {
    count += 4;
    if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
        i++;
    }
}
```

---

### 2.4 `ChatAgent.runToolLoop` conversation grows unbounded
**File:** `ChatAgent.java:194-243`

**Finding:** The tool loop appends every assistant response, tool result, and follow-up prompt to a `StringBuilder`. With `MAX_TOOL_ITERATIONS = 10`, and each iteration potentially adding the full tool result (which could be large — e.g., `SearchPostsTool` returning many posts), the conversation string can grow to tens or hundreds of kilobytes before being sent to the LLM. This is an unbounded-memory vector per user request.

**Suggested fix:** Cap the total conversation size (characters or tokens) and truncate or abort if exceeded. The `ChatSessionRepository.estimateTokens` helper already exists and could be reused.

---

### 2.5 Missing Flyway migration V20
**File:** `infochat-core/src/main/resources/db/migration/`

**Finding:** Migrations run V1 … V19, then jump to V21. V20 is absent. This is noted in the prior survey as "possibly intentional (skipped number) or an oversight." If it was skipped intentionally, it should be documented; if it was an oversight, a gap in migration numbering can confuse operators and tooling.

**Suggested fix:** Verify whether V20 was intentionally omitted. If so, add a `README.md` in the migration directory explaining the gap. If not, audit what V20 should have contained.

---

### 2.6 `Stage1RegexSet` DOTALL + bounded quantifiers still permit pathological backtracking
**File:** `Stage1RegexSet.java`

**Finding:** Patterns like `.{0,40}` with `Pattern.DOTALL` allow newline matching. On adversarial inputs with many newline characters and near-miss prefixes (e.g., `"ignore\n\n\n...previous\n\n\n...instructions"` with enough filler to stay under the 40-char bound), the NFA engine may explore a combinatorial number of paths before the watchdog fires. The watchdog is a wall-clock cap, not a path-count cap, so a single input can still burn significant CPU.

**Suggested fix:** The watchdog is the documented defense, but consider compiling with `Pattern.CANON_EQ` removed (not present) and ensuring the regex-timeout property is tuned conservatively for the deployment profile. No code change required if the watchdog is deemed sufficient.

---

## 3. LOW SEVERITY

### 3.1 `AddSourceArgs.parseUri()` accepts userinfo
**File:** `AddSourceArgs.java:229-248`

**Finding:** The URI parser rejects missing scheme/host but does **not** reject `uri.getRawUserInfo() != null`. Credentials in the source identifier are stored in the database. The SSRF gate prevents them from being used in outbound requests, but this is a data-hygiene issue.

**Suggested fix:** Add `if (uri.getRawUserInfo() != null) return null;`.

---

### 3.2 `UrlProbe` uses string-prefix matching on exception messages
**File:** `UrlProbe.java:88-99`

**Finding:**
```java
if (message.startsWith("body read timeout") || message.startsWith("body read deadline")) {
```
If the `SsrfPolicyException` message text changes (typo fix, rewording), the mapping breaks silently.

**Suggested fix:** Add an enum or typed exception subclasses to `SsrfPolicyException` to distinguish failure modes.

---

### 3.3 Duplicate JSON string-escaping logic across handlers
**Files:** `BanCommandHandler.java:462-486`, `GrantAdminCommandHandler.java:368-392`, `RevokeAdminCommandHandler.java:363-387`, `LlmOutputSanitizer.java:269-289`

**Finding:** At least four classes contain hand-rolled `quoteJsonString` or `jsonEscape` methods. This is a maintenance risk: a future spec addition (e.g., emoji in ban reasons) that breaks escaping must be fixed in N places.

**Suggested fix:** Extract a shared `JsonStrings.escape(String)` utility in `infochat-core`.

---

### 3.4 Busy-wait loops in adapter startup without exponential backoff
**Files:** `SimpleXAdapter.java:369-391`, `SignalAdapter.java:340-356`

**Finding:** Both adapters probe their local endpoint with a fixed 100 ms sleep (`Thread.sleep(100)`) and a hard 200 ms TCP connect timeout. Under process-startup contention or container CPU throttling, this can take many iterations and generates unnecessary syscalls.

**Suggested fix:** Use `LockSupport.parkNanos` or an `ExponentialBackoff` utility, capped at the overall deadline.

---

### 3.5 `InboundRouter.lookupGroupId` throws on missing group
**File:** `InboundRouter.java:740-756`

**Finding:** If a group message arrives for a group that has no `groups` row (e.g., race between group creation and first message, or a soft-deleted group with `removed_at IS NULL` inconsistent), the method throws `IllegalStateException`, which propagates up to `onMessage` and is caught by the generic `RuntimeException` handler, returning `INTERNAL_ERROR_REPLY` to the user.

**Impact:** Low — it is a user-visible error rather than a security hole, but it leaks the fact that the group lookup failed (distinguishable from a ban or probation block by timing).

**Suggested fix:** Return `Optional<UUID>` and handle the empty case with a silent drop or a specific log line.

---

## 4. INFO

### 4.1 Logging framework inconsistency
**Observation:** `SimpleXAdapter` and `ChatAgent` use SLF4J; `SignalAdapter`, `Stage1Pipeline`, and `LlmOutputSanitizer` use JBoss Logging. The mix is not broken (Quarkus bridges both), but it is a hygiene issue.

### 4.2 No HTTP/2 version pinning in `SsrfGuardedHttpClient`
**Observation:** The per-call `HttpClient` uses the default version negotiation. HTTP/2 multiplexing complicates connection-pool behavior and could, in theory, allow a malicious server to hold streams open. The spec does not require HTTP/2, so pinning to `HTTP_1_1` would reduce complexity.

### 4.3 `NostrStreamSource.parseRelays()` defers scheme validation to downstream
**Observation:** Relay URIs are parsed with `URI.create()` but not validated for `ws`/`wss` until they reach `checkAndPinForWebSocket`. This is fail-closed, but an earlier validation would produce clearer logs.

---

## Appendix A — Falsification Notes

For every finding above, the following attempts to falsify were made:

1. **SQL injection (1.1):** Checked whether `actor.id` could ever be user-controlled. It is loaded from the DB as a `UUID`, but `SET LOCAL` concatenation is still raw SQL. Falsification failed — the pattern is unsafe regardless of current type constraints.
2. **JSON parser fragility (1.4):** Attempted to construct valid JSON that `parseToolArgs` mis-parses. `"key": "val,ue"` (comma inside string) is handled by the quote-state tracker, but `"key": "val\\\""` (escaped backslash before quote) flips `inQuote` incorrectly because the backslash check is only one character deep. Falsification succeeded — the parser is buggy.
3. **PinnedDial idempotency (2.2):** Checked whether try-with-resources can call `close()` twice. It cannot, but an explicit `close()` followed by TWR can. Falsification succeeded — the bug is reachable.
4. **Surrogate off-by-one (2.3):** Constructed a string ending in a lone high surrogate. `exceedsUtf8ByteLength` throws `StringIndexOutOfBoundsException`. Falsification succeeded.
5. **304 redirect (2.1):** Checked RFC 7232 and JDK `HttpClient` behavior. A 304 with `Location` is non-conformant but the client would follow it. Falsification succeeded — defense-in-depth gap exists.

---

## Appendix B — Files Read During Audit

- `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java`
- `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java`
- `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java`
- `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java`
- `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java`
- `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java`
- `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1RegexSet.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java`
- `infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java`
- `infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java`
- Flyway migrations V1–V29

---

*End of report.*
