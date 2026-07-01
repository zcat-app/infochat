---
id: M1-539
title: "live-smoke scenario runner + InMemory backend (Phase 4a)"
status: draft
created: 2026-07-01
last_updated: 2026-07-01
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/live/
  - infochat-provider/src/test/resources/scenarios/
complexity: high
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The SimpleX WebSocket backend binding and the actual live drive against a
    real simplex-chat subprocess (Phase 4b, a follow-up ticket). That runs
    out-of-process, async / observed-client-side, and needs a live host with
    provisioned identities — it cannot be @QuarkusTest-covered. This ticket
    delivers only the CI-testable substrate: the scenario format, the runner
    core, and the InMemory backend binding. The runner core's ConversationBackend
    SPI is the seam a later SimpleX binding drops into.
  - >-
    Any src/main change. The runner and scenarios are test-scope
    (infochat-provider/src/test). DevTerminalHarness (M1-414, src/main,
    build-gated) is the prod-classpath bridge for a running deployment; this is
    NOT that — no production-classpath addition, no new build gate, no new bean.
  - >-
    New business logic or a re-test of GoldenPathJourneyIT's lifecycle
    assertions. The runner is the substrate; a scenario is data. The InMemory IT
    proves the runner executes a scenario and captures per-step latency — NOT
    that the lifecycle logic is correct (that stays in GoldenPathJourneyIT + the
    per-scenario ITs; D-live-5: no business-logic backfill for the live run).
  - >-
    The data harness (M1-536 reset / M1-537 seed / M1-538 adversarial inject).
    Those reset/seed the DB for the live run; this ticket drives conversation
    scenarios over the adapter seam and does not touch them.
  - >-
    Signal (Phase 5) and the optional /testcase skill promotion (Phase 6 —
    D-live-4: promote only after 2–3 scenarios prove out).
  - >-
    A movable application Clock or scheduler-firing tests. Time-relative
    behaviour uses seeded timestamps (live-e2e §7, prod Clock is hardcoded
    Clock.systemUTC()); the runner asserts conversation replies + latency, not
    scheduler firing.
acceptance:
  - >-
    A declarative scenario format (a resource file under
    infochat-provider/src/test/resources/scenarios/) expresses an ordered list of
    steps, each pairing a send (scope kind + contact/group id + text) with an
    expect (a match predicate over the bot's reply — at minimum substring, ideally
    regex — plus a per-step timeout). The format carries no Java/adapter types; it
    is data an operator can edit without recompiling.
  - >-
    A backend-agnostic runner executes a parsed scenario against a pluggable
    ConversationBackend abstraction (send a step; await the matching reply within
    the step timeout; record the elapsed time). The InMemory binding implements
    ConversationBackend over InMemoryAdapter (deliverDm / deliverGroupMention →
    replies observed via the adapter's finalizedBodies() / lastReply() seam),
    hiding the sync (InMemory) vs async-observed (live) difference behind a
    poll-until-match-or-timeout wait so the SAME scenario runs on either backend.
  - >-
    A new IT — ScenarioRunnerIT (or equivalent) — loads a golden-path smoke
    scenario file, runs it through the InMemory backend under the inmemory
    deployment shape (D46, infochat.adapters=inmemory), asserts every expect step
    matches within its timeout, and surfaces a per-step latency it collected. The
    test is green.
  - >-
    The runner core and scenario-format types carry NO InMemory-specific type in
    their public API — ConversationBackend is the only seam. Verifiable: the
    runner-core package imports no InMemoryAdapter; only the InMemory *binding*
    class does. This is the property that makes a later SimpleX binding a drop-in.
  - "`mvn -pl infochat-provider verify` is green (the new IT passes; no pre-existing test regresses)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/live/ (runner core + ConversationBackend SPI + InMemory binding)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/live/ScenarioRunnerIT.java (runs a scenario file through InMemory)
    - infochat-provider/src/test/resources/scenarios/ (the golden-path smoke scenario file)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/plan/live-e2e/README.md §Phase 4 — SimpleX live-smoke driver
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/verification.md §Test layers
decision_refs:
  - D46
---

# M1-539: live-smoke scenario runner + InMemory backend (Phase 4a)

## Context

Phase 3 of the live-e2e plan (the reset & data harness — M1-536/537/538) is
done; Phase 4 is the live run itself. Its first buildable piece
(`docs/plan/live-e2e/README.md` §5 Phase 4, D-live-4 "build the scenario format +
runner first") is a **backend-agnostic scenario runner**: ordered
`send → expect(match, timeout)` steps with per-step latency capture, so the SAME
declarative scenario runs against the InMemoryAdapter in CI and — later — a real
SimpleX transport on a host. This ticket delivers the CI-testable substrate: the
scenario format, the runner core, and the InMemory backend binding, proven by an
IT that runs a golden-path smoke scenario end-to-end through InMemoryAdapter. The
SimpleX WebSocket binding + the real-transport drive is Phase 4b (a follow-up
that needs a live host and cannot be @QuarkusTest-covered). The value over the
existing `GoldenPathJourneyIT` is a *portable, declarative* scenario (data, not
hardcoded Java) and *latency capture* — the substrate the live run reuses, not a
re-test of the lifecycle logic (all 15 scenarios are already IT-covered, D-live-5).

## Acceptance

See frontmatter. A declarative scenario format under
`src/test/resources/scenarios/`, a runner that drives a pluggable
`ConversationBackend`, an InMemory binding over `InMemoryAdapter`, and an IT that
runs a golden-path smoke scenario through InMemory asserting each `expect` step
within its timeout and capturing per-step latency. The runner core's public API
stays free of InMemory-specific types so a later SimpleX binding is a drop-in.
`mvn -pl infochat-provider verify` is green.

## Out-of-scope

See frontmatter. Not the SimpleX WS binding / real live drive (Phase 4b), not any
src/main change, not new business logic or a re-test of GoldenPathJourneyIT, not
the data harness (M1-536/537/538), not Signal (Phase 5) or the /testcase skill
(Phase 6), not a movable Clock.

## Notes

- **Two execution contexts, one scenario (the design crux the plan-writer must
  resolve).** InMemory is **in-JVM, synchronous** — `deliverDm()` dispatches on
  the calling thread and the reply is readable immediately via `finalizedBodies()`
  / `lastReply()` (`InMemoryAdapter`). Live SimpleX is **out-of-process,
  async/observed-client-side** — the bot sends a frame to the simplex-chat
  subprocess, which eventually broadcasts to a connected client the harness must
  poll (`docs/plan/live-e2e/README.md` §Phase 4 caveat). The `ConversationBackend`
  SPI abstracts this: `send(step)` + `awaitReply(match, timeout)` implemented as a
  poll-until-match-or-timeout loop that is trivially satisfied on the sync
  InMemory backend and genuinely waits on the async live one. Keeping the runner
  core free of `InMemoryAdapter` types (acceptance item 4) is what lets Phase 4b
  add the SimpleX binding without touching the core.
- **Reuse the golden-path shape as the first scenario.** `GoldenPathJourneyIT`
  (infochat-provider/src/test/.../journey/) already encodes the 13-hop lifecycle
  in Java (bootstrap → /invite → register → probation → vouch → /summary → chat →
  group → /approve-group → /help → digest → /zcash → /ban); the smoke scenario
  file is that sequence expressed declaratively. The live run's scope is the
  **7 transport-relevant** scenarios (3,4,7,10,11,12,15; D-live-5) — Phase 4b picks
  from those; Phase 4a just needs one representative scenario to prove the runner.
- **Latency on InMemory is ~0 (that's fine).** Under the mocked `TestLlmProvider`
  the per-step elapsed time is near-zero; the IT proves the *capture mechanism*
  works and the field is populated, not a real number. Real latency
  (`docs/plan/live-e2e` §1 item 2) is a Phase-4b live-run assertion.
- **Format: keep it simple.** DevTerminalHarness (M1-414) proves a line-based
  `dm <contact> <text>` grammar; a scenario adds an `expect <match> <timeout>` per
  send. A minimal line/YAML format is preferable to a parser framework — the
  reviewer's simplicity bias applies. Exact format is the plan-writer's call.
- **Health/metrics assertions are Phase 4b.** Readiness (`/q/health/ready`,
  AdapterReadinessCheck), per-adapter up/down (`adapter.connection.status`), and
  LLM-down-degraded are live-run assertions that need a real deployment; they are
  out of this InMemory-substrate slice.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-539-*.md
```
