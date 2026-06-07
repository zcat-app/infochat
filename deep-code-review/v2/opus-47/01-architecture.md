# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-06 18:30
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — `infochat-provider` cross-cutting — the `new_price_snapshot` NOTIFY channel is fully spec'd as a Provider-side contract (cursor-only payload, cache-flush-on-reconnect correctness mechanism) but has **zero Provider-side LISTEN/consumer/cache**; the channel is dead in production.
- [high] MAINTAINABILITY-RULES-DRIFT — `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:143-188` — `quarantine_review` cursor advance and the throttled admin notification run on **separate JDBC connections**, violating the architecture.md §Catch-up same-transaction invariant; an admin-notify INSERT failure after a cursor advance permanently loses the PENDING / NEEDS_REVIEW notification.
- [high] SECURITY — `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:260-273` — `lookupEventTime` reads the event timestamp from the row pointed at by the NOTIFY payload **without verifying the row's status** matches the payload's `new_status`; a poisoned or stale-in-flight payload races the row's later state and advances the high-water mark past a still-PENDING row.
- [high] SIMPLIFICATION — `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:81` — the `assertIdentity(InboundMessage)` SPI method has zero production callers; every adapter resolves the identity inside its own protocol handler and stuffs the result into `InboundMessage.sender()` before delivery.
- [medium] MAINTAINABILITY-RULES-DRIFT — `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java:92-107` — the spec's `trustLevel` capability is split off into a separate per-instance accessor on `MessagingAdapter` but the record still carries seven *other* capability flags as a single immutable description; the resulting two-place capability surface (record vs accessor) creates a refactor hazard the spec text does not describe.
- [medium] MAINTAINABILITY-RULES-DRIFT — `infochat-core/src/main/java/app/zcat/infochat/core/audit/RedactionHook.java:43-44` — the SPI signature declares `redact(@NonNull AuditRow)` without a thrown exception, but the only production impl writes audit rows that may carry secrets-shaped values; a regex-watchdog timeout (spec §Secrets handling "fail-closed on regex timeout") has no in-band failure shape and must be smuggled out as an unchecked exception.

## Detail

### F1. `new_price_snapshot` channel has producer but no consumer; Provider-side cache it gates does not exist

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** NOTIFY

**Current code:**

Producer side, `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java:118-134`:

```java
// NOTIFY payload — literal key "source" per spec
// commands.md §Asset commands. The value is the sub_verb
// (e.g. "coingecko"); the key name reconciles spec wording
// ("(asset, source)") with architecture wording
// ("(asset, sub_verb)"). M1-055c deserialises by key
// "source".
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

Consumer side: a `grep -r "LISTEN new_price_snapshot"` / `grep -r "new_price_snapshot"` across `infochat-provider/src/main/java` finds **zero matches**. `infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java:84-113` opens a fresh connection on every `/zcash`/`/monero` invocation and runs `SELECT … FROM price_snapshot ORDER BY captured_at DESC LIMIT 1` directly — no cache, no listener.

The architecture spec, `docs/spec/architecture.md:43-50`, commits to a Provider-side cache that the channel flushes on reconnect:

> `new_price_snapshot` — fires on a successful Fetcher write to `price_snapshot`. Payload carries `(asset, source)` … Correctness mechanism: best-effort; the Provider's in-process cache is **flushed entirely on every Postgres reconnect** so a missed NOTIFY during a connection blip cannot serve a stale row past the reconnect.

**Why this is wrong / suboptimal / risky:**

This is a three-way drift between spec, producer, and consumer:

1. The producer faithfully emits the NOTIFY on every row insert.
2. The spec says the channel exists to invalidate a Provider-side cache, with cache-flush-on-Postgres-reconnect as the correctness mechanism.
3. The Provider has no listener and no cache. Every asset command re-queries the database. The NOTIFY is therefore *cost without benefit*: the Postgres backend serializes payload validation and queues the message to a non-existent listener.

Either the cache (and listener) needs to land, or the channel needs to be removed and the spec amended. Keeping the channel half-implemented locks in two problems:

- A future ticket that adds the cache will find no LISTEN backbone to attach to, will have to write the full listener-reconnector machinery from scratch (mirroring `NewPostListener` / `QuarantineReviewListener`), and will face the "but doesn't the channel already work?" question because the producer already commits to the wire shape.
- The architecture spec carries a falsifiable promise. Anyone reading it and grepping for the consumer (which is the workflow many contributors and reviewers use) finds nothing and either dismisses the spec or files yet another finding.

This is not a fresh discovery — `deep-code-review/v1/deepseek/01-architecture.md#F2` flagged the same gap. It is still open.

**Recommended fix:**

Pick one and execute it in a single ticket:

- **Option A (preferred — match the spec).** Land a Provider-side `PriceSnapshotListener` modeled on `NewPostListener` (dedicated `Connection`, virtual-thread `runLoop`, exponential reconnect backoff) plus a Caffeine cache in `AssetSnapshotReader` keyed by `(asset, sub_verb, vs_currency)`. On NOTIFY, invalidate the matching cache entries. On reconnect, flush the entire cache (the spec's `cache-flush-on-reconnect` rule). Skeleton:

```java
@Startup
@Priority(260)
@ApplicationScoped
public class PriceSnapshotListener {
    static final String CHANNEL = "new_price_snapshot";
    @Inject DataSource dataSource;
    @Inject AssetSnapshotCache cache;
    // ... same reconnect-resilient loop as NewPostListener,
    // but call cache.invalidate(asset, source) per notification
    // and cache.clearAll() on every (re)open of the LISTEN session.
}
```

- **Option B (remove the channel).** Drop `pg_notify('new_price_snapshot', …)` from `PriceSnapshotStore`. Amend `docs/spec/architecture.md` §Inter-service communication to remove the channel and replace the cache-flush-on-reconnect paragraph with "the table read is the correctness guarantee for asset snapshots; there is no cache." Justify the spec change with the observation that public-endpoint exchange APIs already rate-limit the Provider's read pattern to a tolerable cost.

**Reasoning:**

Option A is the spec-aligned fix and matches the existing pattern (two listener beans already exist; the third would be a copy of either). The cache cost is a few hundred lines of Caffeine wiring; the benefit is the per-snapshot read going from a DB roundtrip per invocation to an in-process map lookup, which matters on a Pi or laptop profile where the asset commands are hot.

Option B is cheaper but requires a spec amendment, and the spec's framing ("the Provider's in-process cache is flushed entirely on every Postgres reconnect so a missed NOTIFY during a connection blip cannot serve a stale row") implies the cache decision was deliberate.

**Trade-offs:**

- Option A: ~300 LoC across listener + cache + tests. Adds one more dedicated Postgres connection (one per LISTEN, mirroring the two existing listeners) — the connection budget needs to clear that on the laptop profile (`quarkus.datasource.jdbc.max-size=12`).
- Option B: cheaper to land; locks the deployment into per-invocation DB reads forever.

---

### F2. `quarantine_review` cursor advance and admin notification are not in the same transaction

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:143-188`
- **Surface:** NOTIFY

**Current code:**

```java
boolean handleEvent(@NonNull String targetKind, @NonNull UUID targetId,
                    @NonNull String newStatus, @NonNull Instant eventTime) throws SQLException {
    boolean advanced = providerStateDao.advanceCursor(
            CHANNEL, eventTime, targetKind, targetId.toString());

    if (advanced && isActionable(newStatus)) {
        fireAdminNotification(targetKind, targetId, newStatus);
    }
    return advanced;
}

// ...

private void fireAdminNotification(String targetKind, UUID targetId, String newStatus) {
    // ...
    try (Connection conn = dataSource.getConnection()) {
        boolean emitted;
        try (PreparedStatement ps = conn.prepareStatement(getUpsertSql())) {
            // ...
        }
        // ...
    } catch (SQLException e) {
        LOG.warnf(e, "ADMIN-NOTIFY key=%s error=%s message=persistence failed",
                ADMIN_NOTIFY_KEY, errorClass);
    }
}
```

`handleEvent` is not `@Transactional`. `advanceCursor` opens its own connection and commits autonomously. `fireAdminNotification` opens *another* connection and commits autonomously. The two writes are therefore in two distinct transactions.

For comparison, `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostHandler.java:97-98` does it correctly:

```java
@Transactional
public boolean handle(UUID postId, Instant readyAt) throws SQLException {
```

— and the architecture spec, `docs/spec/architecture.md:96-99`, commits to this rule:

> the high-water mark advances both fields **in the same DB transaction** as the side effect it triggers, making processing idempotent.

**Why this is wrong / suboptimal / risky:**

The split happens precisely on the `actionable` branch — the cursor advances *first*, then the admin notification is attempted, then the catch is silent on `SQLException`. The sequence is:

1. `advanceCursor` commits — cursor moves past the PENDING quarantine event.
2. `fireAdminNotification` opens a new connection. If the connection acquisition or UPSERT throws (DB blip, role grant revoked, `admin_notification_state` lock contention), the catch logs a `warn` and returns.
3. On the next process restart, the reconciler's catch-up reads the cursor (already past this row) and skips it.

Net effect: the operator is never alerted to a PENDING quarantine row or a NEEDS_REVIEW post. The throttled-admin-notifier's purpose — to make sure admins find out about content that needs review — is silently defeated.

The same-transaction invariant exists *exactly* to prevent this class of failure. Splitting cursor advance from side effect across transactions makes the cursor an at-most-once advance with a best-effort side effect; the spec promises an all-or-nothing advance with a durable side effect.

`new_post`'s `NewPostHandler.handle` runs the side effect (currently just a log line, but the comment explicitly says "T1-F adds real consumers … those side effects must live inside the same method") under one `@Transactional` boundary. `quarantine_review` should do the same.

**Recommended fix:**

Wrap the listener's dispatch in `@Transactional` and write the admin-notification UPSERT through the same connection the DAO uses:

```java
@Transactional
boolean handleEvent(@NonNull String targetKind, @NonNull UUID targetId,
                    @NonNull String newStatus, @NonNull Instant eventTime) throws SQLException {
    boolean advanced = providerStateDao.advanceCursor(
            CHANNEL, eventTime, targetKind, targetId.toString());

    if (advanced && isActionable(newStatus)) {
        fireAdminNotificationInTx(targetKind, targetId, newStatus);
    }
    return advanced;
}

private void fireAdminNotificationInTx(String targetKind, UUID targetId, String newStatus) throws SQLException {
    // No try-with-resources on Connection: the JTA-enlisted connection
    // is owned by the @Transactional boundary, not this method.
    Connection conn = dataSource.getConnection();
    OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    String errorClass = "quarantine_review." + newStatus.toLowerCase();
    try (PreparedStatement ps = conn.prepareStatement(getUpsertSql())) {
        // ... same UPSERT body, no inner catch — the @Transactional
        // boundary now rolls back the cursor advance if this fails.
    }
}
```

Two follow-on points worth carrying in the same ticket:

1. The `runLoop`'s `dispatch` swallows `SQLException` thrown by `handleEvent` (line 248-251). With the `@Transactional` change, that catch becomes the recovery point — log the failure, do NOT close the LISTEN connection, and let the next NOTIFY (or the post-reconnect reconciler) re-deliver the same event. The CAS predicate guarantees idempotency.
2. `lookupEventTime` (see F3) needs to be inside the same boundary so the timestamp does not drift between lookup and advance.

**Reasoning:**

The fix lifts `handleEvent` to the same correctness shape as `NewPostHandler.handle` — one method, one transaction, both side effects atomic. The spec's same-transaction rule is then satisfied for both channels uniformly. `NewPostHandler` already documents the pattern; this fix is mechanically the same.

The catch in `dispatch` must change in lockstep because `@Transactional` propagates the rollback as a `RuntimeException` to the caller; the existing `LOG.errorf` for `SQLException` is no longer reached for the wrapped failure, and we need the loop not to die.

**Trade-offs:**

None — the fix is strictly better. The current code carries a silent-loss failure mode that the test suite cannot catch (the cursor advance and the admin-notify failure are observable only through DB inspection after the listener crashes mid-dispatch).

---

### F3. `quarantine_review` payload's `new_status` is trusted without re-reading the row's current status

- **Category:** SECURITY
- **Severity:** high
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:228-273`
- **Surface:** NOTIFY (trust-boundary)

**Current code:**

```java
private void dispatch(PGNotification n) {
    if (!CHANNEL.equals(n.getName())) return;
    Payload payload;
    try {
        payload = parsePayload(n.getParameter());
    } catch (RuntimeException e) {
        LOG.errorf(e, "QuarantineReviewListener: unparseable payload (dropped): %s",
                n.getParameter());
        return;
    }
    try {
        // Look up event timestamp from DB
        Instant eventTime = lookupEventTime(payload.targetKind(), payload.targetId());
        if (eventTime == null) {
            LOG.warnf("QuarantineReviewListener: no matching row for %s/%s (dropped)",
                    payload.targetKind(), payload.targetId());
            return;
        }
        handleEvent(payload.targetKind(), payload.targetId(),
                payload.newStatus(), eventTime);
    } catch (SQLException e) {
        // ...
    }
}

private @Nullable Instant lookupEventTime(String targetKind, UUID targetId) throws SQLException {
    String sql = "quarantine".equals(targetKind)
            ? "SELECT updated_at FROM quarantine_review_view WHERE id = ?"
            : "SELECT status_changed_at FROM post WHERE id = ?";
    // ... selects timestamp only, never the current status
}
```

And the actionable predicate `handleEvent` consumes:

```java
private static boolean isActionable(String status) {
    return "PENDING".equals(status) || "NEEDS_REVIEW".equals(status);
}
```

**Why this is wrong / suboptimal / risky:**

The listener treats `payload.newStatus()` as ground truth for the actionable check. But:

- The payload arrives via Postgres NOTIFY, which is the *trust boundary* between Collector and Provider (the architecture spec §Inter-service communication makes the two services communicate only through the DB). Even though both services run inside the same operator's trust envelope, a buggy or compromised Collector emitting a `('quarantine', <random-id>, 'PENDING')` payload triggers an admin notification with arbitrary content. The Provider has no defense.
- More realistically, a benign reordering race: emit a `PENDING` NOTIFY at t0, a `BENIGN_CLOSED` follow-up at t1, both queued by Postgres. The listener processes the `PENDING` notification, fires the admin notification (still actionable per the payload), then processes the `BENIGN_CLOSED`. The admin is paged about a row that is already closed by the time they look at it.
- A poisoned payload referencing a non-existent UUID is silently dropped (line 241-244), but a payload referencing a *real* UUID with a *wrong* `new_status` is happily processed.

The DB round-trip already happens (`lookupEventTime`), so reading the current `status` next to the `updated_at` costs nothing extra and closes the boundary.

**Recommended fix:**

Re-read the row's current `status` next to the timestamp and use *that* as the actionable signal, not the payload's `new_status`. Drop `new_status` from the payload contract entirely if you prefer; the spec calls it "tagged" but the architecture text (`architecture.md:60-64`) confirms it is the discriminator + payload key, not the source of truth:

```java
private @Nullable EventRow lookupEvent(String targetKind, UUID targetId) throws SQLException {
    String sql = "quarantine".equals(targetKind)
            ? "SELECT updated_at, status FROM quarantine_review_view WHERE id = ?"
            : "SELECT status_changed_at, status FROM post WHERE id = ?";
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setObject(1, targetId);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            Timestamp ts = rs.getTimestamp(1);
            String currentStatus = rs.getString(2);
            return ts == null ? null : new EventRow(ts.toInstant(), currentStatus);
        }
    }
}

record EventRow(Instant eventTime, String currentStatus) {}
```

Then in `handleEvent`, use `eventRow.currentStatus()` (not the payload's `newStatus`) to drive `isActionable`. The cursor still advances with the payload's `(target_kind, target_id, event_time)` so the high-water mark stays accurate.

The fix also folds into F2: with `handleEvent` made `@Transactional`, the `lookupEvent` SELECT and the cursor advance see a consistent snapshot of the row, so the race where a later notify "passes" the earlier one in the same scan cannot fire a stale actionable signal.

**Reasoning:**

The Provider's responsibility at this trust boundary is to validate that the payload it received matches the database state it is acting on. Reading the timestamp without the status is asymmetric: the timestamp drives correctness (cursor monotonicity), the status drives the user-visible side effect (admin page), and the side effect is currently trusting the wire.

The DB is the system of record. Whenever NOTIFY payload is used as more than a wake-up signal, the listener should refresh against the row.

**Trade-offs:**

None — the change is one extra column in the existing `lookupEvent` SELECT.

---

### F4. `MessagingAdapter.assertIdentity` is dead SPI surface

- **Category:** SIMPLIFICATION
- **Severity:** high
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:72-81`
- **Surface:** SPI

**Current code:**

```java
/**
 * Strongly-typed identity assertion for one inbound message. The
 * returned {@link Identity}'s {@code contactId} is the
 * authorization-bearing identifier (decision D10) — implementations
 * MUST NOT trust {@code displayName}.
 *
 * @param msg the inbound message; never null.
 * @return the asserted sender identity; never null.
 */
Identity assertIdentity(@NonNull InboundMessage msg);
```

`grep -r ".assertIdentity(" infochat-*/src/main` returns zero hits across all production code. The only call sites are inside test files. Every adapter implementation (`InMemoryAdapter`, `SimpleXAdapter` via `SimpleXMessageCodec.java:372`, `SignalAdapter` via `SignalJsonRpcClient.java:534`, `SignalGroupHandler.java:160`, `SimpleXGroupHandler.java:77`) builds an `Identity` inline before constructing the `InboundMessage` and stuffs it into `InboundMessage.sender`. Provider's `InboundRouter.onMessage` then reads `msg.sender().contactId()` directly.

**Why this is wrong / suboptimal / risky:**

The SPI declares a method whose contract — "given a wire message, assert the sender's identity" — is already satisfied implicitly by the construction of `InboundMessage`. The method exists in three places:

1. The SPI interface (here).
2. Each adapter implementation, which has to keep a working override because the interface is non-default.
3. The design notes (`docs/design/06-messaging.md:90-91`) re-document it.

Every reader of `MessagingAdapter` has to spend cognitive cycles on "wait, but where is `assertIdentity` called from?" and walk to the test file to discover it is never called. Anyone landing a new adapter has to implement a method that nobody invokes.

This is the §"Simplify aggressively" failure mode the CLAUDE.md style guide names explicitly: an SPI method used by zero production callers is the SPI version of "an unnecessary class."

Worth noting the SPI's own Javadoc carries an entire `<p>Group-membership probing … speculative SPI surface … would violate the engineering rules' no defensive code for impossible scenarios corollary against speculative API</p>` block (`MessagingAdapter.java:26-30`). The team has already articulated the principle; `assertIdentity` is an existing instance of the same anti-pattern that the doc paragraph forbids for new methods.

**Recommended fix:**

Remove the method. Drop it from the interface; remove the (now-unused) override from every adapter. The spec text in `messaging.md:30-38` describes the *responsibility* ("the adapter resolves identity from the wire message"); the SPI method is one possible *shape* for that responsibility but not the one the implementation actually uses. Amend `messaging.md` to remove the bullet's framing as a method and instead say something like "Each adapter MUST resolve the sender's stable, cryptographically anchored contact id from the wire payload BEFORE delivering the `InboundMessage` to the registered handler; the `Identity` value carried in `InboundMessage.sender` is the authorization-bearing assertion."

```java
public interface MessagingAdapter {

    String name();

    CapabilityFlags capabilities();

    AdapterTrustLevel trustLevel();

    // assertIdentity removed — the adapter populates Identity into
    // InboundMessage.sender before invoking the inbound handler.

    MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException;
    // ...
}
```

**Reasoning:**

The method represents a responsibility the adapter *must* discharge but does not represent the *shape* through which Provider consumes the result. Conflating responsibility with SPI method is the source of the cruft.

Removing it makes the SPI surface match the actual call graph: `name`, `capabilities`, `trustLevel`, `send`, `update`, `finalizeMessage`, `setTyping`, `setInboundHandler`, `setMembershipEventHandler`, `start`, `stop` — every one of which has production callers.

**Trade-offs:**

None. The interface shrinks by one method; the three implementations each lose ~15 LoC; the design note loses one bullet. The test files that currently call `assertIdentity` are exercising the construction of `Identity`, which is straightforwardly tested by asserting on the `InboundMessage.sender` value the adapter delivers.

**Alternative options:**

- **Option A (the recommended fix above).**
- **Option B** — keep the method but mark it `default` and have the default throw `UnsupportedOperationException`. Worse: it preserves the speculative surface while adding a default-method trap for anyone who calls it expecting it to work.

---

### F5. Capability surface is split between the immutable record and the `trustLevel()` accessor without a documented invariant

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java:17-25` and `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:60-70`
- **Surface:** capability-flag

**Current code:**

`CapabilityFlags` Javadoc:

```java
 * <p>Trust level is intentionally NOT a capability flag — it is an
 * adapter-instance property accessed via the
 * {@link AdapterTrustLevel}-returning method on
 * {@link MessagingAdapter}, so a single adapter implementation
 * (notably the in-memory test double) can ship two trust postures
 * from the same class without two parallel capability records. The
 * M1-007c nested {@code TrustLevel} enum was removed in this
 * evolution; see {@link AdapterTrustLevel}.</p>
```

And on the SPI side:

```java
AdapterTrustLevel trustLevel();
// ...
CapabilityFlags capabilities();
```

Gate 6 in `AdapterRegistry` reads `adapter.trustLevel()`; gates 3 and 4 read `adapter.capabilities().supportsMarkdownLinks()` and `.supportsMentionByContactId()`.

**Why this is wrong / suboptimal / risky:**

The capability surface is now in two places:

1. An immutable record (`CapabilityFlags`) carrying 13 fields.
2. An accessor on the SPI (`trustLevel()`) returning one enum.

The reason given — "a single adapter implementation can ship two trust postures from the same class" — is satisfied for the InMemory test double, but no production adapter currently uses it (Signal and SimpleX hard-code `HIGH`). The two-place surface creates a subtle hazard: if a future capability gains the same "varies per instance" property, where does it go? The record? An accessor? The spec is silent. Without a clear rule, the next reader has to remember the M1-007c history.

The spec's own framing in `messaging.md:102-104` lists `trustLevel` as a capability flag at the same level as `supportsCodeFormatting`:

> `trustLevel` — `HIGH` for cryptographically anchored ids, `LOW` otherwise.

So the spec treats `trustLevel` as a capability; the implementation treats it as separate. That is spec-drift.

**Recommended fix:**

Pick one location and stick to it:

- **Option A (preferred — match spec).** Add `trustLevel` back to `CapabilityFlags` as a 14th field. Make `MessagingAdapter#trustLevel()` a default method that reads from `capabilities().trustLevel()`. The InMemory test double's two trust postures become two `CapabilityFlags` constants — `INMEMORY_LOW_CAPS` and `INMEMORY_HIGH_CAPS` — selected by the constructor parameter. Half a dozen lines per adapter.

```java
public record CapabilityFlags(
        AdapterTrustLevel trustLevel,
        boolean supportsMentionByContactId,
        // ... rest unchanged
) { }

// In MessagingAdapter:
default AdapterTrustLevel trustLevel() {
    return capabilities().trustLevel();
}
```

- **Option B.** Amend the spec to acknowledge the split, naming the rule under which a capability lives on the SPI vs the record (e.g., "instance-mutable capabilities live on the SPI; class-mutable capabilities live in the record"). Adds a paragraph to `messaging.md` §Capability flags.

**Reasoning:**

The spec's enumeration says `trustLevel` is a capability flag. The code disagrees. Either the code should match (Option A) or the spec should explain why it doesn't (Option B). Today the discrepancy is unmarked and the next maintainer will guess.

Option A is cleaner — capability discovery is one method call, not two — and the InMemory case is preserved without API change.

**Trade-offs:**

Option A churns every adapter constructor (the existing 13-positional constructor becomes 14-positional). Migration is mechanical.
Option B is a spec edit only but locks in the split forever.

---

### F6. `RedactionHook.redact` cannot signal fail-closed on regex timeout

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-core/src/main/java/app/zcat/infochat/core/audit/RedactionHook.java:30-44`
- **Surface:** SPI

**Current code:**

```java
public interface RedactionHook {

    /**
     * Apply redaction to {@code row} and return the redacted form.
     * Implementations are stateless — every call processes one row
     * independently; no per-call scratch state may persist across
     * calls.
     *
     * @param row the row built by the writer, BEFORE redaction.
     * @return the redacted row that reaches the audit_log INSERT.
     *         An impl that does not need to redact MAY return the
     *         input unchanged.
     */
    AuditRow redact(@NonNull AuditRow row);
```

The spec `security.md` §Secrets handling says:

> The redactor is fail-closed on regex timeout (the same `java.util.regex`-plus-watchdog discipline as Stage 1, see §Ingest pipeline): a timed-out match treats the whole field as redacted rather than emitting it raw.

**Why this is wrong / suboptimal / risky:**

The SPI signature has no exception declaration and no return-value variant that means "the regex watchdog fired, I am declining to emit a row." The only options the implementation has:

1. Catch the watchdog signal internally and return a fully-redacted `AuditRow` with the suspect field zapped. This matches the spec's "treat the whole field as redacted." Sustainable.
2. Throw an unchecked exception. The audit writer has no contract telling it what unchecked exceptions to expect, and an `IllegalStateException` from inside an annotation-processed SPI is a quiet leak.

The cross-module hazard: the spec promises fail-closed; the SPI hides the fail-closed semantics inside option 1's per-impl interpretation. A future replacement implementation could read the SPI Javadoc and conclude "regex timeouts don't happen here, this is internal code," skip the watchdog, and silently weaken the audit-log redaction.

**Recommended fix:**

Document the fail-closed contract on the SPI directly, naming it as part of the return-value semantics. The implementation already does the right thing; the SPI just needs to commit to it:

```java
public interface RedactionHook {

    /**
     * Apply redaction to {@code row} and return the redacted form.
     *
     * <p><b>Fail-closed on regex timeout.</b> Per
     * {@code docs/spec/security.md} §Secrets handling, an
     * implementation that runs the closed API-key regex catalogue
     * MUST treat a watchdog timeout on any field as a full
     * redaction of that field — return an {@link AuditRow} with
     * the affected field replaced by the documented sentinel.
     * An impl MUST NOT propagate the watchdog timeout as an
     * exception; the audit writer is downstream of the timeout and
     * has no recovery path. Fail-closed redaction is the contract.
     *
     * <p>Stateless: every call processes one row independently.
     */
    AuditRow redact(@NonNull AuditRow row);
}
```

If the team wants to make the contract enforceable at compile time, replace `AuditRow` with a `RedactionResult` sealed type carrying `RedactedRow(AuditRow)` and `FullyRedacted(AuditRow, RedactionTimeoutReason)` variants. That is a bigger change; the Javadoc fix above is the minimum that closes the spec-drift gap.

**Reasoning:**

The spec commits to "fail-closed on regex timeout" as a structural property. The SPI is the contract surface for that commitment. Leaving it implicit means the next implementor has to discover the rule by reading either the spec or the existing implementation; neither is a reliable disco-very path for code review.

The fix is the minimal possible: one paragraph of Javadoc that names the contract.

**Trade-offs:**

None for the Javadoc-only fix.
The sealed-type variant would touch every call site that consumes `redact`'s return value — currently `AuditLogWriter` — but would surface the timeout case at compile time rather than via documentation.

## Synthesizer-relevant observations

- Three SPIs were checked for orphan-impl status (`Fetcher`, `StreamSource`, `AssetDataSource`, `TranslationProvider`, `EmbeddingProvider`, `LlmProvider`, `RedactionHook`, `CommandHandler`). All have at least one production implementation; only `MessagingAdapter.assertIdentity` is an orphan *method* (F4).
- The 6-module DAG declared in `docs/spec/architecture.md` is upheld by the actual `pom.xml` dependencies. `infochat-collector` does NOT depend on `infochat-messaging-adapter` (confirmed in `infochat-collector/pom.xml`), per the design note "Collector intentionally does NOT depend on this module per docs/design/01-architecture.md §1.2."
- Migrations V1–V38 are tightly coupled to spec text — every migration carries a top-of-file paragraph naming the spec section it implements, and the recent V32 / V37 migrations show the team is correctly using `CREATE OR REPLACE` to evolve stored procedures without breaking existing GRANTs.
- The capability-flag startup gates in `AdapterRegistry` (gates 1–7) correctly enforce the spec invariants: `supportsMarkdownLinks=false` (gate 3), `supportsMentionByContactId` + group-SPI consistency (gate 4), production-exclusion of `inmemory` (gate 5), LOW-trust opt-in (gate 6), bootstrap admin union non-empty (gate 7). The pattern is the right shape — one place, one ordered set of checks, one fail-fast.
- The `audit_log` append-only invariant is enforced two ways (DB role grant + application-layer convention) per spec §Invariant 10; this is the strongest enforcement shape in the codebase and is worth preserving as the pattern other invariants emulate.
