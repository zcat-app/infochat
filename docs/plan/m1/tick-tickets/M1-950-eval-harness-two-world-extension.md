---
id: M1-950
title: "Eval harness two-world extension: per-world fences"
status: pending
created: 2026-08-28
last_updated: 2026-08-28
flow: tick
reproduction: >-
  Child of a 2+ decomposition (analysis
  docs/plan/m1/tick-analysis/two-world-retrieval-instrument.md); the RED leg
  is to-be-written per workflow §0 (only this child can make it writable — no
  world-resolution seam exists to compile against); /tick start converts the
  marker: write the test, run it RED against the unmodified tree before any
  harness edit. The wrong behavior: the harness
  is single-world by construction — the golden-set resource path is
  hardcoded at RetrievalEvalRunnerIT.java:338-345 (goldenSetBytes) and
  independently at RetrievalEvalCharacterizationIT.java:404,
  RetrievalGoldenSetLoaderTest.java:80,
  AnchorLegCharacterizerTest.java:79; the results leaf is hardcoded
  "results" (RetrievalEvalRunnerIT.java:437-449); the manifest carries no
  world key and no embedding-coverage pin (writeArtifacts :451-509) — so a
  fam replica DB can be pointed at via -Deval.db.url but the run would
  execute the TECH golden set against it and score nothing meaningful, with
  no manifest evidence of which world ran (analysis P10). RED test
  (to-be-written, converted at start):
  RetrievalEvalWorldsTest#resolvesWorldToResourceAndLeaf
  — eval.world=fam must resolve golden-set-fam.jsonl + the results-fam leaf
  and eval.world=tech (default) golden-set.jsonl + results; observed today:
  the leg is unwritable — no RetrievalEvalWorlds type and no eval.world
  property exist anywhere in the eval package (grep returns nothing); the
  seam is this ticket's to create.
analysis_ref: docs/plan/m1/tick-analysis/two-world-retrieval-instrument.md
blocked_by: [M1-948, M1-949]
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalWorlds.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalWorldsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalRunnerIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalCharacterizationIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — the executed path
    (SemanticSearchTool, QueryAnchorTranslator, embedding stack) is measured,
    never modified; probe: git diff --name-only names no src/main path.
  - >-
    Fixture content and validator floors — M1-949 owns the fam set; this
    ticket consumes both sets read-only through the world seam.
  - >-
    The two-leg record and any gating-rule wording — M1-952; this ticket may
    smoke-run fam but publishes NO numbers (the M1-929 posture: plumbing
    proof only).
  - >-
    POM/failsafe changes — the @Tag("retrieval-eval") + excludedGroups
    containment stays exactly as M1-929 built it (infochat-provider/pom.xml
    :172/:245); the new unit test is plain JUnit in the default suite.
  - >-
    Any retrieval improvement, threshold/limit change, or width knob — the
    width-32 lever stays undecided until the mixed baseline re-reads it on
    both legs (binding constraint 4; analysis P17); probe: git diff shows no
    application.properties / config change.
  - >-
    Live fam and the prod containers — the fam leg targets the ISOLATED
    replica (M1-948) on its own port only; probe: no 25432 URL anywhere in
    the harness paths (the M1-862 grep pattern).
acceptance:
  - "REPRODUCTION closed: RetrievalEvalWorldsTest.resolvesWorldToResourceAndLeaf passes — the world seam maps tech → (retrieval-eval/golden-set.jsonl, results leaf 'results') and fam → (retrieval-eval/golden-set-fam.jsonl, 'results-fam'); an UNKNOWN world name fails loud with a named error at resolution, never falls back to tech (FAILURE-MODE leg: eval.world=bogus → the named refusal); pure JUnit, CI-runnable, no DB."
  - "ALL five resource sites resolve through the one seam (analysis P10): RetrievalEvalRunnerIT.goldenSetBytes, RetrievalEvalCharacterizationIT's resource read, RetrievalGoldenSetLoaderTest, AnchorLegCharacterizerTest, RetrievalGoldenSetTest — probe: grep -n 'golden-set.jsonl' over the eval package returns matches ONLY inside RetrievalEvalWorlds.java (the single literal site) — a second hardcoded path anywhere fails the grep fence."
  - "world=tech is byte-identical to today's behavior (the campaign's gating reference depends on it; analysis P15): an operator tech smoke run against the frozen test stack produces the same manifest keys plus only the NEW keys, the same results leaf, and — on a fingerprint-matching DB — the same per-query uid lists as a pre-change run — probe: diff of the two runs' queries.jsonl is empty; manifest golden_set_sha256 equals sha256sum of the untouched golden-set.jsonl."
  - "Per-world fences (analysis P2/P6): the manifest gains world, golden_set_resource, and world_embedding_coverage (READY-world posts WITH a post_embedding row / total, computed over the RUN's DB); the label-fingerprint refusal stays world-keyed — FAILURE-MODE (operator leg): a fam-world run pointed at a DB whose fingerprint differs from the fam labels' pinned replica fingerprint exits with the runner's named refusal (label_fingerprint_match false is reported, never scored) — probe: grep over the fam smoke's manifest artifact (.bench/retrieval-eval/results-fam/<ts>/) shows world=fam + golden_set_resource + world_embedding_coverage each resolving with a value, and the mis-pointed-DB operator leg exits nonzero with label_fingerprint_match false in the run log and NO scores — the same fence that makes a mis-pointed live-fam URL refuse."
  - "The runner's existing self-checks are preserved UNMODIFIED in behavior (engineering-rules §10): sentinel, stub-exclusion, inter-pass drift, label-fingerprint match, double-run determinism, en-zero-translator-calls, fallback abort (RetrievalEvalRunnerIT.java:264-330) — probe: git diff shows no change to those methods beyond the resource/leaf/manifest wiring; the operator fam smoke passes all of them with label_fingerprint_match true."
  - "Operator fam smoke (blocked_by M1-948/M1-949): the documented invocation with -Deval.world=fam and the replica's eval.db.url executes the fam golden set through the production SemanticSearchTool bean on the isolated replica (postgres + embedder + translator only; NO provider/collector deployment, analysis P14) — probe: run green, all self-checks pass, artifacts under .bench/retrieval-eval/results-fam/<ts>/ with world=fam in the manifest."
  - "Divergence disclosure extended (memory campaign-harness-must-disclose-excluded-paths; analysis P7/P8): .bench/retrieval-eval/README.md gains the fam-leg enumeration — cross-instance embedder numerics (replica corpus vectors from fam's nomic process vs eval-time query vectors from the test-stack nomic, same GGUF, bounded drift per the characterization record), the doc_embedding boot re-embed (CommandIntentIndexBuilder :183-211, post-fingerprint-neutral), the eval-scope seed writes, and the coverage state being a PIN not an invariant — probe: grep the README for each enumeration entry."
  - "mvn verify from repo root is green with the eval stack ABSENT (the new unit test in the default suite; the runner/characterization ITs still absent from the failsafe list — verify-log probe); git diff --name-only names exactly the files_scope paths plus board/frontmatter regen."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalWorlds.java
      — the world-resolution seam (property → resource + results leaf +
      validation; the single literal holder of both resource paths).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalWorldsTest.java
      — resolvesWorldToResourceAndLeaf, the unknown-world refusal leg, the
      one-literal-site fence helper (pure JUnit, no DB).
  modifies:
    - >-
      RetrievalEvalRunnerIT (AUTHORIZED: goldenSetBytes delegates to the
      seam; resolveResultsDir takes the world's leaf; writeArtifacts gains
      world/golden_set_resource/world_embedding_coverage keys; the class
      javadoc documents the fam invocation — existing keys and fences
      byte-identical).
    - >-
      RetrievalEvalCharacterizationIT (AUTHORIZED: its resource read
      delegates to the seam; the characterization stays tech-world-pinned —
      its fixtures are tech rows; no behavior change).
  preserves:
    - >-
      every runner fence (sentinel, stub-exclusion, inter-pass drift,
      label-fingerprint refusal, determinism identity, en-zero-calls,
      fallback abort) — untouched in behavior.
    - >-
      the POM containment (single excludedGroups entry) and the %eval stub
      exclusion (application.properties:35).
    - all tests currently green on main.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D19
  - D29
  - D54
  - D58
  - D59
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-950: Eval harness two-world extension: per-world fences

## Context

The two-world instrument needs a harness that can execute EITHER leg without
forking: the same runner, the same production bean, the same fences —
parameterized by world. Today the harness is single-world by construction
(five hardcoded resource sites, a hardcoded results leaf, a worldless
manifest — see reproduction), so pointing `eval.db.url` at the fam replica
would execute the TECH set against it and leave no manifest evidence of which
world ran. This ticket lands the world seam, the per-world manifest pins
(including the embedding-coverage pin that closes the coverage confound's
disclosure gap), and the fam operator smoke. Shared analysis:
`analysis_ref:`. Blocked on M1-948 (the replica to smoke against) and
M1-949 (the fam set to execute).

## Root cause

Verified single-world construction: `RetrievalEvalRunnerIT.goldenSetBytes`
(:338-345) hardcodes `retrieval-eval/golden-set.jsonl`; the same literal
lives at `RetrievalEvalCharacterizationIT.java:404`,
`RetrievalGoldenSetLoaderTest.java:80`, `AnchorLegCharacterizerTest.java:79`,
`RetrievalGoldenSetTest.java:33`; `resolveResultsDir` hardcodes the
`"results"` leaf (:437-449); the manifest (:451-509) has no world key and no
coverage pin. The fences that must travel per-world are all present and
mechanical (:264-330) — they key on the RUN's DB and the loaded labels, so
they world-key themselves once resource resolution does. The scorer is
already world-agnostic (`worldNow` derives from the run fingerprint,
RetrievalEvalScorer.java:79-94). The EvalProfile needs nothing (JDBC and
endpoints already ride `eval.*` properties).

## Pitfalls

Numbered per the analysis document; this ticket carries P2 (runtime half),
P6 (pin half), P7, P8, P10, P14 (no-deployment half), P15 (tech-regression
half), P16, P17.

- P10: five resource sites — one seam, one literal site; a second hardcoded
  path anywhere silently reads the tech set under a fam label.
- P2 (runtime half): the label-fingerprint refusal is the live-fam
  tripwire — fam labels pin the replica fingerprint; a run against live fam
  (or the tech DB) refuses; the failure-mode leg proves it per world.
- P6 (pin half): world_embedding_coverage lands in the manifest here; the
  comparability RULE (fingerprint AND coverage) is pre-registered by M1-952,
  not invented by the harness.
- P7/P8: the README divergence enumeration grows the fam legs (cross
  -instance embedder numerics; doc_embedding boot re-embed; seed writes;
  coverage-as-pin).
- P14 (no-deployment half): the fam smoke boots postgres + embedder +
  translator only — no provider/collector container ever targets the
  replica.
- P15 (tech-regression half): world=tech default byte-identical — the
  campaign's gating reference and the M1-944 sha pin must survive the
  extension unchanged.
- P16/P17: no worktree code; no width/config knob moves.

## Approach

- **Files to touch** — `files_scope`: the seam + its unit test, the runner
  and characterization IT wiring (plus `.bench/retrieval-eval/README.md`,
  operator-local).
- **Steps in implementation order:**
  1. `RetrievalEvalWorldsTest` RED (workflow §0): resolution, unknown-world
     refusal, one-literal-site fence.
  2. The seam: `RetrievalEvalWorlds` (world name → resource path + results
     leaf; validation; the only literal holder of both paths).
  3. Wire the runner: `goldenSetBytes` → seam; `resolveResultsDir` leaf by
     world; manifest keys `world`, `golden_set_resource`,
     `world_embedding_coverage` (a ready-with-embedding count query over the
     run's DB, same connection posture as `dbFingerprint`); javadoc fam
     invocation (P10, P6).
  4. Wire the characterization IT + the two unit consumers through the seam
     (tech-pinned) (P10).
  5. Operator tech smoke (byte-identity regression vs a pre-change run on a
     fingerprint-matching DB) + fam smoke on the replica (all fences)
     (P2/P15/P14).
  6. README divergence enumeration; `mvn verify` green; diff fences.
- **Controls to preserve (§10):** every runner fence runs per world
  UNMODIFIED (the §10 enumeration in acceptance item 5); POM containment and
  %eval stub exclusion untouched; default suite gains exactly one plain-JUnit
  test.
- **Pitfall→mitigation:** P10→steps 1-4 (seam + grep fence); P2→step 5
  failure-mode leg; P6→step 3 manifest key; P7/P8→step 6; P14→step 5
  bring-up shape; P15→step 5 tech byte-identity; P16/P17→diff fences.

## Definition of done

The unit legs pass (resolution, unknown-world refusal, one-literal-site
fence); the tech smoke is byte-identical to a pre-change run; the fam smoke
is green on the replica with all self-checks and `world=fam` +
`world_embedding_coverage` in the manifest; the mis-pointed-DB failure mode
refuses with the named refusal; the README enumerates the fam divergences;
`mvn verify` is green with the eval stack absent; the diff touches nothing
outside `files_scope`.

## Verification

- P10 → the grep fence (`golden-set.jsonl` literals only inside
  RetrievalEvalWorlds.java) + resolvesWorldToResourceAndLeaf.
- P2 → the operator failure-mode leg: fam world + wrong-fingerprint DB → the
  named refusal, label_fingerprint_match false, no scores.
- P6 → the manifest key resolves in both smokes with the with/total shape.
- P7/P8 → README grep probes for each enumerated entry.
- P14 → the fam-smoke bring-up (postgres + embedder + translator only)
  restated in the ticket notes; no app-container step anywhere in the
  invocation.
- P15 → the tech-smoke byte-identity diff (queries.jsonl empty diff; sha pin
  equal to sha256sum of the untouched tech file); git diff over the fences
  shows behavior-identical methods.
- P16/P17 → git status fence; no config/application.properties change in the
  diff.
- acceptance items → the named legs/probes; the final item via
  `git diff --name-only`, the verify-log probe, and repo-root `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: any production change; fixture/floors (M1-949); the
record and gating rules (M1-952) — no numbers published here; POM changes;
retrieval improvements or width/threshold knobs (the width-32 lever stays
undecided until the mixed baseline; binding constraint 4); live fam / prod
containers. The runner and characterization IT ARE modified — authorized in
`test_plan.modifies` with existing keys and fences byte-identical
(engineering-rules §8).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-950-eval-harness-two-world-extension.md
```
