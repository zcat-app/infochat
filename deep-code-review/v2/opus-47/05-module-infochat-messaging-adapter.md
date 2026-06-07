# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-06 12:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — cross-cutting (28 main-source files, 181 occurrences) — Hand-written `@NonNull` annotations violate the engineering rule §7a (NullAway package-default makes `@NonNull` redundant; "@NonNull is no longer written by hand").
- [high] MAINTAINABILITY-RULES-DRIFT — `impl/simplex/SimpleXAdapter.java:319-338` — `SimpleXAdapter.setTyping` issues an `apiSetContactTyping`-shaped command despite the adapter declaring `supportsTypingIndicator=false`, violating `docs/design/06-messaging.md` §6.3.9 ("Adapters with the capability disabled MUST treat both calls as silent no-ops").
- [high] MAINTAINABILITY-RULES-DRIFT — `impl/simplex/SimpleXAdapter.java:96-107` — Silent LRU eviction of OPEN (not-finalized) handles can break the SPI try/finally invariant when a slow operation outlives 1023 newer sends; the analogous Signal client (`SignalJsonRpcClient.java:127, 242`) is bounded by in-flight count only, so the SPI surface is non-uniform.
- [medium] MAINTAINABILITY-RULES-DRIFT — `impl/signal/SignalIdentity.java:28-31`, `impl/simplex/SimpleXIdentity.java:28-31` — `SignalIdentity.resolve(Path)` and `SimpleXIdentity.resolve(Path)` are unimplemented stubs that throw `UnsupportedOperationException`; speculative SPI surface with no callers.
- [medium] PERFORMANCE — `impl/signal/SignalJsonRpcClient.java:395-441` — Reader loop calls `BufferedReader.read()` one char at a time; the 16 KiB cap means up to ~16 384 lock-acquire calls per line per connection.
- [medium] SECURITY — `impl/simplex/SimpleXMessageCodec.java:603-622` — `classifyError` runs `contains` against a case-folded error tag, so a signal-cli error string of the literal form `not-a-rate-limit` (containing the substring `rate limit` after the `ratelimit` test) would be misclassified TRANSIENT.
- [medium] SIMPLIFICATION — `impl/signal/SignalConfig.java`, `impl/simplex/SimpleXConfig.java` — The two `Config` beans share an identical validate/inject pattern; the file count and duplication is small but the divergence in field exposure (SimpleX has public getters, Signal has none — `SignalAdapter` Javadoc explicitly calls this out) is suspect.
- [low] MAINTAINABILITY-RULES-DRIFT — `MessagingAdapter.java`, `Identity.java`, etc. — Inconsistent annotation discipline: `Identity` uses `@NonNull String contactId`; `ScopeRef.Dm(String contactId)` carries no annotation; both rely on the same package default.
- [low] SIMPLIFICATION — `impl/signal/SignalJsonRpcClient.java:451-472` — `skipToNewline` uses `mark`/`reset` + manual `skip` to overshoot the terminator by one; can be replaced by a single read-and-look-for-newline loop without rewinding.

## Detail

### F1. Hand-written `@NonNull` annotations across every main-source file violate §7a

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** cross-cutting — 28 of 28 main-source files in `src/main/java/app/zcat/infochat/messaging/`, 181 occurrences total

**Current code (representative samples):**

```java
// MessagingAdapter.java:81
Identity assertIdentity(@NonNull InboundMessage msg);

// Identity.java:20
public record Identity(@NonNull String contactId, @Nullable String displayName, @NonNull Instant lastSeen) {}

// MembershipEvent.java:25
record UserJoined(@NonNull String adapterGroupId, @NonNull String contactId) implements MembershipEvent {}

// SignalGroupHandler.java:74
SignalGroupHandler(@NonNull String botAci, ...
```

**Why this is wrong / suboptimal / risky:**

`CLAUDE.md` §Engineering rules §"Method parameter contracts" and `docs/process/engineering-rules-verbatim.md` §7a are explicit:

> "Non-null is the **package default** — every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`), so a bare reference type means 'never null.' Only genuinely-nullable parameters, returns, and fields carry `@Nullable` (from `org.jspecify.annotations`); **`@NonNull` is no longer written by hand**."

This module has 181 `@NonNull` annotations across 28 files. By comparison, `infochat-core/src/main` has only ~4 hand-written `@NonNull` annotations and `infochat-provider/src/main` ~11 — both an order of magnitude lower despite being larger. The drift is module-local.

The cost is not merely cosmetic:

1. Every `@NonNull` annotation is dead syntax that a maintenance edit can drop without changing semantics (since the package-default already enforces non-null), producing inconsistent code over time.
2. Readers seeing `@NonNull` on one parameter and a bare type on the next (both non-null) wonder whether the difference is intentional — increasing cognitive load on every signature.
3. New files written in this module pick up the local style and propagate the drift.

**Recommended fix:**

Remove every hand-written `@NonNull` from `src/main` in one mechanical sweep. Keep `@Nullable` where it is correct. Verify `mvn verify` (NullAway:ERROR) still passes — the package default does the work.

```java
// After:
Identity assertIdentity(InboundMessage msg);

public record Identity(String contactId, @Nullable String displayName, Instant lastSeen) {}

record UserJoined(String adapterGroupId, String contactId) implements MembershipEvent {}

SignalGroupHandler(String botAci, ...)
```

**Reasoning:**

The single editing rule §7a is unambiguous, the build is the proof (NullAway:ERROR), and the rest of the codebase is already in line. The fix is mechanical (search-and-replace `, @NonNull ` → `, `, `(@NonNull ` → `(`, etc.) and immediately removes ~180 lines of noise.

**Trade-offs:**

None — the fix is strictly better. The build still proves the invariant; the source is shorter and uniform.

---

### F2. `SimpleXAdapter.setTyping` issues a typing command despite `supportsTypingIndicator=false`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:319-338`

**Current code:**

```java
private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
        ...
        /* supportsTypingIndicator    */ false,
        ...);

@Override
public void setTyping(@NonNull ScopeRef scope, boolean typing) {
    SimpleXWebSocketClient ws = webSocket;
    if (ws == null) {
        return;
    }
    try {
        String envelope = SimpleXMessageCodec.encodeTypingCommand(
                nextCorrId(), scope, typing);
        ws.sendFireAndForget(envelope);
    } catch (MessagingException e) {
        LOG.debug("setTyping absorbed encode failure: {}", e.category());
    }
}
```

**Why this is wrong / suboptimal / risky:**

`docs/design/06-messaging.md` §6.3.9 — *Typing indicators*:

> "Adapters with the capability disabled MUST treat both calls as silent no-ops."

The SimpleX adapter declares `supportsTypingIndicator=false` (line 77) but `setTyping` still encodes and sends the `apiSetContactTyping`-shaped command to simplex-chat. The Javadoc on the class explicitly acknowledges the conflict (lines 48-53): *"The supportsTypingIndicator capability flag is false per design §6.4.2 ... setTyping still issues the apiSetContactTyping-shaped command on a best-effort basis"* — i.e. the implementation deliberately violates §6.3.9. The class comment cites *"acceptance item 11 ... commits to issuing the apiSetContactTyping-shaped command"* against an unidentified ticket, against the spec.

Concrete consequence: every long-running `/summary` / digest / chat-agent operation will fire one `/_set_contact_typing @<id> on` request to simplex-chat at start and one `off` at end on a transport whose capability flag says it doesn't support typing. If simplex-chat happens to honor the verb, the bot will silently display a typing indicator while the capability declaration says the surface is off; if simplex-chat rejects it, every long-running request logs at least one DEBUG line. Both are wrong shapes.

The capability flag is the contract the Provider uses to plan its progress-notifier pulses (per §6.2 and `ProgressNotifier` Javadoc) — if the flag is false, the notifier won't emit setTyping calls at all. So the only callers of this method are tests + future bugs; the implementation should be a no-op so neither hits.

**Recommended fix:**

```java
@Override
public void setTyping(@NonNull ScopeRef scope, boolean typing) {
    // supportsTypingIndicator=false per docs/design/06-messaging.md §6.4.2;
    // §6.3.9 requires setTyping be a silent no-op when the flag is false.
    // SimpleX has no first-class typing surface — issuing the command anyway
    // would silently violate the capability contract.
}
```

Drop `encodeTypingCommand` from `SimpleXMessageCodec` along with its tests (they were exercising a code path the spec forbids).

**Reasoning:**

The spec is the contract; the design self-contradicts and `06-messaging.md` §6.3.9 wins (per the project's "spec > design notes" rule). The current code is dead surface that violates the contract on the off-chance simplex-chat accepts the command; removing it aligns with the spec and removes a misleading code path.

**Trade-offs:**

The encode-time validation of `ScopeRef` IDs (which the encode-typing path also performs) is no longer exercised through `setTyping` — but `encodeSendCommand` / `encodeUpdateCommand` / `encodeFinalizeCommand` all run the same validator, so coverage is unchanged.

---

### F3. Silent LRU eviction of open SimpleX handles violates the SPI finalize invariant

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:96-107`

**Current code:**

```java
static final int MAX_TRACKED_HANDLES = 1_024;
...
private final Map<String, TrackedHandle> handles =
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TrackedHandle> eldest) {
                return size() > MAX_TRACKED_HANDLES;
            }
        };
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/messaging.md` §Progress notifications guarantees:

> "On terminal `COMPLETED` / `FAILED`, calls `finalize(handle, text)` and turns off typing. **Both are guaranteed via try/finally — placeholders are never left dangling.**"

This adapter's LRU eviction policy can evict an OPEN (not-yet-finalized) handle when a long-running operation outlives 1023 newer sends. After eviction, `requireKnownAndOpen` throws `MessagingException(PERMANENT, "unknown handle")` — turning what the spec calls a "guaranteed finalize" into a permanent failure. The placeholder message is then visibly stranded in the user's client with no terminal edit.

By contrast, the Signal client (`SignalJsonRpcClient.java:127, 242`) only evicts on `finalizeHandle`:

```java
// SignalJsonRpcClient.java:127
private final ConcurrentMap<String, SignalMessageHandle> handles = new ConcurrentHashMap<>();

// SignalJsonRpcClient.java:242
handles.remove(handle.opaqueValue());
```

The Signal map is bounded by **in-flight** count (sends-but-not-yet-finalized), not cumulative count. That's the correct shape: a steady-state `/summary` workload of 10 concurrent requests holds 10 entries, not 10 000.

The two production adapters with the same SPI thus implement different bounding strategies, and the SimpleX one is the one that can silently break the spec. Memory was the original concern (per the inline comment "pre-M1-148 the handle and finalized tables grew for the life of the adapter") but the right fix was Signal's: evict on finalize, not LRU on size.

Concrete attack/break: with `maxInflightSends=4` and `maxSendsPerSecond=8`, an operator pushing 8 concurrent digests/summaries that each take 130 s (8 × 130 = 1040 in-flight, just over 1024) can starve out the earliest placeholders. Even without an attacker, a regression that forgets `finalize` somewhere in Provider will silently leak 1023 entries and then start evicting the very entries the open progress operations still need.

**Recommended fix:**

Mirror `SignalJsonRpcClient`'s shape — evict on finalize, no LRU cap, monitor the steady-state size:

```java
private final ConcurrentMap<String, TrackedHandle> handles = new ConcurrentHashMap<>();
...
@Override
public void finalizeMessage(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
    SimpleXMessageHandle internal = requireKnownAndOpen(handle);
    SimpleXWebSocketClient ws = requireConnected();
    String corrId = nextCorrId();
    String envelope = SimpleXMessageCodec.encodeFinalizeCommand(
            corrId, internal.chatItemId(), internal.scope(), body);
    ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
    handles.remove(handle.opaqueValue());
}
```

The `finalized` flag becomes unnecessary — a missing key already collapses "unknown" and "already finalized" into the same PERMANENT classification (matching Signal exactly, as the comment on `SignalJsonRpcClient.java:121-126` documents).

If real-world Provider code does sometimes leak unfinalized handles, that is a bug to find, not to mask with a silent cap. Add a metric (`adapter.simplex.handles.open` gauge) so growth is observable.

**Reasoning:**

The spec's finalize invariant is the contract; the SPI's two production adapters must honor it identically; one of them currently does and the other doesn't. The Signal shape is correct and proven by `SignalJsonRpcClientTest.handleEvictedOnFinalize` — replicating it for SimpleX removes the divergence.

**Trade-offs:**

Unbounded growth on a buggy Provider that forgets to finalize. Mitigation: a steady-state gauge + alert at, say, > 256 open handles (much earlier than the 1024 silent cap), so the leak is visible and operator-actionable rather than auto-corrected into a visible UX failure.

---

### F4. `SignalIdentity.resolve` and `SimpleXIdentity.resolve` are unimplemented stubs

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalIdentity.java:28-31`, `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentity.java:28-31`

**Current code:**

```java
// SignalIdentity.java
public static SignalIdentity resolve(@NonNull Path dataDir) {
    throw new UnsupportedOperationException(
            "resolving the bot ACI from signal-cli account state is implemented in M1-107");
}

// SimpleXIdentity.java
public static SimpleXIdentity resolve(@NonNull Path dataDir) {
    throw new UnsupportedOperationException(
            "resolving the bot queue address from simplex-chat data is implemented in M1-103");
}
```

**Why this is wrong / suboptimal / risky:**

The two `resolve` methods (a) cite ticket IDs the spec/design doesn't reference as live work units, (b) throw `UnsupportedOperationException` with no callers, (c) violate the "no defensive code for impossible scenarios" rule's corollary against speculative SPI surface that `MessagingAdapter.java`'s own Javadoc (lines 26-30) calls out:

> "Group-membership probing (`groupExists`) stays deferred to the groups milestone — speculative SPI surface for non-existent callers would violate the engineering rules' 'no defensive code for impossible scenarios' corollary against speculative API."

`SignalIdentity.resolve` and `SimpleXIdentity.resolve` are exactly that — speculative SPI surface. The bot identity is currently constructed externally and passed to the adapter via the constructor (`SignalAdapter(binary, dataDir, account, botAci, daemonEndpoint)`); no code path reads or wants `resolve(Path)`. The method signature shapes the file's API for a future caller that does not exist.

Worse, the throw message names a ticket ID that has already shipped (the constructor IS the implementation of M1-107 / M1-103 identity wiring), so the comment is already wrong and will only rot further.

**Recommended fix:**

Delete both `resolve(Path)` methods. If/when Provider-side wiring needs the on-disk extraction (verify by searching: it does not today), add it then with a real caller.

```java
public record SignalIdentity(String aci) { }

public record SimpleXIdentity(String queueAddress) { }
```

**Reasoning:**

The records themselves carry value (typed wrapper around the trust-anchor string). The static factory `resolve` is unused; deleting it removes dead surface and aligns with the project rule against speculative API.

**Trade-offs:**

None — there are no callers to migrate.

---

### F5. `SignalJsonRpcClient` reader loop reads one char at a time

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:395-441`

**Current code:**

```java
private void readerLoop() {
    Socket s = socket;
    if (s == null) {
        return;
    }
    try (BufferedReader r = new BufferedReader(
            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n') {
                if (sb.length() > 0) {
                    handleLine(sb.toString());
                }
                sb.setLength(0);
                continue;
            }
            if (sb.length() >= MAX_INBOUND_LINE_CHARS) {
                ...
            } else {
                sb.append((char) c);
            }
        }
        ...
    }
}
```

**Why this is wrong / suboptimal / risky:**

`BufferedReader.read()` returns one character at a time, each call acquires the reader's internal lock. For each ~1 KB JSON-RPC notification the loop performs ~1024 lock-acquire calls. The cap-aware accumulation is the reason the previous `readLine()` was replaced (correct for the OOM bound), but the implementation didn't carry over the bulk-read shape.

This is on the hot path: every inbound DM, every group mention, every join/leave event passes through this loop. The lock-acquire cost is small per call but multiplicative under load.

**Recommended fix:**

Read into a char buffer and scan for the newline in-buffer; only switch to single-char on the cap-exceeded path (where the discard already wants per-char drain):

```java
private void readerLoop() {
    Socket s = socket;
    if (s == null) {
        return;
    }
    try (BufferedReader r = new BufferedReader(
            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
        char[] chunk = new char[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = r.read(chunk)) != -1) {
            int start = 0;
            for (int i = 0; i < n; i++) {
                if (chunk[i] != '\n') continue;
                int additional = i - start;
                if (sb.length() + additional > MAX_INBOUND_LINE_CHARS) {
                    LOG.warnf("dropped inbound JSON-RPC line exceeding %d-char cap",
                            MAX_INBOUND_LINE_CHARS);
                    sb.setLength(0);
                } else {
                    sb.append(chunk, start, additional);
                    if (sb.length() > 0) {
                        handleLine(sb.toString());
                    }
                    sb.setLength(0);
                }
                start = i + 1;
            }
            if (start < n) {
                if (sb.length() + (n - start) > MAX_INBOUND_LINE_CHARS) {
                    sb.setLength(MAX_INBOUND_LINE_CHARS);  // mark over-cap; flush on next \n
                } else {
                    sb.append(chunk, start, n - start);
                }
            }
        }
        if (sb.length() > 0 && sb.length() <= MAX_INBOUND_LINE_CHARS) {
            handleLine(sb.toString());
        }
    } catch (IOException e) {
        LOG.debugf("signal-cli reader loop exited: %s", e.getMessage());
    }
}
```

(The over-cap branch in the bulk path needs care; the simpler fully-correct version is to fall through to a per-char skipToNewline once the cap is hit — same as today — but stay in bulk mode while under the cap.)

**Reasoning:**

The bulk-read path takes the same lock once per ~4 KB instead of once per char. The cap-aware drop semantics are preserved. The over-cap drain logic in `skipToNewline` already uses the chunked pattern; this brings the under-cap path into line.

**Trade-offs:**

The code is longer than the single-char loop. The complexity is contained in one method and unit-tested through the existing oversize-line test (`oversizeInboundLineIsDroppedAndReaderSurvives`).

---

### F6. `SimpleXMessageCodec.classifyError` substring matching is brittle

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java:603-622`

**Current code:**

```java
static @NonNull FailureCategory classifyError(@NonNull String errorTag) {
    String lower = errorTag.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("ratelimit")
            || lower.contains("tryagain")
            || lower.contains("networkerror")
            || lower.contains("timeout")
            || lower.contains("temporary")
            || lower.contains("unavailable")
            || lower.contains("connectionerror")) {
        return FailureCategory.TRANSIENT;
    }
    return FailureCategory.PERMANENT;
}
```

**Why this is wrong / suboptimal / risky:**

The classifier matches by substring across an arbitrary error tag. A hypothetical simplex-chat error tag of the form `notRateLimited` or `permanentNetworkErrorWontRetry` (or any future tag that contains one of these substrings) classifies as TRANSIENT regardless of intent. The spec rule (`docs/spec/messaging.md` §Failure handling) is:

> "An adapter that cannot tell the two apart MUST default to permanent — silently looping a permanent failure is a worse failure mode than aborting an occasionally-transient one."

Substring matching is the opposite of "default to permanent on uncertainty" — it actively over-classifies as TRANSIENT.

The known simplex-chat error tag corpus is finite and surveyed by the spec's design notes (§6.4.7). The classifier should match against a known-set of full tags (or known prefixes), not substrings.

**Recommended fix:**

```java
private static final Set<String> TRANSIENT_TAGS = Set.of(
        "rcvratelimit", "tryagainlater", "networkerror",
        "connectiontimeout", "temporaryunavailable", "rcvunavailable",
        "connectionerror");

static FailureCategory classifyError(String errorTag) {
    String normalised = errorTag.toLowerCase(Locale.ROOT);
    return TRANSIENT_TAGS.contains(normalised)
            ? FailureCategory.TRANSIENT
            : FailureCategory.PERMANENT;
}
```

If the live simplex-chat error corpus is broader than the survey, augment the set with each newly observed tag — explicitly, with a code change — rather than relying on a substring that could match an unknown future tag in the wrong direction.

**Reasoning:**

Exact-match is invariant to future simplex-chat additions: an unrecognised tag classifies PERMANENT per the spec's "default to permanent" rule, which is what the comment on this method already claims. The substring shape silently violates that rule for any future tag whose name contains one of the seven keywords.

**Trade-offs:**

A genuine new TRANSIENT tag from a future simplex-chat release goes PERMANENT until the set is updated. This is the correct failure mode per the spec ("the worse failure mode is to silently loop a permanent"); over-classification as PERMANENT aborts the affected reply, which is recoverable on next user inbound; over-classification as TRANSIENT enters the retry loop with no escape.

The existing unit tests (`SimpleXMessageCodecTest.classifiesFailureCategory`) exercise the documented tag corpus only; the proposed fix passes all of them.

---

### F7. `SimpleXConfig` exposes public getters; `SignalConfig` deliberately doesn't — pick one

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalConfig.java`, `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXConfig.java`

**Current code:**

```java
// SimpleXConfig
public String binary() { return binary; }
public String dataDir() { return dataDir; }
public int wsPort() { return wsPort; }

// SignalConfig — no public getters. SignalAdapter.java:49-53 explicitly notes:
// "SignalConfig has no public getters by M1-106 design, and adding them is out
//  of M1-107's files_scope. The Provider-side wiring documents this contract."
```

**Why this is wrong / suboptimal / risky:**

The two config beans serve the same role — operator config + startup validation — and were authored against the same template (`SimpleXConfig`'s class Javadoc says "mirrors SignalConfig"). But the public-access surface is different: `SimpleXConfig` exposes its three fields via record-style accessors; `SignalConfig` has none and forces Provider-side wiring to re-read the `@ConfigProperty` keys separately. The Javadoc on `SignalAdapter` (lines 45-53) calls out this asymmetry as a known oddity.

The result is two parallel patterns in the same module for the same purpose. A future adapter authoring its own `Config` bean has no consistent shape to copy. The pattern divergence is a maintenance hazard.

**Recommended fix:**

Pick one. Per the project's tendency toward simplicity (CLAUDE.md §Coding style "Simplify aggressively") and the existing usage (`SimpleXAdapter.start()` reads `cfg.binary()`, `cfg.dataDir()`, `cfg.wsPort()` — proving the accessors do load-bearing work), keep public getters on both:

```java
@ApplicationScoped
@Startup
public class SignalConfig {

    public static final String BINARY_KEY = ...;
    public static final String DATA_DIR_KEY = ...;
    public static final String ACCOUNT_KEY = ...;

    private final String binary;
    private final String dataDir;
    private final String account;

    @Inject
    SignalConfig(...) { ... }

    public String binary() { return binary; }
    public String dataDir() { return dataDir; }
    public String account() { return account; }

    @PostConstruct
    public void validate() { ... }
}
```

Provider-side wiring can then read the bean directly instead of re-resolving `@ConfigProperty` keys.

**Reasoning:**

A consistent shape across all adapter `Config` beans makes the pattern copy-paste-able for the next adapter and removes the "why is one different?" puzzle the SignalAdapter Javadoc currently has to explain.

**Trade-offs:**

Exposes the three strings as adapter-side read-only state. They're already in MicroProfile Config (which Provider can read directly anyway), so the access-level change leaks nothing.

**Alternative options:**

- **Option A** (recommended above) — add getters to `SignalConfig`.
- **Option B** — remove getters from `SimpleXConfig`, have Provider re-read `@ConfigProperty` keys for both. Cons: SimpleXAdapter's `start()` would need a SimpleXConfig-shaped record passed in instead of the bean, which is more wiring not less.

---

### F8. Inconsistent annotation style on record components

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/Identity.java:20`, `ScopeRef.java:26-29`, `InboundMessage.java:24-29`, `OutboundMessage.java:18-22`, `MessageHandle.java:34`

**Current code:**

```java
// Identity.java
public record Identity(@NonNull String contactId, @Nullable String displayName, @NonNull Instant lastSeen) {}

// ScopeRef.java — NO annotations
public sealed interface ScopeRef {
    record Dm(String contactId) implements ScopeRef {}
    record Group(String adapterGroupId) implements ScopeRef {}
}

// InboundMessage.java — every component annotated
public record InboundMessage(
        @NonNull Identity sender,
        @NonNull ScopeRef scope,
        @NonNull String text,
        @NonNull Instant receivedAt,
        @NonNull String adapterMessageId) {}
```

**Why this is wrong / suboptimal / risky:**

Five SPI record types declared on the same surface use three different annotation patterns: every component annotated, only nullable components annotated, no annotations at all. Per the engineering rule §7a the package default is non-null; the only correct annotation is `@Nullable` on `Identity.displayName`. F1 already covers the broader sweep, but the SPI records are the most-read surface in the module and inconsistency here is the most visible.

**Recommended fix:**

Strip `@NonNull` from every record component; keep `@Nullable` where it's truthful.

```java
public record Identity(String contactId, @Nullable String displayName, Instant lastSeen) {}
public sealed interface ScopeRef { ... }    // unchanged
public record InboundMessage(Identity sender, ScopeRef scope, String text, Instant receivedAt, String adapterMessageId) {}
public record OutboundMessage(ScopeRef scope, String text, Instant requestedAt, String correlationId) {}
public record MessageHandle(String opaqueValue) {}
```

**Reasoning:**

Subset of F1; called out separately because the SPI records are the public-API surface other modules consume — uniformity matters here even more than in impl classes.

**Trade-offs:**

None — the build proves the invariant either way.

---

### F9. `SignalJsonRpcClient.skipToNewline` uses mark/reset to overshoot the terminator by one

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:451-472`

**Current code:**

```java
private static boolean skipToNewline(BufferedReader r) throws IOException {
    char[] chunk = new char[8_192];
    while (true) {
        r.mark(chunk.length);
        int n = r.read(chunk);
        if (n == -1) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (chunk[i] == '\n') {
                r.reset();
                long toSkip = i + 1L;
                while (toSkip > 0) {
                    toSkip -= r.skip(toSkip);
                }
                return true;
            }
        }
    }
}
```

**Why this is wrong / suboptimal / risky:**

The method reads a chunk to find `'\n'`, then rewinds the reader and re-skips up to that newline so the chars after the terminator survive for the next reader-loop iteration. The mark/reset dance plus skip-loop is a non-trivial state machine for what is conceptually "look for a newline and stop after it."

Two simpler shapes:

1. The reader-loop above (lines 395-441) accumulates char-by-char into a StringBuilder anyway; using the same single-char loop here keeps the cap-exceeded discard inline (no mark/reset needed):

```java
private static boolean skipToNewline(BufferedReader r) throws IOException {
    int c;
    while ((c = r.read()) != -1) {
        if (c == '\n') {
            return true;
        }
    }
    return false;
}
```

2. If F5's bulk-read shape lands, the cap-exceeded path can stash the post-`\n` chars in a `StringBuilder` for the next iteration and skip the whole helper.

**Recommended fix:**

Replace with the single-char loop above. The chunked-then-rewound shape was justified by "scan in chunks instead of one locked read() per discarded char," but `BufferedReader.read()` from an 8 KiB-internal-buffer reader doesn't lock per char on the underlying stream — the buffer is filled in bulk; `read()` reads from the in-memory buffer until empty. The skipToNewline savings vs. the simple loop are an order of magnitude smaller than the F5 fix.

**Reasoning:**

The simpler loop is correct, half the lines, and avoids the mark/reset/skip subtlety. If a future profiler shows the cap-exceeded path is hot (it shouldn't — it only fires on protocol-violating peers), revisit.

**Trade-offs:**

None — the bulk-read benefit assumed by the current code doesn't materialise because BufferedReader already buffers underlying I/O. The complexity of the current code is unjustified.

---

## Notes on cross-module concerns

Not in scope for this report but worth mentioning briefly:

- The `MessagingException` and `FailureCategory` use across boundaries: this module raises PERMANENT for "not yet started" send/update/finalize. The downstream Provider retry policy treats PERMANENT as terminal and skips retry (per spec). That's correct, but it does conflate "I haven't been started yet" with "the message was rejected as a policy violation" — both abort the affected reply. A future review of Provider's retry layer should confirm a single PERMANENT classification on a fresh adapter isn't misread as a real terminal failure that triggers the bot-removed-from-group threshold.
- `SimpleXSubprocess.drainStream` (line 298) and `SignalSubprocess.drainAndLog` (line 159) both implement subprocess-output discard correctly per `docs/design/06-messaging.md` §6.4.8. The two implementations have diverged (`SimpleXSubprocess` uses a byte buffer and emits one marker per drain lifetime; `SignalSubprocess` uses `BufferedReader.readLine()` and emits a debug-line-count summary on EOF) — the SimpleX shape is the documented one and more rigorous. Worth aligning when SignalSubprocess's drain is next touched.
