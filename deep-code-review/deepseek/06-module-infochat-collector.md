# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-01 20:57
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — `BlueskyFetcher.java:110` — `buildUri` concatenates actor and cursor into a query string without URL-encoding, making the URI dependent on the host/operator-provided identifier containing no special characters
- [MEDIUM] SIMPLIFICATION — `AssetSnapshotFetcher.java:228` — `recordFailure` implements a complete bespoke failure-counter/status-flip state machine that structurally duplicates `SourceRepository`, adding a second implementation to maintain
- [LOW] MAINTAINABILITY-RULES-DRIFT — `BootstrapAssetsLoader.java:301-305` — defensive code for a scenario documented as unreachable, violating engineering-rules-verbatim.md section 7
- [LOW] SIMPLIFICATION — `NostrRelayConnection.java:354` — `backoffDelay` receives a `Random` instance held as an instance field but is `static`, hiding the fact that each connection uses its own Random; no thread-safety issue but the static signature is misleading
- [LOW] MAINTAINABILITY-RULES-DRIFT — `QuarantineNotifyEmitter.java:41`, `ReadyPromoter.java:176`, `PriceSnapshotStore.java:99` — NOTIFY payloads built via string concatenation with no JSON escaping, safe today only because all values are enum-like constants
- [LOW] SIMPLIFICATION — `sha256Hex` implemented in three files (`PostPersister.java:168`, `BootstrapLoader.java:276`, `BootstrapAssetsLoader.java:363`); `jsonEscape` in four files; `normalizeTag` in three files — each variant is byte-identical to its siblings
- [LOW] SIMPLIFICATION — `EntityExtractorWorker.java:127` embeds the entity-extraction prompt as a Java `String` constant while `TaggerWorker` and `Stage2Worker` load theirs from classpath resources, creating an inconsistency for prompt changes

## Detail

### F1. BlueskyFetcher query parameters lack URL encoding

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** MEDIUM
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java:110-116`

**Current code:**

```java
private URI buildUri(String actor, String cursor) {
    StringBuilder sb = new StringBuilder(xrpcBase)
        .append("?actor=").append(actor);
    if (cursor != null) {
        sb.append("&cursor=").append(cursor);
    }
    return URI.create(sb.toString());
}
```

**Why this is wrong / suboptimal / risky:**

The `actor` (source identifier from the DB) and `cursor` (opaque string from the Bluesky API) are concatenated directly into a URL query string without encoding via `URLEncoder.encode(..., UTF_8)` or `URI` builders. If either value contains characters with special meaning in query strings (`&`, `=`, `+`, `#`), the resulting URI parses differently than intended:

- A cursor containing `&foo=` would inject an additional query parameter beyond the intended `cursor`.
- An actor handle containing `+` would be interpreted as a space by the server (form-encoding convention).
- `URI.create()` throws `IllegalArgumentException` on any input that violates RFC 2396, making an unexpected character a runtime crash rather than a silent corruption.

Bluesky DIDs (`did:plc:...`) and handles (`user.bsky.social`) use a restricted character set, so the practical exploit surface is low through operator-configured sources. The cursor returned by the API is more opaque and could introduce characters the caller does not control. The same pattern in `RedditFetcher.buildPageUri` (line 108-114) has the same issue with `afterCursor`.

This violates the engineering rule against sacrificing correctness to reach a goal (section 2) — the fix is a one-liner and URL encoding is standard defensive practice at a system boundary.

**Recommended fix:**

```java
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

private URI buildUri(String actor, String cursor) {
    String encodedActor = URLEncoder.encode(actor, StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder(xrpcBase)
        .append("?actor=").append(encodedActor);
    if (cursor != null) {
        sb.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }
    return URI.create(sb.toString());
}
```

**Reasoning:**

`URLEncoder.encode` encodes special characters as `%XX` sequences, producing a query-string-safe value that `URI.create` accepts. The result is correct regardless of what characters actor or cursor contain. The `StandardCharsets.UTF_8` constant avoids the checked `UnsupportedEncodingException` that the string-arg variant carries.

**Trade-offs:**

Slightly more verbose (two extra lines). The encoded form of already-safe inputs is identical to the unencoded form (`did:plc:abc` encodes to `did%3Aplc%3Aabc` for URLEncoder, which is slightly longer but semantically identical — the server sees the same decoded value).

---

### F2. AssetSnapshotFetcher duplicates SourceRepository failure-counter logic

- **Category:** SIMPLIFICATION
- **Severity:** MEDIUM
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java:228-297`

**Current code:**

```java
private void recordFailure(@NonNull EnabledPair row, @NonNull FetchException cause) {
    final String bumpSql =
        "UPDATE asset_config "
        + "   SET consecutive_failures = consecutive_failures + 1, "
        + "       last_failure_at = NOW() "
        + " WHERE asset = ? AND sub_verb = ? "
        + "RETURNING consecutive_failures";
    int newCount;
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(bumpSql)) {
        // ...
    } catch (SQLException e) {
        // ...
    }
    // ...
    if (newCount < failureThreshold) {
        return;
    }
    // Step 2: flip status active -> failed
    final String flipSql =
        "UPDATE asset_config "
        + "   SET status = 'failed' "
        + " WHERE asset = ? AND sub_verb = ? AND status = 'active'";
    // ...
}
```

**Why this is wrong / suboptimal / risky:**

`FetchScheduler` uses `SourceRepository` (a dedicated DAO) to encapsulate the counter-update + status-flip + crossing-detection state machine. `AssetSnapshotFetcher` implements the identical three-step pattern (bump counter, check threshold, flip status, notify) from scratch with inline SQL on the `asset_config` table instead. The two implementations differ only in table name and column names (`source` vs `asset_config`, `last_fetch_at` vs `last_failure_at`). This means:

1. Any bug fix or enhancement to the failure-ladder logic (changing threshold semantics, adding jitter, modifying the `RETURNING` shape) must be applied in two places.
2. The `AssetSnapshotFetcher.recordFailure` method (69 lines) is longer than `SourceRepository.recordFailure` (30 lines) because it inlines both the bump and the flip, with a split-bump-then-flip that makes two round-trips instead of one.
3. The crossing-detection logic is subtly different: `SourceRepository` uses a single `CASE` statement in the UPDATE to atomically increment-and-flip in one round-trip; `AssetSnapshotFetcher` does it in two separate statements with a gap where a concurrent tick (impossible given single-instance, but the pattern is still weaker) could double-notify.

**Recommended fix:**

Extract a shared `FailureCounter` abstraction in `infochat-core` (or a new package) that takes a table/column descriptor and encapsulates the counter-increment, threshold check, and status-flip in one `recordFailure` method, then use it in both `SourceRepository` and `AssetSnapshotFetcher`. The two callers differ only in which table they update.

**Reasoning:**

A shared utility eliminates the duplication, ensures the same atomic increment-and-flip pattern in both callers, and creates a single maintenance point for the D42 failure-ladder policy. The extract is small enough that it fits the "push back when simpler exists" and "simplify aggressively" guidance.

**Trade-offs:**

Introduces an abstraction where currently there is none. The abstraction could be argued to be premature if no third failure-ladder caller ever appears. However, two callers already exist in the same module at the same abstraction level, which is the canonical threshold for extraction.

---

### F3. BootstrapAssetsLoader defensive code for unreachable scenario

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** LOW
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoader.java:301-305`

**Current code:**

```java
if (keepAssets.isEmpty()) {
    // Parser rejects an empty assets[] array, so this branch
    // is unreachable in practice — guard kept for SQL safety.
    return 0;
}
```

**Why this is wrong / suboptimal / risky:**

The comment explicitly states the scenario is unreachable in practice (the parser rejects empty `assets[]` arrays). The guard is kept "for SQL safety" — but this is inside a `try` block where the only consequence of an empty list is a malformed SQL statement that would throw `SQLException`, which is caught by the caller and rolled back as a transaction failure. There is no safety benefit, only dead code.

This violates engineering-rules-verbatim.md section 7: "No defensive code for impossible scenarios" and "No 'just in case' branches."

**Recommended fix:**

Remove the guard:

```java
public int softDisableAbsentRows() {
    // ...build keepAssets/keepSubVerbs lists...
    StringBuilder placeholders = new StringBuilder(keepAssets.size() * 6);
    // ...
}
```

**Reasoning:**

The code compiles and runs correctly without the branch. If a future change allows empty asset lists, the SQL exception from the malformed statement is caught by the existing rollback logic and produces a clear error message (the SQL syntax error from the empty `IN (...)` clause is self-documenting). Adding a guard before the fact provides no measurable benefit.

**Trade-offs:**

None — the fix is strictly better.

---

### F4. NostrRelayConnection.backoffDelay is static but uses an instance-level Random

- **Category:** SIMPLIFICATION
- **Severity:** LOW
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java:354`

**Current code:**

```java
static @NonNull Duration backoffDelay(int attempt, @NonNull Duration base,
                                      @NonNull Duration max, @NonNull Random random) {
    // ...
    long jitter = half <= 0 ? 0 : random.nextLong(half + 1);
    // ...
}
```

called from `runLoop`:

```java
private final Random random = new Random();
// ...
long backoffMs = backoffDelay(consecutiveFailures, backoffBase, backoffMax, random).toMillis();
```

**Why this is wrong / suboptimal / risky:**

`backoffDelay` is declared `static` (correctly — it depends only on its parameters), but the production call passes `this.random`, an instance-level `Random` that is never shared. Each `NostrRelayConnection` has its own `Random`, which is wasteful (64 bytes of state per connection, seeded from system entropy if `SecureRandom` fallback is used) and unnecessary. The `static` method signature accepts `Random` as a parameter, which is the right pattern for testability, but the production caller could pass a shared instance.

**Recommended fix:**

Either make `random` a `static final` shared field (since `Random` is thread-safe), or document why per-connection instances are intentional:

```java
private static final Random RANDOM = new Random();
```

Then remove the `random` field and the constructor parameter (if the test constructor for `NostrRelayConnection` that also receives a `Random` is adjusted accordingly), or keep the parameter-passing pattern but use `NostrRelayConnection.RANDOM` at the call site.

**Reasoning:**

A single static `Random` instance is the standard JDK pattern for non-cryptographic randomness. Each `NostrRelayConnection` currently holds its own instance with no benefit, allocating unnecessary heap. The `backoffDelay` method is already thread-safe (it reads Random state, not connection state), so sharing is safe.

**Trade-offs:**

Tests that rely on deterministic `Random` behavior must either supply their own `Random` through the method parameter (already possible) or seed the shared instance. The current pattern of passing `Random` as a parameter already enables this; the change only affects the production caller.

---

### F5. NOTIFY payloads built via string concatenation without JSON escaping

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** LOW
- **Location:** cross-cutting: `QuarantineNotifyEmitter.java:41`, `ReadyPromoter.java:176`, `PriceSnapshotStore.java:99`

**Current code:**

```java
// QuarantineNotifyEmitter.java:41
String payload = "{\"target_kind\":\"" + targetKind
    + "\",\"target_id\":\"" + targetId
    + "\",\"new_status\":\"" + newStatus + "\"}";

// ReadyPromoter.java:176
String payload = "{\"ready_at\":\"" + readyAt.toString()
    + "\",\"post_id\":\"" + postId.toString() + "\"}";

// PriceSnapshotStore.java:99
String payload = "{\"asset\":\"" + jsonEscape(snapshot.asset())
    + "\",\"source\":\"" + jsonEscape(snapshot.subVerb()) + "\"}";
```

**Why this is wrong / suboptimal / risky:**

Two of the three payload builders (`QuarantineNotifyEmitter`, `ReadyPromoter`) concatenate string values directly into JSON without escaping. If any value contains a double-quote, backslash, or control character, the resulting JSON is malformed and the Provider-side listener (which parses this JSON) fails to deserialize it. `PriceSnapshotStore` calls `jsonEscape` (good), demonstrating that the project considers this a concern, yet the other two callers do not follow the same pattern.

The current values are safe because they are enum-like constants or UUIDs/Instants, so the issue is latent rather than active. However, it is a fragility that will bite the first time a value with special characters is added.

**Recommended fix:**

Use a shared `jsonEscape` helper (or a shared JSON serialization method) in all three NOTIFY payload sites. A `static String jsonEscape(String)` already exists in `BootstrapLoader`, `BootstrapAssetsLoader`, and `PriceSnapshotStore`; extract it to a shared utility in `infochat-core` and use it from all NOTIFY builders.

**Reasoning:**

The consistent pattern across the codebase is to JSON-escape values when building payloads by hand. The two sites that don't are outliers. A shared utility ensures uniform escaping and prevents the next developer from copying the concatenation pattern without escaping.

**Trade-offs:**

Slightly more verbose per call site. A more complete fix would be a structured JSON builder (`JsonObject` from Jackson or a dedicated record serialized via `ObjectMapper`), but that adds a dependency or runtime overhead. String concatenation with proper escaping is the pragmatic middle ground already used in three other call sites.

---

### F6. Triplicated sha256Hex, jsonEscape, normalizeTag utilities

- **Category:** SIMPLIFICATION
- **Severity:** LOW
- **Location:** cross-cutting (see current code)

**Current code:**

`sha256Hex` in three files:
- `infochat-collector/.../outbox/PostPersister.java:168-176`
- `infochat-collector/.../bootstrap/BootstrapLoader.java:276-290`
- `infochat-collector/.../bootstrap/BootstrapAssetsLoader.java:363-376`

`jsonEscape` in four files:
- `infochat-collector/.../bootstrap/BootstrapLoader.java:297-310`
- `infochat-collector/.../bootstrap/BootstrapAssetsLoader.java:378-391`
- `infochat-collector/.../assets/store/PriceSnapshotStore.java:133-135`
- `infochat-collector/.../eval/stage2/StartupReleaseOnStage2FailureWarn.java:157-175`

`normalizeTag` / tag-normalization in three files:
- `infochat-collector/.../bootstrap/BootstrapLoader.java:266-274`
- `infochat-collector/.../eval/tagger/TagVocabulary.java:127-134`
- `infochat-collector/.../eval/tagger/TaggerWorker.java:425-432`

**Why this is wrong / suboptimal / risky:**

Three instances of the identical `sha256Hex` helper, four instances of `jsonEscape`, three instances of tag normalization. Each duplicate is byte-identical or semantically identical to its siblings. The TODO comments at `TagVocabulary.java:126` ("TODO(T1-D): move to TagNormalizer helper alongside BootstrapLoader.normalizeTag") and `TaggerWorker.java:424` show the project already recognizes this but has not acted.

Each duplicate carries a maintenance burden: if the hex-encoding format needs to change (e.g., to use `HexFormat.of().withUpperCase()`), an updater must find and patch all three copies. The same applies to JSON-escaping.

**Recommended fix:**

Extract the three utilities into shared location(s) in `infochat-core` (or a `util` package within the module) and replace all inline implementations with calls to the shared method. The three utilities are:

1. `Digests.sha256Hex(byte[])` — `MessageDigest` + hex encode
2. `JsonEscaper.escape(String)` — JSON string escaping
3. `TagNormalizer.normalize(String)` — NFC + `Locale.ROOT.toLowerCase` + `TAG_NAME_PATTERN` validation

**Reasoning:**

Simplifies each call site, eliminates the drifting-risk from multiple implementations, and addresses the TODOs already in the code.

**Trade-offs:**

Extracting shared code to `infochat-core` adds a dependency from `infochat-collector` to `infochat-core` if not already present (it is — the module DAG shows `infochat-core` is a dependency). The extraction itself is mechanical and can be done without behavioral change.

---

### F7. Entity extraction prompt embedded as Java string constant

- **Category:** SIMPLIFICATION
- **Severity:** LOW
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorker.java:127-142`

**Current code:**

```java
private static final String PROMPT_TEMPLATE =
    "Extract the named entities mentioned in the post below.\n"
        + "Respond with ONLY a JSON array of objects, each of the form\n"
        + "{\"text\": \"<entity>\", \"type\": \"<type>\"}.\n"
        // ... (~15 more lines)
        + "{{id}}\n";
```

**Why this is wrong / suboptimal / risky:**

`TaggerWorker` and `Stage2Worker` both load their prompts from classpath resource files (`.md` files in `src/main/resources/prompts/`). `EntityExtractorWorker` embeds its prompt as a Java string constant. This inconsistency means:

1. Changing the entity extraction prompt requires a recompile and redeploy of the entire module; changing a classpath-based prompt can be done by replacing the file in the JAR (or mounting an overlay in containerized deployments).
2. The prompt cannot be reviewed or edited by non-Java contributors (e.g., a security researcher tuning entity extraction) without navigating Java source files.
3. The string is harder to diff across versions than a file on disk.

**Recommended fix:**

Move the inline prompt to `src/main/resources/prompts/entity-extractor.md` (or `prompts/entity.md`) and load it via the same `loadResource` pattern used by `TaggerWorker` and `Stage2Worker`.

**Reasoning:**

Consistency with the rest of the module, separation of prompt content from Java code, enabling file-based prompt review and replacement.

**Trade-offs:**

An extra file in the resource directory and a few lines of loading code. The prompt template is small (~15 lines) so the extract is not large, but the consistency benefit across the module is the primary driver.

---

## Additional observations

The following were examined and found to be correct:

- **InstanceLockGuard** (line 81): `Quarkus.asyncExit(1)` vs spec's planned exit code 42 is documented as intentional (systemd unit-file ticket updates later). The javadoc is explicit about the v1 trade-off.
- **PostPersister.deriveUid** (line 163): SHA-256 hash of `sourceUuid + "|" + upstreamIdentifier` matches the spec commitment at `docs/spec/schema.md` section UID derivation.
- **UrlRedactor.redact() usage**: All exception messages in fetchers use `UrlRedactor.redact(identifier)` rather than the raw identifier. The `FetchScheduler.logFetchFailure` method (line 316) explicitly avoids passing the throwable as a logger parameter to prevent unredacted stack-trace output.
- **TransactionHelper.inTransaction**: Properly handles rollback on both `RuntimeException` and `SQLException`, and the outer `try-with-resources` on the Connection ensures the connection returns to the pool even on rollback failure.
- **Stage1Pipeline step order**: The javadoc devotes extensive prose to why "entity-decode -> Unicode-first -> OWASP-last" is load-bearing. The code matches the documented order.
- **Semaphore usage** in `Stage2Worker`, `TaggerWorker`, `EntityExtractorWorker`, `EmbeddingWorker`: All acquire permits before async work and release in `finally` blocks. No leaked permits.
- **RelayHealthTracker.decideTransition**: The cyclic-bad-cycle logic correctly handles the `allBefore && !allAfter` (entry to all-bad) and `wasSuccess && inAllBadCycle` (recovery) edges. The `TERMINAL` state is monotonic (once set, all subsequent `recordFailure`/`recordSuccess` calls return `NONE`).
