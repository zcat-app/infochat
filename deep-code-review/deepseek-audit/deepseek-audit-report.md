# Deep Code Audit — DeepSeek Audit Report

**Date:** 2026-06-02
**Scope:** Full codebase (all Maven modules, main + test source)
**Reviewer:** DeepSeek V4 (via Claude Code audit session)
**Repository:** infochat — two-service Quarkus application (Collector + Provider)

---

## Executive Summary

This is a thorough, adversarial audit of the infochat codebase covering all five Maven modules
(`infochat-core`, `infochat-ssrf`, `infochat-llm-adapter`, `infochat-messaging-adapter`,
`infochat-collector`, `infochat-provider`). The codebase is at a high level of engineering quality:
the SSRF guard is notably thorough, the authorization pipeline has clear step ordering with
documented TOCTOU closures, and SQL injection is absent (all database access goes through
parameterized queries).

Findings fall into four categories: **security** (3 findings), **performance** (2 findings),
**simplification** (3 findings), and **correctness/robustness** (2 findings).

No critical vulnerabilities were found. The most notable findings are:

1. A **body-size cap bypass** via unpaired UTF-16 surrogates in `InboundRouter.exceedsUtf8ByteLength` (MEDIUM)
2. A **fragile JSON tool-arg parser** in `ChatAgent.parseToolArgs` that breaks on nested JSON objects (LOW)
3. **Massive code duplication** — `quoteJsonString` copied 3×, `lookupUser` pattern repeated in 10+ handlers (MEDIUM simplification debt)

---

## Headline Findings

<!-- TOC depth:3 ordered:false -->

- [SEC-1](#sec-1) **Body-size cap bypass via unpaired UTF-16 surrogates** (MEDIUM)
- [SEC-2](#sec-2) **Stale Javadoc in HostInterfaceSet misrepresents security posture** (LOW)
- [SEC-3](#sec-3) **SQLException message-based last-admin detection is fragile** (LOW)
- [PERF-1](#perf-1) **Per-message DB connection churn in InboundRouter** (LOW)
- [PERF-2](#perf-2) **TreeMap allocation on every ChatToolDispatcher cache-key** (LOW)
- [SIM-1](#sim-1) **quoteJsonString duplicated 3× across command handlers** (MEDIUM)
- [SIM-2](#sim-2) **lookupUser/lookupActorForUpdate pattern duplicated in 10+ handlers** (MEDIUM)
- [SIM-3](#sim-3) **Audit insert pattern duplicated across handlers** (LOW)
- [COR-1](#cor-1) **ChatAgent.parseToolArgs fails on nested JSON objects** (LOW)
- [COR-2](#cor-2) **ChatAgent.runToolLoop has no bound on accumulated conversation size** (LOW)

---

## Security Findings

### SEC-1: Body-size cap bypass via unpaired UTF-16 surrogates

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:817-836` |
| **Method** | `exceedsUtf8ByteLength(String, int)` |

**What the code does:**

The method counts the UTF-8 byte length of a Java string without allocating a `byte[]`, exiting early when the count exceeds the limit. For supplementary characters (code points > U+FFFF), it detects a high surrogate via `Character.isHighSurrogate(c)`, counts 4 UTF-8 bytes, and skips the next char via `i++`.

```java
// Lines 824-827 (simplified)
} else if (Character.isHighSurrogate(c)) {
    count += 4;
    i++;  // skip low surrogate
}
```

**What's wrong:**

Java strings can contain **unpaired** surrogates. If an attacker crafts a string with an unpaired high surrogate followed by a multi-byte character, the byte count is wrong in two ways:

1. The unpaired surrogate is counted as 4 UTF-8 bytes (actual: 3 bytes for a lone surrogate in Java's UTF-8 encoder)
2. The character at `i+1` — which is NOT a low surrogate — is skipped entirely, so its bytes go **uncounted**

**Concrete bypass:**

Given input: `"\uD800中"` (unpaired high surrogate + a 3-byte CJK character)

- Actual UTF-8 size: 3 + 3 = 6 bytes
- `exceedsUtf8ByteLength` counts: 4 + 0 = 4 bytes (under-counts by 2)

An attacker can chain many such pairs to smuggle an arbitrarily large body past the `maxInboundBodyBytes` cap (default 65536). Each bypass pair costs 4 counted bytes for 6 actual bytes — a 50% inflation factor. With a limit of 65536, the attacker can deliver up to ~98KB.

**Impact assessment:**

The body-size cap is defense-in-depth — not a security boundary by itself. Downstream layers have their own guards:
- `normalize()` still runs on the full string (NFKC may or may not change size)
- `chatBodyCap` (default 2048 chars) bounds chat-mode inputs by character count
- Slash commands are short by nature (command + args)
- `BanCheck.isBanned`, `InviteCodeConsumer`, etc. are not affected

However, the bypass means an attacker can force the server to process a significantly larger body than intended through the normalize pass and invite-code parse. The fix is simple and low-risk.

**Recommendation:**

Verify the low surrogate before skipping:

```java
} else if (Character.isHighSurrogate(c) && i + 1 < s.length()
        && Character.isLowSurrogate(s.charAt(i + 1))) {
    count += 4;
    i++;  // skip verified low surrogate
} else {
    // Unpaired surrogate or non-BMP char: count as 3 bytes
    // (Java's UTF-8 encoder outputs 3 bytes per lone surrogate)
    count += 3;
}
```

**Falsification check:** Is there another path that bounds body size before dangerous processing?

- `chatBodyCap` bounds chat-mode by character count (not byte count), so a CJK-heavy body could still be 3× the intended byte size. But the character-count cap is the spec-intended bound for chat, and 2048 chars of CJK is still ~6KB — well within limits.
- For slash commands, no secondary cap exists, but slash commands are inherently short ( `/command args` ), and the rate cap limits throughput.
- The `maxInboundBodyBytes` is described as "defense in depth," so the bypass doesn't defeat a security boundary — but it defeats the defense layer, which is the point of calling it out.

<!-- source: verified by reading InboundRouter.java:817-836 -->

---

### SEC-2: Stale Javadoc in HostInterfaceSet misrepresents security posture

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/HostInterfaceSet.java:21-27` |

**What's wrong:**

The class-level Javadoc says:

> "The `IpBlocklist`'s no-arg constructor snapshots this set at construction time and consults it on every `IpBlocklist#isBlocked` call. The snapshot intentionally captures interfaces at JVM start..."

But M1-026 changed the behavior: `IpBlocklist` now takes a `Supplier<Set<InetAddress>>` and invokes `HostInterfaceSet::enumerate` **per call**, not at construction time. The actual behavior is live enumeration — a stronger defense that sees post-startup interfaces (VPN tunnels, hot-plugged NICs, cloud EIPs).

The stale Javadoc describes a weaker, superseded posture. A future reader relying on this doc could misunderstand the actual defense surface.

**Recommendation:**

Replace the stale paragraph with: "The host's non-loopback interface bindings are consulted PER CALL via a Supplier — post-startup interfaces (VPN, hot-plugged NIC, freshly-attached cloud EIP) are seen on the very next `isBlocked` invocation."

<!-- source: verified by comparing HostInterfaceSet.java:21-27 (stale) with IpBlocklist.java:65-76 (actual per-call Supplier) -->

---

### SEC-3: SQLException message-based last-admin detection is fragile

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `BanCommandHandler.java:294`, `RevokeAdminCommandHandler.java:261` |

**What's wrong:**

Both handlers detect the V5 `trg_last_admin_protection_update` trigger by substring-matching the SQLException message:

```java
if (e.getMessage() != null && e.getMessage().contains("last_admin_protection")) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_BAN_LAST_ADMIN));
}
```

This relies on:
1. The PostgreSQL trigger raising `RAISE EXCEPTION` with a specific literal
2. The JDBC driver preserving that literal in `SQLException.getMessage()`
3. No message transformation by connection pool middleware, PgBouncer, or future Quarkus versions

The spec pins the literal in the migration (V5), which is a contract. The fragility is that any change to the trigger message (e.g., adding a schema prefix, rewording for clarity) silently changes the error surfaced to the user — the bot admin would see `IllegalStateException` instead of `error.ban.last_admin`. No audit trail, no operator signal beyond the generic failure.

The existing test coverage for this path (`BanCommandHandlerTest`, `RevokeAdminCommandHandlerTest`) uses an `H2` or in-memory database that does not run PostgreSQL triggers, so the trigger-based last-admin path is likely untested at the unit level — it depends on `@QuarkusTest` with a real PostgreSQL instance. Verify that `*IT.java` tests exercise this branch.

**Recommendation:**

Consider adding an explicit SQLSTATE-based check as defense-in-depth (`e.getSQLState()` for the trigger's error code), or document the exact trigger message contract in both the migration file and the handler Javadoc with a "DO NOT CHANGE" marker. Confirm that integration tests exercise the trigger-branch.

<!-- source: verified by reading BanCommandHandler.java:287-296, RevokeAdminCommandHandler.java:254-263 -->

---

## Performance Findings

### PERF-1: Per-message DB connection churn in InboundRouter

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `InboundRouter.java:350-590`, `BanCheck.java:46-61`, `ProbationCheck.java:66-123` |

**What's observed:**

A single inbound message happy path opens **6–8 separate JDBC connections** from the pool:

1. `InboundRouter.lookupUser` → connection (step 1 snapshot)
2. `InviteCodeConsumer.consume` → connection (step 2, DM-only; opens its own tx)
3. `BanCheck.isBanned` → connection (step 4)
4. `GroupApprovalCheck.check` → connection (step 3.5, group-only; may open multiple)
5. `GroupAutoPromoteService.tryAutoPromote` → connection (step 4.1, group-only)
6. `ensureGroupMembership` → connection (step 4.1, group-only)
7. `ProbationCheck.inProbation` → connection (step 5)
8. `ProbationCheck.clearIfPromoted` → connection (step 5 lazy clear)
9. `ProbationCheck.probationExpiry` → connection (step 5 blocked branch)

Each is a separate `dataSource.getConnection()` call. The connection pool (Agroal, default max-size 20 for Quarkus) handles this gracefully, and each connection is short-lived (single prepared statement + auto-commit). The design deliberately separates concerns — `BanCheck.isBanned` must see the freshest `is_banned` state, independent of the step-1 `UserSnapshot`.

This is an architectural observation, not a bug. The trade-off (separation of concerns vs. connection churn) is acceptable for v1 at the expected message rate (RSS cadence, not real-time chat). If the message rate scales, the connection pool could become a bottleneck.

**Recommendation:**

No immediate action required. If message throughput increases (e.g., real-time WebSocket chat from many users), profile connection pool wait times and consider consolidating steps that can share a connection without breaking isolation (e.g., step 1 + step 5 reads could share a connection since they read the same users row).

<!-- source: verified by tracing onMessage call graph across InboundRouter, BanCheck, ProbationCheck, InviteCodeConsumer -->

---

### PERF-2: TreeMap allocation on every ChatToolDispatcher cache-key

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java:116-117` |

```java
String cacheKey = toolName + "|" + userId + "|" + scopeKind
                + "|" + scopeId + "|" + new TreeMap<>(args);
```

A new `TreeMap` is constructed on every tool dispatch call to sort the args for a deterministic cache key. The args map typically has 1–3 entries. While `TreeMap` construction is cheap for small maps, it allocates a full tree structure (entry objects, comparator overhead) for what is effectively a sort-and-stringify of 1–3 key-value pairs. Over the LLM's tool loop (up to 10 iterations), this is negligible, but worth noting.

**Recommendation:**

Replace with an inline sort of the entry set:

```java
String cacheKey = toolName + "|" + userId + "|" + scopeKind
                + "|" + scopeId + "|" + args.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(","));
```

Or, since the total args across calls is tiny, simply use `new HashMap<>(args).toString()` — the HashMap iteration order is predictable within a single JVM instance for the same insertion sequence (though not guaranteed across JVMs). The `TreeMap` approach is actually the safest for deterministic ordering; the suggestion is only about avoiding the tree allocation for such small maps.

<!-- source: verified by reading ChatToolDispatcher.java:116-117 -->

---

## Simplification Findings

### SIM-1: quoteJsonString duplicated 3× across command handlers

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **Files** | `BanCommandHandler.java:462-486`, `GrantAdminCommandHandler.java:368-392`, `RevokeAdminCommandHandler.java:363-387` |

**What's observed:**

Three command handlers contain byte-for-byte identical `quoteJsonString` methods (24 lines each). The method escapes JSON string content (`"`, `\`, control characters) for embedding in `details_json` audit columns.

```bash
$ grep -rn 'private static String quoteJsonString' --include='*.java' | grep -v target | grep -v worktree
BanCommandHandler.java:462
GrantAdminCommandHandler.java:368
RevokeAdminCommandHandler.java:363
```

Additionally, `InviteCommandHandler` has its own `inviteCreateOpenIntentDetailsJson` (line 279-281) that does a simpler `targetAdapter.replace("\"", "\\\"")` — a partial reimplementation for a narrower use case.

And `LlmOutputSanitizer` has its own `jsonEscape` (line 269-289) which is again functionally equivalent.

**Impact:**

If a bug is found in the JSON escaping (e.g., a missed control character, or a Unicode escape edge case), it must be fixed in 4+ places. Past security reviews have found exactly this class of problem (the M1-024 acceptance-transcription issue arose from duplicated spec language).

**Recommendation:**

Extract to a shared utility in `infochat-core` (e.g., `app.zcat.infochat.core.log.JsonEscaper`). All handlers and `LlmOutputSanitizer` would delegate to it. This is a ~30-line extraction that eliminates 100+ lines of duplicated code.

<!-- source: verified by grep across all main source files -->

---

### SIM-2: lookupUser/lookupActorForUpdate pattern duplicated in 10+ handlers

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **Files** | 10+ command handler files and `InboundRouter` |

**What's observed:**

The pattern `SELECT id, contact_id, is_admin, is_banned, registration_state FROM users WHERE adapter = ? AND contact_id = ?` is independently implemented in:

| File | Method name | Variant |
|------|-------------|---------|
| `InboundRouter.java` | `lookupUser` | Returns `UserSnapshot` (id, isBanned, registrationState) |
| `BanCommandHandler.java` | `lookupUser` | Returns `UserRow` (id, contactId, isAdmin, isBanned, registrationState) |
| `InviteCommandHandler.java` | `lookupUser` | Returns `UserRow` (id, contactId, isAdmin, isBanned, registrationState) |
| `ForgetCommandHandler.java` | `lookupUser` | Returns `UserRow` |
| `UnbanCommandHandler.java` | `lookupUser` | Returns `UserRow` |
| `RemoveSourceCommandHandler.java` | `lookupUser` | Returns `UserRow` |
| `SourceDisableCommandHandler.java` | `lookupUser` | Returns `UserRow` |
| `SourceEnableCommandHandler.java` | `lookupUser` | Returns `UserRow` |
| `ListSourcesCommandHandler.java` | `lookupUser` | Returns `UserRow` |
| `GrantAdminCommandHandler.java` | `lookupActorForUpdate` | FOR UPDATE variant |
| `GrantAdminCommandHandler.java` | `lookupTargetInTx` | No FOR UPDATE, within tx |
| `RevokeAdminCommandHandler.java` | `lookupActorForUpdate` | FOR UPDATE variant |
| `RevokeAdminCommandHandler.java` | `lookupTargetInTx` | No FOR UPDATE, within tx |
| `VouchCommandHandler.java` | `lookupActorForUpdate` | FOR UPDATE variant |
| `VouchCommandHandler.java` | `lookupTargetInTx` | No FOR UPDATE, within tx |
| `ApproveGroupCommandHandler.java` | `lookupActorForUpdate` | FOR UPDATE variant |
| `RejectGroupCommandHandler.java` | `lookupActorForUpdate` | FOR UPDATE variant |
| `ExportCommandHandler.java` | `lookupUserId` | Returns bare UUID |
| `ClearCommandHandler.java` | `lookupUserId` | Returns bare UUID |
| `CompressCommandHandler.java` | `lookupUserId` | Returns bare UUID |

Each implementation opens its own connection, prepares the same parameterized SQL, maps the ResultSet, returns a slightly different record type. The duplication is systematic: every new command handler that needs to resolve a user copies the pattern from an existing handler.

**Why this matters:** If the `users` table schema changes (e.g., a column is added/renamed, or the lookup shifts from `contact_id` to a normalized form), 20+ methods must be updated. One missed update is a bug.

**Recommendation:**

Introduce a `UserRepository` bean in `infochat-provider` that centralizes:
- `Optional<UserRow> findByAdapterAndContactId(String adapter, String contactId)` (basic lookup)
- `Optional<UserRow> findByAdapterAndContactIdForUpdate(Connection, String, String)` (within-tx, FOR UPDATE)
- `UUID resolveUserId(String adapter, String contactId)` (for callers that only need the PK)

This is a medium-effort refactor (touches 20+ call sites) but eliminates systematic duplication. Should be its own ticket, not piggybacked on otherwise-unrelated work.

<!-- source: verified by grep across all main source files -->

---

### SIM-3: Audit insert pattern duplicated across handlers

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **Files** | `BanCommandHandler.java`, `GrantAdminCommandHandler.java`, `RevokeAdminCommandHandler.java`, `InviteCommandHandler.java`, `ChatAgent.java`, `LlmOutputSanitizer.java` |

Each handler constructs a `RedactionHook.AuditRow` via its builder, populates the same fields (`actorUserId`, `action`, `targetKind`, `targetId`, `detailsJson`), and calls `auditLogWriter.write(conn, row)`. The per-handler `insertAudit` methods differ only in which fields they populate (some include `actorContactId`, some `requestId`, some `targetContactId`).

The `AuditLogWriter` consolidation is explicitly deferred per the BanCommandHandler Javadoc ("The M1-041 AuditLogWriter consolidation is deferred"). This is a known item, not a surprise finding. Flagging it to keep it on the radar.

<!-- source: verified by reading the audit-write path in 6 files -->

---

## Correctness / Robustness Findings

### COR-1: ChatAgent.parseToolArgs fails on nested JSON objects

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java:251-305` |

**What's wrong:**

The `TOOL_CALL_PATTERN` regex uses a reluctant quantifier to match the JSON args body:

```java
static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
        "TOOL_CALL:\\s*(\\w+)\\s+(\\{.*?\\})", Pattern.DOTALL);
```

The `\{.*?\}` reluctantly matches the **shortest** sequence ending in `}`. For flat JSON like `{"tags": ["a"], "limit": 10}`, this works correctly — the shortest `}` is the outer closing brace.

For nested JSON like `{"params": {"key": "value"}}`, the reluctant match finds the **inner** `}` as the shortest match: `{"params": {"key": "value"}` — which is broken JSON (missing the outer `}`). The parsed args map will be empty or malformed, and the tool call silently fails.

The system prompt tells the LLM to emit flat JSON only. The LLM generally complies. However, subtle prompt drift or model updates could produce nested JSON, and the failure mode (silent parse failure → tool returns empty result) provides no diagnostic signal.

**Recommendation:**

Replace the regex-based extraction with a proper brace-counting parser:

```java
static String extractJsonArgs(String text, int matchEnd) {
    int braceDepth = 0;
    boolean inString = false;
    boolean escape = false;
    int start = text.indexOf('{', matchEnd);
    if (start < 0) return null;
    for (int i = start; i < text.length(); i++) {
        char c = text.charAt(i);
        if (escape) { escape = false; continue; }
        if (c == '\\') { escape = true; continue; }
        if (c == '"') { inString = !inString; continue; }
        if (!inString) {
            if (c == '{') braceDepth++;
            else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0) return text.substring(start, i + 1);
            }
        }
    }
    return null; // unclosed
}
```

Alternatively, use the existing `splitTopLevel` brace-counting logic (used in `parseToolArgs`) to verify the extracted JSON is balanced, and retry with a longer match if not.

<!-- source: verified by analyzing TOOL_CALL_PATTERN regex semantics and parseToolArgs decomposition -->

---

### COR-2: ChatAgent.runToolLoop has no bound on accumulated conversation size

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java:194-243` |

**What's observed:**

The `runToolLoop` accumulates the full conversation history (user prompt + all tool calls + all tool results) in a `StringBuilder`:

```java
StringBuilder conversation = new StringBuilder(userPrompt);
// ... in loop:
conversation.append("\n\nAssistant: ").append(text);
conversation.append("\n\nTool result for ").append(toolName).append(":\n");
conversation.append(wrappedResult);
```

With `MAX_TOOL_ITERATIONS = 10`, each iteration appends: the full LLM response (which can include prior context repeated from the model) + the tool result (which for `searchPosts` can be a full JSON array of posts). Each subsequent LLM call receives the ENTIRE accumulated `conversation.toString()` as the user prompt.

In the worst case, 10 iterations of tool calls returning large result sets could produce a multi-megabyte prompt, exceeding the LLM provider's context window and causing `LlmProvider.generate` to fail. The `ChatAgent.handle` catch block converts this to `ERROR_CHAT_UNAVAILABLE` — the user sees a generic "chat unavailable" message with no indication that the conversation was too large.

**Recommendation:**

Add a bound on the accumulated conversation length (e.g., 32KB of tool results total, or a cap on the `conversation` StringBuilder length). When exceeded, stop the loop and call the LLM with the material accumulated so far. This is decoupled from the `MAX_TOOL_ITERATIONS` cap — a single tool call could return a very large result.

<!-- source: verified by reading ChatAgent.java:194-243 -->

---

## Positive Observations

The following aspects of the codebase are notably well-done and worth calling out:

1. **SSRF Guards (infochat-ssrf module):** The `SsrfGuardedHttpClient` is a model of defense-in-depth. The combination of scheme allowlist → userinfo rejection → host canonicalization → DNS validation → IP blocklist → DNS pinning → redirect re-validation → bounded body read (per-read timeout + total deadline) is comprehensive. The Javadoc explains the threat model for each step. The `PinnedDnsResolver` SPI integration correctly handles the JVM-wide singleton constraint via a cross-call lock with staged release (lock held for connect, released for body read).

2. **Prompt-injection defenses:** The `UNTRUSTED_CONTENT` delimiter pattern with per-call random UUIDs is a reasonable v1 defense against indirect prompt injection. The system prompt explicitly instructs the LLM to reject instructions inside delimiters. Tool results are also wrapped in these delimiters via `ChatAgent.runToolLoop`.

3. **LlmOutputSanitizer:** The dual-pass sanitizer (markdown-link strip → closed-list command strip) is thorough. The audit-log-per-occurrence commitment with transactional writes is correctly implemented. The CI test that asserts `CLOSED_LIST` equals the spec's closed list is a good pattern for keeping code and spec in sync.

4. **Authorization pipeline ordering:** `InboundRouter.onMessage` has a clear, documented step order (1 → 1.5 → 1.7 → 2 → 3 → 4 → 3.5 → 4.1 → 5 → 4.5 → 4.6 → 6). Each step has a documented rationale for its position. The rate-cap-before-size-cap ordering (M1-044e fix) correctly prevents the amplification attack.

5. **Audit-before-effect (Invariant 7):** Consistently applied across all admin command handlers. Audit rows are inserted BEFORE the data mutation within the same transaction — a rollback discards both atomically.

6. **SQL injection prevention:** All database access uses parameterized queries (`PreparedStatement`). No string concatenation for SQL. The `SearchPostsTool.bindParams` generic binder is typed correctly (switch over `instanceof`).

7. **JSpecify nullability annotations:** Public methods consistently use `@NonNull`/`@Nullable` from `org.jspecify.annotations`, complying with the engineering rules' parameter contract requirement.

8. **Test coverage patterns:** The codebase uses test seams (package-private constructors, protected methods for subclassing, `Clock` injection) to enable unit testing without booting Quarkus. The balance between plain-JUnit tests and `@QuarkusTest` integration tests appears well-judged.

---

## Summary by Category

| Category | Count | Severity Breakdown |
|----------|-------|--------------------|
| SECURITY | 3 | 0 critical, 1 medium, 2 low |
| PERFORMANCE | 2 | 2 low |
| SIMPLIFICATION | 3 | 2 medium, 1 low |
| CORRECTNESS | 2 | 2 low |
| **Total** | **10** | |

---

## Recommendations Priority Order

1. **Fix SEC-1** (body-size cap bypass) — simple ~5-line fix with a concrete exploit path
2. **Address SIM-1** (quoteJsonString duplication) — extract shared utility, eliminate 4 copies
3. **Address SIM-2** (UserRepository extraction) — file a follow-up ticket; the refactor is medium-effort but eliminates systematic duplication that risks bugs on schema changes
4. **Fix SEC-2** (stale Javadoc) — single-paragraph edit
5. **Address COR-1** (nested JSON parsing) — replace reluctant regex with brace-counting parser
6. **Address COR-2** (conversation size bound) — add a StringBuilder length cap
7. **Address PERF-1 + PERF-2 + SIM-3 + SEC-3** — low-severity items that can be addressed opportunistically

---

*Audit conducted by DeepSeek V4 via Claude Code, 2026-06-02. No files were modified.*
