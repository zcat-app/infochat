---
id: M1-596
title: "Collector: INVESTIGATE — FetchScheduler wedges a kind's dispatch after a bulk identifier/host change collapses its sources onto one host; make the drain self-heal"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerHostPacingIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The per-host pacing POLICY itself (M1-466): host-min-interval keying,
    the heartbeat-quantized one-dispatch-per-host-per-window drain rate, and
    the burst-avoidance rationale are the intended design and stay UNCHANGED.
    This ticket does not loosen or re-key pacing — it ensures a kind that is
    mid-drain cannot be stranded indefinitely, i.e. the drain must always make
    progress or re-enumerate, without removing the throttle.
  - >-
    The D42 per-source failure ladder (recordFailure / consecutive_failures /
    active→failed / throttled admin notify) and SourceRepository. The wedge
    observed here left consecutive_failures=0 with NO fetch attempted, so it is
    NOT a D42-counter problem — the sources never reached tickOnce at all. Do
    not change the ladder or SourceRepository to compensate for a dispatch-side
    stall.
  - >-
    The xcancel/nitter degraded-placeholder handling (M1-588). That ticket makes
    a nitter fetch that DID happen signal failure; this ticket is about nitter
    fetches that never happened. Disjoint concerns, disjoint files.
  - >-
    Any change to the in-memory-vs-DB dueness model. The scheduler gates dueness
    on the in-memory lastTickByKind + per-kind interval and enumerates by
    status='active' (NOT by DB last_fetch_at). The operator's last_fetch_at reset
    is therefore inert to dispatch by design; do NOT add DB-last_fetch_at gating
    to enumeration to "honor" the reset — that is a separate design decision, not
    a bug fix.
  - >-
    Persisting the pending-drain queue or hostNextAllowed across a restart. The
    restart clearing the wedge is a symptom, not a target; the fix is to stop the
    wedge from forming, not to make the in-memory state durable.
acceptance:
  - >-
    INVESTIGATE and record the finding: determine whether FetchScheduler can wedge
    a single kind's dispatch when the due-set / host-pacing state changes underneath
    a mid-drain kind — concretely when a bulk operator edit collapses many of a
    kind's sources onto ONE host (27 nitter sources' identifier host changed
    rss.xcancel.com → nitter.net) while that kind is between/into a drain cycle. The
    suspect path is the M1-466 mid-drain contract: a kind KEEPS its pendingByKind
    entry (and is therefore EXCLUDED from re-enumeration at onTick:~255,
    `!pendingByKind.containsKey(kind) && shouldTick(...)`) and WITHHOLDS its
    lastTickByKind stamp until its pending queue fully empties (drainPending:~349).
    The investigation must state, with evidence from the code and a reproducing
    test, whether the queue can fail to ever empty (a permanent wedge — due sources
    never dispatched, no error logged, only a restart re-enumerates and drains) or
    only drains very slowly (~1 dispatch/host/heartbeat, appearing stalled over an
    8-minute window but eventually progressing). Write the conclusion into the
    ticket Notes before implementing.
  - >-
    IF a genuine wedge is reachable (queue never empties / lastTickByKind never
    re-stamped for a kind that still has due sources): make the drain SELF-HEAL —
    the scheduler must re-enumerate a kind's remaining due sources rather than
    staying permanently pinned to a stale pendingByKind snapshot, so that after the
    underlying source set changes the due sources are dispatched WITHOUT a collector
    restart. The self-heal must preserve the M1-466 burst-avoidance guarantee (a
    crowded single host still drains at the paced rate, never bursts) and must NOT
    re-blast an already-dispatched source within its kind-interval.
  - >-
    IF the investigation concludes there is NO reachable permanent wedge (the
    behavior is only slow-but-progressing drain), the ticket may resolve as
    documentation-plus-regression-test: add the reproducing FetchSchedulerHostPacingIT
    case that pins the observed slow-drain progression as intended, and record in
    Notes that the live 8-minute "stall" was the expected paced drain of 27
    single-host sources at ~1/heartbeat (with the 1m heartbeat + 20s
    host-min-interval + 10m nitter interval), not a defect. Either outcome is an
    acceptable close, but the code MUST match the documented conclusion.
  - >-
    Other kinds are provably unaffected while one kind is mid-drain: a heartbeat
    where kind A (nitter) is mid-drain must still enumerate and dispatch freshly-due
    kinds B/C (rss, youtube) — the live report was that rss and youtube kept
    fetching normally while nitter froze, so any fix must keep per-kind isolation
    intact (one kind's pending queue must never gate another kind's dispatch).
  - >-
    NAMED TEST: FetchSchedulerHostPacingIT gains a case that drives onTick across
    multiple heartbeats with N sources of one kind all resolving to the SAME host
    (mirroring the 27-nitter-on-nitter.net collapse), asserting the kind's due
    sources are ALL eventually dispatched across heartbeats without a
    restart/re-construction of the scheduler, and that a second kind on a distinct
    host dispatches on its own schedule throughout. Red-before/green-after if a
    code change lands; if the outcome is docs-only, the test pins the
    slow-but-progressing drain as intended behavior.
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetch/FetchSchedulerHostPacingIT.java
      — add the single-host-collapse drain case (N same-host sources of one kind
      all eventually dispatch across heartbeats without a restart; a second kind on
      a distinct host is unaffected). Uses the existing FetchScheduler test seams
      (lastTickByKind(), hostNextAllowed(), pendingByKind()) and the injected Clock,
      per M1-498.
  preserves:
    - all tests currently green on main
    - >-
      the existing M1-466 FetchSchedulerHostPacingIT assertions (per-host deferral,
      the withheld lastTickByKind stamp until the queue drains, and pacing=off
      immediate-drain equivalence) — the self-heal must not regress them.
    - >-
      FetchSchedulerKindFilterIT / FetchSchedulerClockIT per-kind interval and
      orphan-warning behavior (untouched).
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D42
  - D38
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-596: FetchScheduler dispatch stall after a bulk source identifier/host change

## Context

Observed live 2026-07-08, during (and likely provoked by) an atypical operator
action, so this is filed as an **INVESTIGATE**, not a confirmed defect with a known
fix. Frame the priority accordingly: operators do not normally rename 27 source
identifiers and reset their `last_fetch_at` all at once, so this may be low
priority. But the *shape* of the failure — **due sources silently never dispatched,
`consecutive_failures=0`, NO error logged, and only a collector restart clears it**
— is worth understanding, because a dispatch path that can wedge with no signal is
a bad failure mode regardless of how it was reached.

### What was done to the running collector

In one burst, mid-fetch-cycle, an operator:

1. Bulk-updated 27 `nitter`-kind sources' `identifier`, changing the host from
   `rss.xcancel.com` to `nitter.net` (the xcancel feeds had gone dead — see M1-588).
2. Reset those 27 rows' `last_fetch_at` to force them "due".

### What was observed

- The collector's `FetchScheduler` **stopped dispatching `nitter`-kind fetches**:
  the max `nitter` `last_fetch_at` **froze** at a single timestamp.
- ~24 nitter sources were "due" by the operator's mental model (DB `last_fetch_at`
  older than the 10m nitter interval) but were **not dispatched for 8+ minutes**.
- `consecutive_failures = 0` on those rows and **no error was logged** — the sources
  never reached a fetch attempt at all.
- **Other kinds kept fetching normally** (`rss`, `youtube`), so this was a
  single-kind stall, not a whole-scheduler halt.
- `nitter.net` itself was fast (67ms), so it was **not** a hung/slow HTTP request.
- A **collector restart fully cleared it**: on restart the scheduler re-enumerated
  all due sources and drained them.

### Where the suspicion points

The relevant machinery is the M1-466 per-host pacing / per-kind drain state in
`FetchScheduler`:

- `pendingByKind` (`FetchScheduler.java:204`) — a per-kind queue of due sources
  "enumerated this fetch cycle but not yet dispatched … drained across heartbeats
  as per-host pacing frees each source's host."
- The **mid-drain re-enumeration exclusion** (`onTick`, ~line 255):
  `if (!pendingByKind.containsKey(kind) && shouldTick(kind, now))` — a kind that
  still has a `pendingByKind` entry is deliberately **not** re-enumerated; its
  remaining due sources finish first (M1-466).
- The **withheld tick stamp**: `drainPending` (~line 349) stamps
  `lastTickByKind` and removes the kind from `pendingByKind` **only when the queue
  empties**. Until then the kind stays mid-drain indefinitely.
- Per-host pacing (`hostNextAllowed`, `drainPending` `continue` at ~line 339): a
  source whose host is still cooling is skipped and left in the queue.

The bulk edit collapsed all 27 sources onto **one** host (`nitter.net`). Combined
with the config in force (`heartbeat-interval=1m`, `host-min-interval=20s`,
`nitter.interval=10m`), the drain is heartbeat-quantized to ~one dispatch per host
per heartbeat, so a single-host crowd of 27 takes ~27 heartbeats to drain — and for
that entire span the kind is mid-drain and **never re-enumerated**. The open
question the investigation must answer: is the live observation (a) merely that very
slow paced drain (which would still advance `last_fetch_at` ~1/min, contradicting
the *frozen* observation), or (b) a **genuine permanent wedge** where the queue never
empties, `lastTickByKind` is never re-stamped, and the kind is pinned to a stale
snapshot until restart? The frozen `last_fetch_at` + zero dispatch over 8 minutes
leans toward (b) and is the thing to explain.

## The investigation / fix

1. **Reproduce and classify.** Drive `onTick` across heartbeats with N same-host
   sources of one kind (the seams `pendingByKind()` / `hostNextAllowed()` /
   `lastTickByKind()` and the injected `Clock` make this deterministic — see
   FetchSchedulerHostPacingIT / M1-498). Determine whether the queue can fail to
   ever empty vs. drains slowly-but-surely. Consider the bulk-edit interaction: the
   `SourceRow`s in a mid-drain queue carry the identifier captured **at enumeration
   time**; if enumeration happened before the DB host change, the queued rows pace
   under the *old* host while the DB shows the new one — check whether that (or any
   `hostNextAllowed` interaction) can pin the queue.
2. **If a permanent wedge exists:** make the drain self-heal — a kind must not stay
   mid-drain forever against a stale snapshot; re-enumerate its remaining due sources
   so a source-set change is picked up without a restart, while preserving M1-466
   burst-avoidance and not re-blasting an already-dispatched source within its
   kind-interval.
3. **If no permanent wedge exists:** resolve as docs-plus-regression-test — pin the
   slow-drain progression as intended and record that the live "stall" was expected
   paced drain of a single-host crowd.

Either way the committed code must match the documented conclusion, and per-kind
isolation (one kind mid-drain never gates another kind) must hold.

## Out-of-scope

See frontmatter. Notably: the M1-466 pacing policy itself (unchanged), the D42
failure ladder and SourceRepository (the wedge is dispatch-side, not counter-side),
M1-588's degraded-placeholder handling (fetches that never happened, not fetches that
returned a stub), and any move to DB-`last_fetch_at` dueness gating (the reset is
inert to dispatch by design).

## Notes

- **Provenance.** Live-observed 2026-07-08, provoked by an atypical bulk source
  manipulation (rename 27 identifiers + reset last_fetch_at at once). Not a red-team
  finding. Priority is judgment-dependent: the trigger is unusual, but a silent
  dispatch wedge with no error and restart-only recovery is worth pinning down.
- **Record the conclusion here** (permanent wedge vs. slow-but-progressing drain)
  before landing code, per the acceptance criteria — the code path chosen depends on
  which it is.
- **Config in force at observation:** `infochat.fetch.heartbeat-interval=1m`,
  `infochat.fetch.host-min-interval=20s`, `infochat.fetch.nitter.interval=10m`.
