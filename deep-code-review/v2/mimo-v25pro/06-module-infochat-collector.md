# Deep code review: module infochat-collector
**Target:** module infochat-collector | **Lens:** module | **Module path:** infochat-collector/ | **Date:** 2026-06-07 | **Reviewer:** senior-developer (mimo-v2.5-pro)

## Headline findings

1. **SECURITY | critical -- Stage1Pipeline.unicodeNormalize strips zero-width characters using visually confusable literal character constants.** The zero-width stripping code at `Stage1Pipeline.java:304` uses literal Unicode characters (`'​'`, `'‌'`, `'‍'`, `'﻿'`) rather than their `\u` escape sequences. These characters are visually identical to a space or empty string in source code, making the code impossible to review accurately. A future maintainer editing this line could easily introduce a non-zero-width character or delete the wrong one. The bidi-control and other zero-width ranges on lines 292-300 use explicit `\u` escapes correctly; only line 304 diverges. The comment on line 302-303 documents the codepoints but the actual source bytes are visually indistinguishable from ordinary whitespace.

2. **SECURITY | medium -- Stage2Worker.tryOnce null-guards `originalBody` after it has already been passed through the delimiter template substitution.** At `Stage2Worker.java:199`, the ternary `originalBody == null ? "" : originalBody` applies a null guard inside the prompt template substitution. The calling method `judge` at line 143 passes `stage1Result.originalBody()` directly, and the `Stage1Result` record's `originalBody` field comes from `Stage1Pipeline.process` which returns `normalized` (the output of `unicodeNormalize`), which is never null. The guard is defensive code for an impossible scenario given the call chain -- `Stage1Pipeline.process` coerces null rawBody to empty at line 256, and `unicodeNormalize` always returns a non-null String. Per engineering rules section 7, this null-check inside a trusted internal boundary is unnecessary.

3. **PERFORMANCE | medium -- Stage1Pipeline.findAllMatchesUnderWatchdog iterates every Rule's Matcher against the full body without short-circuiting on a watchdog abort.** When the watchdog fires (RegexInterruptedException thrown from `charAt`), the exception propagates from whichever `Matcher.find()` call triggered the deadline check. However, if a pathological body exhausts the watchdog on Rule N, rules N+1 through 7 are never attempted -- this is correct fail-closed behavior. The concern is the per-character clock check at `InterruptibleCharSequence.charAt`: `System.nanoTime()` is called on every single character of the body for every regex pattern iteration. On a 100KB body with 7 patterns, this is potentially millions of `nanoTime` calls. While `nanoTime` is fast (~20-40ns on modern JVMs), the overhead is non-trivial for large bodies. A coarser deadline check (e.g., every 1000th character, or using `System.nanoTime()` only between `Matcher.find()` calls rather than per-character) would reduce overhead while still meeting the watchdog contract. The spec commits to "per-input wall-clock watchdog" which does not mandate per-character granularity.

4. **MAINTAINABILITY-RULES-DRIFT | low -- Stage1Pipeline uses package-private mutable static field for sanitizer seam.** At `Stage1Pipeline.java:229`, the `sanitizer` field is `static UnaryOperator<String>` -- package-private, non-final, mutable. The javadoc explains the test-swap-restore contract, but the engineering rules state "No defensive code for impossible scenarios" and the test seam pattern is a mutable shared-static-field anti-pattern that can cause test-ordering flakiness if the restore in `@AfterEach` fails. This is a testability trade-off documented inline, not an engineering-rules violation, but the pattern is fragile.

5. **MAINTAINABILITY-RULES-DRIFT | low -- TagVocabulary.contains accepts nullable parameter despite non-null-by-default contract.** At `TagVocabulary.java:105`, the `contains` method declares `String normalized` without `@Nullable` but has an explicit `normalized != null` check. Per engineering rules section 7a (NullAway/JSpecify), non-null is the package default, so the null-check is defensive code for an impossible scenario. The caller in `TaggerWorker.validate` at line 404 already guards with `normalized != null && tagVocabulary.contains(normalized)`, making the DAO's own check doubly unnecessary.

6. **SECURITY | low -- NostrStreamSource.Registrar creates SsrfGuardedHttpClient and NostrEventVerifier as final instance fields on the inner static class rather than CDI-managed beans.** At `NostrStreamSource.Registrar.java:303-306`, `ssrfClient` and `verifier` are instantiated directly in the field initializer rather than injected. The `SsrfGuardedHttpClient` default constructor is called, which means its IP blocklist configuration comes from defaults rather than from the shared CDI-configured instance. If a deployment customizes the SSRF blocklist via CDI producer, this inner-class instance would miss those customizations. The `NostrEventVerifier` is stateless so CDI vs. direct instantiation is equivalent, but `SsrfGuardedHttpClient` carries configuration state.

7. **PERFORMANCE | low -- LinkingJob.findSemanticCandidates uses a full table self-join without bounding the candidate set before the cosine-distance filter.** At `LinkingJob.java:261-276`, the query joins `post_embedding pe1` to `post_embedding pe2` and applies `WHERE (pe1.embedding <=> pe2.embedding) < threshold` as a filter. Without a pre-filter on `pe2` (e.g., a time-range or source restriction), this performs an O(N) scan over all embeddings for each driving post. The `LIMIT` cap bounds the output but not the scan. For a deployment with thousands of READY posts, this could become expensive. The `fetched_at >= ?` predicate does bound the temporal range, which helps, but the self-join still evaluates cosine distance for every row in the window before filtering.

8. **MAINTAINABILITY-RULES-DRIFT | low -- ReEvaluationJob.reconstructOriginalBody opens two sequential JDBC operations on the same autoCommit connection without a transaction boundary.** At `ReEvaluationJob.java:243-265`, the method opens a connection, reads the post body via `readPostBody`, then reads quarantine rows -- both on the same connection but without wrapping in a transaction. This is functionally correct (both are read-only SELECTs) but inconsistent with the module's convention of using `TransactionHelper.inTransaction` for multi-statement work. If the method were ever extended to include a write, the lack of a transaction boundary would be a correctness issue.

## Detail

### Stage 1 pipeline (SECURITY)

The Stage 1 pipeline (`Stage1Pipeline`, `Stage1RegexSet`, `PlaceholderIds`, `QuarantineDao`) is the most security-critical component in the module. The implementation correctly follows the spec's "entity-decode, Unicode-first, OWASP-last" ordering. The Javadoc on `Stage1Pipeline` is unusually thorough -- it documents the load-bearing step order, the attack vectors each step defends against, and the cross-references to the redteam findings that motivated each defense. This is appropriate for security-critical code.

**Finding 1 details:** The zero-width stripping at `Stage1Pipeline.java:304`:

```java
if (c == '​' || c == '‌' || c == '‍' || c == '﻿') {
```

This is the correct form using escapes. However, the actual source file contains:

```java
if (c == '​' || c == '‌' || c == '‍' || c == '﻿') {
```

where each character between the quotes is the actual zero-width codepoint. The comment on lines 302-303 identifies them by their Unicode names, but the literal source bytes are invisible. A code reviewer cannot verify correctness by reading the source -- they must inspect the raw bytes. The fix is trivial: replace the literals with `\u` escape sequences as done elsewhere in the same method.

**Regex set coverage.** The seven patterns in `Stage1RegexSet` match the design-tier catalogue in `docs/design/04-security.md` section 4.2. The `CASE_INSENSITIVE | DOTALL` flags are correct for feed-body scanning. The bounded `.{0,40}` interstitials cap backtracking per the javadoc's analysis. The rule_ids are stable strings matching the quarantine table's audit expectations.

**PlaceholderIds entropy.** 16 bytes from `SecureRandom` base32-encoded to 26 chars provides 128 bits of entropy per placeholder. The per-row randomization is correctly implemented -- no caching, no per-process seed. The `encodeBase32` implementation is correct (RFC 4648, no padding).

**QuarantineDao.** The DAO is connection-passive (caller-supplied Connection), which is the correct shape for the transactional coupling with `post.body` UPDATE. The NOTIFY emit runs inside the same transaction as the INSERT, satisfying the architecture.md same-transaction rule. The `RETURNING id` + `getObject(1)` pattern correctly retrieves the quarantine UUID.

### Stage 2 pipeline (SECURITY)

**Stage2Worker.** The bounded-concurrency `Semaphore` is correctly shaped: `acquireUninterruptibly` in the `judge` method, `release` in a `finally` block. The `judgeBody` entry point for re-evaluation shares the same semaphore, preventing re-eval from starving first-pass Stage 2. The prompt template is loaded once at `@PostConstruct` from the classpath -- correct for a static template. The per-call `UUID.randomUUID()` delimiter is the spec-committed pattern.

**Verdict parsing.** The exact-match switch expression against the four-token closed set is correct. The `.trim()` tolerates surrounding whitespace. Anything else returns null, which the caller treats as unparseable (retry once, then INFRA_FAILURE).

**Stage2VerdictHandler.** The verdict-vs-infrastructure split is correctly implemented. BENIGN transitions quarantine rows to BENIGN_CLOSED and emits NOTIFY. INJECTION/MALWARE/UNKNOWN sets `post.status='QUARANTINED'`. The INFRA_FAILURE branch respects the `release-on-stage2-failure` flag. The `setStage2Verdict` UPDATE is a separate statement from the status UPDATE but both run inside the same `TransactionHelper.inTransaction` boundary.

**Finding 2 details:** The null guard on `originalBody` at `Stage2Worker.java:199` is unnecessary given the call chain. `Stage1Pipeline.process` coerces null `rawBody` to empty at line 256, and `unicodeNormalize` always returns a non-null String. The `Stage1Result.originalBody()` is therefore never null. The guard at line 199 is the only instance of defensive null-checking in the Stage 2 path.

### Tagger, Entity Extractor, Embedding Worker

**TaggerWorker.** The three-surface fallback chain (schema-violating -> different prompt retry; zero-valid -> same prompt retry; unreachable -> same prompt retry) is correctly implemented. The `parseTags` method handles both JSON and line-oriented (`TAGS: ...`) formats. The `validate` method correctly normalizes via `TagNormalizer.normalize` and checks against `TagVocabulary`. The partial-valid handling (keep valid, drop invalid) matches the spec. The `persistCursor` method writes tags + `tagger_done=true` in a single statement, satisfying Invariant 5.

**TagVocabulary.** Loaded once at `@Priority(350)` (after BootstrapLoader at 200). The `@PostConstruct` reads the full `tag` table and normalizes each name. The `contains` method is a simple `Set.contains` lookup -- O(1). The vocabulary is immutable after construction (`Set.copyOf`).

**Finding 5 details:** `TagVocabulary.contains` at line 105 has `normalized != null` but the parameter is not annotated `@Nullable`. Per the NullAway/JSpecify contract, bare reference types are non-null. The caller already null-guards at `TaggerWorker.java:404`.

**EntityExtractorWorker.** The inline prompt template is a text block with the delimiter pattern. The `VALID_ENTITY_TYPES` set matches the V28 CHECK constraint. The `parseEntities` method correctly normalizes entity text (Locale.ROOT lower-case + strip) and filters by type vocabulary before INSERT. The failure policy (one retry, then release without entities) matches the spec. The `LinkedHashSet` dedup prevents duplicate `(text, type)` pairs from violating the PK.

**EmbeddingWorker.** The batch processing shape is correct: enumerate up to `batchSize` posts, embed in one batch call, validate dimensionality, INSERT + advance `embedding_done` in one transaction. The per-vector dimensionality fatal (throw on mismatch) is the correct fail-fast behavior per the spec. The `formatVector` method correctly produces the pgvector string literal format. The `BODY_FALLBACK_PREFIX_CHARS = 800` constant matches the design.

**ReadyPromoter.** The explicit JDBC transaction boundary (`setAutoCommit(false)` + `commit()`) is correctly used rather than `@Transactional`, because the self-invocation of `promoteOne` from `onTick` would bypass the CDI interceptor. The `pg_notify('new_post', ...)` runs inside the same transaction as the UPDATE, satisfying the architecture.md same-transaction rule. The `WHERE status='RAW'` predicate makes the promotion idempotent. The `afterUpdateHook` test seam is well-documented.

### Re-evaluation and admin-review jobs

**ReEvaluationJob.** The two-class enumeration (infra-failure and UNKNOWN) is correct. The `processOne` method correctly branches on cap exhaustion -> NEEDS_REVIEW, BENIGN -> apply BENIGN, INFRA_FAILURE -> skip attempt increment, non-BENIGN -> increment counter. The `reconstructOriginalBody` method correctly replaces `[REDACTED:<id>]` placeholders with `quarantine.original_html`. The audit log write for `RE_EVAL_RELEASED` includes `prior_verdict`, `new_verdict`, and `attempt`.

**Finding 8 details:** `reconstructOriginalBody` opens a connection at line 244 and performs two sequential SELECTs (post body at `readPostBody`, quarantine rows at lines 247-259) without a transaction boundary. This is functionally correct for read-only operations but inconsistent with the module's convention.

**AdminReviewTtlJob.** The `enumerateExpired` query correctly filters on `q.status = 'PENDING'` and `q.flagged_at <= cutoff`. The `rejectExpired` method correctly transitions quarantine PENDING -> REJECTED and post NEEDS_REVIEW -> QUARANTINED in one transaction. The NOTIFY and audit log write are inside the same transaction. No admin notification fires (the notifier already paged on NEEDS_REVIEW entry) -- correct per the spec.

**PerSourceUnknownTracker.** The SQL correctly joins `source` to `post` and computes per-source UNKNOWN rates. The `HAVING COUNT(*) >= ?` filter enforces the minimum sample size. The `disableSource` method correctly guards with `AND status = 'active'` so repeated ticks are no-ops. The throttled admin notification fires once per source via the error class key.

### Fetch subsystem

**FetchScheduler.** The polymorphic dispatch via `@FetcherKind` annotation and CDI discovery is clean. The per-kind interval gating via `lastTickByKind` ConcurrentHashMap is correct. The D42 failure ladder is correctly implemented: `recordFailure` increments `consecutive_failures` and flips `active -> failed` at threshold; `recordSuccess` zeroes the counter. The log-redaction path (`logFetchFailure` -> `exceptionChainMessage` -> `redactUrlsInText`) correctly avoids passing the raw Throwable to the logger. The `warnedOrphanKinds` set prevents WARN-per-heartbeat noise for expected missing fetchers.

**SourceRepository.** The `RECORD_FAILURE_SQL` uses a `CASE` expression to atomically increment the counter and flip the status in one statement. The `RETURNING` clause captures the post-update state. The `crossedThreshold` detection (`count == threshold && status == 'failed'`) correctly identifies the crossing tick.

**Finding 3 details:** The per-character `System.nanoTime()` check in `InterruptibleCharSequence.charAt` is the watchdog's enforcement mechanism. The spec commits to "per-input wall-clock watchdog" which does not mandate per-character granularity. A coarser check (e.g., every Nth character or between `Matcher.find()` calls) would reduce overhead while still bounding wall time.

### Outbox (PostPersister, EvalQueueProducer, OutboxRehydrator)

**PostPersister.** The UID derivation (`sha256(source_id || '|' || upstream_identifier)`) matches the spec. The `ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING` dedup is the belt-and-suspenders UNIQUE constraint. The null/empty upstreamIdentifier validation at the system boundary is correctly categorized as SPI-contract validation, not defensive code.

**EvalQueueProducer.** The `@Broadcast` annotation allows multiple subscribers (Stage1Worker + TestEvalQueueConsumer). The `Emitter<PostPersister.PersistedPostKey>` correctly carries the composite key (id + fetchedAt) for partition-aware SELECTs.

**OutboxRehydrator.** The keyset-paginated scan (`WHERE (fetched_at, id) > (?, ?)`) correctly bounds memory per chunk. The connection-released-before-emit pattern prevents Agroal pool starvation. The `@Priority(300)` ordering (before FetchScheduler at 400) ensures prior-run RAW posts drain before new fetches arrive.

### Stream sources (Nostr)

**NostrStreamSource.** The kind allowlist (`Set.of(1, 6)`) matches the spec. The inbound queue cap (`INBOUND_CAPACITY = 10_000`) prevents OOM from a flooding relay. The delivery loop correctly drains remaining events after `delivering` is set to false. The `enqueueInbound` method correctly enforces the trust-boundary ordering: signature verification -> kind allowlist -> dedup -> enqueue.

**NostrEventVerifier.** The BIP-340 Schnorr verification is hand-rolled on top of Bouncy Castle's secp256k1 primitives. The NIP-01 canonical serializer is hand-rolled to avoid Jackson dependency stability concerns. The constant-time comparison for the id check prevents timing side-channel attacks. The `liftX` implementation correctly handles the even-y requirement and the quadratic-residue check.

**NostrDedupFilter.** The bounded FIFO `LinkedHashMap` with `removeEldestEntry` is a correct and simple dedup implementation. The `synchronized` block on a private monitor provides thread safety. The FIFO eviction policy (not LRU) is the correct choice -- LRU would bias retention towards the spammiest ids.

**RelayHealthTracker.** The two-layer state machine (per-relay cooldown + source-level cycle tracking) is correctly implemented. The `decideTransition` method correctly distinguishes entry-into-all-bad (cycle counter increment) from recovery (cycle counter reset). The notifier callback runs outside the synchronized region, preventing JDBC in the callback from blocking parallel relay-worker calls.

**Kind6Handler.** The kind-6 repost handling correctly persists the commentary body, writes the `post_reference` edge with `to_upstream_identifier` (not `to_post`), and resolves the edge if the original is already stored. The insert-then-resolve ordering prevents the cross-source race where both the repost-first and original-first paths miss.

**RepostEdgeResolver.** The two-path resolution (original-first via `findNostrOriginalPostId`, repost-first via `resolveEdgesPointingTo`) correctly covers both arrival orders. The `to_post IS NULL` guard prevents re-resolution of already-resolved edges. The `s.kind = 'nostr'` join in `findNostrOriginalPostId` prevents non-Nostr upstream identifiers from resolving Nostr edges.

### Bootstrap and config

**BootstrapLoader.** The transactional shape (single connection, `autoCommit=false`) correctly groups source upserts, tag upserts, audit log insert, and bootstrap_meta upsert. The `WHERE source.deleted_at IS NULL` on the UPDATE branch correctly preserves soft-deleted rows. The SHA-256 of the raw file bytes is the cross-host convergence key. The `normalizeTag` method delegates to the shared `TagNormalizer` and throws on invalid tags for fast-fail at startup.

**BootstrapSourcesParser.** Not read in full but referenced by `BootstrapLoader`. The parser validates schema (name, kind, identifier, category, tags with >=1 entry, optional config).

**InfochatProfile.** The enum correctly maps the four profile names to their config names. The `resolveOrThrow` method walks the Quarkus profile chain and fails fast if no known profile is found. The `Validator` CDI bean skips validation in TEST and DEVELOPMENT modes, which is correct for CI/dev workflows.

### Startup and lifecycle

**InstanceLockGuard.** Extends `AbstractInstanceLockGuard` from infochat-core. The `@Priority(50)` runs before Flyway (100). The `@Scheduled(every = "30s")` probe refreshes the heartbeat.

**HeartbeatScheduler.** Simple `UPDATE heartbeat SET last_seen_at = now() WHERE service = ?` on each tick. Uses a transient pool connection (not the long-lived advisory-lock session).

**StreamSourceSupervisor.** The virtual-thread-per-task executor correctly handles async startup (registration accepted immediately, relay connection runs in background). The `drainAll` method correctly bounds the total flush time by a shared deadline. The `@Priority(450)` ordering (after FetchScheduler at 400) is correct.

**StartupReleaseOnStage2FailureWarn.** The `@Priority(150)` sits between Flyway (100) and OutboxRehydrator (300). The WARN log and audit row fire once per process start only when `release-on-stage2-failure=true`. The javadoc correctly identifies the design-doc "Provider" naming as a doc bug (Stage 2 runs in the Collector).

### Assets subsystem

**AssetSnapshotFetcher.** The per-host `@Scheduled` methods correctly drive the per-host cadence. The D42 failure ladder for `asset_config` is correctly implemented (bump counter, flip status at threshold). The source-map cache (`sourcesById`) uses a volatile field with a `synchronized` double-check for lazy initialization. The `resetSourceCacheForTest` test seam is documented.

**PriceSnapshotStore.** Not read in detail but referenced by `AssetSnapshotFetcher`. The store writes directly to `price_snapshot` (no outbox, no Stage 1/2).

**AssetDataSource implementations (Coingecko, Kraken, Bitfinex).** Not read in detail but referenced by the fetcher. Each implements the `AssetDataSource` SPI with a unique `id()` returning the host name.

### Partition management

**PartitionCreator.** The `@Scheduled(every = "{infochat.partitions.check-interval}")` tick provisions the next month's partitions. The `LIVENESS_THRESHOLD = 25 days` provides multiple daily-tick retries before the active month ends. The `@io.quarkus.agroal.DataSource("owner")` qualification correctly routes DDL to the owner datasource (the least-privileged collector role cannot CREATE TABLE).

### Linking

**LinkingJob.** The driving-set query correctly uses the `idx_post_link_cursor` partial index. The entity-match query joins `post_entity` to itself on `(entity_text, entity_type)`. The semantic-match query uses pgvector's `<=>` cosine distance operator. The bidirectional INSERT writes both legs in one transaction. The `last_linked_at` advance makes the post exit the driving set.

### Notify

**QuarantineNotifyEmitter.** The `emit` method correctly builds the JSON payload from closed-set enums and a UUID. The NOTIFY runs inside the caller's transaction. The `SELECT pg_notify(?, ?)` pattern with result-set drain is the correct JDBC shape for NOTIFY.

### TransactionHelper

The shared `inTransaction` wrapper correctly handles `autoCommit=false`, explicit `commit()`, and `rollback()` on any `RuntimeException | SQLException`. The `catch` block correctly re-throws `RuntimeException` as-is and wraps `SQLException` in `IllegalStateException`.

### Test coverage observations

The module has 84 test files covering the production surface. Notable test patterns:
- `Stage1RegexSetTest` validates each regex pattern against known attack strings
- `Stage1PipelineIT` tests the full Stage 1 flow including watchdog and sanitizer-exception paths
- `Stage1WatchdogIT` tests the watchdog timeout behavior
- `Stage2WorkerIT` tests the full Stage 2 flow including retry and verdict parsing
- `NostrEventVerifierTest` tests BIP-340 verification with known test vectors
- `NostrSsrfIT` / `NostrSsrfTest` test SSRF protection on relay connections
- `FetchSchedulerFailureLadderIT` tests the D42 failure threshold crossing
- `FetchSchedulerLogRedactionTest` tests URL redaction in failure logs
- `OutboxRehydratorPaginationIT` tests the keyset pagination and memory bounds
- `ReadyPromoterIT` tests the same-transaction rule (UPDATE + NOTIFY atomicity)
- `EmbeddingWorkerIT` tests dimensionality mismatch and batch failure paths
- `SchemaHardeningIT` / `FlywayMigrationIT` test schema constraints and migrations

The test doubles (`StubLlmProvider`, `FakeNostrRelay`, `LoopbackPermittingBlocklist`, `SeedDataSource`) are correctly extracted to top-level package-private classes rather than inner classes, following the engineering-rules convention.
