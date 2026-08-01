---
id: M1-706
title: "Profile bundle: eval queue depth and summary worker count"
status: done
created: 2026-07-27
last_updated: 2026-08-01
blocked_by: []
files_budget: 10
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/EvalQueueProducer.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/EvalQueueOverflowIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerTest.java
  - docs/design/01-architecture.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The outbox discipline itself — persist-before-enqueue, the
    `status='RAW'` contract, `OutboxRehydrator`'s startup drain and its
    per-emit readiness poll (M1-551), and `Stage1Worker.reEmitStaleRaw`'s
    sweep. Those are the recovery net this ticket relies on; it changes
    the queue's depth and the overflow's blast radius, never the
    recovery contract.
  - >-
    `infochat.eval.stale-raw.poll-interval` / `.age` values. Retuning the
    sweep to compensate for overflow would be treating the symptom;
    leave both keys at their current per-profile values.
  - >-
    The digest schedule itself — slot kinds, window arithmetic, the
    deterministic per-group stagger (`DigestScheduler.staggerOffset`),
    missed-slot recording, and the digest cache. This ticket bounds how
    many slots run CONCURRENTLY, not when any slot fires.
  - >-
    `infochat.context-hard-limit` and
    `infochat.llm.{summarizer,chat}.max-concurrency` — three further
    §5.7 rows naming keys that do not exist. None has a spec commitment
    behind it (the profile bundle names only "summary worker count" and
    "eval queue depth"), so they are a §5.7 doc decision, not code owed.
  - >-
    Migrating the eval channel to Kafka or any other broker. v1 is
    SmallRye in-memory per CLAUDE.md §Stack; this ticket configures that
    channel, it does not replace it.
  - any Flyway migration
acceptance:
  - >-
    An operator-facing key sets the eval-queue depth and is declared per
    profile in the Collector's application.properties, so the queue's
    capacity is a configured value rather than SmallRye's implicit
    default. `docs/spec/architecture.md` §Hardware profiles names "eval
    queue depth" as part of the profile bundle; after this ticket it is.
  - >-
    An integration test drives the eval queue past its configured depth
    and asserts the outcome the design claims: no post is lost (every
    over-depth post stays recoverable through the existing
    `status='RAW'` sweep) and no producer path is left in a
    version-dependent state. Every emit site in §Census is covered by
    the test or explicitly disposed in the ticket.
  - >-
    `docs/design/01-architecture.md` §1.6's "If full, fetcher blocks
    (back-pressure to feed schedulers)" is replaced with the semantics
    that actually ship, and its GAP marker removed. If blocking
    back-pressure is genuinely unreachable through a SmallRye `Emitter`,
    the design records the reachable semantics and why — it does not
    keep claiming a behavior the code cannot have.
  - >-
    An operator-facing key bounds concurrent digest/summary slot
    dispatch, declared per profile in the Provider's
    application.properties, replacing the unbounded
    `Executors.newVirtualThreadPerTaskExecutor()` fan-out at
    `DigestScheduler.java:78`.
  - >-
    DigestSchedulerTest proves the bound holds: with the worker count
    set to N, more than N due slots in one tick never run more than N
    concurrently, and every due slot still fires (bounded, not dropped).
  - >-
    `docs/design/05-llm-and-embeddings.md` §5.7's status note and
    `docs/design/01-architecture.md`'s digest worker-count paragraph are
    updated for the two rows this ticket closes; the rows for keys still
    missing keep their status note.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/EvalQueueOverflowIT.java
      — emit past the configured depth; assert no post is lost and the
      producer path survives (the file name is a suggestion, the
      behavior is the commitment).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerTest.java
      — concurrency bound holds under more due slots than workers, and
      no due slot is dropped.
  preserves:
    - >-
      OutboxRehydratorReadinessTest and both OutboxRehydrator ITs — the
      startup readiness poll is the guard M1-551 added after two live
      SRMSG00034 failures; its assertions must keep pinning the
      poll-before-first-emit behavior on the path the rehydrator
      actually takes.
    - >-
      The existing DigestScheduler tests for stagger, missed slots and
      window arithmetic — the firing schedule is unchanged.
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Hardware profiles
  - docs/spec/deployment.md §Configuration surface (spec level)
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 654
      removed: 51
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-08-01
  verdict: PASS
  warnings:
    - >-
      Self-check: census re-grepped live (5 sites match). Kind6Handler:167
      and NostrStreamSource:466 disposed NO-CHANGE — emit escapes land in
      NostrStreamSource.deliverOne's RuntimeException catch (SafeLog, worker
      survives, post stays RAW for the sweep). Stage1Worker:245 disposed
      CONFIRM — Quarkus scheduler logs-and-continues; later sweeps unaffected.
  blockers: []
outline_file: target/m1-tick-outline-M1-706.md
escalation_reason:
---

# M1-706: Profile bundle — eval queue depth and summary worker count

## Context

`docs/spec/architecture.md` §Hardware profiles commits the profile to
driving "context-window size, default chat / embedding model, eval
concurrency, vector-index choice, **summary worker count, eval queue
depth**". `docs/spec/deployment.md` §Configuration surface and
`docs/spec/llm.md` §Hardware profile contract repeat both. Neither of the
last two exists. Verified: no `infochat.summary.workers` and no
`infochat.eval.queue-size` anywhere in main sources or either service's
`application.properties`.

**Eval queue depth.** `EvalQueueProducer` holds an
`@Channel("eval-queue") @Broadcast Emitter` with no depth configuration,
so it runs on SmallRye's implicit default buffer. Its own javadoc
(`EvalQueueProducer.java:65-72`) records the consequence: emitting past
the buffer makes the next `send` throw `SRMSG00034`. That is not
theoretical — `OutboxRehydrator.java:153` records two live mid-drain
`SRMSG00034` occurrences (2026-07-03/04) which is why the rehydrator
polls `hasDownstreamRequests()` before its first emit. That guard covers
the rehydrator's own emits only.

The blast radius today, verified per site: `FetchScheduler.java:552`
emits inside a `try` that logs, admin-notifies and abandons the rest of
that source's tick (`:553-570`); the persisted rows stay `RAW` and
`Stage1Worker.reEmitStaleRaw` re-enqueues them within
`infochat.eval.stale-raw.age`. So posts are not lost — but throughput
degrades, operators get paged, and `docs/design/01-architecture.md`
§1.6's "If full, fetcher blocks (back-pressure to feed schedulers)" is
wrong on both halves (it neither blocks nor back-pressures). §1.6 carries
a GAP marker recording this.

**Summary worker count.** `DigestScheduler.java:78-79` dispatches slots
on `Executors.newVirtualThreadPerTaskExecutor()` — no count, no key, no
semaphore. The deterministic per-group stagger
(`DigestScheduler.staggerOffset`) spreads groups across the window and is
the reason this has not bitten yet, but it bounds nothing: groups that
hash to the same stagger minute fire together, and on a `pi` profile a
handful of concurrent digest generations is exactly what the profile
bundle exists to prevent.

Both rows come from the same spec sentence and the same decision — give
the profile bundle the two throughput bounds it promises — which is why
they are one ticket despite spanning two services.

Recorded in the doc-drift audit 2026-07-27 (`.scratch/doc-audit.md` §A5).

## Census

Every producer into `eval-queue` must be disposed, because each one's
caller decides what an overflow does:

    grep -rn "evalQueueProducer" --include=*.java infochat-collector/src/main

| Site | Disposition |
|---|---|
| `fetch/FetchScheduler.java:552` | covered — verified to catch, log, admin-notify, leave rows `RAW`; confirm the configured depth changes only when it fires, not what it does |
| `outbox/OutboxRehydrator.java:245` | covered by the M1-551 readiness poll; must stay covered (see `test_plan.preserves`) |
| `eval/stage1/Stage1Worker.java:245` (`reEmitStaleRaw`) | fix or confirm — this is the recovery sweep itself; an overflow escaping a `@Scheduled` method must not stop later sweeps |
| `stream/nostr/Kind6Handler.java:167` | confirm at `start` — trace what an escape does to the relay worker before deciding fix vs no-change |
| `stream/nostr/NostrStreamSource.java:466` (`deliverFor`) | confirm at `start` — same trace; a killed stream worker would be a worse outcome than a dropped tick |

A site that needs no change is still disposed — the point is that all
five were traced, not that all five are edited.

## Acceptance

- The eval-queue depth is set by an operator-facing key, declared per
  profile in the Collector's `application.properties`.
- An integration test drives the queue past that depth and asserts no
  post is lost and no producer path is left version-dependent; every
  §Census site is covered or disposed.
- `docs/design/01-architecture.md` §1.6 states the overflow semantics
  that actually ship (and why, if blocking back-pressure is unreachable
  through an `Emitter`); its GAP marker is removed.
- Concurrent digest/summary slot dispatch is bounded by an
  operator-facing key, declared per profile in the Provider's
  `application.properties`, replacing the unbounded executor.
- `DigestSchedulerTest` proves the bound holds and that no due slot is
  dropped to honor it.
- The §5.7 status note and the §1.6 worker-count paragraph are updated
  for the two rows this closes; rows for still-missing keys keep theirs.
- `mvn verify` from the repo root is green.

## Out-of-scope

The outbox contract (persist-before-enqueue, `RAW` recovery, the
rehydrator's readiness poll, the stale-RAW sweep) is the safety net this
ticket leans on and is not modified — including the sweep's interval and
age keys, which must not be retuned to paper over overflow. The digest
firing schedule (slot kinds, windows, stagger, missed-slot handling) is
untouched; only concurrency is bounded. `infochat.context-hard-limit` and
the two `infochat.llm.*.max-concurrency` rows in §5.7 name keys that also
do not exist but have no spec commitment behind them — they stay a doc
decision. No broker migration; the channel stays SmallRye in-memory.

## Notes

- **The per-profile values already exist as design.**
  `docs/design/01-architecture.md` §1.7's profile table records
  "Eval queue size" 1024 / 256 / 64 / 4096 and "Periodic-digest workers"
  4 / 2 / 1 / 8 for laptop / vps / pi / remote-llm. Use those rather
  than inventing a spread; if any of them looks wrong for the shipped
  pipeline, say so at `start` instead of quietly picking another number
  (that is the failure mode the §4.9 audit caught elsewhere).

- **Two plausible mechanisms for the depth**, both worth weighing at
  `start` rather than assuming: SmallRye's emitter buffer-size
  configuration (config-driven, profile-overridable, no annotation
  change) or `@OnOverflow` on the injected `Emitter` (explicit strategy,
  but its `bufferSize` is a compile-time literal, which fights the
  "profile-driven" requirement). Whichever is chosen, the requirement is
  that an operator can set the depth by property.

- **"Blocking back-pressure" may not be reachable.** A SmallRye
  `Emitter.send` does not block; the strategies available are buffer,
  drop, latest, fail. If that holds after checking, the honest close is
  to correct §1.6 to the reachable semantics with the reason — the
  acceptance item is worded to permit that, and it is not licence to
  quietly delete the requirement.

- **The stale-RAW sweep is why this is a throughput ticket, not a
  data-loss ticket.** Nothing here is a correctness emergency; the
  reason to do it is that the queue's capacity is currently an
  undocumented framework default on every profile, including `pi`.

- **Digest bound shape.** A fixed-size pool or a semaphore around the
  existing virtual-thread dispatch both satisfy the bound; prefer
  whichever leaves `fireSlot`'s error logging (`DigestScheduler:200-`)
  and the observer-failure behavior unchanged.
