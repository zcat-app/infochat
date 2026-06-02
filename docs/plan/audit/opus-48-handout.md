# Audit consolidation handout (Opus 4.8)

**Date:** 2026-06-02

## Inputs consumed (all nine runs — Deliberately excluded: none)

Five **primary** deep-review runs (multi-file: `00-summary.md` + per-module reports):

1. `deep-code-review/opus-47/` — 8 files (summary + architecture + 6 module reports)
2. `deep-code-review/opus-48/` — 8 files
3. `deep-code-review/deepseek/` — 8 files
4. `deep-code-review/mimo/` — 8 files
5. `deep-code-review/kimi-k/report.md` — single combined report

Four **fresh-eyes** audit runs (single file):

6. `deep-code-review/opus-48-audit/opus-audit-report.md` (7 findings)
7. `deep-code-review/deepseek-audit/deepseek-audit-report.md` (10 findings)
8. `deep-code-review/kimi-k-audit/kimi-k-audit-report.md` (18 + 3 info)
9. `deep-code-review/mimo-audit/mimo-audit-report.md` (36 findings)

> **Read-depth note (honesty about grounding).** Every *numbered* finding in all
> nine runs was read — the four primary summaries are synthesizer-produced
> indexes that enumerate every per-module `Fn` with its location and source
> anchor, and were read in full alongside all four audit reports and the kimi-k
> combined report. The richest primary run (opus-47: 5 of the 8 criticals) had
> its architecture, collector, and provider detail reports read in full. Every
> **critical** and load-bearing **high** below carries a verdict re-checked
> against the **live source tree this pass** (`file:line` cited from the current
> tree, not the report) — see the `GROUNDED` tag. Lower-severity single-source
> items whose locus was confirmed but mechanism taken on the reporter's read are
> tagged `LOCUS` or `REPORTED`. Nothing was dropped silently: every finding has
> a row below or in the Tier-D "not-a-ticket" section.

**Cross-check used (not copied from):** after the independent read+ground pass,
this list was diffed against the prior consolidations
`docs/plan/audit/opus-47-full-handout.md` and `opus-47-only-handout.md`. The one
known gap they dropped — `UrlProbe` mapping SSRF failure modes by
exception-message-prefix string-matching — is captured here as **C-URLPROBE-MSG**
(grounded at `UrlProbe.java:95-96`).

**Purpose.** Staging document for turning findings into M1 tickets. For *every*
finding: (a) what was claimed; (b) a grounded verdict against the live tree
(`CONFIRMED` / `PARTIALLY-CONFIRMED` with the real mechanism / `FALSE`);
(c) why it is worth resolving; (d) why it might not be an issue; (e) a
disposition. The Tier D section is as load-bearing as Tier A — several findings
are false positives or proposals that would *violate* the project's own
engineering rules, and recording the reasoning stops them being re-raised.

---

## Disposition legend

| Tag | Meaning |
|---|---|
| **FIX** | Real defect; ticket it. |
| **FIX-LOW** | Real but low-impact; batch or do opportunistically. |
| **DOC** | Documentation/comment-only correction; no code change. |
| **NON-ISSUE** | Not a defect, OR fixing it would violate an engineering rule (no-defensive-code / surgical-changes / dependency-approval). Reasoning recorded so it isn't re-raised. |
| **WATCH** | Not actionable now; valid only at higher scale/load. Note in design, no M1 ticket. |

Grounding tags: **GROUNDED** = re-checked against live source this pass;
**LOCUS** = file/line locus verified, mechanism per reporter; **REPORTED** =
carried from the run, locus plausible but not independently re-verified.

---

## Why this file was rewritten

A prior version of this handout covered **only the four fresh-eyes audit runs**
and missed four production-blocking criticals that live in the **primary** runs
(`REPLY-TARGET`, `DB-OWNER-ROLE`, `REEVAL-CONFIG-KEYS`, `ASSET-ROUTER`). This
rewrite consolidates all nine. The four audit-run findings are retained; the
primary-run findings are added; severities and mechanisms are reconciled where
runs disagree.

---

## Cross-run deduplication map

Canonical IDs used throughout. "—" = not reported by that run. Severity in
parens is that reporter's call; the adjudicated column is this handout's.

| Canonical | opus-47 | opus-48 | deepseek | mimo | kimi-k | audits | Adjudicated |
|---|---|---|---|---|---|---|---|
| **PARTITIONS** no Jun-2026+ partitions | — | — | — | — | — | mimo-audit C1 (crit) | **Critical** |
| **ANTHROPIC-HEADERS** wrong header names + test pins bug | llm F1/F2 (crit/high) | — | — | — | — | — | **Critical** |
| **REPLY-TARGET** single volatile adapter | prov F1 (crit) | — | — | — | — | — | **Critical** |
| **DB-OWNER-ROLE** runtime connects as owner | arch F1 (crit) | — | — | — | — | — | **Critical** |
| **REEVAL-CONFIG-KEYS** only in test resources | coll F1 (crit) | — | — | — | — | — | **Critical** |
| **ASSET-ROUTER** hardcoded zcash/monero | prov F2 (crit) | — | — | — | — | — | **Critical** |
| **REEVAL-CAP-UNREACHABLE** NEEDS_REVIEW dead branch | coll F2 (crit) | — | — | — | — | — | **Critical** |
| **TOOL-ARGS** parser can't build lists | prov F8 (med) | — | COR-1 angle | — | F4 | opus-48-audit #1 (high), kimi-audit 1.4 (high), ds-audit COR-1 (low) | **High** |
| **LOCK-LIVENESS** zombie / split-brain | — | — | — | — | — | mimo-audit C2 (crit) | **Medium** (mechanism corrected) |
| **SIGNAL-HANDLER** exception kills reader | — | — | — | — | — | mimo-audit H1 (high) | **High** |
| **AUDIT-VIEW-REDACTION** stub returns input | — | arch F1 (high) | — | — | — | — | **High** |
| **LOCAL-ONLY-GUARD** misses embedding endpoint | — | llm F1 (high) | llm F3 (med, placement) | — | — | — | **High** |
| **SIMPLEX-MENTION** non-injective recognition | — | msg F1 (high) | — | — | — | — | **High** |
| **DIGEST-APPROVAL** no approval_status filter | — | prov F1 (high) | — | prov F1 (high) | — | — | **High** |
| **READY-PROMOTER-TX** @Transactional self-invocation | — | coll F1 (high) | — | — | — | mimo-audit L4 (low) | **High** |
| **IPV6-BLOCKLIST** transition ranges bypass | ssrf (obs) | ssrf F1 (high) | — | — | — | opus-48-audit #5 (low, narrower) | **High** |
| **STOP-CMD** noop in group / wrong scope key | prov F3 (high) | — | — | — | — | — | **High** |
| **HELP-NO-TIER** no per-tier filtering | prov F4 (high) | — | — | — | — | — | **High** |
| **KIND6-REPOST** edge unresolvable | coll F3 (high) | — | — | — | — | — | **High** |
| **QUARANTINE-NOTIFY-MISSING** approve/reject no NOTIFY | arch F2 (high) | — | — | — | — | — | **High** |
| **STAGE2-PENDING-NOTIFY** fires at wrong stage | coll F4 (high) | — | — | — | — | — | **High** |
| **EMBEDDING-SIZE-CONTRACT** SPI size mismatch | llm F3 (high) | — | — | — | — | — | **High** |
| **EMBEDDINGRESULT-ARRAY** mutable array record | llm F4 (high) | — | — | llm F1 (med) | — | — | **High** |
| **IPBLOCKLIST-SHIM** M1-025 compat constructor | ssrf F1 (high) | — | — | — | — | — | **High** (rule §7) |
| **QUARANTINE-LISTENER-SQL** interval string-concat | — | — | prov F1 (high) | — | F6 | — | **Medium** |
| **SIGNALCONFIG-BOOT** misleading boot guarantee | — | — | msg F1 (high) | — | — | — | **Medium** |
| **NORMALIZEDPOST-JAVADOC** sourceId contract wrong | — | — | — | core F1 (high) | — | — | **Medium/DOC** |
| **HTTP-CLIENT-LEAK** per-call client never closed | — | — | — | — | 4.2 (info) | opus-48-audit #2 (med) | **Medium** |
| **WS-LOCK** global SSRF lock across WS handshake | ssrf (obs) | — | — | — | — | opus-48-audit #3 (med) | **Medium (arch)** |
| **READBOUNDED-EXECUTOR** per-call platform thread | ssrf F4 (med) | — | — | ssrf F1 (med) | — | — | **Medium** |
| **LLM-OOM** unbounded response body | — | — | — | — | — | mimo-audit M1 (med) | **Medium** |
| **SETLOCAL-SQLI** actor_id string concat | — | — | prov F5 (med) | — | F3 | kimi-audit 1.1 (high) | **NON-ISSUE** |
| **UTF8-CAP** surrogate bypass / OOB | — | — | — | — | — | ds-audit SEC-1 (med), kimi-audit 2.3 (med) | **NON-ISSUE (false)** |
| **PINNED-CLOSE** close() not idempotent | — | — | — | — | — | kimi-audit 2.2 (med) | **NON-ISSUE** |
| **TREEMAP-ALLOC** cache-key allocation | — | — | — | — | — | ds-audit PERF-2 (low) | **NON-ISSUE** |
| **MISSING-V20** Flyway gap | — | — | — | — | — | kimi-audit 2.5 (med) | **DOC** |
| **STAGE1-BACKTRACK** regex pathological | — | — | — | — | — | kimi-audit 2.6 (med) | **NON-ISSUE (watchdog)** |
| **TOOL-LEAK** multi-line TOOL_CALL leak | — | — | — | — | — | mimo-audit H2 (high) | **FIX-LOW** (mechanism corrected) |
| **CONN-CHURN** N+1 connections per inbound | prov F5 (high) | — | — | prov(M11) | — | ds-audit PERF-1 (low), mimo-audit M11 (med) | **WATCH + pool-size FIX-LOW** |
| **CIRCUIT-BREAKERS** none | — | — | — | — | — | mimo-audit L19 | **NON-ISSUE (by design)** |
| **DUP-MSG-DEP** provider pom duplicate dep | — | — | — | — | F1 (high) | — | **NON-ISSUE (false)** |
| **JSON-DUP** hand-rolled JSON escape ×12 | prov F12, coll F10 | CT4 | coll F5/F6, ds-audit SIM-1 | coll F1/F4 | F3, kimi-audit 3.3 | opus-48-audit #6 | **Med (debt) + Low (C0)** |
| **LOOKUP-DUP** lookupUser pattern ×15+ | — | — | — | — | — | ds-audit SIM-2 (med) | **Med (debt)** |
| **TAG-NORM-DUP** tag normalize ×3 | — | CT3 | coll F6 | coll F2 | — | — | **Med (debt)** |
| **SHA256-DUP** two hex helpers | coll F10 | — | coll F6 | coll F3 | — | — | **Low (debt)** |
| **JSPECIFY-MISSING** @NonNull/@Nullable gaps | llm F7, msg F6 | — | ssrf F1, llm F4 | — | F2 (high), F11 | — | **Med (debt)** |
| **DEFENSIVE-CODE** internal null/catch guards | llm F6 | ssrf F4, llm F3/F4, coll F3 | prov F2 | — | F8/F10 | — | **Med (sweep)** |

Single-sourced findings are listed under their canonical IDs in the tiers below.

---

# Tier A — Confirmed real: Critical / High (ticket first)

## Criticals

### A1 · PARTITIONS — no partitions exist for June 2026 or later  *(mimo-audit C1, Critical)* — GROUNDED
**Files (live tree):** `V7__joins_post.sql:175-176`, `V11__post_embedding.sql:77-78`, `V17__price_snapshot.sql:60-61`, `V28__post_entity.sql:69-70`, `V29__post_reference.sql:69-70`.
**Claim.** All five partitioned tables have only a May-2026 partition; first INSERT ≥ 2026-06-01 fails `no partition of relation … found for row`. No partition-creation scheduler exists.
**Verdict — CONFIRMED.** Grep returns exactly five `PARTITION OF` declarations, each `FOR VALUES FROM ('2026-05-01') TO ('2026-06-01')`. Latest migration is V29; no V30. No `@Scheduled` partition-creator bean (the grep hits in collector are substring matches in unrelated files). Today is 2026-06-02 → ingest cannot persist a single new post.
**Why resolve:** highest-priority item across all nine runs — the collector is dead-on-arrival on first real insert. The spec promises an "application-tier partition scheduler" that was never built.
**Why it might be less urgent:** greenfield M1, nothing in production yet; IT containers spin up fresh per test so they don't hit the month boundary. But the build must not ship a schema that breaks on the first insert.
**Disposition: FIX (Critical).** (1) `V30` adding Jun+Jul 2026 partitions to all five tables (immediate unblock). (2) `@Scheduled` monthly partition-creator with a "hasn't run in 25 days" alarm (durable fix). Check the IT suite reds first as a falsifier of "it's fine today."

### A2 · ANTHROPIC-HEADERS — wrong header names, and the test pins the bug  *(opus-47 llm F1 crit + F2 high)* — GROUNDED
**Files:** `AnthropicProvider.java:139` (`x-anthropic-version`), `:142` (`anthropic-api-key`); class javadoc `:50-51`; test `AnthropicProviderTest.java:133-134,154-157`.
**Claim.** Code emits `x-anthropic-version` / `anthropic-api-key`; the Messages API requires `anthropic-version` (no `x-`) and `x-api-key` (with `x-`). The test asserts the wrong names, so it locks the bug in (§8 test-integrity).
**Verdict — CONFIRMED.** Grep confirms `.header("x-anthropic-version", API_VERSION)` at :139 and `.header("anthropic-api-key", cfg.apiKey())` at :142, with the same wrong names in the class javadoc. The header-name swap matches the documented Anthropic contract.
**Why resolve:** every production Anthropic call 401s; the API key ships in a header Anthropic discards (minor name-keyed exposure). The bug-pinned test is a §8 violation that must be corrected in the same diff.
**Why it might not be an issue:** OpenAI-compatible is the v1 default provider, so AnthropicProvider may be dormant — but it is a v1 deliverable (M1-085/M1-120).
**Disposition: FIX (Critical).** Two header strings + four test assertions + javadoc; also narrow `extractErrorMessage`'s `catch (Exception)` → `catch (IOException)` (A2b below) in the same Anthropic-family diff. Low complexity.

### A2b · ANTHROPIC-CATCH — `extractErrorMessage` swallows `Exception`  *(opus-48 llm F2, kimi-k F5, deepseek llm F2, mimo llm F2)* — LOCUS
**File:** `AnthropicProvider.java:195-205` (`catch (Exception ignored)`).
**Verdict — CONFIRMED (four-reporter convergence).** The diagnostic helper's only legitimate failure is `IOException` from `JSON.readTree`; `catch (Exception)` also swallows runtime/programming errors. Note `mimo-audit L8` (error-message not truncated) folds here — the fall-through returns `preview(body)`, so the only possibly-untruncated path is the parsed `message` field; apply `preview()` there if so.
**Disposition: FIX-LOW, bundled with A2.** Narrow to `IOException`; truncate the parsed branch.

### A3 · REPLY-TARGET — single `volatile MessagingAdapter` routes every reply to the last adapter  *(opus-47 prov F1, Critical)* — GROUNDED
**Files:** `InboundRouter.java:284` (`private volatile MessagingAdapter replyTarget`), used at `:604`; setter `:290-291`; activation loop `AdapterRegistry.java:254-266`.
**Claim.** `AdapterRegistry.start` calls `setReplyTarget(adapter)` once per activated adapter; last wins. In SimpleX+Signal, every reply ships through the last-registered adapter regardless of inbound adapter → cross-adapter outbound to an unrelated identity space.
**Verdict — CONFIRMED.** `replyTarget` is a single volatile field read at the reply site with no adapter-name discriminator, even though `onMessage(msg, adapterName)` carries the discriminator and `DigestWorker.findAdapter`/`ApproveGroupCommandHandler.findAdapter` already demonstrate the correct per-name lookup.
**Why resolve:** D46 + `security.md` §Per-adapter admin threat profile commit to per-adapter isolation, which is exactly the multi-adapter shape M1 ships. Banned-user fixed-reply also silently becomes a drop. Memory: *Signal adapter must remain in v1* — so the multi-adapter shape is live.
**Why it might not be an issue:** single-adapter profiles cannot exhibit it. But the v1 commitment is multi-adapter.
**Disposition: FIX (Critical).** Replace the field with per-name resolution (`findAdapter(adapterName)`), thread `adapterName` through `sendReply`. Medium complexity — recommend a plan-writer pass.

### A4 · DB-OWNER-ROLE — runtime connects as the `infochat` owner, not the per-service roles  *(opus-47 arch F1, Critical)* — GROUNDED
**Files:** `infochat-collector/.../application.properties:13`, `infochat-provider/.../application.properties:18` (both `quarkus.datasource.username=infochat`); roles `V2__roles.sql:32-65`.
**Claim.** Both services connect as the owner; `infochat_collector/provider/admin` are NOLOGIN; no `SET ROLE` anywhere. Every GRANT/REVOKE the spec attaches to the role split (audit_log_view redaction value, quarantine carve-outs, Invariants 4/10) is decorative.
**Verdict — CONFIRMED.** Grep confirms both `username=infochat` and zero `SET ROLE`. V2 carries the explicit "until the named-datasource wiring ticket lands, the bootstrap `infochat` superuser remains the connecting role" comment; that ticket never landed.
**Why resolve:** the role split is one of the spec's named defense-in-depth layers; a SQL-injection foothold in the Provider would have owner privilege on every table today. Every new handler accrues more privilege-mismatched DML the eventual switch must sweep.
**Why it might not be an issue:** it is a defense-in-depth layer, not the only barrier; documented surfaces (views, stored procs) are still used. But the spec treats the split as load-bearing, not optional.
**Disposition: FIX (Critical).** `V30` adds `LOGIN` to the two service roles; Quarkus named-datasource wiring (Flyway on owner, runtime on role); two operator passwords; sweep code against the GRANT matrix. **Expect IT failures that surface real privilege-mismatched DML** — those are the bugs the current setup hides. High complexity; plan-writer pass. Bundle with A11 (audit-view redaction — same trust boundary).

### A5 · REEVAL-CONFIG-KEYS — `infochat.reeval.*` declared only in test resources  *(opus-47 coll F1, Critical)* — GROUNDED
**Files:** `infochat-collector/src/main/resources/application.properties` (0 matches), `src/test/resources/application.properties` (12 matches); consumers `ReEvaluationJob.java:74-86`, `PerSourceUnknownTracker.java:41-50`, `AdminReviewTtlJob.java:51-57`.
**Claim.** Nine `infochat.reeval.*` keys live only in test config; consumers carry no `defaultValue`; `@Scheduled(every="{infochat.reeval.poll-interval}")` fails at scheduler-config-parse. Collector startup fails in every operator profile.
**Verdict — CONFIRMED.** `grep -c` returns 0 in main, 12 in test. Only `%test` boots.
**Why resolve:** the entire re-evaluation policy is dead until the keys land; production startup is the immediate blocker.
**Why it might not be an issue:** `quarkus:dev` inherits `%dev` — but `%dev` does not carry these keys either, so it fails too.
**Disposition: FIX (Critical).** Add the nine keys to main config with profile overrides per `docs/design/04-security.md`. Plus a CI guard asserting every `@ConfigProperty(name="infochat.*")` resolves in main config (separate ticket). Low-medium complexity.

### A6 · ASSET-ROUTER — only `/zcash` and `/monero` are wired; third asset is invisible  *(opus-47 prov F2, Critical)* — GROUNDED
**File:** `AssetCommandRouter.java:24-55` — two static inner `CommandHandler` beans returning `"zcash"`/`"monero"`.
**Claim.** A third asset in `bootstrap-assets.json` loads into `asset_config`/`price_snapshot` but has no `CommandHandler`, so `/litecoin` → "Unknown command". Probation gate also leaks (the asset oracle returns true, user passes the gate, then gets Unknown command).
**Verdict — CONFIRMED.** Grep confirms exactly two hardcoded `name()` returns. `commands.md` §Asset commands commits to operator-config-driven extensibility "without a new top-level command per verb."
**Why resolve:** permanently constrains the spec's per-asset extensibility surface to two assets; every new asset becomes a code change.
**Why it might not be an issue:** the probation-then-Unknown-command path is arguably a benign "not wired yet" diagnostic rather than a security harm — a policy call.
**Disposition: FIX (Critical).** Either a router fallback that consults `AssetCommandFamilyOracle` before `UNKNOWN_COMMAND_REPLY` (opus-47 Option A, deletes the file), or a `@Produces` per-asset `CommandHandler` list (opus-47-full Option B). Medium complexity.

### A7 · REEVAL-CAP-UNREACHABLE — `NEEDS_REVIEW` cap-exhaustion transition is dead code  *(opus-47 coll F2, Critical)* — GROUNDED
**File:** `ReEvaluationJob.java:109-110` (`if (candidate.reEvalAttempts() >= cap) transitionToNeedsReview`), enumerate SQL `:289,292` (`… AND re_eval_attempts < ?`).
**Claim.** `enumerateCandidates` filters `re_eval_attempts < cap`, so a cap-reached row never enters `processOne`; the `>= cap` branch never fires. Rows stay `QUARANTINED` forever, the spec's `NEEDS_REVIEW` transition never happens, the admin notification never fires. The unit test passes only because it bypasses `enumerateCandidates`.
**Verdict — CONFIRMED.** Both predicates present at the cited lines; the in-process `>= cap` check is structurally unreachable from the scheduled path.
**Why resolve:** `security.md` §Re-evaluation job mandates the transition; the operator-alerting commitment depends on it.
**Why it might not be an issue:** single-reporter, but the SQL+code progression is unambiguous on grounding.
**Disposition: FIX (Critical).** Drop the `re_eval_attempts < ?` predicates from enumerate; let `processOne`'s cap check drive the transition. Add an IT exercising the full scheduled path on a seeded cap-exceeded row. Low complexity once understood.

### A8 · TOOL-ARGS — chat tool-call parser cannot build list arguments  *(opus-48-audit #1 High; opus-47 prov F8 med; kimi-audit 1.4 high; deepseek-audit COR-1 low; kimi-k F4)* — GROUNDED
**Files:** `ChatAgent.java:264` (`splitTopLevel`), `:274` (`Integer.parseInt`), no array branch; regex `TOOL_CALL_PATTERN`; consumers `SearchPostsTool.java:46`, `RecallMemoryTool.java:39`, `ListSavesTool.java:45` (all `(List<String>) args.get(...)`).
**Claim.** Three independent defects in one hand-rolled parser: (1) no array support — `["bitcoin"]` is stored as a raw `String`, consumers cast `(List<String>)` → `ClassCastException`; (2) reluctant `\{.*?\}` truncates nested JSON; (3) one-char-deep escape check mis-flips `inQuote` on `\\\"`. `recallMemory` is entirely broken; tag-filtered `searchPosts`/`listSaves` always fail.
**Verdict — CONFIRMED.** `parseToolArgs` only emits `String`/`Integer` (no `startsWith("[")` branch); the three consumers cast to `List<String>` at the cited lines. opus-48-audit grepped the tests and found no array-path coverage — that is the falsifier: the code is broken *and* unexercised. opus-48-audit's `High` is the correct severity (functional break in a core v1 surface); deepseek-audit's `Low` framing (nested-only) understates it.
**Why resolve:** chat search/recall by tag/keyword is broken whenever it matters, with zero coverage on the failing path.
**Why it might not be an issue:** only the chat-mode tool loop is affected; slash commands and ingest are untouched.
**Disposition: FIX (High).** Replace `parseToolArgs`/`splitTopLevel`/the regex with Jackson `ObjectMapper.readTree` (already on the classpath) — this dissolves all three defects and the TOOL-LEAK residual (D-TOOL-LEAK) at once. Bundle with A8b (dispatcher catch). Add tests for array args, nested objects, escaped quotes. Recommend a plan-writer pass (reporters disagree on which subset to fix first).

### A8b · TOOL-DISPATCH — dispatcher swallows only two exception types  *(opus-48-audit #4; opus-47 prov F11)* — LOCUS
**File:** `ChatToolDispatcher.java:137-145` (catches only `IllegalArgumentException`/`SQLException`); `SearchPostsTool.java:46-48` (`Duration.parse`).
**Verdict — CONFIRMED.** `ClassCastException` (from A8) and `DateTimeParseException` (from `Duration.parse` — extends `DateTimeException`→`RuntimeException`, NOT `IllegalArgumentException`) escape the dispatcher, abort the turn, surface as `ERROR_CHAT_UNAVAILABLE`, and deny the model a structured `ValidationError` to self-correct.
**Disposition: FIX (Medium), bundled with A8.** Coerce/validate arg types at the dispatcher boundary (the right system boundary for "the LLM produced a malformed call") and translate to `ValidationError`.

## Highs

### A9 · LOCK-LIVENESS — advisory-lock zombie / split-brain  *(mimo-audit C2, reported Critical — mechanism corrected)* — GROUNDED
**Files:** `InstanceLockGuard.java:76` (`heldConnection.setAutoCommit(true)`), `:84` (`upsertHeartbeat` — single call), `HeartbeatScheduler.java:30-38` (both services).
**Claim (mimo):** "heartbeat written once at startup, never refreshed" → a silently-dropped held connection lets a second instance acquire the lock.
**Verdict — PARTIALLY-CONFIRMED; mimo's mechanism is FALSE.** `HeartbeatScheduler.tick()` runs `@Scheduled(every="{infochat.heartbeat.interval}")` and refreshes `last_seen_at` — so it *is* refreshed. **The real defect:** the scheduler uses a *transient pool connection* (its own javadoc says so), so the held lock-owning session is never liveness-probed and the advisory lock is never re-verified after startup. Worse — the scheduler refreshing on a healthy pool connection *masks* a dead holder: a zombie (held session killed by PG restart / `idle_in_transaction_session_timeout` / NAT reaping; note `setAutoCommit(true)`, borrowed outside the pool with no keepalive) still looks "alive" to a second acquirer's staleness check.
**Why resolve:** D41 single-instance enforcement underpins the outbox rehydrator and FetchScheduler advisory-locked enumeration.
**Why not Critical:** needs a server-side session death (uncommon); and a ticket written to mimo's literal description ("add a heartbeat refresh") would change nothing because the refresh already exists.
**Disposition: FIX (Medium).** Liveness on the *held* session: periodically re-run the advisory-lock ownership check / `SELECT 1` on the lock-owning connection and `Quarkus.asyncExit(1)` on loss; set TCP keepalive / `setNetworkTimeout`. **Ticket text must target the held session, not "add a heartbeat scheduler."** Bundle with the `InstanceLockGuard` collector/provider de-dup (kimi-k F4 — byte-for-byte duplicate; one fix must otherwise land twice).

### A10 · SIGNAL-HANDLER — inbound handler exception kills the JSON-RPC reader thread  *(mimo-audit H1, High)* — REPORTED
**Files:** `SignalJsonRpcClient.java:433`, `SignalGroupHandler.java:167` (`handler.onMessage(inbound)` with no try/catch).
**Claim.** Any `RuntimeException` from `onMessage` propagates through `readerLoop` and kills the `signal-jsonrpc-reader` thread; the subprocess stays alive (no restart), so the adapter is half-dead indefinitely. `SimpleXAdapter.onInbound` wraps its handler call; Signal does not — a genuine asymmetry.
**Verdict — CONFIRMED-BY-LOCUS** (prior opus-47-full grounded the asymmetry; not re-read this pass).
**Why resolve:** one bad inbound message permanently wedges Signal, a v1 adapter.
**Why narrow:** requires `onMessage` to actually throw, which the intake pipeline mostly prevents.
**Disposition: FIX (High).** Wrap `handler.onMessage` in `try/catch (RuntimeException)`, log class-name only (D37 — no user bytes), drop the message. Bundle with B-SIGNAL-HUNG.

### A11 · AUDIT-VIEW-REDACTION — `audit_log_view` redactors are no-op stubs  *(opus-48 arch F1, High)* — GROUNDED
**Files:** `V5__identity_audit.sql:324-336` — `redact_contact_id(input TEXT) … RETURN input` and `redact_secrets_jsonb(input JSONB) … RETURN input`; view at `:338+` selects `redact_contact_id(actor_contact_id)` etc.; Provider read path `AuditCommandHandler.java:179-204`.
**Claim.** The view exists and the Provider reads it, but the redaction functions return their input unchanged, so `/audit` surfaces raw contact ids and unredacted `details_json`.
**Verdict — CONFIRMED.** Both functions are literal `RETURN input` no-ops (`IMMUTABLE`); V5's own header comment calls them stubs the redaction-hook ticket "can supersede." The confidentiality property is hollow.
**Why resolve:** the view is the documented redaction-safe trust boundary; the stub state is now load-bearing for `/audit`.
**Why it might not be an issue:** the consumer is a bot admin already authorized to see audit data; whether they should see plaintext contact ids is a deployment-threat-model call — the spec's view design says no.
**Disposition: FIX (High), bundled with A4.** Implement the two redactors per `security.md`; flip the code-side hook on. Coupled with the role split (A4).

### A12 · LOCAL-ONLY-GUARD — startup guard misses the embedding endpoint and provider-name overrides  *(opus-48 llm F1 high; deepseek llm F3 — placement)* — REPORTED
**Files:** `LlmRouterStartupGuard.java:96-103,183-204`; spec `docs/spec/llm.md:132-134`.
**Claim.** When `infochat.llm.local-only=true`, the guard scans per-task base-urls but not `infochat.embeddings.base-url` or provider-name overrides — a local-only deployment with a remote embedding endpoint silently ships post title+summary off-host. deepseek adds: the guard runs on Collector startup, spec says Provider.
**Verdict — CONFIRMED-BY-LOCUS** (prior cross-check; not re-read this pass).
**Why resolve:** local-only is the privacy invariant the spec sells operators; a guard closing 6 of 7 paths is no guard for the 7th. The promised "explicit confirmation log line on startup" is also missing.
**Why it might not be an issue:** default profiles point embedding at loopback Ollama; dormant unless an operator sets a remote base-url.
**Disposition: FIX (High).** Extend `validate` to cover the embedding base-url + provider-name overrides; reconcile the Collector-vs-Provider placement with the spec; add the confirmation log line.

### A13 · SIMPLEX-MENTION — non-injective recognition can spoof or suppress mentions  *(opus-48 msg F1, High)* — REPORTED
**File:** `SimpleXMentionParser.java:57-93`.
**Claim.** The canonicalization is not 1:1: two distinct queue-address strings can collide, so a non-mention reads as a bot mention or a real mention is suppressed.
**Verdict — REPORTED (single-reporter).** Constructing the collision requires SimpleX queue-address structure knowledge; locus exists.
**Why resolve:** D10 makes mentions the group-mode authorization trust anchor; the spec promises mentions can't be forged/suppressed.
**Why it might not be an issue:** real-world collision likelihood depends on the queue-address symbol set; falsifier (an actual colliding pair) not constructed.
**Disposition: FIX (High).** Replace canonicalization with a constant-time exact-bytes compare; add a regression test with the collision pair. Read the per-module detail before scoping.

### A14 · DIGEST-APPROVAL — DigestScheduler queries all non-removed groups, not just approved  *(opus-48 prov F1, mimo prov F1 — both High)* — GROUNDED
**File:** `DigestScheduler.java:175` — `SELECT id, timezone FROM groups WHERE removed_at IS NULL` (no `approval_status='approved'`).
**Claim.** Pending/rejected groups receive periodic digests. The roundtrip IT seeds only approved groups, so no fixture exposes the gap.
**Verdict — CONFIRMED.** The SELECT is verbatim at :175 with no approval filter. Two convergent reporters, both High; no falsifier survives.
**Why resolve:** only approved groups should receive digests; pending/rejected must be silent.
**Disposition: FIX (High).** Add `AND approval_status='approved'`; add a `pending` fixture group to `DigestRoundtripIT` asserting no delivery (closes the CT6 "test seeds only the passing path" theme).

### A15 · READY-PROMOTER-TX — `@Transactional promoteOne` bypassed by self-invocation  *(opus-48 coll F1 high; mimo-audit L4 low)* — GROUNDED
**File:** `ReadyPromoter.java:113` (`@Scheduled tick()`), `:124` (unqualified `promoteOne(...)` self-call), `:143-144` (`@Transactional public void promoteOne`), `:179` (`pg_notify`).
**Claim.** CDI interceptors don't fire on self-invocation, so the UPDATE and `pg_notify('new_post')` run as two auto-commits, voiding the documented same-transaction NOTIFY guarantee. The IT masks it by calling `promoteOne` through the proxy.
**Verdict — CONFIRMED.** `tick()` calls `promoteOne` unqualified (in-class), so the ARC proxy is bypassed; the method is `@Transactional`. The class javadoc itself asserts the same-transaction guarantee that self-invocation breaks.
**Why resolve:** a documented atomicity property does not hold on the production path; any future second mutation in `promoteOne` gets silent non-atomicity. mimo-audit's `Low` underweights it.
**Why it might not be an issue:** today `promoteOne` has one write + one NOTIFY, so non-atomicity is observable only on a crash in the gap.
**Disposition: FIX (High).** Move `promoteOne` to a separate injected bean (call through the proxy) OR manage the transaction explicitly (`setAutoCommit(false)`+commit). Update the IT to drive `tick()`, not the proxy method directly (CT6).

### A16 · IPV6-BLOCKLIST — transition ranges bypass the blocklist  *(opus-48 ssrf F1 high; opus-48-audit #5 low, narrower)* — REPORTED
**File:** `IpBlocklist.java:114-119,166-188,208-215`.
**Claim.** Only IPv4-mapped (`::ffff:a.b.c.d`) is decoded. 6to4 (`2002::/16`), Teredo (`2001::/32`), NAT64 (`64:ff9b::/96`), IPv4-compatible (`::a.b.c.d`) embed an IPv4 target but pass `isBlockedV6`. `http://[::127.0.0.1]/` would pass.
**Verdict — CONFIRMED-BY-LOCUS** (prior opus-47-full grep: only `isIpv4Mapped` decoder exists). opus-48 (primary) flags the broader 4-form set at High; opus-48-audit flags the narrower IPv4-compat+NAT64 subset at Low. Adjudicate at **High** (the broad set), with the audit's falsifier noted.
**Why resolve:** fail-closed egress must not depend on the host routing table; 6to4 can reach `169.254.169.254` on common cloud images.
**Why it might not be an issue (opus-48-audit falsifier):** IPv4-compatible is deprecated and not routed to loopback on modern Linux; NAT64 resolves internally only where a gateway exists. Practical exploitability is narrow.
**Disposition: FIX (High).** Decode all four embedded-IPv4 forms → `isBlockedV4`; extend the `IpBlocklistTest` matrix. Bundle with the SSRF-hardening family (A26, C-SSRF-304, B-HTTP-CLIENT, etc.).

### A17 · STOP-CMD — `/stop` is a no-op in group scope and uses the wrong scope key in DM  *(opus-47 prov F3, High)* — REPORTED
**File:** `StopCommandHandler.java:62-95,97-115`.
**Claim.** `resolveUserId` returns empty for any non-DM scope, so group `/stop` never cancels in-flight chat work; DM path hardcodes `scopeKind="dm"`/`scopeId=userId` (load-bearing). Spec §Chat mode (D35) commits to per-(user, scope) cancellation in groups.
**Verdict — REPORTED (single-reporter, locus cited).**
**Why resolve:** a group user's expensive chat/`/summary` run can't be cancelled; LLM tokens keep burning; per-(user, scope) cancellation contract silently violated.
**Disposition: FIX (High).** Resolve `scopeKind`/`scopeId` the way `InboundRouter.resolveChatScopeId` does (DM→userId, group→groupId); route cancellation through that key.

### A18 · HELP-NO-TIER — `/help` ignores spec-promised per-tier filtering  *(opus-47 prov F4, High)* — REPORTED
**File:** `HelpCommandHandler.java:46-74`.
**Claim.** Hardcodes three commands + assets; no permission/tier filtering; no group header; no probation footer. `commands.md` §Discovery requires per-tier filtering and bundle-composition from per-command keys.
**Verdict — REPORTED (single-reporter, locus cited).**
**Why resolve:** load-bearing UX promise absent; the bundle-completeness CI rule cannot fire against a static three-line list.
**Disposition: FIX (High).** Drive from a closed `(command, bundleKey, tier)` catalogue filtered by caller tier + scope; land the per-command help keys in bundles.

### A19 · KIND6-REPOST — repost edge `to_post` is never resolvable to a real post  *(opus-47 coll F3, High)* — REPORTED
**Files:** `Kind6Handler.java:142-167` (`to_post = nameUUIDFromBytes(eventId)`), `PostPersister.java:108-119` (`id = gen_random_uuid()`), `GetReferencesTool.java:67-80` (joins `pr.to_post = post.id`).
**Claim.** The edge target is a deterministic UUID-v3 of the event id; persisted posts use random UUIDs → the join can never match. Every kind-6 repost edge is structurally unresolvable; M1-100's user-visible payoff is absent.
**Verdict — REPORTED (single-reporter, locus cited; the UUID-derivation vs random-id mismatch is concrete).**
**Why resolve:** `architecture.md` §Source identity commits to resolving the link "if and when the original event is also seen."
**Disposition: FIX (High); plan-writer pass.** Option A (spec-closest): store `to_upstream_identifier`, leave `to_post` NULL until a resolver job fills it. Option B: make `post.id` deterministic for Nostr posts (smaller diff, changes id semantics).

### A20 · QUARANTINE-NOTIFY-MISSING — approve/reject procedures don't fire `quarantine_review`  *(opus-47 arch F2, High)* — REPORTED
**File:** `V25__quarantine_procedure_remediation.sql:46-65,67-104`.
**Claim.** `approve_quarantine`/`reject_quarantine` UPDATE status + write audit but emit no `pg_notify('quarantine_review',…)`. Every other quarantine writer fires via `QuarantineNotifyEmitter`; the procedures are the only writers that skip it. The Provider cursor never advances for these transitions; on restart the reconciler over-replays.
**Verdict — REPORTED (single-reporter; opus-47 quotes the procedure bodies — no `quarantine_review` NOTIFY).**
**Why resolve:** `architecture.md` §Inter-service communication commits the channel to fire on APPROVED/REJECTED; the spec's "comprehensive channel lets v2 attach behavior" rationale breaks.
**Why it might not be an issue:** cursor over-replay is harmless today (non-actionable rows); impact is restart bandwidth + the v2-forward-compat gap.
**Disposition: FIX (High); bundle CT2.** `V30` `CREATE OR REPLACE` both procedures adding the NOTIFY. Bundle with A21, the emitter enum-ification (B-EMITTER-ENUM), and V21/V25 jsonb_build_object (C-NOTIFY-CONCAT).

### A21 · STAGE2-PENDING-NOTIFY — PENDING fires at the wrong stage  *(opus-47 coll F4, High)* — REPORTED
**Files:** `Stage2VerdictHandler.java:269-281`, `QuarantineDao.java:66-90`.
**Claim.** Spec says PENDING fires on row insert; code defers it to Stage 2 and re-fires PENDING for every PENDING row on every quarantine verdict. Stage1→Stage2 BENIGN never fires PENDING; INJECTION/MALWARE/UNKNOWN fires it redundantly.
**Verdict — REPORTED (single-reporter, locus cited).**
**Why resolve:** aligns code with "PENDING insert"; removes the redundant signal; closes the BENIGN-fast-path gap.
**Why it might not be an issue:** redundant signals are bandwidth, not duplicate side effects (cursor CAS suppresses).
**Disposition: FIX (High); bundle CT2 with A20.** Move PENDING NOTIFY into `QuarantineDao.insert` (RETURNING id, same Stage-1 tx); drop `emitQuarantineNotifyForPendingRows`.

### A22 · NORMALIZEDPOST-JAVADOC — `sourceId` javadoc contradicts runtime value  *(mimo core F1, High)* — REPORTED
**Files:** `NormalizedPost.java:17-41`, `Fetcher.java:33`, `StreamSource.java:37`; related `FetchScheduler.java:408-419` (per-tick, not per-startup — opus-47 coll F9).
**Claim.** Javadoc describes `sourceId` as a DB key; runtime value is an opaque per-tick `dispatchKey`. An implementor keying state on it builds a bug.
**Verdict — REPORTED (single-reporter; opus-47 coll F9 independently flags the per-tick-vs-per-startup mismatch — convergent).**
**Why resolve:** misleading SPI contract on a public type.
**Why it might not be an issue:** current impls treat it as opaque, so it's documentation-only today.
**Disposition: FIX / DOC.** Rewrite the javadoc to "opaque per-tick token, do not key state on it"; fix the matching `dispatchKey` per-startup javadoc (opus-47 coll F9). Bundle into the CT3 documentation sweep. Low complexity.

### A23 · INMEMORY-CODEFORMATTING — `supportsCodeFormatting=false` contradicts design  *(mimo msg F1, High)* — REPORTED
**File:** `InMemoryAdapter.java:61`.
**Claim.** Design §6.6 intended InMemoryAdapter to exercise the code-formatting render path; `false` means no test adapter covers monospace rendering.
**Verdict — REPORTED (single-reporter).** Part of the broader capability-flag drift family (opus-47 msg F3/F7, opus-48 msg F4, mimo msg F1/F2/F3) — see CT5.
**Why resolve:** eliminates test coverage of a render path the design wanted exercised.
**Why it might not be an issue:** mimo's own note — the value may be empirically tuned; verify against design before reverting.
**Disposition: FIX (capability-flag bundle).** Decide design-vs-implementation per flag; align in one commit (amend design if the implementation value is correct).

### A24 · EMBEDDING-SIZE-CONTRACT — provider breaks the SPI's size-equals-input contract  *(opus-47 llm F3, High)* — REPORTED
**File:** `OpenAiCompatibleEmbeddingProvider.java:162-201`.
**Claim.** `EmbeddingProvider.embed` javadoc says "size equals texts.size()"; the impl returns a mismatched-size list with only a WARN, so a caller zip-indexing vectors to texts mis-attributes a vector silently.
**Verdict — REPORTED (single-reporter, locus cited).**
**Why resolve:** the SPI contract is load-bearing; the spec mandates per-batch retry on shape mismatch — detection belongs at the SPI seam.
**Why it might not be an issue:** no current caller depends on size equality.
**Disposition: FIX (High).** Throw `EmbeddingCallFailedException` on `results.size() != expectedCount`. Trivial. Bundle with A25.

### A25 · EMBEDDINGRESULT-ARRAY — mutable array in a record value type  *(opus-47 llm F4 high; mimo llm F1 med)* — REPORTED
**File:** `EmbeddingResult.java:14` (`record EmbeddingResult(float[] vector)`).
**Claim.** Record `equals`/`hashCode` use array reference identity (two identical embeddings unequal); the accessor returns the live array.
**Verdict — REPORTED (two-reporter; textbook record-with-array hazard).**
**Why resolve:** broken equality + shared-reference mutability defeats the wrapper's only justification.
**Why it might not be an issue:** no current caller uses `equals` or mutates the array; latent.
**Disposition: FIX (High), bundle with A24.** Either drop the wrapper (expose `List<float[]>`) or defensive-copy + `Arrays.equals/hashCode`.

### A26 · IPBLOCKLIST-SHIM — M1-025 backwards-compat constructor  *(opus-47 ssrf F1, High)* — REPORTED
**File:** `IpBlocklist.java:78-87` — the `IpBlocklist(Set<InetAddress>)` overload labeled "M1-025 test-mode constructor."
**Claim.** `engineering-rules-verbatim.md §7` forbids backwards-compat shims in greenfield M1; the shim self-identifies as the prohibited pattern.
**Verdict — REPORTED (single-reporter; locus cited).** This is a §7 violation by *shape*, not behavior.
**Why resolve:** the rule prohibits the pattern; the two test call-sites can move to the `Supplier` form in one line each.
**Why it might not be an issue:** harmless at runtime; single-reporter.
**Disposition: FIX (High, rule-driven).** Delete the overload; rewrite `IpBlocklistTest` call-sites to `new IpBlocklist(() -> Set.of(hostIp))`. Bundle with the SSRF family.

---

# Tier B — Confirmed real, Medium

### B-QUARANTINE-LISTENER-SQL · `getUpsertSql` interpolates an interval via string-concat  *(deepseek prov F1 high; kimi-k F6 med)* — REPORTED
**File:** `QuarantineReviewListener.java:82-97`. `"INTERVAL '" + ms + " milliseconds'"` spliced into SQL; the cached `upsertSql` field also has unsynchronized access.
**Verdict — REPORTED.** `ms` is a local `long` (safe today); the pattern normalizes an injection-unsafe style in a codebase that otherwise binds everything.
**Disposition: FIX (Medium).** Bind the interval via `?::interval`/`setString`; make the cache field memory-safe or drop the caching.

### B-SIGNALCONFIG-BOOT · `SignalConfig.validate()` misleading boot guarantee  *(deepseek msg F1, High→Med)* — REPORTED
**File:** `SignalConfig.java:63-79`. Boot-time `Files.exists`/`isWritable` is a single-instant check; javadoc promises misconfig fails at boot, but a post-boot remount/detach defeats it.
**Disposition: FIX (Medium).** Soften the javadoc to "boot-time only" and/or re-check at adapter-start; align with `SimpleXConfig` (which validates lazily — see C-SIMPLEXCONFIG-LIFECYCLE).

### B-HTTP-CLIENT · per-call `HttpClient` built per request + per redirect, never closed  *(opus-48-audit #2 med; kimi-k 4.2 info)* — REPORTED
**File:** `SsrfGuardedHttpClient.java:324-327` (inside the redirect loop).
**Verdict — CONFIRMED-BY-LOCUS.** JDK 21+ `HttpClient` is `AutoCloseable` and owns a `SelectorManager` thread + pool; per-call build with no close churns threads/FDs lagging GC and defeats connection reuse.
**Disposition: FIX (Medium).** Build one client before the redirect loop, reuse across hops, `close()` in `finally` after `readBounded`. Bundle with B-READBOUNDED-EXECUTOR (same lifecycle).

### B-READBOUNDED-EXECUTOR · per-call platform-thread executor in `readBounded`  *(opus-47 ssrf F4; mimo ssrf F1)* — REPORTED
**File:** `SsrfGuardedHttpClient.java:420-424,471`.
**Verdict — CONFIRMED-BY-LOCUS (two-reporter).** A fresh single-thread `ExecutorService` + OS platform thread per `get()` to enforce the per-read watchdog, on every outbound fetch.
**Disposition: FIX (Medium).** Use a static `Thread.ofVirtual().factory()` (JDK 25) per read; removes the executor/shutdownNow bookkeeping. Bundle with B-HTTP-CLIENT and B-DEADLINE-TOCTOU.

### B-LLM-OOM · unbounded `BodyHandlers.ofString()` in LLM/embedding providers  *(mimo-audit M1, Med)* — REPORTED
**Files:** `OpenAiCompatibleProvider.java:189`, `AnthropicProvider.java:148`, `OpenAiCompatibleEmbeddingProvider.java`.
**Verdict — CONFIRMED-BY-LOCUS.** These do not go through the SSRF `readBounded`; a multi-GB response → OOM → JVM crash.
**Why lower than an SSRF target:** the LLM endpoint is operator-configured (semi-trusted).
**Disposition: FIX (Medium).** Bounded body read (custom `BodySubscriber` or `Content-Length` guard), configurable 1–8 MiB. Bundle with B-LLM-RETRY.

### B-WS-LOCK · global SSRF resolver lock held across the WebSocket handshake  *(opus-48-audit #3; opus-47 ssrf obs)* — REPORTED
**Files:** `SsrfGuardedHttpClient.java:502-517` (`checkAndPinForWebSocket`), `NostrRelayConnection.java:255-266`.
**Verdict — CONFIRMED-BY-LOCUS.** `PinnedDnsResolver.Provider` is JVM-wide; the lock is held across `buildAsync(...).get(connectTimeout+1s)`, serializing all outbound connection establishment behind any one relay handshake. Holding the pin across the awaited connect is *correct* for SSRF; the cost is process-wide head-of-line blocking.
**Disposition: FIX-LOW now / WATCH.** Cheapest: document + keep `CONNECT_TIMEOUT` tight. Larger (own milestone): per-connection resolver removing the JVM-global lock. Don't bundle with small items.

### B-DEADLINE-TOCTOU · body-read deadline overshoot by up to one read-timeout  *(mimo-audit M2, Med)* — REPORTED
**File:** `SsrfGuardedHttpClient.java:430-441`.
**Verdict — CONFIRMED-BY-LOCUS, minor.** Deadline checked at loop top; `readFuture.get(readTimeout)` can then block a further `readTimeout` (e.g. 150s vs 120s). Bounded overshoot, not unbounded.
**Disposition: FIX-LOW.** Clamp each `get()` to `min(readTimeout, remaining-until-deadline)`. Bundle with B-HTTP-CLIENT / B-READBOUNDED-EXECUTOR.

### B-LLM-RETRY · no 429/503/Retry-After handling  *(mimo-audit M8, Med)* — REPORTED
**Files:** `OpenAiCompatibleProvider`, `AnthropicProvider`.
**Verdict — REPORTED.** All non-2xx throw identically; callers retry once immediately, re-hitting the limit.
**Disposition: FIX-LOW.** Parse `Retry-After` on 429/503, carry `retryAfterMs`, sleep before retry. Bundle with B-LLM-OOM.

### B-MEMBERSHIP-AUDIT · `MembershipEventHandler` audits after the mutation and swallows failure  *(opus-47 prov F7, Med)* — REPORTED
**File:** `MembershipEventHandler.java:105-127`.
**Verdict — REPORTED (single-reporter, locus cited).** Mutates state, then opens a fresh connection to audit, then logs-and-continues on failure — inverts Invariant 7 (audit-before-effect) for `MEMBER_LEFT`/`BOT_REMOVED`, unlike every other admin handler. The `was_group_admin` flag loss has a real downstream effect on `/unban` restoration.
**Disposition: FIX (Medium).** Wrap audit+mutation in one transaction (`BanCommandHandler` pattern); add Connection-accepting overloads.

### B-NOTIFY-RECONCILE · NOTIFY loss on reconnect not recovered until restart  *(mimo-audit M3, Med)* — REPORTED
**File:** `NewPostListener.java:164-211`.
**Verdict — REPORTED.** On a `getNotifications` throw the listener backs off before re-`LISTEN`; NOTIFYs in that window are lost, and the reconciler runs only at startup — a transient PG blip that doesn't restart the Provider leaves the live cursor permanently behind.
**Disposition: FIX (Medium).** Run `reconcile()` after every successful reconnect (confirm idempotency first).

### B-PROMOTE-FORUPDATE · `/promote` reads actor row without `FOR UPDATE`  *(opus-47 prov F6, Med)* — REPORTED
**File:** `PromoteCommandHandler.java:90-93,158-169`.
**Verdict — REPORTED (single-reporter, locus cited).** A concurrent `/revoke-admin` can commit between the admin check and the demote/promote, letting a just-demoted admin still swap the group admin. The sibling handlers (Grant/Revoke/ApproveGroup) already use `FOR UPDATE` ("M1-046 PERM-ESCAL closure"); Promote was missed.
**Disposition: FIX (Medium).** Add `FOR UPDATE` to the SELECT in the existing tx. Bundle with the LOOKUP-DUP / `UserRepository` work (B-LOOKUP-DUP).

### B-SAVE-UNBOUNDED · `/save -t` accepts unbounded personal-tag strings and counts  *(opus-47 prov F10, Med)* — REPORTED
**File:** `SaveCommandHandler.java:265-284,305-321`.
**Verdict — REPORTED.** No length/count cap on personal tags (bounded only by the 64 KB body cap). `/saved` interpolates them into outbound (bypasses the chat body cap); `listSaves` reads them into the prompt. The read side is capped; the write side is the symmetric obligation.
**Disposition: FIX (Medium).** Profile-driven per-tag length + per-call count caps at the parser; friendly-error bundle keys.

### B-SIGNAL-HUNG · no signal-cli hung-process detection  *(mimo-audit M4, Med)* — REPORTED
**File:** `SignalSubprocess.java`.
**Verdict — REPORTED.** The watchdog detects `Process.onExit()` but not a deadlocked-but-alive subprocess; JSON-RPC calls time out at 15s with no consecutive-timeout counter / escalation.
**Disposition: FIX-LOW.** Consecutive-timeout counter → restart after N; optional periodic `listAccounts` probe. Bundle with A10 (both are "Signal adapter alive but useless").

### B-DIGEST-CONCURRENCY · DigestWorker missed-slot atomicity / no in-flight guard  *(mimo-audit M9; deepseek prov F4)* — REPORTED
**Files:** `DigestWorker.java:69-75`, `DigestScheduler.java:130-158`.
**Verdict — REPORTED (two-reporter).** The audit-log INSERT commits before the sentinel cache insert + admin notify (no spanning tx) → a crash between them duplicates audit rows on the next tick; a tick overrun can overlap same-group processing.
**Disposition: FIX-LOW.** Wrap sentinel+audit in one tx; add an in-flight map keyed `groupId+":"+slotKind`.

### B-SIMPLEX-RACE · `sendCommand` race with `close()` throws raw RuntimeException  *(mimo-audit M7, Med)* — REPORTED
**File:** `SimpleXWebSocketClient.java:162-198`.
**Verdict — REPORTED.** Between the `closed` check and `ws.sendText()` another thread can `close()`; the `IllegalStateException` escapes `sendCommand`'s catch set (`InterruptedException`/`TimeoutException`/`ExecutionException`).
**Disposition: FIX-LOW.** `catch (RuntimeException) → MessagingException(PERMANENT)`.

### B-INVITE-COUNTER · brute-force counter keyed per-contact, not per-code  *(mimo-audit M6, Med)* — REPORTED
**File:** `InviteCodeConsumer.java:74-76`.
**Verdict — REPORTED.** Counter keyed `(adapter, contact_id)`; N contact ids ⇒ N× attempts on one code. The in-memory `breachAudited` set is unbounded.
**Why exploitability is narrow:** only matters if invite codes are low-entropy — verify the code format first. High-entropy random codes make per-contact keying acceptable (→ NON-ISSUE).
**Disposition: FIX-LOW (conditional on code entropy).** Per-code attempt counter + periodic `breachAudited` eviction.

### B-EMITTER-ENUM · `QuarantineNotifyEmitter` builds payload by string-concat, untyped  *(opus-47 arch F3; mimo coll F1 SECURITY; deepseek coll F5)* — REPORTED
**File:** `QuarantineNotifyEmitter.java:39-43`.
**Verdict — REPORTED (three-reporter).** Takes `String targetKind`/`newStatus` and concatenates without escaping; spec constrains both to closed enums; `PriceSnapshotStore` (peer emitter) *does* escape — internal inconsistency. Safe today by caller discipline (§7 doesn't require escaping impossible inputs); the hazard is the SPI shape.
**Disposition: FIX (Medium), bundle CT2.** Switch the signature to closed enums (`QuarantineNotifyKind`/`QuarantineNotifyStatus`); update four call sites. The type system then enforces the contract (§7a positive). Bundle with A20/A21 + C-NOTIFY-CONCAT.

### B-CONN-POOL-SIZE · no explicit connection-pool sizing  *(mimo-audit M10, Med)* — REPORTED
**Files:** both `application.properties`.
**Verdict — REPORTED.** Neither service sets `quarkus.datasource.jdbc.max-size` (Agroal default 20); Collector has 5+ scheduled workers + 1 long-lived lock connection, Provider has lock + `NewPostListener`.
**Disposition: FIX-LOW.** Declare explicit `max-size` per service with `%laptop`/`%vps` overrides. Pairs with the CONN-CHURN WATCH (D-CONN-CHURN).

---

# Tier C — Confirmed real, Low (hygiene / defense-in-depth / tech debt)

### C-JSON-DUP · hand-rolled JSON escaping duplicated (and C0-incomplete)  *(8-reporter convergence)* — GROUNDED
**Verdict — CONFIRMED.** Grep finds a JSON-escape-shaped helper in **12** main-source files: `PriceSnapshotStore`, `BootstrapAssetsLoader`, `BootstrapLoader`, `StartupReleaseOnStage2FailureWarn`, `ApproveGroupCommandHandler`, `AuditCommandHandler`, `BanCommandHandler`, `ExportPaginator`, `GrantAdminCommandHandler`, `RejectGroupCommandHandler`, `RevokeAdminCommandHandler`, `LlmOutputSanitizer`. Two issues: (1) **duplication** (a bug fixes in 12 places); (2) **C0 incompleteness** — several escape only `\ " \n \r \t` and emit other control chars raw → invalid JSON; `LlmOutputSanitizer.jsonEscape` does `c<0x20 → \u%04x` correctly and is the pattern to follow. Real external exposure: `SearchPostsTool.jsonStr` (feed titles carry C0 controls; the tool JSON fed back to the LLM can be malformed). Admin/internal sites dominate.
**Disposition: FIX (Medium, debt — the CT1 bundle leader).** Extract `app.zcat.infochat.core.log.JsonEscaper` (correct C0), delegate all 12 sites + the emitters + the A8 tool parser's output. Also folds opus-48-audit #6, kimi 1.2/1.3/3.3, deepseek SIM-1.

### C-LOOKUP-DUP · `lookupUser`/`lookupActorForUpdate` duplicated across 15+ handlers  *(deepseek-audit SIM-2, Med)* — REPORTED
**Verdict — REPORTED.** The `SELECT … FROM users WHERE adapter=? AND contact_id=?` pattern is re-implemented in 15+ handlers + `InboundRouter`, each returning a slightly different record. A `users`-schema change touches 20+ methods.
**Disposition: FIX-LOW (own ticket).** `UserRepository` bean (`findByAdapterAndContactId`, `…ForUpdate(Connection,…)`, `resolveUserId`); keep per-handler record types, share the SQL + row mapping. Bundle B-PROMOTE-FORUPDATE here.

### C-TAG-NORM-DUP · tag normalization duplicated ×3  *(mimo coll F2; opus-48 CT3; deepseek coll F6)* — REPORTED
**Files:** `BootstrapLoader.java:266-274`, `TaggerWorker.java:425-432`, `TagVocabulary.java:127-134` (carry `TODO(T1-D)` markers).
**Disposition: FIX-LOW.** `TagNormalizer.normalize` in `infochat-core`; remove the TODOs. Bundle with C-JSON-DUP/C-SHA256-DUP (all extract-to-core), and the stale `TODO(T1-D)` markers (C-TODOS).

### C-SHA256-DUP · two SHA-256-to-hex helpers  *(opus-47 coll F10; mimo coll F3; deepseek coll F6)* — GROUNDED (locus)
**Files:** `BootstrapLoader.java:285-289` (`String.format("%02x")`), `PostPersister.java:168-177` (`HexFormat.of()`).
**Disposition: FIX-LOW.** Shared `Sha256.hex(byte[])` in `infochat-core` using `HexFormat`. Bundle CT1.

### C-JSPECIFY-MISSING · missing `@NonNull`/`@Nullable` on public methods  *(kimi-k F2 high, F11; opus-47 llm F7, msg F6; deepseek ssrf F1, llm F4)* — REPORTED
**Verdict — REPORTED (wide convergence).** Many public/protected methods lack JSpecify annotations (`InboundContext.adapterName()` — javadoc says nullable but no `@Nullable`; `LlmProvider.generate` return; `SsrfGuardedHttpClient`/`PinnedDnsResolver` constructors; `MessagingException` constructors). `scripts/lint-contracts.py` baseline is empty (no grandfathering) yet not CI-enforced. §7a requires the contract.
**Disposition: FIX-LOW (one retroactive lint-pass ticket).** Run `scripts/lint-contracts.py`, annotate everything flagged, add to CI. Mechanical; pairs with C-DEFENSIVE-CODE (the §7+§7a pair).

### C-DEFENSIVE-CODE · defensive null-checks / catch arms inside the trust boundary  *(opus-47 llm F6; opus-48 ssrf F4, llm F3/F4, coll F3; deepseek prov F2)* — REPORTED
**Verdict — REPORTED (wide convergence).** Null-checks on internally-supplied collaborators (`LlmRouter` ctor/record, `SsrfGuardedHttpClient` resolver-seam ctor, `OpenAiCompatibleProvider` apiKey already coalesced, `AssetSnapshotFetcher` `catch (RuntimeException)`, CDI-injected fields in `HelpCommandHandler`/`InboundRouter`/`LlmOutputSanitizer`/`AssetCommandFamilyOracle`, dead `UserSnapshot.isBanned` field). §7 forbids these between internal classes; the reviewer applies it narrowly (boundary checks stay).
**Disposition: FIX-LOW (one sweep).** Distinguish boundary validation (keep) from internal guards (remove / replace with `@NonNull`). The `AssetSnapshotFetcher` catch moves to the outer loop with a distinct error class (opus-47 coll F8). Bundle with C-JSPECIFY-MISSING.

### C-NOTIFY-CONCAT · V21/V25 `pg_notify` + collector emitters build payload by raw concat  *(opus-47 core F4; deepseek coll F5)* — REPORTED
**Files:** `V21__quarantine_admin.sql:74-75`, `V25:62-63`; `QuarantineNotifyEmitter.java:41`, `ReadyPromoter.java:176`, `PriceSnapshotStore.java:99`.
**Disposition: FIX-LOW, bundle CT2.** Convert the SQL `pg_notify` payloads to `jsonb_build_object(...)::text`; route the Java emitters through `JsonEscaper` / enums.

### C-SECDEF-ACTOR-COLS · SECURITY DEFINER procedures drop denormalized actor columns  *(opus-47 core F1; mimo core F3)* — REPORTED
**Files:** `V24__identity_audit_remediation.sql:44-53`, `V25:58-60,100-101`.
**Verdict — REPORTED.** The procedures' `audit_log` INSERT omits the spec-mandated `actor_contact_id`/`actor_adapter` denormalization that `delete_preban_user` carries (with a justification comment). mimo frames it as "missing comment"; opus-47 as "missing columns."
**Disposition: FIX-LOW.** Add the columns (or, if intentionally omitted, the justification comment). New `CREATE OR REPLACE` migration.

### C-IPV6-CANON · IPv6 URL-literal hosts can't pass `canonicalizeHost`  *(opus-47 ssrf F2; deepseek ssrf F2)* — REPORTED
**File:** `SsrfGuardedHttpClient.java:269-279` (`IDN.toASCII` rejects brackets).
**Disposition: FIX-LOW.** Strip brackets before `IDN.toASCII`, re-add for the dial. Bundle SSRF family.

### C-SSRF-304 · 304/305/306 treated as redirects  *(kimi-audit 2.1, Med→Low)* — REPORTED
**File:** `SsrfGuardedHttpClient.java:340` (`status >= 300 && status < 400`).
**Verdict — REPORTED.** Any 3xx is followed; no bypass (pipeline re-gates), but narrows incorrectly. A 304/305/306 without Location → `SsrfPolicyException`.
**Disposition: FIX-LOW.** Narrow to 301/302/303/307/308. Bundle SSRF family.

### C-CLOSEDLIST-WS · closed-list strip is whitespace-literal  *(opus-48-audit #7; opus-48 prov F4; mimo-audit L10; deepseek prov F7)* — REPORTED
**File:** `LlmOutputSanitizer.java:187-209`.
**Verdict — REPORTED (four-reporter).** Multi-word tokens (`/invite create`) matched with `Pattern.quote` (literal single space); `/invite  create` evades. mimo L10 adds the Unicode-obfuscation angle (fullwidth `／`, ZWSP; LLM output isn't NFKC-normalized). Defense-in-depth only — real authorization is deterministic Java; these strings never execute.
**Disposition: FIX-LOW.** Compile multi-word entries with internal whitespace as `\s+`; optionally normalize LLM output before the strip.

### C-SANITIZER-PERF · `LlmOutputSanitizer` compiles 26 patterns per call  *(opus-47 prov F9, Med→Low)* — REPORTED
**File:** `LlmOutputSanitizer.java:187-209`.
**Disposition: FIX-LOW.** Move `Pattern.compile` to a `static final List<Pattern>`. Bundle with C-CLOSEDLIST-WS (same method).

### C-LASTADMIN-MSG · last-admin detection by SQLException substring  *(deepseek-audit SEC-3)* — GROUNDED (locus)
**Files:** `RevokeAdminCommandHandler.java:261` (`e.getMessage().contains("last_admin_protection")`), `BanCommandHandler.java` (same).
**Verdict — CONFIRMED.** Substring match on the V5 trigger message; fragile against any reword or pooler transformation → silent degrade to generic `IllegalStateException`.
**Disposition: FIX-LOW.** Prefer `RAISE … USING ERRCODE` + `getSQLState()`; confirm an IT exercises the trigger branch against real PG. Bundle with C-URLPROBE-MSG ("typed signals not string-sniffing").

### C-URLPROBE-MSG · `UrlProbe` maps failure modes by exception-message prefix  *(kimi-audit 3.2 — dropped by opus-47-full)* — GROUNDED
**File:** `infochat-provider/.../source/UrlProbe.java:95-96` — `message.startsWith("body read timeout") || message.startsWith("body read deadline")`.
**Verdict — CONFIRMED.** Branches on the `SsrfPolicyException` message text; a reword silently breaks the mapping. (Note: the file lives under `provider/source`, not `collector/fetch` as the old handout said.) Same fragility class as C-LASTADMIN-MSG.
**Disposition: FIX-LOW.** Introduce typed `SsrfPolicyException` subclasses / an enum reason; match on type. Share a ticket with C-LASTADMIN-MSG.

### C-USERINFO-SRC · `AddSourceArgs.parseUri()` accepts userinfo  *(kimi-audit 3.1)* — REPORTED
**File:** `AddSourceArgs.java:229-248`. Rejects missing scheme/host but not `getRawUserInfo()`; creds stored in DB (unusable — the SSRF gate rejects userinfo at fetch).
**Disposition: FIX-LOW.** Reject `getRawUserInfo() != null` at parse with a clear error.

### C-BIDI-GAP · normalization misses U+061C / U+200E / U+200F  *(mimo-audit L2)* — REPORTED
**Files:** `InboundRouter.java:962-965`, `Stage1Pipeline.java:283-294`.
**Disposition: FIX-LOW.** Extend the predicate to those three; NFKC doesn't remove them.

### C-REDACTOR-SEP · `Redactor` generic pattern bypassable with >5 separators  *(mimo-audit L3; deepseek core F4)* — REPORTED
**File:** `Redactor.java:52-54` (`[\"'\\s:=]{0,5}`).
**Disposition: FIX-LOW.** Widen to `{0,20}` or possessive; add a long-separator test. (deepseek frames it as backtracking — same locus.)

### C-NOTIFY-REGEX-UNANCHORED · NOTIFY payload regexes not anchored  *(kimi-k F7)* — REPORTED
**Files:** `NewPostListener.java:311`, `QuarantineReviewListener.java:270`.
**Disposition: FIX-LOW (folds into CT7).** Once B-EMITTER-ENUM normalizes payload shape, replace the parser regex with Jackson on structured payloads.

### C-STAGE2-CHECK · missing CHECK on `post.stage2_verdict`  *(mimo-audit L6)* — REPORTED
**File:** `V22__post_stage2_verdict.sql:9`.
**Verdict — REPORTED.** Closed set documented (`BENIGN/INJECTION/MALWARE/UNKNOWN`), unenforced. A DB boundary CHECK is the allowed carve-out (not "defensive code for impossible scenario").
**Disposition: FIX-LOW.** `CHECK (stage2_verdict IS NULL OR IN (…))` in a new migration (cannot edit applied V22).

### C-AUTOPROMOTE-TX · `GroupAutoPromoteService` eligibility check outside tx  *(mimo-audit L9)* — REPORTED
**File:** `GroupAutoPromoteService.java:71-83`. `isEligible` runs before `setAutoCommit(false)`; a user could be banned between check and INSERT (the `one_admin_per_group` index blocks double-promotion but not promoting a just-banned user).
**Disposition: FIX-LOW.** Move `isEligible` inside the tx.

### C-ACQUIRE-INT · `acquireUninterruptibly()` swallows interrupt  *(mimo-audit L7)* — REPORTED
**Files:** `TaggerWorker.java:214`, `EmbeddingWorker`, `EntityExtractorWorker`.
**Disposition: FIX-LOW.** `acquire()` + restore the flag. Bundle with C-DEAD-SEMAPHORE (same workers).

### C-DEAD-SEMAPHORE · dead semaphores in Tagger/EntityExtractor workers  *(opus-47 coll F7)* — REPORTED
**Files:** `TaggerWorker.java:160-205`, `EntityExtractorWorker.java:153-193`.
**Verdict — REPORTED.** `enumeratePending(maxConcurrency)` + serial loop means the `Semaphore(maxConcurrency)` never has >1 acquirer — dead code that misleads about the concurrency bound.
**Disposition: FIX-LOW (simplification).** Drop the semaphore; document batch+serial-loop as the bound. (Defer the virtual-thread fan-out alternative.)

### C-DIGEST-TZLOG · DigestScheduler silently skips invalid/null timezone  *(mimo-audit L1; kimi-k F10)* — REPORTED
**File:** `DigestScheduler.java:189-195` (`catch (Exception) → null`).
**Disposition: FIX-LOW.** WARN once on parse failure; narrow the catch to `DateTimeException` (kimi-k F10).

### C-DIGESTWORKER-CATCH · `DigestWorker.execute` broad `catch (Exception)`  *(kimi-k F8)* — REPORTED
**File:** `DigestWorker.java:69-74`. Suppresses programming errors alongside the degraded path.
**Disposition: FIX-LOW.** Narrow to `SQLException | MessagingException`.

### C-FETCHER-URLENCODE · pagination cursors concatenated into URLs without encoding  *(opus-47 coll F6; opus-48 coll F2; deepseek coll F1)* — REPORTED
**Files:** `BlueskyFetcher.java:110-117`, `RedditFetcher.java:108-114`.
**Verdict — REPORTED (three-reporter).** Untrusted upstream `cursor`/`after` concatenated raw; a cursor with `&`/`#`/`?` injects query params (same host, so survives the SSRF gate). `CoingeckoSnapshotSource` already uses `URLEncoder.encode` — local precedent.
**Disposition: FIX (low-medium).** `URLEncoder.encode` every query value.

### C-SIMPLEXCONFIG-LIFECYCLE · `SimpleXConfig.validate()` never called for idle adapters  *(deepseek msg F2; mimo-audit L14)* — REPORTED
**File:** `SimpleXConfig.java:73-88`. Lazy validation vs `SignalConfig`'s eager `@Startup`; a misconfigured SimpleX adapter fails only at adapter-start.
**Disposition: FIX-LOW.** Make `SimpleXConfig` a `@Startup`-validated bean, matching `SignalConfig`.

### C-SIGNAL-DRAIN · oversize-line char-at-a-time drain in Signal reader loop  *(deepseek msg F3)* — REPORTED
**File:** `SignalJsonRpcClient.java:87,326-370`.
**Disposition: FIX-LOW.** Bulk-skip oversize lines instead of per-char.

### C-MEMBERSHIP-SPI · `MessagingAdapter.onMembershipEvent` confused dual-shape SPI  *(opus-47 msg F2; opus-48 msg F4 — "dead surface")* — REPORTED
**File:** `MessagingAdapter.java:162-174`.
**Verdict — REPORTED.** Creates two incompatible dispatch shapes; opus-48 flags it as dead surface (no wired producer/consumer). Falsifier: D47 may require it.
**Disposition: RESOLVE or DROP — reconcile with D47 design first.**

### C-ADAPTER-CLASSIFY · "not connected" TRANSIENT (Signal) vs PERMANENT (SimpleX)  *(opus-47 msg F5)* / **C-CODEC-EXC** codec validators throw past `MessagingException` *(opus-47 msg F4; deepseek msg F4: `SignalAdapter.start` throws ISE)* / **C-CAPABILITY-DRIFT** capability flags drift from design *(opus-47 msg F3/F7; opus-48 msg F4; mimo msg F1/F2/F3)* — REPORTED
**Verdict — REPORTED (CT5 family).** Cross-adapter contract inconsistency: pick one classification per semantic state, align capability flags to design (or amend design), force codec validators to throw the SPI's checked exception.
**Disposition: FIX (capability-flag + contract-test bundle).** A cross-adapter contract test is the forcing function.

### C-FINALIZE-SHADOW · `finalize` SPI method shadows `Object.finalize()`  *(opus-48 arch F2)* — REPORTED
**File:** `MessagingAdapter.java:125`.
**Disposition: FIX-LOW.** Rename to `shutdown`/`stop`. Bundle with C-SPI-LIFECYCLE.

### C-SPI-LIFECYCLE · `MessagingAdapter` lacks `start()`/`stop()`; reflective dispatch with `catch(Throwable)`  *(mimo-audit L12)* — REPORTED
**Files:** `MessagingAdapter.java`, `MessagingStartup.java`.
**Disposition: FIX-LOW.** Add `default void start()/stop()`. Bundle with C-FINALIZE-SHADOW + C-ADAPTER-DUP-NAME.

### C-ADAPTER-DUP-NAME · `AdapterRegistry` accepts duplicate adapter names  *(opus-47 arch F5)* — REPORTED
**File:** `AdapterRegistry.java:150-159`. `simplex,simplex` wires the same adapter twice.
**Disposition: FIX-LOW.** Add a dedup gate (1.5) before resolution.

### C-CHATTOOL-COMPLETENESS · dispatcher doesn't validate registry vs advertised tools  *(deepseek prov F3)* — REPORTED
**File:** `ChatToolDispatcher.java:69-75`.
**Disposition: FIX-LOW.** Validate at construction that every system-prompt-advertised tool has a registered handler.

### C-LLMROUTER-INSTANCEOF · `LlmRouter.providerName` couples to impls via `instanceof`  *(opus-47 llm F8)* / **C-TASKKEY-DUP** task-key-segment triplicated *(opus-48 llm F5; deepseek llm F1; opus-47 obs)* — REPORTED
**Files:** `LlmRouter.java:249-258,298-315`, `AnthropicProvider.java:207-218`, `AnthropicProviderTest.java:221-230`.
**Disposition: FIX-LOW.** Move the key segment onto the `ModelTask` enum; drop the `instanceof` chain.

### C-MICROPROFILE-NULL · hidden `"null"` string sentinel in `MicroProfileConfigReader.get`  *(opus-47 llm F5; deepseek llm F5)* — REPORTED
**File:** `LlmRouter.java:396-399`.
**Disposition: FIX-LOW / DOC.** Comment the sentinel or remove the silent `toLowerCase().equals("null") → ""`.

### C-FINDFIRSTSTRING · `findFirstString` does an attacker-influenced key search  *(opus-48 msg F3)* — REPORTED
**File:** `SimpleXMessageCodec.java:520-582`.
**Disposition: FIX-LOW.** Read the known field instead of searching keys.

### C-SIGNAL-GROUP-DUP · Signal group handler duplicates DM decode, no wired producer  *(opus-48 msg F2)* — REPORTED
**Files:** `SignalGroupHandler.java:103-168`, `SignalMessageCodec.java:132-157`.
**Disposition: RESOLVE or DROP — confirm whether the group path is wired live (D47).**

### C-BODYCAP-ORDER · chat-mode body cap runs after DB writes  *(opus-48 prov F2)* — REPORTED
**File:** `InboundRouter.java:509-543`.
**Verdict — REPORTED.** The chat body cap runs after writes the spec forbids for oversized messages.
**Disposition: FIX.** Move the cap before the writes.

### C-PRICE-SCHEMA · `price_snapshot` PK diverges from spec, dedup invariant lost  *(mimo core F2)* / **C-PRICE-NOTIFY-ORPHAN** `new_price_snapshot` channel has producer, no consumer / spec cache absent *(deepseek arch F2; mimo arch F1)* — REPORTED
**Files:** `V17__price_snapshot.sql:35-52`; `PriceSnapshotStore.java:20-42`, `AssetSnapshotReader.java`.
**Verdict — REPORTED.** Surrogate PK `(id, captured_at)` dropped the spec's `(asset, sub_verb, captured_at)` dedup invariant with no replacement UNIQUE; the `new_price_snapshot` channel emits into a vacuum (no LISTEN consumer; spec cache layer absent).
**Disposition: FIX-LOW + spec reconciliation.** Either add the UNIQUE constraint + implement the consumer (Option B) or amend the spec to drop the channel (Option A). Decide intent first.

### C-SUMMARYANCHOR-SCOPE · `summary_anchor` omits `scope_kind`  *(opus-48 core F2)* — REPORTED
**File:** `V19__summary_anchor.sql:5-30`.
**Verdict — REPORTED.** Omits the `scope_kind` discriminator every other per-(user, scope) table carries.
**Disposition: FIX (verify first).** Confirm whether DM/group anchors can collide on `scope_id` before adding the column.

### C-V27-AUDIT-VERB · V27 writes an `audit_log.action` absent from `AuditAction`  *(opus-48 core F3)* — REPORTED
**File:** `V27__d47_remove_group_only.sql:51-52`.
**Disposition: FIX-LOW.** Add the verb to the `AuditAction` closed set (or change the migration).

### C-TRUNCATEALL · `PostgresSchemaTestBase.truncateAll()` omits tables  *(deepseek core F1)* — REPORTED
**File:** `PostgresSchemaTestBase.java:80-84`. Cross-test pollution risk.
**Disposition: FIX-LOW (test).** Add the missing tables.

### C-INGESTSPIS-TEST · `IngestSpisLoadTest` checks only compiler guarantees  *(opus-47 core F5)* — REPORTED
**File:** `IngestSpisLoadTest.java:20-39` (`Class.forName`/`isInterface`).
**Disposition: FIX-LOW.** Delete the test (asserts nothing the compiler doesn't).

### C-HTTPCLIENT-NOTIMEOUT · adapter `HttpClient.newHttpClient()` lacks default timeouts  *(kimi-k F9)* — REPORTED
**Files:** `ProductionAdapterBeans.java:138`, `NostrStreamSource.Registrar.java:282`.
**Disposition: FIX-LOW.** Add `connectTimeout`.

### C-NOSTR-BACKOFF-RANDOM · `NostrRelayConnection.backoffDelay` static but uses instance Random  *(deepseek coll F4)* — REPORTED
**File:** `NostrRelayConnection.java:354`.
**Disposition: FIX-LOW.**

### C-ASSETFETCHER-DUP · `AssetSnapshotFetcher` duplicates failure-counter logic  *(deepseek coll F2)* — REPORTED
**File:** `AssetSnapshotFetcher.java:228-297`.
**Disposition: FIX-LOW.** Share the `SourceRepository` failure-counter.

### C-NOSTR-INDEX · `latestPublishedAtEpochSeconds` lacks supporting index  *(opus-47 coll F5)* — REPORTED
**File:** `NostrStreamSource.java:439-458`.
**Verdict — REPORTED.** `SELECT MAX(published_at) … WHERE source_id=?` per reconnect; no `(source_id, published_at DESC)` index.
**Disposition: FIX-LOW.** Add the composite index (or in-memory cache).

### C-SIMPLEX-HANDLE-TABLE · SimpleXAdapter handle table grows unbounded  *(opus-47 msg F1, High→re-rated)* — REPORTED
**File:** `SimpleXAdapter.java:88-91,255-256,282-284`.
**Verdict — REPORTED (single-reporter, no OOM observed).** Per-correlation-id handle map with no expiry; slow memory growth, accelerable.
**Disposition: FIX-LOW.** Bounded LRU (`LinkedHashMap` access-order or Caffeine if approved).

### C-EXTRAHEADERS-REDIRECT · `extraHeaders` re-applied across cross-origin redirects  *(opus-47 ssrf F3)* — REPORTED
**File:** `SsrfGuardedHttpClient.java:319-335`.
**Verdict — REPORTED.** The only current `extraHeaders` use is a benign `Range`; the risk is shape — a future `Authorization` would ship to a redirect target. Browsers/curl strip sensitive headers cross-origin.
**Disposition: FIX-LOW.** Cross-origin scrub list (`Authorization`, `Cookie`, `Proxy-Authorization`) gated on same host+port+scheme. Bundle SSRF family.

### C-IDN-UNASSIGNED · `canonicalizeHost` uses `IDN.ALLOW_UNASSIGNED`  *(mimo ssrf F3)* — REPORTED
**File:** `SsrfGuardedHttpClient.java:273`.
**Disposition: FIX-LOW.** Drop `ALLOW_UNASSIGNED` in the security-critical path.

### C-URLREDACTOR-IPV6 · `UrlRedactor` omits brackets around IPv6  *(deepseek ssrf F3)* — REPORTED
**File:** `UrlRedactor.java:64`.
**Disposition: FIX-LOW.**

### C-SSRF-ERRMSG · indistinct constructor-validation error messages  *(opus-47 ssrf F6)* — REPORTED
**File:** `SsrfGuardedHttpClient.java:194-205` (same "timeout must be configured" for two knobs).
**Disposition: FIX-LOW.**

### C-SSRF-JAVADOC · class javadoc claims ws/wss rejected while supported; `HostInterfaceSet` snapshot javadoc stale  *(opus-47 ssrf F5; opus-48 ssrf F2/F3; deepseek-audit SEC-2; mimo ssrf F2)* — REPORTED
**Files:** `SsrfGuardedHttpClient.java:33-82,119-121`, `HostInterfaceSet.java:21-26`.
**Verdict — REPORTED (wide convergence, CT3).** `HostInterfaceSet` describes a construction-time snapshot that M1-026 replaced with a per-call Supplier.
**Disposition: DOC.** Rewrite both javadocs. Bundle CT3 documentation sweep.

### C-DAG-DOC · `09-reference.md` DAG claims sibling→core deps that don't exist  *(opus-47 arch F4; deepseek arch F1)* — REPORTED
**File:** `docs/design/09-reference.md:31-38`.
**Disposition: DOC.** Set `(none)` for the three sibling modules. Bundle CT3.

### C-MIGRATION-COMMENTS · V16/V7 grant-block comments reference relocated/never-created roles  *(opus-47 core F2/F3)* — REPORTED
**Files:** `V7__joins_post.sql:212-214` (`infochat_listen` never created), `V16__admin_notification_state.sql:67-73` (ThrottledAdminNotifier relocated).
**Disposition: DOC.** Bundle CT3.

### C-LANG-JAVADOC · `LangCommandHandler`/`FollowTagCommandHandler` javadoc describes removed short-circuit  *(opus-48 prov F5)* — REPORTED
**Files:** `LangCommandHandler.java:37-48`, `FollowTagCommandHandler.java:37-41`.
**Disposition: DOC.** Bundle CT3.

### C-ASSET-LOCALE · asset tokens lowercased with JVM-default locale  *(opus-47 prov F13)* — REPORTED
**File:** `AssetHandler.java:156,160`.
**Disposition: FIX-LOW.** `Locale.ROOT`.

### C-LEVENSHTEIN · `GroupTimezoneCommandHandler` recomputes distances  *(mimo-audit L17)* — REPORTED
**File:** `GroupTimezoneCommandHandler.java:206-210`.
**Disposition: FIX-LOW.** Precompute into a `Map<String,Integer>`. (Admin-rate command — low value.)

### C-INFOCHATPROFILE-DUP · `InfochatProfile` duplicated across services  *(mimo-audit L11)* — REPORTED
**Files:** collector + provider copies (provider's comment promises consolidation "once infochat-core lands" — it has).
**Disposition: FIX-LOW.** Move to `infochat-core`; delete both.

### C-ADAPTER-BACKOFF · busy-wait adapter startup probes  *(kimi-audit 3.4)* — REPORTED
**Files:** `SimpleXAdapter.java:369-391`, `SignalAdapter.java:340-356`.
**Disposition: FIX-LOW.** Exponential backoff capped at the deadline.

### C-GROUPLOOKUP-THROW · `lookupGroupId` throws on missing group (timing oracle)  *(kimi-audit 3.5)* — REPORTED
**File:** `InboundRouter.java:740-756`.
**Disposition: FIX-LOW.** Return `Optional<UUID>`; silent-drop / specific-log the empty case.

### C-V28-UPDATE · unbatched full-table UPDATE in V28  *(mimo-audit M5→Low)* — REPORTED
**File:** `V28__post_entity.sql:32`.
**Verdict — REPORTED.** One-shot `UPDATE post SET entity_done=TRUE WHERE tagger_done=TRUE` on the partitioned table; one-time backfill on a new/empty M1 table. V28 is already applied — rewriting an applied migration is itself risky.
**Disposition: WATCH / DOC.** Document expected row count; apply the batched pattern only to *future* backfills.

### C-TODOS · stale `TODO(T1-D)` markers in production  *(mimo-audit L15)* — REPORTED
**Files:** `TagVocabulary.java:125`, `TaggerWorker.java:423`, `BootstrapLoader.java:92,265`, `InviteCodeConsumer.java:182`.
**Disposition: FIX-LOW.** Resolve via the `TagNormalizer` extraction (C-TAG-NORM-DUP) + convert the Micrometer-metric TODO to a tracked ticket.

### C-TEST-INNERCLASS · test files exceed the 3-inner-class guideline  *(mimo-audit L16)* — REPORTED
**Files:** `InboundRouterProbationOrderingTest` (13), `…IntakeOrderingTest` (11), `…ContactIdRedactionTest` (10), `…ConfirmCancelTest` (8), `DigestWorkerTest` (8), `…NormalizeTest` (7).
**Verdict — CONFIRMED-by-convention.** Matches memory `feedback_avoid_test_inner_classes.md`.
**Disposition: FIX-LOW (test-only).** Extract shared fakes to top-level package-private doubles. Own ticket; not bundled with production fixes.

### C-OPTIONAL-IMPORT · unused `Optional` import  *(opus-47 llm F9)* — REPORTED
**File:** `AnthropicProvider.java:24`.
**Disposition: FIX-LOW.** Delete the line (bundle with A2).

---

# Tier D — Disputed, false positives, or "fixing it" would violate a rule

Recorded with reasoning so they are not re-raised.

### D-SETLOCAL-SQLI · "SQL injection via `SET LOCAL infochat.actor_id`"  *(kimi-audit 1.1 high; kimi-k F3; deepseek prov F5)* — GROUNDED
**Files:** 11 `SET LOCAL infochat.actor_id = '…'` sites across 8 command handlers.
**Verdict — NON-ISSUE, and the proposed fix doesn't work.** (1) `actor.id` is a `java.util.UUID` from the DB; `UUID.toString()` is RFC-4122 (hex+hyphens) — no character can break the quoted literal. kimi's own falsification admits it's loaded as a UUID. (2) The proposed `ps.setObject(1, actor.id)` against `SET LOCAL … = ?` is **not portable** — Postgres rejects bind parameters for `SET LOCAL`'s value in the simple protocol; the only working parameterized form is `set_config('infochat.actor_id', ?, true)`, which `UnbanCommandHandler`'s javadoc documents was deliberately rejected to match an acceptance-grep predicate. (3) Hardening against "a future path that lets attacker strings reach `actor.id`" defends an impossible scenario across an internal boundary — §7 forbids it.
**Disposition: NON-ISSUE.** If anyone widens `actor.id` away from `UUID`, *that* change carries the obligation. (Optional DOC: add the `SET LOCAL` caveat comment to handlers lacking it.)

### D-UTF8-CAP · "body-size cap bypass via unpaired surrogates" + "SIOOBE at string end"  *(deepseek-audit SEC-1 med; kimi-audit 2.3 med)* — REPORTED (prior-grounded)
**File:** `InboundRouter.exceedsUtf8ByteLength:817-836`.
**Verdict — BOTH FALSE.** On a high surrogate the function does `count += 4; i++`. (a) deepseek's undercount assumes a lone surrogate costs 3 actual bytes, but Java's `getBytes(UTF_8)` replaces an unpaired surrogate with `?` = **1 byte**; the function counts 4 + skips ≤3 → always ≥ actual (it *over*-counts, never under). No smuggling. (b) kimi's SIOOBE is unreachable: when the last char is a high surrogate at `i=len-1`, the body sets `i=len`, the for-increment makes `i=len+1`, and the loop condition `i<len` is checked before `charAt` — `charAt` is never called out of bounds.
**Disposition: NON-ISSUE (false positive).** No change (a cosmetic low-surrogate check has zero behavioural effect).

### D-PINNED-CLOSE · "`PinnedDial.close()` not idempotent"  *(kimi-audit 2.2 med)* — REPORTED
**File:** `SsrfGuardedHttpClient.PinnedDial.close:582-587`.
**Verdict — NON-ISSUE per §7.** `close()` is documented single-shot and the only caller uses try-with-resources (closes exactly once). A double-close needs a caller that both explicitly calls `close()` *and* wraps in TWR — no such caller exists. kimi's falsification describes a hypothetical caller, not actual code. An `AtomicBoolean` guard is defensive code for an internal scenario that cannot occur.
**Disposition: NON-ISSUE.** Note the single-shot contract in design for the next WS-adapter author.

### D-TREEMAP-ALLOC · "TreeMap allocation per cache-key"  *(deepseek-audit PERF-2 low)* — REPORTED
**File:** `ChatToolDispatcher.java:116-117`.
**Verdict — NON-ISSUE (the report walks it back).** `new TreeMap<>(args)` over a 1–3-entry map, ≤10×/turn; deepseek concludes "the TreeMap approach is actually the safest for deterministic ordering." The current form is correct for a deterministic cache key.
**Disposition: NON-ISSUE.** Changing it trades correctness clarity for unmeasurable savings (§Simplify-aggressively).

### D-MISSING-V20 · "missing Flyway migration V20"  *(kimi-audit 2.5 med)* — REPORTED
**Verdict — NON-ISSUE functionally.** Flyway tracks applied versions and tolerates gaps; V19→V21 runs with no error. No runtime/ordering consequence.
**Disposition: DOC (optional).** One-line note that V20 was intentionally skipped, if it was.

### D-STAGE1-BACKTRACK · "Stage1RegexSet pathological backtracking"  *(kimi-audit 2.6 med)* — REPORTED
**File:** `Stage1RegexSet.java`.
**Verdict — ALREADY MITIGATED; the report agrees.** `.{0,40}` + DOTALL could backtrack, but the documented defense is the Stage-1 wall-clock watchdog (verified elsewhere to fire on JDK 25 via `InterruptibleCharSequence`). kimi: "No code change required if the watchdog is deemed sufficient." The only live concern is the `Stage1WatchdogIT` 50ms-cap flake — already tracked in memory `project_stage1watchdogit_flake.md`.
**Disposition: NON-ISSUE / WATCH.** Keep the watchdog timeout profile-tuned.

### D-TOOL-LEAK · "multi-line TOOL_CALL leaks JSON args to user"  *(mimo-audit H2, reported High)* — REPORTED (prior-grounded)
**File:** `ChatAgent.java:49-51` (strip), `:159-160` (two-pass strip).
**Verdict — MOSTLY FALSE as described; a narrow residual is real but Low.** mimo's example (`TOOL_CALL: searchPosts\n{"tags":["crypto"]}`) is *already handled*: the first strip pass runs `TOOL_CALL_PATTERN` (DOTALL, `\s+` between name and body), which matches a well-formed call whose JSON is on the next line. mimo only looked at the second pattern (`TOOL_CALL:.*`, no DOTALL) and missed the DOTALL first pass. The only residual leak is a *malformed* multi-line call (no closing `}`), where the first pass can't match and the second strips only to end-of-line, leaving the broken fragment — and only a JSON-ish fragment leaks, nothing executes.
**Disposition: FIX-LOW (not High).** Make the broad strip DOTALL-aware / anchor it. **Dissolved entirely by the A8 Jackson rewrite** (balanced-brace extraction) — list as an A8 sub-goal, no separate ticket.

### D-CONN-CHURN · per-message DB connection churn / N+1  *(opus-47 prov F5 high; deepseek-audit PERF-1 low; mimo-audit M11 med)* — GROUNDED (mechanism)
**Files:** `InboundRouter` intake path + per-step checks.
**Verdict — CONFIRMED as observation, NOT a bug. Reporters disagree (low/med/high).** A single inbound opens 6–10 short-lived pool connections. This is *deliberate*: `BanCheck.isBanned` (step 4.5) must see the freshest `is_banned` independent of the step-1 snapshot (documented TOCTOU closure). Agroal handles short-lived checkouts fine at v1 RSS-cadence rates. Consolidating risks re-introducing the staleness races the design closed on purpose.
**Disposition: WATCH** (no structural M1 ticket) **+ B-CONN-POOL-SIZE FIX-LOW** (declare explicit `max-size`). Profile pool waits if throughput scales; only then consider sharing a connection across steps that read the same row.

### D-CIRCUIT-BREAKERS · "no circuit breakers anywhere"  *(mimo-audit L19)* — REPORTED
**Verdict — BY DESIGN.** The D42 failure-counter state machine is the chosen v1 degradation mechanism; adding Resilience4j/MP-FT is a v2 architecture decision and a dependency addition (memory `feedback_dependency_approval.md`).
**Disposition: NON-ISSUE.** Raise narrowly with dependency justification if a specific path needs breaker semantics.

### D-DUP-MSG-DEP · "duplicate `infochat-messaging-adapter` dependency in provider pom"  *(kimi-k F1, reported High)* — GROUNDED
**File:** `infochat-provider/pom.xml:27` and `:53-56`.
**Verdict — FALSE POSITIVE.** The two entries are distinct: `:27` is the default jar; `:53-56` is `<type>test-jar</type><scope>test</scope>` (the messaging-adapter's protocol-shaped test doubles), with a `dependencyManagement` comment explaining Maven does not inherit the version onto a `test-jar` request. Maven treats them as separate dependencies — the canonical idiom for "production code + test fixtures of the same module." kimi-k missed the `<type>`/`<scope>` discriminator.
**Disposition: NON-ISSUE.** No change.

### D-BOOTSTRAP-PATH · "BootstrapLoader path traversal"  *(mimo-audit L5)* — REPORTED
**File:** `BootstrapLoader.java:105-106,124`.
**Verdict — WEAK; borderline NON-ISSUE.** The path is an *operator-supplied config value* read at startup; the operator already controls the process and filesystem, so "traversal" to a file they could name directly is not a privilege boundary.
**Disposition: FIX-LOW (optional) / NON-ISSUE.** If trivial, `toAbsolutePath().normalize()` for tidy logs; do not invent a containment root the spec doesn't define.

### D-PROGRESS-NOTIFIER · "`ProgressNotifier` SPI has zero implementations"  *(mimo-audit L13)* — REPORTED
**File:** `ProgressNotifier.java`.
**Verdict — UNCONFIRMED INTENT.** May be an intentional SPI seam for later adapters (progress is an adapter capability).
**Disposition: WATCH / verify intent before deleting.**

### D-PERF-TAIL · workload-scaling perf items with no current symptom  *(mimo-audit L18, L34, L35)* — REPORTED
- `FetchScheduler`/`DigestScheduler` unbounded result sets (no LIMIT) — fine at v1 scale.
- `DigestScheduler` 2×N queries per tick.
- `NostrDedupFilter` 10K entries/source.
**Disposition: WATCH.** Pagination/batching only needed at scale.

### D-LOGGING-MIX / D-HTTP2 / D-NOSTR-SCHEME · info-level observations  *(kimi-k 4.1/4.2/4.3)* — REPORTED
- SLF4J vs JBoss-Logging mix — Quarkus bridges both; cosmetic. **NON-ISSUE** (cross-cutting churn vs §Surgical-changes).
- No HTTP/2 pinning in SsrfGuardedHttpClient — defensible but speculative. **WATCH**.
- `NostrStreamSource.parseRelays` defers ws/wss validation to `checkAndPinForWebSocket` — fail-closed already; earlier validation only improves logs. **FIX-LOW at most.**

### D-AUDIT-INSERT-DUP · "audit-insert pattern duplicated"  *(deepseek-audit SIM-3)* — REPORTED
**Verdict — already a known deferral.** `BanCommandHandler` javadoc: "the M1-041 `AuditLogWriter` consolidation is deferred."
**Disposition: track under the existing M1-041 deferral.** No new analysis.

### D-WATCHDOG-FLAKE · `Stage1WatchdogIT` 50ms cap marginal  *(memory, not a run finding)*
**Disposition: DROP from this handout — already tracked** in memory `project_stage1watchdogit_flake.md`.

---

# Suggested ticket grouping (a starting point for the user, not a decided plan)

> The user decides bundle granularity. Criticals (A1–A8) should each be their own
> ticket; the rest cluster by code locus / shared fix.

### Priority 0 — blocks production
1. **V30 partitions Jun+Jul 2026** (A1) + follow-up `@Scheduled` monthly partition creator. Check IT reds first.
2. **`infochat.reeval.*` keys in main config** (A5) + CI guard for `@ConfigProperty(name="infochat.*")` coverage.
3. **InstanceLockGuard held-session liveness** (A9) + collector/provider `InstanceLockGuard` de-dup. *Ticket text targets the held session, not "add a heartbeat."*

### Priority 1 — security / correctness criticals
4. **Anthropic family** (A2 + A2b + C-OPTIONAL-IMPORT) — header names + test alignment + narrow catch.
5. **Per-adapter reply target** (A3) — plan-writer pass.
6. **DB role switch + audit-view redaction** (A4 + A11) — large, plan-writer pass; expect IT failures.
7. **Asset-command extensibility** (A6).
8. **ReEvaluationJob enumerate filter + cap-exhaustion transition** (A7) + IT.
9. **DigestScheduler `approval_status` filter + negative-case fixture** (A14, CT6).
10. **ReadyPromoter transaction boundary + IT through `tick()`** (A15, CT6).
11. **ChatAgent parseToolArgs via Jackson + dispatcher catch widening + TOOL-LEAK** (A8 + A8b + D-TOOL-LEAK) — plan-writer pass.
12. **Signal handler isolation + hung-process detection** (A10 + B-SIGNAL-HUNG).

### Priority 2 — security / correctness mediums & highs
13. **CT1 shared-helper bundle** (C-JSON-DUP + C-TAG-NORM-DUP + C-SHA256-DUP) — biggest line-count reduction.
14. **`quarantine_review` channel completeness (CT2)** (A20 + A21 + B-EMITTER-ENUM + C-NOTIFY-CONCAT).
15. **SSRF hardening bundle** (A16 + A26 + C-IPV6-CANON + C-SSRF-304 + C-EXTRAHEADERS-REDIRECT + C-IDN-UNASSIGNED + C-SSRF-ERRMSG + B-HTTP-CLIENT + B-READBOUNDED-EXECUTOR + B-DEADLINE-TOCTOU).
16. **local-only guard + remote-embedding confirmation log** (A12).
17. **SimpleX mention canonicalization** (A13).
18. **`/stop` scope fix** (A17); **`/help` per-tier filtering** (A18).
19. **Kind-6 repost resolution** (A19) — plan-writer pass.
20. **EmbeddingResult + EmbeddingProvider size contract** (A24 + A25).
21. **LLM robustness** (B-LLM-OOM + B-LLM-RETRY).
22. **NOTIFY-reconcile after reconnect** (B-NOTIFY-RECONCILE).
23. **Membership audit-before-effect** (B-MEMBERSHIP-AUDIT).
24. **UserRepository extraction** (C-LOOKUP-DUP) + `/promote FOR UPDATE` (B-PROMOTE-FORUPDATE) — own ticket.
25. **`/save` tag caps** (B-SAVE-UNBOUNDED).
26. **IpBlocklist M1-025 shim removal** (A26 — or fold into the SSRF bundle).
27. **JSpecify retroactive pass + lint CI** (C-JSPECIFY-MISSING); **defensive-code sweep** (C-DEFENSIVE-CODE) — CT4 pair.

### Priority 3 — small hardening, capability flags, documentation
28. **Adapter capability/contract bundle (CT5)** (A23 + C-ADAPTER-CLASSIFY + C-CODEC-EXC + C-CAPABILITY-DRIFT + cross-adapter contract test).
29. **Adapter SPI lifecycle** (C-FINALIZE-SHADOW + C-SPI-LIFECYCLE + C-ADAPTER-DUP-NAME).
30. **Fetcher URL-encoding** (C-FETCHER-URLENCODE).
31. **Digest hygiene** (B-DIGEST-CONCURRENCY + C-DIGEST-TZLOG + C-DIGESTWORKER-CATCH).
32. **Typed SSRF signals** (C-URLPROBE-MSG + C-LASTADMIN-MSG).
33. **Data/schema** (C-STAGE2-CHECK + C-SUMMARYANCHOR-SCOPE + C-V27-AUDIT-VERB + C-SECDEF-ACTOR-COLS + C-PRICE-SCHEMA/C-PRICE-NOTIFY-ORPHAN — decide intent first).
34. **Concurrency** (C-AUTOPROMOTE-TX + C-ACQUIRE-INT + C-DEAD-SEMAPHORE + B-SIMPLEX-RACE).
35. **Pool sizing** (B-CONN-POOL-SIZE) — pairs with the D-CONN-CHURN WATCH.
36. **Documentation sweep (CT3)** (C-SSRF-JAVADOC + C-DAG-DOC + C-MIGRATION-COMMENTS + C-LANG-JAVADOC + A22/dispatchKey javadoc + C-MICROPROFILE-NULL).
37. **Tidy** (C-INFOCHATPROFILE-DUP + C-LLMROUTER-INSTANCEOF/C-TASKKEY-DUP + C-ASSET-LOCALE + C-LEVENSHTEIN + C-ADAPTER-BACKOFF + C-GROUPLOOKUP-THROW + C-NOSTR-INDEX + C-SIMPLEX-HANDLE-TABLE + C-ASSETFETCHER-DUP + C-NOSTR-BACKOFF-RANDOM + C-HTTPCLIENT-NOTIMEOUT + C-FINDFIRSTSTRING + C-CHATTOOL-COMPLETENESS + C-SANITIZER-PERF + C-CLOSEDLIST-WS + C-BIDI-GAP + C-REDACTOR-SEP + C-USERINFO-SRC + C-SIMPLEXCONFIG-LIFECYCLE + C-SIGNAL-DRAIN + C-TODOS + C-TRUNCATEALL + C-INGESTSPIS-TEST + C-URLREDACTOR-IPV6).
38. **Body-cap ordering** (C-BODYCAP-ORDER); **invite per-code counter** (B-INVITE-COUNTER, gate on code entropy).
39. **Confirm-or-drop SPI surfaces** (C-MEMBERSHIP-SPI + C-SIGNAL-GROUP-DUP + D-PROGRESS-NOTIFIER) — reconcile with D47 design before deleting.
40. **Test-debt** (C-TEST-INNERCLASS) — test-only, own ticket.

### Not a ticket (record only)
D-SETLOCAL-SQLI, D-UTF8-CAP, D-PINNED-CLOSE, D-TREEMAP-ALLOC, D-MISSING-V20 (DOC), D-STAGE1-BACKTRACK, D-CIRCUIT-BREAKERS, D-DUP-MSG-DEP, D-BOOTSTRAP-PATH (optional), D-LOGGING-MIX, D-AUDIT-INSERT-DUP (existing M1-041 deferral), D-WATCHDOG-FLAKE (existing memory). D-CONN-CHURN and D-PERF-TAIL are WATCH.

---

## Auditor calibration notes (for future rounds)

- **opus-47** — the richest run (5 of 8 criticals, the deepest spec-vs-code grounding). Findings were real and well-calibrated; its provider/collector/architecture reports are the most ticket-ready.
- **opus-48** — precise on the genuine highs (audit-view stub, local-only embedding gap, ReadyPromoter self-invocation, DigestScheduler filter); strong on test-masks-defect (CT6) and stale-javadoc (CT3) themes.
- **opus-48-audit** — sharpest on the one high-impact functional bug (TOOL-ARGS array→CCE), correctly severity-rated; its SSRF resource findings (HttpClient leak, WS-lock) are real.
- **deepseek** — found real debt (duplication, NOTIFY drift); its primary security finding was low and its audit's flagship (UTF8-CAP bypass) is arithmetically **false** (mis-modeled Java's lone-surrogate encoding as 3 bytes; it's 1 via `?`).
- **kimi-k** — strong on parser fragility; weak on calibration: its top audit finding (SETLOCAL-SQLI) is a non-issue with an unworkable fix, its SIOOBE claim is unreachable, and its primary F1 (duplicate pom dep) is a false positive (missed the test-jar discriminator). Treat kimi severities as one notch high until grounded.
- **mimo** — widest net; caught the genuine Critical (partitions). But its two audit flagships carried **wrong mechanisms** (C2 heartbeat "never refreshed" — it is, via `HeartbeatScheduler`; H2 multi-line TOOL_CALL leak — already handled for well-formed calls). Every mimo finding needs the mechanism re-derived before ticketing.

*Consolidation grounded against the live tree on 2026-06-02; no source files modified.*
