---
name: mutation-testing-recheck-after-batch
description: "DISCHARGED 2026-07-27: pitest evaluated, spiked and scoped. Opt-in -Pmutation profile filed as M1-713; the spike's three real findings filed as M1-710/711/712. Numbers and scoping rationale below."
metadata:
  type: project
---

**Status: the open TODO is discharged.** The user asked (2026-07-25,
during M1-689) to revisit mutation testing after the ticket batch. Done
2026-07-27: researched, spiked against the real repo, scoped, and filed.
Four tickets — M1-713 (the tooling), M1-710/711/712 (what it found).
Nothing further is owed.

**Why it came up.** M1-689 moved the retrieval window predicate from
`published_at` to `ready_at`; `DigestRoundtripIT` positioned its fixture
by rewriting `published_at`, so the post fell outside every window.
Steps (a)–(c) kept passing anyway — a digest with zero eligible posts
sends the "no posts yet" reply, which is neither degraded nor empty, so
`assertFalse(isDegraded)` / `assertFalse(content.isEmpty())` both still
held. Only step (e), `assertTrue(llmCallCount > before)`, went red. An
assertion that holds regardless of whether the behaviour works is exactly
what mutation testing measures. See
[[green-suite-can-be-environmental-accident]] for the sibling failure
mode and [[scan-window-fixture-timebombs]], whose M1-602 census found all
3 real time-bombs were vacuous-negatives rather than red failures.

## What the spike measured

`-Pmutation` over the four pure-Java modules, PIT 1.25.8 +
pitest-junit5-plugin 1.2.3, threads=4, 4-core box, live stack up.
13:57 min total including compile.

| module | mutants | score | test strength | survived | no-coverage | PIT time |
|---|---|---|---|---|---|---|
| core | 305 | 60% | 89% | 23 | 100 | 62s |
| ssrf | 302 | 76% | 87% | 34 | 37 | 255s |
| llm-adapter | 418 | 78% | 86% | 52 | 40 | 55s |
| messaging-adapter | 1125 | 71% | 82% | 175 | 154 | 443s |

**Read test strength, not mutation score.** The two differ by mutants
with no coverage at all, and `core`'s 100 of those are an artifact of
excluding its 12 `*IT` classes — not a statement about its tests. A low
score can be a scoping decision wearing a number's clothes.

## The scoping decision, and why

- **Four modules, never collector/provider.** `collector` + `provider`
  hold 294 of the repo's 299 `@QuarkusTest` classes. PIT re-runs covering
  tests once per mutant, so each mutant there pays a Quarkus boot against
  a suite where one provider `verify` is already ~9 min.
- **Advisory, never a gate.** A score threshold rewards assertions
  written to kill mutants over assertions that state intent — it inverts
  the incentive the test-integrity rules depend on. It also has a real
  noise floor: equivalent mutants exist (the spike hit one at
  `SimpleXLoopbackProbe.isReachable:112`, where the mutated line already
  returns the mutated value).
- **Java 25 is a non-issue** — PIT supports bytecode through Java 26
  (`hcoles/pitest#1439`).
- Diff-scoped mode was considered and not adopted: PIT's
  `scmMutationCoverage` needs an `<scm>` block the POM lacks and works at
  class granularity; Arcmutate's line-granular git plugin is licensed.
- Excluding `*IT` and the two `@QuarkusTest` classes keeps the run
  container-free, so it is safe alongside the live stack — unlike a full
  `mvn verify` ([[clean-verify-monitoring]]).

## The correction worth remembering

**Mutation testing would NOT have caught M1-689.** Those vacuous
assertions live in a `@QuarkusTest` IT — the exact tier the affordable
scope excludes. The tool is worth having on its own merits (it found
three real defects in one run), but it does not close the failure class
that motivated the request. Sell it as "hardens the unit tier," never as
"prevents another M1-689."

## The pattern the findings shared

Two of the three were the *same* structural defect: a contract
implemented in **both** adapters, tested on Signal, unpinned on SimpleX
— outbound rate-limit draws (M1-710) and mention-strip ordering
(M1-711). The third (M1-712) was a refuse-leg: a command-injection guard
whose accept path is tested and whose reject path is not, on the one
`requireValidQueueAddressId` call site of four that no test kills.

That is [[relocated-controls-dont-travel]] seen one step earlier — a
control that was never carried onto the second path to begin with. When
reading a future sweep, **look for adapter-parity gaps and refuse-legs
first**; they were 3 of 3 here and they are invisible to both the green
suite and code review ([[reviewer-is-conformance-not-correctness]]).
