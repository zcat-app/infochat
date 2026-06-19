---
id: M1-371
title: "collector: give stream dispatch keys a distinct type so they cannot collide with FetchScheduler source keys"
status: done
created: 2026-06-14
last_updated: 2026-06-19
clarity_check:
  date: 2026-06-19
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 13
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceSupervisor.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceRegistration.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceDrainHandle.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamDispatchKey.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The FetchScheduler polled-source keyspace itself — unchanged; this ticket only makes the stream-supervisor surface refuse a foreign bare-long key, it does not unify the two keyspaces.
  - The worker lifecycle, drain/flush budget, and registration semantics of StreamSourceSupervisor — unchanged apart from the key parameter/handle type.
  - "The StreamSource SPI and the core ingest keyspace stay a bare long. infochat-core's StreamSource.start(long dispatchKey, ...) and NormalizedPost's `long dispatchKey` record component MUST NOT change — the dispatch key is stamped onto every delivered post and persisted as a long. The typed handle is a collector-side supervisor wrapper only; StreamSourceRegistration unwraps it to handle.value() at the source.start(...) call. No change may cross into infochat-core."
acceptance:
  - "A distinct stream-dispatch handle type (a small value wrapper, e.g. `record StreamDispatchKey(long value)`) is introduced in the collector stream package. Because StreamSourceSupervisor's public surface and the stream.nostr subpackage both reference it, it is `public` (a package-private nested type is not visible to stream.nostr)."
  - "Every StreamSourceSupervisor surface that is keyed on the dispatch key takes/returns the handle type rather than a bare long: register, stop, drainAll's returned Map key, eventsLostOnShutdown, and the internal `registrations` map. eventsLostOnShutdown is included deliberately — it reads `registrations.get(dispatchKey)`, and a bare-long argument against a handle-keyed map would still COMPILE (Map.get takes Object) but silently always miss and return 0; the parameter must become the handle so the lookup is correct, not just compiling."
  - "A polled FetchScheduler source key (a bare long) can no longer be passed to supervisor.stop / register / eventsLostOnShutdown — it is a compile-time type error. The Nostr registrar mints typed handles (StreamDispatchKey from its 1-based counter); dispatchKeyBySource, the relay-health transition path (handleTransition/handleTerminal), and the auto-disable observer (onSourceDisabled) all carry the handle; the supervisor.stop call sites pass handles."
  - "StreamSourceRegistration and StreamSourceDrainHandle carry the handle type internally (field, constructor, dispatchKey() accessor), BUT StreamSourceRegistration unwraps to the bare long when calling the SPI: source.start(dispatchKey.value(), filterSpec, deliver). The SPI signature is untouched (see out_of_scope)."
  - "The 'collision is only documented' caveat in StreamSourceSupervisor.stop's javadoc is replaced by the type-level guarantee (or trimmed to reflect that the type now prevents it)."
  - "The existing stream tests in infochat-collector/src/test/java/app/zcat/infochat/collector/stream (and its nostr subdir) are updated to the typed handle at every supervisor call site, and a test confirms a typed handle round-trips through register→stop. The seven affected test files are listed in Notes."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream (typed-handle call-site updates across StreamSourceSupervisorTest, StreamSourceSupervisorIT, and the five nostr ITs listed in Notes + a register→stop round-trip assertion, folded into StreamSourceSupervisorTest unless a new file reads cleaner)
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 119
      removed: 57
escalations:
  - date: 2026-06-19
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      FILES-BUDGET-PLAUSIBLE: FAIL — the compile-time type change ripples to
      two files absent from files_scope (verified at source 2026-06-19):
      StreamSourceRegistration.java (private final long dispatchKey, the
      long dispatchKey() accessor, source.start(dispatchKey,...) at line 61)
      and StreamSourceDrainHandle.java (long dispatchKey() delegating to
      registration). The new value-wrapper type also needs a scope decision
      (own file vs. nested record inside StreamSourceSupervisor).
revisions:
  - date: 2026-06-19
    reason: refine ticket spec (clarity-fail rework) — scope/budget undercount + core-boundary design pin
    prior_values: |
      files_budget was 5; files_scope listed only StreamSourceSupervisor.java,
      NostrStreamSource.java, and the test dir. Three production files were
      missing (StreamSourceRegistration.java, StreamSourceDrainHandle.java, and
      the new StreamDispatchKey.java) and the budget did not account for the
      seven test files that key on bare longs — real touch set is 5 production
      + 7 test = 12 files. Raised files_budget to 13 (1 slot headroom) and added
      the three production paths to files_scope. Two issues the clarity FAIL did
      not name, found by reading the data flow: (1) the dispatch key crosses into
      infochat-core — StreamSource.start(long,...) stamps it onto NormalizedPost's
      `long dispatchKey` component — so the handle MUST be unwrapped to long at the
      source.start boundary and the SPI/core keyspace stay long (new out_of_scope
      item + acceptance item); (2) StreamSourceSupervisor.eventsLostOnShutdown(long)
      also keys the registrations map and would silently miss (Map.get(Object)
      compiles) if left on bare long — added to the enumerated surface in
      acceptance. Acceptance item 1 was split into explicit per-surface items so
      the reviewer can check each. clarity_check cleared (described the old ticket).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-371: distinct stream dispatch-key type

## Context

Deep-review v7 (opus-48) collector finding **F3** (MAINTAINABILITY). Verified at
source 2026-06-14 — **real but latent, severity adjusted to low**:

`NostrStreamSource`'s registrar mints `nextDispatchKey` from 1
(`.../stream/nostr/NostrStreamSource.java:399-444`) and `FetchScheduler` mints an
independent 1-based source keyspace; both are bare `long`. `StreamSourceSupervisor.stop(long)`
(`.../stream/StreamSourceSupervisor.java:119-138`) documents the collision in
prose: a key minted in another keyspace passed here "would collide with, and
stop, an unrelated stream this supervisor happens to hold under the same numeric
key."

**No live collision exists today** — `supervisor.stop` is only ever called from
the Nostr auto-disable path with keys from the stream keyspace; `FetchScheduler`
never touches the supervisor. So this is a documented footgun guarded only by
caller discipline, not an active bug. A distinct handle type makes the wrong call
a compile error and removes the prose caveat. Worth doing for type-safety;
**not a beta blocker.**

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- **The handle type.** A minimal value wrapper (`record StreamDispatchKey(long value)`)
  is enough; the goal is type distinctness, not a new abstraction layer. It is a
  standalone `public` record in `app.zcat.infochat.collector.stream` (own file,
  `StreamDispatchKey.java`) rather than a nested type, because `NostrStreamSource`
  in the `stream.nostr` subpackage references it and the supervisor's `public`
  register/stop/drainAll/eventsLostOnShutdown surface exposes it — a package-private
  nested type would not be visible to either. Records get value-based equals/hashCode
  for free, so the wrapper works directly as a `ConcurrentHashMap` key.

- **The core boundary is the whole point of the refine.** The dispatch key is not
  supervisor-internal: `StreamSource.start(long dispatchKey, ...)` (infochat-core
  SPI) receives it and stamps it onto every delivered post; `NormalizedPost` carries
  it as a `long dispatchKey` record component and it is persisted as a long. So the
  typed handle is a **collector-side supervisor wrapper only**. `StreamSourceRegistration`
  holds the handle but calls `source.start(dispatchKey.value(), ...)`, unwrapping at
  the SPI boundary. Do NOT change `StreamSource`, `NormalizedPost`, or anything else
  in infochat-core. (If you find yourself editing a file under `infochat-core/`, stop
  — that is the scope-leak this refine exists to prevent.)

- **Keep `dispatchKeyBySource` keyed by source UUID** (per the original finding);
  its VALUE becomes the handle (`Map<UUID, StreamDispatchKey>`) since the registrar
  now mints handles.

- **eventsLostOnShutdown(long) is a silent-miss trap.** It does
  `registrations.get(dispatchKey)` — left on a bare long against a handle-keyed map
  it compiles (Map.get takes Object) but always returns 0. Its parameter must become
  the handle. `StreamSourceSupervisorTest` already asserts on its return value
  (lines 59, 73), so a regression here would surface — but only if those call sites
  are migrated to the handle, which they must be.

- **The worker SPI side stays on long.** `NostrStreamSource` is both the registrar
  (the nested class that mints keys and calls the supervisor) and the worker (the
  `StreamSource` impl whose `start(long dispatchKey, ...)` the registration invokes).
  Only the registrar half changes; the worker's `start(long, ...)` signature is the
  SPI and is unchanged.

- **Affected test files (all under the in-scope `.../collector/stream` dir).** Seven
  files call a keyed supervisor method with a bare long and must migrate to the
  handle: `StreamSourceSupervisorTest.java`, `StreamSourceSupervisorIT.java`,
  `nostr/NostrStreamSourceIT.java`, `nostr/NostrDegradationIT.java`,
  `nostr/NostrDedupIT.java`, `nostr/NostrStreamSourceVerificationIT.java`,
  `nostr/NostrSourceDisabledStopsWorkerIT.java`. (`nostr/NostrStreamSourceTest.java`
  calls only the SPI no-arg `source.stop()` and does NOT need changing.)
