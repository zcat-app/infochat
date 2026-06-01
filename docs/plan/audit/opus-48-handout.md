# Audit consolidation handout (Opus 4.8)

**Date:** 2026-06-02
**Inputs:** the four independent audit reports under `deep-code-review/`:
- `opus-48-audit/opus-audit-report.md` (7 findings)
- `deepseek-audit/deepseek-audit-report.md` (10 findings)
- `kimi-k-audit/kimi-k-audit-report.md` (18 findings + 3 info)
- `mimo-audit/mimo-audit-report.md` (36 findings)

**Purpose.** This is the staging document for turning audit findings into M1 tickets.
For *every* reported issue it records: what was claimed, the **grounded verdict**
(each claim was re-checked against the actual source — line numbers below are from
the live tree, not the reports), **why it is worth resolving**, **why it may not be
an issue**, and a **disposition**. The Tier D section is as important as Tier A:
several findings are false positives or proposals that would *violate* the project's
own "no defensive code" rule, and we should not spend tickets on them.

**Method note.** Findings were falsified, not confirmed. Where an audit's *conclusion*
is right but its *stated mechanism* is wrong (C2, H2, SEC-1), the disposition targets
the real defect, not the report's description — otherwise a ticket would "fix" the
wrong thing.

---

## How to read the disposition column

| Tag | Meaning |
|---|---|
| **FIX** | Real defect, ticket it. |
| **FIX-LOW** | Real but low-impact; batch with neighbours or do opportunistically. |
| **DOC** | Documentation/comment-only correction; no code change. |
| **NON-ISSUE** | Not a defect, or "fixing" it would violate an engineering rule (no-defensive-code, surgical-changes). Record the reasoning so it isn't re-raised. |
| **WATCH** | Not actionable now; valid at higher scale/load. Note in design, no M1 ticket. |

---

## Cross-audit deduplication map

Many findings are the same defect seen by multiple auditors (sometimes with
contradictory severity). Canonical IDs used in this doc:

| Canonical | opus-48 | deepseek | kimi-k | mimo | Adjudicated |
|---|---|---|---|---|---|
| **TOOL-ARGS** parseToolArgs cannot build lists | #1 (High) | COR-1 (Low, nested) | 1.4 (High) | — | **High** |
| **TOOL-DISPATCH** dispatcher swallows only IAE/SQLException | #4 (Low) | — | (implied 1.4) | — | **Medium** (pairs with TOOL-ARGS) |
| **TOOL-CONV** unbounded conversation growth | — | COR-2 (Low) | 2.4 (Med) | — | **Low-Med** |
| **TOOL-LEAK** TOOL_CALL strip multi-line | — | — | — | H2 (High) | **Low** |
| **JSON-DUP** hand-rolled JSON escaping duplicated / missing C0 | #6 (Low) | SIM-1 (Med) | 1.2/1.3/3.3 | (M-impl) | **Med (debt) + Low (C0)** |
| **HTTP-CLIENT** per-call HttpClient never closed | #2 (Med) | — | 4.2 (info) | — | **Medium** |
| **WS-LOCK** global SSRF lock across WS handshake | #3 (Med) | — | — | — | **Medium (arch)** |
| **SSRF-304** 304/305/306 treated as redirect | — | — | 2.1 (Med) | — | **Low** |
| **PINNED-CLOSE** PinnedDial.close not idempotent | — | — | 2.2 (Med) | — | **NON-ISSUE** |
| **IPV6-FORMS** blocklist misses ::compat / NAT64 | #5 (Low) | — | — | — | **Low** |
| **UTF8-CAP** exceedsUtf8ByteLength surrogate handling | — | SEC-1 (Med) | 2.3 (Med) | — | **NON-ISSUE (false)** |
| **SETLOCAL-SQLI** SET LOCAL actor_id concatenation | — | — | 1.1 (High) | — | **NON-ISSUE** |
| **CONN-CHURN** per-message DB connection churn | — | PERF-1 (Low) | — | M11 (Med) | **WATCH** |
| **LOOKUP-DUP** lookupUser pattern duplicated | — | SIM-2 (Med) | — | — | **Med (debt)** |
| **LAST-ADMIN-MSG** SQLException message match | — | SEC-3 (Low) | — | — | **Low** |
| **LLM-OOM** unbounded LLM response body | — | — | — | M1 (Med) | **Medium** |
| **PARTITIONS** no June 2026+ partitions | — | — | — | C1 (Crit) | **Critical** |
| **LOCK-LIVENESS** advisory-lock zombie / split-brain | — | — | — | C2 (Crit) | **Medium (mechanism corrected)** |
| **SIGNAL-HANDLER** handler exception kills reader | — | — | — | H1 (High) | **High** |

Everything else is single-sourced and listed under its originating audit below.

---

# Tier A — Confirmed real, Critical / High (ticket first)

### A1 · PARTITIONS — no partitions exist for June 2026 or later  *(mimo C1, Critical)*
**Files:** `V7__joins_post.sql:175`, `V11__post_embedding.sql:77`, `V17__price_snapshot.sql:60`, `V28__post_entity.sql:69`, `V29__post_reference.sql:69`.
**Verdict — CONFIRMED.** All five partitioned tables define exactly one partition,
`FOR VALUES FROM '2026-05-01' TO '2026-06-01'`. The latest migration is V29; there is
no V30 and no partition-creation scheduler bean anywhere in `infochat-collector` or
`infochat-provider` (grep for `PARTITION OF` / `createPartition` finds only the
migrations and unrelated comment hits). Today is 2026-06-02, so any insert with
`fetched_at >= 2026-06-01` fails with `no partition of relation "post" found for row`.
**Why resolve:** without it the collector cannot persist a single new post — the
ingest pipeline is dead on arrival. This is the highest-priority item across all four
audits. Note this likely also reddens any IT that inserts rows at `now()` unless tests
pin a May timestamp/clock — worth checking the suite state as a falsification of "it's
fine today."
**Why it might be less urgent than it looks:** this is greenfield M1, not a running
deployment, so nothing is "failing in production" yet. But the build must not ship a
schema that breaks on the first real insert.
**Disposition: FIX (Critical).** Two parts: (1) V30 migration adding June+July 2026
partitions to all five tables (immediate unblock); (2) a `@Scheduled` monthly
partition-creator bean (the spec promises one — "the application-tier partition
scheduler will create the next partition before it is needed" — but it was never
built). Part 2 is the durable fix; part 1 stops the bleeding.

### A2 · TOOL-ARGS — chat tool-call parser cannot produce list arguments  *(opus-48 #1 High; deepseek COR-1; kimi 1.4)*
**Files:** `ChatAgent.java:251-305` (`parseToolArgs`/`splitTopLevel`) vs `SearchPostsTool.java:45-46`, `RecallMemoryTool.java:38-39`, `ListSavesTool.java:44-45`.
**Verdict — CONFIRMED, and this is the standout functional bug.** `parseToolArgs`
only ever stores `String` or `Integer`. For a value like `["bitcoin"]` it fails
`Integer.parseInt`, catches `NumberFormatException`, and stores the **raw string**
`["bitcoin"]`. `splitTopLevel` keeps the bracketed token intact, so the value is never
a `List`. The three consuming tools then do an unchecked `(List<String>) args.get("tags"/"keywords")`,
which throws `ClassCastException` at runtime. Consequences, each verified against the code:
- `recallMemory` requires `keywords` → **never works** when called with keywords.
- `searchPosts` / `listSaves` work only when *no* tags are supplied; any tag filter throws.
- The CCE is not caught by `ChatToolDispatcher` (see A3), so it bubbles to
  `ChatAgent.handle`'s `catch (Exception)` → user sees `ERROR_CHAT_UNAVAILABLE`.

The disconnect is structural: `ChatToolDispatcher.validateInputLengths` already branches
on `value instanceof List<?>` (lines 156-168) — the dispatcher *expects* list-valued
args that the parser can never produce. No test drives an array end-to-end
(`ChatAgentTest` only passes scalar JSON and no-op tools), which is why it slipped.
deepseek/kimi saw the same parser as "fragile/nested-object"; opus-48 pinned the exact
array→CCE break, which is the real, always-on failure.
**Why resolve:** a core chat feature (search/recall by tag/keyword) is broken whenever
it matters, with zero coverage on the failing path.
**Why it might be deferrable:** only the chat-mode tool loop is affected; slash commands
and ingest are untouched. But chat is a v1 surface, so this is High.
**Disposition: FIX (High).** Teach the parser to emit `List<String>` for bracketed
values (reuse `splitTopLevel` on the interior, strip per-element quotes), OR — cleaner,
since Quarkus already ships Jackson — parse the args object with a real JSON reader
(this *also* dissolves TOOL-DISPATCH and TOOL-PARSE-NESTED at once). Add a `parseToolArgs`
test asserting a `List` result and an integration test driving `recallMemory` through
`runToolLoop` with a fake-backed tool. **Fix A2 + A3 together.**

### A3 · TOOL-DISPATCH — dispatcher swallows only two exception types  *(opus-48 #4 Low)*
**File:** `ChatToolDispatcher.dispatch:137-145`; `clampLimit:174-179`.
**Verdict — CONFIRMED.** `dispatch` catches only `IllegalArgumentException` (→
`ValidationError`) and `SQLException` (→ `IllegalStateException`). But `clampLimit` does
`((Number) args.get("limit")).intValue()` and tools do `Duration.parse((String) args.get("window"))`
and `(List<String>) ...`. A quoted `"limit"`, a malformed `"window"`, or a tag list all
throw `ClassCastException`/`DateTimeParseException` — neither is an `IllegalArgumentException`,
so they escape the dispatcher, abort the whole turn, and surface as the generic
"chat unavailable." This both masks A2 and denies the model the structured
`ValidationError` it could use to self-correct.
**Why resolve:** the dispatcher *is* the right system boundary for "the LLM produced a
malformed tool call" — coercing/validating arg types there is in-bounds, not defensive
overreach.
**Why limited:** purely a graceful-degradation improvement; with A2 fixed and Jackson
parsing, most of these can't arise. Still worth converting type/parse failures to
`ValidationError`.
**Disposition: FIX (Medium), bundled with A2.**

### A4 · SIGNAL-HANDLER — inbound handler exception kills the JSON-RPC reader thread  *(mimo H1, High)*
**File:** `SignalJsonRpcClient.java:433` (`handler.onMessage(inbound)` in `dispatchNotification`).
**Verdict — CONFIRMED.** The reader thread runs `readerLoop()` (326) → `handleLine()`
(372) → `dispatchNotification()` (412) → `handler.onMessage()` (433). `handleLine`'s
*only* try/catch wraps `codec.decode` and catches `IllegalArgumentException`
(372-384); the `switch`-dispatch that reaches `onMessage` is **outside** that try.
`readerLoop` catches only `IOException`. So any `RuntimeException` from `onMessage`
(DB constraint violation, NPE, serialization failure) propagates up and kills the
`signal-jsonrpc-reader` thread. After that: no inbound delivery, `send()`/`update()`
block to timeout then fail TRANSIENT, the signal-cli subprocess stays alive so no
restart fires — the adapter is half-dead indefinitely. `SimpleXAdapter.onInbound`
(cited 316-322) wraps its handler call; Signal does not — a genuine asymmetry.
**Why resolve:** a single bad inbound message permanently wedges the Signal adapter.
Signal is a v1 adapter (memory: "Signal adapter must remain in v1"), so this is on the
critical path.
**Why narrow:** requires `onMessage` to actually throw, which the intake pipeline mostly
prevents — but "mostly" is not "never," and the failure mode is catastrophic and silent.
**Disposition: FIX (High).** Wrap `handler.onMessage` in `try/catch (RuntimeException)`,
log class-name-only (per D37 — no user bytes), drop the message. One-line-ish fix
mirroring the SimpleX path.

---

# Tier B — Confirmed real, Medium

### B1 · LOCK-LIVENESS — advisory-lock zombie / split-brain  *(mimo C2, reported Critical — mechanism corrected)*
**Files:** `infochat-collector/.../startup/InstanceLockGuard.java` (and provider twin);
`HeartbeatScheduler.java` in **both** services.
**Verdict — PARTIALLY CONFIRMED; mimo's stated mechanism is wrong.** mimo claims the
heartbeat "is written once at startup and never refreshed" and that this holds for the
Provider too. **False:** both services have a `@Scheduled(every="{infochat.heartbeat.interval}")`
`HeartbeatScheduler.tick()` that updates `heartbeat.last_seen_at` every interval.
*However*, the underlying split-brain risk is real for a different reason: `tick()`
uses a **transient pool connection**, deliberately decoupled from the long-lived
session that owns `pg_try_advisory_lock` (the Javadoc says so explicitly). Consequences:
- The held lock-session is never liveness-probed and the advisory lock is never
  re-verified after startup. If that session dies server-side (PG restart, network
  partition, `idle_in_transaction_session_timeout`, NAT idle reaping — note
  `setAutoCommit(true)`, so the connection is borrowed *outside* the pool with no
  keepalive), the lock releases but the JVM keeps running.
- Worse, the heartbeat scheduler on its healthy pool connection keeps refreshing
  `last_seen_at`, so the zombie looks **alive** to a second acquirer's staleness check —
  the heartbeat *masks* the dead holder.
**Why resolve:** D41 promises single-instance enforcement; a silent lock loss violates it.
**Why not Critical:** requires a server-side session death (uncommon), and the existing
heartbeat scheduler means mimo's "fix" ("add a heartbeat refresh") is already done — so
a ticket written to mimo's description would change nothing.
**Disposition: FIX (Medium).** The real fix is liveness on the *held* session: periodically
re-run `SELECT pg_try_advisory_lock(...)` / `pg_advisory_lock` ownership check on the
lock-owning connection (or a `SELECT 1` on it), and `Quarkus.asyncExit(1)` if it lost
the lock or the connection is dead; set TCP keepalive / `setNetworkTimeout` on the held
connection. Do **not** "add a heartbeat scheduler" — clarify the ticket so it targets the
held-session check, not the already-present `last_seen_at` refresh.

### B2 · HTTP-CLIENT — per-call `HttpClient` built per request and per redirect, never closed  *(opus-48 #2, Med)*
**File:** `SsrfGuardedHttpClient.java:324-327` (inside the redirect `while` loop).
**Verdict — CONFIRMED.** A fresh `HttpClient` is built on every `get()` and again on
every redirect hop, and none are `close()`d. On JDK 25 `HttpClient` is `AutoCloseable`
and owns a `SelectorManager` daemon thread + connection pool, reclaimed only when the
client is unreachable and its `Cleaner` runs.
**Why resolve:** under sustained fetch concurrency (RSS + social + asset snapshots +
URL probes all share this gate) this churns threads/FDs lagging GC — a latent
FD-exhaustion risk on long-running collectors — and defeats connection reuse (fresh
TCP+TLS per request even to the same host).
**Why not higher:** not a hard leak (GC/Cleaner eventually reaps), and per-call clients
do give per-call isolation. But it's cheap to fix and strictly better.
**Disposition: FIX (Medium).** Build one client before the redirect loop, reuse across
hops, close after `readBounded` (or `finally` after the body read). Reuse is also
cleaner for the pinned-DNS design since pins are per-host-per-hop, not per-client.

### B3 · LLM-OOM — unbounded LLM/embedding response body  *(mimo M1, Med)*
**Files:** `OpenAiCompatibleProvider.java:189`, `AnthropicProvider.java:148`, `OpenAiCompatibleEmbeddingProvider.java:140`.
**Verdict — CONFIRMED.** All three call `http.send(request, BodyHandlers.ofString())`,
which buffers the entire response into one `String` with no cap. These providers do
*not* go through the SSRF guard's `readBounded`. A misconfigured/buggy/compromised LLM
endpoint sending a multi-GB body → `OutOfMemoryError` → JVM crash.
**Why resolve:** the `remote-llm` profile points at a remote API the operator doesn't
fully control; a bounded read is standard hygiene and matches what `SsrfGuardedHttpClient`
already does for fetched content.
**Why lower than an SSRF target:** the LLM endpoint is operator-configured (semi-trusted),
not attacker-chosen, so exploitability needs a compromised/buggy endpoint.
**Disposition: FIX (Medium).** Cap the body with a custom `BodySubscriber` (or check
`Content-Length` and stream-with-limit), configurable max (e.g. 1–8 MiB).

### B4 · WS-LOCK — JVM-global SSRF lock held across the full WebSocket handshake  *(opus-48 #3, Med)*
**Files:** `SsrfGuardedHttpClient.checkAndPinForWebSocket:502-517` + `PinnedDial:562-587`; caller `NostrRelayConnection.connectAndSubscribe`.
**Verdict — CONFIRMED.** The pin slot and resolver are JVM-wide, so `PinnedDial` holds
`PinnedDnsResolver.Provider.lock()` until `close()`, and the caller awaits
`buildAsync(...).get(connectTimeout+1s)` *inside* the try-with-resources. Holding the
pin across the awaited handshake is **correct** for SSRF (the pin must outlive the
connect — verified). The cost: the same global lock gates *every* other outbound
connection establishment process-wide (every RSS/asset `get()`, every other relay
reconnect). A single slow/stalled relay handshake blocks all outbound connect for up to
~`CONNECT_TIMEOUT + 1s`; with many Nostr relays cycling reconnect backoff this is a
process-wide head-of-line-blocking surface.
**Why resolve:** availability coupling — the slowest relay's connect latency leaks into
unrelated fetchers.
**Why not a bug:** it's the deliberate consequence of the single-slot JVM-global
`PinnedDnsResolver` SPI; the security property is intact. De-globalizing is a large change.
**Disposition: FIX-LOW / WATCH.** Cheapest now: document the contention and keep
`CONNECT_TIMEOUT` tight (bounds worst-case stall). Larger ticket (own milestone): replace
the JVM-global pin with a per-connection resolver bound to one client, removing the
global lock. Don't bundle with small items.

### B5 · TOOL-CONV — unbounded conversation growth in the tool loop  *(deepseek COR-2; kimi 2.4, Med)*
**File:** `ChatAgent.runToolLoop:194-243`.
**Verdict — CONFIRMED, bounded.** Each of up to `MAX_TOOL_ITERATIONS=10` iterations
appends the full LLM response + the wrapped tool result to a `StringBuilder`, and the
whole accumulation is re-sent as the next prompt. `searchPosts` results can be sizeable.
Mitigants in code: the dispatcher clamps `limit` (`limitCap=200`) and enforces
`inputMaxLength`/`listMaxSize`, and the loop is capped at 10. So "multi-megabyte" is a
worst case, not typical. If the prompt does exceed the provider window, `generate` fails
→ caught → `ERROR_CHAT_UNAVAILABLE` (no crash, just an opaque error).
**Why resolve:** a per-request memory/again-and-again-resend cost, and a confusing
failure mode when it trips.
**Why low:** bounded by the iteration cap and limit clamp; no unbounded-per-request leak
in practice.
**Disposition: FIX-LOW.** Add a total accumulated-size cap (chars/tokens — reuse
`ChatSessionRepository.estimateTokens`); when exceeded, stop looping and do the final
call with what's accumulated.

### B6 · CONN-CHURN — per-message DB connection churn / N+1 checkouts  *(deepseek PERF-1; mimo M11)*
**Files:** `InboundRouter` intake path + `BanCheck`, `ProbationCheck`, `GroupApprovalCheck`, etc.
**Verdict — CONFIRMED as an observation, not a bug.** A single inbound message opens
6–9 short-lived pool connections (one per intake step). This is **deliberate**: e.g.
`BanCheck.isBanned` must see the freshest `is_banned` independent of the step-1 snapshot
(documented TOCTOU closure). Agroal handles short-lived checkouts fine at v1 message
rates (RSS cadence, not real-time chat).
**Why it could matter:** at higher throughput (real-time multi-user chat) the pool
could become a bottleneck.
**Why not now:** separation-of-concerns is the right call for v1; consolidating risks
re-introducing the staleness races the design closed on purpose.
**Disposition: WATCH.** No M1 ticket. If throughput scales, profile pool waits and
consider sharing a connection only across steps that read the same row without breaking
isolation. (Related: B-config M10 pool sizing below.)

### B7 · POOL-SIZE — no explicit connection-pool sizing  *(mimo M10)*
**Files:** both `application.properties`.
**Verdict — CONFIRMED.** Neither service sets `quarkus.datasource.jdbc.max-size`
(Agroal default 20). Collector has 5+ scheduled workers + 1 long-lived lock connection;
Provider has the lock connection + `NewPostListener`. Saturation is plausible under load.
**Why resolve:** explicit, profile-aware sizing is cheap and removes a latent surprise.
**Why low:** defaults are adequate at v1 rates; this is tuning, not a defect.
**Disposition: FIX-LOW.** Declare explicit `max-size` per service, with `%laptop`/`%vps`
overrides. Pairs naturally with B6's WATCH note.

### B8 · NOTIFY-RECONCILE — NOTIFY loss on reconnect not recovered without restart  *(mimo M3)*
**File:** `NewPostListener.java:164-211` (reconnect/backoff loop).
**Verdict — REPORTED; consistent with the code structure, not line-by-line re-verified
in this pass.** Claim: when `getNotifications` throws, the listener closes and backs off
before re-`LISTEN`; NOTIFYs emitted during that window are lost. `NewPostReconciler`
catches missed NOTIFYs **only at startup**, so a transient PG blip that doesn't restart
the Provider leaves the live cursor permanently behind.
**Why resolve:** a transient DB hiccup silently stops new posts surfacing to users until
the next restart — exactly the kind of "looks healthy, isn't" failure that's hard to spot.
**Why possibly lower:** depends how often the listener actually drops; LISTEN/NOTIFY over
a stable local socket is reliable in practice.
**Disposition: FIX (Medium).** Invoke the reconciler after every successful reconnect,
not only at startup (`reconcile()` immediately after re-issuing `LISTEN`). Confirm the
reconciler is idempotent first.

### B9 · SIGNAL-HUNG — no signal-cli hung-process detection  *(mimo M4)*
**File:** `SignalSubprocess.java`.
**Verdict — REPORTED; plausible, not deep-verified.** The watchdog detects process
*exit* via `Process.onExit()` but not a *hung* (alive-but-unresponsive) subprocess. JSON-RPC
calls time out at 15s individually, but nothing counts consecutive timeouts or escalates
to a restart.
**Why resolve:** a deadlocked signal-cli silently degrades the only Signal path with no
recovery. Complements A4 (handler-death) — both are "adapter alive but useless" gaps.
**Why low-ish:** needs signal-cli to actually wedge; rare.
**Disposition: FIX-LOW.** Consecutive-timeout counter → restart after N (e.g. 3);
optionally a periodic `listAccounts` liveness probe.

### B10 · INVITE-COUNTER — brute-force counter keyed per-contact, not per-code  *(mimo M6)*
**File:** `InviteCodeConsumer.java:74-76` (`COUNT_ATTEMPTS_SQL ... WHERE adapter=? AND contact_id=?`).
**Verdict — CONFIRMED.** The attempt counter is keyed `(adapter, contact_id)`. An attacker
with N contact IDs gets N× the attempt budget against one specific code. The in-memory
`breachAudited` set is also unbounded (one entry per offending key).
**Why resolve:** defence-in-depth against guessing a specific PENDING code; and the
unbounded `breachAudited` set is a slow memory growth.
**Why exploitability is narrow:** practical only if invite codes are low-entropy enough to
guess in a handful of tries — verify the code format. If codes are high-entropy random,
per-contact keying is acceptable and this drops toward NON-ISSUE.
**Disposition: FIX-LOW (conditional).** Add a per-code attempt counter and periodic
eviction of stale `breachAudited` entries. Gate the priority on confirming code entropy.

### B11 · SSRF-DEADLINE-TOCTOU — body-read deadline overshoot by up to one read-timeout  *(mimo M2)*
**File:** `SsrfGuardedHttpClient.readBounded:430-440`.
**Verdict — CONFIRMED, minor.** The total-deadline check is at the top of the loop;
after it passes, `readFuture.get(readTimeout)` can block a further `readTimeout`. So a
drip attacker overshoots `bodyReadDeadline` by at most one `readTimeout` (e.g. 150s vs
120s). The deadline still fires — it's a constant overshoot, not unbounded.
**Why resolve:** tightens the documented total-elapsed bound to actually be the bound.
**Why minor:** the M1-026 deadline already converts an unbounded drip into a bounded one;
this just removes a constant slack.
**Disposition: FIX-LOW.** Clamp each `get()` to `min(readTimeout, remaining-until-deadline)`.

### B12 · LLM-RETRY — no 429/503/Retry-After handling  *(mimo M8)*
**Files:** `OpenAiCompatibleProvider`, `AnthropicProvider`.
**Verdict — REPORTED; plausible.** Both treat all non-2xx identically (throw
`LlmCallFailedException`); callers retry once immediately, re-hitting the same 429/503.
**Why resolve:** respecting `Retry-After` avoids hammering a rate-limited endpoint and
improves resilience on shared/remote LLMs.
**Why low:** correctness is unaffected; it's a politeness/resilience enhancement.
**Disposition: FIX-LOW.** Parse `Retry-After` on 429/503, carry `retryAfterMs` on the
exception, have callers sleep before retry.

### B13 · DIGEST-CONCURRENCY — DigestWorker has no same-group in-flight guard  *(mimo M9)*
**File:** `DigestWorker.java:69-75`.
**Verdict — REPORTED; plausible.** Scheduler is single-threaded normally, but if a `tick()`
outruns its interval the next tick can overlap; `recordMissedSlot` inserts a sentinel
after the audit commit with no transaction spanning both → a crash between them can
duplicate audit rows.
**Why resolve:** avoids duplicate digests/audit rows under slow ticks.
**Why low:** requires tick > interval, unusual at v1 cadence.
**Disposition: FIX-LOW.** In-flight `ConcurrentHashMap` keyed `groupId+":"+slotKind`; or
wrap the sentinel+audit in one transaction.

### B14 · SIMPLEX-RACE — `sendCommand` race with `close()` throws raw RuntimeException  *(mimo M7)*
**File:** `SimpleXWebSocketClient.java:162-198`.
**Verdict — REPORTED; plausible.** Between the `closed` check (165) and `ws.sendText()`
(177), another thread can `close()`; the resulting `IllegalStateException` isn't among
`sendCommand`'s caught types (`InterruptedException`/`TimeoutException`/`ExecutionException`)
and escapes as an unhandled RuntimeException.
**Why resolve:** translate to `MessagingException(PERMANENT,…)` so the SPI contract holds
on the shutdown race.
**Why low:** only on the close race; cosmetic vs. functional.
**Disposition: FIX-LOW.** Add `catch (RuntimeException)` → `MessagingException(PERMANENT)`.

---

# Tier C — Confirmed real, Low (hygiene / defence-in-depth / tech debt)

### C-JSON-DUP · hand-rolled JSON escaping duplicated (and C0-incomplete)  *(deepseek SIM-1 Med; kimi 1.2/1.3/3.3; opus-48 #6; mimo implied)*
**Verdict — CONFIRMED, broader than any single report.** Grep finds a JSON-escape-shaped
method (`quoteJsonString`/`jsonEscape`/`escapeJson`) in **~12** main-source files
(`BanCommandHandler`, `GrantAdminCommandHandler`, `RevokeAdminCommandHandler`,
`ApproveGroupCommandHandler`, `RejectGroupCommandHandler`, `AuditCommandHandler`,
`ExportPaginator`, `LlmOutputSanitizer`, `BootstrapLoader`, `BootstrapAssetsLoader`,
`PriceSnapshotStore`, `StartupReleaseOnStage2FailureWarn`). Two distinct issues:
1. **Duplication (deepseek SIM-1, Med debt).** A bug in escaping must be fixed in ~12 places.
2. **C0 incompleteness (opus-48 #6, Low).** Several escapers handle only `\ " \n \r \t`
   and emit other control chars (`\b`, `\f`, vertical tab) raw → invalid JSON.
   `LlmOutputSanitizer.jsonEscape` *does* `\u`-escape `c < 0x20` correctly — the others
   should match it. The one with real external exposure is `SearchPostsTool.jsonStr`:
   post titles come from feeds and can carry C0 controls, so its JSON tool-result fed
   back to the LLM can be malformed. `AuditCommandHandler`/`ChatAgent.writeAuditRow` are
   admin/internal-only (lower risk).
**Why resolve:** single source of truth for escaping; fixes the C0 gap once.
**Why not urgent:** no injection (values are escaped, just incompletely for exotic
controls); admin/internal call-sites dominate.
**Disposition: FIX (Medium, debt).** Extract `app.zcat.infochat.core.log.JsonEscaper`
(correct C0 handling), delegate all sites — or build these small payloads with Jackson.
Resolves opus #6, kimi 1.2/1.3/3.3, deepseek SIM-1, and the JSON half of A2 in one ticket.

### C-LOOKUP-DUP · `lookupUser` / `lookupActorForUpdate` duplicated across 10+ handlers  *(deepseek SIM-2, Med)*
**Verdict — CONFIRMED (deepseek's table matches the tree).** The
`SELECT … FROM users WHERE adapter=? AND contact_id=?` pattern is re-implemented in 15+
handlers + `InboundRouter`, each returning a slightly different record. A `users`-schema
change must touch all of them; one miss is a bug.
**Why resolve:** systematic duplication; high blast radius on schema change.
**Why not now:** touches 20+ call-sites — medium-effort refactor, must be its own ticket,
not piggybacked.
**Disposition: FIX-LOW (own ticket).** Introduce a `UserRepository` bean with
`findByAdapterAndContactId`, a `…ForUpdate(Connection,…)` variant, and `resolveUserId`.

### C-AUDIT-DUP · audit-insert pattern duplicated  *(deepseek SIM-3, Low)*
**Verdict — CONFIRMED and already a known deferral.** `BanCommandHandler` Javadoc states
"the M1-041 `AuditLogWriter` consolidation is deferred." Not a surprise finding.
**Disposition: FIX-LOW / track under the existing M1-041 deferral.** No new analysis needed.

### C-IPV6-FORMS · blocklist misses IPv4-compatible (`::a.b.c.d`) and NAT64 (`64:ff9b::/96`)  *(opus-48 #5, Low)*
**File:** `IpBlocklist.isBlocked:100-119`, `isIpv4Mapped:208-215`.
**Verdict — CONFIRMED.** Only IPv4-*mapped* (`::ffff:a.b.c.d`) is decoded to its embedded
IPv4 and re-checked. `::127.0.0.1` (IPv4-compatible): `isIpv4Mapped` returns false (bytes
10-11 are 0, not 0xFFFF), then `isBlockedV6` doesn't match (not all-zero, not `::1`, not
fe80/fc00/ff00) → returns **false**, i.e. passes. NAT64 `64:ff9b::7f00:1` likewise.
**Why resolve:** the blocklist's stated intent is "cover the kernel-level bypass forms";
these are the same bypass class as the `::ffff:` form already handled.
**Why Low:** IPv4-compatible is deprecated (RFC 4291) and not routed to loopback on modern
Linux; NAT64 only resolves internally where a NAT64 gateway exists. Narrow exploitability.
**Disposition: FIX-LOW.** Decode IPv4-compatible (first 12 bytes zero, last 4 non-trivial)
and the NAT64 prefix to embedded IPv4 → `isBlockedV4`; add literals to the blocklist test matrix.

### C-CLOSEDLIST-WS · closed-list strip is whitespace-literal  *(opus-48 #7; overlaps mimo L10)*
**File:** `LlmOutputSanitizer.applyClosedListStripWithMatches:187-209`.
**Verdict — CONFIRMED.** Multi-word tokens (`/invite create`, `/quarantine approve`,
`/list-sources --all`) are matched with `Pattern.quote(token)` — exact single internal
space. `/invite  create` (two spaces) or `/invite\ncreate` evades. mimo L10 adds the
Unicode-obfuscation angle (fullwidth `／`, ZWSP) and notes LLM output isn't NFKC-normalized.
**Why resolve:** the multi-word entries are exactly what an injection-steered model is most
likely to reproduce with odd spacing.
**Why Low (defence-in-depth only):** real authorization is deterministic Java; these
strings are never executed. Impact is limited to social-engineering text reaching the user.
**Disposition: FIX-LOW.** Compile multi-word entries with internal whitespace as `\s+`
(keep the trailing boundary). Optionally normalize LLM output before the strip (addresses
mimo L10's Unicode angle). Single-word entries unaffected.

### C-SSRF-304 · 304/305/306 treated as redirects  *(kimi 2.1, Med→Low)*
**File:** `SsrfGuardedHttpClient.java:340` (`if (status >= 300 && status < 400)`).
**Verdict — CONFIRMED, minor.** Any 3xx is treated as a redirect. The request is an
unconditional GET (no `If-None-Match`/`If-Modified-Since`), so a well-behaved server
won't 304; a 304 *with* a `Location` (non-conformant) would be followed — but still
through the full SSRF pipeline, so no bypass. Edge case: a 304/305/306 *without* Location
hits `orElseThrow` → `SsrfPolicyException("redirect response missing Location header")`,
turning an odd-but-harmless response into an error.
**Why resolve:** narrows the follow set to actual redirects (301/302/303/307/308); cleaner.
**Why Low:** no security bypass (pipeline re-gates); only affects misbehaving upstreams.
**Disposition: FIX-LOW.** Narrow the condition to the five real redirect codes.

### C-LASTADMIN-MSG · last-admin detection by SQLException message substring  *(deepseek SEC-3, Low)*
**Files:** `BanCommandHandler.java:294`, `RevokeAdminCommandHandler.java:261`.
**Verdict — CONFIRMED.** Both detect the V5 `trg_last_admin_protection_*` trigger by
`e.getMessage().contains("last_admin_protection")`. Fragile against any reword of the
trigger message or driver/pooler transformation — a changed message silently degrades the
user-facing `error.ban.last_admin` to a generic `IllegalStateException`.
**Why resolve:** SQLSTATE-based detection (or a "DO NOT CHANGE" contract marker in both
the migration and the handler) is more robust. Also worth confirming an `*IT` actually
exercises the trigger branch against real PostgreSQL.
**Why Low:** the message literal is pinned in V5 (a contract); breakage requires a future
edit to the migration.
**Disposition: FIX-LOW.** Prefer a `RAISE … USING ERRCODE` + `getSQLState()` check; confirm IT coverage.

### C-USERINFO-SRC · `AddSourceArgs` accepts userinfo in source URI  *(kimi 3.1, Low)*
**File:** `infochat-provider/.../command/AddSourceArgs.java` (parse path).
**Verdict — CONFIRMED data-hygiene only.** The parser rejects missing scheme/host but not
`getRawUserInfo() != null`, so credentials in a source identifier get stored. The SSRF gate
(`resolveAndValidate:373`) rejects userinfo at *fetch* time, so such a source can never be
fetched anyway.
**Why resolve:** avoids storing creds and avoids a source that's silently un-fetchable.
**Why Low:** no outbound use; cosmetic + secret-hygiene.
**Disposition: FIX-LOW.** Reject `getRawUserInfo() != null` at parse time with a clear error.

### C-URLPROBE-MSG · `UrlProbe` maps failure modes by exception-message prefix  *(kimi 3.2, Low)*
**File:** `UrlProbe.java:88-99`.
**Verdict — CONFIRMED fragility.** Branches on `message.startsWith("body read timeout"/"body read deadline")`;
a reword of `SsrfPolicyException` text silently breaks the mapping. Same class of fragility
as C-LASTADMIN-MSG.
**Disposition: FIX-LOW.** Introduce typed `SsrfPolicyException` subclasses / an enum reason,
match on type not text. Could share a ticket with C-LASTADMIN-MSG ("replace string-sniffing
with typed signals").

### C-BIDI-GAP · normalization misses U+061C / U+200E / U+200F  *(mimo L2, Low)*
**File:** `InboundRouter.isBidiControl:962-965` / `isZeroWidth:968-970`.
**Verdict — CONFIRMED.** Covers `0x202A–0x202E` and `0x2066–0x2069` (and ZWSP/ZWNJ/ZWJ),
but not U+061C (Arabic Letter Mark), U+200E (LRM), U+200F (RLM). NFKC does not remove these.
**Why resolve:** completes bidi-spoofing coverage in stored/displayed content.
**Why Low:** marks affect display, not authorization or parsing.
**Disposition: FIX-LOW.** Extend the predicate with `cp == 0x061C || cp == 0x200E || cp == 0x200F`.

### C-REDACTOR-SEP · generic redaction pattern bypassable with >5 separators  *(mimo L3, Low)*
**File:** `Redactor.java:52-53` (`[\"'\\s:=]{0,5}`).
**Verdict — REPORTED; plausible.** The catch-all key/value separator allows ≤5 separator
chars; a key with more separators evades the generic pattern. (Specific provider patterns
still catch known token shapes.)
**Disposition: FIX-LOW.** Widen to `{0,20}` or possessive `{0,}+`; add a test with a long
separator run.

### C-STAGE2-CHECK · missing CHECK constraint on `post.stage2_verdict`  *(mimo L6, Low)*
**File:** `V22__post_stage2_verdict.sql:9` (`ADD COLUMN stage2_verdict TEXT;`).
**Verdict — CONFIRMED.** The comment documents the closed set (`BENIGN/INJECTION/MALWARE/UNKNOWN`)
but no CHECK enforces it.
**Why resolve:** the DB schema is a system boundary; an enum-style CHECK is standard and
catches a Stage-2 code bug at write time. (Not "defensive code for an impossible scenario" —
it's a boundary constraint, the carve-out the rule explicitly allows.)
**Why Low:** the writer is trusted internal code; no external input reaches the column.
**Disposition: FIX-LOW.** Add `CHECK (stage2_verdict IS NULL OR stage2_verdict IN (…))` in a new migration (cannot edit applied V22).

### C-V28-UPDATE · unbatched full-table UPDATE in V28  *(mimo M5→Low)*
**File:** `V28__post_entity.sql:32` (`UPDATE post SET entity_done=TRUE WHERE tagger_done=TRUE`).
**Verdict — CONFIRMED.** A one-shot full-table UPDATE on the partitioned `post` table —
row locks on all matches + WAL. It's a one-time backfill on an essentially empty M1 table.
**Why resolve:** for a large pre-existing dataset it'd be heavy.
**Why Low:** V28 is already applied and the table is new/small; rewriting an applied
migration is itself risky and usually not done.
**Disposition: WATCH / DOC.** Document expected row count; no rewrite of an applied migration.
Apply the batched pattern only to *future* backfills.

### C-ACQUIRE-INT · `acquireUninterruptibly()` swallows interrupt  *(mimo L7, Low)*
**Files:** `TaggerWorker.java:214`, `EmbeddingWorker`, `EntityExtractorWorker`.
**Verdict — REPORTED; plausible.** `acquireUninterruptibly()` consumes the interrupt flag,
hindering prompt shutdown.
**Disposition: FIX-LOW.** Use `acquire()` + `catch (InterruptedException)` restoring the flag
(`Thread.currentThread().interrupt()`).

### C-DIGEST-TZLOG · DigestScheduler silently skips invalid/null timezone  *(mimo L1, Low)*
**File:** `DigestScheduler.java:85-86,189-195`.
**Verdict — REPORTED; plausible.** `parseTimezone` returns null on bad input and the group
is skipped every tick with no log → invisible misconfiguration.
**Disposition: FIX-LOW.** WARN once when parsing fails.

### C-READYPROMOTER-TX · `@Transactional` + explicit `getConnection()`  *(mimo L4, Low)*
**File:** `ReadyPromoter.java:143-191`.
**Verdict — REPORTED; needs confirmation.** A `@Transactional` method that also acquires its
own connection — if the acquired connection isn't the transaction-scoped one, the tx boundary
is illusory. Worth verifying whether Agroal returns the enlisted connection here.
**Disposition: FIX-LOW (verify first).** Either use the managed connection or drop
`@Transactional` and manage commit/rollback explicitly. Confirm actual behaviour before editing.

### C-AUTOPROMOTE-TX · GroupAutoPromoteService eligibility check outside tx  *(mimo L9, Low)*
**File:** `GroupAutoPromoteService.java:71-83`.
**Verdict — REPORTED; plausible.** `isEligible` runs before `setAutoCommit(false)`; a user
could be banned between the check and the INSERT. The `one_admin_per_group` index prevents
double-promotion but not promoting a just-banned user.
**Disposition: FIX-LOW.** Move `isEligible` inside the transaction.

### C-ANTHROPIC-PREVIEW · Anthropic error message truncation  *(mimo L8, Low)*
**File:** `AnthropicProvider.java:158-164`.
**Verdict — DISPUTED / needs confirmation.** mimo says the error is included verbatim
"unlike OpenAI which uses `preview()`." But `extractErrorMessage(body)` (159) falls through
to `return preview(body)` (204), so the *fall-through* path **is** truncated. The only
potentially-untruncated path is the successfully-parsed JSON `message` field. So this is at
most a narrow gap, possibly already handled.
**Disposition: FIX-LOW (confirm first).** If the parsed-message branch is untruncated, apply
`preview()` there too; otherwise close as NON-ISSUE.

### C-ADAPTER-BACKOFF · busy-wait in adapter startup probes  *(kimi 3.4, Low)*
**Files:** `SimpleXAdapter.java:369-391`, `SignalAdapter.java:340-356`.
**Verdict — REPORTED; plausible.** Fixed 100 ms sleep + 200 ms connect-timeout probe loop;
under CPU throttling this spins with unnecessary syscalls.
**Disposition: FIX-LOW.** Exponential backoff capped at the overall deadline.

### C-GROUPLOOKUP-THROW · `lookupGroupId` throws on missing group  *(kimi 3.5, Low)*
**File:** `InboundRouter.java:740-756`.
**Verdict — REPORTED; plausible.** A group message with no `groups` row throws
`IllegalStateException` → generic `INTERNAL_ERROR_REPLY`; a timing oracle distinguishes
"unknown group" from ban/probation blocks.
**Why Low:** user-visible error, not a security hole; the oracle is weak.
**Disposition: FIX-LOW.** Return `Optional<UUID>` and silent-drop / specific-log the empty case.

### C-LEVENSHTEIN · `GroupTimezoneCommandHandler` recomputes distances  *(mimo L17, Low)*
**File:** `GroupTimezoneCommandHandler.java:206-210`.
**Verdict — REPORTED; plausible.** `levenshtein()` is called once per zone in the filter,
then again per comparison in the sort comparator (~6,600 × O(600)).
**Disposition: FIX-LOW.** Precompute distances into a `Map<String,Integer>`, sort by them.

### C-INFOCHATPROFILE-DUP · `InfochatProfile` duplicated across services  *(mimo L11, Low)*
**Files:** collector + provider copies.
**Verdict — CONFIRMED debt.** The provider copy's own comment promises consolidation "once
infochat-core lands" — and infochat-core exists.
**Disposition: FIX-LOW.** Move to `infochat-core`, delete both duplicates. (Coordinate with
any reviewer scope rules; it touches two modules.)

### C-TODOS · stale `TODO(T1-D)` comments in production code  *(mimo L15, Low)*
**Files:** `TagVocabulary.java:125`, `TaggerWorker.java:423`, `BootstrapLoader.java:92,265`,
`InviteCodeConsumer.java:182`.
**Verdict — REPORTED.** Five TODOs (a `TagNormalizer` consolidation + a Micrometer metric).
**Disposition: FIX-LOW / file follow-ups.** Either implement `TagNormalizer` (its own ticket)
or convert TODOs to tracked tickets and remove the inline markers.

### C-TEST-INNERCLASS · test files exceed the 3-inner-class guideline  *(mimo L16, Low)*
**Files:** `InboundRouterProbationOrderingTest` (13), `…IntakeOrderingTest` (11),
`…ContactIdRedactionTest` (10), `…ConfirmCancelTest` (8), `DigestWorkerTest` (8),
`InboundRouterNormalizeTest` (7).
**Verdict — CONFIRMED; matches a standing convention** (memory: "Avoid private inner classes
in test files" — extract stateless doubles to top-level package-private classes).
**Disposition: FIX-LOW.** Extract shared fakes to top-level package-private test doubles.
Own cleanup ticket; not bundled with production fixes.

---

# Tier D — Disputed, false positives, or "fixing it" violates a rule

These were checked and found **not** to warrant the proposed change. Recording the reasoning
so they are not re-raised in a future audit.

### D1 · SETLOCAL-SQLI — "SQL injection via `SET LOCAL infochat.actor_id`"  *(kimi 1.1, reported High)*
**Files:** `GrantAdminCommandHandler:196`, `RevokeAdminCommandHandler:211`, `BanCommandHandler:247`,
`RejectGroupCommandHandler:214`, `ApproveGroupCommandHandler:151`, `VouchCommandHandler:169`,
`QuarantineCommandHandler:118/211/251`, `UnbanCommandHandler:170/229`.
**Verdict — NON-ISSUE, and the proposed fix would not even compile-behave as intended.**
Three independent reasons:
1. `actor.id` is a `java.util.UUID` loaded from the DB; `UUID.toString()` is RFC-4122 grammar
   (hex + hyphens) — there is no character that can break out of the quoted literal. kimi's
   own falsification admits "It is loaded from the DB as a UUID."
2. The proposed `ps.setObject(1, actor.id)` against `SET LOCAL infochat.actor_id = ?` **does not
   work**: `SET LOCAL <name> = <value>` is a Postgres *meta-command*, not DML, and does not accept
   JDBC bind parameters for the value. `UnbanCommandHandler`'s class Javadoc (lines 70-79) documents
   exactly this and explains the alternative (`SELECT set_config('…', ?, true)`) was deliberately
   rejected because it wouldn't match an acceptance-item grep predicate.
3. Hardening against "a future code path that lets attacker-controlled strings reach `actor.id`"
   is defending an impossible scenario across an internal trust boundary — precisely what
   §"No defensive code" forbids.
**Disposition: NON-ISSUE.** No ticket. If anyone ever widens `actor.id`'s type away from UUID,
*that* change carries the obligation, not this code today. (Optional DOC: add the same
"SET LOCAL caveat" note to the other handlers that lack it, for the next reader — but that's
comment-only and low value since the value is a UUID.)

### D2 · UTF8-CAP — "body-size cap bypass via unpaired surrogates" + "SIOOBE at string end"  *(deepseek SEC-1 Med; kimi 2.3 Med)*
**File:** `InboundRouter.exceedsUtf8ByteLength:817-836`.
**Verdict — BOTH FALSE.** The function: on a high surrogate it does `count += 4; i++`.
- **deepseek's undercount/bypass is arithmetically impossible.** deepseek assumed a lone
  surrogate costs 3 actual UTF-8 bytes; in Java, `String.getBytes(UTF_8)` replaces an unpaired
  surrogate with `?` (0x3F) = **1 byte**. Per "surrogate + next char" the function counts 4 and
  skips the next char (≤3 bytes). Actual = 1 + (1..3) = 2..4 bytes. So function count (4) is
  **always ≥** actual — the counter *over*-counts unpaired surrogates and can never undercount.
  No input smuggles bytes past the cap; if anything it rejects slightly early. deepseek's "98KB
  past a 64KB limit" does not exist.
- **kimi's `StringIndexOutOfBoundsException` is unreachable.** When the last char is a high
  surrogate at `i = len-1`: the body sets `i = len`, the `for` increment makes `i = len+1`, and
  the loop condition `i < len` is checked **before** `charAt(i)` — so `charAt` is never called
  out of bounds. The for-condition guards every `charAt`.
**Disposition: NON-ISSUE (false positive).** No code change. (A purely cosmetic tidy — verifying
the low surrogate before `i++` — has zero behavioural effect since the cap is already conservative
and bounds-safe; not worth a ticket.)

### D3 · PINNED-CLOSE — "PinnedDial.close() is not idempotent"  *(kimi 2.2, reported Med)*
**File:** `SsrfGuardedHttpClient.PinnedDial.close:582-587`.
**Verdict — NON-ISSUE per the no-defensive-code rule.** `close()` is documented "single-shot"
(Javadoc 557-560) and the *only* caller uses try-with-resources, which closes exactly once. A
double-close throwing `IllegalMonitorStateException` requires a caller that both explicitly
calls `close()` *and* wraps in TWR — no such caller exists. kimi's falsification ("an explicit
close() followed by TWR") describes a hypothetical caller, not actual code. Adding an
`AtomicBoolean` guard would be defensive code for an internal scenario that cannot occur given
the single TWR caller.
**Disposition: NON-ISSUE.** No ticket *unless* a second, non-TWR caller of `PinnedDial` is ever
added — at which point the guard becomes a boundary concern. Note in the design so the next
WebSocket-adapter author knows the contract.

### D4 · TREEMAP-ALLOC — "TreeMap allocation on every cache-key"  *(deepseek PERF-2, Low)*
**File:** `ChatToolDispatcher.java:116-117`.
**Verdict — NON-ISSUE (micro-optimization the report itself walks back).** A `new TreeMap<>(args)`
per dispatch over a 1–3 entry map, ≤10 times per turn. deepseek's own text concludes "the TreeMap
approach is actually the safest for deterministic ordering" and the alternatives risk JVM-dependent
ordering. The allocation is negligible and the current form is the *correct* choice for a
deterministic cache key.
**Disposition: NON-ISSUE.** No ticket; would trade correctness clarity for unmeasurable savings,
against §"Simplify aggressively"/§"No premature optimization".

### D5 · MISSING-V20 — "missing Flyway migration V20"  *(kimi 2.5, reported Med)*
**Verdict — NON-ISSUE functionally.** Migrations run V1…V19, V21…V29 (V20 absent). Flyway tracks
*applied* versions and tolerates gaps — it runs V19 then V21 with no error. There is no runtime or
ordering consequence.
**Disposition: DOC (optional).** A one-line note in the migration directory (or the design deployment
doc) recording that V20 was intentionally skipped, if indeed it was. No code/migration change.

### D6 · STAGE1-BACKTRACK — "Stage1RegexSet pathological backtracking"  *(kimi 2.6, Med)*
**File:** `Stage1RegexSet.java`.
**Verdict — ALREADY MITIGATED by design; report agrees.** `.{0,40}` + DOTALL could in theory
backtrack, but the documented defense is the Stage-1 wall-clock watchdog (verified elsewhere to fire
on JDK 25 via `InterruptibleCharSequence`). kimi's own text: "No code change required if the
watchdog is deemed sufficient." The 50ms-cap marginal-flake note (project memory
`Stage1WatchdogIT 50ms cap is marginal`) is the only live concern, and it's a test-timing issue,
not a regex defect.
**Disposition: NON-ISSUE / WATCH.** No regex change. Keep the watchdog timeout tuned per profile
(already configurable). Existing memory covers the test flake.

### D7 · TOOL-LEAK — "multi-line TOOL_CALL leaks JSON args to user"  *(mimo H2, reported High)*
**File:** `ChatAgent.java:50-51` (strip pattern), `159-160` (two-pass strip).
**Verdict — MOSTLY FALSE as described; a narrow residual case is real but Low.** mimo's example
(`TOOL_CALL: searchPosts\n{"tags":["crypto"]}`) is **already handled**: the strip runs
`TOOL_CALL_PATTERN` first (line 159), and that pattern is `DOTALL` with `\s+` between name and body,
so it *does* match a well-formed call whose JSON is on the next line and removes it whole. mimo only
looked at the second pattern (`TOOL_CALL_STRIP_PATTERN = "TOOL_CALL:.*"`, no DOTALL) and missed the
DOTALL first pass. The *only* residual leak is a **malformed** tool call whose broken body spills to a
second line: the first (DOTALL) pass needs a closing `}` to match, so it skips it; the second pass
strips `TOOL_CALL: <name>` to end-of-line, leaving the orphaned `{…broken` fragment. That requires the
LLM to emit a malformed multi-line call *and* it only leaks a JSON-ish fragment (nothing executes).
**Why still worth a small fix:** defence-in-depth against internal-protocol text reaching users; the
comment at 48-49 ("tool calls on their own line, so stripping to end-of-line is safe") assumes
single-line.
**Disposition: FIX-LOW (not High).** Make the broad strip `DOTALL`-aware or anchor it so a spilled
malformed body is also removed. This is dissolved entirely if A2 switches to Jackson-based tool
parsing with a balanced-brace extractor (see TOOL-PARSE-NESTED below).

### D8 · TOOL-PARSE-NESTED — "parseToolArgs/TOOL_CALL_PATTERN fails on nested JSON"  *(deepseek COR-1 Low; kimi 1.4 part)*
**File:** `ChatAgent.TOOL_CALL_PATTERN:43-44` (`\{.*?\}` reluctant).
**Verdict — TRUE but out-of-schema; folds into A2.** The reluctant `\{.*?\}` stops at the first `}`,
so a nested args object (`{"params":{"k":"v"}}`) is captured truncated. But the v1 tool schema is
flat-only (system prompt instructs flat JSON; no tool takes a nested arg). So today this is a
latent fragility, not an active bug — it only bites under prompt drift / model change.
**Disposition: FOLD INTO A2.** The recommended A2 fix (Jackson reader + balanced-brace extraction)
removes this and TOOL-LEAK's residual at the same time. No separate ticket; list as a sub-goal of A2
so the fix is chosen to cover it.

### D9 · CIRCUIT-BREAKERS — "no circuit breakers anywhere"  *(mimo L19)*
**Verdict — BY DESIGN.** The D42 failure-counter state machine (`active`→`failed`) is the chosen
degradation mechanism; the project deliberately avoids extra resilience libraries (memory:
"Dependency additions need approval"). "Add Resilience4j/MP-FT" is scope expansion, not a defect.
**Disposition: NON-ISSUE.** No ticket. If a specific path needs breaker semantics, raise it
narrowly with dependency justification, per the dependency-approval rule.

### D10 · BOOTSTRAP-PATH-TRAVERSAL — "BootstrapLoader path traversal"  *(mimo L5, Low)*
**File:** `BootstrapLoader.java:105-106,124`.
**Verdict — WEAK; borderline NON-ISSUE.** The path comes from `infochat.bootstrap.sources-file`,
an **operator-supplied config value**, read at startup. The operator already controls the process
and filesystem; "traversal" to a file they can also just name directly is not a privilege boundary.
Config parsing is a legitimate system boundary, so a normalize+containment check isn't *forbidden*,
but there's no untrusted input here.
**Disposition: FIX-LOW (optional) / NON-ISSUE.** If trivial, `toAbsolutePath().normalize()` for tidy
logs; do not invent a "containment root" the spec doesn't define. Low value.

### D11 · SPI-LIFECYCLE / PROGRESS-NOTIFIER / CONFIG-LIFECYCLE — adapter-SPI shape items  *(mimo L12, L13, L14, L18)*
**Verdict — DESIGN OBSERVATIONS, mostly valid-low, one by-design.**
- **L12 `MessagingAdapter` lacks `start()`/`stop()`** → `MessagingStartup` uses reflective
  `Class.getMethod("start")` + `catch(Throwable)`. Adding `default void start()/stop()` to the SPI is a
  reasonable cleanup. **FIX-LOW.**
- **L13 `ProgressNotifier` has zero production impls.** This may be an intentional SPI seam for later
  adapters (progress is an adapter *capability*). Verify intent before "fixing." **WATCH / verify.**
- **L14 `SimpleXConfig` validated lazily vs `SignalConfig` eager `@Startup`.** Consistency cleanup;
  making SimpleXConfig fail fast at startup is a small improvement. **FIX-LOW.**
- **L18 unbounded result sets (`FetchScheduler`/`DigestScheduler`)** — fine at v1 scale, pagination
  only needed at scale. **WATCH.**
**Disposition: as tagged above; bundle the two FIX-LOW SPI cleanups if touched together.**

### D12 · LOGGING-MIX / HTTP2 / NOSTR-SCHEME — info-level observations  *(kimi 4.1, 4.2, 4.3)*
**Verdict — INFO, no action.**
- **4.1 SLF4J vs JBoss Logging mix** — Quarkus bridges both; cosmetic. NON-ISSUE (would be a
  cross-cutting churn change against §Surgical-changes).
- **4.2 no HTTP/2 pinning** — pinning to HTTP/1.1 in `SsrfGuardedHttpClient` *would* slightly reduce
  multiplexing complexity; defensible but speculative. WATCH (revisit only if H2-specific issues appear).
- **4.3 `NostrStreamSource.parseRelays` defers ws/wss validation to `checkAndPinForWebSocket`** —
  fail-closed already; earlier validation only improves log clarity. FIX-LOW at most.
**Disposition: NON-ISSUE / WATCH (4.2) / optional FIX-LOW (4.3).**

---

# Suggested ticket grouping (for the planning pass)

1. **M1-AUDIT-partitions** *(Critical)* — A1: V30 June+July partitions for all five tables +
   `@Scheduled` monthly partition creator. Check IT suite reds first.
2. **M1-AUDIT-chat-tools** *(High)* — A2 + A3 + D8 + D7-residual: replace `parseToolArgs`/regex with
   Jackson + balanced-brace extraction; coerce/validate arg types → `ValidationError`; DOTALL-safe
   strip. Add array-path unit + integration tests (`recallMemory` end-to-end).
3. **M1-AUDIT-signal-resilience** *(High + Low)* — A4 (handler try/catch) + B9 (hung-process counter).
4. **M1-AUDIT-lock-liveness** *(Medium)* — B1: held-session advisory-lock re-verification + connection
   liveness + keepalive; both services. (Ticket text must target the held session, not "add heartbeat.")
5. **M1-AUDIT-ssrf-hardening** *(Medium/Low)* — B2 (close/reuse HttpClient) + C-IPV6-FORMS + C-SSRF-304 +
   B11 (deadline clamp). Batch; they're all in `SsrfGuardedHttpClient`/`IpBlocklist`.
6. **M1-AUDIT-ws-deglobalize** *(Medium, own milestone candidate)* — B4: per-connection pinned resolver.
   Large; keep separate. Interim: doc + tight `CONNECT_TIMEOUT`.
7. **M1-AUDIT-llm-robustness** *(Medium/Low)* — B3 (body cap) + B12 (Retry-After) + C-ANTHROPIC-PREVIEW.
8. **M1-AUDIT-json-escaper** *(Medium debt)* — C-JSON-DUP: extract `core.log.JsonEscaper`, delegate ~12 sites, fix C0.
9. **M1-AUDIT-user-repo** *(Medium debt, own ticket)* — C-LOOKUP-DUP: `UserRepository`.
10. **M1-AUDIT-notify-reconcile** *(Medium)* — B8: reconcile after reconnect.
11. **M1-AUDIT-low-batch-A** — sanitizer/closed-list (C-CLOSEDLIST-WS + mimo L10), C-BIDI-GAP, C-REDACTOR-SEP.
12. **M1-AUDIT-low-batch-B** — typed SSRF exceptions (C-URLPROBE-MSG + C-LASTADMIN-MSG), C-USERINFO-SRC.
13. **M1-AUDIT-low-batch-C (data)** — C-STAGE2-CHECK, (C-V28-UPDATE doc-only).
14. **M1-AUDIT-low-batch-D (concurrency)** — C-ACQUIRE-INT, C-AUTOPROMOTE-TX, C-READYPROMOTER-TX(verify), B13, B14.
15. **M1-AUDIT-tidy** — C-INFOCHATPROFILE-DUP, SPI lifecycle (D11 L12/L14), C-DIGEST-TZLOG, C-GROUPLOOKUP-THROW,
    C-LEVENSHTEIN, C-ADAPTER-BACKOFF, C-TODOS, pool sizing (B7).
16. **M1-AUDIT-test-debt** — C-TEST-INNERCLASS (test-only).
17. **No ticket (record only):** D1 SETLOCAL-SQLI, D2 UTF8-CAP, D3 PINNED-CLOSE, D4 TREEMAP-ALLOC,
    D5 MISSING-V20 (doc), D6 STAGE1-BACKTRACK, D9 CIRCUIT-BREAKERS, D10 BOOTSTRAP-PATH (optional),
    D12 LOGGING-MIX. CONN-CHURN/B6 + L18 are WATCH.

---

## Auditor calibration notes (for future audit rounds)

- **opus-48** was the most precise on the one genuinely high-impact functional bug (TOOL-ARGS array→CCE);
  its findings were all real and correctly severity-rated.
- **deepseek** found real debt (duplication) but its one Medium *security* finding (UTF8-CAP bypass) is
  arithmetically false — it mis-modeled Java's lone-surrogate encoding (1 byte via `?`, not 3).
- **kimi** raised the most "High" findings, but its top one (SETLOCAL-SQLI) is a non-issue with an
  unworkable proposed fix, and its SIOOBE claim is unreachable. Strong on the parser fragility, weak on
  calibration — treat kimi severities as one notch high until grounded.
- **mimo** had the widest net and caught the genuine Critical (partitions). But its two flagship items
  carried wrong mechanisms (C2 heartbeat "never refreshed" — it is; H2 multi-line leak — already handled
  for well-formed calls). Its conclusions often point at a real area while mis-describing the defect, so
  every mimo finding needs the mechanism re-derived before ticketing.
