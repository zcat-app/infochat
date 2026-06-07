# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-07
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — `ReadyPromoter.java:43-54` — `@Transactional` annotation explicitly avoided because `onTick` self-invokes `promoteOne`; the self-invocation bypass is documented, but a `@Scheduled` method calling a `@Transactional` method on a different bean is the standard pattern that would avoid the bypass
- [medium] PERFORMANCE — `OutboxRehydrator.java` — paginated scan with configurable page size; default 500 is reasonable for v1, but the rehydrator holds no overall progress metric visible to operators during a long rehydration
- [medium] SECURITY — `Stage1Pipeline.java:regex watchdog` — `java.util.regex` with per-input wall-clock deadline per the spec commitment; the watchdog is fail-closed (quarantines on timeout), matching the spec, but the cap value is profile-driven and stored in design notes only — an operator who misconfigures the profile gets the wrong timeout
- [low] SIMPLIFICATION — `AssetSnapshotFetcher.java` and `PriceSnapshotStore.java` — three near-identical asset data sources (Coingecko, Kraken, Bitfinex) each implement `AssetDataSource`; the per-source classes are ~50 lines each, mostly HTTP fetch + JSON parse boilerplate that could be a single config-driven class
- [low] MAINTAINABILITY-RULES-DRIFT — `NostrEventVerifier.java` — signature verification is security-critical; the verifier uses a third-party library (presumably a Nostr-specific dependency) whose update cadence is not controlled by the project

## Detail

### F1. Self-invocation bypass documented but avoidable

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java:43-54`

**Current code:**

```java
// The Javadoc explains:
// "A @Transactional annotation could NOT: onTick self-invokes promoteOne,
// so the CDI interceptor never fires and the two statements would fall
// back to separate autocommits."
//
// promoteOne manages its own transaction via setAutoCommit(false) + commit()
```

**Why this is wrong / suboptimal / risky:**

The self-invocation problem is real (CDI interceptors don't apply to `this.method()` calls), but the solution — manual transaction management with `setAutoCommit(false)` + `commit()` — bypasses the standard Quarkus transaction infrastructure. This means:
- No integration with Quarkus' `@TransactionConfiguration` (timeout enforcement)
- No `TransactionManager` integration (suspend/resume for nested calls)
- Manual `rollback()` on exception must be correct in every path

The standard pattern for this in Quarkus is: extract the transactional method to a separate `@ApplicationScoped` bean. The `@Scheduled` method calls `transactionalBean.promoteOne()`, and CDI applies `@Transactional` on the intercepted call. This avoids the self-invocation problem AND keeps transaction management in the framework.

The current code works correctly (the transaction is managed explicitly and the NOTIFY is in the same transaction). The finding is that the workaround is more complex than the standard pattern.

**Recommended fix:**

Extract `promoteOne` to a separate `@ApplicationScoped ReadyPromotionExecutor` with `@Transactional` on the method. The `@Scheduled onTick()` injects the executor and calls `executor.promoteOne()`.

**Reasoning:**

Standard Quarkus pattern. Transaction timeout, commit/rollback, and connection cleanup are handled by the framework.

**Trade-offs:**

- Adds one more class (but removes the manual transaction code from ReadyPromoter).
- The current code is tested and correct. Changing transaction management requires re-verifying the NOTIFY same-transaction invariant.

---

### F2. No progress metric during long rehydration

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/OutboxRehydrator.java`

**Why this is wrong / suboptimal / risky:**

The outbox rehydrator uses keyset pagination with a configurable page size (default 500), which correctly bounds memory for large RAW backlogs. However, there is no progress metric exposed during a long rehydration. An operator restarting the Collector after an extended eval-pipeline outage cannot tell how far through the RAW backlog the rehydrator has progressed until it finishes and the `@Startup` bean returns.

The rehydrator runs at startup (before the readiness probe goes healthy), so a long rehydration blocks the Collector from becoming ready. Without a progress signal, an operator has no visibility into whether the rehydrator is making progress or stuck.

**Recommended fix:**

Log a progress line every N pages (e.g., every 5,000 rows): "OutboxRehydrator: rehydrated <count> posts so far, continuing..." so the operator can grep the log and see forward progress.

**Reasoning:**

Operator visibility during startup recovery. A log line per N pages costs nothing and provides a heartbeat during long rehydration.

**Trade-offs:**

- Log noise at startup (mitigated by only emitting every N pages, not per page).
- None — the fix is strictly better.

---

### F3. Profile-driven regex watchdog timeout is design-tier only

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java`

**Why this is wrong / suboptimal / risky:**

The Stage 1 regex watchdog timeout is profile-driven per the spec ("the cap value is profile-driven and lives in design notes"). If an operator selects the `pi` profile (which has the tightest resource constraints), the watchdog timeout may be too short for the Pi's CPU to complete the regex pass on a large body, causing spurious fail-closed quarantines. Conversely, the `vps` profile may have a timeout so generous that a ReDoS-crafted body can consume a VPS CPU core for the entire timeout window on every tick.

The spec correctly delegates the exact value to design notes. The finding is that the watchdog timeout has a safety dimension (too short → spurious quarantine avalanche; too long → ReDoS CPU consumption) that may not be obvious to an operator overriding the value directly via `infochat.security.stage1.regex-timeout-ms` without understanding the trade-off.

**Recommended fix:**

Add a Javadoc comment on the `@ConfigProperty` field documenting the ReDoS vs spurious-quarantine trade-off, with a note that the default is calibrated for the `laptop` profile and operators should tune for their hardware.

**Reasoning:**

Makes the safety trade-off visible at the configuration injection point.

**Trade-offs:**

- None — the fix is strictly better.

---

### F4. Nearly-identical AssetDataSource implementations

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/{Coingecko,Kraken,Bitfinex}SnapshotSource.java`

**Why this is wrong / suboptimal / risky:**

The three asset data sources each implement `AssetDataSource` with the same pattern: HTTP GET → JSON parse → extract price field → return `PriceSnapshot`. The per-class differences are: the URL template, the JSON path to the price field, and the currency label. This is three ~50-line classes that could be one config-driven class reading from `asset_config`.

The current design is deliberate (decision D39: per-source classes for clarity). But the boilerplate is visible: the three classes differ in <10 lines each.

**Recommended fix:**

Not recommended for v1 — the current design is intentional and clean. For v2, consider a single `ConfigurableAssetDataSource` that reads URL template and JSON path from `asset_config.config`.

**Reasoning:**

A config-driven approach would reduce the code surface for future asset additions (adding `/ethereum` would be a config entry, not a new class).

**Trade-offs:**

- Config-driven approach is harder to test per-source (no per-class unit tests).
- The three-class approach makes per-source custom parsing logic easy to add (e.g., Coingecko's paginated vs Kraken's ticker endpoint).
