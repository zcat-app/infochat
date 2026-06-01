# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-01 12:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [critical] SECURITY — `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:284,604-628` — single `volatile MessagingAdapter replyTarget` makes the last-registered adapter the reply path for every inbound, so in a SimpleX+Signal deployment a SimpleX user's reply ships through Signal (cross-adapter user spoofing + outbound to an unrelated identity).
- [critical] MAINTAINABILITY-RULES-DRIFT — `infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetCommandRouter.java:24-55` — only `/zcash` and `/monero` are wired as `CommandHandler`s; any third asset added to `bootstrap-assets.json` is invisible to the slash dispatcher, contradicting the spec's "future asset can land without a new top-level command per verb" commitment and the operator-config-driven `bootstrap-assets.json` contract.
- [high] SECURITY — `infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java:62-69,97-100` — `/stop` is silently a no-op in group scope and uses the wrong scope key (`scopeKind="dm"`, `scopeId=userId`) even in DM, so it can never cancel in-flight chat work executed under a group scope; spec §Chat mode commits to per-(user, scope) cancellation in groups.
- [high] MAINTAINABILITY-RULES-DRIFT — `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java:46-74` — `/help` is hard-coded to three lines (`help`, `add-source`, `summary`) plus the asset list and never filters by caller tier; spec §Discovery requires per-tier filtering (probation, non-admin, non-group-admin) and the bundle-composition contract.
- [high] PERFORMANCE — `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:300-553` — every inbound burns a fresh JDBC connection at step 1 (`lookupUser`) and again at step 4 (`BanCheck.isBanned`) plus a third for `lookupGroupId` in group scope, then more inside each command handler; one inbound easily costs 6–10 short-lived connections from the pool.
- [medium] SECURITY — `infochat-provider/src/main/java/app/zcat/infochat/provider/command/PromoteCommandHandler.java:90-93,158-169` — the admin gate on `/promote` reads the actor row without `FOR UPDATE`, so a concurrent `/revoke-admin` against the caller can commit between the admin check and the demote/promote UPDATEs, allowing a freshly-demoted admin to swap the group admin.
- [medium] MAINTAINABILITY-RULES-DRIFT — `infochat-provider/src/main/java/app/zcat/infochat/provider/group/MembershipEventHandler.java:105-127` — `writeAudit` happens AFTER the membership UPDATE and swallows `SQLException` with a log line; this inverts Invariant 7 (audit-before-effect) for `MEMBER_LEFT` and `BOT_REMOVED`.
- [medium] SECURITY — `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java:251-305` — the hand-written tool-call parser (`parseToolArgs` + `splitTopLevel`) is best-effort string-mashing for what the spec models as a structured tool-arg payload; unescaped quotes, nested objects, or arrays are silently mangled rather than rejected, weakening the typed-argument boundary on which §Prompt-injection defenses depends.
- [medium] PERFORMANCE — `infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java:187-209` — the closed-list strip compiles a fresh `Pattern` per token per call and iterates 26 tokens for every LLM reply (chat, summary, digest, retry); `Pattern.compile` is invoked ~26×N times under chat load.
- [medium] SECURITY — `infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java:305-321,265-284` — personal tags supplied with `/save -t …` are inserted verbatim with no length or count cap; spec §Prompt-injection defenses caps `listSaves` tool reads but the write path has no symmetric cap, letting one user store arbitrarily large strings under each save row.
- [medium] SECURITY — `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java:46-48` — `Duration.parse((String) args.get("window"))` is uncaught; an LLM that emits a malformed ISO duration throws `DateTimeParseException` past `ChatToolDispatcher`'s `IllegalArgumentException`-only catch and surfaces `INTERNAL_ERROR_REPLY` to the user instead of the typed validation error the spec promises.
- [low] SIMPLIFICATION — `infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java:462-486,UnbanCommandHandler.java,GrantAdminCommandHandler.java:368-392,ApproveGroupCommandHandler.java:331-351,SourceUpsertService.java` (cross-cutting) — every handler that emits `details_json` ships its own hand-written `quoteJsonString` / `escapeJson` / inline string-builder; one shared `JsonText.quote(String)` helper would replace ~150 duplicated lines.
- [low] MAINTAINABILITY-RULES-DRIFT — `infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java:156,160` — `tokens[i+1].toLowerCase()` and `tokens[i].toLowerCase()` use the JVM-default locale; sub-verb / `--vs` matching should be `Locale.ROOT` for the same reason the spec pins tag normalization to `Locale.ROOT`.

## Detail

### F1. Single reply-target makes multi-adapter outbound route to the wrong adapter

- **Category:** SECURITY
- **Severity:** critical
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:284, 290-292, 604-628; AdapterRegistry.java:254-266

**Current code:**

```java
// InboundRouter.java
private volatile MessagingAdapter replyTarget;

void setReplyTarget(MessagingAdapter adapter) {
    this.replyTarget = adapter;
}

private void sendReply(ScopeRef scope, String body) {
    MessagingAdapter target = replyTarget;
    if (target == null) {
        log.error("InboundRouter has no replyTarget; dropping reply for scope={}", ...);
        return;
    }
    try {
        target.send(new OutboundMessage(scope, body, Instant.now(), UUID.randomUUID().toString()));
    } ...
}

// AdapterRegistry.java (start)
for (MessagingAdapter adapter : activating) {
    inboundRouter.setReplyTarget(adapter);                       // last call wins
    String adapterName = adapter.name();
    adapter.setInboundHandler(msg -> inboundRouter.onMessage(msg, adapterName));
    ...
}
```

**Why this is wrong / suboptimal / risky:**

The Provider spec commits to running the SimpleX + Signal adapters simultaneously in v1 (decision D46, `docs/spec/architecture.md` §Topology; reinforced by the per-adapter admin threat model in `docs/spec/security.md` §Per-adapter admin threat profile and the cross-adapter isolation invariant in `docs/spec/messaging.md` §Per-adapter trust level). Every reply must travel back through the same adapter the inbound arrived on; cross-adapter outbound is a hard isolation violation.

The current implementation registers one inbound handler per adapter (which is correct — the lambda captures `adapterName`) but stores a single `volatile MessagingAdapter replyTarget` that `AdapterRegistry.start()` overwrites once per activated adapter. The last `activating` entry wins for the rest of the process lifetime. In a SimpleX + Signal deployment, every reply — bans, invite-required notices, command output, chat-mode prose — is shipped through the alphabetically-last adapter regardless of which adapter the inbound arrived on.

Concrete consequences:

1. A SimpleX user's `/help` reply is delivered to the Signal adapter, which either silently drops it (no Signal session for that contact) or attempts to send to a Signal contact id that does not exist on this Provider.
2. A banned SimpleX user receives no fixed ban reply because the Signal adapter has no SimpleX scope to deliver to — instead the operator sees adapter send-errors. The intake counter increments but the user-visible behaviour silently changes from "fixed ban reply" to "silent drop", which contradicts spec §User ban.
3. If the alphabetically-last adapter is mis-trusted (Signal vs SimpleX threat profiles differ), outbound state leaks across the trust boundary — a Signal compromise sees replies for SimpleX users that should never have traversed Signal.

The class-level Javadoc acknowledges this as "an acceptable MVP limitation because no MVP user-facing flow runs more than one adapter simultaneously," but M1 explicitly commits to SimpleX + Signal simultaneously (D46, the M1-109 production-shape IT in the recent commit log). `DigestWorker.findAdapter` and `ApproveGroupCommandHandler.findAdapter` already demonstrate the correct shape — look up the target adapter by name from `AdapterRegistry.activatedAdapters()`.

**Recommended fix:**

Replace the single reply target with a per-name map (or a router method that takes the source adapter) and pass the source adapter name from the inbound lambda all the way to `sendReply`.

```java
// AdapterRegistry — drop setReplyTarget; the InboundRouter already
// has access to activatedAdapters() via the registry. Keep the lambda
// shape so the source adapter name is captured.
for (MessagingAdapter adapter : activating) {
    String adapterName = adapter.name();
    adapter.setInboundHandler(msg -> inboundRouter.onMessage(msg, adapterName));
    adapter.setMembershipEventHandler(event -> membershipEventHandler.handle(event, adapterName));
    log.info("activating adapter: {} (trust={}{})", adapterName, ...);
    activatedAdapters.add(adapter);
}

// InboundRouter — drop the volatile field. Resolve the reply adapter
// by name on every send; threadlocal carrying adapterName already
// reaches sendReply via the existing call chain (it's the parameter
// to onMessage).
private void sendReply(String adapterName, ScopeRef scope, String body) {
    MessagingAdapter target = findAdapter(adapterName);
    if (target == null) {
        log.error("InboundRouter: no activated adapter named '{}' for scope={}",
                adapterName, ContactIds.redact(scopeIdOf(scope)));
        return;
    }
    try {
        target.send(new OutboundMessage(scope, body, Instant.now(),
                UUID.randomUUID().toString()));
    } catch (MessagingException e) {
        SafeLog.error(log, "reply send failed adapter=" + adapterName
                + " scope=" + ContactIds.redact(scopeIdOf(scope)), e);
    }
}

private MessagingAdapter findAdapter(String adapterName) {
    for (MessagingAdapter a : adapterRegistry.activatedAdapters()) {
        if (a.name().equals(adapterName)) return a;
    }
    return null;
}
```

Then update every `sendReply(scope, body)` call in `onMessage` to `sendReply(adapterName, scope, body)`. The `adapterName` variable is already in scope at every call site — it was the second parameter to `onMessage`.

**Reasoning:**

The fix mirrors the pattern that `DigestWorker.findAdapter` and `ApproveGroupCommandHandler.findAdapter` already use, so it does not introduce a new shape — it removes a divergent shape. Replies travel through the same adapter the inbound arrived on, which is the invariant the rest of the system already assumes. The `volatile` write race that the current code creates (every adapter activation overwrites the field) goes away. The fix is one helper method plus threading the adapter name through `sendReply` — no SPI change, no schema change.

**Trade-offs:**

A per-reply O(N) scan of `activatedAdapters()` where N is small (1 or 2 in v1). Inlining a `Map<String, MessagingAdapter>` is the next step if M1 grows past two adapters; the linear scan is fine for v1 and matches what `DigestWorker` does.

---

### F2. Hardcoded `/zcash`, `/monero` command handlers break `bootstrap-assets.json` extensibility

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** critical
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetCommandRouter.java:24-55

**Current code:**

```java
public final class AssetCommandRouter {
    @ApplicationScoped
    public static class ZcashCommandHandler implements CommandHandler {
        @Inject AssetHandler assetHandler;
        @Override public String name() { return "zcash"; }
        @Override public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
            return assetHandler.handle("zcash", scope, rawText);
        }
    }
    @ApplicationScoped
    public static class MoneroCommandHandler implements CommandHandler {
        @Inject AssetHandler assetHandler;
        @Override public String name() { return "monero"; }
        @Override public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
            return assetHandler.handle("monero", scope, rawText);
        }
    }
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/commands.md` §Asset commands commits explicitly to a configuration-driven asset family:

> "the per-asset sub-verb shape is the spec-level commitment so future asset-specific verbs ... can land without a new top-level command per verb"

> "Asset commands are enabled only when `bootstrap-assets.json` is configured and contains the asset"

The current code hardcodes exactly two top-level commands as named CDI beans. `AssetRegistry` and `AssetCommandFamilyOracle` are dynamically populated from `asset_config` + `bootstrap-assets.json`, but `InboundRouter.handleSlash` (line 651-659) walks `Instance<CommandHandler>` and matches by `name()`. A third asset added to `bootstrap-assets.json` — say, `litecoin` — produces no `CommandHandler`, so `/litecoin` falls through to `UNKNOWN_COMMAND_REPLY`. The operator's data is honoured by `/help` (which iterates `assetRegistry.getEnabledAssets()`) but not by the dispatcher.

Worse, the probation gate's `AssetCommandFamilyOracle.isAssetCommand("litecoin")` would return `true` (the registry contains it), so a probation user typing `/litecoin` would pass the probation check… and then receive "Unknown command", which contradicts spec §Slow-start tier's promise that "the asset-command family" is the probation-allowed catalogue.

This is the kind of bug that an early redteam pass would flag as "spec says X, code does Y, the gap is silent until production." It's not a security finding by itself but it permanently constrains the asset-extensibility commitment the spec makes.

**Recommended fix:**

Replace the two hardcoded handlers with a single dynamic dispatch in the InboundRouter, or with a per-asset `CommandHandler` bean produced from `AssetRegistry` at startup.

Option A (minimal change — recommended): add a fallback branch in `InboundRouter.handleSlash` that consults the asset registry before returning `UNKNOWN_COMMAND_REPLY`.

```java
private String handleSlash(ScopeRef scope, String normalized) {
    String firstToken = normalized.split("\\s+", 2)[0];
    String commandName = firstToken.substring(1);
    for (CommandHandler handler : commandHandlers) {
        if (handler.name().equals(commandName)) {
            return handler.handle(scope, normalized).text();
        }
    }
    // Asset family — dynamically resolved from operator config.
    if (assetCommandFamilyOracle.isAssetCommand(commandName)) {
        return assetHandler.handle(commandName, scope, normalized).text();
    }
    return UNKNOWN_COMMAND_REPLY;
}
```

Then delete `AssetCommandRouter` entirely; nothing else references the two inner classes.

Option B: produce one `CommandHandler` bean per enabled asset at `@Startup` via a CDI producer that reads from `AssetRegistry`. More code, but keeps the dispatcher single-shape.

**Reasoning:**

Option A is one branch in the router and removes a whole file. The asset oracle is already the source of truth for "is this slash an asset command?" so adding the dispatch right next to the lookup is the symmetric shape. The probation gate's "asset family" promise is satisfied. `bootstrap-assets.json` becomes the actual single source of truth for which `/<asset>` commands exist.

**Trade-offs:**

The dispatcher gains one branch. The asset handler's `assetName` parameter (already untrusted from the parsed slash) is also the dispatch key, which has no security impact — `assetHandler.handle` already returns "not configured" for unknown asset names, so the path is the same as today's error branch.

**Alternative options:**

- **Option A** (the recommended fix above)
- **Option B** — CDI producer that emits one `CommandHandler` bean per `AssetRegistry.getEnabledAssetNames()` entry — pros: keeps dispatcher uniform; cons: harder to wire to a runtime-mutable registry (`AssetRegistry.refresh()` already exists), and the bean set is frozen at startup so a runtime asset add would still miss the dispatcher.

---

### F3. `/stop` is a no-op in group scope and uses the wrong scope key in DM

- **Category:** SECURITY
- **Severity:** high
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java:62-95, 97-115

**Current code:**

```java
@Override
public @NonNull OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
    Optional<UUID> userId = resolveUserId(scope);
    if (userId.isEmpty()) {
        return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_NOOP));
    }

    // v1 DM scope: scopeKind = "dm", scopeId = userId
    String scopeKind = "dm";
    UUID scopeId = userId.get();

    boolean cancelledInFlight = cancellationService.cancel(userId.get(), scopeKind, scopeId);
    ...
}

private Optional<UUID> resolveUserId(ScopeRef scope) {
    if (!(scope instanceof ScopeRef.Dm dm)) {
        return Optional.empty();          // group scope → noop, never cancels
    }
    ...
}
```

**Why this is wrong / suboptimal / risky:**

Two distinct bugs in one handler:

1. **Group `/stop` is never cancellation.** `resolveUserId` returns empty for any non-DM scope, so a group user with in-flight chat-mode work cannot cancel it. `docs/spec/commands.md` §Chat mode commits to "Chat-mode replies and user-issued `/summary` runs can be interrupted by `/stop` (decision D35). Cancellation observes the same per-(user, scope) isolation as every other state in the system: a `/stop` from one user never affects another user's in-flight request, even within the same group." The spec assumes group-scope cancellation works.

2. **DM `/stop` uses the wrong scope key.** The hardcoded `scopeKind = "dm"` and `scopeId = userId` would be correct for DM in v1 (DM scope key is the user's id per `ChatAgent.handle`'s `actorId` → `scopeId` resolution at `InboundRouter` line 538), but it's load-bearingly hardcoded in the handler. If a future change unifies the DM scope key (e.g., moves to a separate `dm_scope.id`), the cancellation key drifts silently. The `InFlightTracker.tryAcquire(userId, "dm", actorId)` call site in `ChatAgent.handle` is the authoritative key shape and `/stop` reproduces it by accident.

The combined effect: a group user who triggers an expensive chat-mode reply (or the spec-promised `/summary` in-flight cancellation) gets `REPLY_STOP_NOOP` regardless of what's running, while their LLM tokens continue to burn. This silently violates the per-(user, scope) cancellation contract and removes the cost-shedding lever the spec relies on.

**Recommended fix:**

Mirror the scope-key resolution that `InboundRouter` already does (`resolveChatScopeId`) and route the cancellation key through that:

```java
@Inject InboundContext inboundContext;
@Inject DataSource dataSource;
@Inject InFlightTracker inFlightTracker;
@Inject CancellationService cancellationService;
@Inject ConfirmStateService confirmStateService;

@Override
public @NonNull OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
    String adapter = inboundContext.adapterName();
    String contactId = inboundContext.senderContactId();
    Optional<UUID> userIdOpt = lookupUserId(adapter, contactId);
    if (userIdOpt.isEmpty()) {
        return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_NOOP));
    }
    UUID userId = userIdOpt.get();

    String scopeKind = scope instanceof ScopeRef.Dm ? "dm" : "group";
    UUID scopeId = switch (scope) {
        case ScopeRef.Dm ignored -> userId;
        case ScopeRef.Group g -> lookupGroupId(adapter, g.adapterGroupId())
                .orElseThrow(() -> new IllegalStateException("group missing"));
    };

    boolean cancelledInFlight = cancellationService.cancel(userId, scopeKind, scopeId);
    Optional<ConfirmStateService.PendingConfirm> cancelledConfirm =
            confirmStateService.takeAny(userId, scope);
    // ... existing reply selection
}
```

The `lookupGroupId` helper already exists on `InboundRouter`; extract it to a shared utility (or read it through `GroupRepository`).

**Reasoning:**

Cancellation must match the same `ScopeKey` (userId, scopeKind, scopeId) that `ChatAgent.handle` registered. Reproducing the resolution in one place (a small helper) is the natural fix; the InboundRouter has the same code today. Probation `/stop` is exempt per spec, but probation gate runs before dispatch so it's not the handler's concern.

**Trade-offs:**

Adds one DB lookup (`lookupGroupId`) per group `/stop`. A `/stop` is a low-frequency event; the cost is negligible. The DM path retains the single lookup it already does.

---

### F4. `/help` ignores the spec-promised per-tier filtering

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java:46-74

**Current code:**

```java
@Override
public OutboundMessage handle(ScopeRef scope, String rawText) {
    StringBuilder body = new StringBuilder();
    body.append(bundleLoader.get(BundleKeys.HELP_HEADER_DM_USER));
    body.append('\n');
    body.append(bundleLoader.get(BundleKeys.HELP_CMD_HELP_SHORT));
    body.append('\n');
    body.append(bundleLoader.get(BundleKeys.HELP_CMD_ADD_SOURCE_SHORT));
    body.append('\n');
    body.append(bundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_SHORT));

    List<AssetRegistry.AssetEntry> enabledAssets = assetRegistry != null
            ? assetRegistry.getEnabledAssets() : List.of();
    for (AssetRegistry.AssetEntry asset : enabledAssets) {
        ...
    }
    ...
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/commands.md` §Discovery commits, at the spec level, to several properties of `/help`:

1. "context-aware list of commands available to the caller"
2. "a probation-tier caller (decision D45) sees **only** the slow-start allowed subset, with a one-line note that fuller access ... unlocks when probation ends"
3. "a non-admin caller does not see admin commands; a group member who is not group admin does not see group-admin-only commands"
4. "**Bundle composition.** `/help` output is **composed from per-command bundle entries** (one localization-bundle key per command, holding that command's short-help line) ... `/help` concatenates the header, then the per-command lines for the caller's permitted set in a fixed order, then the footer."

The current implementation:
- Hardcodes three commands (`help`, `add-source`, `summary`) and the asset list. None of the other catalogued commands (`/status`, `/get-tags`, `/saved`, `/forget`, `/lang`, `/clear`, `/compress`, `/follow-tag`, etc.) appear.
- Sends `HELP_HEADER_DM_USER` even in group scope (no group-aware variant).
- Does no permission check at all — a probation user sees `/add-source` (which is in the probation-blocked set) and an admin sees no admin commands.
- Has no footer key, no probation-tier note.

Result: every caller, regardless of tier or scope, gets the same three-line static help output. The bundle-completeness CI rule the spec promises ("CI's bundle-completeness check asserts that every command in the catalogue has a help-line key in every shipped language bundle") cannot fire against a static list that omits most of the catalogue.

This is a load-bearing UX promise that is silently absent. Either the rest of the command catalogue's help lines are not in the bundle (in which case the spec is unimplementable as written), or they are in the bundle but the handler ignores them (in which case the handler must be rewritten).

**Recommended fix:**

Drive `/help` from a closed list of `(commandName, bundleKey, permissionTier)` entries — exactly the data the spec's catalogue describes — and filter by the caller's tier inside the handler:

```java
private static final List<HelpEntry> CATALOGUE = List.of(
    new HelpEntry("help",           BundleKeys.HELP_CMD_HELP_SHORT,           Tier.ANY),
    new HelpEntry("status",         BundleKeys.HELP_CMD_STATUS_SHORT,         Tier.ANY),
    new HelpEntry("get-tags",       BundleKeys.HELP_CMD_GET_TAGS_SHORT,       Tier.ANY),
    new HelpEntry("get-sources",    BundleKeys.HELP_CMD_GET_SOURCES_SHORT,    Tier.ANY),
    new HelpEntry("list-sources",   BundleKeys.HELP_CMD_LIST_SOURCES_SHORT,   Tier.ANY),
    new HelpEntry("summary",        BundleKeys.HELP_CMD_SUMMARY_SHORT,        Tier.ANY),
    new HelpEntry("saved",          BundleKeys.HELP_CMD_SAVED_SHORT,          Tier.ANY),
    new HelpEntry("save",           BundleKeys.HELP_CMD_SAVE_SHORT,           Tier.POST_PROBATION),
    new HelpEntry("unsave",         BundleKeys.HELP_CMD_UNSAVE_SHORT,         Tier.POST_PROBATION),
    new HelpEntry("add-source",     BundleKeys.HELP_CMD_ADD_SOURCE_SHORT,     Tier.POST_PROBATION_OR_GROUP_ADMIN),
    ...
    new HelpEntry("ban",            BundleKeys.HELP_CMD_BAN_SHORT,            Tier.BOT_ADMIN),
    ...
);

@Override
public OutboundMessage handle(ScopeRef scope, String rawText) {
    ActorTier tier = resolveCallerTier(scope);   // bot-admin? group-admin? probation? plain?
    StringBuilder body = new StringBuilder();
    body.append(bundleLoader.get(headerKey(scope, tier)));
    for (HelpEntry entry : CATALOGUE) {
        if (entry.permitted(tier, scope)) {
            body.append('\n').append(bundleLoader.get(entry.bundleKey));
        }
    }
    appendAssetCommands(body, tier);
    if (tier == ActorTier.PROBATION) {
        body.append('\n').append(bundleLoader.get(BundleKeys.HELP_FOOTER_PROBATION));
    }
    return new OutboundMessage(scope, body.toString(), Instant.now(), UUID.randomUUID().toString());
}
```

The matching bundle keys land in `en.properties` and `cs.properties` — the bundle-completeness CI check the spec describes then becomes meaningful.

**Reasoning:**

The spec is explicit about both the data shape (per-command bundle keys) and the filtering rules. The current handler is a placeholder; treating it as such ("MVP, expand later") loses the bundle-completeness CI rule the spec leans on for adding a third language. Driving from a list keeps the code uniform and lets the test that walks the catalogue assert "for every command in the closed catalogue, its help-line key resolves in every shipped bundle."

**Trade-offs:**

The handler gets longer. The CATALOGUE constant duplicates the command-name set already implicit in `Instance<CommandHandler>`; a sufficiently-clever helper could derive it. The closed-list form is what the spec asks for — derive vs hand-maintain is a maintainability call but either way the per-tier filtering is required.

---

### F5. Connection-per-step churn in the inbound path

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:300-553

**Current code:**

```java
@ActivateRequestContext
public void onMessage(@NonNull InboundMessage msg, @NonNull String adapterName) {
    ...
    Optional<UserSnapshot> snapshot = lookupUser(adapterName, contactId);   // conn #1
    ...
    if (banCheck.isBanned(adapterName, contactId)) {                         // conn #2
        sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_BAN_FIXED));
        return;
    }
    ...
    if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()
            && groupAutoPromoteService != null) {
        UUID groupId = lookupGroupId(adapterName, group.adapterGroupId());    // conn #3
        UUID senderId = snapshot.get().id();
        groupAutoPromoteService.tryAutoPromote(groupId, senderId, ...);       // conn #4 + #5 (eligibility + insert)
        ensureGroupMembership(groupId, senderId);                             // conn #6
    }
    ...
    if (probationCheck.inProbation(probationActorId)) {                      // conn #7
        ...
    }
    if (!"retry".equals(commandName)) {
        UUID anchorScopeId = resolveChatScopeId(msg.scope(), anchorActorId, adapterName);  // conn #8 (group only)
        summaryAnchorRepository.clear(anchorActorId, anchorScopeId);          // conn #9
    }
    ...
    body = handleSlash(...);  // handler opens its own connections
}
```

`lookupUser`, `BanCheck.isBanned`, `lookupGroupId`, `ensureGroupMembership`, `GroupAutoPromoteService.tryAutoPromote` (one `getConnection` per call internally), `ProbationCheck.inProbation`, `summaryAnchorRepository.clear`, plus the handler each open their own JDBC connection.

**Why this is wrong / suboptimal / risky:**

Each `dataSource.getConnection()` round-trips to Agroal's pool; under steady-state cached pool conditions this is cheap, but the connection still has its session set up, autocommit defaulted, and the connection returned at scope close. A single inbound on an approved group path drains 6–10 connections from the pool sequentially. For an MVP single user that is invisible; for the spec-committed multi-adapter, multi-group, multi-user shape with the per-group rate cap of 10/15min and burst floods that the rate cap is designed to absorb, the connection pool becomes the bottleneck long before the LLM does.

More importantly, every step except `BanCheck.isBanned` re-reads data the previous step already read. The `users` SELECT at `lookupUser` returns `id`, `is_banned`, and `registration_state` — the second `BanCheck.isBanned` re-SELECTs the same row to read `is_banned` again on a fresh connection. The class-level Javadoc claims "exactly one users-row SELECT per inbound" — but step 4 immediately violates that claim with a second SELECT on a new connection.

The class Javadoc says step 4 reads `BanCheck.isBanned` directly "per spec (a separate query that sees the freshest `is_banned` state for a banned-mid-dispatch race)." The TOCTOU concern is real but the fix is to read `is_banned` inside the same connection as `lookupUser` and then re-check at the COMMIT boundary of any mutating step — not to spend a fresh connection per check.

**Recommended fix:**

Open one `Connection` at the top of `onMessage`, thread it through every helper as a parameter, and commit/close once at the bottom:

```java
@ActivateRequestContext
public void onMessage(@NonNull InboundMessage msg, @NonNull String adapterName) {
    inboundContext.setAdapterName(adapterName);
    String raw = msg.text();
    String contactId = msg.sender().contactId();
    inboundContext.setSenderContactId(contactId);

    if (!rateCapBucket.tryAcquire(adapterName, contactId)) return;
    if (raw != null && exceedsUtf8ByteLength(raw, maxInboundBodyBytes)) {
        sendReply(adapterName, msg.scope(), MESSAGE_TOO_LARGE_REPLY);
        return;
    }
    String normalized = normalize(raw);
    if (normalized.isEmpty()) return;

    try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(true); // read-only path; per-step transactions inside handlers stay isolated
        UserSnapshot snapshot = lookupUserOn(conn, adapterName, contactId).orElse(null);
        // ... use snapshot for steps 2, 3, 4, 3.5, 5
        // The handler dispatch path still opens its own connection for the
        // mutating transaction, but the read-only intake costs one
        // connection total.
    } catch (SQLException e) {
        // single fail-closed branch for connection failures
        throw new IllegalStateException("InboundRouter intake DB failure", e);
    }
}
```

Alternatively, if threading the connection through every helper is invasive, switch the helpers to a "connection passed in" overload and keep the existing single-shot helpers for tests.

**Reasoning:**

The intake-path reads are deterministic and all hit the same two tables (`users`, `groups`). One connection covers them. The mutating handler still opens its own transactional connection — that's correct, audit-before-effect requires it. The TOCTOU race the Javadoc invokes is bounded by the per-(user, scope) in-flight gate anyway: a banned-mid-dispatch race must arrive in <1 round-trip of the inbound to matter, and the handler's own `FOR UPDATE` reads on the actor row close that window.

**Trade-offs:**

The change touches every read helper signature. The current shape is easy to test in isolation; threading a connection forces tests to either pass a real connection or mock the helper. Both shapes are present in the codebase (see `BanCommandHandler` which passes Connection vs `BanCheck` which opens its own).

---

### F6. `/promote` reads the actor row without `FOR UPDATE`, leaving a TOCTOU window with `/revoke-admin`

- **Category:** SECURITY
- **Severity:** medium
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/command/PromoteCommandHandler.java:90-93, 158-169

**Current code:**

```java
private static final String SELECT_ACTOR_SQL =
        "SELECT id, is_admin FROM users "
                + "WHERE adapter = ? AND contact_id = ?";

private UUID resolveAdmin(Connection conn, String adapter,
                          String contactId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_SQL)) {
        ps.setString(1, adapter);
        ps.setString(2, contactId);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            if (!rs.getBoolean("is_admin")) return null;
            return (UUID) rs.getObject("id");
        }
    }
}
```

**Why this is wrong / suboptimal / risky:**

`GrantAdminCommandHandler`, `ApproveGroupCommandHandler`, `RejectGroupCommandHandler`, and `RevokeAdminCommandHandler` all switched their actor-gate SELECT to `FOR UPDATE` (cited as the "M1-046 redteam PERM-ESCAL closure" in the GrantAdmin Javadoc). `PromoteCommandHandler` did not. The race is the same: a concurrent `/revoke-admin` against the caller can commit between this SELECT and the demote+promote UPDATEs, letting an actor whose admin bit has just been revoked still complete the swap.

Concrete attack:

1. Admin A (compromised) issues `/promote attacker`.
2. The handler reads `is_admin=TRUE` for admin A.
3. Admin B issues `/revoke-admin A` on a separate transaction; it commits.
4. The handler's `demoteExisting` + `promoteTarget` proceed under A's stale "admin" claim — attacker is now group admin.

The window is small but it is the exact scenario the GrantAdmin Javadoc names. PromoteCommandHandler is the one privileged-tier write the prior redteam pass missed.

**Recommended fix:**

Switch `SELECT_ACTOR_SQL` to use `FOR UPDATE` and move the call inside the existing transaction (it is already inside, but the SELECT is non-locking today):

```java
private static final String SELECT_ACTOR_FOR_UPDATE_SQL =
        "SELECT id, is_admin FROM users "
                + "WHERE adapter = ? AND contact_id = ? FOR UPDATE";

private UUID resolveAdmin(Connection conn, String adapter,
                          String contactId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_FOR_UPDATE_SQL)) {
        ps.setString(1, adapter);
        ps.setString(2, contactId);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            if (!rs.getBoolean("is_admin")) return null;
            return (UUID) rs.getObject("id");
        }
    }
}
```

The transaction wrapper at lines 86-150 already opens `autoCommit=false` so the row lock is held until commit.

**Reasoning:**

Identical pattern to `GrantAdminCommandHandler.SELECT_ACTOR_FOR_UPDATE_SQL`. The lock blocks a concurrent `/revoke-admin` UPDATE on the same row; the handler's `is_admin` read then reflects the post-revoke state and the demote+promote UPDATEs short-circuit with the standard non-admin response.

**Trade-offs:**

A `FOR UPDATE` row lock held for the duration of the `/promote` transaction. Two concurrent `/promote` calls against the same caller serialize on the lock; this is acceptable and matches the other admin handlers' behaviour.

---

### F7. `MembershipEventHandler` writes audit rows AFTER state mutation and swallows failures

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/group/MembershipEventHandler.java:69-103, 105-127

**Current code:**

```java
private void handleUserLeft(MembershipEvent.UserLeft event, String adapter) {
    UUID groupId = resolveGroup(adapter, event.adapterGroupId());
    if (groupId == null) { ...; return; }
    UUID userId = resolveUser(adapter, event.contactId());
    if (userId == null) { ...; return; }
    boolean wasGroupAdmin = membershipRepository.isGroupAdmin(groupId, userId);
    membershipRepository.markMemberRemoved(groupId, userId);          // mutate
    writeAudit(AuditAction.MEMBER_LEFT, userId, event.contactId(), adapter,
            "user", userId.toString(), groupId,
            "{\"was_group_admin\":" + wasGroupAdmin + "}");            // then audit
    log.info("UserLeft: marked member removed group={} user={}", groupId, userId);
}

private void writeAudit(...) {
    ...
    try (Connection conn = dataSource.getConnection()) {
        auditLogWriter.write(conn, row);
    } catch (SQLException e) {
        // Audit failure must not block the membership state mutation
        // that already succeeded — log and continue.
        SafeLog.error(log, "failed to write audit row action=" + action + " scope=" + scopeId, e);
    }
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §Authorization model step 8 commits unconditionally to "Audit-log the intent" before execution (step 9). Every other handler in this module that touches authorization state pre-writes the audit row inside the same transaction as the mutation — `BanCommandHandler`, `UnbanCommandHandler`, `GrantAdminCommandHandler`, `PromoteCommandHandler`, `ApproveGroupCommandHandler`, etc. all open `autoCommit=false`, write audit, then mutate, then commit.

`MembershipEventHandler` does the opposite:
1. Mutate state (`markMemberRemoved` / `groupRepository.markRemoved`).
2. Open a fresh connection.
3. Try to write audit. If audit insert fails, log and continue.

The comment ("Audit failure must not block the membership state mutation that already succeeded") concedes Invariant 7 is being violated. The argument is "the membership change came from the adapter and we can't roll it back" — but the adapter event is an inbound notification; the DB mutation is entirely under our control and could be done in the same transaction as the audit.

Concrete consequence: a transient audit-write failure during a leave-storm (e.g., schema migration, role-grant misconfig, audit_log full) leaves a `MEMBER_LEFT` mutation with no trace. The "was_group_admin" flag is critical for the `/unban` group-admin restoration disclosure path; losing it produces a real downstream bug. The same applies to `BotRemoved` — the group is marked removed and the audit-log claim that "every privileged action against user state is audit-logged before effect" silently breaks.

**Recommended fix:**

Wrap the mutation + audit in one transaction, mirroring the `BanCommandHandler` pattern:

```java
private void handleUserLeft(MembershipEvent.UserLeft event, String adapter) {
    UUID groupId = resolveGroup(adapter, event.adapterGroupId());
    if (groupId == null) { ...; return; }
    UUID userId = resolveUser(adapter, event.contactId());
    if (userId == null) { ...; return; }

    try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        try {
            boolean wasGroupAdmin = membershipRepository.isGroupAdmin(conn, groupId, userId);
            // Pre-write audit, then mutate, then commit — Invariant 7.
            auditLogWriter.write(conn, buildAuditRow(
                    AuditAction.MEMBER_LEFT, userId, event.contactId(), adapter,
                    "user", userId.toString(), groupId,
                    "{\"was_group_admin\":" + wasGroupAdmin + "}"));
            membershipRepository.markMemberRemoved(conn, groupId, userId);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            SafeLog.error(log, "MEMBER_LEFT processing failed for group=" + groupId, e);
            // No silent partial state: the rollback releases both
            // intended mutations together.
        }
    } catch (SQLException e) {
        SafeLog.error(log, "MEMBER_LEFT connection failure for group=" + groupId, e);
    }
}
```

`GroupMembershipRepository.isGroupAdmin` and `markMemberRemoved` need Connection-accepting overloads.

**Reasoning:**

Invariant 7 is the single biggest reason the audit log can be trusted. Per-handler exceptions to it dilute the operator's signal. The fix is to keep mutation + audit in one transaction so either both happen or neither does. A persistent DB failure already produces a `SafeLog.error`; an unbroken audit trail is the audit log's reason to exist.

**Trade-offs:**

A leave event whose audit row fails to write also fails to update `group_membership`. The membership row remains stale for one tick — but the adapter is the source of truth for membership, so the next adapter event (or a `/list-groups` from an admin) reconciles. Compared to losing audit rows silently, the trade is worth it.

---

### F8. Hand-rolled JSON arg parser in ChatAgent silently mangles non-trivial tool payloads

- **Category:** SECURITY
- **Severity:** medium
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java:251-305

**Current code:**

```java
static Map<String, Object> parseToolArgs(String json) {
    Map<String, Object> args = new HashMap<>();
    if (json == null || json.isBlank()) return args;
    String trimmed = json.trim();
    if (trimmed.equals("{}")) return args;
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
    }
    for (String pair : splitTopLevel(trimmed)) {
        String[] kv = pair.split(":", 2);
        if (kv.length != 2) continue;
        String key = kv[0].trim().replaceAll("^\"|\"$", "");
        String value = kv[1].trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            args.put(key, value.substring(1, value.length() - 1));
        } else {
            try {
                args.put(key, Integer.parseInt(value));
            } catch (NumberFormatException e) {
                args.put(key, value);
            }
        }
    }
    return args;
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §Prompt-injection defenses commits to a typed tool-input boundary:

> "Every argument is type-checked and bound to enums, validated ranges, or length caps before the underlying SQL runs."

> "Every output is a typed structured value, never a passthrough of free-form upstream text..."

`searchPosts`, `listSaves`, and `recallMemory` accept lists of strings. The hand-rolled parser:

- Does not handle JSON arrays (`"tags": ["foo", "bar"]`). `splitTopLevel` tracks `{` and `[` depth but the result-storage code never branches on `[`; arrays land as raw strings like `[foo, bar]` in the `args` map, then `SearchPostsTool` casts to `List<String>` and crashes with `ClassCastException`. That crash propagates as `INTERNAL_ERROR_REPLY` to the user instead of the typed validation error the spec promises.
- Strips outer quotes via `value.substring(1, length()-1)` without un-escaping `\\"`, `\\\\`, `\\n`. An LLM that emits a perfectly valid JSON-quoted string with an embedded `\\"` produces a key/value pair with a corrupt value.
- Splits on `,` at depth 0 — but the depth tracking includes `[` and `]` while the storage layer is unaware of them, so a list arg is one corrupt string.
- Recognizes a quoted string only if `value.startsWith("\"") && value.endsWith("\"")`. An LLM that quotes a string but appends a trailing space or stray character bypasses the string branch and the value is interpreted as `Integer.parseInt(value)` (NumberFormatException, then kept as raw string).

Consequence:

- `searchPosts` with a `tags` list never works correctly through the chat agent. The user-facing effect is "the chat agent says it searched but found nothing", silently. There is no validation error to debug from.
- `recallMemory` with `keywords` similarly broken.
- `listSaves` with `tags` similarly broken.

This silently undermines the chat-mode value proposition — the very tools the spec catalogues as the v1 chat surface.

Beyond functionality: a parser this fragile is exactly the surface a prompt-injection attack would weaponize. A crafted LLM output of the form `{"uid": "x\\", "tags": ["malicious"]}` produces undefined parsing behaviour. The tool-dispatcher's length caps protect against runaway sizes but not against type confusion.

**Recommended fix:**

Use Jackson (already a project dependency — see `AssetRegistry`'s `ObjectMapper`):

```java
private static final ObjectMapper TOOL_ARG_MAPPER = new ObjectMapper();

static Map<String, Object> parseToolArgs(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
        JsonNode node = TOOL_ARG_MAPPER.readTree(json);
        if (!node.isObject()) {
            return Map.of();  // dispatcher will return ValidationError on missing args
        }
        Map<String, Object> out = new HashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), toJavaValue(e.getValue())));
        return out;
    } catch (JsonProcessingException ex) {
        // Malformed JSON from the LLM → empty args → dispatcher reports
        // ValidationError, which the loop feeds back to the LLM.
        return Map.of();
    }
}

private static Object toJavaValue(JsonNode node) {
    if (node.isTextual()) return node.asText();
    if (node.isInt() || node.isLong()) return node.asLong();
    if (node.isDouble()) return node.asDouble();
    if (node.isBoolean()) return node.asBoolean();
    if (node.isArray()) {
        List<Object> list = new ArrayList<>(node.size());
        node.forEach(child -> list.add(toJavaValue(child)));
        return list;
    }
    return node.asText();
}
```

The `ChatToolDispatcher.validateInputLengths` already enforces the spec's per-argument cap; this fix produces an honest `List<String>` for it to validate rather than a corrupt `String` that crashes downstream.

**Reasoning:**

Jackson is already loaded for `bootstrap-assets.json` parsing. Using it for tool-call JSON keeps the typed-argument boundary the spec commits to. The fix removes ~50 lines of fragile string-mashing and produces the correct shape for every tool's signature.

**Trade-offs:**

A tiny Jackson allocation per tool call; chat throughput is gated by the LLM, not the JSON parser. The `Object` map shape stays the same so no downstream call sites change.

---

### F9. `LlmOutputSanitizer` compiles 26 patterns per call

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java:187-209

**Current code:**

```java
static ClosedListStripResult applyClosedListStripWithMatches(String input) {
    String current = input;
    List<String> matches = new ArrayList<>();
    for (String token : CLOSED_LIST) {
        Pattern p = Pattern.compile(Pattern.quote(token) + "(?=$|[^a-zA-Z0-9\\-])");
        Matcher m = p.matcher(current);
        ...
    }
    return new ClosedListStripResult(current, matches);
}
```

**Why this is wrong / suboptimal / risky:**

`CLOSED_LIST` has 26 entries. Every LLM-authored output (chat reply, summary prose, digest, retry, translation) calls `sanitize` once. Each call re-compiles 26 `Pattern` objects. `Pattern.compile` is non-trivial work — regex compilation is in the hot path for an LLM-heavy workload.

The patterns never change (the closed list is `static final`), so the compiled patterns can be cached at class-init time. The same applies to `MARKDOWN_LINK` — already statically cached, but the closed-list patterns are not.

Under the spec's load assumptions (chat-mode + digest + summary + retry, with the per-group caps absorbing bursts), every active user produces sanitizer calls in the tens-per-minute range; the wasted regex compilation easily dominates the sanitizer cost.

**Recommended fix:**

Compile once, into a `static final List<Pattern>`:

```java
private static final List<Pattern> CLOSED_LIST_PATTERNS = CLOSED_LIST.stream()
        .map(token -> Pattern.compile(Pattern.quote(token) + "(?=$|[^a-zA-Z0-9\\-])"))
        .toList();

static ClosedListStripResult applyClosedListStripWithMatches(String input) {
    String current = input;
    List<String> matches = new ArrayList<>();
    for (int i = 0; i < CLOSED_LIST.size(); i++) {
        String token = CLOSED_LIST.get(i);
        Pattern p = CLOSED_LIST_PATTERNS.get(i);
        Matcher m = p.matcher(current);
        ...
    }
    return new ClosedListStripResult(current, matches);
}
```

**Reasoning:**

The patterns are immutable; caching them is the standard idiom. The fix removes the per-call compile cost. The per-occurrence audit + WARN-log behaviour is unchanged.

**Trade-offs:**

Adds a `static final List<Pattern>` field. Holds 26 compiled patterns for the JVM lifetime; the memory cost is trivial.

---

### F10. `/save` accepts unbounded personal-tag strings and counts

- **Category:** SECURITY
- **Severity:** medium
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java:305-321, 265-284

**Current code:**

```java
static ParsedArgs parseArgs(String rawText) {
    String[] tokens = rawText.trim().split("\\s+");
    ...
    String uid = tokens[1];
    List<String> personalTags = List.of();
    for (int i = 2; i < tokens.length; i++) {
        if ("-t".equals(tokens[i]) && i + 1 < tokens.length) {
            personalTags = parseTagList(tokens[i + 1]);
            break;
        }
    }
    return new ParsedArgs(uid, personalTags);
}

private static List<String> parseTagList(String csv) {
    List<String> out = new ArrayList<>();
    for (String raw : csv.split(",")) {
        String trimmed = raw.trim();
        if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return out;
}
```

The downstream `insertSavedPost` writes `personal_tags` as a `TEXT[]` array with no length or count cap.

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §Prompt-injection defenses caps tool inputs at "a profile-driven length cap"; `listSaves` is explicitly free-form personal tags ("free-form, but length-capped"). The READ side has a length cap (via `ChatToolDispatcher.validateInputLengths`). The WRITE side via `/save -t …` has no cap.

A misbehaving user can run `/save uid-X -t aaa,bbb,ccc,...,zzz` with hundreds of comma-separated tokens or a single token of megabyte length (bounded only by `infochat.router.max-inbound-body-bytes` at 65 KB default). The row lands in `saved_post.personal_tags` as a TEXT[] with arbitrary cardinality and arbitrary per-element length.

Concrete downstream consequences:

1. `/saved` lists the row with `personal_tags` interpolated into the reply via `String.join(", ", joined)` (SavedCommandHandler line 246-255). A megabyte tag is appended verbatim to the reply body; the outbound message bypasses the chat-mode body cap (which only applies to inbound). The adapter receives a many-MB message and either crashes or truncates silently.
2. `listSaves` tool reads it and serializes it into the LLM prompt as part of `String[]`, blowing the prompt budget.
3. `saved_post` rows with multi-MB `personal_tags` arrays consume the user's `save_count` cap slot but waste 100× the storage budget.

The spec's tag normalization rule (lowercase, `[a-z0-9][a-z0-9-]{0,47}`) applies to controlled-vocabulary tags but not to personal tags ("Personal tags are free-form and never join the controlled vocabulary"). "Free-form" should not mean "unbounded" — the spec explicitly says listSaves' read side is length-capped. The write side is the symmetric obligation.

**Recommended fix:**

Apply a profile-driven per-tag length cap and a per-call count cap at the parser:

```java
@ConfigProperty(name = "infochat.save.personal-tag-max-length", defaultValue = "64")
int personalTagMaxLength;

@ConfigProperty(name = "infochat.save.personal-tag-max-count", defaultValue = "20")
int personalTagMaxCount;

// In handle():
ParsedArgs args = parseArgs(rawText);
if (args.uid == null) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID));
}
if (args.personalTags.size() > personalTagMaxCount) {
    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_TOO_MANY_TAGS));
}
for (String tag : args.personalTags) {
    if (tag.length() > personalTagMaxLength) {
        return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_TAG_TOO_LONG));
    }
}
```

**Reasoning:**

Mirrors the same cap pattern the chat-tool dispatcher already enforces on reads. Bounds the `saved_post.personal_tags` blast radius. The cap values are profile-driven so the operator can widen them per deployment.

**Trade-offs:**

Adds two config properties and two friendly-error bundle keys. A user typing more than 20 personal tags receives a friendly error rather than silent acceptance — strictly better behaviour.

---

### F11. `SearchPostsTool.Duration.parse` throws past the tool dispatcher's exception filter

- **Category:** SECURITY
- **Severity:** medium
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java:46-48; ChatToolDispatcher.java:137-145

**Current code:**

```java
// SearchPostsTool.execute
Duration window = args.containsKey("window")
        ? Duration.parse((String) args.get("window")) : WINDOW_MAX;

// ChatToolDispatcher.dispatch
try {
    String result = tool.execute(userId, scopeKind, scopeId, validatedArgs);
    turn.cache.put(cacheKey, result);
    return new ToolResult.Success(result);
} catch (IllegalArgumentException e) {
    return new ToolResult.ValidationError(e.getMessage());
} catch (SQLException e) {
    throw new IllegalStateException("Tool execution failed: " + toolName, e);
}
```

**Why this is wrong / suboptimal / risky:**

`Duration.parse` throws `DateTimeParseException`, which extends `RuntimeException` but not `IllegalArgumentException`. The dispatcher's catch block intercepts only `IllegalArgumentException`. An LLM-emitted `"window": "7 days"` (intuitively-typed instead of ISO-8601) raises `DateTimeParseException` past the dispatcher and crashes the chat-agent loop.

The `ChatAgent.handle`'s outer `try { ... } catch (Exception e)` swallows it (line 122-133), returns `ERROR_CHAT_UNAVAILABLE`, and the conversation state is corrupted — no `chat_session` advance, no user-visible "your input was malformed."

`docs/spec/security.md` §Prompt-injection defenses commits to a typed validation surface ("a call exceeding the cap is rejected by the tool dispatcher before any SQL runs and the LLM sees a typed validation-error reply"). The DateTimeParseException path bypasses that contract.

The same shape applies to `(String) args.get("window")` if the LLM emits `"window": 7` (an integer): `ClassCastException` past the catch block, same crash path.

`SearchPostsTool` is the most heavily-used tool in the agent loop. Every chat turn that uses `searchPosts` with an unexpected `window` shape kills the reply.

**Recommended fix:**

Catch the parse and class-cast failures at the tool boundary and translate them into the typed validation error path:

```java
@Override
public @NonNull String execute(@NonNull UUID userId, @NonNull String scopeKind,
                                @NonNull UUID scopeId, @NonNull Map<String, Object> args)
        throws SQLException {
    List<String> tags;
    try {
        tags = args.containsKey("tags")
                ? (List<String>) args.get("tags") : List.of();
    } catch (ClassCastException e) {
        throw new IllegalArgumentException("'tags' must be a list of strings");
    }

    Duration window;
    if (args.containsKey("window")) {
        Object raw = args.get("window");
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException(
                    "'window' must be an ISO-8601 duration string (e.g. P7D)");
        }
        try {
            window = Duration.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'window' is not a valid ISO-8601 duration: " + s);
        }
    } else {
        window = WINDOW_MAX;
    }
    ...
}
```

The same `args.get` validation should land in every tool's `execute` — `RecallMemoryTool`, `ListSavesTool`, etc. The pattern is uniform enough that a `ToolArgs` helper (`requireString`, `requireDuration`, `requireList`) would replace the duplication.

**Reasoning:**

The tool spec promises typed input validation. The current shape leaks raw Java exceptions through the dispatcher, which produces the wrong user-visible path. The fix converts `DateTimeParseException` and `ClassCastException` to `IllegalArgumentException` — which the dispatcher already maps to `ToolResult.ValidationError` — so the LLM sees the typed validation reply the spec describes, and the chat-mode loop continues.

**Trade-offs:**

A few extra try/catch blocks per tool. Centralizing them in a small helper would deduplicate.

---

### F12. Duplicate per-handler JSON quoting helpers

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// BanCommandHandler.java:462-486
private static String quoteJsonString(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            ...
        }
    }
    sb.append('"');
    return sb.toString();
}

// GrantAdminCommandHandler.java:368-392 — identical body
// ApproveGroupCommandHandler.java:331-351 — slightly different (escapeJson, no surrounding quotes)
// GroupApprovalService.java:201-207 — escapeControlChars (subset, only \\, \n, \r, \t)
// UnbanCommandHandler — string-builder inline for details_json
// LlmOutputSanitizer.java:269-289 — jsonEscape (no surrounding quotes)
```

**Why this is wrong / suboptimal / risky:**

Five distinct hand-written JSON-quoting helpers exist across the module, each with slightly different escape sets. They drift independently. The audit-log writer already accepts a `details_json` string, so the implicit contract is "the handler hands us valid JSON." That contract is enforced by hand-written code in every handler that touches it. CLAUDE.md §Simplify aggressively names exactly this anti-pattern.

A future audit-log reader that depends on standard JSON-escape behaviour can be silently broken by a handler that forgets one of the control-character escapes (`escapeControlChars` in `GroupApprovalService` does not escape the JSON-quote-relevant `\\"`, for example).

**Recommended fix:**

Extract one helper to `infochat-core` (or `infochat-provider/.../audit`), e.g. `JsonText`:

```java
public final class JsonText {
    private JsonText() {}

    /** Returns the JSON-encoded form of {@code s}, including surrounding quotes. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
```

Every handler call site becomes `JsonText.quote(value)`. The five drifting variants collapse to one.

**Reasoning:**

Removes ~150 duplicated lines, prevents drift, fixes the `GroupApprovalService.escapeControlChars` divergence (which silently omits the quote escape). The shape is the canonical JSON escape table; no behavioural change for the unaffected call sites.

**Trade-offs:**

None — the fix is strictly better.

---

### F13. Asset-command tokens lowercased with the JVM-default locale

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java:156, 160

**Current code:**

```java
static ParsedArgs parseArgs(@NonNull String rawText) {
    String[] tokens = rawText.trim().split("\\s+");
    ...
    while (i < tokens.length) {
        if ("--vs".equals(tokens[i]) && i + 1 < tokens.length) {
            vsCurrency = tokens[i + 1].toLowerCase();        // default locale
            i += 2;
        } else if (!tokens[i].startsWith("--")) {
            if (subVerb == null) {
                subVerb = tokens[i].toLowerCase();            // default locale
            }
            i++;
        }
        ...
    }
    return new ParsedArgs(subVerb, vsCurrency);
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/commands.md` §Surface conventions, tag-normalization rule 3 explicitly pins case-folding to `Locale.ROOT` ("locale-independent so `İ`/`I` do not split between locales"). The same reasoning applies to sub-verb and `--vs` matching: an operator running the Provider in a Turkish locale would have `KRAKEN.toLowerCase()` produce `kraken` (no I/dotless-I drama for ASCII letters) but the principle is the same — the input is an ASCII identifier and the lowercase operation is identity-up-to-ASCII; using the default locale leaks operator-environment surface into a deterministic identifier comparison.

Concretely: a `Locale.TURKISH` deployment that ever receives a `--vs USDT` request from a misconfigured LLM or a typo'd manual invocation could in principle round-trip differently than the spec contemplates. The bug is not currently exploitable because the registered tokens are all ASCII; the issue is the standard `toLowerCase()`-without-`Locale.ROOT` foot-gun the spec already calls out for tags.

**Recommended fix:**

```java
vsCurrency = tokens[i + 1].toLowerCase(Locale.ROOT);
subVerb = tokens[i].toLowerCase(Locale.ROOT);
```

The `BanCommandHandler`, `AuditCommandHandler`, and others use `Locale.ROOT` for the same reason; this handler is the lone outlier.

**Reasoning:**

Spec-consistent, defense-in-depth against operator-locale drift, matches every other case-folding site in the module.

**Trade-offs:**

None — the fix is strictly better.

---

## Synthesizer-relevant observations (cross-module / architecture-lens)

- The single-replyTarget shape in `InboundRouter` (F1) plus the per-name lookup pattern in `DigestWorker.findAdapter` / `ApproveGroupCommandHandler.findAdapter` suggests the AdapterRegistry should expose a `MessagingAdapter byName(String)` method that every outbound site consumes. Worth raising at architecture-lens.
- `bootstrap-assets.json` extensibility (F2) needs an architectural decision on whether per-asset slash commands are dispatched by name match (Option A) or by a startup-generated bean set (Option B). The decision affects the CommandHandler SPI contract.
- `SET LOCAL infochat.actor_id = '<uuid>'` is string-concatenated into SQL across eight handlers. The concatenated value is provably a UUID so today it is safe, but the pattern is an architectural smell — a `SELECT set_config('infochat.actor_id', ?, true)` overload that accepts a JDBC bind parameter would remove the concatenation across the module.
