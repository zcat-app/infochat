# Opus-4.7 deep-code-review handout

**Source reports:** `deep-code-review/opus-47/` (00-summary + 01..07 module reports)
**Date evaluated:** 2026-06-02
**Purpose:** Catalogue every finding from the opus-47 audit with an opinion on whether it should become a ticket. Each entry has enough context to feed `/m1-tick` ticket creation directly.

The audit raised **47 numbered findings** across architecture + six modules, grouped by the synthesizer into 8 SECURITY, 5 PERFORMANCE, 4 SIMPLIFICATION, and 28 MAINTAINABILITY-RULES-DRIFT items, plus 5 cross-cutting themes (CT1–CT5). Each entry below carries the same key facts the reviewer collected; the **Verdict** line is the recommendation for whether to spend a ticket on it.

Verdict legend:
- **RESOLVE** — real defect or rules violation; spend a ticket.
- **RESOLVE-BUNDLED** — small, mechanical, low-risk; bundle with a sibling fix in one ticket.
- **DEFER** — real but the cost/value or timing argues for waiting until a triggering event.
- **DROP** — finding doesn't survive falsification, or is a doc-only nit better fixed inline next time the file is touched.

---

## Top-priority (synthesizer-ranked)

### TP1 — Anthropic auth/version headers use the wrong names (CRITICAL, SECURITY)

- **Sources:** 04#F1, 04#F2
- **Locations:**
  - `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:139,142`
  - `infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java:133-134,154-157`
- **What's wrong:** Code emits `x-anthropic-version` + `anthropic-api-key`; the Anthropic Messages API documents `anthropic-version` (no `x-`) + `x-api-key`. The test was written against the broken names, locking the bug in — a textbook §8 test-integrity violation ("test modified to match new (wrong) behaviour rather than code fixed to match test").
- **Why resolve:** Every production call to Anthropic returns 401 today; the test green-check is misleading. Real Anthropic integration is dead without this. Also fixes the handoff doc `docs/plan/m1/drafts/handoff-tier3-D-anthropic-llm.md` which propagated the same error.
- **Why it might not be an issue:** None — the Anthropic public reference is unambiguous; the SDK and OpenRouter passthrough both use `x-api-key` + `anthropic-version`. Falsifier check: confirmed against `https://docs.anthropic.com/en/api/messages`. Cannot be dropped.
- **Scope hints:** Fix code + test + class javadoc + handoff doc in ONE diff (per test-integrity rule). ~4 lines of code, ~6 lines of test assertions, plus javadoc.
- **Verdict:** **RESOLVE** — single ticket, bundles 04#F1 and 04#F2 in the same diff per §8.

### TP2 — Single reply-target makes multi-adapter outbound route to the wrong adapter (CRITICAL, SECURITY)

- **Sources:** 07#F1
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:284,290-292,604-628`, `AdapterRegistry.java:254-266`
- **What's wrong:** `private volatile MessagingAdapter replyTarget` is overwritten once per activated adapter in `AdapterRegistry.start()`; last activation wins. In SimpleX+Signal deployments, every reply ships through the alphabetically-last adapter regardless of the inbound's origin. Violates D46 cross-adapter isolation (`docs/spec/messaging.md` §Per-adapter trust level, `docs/spec/security.md` §Per-adapter admin threat profile).
- **Why resolve:** This is the exact multi-adapter shape M1 commits to ship; the bug is silent (no test exercises >1 adapter today). The fix mirrors the existing `DigestWorker.findAdapter` / `ApproveGroupCommandHandler.findAdapter` pattern — it removes a divergent shape rather than adding a new one. The `volatile` write race is real even with one adapter (every activation rewrites the field).
- **Why it might not be an issue:** The class javadoc claims "acceptable MVP limitation because no MVP user-facing flow runs more than one adapter simultaneously" — but the M1-109 production-shape IT and D46 contradict that claim. Cannot be dropped.
- **Scope hints:** Drop `setReplyTarget` from the registry; thread `adapterName` (already in scope at every call site as the second parameter to `onMessage`) through `sendReply`; add a `findAdapter(String)` helper to `InboundRouter` matching the DigestWorker shape. **Security_relevant: true** — should run /redteam after.
- **Verdict:** **RESOLVE** — high-priority security ticket.

### TP3 — Application DB connections use the superuser, not per-service roles (CRITICAL, SECURITY)

- **Sources:** 01#F1
- **Locations:**
  - `infochat-collector/src/main/resources/application.properties:7-13`
  - `infochat-provider/src/main/resources/application.properties:12-14`
  - `infochat-core/src/main/resources/db/migration/V2__roles.sql:32-39`
- **What's wrong:** Both services connect as the `infochat` superuser. The `infochat_collector` / `infochat_provider` / `infochat_admin` GRANT/REVOKE matrix is documented but **never enforced at runtime** — no `SET ROLE`, no per-role JDBC user. Every defense-in-depth layer the spec attaches to the role split (`audit_log_view`, quarantine SECURITY DEFINER carve-outs, Invariant 4/10) is decorative.
- **Why resolve:** Every additional code path that lands accumulates more cleanup the eventual fix has to sweep. Closing this now hardens the IT suite too — privilege-mismatched DML becomes a build failure rather than a silent runtime gap. Spec is explicit: `security.md` §DB roles commits to the three-role least-privilege model with specific exploit paths the role split blocks ("SQL-injection bug in the Provider cannot delete posts, mutate price snapshots, alter quarantine entries, read unredacted audit rows, or read raw quarantine originals").
- **Why it might not be an issue:** The "until the named-datasource wiring ticket lands" note in `V2__roles.sql` has been load-bearing across many tickets; one could argue this is "scheduled work, not drift." Falsifier check: the work has not landed and there is no open ticket for it in `docs/plan/m1/STATUS.md`. Cannot be dropped.
- **Scope hints:** Larger ticket — files_budget ~8: new migration `V30__role_login.sql` (`ALTER ROLE … WITH LOGIN`); Quarkus named-datasource config in both services (Flyway runs as owner, runtime as role); Testcontainers init to provision role passwords; sweep production code paths against GRANT matrix; operator runbook update in `docs/design/07-deployment.md`. Anticipate **IT-suite breakage as a feature** — every privilege mismatch the role split is meant to catch will surface as a build failure. **Security_relevant: true** + /redteam after.
- **Verdict:** **RESOLVE** — should be the next critical ticket; risk grows linearly with every new handler that assumes superuser.

### TP4 — `infochat.reeval.*` config keys declared only in test resources (CRITICAL, MAINTAINABILITY-RULES-DRIFT)

- **Sources:** 06#F1
- **Locations:**
  - Missing from `infochat-collector/src/main/resources/application.properties`
  - Consumed by `ReEvaluationJob.java:74-86`, `PerSourceUnknownTracker.java:41-50`, `AdminReviewTtlJob.java:51-57`
- **What's wrong:** Nine `infochat.reeval.*` keys (infra-failure-cap, unknown-cap, needs-review-depth-threshold, poll-interval, unknown-rate-threshold, unknown-rate-window, unknown-tracker-poll-interval, admin-review-ttl, ttl-poll-interval) exist only in `src/test/resources/application.properties`. Quarkus raises `NoSuchElementException` at bean-init when a `@ConfigProperty` has no default and no profile value. **Collector startup fails in every operator profile** (laptop, vps, pi, remote-llm); only `test` boots.
- **Why resolve:** Hard production startup failure — collector cannot run anywhere except inside QuarkusTest. The entire re-evaluation policy in `security.md` §Re-evaluation job is dead until the keys land.
- **Why it might not be an issue:** None. Falsifier check: grep `infochat-collector/src/main/resources/application.properties` for `infochat.reeval` — zero hits. Cannot be dropped.
- **Scope hints:** Add base values + per-profile overrides per `docs/design/04-security.md`. Suggested baseline from the report (cross-check values):
  - `poll-interval=5m`, `unknown-tracker-poll-interval=15m`, `ttl-poll-interval=30m`
  - `infra-failure-cap=5`, `unknown-cap=3`, `needs-review-depth-threshold=100`
  - `unknown-rate-threshold=0.5`, `unknown-rate-window=PT1H`, `admin-review-ttl=PT72H`
  - Consider adding a CI check that every `@ConfigProperty(name = "infochat.*")` has a base declaration (out of scope for the immediate fix).
- **Verdict:** **RESOLVE** — mechanical and unblocks production startup; could land in the same ticket as the per-profile override table if values are finalized.

### TP5 — Hardcoded `/zcash` and `/monero` handlers break `bootstrap-assets.json` extensibility (CRITICAL, MAINTAINABILITY-RULES-DRIFT)

- **Sources:** 07#F2
- **Locations:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetCommandRouter.java:24-55`
- **What's wrong:** Only the two literal asset names are wired as `CommandHandler` beans. A third asset added to `bootstrap-assets.json` (e.g., `litecoin`) is visible to `/help` (which iterates `AssetRegistry.getEnabledAssets()`) but invisible to the slash dispatcher in `InboundRouter.handleSlash`. Worse, the probation gate's `AssetCommandFamilyOracle.isAssetCommand("litecoin")` returns `true`, so a probation user passes the gate then receives "Unknown command" — contradicts §Slow-start tier promise.
- **Why resolve:** Permanently constrains the operator-config-driven asset-extensibility commitment in `docs/spec/commands.md` §Asset commands. Probation-tier UX leaks. Bug is silent until production deploys a third asset.
- **Why it might not be an issue:** One could argue "v1 only ships zcash and monero, so the extensibility is theoretical." Falsifier check: spec says "future asset-specific verbs ... can land without a new top-level command per verb" — that's a contract, not a footnote. Cannot be dropped.
- **Scope hints:** Option A (recommended in report, ~10 lines): add a fallback branch in `InboundRouter.handleSlash` that consults `AssetCommandFamilyOracle` after the per-name CommandHandler scan, dispatches to `assetHandler.handle(commandName, …)`, and delete `AssetCommandRouter` entirely. Option B (CDI producer per asset) is heavier and doesn't survive runtime `AssetRegistry.refresh()`.
- **Verdict:** **RESOLVE** — Option A is the cleaner fix; the dispatcher gains one branch and a whole file disappears.

---

## SECURITY (8 total, 3 in top-priority above)

### S1 — `/stop` is a no-op in group scope and uses the wrong scope key in DM (HIGH)

- **Source:** 07#F3
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java:62-95, 97-115`
- **What's wrong:** `resolveUserId` returns empty for any non-DM scope, so a group user with in-flight chat-mode work cannot cancel it. `scopeKind="dm"` and `scopeId=userId` are hardcoded — load-bearing if the DM scope key ever moves. Violates spec §Chat mode's per-(user, scope) cancellation commitment (D35).
- **Why resolve:** The cost-shedding lever the spec relies on is silently absent in groups. LLM tokens burn while the user thinks they cancelled.
- **Why it might not be an issue:** None — the bug is concrete and the scope-key shape is documented. Cannot be dropped.
- **Scope hints:** Mirror `InboundRouter.resolveChatScopeId`, extract a small helper or read through `GroupRepository`. Add `scopeKind = scope instanceof ScopeRef.Dm ? "dm" : "group"` and `scopeId = switch(scope) { Dm → userId; Group g → lookupGroupId(...).orElseThrow(...); }`. Adds one DB lookup per group `/stop` — `/stop` is low-frequency.
- **Verdict:** **RESOLVE** — single focused ticket.

### S2 — extraHeaders leak across cross-origin redirects (MEDIUM)

- **Source:** 03#F3
- **Location:** `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:319-335`
- **What's wrong:** The redirect loop re-applies caller-supplied `extraHeaders` to every hop without checking same-origin. Today's only consumer sends `Range: bytes=0-0` (benign), but the API shape silently leaks Authorization/Cookie/Proxy-Authorization to attacker-controlled 302 targets the moment any caller adds them.
- **Why resolve:** Engineering rule §"Defense in depth at boundaries": the SSRF wrapper IS the boundary. Browsers, curl, and major HTTP clients all strip on cross-origin precisely because the original authorization was for the original origin only. Hardening before a credentialed caller arrives is cheaper than after.
- **Why it might not be an issue:** No current caller passes credential headers, so the vector is latent. One could argue "fix when the first authenticated probe lands." Falsifier: that's a "fix it later" gamble against a known foot-gun in a security boundary; the spec's `/add-source` URL-probe family is explicitly the type of caller that will eventually want auth.
- **Scope hints:** Recommended fix (Option A): define `CROSS_ORIGIN_STRIPPABLE = Set.of("authorization", "cookie", "proxy-authorization")`; compute same-origin from (canonical-host, port, scheme) and filter the map per hop. ~25 lines. **Security_relevant: true**.
- **Verdict:** **RESOLVE** — small, mechanical, hardens a known boundary.

### S3 — Hand-rolled JSON arg parser in ChatAgent silently mangles non-trivial tool payloads (MEDIUM)

- **Source:** 07#F8
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java:251-305`
- **What's wrong:** `parseToolArgs` + `splitTopLevel` is a regex-and-substring mash that does not handle JSON arrays, doesn't un-escape `\"` / `\\` / `\n`, and is confused by trailing whitespace after quoted strings. Concrete consequences:
  - `searchPosts` with a `tags` list never works through the chat agent.
  - `recallMemory` and `listSaves` similarly broken.
  - Crafted LLM output of the form `{"uid": "x\", "tags": ["malicious"]}` produces undefined parsing.
- **Why resolve:** The chat-mode value proposition silently breaks for every tool that takes a list arg. Also undermines the spec's typed-tool-input boundary in §Prompt-injection defenses. Jackson is already loaded for `bootstrap-assets.json`, so this is replacement, not addition.
- **Why it might not be an issue:** Falsifier: the bug is real and removes ~50 lines of fragile code; the fix produces the typed `List<String>` the dispatcher's length caps expect. Cannot be dropped.
- **Scope hints:** Replace with `ObjectMapper.readTree` + a small `toJavaValue` recursive helper; on parse failure return empty map (dispatcher returns `ValidationError` which the LLM loop already handles). ~30 lines net. **Security_relevant: true**.
- **Verdict:** **RESOLVE** — removes code, fixes functionality, hardens parsing boundary.

### S4 — `/promote` reads actor row without `FOR UPDATE` (MEDIUM, TOCTOU)

- **Source:** 07#F6
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/PromoteCommandHandler.java:90-93, 158-169`
- **What's wrong:** Other admin handlers (GrantAdmin, ApproveGroup, RejectGroup, RevokeAdmin) all switched their actor-gate SELECT to `FOR UPDATE` per the M1-046 redteam PERM-ESCAL closure. `/promote` did not. A concurrent `/revoke-admin` against the caller can commit between the SELECT and the demote+promote UPDATEs.
- **Why resolve:** Identical TOCTOU window to the one M1-046 closed for the sibling handlers; the closure is incomplete without this handler. The fix is a one-line SQL change.
- **Why it might not be an issue:** The race window is tiny in real deployments. Falsifier: the spec and prior redteam closure committed the project to "no admin handler reads actor without FOR UPDATE"; leaving one outlier is the kind of incomplete-fix pattern the rules call out.
- **Scope hints:** One-line change to SELECT, no schema change. Verify the transaction wrapper is already `autoCommit=false` (per F6 lines 86-150 — yes). Add a regression test mirroring the GrantAdmin TOCTOU test.
- **Verdict:** **RESOLVE-BUNDLED** — could bundle with S5 or stand alone; it's a one-liner with a test.

### S5 — `/save` accepts unbounded personal-tag strings and counts (MEDIUM)

- **Source:** 07#F10
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java:305-321, 265-284`
- **What's wrong:** Read path has length cap (via `ChatToolDispatcher.validateInputLengths`); write path does not. `/save uid-X -t aaa,bbb,...` accepts arbitrary count + arbitrary per-tag length (bounded only by `infochat.router.max-inbound-body-bytes` at 65 KB). Downstream:
  - `/saved` interpolates personal_tags into reply via `String.join` — outbound message bypasses chat-mode body cap.
  - `listSaves` tool serializes into LLM prompt and blows the budget.
  - `saved_post` rows consume `save_count` slot but waste 100× storage budget.
- **Why resolve:** Symmetric obligation to the read-side length cap the spec already commits to. "Free-form" was never "unbounded."
- **Why it might not be an issue:** Bounded by the existing `max-inbound-body-bytes`. One could argue that's the real cap. Falsifier: 65 KB per `/save` × no row count cap = many-MB `personal_tags` arrays still possible; the spec's `listSaves` cap exists precisely because per-tag length and count both matter independently.
- **Scope hints:** Add `infochat.save.personal-tag-max-length` (default 64) and `infochat.save.personal-tag-max-count` (default 20); enforce in `SaveCommandHandler.handle()` with two new bundle keys for the error messages. ~15 lines.
- **Verdict:** **RESOLVE** — mechanical, closes a known asymmetry. **Security_relevant: true**.

### S6 — `SearchPostsTool.Duration.parse` throws past the tool dispatcher's catch (MEDIUM)

- **Source:** 07#F11
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java:46-48`; `ChatToolDispatcher.java:137-145`
- **What's wrong:** `Duration.parse` throws `DateTimeParseException` (a `RuntimeException`, not `IllegalArgumentException`). Dispatcher only catches `IllegalArgumentException`, so an LLM emitting `"window": "7 days"` (intuitive) crashes the chat-agent loop into `INTERNAL_ERROR_REPLY` instead of the typed ValidationError the spec promises. Same shape for `ClassCastException` if LLM emits `"window": 7` (integer). `searchPosts` is the heaviest-used tool.
- **Why resolve:** Typed validation surface is a spec commitment in §Prompt-injection defenses. The fix preserves the LLM-feedback loop (LLM sees `ValidationError` → reformulates) instead of corrupting conversation state.
- **Why it might not be an issue:** Falsifier: LLMs do emit non-ISO durations; this is a frequent failure mode. Cannot be dropped.
- **Scope hints:** Add per-arg try/catch in `SearchPostsTool.execute` translating to `IllegalArgumentException`. Pattern should be uniform across tools — consider a small `ToolArgs` helper (`requireString`, `requireDuration`, `requireList`) at the same time (~30 lines).
- **Verdict:** **RESOLVE** — small ticket; consider expanding to a `ToolArgs` helper across `RecallMemoryTool`, `ListSavesTool` as well.

### S7 — Upstream-supplied pagination cursors concatenated into URLs without encoding (MEDIUM)

- **Source:** 06#F6
- **Locations:**
  - `infochat-collector/.../bluesky/BlueskyFetcher.java:110-117`
  - `infochat-collector/.../reddit/RedditFetcher.java:108-114`
- **What's wrong:** `cursor` (Bluesky) and `after` (Reddit) come from upstream JSON — untrusted per `security.md` §Threat model — and are concatenated into the next URL with `?after=` / `&cursor=` directly. A cursor containing `&actor=evil` or `#` injects/truncates the URL. SSRF guard checks host+IP, not path/query.
- **Why resolve:** Bounded blast radius (still same upstream host → at worst "fetch a different actor's feed"), but trust-boundary breakage is the smell. `CoingeckoSnapshotSource:103` already uses `URLEncoder.encode(...)`, so the codebase has the right pattern.
- **Why it might not be an issue:** Falsifier: the upstream API likely 400s on malformed cursors anyway, but defense in depth at trust boundaries is rule-pinned (`security.md`).
- **Scope hints:** `URLEncoder.encode(value, StandardCharsets.UTF_8)` for every query-parameter value (including identifier values, which come from operator-trusted bootstrap but should still be encoded for hardening). Reddit identifier is a full URL with `.json` appended — use `URI.resolve("?after=...")` for that case.
- **Verdict:** **RESOLVE** — small, mechanical hardening. **Security_relevant: true**.

---

## PERFORMANCE (5 total)

### P1 — SimpleXAdapter handle table grows unbounded (HIGH, DOS / memory leak)

- **Source:** 05#F1
- **Location:** `infochat-messaging-adapter/.../simplex/SimpleXAdapter.java:88-91, 255-256, 282-284`
- **What's wrong:** Every successful `send()` adds an entry to `handles`; `finalize()` flips `finalized.put(opaque, TRUE)` but never removes either entry. Both maps grow forever for the adapter's lifetime. Same pattern was caught and fixed in `SignalJsonRpcClient` by M1-107 redteam (test `handleEvictedOnFinalize`); the fix never propagated to SimpleX.
- **Why resolve:** Provider lifetime = process restart; 600K outbound/week (1 msg/sec) = 600K handle entries pinned. A hostile/busy correspondent grows heap until OOM with no inbound cap able to defend.
- **Why it might not be an issue:** None. Falsifier: identical M1-107 finding shipped a Signal-side regression test pinning the fix; not applying it to SimpleX is incomplete-fix drift. Cannot be dropped.
- **Scope hints:** Apply the SignalJsonRpcClient pattern verbatim: `handles.remove(handle.opaqueValue())` in `finalize()`; collapse "unknown handle" and "already finalized" into one PERMANENT path in `requireKnownAndOpen`; delete the `finalized` map entirely. Mirror the `handleEvictedOnFinalize` test from SignalJsonRpcClientTest into a SimpleX-side test.
- **Verdict:** **RESOLVE** — mirror the existing fix; CT5 (cross-adapter contract test suite) is the bigger lever to prevent the next instance of this drift.

### P2 — Connection-per-step churn in inbound path (HIGH)

- **Source:** 07#F5
- **Location:** `infochat-provider/.../InboundRouter.java:300-553`
- **What's wrong:** One inbound burns 6-10 short-lived JDBC connections (lookupUser, BanCheck.isBanned, lookupGroupId, GroupAutoPromote internals, ensureGroupMembership, ProbationCheck, summaryAnchorRepository.clear, plus handler). Step 4 `BanCheck.isBanned` re-reads `is_banned` that step 1 `lookupUser` already returned. Class javadoc claims "exactly one users-row SELECT per inbound" — false from step 4 onward.
- **Why resolve:** Spec-committed shape (multi-adapter, multi-group, rate-cap absorbing bursts) makes the pool the next bottleneck after the LLM. The TOCTOU argument the javadoc gives for the second SELECT does not require a fresh connection — same-connection re-read + `FOR UPDATE` at the mutating step closes the race more cheaply.
- **Why it might not be an issue:** MVP single user makes this invisible; one could argue it's "wait for load." Falsifier: M1's spec-committed adapter and group surfaces all amplify; the threading change is mechanical now and increasingly disruptive later.
- **Scope hints:** Larger ticket — open one `Connection` at the top of `onMessage`, thread it through every helper as a parameter, commit/close once. Helpers need Connection-accepting overloads. Mutating handlers still open their own transactional connection (correct per Invariant 7). High files_budget (~10+); could be split into "intake-path Connection threading" and "BanCheck inlined into lookupUser" sub-tickets.
- **Verdict:** **RESOLVE** — but plan-writer should outline before commit; this is the highest-risk refactor in the audit because it touches every inbound code path.

### P3 — `LlmOutputSanitizer` compiles 26 patterns per call (MEDIUM)

- **Source:** 07#F9
- **Location:** `infochat-provider/.../llm/LlmOutputSanitizer.java:187-209`
- **What's wrong:** `CLOSED_LIST` is `static final`; each `applyClosedListStripWithMatches` call re-compiles 26 `Pattern` objects. Sanitizer is called on every chat reply, summary, digest, retry, and translation.
- **Why resolve:** Textbook regex-compile-in-hot-path; the patterns never change. Already cached for `MARKDOWN_LINK`; the closed-list path is the lone outlier.
- **Why it might not be an issue:** None — strict improvement.
- **Scope hints:** `static final List<Pattern> CLOSED_LIST_PATTERNS = CLOSED_LIST.stream().map(...).toList()` and index into both in lockstep. ~10-line diff. No behavioural change.
- **Verdict:** **RESOLVE-BUNDLED** — tiny; pair with another sanitizer-area fix.

### P4 — `latestPublishedAtEpochSeconds` lacks supporting composite index (MEDIUM)

- **Source:** 06#F5
- **Location:** `infochat-collector/.../nostr/NostrStreamSource.java:440`
- **What's wrong:** `SELECT MAX(published_at) FROM post WHERE source_id = ?` on every Nostr reconnect. Existing indexes (`idx_post_source(source_id, fetched_at DESC)`, `idx_post_published(published_at DESC)`) don't cover it — either scans the source's slice or the global descending stream filtered by source.
- **Why resolve:** Reconnect frequency is not operator-controlled (DNS-rebind, transient drops). A flapping relay produces many of these queries against a growing `post` table.
- **Why it might not be an issue:** Low Nostr volume at MVP makes this invisible. Falsifier: the cost is one extra index (write amplification on `post` insert); `post` is not the throughput-gating hot path. The READ benefit dominates as Nostr sources accumulate posts. Could defer until a real Nostr deployment is exercised, but that's "fix when it bites" — at worst a one-migration cleanup.
- **Scope hints:** Option A (recommended): new migration `CREATE INDEX idx_post_source_published ON post(source_id, published_at DESC);`. Option B (in-memory cache via `volatile long` updated per event) trades index write-cost for startup-rebuild query — both acceptable.
- **Verdict:** **RESOLVE-BUNDLED** — bundle with another small migration; the index alone is a 1-file ticket.

### P5 — Per-call ExecutorService spawn in `readBounded` (MEDIUM)

- **Source:** 03#F4
- **Location:** `infochat-ssrf/.../SsrfGuardedHttpClient.java:420-424, 471`
- **What's wrong:** `Executors.newSingleThreadExecutor(...)` per `get()` call to enforce the per-read watchdog. Uses platform threads via the explicit `new Thread(r, ...)` factory, even though the project targets JDK 25 + virtual threads.
- **Why resolve:** SSRF wrapper is the gate for every outbound HTTP fetch in both services; on a busy Collector this is one OS-thread allocation per feed fetch. Virtual threads are essentially free (~hundreds of nanoseconds, no OS-thread).
- **Why it might not be an issue:** v1 RSS cadence makes the per-call cost invisible. Falsifier: the rule precedent matters — the project's stack note commits to virtual threads + blocking style; using platform threads here drifts.
- **Scope hints:** Option A (recommended): static `READER_FACTORY = Thread.ofVirtual().name(...).factory()`; per-call `newThread().start()` + `CompletableFuture.get(timeout)`. ~20 lines net. Cancellation semantics same as today (`InputStream.read` may or may not honour interrupt — true today as well).
- **Verdict:** **RESOLVE-BUNDLED** — small, no behavioural change; could pair with another SSRF-module fix.

---

## SIMPLIFICATION (4 total)

### Si1 — Dead semaphores in TaggerWorker and EntityExtractorWorker (MEDIUM)

- **Source:** 06#F7
- **Locations:**
  - `infochat-collector/.../tagger/TaggerWorker.java:160-205`
  - `infochat-collector/.../entity/EntityExtractorWorker.java:153-193`
- **What's wrong:** `Semaphore(maxConcurrency)` is acquired/released around each `processOne` call, but `enumeratePending(maxConcurrency)` caps the per-tick batch and the for-loop runs serially on the scheduler thread. The semaphore never sees a count above 1.
- **Why resolve:** Misleads readers — the class-level javadoc cites bounded concurrency, but the bound comes from the sequential loop, not the semaphore. A future refactor that introduces parallelism would silently rely on the wrong mechanism.
- **Why it might not be an issue:** Functionally fine today. Falsifier: the javadoc-vs-implementation drift is the rule violation; the choice is also "should we make this parallel?"
- **Scope hints:** Option A (recommended): drop the semaphore, document that batch-per-tick + serial-loop is the concurrency bound. Option B: actually make it parallel via virtual-thread fan-out gated by the semaphore (matches docs but changes throughput characteristics).
- **Verdict:** **RESOLVE-BUNDLED** — Option A is mechanical; if the team wants throughput improvement, that's a separate, larger ticket. Bundle Option A with another collector cleanup.

### Si2 — Duplicate per-handler JSON quoting helpers (LOW, cross-cutting)

- **Source:** 07#F12 (see also CT4)
- **Locations:** BanCommandHandler, UnbanCommandHandler, GrantAdminCommandHandler, ApproveGroupCommandHandler, SourceUpsertService, GroupApprovalService, LlmOutputSanitizer — five drifting variants.
- **What's wrong:** Five distinct hand-written JSON-escape helpers across the module, each with a slightly different escape set. `GroupApprovalService.escapeControlChars` silently omits the `\"` escape. ~150 duplicated lines.
- **Why resolve:** §Simplify aggressively names this anti-pattern; the audit-log contract is "valid JSON in `details_json`" and one site already drifts off-contract.
- **Why it might not be an issue:** None — strictly better.
- **Scope hints:** Extract `JsonText.quote(String)` to `infochat-core` or `infochat-provider/.../audit`. Replace every call site. Canonical escape set per the report.
- **Verdict:** **RESOLVE** — bundle with CT4 (Hex/SHA helper extraction) into one "shared text utilities" ticket; ~150 lines net deletion.

### Si3 — `IngestSpisLoadTest` checks only what compiler guarantees (LOW)

- **Source:** 02#F5
- **Location:** `infochat-core/src/test/java/app/zcat/infochat/core/ingest/IngestSpisLoadTest.java:20-39`
- **What's wrong:** Three `Class.forName` + `assertTrue(type.isInterface() / isRecord())` checks ratify what the source declaration already commits to in Java syntax. If `Fetcher` ever converts from `interface` to `abstract class`, every caller fails at compile, not this test.
- **Why resolve:** Removes a placeholder that future readers might mistake for a real load surface.
- **Why it might not be an issue:** Test isn't actively harmful; one could argue "doesn't deduct points." Falsifier: simplification rule applies — every dead test is one more thing readers must mentally discard.
- **Scope hints:** Delete the file. ~20 lines. The class's own javadoc anticipates a real load-test in M1-007 territory, which lives in the SPI bundle module if it materializes.
- **Verdict:** **RESOLVE-BUNDLED** — pair with the SHA helper extraction; or leave for the next ticket that touches the file.

### Si4 — Two distinct SHA-256-to-hex helpers (LOW)

- **Source:** 06#F10 (see also CT4)
- **Locations:**
  - `infochat-collector/.../bootstrap/BootstrapLoader.java:285-289` (uses `String.format("%02x", ...)`)
  - `infochat-collector/.../outbox/PostPersister.java:168-177` (uses `HexFormat.of()`)
- **What's wrong:** Same primitive, two implementations; one uses pre-JDK-17 idiom.
- **Why resolve:** Single source of truth for a 5-line cryptographic primitive; the next caller produces a third copy.
- **Why it might not be an issue:** Functionally equivalent. Falsifier: the spec's "simplify aggressively" stance applies; extract to `infochat-core` since both Collector and Provider may need it.
- **Scope hints:** `Hex.sha256(byte[])` helper in `infochat-core`; replace both call sites with the `HexFormat` form. Trivial ticket alone — bundle with Si2.
- **Verdict:** **RESOLVE-BUNDLED** with Si2 (CT4 bundle).

### Si5 — Unused `Optional` import in AnthropicProvider (LOW)

- **Source:** 04#F9
- **Location:** `infochat-llm-adapter/.../AnthropicProvider.java:24`
- **What's wrong:** `Optional` is imported but not used as a type.
- **Why resolve:** Reduces noise; the file was added by M1-085/M1-120 so the "surgical changes" rule allows cleanup of the file's own unused imports.
- **Why it might not be an issue:** Trivial. Falsifier: bundle with TP1 (the header fix touches the same file) and it's free.
- **Scope hints:** One-line deletion.
- **Verdict:** **RESOLVE-BUNDLED** — fold into TP1's diff; no ticket of its own.

---

## MAINTAINABILITY-RULES-DRIFT (28 total; 3 in top-priority above)

### M1 — `approve_quarantine` / `reject_quarantine` do not fire `quarantine_review` NOTIFY (HIGH)

- **Source:** 01#F2 (see CT1)
- **Location:** `infochat-core/.../V25__quarantine_procedure_remediation.sql:46-65, 67-104`
- **What's wrong:** Admin-driven APPROVED / REJECTED transitions never fire `pg_notify('quarantine_review', ...)`. Consumer's cursor stays pinned; the spec's "v2 can attach behaviour to a transition without a NOTIFY change" rationale is broken; reconciler reads more rows than necessary on restart forever.
- **Why resolve:** Spec contract (`architecture.md` §Inter-service communication) is unambiguous about all five transitions firing.
- **Why it might not be an issue:** No actionable behaviour breaks today (admin transitions are non-actionable; consumer cursor handles missing NOTIFYs harmlessly). Falsifier: the contract IS the value — closing this prevents the next v2 ticket from discovering the gap the hard way.
- **Scope hints:** New migration `V30__quarantine_review_notify.sql` with `CREATE OR REPLACE FUNCTION` for both procedures. Two added `PERFORM pg_notify('quarantine_review', jsonb_build_object(...)::text)` lines. Cleanest: bundle with F2/F4 of M11 to also switch the inline string-concat NOTIFY to `jsonb_build_object`.
- **Verdict:** **RESOLVE** — bundle with M11 (CT1 unification).

### M2 — Embedding provider silently breaks SPI size-equals-input contract (HIGH)

- **Source:** 04#F3
- **Location:** `infochat-llm-adapter/.../OpenAiCompatibleEmbeddingProvider.java:162-201`
- **What's wrong:** SPI javadoc commits to "size equals texts.size()"; impl returns a wrong-size list with only a WARN log. Caller that trusts the SPI silently mis-attributes vectors to wrong posts.
- **Why resolve:** Spec at `docs/spec/llm.md` §Embedding pipeline mandates "one-failure-fails-batch retry" — detection at the SPI boundary, not at every caller. Current shape pushes the detection into every caller.
- **Why it might not be an issue:** Bug is silent (mis-attribution degrades retrieval but doesn't poison data; post bodies are public). Falsifier: the spec mandates SPI-side detection, and silent mis-attribution undermines vector-search trust over time.
- **Scope hints:** Change WARN to `throw new EmbeddingCallFailedException(...)`. EmbeddingWorker already retries-once-then-releases. ~5 lines.
- **Verdict:** **RESOLVE** — small ticket.

### M3 — `EmbeddingResult` exposes mutable array via record (HIGH)

- **Source:** 04#F4
- **Location:** `infochat-llm-adapter/.../EmbeddingResult.java:14`
- **What's wrong:** `record EmbeddingResult(float[] vector)` — auto-generated `equals`/`hashCode` use array reference equality (any `assertEquals` between two embeddings always fails); accessor returns live array reference (callers can mutate).
- **Why resolve:** Textbook record-with-array hazard; both invariants a value type promises are broken. The wrapper's own javadoc says "a bare float[] would also meet the spec."
- **Why it might not be an issue:** Falsifier: bug is latent until a test does `assertEquals` or a caller mutates. Either occurs eventually; cleaner to fix now.
- **Scope hints:** Option A (recommended): drop the wrapper, expose `List<float[]>` from the SPI. Option B: keep wrapper with defensive `clone()` on construction + accessor + `Arrays.equals/hashCode` overrides (~400 KB extra alloc per batch at typical D=768, N=64).
- **Verdict:** **RESOLVE** — Option A; mechanical SPI signature change.

### M4 — Kind-6 repost edge `to_post` is never resolvable (HIGH)

- **Source:** 06#F3
- **Locations:** Kind6Handler.java:142-153, PostPersister.java:108-119, GetReferencesTool.java:67-80
- **What's wrong:** Kind6Handler stores `to_post = nameUUIDFromBytes(originalEventId)`. PostPersister persists with `id = gen_random_uuid()`. Provider's join `pr.to_post = post.id` cannot match. **The entire M1-100 user-visible payoff is absent.**
- **Why resolve:** Spec is explicit (`architecture.md` §Source identity, Kind-6 cross-source linking) about resolution-to-post-UID. Half-implementation is worse than nothing — edges are written, indexed, queried, all returning zero rows.
- **Why it might not be an issue:** Falsifier: M1-100 shipped without the resolution path; this is a structural bug, not a missing feature.
- **Scope hints:** Option A (recommended, closest to spec): new column `to_upstream_identifier TEXT` on `post_reference` (NULL when unresolved); nightly resolver `UPDATE post_reference SET to_post = p.id FROM post p WHERE p.upstream_identifier = pr.to_upstream_identifier AND pr.to_post IS NULL`; GetReferencesTool naturally filters NULL via JOIN. Option B (2-line change but changes `post.id` semantics): use `nameUUIDFromBytes` deterministically for Nostr posts in PostPersister. Option C (spec amendment): defer kind-6 resolution to v2.
- **Verdict:** **RESOLVE** — Option A. Plan-writer outline recommended; ~5 files (migration, Kind6Handler, PostPersister, GetReferencesTool, new resolver job + tests).

### M5 — Stage 2 `emitQuarantineNotifyForPendingRows` re-fires PENDING (HIGH)

- **Source:** 06#F4 (see CT1)
- **Location:** `Stage2VerdictHandler.java:269-281`; `QuarantineDao.java:66-90`
- **What's wrong:** Stage 1 inserts PENDING quarantine row with NO NOTIFY; Stage 2 INJECTION/MALWARE/UNKNOWN verdict THEN fires PENDING NOTIFY. Spec says "PENDING fires on insert." A Stage1→Stage2-BENIGN path never fires PENDING at all (row is already BENIGN_CLOSED by the time the SELECT runs). Stage 2 path also re-NOTIFYs for already-NOTIFIED rows (wasted bandwidth).
- **Why resolve:** Spec text "PENDING insert" is unambiguous. Moves emission to the right boundary; consumer cursor handles it cleanly.
- **Why it might not be an issue:** No observable behaviour breaks (cursor compare-and-swap suppresses duplicates). Falsifier: the contract drift is real and the move is mechanical.
- **Scope hints:** Modify `QuarantineDao.insert` to `RETURNING id` and emit PENDING NOTIFY in the same transaction; drop `emitQuarantineNotifyForPendingRows` from Stage 2. Bundle with M1 (V25 NOTIFY remediation) for one coordinated NOTIFY-contract ticket.
- **Verdict:** **RESOLVE** — bundle with M1 + M11 into one "quarantine NOTIFY contract closure" ticket.

### M6 — `/help` ignores spec-promised per-tier filtering (HIGH)

- **Source:** 07#F4
- **Location:** `infochat-provider/.../HelpCommandHandler.java:46-74`
- **What's wrong:** Handler is hard-coded to 3 lines (`help`, `add-source`, `summary`) plus the asset list. Spec §Discovery requires per-tier filtering (probation, non-admin, non-group-admin), per-scope header (DM vs group), probation-tier footer, and bundle-composition contract ("CI's bundle-completeness check asserts every command has a help-line key in every shipped bundle").
- **Why resolve:** Load-bearing UX promise that's silently absent. Bundle-completeness CI rule the spec leans on for adding a third language cannot fire today.
- **Why it might not be an issue:** Falsifier: spec is explicit about data shape AND filtering rules; current handler is a placeholder.
- **Scope hints:** Drive from `List<HelpEntry>(commandName, bundleKey, permissionTier)`; add all catalogued commands' bundle keys to `en.properties` + `cs.properties`; filter by caller tier inside handler. Several new bundle keys. Plan-writer outline recommended.
- **Verdict:** **RESOLVE** — medium-large ticket; full M1 catalogue surface.

### M7 — IpBlocklist M1-025 compatibility-shim constructor (HIGH)

- **Source:** 03#F1
- **Location:** `infochat-ssrf/.../IpBlocklist.java:78-87`
- **What's wrong:** `IpBlocklist(Set<InetAddress>)` constructor is self-identified as "preserved as an overload so M1-025 tests pass unchanged." §7 prohibits backwards-compatibility shims in greenfield M1.
- **Why resolve:** Shim hides constructor-overload asymmetry (M1-025 callers get frozen snapshot; M1-026 callers get per-call freshness). §7 is verbatim about exactly this pattern.
- **Why it might not be an issue:** Two-line shim that doesn't hurt anyone today. Falsifier: the rule is explicit; the change "can simply be made" (the two test call sites are in the same module).
- **Scope hints:** Delete the `Set<InetAddress>` overload; change the two `IpBlocklistTest` call sites (lines 150, 160) to wrap with `Supplier`. ~6 lines total.
- **Verdict:** **RESOLVE-BUNDLED** — bundle with another SSRF-module finding.

### M8 — IPv6 URL-literal hosts cannot pass `canonicalizeHost` (MEDIUM)

- **Source:** 03#F2
- **Location:** `SsrfGuardedHttpClient.java:269-279`
- **What's wrong:** `URI.getHost()` for IPv6 literals returns `[::1]`-with-brackets; `IDN.toASCII` rejects (`[`, `]`, `:` not valid label chars). Wrapper cannot dial ANY IPv6-literal URL — neither legit public IPv6 nor the v6 forms `[::1]`, `[fe80::1]`, `[::ffff:127.0.0.1]` that IpBlocklist correctly blocks. The IPv6 portion of IpBlocklist (lines 166-188 + IPv4-mapped delegation) is unreachable.
- **Why resolve:** Spec contract (`security.md` §SSRF) says "DNS-resolved IPs are checked against ... IPv6 equivalents." Wrapper's actual behaviour for `[::1]` is "invalid host" rather than "blocked IP" — right outcome by accident for malicious targets, wrong for legitimate ones. No test exercises this — silent regression.
- **Why it might not be an issue:** v1 may not exercise IPv6 destinations. Falsifier: bootstrap-sources.json or `/add-source` can include IPv6-literal URLs (legit RSS feeds increasingly do); current code rejects them all.
- **Scope hints:** Strip brackets and bypass IDN for IPv6 literals; preserve lowercase + trailing-dot strip. Add tests that construct `HttpServer` on `[::1]:0` and assert "blocked IP" not "invalid host" (skip cleanly if no IPv6 stack). ~20 lines.
- **Verdict:** **RESOLVE** — closes a spec-level gap with a deterministic small fix.

### M9 — `MessagingAdapter.onMembershipEvent` confused SPI method (HIGH)

- **Source:** 05#F2 (see CT5)
- **Location:** `infochat-messaging-adapter/.../MessagingAdapter.java:163-174`
- **What's wrong:** `onMembershipEvent` sits on the SPI as if Provider-facing, but it's the adapter's self-dispatch. SignalAdapter bypasses it (calls `handler.onEvent(event)` directly); InMemoryAdapter routes through it. Two incompatible dispatch shapes for the same SPI.
- **Why resolve:** Leaky abstraction; future adapter author picking the InMemoryAdapter shape gets silent no-op drops. SPI surface should be unambiguous.
- **Why it might not be an issue:** Two adapters work today. Falsifier: the SPI surface IS the contract for future adapters; ambiguity now means breakage later.
- **Scope hints:** Remove `onMembershipEvent` from the SPI; InMemoryAdapter's `removeMember`/`removeBot` call the stored `membershipHandler.onEvent(event)` directly (Signal pattern). Three files (SPI + two adapters), one test file.
- **Verdict:** **RESOLVE** — bundle with CT5 (cross-adapter contract test suite) if that ticket lands.

### M10 — Adapter codec/encoder raise wrong exception types (MEDIUM)

- **Source:** 05#F4 (see CT5)
- **Locations:** `SimpleXAdapter.java:178-183`, `SimpleXMessageCodec.java:226-232`
- **What's wrong:** SPI declares `throws MessagingException` (categorised TRANSIENT/PERMANENT); codec raises `IllegalStateException`/`IllegalArgumentException`, escaping the two-category retry model. Provider's retry layer reads `category()` to decide retry/abort — uncategorised exceptions bypass that branch.
- **Why resolve:** Spec at §Failure handling pins the categorised contract; uncategorised throwables break Provider's retry policy in unpredictable ways.
- **Why it might not be an issue:** Falsifier: the codec's defensive validators ARE the right place (design §6.4.4 mandates encode-time defense in depth); the *exception type* is the bug.
- **Scope hints:** Switch validator throws to `MessagingException(PERMANENT, ...)`. Encode methods declare `throws MessagingException`. Test `encodeRejectsContactIdWithCommandInjectionChars` swaps the assertion class.
- **Verdict:** **RESOLVE** — small mechanical ticket.

### M11 — "Adapter not connected" classifies TRANSIENT in Signal, PERMANENT in SimpleX (MEDIUM)

- **Source:** 05#F5 (see CT5)
- **Locations:** `SignalAdapter.java:330-338`, `SimpleXAdapter.java:348-355`
- **What's wrong:** Same semantic state, opposite retry posture. Provider burns 3 retries against a closed Signal client; aborts immediately for SimpleX.
- **Why resolve:** Spec's "uniform across adapters" framing for retry is undermined. Pick one.
- **Why it might not be an issue:** Each choice is defensible in isolation. Falsifier: cross-adapter contract test would catch it; the asymmetry IS the bug.
- **Scope hints:** Switch Signal to PERMANENT (matches `messaging.md` §Failure handling default and SimpleX's existing posture). ~3 lines + test.
- **Verdict:** **RESOLVE-BUNDLED** — bundle with M9 + CT5.

### M12 — `MessagingException` constructors lack `@NonNull`/`@Nullable` (MEDIUM)

- **Source:** 05#F6 (see CT2)
- **Location:** `MessagingException.java:22-30`
- **What's wrong:** Three reference-type parameters across two public constructors, no annotations. §7a requires `@NonNull`/`@Nullable` on every public method's reference-type parameters.
- **Why resolve:** Every other SPI record in the module (Identity, InboundMessage, OutboundMessage, MessageHandle, CapabilityFlags, ScopeRef, MembershipEvent) is annotated; this is the lone outlier.
- **Why it might not be an issue:** None — strict improvement.
- **Scope hints:** 3 annotations + 3 import lines. Bundle with CT2.
- **Verdict:** **RESOLVE-BUNDLED** — single CT2 "JSpecify annotation sweep" ticket.

### M13 — Defensive `catch (RuntimeException)` in `AssetSnapshotFetcher` (MEDIUM)

- **Source:** 06#F8
- **Location:** `AssetSnapshotFetcher.java:188-198`
- **What's wrong:** Catches `RuntimeException` between two internal classes (within-module first-party beans). §7 forbids defensive code at internal trust boundaries.
- **Why resolve:** Misattributes SPI-contract bugs as D42 upstream-fetch failures; operator visibility distorted.
- **Why it might not be an issue:** Falsifier: the comment self-identifies the violation ("Defensive guard"); legitimate boundary is the outer `runHostTick` loop iterator, not the per-call site.
- **Scope hints:** Move catch to outer loop in `runHostTick`; log with distinct error class so it doesn't feed D42 ladder. ~15 lines refactor.
- **Verdict:** **RESOLVE** — small focused ticket.

### M14 — Defensive null checks in `LlmRouter` and `OpenAiCompatibleProvider` (MEDIUM)

- **Source:** 04#F6 (see CT2)
- **Locations:** `LlmRouter.java:144-146,182`, `OpenAiCompatibleProvider.java`
- **What's wrong:** `LlmRouter.forTask` checks `task == null` behind `@NonNull` annotation; `OpenAiCompatibleProvider.doCall` checks `cfg.apiKey() != null` after `Optional.orElse("")` (which cannot return null). Dead branches.
- **Why resolve:** §7 prohibits defensive checks behind already-non-null contracts.
- **Why it might not be an issue:** None.
- **Scope hints:** Delete the dead checks. ~4 lines.
- **Verdict:** **RESOLVE-BUNDLED** — fold into CT2 annotation sweep.

### M15 — Hidden "null" string sentinel in `MicroProfileConfigReader.get` (MEDIUM)

- **Source:** 04#F5
- **Location:** `LlmRouter.java:399`
- **What's wrong:** `s.toLowerCase().equals("null") → ""` undocumented; no spec/design reference; format-fragile (matches exact 4-char string only).
- **Why resolve:** §"Comment important code" — hidden invariant must carry the WHY. Either delete (no purpose) or document + bound (YAML-null hypothesis is plausible per SmallRye Config behaviour).
- **Why it might not be an issue:** Bug-free today. Falsifier: future maintainer cannot tell whether to delete or extend.
- **Scope hints:** Either delete or replace with `equalsIgnoreCase("null")` plus a WHY comment naming the YAML-null hypothesis. ~6 lines.
- **Verdict:** **RESOLVE-BUNDLED** — fold into the LlmRouter cleanup ticket (CT2/M14).

### M16 — `LlmRouter` constructors missing JSpecify annotations (MEDIUM)

- **Source:** 04#F7 (see CT2)
- **Location:** `LlmRouter.java:109,134`
- **What's wrong:** Both public constructors take reference-type parameters with no `@NonNull`/`@Nullable`. §7a violation. Pattern is correctly applied on `Entry`, `ConfigReader.get`, `LlmProvider.generate` — constructors are the oversight.
- **Why resolve:** Static contract should match runtime check; CDI container never passes null to `@Inject @NonNull`.
- **Scope hints:** Add 4 annotations.
- **Verdict:** **RESOLVE-BUNDLED** — CT2 sweep.

### M17 — Router tightly coupled to concrete provider impls via `instanceof` (MEDIUM)

- **Source:** 04#F8
- **Location:** `LlmRouter.java:298-315`
- **What's wrong:** `providerName(LlmProvider p)` `instanceof` cascade naming every concrete impl. Every new provider edits the router. Also a fragile CDI-proxy-naming walk-up heuristic.
- **Why resolve:** SPI is supposed to be the contract; router is currently part of it. Three concrete drawbacks: router can't be packaged independently of impls; authoritative name registry split across two files; test stubs and real impls take different code paths.
- **Why it might not be an issue:** Works today with 2 impls. Falsifier: M1-007b's SPI freeze is the only argument against an SPI default method; an annotation-based variant is the same shape with no SPI change.
- **Scope hints:** Option A (recommended): default method `String name()` on `LlmProvider` SPI. Option B (no SPI change): `@ProviderName("anthropic")` annotation read via reflection at router startup. ~15 lines either way.
- **Verdict:** **RESOLVE** — pick Option B if SPI freeze is binding; Option A otherwise.

### M18 — SECURITY DEFINER procedures drop denormalized actor columns (MEDIUM)

- **Source:** 02#F1
- **Location:** `V24__identity_audit_remediation.sql:44-53`; `V25__quarantine_procedure_remediation.sql:58-60, 100-101`
- **What's wrong:** V24 `delete_preban_user` dropped the V5 join+denormalization of `actor_contact_id`/`actor_adapter`. V25 `approve_quarantine`/`reject_quarantine` inherited the omission. Three procedure-written audit rows now have NULL where `redact_contact_id(actor_contact_id) AS actor_contact_id` expects values — silently violates `schema.md` §Identity and access audit-log invariant.
- **Why resolve:** Spec commitment to "redaction-free historical lookup" depends on the denormalization. The "derivable from actor_user_id" V24 comment contradicts the spec rationale.
- **Why it might not be an issue:** Bug is silent (returns NULL rather than wrong value). Falsifier: the spec wording is explicit; `/audit` Provider surface displays inconsistent rows.
- **Scope hints:** Re-add the JOIN and the two columns. Actor existence is already checked before the INSERT so no defensive-code worry. ~15 lines across V24/V25 successor migration.
- **Verdict:** **RESOLVE** — bundle with M1/M5 (single quarantine-procedure migration).

### M19 — SimpleX `supportsTypingIndicator=true` contradicts design §6.4.2 (MEDIUM)

- **Source:** 05#F3 (see CT5)
- **Location:** `SimpleXAdapter.java:288-298`
- **What's wrong:** Capability declared `true`; design §6.4.2 declares `false`. Class javadoc rationalises with "send and hope" — exactly the speculative-SPI pattern §7 rejects.
- **Why resolve:** Provider uses the capability flag to decide whether to invoke `setTyping`. With `true`, Provider sends typing pulses for every progress-notifier session that silently fail on the wire.
- **Why it might not be an issue:** Falsifier: the right answer depends on whether `apiSetContactTyping` exists on simplex-chat (M1-105 question). Recommend B (flip to `false`) until verified — cheaper to flip back if M1-105 confirms.
- **Scope hints:** Option B: flip capability to `false`; remove `encodeTypingCommand`; `setTyping` becomes no-op; add unit test pinning the false declaration. If M1-105 verifies, update design and flip back.
- **Verdict:** **RESOLVE** — flip-to-false now (conservative), revisit during M1-105.

### M20 — `MembershipEventHandler` writes audit AFTER mutation, swallows failures (MEDIUM)

- **Source:** 07#F7
- **Location:** `MembershipEventHandler.java:105-127`
- **What's wrong:** Inverts Invariant 7 (audit-before-effect). On audit-write failure, swallows SQLException and continues. Every other privileged handler in the module uses pre-write-audit-then-mutate-then-commit.
- **Why resolve:** Invariant 7 is the audit log's reason to exist; per-handler exceptions dilute the trust signal. The `was_group_admin` flag is critical for `/unban` group-admin restoration disclosure path.
- **Why it might not be an issue:** Comment argues "adapter event already happened, can't roll it back." Falsifier: the DB mutation IS under our control and can be in the same transaction as the audit; rollback releases both intended mutations together.
- **Scope hints:** Wrap mutation + audit in one transaction, mirror BanCommandHandler shape. `GroupMembershipRepository.isGroupAdmin` and `markMemberRemoved` need Connection-accepting overloads. ~40 lines refactor, two handlers (UserLeft + BotRemoved).
- **Verdict:** **RESOLVE** — focused security/correctness ticket.

### M21 — NOTIFY payload string-concat in `QuarantineNotifyEmitter` does not escape (MEDIUM)

- **Source:** 01#F3 (see CT1)
- **Location:** `QuarantineNotifyEmitter.java:39-43`
- **What's wrong:** Emitter takes `String targetKind`/`newStatus`; trusts caller to pass enum-shaped literal. Not currently exploitable (all callers pass literals), but `PriceSnapshotStore` already escapes — codebase is internally inconsistent. Also the regex-based consumer parser would mis-handle any `"` in payload.
- **Why resolve:** Tighten SPI shape to closed enums; emitter becomes the canonical owner of the channel's payload shape. Engineering-rule §7a positive: make contract explicit in types instead of paranoia at the seam.
- **Why it might not be an issue:** Today's callers all pass literals; current behaviour is correct. Falsifier: the future-caller hazard is concrete; the parser-side robustness improves too.
- **Scope hints:** Two enums (`QuarantineNotifyKind`, `QuarantineNotifyStatus`); change emitter signature; update 4 call sites; optionally swap regex parser for Jackson on consumer side. Bundle with M1+M5.
- **Verdict:** **RESOLVE** — bundle into the "quarantine NOTIFY contract closure" ticket (CT1).

### M22 — V21/V25 `pg_notify` payload built by raw `||` concatenation (LOW)

- **Source:** 02#F4 (see CT1)
- **Locations:** `V21__quarantine_admin.sql:74-75`; `V25__quarantine_procedure_remediation.sql:62-63`
- **What's wrong:** Raw concat of `v_ready_at::TEXT` and `v_post_id::TEXT`. Safe today only because Postgres's timestamptz/uuid text forms happen not to contain `"` or `\`. `approve_quarantine`'s own audit-log INSERT already uses `jsonb_build_object` one statement earlier.
- **Why resolve:** Structural safety; future timestamp-format change or new payload key won't silently break the consumer parse.
- **Why it might not be an issue:** Functionally fine. Falsifier: cost is zero; benefit is real.
- **Scope hints:** Replace concat with `jsonb_build_object(...)::text` in both migrations' successor migration. Receiver-side parser must accept ISO 8601 with `T` separator (`Instant.parse` handles it).
- **Verdict:** **RESOLVE-BUNDLED** — bundle into the CT1 quarantine NOTIFY closure ticket; pair with receiver-side parser update.

### M23 — V7 comment references non-existent `infochat_listen` role (LOW)

- **Source:** 02#F2 (see CT3)
- **Location:** `V7__joins_post.sql:212-214`; consistent reference at `docs/design/02-schema.md:26`
- **What's wrong:** V2 creates three roles; `infochat_listen` exists nowhere. Spec `security.md` §DB roles only names the three. Design note + migration comment both assert a phantom role.
- **Why resolve:** Spec/design/code disagree — the rule "spec wins on conflict" picks Option B (delete the orphan reference).
- **Why it might not be an issue:** Comment-only drift; backs no code path. Falsifier: still a real "stale comment misleads next reader" hazard per CT3.
- **Scope hints:** Delete V7:212-214 + the `infochat_listen` bullet in `docs/design/02-schema.md`. ~5 lines.
- **Verdict:** **RESOLVE-BUNDLED** — bundle with CT3 (stale-comment sweep) or delete inline next time the file is touched.

### M24 — V16 comment hard-codes pre-relocation wiring (LOW)

- **Source:** 02#F3 (see CT3)
- **Location:** `V16__admin_notification_state.sql:67-73`
- **What's wrong:** Comment claims "notifier lives in infochat-collector" and "Provider is read-only," contradicted by M1-082 relocation of `ThrottledAdminNotifier` into infochat-core and four Provider write call sites. V21 extends the GRANT matrix; V16 comment is silent on it.
- **Why resolve:** Reader auditing role matrix by reading V16 alone sees a self-consistent statement that the next migration silently contradicts.
- **Why it might not be an issue:** Comment-only. Falsifier: CT3 pattern.
- **Scope hints:** Rewrite the 7-line comment block per the report. No code change.
- **Verdict:** **RESOLVE-BUNDLED** — CT3 sweep or inline.

### M25 — `SsrfGuardedHttpClient` class javadoc claims ws/wss rejected (LOW)

- **Source:** 03#F5 (see CT3)
- **Location:** `SsrfGuardedHttpClient.java:41-47`
- **What's wrong:** Top-of-class bullet claims ws/wss "deliberately rejected" while the class now exposes `checkAndPinForWebSocket` / `resolveForWebSocket` (M1-101 added them). Two-surface design is acknowledged at lines 111-118; top-of-class bullet is doubly stale.
- **Why resolve:** Reader following javadoc top-down learns ws/wss unsupported, then encounters contradicting methods.
- **Why it might not be an issue:** Comment-only. Falsifier: CT3.
- **Scope hints:** Rewrite bullet per the report; drops ticket-id reference per `feedback_no_plan_refs_in_docs.md` extension.
- **Verdict:** **RESOLVE-BUNDLED** — CT3.

### M26 — `SsrfGuardedHttpClient` indistinct constructor error messages (LOW)

- **Source:** 03#F6
- **Location:** `SsrfGuardedHttpClient.java:194-205`
- **What's wrong:** `connectTimeout` and `requestTimeout` both report literal `"timeout must be configured"`. Operator cannot tell which knob to fix.
- **Why resolve:** §"Descriptive names" — cost of naming parameter in message is paid once, benefit paid every error fire. Constructor is a system boundary, so the validation itself is appropriate.
- **Why it might not be an issue:** None.
- **Scope hints:** Extract `requirePositive(value, paramName)` helper, collapse 4 branches into 4 lines, name parameter in message. ~12 lines net deletion.
- **Verdict:** **RESOLVE-BUNDLED** — bundle with another SSRF cleanup (M7/M8).

### M27 — Asset-command tokens use JVM-default locale `toLowerCase()` (LOW)

- **Source:** 07#F13
- **Location:** `AssetHandler.java:156, 160`
- **What's wrong:** `tokens[i].toLowerCase()` without `Locale.ROOT`. Spec §Surface conventions pins tag normalization to `Locale.ROOT` for the same I/dotless-I reason; this handler is the lone outlier (BanCommand, AuditCommand, etc. all use `Locale.ROOT`).
- **Why resolve:** Defense-in-depth against operator-locale drift; spec-consistent.
- **Why it might not be an issue:** Not exploitable today (all registered tokens are ASCII). Falsifier: spec rule + every-other-site applies it — the outlier should converge.
- **Scope hints:** `tokens[i].toLowerCase(Locale.ROOT)`. 2-line change.
- **Verdict:** **RESOLVE-BUNDLED** — fold into the AssetCommandRouter rewrite ticket (TP5).

### M28 — `AdapterRegistry` parses `infochat.adapters` CSV without dedup (LOW)

- **Source:** 01#F5
- **Location:** `AdapterRegistry.java:130-159`
- **What's wrong:** `infochat.adapters=simplex,simplex` runs through gates 2-6 with the adapter wired twice. SPI declares `setInboundHandler` as "replaceable" → "undefined for v1"; second call clobbers (harmless but contract-violating).
- **Why resolve:** Operator config error surfaces as silent double-wiring; fail-fast IllegalStateException matches existing gate error vocabulary.
- **Why it might not be an issue:** Operator typo, not a real bug. Falsifier: six lines closes a known operator-error path.
- **Scope hints:** Add `LinkedHashSet`-based dedup gate before resolution; throw with the offending name. ~10 lines + 1 test.
- **Verdict:** **RESOLVE-BUNDLED** — bundle with TP2 (reply-target fix); both touch AdapterRegistry/InboundRouter.

### M29 — DAG documentation disagrees with poms (LOW)

- **Source:** 01#F4 (see CT3)
- **Location:** `docs/design/09-reference.md:33-38`
- **What's wrong:** Doc says `infochat-ssrf`, `infochat-llm-adapter`, `infochat-messaging-adapter` depend on `infochat-core`; actual poms declare no such dep and no consumer code references `app.zcat.infochat.core.*`.
- **Why resolve:** §9.1 carries normative force ("normative for module dependencies"); contradicting code is a hazard.
- **Why it might not be an issue:** Doc-only. Falsifier: CT3.
- **Scope hints:** Update the DAG table to `(none)` for all three sibling modules; update ASCII diagram so they sit at infochat-core's level. ~10 lines doc.
- **Verdict:** **RESOLVE-BUNDLED** — CT3 sweep or inline next time the file is touched.

### M30 — InMemoryAdapter `supportsCodeFormatting=false` drifts from design (LOW)

- **Source:** 05#F7 (see CT5)
- **Location:** `InMemoryAdapter.java:61`
- **What's wrong:** Design §6.6 declares `true` ("exercises the markdown-code render path"); code declares `false`. The test-harness "exercises the code-formatting render path" rationale is broken.
- **Why resolve:** Either pick: flip code to `true` (restores test coverage) or update design to match (purely documentary).
- **Why it might not be an issue:** Each direction is defensible. Falsifier: the drift IS the bug; one must be picked.
- **Scope hints:** Recommend flipping code to `true` (matches design's stated rationale); update `InMemoryAdapterTest` assertion. Some Provider-side test may need adjustment if it observed plain-text output where formatted was expected.
- **Verdict:** **RESOLVE-BUNDLED** — bundle with M9 (the larger messaging-adapter SPI cleanup).

### M31 — `dispatchKey` is per-tick, not per-startup (LOW)

- **Source:** 06#F9
- **Location:** `FetchScheduler.java:408-419`
- **What's wrong:** Local `long dispatch = 1L` inside `enumerateActiveSources` resets each call; record's javadoc says "monotonically-assigned per-startup token." Not a correctness bug (Fetcher SPI documents `sourceId` as opaque), but javadoc-vs-implementation drift.
- **Why resolve:** Future maintainer reading the record assumes per-startup stability and may key state on `dispatchKey`, silently breaking.
- **Why it might not be an issue:** Comment-only. Falsifier: CT3 pattern; comment is the cheaper fix.
- **Scope hints:** Update javadoc to "per-tick, MAY change between ticks for the same source." 5-line comment edit.
- **Verdict:** **RESOLVE-BUNDLED** — CT3 sweep or inline.

---

## Cross-cutting themes (CT1–CT5)

The audit's synthesizer grouped findings into 5 themes. Each suggests a "system-level fix" that bundles individual findings into one coordinated ticket. Reusing the audit's framing:

### CT1 — Quarantine NOTIFY contract closure
- **Findings bundled:** M1 (V25 procs missing NOTIFY), M5 (Stage 2 PENDING re-fire), M21 (emitter no escape), M22 (V21/V25 raw concat).
- **Outcome:** One coordinated migration + Stage 2 refactor + emitter type-tightening. `QuarantineNotifyEmitter` becomes the lone source-of-truth for the channel; admin procs fire NOTIFY; PENDING moves to insert time; closed-enum emitter signature; consumer-side parser switched to Jackson.
- **Files budget estimate:** 8-10 (V30 migration, QuarantineDao, Stage2VerdictHandler, QuarantineNotifyEmitter, QuarantineReviewListener, V21/V25 successor for `jsonb_build_object`, tests).
- **Verdict:** Single coordinated ticket — high complexity, plan-writer outline recommended.

### CT2 — JSpecify annotation + dead-defensive-code sweep
- **Findings bundled:** M12 (MessagingException), M14 (LlmRouter defensive nulls), M15 (null sentinel), M16 (LlmRouter constructor annotations), part of CT-pattern in `infochat-llm-adapter` and `infochat-messaging-adapter`.
- **Outcome:** Run `scripts/lint-contracts.py` across both modules; add every missing annotation; remove dead null checks that the now-explicit `@NonNull` contracts make impossible.
- **Files budget estimate:** 6-8 (LlmRouter, OpenAiCompatibleProvider, MessagingException, plus any other surface the lint script surfaces).
- **Verdict:** Mechanical sweep ticket; single bundled diff.

### CT3 — Stale-comment / pre-relocation-wiring sweep
- **Findings bundled:** M23 (V7 infochat_listen), M24 (V16 notifier location), M25 (SsrfGuardedHttpClient ws/wss bullet), M29 (DAG doc), M31 (dispatchKey javadoc).
- **Outcome:** Sweep the codebase for ticket-id references in long-lived comments + comment-vs-current-state drift. Extend `feedback_no_plan_refs_in_docs.md` to source comments.
- **Files budget estimate:** 5-6 files, all comment edits.
- **Verdict:** Either one "stale comment sweep" ticket OR fix-inline-next-touch for each.

### CT4 — Shared text utilities extraction (JSON + Hex/SHA)
- **Findings bundled:** Si2 (JSON quote x5 drift), Si4 (SHA-256 hex x2 drift). Optionally Si3 (delete IngestSpisLoadTest).
- **Outcome:** Extract `JsonText.quote(String)` and `Hex.sha256(byte[])` to `infochat-core`. Replace ~7 call sites. ~150 net deletion.
- **Files budget estimate:** 8-10 files (new utility classes + every replacement site).
- **Verdict:** Single bundled ticket; bigger benefit than the individual fixes suggest.

### CT5 — Cross-adapter SPI contract test
- **Findings bundled:** M9 (onMembershipEvent SPI confusion), M10 (codec exception types), M11 (not-connected category drift), M19 (supportsTypingIndicator), M30 (supportsCodeFormatting).
- **Outcome:** Introduce a cross-adapter contract test suite per the synthesizer's recommendation; align capability flags + classification per semantic state. Future adapter additions cannot regress.
- **Files budget estimate:** 10-12 (MessagingAdapter SPI, SimpleXAdapter, SignalAdapter, InMemoryAdapter, codec, new test class + per-adapter test cases, design §6.4.2 / §6.6 updates).
- **Verdict:** Larger ticket; plan-writer outline strongly recommended. The contract test is the forcing function that prevents the next instance of this drift.

---

## Synthesizer-relevant observations (not numbered F-findings)

These appeared in per-report "Synthesizer-relevant observations" sections and were not entered as F-findings, but bear naming for triage:

1. **Remote-embedding-switch startup log missing** (04 obs.). `docs/spec/llm.md` §Per-task routing rules commits to "Switching the embedding provider to a remote service emits an explicit confirmation log line on startup." No code in `infochat-llm-adapter` emits such a log. The `LlmRouterStartupGuard` logs only the local-only enforcement.
   - **Verdict:** **RESOLVE** as a small ticket; pair with M17 (router/SPI cleanup).

2. **`OpenAiCompatibleProvider.configFor` throws for every ModelTask other than `SECURITY_JUDGE`** (04 obs.). Router can resolve this provider for any task; misconfigured router that sends `TAGGER` calls through it blows up at the call site rather than at startup.
   - **Verdict:** **RESOLVE-BUNDLED** with M17; add a startup-guard scan that asserts every routed task has a serving provider.

3. **`PinnedDnsResolver.Provider` JVM-wide lock holds across WebSocket handshakes** (03 obs.). A slow relay handshake stalls every RSS fetch on a node.
   - **Verdict:** **DEFER** — depends on `checkAndPinForWebSocket` callers enforcing connect timeouts that bound lock-hold; flag for the architecture pass.

4. **`META-INF/services/InetAddressResolverProvider` registered JVM-globally** (03 obs.). Any module depending on `infochat-ssrf` installs the resolver for the whole JVM, including tests in unrelated modules.
   - **Verdict:** **DEFER** — verify no module has a non-test runtime dep on `infochat-ssrf` it doesn't use; might be a one-line pom audit.

5. **`SET LOCAL infochat.actor_id = '<uuid>'` string-concatenated across 8 handlers** (07 obs.). UUID-only today so safe; pattern is an architectural smell. A `SELECT set_config('infochat.actor_id', ?, true)` JDBC-bindable overload would remove the concat.
   - **Verdict:** **RESOLVE-BUNDLED** — could land alongside TP3 (DB role split) since both touch SQL dispatch.

6. **`MAX_OUTBOUND_TEXT_BYTES = 4_000` (codec) vs `maxMessageBytes = 2_000` (capability) lockstep not enforced** (05 obs.). Two declarations of the same SimpleX limit.
   - **Verdict:** **RESOLVE-BUNDLED** with CT5 — assert lockstep in cross-adapter contract test.

7. **`NostrStreamSource.Registrar` reads `source.config` with hand-written `JsonNode` traversal** (06 obs.). Bootstrap loader writes the same column with a different validator. A config row that bootstraps cleanly but contains malformed relay URI fails silently at registration.
   - **Verdict:** **RESOLVE** as a small ticket; introduce a shared `ConfigBlock` record.

---

## Suggested ticket bundling (proposed for `/m1-tick` queue)

A reasonable order, batching findings into ticket-sized units:

1. **CRIT-AUTH** — Anthropic headers (TP1) — small, must fix to ship Anthropic at all.
2. **CRIT-MULTI-ADAPTER** — replyTarget per-adapter + duplicate-name dedup (TP2 + M28). **security_relevant**.
3. **CRIT-DB-ROLES** — per-service role wiring (TP3). High files_budget, plan-writer outline. **security_relevant**.
4. **CRIT-COLLECTOR-CONFIG** — `infochat.reeval.*` keys (TP4).
5. **CRIT-ASSET-EXT** — AssetCommandRouter rewrite + Locale.ROOT (TP5 + M27).
6. **HIGH-STOP** — `/stop` group + scope key fix (S1).
7. **HIGH-CT1** — quarantine NOTIFY contract closure (M1 + M5 + M18 + M21 + M22). Plan-writer outline.
8. **HIGH-EMBEDDING-SPI** — embedding result + SPI size contract (M2 + M3).
9. **HIGH-KIND6** — kind-6 repost edge resolution (M4). Plan-writer outline.
10. **HIGH-HELP** — `/help` per-tier filtering + bundle composition (M6). Plan-writer outline.
11. **HIGH-MEMBERSHIP-AUDIT** — audit-before-effect in MembershipEventHandler (M20).
12. **HIGH-SIMPLEX-LEAK** — SimpleXAdapter handle eviction (P1).
13. **HIGH-INTAKE-PERF** — InboundRouter Connection threading (P2). Plan-writer outline.
14. **MED-SSRF-CLEANUP** — IPv6 + extra-headers + per-call executor + indistinct errors + ws/wss javadoc + IpBlocklist shim (M7 + M8 + S2 + P5 + M25 + M26). **security_relevant**.
15. **MED-CHAT-TOOLS** — Jackson parser + Duration parse + per-tool ToolArgs helper (S3 + S6). **security_relevant**.
16. **MED-SAVE-CAPS** — `/save` length/count caps (S5). **security_relevant**.
17. **MED-CURSORS** — URL-encode pagination cursors (S7). **security_relevant**.
18. **MED-PROMOTE-TOCTOU** — `FOR UPDATE` on /promote (S4). **security_relevant**.
19. **MED-CT2** — JSpecify annotation sweep + dead defensive-code removal (M12 + M14 + M15 + M16). Mechanical.
20. **MED-CT5** — cross-adapter contract test + SPI cleanup (M9 + M10 + M11 + M19 + M30 + obs.6). Plan-writer outline.
21. **MED-COLLECTOR-CLEANUP** — dead semaphores + AssetSnapshotFetcher catch + reeval cap unreachable (Si1 + M13 + 06#F2 NEEDS_REVIEW reachability).
22. **MED-LLM-ROUTER-COUPLING** — instanceof chain + remote-embedding log + configFor coverage (M17 + obs.1 + obs.2).
23. **LOW-CT4** — JsonText + Hex.sha256 extraction + delete dead test (Si2 + Si3 + Si4).
24. **LOW-CT3** — stale-comment sweep (M23 + M24 + M25 + M29 + M31).
25. **LOW-PERF** — sanitizer pattern caching + Nostr index (P3 + P4). Bundle.
26. **LOW-NOSTR-CONFIG** — shared ConfigBlock validator (obs.7).

This is a starting layout; **the user is the decision-maker** for whether a bundle is right or should split. The bundling above honours the rules' "surgical" / "one-purpose-per-diff" stance while keeping the ticket count finite.

---

## Items I'd flag for falsifier-after-grounding before ticketing

A handful of audit claims are written with high confidence but assume the spec/design files are exactly as the reviewer cited. Before opening tickets, the implementer should re-check:

- **TP1** — Confirm Anthropic public-reference headers haven't drifted again (`https://docs.anthropic.com/en/api/messages`).
- **TP4** — Confirm the suggested baseline values cross-check against `docs/design/04-security.md` §Re-evaluation job (the report flags them as suggested, not authoritative).
- **M4** (kind-6) — Decide Option A vs Option B with the architecture-decision lens; Option B redefines `post.id` semantics module-wide.
- **M19** (supportsTypingIndicator) — Decide before M1-105 whether the conservative flip-to-false is preferred, or whether M1-105 timing makes "verify first, then flip" cleaner.
- **CT5** — The cross-adapter contract test suite is suggested but not scoped in any existing M1 ticket; need to decide whether it lands as one ticket or piggybacks on the existing per-finding tickets.

These are not "the audit is wrong"; they're "ground before writing the acceptance items."
