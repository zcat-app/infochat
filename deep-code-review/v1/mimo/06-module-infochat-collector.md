# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-02 00:05
**Reviewer:** senior-developer (mimo)

## Headline findings

- [medium] SECURITY — `QuarantineNotifyEmitter.java:41-42` — NOTIFY payload fields interpolated without JSON escaping
- [medium] SIMPLIFICATION — cross-cutting (BootstrapLoader, TaggerWorker, TagVocabulary) — tag normalization logic duplicated in three places
- [medium] SIMPLIFICATION — cross-cutting (BootstrapLoader, PostPersister) — SHA-256 hex encoding duplicated in two places
- [low] SIMPLIFICATION — cross-cutting (BootstrapLoader, StartupReleaseOnStage2FailureWarn) — JSON escape helper duplicated

## Detail

### F1. QuarantineNotifyEmitter NOTIFY payload fields not JSON-escaped

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java:41-42`

**Current code:**

```java
String payload = "{\"target_kind\":\"" + targetKind
    + "\",\"target_id\":\"" + targetId
    + "\",\"new_status\":\"" + newStatus + "\"}";
```

**Why this is wrong / suboptimal / risky:**

The `targetKind`, `targetId`, and `newStatus` strings are interpolated directly into the JSON payload without escaping. While current call sites pass controlled values (literal `"quarantine"`, `"post"`, UUIDs, and status enum names), the method signature accepts arbitrary `String` and `UUID` parameters. If a future caller passes a string containing `"` or `\`, the resulting JSON would be malformed or could inject additional keys. Compare with `PriceSnapshotStore.java:99-100` and `ReadyPromoter.java:176-177`, which both apply JSON escaping to their NOTIFY payload fields. The inconsistency means `quarantine_review` NOTIFY payloads are the only channel whose JSON is not defensively escaped.

**Recommended fix:**

```java
public void emit(@NonNull Connection conn, @NonNull String targetKind,
                 @NonNull UUID targetId, @NonNull String newStatus) throws SQLException {
    String payload = "{\"target_kind\":\"" + jsonEscape(targetKind)
        + "\",\"target_id\":\"" + targetId
        + "\",\"new_status\":\"" + jsonEscape(newStatus) + "\"}";
    // ... rest unchanged
}

private static String jsonEscape(String in) {
    return in.replace("\\", "\\\\").replace("\"", "\\\"");
}
```

**Reasoning:**

`UUID.toString()` produces only hex digits and hyphens, so it does not need escaping. The `targetKind` and `newStatus` fields are caller-supplied strings that could theoretically contain JSON-significant characters. Adding the escape brings this emitter into alignment with the other two NOTIFY emitters in the module and eliminates a class of malformed-payload bugs if a future caller passes unexpected content.

**Trade-offs:**

None -- the fix is strictly better. The extra string replacement on two short strings has negligible cost.

---

### F2. Tag normalization logic duplicated in three places

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:**
  - `infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java:266-274`
  - `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java:425-432`
  - `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagVocabulary.java:127-134`

**Current code (BootstrapLoader):**

```java
private static final Pattern TAG_NAME_PATTERN =
    Pattern.compile("^[a-z0-9][a-z0-9-]{0,47}$");

private static String normalizeTag(String raw) {
    String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    if (!TAG_NAME_PATTERN.matcher(normalized).matches()) {
        throw new IllegalStateException(
            "BootstrapLoader: invalid tag '" + raw + "' (normalized: '" + normalized
                + "') — must match " + TAG_NAME_PATTERN.pattern());
    }
    return normalized;
}
```

**Current code (TaggerWorker):**

```java
static String normalizeTag(String raw) {
    if (raw == null) { return null; }
    String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
    String lower = nfc.toLowerCase(Locale.ROOT);
    return TagVocabulary.TAG_NAME_PATTERN.matcher(lower).matches() ? lower : null;
}
```

**Current code (TagVocabulary):**

```java
static final Pattern TAG_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,47}$");

static String normalize(String raw) {
    if (raw == null) { return null; }
    String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
    String lower = nfc.toLowerCase(Locale.ROOT);
    return TAG_NAME_PATTERN.matcher(lower).matches() ? lower : null;
}
```

**Why this is wrong / suboptimal / risky:**

The normalization rule (NFC + `Locale.ROOT` lowercase + character-class regex) is a spec-committed invariant (`docs/spec/schema.md` §Tags -- "stored form"). Three copies exist in the same module, each slightly different: `BootstrapLoader` throws on invalid input (startup validation), `TaggerWorker` returns null (filter), and `TagVocabulary` returns null (filter). The `TAG_NAME_PATTERN` regex is compiled independently in `BootstrapLoader` (private field, line 94) and `TagVocabulary` (package-private field, line 72). All three carry `TODO(T1-D): consolidate` comments. If the regex or normalization steps ever need to change (e.g., length limit adjustment), three files must be updated in lockstep.

**Recommended fix:**

Extract a shared `TagNormalizer` utility class in the `eval/tagger` package (or a shared `common` package) with a single `TAG_NAME_PATTERN`, a single `normalize(String raw)` method returning `Optional<String>` (empty for invalid), and a single `normalizeOrThrow(String raw, String context)` for the bootstrap loader's fail-fast path. Replace all three call sites.

**Reasoning:**

The engineering rules (CLAUDE.md §Coding style: "Simplify aggressively") and the three TODO comments already identify this as the right consolidation. A single source of truth for the tag normalization invariant eliminates the risk of divergence.

**Trade-offs:**

Slight increase in coupling (BootstrapLoader now depends on a class in the eval/tagger package). Could be mitigated by placing the normalizer in a shared utility package.

---

### F3. SHA-256 hex encoding duplicated in two places

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:**
  - `infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java:276-290`
  - `infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java:168-177`

**Current code (BootstrapLoader):**

```java
private static String sha256Hex(byte[] data) {
    MessageDigest md;
    try {
        md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable in this JRE", e);
    }
    byte[] digest = md.digest(data);
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
        hex.append(String.format("%02x", b & 0xff));
    }
    return hex.toString();
}
```

**Current code (PostPersister):**

```java
private static String sha256Hex(byte[] data) {
    MessageDigest md;
    try {
        md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable in this JRE", e);
    }
    return HexFormat.of().formatHex(md.digest(data));
}
```

**Why this is wrong / suboptimal / risky:**

Two identical-purpose methods exist in the same module. `PostPersister` uses the JDK 25 `HexFormat` API (cleaner); `BootstrapLoader` uses a manual `StringBuilder` loop. The implementations produce the same output but diverge in style. The `BootstrapAssetsLoader.sha256Hex()` is a third copy (line 363-376) using the same manual loop as `BootstrapLoader`.

**Recommended fix:**

Extract a single `Sha256.hex(byte[] data)` utility method (or use `HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))` inline) and replace all three call sites. The JDK 25 `HexFormat` form is preferred.

**Reasoning:**

Three copies of the same cryptographic utility is unnecessary duplication. The `HexFormat` form is both shorter and more idiomatic for JDK 25.

**Trade-offs:**

None -- the fix is strictly shorter and more readable.

---

### F4. JSON escape helper duplicated across the module

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:**
  - `infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java:297-310`
  - `infochat-collector/src/main/java/app/zcat/infochat/collector/startup/StartupReleaseOnStage2FailureWarn.java:152-175`
  - `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java:133-135`

**Current code (PriceSnapshotStore, minimal):**

```java
private static String jsonEscape(String in) {
    return in.replace("\\", "\\\\").replace("\"", "\\\"");
}
```

**Current code (StartupReleaseOnStage2FailureWarn, thorough):**

```java
private static String jsonEscape(String s) {
    // ... handles \\, \", \n, \r, \t, and control chars < 0x20
}
```

**Why this is wrong / suboptimal / risky:**

Three different `jsonEscape` implementations exist in the module with varying levels of thoroughness. `PriceSnapshotStore` only escapes backslash and double-quote. `BootstrapLoader` escapes backslash, double-quote, CR, and LF. `StartupReleaseOnStage2FailureWarn` is the most thorough (adds tab and generic control-char escaping). The inconsistency means the escaping behavior depends on which call site builds the JSON string.

**Recommended fix:**

Extract a single `JsonEscape.minimal(String)` (for short operator-controlled values like asset ids) and optionally a `JsonEscape.full(String)` (for paths and labels) in a shared utility, or consolidate to one thorough implementation.

**Reasoning:**

Consistent escaping behavior across all hand-built JSON payloads eliminates a class of malformed-JSON bugs and reduces the reviewer's cognitive load when auditing NOTIFY payloads and audit-log details_json fields.

**Trade-offs:**

Minor -- adds a shared utility class. The alternative (leave as-is) is acceptable since all current inputs are operator-controlled ASCII.
