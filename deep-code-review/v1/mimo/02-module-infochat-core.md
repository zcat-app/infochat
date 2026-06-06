# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-02 00:05
**Reviewer:** senior-developer (mimo)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — NormalizedPost.java:41 — `sourceId` field is `long` (a per-startup dispatch key) but javadoc claims it is "`source.id`"; Fetcher and StreamSource SPIs share the same misleading contract
- [medium] MAINTAINABILITY-RULES-DRIFT — V17__price_snapshot.sql — `price_snapshot` schema diverges from spec on PK shape, column names, and NOT NULL constraints; design doc documents the divergence but spec wins per project rules
- [low] SIMPLIFICATION — V21__quarantine_admin.sql:68-69 — `approve_quarantine` audit INSERT omits `actor_contact_id` and `actor_adapter` columns that the spec's audit_log contract expects to be denormalized at write time

## Detail

### F1. NormalizedPost.sourceId javadoc contradicts the runtime value

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java:17-41

**Current code:**

```java
/**
 * <ul>
 *   <li>{@code sourceId} — the {@code source.id} this post belongs to.</li>
 * </ul>
 */
public record NormalizedPost(
        long sourceId,
        // ...
) {
}
```

The same `long sourceId` parameter appears in `Fetcher.java:33` and `StreamSource.java:37`. The `FetchScheduler` in the collector module (FetchScheduler.java:437-444) documents that this value is actually a "monotonically-assigned per-startup token" (a dispatch key), NOT the `source.id` UUID from the database:

```java
/**
 * One enumerated source row. The {@code dispatchKey} is a
 * monotonically-assigned per-startup token passed to the Fetcher
 * SPI's {@code long sourceId} parameter; it is NOT the
 * {@code source.id} UUID and is opaque to the Fetcher.
 */
public record SourceRow(@NonNull UUID uuid, @NonNull String identifier, long dispatchKey,
                           @NonNull String kind) {
}
```

**Why this is wrong / suboptimal / risky:**

The `NormalizedPost` javadoc explicitly says `sourceId` is "the `source.id` this post belongs to," implying it matches the `source.id` UUID column. In reality the FetchScheduler passes a per-startup dispatch key (a `long`). Any Fetcher or StreamSource implementor reading the javadoc would assume they can correlate this value back to a database row; they cannot. The type itself (`long` vs `UUID`) is a second signal that something is off, but the javadoc actively misleads.

This violates the §7a method-parameter-contracts rule spirit: the contract must be explicit so callers know what they are receiving. The current javadoc is worse than no contract — it states a falsehood.

**Recommended fix:**

Update the javadoc on `NormalizedPost`, `Fetcher.fetch()`, and `StreamSource.start()` to accurately describe the value:

```java
/**
 * <ul>
 *   <li>{@code sourceId} — an opaque, per-startup dispatch key assigned by
 *       the scheduler. NOT the {@code source.id} UUID; Fetcher / StreamSource
 *       implementations must not attempt to resolve it against the database.
 *       The scheduler uses this key to route the returned
 *       {@link NormalizedPost} back to the correct source row.</li>
 * </ul>
 */
```

**Reasoning:**

The javadoc is the contract surface for implementors. A falsehood here will cause wasted debugging time when an implementor tries to use `sourceId` as a database lookup key and gets wrong results or type errors.

**Trade-offs:**

None — the fix is strictly better. The javadoc becomes accurate.

---

### F2. price_snapshot schema diverges from spec

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql:35-52

**Current code (V17):**

```sql
CREATE TABLE price_snapshot (
    id              BIGSERIAL,
    asset           TEXT NOT NULL,
    sub_verb        TEXT NOT NULL,
    vs_currency     TEXT NOT NULL,
    price           NUMERIC(24,12) NOT NULL,
    volume_24h      NUMERIC(28,8),
    high_24h        NUMERIC(24,12),
    low_24h         NUMERIC(24,12),
    change_1h_pct   NUMERIC(8,4),
    change_24h_pct  NUMERIC(8,4),
    change_7d_pct   NUMERIC(8,4),
    captured_at     TIMESTAMPTZ NOT NULL,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_url      TEXT,
    raw_payload     JSONB,
    PRIMARY KEY (id, captured_at)
) PARTITION BY RANGE (captured_at);
```

**Spec (docs/spec/schema.md §Operational — Price snapshot):**

```
Columns: asset (FK to asset_config), sub_verb, captured_at, price,
currency, source_url, raw_payload (JSONB). INSERT-only; no updates.
PRIMARY KEY (asset, sub_verb, captured_at)
```

**Why this is wrong / suboptimal / risky:**

Three divergences between spec and implementation:

1. **PK shape.** Spec: `(asset, sub_verb, captured_at)`. Implementation: `(id, captured_at)` with a BIGSERIAL surrogate. The spec PK enforces dedup at the schema layer (two rows for the same `(asset, sub_verb, captured_at)` collide); the surrogate PK requires a separate UNIQUE constraint to enforce the same invariant, and none is present.

2. **Column name.** Spec: `currency`. Implementation: `vs_currency`.

3. **Column nullability.** Spec: `source_url` appears in the column list without a nullable qualifier, and the spec says "Every reply names its data source and includes the source URL bare" — implying it is always present. Implementation: `source_url TEXT` (nullable by default).

4. **Extra columns.** Implementation adds `volume_24h`, `high_24h`, `low_24h`, `change_1h_pct`, `change_24h_pct`, `change_7d_pct`, `fetched_at` — none in the spec. Design-tier additions are expected, but the PK and column-name changes are structural.

The design doc (docs/design/02-schema.md §2.7.2) documents some of these choices but the project rules state "Spec wins over design notes on conflict."

**Recommended fix:**

Add a UNIQUE constraint to restore the spec's dedup invariant:

```sql
CREATE UNIQUE INDEX uq_price_snapshot_dedup
    ON price_snapshot (asset, sub_verb, captured_at);
```

The column naming (`vs_currency` vs `currency`) and extra columns are design-tier choices that the spec leaves open. The missing NOT NULL on `source_url` should be addressed if the spec's commitment is that every snapshot carries a source URL.

**Reasoning:**

Without the UNIQUE constraint, two concurrent fetchers for the same `(asset, sub_verb)` can insert duplicate snapshots for the same `captured_at` instant. The spec's PK shape was the dedup mechanism; the surrogate PK removed it without a replacement.

**Trade-offs:**

The extra UNIQUE index adds a small write-path cost (one additional index maintenance per INSERT). This is negligible for the low-volume asset-fetcher path.

---

### F3. approve_quarantine audit INSERT omits denormalized actor columns

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-core/src/main/resources/db/migration/V21__quarantine_admin.sql:68-69 (and the V25 redeclaration at V25__quarantine_procedure_remediation.sql:58-59)

**Current code:**

```sql
INSERT INTO audit_log (actor_user_id, action, target_kind, target_id, details_json)
VALUES (p_actor_id, 'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
        jsonb_build_object('post_id', v_post_id::TEXT));
```

The same pattern appears in `reject_quarantine` (V21:112, V25:100-101).

**Why this is wrong / suboptimal / risky:**

The spec's audit_log contract (schema.md §Audit log) specifies `actor_contact_id` (denormalized at write time) and `actor_adapter` as columns. The `delete_preban_user` procedure (V24) also omits these columns, but V24's comment explicitly justifies this: "actor_contact_id and actor_adapter are omitted — derivable from actor_user_id by any reader that needs them, avoiding a second SELECT round-trip." The quarantine procedures lack this justification comment.

This is a minor consistency issue — the stored procedures all omit the denormalized columns, which is a defensible choice (the `actor_user_id` FK is sufficient for lookup). But the inconsistency in documentation (V24 justifies the omission; V21/V25 do not) creates a "why is this different?" question for future reviewers.

**Recommended fix:**

Add a comment to V21's and V25's quarantine procedures matching V24's justification:

```sql
-- actor_contact_id and actor_adapter are omitted — derivable from
-- actor_user_id by any reader that needs them, matching
-- delete_preban_user's pattern (V24).
INSERT INTO audit_log (actor_user_id, action, target_kind, target_id, details_json)
```

**Reasoning:**

The three SECURITY DEFINER procedures should document their audit-row shape consistently. The omission is defensible but the lack of a why-comment makes it look accidental.

**Trade-offs:**

None — a comment-only change.
