# Deep code review v3 — unified, verified report

**Date:** 2026-06-09
**Sources:** `opus-48/` (full 7-report run), `mimo/full-2026-06-09-0148/` (full 7-report run),
`deepseek/` (architecture review only — its 6 module sub-agents failed to spawn, so deepseek
contributes 4 architecture findings, not a module sweep).
**Purpose:** every finding below was independently re-checked against the working tree (main
checkout, not worktrees) before inclusion. Verdicts and the evidence that settles them are
recorded per finding. Suggested fixes are *inspiration for ticket drafting*, not committed designs.

## How to read the verdicts

- **CONFIRMED** — the code matches the reviewer's claim; I read the cited lines.
- **CONFIRMED (nuance)** — the defect is real but the framing needs adjustment before it becomes a ticket.
- **RECONCILED** — two reviewers flagged the same code with conflicting fixes; I picked a direction and say why.
- **OVER-CLAIMED** — part of the finding is false; scope narrowed to the true part.
- **NON-ACTIONABLE** — verified, but the reviewer's own recommendation is "no change" / doc-only.

Findings the two full reviewers raised in common are noted; agreement raises confidence but the
verdict still rests on the code read, not the vote count.

---

## Proposed tickets

Severity is the higher of the two reviewers' where they overlap. "Commit prefix" follows
CLAUDE.md: `M1-NNN` for code/tests/migrations/spec-with-code; `spec:` / `process:` for pure docs.

### Tier 1 — security & correctness (do first)

#### T1. Signal inbound body-byte cap is not enforced  ·  [high] SECURITY  ·  M1 ticket
- **Verdict:** CONFIRMED. The only Signal inbound bound is `MAX_INBOUND_LINE_CHARS = 16_384`
  (`SignalJsonRpcClient.java:108,547`), a UTF-16 **char** cap on the whole JSON-RPC envelope line.
  `SignalMessageCodec.extractDm` (`:195-203`) builds the body with no byte check. SimpleX *does*
  enforce the cap on the decoded body in UTF-8 bytes (`SimpleXMessageCodec.java:364,475`,
  `MAX_INBOUND_TEXT_BYTES = 16_384`). `SignalAdapter` declares `maxInboundMessageBytes = 16_384`
  (`:81`) and the line-cap comment (`SignalJsonRpcClient.java:100`) falsely claims it "Matches" that
  capability. So the declared capability is unenforced on one of the two production adapters, and the
  gap is invisible in a SimpleX-only test run.
- **Source:** opus `05#F1`.
- **Suggested approach:** add `MAX_INBOUND_TEXT_BYTES` to `SignalMessageCodec`, gate the decoded body
  (DM path in `extractDm`, group path in `SignalGroupHandler`) in UTF-8 bytes, mirroring SimpleX. Keep
  `MAX_INBOUND_LINE_CHARS` as the coarse unterminated-line OOM guard but fix its comment to stop
  claiming it implements the capability.

#### T2. Signal `canonicalizeAci` accepts arbitrary wire strings as contact ids  ·  [high] SECURITY  ·  M1 ticket
- **Verdict:** CONFIRMED (nuance). `canonicalizeAci` (`SignalMessageCodec.java:240-242`) only
  lowercases; its own javadoc states it "Returns the input untouched if it does not parse as a UUID."
  `extractDm:203` and the group path pass the result straight into `ReceivedDm`/`Identity` with no
  validation, so an arbitrary `sourceUuid` becomes a permanent `(adapter, contact_id)` join-key value.
  SimpleX validates every inbound id against its queue-address charset; Signal has no analogous gate.
  **Nuance for the ticket:** the javadoc deliberately leaves acceptance "to the caller … (e.g. legacy
  phone-number sources during account migration)." So the fix is a *caller-side* decision, and the
  acceptance criteria must say whether phone-number ACIs are a real v1 case (accept E.164 + UUID via a
  charset gate) or not (UUID-only, drop others). Don't hard-fail legitimate non-UUID identities
  without deciding this first.
- **Source:** mimo `05#F1`.
- **Note:** T1 and T2 both live in `SignalMessageCodec.extractDm` + the group path. Distinct concerns
  (size vs identity) but a single ticket touching that boundary could carry both; the reviewers
  treated them separately.
- **Suggested approach:** validate at decode; on rejection return `Optional.empty()` (DM) / skip
  (group), consistent with "a message whose identity cannot be asserted is dropped at decode."

#### T3. `LlmRouterStartupGuard.isLoopback` trusts only the first resolved IP  ·  [medium] SECURITY  ·  M1 ticket
- **Verdict:** CONFIRMED + RECONCILED. `isLoopback` (`LlmRouterStartupGuard.java:285-292`) uses
  `InetAddress.getByName(host)`, which returns only the **first** resolved address. The guard fails
  boot when `local-only=true` and any base-url is non-loopback (`:183,211,252`); `isLoopback==true` is
  the "safe/on-host" verdict. A multi-A-record host whose first record sorts loopback passes the guard
  while the per-call `HttpClient` may connect to a sibling public address — the exact silent post-body
  leak the guard exists to prevent.
- **Conflict reconciled:** opus (`04#F1`, SECURITY) says check **all** resolved addresses; mimo
  (`04#F1`, SIMPLIFICATION) says **drop DNS** for a static loopback-literal set because
  `getByName` blocks the boot thread on a slow resolver. These point in opposite directions on the
  same method. **Take opus's direction:** switch to `getAllByName` and require *every* address to be
  loopback (`addrs.length > 0` and all `isLoopbackAddress()`). It closes the leak and keeps
  `/etc/hosts` alias detection. mimo's literal-set fix trades away alias recognition (a legit local
  alias would wrongly fail-boot) and doesn't address the multi-record gap. mimo's startup-blocking
  concern is real but minor (one resolution, once, at boot) and is the acceptable cost of correctness;
  note it in the ticket but don't let it drive the design.

#### T4. BanCheck issues a second `users` SELECT per inbound; fold `is_banned` into the snapshot  ·  [high] PERFORMANCE  ·  M1 ticket
- **Verdict:** CONFIRMED (nuance). `USER_SNAPSHOT_SQL` selects only `id, registration_state`
  (`InboundRouter.java:220-221`); step 4 calls `banCheck.isBanned(...)` (`:450`), a second
  `SELECT is_banned FROM users WHERE adapter=? AND contact_id=?` on every inbound. The class javadoc
  (`:132-137`, and `UserSnapshot` doc `:731-737`) *explicitly* documents the second read as a
  deliberate "freshest is_banned … per spec" choice — so mimo's "the javadoc claims one SELECT but
  issues two" framing is slightly unfair; the code is internally honest.
  **What I verified against the spec:** `security.md:416-422` requires only that the ban check reads
  `is_banned=true` at step-4 ordering. It does **not** mandate a separate live query. So folding
  `is_banned` into the snapshot is spec-legal — the TOCTOU between a step-1 read and a step-4 read is
  milliseconds and the ban takes effect on the next message regardless.
- **Source:** mimo `07#F1` (perf) + `07#F4` (same root cause, maintainability). One fix resolves both.
- **Suggested approach:** add `is_banned` to `USER_SNAPSHOT_SQL` + an `isBanned` field on
  `UserSnapshot`; use the snapshot value on the intake path. Keep `BanCheck` for the admin
  confirm-leg paths that already do their own `FOR UPDATE` reads. Update the class javadoc to drop the
  "separate query" rationale.

#### T5. Re-evaluation candidate scan reads every `post` partition each tick  ·  [medium] PERFORMANCE  ·  M1 ticket (+ core migration)
- **Verdict:** CONFIRMED. `ReEvaluationJob` enumerates candidates with a disjunctive predicate carrying
  **no `fetched_at` lower bound** (opus quotes `:444-451`; the surrounding file confirms every other
  statement is a point `WHERE id=? AND fetched_at=?` update, the scan is the outlier). `post` is
  `RANGE (fetched_at)` partitioned (V7), and no migration indexes `stage2_failed`/`stage2_verdict`/
  `re_eval_attempts`, so the planner can prune nothing — full multi-partition scan every poll tick
  (5m). The sibling `PerSourceUnknownTracker` deliberately adds a `fetched_at >= now() - (window+slack)`
  bound to get pruning; this job omits it.
- **Source:** opus `06#F1`.
- **Suggested approach:** add the `fetched_at >= ?` window bound (size = retention horizon + slack, as
  `PerSourceUnknownTracker` documents) **and** a paired partial index in a new `infochat-core`
  migration. Coordinate the two — the query change alone doesn't restore pruning, the index alone
  doesn't prune partitions. Cross-module: query in collector, index in core.

### Tier 2 — rules-drift & contract gaps (medium)

#### T6. Command body cap (slash-command line length) is unimplemented  ·  [medium] RULES-DRIFT  ·  M1 ticket
- **Verdict:** CONFIRMED. `commands.md:82-85` commits to **two** caps; `design/03-commands.md:186`
  assigns per-profile values (`laptop 8192 / vps 4096 / pi 2048 / remote-llm 16384`). Only the
  chat-mode cap is implemented (`chatBodyCap`, `InboundRouter.java:286,495`, and only for non-slash
  bodies). There is no `infochat.command.body-cap` property; the slash path (`:604,829`) has no length
  gate before parsing. The only backstop is the generic 64 KiB byte cap (`:384`).
- **Source:** opus `07#F1`.
- **Suggested approach:** add a profile-driven `infochat.command.body-cap` char cap applied to slash
  bodies after normalization, before `handleSlash`, with a friendly error bundle key — mirror the
  existing chat-cap shape and the design-note per-profile values.

#### T7. Quarantine stored procedures write audit *after* their side effects (Invariant 7)  ·  [medium] RULES-DRIFT  ·  M1 ticket (migration)
- **Verdict:** CONFIRMED. `V41 approve_quarantine`: `FOR UPDATE` (`:44`) → `UPDATE quarantine` (`:55`)
  → `UPDATE post` (`:60`) → `INSERT INTO audit_log` (`:70`) → `pg_notify` (`:81,87`). Audit lands
  after the mutations. The sibling `V5 delete_preban_user` does audit-before-effect (`INSERT` `:381`
  → `DELETE` `:394`) and comments the invariant. `reject_quarantine` (V32) has the same shape.
  Invariant 7 (`schema.md:714-715`) is "audit-before-effect." Blast radius is bounded (single
  transaction, atomic rollback) → medium, not high.
- **Source:** opus arch `#F2`.
- **Suggested approach:** new `CREATE OR REPLACE FUNCTION` migration reordering both bodies so the
  `audit_log` INSERT precedes the UPDATEs; `v_post_id` is read at the `FOR UPDATE` so the
  `details_json` payload is unaffected; NOTIFYs stay last. (Opus's Option B — weaken the invariant —
  was correctly rejected by the reviewer.)

#### T8. SSRF body-cap default (10 MiB) contradicts design note (5 MB)  ·  [medium] RULES-DRIFT  ·  M1 ticket *or* `spec:`
- **Verdict:** CONFIRMED. `SsrfGuardedHttpClient.java:107` → `DEFAULT_BODY_CAP = 10L*1024*1024`.
  `design/04-security.md:154` → "`infochat.fetch.max-body-bytes` (default 5 MB)." Every no-arg
  consumer inherits 10 MiB. Not security-critical (both bound the body) but the design note
  under-states exposure 2×.
- **Source:** mimo `03#F1`.
- **Decision the ticket must make:** which value is canonical. If 10 MiB is intended → `spec:` edit to
  the design note (no code). If 5 MB is intended → one-line code change. Pick one; they must agree.

#### T9. `V20` missing from the Flyway sequence  ·  [medium] RULES-DRIFT  ·  M1 ticket (rename) *or* doc
- **Verdict:** CONFIRMED. `ls db/migration` jumps `V19__summary_anchor.sql` → `V21__quarantine_admin.sql`.
  Greenfield, no prior deploys, so a renumber is safe but mechanical (V21→V46 shift, 26 files, plus any
  hard-coded version refs in tests/design).
- **Source:** deepseek arch `#F1`.
- **Suggested approach:** prefer the cheap path — a `V20__intentionally_skipped.sql` placeholder
  (comment-only) documenting the gap, unless a clean renumber is wanted. Either is fine; the goal is to
  remove the "what happened to V20?" question. **Falsify-before-acting reminder:** before a renumber,
  grep tests/design for literal `V21`–`V46` references.

#### T10. Platform threads where the project's virtual-thread policy applies  ·  [medium] PERFORMANCE  ·  M1 ticket
- **Verdict:** CONFIRMED. `SignalAdapter.java:402` → `new Thread(this::reconnect, …)`, while the
  SimpleX equivalent `SimpleXAdapter.java:295` uses `Thread.ofVirtual()`. Both dispatch executors use
  `Thread.ofPlatform()` (`SignalJsonRpcClient.java:276`, `SimpleXWebSocketClient.java:169`) for a
  single worker that calls handler callbacks documented to block on DB/LLM.
  **Calibration for the ticket:** the dispatch-executor change is low absolute impact — concurrency=1,
  so it's 2 OS threads total, and the FIFO order is held by the executor not the thread type. The
  Signal-reconnect inconsistency (one adapter virtual, one platform, same job) is the cleaner
  motivation. Treat as a small consistency fix, not a throughput win.
- **Source:** mimo `05#F2` (reconnect) + `05#F3` (dispatch executors).
- **Suggested approach:** `Thread.ofVirtual()` for the reconnect; `Thread.ofVirtual().factory()` for
  the two single-thread dispatch executors. Confirm handler paths use `ReentrantLock`/concurrent
  collections (not `synchronized`, which pins) — mimo asserts they do; verify in the ticket.

#### T11. `RateCapBucket` — four copy-pasted token-bucket acquire methods  ·  [medium] SIMPLIFICATION  ·  M1 ticket
- **Verdict:** CONFIRMED. The class has parallel bucket maps (`buckets`, `groupBuckets`,
  `groupLlmBuckets`, `groupCommandBuckets`, `strangerBuckets`, `:114-144`) and multiple
  `tryAcquire*` methods sharing one `synchronized (bucket)` refill/decrement body (`:285`). A refill
  bug must be fixed in each copy.
- **Source:** mimo `07#F3`.
- **Suggested approach:** extract one private `tryAcquireFrom(map, key, cap, refillWindow)`; each
  public method delegates. This is a parameterized helper, not a new abstraction.

#### T12. Fetcher SPI "output-type discriminator" — spec asserts a mechanism the code doesn't have  ·  [medium] RULES-DRIFT  ·  `spec:` edit (no code)
- **Verdict:** CONFIRMED. `architecture.md:159-174` says "The Fetcher SPI carries an output-type
  discriminator." `Fetcher.java:16-36` explicitly disclaims it ("intentionally NOT method-shape
  commitments"); asset ingest is a *separate* `AssetDataSource` SPI. The two-SPI split is the better
  design; the spec text is the stale artifact.
- **Source:** opus arch `#F1`.
- **Suggested approach:** `spec:` amendment to describe the two distinct SPIs and why (no shared
  discriminated `Fetcher`). Pure doc — bypasses the ticket flow per CLAUDE.md.

### Tier 3 — low-severity (bundle by file where noted)

| ID | Sev | Finding | Verdict | Location | Source |
|---|---|---|---|---|---|
| T13 | low | `Redactor` scans each pattern twice (`find()` then `replaceAll`) | CONFIRMED | `core/log/Redactor.java:84-93` | opus `02#F2` |
| T14 | low | `InterruptibleCharSequence.charAt` calls `nanoTime()` every char (check every Nth) | CONFIRMED | `core/log/Redactor.java:158-160` | mimo `02#F1` |
| T15 | low | `isCrossOrigin` compares raw `getHost()` while the module canonicalizes elsewhere | CONFIRMED | `ssrf/SsrfGuardedHttpClient.java:440-444` | opus `03#F2` |
| T16 | med→low | Duplicate `LoopbackPermitting` inner class vs shared `LoopbackPermittingBlocklist` | CONFIRMED (both reviewers) | `ssrf/SsrfGuardedHttpClientTest.java:686` | opus `03#F1`, mimo `03#F2` |
| T17 | low | `BoundedByteArrayResponse` heavyweight wrapper | CONFIRMED — *fold only if touching the return type* | `ssrf/SsrfGuardedHttpClient.java:766-816` | opus `03#F3` |
| T18 | low | Over-broad `catch (RuntimeException …)` around non-throwing JSON assembly (×3 providers) | CONFIRMED | `llm/impl/{OpenAiCompatibleProvider,AnthropicProvider,OpenAiCompatibleEmbeddingProvider}.java` | opus `04#F2` |
| T19 | low | Embedding provider duplicates the shared send/non-2xx/clamp pipeline | CONFIRMED | `llm/impl/OpenAiCompatibleEmbeddingProvider.java:145-167` | opus `04#F3` |
| T20 | low | `StubConfig` copy-pasted across 5 test files (~200 lines) | CONFIRMED | `llm/.../*Test.java` | mimo `04#F2` |
| T21 | low | Signal mention-strip `getInt` throws CCE on wrong-typed span → drops whole message | CONFIRMED | `signal/SignalGroupHandler.java:188-208` | opus `05#F2` |
| T22 | low | Oversize inbound drop is silent; design §6.3.10 commits to a fixed reply | CONFIRMED — likely resolve via design edit (Option B) | `simplex/SimpleXMessageCodec.java:364` + Signal | opus `05#F3` |
| T23 | low | `SimpleXSubprocess` uses `java.util.Random`; sibling uses `ThreadLocalRandom` | CONFIRMED | `simplex/SimpleXSubprocess.java:76` | mimo `05#F4` |
| T24 | low | `NostrEventVerifier` allocates `MessageDigest` per `verify()` | CONFIRMED | `collector/.../NostrEventVerifier.java:285` | mimo `06#F1` |
| T25 | low | `Stage2VerdictHandler` issues a 2nd UPDATE on a row the parent txn already touched | CONFIRMED | `collector/.../Stage2VerdictHandler.java:211-219` | mimo `06#F2` |
| T26 | low | `EmbeddingWorker` swallows `InterruptedException` silently (no log) | CONFIRMED | `collector/.../EmbeddingWorker.java:224-233` | mimo `06#F3` |
| T27 | low | `CollectorSsrfClientProducer` bypassed by 10 of 11 consumers | CONFIRMED | `collector/ssrf/CollectorSsrfClientProducer.java:31-38` | opus `06#F2` |
| T28 | low | `ThrottledAdminNotifier` javadoc documents a phantom `xmax` discriminator | CONFIRMED | `core/notifier/ThrottledAdminNotifier.java:152-167` | opus `02#F1` |
| T29 | low | `BanCommandHandler.lookupUser` null-guard on a non-nullable param (§7) | CONFIRMED | `provider/.../BanCommandHandler.java:393-401` | mimo `07#F5` |
| T30 | low | `NewPostListener.parsePayload` uses unanchored `find()` + raw JSON in exception/log | CONFIRMED | `provider/outbox/NewPostListener.java:343-353` | deepseek `#F3` + `#F4` |

**Bundling suggestions:** T13+T14 are the same file (`Redactor.java`) → one ticket. T15+T16(+T17)
are all `infochat-ssrf` → one ticket. T18+T19+T20 are all `infochat-llm-adapter` → one ticket.
T24+T25+T26 are all `infochat-collector` lows → one ticket.

---

## Findings adjusted or dropped during verification

These did not survive as-written; recorded so the corrections aren't lost.

- **deepseek `#F2` "three dead capability flags" — OVER-CLAIMED → narrowed.** `supportsTypingIndicator`
  is **not** dead: it gates the `setTyping()` SPI method, which `StageProgressNotifier.java:115,168`
  actually calls; Signal/InMemory set it `true`. Only `supportsAttachments` and `supportsThreading`
  appear unused in v1 (all-`false`, no SPI method, no behavioral consumer found). A ticket to "remove
  three flags" would break typing. Re-scope to the two genuinely-unused flags, and confirm even those
  against the capability-contract tests before deletion. Severity drops to low. *(Possible small ticket;
  optional.)*

- **mimo `01#F1` "dual NOTIFY payload construction" — CONFIRMED but NON-ACTIONABLE.** Java-side
  string-concat (`QuarantineNotifyEmitter`) vs SQL-side `jsonb_build_object` (V32+) genuinely coexist,
  but the `quarantine_review` payload carries no timestamp today, so the byte outputs match and there is
  no live drift. The reviewer's own recommendation is comment-only (cross-reference the two emit sites).
  If anything, a `process:`/`spec:` note, not a code ticket.

- **mimo `01#F2` "TranslationProvider placement" — NON-ACTIONABLE.** Verified spec-correct
  (`llm.md` places the SPI deliberately). The reviewer recommends no change for v1. Drop.

- **BanCheck "javadoc lies" framing (mimo `07#F4`) — corrected.** The javadoc does *not* lie; it
  explicitly documents the second query as deliberate. Folded into **T4** as a pure performance
  optimization with the spec check that authorizes it. Don't draft the ticket as a "documentation
  contradiction."

- **`LlmRouterStartupGuard` "drop DNS" (mimo `04#F1`) — superseded by T3.** Same code as the opus
  security finding; the simplification direction was rejected for trading away alias detection. See T3.

---

## Cross-cutting themes worth a tracking note (not tickets themselves)

1. **Inbound size-cap contract is enforced unevenly across the path it protects.** Signal char-vs-byte
   (T1), the missing command cap (T6), and the silent-vs-reply oversize drop (T22) are three points on
   the same `maxInboundMessageBytes` surface. Fixing them piecemeal is fine, but a one-paragraph
   "which cap is enforced where, in what units, with what user-visible outcome" note in
   `messaging.md`/`commands.md` would stop the three points drifting again.

2. **Host/IP comparisons sit on weaker primitives than their security neighbors.** T3 (first-IP-only
   resolution) and T15 (raw-host credential-scrub compare) are independent bugs with the same shape:
   a single comparison left on a narrower primitive than the conservative form used elsewhere in the
   same module. Worth treating "compare hosts/addresses the conservative way" as a module-local
   invariant when touching either.

3. **Per-module test-double duplication.** T16 (SSRF `LoopbackPermitting`) and T20 (LLM `StubConfig`)
   are the same anti-pattern in two modules; both reviewers flagged it independently. No shared
   test-util jar is warranted at this scale — each module de-dups its own.

---

## Coverage caveat

deepseek's six **module** reviews did not run (sub-agent spawn failure on its provider), so its
contribution is architecture-only and one of its four findings (capability flags) was partly wrong.
opus-48 and mimo each completed all seven targets and account for every module-level finding here.
Where only one of the two full reviewers covered a low-severity item, the verdict rests on my own code
read, recorded inline.
