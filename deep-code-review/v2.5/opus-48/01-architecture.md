# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-08 18:40
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] SIMPLIFICATION — cross-cutting (PriceSnapshotStore.java + AssetSnapshotReader.java) — the `new_price_snapshot` NOTIFY channel is emitted on every snapshot write but has zero production consumers and the spec's "Provider in-process cache" does not exist; the channel is pure overhead and the spec describes a component that was never built.
- [low] MAINTAINABILITY-RULES-DRIFT — QuarantineReviewListener.java:194-197 — an unrecognized `target_kind` in a `quarantine_review` payload is silently routed to the `post` base-table lookup instead of being rejected at the documented NOTIFY trust boundary.

## Detail

### F1. `new_price_snapshot` channel has no consumer and the spec'd Provider cache does not exist

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** NOTIFY

**Current code:**

```java
// infochat-collector/.../assets/store/PriceSnapshotStore.java:46-52
/**
 * NOTIFY channel name — best-effort cache-invalidation seam
 * (spec commands.md §Asset commands); no production consumer yet,
 * the Provider's in-process snapshot cache will subscribe. The
 * table read is the correctness guarantee.
 */
public static final String NEW_PRICE_SNAPSHOT_CHANNEL = "new_price_snapshot";
```

```java
// infochat-collector/.../assets/store/PriceSnapshotStore.java:123-133
String payload = "{\"asset\":\"" + JsonEscaper.escape(snapshot.asset())
    + "\",\"source\":\"" + JsonEscaper.escape(snapshot.subVerb()) + "\"}";
try (PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
    ps.setString(1, NEW_PRICE_SNAPSHOT_CHANNEL);
    ps.setString(2, payload);
    try (ResultSet rs = ps.executeQuery()) {
        rs.next();
    }
}
```

```java
// infochat-provider/.../command/asset/AssetSnapshotReader.java:88-112
public @Nullable SnapshotResult readLatest(String asset, String subVerb, String vsCurrency) {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(
                 "SELECT ... FROM price_snapshot WHERE asset = ? AND sub_verb = ? "
                         + "AND vs_currency = ? ORDER BY captured_at DESC LIMIT 1")) {
        // ... reads the table directly on every /zcash, /monero invocation
```

A grep across the whole codebase confirms the only `LISTEN new_price_snapshot` / `pg_notify('new_price_snapshot', ...)` statements outside this producer are in test files (`PriceSnapshotStoreTest`, `AssetCommandsRoundtripIT`). There is no production listener bean in `infochat-provider` parallel to `NewPostListener` / `QuarantineReviewListener`, and `AssetSnapshotReader` holds no in-process cache.

**Why this is wrong / suboptimal / risky:**

`docs/spec/architecture.md` §Inter-service communication declares `new_price_snapshot` as one of three closed v1 channels and ties it to a concrete correctness mechanism:

> `new_price_snapshot` — fires on a successful Fetcher write to `price_snapshot`. ... Correctness mechanism: best-effort; the Provider's in-process cache is **flushed entirely on every Postgres reconnect** so a missed NOTIFY during a connection blip cannot serve a stale row past the reconnect.

`docs/spec/schema.md` §Operational repeats it: "NOTIFY `new_price_snapshot` is the latency optimization; the table read is the correctness guarantee." Both spec sentences presuppose a Provider-side in-process cache that the NOTIFY invalidates. That cache was never implemented — the Provider reads `price_snapshot` directly with `ORDER BY captured_at DESC LIMIT 1` on every command. As built, the entire channel is dead weight: every Collector snapshot write pays an extra `pg_notify` round-trip whose only subscribers are tests.

This is the SIMPLIFICATION case the architecture lens calls out explicitly ("a NOTIFY channel that could be a method call / a NOTIFY channel with no consumers"). It is also a contract-surface mismatch: the spec describes infrastructure (a cache + reconnect-flush) that does not exist, so a future reader sizing the asset path or reasoning about staleness will trust a guarantee the code does not provide. The producer's own javadoc ("no production consumer yet ... will subscribe") concedes the gap.

**Recommended fix:**

Pick one direction and make spec and code agree.

Option A (drop the channel — recommended for v1): delete the `pg_notify` emit from `PriceSnapshotStore.store` and the channel constant, and amend `architecture.md` §Inter-service communication to a two-channel closed list (`new_post`, `quarantine_review`), moving `new_price_snapshot` to a v2 candidate alongside the cache it would invalidate. The asset reader is already correct and cheap (single indexed `(asset, sub_verb, captured_at DESC)` lookup).

```java
// PriceSnapshotStore.store — remove the dead emit entirely:
if (inserted == 0) {
    return;
}
// (no pg_notify — the table read in AssetSnapshotReader is the
//  single source of truth; staleness is computed from captured_at)
```

Option B (build the cache): add a `NewPriceSnapshotListener` consumer in `infochat-provider` mirroring the other two listeners, give `AssetSnapshotReader` an in-process cache keyed by `(asset, sub_verb, vs_currency)`, invalidate on NOTIFY, and flush the whole cache on every reconnect per the spec sentence. Only worth it if `/zcash`-family call volume actually justifies a cache.

**Reasoning:**

The asset read path is a single-row indexed lookup served from Postgres' buffer cache; an in-process cache buys little and the staleness window is already bounded by `captured_at` arithmetic. Option A removes a moving part, a per-write NOTIFY, and a spec promise that overstates what the system does — strictly less to maintain and reason about. Either way the load-bearing property (spec matches code) is restored, which is the actual defect.

**Trade-offs:**

Option A requires a spec amendment (a `new_price_snapshot` channel removal is a closed-list edit, which `architecture.md` says is a spec amendment). That is the correct cost — the spec is wrong and should change. No runtime behavior is lost: the reader was never cache-backed.

**Alternative options:**

- **Option A** (drop the channel; recommended) — pros: removes dead code, a per-write `pg_notify`, and a false spec guarantee — cons: spec amendment to the closed channel list.
- **Option B** (build the cache + listener to honor the spec) — pros: no spec change — cons: adds a third long-lived LISTEN connection, a cache + reconnect-flush, and invalidation logic for a path that does not measurably need it; this is added complexity to satisfy prose rather than a measured need.

---

### F2. `quarantine_review` consumer treats an unknown `target_kind` discriminator as a `post` event

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-provider/.../outbox/QuarantineReviewListener.java:194-206 (and parsePayload, 276-289)
- **Surface:** NOTIFY / trust-boundary

**Current code:**

```java
// QuarantineReviewListener.java:194-206
private @Nullable RowState lookupRowState(String targetKind, UUID targetId) throws SQLException {
    String sql = "quarantine".equals(targetKind)
            ? "SELECT updated_at, status FROM quarantine_review_view WHERE id = ?"
            : "SELECT status_changed_at, status FROM post WHERE id = ?";
    // ...
}
```

```java
// QuarantineReviewListener.java:276-289 — parsePayload validates presence/shape
// of target_kind/target_id/new_status, but never that target_kind ∈ {"quarantine","post"}.
return new Payload(
        kindMatcher.group(1),               // any non-empty string passes
        UUID.fromString(idMatcher.group(1)),
        statusMatcher.group(1));
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/architecture.md` §Inter-service communication defines the payload as a **tagged shape** where `target_kind ∈ {'quarantine', 'post'}` and states the discriminator "is required so a single listener can route both event families without ambiguity." The architecture lens explicitly designates NOTIFY payload parsing as a system trust boundary where "tagged payloads [must be] parsed defensively."

The consumer's routing is a binary `"quarantine".equals(...)` ternary: any value that is not literally `"quarantine"` — including a typo, a corrupted payload, or a future-channel `target_kind` value the producer introduces before this listener is updated — is silently interpreted as a `post` event and run against `SELECT ... FROM post WHERE id = ?`. If the UUID happens to collide with a real `post.id`, the listener advances the `quarantine_review` cursor on, and potentially admin-notifies for, the wrong event family. The failure is silent: there is no "unknown discriminator" log or drop.

This is not currently exploitable — the sole producer (`QuarantineNotifyEmitter`) emits a closed two-value enum, both services share the DB, and NOTIFY cannot be injected by an external actor — which is why this is `low` and not higher. But the spec named this boundary as one that must be parsed defensively precisely so the discriminator contract is enforced at the seam rather than assumed from the current producer.

**Recommended fix:**

Reject an out-of-set discriminator at the wire boundary, the same way `parsePayload` already rejects a missing field:

```java
// in parsePayload, after extracting the three groups:
String kind = kindMatcher.group(1);
if (!"quarantine".equals(kind) && !"post".equals(kind)) {
    throw new IllegalArgumentException(
            "quarantine_review payload target_kind must be 'quarantine' or 'post'; got: " + kind);
}
return new Payload(kind, UUID.fromString(idMatcher.group(1)), statusMatcher.group(1));
```

`dispatch` already catches the `RuntimeException` from `parsePayload`, logs the raw payload, and drops the event — so an out-of-set discriminator would surface as a logged drop instead of a silent mis-route.

**Reasoning:**

This makes the `lookupRowState` ternary total over a validated input: by the time it runs, `targetKind` is provably one of the two legal values, so the `else` branch genuinely means `post` rather than "anything that isn't quarantine." It converts a silent mis-route into a visible, logged boundary rejection — which is exactly the defensive-parsing posture the spec asks for at this seam, and is permitted under §7 because NOTIFY deserialization is an enumerated system boundary, not internal-to-internal code.

**Trade-offs:**

None — the fix is strictly better. It adds one comparison at a boundary the spec already classifies as untrusted; it does not add defensive code inside the trust boundary.
