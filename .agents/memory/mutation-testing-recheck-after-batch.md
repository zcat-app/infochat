---
name: mutation-testing-recheck-after-batch
description: "OPEN TODO (user-requested 2026-07-25): after the current M1 ticket batch finishes, revisit adding a mutation-testing framework (pitest named) — motivated by vacuous-pass tests the suite cannot self-detect"
metadata:
  type: project
---

**Owed after the current M1 ticket batch completes.** The user asked
(2026-07-25, during M1-689) to revisit adding a **mutation-testing
framework — `pitest` was named** — and explicitly deferred the research
until the batch is done. This file is the reminder, not the evaluation:
nothing has been researched, benchmarked, or costed yet.

**Why it came up — the motivating evidence.** M1-689 moved the retrieval
window predicate from `published_at` to `ready_at`. `DigestRoundtripIT`
positions its one fixture post by rewriting `published_at` mid-test, so
under the new predicate the post fell outside every window and the digest
had nothing to summarize. Steps (a)–(c) of that roundtrip **kept
passing** anyway: a digest with zero eligible posts sends the "no posts
yet" reply, which is neither degraded nor empty, so
`assertFalse(isDegraded)` / `assertFalse(content.isEmpty())` were both
still satisfied. Only step (e) — `assertTrue(llmCallCount > before)`,
the first assertion that genuinely requires a post to exist — went red.

That is the exact failure class mutation testing exists to surface: an
assertion that holds regardless of whether the behavior under test
works. A green suite does not distinguish "the code is right" from "the
assertion cannot fail." See [[green-suite-can-be-environmental-accident]]
for the sibling failure mode (green for an *environmental* reason) and
[[scan-window-fixture-timebombs]] — whose M1-602 census found that all 3
real time-bombs were **vacuous-negatives, not red failures**, i.e. the
same class, found by hand, at census cost.

**What to actually check when picking this up:**

- Does pitest survive the constraints this repo already has? Full
  `mvn verify` is long and serialized under `flock`
  ([[clean-verify-monitoring]]), cross-module `-Dtest` filtering is
  blocked by the parent-POM tripwire
  ([[mvn-dtest-filter-blocked-by-tripwire]]), and the suite leaks Dev
  Services containers per run. A framework that re-runs the suite once
  per mutant is likely unaffordable against the IT tier.
- Therefore scope it before adopting it: mutation-test the **deterministic
  pure-Java units** (parsers, predicate builders, renderers, sanitizers),
  not the `@QuarkusTest` IT tier that owns most of the wall-clock.
- Decide where it runs — a gate in `/m1-tick` would tax every ticket;
  an occasional advisory sweep (the `/deep-code-review full` posture)
  probably fits the workflow better. It is a **quality-measurement**
  tool, so it belongs nearer the advisory tier than the merge gate.
- Note the overlap with existing coverage: the reviewer is a conformance
  gate, not a correctness gate ([[reviewer-is-conformance-not-correctness]]),
  and `/redteam` finds real bugs in ~30% of audits. Mutation testing
  targets a gap neither covers — assertion *strength*.
