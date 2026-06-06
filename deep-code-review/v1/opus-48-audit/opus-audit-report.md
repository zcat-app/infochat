# infochat — independent code audit (Opus 4.8)

**Date:** 2026-06-02
**Scope:** real source tree (`infochat-*/src/main`), 243 main Java files / ~47 KLOC. Stale `.claude/worktrees/*` copies were excluded.
**Method:** fresh-eyes read of the security-critical boundaries — SSRF gate, inbound authorization/ban pipeline, LLM tool surface + prompt-injection defenses, SQL retrieval, output sanitizer — followed by targeted falsification (tracing whether each defense actually fires, and whether documented happy paths actually execute). Findings below were each checked against the code, not just the comments.

This repo is, on the whole, unusually careful: the SSRF pipeline, the prompt-injection delimiter scheme, the audit-durability coupling, and the intake-step ordering are all well-reasoned and well-commented. The findings concentrate in two places: a runtime-only path that no test exercises (the text tool-call parser), and a few resource/robustness edges.

---

## Findings summary

| # | Severity | Area | One-line |
|---|----------|------|----------|
| 1 | **High** | Chat tools | `ChatAgent.parseToolArgs` cannot produce JSON arrays, so every array-valued tool call (`tags`, `keywords`) throws `ClassCastException` — `recallMemory` is entirely non-functional; tag-filtered `searchPosts`/`listSaves` always fail. |
| 2 | Medium | SSRF / resources | `SsrfGuardedHttpClient.get` builds a new `HttpClient` per call **and per redirect hop** and never closes it (JDK 21+ `HttpClient` is `AutoCloseable`); relies on GC/Cleaner to reap selector threads + connection pools. |
| 3 | Medium | Concurrency / availability | The JVM-global `PinnedDnsResolver.Provider` lock is held for the **entire WebSocket handshake** (`buildAsync(...).get(connectTimeout+1s)`), serializing *all* outbound connection establishment process-wide behind every relay (re)connect. |
| 4 | Low | Robustness (masks #1) | `ChatToolDispatcher.dispatch` catches only `IllegalArgumentException`/`SQLException`; `ClassCastException` from bad LLM arg *types* escapes to a generic "chat unavailable" instead of a `ValidationError` the model can correct. |
| 5 | Low | SSRF completeness | `IpBlocklist` does not block IPv4-compatible IPv6 (`::a.b.c.d`) or NAT64 (`64:ff9b::/96`) embedded-IPv4 forms; only IPv4-mapped (`::ffff:0:0/96`) is decoded. |
| 6 | Low | Output hygiene | Several hand-rolled JSON builders escape only `\ " \n \r \t`, not other C0 control chars — invalid JSON for adversarial/feed-sourced strings (`AuditCommandHandler` details, `SearchPostsTool` titles). |
| 7 | Low | Defense-in-depth | `LlmOutputSanitizer` closed-list strip is whitespace-literal; multi-word tokens (`/invite create`) evade with non-standard whitespace. |

---

## Finding 1 — Text tool-call parser cannot build list arguments (High)

**Where:** `infochat-provider/.../chat/ChatAgent.java` `parseToolArgs` (lines 251–305) vs. the three tools that consume list args:
`SearchPostsTool.java:45-46`, `RecallMemoryTool.java:38-39`, `ListSavesTool.java:44-45`.

**What the code does.** The v1 LLM SPI is a single string, so tool calls are a text protocol. The system prompt (`ChatAgent.TOOL_INSTRUCTIONS`, lines 55-70) instructs the model to emit array arguments:

```
- searchPosts {"tags": ["tag1"], "window": "P7D", "limit": 10}
- recallMemory {"keywords": ["keyword1", "keyword2"]}
- listSaves {"tags": ["tag1"], "window": "P7D"}
```

But `parseToolArgs` only ever produces `String` or `Integer` values. For a value like `["bitcoin"]` it does:

```java
if (value.startsWith("\"") && value.endsWith("\"")) { args.put(key, value.substring(1, ...)); }
else { try { args.put(key, Integer.parseInt(value)); } catch (NumberFormatException e) { args.put(key, value); } }  // stored as the raw String "[\"bitcoin\"]"
```

`splitTopLevel` correctly keeps the bracketed array together (bracket-depth tracking), so the value is the literal string `["bitcoin"]` — never a `List`.

The consuming tools then do an unchecked cast:

```java
List<String> tags = args.containsKey("tags") ? (List<String>) args.get("tags") : List.of();   // SearchPostsTool:45
List<String> keywords = args.containsKey("keywords") ? (List<String>) args.get("keywords") : List.of(); // RecallMemoryTool:38
```

`(List<String>) "[\"bitcoin\"]"` throws `ClassCastException` at runtime.

**Impact.**
- `recallMemory` requires `keywords` (returns `[]` only if the list is empty) → it **never works** when actually called with keywords.
- `searchPosts`/`listSaves` work *only* when no tags are supplied; any tag-filtered query fails.
- The exception is not caught by `ChatToolDispatcher` (Finding 4), so it bubbles to `ChatAgent.handle`'s `catch (Exception)` and the user gets `ERROR_CHAT_UNAVAILABLE`. From the user's seat, chat search/recall is broken whenever it matters.

**Why it slipped through.** The only `parseToolArgs` unit tests pass scalar JSON (`{"query":"test","limit":10}`) and `{}` (`ChatAgentTest.java:262-270`); the `runToolLoop` tests wire no-op tools that ignore args (`ChatAgentTest.java:347`). No test ever drives an array value end-to-end. Falsification check: I grepped every `parseToolArgs` test and the tool-loop fakes — none exercise a `[...]` argument.

**Fix.** Make the parser actually understand arrays and return `List<String>` for bracketed values. Minimal, dependency-free change inside `parseToolArgs`:

```java
String value = kv[1].trim();
if (value.startsWith("[") && value.endsWith("]")) {
    args.put(key, parseJsonStringArray(value));   // returns List<String>
} else if (value.startsWith("\"") && value.endsWith("\"")) {
    args.put(key, value.substring(1, value.length() - 1));
} else {
    try { args.put(key, Integer.parseInt(value)); }
    catch (NumberFormatException e) { args.put(key, value); }
}
```

where `parseJsonStringArray` reuses `splitTopLevel` on the bracket interior and strips per-element quotes. Add a `parseToolArgs` test with `{"tags":["a","b"]}` asserting a `List` result, and an integration test that drives `recallMemory` through `runToolLoop` with a real (fake-backed) tool so the cast path is covered.
A cleaner alternative — given Quarkus already ships Jackson — is to parse the args object with a real JSON reader rather than the bespoke splitter; that also fixes the brittle non-greedy `\{.*?\}` capture in `TOOL_CALL_PATTERN`, which breaks on any tool argument that legitimately contains a `}`.

---

## Finding 2 — Per-call `HttpClient` is never closed (Medium)

**Where:** `infochat-ssrf/.../SsrfGuardedHttpClient.java` `get(URI, Map)` lines 324-327 (inside the redirect `while` loop).

```java
HttpClient perCallClient = HttpClient.newBuilder()
    .connectTimeout(connectTimeout)
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();
```

A fresh `HttpClient` is built on **every** call and again on **every redirect hop**, and none are ever `close()`d. On Java 21+ (this project targets JDK 25) `HttpClient` implements `AutoCloseable` and owns a `SelectorManager` daemon thread plus a connection pool; without an explicit `close()` those are reclaimed only when the client becomes unreachable and its `Cleaner` runs.

**Impact.** Under sustained fetch concurrency (RSS + social fetchers + asset snapshots + URL probes all share this gate) this produces thread and file-descriptor churn that lags GC, not a hard leak but a real steady-state cost and a latent FD-exhaustion risk on long-running collectors. It also defeats connection reuse — a new pool per request means a fresh TCP+TLS handshake every time even to the same host.

**Fix.** Wrap the client in try-with-resources for the duration of the hop (it is only needed until the body is read; note the body is read *after* the redirect loop, so the client must outlive the loop iteration that produced the terminal response). The simplest correct shape is to build **one** client before the loop, reuse it across hops, and close it after `readBounded`. Reusing one client across hops is also strictly better for the pinned-DNS design since the pin is per-host-per-hop, not per-client. If per-call isolation is required, close in a `finally` after the body read.

---

## Finding 3 — Global SSRF lock held across the full WebSocket handshake (Medium)

**Where:** `SsrfGuardedHttpClient.checkAndPinForWebSocket` (lines 502-517) + `NostrRelayConnection.connectAndSubscribe` (lines 255-266).

The pin slot and resolver are JVM-wide, so correctness requires serializing connection establishment on `PinnedDnsResolver.Provider.lock()`. For HTTP `get()` the lock is released before the body read — good. For WebSocket the `PinnedDial` holds the lock until `close()`, and the dial is performed *and awaited* inside the try-with-resources:

```java
try (PinnedDial dial = ssrfClient.checkAndPinForWebSocket(relayUri)) {
    pinnedAddresses = dial.addresses();
    webSocket = httpClient.newWebSocketBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .buildAsync(relayUri, new RelayListener())
        .get(CONNECT_TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);   // lock held the whole time
}
```

Awaiting the handshake inside the block is *correct* for SSRF (the pin must outlive the connect, Finding-checked: yes, it does). The cost is that the same global lock gates **every** other outbound connection in the process — every RSS/asset `get()` and every other relay reconnect. A single slow or stalled relay handshake therefore blocks all outbound connection establishment for up to ~`CONNECT_TIMEOUT + 1s`. With many Nostr relays cycling through reconnect backoff, this is a process-wide head-of-line blocking surface.

**Impact.** Availability/throughput, not a security hole — but it couples the slowest relay's connect latency to the latency of unrelated fetchers.

**Fix options (in order of preference):**
1. Replace the JVM-global pin slot with a per-connection resolver bound to that one client (e.g. a custom `InetAddress` resolution path per `HttpClient`/WebSocket builder), removing the global lock entirely. Larger change but eliminates the serialization root cause.
2. If the global slot stays, scope the lock hold to only the handshake's DNS-resolution window rather than the full TCP/TLS/WS-upgrade `.get()` — requires confirming the JDK resolves DNS synchronously at dial submission.
3. At minimum, document the contention explicitly and keep `CONNECT_TIMEOUT` tight so the worst-case global stall is bounded.

---

## Finding 4 — Dispatcher swallows only two exception types (Low; masks Finding 1)

**Where:** `ChatToolDispatcher.dispatch` lines 137-145.

```java
try { String result = tool.execute(...); ... }
catch (IllegalArgumentException e) { return new ToolResult.ValidationError(e.getMessage()); }
catch (SQLException e) { throw new IllegalStateException(...); }
```

`clampLimit` (`((Number) args.get("limit")).intValue()`) and the tools' own casts (`(List<String>)`, `(String) args.get("window")`, `Duration.parse`) can throw `ClassCastException` / `DateTimeParseException` for ill-typed LLM arguments. These are not `IllegalArgumentException`, so they escape the dispatcher, abort the whole turn, and surface as a generic "chat unavailable." That both (a) hides Finding 1 behind a non-specific error and (b) denies the model the structured `ValidationError` feedback it could otherwise use to retry with corrected arguments.

**Fix.** Validate/coerce arg types at the dispatcher boundary (it already validates lengths and clamps limits there) and convert type/parse failures into `ToolResult.ValidationError`. The dispatcher is the right system boundary for "the LLM produced a malformed tool call."

---

## Finding 5 — Blocklist misses non-mapped embedded-IPv4 IPv6 forms (Low)

**Where:** `IpBlocklist.isBlocked` / `isIpv4Mapped` (lines 100-119, 208-215).

The decoder only recognizes IPv4-mapped IPv6 (`::ffff:a.b.c.d`, bytes 10-11 = `0xFFFF`). It does not decode:
- **IPv4-compatible** `::a.b.c.d` (deprecated, RFC 4291) — e.g. `::127.0.0.1` falls through `isBlockedV6` (not all-zero, not `::1`, not fe80/fc00/ff00) and returns `false`.
- **NAT64** `64:ff9b::/96` — `64:ff9b::7f00:1` similarly is not decoded.

A literal-IP URL such as `http://[::127.0.0.1]/` would have that address returned by the resolver seam and pass the blocklist.

**Why Low, not higher.** IPv4-compatible addresses are deprecated and not routed to loopback on modern Linux kernels, and NAT64 only resolves to an internal target where a NAT64 gateway is deployed. So practical exploitability is narrow. Still, the blocklist's stated intent is "cover the kernel-level bypass forms," and these are the same class of bypass as the `::ffff:` form it already handles.

**Fix.** In `isBlocked`, also decode the IPv4-compatible form (first 12 bytes zero, last 4 non-trivial) and the NAT64 prefix to their embedded IPv4 and run `isBlockedV4`. Add the literals to the existing blocklist test matrix.

---

## Finding 6 — Hand-rolled JSON escaping omits C0 control characters (Low)

**Where:** `AuditCommandHandler.escapeJson` (212-218), `SearchPostsTool.jsonStr` (194-209), `ChatAgent.writeAuditRow` details (319-320, no escaping at all on `scopeKind`).

These escapers handle `\ " \n \r \t` but emit other control chars (` `-``, e.g. `\b`, `\f`, vertical tab) raw, producing invalid JSON. Contrast `LlmOutputSanitizer.jsonEscape` (269-289), which *does* `\u`-escape `c < 0x20` — that one is correct and is the pattern the others should follow.

**Impact.**
- `AuditCommandHandler`: an admin passing `--actor` with a control char yields invalid `details_json`; if the column is `jsonb`, the insert throws → rollback → `ERROR_INTERNAL`. Self-inflicted and admin-only, but it's an avoidable failure on the audit-write path.
- `SearchPostsTool.jsonStr`: post titles come from external feeds and can contain C0 controls; the tool's JSON result fed back to the LLM can be malformed, degrading tool-result parsing.
- `ChatAgent.writeAuditRow`: `scopeKind` is internal (`"dm"`/`"group"`) so safe in practice, but the unescaped concatenation is fragile.

**Fix.** Reuse the `c < 0x20 → \u%04x` branch from `LlmOutputSanitizer.jsonEscape` in all three, or (better) build these small JSON payloads with Jackson rather than string concatenation.

---

## Finding 7 — Closed-list strip is whitespace-literal (Low, defense-in-depth)

**Where:** `LlmOutputSanitizer.applyClosedListStripWithMatches` (187-209), tokens like `/invite create`, `/quarantine approve`, `/list-sources --all`.

Each token is matched with `Pattern.quote(token)`, i.e. literally including its single internal space. LLM output containing `/invite  create` (two spaces) or `/invite\ncreate` would not be stripped. Because this is a defense-in-depth layer over LLM-authored prose (real authorization is deterministic Java, and these strings are never executed), the impact is limited to social-engineering text reaching the user. Still, the multi-word entries are exactly the ones an injection-steered model is most likely to reproduce with odd spacing.

**Fix.** For multi-word closed-list entries, compile with internal whitespace as `\s+` (keeping the existing trailing `(?=$|[^a-zA-Z0-9\-])` boundary). Single-word entries are unaffected.

---

## Things checked and found sound (no action needed)

- **SSRF pipeline ordering** — scheme allowlist → userinfo gate → host canonicalization (`IDN.toASCII` → lowercase → trailing-dot strip) → resolve → per-address blocklist → pin the *validated* set → dial through the pinned resolver. The validated set equals the pinned set, so there is no resolve/connect TOCTOU rebind window. Redirects re-enter the full pipeline per hop.
- **WebSocket SSRF gate** — the pin/lock is correctly held across the awaited handshake (the cost of that is Finding 3, but the security property holds), and `peerIpDiverged` re-resolves for mid-session rebind detection.
- **Inbound authorization order** (`InboundRouter.onMessage`) — rate-cap-first (no outbound amplification), size-cap before normalize (no NFKC blowup), single user-snapshot SELECT feeding steps 2/3/5 with a separate fresh ban check for the banned-mid-dispatch race, banned users short-circuited before any group DB write. Matches the spec's intake order.
- **Prompt-injection defense** — per-call random-UUID `<<<UNTRUSTED_CONTENT id="...">>>` delimiters wrap user message, pre-fetched memory, *and* tool results; the parser only ever executes the model's own freshly-emitted `TOOL_CALL`, never text echoed from inside untrusted blocks.
- **SQL** — every retrieval path I read is parameterized (`PreparedStatement` + bound params, including pgvector `?::TEXT[]` arrays); the one dynamic SQL string (`AuditCommandHandler` WHERE builder) only ever concatenates fixed clause fragments with `?` placeholders, values bound separately. No injection found.
- **Sanitizer audit durability** — closed-list hits write one audit row per occurrence in a single transaction and the method throws (aborting the reply) if the audit write fails, honoring the spec's per-occurrence durability commitment.
- **Tool limit clamping** — `ChatToolDispatcher.clampLimit` bounds `limit` to `[1, limitCap]` before tools run, so the unbounded-`limit` concern I initially flagged in `SearchPostsTool` is already mitigated at the dispatcher boundary.

---

## Recommended priority order

1. **Finding 1** — restore array-argument parsing; it's a user-visible functional break in a core feature with zero test coverage on the failing path. Fix the parser *and* add the missing array-path tests.
2. **Finding 4** — fix alongside #1 so malformed tool calls degrade gracefully and the model can self-correct.
3. **Finding 2** — close/reuse the `HttpClient`; cheap change, removes thread/FD churn and restores connection reuse.
4. **Finding 3** — evaluate de-globalizing the pinned resolver; larger but addresses a real availability coupling.
5. **Findings 5–7** — small hardening items; batch them.
