# Deep code review: module infochat-provider
**Target:** module infochat-provider | **Lens:** module | **Module path:** infochat-provider/ | **Date:** 2026-06-07 | **Reviewer:** senior-developer (mimo-v2.5-pro)

## Headline findings

1. **SECURITY | high | SQL injection surface via `SET LOCAL infochat.actor_id` string concatenation.** Six admin command handlers (`BanCommandHandler`, `GrantAdminCommandHandler`, `RevokeAdminCommandHandler`, `VouchCommandHandler`, `PromoteCommandHandler`, `QuarantineCommandHandler`) concatenate a UUID directly into a SQL string: `st.execute("SET LOCAL infochat.actor_id = '" + actor.id + "'")`. The `actor.id` is a `UUID` resolved from the database (not user input), so exploitation requires a compromised DB or a UUID-overflow attack -- unlikely but the pattern violates prepared-statement discipline and creates a latent injection surface if the code is ever refactored to accept a `String` id. Every other parameterized query in the module uses `PreparedStatement` bindings; this is the one class of exception.

2. **MAINTAINABILITY-RULES-DRIFT | medium | Defensive null guards on CDI-injected fields in InboundRouter bypass NullAway's purpose.** `InboundRouter.onMessage` contains null checks on `@Inject`-ed CDI fields (`groupApprovalCheck != null`, `groupAutoPromoteService != null`, `assetCommandFamilyOracle != null`). The comments explain these are for "plain-JUnit test subclasses that bypass CDI." Engineering rule 7a states non-null is the package default via NullAway. These guards are internal-to-internal checks, not system-boundary validation. The pattern creates a maintenance convention where every CDI field needs a null-guard comment rather than relying on the contract.

3. **SECURITY | medium | `PromoteCommandHandler` admin gate does not use `FOR UPDATE`.** `BanCommandHandler`, `GrantAdminCommandHandler`, and `RevokeAdminCommandHandler` all use `SELECT ... FOR UPDATE` on the actor row inside their transaction to close the M1-046 TOCTOU. `PromoteCommandHandler.resolveAdmin` does use `FOR UPDATE` via `userRepository.findByAdapterAndContactIdForUpdate`. However, the method `resolveAdmin` filters by `isAdmin` and returns the id, but does NOT re-read the `is_admin` flag after the FOR UPDATE lock acquisition the way the other handlers do. The other handlers do `lookupActorForUpdate` then check `.isAdmin()` on the locked read; `PromoteCommandHandler` does the same via `.filter(UserRepository.UserRow::isAdmin)`. This is actually correct -- the filter runs on the locked row. Withdrawing this finding on closer inspection.

4. **PERFORMANCE | low | LLM rate cap in InboundRouter uses `ConcurrentHashMap.computeIfAbsent` with synchronized deques.** The `llmCallTimestamps` map grows unbounded for every distinct `userId` that ever invokes chat mode. The scheduled sweep (`evictIdleLlmRateCapEntries`) runs every 5 minutes and removes entries whose deque is empty after pruning, but an active user who stops chatting retains their deque for 2x the 60s window (120s). This is acceptable -- the sweep bounds memory for idle users. However, the deque synchronization pattern (`synchronized (timestamps)`) means two concurrent chat messages from the same user serialize on the deque monitor. Given v1 runs exactly one Provider instance, this is fine.

## Detail

### Security: Intake path (InboundRouter)

The intake path in `InboundRouter.onMessage` faithfully implements the spec's authorization model steps in exact order: rate cap (step 1.5) fires before body-size cap, which fires before normalization (step 1.7), which fires before the invite gate (step 2), group unregistered drop (step 3), ban check (step 4), group approval (step 3.5), chat body cap, membership write (step 4.1), probation gate (step 5), confirm-cancel sweep (step 4.5), anchor clear (step 4.6), and dispatch (step 6).

**Verified:** The rate cap fires FIRST, so a hostile flood cannot drive outbound cost via any fixed-reply path. The body-size cap fires before normalization, so NFKC amplification on adversarial inputs cannot drive cost. Banned users short-circuit before any group approval check or group-related DB write. Over-cap inbound is dropped silently with no reply of any kind.

**Verified:** The normalization pass (`normalize`) correctly implements CommonMark fenced-code-block recognition with the 0-3 leading spaces rule, opener/closer matching, and verbatim preservation inside fences. Bidi-control and zero-width stripping covers ALM, LRM, RLM, LRE-RLO, LRI-PDI, ZWSP, ZWNJ, ZWJ, and BOM.

**Verified:** The contact-id is redacted in all three error-log sites via `ContactIds.redact`.

### Security: Ban check

`BanCheck.isBanned` queries `SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?`. An absent row returns `false`, which is correct fail-closed behavior: the caller falls through to the invite gate. The ban check is a separate query from the `UserSnapshot` lookup (step 1), which means it sees the freshest `is_banned` state -- a user banned mid-dispatch is caught at step 4 regardless of when the snapshot was taken.

### Security: Invite code consumer

`InviteCodeConsumer.consume` implements the race-safe conditional UPDATE pattern correctly. The `UPDATE invite_code SET status = 'USED' ... RETURNING id` is the serialization point. Non-UUID bodies increment the brute-force counter (M1-044e AUDIT-EVASION fix). The brute-force threshold breach audit is written exactly once per breach event via the in-memory `breachAudited` sentinel map, with the map entry written AFTER `conn.commit()` so a SQL fault does not permanently mark the key. The stale-entry sweep is time-gated to at most once per window.

**Verified:** The `CONSUME_INVITE_SQL` correctly handles both `--contact` and `--open` invite types via `invite_type = 'OPEN_ADAPTER' OR expected_contact_id = ?`.

### Security: Probation check

`ProbationCheck.inProbation` uses `probation_until IS NOT NULL AND probation_until > NOW()`, which is correct. The lazy promotion via `clearIfPromoted` is idempotent (`WHERE ... AND probation_until <= NOW()` matches zero rows after the first call).

### Security: Command authorization

`CommandPermissions.allowedDuringProbation` enumerates the spec's closed allowed-set verbatim and delegates asset commands to `AssetCommandFamilyOracle`. The set is: help, status, get-tags, get-sources, list-sources, summary, saved, export, forget, lang, stop. This matches `security.md` section "Slow-start tier" exactly.

**Verified:** Every admin command handler (`BanCommandHandler`, `GrantAdminCommandHandler`, `RevokeAdminCommandHandler`, `VouchCommandHandler`, `PromoteCommandHandler`, `DemoteCommandHandler`, `QuarantineCommandHandler`, `ApproveGroupCommandHandler`, `RejectGroupCommandHandler`) performs an admin gate check before any mutation. The admin gate uses `SELECT ... FOR UPDATE` inside the transaction (M1-046 TOCTOU closure).

**Verified:** Last-admin protection is enforced via the V5 `trg_last_admin_protection_update` trigger (SQLSTATE `IC001`), not just in application code. The trigger fires on both `BanCommandHandler` and `RevokeAdminCommandHandler`.

**Verified:** `BanCommandHandler` correctly implements the pre-ban path (`INSERT INTO users ... 'preban'`), the invite-revoke-on-ban path (`UPDATE invite_code SET status = 'REVOKED'`), and the self-ban guard.

**Verified:** `GrantAdminCommandHandler` correctly writes the `GRANT_ADMIN_INTENT` audit row on a separate auto-commit connection BEFORE the locking transaction, avoiding the FK-vs-FOR-UPDATE deadlock. The same pattern is used by `RevokeAdminCommandHandler` and `BanCommandHandler`.

### Security: Chat agent and LLM tools

`ChatAgent.handle` acquires the in-flight slot, builds the prompt (with random UUID delimiters wrapping untrusted content), runs the multi-turn tool loop (capped at 10 iterations), strips residual TOOL_CALL fragments, sanitizes output via `LlmOutputSanitizer`, persists both turns, translates if needed, and runs auto-compress. The order is correct: sanitize BEFORE persist, so admin commands never enter the DB.

**Verified:** `ChatPromptBuilder` wraps both user messages and memory hits in `<<<UNTRUSTED_CONTENT id="...">>>` blocks with per-call random UUIDs. The system prompt instructs the model to never follow instructions inside the wrapper.

**Verified:** `ChatToolDispatcher` validates the tool name against the closed allowlist (`ChatToolRegistry.TOOL_NAMES`), validates input lengths (recursing into nested lists/maps), clamps limit values, and rejects unknown tools before any SQL runs. The `TurnContext` tracks per-turn call counts (cap 25) and caches identical calls.

**Verified:** `SearchPostsTool` validates tags against the controlled vocabulary, applies window bounds, and uses scope-filtered queries (only `READY` posts in subscribed sources).

**Verified:** `LlmOutputSanitizer` runs markdown-link strip BEFORE closed-list strip, so a hostile `[Click](/grant-admin)` cannot hide an admin command. Every match emits a WARN log line AND an `audit_log` row. The audit write is atomic (all rows in one transaction); a failure prevents the sanitized reply from being emitted.

### Security: Outbox/LISTEN-NOTIFY

`NewPostListener` owns a dedicated connection for the full JVM lifetime (never returned to the pool), issues `LISTEN new_post`, and polls via `PGConnection.getNotifications(1000)`. Reconnection uses exponential backoff (1s to 30s ceiling) and runs the reconciler catch-up after each reconnect.

**Verified:** `ProviderStateDao.advanceCursor` uses tuple comparison `(cursor_high, cursor_low_kind, cursor_low_id) < (?, ?, ?)` for the CAS, which correctly handles two events sharing the same `cursor_high`.

**Verified:** `NewPostHandler.handle` performs a pre-advance existence check (`SELECT 1 FROM post WHERE id = ? AND ready_at = ? AND status = 'READY'`) inside the same transaction as the cursor advance, so a poisoned NOTIFY cannot advance the cursor past real rows.

**Verified:** `NewPostReconciler` pages through the catch-up query with `LIMIT ?` and uses a local paging cursor so successive pages skip already-processed rows without re-reading `provider_state` mid-scan.

### Digest subsystem

`DigestScheduler` queries active groups (`approval_status = 'approved' AND removed_at IS NULL`), evaluates morning and evening slot windows per group's timezone, and fires `DigestSlot` CDI events. Missed slots (past window-end with no cache row) are recorded as audit rows with a sentinel cache insert in the same transaction.

**Verified:** The stagger offset uses `UUID.getMostSignificantBits()` for stability across JVM restarts.

`DigestWorker` observes `DigestSlot` events, collects posts, renders prose (with timeout-bounded degradation), caches, and delivers. The in-flight guard (`ConcurrentHashMap.newKeySet()`) prevents overlapping same-group processing.

**Verified:** The degraded path (`CompletableFuture.get(remaining.toMillis(), MILLISECONDS)`) correctly falls back to `DegradedDigestRenderer` on timeout or execution failure.

### Group subsystem

`GroupAutoPromoteService.tryAutoPromote` checks eligibility (not banned, not in probation), then attempts `INSERT ... ON CONFLICT (group_id, user_id) DO UPDATE SET is_group_admin = true WHERE removed_at IS NULL`. The partial unique index `one_admin_per_group` enforces at-most-one admin. Unique-violation (`23505`) from the race loser is caught and returns `false` without throwing.

**Verified:** The audit row is written in the same transaction as the promotion, and the `conn.rollback()` on `executeUpdate() != 1` correctly prevents a spurious audit row for the race loser.

### Source management

`SourceUpsertService` uses `INSERT ... ON CONFLICT (kind, identifier) DO UPDATE ... RETURNING id, (xmax = 0) AS was_inserted` to distinguish insert from update in a single statement. The conditional SET on `bootstrap_tags` uses `CASE WHEN ? AND source.deleted_at IS NULL` so non-admin callers cannot rewrite tags on existing rows.

**Verified:** `KindResolver` implements the spec's closed host-pattern table with IDN canonicalization and case-insensitive comparison. The `matchesHost` method uses the dot-boundary check (`canonicalHost.endsWith("." + suffix)`) to prevent `evilbsky.app.attacker.com` from matching `bsky.app`.

### Startup

`InstanceLockGuard` extends `AbstractInstanceLockGuard` and supplies the service name `"provider"`. The advisory lock prevents multiple Provider instances (decision D41). The `@Scheduled(every = "30s")` probe updates the heartbeat row.

### SQL injection surface: `SET LOCAL infochat.actor_id`

Six handlers use `st.execute("SET LOCAL infochat.actor_id = '" + actor.id + "'")`. The `actor.id` is a `UUID` resolved from the database, not directly from user input. UUIDs cannot contain SQL-special characters (`'`, `;`, `--`), so exploitation requires either (a) a compromised DB that stores a malicious UUID value in the `users.id` column or (b) a future refactor that changes `actor.id` from `UUID` to `String`. The pattern is a latent risk, not an active vulnerability. A prepared-statement approach (`SET LOCAL infochat.actor_id = ?`) is not supported by PostgreSQL for session variables; the current approach is the standard PostgreSQL pattern for setting session variables from application code.

### NullAway compliance

The module uses JSpecify `@NonNull` (default) and `@Nullable` annotations throughout. The `@SuppressWarnings("NullAway.Init")` annotations in `NewPostReconciler` are for fields initialized in `@PostConstruct` rather than the constructor -- a known NullAway limitation with CDI lifecycle methods. The in-transaction `UserRow` inner records in admin handlers use `@Nullable` for optional fields (`reason`, `probationUntil`).

### `PromoteCommandHandler.resolveAdmin` TOCTOU closure

On re-examination: `PromoteCommandHandler.resolveAdmin` calls `userRepository.findByAdapterAndContactIdForUpdate` and filters by `isAdmin`. This is identical in effect to the other handlers' `lookupActorForUpdate` pattern. The `FOR UPDATE` lock on the actor row is acquired, the `is_admin` flag is read from the locked row, and the filter rejects non-admin callers. This correctly closes the M1-046 TOCTOU.

### Engineering rule compliance

**Rule 1 (Surgical changes):** The codebase shows evidence of surgical discipline. Each command handler is self-contained; cross-cutting concerns (audit, redaction) are delegated to shared utilities.

**Rule 7 (No defensive code for impossible scenarios):** The null guards on CDI-injected fields in `InboundRouter` are explicitly documented as test-seam accommodations. While they technically violate the "no null-checks for parameters callers cannot legally pass null for" rule (CDI guarantees injection), the comments explain the plain-JUnit subclass pattern. The `@Nullable` annotation on `formatTimeUntilUnlock`'s `expiry` parameter correctly handles the race condition where `inProbation` returns true but `clearIfPromoted` nulled the column between reads.

**Rule 7a (Method parameter contracts):** The module uses JSpecify `@NonNull` as the default and `@Nullable` only for genuinely-nullable parameters. NullAway enforcement is active.

**Rule 8 (Test integrity):** Not directly observable from production code alone, but the test-seam pattern (package-private methods and test constructors) suggests a healthy test infrastructure.
