---
id: M1-950
title: "Eval harness two-world extension: per-world fences"
status: done
created: 2026-08-28
last_updated: 2026-08-29
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
  (converted at start, run RED 2026-08-29 — .scratch/tick-red-M1-950.log:
  test-compile fails `cannot find symbol RetrievalEvalWorlds`):
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
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetLoaderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/AnchorLegCharacterizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
  - infochat-provider/src/test/resources/retrieval-eval/golden-set-fam.jsonl
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
  - "ALL five resource sites resolve through the one seam (analysis P10): RetrievalEvalRunnerIT.goldenSetBytes, RetrievalEvalCharacterizationIT's resource read, RetrievalGoldenSetLoaderTest, AnchorLegCharacterizerTest, RetrievalGoldenSetTest — probe: grep -n 'golden-set.jsonl' over the eval package returns matches ONLY inside RetrievalEvalWorlds.java plus RetrievalEvalWorldsTest.java's assertEquals pins (the executable form of acceptance item 1's mapping, not a second resolution site) — a second hardcoded path anywhere else fails the grep fence."
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
    - >-
      RetrievalGoldenSetLoaderTest, AnchorLegCharacterizerTest (AUTHORIZED:
      their tech resource literals resolve through the seam — tech-pinned,
      no behavior change; clarity_check 2026-08-29).
    - >-
      RetrievalGoldenSetTest (AUTHORIZED: its TECH and FAM World.resource
      strings derive from the seam — same values, byte-identical behavior;
      clarity_check 2026-08-29).
    - >-
      golden-set-fam.jsonl + RetrievalGoldenSetTest.FAM_REPLICA_FINGERPRINT
      (AUTHORIZED 2026-08-29, user-directed fold-in of the M1-949 defect
      repair: all 46 records and the validator constant shipped the
      placeholder `ready=<redacted>;…` in 81ae8b36, making the pin vacuous
      and every fam run refuse at the label fence; this diff substitutes
      the real replica pin recorded by M1-948 (value redacted here per
      §13/M1-949 ticket style — it is committed where the instrument
      needs it: the fixture records + the validator constant);
      per the user's instruction to fix it here rather than a new defect
      ticket; the squash-merge carries it).
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
reviews:
  - round: 1
    date: 2026-08-29
    verdict: REWORK
    checks: 'SPEC-TRUTHNESS: PASS, SECURITY: PASS, TEST-ADEQUACY: PASS, MAINTAINABILITY: PASS, SCOPE: FAIL (replica address published in the ticket body, §13 placement); 4 candidate findings falsified-and-dropped (fingerprint-in-fixture §13 carve-out, D2 probe satisfaction via the manifest artifact, A/B base-point equivalence via stack frames + identical fingerprints, memory-rider provenance)'
    diff_stats: '11 files, +364/-96 (8 files_scope paths, board regen, ticket; the .agents/memory rider is excluded at commit per the binding exclusion)'
  - round: 2
    date: 2026-08-29
    verdict: APPROVE-WITH-FIXES
    checks: 'SPEC-TRUTHNESS: PASS, SECURITY: PASS, TEST-ADEQUACY: PASS, MAINTAINABILITY: WARN (bullet merge), SCOPE: FAIL carried by two low text-only findings (the round-1 rework record quoted the port literal inside the probe; the D2 bullet merge) — both within the fix-apply conditions; round-1 items dispositioned SATISFIED/SATISFIED; 2 candidate findings falsified-and-dropped (committed precedent for state-pin values; mech-report self-reference = the same FINDING-1 residual)'
    diff_stats: 'round-2 fix hunks: ticket only, +49/-6 (scrub, defang, mandated records)'
    fixes_applied: 'FIX-1 port literal masked in the quoted probe (grep probe returns no match over tracked files); FIX-2 D2 bullet split (grep probe returns no match); test-compile -pl infochat-provider -am exit 0; fixed-tree snapshot .scratch/tick-fixes-M1-950.tree (gitignored)'
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-29
  result: resolved
  note: >-
    acceptance item 2's grep fence (five resource sites through the seam)
    vs item 8's exactly-files_scope probe conflicted as drafted; user
    confirmed extending files_scope to the three unit consumers
    (RetrievalGoldenSetLoaderTest, AnchorLegCharacterizerTest,
    RetrievalGoldenSetTest) with tech-pinned seam wiring; Approach step 4
    "two unit consumers" corrected to three.
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
  4. Wire the characterization IT + the three unit consumers
      (RetrievalGoldenSetLoaderTest, AnchorLegCharacterizerTest,
      RetrievalGoldenSetTest — the latter's fam World.resource too)
      through the seam (tech-pinned) (P10).
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

- Operator legs, 2026-08-29 (the isolated replica's eval.db.url, test-stack
  model endpoints; invocation + address live in the operator-local
  .bench/retrieval-eval/README.md;
  logs .scratch/tick-smoke-{A,C,C2,D,D2}-M1-950.log):
  - C2 fam smoke GREEN (235s, all self-checks, label_fingerprint_match
    true): manifest world=fam, golden_set_resource=retrieval-eval/
    golden-set-fam.jsonl, world_embedding_coverage 8260/8260 (= M1-948's
    pin byte-equal), db fingerprint = the replica pin both passes,
    scores.json written (operator-local, no numbers published here — the
    M1-929 posture).
  - D2 mis-pointed-DB leg: fam world + tech DB → exit nonzero,
    label_fingerprint_match false in the manifest, NO scores.json; the
    inter-pass drift fence co-fired (the tech DB ingests live, ready
    5456→5467 mid-run) — on a frozen wrong DB the label refusal is the
    sole named error; the manifest evidence is identical either way.
  - A/B tech byte-identity PROVEN 2026-08-29 14:39-14:42Z (logs
    .scratch/tick-smoke-{A2,B}-M1-950.log; window log
    .scratch/tick-freeze-M1-950.log): the test stack had been removed by
    an external restructure mid-window; the operator restarted ONLY
    postgres + embedder + gemma (collector never up → DB frozen at
    ready=5569), ran A (pre-change, main@6aa73f26 in the detached
    .worktree/M1-950-ab) and B (this branch) against the identical
    corpus, then removed the containers again (volumes kept). Probes:
    A/B queries.jsonl byte-identical (empty diff); B manifest = A's keys
    plus exactly {world, golden_set_resource, world_embedding_coverage},
    every shared value identical; golden_set_sha256 = sha256sum of the
    untouched golden-set.jsonl (4dfed2d3…154, equal to the M1-944-era
    reference); both legs refused at the label fence identically (labels
    pin 5214, DB at 5569 — expected; artifacts are written before the
    fence, which is what the byte-identity compares).
- P10 → the grep fence (path literals only in RetrievalEvalWorlds.java +
  the test's own assertEquals pins) + resolvesWorldToResourceAndLeaf.
- P2 → the D2 operator failure-mode leg above.
- P6 → world_embedding_coverage resolves in C2 and D2 manifests with the
  with/total shape (8260/8260 on the replica).
- P7/P8 → README grep probes for each enumerated entry (cross-instance
  embedder numerics, doc_embedding boot re-embed, seed writes,
  coverage-as-pin).
- P14 → the fam-smoke bring-up (postgres + embedder + translator only)
  is the README fam invocation; no app-container step anywhere.
- P15 → the tech-smoke byte-identity diff pending the freeze window
  (above); git diff over the fences shows behavior-identical methods
  (only resource/leaf/manifest wiring touched).
- P16/P17 → git status fence; no config/application.properties change in
  the diff.
- acceptance items → the named legs/probes; the final item via
  `git diff --name-only` (the 8 files_scope paths + board/ticket regen),
  the verify-log probe (391 tests green, runner/characterization ITs
  absent from failsafe; log .scratch/tick-test-M1-950-r1.log), and
  repo-root `mvn verify`.

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

## Round 1 rework

1. Finding 1: scrub the replica address (and optionally the pin-value
   quote) from the ticket body at
   docs/plan/m1/tick-tickets/M1-950-eval-harness-two-world-extension.md:267,
   evaluated via `grep -rn "<replica-port>" docs/ scripts/ infochat-provider/`
   (the operator substitutes the real port from the gitignored
   .bench/retrieval-eval/README.md when running the probe)
   returning no match over tracked files.
2. (Binding exclusion, no code change) The .agents/memory/
   v1-0-0-tag-pulled-pre-announcement.md rider must not enter the commit,
   per the mechanical report's plan; evaluated via the committed diff
   (`git diff --name-only <fork-point>..HEAD`) naming no .agents/memory
   path — the committed file set must be exactly the eight files_scope
   paths plus the board and ticket regen.

Disposition: item 1 fixed (address + endpoints scrubbed from the
Verification bullet; the pin-value quote in test_plan.modifies defanged —
the value stays committed only in the fixture records and the validator
constant, where the instrument needs it); the grep probe returns no
match. Item 2 is honored at the commit step (the rider is staged but the
commit stages exactly the ticket's file set).

## Review observations (round 1, RECOMMENDED-NEW-TICKET, TOUCHED-BY-THIS-DIFF: no)

- The committed analysis doc (docs/plan/m1/tick-analysis/
  two-world-retrieval-instrument.md:13-21,134-142) names live deployment
  facts — the live fam postgres port, the fam checkout host path,
  volume/project names, the real users' language split, live census
  tallies — §13-class material; M1-948/949's ticket bodies carry
  redaction markers (a scrub event this analysis doc escaped). Candidate
  scrub ticket, user's call.
- M1-948's committed artifacts are absent (scripts/replica-restore.sh,
  FamReplicaRestoreWiringTest.java) against a done ticket whose acceptance
  references both — the replica bring-up is not reproducible from a fresh
  checkout. Known to the user: the squash-merge dropped the code; re-land
  queued after this family.
