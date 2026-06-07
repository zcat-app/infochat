# v2 Cross-Model Audit — Unified Verified Report

Date: 2026-06-07. Synthesizes six audit runs under `deep-code-review/v2/`
(opus-48, opus-47, kimi-k, mimo-v25pro, deepseek, gpt-55) into one
verification-backed report supporting ticket creation.

**Provenance caveats (binding for the per-model evaluation):**
- `gpt-55/HANDOFF.md` is a temp session file — excluded.
- `kimi-k/PROVENANCE.md`: only `kimi-k/04-module-infochat-llm-adapter.md` is
  genuine kimi-k output; the other six files in that folder are **Opus 4.8**
  reusing kimi's prompts. They are evaluated as a second Opus 4.8 run; "kimi-k
  the model" is judged on file 04 alone.
- `opus-48/VERIFIED.md` already confirmed the inbound-dispatch deadlock by
  manual trace; that verification is cited, not repeated.

**Method.** Every finding was checked against the working tree (main,
post-V38). Verdicts: **VALID** (mechanism confirmed in code, with file:line
evidence), **PARTIAL** (one leg confirmed / severity recalibrated /
deliberate-documented), **INVALID** (falsified), **ACCEPTED** (uncontested
low-severity finding where the reporter quoted exact code; not independently
re-executed). Full falsifier evidence per verdict lives in
`.scratch/v2-unified/notes.md` §VERDICTS.

---

## 1. Verified criticals (3)

### C1. Inbound dispatch runs on the transport read thread — reply deadlock
- **Sources:** opus-48 msg F1; kimi-folder msg F1. Confirmed in
  `opus-48/VERIFIED.md` by manual trace (deadlock-until-timeout; 30s SimpleX /
  15s Signal; 3 Signal timeouts → restartHung → SIGKILL cycle).
- Adapters dispatch `InboundHandler.onMessage` synchronously from the thread
  that reads the transport socket; a handler that replies awaits an ack that
  the same (blocked) reader thread must deliver.
- **Category:** correctness/liveness (not just perf). Severity: **critical**.

### C2. Bootstrap-admin `@Startup` bean does not exist — fresh deployment is unusable
- **Sources:** kimi-folder arch F1 (critical); gpt S3 (med — underrated).
- **Verified:** `AdapterRegistry.java:235-237` says it itself: *"The @Startup
  admin-bootstrap bean (deferred per M1-046's notes) will later read the same
  per-adapter property to seed the row"*. Gate 7 validates the property;
  nothing consumes it. Falsifiers checked: the only `INSERT INTO users` in
  provider main are `BanCommandHandler:115` (preban row) and
  `InviteCodeConsumer:102` (invite registration, not admin); no migration
  seeds an admin. Fresh deploy → zero `is_admin=true` rows → no `/invite` →
  no registration → bot unusable; CLAUDE.md §Bootstrap admin & sources promises
  exactly this bean.

### C3. No cross-tick UID dedup — full feed re-ingested every tick
- **Sources:** kimi-folder coll F1 (critical). deepseek/mimo "outbox fine"
  verdicts verified the outbox pattern only, never cross-tick dedup — no
  genuine conflict.
- **Verified chain:** `RssFetcher.java:70` / `BlueskyFetcher.java:74` stamp
  `Instant fetchedAt = Instant.now()` per tick → `PostPersister.java:116`
  `ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING` can
  never fire across ticks (fresh timestamp every tick) → `V7:119-121`
  assigns cross-window dedup to the fetcher ("cross-window dedup … is the
  fetcher's responsibility") and **no fetcher implements it** — no
  If-Modified-Since/ETag, no persisted cursor (the Bluesky/Reddit "cursor"
  hits are within-tick pagination; `NostrDedupFilter` is nostr-stream-only and
  in-memory). `Stage1Worker`'s short-circuit is per-row `stage1_done`, not
  per-uid, so every duplicate row pays Stage 1 + tagger + entity + embedding.
  A stable 20-item feed at 1-min ticks ≈ 28,800 duplicate rows/source/day,
  each with 4 LLM/embedding calls; duplicates also balloon READY rows
  (compounding P-cluster T1 below).

---

## 2. Verdict table (deduplicated, by module)

Severity shown is the **calibrated** severity after verification, not the
reporter's. "Sources" name the first/strongest reporter; full cross-references
in notes.md.

### Architecture / cross-module

| # | Finding | Verdict | Sev | Evidence anchor |
|---|---------|---------|-----|-----------------|
| A1 | Module-DAG guard claimed build-enforced; no enforcer/CI exists | VALID | med | 0 grep hits `enforcer` in any pom; no `.github`; 09-reference.md:41 claims "Enforced by the parent POM and verified in CI" |
| A2 | 09-reference "core: Pure Java; no Quarkus, no I/O" false | VALID | low (doc) | 09-reference.md:30 vs ThrottledAdminNotifier JDBC in core |
| A3 | `new_price_snapshot` producer w/o consumer = drift (opus-47 A-F1 high) | **INVALID** | — | commands.md:277-279 "cache is an optimization; correctness comes from the table read"; schema.md:596-598 same; provider grep = 0 LISTEN, direct SELECT is compliant; M1-161 settled intent |
| A4 | `MessagingAdapter.assertIdentity` zero production callers | VALID | med (simpl) | grep `.assertIdentity(` in */src/main → 0 |
| A5 | ProgressNotifier pipeline: zero impls/consumers | VALID | med (decision) | grep `implements ProgressNotifier` → 0 |
| A6 | Hardware-profile label two sources of truth + divergent defaults | VALID | med | `defaultValue="unknown"` StartupReleaseOnStage2FailureWarn:82 vs `"laptop"` EligiblePostQuery:67, StatusCommandHandler:61 |
| A7 | Design docs document `infochat.profile=` key that code doesn't read | VALID | med | design 07-deployment:103,:135 runbook vs InfochatProfile.java:26-27 ("Why no separate infochat.profile key"); startup crash :95 |
| A8 | TranslationProvider lives in messaging-adapter vs spec llm.md | VALID | low (doc/decision) | file listing: `infochat-messaging-adapter/.../TranslationProvider.java` |
| A9 | Asset-refresh keys duplicated Collector/Provider w/ different defaults | VALID | low | AssetSnapshotFetcher:121-129 (no default) vs AssetSnapshotReader:58-64 (`defaultValue="90"`) |
| A10 | Gate-count comment "six gates" vs 7 | VALID | low | AdapterRegistry:60 vs :227 "Gate 7" |
| A11 | deployment.md "both services run Flyway" vs provider test-scoped | VALID | low (doc) | deployment.md:39 vs provider properties:7 |

### Core / migrations

| # | Finding | Verdict | Sev | Evidence anchor |
|---|---------|---------|-----|-----------------|
| D1 | approve/reject_quarantine SECURITY DEFINER, no REVOKE FROM PUBLIC | VALID | **high (sec)** | REVOKE only in V5:398,:426; none in V21/V25/V32; PG default PUBLIC EXECUTE survives REPLACE |
| D2 | V17 `GRANT … UPDATE ON price_snapshot TO infochat_collector` vs spec INSERT-only | VALID | med (sec) | V17:85; V38 comment reaffirms "INSERT-only (spec: 'no updates')", grant unrevoked |
| D3 | Spec-internal contradiction security.md vs schema.md on price_snapshot writes | VALID | med (spec) | security.md §DB roles "INSERT/UPDATE … including price_snapshot" vs schema.md "INSERT-only; no updates" |
| D4 | price_snapshot: no FK to asset_config; `vs_currency` vs spec `currency` | VALID | low | V17:37 `asset TEXT NOT NULL` no REFERENCES; schema.md "`asset` (FK to asset_config)… `currency`" |
| D5 | infochat_admin paper principal (USAGE only) | VALID | med (decision) | only grant: V2:65 |
| D6 | Last-admin trigger: unconditional `LOCK TABLE users` on every UPDATE row | VALID | **high (perf)** | V35:31/:65 first statement; V5:117 trigger has no WHEN; V15 save_count updates users per /save |
| D7 | chat_memory LRU trigger: COUNT(*) per insert + race past cap | VALID | low-med | V18:35-57 |
| D8 | `idx_chat_message_session_seq` duplicates PK index | VALID | low | V18:69 PK vs :74-75 identical column list |
| D9 | getState logs raw key on SQLException (opus-47 rated high) | VALID | **low** | ThrottledAdminNotifier:309 `key=%s", key` (SQL itself uses safeKey :289) |
| D10 | sanitize misses C0 beyond CR/LF/NUL (ESC → ANSI forgery) | VALID | low | :116 |
| D11 | AuditLogWriter two constructors | VALID | low | :70 + :82 |
| D12 | SafeLog.error drops stack trace | VALID | low | :25 `logger.error(formatSafe(msg, t))`, no throwable arg |
| D13 | V5 verb-catalogue comments stale vs AuditAction | ACCEPTED | low | uncontested |

### SSRF

| # | Finding | Verdict | Sev | Evidence anchor |
|---|---------|---------|-----|-----------------|
| S1 | JVM-wide static pin lock serializes all outbound (incl. WS dials) | VALID | **high (perf)** | PinnedDnsResolver:111 `static final ReentrantLock LOCK` (consensus: opus-48/47, kimi-folder, gpt) |
| S2 | Malformed Location → raw IAE escapes exception contract | VALID | med-low | SsrfGuardedHttpClient:384 `current.resolve(location)`, no RuntimeException catch in hop loop |
| S3 | fec0::/10 (deprecated site-local) not blocked | VALID | low (sec) | IpBlocklist blocks ::1/::, fe80::/10, fc00::/7, ff00::/8, transition forms; fec0 absent |
| S4 | Scheme allowlist case-sensitive (`HTTP://` rejected) vs isCrossOrigin case-folds | VALID | low | :443 `contains(scheme)` raw vs :422 `equalsIgnoreCase`; fail-closed |
| S5 | IPv6 bracket pin-key mismatch | PLAUSIBLE | low | canonicalizeHost:289 keeps brackets; JDK-SPI behavior claim untested; opus-47 itself: security impact zero |
| S6 | Tests assert reworded-able message text; `reason()` never asserted | ACCEPTED | low | uncontested |
| S7 | Stale `rejectsWebsocketSchemeForNow` narrative; WS surface untested module-locally | ACCEPTED | low | uncontested |

### LLM adapter

| # | Finding | Verdict | Sev | Evidence anchor |
|---|---------|---------|-----|-----------------|
| L1 | configFor throws UOE for 5/6 tasks with live production call sites | VALID | **high** | OpenAiCompatibleProvider:157-165 "M1-033 wires SECURITY_JUDGE only"; dead config keys shipped (collector properties:339-356) |
| L2 | %remote-llm declares chat/summarizer provider+base-url+max-tokens but **no model**; AnthropicProvider requires model | VALID | **high** | grep `llm.chat.model\|llm.summarizer.model` → 0; AnthropicProvider:108 `config.getValue(prefix+"model")` |
| L3 | Unknown configured default provider → WARN + entries.get(0) | PARTIAL | low-med | LlmRouter:182-195 — deliberate, documented posture (M1-042 out_of_scope constraint); ticket = revisit, not bug |
| L4 | Retry-After machinery dead; unclamped parse | VALID | med | `retryAfterMs` 20 hits inside adapter, 0 outside |
| L5 | local-only guard never snapshots `infochat.llm.default.provider` | VALID | low (sec) | guard snapshot keys :180-217 — per-task + embeddings only |
| L6 | Entry.supportedLanguages @Nullable contradiction (null check dead) | VALID | low | LlmRouter:163-168 comment claims @Nullable AND null→empty normalization |
| L7 | joinPath/preview triplicated; LlmHttpSupport exists | VALID | low (simpl) | ×3 each (Anthropic:215/222, OpenAiCompatible:257/265, Embedding:222/230) |
| L8 | Anthropic parseContentText reads content[0] only | VALID | low (was med) | AnthropicProvider:191; theoretical for v1 call shapes |
| L9 | CHAT_AGENT keySegment "chat" vs design "chat-agent" | VALID | low (doc) | ModelTask:28 |
| L10 | Provider-name normalization guard-vs-router case handling | PARTIAL | low | guard lowercases (:210); router side not re-traced |
| L11 | "SmallRye logs every @ConfigProperty value at startup" (deepseek) | **INVALID** | — | hallucinated mechanism; Quarkus does not print config values at startup |
| L12 | HttpClient-executor / Thread.sleep carrier-pinning claims (deepseek F3, mimo D6) | **INVALID** | — | vthread sleep unmounts; executor serves async callbacks |
| L13 | §7-defensive trivia, javadoc drift, ticket refs in comments | ACCEPTED | low | uncontested |

### Messaging adapter

| # | Finding | Verdict | Sev | Evidence anchor |
|---|---------|---------|-----|-----------------|
| M1 | Inbound dispatch deadlock | VALID | **critical** | see C1 |
| M2 | Group handlers don't strip bot-mention span; span data not propagated; E2E test pins unstripped text | VALID | **high** | SignalGroupHandler ~:142-167 raw body; SimpleXGroupHandler:79-84 `gc.text()`; SignalGroupEndToEndTest:83 `assertEquals("@bot summarise this", msg.text())` |
| M3 | Signal reader thread dies on structurally-malformed frames | VALID | **high** | codec:154-155 NPE on absent timestamp (`getJsonNumber(...).longValueExact()`), CCE on wrong-typed :101/:133; handleLine catches IAE only :478; readerLoop IOException only :438. opus-48's narrower "DM unguarded" framing imprecise — both onMessage paths ARE guarded (:541-552, :560-569); the typed-accessor phase is not |
| M4 | Supervisor restarts child but nothing reconnects transport | VALID | **high** | `c.connect()` single call site SignalAdapter:229; doRestart() only spawn()s; zero supervisor→adapter callbacks; SimpleX same shape |
| M5 | Signal group outbound rejected PERMANENT while group inbound delivered | VALID | **high** | recipientFromDmScope:270-274 "lands in M1-108" (stale — inbound landed); group replies always fail |
| M6 | Signal handle map leaks fire-once sends; SimpleX LRU is the correct documented shape | VALID (opus-48) / opus-47's inversion **INVALID** | high (perf) | SignalJsonRpcClient:127/:226/:242 (remove only on finalize; wholesale clear :214 on reconnect); SimpleXAdapter:89-105 documented M1-148 trade-off |
| M7 | Concurrent ws.sendText collision (JDK one-outstanding-send) | VALID | high | SimpleXWebSocketClient:186/:238 `var unused = ws.sendText(...)`; async reject unobserved → 30s ack stall; sync ISE mislabeled PERMANENT :207-217 |
| M8 | SimpleX setTyping sends command despite supportsTypingIndicator=false | VALID | med (was high) | SimpleXAdapter:48-53/:77 vs design 06-messaging:87 "No-op for adapters with … = false" |
| M9 | SignalAdapter.start throws IllegalStateException vs SPI MessagingException | VALID | med-low | :211/:217/:233 |
| M10 | maxInflightSends/maxSendsPerSecond advertised, enforced nowhere; bounded-inbound-queue unimplemented | ACCEPTED | med | uncontested (opus-48 M-F5) |
| M11 | minEditInterval ZERO vs design 600ms; maxSendsPerSecond 8 vs design 5 | VALID | low | SimpleXAdapter:78/:75 vs design :482/:479 |
| M12 | SimpleX equal-jitter vs own javadoc + design full-jitter | VALID | low | backoffDelay:341-343 `half + nextLong(half+1)`; Signal IS full-jitter |
| M13 | Identity.resolve stubs UOE citing shipped tickets | VALID | low | SignalIdentity:28-31, SimpleXIdentity:28-31 |
| M14 | Signal codec interpolates raw line into exception messages | PARTIAL (latent) | low | codec:97/:111; current catch logs class name only (D37-compliant) |
| M15 | classifyError substring brittleness, codec/adapter cap mismatch, dormant CDI eager-bean, InMemory items | ACCEPTED | low | uncontested |

### Collector

| # | Finding | Verdict | Sev | Evidence anchor |
|---|---------|---------|-----|-----------------|
| K1 | Cross-tick UID dedup absent | VALID | **critical** | see C3 |
| K2 | PartitionCreator provisions next month only; current-month gap on fresh-deploy/outage; drop-half of Invariant 6 unimplemented | VALID | **high** | PartitionCreator:56 `now().plusMonths(1)` only; V30 = 2026-06/07 one-shot; class is create-only (no DROP logic) |
| K3 | Re-eval re-hide gap: non-BENIGN verdict on released READY post only bumps counter | VALID | **high (sec)** | ReEvaluationJob:124-126; enumerate :294 includes READY rows |
| K4 | Re-eval BENIGN path: no quarantine_review NOTIFY; UNKNOWN-promote skips tagger/entity/embedding + no new_post NOTIFY → tags '{}' forever | VALID | **high** | applyBenignReEval :129-150 no emit (contrast :162); promoteToReady:200 bypasses ReadyPromoter (":22 the only pg_notify('new_post') emit"; TaggerWorker:461 picks RAW only) |
| K5 | Closed-rows NOTIFY re-emit for ALL prior BENIGN_CLOSED rows | VALID | med (perf) | Stage2VerdictHandler:250 SELECT not RETURNING |
| K6 | PerSourceUnknownTracker: no fetched_at partition predicate; global throttle key suppresses 2nd source's disable notice | VALID ×2 | med | :69 WHERE only s.status; :100-102 constant key |
| K7 | @Scheduled pollers default PROCEED, no FOR UPDATE SKIP LOCKED | VALID-PARTIAL | med | 0 grep hits both; overlap-pick severity depends on per-worker claim SQL (not traced) |
| K8 | Bluesky actor not URL-encoded (cursor IS encoded) | VALID | med | BlueskyFetcher:115 |
| K9 | Bluesky Instant.parse throws on malformed indexedAt | VALID | low | BlueskyResponseParser:83 |
| K10 | AdminReviewTtlJob inner-join defeats post_fetched_at denormalization | VALID | low | :81 |
| K11 | Nostr Registrar `new SsrfGuardedHttpClient()` bypasses CDI config | VALID | low-med | NostrStreamSource:303 |
| K12 | sha256Hex duplicated vs core Sha256.hex | VALID | low | BootstrapAssetsLoader:358 |
| K13 | Pagination-cap saturation counter unimplemented (spec commits) | VALID | low | grep "saturat" → no counter |
| K14 | ?::INTERVAL string param | VALID | low | PerSourceUnknownTracker:72 |
| K15 | Zero-width literal chars in Stage1Pipeline (mimo "CRITICAL") | VALID-as-fact | **low** | cat -A confirms raw literals :302-304; readability nit, comment names them |
| K16 | RSS-shaped fetcher ×4 copy-paste, reconstructOriginalBody O(N²), static sanitizer seam, JSON concat, RssFeedParser close swallow, setStage2Verdict double UPDATE | ACCEPTED/PARTIAL | low | uncontested or single-leg-verified |

### Provider

| # | Finding | Verdict | Sev | Evidence anchor |
|---|---------|---------|-----|-----------------|
| P1 | /summary + /retry bypass per-user LLM rate cap; /summary also bypasses InFlightTracker | VALID | **high (sec)** | `tryAcquireLlmRateCap` only InboundRouter:601 (chat); SummaryCommandHandler: SummaryProseGenerator ("one LLM call per cluster"), zero tracker/cap refs |
| P2 | /stop safety net unwired: registerPgBackendPid never called; statement_timeout only in /retry | VALID | **high** | definition-only InFlightTracker:36; applyStatementTimeout sole call RetryCommandHandler:299 |
| P3 | EligiblePostQuery loads ALL eligible rows incl. bodies; caps in Java | VALID | high (perf) | main query no LIMIT, selects body; subList cap :138-147; compounds with C3 duplicates |
| P4 | GroupAutoPromote: spurious PROMOTE_GROUP_ADMIN audit on every admin message; 23505/rollback cycle per member message (mimo's "verified correct" wrong) | VALID | med-high (sec/audit) | InboundRouter:500-514 every group msg; AUTO_PROMOTE_SQL:47-49 WHERE lacks `is_group_admin=false`; 23505 caught :99-105; comment :42-43 wrong about mechanism |
| P5 | Chat seq race: SELECT next_seq w/o FOR UPDATE → PK collision | VALID | med | ChatSessionRepository:64-72 + V18 trigger |
| P6 | ChatMemoryPruner toDays truncation: PT12H → 0 days → deletes everything | VALID | med | :34 |
| P7 | /unban of non-banned user writes false UNBAN audit + restoration disclosure | VALID | med (sec/audit) | no is_banned check :149-180; UPDATE WHERE id=? only |
| P8 | Intent-audit asymmetry: 8 handlers have *_INTENT, Vouch/Promote/Demote/Unban don't | VALID | med (sec/audit) | grep _INTENT file list |
| P9 | searchPosts/getPost label published_at as "ready_at" in JSON | VALID | med | SearchPostsTool:168-169, GetPostTool:61-62 |
| P10 | /save: no scope/subscription filter — bookmark any READY post by uid | VALID | med (spec judgment) | SaveCommandHandler:99-104 |
| P11 | /invite (+ban/unban) DM-only via contactIdOf(scope)→null → misleading error.admin_only in groups | VALID | med | InviteCommandHandler:665-667, :172-178 |
| P12 | Digest: spurious DIGEST_SLOT_MISSED for pre-approval windows; synchronous Event.fire on tick thread | VALID ×2 | med | :190-191 filter lacks approved-at; :70/:132 sync fire |
| P13 | /export concatenates all pages into one OutboundMessage; truncation off-by-one | VALID ×2 | med/low | ExportCommandHandler:102-104/:143-145; ExportDataCollector:191 `>=` with LIMIT==cap |
| P14 | SearchPostsTool acquires 4 pooled connections per call | VALID | med (perf) | :71,:83,:120,:156 |
| P15 | Nostr /add-source always fails (wss → HTTP probe → SCHEME_NOT_ALLOWED → "blocked by SSRF policy") | VALID | med | AddSourceCommandHandler:161-166; KindResolver:112 |
| P16 | QuarantineReviewListener cluster: cursor-advance/notify split-tx (notify lost forever), payload new_status trusted, single throttle key collapses classes + inline UPSERT, no reconnect catch-up, reconciler never notifies + CAS suppresses out-of-order actionable + javadoc falsely claims reconciler calls handleEvent | VALID ×5 | **high (cluster)** | handleEvent:145-151; :184-187 swallow; lookupEventTime :260-273; ADMIN_NOTIFY_KEY :67; UPSERT :92-102; NewPostListener:238 contrast; Reconciler:29-30,:127,:163 |
| P17 | Double admin notification on cap exhaustion (collector notifyOnce + provider NOTIFY-driven) | VALID | med | ReEvaluationJob:165-168 + QuarantineReviewListener isActionable(NEEDS_REVIEW) |
| P18 | ConfirmStateService: no sweep; abandoned entries persist | PARTIAL | low-med (was high) | lazy expiry is spec-sanctioned (javadoc :24-27); growth bounded by admins×scopes |
| P19 | SET LOCAL infochat.actor_id by string concat (12 sites / 8 files) | VALID | med (hygiene; values are internal UUIDs) | grep evidence; set_config(?,?,true) is the parameterized fix |
| P20 | infochat-dev password fallbacks in production-shaped keys | VALID | med (ops) | provider properties:23, collector :16,:22 `${…:infochat-dev}` |
| P21 | No readiness/health implementation despite deployment spec | VALID | med (ops) | grep smallrye-health/HealthCheck/@Readiness → 0 |
| P22 | Misleading fallback replies, /invite list prefix vs revoke full-UUID, /stop slot release race, admin-handler dup, tool LIMIT clamp inconsistency, InboundRouter null guards | ACCEPTED | low | uncontested |

### Cross-cutting

| # | Finding | Verdict | Sev | Evidence |
|---|---------|---------|-----|----------|
| X1 | Hand-written @NonNull persists repo-wide despite D48 null-marked packages | VALID | med (mech sweep) | 171 files / 1006 occurrences in */src/main (189/1070 incl. test) |
| X2 | deepseek summary "zero critical, zero exploitable, excellent shape" | **FALSIFIED** | — | 3 verified criticals, ~14 verified highs |

---

## 3. Proposed tickets

Severity-ordered. `MIG` = next free migration version (worktrees swept
2026-06-07: none in flight; **V39 is free**). `TEST-AUTH` = needs explicit
test-modification authorization in the ticket. Where models conflicted, the
adjudicated direction is stated.

### Critical

**T1. Move inbound dispatch off the transport read thread** (C1/M1)
Files: SignalJsonRpcClient, SimpleXWebSocketClient/Adapter, InMemoryAdapter
(parity), InboundRouter contract note. Fix: hand inbound to a virtual-thread
executor per adapter; keep reader loop pure. TEST-AUTH (E2E tests pin
synchronous delivery). Pairs naturally with T8 (reader hardening) — same
files; consider sequencing T1 → T8.

**T2. Implement the bootstrap-admin @Startup bean** (C2)
Files: new provider startup bean + IT; AdapterRegistry comment update.
Per-adapter `infochat.adapters.<name>.admin` → ensure user row
`is_admin=true`, audit `BOOTSTRAP_ADMIN` per CLAUDE.md. No migration (uses
existing users table).

**T3. Cross-tick UID dedup in the fetch path** (C3/K1)
Files: PostPersister (uid-existence pre-check or `ON CONFLICT (uid,
fetched_at)`-compatible strategy), FetchScheduler, fetchers; decide
mechanism: (a) `WHERE NOT EXISTS (SELECT 1 FROM post WHERE uid=?)` batch
pre-filter, (b) per-source seen-cursor, or (c) conditional GET — (a) is the
deterministic minimum; (b)/(c) are optimizations. Spec already assigns this
to the fetcher (schema.md §UID derivation). TEST-AUTH (persister ITs pin
current conflict target).

### High

**T4. Partition lifecycle: provision current month + implement drop pruner** (K2)
Files: PartitionCreator (+current month on tick AND at startup), new pruner
honoring retention horizon, design 02-schema cadence reconciliation. gpt R3 +
kimi A-F3 fold in.

**T5. quarantine_review listener correctness cluster** (P16, P17)
One ticket: same-transaction cursor+notify (mirror NewPostHandler pattern),
re-read row status instead of trusting payload `new_status`, per-class
throttle keys via shared ThrottledAdminNotifier (kill inline UPSERT),
reconcileAfterReconnect parity with NewPostListener, decouple notify from CAS
advance (or notify-before-advance), fix the javadoc lie, drop the
collector-side duplicate notifyOnce (REEVAL_CAP_EXHAUSTION — spec assigns
admin paging to Provider).

**T6. Re-evaluation verdict handling** (K3, K4, K5)
Re-hide non-BENIGN released posts (READY→QUARANTINED + quarantine row +
NOTIFY); BENIGN close emits quarantine_review NOTIFY; UNKNOWN-promote routes
through the tagger/entity/embedding pipeline (set status RAW + stage flags
rather than direct READY) so new_post NOTIFY and tags happen naturally;
RETURNING-based closed-row NOTIFY. Spec quotes in acceptance items verbatim
(memory: transcribe-spec-promises).

**T7. LLM rate-cap + in-flight coverage for /summary and /retry** (P1)
Wire tryAcquireLlmRateCap + InFlightTracker into SummaryCommandHandler;
rate-cap into RetryCommandHandler. Spec: security.md §Rate limiting names all
three surfaces.

**T8. Signal reader/codec hardening** (M3, M14)
Catch RuntimeException at handleLine boundary (D37 class-name-only logging);
null-tolerant timestamp accessors; stop interpolating raw line into codec
exception messages.

**T9. Transport reconnect after subprocess restart** (M4)
Supervisor restart callback → adapter rebuilds/reconnects JSON-RPC / WS
client; javadocs already promise this ("the adapter rebuilds
SimpleXWebSocketClient after the supervisor reports each restart" —
SimpleXSubprocess:29-30). Both adapters.

**T10. Signal group outbound** (M5)
Implement group-scope send/encode path (encodeSend groupId variant); remove
stale "lands in M1-108" comments.

**T11. Mention stripping + span propagation** (M2)
Strip bot-mention span in both group handlers (propagate span data from
codecs); fix SignalGroupEndToEndTest:83 expected text. TEST-AUTH.

**T12. Send-path bounding: serialize WS sends + bound Signal handle map** (M7, M6)
Serialize ws.sendText per connection (queue or lock + await send future);
add Signal LRU cap mirroring SimpleX MAX_TRACKED_HANDLES (documented
trade-off text exists to copy). Adjudication: opus-48's direction, NOT
opus-47's (SimpleX LRU is the correct shape).

**T13. DB grants migration** (D1, D2, D4) — **MIG V39**
`REVOKE ALL ON FUNCTION approve_quarantine/reject_quarantine FROM PUBLIC` +
explicit EXECUTE grant to provider role; `REVOKE UPDATE ON price_snapshot
FROM infochat_collector`; decide FK on price_snapshot.asset (or spec-amend).
Plus spec edit resolving D3 (schema.md INSERT-only wins; fix security.md
§DB roles wording) and `currency`→`vs_currency` naming.

**T14. Scope the last-admin LOCK TABLE** (D6) — **MIG V40**
Move LOCK inside the admin-relevant IF branches of both V35 functions.

**T15. SSRF per-host pin map** (S1)
Replace the static single pin slot with a refcounted per-host pin map; covers
WebSocket dial path too. High-risk module — `complexity: high`, plan-writer.

**T16. OpenAiCompatibleProvider per-task config + remote-llm completion** (L1, L2, L4, L5)
Dynamic config read for the 5 unwired tasks (AnthropicProvider pattern);
add %remote-llm chat/summarizer `.model` keys; clamp Retry-After parse and
either consume or delete the machinery; snapshot `default.provider` in the
local-only guard.

**T17. /stop wiring + statement timeouts** (P2, P14)
Call registerPgBackendPid from chat/tool execution; applyStatementTimeout on
chat tools + EligiblePostQuery connections; collapse SearchPostsTool to one
connection per call.

**T18. EligiblePostQuery SQL LIMIT + tool result budgets** (P3, gpt P2)
`LIMIT clusterCap` (+ COUNT(*) for the excess note) — do NOT regress the
cap-excess message; byte-budget on getPost/recallMemory tool results.

### Medium

**T19. Audit-correctness sweep** (P4, P7, P8): auto-promote WHERE
`is_group_admin = false` (kills both spurious audit and 23505 cycle) + fix
the wrong SQL comment; /unban no-op guard on is_banned; add *_INTENT rows to
vouch/promote/demote/unban.
**T20. Digest scheduler** (P12): approved-at-aware missed-slot detection;
fireAsync or per-group virtual threads.
**T21. Provider reply correctness** (P9, P11, P13): ready_at→published_at
JSON key (or SELECT ready_at — decide against spec); group-scope admin
commands resolve sender via inboundContext (ApproveGroup pattern); export
sends per-page messages; truncation `> cap` via LIMIT cap+1.
**T22. /save scope decision** (P10): spec judgment — add subscription filter
or spec-amend; opus-47's spec quote must be re-anchored before coding.
**T23. Chat persistence** (P5, P6, D7, D8): seq via
`UPDATE … RETURNING`/FOR UPDATE; pruner `make_interval(secs => …)` or
toMillis; drop duplicate index — **MIG V41** (index drop) — LRU-trigger race
note (accept or FOR UPDATE).
**T24. Ops hardening** (P20, P21): remove infochat-dev fallbacks
(fail-fast on missing env) per deployment docs; smallrye-health readiness
(not-ready at zero adapters per spec).
**T25. Collector fetch hygiene** (K6, K8, K9, K11, K7): URL-encode actor;
tolerant indexedAt parse; fetched_at predicate + per-source notify keys in
PerSourceUnknownTracker; CDI-inject the Registrar's SSRF client;
`concurrentExecution = SKIP` on pollers (cheap, settles K7 without
SKIP-LOCKED work).
**T26. Nostr /add-source probe** (P15): skip HTTP probe for NOSTR kind or
add a WS-shaped probe; fix misleading reply.
**T27. Messaging constants + SPI conformance** (M8, M9, M11, M12, M13):
setTyping no-op when capability false; start() throws MessagingException;
align minEditInterval/maxSendsPerSecond with design (or design-amend after
observation note); full-jitter for SimpleX backoff (or fix javadoc+design);
delete resolve() stubs.
**T28. Rate-limit enforcement decision** (M10, gpt S5): implement
maxInflightSends/maxSendsPerSecond + bounded inbound queue, or spec-amend
the §6.3.7 commitment.

### Low / mechanical / decisions

**T29. @NonNull sweep** (X1): mechanical removal (171 files), NullAway green
build is the gate.
**T30. Enforcer plugin + doc-truth sweep** (A1, A2, A6, A7, A10, A11, L9,
design-05 `remote`): add maven-enforcer banned-deps rule (makes
09-reference.md:41 true); fix "Pure Java" row or move offenders (decide:
opus-48 doc-fix vs mimo relocate — doc-fix is the surgical option); unify
profile-label defaults; fix `infochat.profile` runbook → `quarkus.profile`;
"six gates"→seven; Flyway ownership paragraph; keySegment doc.
**T31. SPI dead-surface decisions** (A4, A5, A8): assertIdentity — implement
per design §6.2 or remove from SPI; ProgressNotifier — implement minimal
typing-pulse pipeline or spec-amend; TranslationProvider placement —
move to llm-adapter or spec-amend (D-level decision, user call).
**T32. SSRF small fixes** (S2, S3, S4, S6, S7): wrap Location resolve →
`REDIRECT_LOCATION_INVALID`; add fec0::/10; case-fold scheme before
allowlist check; reason()-based test assertions. TEST-AUTH.
**T33. Misc lows**: D9-D12 (safeKey in WARN, C0 sanitize, single ctor,
SafeLog rename/fix), K10/K12/K13/K14, L6/L7/L8, P18 sweep (optional), K15
zero-width → \u escapes, P22 items as time permits.

Deliberately **not ticketed**: A3 (INVALID), L11/L12 (INVALID), deepseek's
doc-comment bulk (fold into T30 where overlapping, else drop), gpt T1-T7
test-gap list (fold into the verification.md backlog, not code tickets).

---

## 4. Per-model evaluation

### Opus 4.8 (own run + the six opus-48-authored kimi-folder files)
**The strongest corpus by a wide margin.** Found all 3 criticals (deadlock in
its own run — the only run that did; bootstrap-admin and UID dedup in the
kimi-folder run). Evidence chains are deep and almost always survived
verification: cross-module call-site tracing (configFor's 5 dead tasks),
tests-pinning-wrong-behavior detection (SignalGroupEndToEndTest:83), and the
only self-verification artifact (VERIFIED.md). Adjudications went its way in
all three head-to-head conflicts (price-snapshot channel, handle maps,
sourceId direction). Severity calibration good; its "low" on getState matched
verification where opus-47's "high" didn't. Weaknesses: its own run missed
the rate-cap bypass, the V17 UPDATE grant, and all the partition findings —
the kimi-folder second pass with different prompts caught what the first
missed, which says prompt framing matters more than model ceiling here. Some
findings narrower than the strongest framing (M-F4 vs kimi-folder M-F2 on
reader death).

### Opus 4.7
**Broad and genuinely complementary, but missed both headline runtime
defects** (dispatch deadlock, configFor UOE) — disqualifying misses for a
"senior review" framing since both sit on the main execution path. Unique
verified contributions are real: V17 UPDATE grant, setTyping-vs-capability,
ready_at mislabel, contactIdOf DM-trap, ChatMemoryPruner truncation,
closed-rows NOTIFY re-emit, Bluesky encoding, listener split-transaction
analysis (the best NOTIFY-contract work in the corpus). Systematic severity
inflation (@NonNull as high ×2, getState as high, ConfirmStateService as
high) and one outright backwards adjudication (called Signal's handle
handling "the correct shape" — verification showed Signal is the leaking
side and SimpleX's LRU is the documented fix). Also wrongly rated the
price-snapshot channel "high drift" against explicit spec text.

### kimi-k (genuine output = file 04 only)
**Shallow.** Six findings on the llm-adapter module, mostly severity-inflated
trivia (@NonNull on one method as "high"); missed every major issue
opus-48's own report found in the same module with the same access (configFor
UOE, router fallback, Retry-After dead code, joinPath triplication). One
partially-useful observation (Optional api-key shape). Not suitable as a
solo reviewer at this depth; possibly useful as a style-nit pass.

### MiMo v2.5 Pro
**Honest but shallow-tracing.** Good epistemics on paper (withdraws its own
TOCTOU finding mid-report, marks explicit "no finding" confirmations that
provided useful corroboration), but verification exposed the tracing gap:
resolved the sourceId contradiction backwards (never read FetchScheduler),
rated configFor "medium, not a bug today" (never traced the 5 live call
sites), called joinPath duplication a conscious trade-off (LlmHttpSupport
exists), and made one flatly false technical claim (vthread Thread.sleep
pinning carriers). Severity inversion is its signature failure: zero-width
chars "CRITICAL SECURITY" vs configFor "medium". Valid unique finds are
mostly constants drift (minEditInterval/maxSendsPerSecond vs design,
equal-jitter, Registrar CDI bypass) — real but low-tier.

### DeepSeek
**Weakest corpus.** Self-admitted sampled coverage produced mostly
doc/comment suggestions inflated to "high" (a step-numbering comment as the
top provider finding). Two hallucinated/false technical claims (SmallRye
config-value dump at startup; HttpClient-executor carrier pinning) and one
likely-wrong dependency claim (Nostr third-party verifier). Its headline
conclusion — "zero critical, zero exploitable security vulnerabilities,
codebase in excellent shape" — is falsified by three verified criticals.
Salvageable uniques: asset-refresh duplicate keys w/ different defaults,
gate-count comment (shared w/ gpt). Honest about its sampling, which is the
one redeeming epistemic trait.

### GPT-5.5
**Sharp ops/config specialist, no module depth.** Found a verified angle
nobody else had: the deployment seam (infochat-dev password fallbacks,
missing readiness, %remote-llm missing model keys, Flyway ownership drift,
partition current-month gap independently of kimi-folder). Best epistemics
in the corpus: explicitly re-verified and retracted two of its own earlier
claims (SM1, SM4), hedged its unverified query-plan finding. Severity
calibration good (its "med" on bootstrap-admin was an underrate, but the
direction of its hedging is consistently honest). Missed the entire
adapter-internals layer (deadlock, mention strip, reader death) and the
re-eval/NOTIFY mechanics — it reviewed the system's edges, not its core.

---

## 5. Cross-model stats

| Model | Raw findings | Verified VALID/PARTIAL | INVALID/false | Unique VALID (no other run) | Criticals found |
|---|---|---|---|---|---|
| Opus 4.8 (own run) | 36 | ~33 | 0 | deadlock, V35 lock, re-hide gap, /stop, configFor trace, mention strip | 1/3 |
| Opus 4.8 (kimi-folder run) | ~55 | ~50 | 0 | bootstrap-admin, UID dedup, partition gap, reconnect gap, group send, sendText, auto-promote audit, /unban, intent asymmetry, Nostr probe, export concat | 2/3 (+1 shared) |
| Opus 4.7 | ~46 | ~38 | 1 (price-snapshot drift) + 1 backwards (handle map) | V17 grant, setTyping, ready_at, contactIdOf, pruner, closed-rows re-emit, Bluesky encode, split-tx | 0/3 |
| kimi-k (genuine, 1 module) | 6 | ~3 (trivia) | 0 | none | 0/1 in-module |
| mimo-v25pro | ~30 | ~20 | 2 (vthread sleep, sourceId direction) | minEdit/maxSends drift, equal-jitter, Registrar bypass | 0/3 |
| deepseek | ~24 | ~12 (mostly doc-tier) | 3 (SmallRye dump, executor pinning, Nostr dep) + falsified summary | asset-refresh dup keys | 0/3 |
| gpt-55 | ~25 | ~21 | 0 | passwords, readiness, remote-llm model gap, Flyway docs | 0/3 (S3 found but under-rated) |

**Overlap observations.** The three criticals had near-zero overlap: each was
found by exactly one run (deadlock: opus-48 own; bootstrap-admin + UID dedup:
opus-48/kimi-prompts). The consensus findings (pin lock, @NonNull, configFor)
were all real but none critical. Conclusion for future audits: run the same
strong model with **two differently-framed prompt sets** (the opus-48 own-run
vs kimi-prompt-run delta exceeded any cross-model delta), and keep one
ops/config-framed pass (gpt's niche was untouched by all module-level runs).
