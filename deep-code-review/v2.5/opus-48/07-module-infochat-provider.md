# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-08 17:42
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — HelpCommandHandler.java:98-101 / CommandPermissions.java:48-49 / bundles/en.properties:100,167 — `/get-tags` and `/get-sources` are spec-committed v1 commands and are advertised to users in the welcome + probation bundles and the probation allow-set, but no `CommandHandler` registers either name, so invoking them returns "Unknown command."
- [medium] MAINTAINABILITY-RULES-DRIFT — DigestRetryService.java:78-83 — `/retry --digest` deletes the cached digest row before re-running the worker, but the worker's own per-`(group, slotKind)` in-flight guard can silently skip the re-run; the cache is then lost while the admin is told `SUCCESS`.
- [low] MAINTAINABILITY-RULES-DRIFT — AddSourceCommandHandler.java:50-55 — the in-handler ban check is justified by a comment claiming "the upstream T2-A ban gate is not yet wired," which is now stale (InboundRouter step 4 performs the ban check); the defensive check and its rationale should be reconciled.
- [low] SECURITY — GroupTimezoneCommandHandler.java:198,200 — fuzzy timezone suggestion folds case with the default locale (`toLowerCase()` without `Locale.ROOT`), diverging from the spec's locale-independent case-folding rule used everywhere else.

## Detail

### F1. `/get-tags` and `/get-sources` are advertised but unimplemented

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java:98-101; infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java:45-56; infochat-provider/src/main/resources/bundles/en.properties:100,167

**Current code:**

```java
// HelpCommandHandler.java
 * Spec commands without a v1 handler ({@code /get-tags},
 * {@code /get-sources}) are intentionally absent — listing a
 * non-dispatchable command would advertise an unknown-command path.
```

```java
// CommandPermissions.java — allowed during probation
private static final Set<String> ALLOWED = Set.of(
        "help", "status",
        "get-tags",
        "get-sources",
        "list-sources", "summary", "saved", "export", "forget", "lang", "stop");
```

```properties
# en.properties:167
error.probation.blocked=You are still in slow-start probation. Full access unlocks in {0}. \
Allowed during probation: /help, /status, /summary, /saved, /get-tags, /get-sources, \
/list-sources, /export, /forget, /lang, /stop.
```

The dispatch path has no handler for either name. `InboundRouter.handleSlash` iterates `commandHandlers`, finds no `name().equals("get-tags")`, falls through the asset-oracle branch, and returns `UNKNOWN_COMMAND_REPLY`. A grep across `src/main` confirms `ListSourcesCommandHandler.name()` returns only `"list-sources"` — the spec-mandated `/get-sources` alias is unregistered — and no class registers `"get-tags"`.

**Why this is wrong / suboptimal / risky:**

`docs/spec/commands.md` §Discovery commits `/get-tags` and `/get-sources` as v1 commands available to "any non-banned user," and `/get-sources` is defined as "an alias of `/list-sources` accepting the same flags except `--all`." This is a spec-level commitment, so the absence of a handler is a SPEC-DRIFT.

The drift is not merely an omission — it is internally inconsistent and user-facing. Three surfaces actively tell the user these commands work:

1. `CommandPermissions.ALLOWED` lists `get-tags`/`get-sources` as permitted during probation, so the probation gate (`InboundRouter` step 5) lets them through to dispatch.
2. `reply.welcome.dm_fresh` (en.properties:100) tells every newly-registered user: "While probation is on, you can: ... /get-tags ...".
3. `error.probation.blocked` (en.properties:167) lists `/get-tags, /get-sources` among allowed commands.

A probation user who follows the welcome message verbatim and types `/get-tags` gets "Unknown command. Try /help" — the exact path `HelpCommandHandler`'s comment claims it is avoiding. The `HelpCommandHandler` carve-out fixes only one of the three surfaces; the bundle strings and the probation gate still advertise the commands.

**Recommended fix:**

Implement the two commands (they are cheap, deterministic, scope-filtered DB reads — exactly the spec's "read-only, scope-filtered" tier):

```java
// New GetTagsCommandHandler: name() -> "get-tags"
// SELECT t.name, (followed?) FROM tag t ... marking the scope's followed tags
// per scope_tag / tag_mode (commands.md §Discovery "marking the scope's followed tags").

// /get-sources alias: register the alias in ListSourcesCommandHandler by
// having InboundRouter.handleSlash treat "get-sources" as "list-sources"
// with --all/--include-deleted stripped, OR add a thin GetSourcesCommandHandler
// that delegates to the same ListSources logic minus the --all flag.
```

If the decision is genuinely to defer these to a later ticket, then the *advertising* must be removed in lockstep: drop `get-tags`/`get-sources` from `CommandPermissions.ALLOWED` and from both bundle strings in `en.properties` and `cs.properties`, so no surface promises a command that returns "Unknown command."

**Reasoning:**

The bar is "would a careful senior reviewer let this through?" A first-run user being told to use a command that the bot then rejects is a visible contract break on the most-trafficked onboarding path (the welcome message). Either direction (implement, or stop advertising) closes the inconsistency; the spec favors implementing, since the commands are committed v1 surface.

**Trade-offs:**

Implementing adds two small handlers (or one handler plus an alias). Removing the advertising is smaller but leaves the spec commitment unmet, which a later ticket must still satisfy. Implementing is the spec-aligned choice.

### F2. `/retry --digest` can destroy the cached digest and still report success

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java:69-88; infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java:76-91

**Current code:**

```java
// DigestRetryService.retryDigest
deleteCacheRow(groupId, coords.slotKind, coords.slotFiredAt);
DigestSlot slot = new DigestSlot(
        groupId, timezone, coords.slotKind,
        coords.slotFiredAt, coords.expiresAt);
digestWorker.execute(slot);
lastRetryAt.put(groupId, Instant.now());
return RetryResult.SUCCESS;
```

```java
// DigestWorker.execute
String inFlightKey = slot.groupId() + ":" + slot.slotKind();
if (!inFlightSlots.add(inFlightKey)) {
    LOG.warnf("Digest already in flight for group %s slot %s — skipping overlapping execution",
            slot.groupId(), slot.slotKind());
    return;   // <-- silent skip
}
```

**Why this is wrong / suboptimal / risky:**

`DigestRetryService` guards concurrency with its own per-`groupId` `inFlight` map, but a *scheduled* digest fires `DigestSlot` as a CDI event observed directly by `DigestWorker.execute`, bypassing `DigestRetryService`'s lock entirely. If a scheduled run for the same `(group, slotKind)` is in flight when an admin issues `/retry --digest`:

1. `retryDigest` passes its own `inFlight.putIfAbsent` (different map).
2. `deleteCacheRow` removes the cached digest row.
3. `digestWorker.execute(slot)` hits the worker's `inFlightSlots.add` guard, which returns `false`, and the worker returns immediately — **no re-collection, no re-cache, no delivery.**
4. `retryDigest` proceeds to `lastRetryAt.put(...)` and returns `RetryResult.SUCCESS`.

The result: the group's cached digest is gone, nothing replaced it, and the acting admin is told the retry succeeded. `commands.md` §`/retry` commits that the retry *replaces* the cached digest; a path that deletes-then-skips leaves the cache empty and the cache message handle stale, which violates the "replaces the cached digest" contract and silently degrades the next reader's view.

The window is narrow (requires a concurrent scheduled run of the exact same slot), but the failure mode is destructive and the success report is actively misleading.

**Recommended fix:**

Make the worker's skip observable to the caller and re-order so the delete only commits when the regeneration actually runs:

```java
// DigestWorker: return a boolean (or a small result enum) instead of void.
public boolean execute(DigestSlot slot) {           // also keep an @Observes overload
    if (!inFlightSlots.add(inFlightKey)) {
        return false;                               // skipped — caller must not claim success
    }
    try { executeSlot(slot); return true; }
    ...
}
```

```java
// DigestRetryService.retryDigest: regenerate first, delete inside the same
// guarded section, and surface the skip.
boolean ran = digestWorker.execute(slot);   // re-collect + re-cache (UPSERT) under the worker lock
if (!ran) {
    return RetryResult.ALREADY_IN_PROGRESS; // the scheduled run owns the slot; cache untouched
}
lastRetryAt.put(groupId, Instant.now());
return RetryResult.SUCCESS;
```

This requires `SummaryCacheRepository.insert` to UPSERT on `(group_id, slot_kind, slot_fired_at)` so the explicit `deleteCacheRow` becomes unnecessary; the regeneration overwrites the row atomically and the destructive delete-before-skip window disappears.

**Reasoning:**

The root defect is that the delete and the regeneration are not atomic with respect to the worker's own guard, and the caller cannot tell whether the worker actually ran. Returning a status and making the cache write an UPSERT closes both: there is no moment where the cache is deleted but not yet rewritten, and a skipped run is reported honestly as `ALREADY_IN_PROGRESS`.

**Trade-offs:**

Changes `DigestWorker.execute`'s signature (one caller in this module plus the `@Observes` entry point — keep a void-returning `@Observes` wrapper that ignores the boolean). Requires the cache repository to support UPSERT. Both are small, contained changes.

**Alternative options:**

- **Option A** (the recommended fix above): return status + UPSERT.
- **Option B** — have `DigestRetryService` acquire the *same* `inFlightSlots` key the worker uses (expose `tryAcquireSlot(groupId, slotKind)` on the worker) before deleting, so the retry and the scheduled run serialize on one lock. Pros: no signature change to `execute`. Cons: leaks the worker's internal guard into the service API and still relies on delete-then-execute ordering for the non-skip path.

### F3. Stale rationale on the in-handler ban check in `/add-source`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java:50-55,124-128

**Current code:**

```java
 *   <li>Ban check — defense-in-depth; the upstream T2-A ban gate
 *       is not yet wired so the handler reads the flag itself.</li>
...
Optional<UserRow> actor = lookupActor(contactId);
if (actor.isPresent() && actor.get().isBanned) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_BANNED));
}
```

**Why this is wrong / suboptimal / risky:**

The comment's premise — "the upstream T2-A ban gate is not yet wired" — is false in the current tree. `InboundRouter.onMessage` step 4 (line 450) calls `banCheck.isBanned(...)` and returns the fixed ban reply before any command handler is reached, for both DM and group scope. A banned user can therefore never reach `AddSourceCommandHandler.handle`. The in-handler check is dead defensive code between two internal layers, justified by a rationale that no longer holds.

Under `engineering-rules-verbatim.md` §7, a defensive check between two internal classes (router → handler, both inside the ban trust boundary) is scope drift, not a system-boundary validation. The other admin handlers in this module (e.g. `GrantAdminCommandHandler`) keep an in-tx ban/probation re-check with an explicit defense-in-depth rationale tied to the locking transaction; this one's rationale is simply stale.

**Recommended fix:**

Either remove the in-handler ban check (the router gate is authoritative), or, if defense-in-depth here is deliberate, replace the stale comment with the real reason:

```java
// Defense-in-depth: InboundRouter step 4 (BanCheck.isBanned) already
// blocks banned senders before dispatch; this re-read guards the
// /add-source write path against a ban that lands between the intake
// gate and this handler in the same dispatch.
```

**Reasoning:**

A comment that asserts a false fact about the system ("not yet wired") is worse than no comment — a future reader trusts it and may "wire up" a gate that already exists, or remove the router gate believing the handler covers it. Per `CLAUDE.md` §Coding style the comment policy is WHY-not-WHAT and must not rot; this one has rotted. Pick one of the two reconciliations so the code and its stated intent agree.

**Trade-offs:**

Removing the check is the §7-aligned choice but loses a (currently unreachable) backstop; keeping it with a corrected comment matches the sibling-handler convention. Either is acceptable; the status quo (real check, false comment) is not.

### F4. Locale-dependent case folding in timezone fuzzy suggestions

- **Category:** SECURITY
- **Severity:** low
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandler.java:198,200,207-210

**Current code:**

```java
static String fuzzySuggestions(String input) {
    String lowerInput = input.toLowerCase();                 // default locale
    List<String> matches = ZoneId.getAvailableZoneIds().stream()
            .filter(z -> z.toLowerCase().contains(lowerInput) // default locale
                    || lowerInput.contains(z.toLowerCase().replace("/", "")))
            ...
```

**Why this is wrong / suboptimal / risky:**

`String.toLowerCase()` with no argument folds case using the JVM default locale. `docs/spec/commands.md` §Surface conventions mandates locale-independent folding (`String.toLowerCase(Locale.ROOT)`) precisely so `İ`/`I` and similar do not diverge between locales — and the rest of this module already uses `Locale.ROOT` (e.g. `AddSourceCommandHandler.resolverHintedRssByPath`/`contradictsRss`). On a JVM running with a Turkish locale, `"Istanbul".toLowerCase()` yields `"ıstanbul"`, so the fuzzy match for `Europe/Istanbul` silently breaks for an operator who typo'd it.

This is a user-experience degradation, not an authorization break (the authoritative validation is `ZoneId.of(tzArg)`, which is locale-independent), so it is low severity — but it is a concrete deviation from a spec-stated rule that the codebase otherwise honors.

**Recommended fix:**

```java
String lowerInput = input.toLowerCase(Locale.ROOT);
...
.filter(z -> z.toLowerCase(Locale.ROOT).contains(lowerInput)
        || lowerInput.contains(z.toLowerCase(Locale.ROOT).replace("/", "")))
...
.filter(z -> levenshtein(z.toLowerCase(Locale.ROOT), lowerInput) <= 3)
```

`java.util.Locale` is already imported in sibling handlers; add the import here.

**Reasoning:**

Locale-independent folding is the project-wide rule for any case comparison; using the default locale makes behavior depend on the deployment host's locale setting, which is exactly the non-determinism the spec rule exists to prevent. The fix is mechanical and strictly more correct.

**Trade-offs:**

None — the fix is strictly better.

## Synthesizer-relevant observations

- The chat tool surface (`SearchPostsTool`, `GetPostTool`, `GetReferencesTool`, `RecallMemoryTool`, `ListSavesTool`) and `ChatToolDispatcher` correctly enforce per-`(user, scope)` isolation (every query carries the `source_subscription`/`chat_memory`/`saved_post` scope predicate), the closed allowlist, and pre-SQL input bounds. The registry-vs-handler completeness check (`ChatToolDispatcher.requireHandlerForEveryAdvertisedTool`) and the spec-mirroring `LlmOutputSanitizer.CLOSED_LIST` (with its CI byte-equality test) are cross-module contracts worth confirming against the architecture lens: the sanitizer's closed list and the chat registry's tool-name set are both asserted equal to spec at TEST tier, but that equality lives in this module's tests — the architecture reviewer should confirm the spec files those tests read are the canonical ones.
- `ClusterTraversal` is deterministic Java BFS over SQL-fetched `post_reference` edges, not literal "SQL traversal" as `commands.md` §`/summary` phrases it. The determinism boundary (cluster set reproducible from DB state, computed before any LLM call) is honored, so this is not a finding — but the spec wording ("deterministic SQL traversal") slightly overstates the mechanism and may warrant a one-word spec clarification ("deterministic traversal") in the architecture/spec pass.
- The admin-tier handlers (`GrantAdminCommandHandler`, `RevokeAdminCommandHandler`, `PromoteCommandHandler`, `ApproveGroupCommandHandler`) consistently implement audit-on-intent (separate auto-commit connection, pre-transaction, to avoid the `audit_log.actor_user_id` FK FOR-KEY-SHARE vs FOR-UPDATE deadlock) and in-tx `SELECT ... FOR UPDATE` admin gates. The last-admin protection relies on the V35 trigger raising SQLSTATE `IC001`; that the trigger and the SQLSTATE constant agree is a schema/Provider cross-module contract for the architecture lens.
