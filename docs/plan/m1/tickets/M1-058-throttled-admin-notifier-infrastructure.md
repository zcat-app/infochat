---
id: M1-058
title: ThrottledAdminNotifier (T2-G infrastructure) + admin_notification_state table
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by: []
files_budget: 5
files_scope:
  - infochat-core/src/main/resources/db/migration/V16__admin_notification_state.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/ThrottledAdminNotifier.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/AdminNotificationRecord.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/NotifyOutcome.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/notifier/ThrottledAdminNotifierTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - any change to existing call-sites that carry "future T2-G throttled admin notifier" comments (Stage1Pipeline.java, Stage2VerdictHandler.java, TaggerWorker.java, EmbeddingWorker.java) — those adopt M1-058's notifier in separate follow-up tickets, NOT here
  - any change to RssFetcher.java or FetchScheduler.java — RssFetcher's D42 wiring remains deferred (FetchScheduler.java:58-66); adoption of M1-058's notifier from the RSS path is a separate follow-up ticket
  - any change to M1-055b's asset-fetcher path — M1-058 lands the notifier; M1-055b consumes it
  - any actual outbound notification delivery (email, Slack, SimpleX DM, etc.) — v1's `notifyOnce` writes the notification record to DB + emits a WARN log line; the delivery mechanism (which adapter to use, how to format) is operator-side log scraping in v1. A future ticket may add an `AdminNotificationDelivery` SPI; not this one.
  - any change to the AuditAction enum — admin notifications are not audit-logged (they ARE the operator-visibility surface, not an action by a privileged user)
  - any change to pre-V15 migrations — V16 is the only schema change in this ticket
  - any change to application.properties — the throttle-window @ConfigProperty uses an inline `defaultValue` (single-global-default property, matching the `invite.brute-force-window=1h` precedent in InviteCodeConsumer.java); operators override via `-D` or env. Per-profile tuning is a follow-up ticket.
  - any test outside the single test file in files_scope — every pre-existing test continues to pass unchanged
acceptance:
  - "infochat-core/src/main/resources/db/migration/V16__admin_notification_state.sql exists and applies cleanly on a fresh DB. (V16 is the next-free integer at refine time — V14=asset_config, V15=saved_post. If other migrations land first, re-run `ls infochat-core/src/main/resources/db/migration/ | sort -V | tail` and rename plus substitute V<NN> in all acceptance greps below.) Verify: `mvn -pl infochat-core flyway:migrate` exits 0 against a fresh DB AND the migration file exists matching glob `V*__admin_notification_state.sql`"
  - "V16 creates the `admin_notification_state` table per spec schema.md:510 ('Admin notification state — backing store for the throttled admin notifier (decision D22)'). Columns: `(notification_key TEXT PRIMARY KEY, error_class TEXT NOT NULL, last_notified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), notification_count BIGINT NOT NULL DEFAULT 1, suppressed_count BIGINT NOT NULL DEFAULT 0, first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`. Verify: `grep -E 'CREATE TABLE admin_notification_state' V*__admin_notification_state.sql` returns ≥1 match AND `grep -E 'notification_key\\s+TEXT\\s+PRIMARY KEY' V*__admin_notification_state.sql` returns ≥1 match AND `grep -E 'last_notified_at\\s+TIMESTAMPTZ' V*__admin_notification_state.sql` returns ≥1 match AND `grep -E 'notification_count\\s+BIGINT' V*__admin_notification_state.sql` returns ≥1 match AND `grep -E 'suppressed_count\\s+BIGINT' V*__admin_notification_state.sql` returns ≥1 match"
  - "V16 carries the V7-style per-role GRANT split: `GRANT SELECT, INSERT, UPDATE ON admin_notification_state TO infochat_collector;` AND `GRANT SELECT ON admin_notification_state TO infochat_provider;`. DELETE NOT granted to either role (the table grows monotonically; pruning is operator-side via DBA TRUNCATE if needed). Verify: `grep -E 'GRANT\\s+SELECT,\\s+INSERT,\\s+UPDATE\\s+ON\\s+admin_notification_state\\s+TO\\s+infochat_collector' V*__admin_notification_state.sql` returns ≥1 match AND `grep -E 'GRANT\\s+SELECT\\s+ON\\s+admin_notification_state\\s+TO\\s+infochat_provider' V*__admin_notification_state.sql` returns ≥1 match AND `grep -E 'GRANT[^;]*DELETE[^;]*ON\\s+admin_notification_state' V*__admin_notification_state.sql` returns ZERO matches"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/NotifyOutcome.java exists as a public enum with two values: `EMITTED` (the call resulted in a WARN log line emission) and `SUPPRESSED` (the call was within the throttle window and did NOT emit). Verify: `grep -E 'public\\s+enum\\s+NotifyOutcome' NotifyOutcome.java` returns ≥1 match AND `grep -E 'EMITTED' NotifyOutcome.java` returns ≥1 match AND `grep -E 'SUPPRESSED' NotifyOutcome.java` returns ≥1 match"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/AdminNotificationRecord.java exists as an immutable Java record carrying `(String key, String errorClass, Instant lastNotifiedAt, long notificationCount, long suppressedCount, Instant firstSeenAt)`. Used by `ThrottledAdminNotifier` to return current state for tests + future admin commands. Verify: `grep -E 'public\\s+record\\s+AdminNotificationRecord' AdminNotificationRecord.java` returns ≥1 match"
  - "infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/ThrottledAdminNotifier.java exists, is `@ApplicationScoped`, injects `Clock` (`@Inject Clock clock`) and `DataSource` for testability and DB access, and exposes the public method `NotifyOutcome notifyOnce(@NonNull String key, @NonNull String errorClass, @NonNull String message)`. Semantics: (a) on first call for a given `key`, UPSERTS a row in `admin_notification_state` (notification_key=key, error_class=errorClass, last_notified_at=NOW(), notification_count=1, suppressed_count=0, first_seen_at=NOW()), emits a WARN log line via `LOG.warnf(\"ADMIN-NOTIFY key=%s error=%s message=%s\", key, errorClass, message)` (canonical format for operator log scraping), and returns `NotifyOutcome.EMITTED`; (b) on a subsequent call within the throttle window, atomically INCREMENTS `suppressed_count`, does NOT log, and returns `NotifyOutcome.SUPPRESSED`; (c) on a call after the throttle window has elapsed since `last_notified_at`, atomically UPDATEs `last_notified_at=NOW()` and `notification_count`+=1, emits the WARN log line, and returns `NotifyOutcome.EMITTED`. The UPSERT MUST be a single Postgres `INSERT ... ON CONFLICT (notification_key) DO UPDATE` statement with a conditional SET clause keyed on `last_notified_at < NOW() - <window>::interval` so concurrent first-time callers for the same key produce exactly one EMITTED result and N-1 SUPPRESSED results (race-safety guarantee). Verify: `grep -E '@ApplicationScoped' ThrottledAdminNotifier.java` returns ≥1 match AND `grep -E '@Inject\\s+Clock' ThrottledAdminNotifier.java` returns ≥1 match AND `grep -E 'public\\s+NotifyOutcome\\s+notifyOnce' ThrottledAdminNotifier.java` returns ≥1 match AND `grep -E 'INSERT\\s+INTO\\s+admin_notification_state' ThrottledAdminNotifier.java` returns ≥1 match AND `grep -E 'ON\\s+CONFLICT' ThrottledAdminNotifier.java` returns ≥1 match AND `grep -E 'ADMIN-NOTIFY' ThrottledAdminNotifier.java` returns ≥1 match"
  - "The throttle window is configurable via `@ConfigProperty(name = \"infochat.admin-notifier.throttle-window\", defaultValue = \"1h\")` (Duration). Inline default permitted — single-global-default property per the codebase's split convention (matches `invite.brute-force-window=1h` at InviteCodeConsumer.java); FetchScheduler.java:95-100's no-inline-default rule applies to profile-driven properties only. Verify: `grep -E '@ConfigProperty\\(name\\s*=\\s*\"infochat\\.admin-notifier\\.throttle-window\"' ThrottledAdminNotifier.java` returns ≥1 match AND `grep -E 'defaultValue\\s*=\\s*\"1h\"' ThrottledAdminNotifier.java` returns ≥1 match"
  - "ThrottledAdminNotifierTest is `@QuarkusTest` and exercises the notifier against a real Postgres connection via Quarkus dev-services (no mocked DataSource). Each test resets `admin_notification_state` via a `@BeforeEach` TRUNCATE so tests are independent. The fixed Clock is installed via `QuarkusMock.installMockForType(Clock.class, fixedClock)` per the Quarkus-idiomatic test seam pattern. Verify: `grep -E '@QuarkusTest' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -iE 'TRUNCATE\\s+admin_notification_state|DELETE\\s+FROM\\s+admin_notification_state' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -E 'QuarkusMock\\.installMockForType\\s*\\(\\s*Clock\\.class|@Inject\\s+Clock' ThrottledAdminNotifierTest.java` returns ≥1 match"
  - "ThrottledAdminNotifierTest contains a `@Test` method whose name contains `firstCallEmitsAndPersists` (case-insensitive) that asserts: (a) `notifyOnce(key, errorClass, message)` returns `NotifyOutcome.EMITTED`; (b) `admin_notification_state` gains exactly one row with `notification_count=1, suppressed_count=0`; (c) a WARN log line containing `ADMIN-NOTIFY key=<key>` is emitted (log capture per M1-009/M1-040 precedent). Verify: `grep -iE 'void\\s+\\w*firstCallEmitsAndPersists\\w*\\s*\\(' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -E 'NotifyOutcome\\.EMITTED' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -iE 'ADMIN-NOTIFY|notification_count|notificationCount' ThrottledAdminNotifierTest.java` returns ≥1 match"
  - "ThrottledAdminNotifierTest contains a `@Test` method whose name contains `withinWindowSuppresses` (case-insensitive) that asserts: (a) after one EMITTED call, a second call with the same `key` (within the throttle window per the fixed Clock) returns `NotifyOutcome.SUPPRESSED`; (b) the row's `suppressed_count` increments to 1 (and to N-1 after N total calls); (c) no second WARN log line is emitted. Verify: `grep -iE 'void\\s+\\w*withinWindowSuppresses\\w*\\s*\\(' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -E 'NotifyOutcome\\.SUPPRESSED' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -iE 'suppressed_count|suppressedCount' ThrottledAdminNotifierTest.java` returns ≥1 match"
  - "ThrottledAdminNotifierTest contains a `@Test` method whose name contains `afterWindowEmitsAgain` (case-insensitive) that uses the fixed-Clock test seam to advance simulated time PAST the throttle window AND asserts: (a) the next `notifyOnce` call returns `NotifyOutcome.EMITTED`; (b) the row's `notification_count` increments to 2; (c) a second WARN log line is emitted. Verify: `grep -iE 'void\\s+\\w*afterWindowEmitsAgain\\w*\\s*\\(' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -iE 'Clock|Instant|plus(Hours|Minutes|Duration)|advance|fixedClock' ThrottledAdminNotifierTest.java` returns ≥1 match"
  - "ThrottledAdminNotifierTest contains a `@Test` method whose name contains `concurrentNotifyOnceRaceSafe` (case-insensitive) that fires N parallel `notifyOnce(sameKey, ...)` calls via `ExecutorService` or `CompletableFuture.allOf(...)` and asserts: (a) EXACTLY ONE of the N returns `NotifyOutcome.EMITTED`; (b) the other N-1 return `NotifyOutcome.SUPPRESSED`; (c) the resulting row has `notification_count=1` AND `suppressed_count=N-1` (UPSERT-with-conditional-WHERE atomicity guarantee). Verify: `grep -iE 'void\\s+\\w*concurrentNotifyOnceRaceSafe\\w*\\s*\\(' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -iE 'ExecutorService|CompletableFuture|Thread\\s*\\(|parallelStream' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -E 'EMITTED' ThrottledAdminNotifierTest.java` returns ≥1 match AND `grep -E 'SUPPRESSED' ThrottledAdminNotifierTest.java` returns ≥1 match"
  - "No pre-existing tests are modified by this ticket. Verify: `git diff main --stat -- 'infochat-*/src/test/**'` shows ONLY the new test file (ThrottledAdminNotifierTest) as added; zero modifications to other test files"
  - "mvn -B clean verify from the repo root exits 0; every prior test currently green on main continues to pass"
test_plan:
  adds:
    - infochat-core/src/main/resources/db/migration/V16__admin_notification_state.sql
    - infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/ThrottledAdminNotifier.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/AdminNotificationRecord.java
    - infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/NotifyOutcome.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/notifier/ThrottledAdminNotifierTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Operational
  - docs/spec/decisions.md D22
  - docs/spec/decisions.md D42
  - docs/spec/security.md §Failure handling
decision_refs:
  - D22
  - D34
  - D42
---

# M1-058: ThrottledAdminNotifier (T2-G infrastructure) + admin_notification_state table

## Context

The throttled admin notifier is **spec-committed shared
infrastructure** (D22 + D42 + `schema.md:510` "Admin
notification state — backing store for the throttled admin
notifier (decision D22)"). It has been anticipated by every
eval-pipeline failure path that needs operator visibility
without paging on every per-instance failure:

- `Stage1Pipeline.java` — Stage 1 regex timeout coalescing on
  the canonical error_class string for "the future throttled
  admin notifier (T2-G)".
- `Stage2VerdictHandler.java` — Stage 2 verdict failure path
  ("The throttled admin notifier (T2-G) coalesces on the
  canonical error_class string").
- `TaggerWorker.java` — tagger fallback path ("throttled
  admin notify").
- `EmbeddingWorker.java` — embedding failure path ("T2-G
  throttled admin notifier").
- `FetchScheduler.java:58-66` — RssFetcher D42 wiring,
  explicitly deferred to T2-B.

M1-055b is the **first ticket to need the notifier in code**
(D42's per-source failure-counter for the asset fetcher);
M1-058 builds the notifier so M1-055b — and every consumer
listed above, in future follow-up tickets — can consume it.

This ticket lands the notifier infrastructure ONLY. It does
NOT adopt the notifier in any pre-existing call-site
(Stage1Pipeline, Stage2VerdictHandler, TaggerWorker,
EmbeddingWorker, RssFetcher all keep their current
"comment + deferred" shape and adopt the notifier in
separate follow-up tickets). M1-055b is the only ticket
whose `blocked_by` includes M1-058; the other call-sites
adopt the notifier on their own timeline.

`security_relevant: true` because the notifier is the
operator's PRIMARY signal that a fetcher / eval-pipeline
component is failing. A miscalibrated throttle (window too
long → operator misses real failures; window too short →
notification storm masks real signal) is a real safety
concern. `migration_touch: true` because V16 (or its rebased
equivalent) introduces the `admin_notification_state` table.

## Acceptance

See YAML `acceptance:` above. The contract:

- V16 creates `admin_notification_state` per `schema.md:510`
  with the per-role GRANT split.
- `NotifyOutcome` enum carries `EMITTED` / `SUPPRESSED` so
  callers (and tests) can branch on whether a log was
  actually emitted without needing log capture.
- `AdminNotificationRecord` exposes current row state for
  tests + future admin commands.
- `ThrottledAdminNotifier` is `@ApplicationScoped`, injects
  `Clock` (test seam) and `DataSource`, exposes
  `NotifyOutcome notifyOnce(key, errorClass, message)`, and
  is race-safe via a single Postgres `INSERT ... ON
  CONFLICT DO UPDATE` with a conditional WHERE on
  `last_notified_at`. WARN log format is pinned:
  `ADMIN-NOTIFY key=%s error=%s message=%s` for operator
  log scraping.
- Throttle window is `1h` by default, configurable via
  `infochat.admin-notifier.throttle-window` ConfigProperty.
- Four `@Test` methods cover: first-call-emits,
  within-window-suppresses, after-window-emits-again,
  concurrent-race-safe — all against a real Postgres via
  Quarkus dev-services.

## Out-of-scope

This ticket builds the infrastructure ONLY. Adopting the
notifier in any pre-existing call-site is OUT of scope:

- Stage1Pipeline, Stage2VerdictHandler, TaggerWorker,
  EmbeddingWorker all carry "future T2-G throttled admin
  notifier" comments today. Those comments stay in place
  after M1-058 lands; separate follow-up tickets retrofit
  each call-site to actually inject and call the notifier.
- RssFetcher / FetchScheduler D42 wiring remains deferred
  per FetchScheduler.java:58-66. A separate follow-up
  ticket adopts the notifier from the RSS path.
- M1-055b is the only ticket whose `blocked_by` includes
  M1-058; it is the only ticket that adopts the notifier
  in the same milestone.
- The notifier writes to DB + emits a WARN log. A future
  `AdminNotificationDelivery` SPI (push to SimpleX DM,
  email, etc.) is v2.
- Per-profile throttle-window tuning is deferred to a
  follow-up ticket — the inline `defaultValue = "1h"`
  applies uniformly across profiles; operators override
  via `-D` or env.

## Notes

- **Why "first" rather than "best".** Multiple call-sites
  have been waiting for T2-G. Building the notifier as part
  of any one consumer's ticket would shape the API around
  that consumer's needs (M1-055b's `key = "asset-source-
  failed:" + asset + ":" + sub_verb`; Stage1's `key =
  "stage1-regex-timeout"`; Tagger's per-source key). A
  standalone infrastructure ticket lets the API be designed
  once for all consumers.
- **Race safety via UPSERT with conditional WHERE.** The
  implementation MUST use a single Postgres `INSERT ... ON
  CONFLICT (notification_key) DO UPDATE` statement. The
  conditional WHERE clause in the SET portion gates the
  `last_notified_at` refresh on `last_notified_at < NOW() -
  :window::interval`. The query RETURNS enough to
  distinguish: fresh INSERT (no conflict, EMITTED), UPDATE
  that refreshed the timestamp (EMITTED), UPDATE that
  incremented suppressed_count only (SUPPRESSED). This
  guarantees exactly-one-log-per-window even under N
  concurrent callers for the same key.
- **No outbound delivery in v1.** The "admin notification"
  is a WARN log line + a DB row. Operators tail logs and
  query the table. A future SPI may push to SimpleX DM or
  email; out of scope here.
- **Throttle window default — `1h`.** Matches the existing
  `invite.brute-force-window=1h` precedent (a security-
  sensitive throttle window with the same shape). Inline
  `defaultValue` is permitted per the codebase's split
  convention (single-global-default properties use inline;
  profile-driven properties live in application.properties).
- **No `infochat-collector/notifier` package today.** This
  ticket creates the package. Future migrations of the
  package (e.g. into `infochat-core` for cross-module reuse)
  are not v1 concerns — the notifier today is consumed
  Collector-side only.
- **Migration race.** V16 is the next-free integer at
  refine time. If parallel work lands a migration first,
  re-run `ls infochat-core/src/main/resources/db/
  migration/ | sort -V | tail` and rename.

## Big-picture notes

- M1-058 is a clean leaf: no `blocked_by`, no `decomposed_
  from`. It exists because M1-055b discovered that the
  spec-committed infrastructure (D22 + schema.md:510) was
  never filed as its own ticket.
- After M1-058 lands, every existing "future T2-G
  throttled admin notifier" comment in the codebase
  becomes a candidate retrofit ticket. Those retrofits
  are NOT this ticket's responsibility.

## Authorized test changes

- (none — this ticket adds new tests and modifies no
  pre-existing tests.)

## Alternatives considered

- **Build the notifier inline inside M1-055b.** Rejected
  per M1-055b §Alternatives considered — would shape the
  notifier API around one consumer.
- **In-memory throttle map instead of DB-backed state.**
  Rejected — spec `schema.md:510` explicitly commits to
  a "backing store" for the throttled admin notifier.
  DB-backed state survives JVM restart; in-memory does
  not (a restart-during-flapping scenario would lose
  throttle state and re-spam admin).
- **Push delivery to an adapter (e.g. SimpleX DM to bot
  admin) in v1.** Rejected — adds an adapter dependency
  to a Collector-side bean; complicates testing; and
  introduces a Collector-to-Provider coupling that
  currently doesn't exist (Collector has no MessagingAdapter
  surface). Log + DB-row is sufficient for v1 operator
  visibility; a future SPI handles delivery shapes.
- **Boolean return from `notifyOnce` instead of
  `NotifyOutcome` enum.** Rejected — enum is more
  self-documenting at callsites (`if (notifier.notifyOnce(...)
  == EMITTED)` reads better than `if (notifier.notifyOnce(...))`)
  AND lets tests assert on outcome without log-capture.
- **Per-profile throttle window in application.properties.**
  Deferred to follow-up — keeps M1-058 simpler; uniform 1h
  is a reasonable v1 default.
- **Separate `Test` (unit, mocked DataSource) and `IT`
  (real DB) test files.** Rejected — Quarkus dev-services
  provide a real Postgres for `@QuarkusTest` out of the
  box; splitting unit from integration tests when the
  contract is "race-safe SQL against PG" would duplicate
  setup with no behavioral benefit. Single
  `ThrottledAdminNotifierTest @QuarkusTest` covers both.
