# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-07
**Reviewer:** senior-developer (mimo-v2.5-pro)

## Headline findings

One medium finding: the `NormalizedPost` record's `sourceId` field contract contradicts the `Fetcher` and `StreamSource` SPI contracts and the spec's UID derivation formula. The module is otherwise well-structured with thorough spec alignment, comprehensive test coverage (38 migration files tested by 20+ schema tests, redaction parity test between Java and SQL engines, concurrent revocation race test, liveness probe test, notifier race test), and clean separation of concerns.

## Detail

### F1: NormalizedPost.sourceId field contract contradicts Fetcher/StreamSource SPIs and spec UID derivation

**Category:** MAINTAINABILITY-RULES-DRIFT
**Severity:** medium
**Location:** `infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java`, lines 17-21

**Current code:**
```java
 *   <li>{@code sourceId} — the per-tick opaque dispatch token the
 *       scheduler handed the Fetcher SPI for this fetch; it is NOT
 *       the {@code source.id} UUID, is not stable across ticks, and
 *       must not be used to key any persistent or cross-tick
 *       state.</li>
```

**Why wrong:** Three sources within the same module disagree:

1. `Fetcher.fetch(long sourceId, ...)` documents: `@param sourceId the source.id this fetch is on behalf of; stamped onto every returned post`.
2. `StreamSource.start(long sourceId, ...)` documents: `@param sourceId the source.id this stream is on behalf of; stamped onto every delivered post`.
3. The spec (`docs/spec/schema.md` UID derivation) defines the UID as `sha256(source_id || '|' || upstream_identifier)` where `source_id` is the source row's primary key -- a stable UUID, not an opaque per-tick token.

All three say `sourceId` IS the `source.id` database row identifier, stable across ticks and central to the dedup key. `NormalizedPost` says it is NOT that, is not stable across ticks, and must not be used for persistent state. This is a direct contradiction.

The `NormalizedPost` contract would make the spec's UID derivation impossible: if `sourceId` changed every tick, the same upstream post would produce a different UID on every fetch, breaking dedup entirely.

**Recommended fix:** Update `NormalizedPost`'s `sourceId` field Javadoc to align with the Fetcher/StreamSource SPIs and the spec:
```java
 *   <li>{@code sourceId} — the {@code source.id} UUID this post
 *       originated from. Stable across ticks; used for UID
 *       derivation (sha256(source_id || '|' || upstream_identifier))
 *       and for the post.source_id FK.</li>
```

**Reasoning:** The SPIs and spec are consistent with each other and with the actual behavior required for dedup. Only `NormalizedPost`'s documentation diverges -- likely inherited from an earlier design iteration where a dispatch-token model was considered. The record itself is a plain `long sourceId` that works correctly either way; the risk is a future Fetcher/StreamSource implementor reading only the `NormalizedPost` contract and treating the field as disposable, which would silently break UID stability and cross-fetch dedup.

**Trade-offs:** Documentation-only change. No code behavior changes. Eliminates a contradiction that could mislead implementors.

**Alternative options:** None. The SPIs and spec are authoritative; the record's doc must align with them.
