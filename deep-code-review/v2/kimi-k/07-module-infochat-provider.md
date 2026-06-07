# Deep code review: module
**Target:** module infochat-provider
**Lens:** module
**Module path:**
    infochat-provider/
**Date:** 2026-06-07 01:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — SummaryCommandHandler.java:126-205 / InboundRouter.java:601 — `/summary` and `/retry` bypass the per-user LLM-triggering rate bucket, and `/summary` also bypasses the in-flight tracker, so a registered user can multiply LLM cost and run concurrent prose generations that `/stop` cannot cancel.
- [high] SECURITY — GroupAutoPromoteService.java:44-49 — every group message sent by the current group admin writes a spurious `PROMOTE_GROUP_ADMIN` audit row, polluting the append-only audit log proportionally to normal traffic.
- [medium] SECURITY — UnbanCommandHandler.java:149-204 — `/unban` never checks that the target is actually banned: an unban of a non-banned user writes a false `UNBAN` audit row and a false "group admins restored" disclosure.
- [medium] SECURITY — cross-cutting (see CURRENT-CODE) — audit-on-intent is applied to `/grant-admin`, `/revoke-admin`, `/ban` but not to `/vouch`, `/promote`, `/demote`, `/unban`, re-opening the probe-enumeration audit-evasion class those redteam fixes closed on the sibling commands.
- [medium] MAINTAINABILITY-RULES-DRIFT — InFlightTracker.java:36 / CancellationService.java:77 — the spec's two-layer `/stop` cancellation is mostly unwired: `registerPgBackendPid` is never called from production code and `applyStatementTimeout` is applied only inside `/retry`, never on chat-tool or `/summary` queries.
- [medium] MAINTAINABILITY-RULES-DRIFT — AddSourceCommandHandler.java:163-167 / UrlProbe.java:76 — the spec'd Nostr relay-connection probe is unimplemented; a `wss://` URL is routed into the HTTP prober, rejected as `SCHEME_NOT_ALLOWED`, and surfaced to the user as a misleading "blocked by SSRF policy" error.
- [medium] PERFORMANCE — DigestScheduler.java:132 / DigestWorker.java:77 — digest slots are dispatched via a synchronous CDI event, so per-group LLM digest renders serialize on the scheduler tick thread and a slow group delays every group after it in the same tick.
- [medium] MAINTAINABILITY-RULES-DRIFT — ExportCommandHandler.java:98-109 — all export "pages" are concatenated into a single `OutboundMessage`, so the spec's per-message size cap and pagination are cosmetic.
- [medium] MAINTAINABILITY-RULES-DRIFT — InviteCommandHandler.java:357,474-488,513-519 — `/invite list` shows only an 8-char code prefix while `/invite revoke` requires the full UUID, and the open-cap error omits the spec-required list of current open codes; a lost creation reply leaves a PENDING open code unrevokable from chat.
- [medium] SIMPLIFICATION — cross-cutting (see CURRENT-CODE) — the admin command handlers duplicate ~150 lines of identical boilerplate each (`UserRow`, `lookupUser*`, `insertAudit`, `reply`, `contactIdOf`, positional-arg parse) across seven files.
- [low] SECURITY — CancellationService.java:66 / InFlightTracker.java:58-60 — `/stop` releases the in-flight slot while the cancelled worker is still winding down; the worker's `finally` release can then evict a *newer* request's handle, breaking the at-most-one-in-flight invariant.
- [low] MAINTAINABILITY-RULES-DRIFT — BanCommandHandler.java:188-193 / RetryCommandHandler.java:175-177 — misleading fallback replies: missing-argument paths on admin commands answer an authenticated admin with `error.admin_only`, and `/retry`'s in-flight conflict answers with `error.retry.no_anchor` instead of the spec'd "request already in progress" reply.
- [low] MAINTAINABILITY-RULES-DRIFT — InboundRouter.java:487-569 — the confirm-cancel sweep (step 4.5) runs after the chat-body-cap and probation early returns, so input that short-circuits on those paths does not cancel a pending confirmation, contradicting the spec's "any other input cancels it".

## Detail

### F1. `/summary` and `/retry` bypass the LLM rate bucket; `/summary` bypasses the in-flight gate

- **Category:** SECURITY
- **Severity:** high
- **Location:** SummaryCommandHandler.java:126-205, RetryCommandHandler.java:133-217, InboundRouter.java:594-615

**Current code:**

```java
// InboundRouter.java:600-614 — the ONLY call site of the LLM rate cap,
// inside the chat-mode (non-slash) branch:
UUID actorId = snapshot.get().id();
if (!tryAcquireLlmRateCap(actorId)) {
    body = bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP);
} else {
    ...
    body = chatAgent.handle(actorId, scopeKind, scopeId.get(), normalized);
}
```

```java
// SummaryCommandHandler.java:156-164 — no InFlightTracker, no rate bucket:
Result result = eligiblePostQuery.fetch(scopeKind, scopeId.get(), args.tag(), args.window());
...
List<Cluster> clusters = clusterTraversal.cluster(result.posts());
List<ClusterProse> prose = summaryProseGenerator.generate(clusters, "en");
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §Rate limiting commits to one shared bucket for "LLM-triggering operations (chat replies + on-demand `/summary` + `/retry` re-rolls)". The implemented bucket (`InboundRouter.tryAcquireLlmRateCap`) is consulted only on the chat-mode branch. `/summary` and `/retry` dispatch as slash commands and never touch it. `/summary` additionally never acquires the `InFlightTracker` slot, violating `commands.md` §Surface conventions ("At most one in-flight interruptible request per (user, scope)") and D35: `/stop` cannot cancel a running `/summary` because no `CancellationHandle` exists for it, and the same user can run several `/summary` generations concurrently. The cost shape is severe: `/summary` is allowed even during probation, the only remaining bound is the transport cap (60 inbound/min), and each `/summary` call fans out **one LLM call per cluster** with the cluster cap at 200 on the laptop profile. A single post-probation user can therefore drive orders of magnitude more LLM cost than the chat bucket (10/min) was designed to bound, and the bound is supposed to be the cheapest defense the deployment has against LLM-cost exhaustion.

**Recommended fix:**

```java
// Extract the per-user LLM bucket into its own bean (move the deque map
// out of InboundRouter), then in SummaryCommandHandler.handle, before
// generating prose:
if (!llmRateBucket.tryAcquire(scopeId.get())) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP));
}
if (!inFlightTracker.tryAcquire(scopeId.get(), scopeKind, scopeId.get())) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT));
}
try {
    List<ClusterProse> prose = summaryProseGenerator.generate(clusters, "en");
    ...
} finally {
    inFlightTracker.release(scopeId.get(), scopeKind, scopeId.get());
}
// RetryCommandHandler already acquires the in-flight slot; add the same
// llmRateBucket.tryAcquire before the re-roll.
```

**Reasoning:**

This restores the spec's single-bucket semantics for all three LLM-triggering surfaces, makes `/summary` interruptible (a real `CancellationHandle` exists for `/stop` to interrupt), and prevents concurrent `/summary` generations per (user, scope). Extracting the bucket from `InboundRouter` is necessary because handlers are dispatched downstream of the router branch and need the same instance.

**Trade-offs:**

A user who alternates chat and `/summary` now shares one budget, which is exactly what the spec asks for. The extraction adds one small class. None beyond that — the fix is strictly closer to spec.

---

### F2. Auto-promote writes a spurious `PROMOTE_GROUP_ADMIN` audit row on every message from the current group admin

- **Category:** SECURITY
- **Severity:** high
- **Location:** GroupAutoPromoteService.java:44-49 and 77-98 (call site InboundRouter.java:500-515)

**Current code:**

```java
private static final String AUTO_PROMOTE_SQL =
        "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                + "VALUES (?, ?, true) "
                + "ON CONFLICT (group_id, user_id) DO UPDATE "
                + "SET is_group_admin = true "
                + "WHERE group_membership.removed_at IS NULL";
...
if (ps.executeUpdate() != 1) {
    conn.rollback();
    return false;
}
// ...audit row PROMOTE_GROUP_ADMIN with {"auto_promote":true} is written,
// conn.commit(), return true;
```

**Why this is wrong / suboptimal / risky:**

`InboundRouter` step 4.1 calls `tryAutoPromote` for **every** approved-group inbound. When the sender is already the group admin, the `ON CONFLICT ... DO UPDATE SET is_group_admin = true` matches their own membership row (the `WHERE removed_at IS NULL` guard is true, and setting `true → true` does not violate the `one_admin_per_group` partial index because it is the same row), so `executeUpdate()` returns 1 and the handler commits a fresh `PROMOTE_GROUP_ADMIN` audit row with `{"auto_promote":true}`. Every single message from a group admin appends one such row. Consequences: (a) the append-only `audit_log` grows in proportion to ordinary chat traffic; (b) `/audit` output for `PROMOTE_GROUP_ADMIN` becomes useless — real promotions drown in thousands of no-op rows; (c) the audit log no longer "records intent" (`security.md` §Secrets handling) — it records noise. `GroupAutoPromoteServiceTest` has no case for "current admin sends again", so the bug is untested. Secondary cost: in the steady state where a *different* user holds the admin slot, every member message pays an INSERT that fails with a `23505` unique violation caught as control flow — an exception per group message.

**Recommended fix:**

```java
private static final String AUTO_PROMOTE_SQL =
        "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                + "VALUES (?, ?, true) "
                + "ON CONFLICT (group_id, user_id) DO UPDATE "
                + "SET is_group_admin = true "
                + "WHERE group_membership.removed_at IS NULL "
                + "  AND group_membership.is_group_admin = false";
```

and short-circuit before the INSERT with a cheap read so the steady state pays one indexed SELECT instead of a failed INSERT:

```java
private static final String GROUP_HAS_ADMIN_SQL =
        "SELECT 1 FROM group_membership "
                + "WHERE group_id = ? AND is_group_admin = true AND removed_at IS NULL";
// in tryAutoPromote, before isEligible:
if (groupHasAdmin(conn, groupId)) {
    return false;
}
```

**Reasoning:**

The added `is_group_admin = false` predicate makes the already-admin case a zero-row update, so `executeUpdate() != 1` takes the existing rollback path and no audit row is written. The pre-check removes both the per-message audit write *and* the exception-as-control-flow on every member message in groups that already have an admin; the partial unique index remains the race-safe arbiter for the genuinely-contended zero-admin case.

**Trade-offs:**

The pre-check adds one SELECT per group message in the (rare) no-admin window before the INSERT runs; everywhere else it *replaces* a more expensive failed INSERT. The TOCTOU between pre-check and INSERT is benign — the unique index still decides the winner.

---

### F3. `/unban` of a non-banned user writes a false `UNBAN` audit row and a false restoration disclosure

- **Category:** SECURITY
- **Severity:** medium
- **Location:** UnbanCommandHandler.java:149-204 and 254-261

**Current code:**

```java
Optional<UserRow> targetOpt = lookupUser(adapter, targetContactId);
if (targetOpt.isEmpty()) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED));
}
UserRow target = targetOpt.get();
...
if ("preban".equals(target.registrationState)) { ... }

// Step 5 — non-preban path. One transaction: read group-admin
// memberships, pre-write the UNBAN audit row ...
restored = selectGroupAdminMemberships(conn, target.id);
insertUnbanAudit(conn, actor, adapter, target.id, targetContactId,
        requestId, unbanDetailsJson(restored));
updateUserUnbanned(conn, target.id);
```

```java
// lookupUser drops is_banned entirely:
return userRepository.findByAdapterAndContactId(adapter, contactId)
        .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(),
                u.registrationState()));
```

**Why this is wrong / suboptimal / risky:**

There is no "is the target actually banned?" check anywhere in the handler — `is_banned` is not even mapped into the local `UserRow`. `/unban <contact>` against a registered, *not banned* user runs the full non-preban path: it writes an `UNBAN` audit row for an action that changed nothing, and — because `SELECT_GROUP_ADMINS_SQL` has no ban filter — if the target happens to be a group admin, the reply falsely claims "group admin restored in: <groups>" when nothing was ever unreachable. This contradicts the audit log's record-of-intent role and the spec's `/unban` disclosure semantics (`security.md` §User ban: the disclosure exists because restoration is a *side effect of lifting a ban*). It also diverges from the codebase's own established pattern — `VouchCommandHandler`'s no-op detection comment explicitly cites "the `/unban` pattern for in-effect no-ops", a pattern `/unban` itself does not implement.

**Recommended fix:**

```java
// map is_banned into UserRow, then before the preban branch:
if (!target.isBanned) {
    return reply(scope, bundleLoader.get(BundleKeys.REPLY_UNBAN_NOOP)); // new bundle key
}
```

**Reasoning:**

A verified no-op skips both the audit row and the misleading disclosure, matching the Invariant-7 carve-out already applied in `/vouch` (`REPLY_VOUCH_NOOP`) and `/forget` (no-op purge writes no audit row). One new bundle key (`en` + `cs`) is required.

**Trade-offs:**

None — the fix is strictly better. The mutation itself was already a semantic no-op; only the audit and reply honesty change.

---

### F4. Audit-on-intent applied inconsistently across admin handlers

- **Category:** SECURITY
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// GrantAdminCommandHandler.java:245-246 — intent row before every
// execution-semantics check (same in RevokeAdmin, Ban):
String requestId = UUID.randomUUID().toString();
insertIntentAudit(adapter, targetContactId, actor, requestId);
```

```java
// VouchCommandHandler.java:167-183 — refusal legs roll back with NO row:
if (targetOpt.isEmpty()) {
    conn.rollback();
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED));
}
if (target.isBanned) {
    conn.rollback();
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_VOUCH_BANNED_TARGET));
}
// PromoteCommandHandler.java:96-119 and DemoteCommandHandler.java:88-102
// have the same shape: contact-not-registered / banned / probation /
// not-in-group refusals leave zero audit trace.
```

**Why this is wrong / suboptimal / risky:**

`/grant-admin`, `/revoke-admin`, and `/ban` write `*_INTENT` rows unconditionally once the permission gate passes, explicitly to close the M1-151/M1-173 AUDIT-EVASION class: without the intent row "a bot admin [can] enumerate registration state, ban state and the admin bit with zero audit trace" (GrantAdminCommandHandler's own Javadoc). The exact same enumeration surface exists on `/vouch` (4-way reply discrimination: not-registered / banned / noop / success → leaks registration, ban, and probation state), `/promote` (additionally leaks group-membership state), `/demote`, and `/unban` — and none of them write an intent row, so the refusal legs are invisible in the audit log. `security.md` §Authorization model step 8 ("Audit-log the intent") precedes step 9 ("Execute") for *every* command; the closure was applied to three handlers and silently not to their four siblings.

**Recommended fix:**

```java
// In each of Vouch/Promote/Demote/Unban handlers, after the admin
// pre-check and before the transaction (the BAN_INTENT pattern):
String requestId = UUID.randomUUID().toString();
insertIntentAudit(adapter, targetContactId, actor, requestId,
        AuditAction.VOUCH_INTENT /* new enum constants per command */);
```

**Reasoning:**

Reuses the exact pre-transaction auto-commit pattern the three fixed handlers pinned (including the FK-vs-FOR-UPDATE deadlock rationale documented there), so admin probes against any privileged target-state-revealing command leave a durable trace. Four new `AuditAction` constants are needed; the effect rows already exist.

**Trade-offs:**

One extra audit row per permission-passing dispatch of these commands (including legitimate ones) — the same cost the project already accepted for `/ban`, `/grant-admin`, `/revoke-admin`. Slight audit-log growth, bounded by admin command volume.

---

### F5. `/stop`'s pg_cancel_backend path and the statement_timeout safety net are unwired

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** InFlightTracker.java:36, CancellationService.java:77-81, chat/tool/*.java (no call sites)

**Current code:**

```java
// InFlightTracker.java:36 — never called from any production code:
public void registerPgBackendPid(int pid) { pgBackendPid.set(pid); }
```

```java
// CancellationService.java:77 — only production call site is
// RetryCommandHandler.java:299; none of the five chat tools, the
// ChatMemoryPreFetcher, or EligiblePostQuery apply it:
public void applyStatementTimeout(@NonNull Connection conn) throws SQLException {
```

**Why this is wrong / suboptimal / risky:**

`commands.md` §`/stop` commits to two layers: "the cancellation primitive is `pg_cancel_backend(pid)` at the released connection" and "every interruptible read-only query (chat-mode tool calls, on-demand `/summary`) runs under a profile-driven `statement_timeout`". Neither holds: no tool ever registers its backend PID, so `handle.hasPgBackendPid()` in `CancellationService.cancel` is always false and the `pg_cancel_backend` branch is dead code; and `applyStatementTimeout` is applied only in `/retry`'s `fetchReadyPosts`, leaving chat-tool queries (`SearchPostsTool`, `GetReferencesTool`, `RecallMemoryTool`, `ListSavesTool`, `GetPostTool`) and the `/summary` query path with no timeout bound. Today the queries are cheap, but the spec made the timeout the *safety net for the worst case* (e.g. a pathological `post_reference` join as data grows), and `/stop`'s guarantee silently degrades to "thread interrupt only".

**Recommended fix:**

```java
// In ChatToolDispatcher.dispatch (or a shared connection helper the
// tools use), around tool execution:
try (Connection conn = dataSource.getConnection()) {
    cancellationService.applyStatementTimeout(conn);
    inFlightTracker.getCancellationHandle(userId, scopeKind, scopeId)
            .ifPresent(h -> h.registerPgBackendPid(backendPid(conn)));
    ...
}

private static int backendPid(Connection conn) throws SQLException {
    return conn.unwrap(org.postgresql.PGConnection.class).getBackendPID();
}
```

**Reasoning:**

Centralizing connection acquisition for tool calls in the dispatcher (instead of each tool opening its own connection) is the smallest change that lets every tool query inherit both the timeout and the PID registration; `PGConnection.getBackendPID()` avoids a round-trip. This makes the spec's two-layer cancellation real instead of decorative.

**Trade-offs:**

Each tool dispatch pays one extra `SET statement_timeout` statement. Refactoring tools to accept a caller-supplied `Connection` touches five files. Both costs are small relative to honoring a spec-load-bearing guarantee.

---

### F6. Nostr `/add-source` probe unimplemented; wss URLs get a misleading SSRF-blocked error

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** AddSourceCommandHandler.java:163-167, UrlProbe.java:74-108

**Current code:**

```java
// AddSourceCommandHandler.java:163-167 — every kind, including NOSTR,
// goes through the HTTP prober:
ProbeResult probe = urlProbe.probe(args.url());
if (!probe.ok()) {
    return reply(scope, bundleLoader.get(probe.failureBundleKey()));
}
```

```java
// UrlProbe.java:76 — small-range HTTP GET:
HttpResponse<byte[]> response = httpClient.get(url, RANGE_FIRST_BYTE);
```

**Why this is wrong / suboptimal / risky:**

`commands.md` §Source management: "For StreamSource-shaped kinds (Nostr in v1) the equivalent check is a single connection attempt against the first relay in the supplied `config`". `KindResolver` correctly resolves `ws`/`wss` schemes to `NOSTR`, but the handler then probes the wss URL through `SsrfGuardedHttpClient.get`, whose HTTP scheme allowlist rejects `ws`/`wss` with `SCHEME_NOT_ALLOWED` (SsrfGuardedHttpClient.java:442-445). `UrlProbe` maps every non-timeout `SsrfPolicyException` to `ERROR_ADD_SOURCE_URL_BLOCKED_SSRF`. Net effect: `/add-source wss://relay.example` *always* fails, and it fails with "blocked by security policy" — telling the user a legitimate public relay tripped the SSRF guard. The Nostr add-source path is therefore both broken and misleading; the SSRF library already has a WebSocket validation surface (`WEBSOCKET_SCHEMES`, the ws policy pipeline) that is not used here.

**Recommended fix:**

```java
// AddSourceCommandHandler, branch on kind before probing:
ProbeResult probe = (kind == KindResolver.SourceKind.NOSTR)
        ? urlProbe.probeRelay(args.url())   // new: SSRF ws-policy check +
                                            // single WebSocket connection attempt
        : urlProbe.probe(args.url());
```

**Reasoning:**

A `probeRelay` that runs the existing ws-policy validation from `infochat-ssrf` plus one short-timeout WebSocket connect implements the spec's commitment with the same guard pipeline the Collector's StreamSource uses, and gives Nostr users the same friendly-error taxonomy (blocked / unreachable / timeout) HTTP sources get.

**Trade-offs:**

New code path with its own test fixture (a fake relay). If v1 deliberately defers Nostr `/add-source`, the cheaper honest fix is to reject `NOSTR` kind with a dedicated "not yet supported, use bootstrap-sources.json" friendly error instead of the false SSRF claim — but that choice should be explicit, not an accident of scheme allowlists.

**Alternative options:**

- **Option A** (the recommended fix above)
- **Option B** — explicit "Nostr sources are bootstrap-only in this version" friendly error on `kind == NOSTR` — pros: tiny, honest; cons: leaves a spec commitment unimplemented and must be tracked as deliberate deferral.

---

### F7. Digest slots execute synchronously on the scheduler tick thread

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** DigestScheduler.java:130-134, DigestWorker.java:77

**Current code:**

```java
// DigestScheduler.java:130-134
if (!now.isBefore(effectiveFireTime)) {
    // Within window and past stagger time: emit
    digestSlotEvent.fire(new DigestSlot(
            group.id, group.timezone, slotKind, windowStart, windowEnd));
}
```

```java
// DigestWorker.java:77 — synchronous observer:
public void execute(@Observes @NonNull DigestSlot slot) {
```

**Why this is wrong / suboptimal / risky:**

`Event.fire` with a synchronous `@Observes` observer runs `DigestWorker.execute` inline on the scheduler tick thread. `executeSlot` blocks on the LLM render (`CompletableFuture.get(remaining)`, bounded only by the slot's *remaining window* — up to ~30 minutes on the default width) plus the adapter `send`. Within one tick, groups are processed serially: a slow LLM render for group 1 delays group 2's digest past its stagger time and potentially past its window (mis-classifying it as saturated/missed). It also occupies a Quarkus scheduler executor thread for minutes while subsequent `tick()` invocations pile up concurrently (default `ConcurrencyExecution.PROCEED`), partially mitigated by the in-flight set but still re-scanning and re-blocking. The whole point of the stagger offset (spreading load inside the window) is defeated when execution is serialized behind the first slow render.

**Recommended fix:**

```java
// DigestWorker.java — async observer on a virtual thread:
public void execute(@ObservesAsync @NonNull DigestSlot slot) { ... }

// DigestScheduler.java:
digestSlotEvent.fireAsync(new DigestSlot(...));
```

**Reasoning:**

`fireAsync` + `@ObservesAsync` decouples slot execution from the tick: each group's digest runs independently, the stagger offsets actually spread the LLM load, and the scheduler thread returns immediately. The existing `inFlightSlots` guard already protects against duplicate concurrent processing of the same slot.

**Trade-offs:**

Async observers swallow the observer's exceptions into the returned `CompletionStage`; the worker must keep its own catch-and-log (it already has one). Tests that rely on synchronous event semantics need a `toCompletableFuture().join()` or an awaitility wait.

---

### F8. `/export` ships all pages in a single message, defeating the per-message size cap

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** ExportCommandHandler.java:98-109, 147-159

**Current code:**

```java
int effectiveCap = exportPageCap - HEADER_BUDGET;
List<String> pages = ExportPaginator.paginate(result.tables(), effectiveCap);

String body = formatPages(pages);
...
return reply(scope, body);
```

```java
// formatPages joins every page into one string:
for (int i = 0; i < pages.size(); i++) {
    if (i > 0) sb.append("\n\n");
    sb.append("page=").append(i + 1).append('/').append(pages.size()).append('\n');
    sb.append("```json\n").append(pages.get(i)).append("\n```");
}
```

**Why this is wrong / suboptimal / risky:**

`commands.md` §`/export`: "if the total export size exceeds the per-message cap, the reply is split into pages." The pagination here computes correctly sized pages and then concatenates all of them into one `OutboundMessage`, so the outbound payload is the *full* export regardless of `infochat.export.page-cap`. The cap exists because transports have message-size limits (and the router enforces 64 KiB inbound for the same reason); a user with a large library gets one message that the adapter may truncate or reject, and the "page=N/T" headers are decoration on an unsplit blob. The pagination machinery is effectively dead weight in its current wiring.

**Recommended fix:**

```java
// Inject AdapterRegistry (the DigestWorker pattern) and send pages
// individually; return the last page as the handler's OutboundMessage:
MessagingAdapter adapter = findAdapter(inboundContext.adapterName());
for (int i = 0; i < pages.size() - 1; i++) {
    adapter.send(new OutboundMessage(scope, renderPage(pages, i),
            Instant.now(), UUID.randomUUID().toString()));
}
return reply(scope, renderPage(pages, pages.size() - 1));
```

**Reasoning:**

Each transport message then actually respects the per-message cap, which is what makes the export deliverable on size-limited adapters. The DigestWorker already establishes the precedent of a handler-tier component sending through `AdapterRegistry` directly, so no SPI change is needed.

**Trade-offs:**

Multi-message sends are not atomic — a transient send failure mid-export leaves a partial export delivered (the user can re-run `/export`). The handler takes on adapter resolution it previously didn't need.

---

### F9. `/invite list` shows an 8-char prefix but `/invite revoke` requires the full UUID

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** InviteCommandHandler.java:357, 474-488, 513-519

**Current code:**

```java
// renderListEntry — prefix only:
String codePrefix = row.code.toString().substring(0, 8);
```

```java
// handleRevoke — full UUID required:
String codeText = trimmed.split("\\s+", 2)[0];
UUID code;
try {
    code = UUID.fromString(codeText);
} catch (IllegalArgumentException e) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_REVOKE_NOT_PENDING));
}
```

```java
// createOpen — cap error without the spec-required code list:
if (current >= openCap) {
    conn.rollback();
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_OPEN_CAP_MET));
}
```

**Why this is wrong / suboptimal / risky:**

The only place the full code is ever displayed is the creation reply ("The code is displayed once in the reply", `commands.md` §Admin). `/invite list` then shows only `substring(0, 8)`, and `/invite revoke` parses strictly with `UUID.fromString`. An admin who loses the creation reply (chat history rotation, different device) **cannot revoke a PENDING open code from chat at all** — the only path left is operator `psql`. This directly undermines the cap workflow the spec mandates: `security.md` §Invite-code registration says the open-cap error gives "a friendly error listing the current open codes and a hint pointing at `/invite revoke`" — the implemented `error.invite.open_cap_met` bundle string lists nothing, so the admin is pointed at a revoke they may be unable to perform.

**Recommended fix:**

```java
// renderListEntry: show the full code. /invite list is bot-admin-only
// and the spec's "shown once" applies to limiting *casual* exposure at
// creation, not to making codes irrecoverable by their own issuer:
String codeText = row.code.toString();
```

plus interpolate the current open codes (full values, with adapter + expiry) into `error.invite.open_cap_met`.

**Reasoning:**

The revoke workflow becomes self-sufficient: list → copy code → revoke. The exposure delta is nil in practice — the admin's chat session already contained the full code at creation; the threat of a compromised admin session is identical either way, and the cap + TTL bound any leaked code's value.

**Trade-offs:**

Full codes re-appear in the admin's chat surface on every `/invite list`, widening the screenshot/scrollback exposure window. If that is judged unacceptable, Option B keeps the prefix display but makes `/invite revoke <prefix>` resolve an unambiguous prefix server-side.

**Alternative options:**

- **Option A** (full code in list + cap-error interpolation)
- **Option B** — prefix-revoke: `SELECT ... WHERE code::text LIKE ? || '%' AND status='PENDING'`, refuse on ambiguity — pros: codes never redisplayed; cons: more code, prefix collisions need a friendly error, and the cap-error list still has to show something usable.

---

### F10. Seven admin handlers duplicate the same ~150-line support boilerplate

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// Repeated near-verbatim in BanCommandHandler, GrantAdminCommandHandler,
// RevokeAdminCommandHandler, UnbanCommandHandler, VouchCommandHandler,
// InviteCommandHandler, ForgetCommandHandler (with small field-set drift):
private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned) {}
private Optional<UserRow> lookupUser(String adapter, String contactId) { ... }
private Optional<UserRow> lookupActorForUpdate(Connection conn, ...) { ... }
private void insertAudit(Connection conn, AuditAction action, ...) {
    RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()...build();
    auditLogWriter.write(conn, row);
}
private OutboundMessage reply(ScopeRef scope, String text) {
    return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
}
private static @Nullable String contactIdOf(ScopeRef scope) {
    return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
}
private static @Nullable String parseTargetContact(String rawText) { ... }
private static String quoteJsonString(String s) { ... }
```

**Why this is wrong / suboptimal / risky:**

This is far past "three similar lines beats a premature abstraction": `GrantAdminCommandHandler` and `RevokeAdminCommandHandler` are ~500-line files that differ in roughly 60 lines of substance. The duplication has already produced real divergence — the per-handler `UserRow` field sets drift (UnbanCommandHandler's drops `is_banned`, which is the root of F3), and the audit-on-intent pattern was applied to three copies and missed in four (F4). Every future fix to the audit shape, the redaction rule, or the actor-lookup contract must be replicated by hand across seven files, and the M1 history shows that replication does not happen reliably.

**Recommended fix:**

```java
// One @ApplicationScoped support bean + one shared record:
public record ActorRow(UUID id, String contactId, boolean isAdmin,
                       boolean isBanned, String registrationState) {}

@ApplicationScoped
public class AdminCommandSupport {
    public Optional<ActorRow> lookupActor(String adapter, @Nullable String contactId) { ... }
    public Optional<ActorRow> lookupActorForUpdate(Connection conn, String adapter, String contactId) { ... }
    public void writeAudit(Connection conn, AuditAction action, String targetKind,
                           String targetId, @Nullable String targetContactId,
                           ActorRow actor, String adapter, String requestId,
                           String detailsJson) { ... }
    public void writeIntentAudit(...) { ... }          // the BAN_INTENT pattern, once
    public OutboundMessage reply(ScopeRef scope, String text) { ... }
    public static @Nullable String firstPositionalArg(String rawText) { ... }
}
```

**Reasoning:**

One implementation of the actor lookup, the locked lookup, the audit-row build, and the intent-row pattern means F3/F4-class drift cannot recur silently; new admin commands inherit the full pattern by construction. The per-command transaction choreography (which is genuinely command-specific) stays in the handlers.

**Trade-offs:**

A shared `ActorRow` carries fields some handlers don't read (one extra column in the SELECT). The refactor touches seven files in one diff, which is a large reviewed change — best done as its own ticket, not folded into a feature.

---

### F11. `/stop` releases the in-flight slot for a still-running worker, allowing the invariant to be bypassed

- **Category:** SECURITY
- **Severity:** low
- **Location:** CancellationService.java:58-68, InFlightTracker.java:58-60

**Current code:**

```java
// CancellationService.cancel:
handle.workerThread().interrupt();
...
// Release the in-flight slot so the user can issue new requests.
inFlightTracker.release(userId, scopeKind, scopeId);
```

```java
// InFlightTracker.release — removes by key regardless of which
// handle currently occupies the slot:
public void release(@NonNull UUID userId, @NonNull String scopeKind, @NonNull UUID scopeId) {
    inFlight.remove(new ScopeKey(userId, scopeKind, scopeId));
}
```

**Why this is wrong / suboptimal / risky:**

After `/stop`, the interrupted worker is still unwinding (the interrupt is best-effort; the LLM call may complete normally). Sequence: `/stop` releases the slot → user sends a new chat message → `ChatAgent.tryAcquire` installs a *new* handle → the old worker reaches its `finally { inFlightTracker.release(...) }` (ChatAgent.java:134-136) and removes the **new** request's handle. From that point the new request runs with no registered handle (`/stop` against it is a no-op) and a third request can acquire the slot concurrently — two in-flight LLM generations for one (user, scope), violating the §Surface conventions invariant the tracker exists to enforce.

**Recommended fix:**

```java
// InFlightTracker: handle-identity-checked release.
public boolean tryAcquireReturningHandle(...) / acquire returns the handle;
public void release(@NonNull UUID userId, @NonNull String scopeKind,
                    @NonNull UUID scopeId, @NonNull CancellationHandle owned) {
    inFlight.remove(new ScopeKey(userId, scopeKind, scopeId), owned);
}
```

with `ChatAgent`/`RetryCommandHandler` holding the handle they acquired and releasing only their own.

**Reasoning:**

`ConcurrentHashMap.remove(key, value)` makes the release a no-op when the slot has been re-acquired by a newer request, closing the window without locks. The cancel path can keep its eager release (the cancelled handle is the one it removes).

**Trade-offs:**

Small API change on `InFlightTracker` (acquire must surface the handle); call sites in ChatAgent and RetryCommandHandler update. None beyond that.

---

### F12. Misleading fallback replies on argument and in-flight errors

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** BanCommandHandler.java:186-193, GrantAdminCommandHandler.java:207-210, RevokeAdminCommandHandler.java:206-209, UnbanCommandHandler.java:141-144, PromoteCommandHandler.java:79-82, DemoteCommandHandler.java:72-75, VouchCommandHandler.java:138-141, RetryCommandHandler.java:175-177

**Current code:**

```java
// BanCommandHandler — admin typed `/ban` with no target:
BanArgs args = BanArgs.parse(rawText);
if (args == null) {
    // No positional contact arg. Fall back to error.admin_only;
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
}
```

```java
// RetryCommandHandler — in-flight conflict answered as "no anchor":
if (!inFlightTracker.tryAcquire(userId.get(), scopeKind, scopeId)) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR));
}
```

**Why this is wrong / suboptimal / risky:**

An authenticated bot admin who omits the positional argument is told "admin only" — a reply that asserts something false about their permission and gives no hint about the actual problem. The pattern is copied across seven handlers. Separately, `/retry` during an in-flight request replies "Nothing to retry. Run /summary first." — also false (the anchor exists), and the spec'd reply for this exact condition ("request already in progress; use `/stop` to cancel", §Surface conventions) already exists in the bundle as `error.chat.in_flight` (en.properties:381). `commands.md` §Friendly errors commits to errors that tell the caller what to fix; these tell them something wrong.

**Recommended fix:**

```java
// One new bundle key, reused across the seven handlers:
// error.usage.missing_argument=Missing required argument. Usage: {0}
if (args == null) {
    return reply(scope, MessageFormat.format(
            bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT), "/ban <contact> [--reason \"...\"]"));
}
// RetryCommandHandler:
if (!inFlightTracker.tryAcquire(...)) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT));
}
```

**Reasoning:**

One parameterized usage key fixes all seven sites without per-command bundle churn; the `/retry` fix reuses an existing key that says exactly what the spec requires.

**Trade-offs:**

A usage error confirms to a *non*-admin probing `/ban` (with no args) that the command exists — but the admin gate runs first in every one of these handlers, so non-admins never reach the parse branch. None remaining.

---

### F13. Confirm-cancel sweep is skipped by earlier short-circuits

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** InboundRouter.java:487-490, 530-546 (early returns) vs 548-569 (sweep)

**Current code:**

```java
// chat-body-cap return (line 487) and probation-blocked return (line 538)
// both fire BEFORE the step 4.5 confirm-cancel sweep at line 556:
if (!normalized.startsWith("/") && normalized.length() > chatBodyCap) {
    sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_CHAT_BODY_TOO_LARGE), adapterName);
    return;
}
...
if (probationCheck.inProbation(probationActorId)) {
    if (!commandPermissions.allowedDuringProbation(commandName)) {
        ...
        sendReply(msg.scope(), body, adapterName);
        return;
    }
}
...
// Step 4.5 — confirm-cancel sweep (M1-051).
Optional<ConfirmStateService.PendingConfirm> pending =
        confirmStateService.peek(confirmActorId, msg.scope());
```

**Why this is wrong / suboptimal / risky:**

`commands.md` §Surface conventions: "The confirmation is scoped to (user, scope) and any other input cancels it with an explicit acknowledgement". A user with a pending confirmation (e.g. a probation user's `/forget` prompt — `/forget` is probation-allowed and confirm-gated) who then sends a probation-blocked command or an oversized chat body receives the corresponding error reply, but the pending confirmation survives. Their *next* `<command> confirm` within the timeout still executes, even though intervening input arrived. The window is short (confirm timeout, 60 s default) and the surviving action is one the user themselves initiated, so the practical risk is small — but the state machine no longer matches the spec's "any other input cancels" commitment, and the divergence is invisible in the step-order Javadoc, which presents step 4.5 as if it covers all input.

**Recommended fix:**

```java
// Hoist the sweep so it runs for every inbound that has resolved a
// snapshot (i.e. immediately after step 3.5 / 4), before the body-cap
// and probation returns:
snapshot.ifPresent(s -> sweepStaleConfirm(s.id(), msg.scope(), normalized, adapterName));
```

**Reasoning:**

Running the sweep as soon as the actor is known restores the spec's semantics: any input — including input that subsequently short-circuits — cancels a non-matching pending confirmation with the acknowledgement reply. The sweep is cheap (in-memory map peek).

**Trade-offs:**

The cancellation acknowledgement is now sent even on paths that follow it with an error reply (two outbound messages for one inbound). That is arguably correct per spec ("explicit acknowledgement"), but it consumes one extra send against per-group reply buckets in group scope.

## Synthesizer-relevant observations

- Cross-module: the `CommandHandler.handle(ScopeRef, String)` SPI carrying no actor identity forces every group-capable handler through the `InboundContext` request-scope side channel and is the stated reason `/export`, `/summary`, and `/retry` reject or degrade group scope ("T2-F actor seam"); the architecture pass should look at whether the frozen SPI shape is still the right contract.
- Cross-module: `ExportDataCollector` notes that V5's `redact_contact_id()` is a pass-through stub, meaning `audit_log_view` is currently unredacted at the DB tier (mitigated handler-side by column exclusion); that is a schema/collector-side concern.
