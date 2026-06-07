# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-06 17:30
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — `SummaryCommandHandler.java`, `RetryCommandHandler.java` — LLM-triggering slash commands `/summary` and `/retry` bypass the per-user LLM rate cap that the spec mandates apply to "chat replies + on-demand /summary + /retry re-rolls."
- [high] MAINTAINABILITY-RULES-DRIFT — `chat/tool/SearchPostsTool.java:139,168-169`, `chat/tool/GetPostTool.java:43,61-62` — Tools select `post.published_at` and label the JSON field `ready_at`; the schema's `ready_at` is a distinct column (set on READY transition) and is what the spec's tool contract promises.
- [high] PERFORMANCE — `command/ConfirmStateService.java` — Pending confirmation entries are evicted lazily only on access to the same `(actor, scope)` key; an abandoned confirm prompt sits in the in-memory map forever, with no scheduled sweep — unbounded growth under a churning user population.
- [high] MAINTAINABILITY-RULES-DRIFT — `command/InviteCommandHandler.java:172,665-667` — Bot-admin `/invite` commands resolve the caller via `contactIdOf(scope)` (DM-only); in group scope the handler returns null and the command silently fails as "admin only," contradicting the spec's general "bot admin only" wording without the "DM only" restriction other admin handlers (`/approve-group`, `/reject-group`) honor.
- [medium] SECURITY — `command/SaveCommandHandler.java:99-104` — `/save` looks up the target post with no scope/subscription filter; any DM user can bookmark any READY post in the deployment regardless of whether their scope subscribes to its source, contradicting "The `/save` flow never lets a user bookmark content they cannot see."
- [medium] MAINTAINABILITY-RULES-DRIFT — `command/BanCommandHandler.java:289`, plus 11 sites listed in CURRENT-CODE — `SET LOCAL infochat.actor_id` is built via string concatenation through `Statement`; safe today only because the value is a `UUID`, but the pattern is fragile and forbidden in every other SQL site in the module.
- [medium] PERFORMANCE — `outbox/QuarantineReviewListener.java:294-302` — Post-reconnect catch-up is not run; events lost between connection drop and re-`LISTEN` are not replayed mid-process, in contrast to `NewPostListener.reconcileAfterReconnect()` (line 257) which makes the same promise.
- [medium] MAINTAINABILITY-RULES-DRIFT — `command/ExportDataCollector.java:188-195,197-199` — `ExportResult.truncatedTables` flags every table that returns exactly `maxRowsPerTable` rows as truncated, even when that is the true row count; SQL builds `LIMIT N` by string concatenation of an `int` config value.
- [medium] PERFORMANCE — `scheduler/ChatMemoryPruner.java:34,38-46` — Retention horizon is converted with `retention.toDays()` (integer truncation); a sub-day `infochat.chat.retention` value (e.g. `PT12H`) silently becomes 0 days and deletes everything.
- [low] SIMPLIFICATION — `chat/tool/GetReferencesTool.java:38-39`, `chat/tool/RecallMemoryTool.java:46`, `chat/tool/ListSavesTool.java:29-30` — Each LLM tool hardcodes its own LIMIT and ignores the dispatcher's profile-driven `limitCap`; one tool's clamp does not match the dispatcher's clamp, defeating the spec's single profile-driven cap.
- [low] MAINTAINABILITY-RULES-DRIFT — `messaging/InboundRouter.java:457-476,500-515` — Three `groupAutoPromoteService != null` / `groupApprovalCheck != null` guards exist solely to support plain-JUnit subclasses; this is defensive code between two internal classes (`§7`), not a system-boundary check.

## Detail

### F1. `/summary` and `/retry` bypass the per-user LLM rate cap

- **Category:** SECURITY
- **Severity:** high
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:587-616`; `command/SummaryCommandHandler.java` (no rate-cap consult); `command/RetryCommandHandler.java` (no rate-cap consult)

**Current code:**

```java
// InboundRouter.onMessage (slash branch — no LLM rate cap consulted)
if (normalized.startsWith("/")) {
    body = handleSlash(msg.scope(), normalized);
} else {
    // Chat-mode dispatch: enforce LLM rate cap, then delegate to ChatAgent
    UUID actorId = snapshot.get().id();
    if (!tryAcquireLlmRateCap(actorId)) {
        body = bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP);
    } else {
        ...
        body = chatAgent.handle(actorId, scopeKind, scopeId.get(), normalized);
    }
}
```

```bash
$ grep -rln 'tryAcquireLlmRateCap\|llm-rate-cap-per-minute' src/main/java
infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
# (no other hits)
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §Rate limiting commits explicitly:

> **LLM-triggering operations** (chat replies + on-demand `/summary` + `/retry` re-rolls) — its own bucket, capped lower, profile-driven.

`SummaryCommandHandler` and `RetryCommandHandler` are LLM-triggering by construction (they invoke the summarizer LLM, optionally per cluster). They are also in the slow-start probation allowed set (`/summary` is allowed; `/forget`, `/lang`, `/saved` etc.), so a freshly-registered probation user can flood the LLM via `/summary` calls with only the per-(adapter, contact_id) transport cap applied — which is `60/minute` by default versus the LLM bucket's `10/minute`. The `infochat.chat.llm-rate-cap-per-minute` property exists, the router has a `tryAcquireLlmRateCap` helper, and chat mode honors it. Slash-command LLM calls do not. This is a direct spec violation, not a minor undersized cap.

The cost surface is asymmetric too: a probation user cannot use chat mode (which IS rate-capped), but can use `/summary` (which IS NOT). The spec rate-cap mismatch lets the lowest-trust tier drive the LLM hardest.

**Recommended fix:**

Lift `tryAcquireLlmRateCap` invocation up to dispatch time for every LLM-triggering command, not only chat mode. The cleanest shape: a marker interface (`LlmTriggeringCommand`) on `CommandHandler` implementations or a `commandNameOf`-keyed set in `InboundRouter`, consulted before `handleSlash`:

```java
private static final Set<String> LLM_TRIGGERING_COMMANDS =
        Set.of("summary", "retry");

if (normalized.startsWith("/")) {
    String commandName = commandNameOf(normalized);
    if (LLM_TRIGGERING_COMMANDS.contains(commandName)
            && !tryAcquireLlmRateCap(snapshot.get().id())) {
        body = bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP);
    } else {
        body = handleSlash(msg.scope(), normalized);
    }
} else { ... }
```

**Reasoning:**

The router already centralizes intake gates and already owns the `llmCallTimestamps` map; teaching it to consult the same map on the slash path is one branch, not a new architecture. The marker-set lives next to `CommandPermissions`'s probation allowed-set so the two slow-start invariants (allowed vs LLM-capped) are visible side by side. Per spec, `/summary` and `/retry` are the only v1 LLM-triggering slash commands; group digests are system-initiated and have their own backstop. Adding a future LLM-triggering command becomes a one-line edit here AND in the spec's closed list.

**Trade-offs:**

The closed `LLM_TRIGGERING_COMMANDS` set is one more place where commands enumerate themselves (alongside `CommandPermissions.ALLOWED` and the privileged-tier sanitizer list). The marker-interface alternative avoids the duplicate enumeration at the cost of one extra interface in the public surface. The set is the simpler shape for v1's two entries.

**Alternative options:**

- **Option A** (the recommended fix above) — closed set in the router.
- **Option B** — marker interface (`LlmTriggeringCommand extends CommandHandler`), router checks `handler instanceof LlmTriggeringCommand`. Pros: handler-local declaration; cons: one new interface; complicates the unit-test fakes.

---

### F2. `searchPosts` / `getPost` return `published_at` labeled as `ready_at`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java:135-153,168-170`; `chat/tool/GetPostTool.java:43-62`

**Current code:**

```java
// SearchPostsTool — queryPosts
sql.append("SELECT p.uid, p.title, p.url, p.published_at, p.tags ")
   ...
json.append("{\"uid\":").append(jsonStr(rs.getString("uid")))
    .append(",\"title\":").append(jsonStr(rs.getString("title")))
    .append(",\"url\":").append(jsonStr(rs.getString("url")))
    .append(",\"ready_at\":").append(jsonStr(
            instantStr(rs.getTimestamp("published_at"))))   // ← published_at AS ready_at
    .append(",\"tags\":");
```

```java
// GetPostTool — same shape
String sql = "SELECT p.uid, p.title, p.body, p.url, p.published_at, p.tags FROM post p ..."
...
    .append(",\"ready_at\":").append(jsonStr(instantStr(
            rs.getTimestamp("published_at"))))
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §Prompt-injection defenses pins the tool output contract verbatim:

| `searchPosts` | `tags: list<Tier-1 tag>`, `window: duration`, `limit: int ≤ profile-driven cap` | list of `{uid, title, url, ready_at, tags}` | ... |
| `getPost` | `uid: string` | `{uid, title, body, url, ready_at, tags}` or `null` | ... |

`docs/design/02-schema.md` §`post` distinguishes the two columns explicitly:

```sql
published_at        TIMESTAMPTZ,                    -- original upstream publication time
ready_at            TIMESTAMPTZ,                    -- set when status transitions → READY;
                                                    --   the new_post NOTIFY cursor
```

These are semantically different: a post can publish months before it reaches `READY` (delayed ingest, quarantine review), and the `new_post` NOTIFY cursor is keyed on `ready_at`. The LLM contract says "ready_at" because the agent is reasoning about *when the user could see this*, not *when the source claims to have written it*. Returning `published_at` under the label `ready_at` lies to both the model and to verification — an LLM that reasons "what's new in the last hour" using `ready_at` gets the upstream timeline, not the local-visibility timeline.

This is also a verification trip-wire: `docs/spec/verification.md` will at some point assert the tool output shape matches the spec exactly, and a column-rename is the trivial test that catches this drift.

**Recommended fix:**

Read `ready_at` from `post` and return it under the same JSON name. Note `ready_at` may be NULL during the brief window before the `READY` transition completes, but the `WHERE p.status = 'READY'` filter excludes those rows by construction.

```java
// SearchPostsTool
sql.append("SELECT p.uid, p.title, p.url, p.ready_at, p.tags ")
   ...
   .append(",\"ready_at\":").append(jsonStr(
           instantStr(rs.getTimestamp("ready_at"))))
```

```java
// GetPostTool
String sql = "SELECT p.uid, p.title, p.body, p.url, p.ready_at, p.tags FROM post p ..."
   ...
   .append(",\"ready_at\":").append(jsonStr(instantStr(
           rs.getTimestamp("ready_at"))))
```

**Reasoning:**

The fix is a one-column rename per tool. `idx_post_ready_at` is a partial index covering the `WHERE status='READY'` path, so query planning is at least as good as on `published_at`. The LLM gets the contract the spec promises; the catch-up cursor mechanism (which the NOTIFY listener and reconciler use) and the tool output stay coherent on a single timeline.

**Trade-offs:**

None — the fix is strictly better. If a future product decision genuinely wants the upstream publication time exposed, add a second field (`published_at`) rather than mislabel `ready_at`.

---

### F3. `ConfirmStateService` has unbounded in-memory growth for abandoned confirms

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java:49,101-106,161-177`

**Current code:**

```java
private final ConcurrentHashMap<ConfirmKey, Stored> pending = new ConcurrentHashMap<>();
...
public void remember(@NonNull UUID actorUserId,
                     @NonNull ScopeRef scope,
                     @NonNull PendingConfirm pendingConfirm) {
    Instant deadline = clock.instant().plus(timeout);
    pending.put(new ConfirmKey(actorUserId, scope), new Stored(pendingConfirm, deadline));
}

public Optional<PendingConfirm> peek(@NonNull UUID actorUserId,
                                     @NonNull ScopeRef scope) {
    ConfirmKey key = new ConfirmKey(actorUserId, scope);
    Stored stored = pending.get(key);
    if (stored == null) return Optional.empty();
    if (!clock.instant().isBefore(stored.deadline)) {
        pending.remove(key, stored);
        return Optional.empty();
    }
    return Optional.of(stored.pending);
}
```

```bash
$ grep -n '@Scheduled' src/main/java/.../command/ConfirmStateService.java
# (no hits)
```

**Why this is wrong / suboptimal / risky:**

Pending entries are evicted lazily only when the same `(actorUserId, scope)` key is queried again — through `peek`, `takeMatching`, `takeAny`. A user who issues a confirmable command (`/ban`, `/clear`, `/forget`, `/remove-source`, `/source-enable`, `/invite revoke`, `/invite create --open`, `/reject-group`, `/unfollow-tag --all`) and then never returns leaves a `Stored` value in the map past its `deadline`, forever.

A user who interacts a lot eventually hits `peek` from the router's step 4.5 sweep on every inbound; but a user who issues exactly one confirmable command and then is silent (loses interest, switches adapters, churns) leaves a leaked entry. Multiply across an admin auditing a hundred groups (`/reject-group`, `/remove-source`, `/source-enable` are all confirmable), or any inbound flood that survives the per-user rate cap.

`RateCapBucket` solves the same problem (lazy + scheduled eviction, lines 204-219), and the class javadoc points at `RateCapBucket` for clock seam yet does not replicate the scheduled-sweep pattern. `InviteCodeConsumer.evictStaleBreachAudited` (line 258) also has the same fix shape inline. The pattern is established in this module.

**Recommended fix:**

Add a `@Scheduled` sweep mirroring `RateCapBucket.evictIdleBuckets`. The predicate is "deadline has passed and clock now is past it"; the operation is `pending.entrySet().removeIf(...)` with the value's `deadline` field checked under no lock (the writes are `pending.put`/`pending.compute`, no shared mutable state inside `Stored`).

```java
@Scheduled(every = "{infochat.confirm.sweep-interval:1m}")
void evictExpired() {
    Instant now = clock.instant();
    pending.entrySet().removeIf(e -> !now.isBefore(e.getValue().deadline));
}
```

**Reasoning:**

A 60s sweep matches the default `infochat.confirm.timeout=60s`; under default settings every expired entry is removed within at most one sweep interval after its deadline. Memory bound becomes "number of pending confirms with deadline within the last sweep-interval," which is bounded by the per-user rate cap. The sweep is idempotent and the iterator-removeIf path on `ConcurrentHashMap` is concurrency-safe.

The clock is already a class field, so test seams continue to work without further plumbing.

**Trade-offs:**

One Quarkus `@Scheduled` registration per process (negligible). The sweep walks the map once per minute, which is O(N) on map size. The alternative — a priority queue keyed by deadline — is more code for marginal benefit at the v1 scale.

---

### F4. `/invite` command is silently DM-only for bot admins

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java:170-181,665-667`

**Current code:**

```java
@Override
public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
    String inboundAdapter = inboundContext.adapterName();
    String callerContactId = contactIdOf(scope);    // ← returns null for group scope

    // Step 1 — admin gate. Resolved by the inbound adapter regardless
    // of which --adapter the command targets ...
    Optional<UserRow> actorOpt = lookupUser(inboundAdapter, callerContactId);
    if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
        return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
    }
    ...
}

private static @Nullable String contactIdOf(ScopeRef scope) {
    return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
}
```

`BanCommandHandler` and `UnbanCommandHandler` use the same DM-only `contactIdOf`. `ApproveGroupCommandHandler.handle` line 107 instead uses `inboundContext.senderContactId()`, which is set by `InboundRouter` line 357 from `msg.sender().contactId()` regardless of scope.

**Why this is wrong / suboptimal / risky:**

`docs/spec/commands.md` §Admin lists `/invite create --adapter <name> --open` and `--contact` as bot-admin commands with no DM-only restriction. `/approve-group` and `/reject-group` explicitly say "DM or group context." The spec's general rule (line 813 §Admin) is "the unknown-contact rule applies uniformly" — not "DM only."

In group scope, `contactIdOf` returns null → `lookupUser(adapter, null)` short-circuits in `UserRepository` → `actorOpt.isEmpty()` → user gets `error.admin_only`. This is silently wrong: a bot admin who runs `/invite create --adapter signal --contact ...` in a group sees the same error as a non-admin user. The error is misleading and the behavior contradicts the spec.

The same shape exists in `BanCommandHandler` line 154 and `UnbanCommandHandler` line 131 (both DM-only), and probably others. `/ban`'s "DM only" might be defensible (it's destructive and admins typically have a DM channel), but the spec does not restrict it. `/invite` is constructive and there is no plausible rationale to forbid it from group scope.

**Recommended fix:**

Switch the actor resolution to `inboundContext.senderContactId()` — the existing seam ApproveGroupCommandHandler uses — so admin commands resolve the caller the same way regardless of scope:

```java
String callerContactId = inboundContext.senderContactId();
```

Audit the other admin handlers (`BanCommandHandler`, `UnbanCommandHandler`, `VouchCommandHandler`, `GrantAdminCommandHandler`, `RevokeAdminCommandHandler`, `PromoteCommandHandler`, `DemoteCommandHandler`, `AuditCommandHandler`, `QuarantineCommandHandler`) for the same `contactIdOf(scope) → null in group` pattern and align.

**Reasoning:**

The per-(scope, caller) identity that matters for the admin gate is "who sent this message," not "what's the DM key." `InboundContext.senderContactId` is set by `InboundRouter.onMessage` on every inbound, regardless of scope. The spec's `/promote`/`/demote` are explicitly group-context. Other admin commands are not restricted, and silently rejecting them in group scope with `error.admin_only` makes the admin re-discover the constraint experimentally.

**Trade-offs:**

If the operator intended DM-only admin (a soft hardening), the change weakens that — but the spec does not promise that hardening, and `/approve-group` already permits both. If a deliberate DM-only constraint is desired, it should be a separate explicit reply (`error.admin.dm_only`) rather than the misleading `error.admin_only`.

**Alternative options:**

- **Option A** (the recommended fix above) — use `senderContactId` uniformly.
- **Option B** — add an explicit `error.admin.dm_only` bundle key and emit it for the DM-only handlers; leave `contactIdOf` as is. Pros: honest about the constraint; cons: confirms a constraint the spec does not require.

---

### F5. `/save` does not scope-check the target post

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java:99-104,257-279`

**Current code:**

```java
private static final String SELECT_POST_SQL =
        "SELECT p.id, p.title, p.body, p.url, p.author, p.published_at, "
                + "p.source_id, s.bootstrap_tags "
                + "FROM post p JOIN source s ON s.id = p.source_id "
                + "WHERE p.uid = ? AND p.status = 'READY' "
                + "ORDER BY p.fetched_at DESC LIMIT 1";
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/commands.md` §Content `/save` Visibility-of-target rules say:

> The `/save` flow never lets a user bookmark content they cannot see.

A user can only see posts via `source_subscription` rows for their `(scope_kind, scope_id)`. SaveCommandHandler's SELECT has no scope filter, no subscription join. A user who knows or guesses any READY post's `uid` can bookmark it whether their scope is subscribed to its source or not — and `/saved` will subsequently render the snapshot body. Combined with the chat-tool `getPost` (which IS scope-filtered, lines 43-62), the attack surface is small (the user must know the UID), but the asymmetry breaks an invariant the spec commits to.

Compare `GetPostTool` line 46-47 which does enforce subscription scoping:

```java
+ "AND p.source_id IN (SELECT source_id FROM source_subscription "
+ "WHERE scope_kind = ? AND scope_id = ?)"
```

The same filter belongs in `/save`'s lookup. Save semantics are global (D13), but the *visibility check at save time* must be scope-anchored — the spec is explicit.

**Recommended fix:**

Add the source-subscription filter against the calling `(scope_kind, scope_id)` to the post lookup:

```java
private static final String SELECT_POST_SQL =
        "SELECT p.id, p.title, p.body, p.url, p.author, p.published_at, "
                + "p.source_id, s.bootstrap_tags "
                + "FROM post p JOIN source s ON s.id = p.source_id "
                + "WHERE p.uid = ? AND p.status = 'READY' "
                + "  AND p.source_id IN (SELECT source_id FROM source_subscription "
                + "                       WHERE scope_kind = ? AND scope_id = ?) "
                + "ORDER BY p.fetched_at DESC LIMIT 1";
```

The handler then accepts `scopeKind`/`scopeId` (resolved the same way `AddSourceCommandHandler` already does at line 145-152) and binds them. Saving in DM uses the user's DM scope; saving in a group uses the group's scope; the saved row remains global per D13.

**Reasoning:**

The visibility-vs-storage split is honored: scoped *check* at save time, unscoped *list* at saved-time. Spec language "The `/save` flow never lets a user bookmark content they cannot see" matches exactly: an unknown UID and a UID-from-an-unsubscribed-source both surface as `error.save.unknown_uid`, indistinguishably (no oracle).

**Trade-offs:**

One extra subquery in the SELECT — cheap given the leading `p.uid = ?` predicate. Users who save a post and then `/unfollow-source` later keep their saved snapshot (D13) — the check is at save time only.

---

### F6. `SET LOCAL infochat.actor_id` uses string-concatenated SQL

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```
infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java:281
infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java:289
infochat-provider/src/main/java/app/zcat/infochat/provider/command/RejectGroupCommandHandler.java:214
infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java:119,212,252
infochat-provider/src/main/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandler.java:151
infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java:171,230,231 (also SET LOCAL infochat.request_id)
infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java:164
infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java:297
```

Verbatim pattern (every site):

```java
try (Statement st = conn.createStatement()) {
    st.execute("SET LOCAL infochat.actor_id = '" + actor.id + "'");
}
```

**Why this is wrong / suboptimal / risky:**

`SET LOCAL` does not accept JDBC bind parameters for the value, so the interpolation is forced. The values are `UUID`s today, so injection is not reachable (the canonical 36-char `[0-9a-f-]` literal contains no SQL metacharacters). UnbanCommandHandler line 231 acknowledges this in a comment.

Two problems remain:

1. The same pattern is repeated 12 times across 8 files. Any future change (a JSON-typed actor value, a Postgres role-switch wrapping, an audit-row correlation id that is not a UUID) silently becomes injection-prone in every site simultaneously. The "it's safe because UUID" argument is fragile and unenumerated.

2. The pattern violates §1 "Match existing style" — every other SQL site in the module uses `PreparedStatement` with binds, even where the value is a known-safe enum or numeric (e.g. `BanCheck` line 47, `QuarantineCommandHandler` line 215 right next to the offending `Statement`). The mixed style is a maintenance trap.

The correct Postgres surface is `set_config(setting, value, is_local)`:

```sql
SELECT set_config('infochat.actor_id', $1, true);
```

`set_config(text, text, boolean)` accepts bind parameters for both `setting` and `value`. The third argument `true` mirrors the `LOCAL` semantics. Use through a normal `PreparedStatement`.

**Recommended fix:**

Extract a single helper in a shared utility (e.g. `core/audit/AuditSession.setActor(Connection, UUID)`):

```java
public static void setActor(Connection conn, UUID actorId) throws SQLException {
    try (PreparedStatement ps =
            conn.prepareStatement("SELECT set_config('infochat.actor_id', ?, true)")) {
        ps.setString(1, actorId.toString());
        ps.execute();
    }
}
```

Replace every `Statement.execute("SET LOCAL infochat.actor_id = '" + actor.id + "'")` with `AuditSession.setActor(conn, actor.id)`. Same for the `infochat.request_id` site in `UnbanCommandHandler` line 231.

**Reasoning:**

The fix removes 12 instances of string concatenation into SQL, replaces them with parametrized calls, and creates one chokepoint for future changes (e.g. switching to a numeric actor id, or adding a per-request session label). Semantically identical at the DB layer: `set_config(name, value, true)` is the function form of `SET LOCAL name = value`.

**Trade-offs:**

`set_config` returns the new value as a row, so the helper uses `execute()` not `executeUpdate()`. Existing tests that grep for `SET LOCAL` in audit logs may need updating; tests that read `current_setting('infochat.actor_id')` continue to work unchanged. One round-trip's worth of cost per admin command, indistinguishable.

---

### F7. `QuarantineReviewListener` does not catch up on reconnect

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:294-310`; compare `outbox/NewPostListener.java:229-264`

**Current code:**

```java
// QuarantineReviewListener
private PGConnection ensureListenConnection() throws SQLException {
    if (listenConnection == null || listenConnection.isClosed()) {
        closeListenConnectionQuietly();
        openListenConnection();
        LOG.info("QuarantineReviewListener: (re)acquired LISTEN connection "
                + "and re-issued LISTEN " + CHANNEL);
    }
    return Objects.requireNonNull(listenConnection).unwrap(PGConnection.class);
}
```

```java
// NewPostListener — the matching code DOES catch up
private PGConnection ensureListenConnection() throws SQLException {
    if (listenConnection == null || listenConnection.isClosed()) {
        closeListenConnectionQuietly();
        openListenConnection();
        LOG.info("NewPostListener: (re)acquired LISTEN connection and re-issued LISTEN new_post");
        reconcileAfterReconnect();
    }
    return Objects.requireNonNull(listenConnection).unwrap(PGConnection.class);
}

private void reconcileAfterReconnect() throws SQLException {
    try {
        newPostReconciler.runCatchUp();
    } catch (SQLException e) {
        closeListenConnectionQuietly();
        throw e;
    }
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/architecture.md` §Catch-up commits the high-water mark mechanism for BOTH channels equally: "The `quarantine_review` channel uses the same cursor mechanism on its own `provider_state` row." A transient Postgres outage that drops the LISTEN session means NOTIFYs fired between disconnect and re-LISTEN are not delivered. `NewPostListener` covers that gap by running the reconciler after every reconnect; `QuarantineReviewListener` covers it only at process startup via `@Startup` priority 250.

Consequence: a Provider that drops and reconnects to PG (network blip, PG restart, connection-pool churn) loses the admin notification on every `PENDING` insert or `NEEDS_REVIEW` transition that fired during the blip. The `QuarantineReviewReconciler.runCatchUp` will advance the cursor on next startup, but `fireAdminNotification` is intentionally skipped on the reconciler path (`QuarantineReviewReconciler` only advances cursor — confirmed by its class javadoc lines 28-31). So the missed admin notifications never fire — period.

The class javadoc admits this is a design choice ("admins will see the queue on the next `/quarantine list`"), but the live listener vs reconciler asymmetry means a transient mid-process outage produces worse behavior than a process restart (which catches up). That's the wrong way around.

**Recommended fix:**

Mirror `NewPostListener.reconcileAfterReconnect()` in `QuarantineReviewListener.ensureListenConnection`. The reconciler advances the cursor; for the *post-reconnect* path the listener can call the catch-up scan AND fire admin notifications for each scanned row. The simplest shape: extract a `catchUpAndNotify` method on `QuarantineReviewReconciler` that the listener calls on reconnect; it walks the same SQL but invokes `listener.handleEvent` (already package-private) for each row, so the notification logic stays in one place.

```java
// QuarantineReviewListener.ensureListenConnection
private PGConnection ensureListenConnection() throws SQLException {
    if (listenConnection == null || listenConnection.isClosed()) {
        closeListenConnectionQuietly();
        openListenConnection();
        LOG.info("QuarantineReviewListener: (re)acquired LISTEN connection ...");
        try {
            quarantineReviewReconciler.runCatchUpWithNotify(this::handleEvent);
        } catch (SQLException e) {
            closeListenConnectionQuietly();
            throw e;
        }
    }
    return Objects.requireNonNull(listenConnection).unwrap(PGConnection.class);
}
```

**Reasoning:**

The fix removes the post-reconnect gap entirely; the cursor and the admin notifier both observe the same set of events on the live and the reconciliation paths. Process-startup behavior stays as is (the spec accepts no admin notification for events missed across a full process restart, because the reconciler still advances the cursor and the admin's next `/quarantine list` sees the work). The two paths are now symmetric.

**Trade-offs:**

A reconnect now pays the catch-up scan cost. Bounded by `infochat.provider.catchup.quarantine-page-size=500` per page. If the spec genuinely wants reconnect to behave like startup (cursor-only, no notify), the alternative is to keep current behavior and document it loud in the class javadoc — but the current javadoc doesn't make the live-vs-reconciler split visible, so an operator looking at why admin notifications are missing has nothing to read.

**Alternative options:**

- **Option A** (the recommended fix above) — symmetric notify on reconnect.
- **Option B** — explicit `cursor-only` behavior on both reconnect AND startup; document the trade-off; add a Prometheus counter on cursor advances by source (`live_notify` vs `reconciler`) so operators can observe the gap.

---

### F8. `ExportDataCollector` false-positive truncation + `LIMIT N` via string concatenation

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportDataCollector.java:188-199`

**Current code:**

```java
private void collectTable(LinkedHashMap<String, List<String>> tables,
                          List<String> truncated,
                          String tableName, List<String> rows) {
    if (rows.size() >= maxRowsPerTable) {
        truncated.add(tableName);
    }
    tables.put(tableName, rows);
}

private String withLimit(String sql) {
    return sql + " LIMIT " + maxRowsPerTable;
}
```

**Why this is wrong / suboptimal / risky:**

1. **False-positive truncation.** `LIMIT maxRowsPerTable` returns *at most* that many rows. When the table contains EXACTLY `maxRowsPerTable` rows, the user is told their export was truncated even though every row was returned. With `maxRowsPerTable=10000` and a user whose `saved_post` count is exactly 10 000, the export reply says "saved_post truncated" — incorrect. The fix is `LIMIT maxRowsPerTable + 1`, then flag truncation when the returned list size exceeds `maxRowsPerTable` (and drop the extra row before serialization).

2. **`LIMIT N` via string concatenation.** `maxRowsPerTable` is an `int` from config, so injection is not reachable. But the pattern is wrong — every other SQL in this collector uses bind parameters. JDBC drivers will happily parameter-bind a `LIMIT ?` clause on Postgres, so the concatenation is style drift, not a forced choice. Also, the concatenated form is computed per `withLimit(sql)` call (string allocation per table), not cached.

**Recommended fix:**

```java
private void collectTable(LinkedHashMap<String, List<String>> tables,
                          List<String> truncated,
                          String tableName, List<String> rows) {
    if (rows.size() > maxRowsPerTable) {
        truncated.add(tableName);
        rows = rows.subList(0, maxRowsPerTable);   // drop the sentinel
    }
    tables.put(tableName, rows);
}

private String withLimit(String sql) {
    return sql + " LIMIT ?";
}

// in each query method, bind maxRowsPerTable + 1 as the last parameter
```

Or — simpler if the truncation signal is rarely consumed — leave `LIMIT N` exact and tighten the predicate to `rows.size() == maxRowsPerTable && hasMoreRows(...)` via a second SELECT. The "+1 sentinel" pattern above is the cheapest.

**Reasoning:**

The fix corrects the false-positive (a user seeing truncation when there isn't any can't trust the export), aligns with the bind-parameter style every other SQL in the module uses, and stops the per-call string allocation. The `subList` trims back to the exact cap.

**Trade-offs:**

One extra row touched per table query in the worst case. Negligible against the `LIMIT 10000` size.

---

### F9. `ChatMemoryPruner` truncates retention to whole days

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/scheduler/ChatMemoryPruner.java:29-46`

**Current code:**

```java
@ConfigProperty(name = "infochat.chat.retention", defaultValue = "PT2160H")
Duration retention;

@Scheduled(every = "{infochat.chat.pruner.interval:24h}")
void prune() throws SQLException {
    int days = (int) retention.toDays();
    int total = 0;
    try (Connection conn = dataSource.getConnection()) {
        total += deleteOlderThan(conn,
            "DELETE FROM chat_memory WHERE created_at < now() - make_interval(days => ?)",
            days);
        ...
    }
}
```

**Why this is wrong / suboptimal / risky:**

`Duration.toDays()` truncates: `PT12H.toDays() == 0`, `PT47H.toDays() == 1`. An operator setting `infochat.chat.retention=PT12H` (twelve hours) gets `days = 0`, then `make_interval(days => 0)` is the zero interval, and the DELETE becomes `created_at < now() - INTERVAL '0 days'` — i.e. delete every row.

D37 makes retention a *privacy commitment*: the operator deliberately bounds how long the bot holds the user's conversation history. A truncation bug that silently maps "shorter retention" to "delete everything" is the exact wrong failure mode. Some operator profiles want a tight window (Pi is documented as 30d but a future tighter profile is plausible; a security-tier deployment might want 1 hour).

The `Duration` type carries seconds; the SQL function accepts any interval — passing the duration through verbatim avoids the lossy conversion.

**Recommended fix:**

Use a sub-day-safe interval. Postgres `make_interval` accepts `secs` (`double precision`):

```java
@Scheduled(every = "{infochat.chat.pruner.interval:24h}")
void prune() throws SQLException {
    long seconds = retention.getSeconds();
    int total = 0;
    try (Connection conn = dataSource.getConnection()) {
        total += deleteOlderThan(conn,
            "DELETE FROM chat_memory WHERE created_at < now() - make_interval(secs => ?)",
            seconds);
        ...
    }
}

private int deleteOlderThan(Connection conn, String sql, long seconds) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong(1, seconds);
        return ps.executeUpdate();
    }
}
```

**Reasoning:**

`make_interval(secs => N)` is exact at one-second resolution, which is finer than any plausible retention horizon. The log line can still report days for readability.

**Trade-offs:**

None — the fix is strictly better.

---

### F10. LLM tool LIMIT clamping is inconsistent across tools

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetReferencesTool.java:38-39,108-116`; `chat/tool/SearchPostsTool.java:51-54`; `chat/tool/RecallMemoryTool.java:46`; `chat/tool/ListSavesTool.java:29-30`

**Current code:**

```java
// GetReferencesTool
private static final int LIMIT_DEFAULT = 25;
private static final int LIMIT_MAX = 25;

private static int readLimit(Map<String, Object> args) {
    if (!args.containsKey("limit")) return LIMIT_DEFAULT;
    int requested = ((Number) args.get("limit")).intValue();
    if (requested < 1) return 1;
    if (requested > LIMIT_MAX) return LIMIT_MAX;
    return requested;
}

// SearchPostsTool
int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 50;
// (no per-tool clamp — relies on dispatcher's clampLimit)

// RecallMemoryTool — no limit arg at all; hardcoded LIMIT 50
// ListSavesTool — hardcoded LIMIT 200, no limit arg
```

```java
// ChatToolDispatcher applies a profile-driven cap
@ConfigProperty(name = "infochat.chat.tool.limit-cap", defaultValue = "200") int limitCap
...
private void clampLimit(Map<String, Object> args) {
    if (!args.containsKey("limit")) return;
    int limit = ((Number) args.get("limit")).intValue();
    if (limit > limitCap) args.put("limit", limitCap);
    if (limit < 1) args.put("limit", 1);
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §Prompt-injection defenses commits "All free-form string and list inputs across every tool below are length-bounded by a profile-driven cap." The dispatcher clamps `limit` to `limitCap` (default 200), but `GetReferencesTool` re-clamps to a hardcoded 25 below the dispatcher's cap and `RecallMemoryTool`/`ListSavesTool` ignore `limit` entirely with hardcoded `LIMIT 50`/`LIMIT 200`. The result:

- `getReferences` is harder-capped than the dispatcher.
- `searchPosts` honors the dispatcher cap and defaults to 50.
- `recallMemory` ignores `limit` from the LLM altogether (hardcoded 50).
- `listSaves` ignores `limit` from the LLM altogether (hardcoded 200).

The spec also commits per-tool input shapes including `limit` for `searchPosts` and `getReferences`, but not for `recallMemory` or `listSaves`. So the tool-side inputs match the spec for the latter two — but the inconsistency is still a maintenance hazard: changing `infochat.chat.tool.limit-cap` does not propagate uniformly. A future spec amendment that adds `limit` to `listSaves` will look like a one-line change but actually requires touching the tool.

**Recommended fix:**

Push the profile-driven cap into a single shared place — for example, an `Inject @ConfigProperty int limitCap` on each tool that supports a `limit` arg — and remove per-tool hardcoded caps. The dispatcher's `clampLimit` becomes the single point of enforcement.

```java
// GetReferencesTool — drop LIMIT_DEFAULT / LIMIT_MAX constants; trust dispatcher
private static int readLimit(Map<String, Object> args) {
    if (!args.containsKey("limit")) return 25;   // tool-specific default
    return ((Number) args.get("limit")).intValue();
}
```

**Reasoning:**

The dispatcher's clamp is the load-bearing system-boundary check (LLM tool-call args are an external boundary per §7). Tools trusting that clamp keeps the cap consistent with the property. Defaults remain per-tool because the spec leaves the default unspecified.

**Trade-offs:**

None — the fix is strictly better.

---

### F11. Internal-class null guards in `InboundRouter` violate §7

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:457-476,500-515,737-744`

**Current code:**

```java
if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()
        && groupApprovalCheck != null) {
    GroupApprovalCheck.Outcome outcome = groupApprovalCheck.check(...);
    ...
}

if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()
        && groupAutoPromoteService != null) {
    Optional<UUID> groupId = lookupGroupId(adapterName, group.adapterGroupId());
    ...
}

if (assetCommandFamilyOracle != null && assetCommandFamilyOracle.isAssetCommand(commandName)) {
    return assetHandler.handle(commandName, scope, normalized).text();
}
```

Each guard's comment cites the same justification: "plain-JUnit test subclasses do not wire CDI fields."

**Why this is wrong / suboptimal / risky:**

`docs/process/engineering-rules-verbatim.md` §7 is explicit:

> Validation belongs at *system boundaries* — adapter inbound, HTTP endpoints, JSON/YAML config parsing, SQL deserialization, LLM tool-call arguments, file I/O. Inside those boundaries, internal code calling internal code is trusted.

> The reviewer applies this rule narrowly: a defensive check at a system boundary is fine; a defensive check between two internal classes is scope drift.

`groupApprovalCheck`, `groupAutoPromoteService`, and `assetCommandFamilyOracle` are CDI-injected internal collaborators. In production, CDI guarantees they are non-null at injection (and the new `@NonNull`/JSpecify default also rules it out at compile). The null guards exist purely to support test subclasses that bypass CDI — which is testing the production code with an unrealistic configuration to make a unit test cheaper to write.

The right shape is fix the tests, not weaken the production class: either use `@QuarkusTest` so CDI wires the collaborators, or use a constructor that takes the collaborators as arguments (already a pattern in `ChatToolDispatcher` line 56-80 and `RateCapBucket` line 96-117). The current shape couples production code to test-mock convenience.

**Recommended fix:**

1. Remove the three `!= null` guards.
2. Update the affected test subclasses (`InboundRouterNormalizeTest`, `InboundRouterContactIdRedactionTest`, `InboundRouterIntakeOrderingTest` per the existing javadoc references) to either use `@QuarkusTest` or to construct via a package-private constructor that takes the three collaborators.

Add a constructor-based seam to mirror the existing `lookupUser` test override:

```java
// Production: keep CDI fields, drop the null guards
// Test seam: a package-private constructor for plain-JUnit subclasses
InboundRouter(GroupApprovalCheck groupApprovalCheck,
              GroupAutoPromoteService groupAutoPromoteService,
              AssetCommandFamilyOracle assetCommandFamilyOracle, ...) {
    this.groupApprovalCheck = groupApprovalCheck;
    this.groupAutoPromoteService = groupAutoPromoteService;
    this.assetCommandFamilyOracle = assetCommandFamilyOracle;
    ...
}
```

**Reasoning:**

Production behavior becomes simpler (three branches collapse) and the no-defensive-code invariant holds. Tests get an explicit dependency-injection seam rather than relying on field nullability.

**Trade-offs:**

One extra constructor (~6 lines). Three test files need a constructor change. The null guards' removal IS a behavior change — but a behavior change in the direction the rules require.

---

## Cross-module observations (not findings)

These are surface-level notes from reading this module that may matter elsewhere; they are not in-module findings:

- `LlmOutputSanitizer.applyClosedListStripWithMatches` matches the spec's closed list with `\s+` between space-separated tokens (good) but the trailing lookahead `(?=$|[^a-zA-Z0-9\-])` does not cover Unicode-defined word characters; in practice the post-normalize body is mostly ASCII so this is fine, but a CI test against UTF-8 LLM output is worth confirming.
- `InboundRouter`'s `llmCallTimestamps` sliding-window cap is a per-user counter only; spec §Rate limiting also names per-group LLM caps (D47) that are not implemented in the router. `RateCapBucket.tryAcquireGroupReply` covers reply count but not LLM count specifically. Cross-check the per-group LLM bucket spec commitment when wiring the group-LLM cap.
- The cross-module `SET LOCAL infochat.actor_id` SQL pattern (Finding F6) likely exists in `infochat-collector` audit-row writes as well; a shared `AuditSession.setActor` utility lives most naturally in `infochat-core/audit`.
