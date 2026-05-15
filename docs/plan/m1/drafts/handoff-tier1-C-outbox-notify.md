# Session handoff — Tier 1 Group C: outbox + LISTEN/NOTIFY + provider_state + rehydrator

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T1-C ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0 tickets are done and merged on main (M1-001..M1-007 +
  M1-009).
- Tier 1 Group A (T1-A schema) is done and merged on main:
    M1-008 (umbrella per-(user, scope) isolation IT)
    M1-008a (identity + audit + last-admin trigger, V5 migration)
    M1-008b (sources + tags catalogues, V6 migration)
    M1-008c (joins + scope_preferences + post, V7 migration)
- Tier 1 Group B (T1-B ingest sources) is done and merged on main:
    M1-022 (Bootstrap-sources loader + bootstrap_meta, V8 migration)
    M1-023 (RSS Fetcher impl of the Fetcher SPI, kind='rss')
    M1-024 (infochat-ssrf module + RssFetcher hardening — Option B
            from T1-B authoring picked mid-session; SSRF gate lands
            from day one)
    M1-025 (infochat-ssrf hardening — M1-024 redteam remediation)
    M1-026 (infochat-ssrf hardening followup — M1-025 remediation)
- M1-019 (stdout API-key redaction) and M1-020 (exception-message
  sanitization) are `status: deferred` with `deferred_reason:
  post-mvp-hardening` and empty `deferred_on`. They will run at the
  end of T1 once their guarded code paths (LLM call sites for M1-019;
  messaging adapter intake for M1-020) exist.
- M1-021 (identity/audit redteam remediation, V8+ migration) is
  `status: deferred` with `deferred_reason:
  end-of-tier-1-redteam` and runs at end of T1 alongside
  M1-019/M1-020. The GUC-unset path is backward-compatible, so T1-C
  can proceed without M1-021 being landed first.
- Flyway migrations on disk under
  infochat-core/src/main/resources/db/migration/:
    V1__init.sql, V2__roles.sql, V3__heartbeat.sql, V4__nologin.sql,
    V5__identity_audit.sql, V6__sources_tags.sql, V7__joins_post.sql,
    V8__bootstrap_meta.sql.
  T1-C's `provider_state` migration takes V9 IFF M1-021 has not landed;
  if M1-021 has been started/committed first it will own V9 and the
  provider_state migration takes V10. Re-grep the migration directory
  at /m1-tick start time and pick the next free integer; the file
  name suggested below is the EXPECTED slot when only V1..V8 exist.
- Branch is main, otherwise clean.

## Already-fixed prerequisite (do not redo)

A prior `spec:` commit on main (2026-05-15) corrected a
documentation inconsistency in `docs/design/01-architecture.md`:
§1.3.1's rehydrator box and §1.4.3's priority-300 table row used
to say the rehydrator scans `status IN ('RAW', 'EVALUATING')`, but
`docs/spec/schema.md` §Posts and derivatives and Invariant 5 are
categorical that the `post.status` enum is `('RAW','READY',
'QUARANTINED','NEEDS_REVIEW')` only — no `'EVALUATING'`. The
design lines now correctly say "scans `status='RAW'`" with an
inline Invariant-5 note. M1-028's rehydrator predicate cites
Invariant 5 (not the design line) and uses `WHERE status='RAW'`.
You do not need to relitigate this.

## What you do this session

Author ticket files in docs/plan/m1/tickets/ for the T1-C group.

The T1-C group per docs/plan/m1/drafts/session-grouping-plan.md §Tier
1 is "outbox + LISTEN/NOTIFY + provider_state + rehydrator" planned
as a single ticket. The default for this authoring session is
**2 tickets** rather than 1 — the scope spans both services
(Provider-side catch-up + Collector-side outbox emit) and pairing
them in one ticket hurts review focus. The split is justified
inline in "Open question for the authoring session" below.

When you finish, leave the new ticket files UNTRACKED on main
(workflow rule: drafts ride untracked through /m1-tick start). Do
NOT commit the ticket files.

## ID allocation (LOCKED at the tail)

Per session-grouping-plan §"ID allocation": T1-C gets fresh IDs at
the tail at authoring time. M1-019/020/021 are deferred and consume
no new slots; M1-022..M1-026 are done. The next free integer at
this session's start is M1-027.

Default (2-ticket split):
  M1-027 — Provider catch-up: provider_state migration +
           NewPostReconciler @Priority 250 + new_post NOTIFY listener
  M1-028 — Collector outbox: FetchScheduler @Priority 400 +
           outbox sink (persist RAW + emit eval-channel) +
           OutboxRehydrator @Priority 300

Re-grep the tickets directory at /m1-tick start time to confirm
M1-027/M1-028 are still the next free slots before committing. If
a new ticket has been authored in the interim, take the next free
slot — the slug → file-name mapping is the only invariant; the
numeric ID is allocated mechanically.

## Where you are in the milestone

Tier 1 (MVP vertical slice) is in flight.
  T1-A schema (done)
  T1-B ingest sources (done — 5 tickets including SSRF chain)
  T1-C outbox/NOTIFY (this session — 2 tickets default)
  T1-D eval pipeline (Stage 1, LLM + Stage 2, tagger + embedding)
  T1-E adapter + router (umbrella + InMemoryAdapter + router + /help)
  T1-F first commands (/add-source, /summary)

After T1-C, the next session authors T1-D's detailed handoff JIT.
See docs/plan/m1/drafts/session-grouping-plan.md for the full plan.

## Open question for the authoring session

**One ticket or two?** session-grouping-plan §Tier 1 planned T1-C as
1 ticket; this handoff defaults to 2. The case for the split:

- The Collector side and the Provider side have **different review
  focus**. The Provider side is "catch-up correctness + cursor
  semantics + first-boot race." The Collector side is "scheduler
  cadence + persist-before-enqueue + crash-recovery rehydrator."
  Bundling both in one ticket forces the reviewer to chase two
  unrelated correctness arguments in one diff.
- The two halves can be authored, reviewed, and merged
  **independently** — neither depends on the other's runtime
  behavior in T1-C scope. The Provider side exercises catch-up via
  test rows manually flipped to `READY`. The Collector side
  exercises persist+rehydrate via fixture sources and a test
  consumer that drains the eval channel.
- Files_budget for a combined ticket would land around 14-16 files
  (V9 migration + ~3 provider classes + ~3 provider tests +
  ~4 collector classes + ~4 collector tests). Two tickets at
  ~7-8 files each fit the medium-complexity ticket profile cleanly.

Two viable shapes:

- **Option A (default — 2 tickets):** M1-027 (Provider catch-up) and
  M1-028 (Collector outbox), as described in "ID allocation" above
  and locked below. Independent runnable-now once T1-B is merged
  (which it is). They can be implemented in either order.

- **Option B (1 ticket):** M1-027 (combined). Lower bookkeeping
  cost but heavier review surface. ACCEPTABLE only if you assess
  after reading the spec sections that the combined files_budget
  fits in ~12 and the acceptance criteria stay readable as a
  single list. If you go this route, the locked-decisions
  sections below collapse into one — keep the same scope items,
  just one ticket file.

Default Option A. Pick at the top of the session; document the
choice in the first ticket's "Implementation notes." Do NOT split
the difference (a half-baked Provider side in one ticket and the
rehydrator in another is the worst of both).

## Locked decisions for the two-ticket shape (Option A)

If Option A is picked, the following are LOCKED. Don't re-debate.

### M1-027 — Provider catch-up (provider_state + NewPostReconciler + NOTIFY listener)

- blocked_by: [M1-008c]
  (the `post` table from V7 — the reconciler queries
  `post WHERE status='READY' AND (ready_at, id) > (cursor)`.
  Independent of M1-022/M1-023/M1-024 — those write to source/post
  on the Collector side; the Provider catch-up reads `post.status`
  rows from any source.)
- complexity: medium
- risk: medium
- security_relevant: TRUE
  (provider_state holds the high-water-mark cursor; a buggy CAS
  update or a missing `ON CONFLICT (channel) DO NOTHING` on the
  first-boot insert could either lose READY posts permanently or
  double-process them. The cursor is also a single point of failure
  for the inter-service correctness guarantee — spec/architecture.md
  §Inter-service communication §Catch-up. Per-table GRANT discipline
  is a redteam-relevant surface too.)
- migration_touch: TRUE
  (adds Flyway V<N> for provider_state per
  docs/design/02-schema.md §2.9.2)
- round_cap: 2
- files_budget: 8
- files_scope:
    - infochat-core/src/main/resources/db/migration/V<N>__provider_state.sql
    - infochat-provider/src/main/java/io/infochat/provider/outbox/ProviderStateDao.java
    - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostReconciler.java
    - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java
    - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostHandler.java
    - infochat-provider/src/test/java/io/infochat/provider/outbox/ProviderStateDaoIT.java
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerIT.java
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerIT.java
- Scope:
  * Flyway V<N> migration creating `provider_state` per
    docs/design/02-schema.md §2.9.2:
      - `channel TEXT NOT NULL`
      - `cursor_high TIMESTAMPTZ NOT NULL`
      - `cursor_low_kind TEXT NOT NULL`
      - `cursor_low_id TEXT NOT NULL`
      - `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
      - `UNIQUE (channel)` — schema-layer singleton-row-per-channel
        enforcement
      - Per-table GRANTs: SELECT + INSERT + UPDATE to
        infochat_provider (Provider owns the row); SELECT to
        infochat_collector (collector-side admin/diagnostic reads);
        REVOKE DELETE from both service roles (the row is
        upserted, never deleted).
      - First-boot insert for the `new_post` channel via
        `INSERT INTO provider_state (channel, cursor_high,
        cursor_low_kind, cursor_low_id, updated_at) VALUES
        ('new_post', 'epoch'::TIMESTAMPTZ, 'post', '', now())
        ON CONFLICT (channel) DO NOTHING` — emitted by the migration
        so the row exists before any Provider code runs.
        DO NOT seed the `quarantine_review` row in this migration;
        that channel's reconciler lands in M2 per design §1.5.
  * `ProviderStateDao` — narrow JDBC wrapper around the two SQL
    shapes in design §2.9.2 (read cursor; CAS update). The CAS
    update MUST be the verbatim compound-cursor compare-and-swap
    from §2.9.2:
    `UPDATE provider_state SET cursor_high=:new_high,
    cursor_low_kind=:new_kind, cursor_low_id=:new_id,
    updated_at=now() WHERE channel=:ch AND
    (cursor_high, cursor_low_kind, cursor_low_id) <
    (:new_high, :new_kind, :new_id)`. The DAO is the ONLY code path
    that writes provider_state; both the listener and the reconciler
    advance the cursor through this DAO.
  * `NewPostHandler` — the unit-of-work that processes one READY
    post. In T1-C scope this is a stub that logs the event and
    advances the cursor; T1-F wires the real cache-invalidation /
    digest-recompute consumers downstream. Document the stub
    boundary explicitly in the ticket body. The handler MUST
    advance the cursor **in the same DB transaction** as its side
    effect (per spec §Inter-service communication §Catch-up — the
    advance-and-side-effect must be atomic so a duplicate NOTIFY or
    a repeated catch-up pass produces no additional effect).
  * `NewPostReconciler` Quarkus `@Startup` bean at @Priority(250)
    (per docs/design/01-architecture.md §1.4.3 Provider table).
    Steps:
      1. Read the current cursor via ProviderStateDao.
      2. Run the catch-up scan:
         `SELECT id, ready_at FROM post WHERE status='READY' AND
         (ready_at, id) > (:cursor_high, :cursor_low_id) ORDER BY
         ready_at, id`.
      3. For each row, hand to NewPostHandler within a single
         transaction (handler side effect + DAO CAS advance).
      4. Log a one-line summary at INFO: "NewPostReconciler:
         caught up N posts from cursor=(ready_at=…, id=…) to
         cursor=(…)".
  * `NewPostListener` — Quarkus `@ApplicationScoped` bean that
    LISTENs on the `new_post` channel and dispatches each payload
    to NewPostHandler (same handler the reconciler uses — single
    code path for both push and catch-up). Use the Quarkus
    pgjdbc-ng async LISTEN/NOTIFY support or the standard JDBC
    notification polling — pick at authoring time, document the
    rejected alternative in "Alternatives considered". The listener
    starts AFTER the reconciler finishes its initial catch-up
    (so a NOTIFY arriving mid-catch-up cannot be processed before
    the catch-up of older READY posts).
  * Tests:
      - `ProviderStateDaoIT` exercises the CAS update against a
        real Postgres (testcontainers or the existing dev compose
        DB): asserts the CAS is a no-op when the supplied cursor
        is `<=` the stored cursor (slow processor cannot roll
        back), and asserts the CAS succeeds and updates all four
        column values when the supplied cursor is `>`. Also
        asserts the first-boot insert behavior: a second
        `INSERT … ON CONFLICT (channel) DO NOTHING` against an
        existing row is a no-op.
      - `NewPostReconcilerIT` boots the Provider against a clean
        DB (or one with the V<N> migration applied), inserts N
        READY posts directly via JDBC with controlled
        (ready_at, id) values, runs the reconciler, asserts the
        NewPostHandler stub saw every row exactly once in
        (ready_at, id) order, and asserts the final cursor
        matches the last row's (ready_at, id). Then re-runs the
        reconciler (idempotency check) and asserts the handler
        saw zero additional rows.
      - `NewPostListenerIT` boots the Provider against a clean DB,
        inserts a READY post, emits `NOTIFY new_post` with the
        cursor payload via a JDBC NOTIFY from the test, and
        asserts NewPostHandler saw the row + the cursor advanced.
        Also asserts a DUPLICATE NOTIFY (same payload, listener
        still up) produces no additional handler call (idempotent
        via the cursor CAS).
- Out-of-scope MUST list:
    - any `new_price_snapshot` channel listener or asset cache
      (Tier-2 T2-H per decision D39 — best-effort,
      flush-on-Postgres-reconnect, no provider_state row, see
      design §1.3.3 + §2.9.1)
    - any `quarantine_review` channel listener, reconciler, or
      admin notifier (M2 territory per design §1.5 — only the
      `new_post` channel is wired in M1)
    - any real cache-invalidation or digest-recompute consumer
      inside NewPostHandler (T1-F territory; T1-C ships the stub
      handler only)
    - any Collector-side outbox emit, persist, FetchScheduler, or
      OutboxRehydrator (M1-028 territory; the two tickets share no
      runtime code)
    - any actual `post.status → READY` transition logic — that fires
      from T1-D's eval pipeline (Stage 5 sets status=READY +
      pg_notify); T1-C's tests insert READY rows directly via JDBC
    - any change to V1..V8 migrations already on disk
- Spec_refs (verify all anchors before citing):
  * docs/spec/architecture.md §Inter-service communication
  * docs/spec/architecture.md §Deployment topology (v1)
  * docs/spec/architecture.md §Architectural principles
  * docs/spec/schema.md §Operational (Provider state)
  * docs/spec/schema.md §Posts and derivatives (status state machine)
  * docs/spec/schema.md §Invariants (Invariant 5: no EVALUATING)
  * docs/spec/security.md §DB roles (per-table GRANT discipline)
  * docs/design/01-architecture.md §1.4.3 Startup-bean ordering
  * docs/design/01-architecture.md §1.5 Architectural principles
    (design-tier additions — high-water-mark catch-up implementation)
  * docs/design/02-schema.md §2.9.1 LISTEN/NOTIFY channels
  * docs/design/02-schema.md §2.9.2 provider_state
- decision_refs: D3, D4, D41

### M1-028 — Collector outbox (FetchScheduler + persist + OutboxRehydrator)

- blocked_by: [M1-007a, M1-008c, M1-022, M1-023]
  (Fetcher SPI from M1-007a; post + source tables from V7 via
  M1-008c; bootstrap-seeded source rows from M1-022 give the
  scheduler something to tick; RssFetcher from M1-023 is the only
  concrete Fetcher impl in M1 and gives the IT a real fetcher to
  schedule. The infochat-ssrf chain — M1-024/025/026 — is
  transitively pulled in via M1-023's blocked_by, no explicit
  listing needed.)
- complexity: medium
- risk: medium
- security_relevant: TRUE
  (the scheduler reads `source` rows and ticks fetchers; a buggy
  cadence or per-source isolation bug could over-poll a single
  source — quota / abuse-of-upstream surface. The persist step
  writes to `post` with `status='RAW'`; a missing per-stage flag
  default or a status-enum drift could leave posts permanently
  stuck. The rehydrator scans `WHERE status='RAW' AND NOT
  embedding_done` — a wrong predicate could re-enqueue READY posts
  and double-process them.)
- migration_touch: FALSE
- round_cap: 2
- files_budget: 8
- files_scope:
    - infochat-collector/src/main/java/io/infochat/collector/outbox/PostPersister.java
    - infochat-collector/src/main/java/io/infochat/collector/outbox/EvalQueueProducer.java
    - infochat-collector/src/main/java/io/infochat/collector/outbox/OutboxRehydrator.java
    - infochat-collector/src/main/java/io/infochat/collector/fetch/FetchScheduler.java
    - infochat-collector/src/test/java/io/infochat/collector/outbox/PostPersisterIT.java
    - infochat-collector/src/test/java/io/infochat/collector/outbox/OutboxRehydratorIT.java
    - infochat-collector/src/test/java/io/infochat/collector/fetch/FetchSchedulerIT.java
    - infochat-collector/src/test/resources/fixtures/outbox/<as needed>
- Scope:
  * `PostPersister` — narrow JDBC wrapper around the post INSERT.
    Takes a `NormalizedPost` + computed UID and inserts one row
    with `status='RAW'`, all four `*_done` flags FALSE, the four
    boolean outcome flags FALSE, empty tags array. UID is computed
    here (or by the caller — pick at authoring time, document in
    Implementation notes) per spec §UID derivation:
    `sha256(source_id || '|' || upstream_identifier)` lower-case
    hex, or the content-hash fallback when upstream_identifier is
    null. ON CONFLICT on `(source_id, upstream_identifier,
    fetched_at)` DO NOTHING — duplicate fetches in the same
    partition are silently deduped (the post table has both a
    `(uid, fetched_at)` and a `(source_id, upstream_identifier,
    fetched_at)` UNIQUE constraint — both are belt-and-suspenders
    against the same race).
  * `EvalQueueProducer` — SmallRye Reactive Messaging emitter that
    writes the post id to an in-memory channel named `eval-queue`
    (per CLAUDE.md §Stack: "SmallRye Reactive Messaging (in-memory
    channels v1, Kafka optional later)" + decision D4). The
    channel has NO consumer in T1-C scope — T1-D's eval workers
    subscribe later. Tests drain the channel via a test consumer
    to assert the post id was emitted. Document the consumer-less
    state explicitly in the ticket body so the reviewer doesn't
    flag the channel as an unused abstraction.
  * `OutboxRehydrator` Quarkus `@Startup` bean at @Priority(300)
    (per docs/design/01-architecture.md §1.4.3 Collector table).
    Steps:
      1. Scan `SELECT id FROM post WHERE status='RAW' ORDER BY
         fetched_at, id`. Per Invariant 5 (no `'EVALUATING'`
         status), `status='RAW'` IS the in-flight marker — any
         post that has completed stage 5 has `status='READY'` or
         `'QUARANTINED'` or `'NEEDS_REVIEW'`. The per-stage
         `*_done` flags tell the downstream eval workers WHERE to
         restart; the rehydrator's predicate does not look at
         them. Cite Invariant 5 in the ticket body; the §1.3.1
         and §1.4.3 design lines confirm this predicate post the
         2026-05-15 `spec:` correction.
      2. For each row, emit the post id to the eval-queue channel
         via EvalQueueProducer. This is the same channel the
         FetchScheduler's persist path emits to; the rehydrator
         and the live path share the producer.
      3. Log a one-line summary at INFO: "OutboxRehydrator:
         re-enqueued N RAW posts from prior run."
    The §1.3.1 / §1.4.3 design lines now match this predicate (a
    prior `spec:` commit on 2026-05-15 corrected the older
    `'EVALUATING'` mention); the ticket cites Invariant 5 as the
    primary authority and the design lines as confirming.
  * `FetchScheduler` Quarkus `@Startup` bean at @Priority(400)
    (per docs/design/01-architecture.md §1.4.3 Collector table).
    In T1-C scope this is a MINIMAL impl:
      - At startup: read all enabled `source` rows where
        `kind='rss'` (the only Fetcher kind landed in M1) and
        register one Quarkus `@Scheduled` per-source tick. The
        per-source cadence is profile-driven (per design §1.6 +
        §1.7); use a single CONFIG-driven default in T1-C
        (e.g. `infochat.fetch.rss.interval`, default `5m`) and
        defer per-source cadence overrides to a later ticket.
      - On each tick: invoke `RssFetcher.fetch(sourceId,
        identifier)` to obtain `List<NormalizedPost>`, then for
        each post call PostPersister + EvalQueueProducer in
        order. Persist BEFORE enqueue (the outbox discipline —
        spec §Pipelines + Architectural principle 2).
      - Failure handling in T1-C is intentionally minimal: log
        the exception per-source per-tick at WARN. D42's
        `source.last_fetch_at` / `last_success_at` /
        `consecutive_failures` columns and the active→failed
        transition land in a later ticket (admin source-status
        management is T2-B). Out-of-scope listing must call this
        out so the reviewer's negative-space check doesn't flag
        D42 wiring as missing in this ticket.
  * Tests:
      - `PostPersisterIT` boots the Collector against a real DB,
        seeds a `source` row, calls PostPersister with a fixture
        NormalizedPost, asserts the row exists with the expected
        UID and all status defaults. Also asserts the ON CONFLICT
        dedup: a second call with the same (source_id,
        upstream_identifier, fetched_at) is a no-op.
      - `OutboxRehydratorIT` seeds N posts with status='RAW' and
        a mix of `*_done` flag states, runs the rehydrator,
        asserts the EvalQueueProducer received exactly the
        not-fully-done posts in (fetched_at, id) order, and
        asserts a re-run after marking some posts READY produces
        the expected reduced re-enqueue set.
      - `FetchSchedulerIT` boots the Collector with a fixture
        source pointing at a Quarkus WireMock or in-process HTTP
        server serving a fixture RSS feed, lets one tick fire
        (via Quarkus's `Awaitility` or a `@QuarkusTest` clock
        helper), asserts N `post(status='RAW')` rows appear and
        N corresponding post ids land on the eval-queue channel.
- Out-of-scope MUST list:
    - any Stage 1 / Stage 2 / tagger / embedding / status→READY
      transition (T1-D territory; T1-C posts stay at status='RAW'
      for the duration of the IT)
    - any `pg_notify('new_post', …)` emit (also T1-D — fires from
      the eval pipeline's stage 5)
    - any per-source cadence override, max-page cap, Retry-After
      honoring, microprofile-faulttolerance integration (later
      ticket — T1-C ships a single global RSS interval)
    - any `source.status` machine transitions or D42 failure-counter
      wiring (`last_fetch_at`, `last_success_at`,
      `consecutive_failures`, `active→failed`) — admin source-status
      management is T2-B
    - any StreamSource / supervised-worker lifecycle (T3-C — Nostr
      lands the StreamSource SPI; T1-C only wires Fetchers)
    - any Provider-side reconciler, listener, or provider_state code
      (M1-027 territory)
    - any change to V1..V8 migrations already on disk
- Spec_refs (verify all anchors before citing):
  * docs/spec/architecture.md §Pipelines (outbox discipline:
    persist-before-enqueue)
  * docs/spec/architecture.md §Architectural principles (principle 2)
  * docs/spec/architecture.md §Ingest SPIs (Fetcher SPI contract,
    cross-source dedup, at-least-once outbox delivery)
  * docs/spec/schema.md §Posts and derivatives (status state machine,
    UID derivation)
  * docs/spec/schema.md §Invariants (Invariant 5: no EVALUATING;
    Invariant 6: TTL by partitioning)
  * docs/design/01-architecture.md §1.3.1 Polled Fetcher → outbox
    → eval pipeline
  * docs/design/01-architecture.md §1.4.3 Startup-bean ordering
  * docs/design/01-architecture.md §1.6 Concurrency and rate limiting
  * docs/design/02-schema.md §2.3.1 post (status enum + *_done flags)
- decision_refs: D3, D4, D38, D42

## Spec anchors verified (use ONLY these; others MUST be re-verified)

These were confirmed by `grep -n '^## \|^### ' <file>` at this
session's authoring time. Any spec_ref you cite that ISN'T in this
list, verify the anchor exists by reading the cited file before
using it. The clarity-preflight subagent will FAIL the ticket if a
spec_ref doesn't resolve.

  docs/spec/architecture.md §Inter-service communication        (line 33)
  docs/spec/architecture.md §Deployment topology (v1)           (line 111)
  docs/spec/architecture.md §Ingest SPIs                        (line 138)
  docs/spec/architecture.md §Pipelines                          (line 316)
  docs/spec/architecture.md §Architectural principles           (line 334)
  docs/spec/security.md §DB roles                               (line 943)
  docs/spec/schema.md §Identity and access                      (line 13)
  docs/spec/schema.md §Sources and tags                         (line 175)
  docs/spec/schema.md §Posts and derivatives                    (line 245)
  docs/spec/schema.md §Per-user state (scope-independent)       (line 351)
  docs/spec/schema.md §Per-scope state                          (line 366)
  docs/spec/schema.md §Operational                              (line 443)
  docs/spec/schema.md §Invariants                               (line 554)
  docs/design/01-architecture.md §1.1 Components                (line 14)
  docs/design/01-architecture.md §1.2 Module layout (Maven)     (line 89)
  docs/design/01-architecture.md §1.3 Key data flow: ingest     (line 120)
  docs/design/01-architecture.md §1.3.1 Polled Fetcher → outbox (line 127)
  docs/design/01-architecture.md §1.3.4 Eval pipeline workers   (line 209)
  docs/design/01-architecture.md §1.4.3 Startup-bean ordering   (line 433)
  docs/design/01-architecture.md §1.5 Architectural principles
                                  (design-tier additions)       (line 519)
  docs/design/01-architecture.md §1.6 Concurrency and rate limiting (line 568)
  docs/design/02-schema.md §2.3 Posts (ingest)                  (line 592)
  docs/design/02-schema.md §2.3.1 post                          (line 594)
  docs/design/02-schema.md §2.9 Notification channels & operational state (line 1300)
  docs/design/02-schema.md §2.9.1 LISTEN / NOTIFY channels      (line 1302)
  docs/design/02-schema.md §2.9.2 provider_state                (line 1326)

## Style requirements

Match M1-022 + M1-023 + M1-024 in docs/plan/m1/tickets/ — those are
the closest structural analogues for ticket-frontmatter shape and
runnable acceptance criteria. M1-008a/b/c are the closest analogues
for migration-tier scope and per-table GRANT discipline. Read those
once for style. Read docs/process/ticket-template.md once for the
canonical schema. Then write.

Length per ticket: M1-027 ~260-320 lines (the CAS cursor +
reconciler + listener trio carries real concurrency-correctness
argumentation). M1-028 ~240-300 lines (the Fetcher→persist→enqueue
+ rehydrator path is mechanically simpler but has more files and
more out-of-scope to enumerate).

Style points to preserve:
- Frontmatter follows docs/process/ticket-template.md schema exactly.
- Acceptance criteria are RUNNABLE grep / test / SQL assertions, not
  prose. Per the memory-feedback "Run the regex, don't paraphrase
  it" rule, execute every regex/grep predicate in the DoD against
  the inlined fragments before saving the ticket.
- For security_relevant tickets, every separable spec sentence
  becomes one acceptance item (verbatim, no summarizing) — per the
  memory-feedback "Transcribe spec promises" rule. M1-027's
  per-channel cursor semantics, first-boot ON-CONFLICT race
  guarantee, CAS rollback-prevention guarantee, and
  same-transaction-as-side-effect guarantee each get their own
  acceptance item — do not collapse them.
- spec_refs cite real §anchors that resolve.
- out_of_scope is specific and concrete, not generic.
- Body sections: Context, Definition of Done, Implementation notes,
  Big-picture notes, Out-of-scope expansion, Authorized test changes,
  Alternatives considered.

Use today's date for `created:` and `last_updated:`.

## Token-budget discipline

- DO read M1-022 + M1-023 once for style (migration + persist patterns).
- DO read M1-008c once (the post table schema you're writing against).
- DO read docs/process/ticket-template.md once.
- DO read docs/spec/architecture.md §Inter-service communication +
  §Pipelines + §Architectural principles in one pass.
- DO read docs/spec/schema.md §Operational + §Posts and derivatives
  + Invariant 5 in one pass.
- DO read docs/design/01-architecture.md §1.3 + §1.4.3 + §1.5 + §1.6
  in one pass.
- DO read docs/design/02-schema.md §2.9 in one pass.
- DO NOT spawn Explore or any other subagent.
- DO NOT pre-load the full docs/spec/ tree.
- DO NOT re-read sections you already loaded.

## After authoring both tickets

1. Eyeball each frontmatter parses cleanly.
2. Confirm both tickets' `out_of_scope` correctly punts the eval
   pipeline (T1-D), the messaging adapter + router (T1-E), the
   first commands (T1-F), the quarantine_review channel (M2), and
   the asset cache (T2-H). The reviewer's negative-space check is
   sharper when the exclusions are concrete and name the target
   group.
3. Confirm M1-027's migration filename matches the next free
   integer at this moment (re-grep `infochat-core/src/main/resources
   /db/migration/` and pick — V9 if M1-021 hasn't landed, V10 if
   it has).
4. Print a one-paragraph summary: "T1-C outbox/NOTIFY drafted as
   M1-027 (provider catch-up + provider_state + reconciler +
   listener) and M1-028 (collector FetchScheduler + outbox sink
   + rehydrator) under docs/plan/m1/tickets/. The two tickets are
   untracked on main and independent runnable-now (no blocking edge
   between them). The user runs /m1-tick start M1-027 (or M1-028
   first if running in parallel) when ready."
5. STOP. Do NOT commit the ticket files. Do NOT run /m1-tick start.

## What you do NOT do

- Do NOT commit any ticket file (drafts ride untracked through
  /m1-tick start).
- Do NOT run /m1-tick start or any other /m1-tick subcommand.
- Do NOT begin authoring T1-D, T1-E, or T1-F tickets. Those are
  separate sessions with their own JIT handoffs.
- Do NOT touch M1-019 / M1-020 / M1-021. Their `deferred_on` (or
  `blocks`) fields get updated by the T1-D / T1-E sessions or by
  the operator at end-of-T1, not by this session.
- Do NOT add Stage 1 / Stage 2 / tagger / embedding / status→READY
  transition logic. All of those are T1-D.
- Do NOT wire a real cache-invalidation or digest-recompute
  consumer in NewPostHandler — the stub is intentional and lets
  T1-C ship independently of T1-F.
- Do NOT add a `quarantine_review` reconciler or listener. That
  ships in M2 alongside the admin quarantine-review commands.
- Do NOT add a `new_price_snapshot` listener or asset cache. That
  ships in T2-H per decision D39.
- Do NOT spawn Explore or any other subagent.

## Workflow ground rules

- One ticket = one file under docs/plan/m1/tickets/M1-NNN-<slug>.md.
- Slug per docs/process/workflow.md §Naming conventions: lowercased
  ASCII [a-z0-9-], truncated to 30 chars, trailing hyphen trimmed.
- Drafts ride UNTRACKED through /m1-tick start.
- "M" prefix → /m1-tick flow; "process:" prefix → direct commit on
  main; "spec:" prefix → direct commit on main. This handoff itself
  is a `process:` commit; the tickets it authors are M-prefix
  commits later.

## Your immediate task when the user says "go"

1. Re-grep `infochat-core/src/main/resources/db/migration/` to
   confirm the next free integer for M1-027's provider_state
   migration (V9 if M1-021 hasn't landed, V10 if it has).
2. Re-grep `docs/plan/m1/tickets/` for `^id: M1-` to confirm the
   next free numeric IDs (M1-027/M1-028 expected; bump if a new
   ticket was authored since this handoff).
3. Decide Option A (2 tickets — default) vs. Option B (1 ticket)
   and document the choice in the first ticket's "Implementation
   notes."
4. Read M1-022 + M1-023 in docs/plan/m1/tickets/ once for style.
5. Read M1-008c in docs/plan/m1/tickets/ once (the post-table
   schema you're writing against).
6. Read docs/process/ticket-template.md once.
7. Read docs/spec/architecture.md §Inter-service communication +
   §Pipelines + §Architectural principles in one pass.
8. Read docs/spec/schema.md §Operational + §Posts and derivatives
   + Invariant 5 in one pass.
9. Read docs/design/01-architecture.md §1.3 + §1.4.3 + §1.5 + §1.6
   in one pass.
10. Read docs/design/02-schema.md §2.9 in one pass.
11. Write M1-027-provider-catch-up-new-post.md (or the slug your
    judgment lands on; ≤30 chars, lower-case ASCII, hyphenated).
12. Write M1-028-collector-outbox-fetch-schedu.md (or similar).
13. Print the summary. STOP.
```
