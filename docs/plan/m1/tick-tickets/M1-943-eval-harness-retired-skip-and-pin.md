---
id: M1-943
title: "Eval harness: skip retired records, pin golden-set id"
status: done
created: 2026-08-27
last_updated: 2026-08-27
flow: tick
reproduction: >-
  RetrievalGoldenSetLoaderTest.skipsRetiredRecords (written and run RED
  first, workflow §0 — RED log .scratch/tick-red-M1-943-r1.log: against
  the pre-fix loads-all-lines behavior all four legs failed, incl. the
  mutation probe of acceptance item 2): feeds JSONL content containing a
  retired record (textual replaced_by) + its successor + a normal record
  and asserts the loaded golden rows EXCLUDE the retired id, include the
  successor, and preserve file order. The behavior under test was
  verified defective before the fix by reading the runner's then-inline
  parsing loop `loadGoldenSet` (RetrievalEvalRunnerIT.java:296-323 in
  the pre-M1-943 tree; since replaced by the loader delegation): it parsed EVERY line and never read supersedes/replaced_by, so after
  the first correction ever (M1-942 lands 18 of them) the retired record
  would be executed and scored as a duplicate query — per-class n
  inflates (topical would read as 21+ instead of 16) and the T1
  discordance accounting is polluted. Companion leg:
  RetrievalGoldenSetLoaderTest.hashDiscriminatesOneByteAnswerKeyChange —
  the manifest previously written by writeArtifacts (:437-459) carried
  golden_set_records as a bare COUNT and no content hash, so two runs
  with different answer keys were indistinguishable (verified by reading
  the manifest keys).
analysis_ref: docs/plan/m1/tick-analysis/golden-set-corrections.md
blocked_by: []
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalRunnerIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetLoader.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetLoaderTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — the executed path
    (SemanticSearchTool, translator, embedding stack) is never the target;
    probe: git diff --name-only names no src/main path.
  - >-
    Fixture content, corrections, relabels, extension, and validator
    constants — M1-942 owns the golden set; this ticket consumes it
    read-only (the loader's skip semantics follow the file's own
    replaced_by markers, whatever records they name).
  - >-
    The re-baseline run and its record — M1-944; this ticket lands the
    harness capability and may smoke-run it, but publishes no numbers.
  - >-
    Scoring/metric changes — RetrievalEvalScorer already supports |E| up
    to 16 (capped recall hits/min(|E|,16) at RetrievalEvalScorer.java:139-
    140); no metric moves here.
  - >-
    POM/failsafe changes — the @Tag("retrieval-eval") + excludedGroups
    containment stays exactly as M1-929 built it; the new loader test is
    plain JUnit in the default suite and needs no containment.
  - >-
    ANY docs/spec/** edit and any CI-gating automation — operator-run
    discipline unchanged.
acceptance:
  - "RetrievalGoldenSetLoaderTest.skipsRetiredRecords passes (written and run RED first, per reproduction): the loader fed JSONL with a retired target + successor + normal record returns rows excluding the retired id, including the successor, in file order — and RetrievalEvalRunnerIT.loadGoldenSet delegates to this loader, so executed and scored records are ACTIVE-only — probe: the unit test green in the default suite (plain JUnit, no DB) plus grep showing loadGoldenSet's parsing loop replaced by the delegation (analysis P5)."
  - "FAILURE-MODE (the skip is load-bearing, analysis P5): a corrupted-content leg feeds a record carrying replaced_by whose successor is ABSENT and asserts the loader still skips the retired row (skip is keyed on the marker, not on pair resolution — pair integrity is RetrievalGoldenSetTest's job, M1-942); a mutation that loads all lines fails skipsRetiredRecords."
  - "The run manifest pins golden-set identity (analysis P6): writeArtifacts adds golden_set_sha256 (sha256 over the exact golden-set.jsonl resource bytes as loaded) plus active/retired record counts — probes: an operator smoke run's manifest.json shows golden_set_sha256 equal to sha256sum of the committed file and counts matching the file; FAILURE-MODE unit leg: the hash helper over two inputs differing by one byte yields different digests (a hash that cannot discriminate answer keys fails)."
  - "The runner's existing self-checks and controls are preserved (engineering-rules §10): DB-fingerprint drift refusal between passes and vs the labels, double-run per-query uid identity (docs/spec/llm.md §Determinism boundary), en-scope zero-translator-calls, and the non-zero-fallback scoring abort all behave unchanged — probe: git diff shows no change in RetrievalEvalRunnerIT outside the loader delegation and the manifest block; operator smoke run passes all self-checks with label_fingerprint_match true (analysis P11)."
  - "Default-suite containment unchanged — probe: grep -n 'excludedGroups' infochat-provider/pom.xml shows the same single M1-929 entry (no POM diff); plain mvn verify from repo root is green and its failsafe run list contains no RetrievalEvalRunnerIT (verify-log probe) while RetrievalGoldenSetLoaderTest runs in it."
  - "git diff --name-only names exactly the files_scope paths (plus board/frontmatter regen); no operator-local path is committed — probe: git status --porcelain output (analysis P12)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetLoader.java
      — plain-Java loader seam (parses JSONL, skips textual replaced_by,
      returns the runner's GoldenRow shape; hashing helper for the
      manifest pin).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetLoaderTest.java
      — skipsRetiredRecords, the absent-successor failure-mode leg, and
      the one-byte-mutation hash leg (plain JUnit, no DB).
  modifies:
    - >-
      RetrievalEvalRunnerIT.loadGoldenSet (authorized: the in-method
      parsing loop at :296-323 is replaced by delegation to the loader;
      new expected behavior — retired records are never executed or
      scored) and RetrievalEvalRunnerIT.writeArtifacts (authorized: the
      manifest gains golden_set_sha256 + active/retired counts; existing
      keys unchanged), per engineering-rules §8.
  preserves:
    - all tests currently green on main
    - >-
      every runner self-check (fingerprint refusal, determinism identity,
      en-no-call, fallback abort) — untouched by this diff.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D58
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
    date: 2026-08-27
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "5 files, +274/-66 (loader 87, loader test 119, runner 54, ticket 68, board 12)"
    notes: "6 falsification candidates dropped with citations (manifest-pin CI leg vs approved operator split; List.copyOf record idiom vs §7; NoSuchAlgorithmException wrap vs §7; header-comment provenance vs §11; dual golden_set_records/active keys as deliberate compat; IllegalStateException wrap vs old IOException — no caller keyed on the old type). RED-first + mutation probe corroborated; smoke-run manifest hash triple-corroborated. Verdict: .scratch/tick-review-M1-943-r1.txt"
overrides: []
aborted_attempts: []
reopens: []
clarity_check: >-
  start 2026-08-27: all citations re-verified against the code
  (loadGoldenSet :296-323 parses every line and never reads
  supersedes/replaced_by; writeArtifacts :437-459 carries
  golden_set_records as a bare count at :457, no content hash;
  self-checks at :208-248; scorer K=16 at :28, capped recall
  :139-140; POM excludedGroups single entry at
  infochat-provider/pom.xml:245; fixture has zero replaced_by rows
  today). Skip key resolved from the validator's own convention
  (RetrievalGoldenSetTest.java:242 — textual replaced_by = retired).
  No blocking ambiguity.
escalation_reason:
---

# M1-943: Eval harness: skip retired records, pin golden-set id

## Context

The golden set's freeze discipline (M1-928) keeps corrected records in-file
as retired (`replaced_by`) rows beside their successors — but the runner
that executes the set never learned that: `loadGoldenSet`
(RetrievalEvalRunnerIT.java:296-323) parses every line and ignores
`supersedes`/`replaced_by`. The moment M1-942's 18 corrections land, any
run would execute and score retired records as duplicate queries,
inflating every per-class slice and polluting the T1 sign-test accounting.
The run manifest likewise carries no golden-set identity
(`golden_set_records` is a bare count, :457), so two readings made against
different answer keys would be indistinguishable in the operator-local
record. Shared analysis: `analysis_ref:`. Recommended to land BEFORE
M1-942 (minimizes the window in which the committed set and the runner
disagree); no hard dependency either way.

## Root cause

Verified: the loader predates the first correction — the set M1-928
committed has zero `supersedes` rows (every record `"supersedes": null`),
so retired-record handling was never exercised; the manifest was designed
when a single immutable set existed, so identity beyond the repo commit
was redundant. Both are mechanical gaps in test-scope code
(RetrievalEvalRunnerIT.java:296-323, :437-459), not production defects.
The scorer needs nothing: capped/raw recall already handle |E| up to 16
(RetrievalEvalScorer.java:28,:139-140).

## Pitfalls

Numbered per the analysis document; this ticket carries P5, P6, P11 (and
the operator-probe half of P12).

- P5: retired-record execution — the skip must land before any re-baseline
  run quotes numbers; keyed on the textual `replaced_by` marker, with
  pair-integrity left to RetrievalGoldenSetTest (M1-942's validator owns
  pairing; the loader owns execution semantics).
- P6: label-set identity — the manifest needs a content hash over the
  exact resource bytes (plus active/retired counts), reproducible by
  `sha256sum` against the committed file, so M1-944's record can pin the
  answer key per run.
- P11: §8 test-modification authorization — this ticket reworks the
  runner's loader and manifest blocks (pre-existing test-scope code);
  both modifications are named in `test_plan.modifies` with their new
  expected behavior, and the surrounding self-checks are enumerated as
  preserved controls.
- P12: operator/verifiability split — the manifest probes ride an
  operator smoke run (recorded probe, the M1-930 posture); the unit legs
  must discriminate without any DB or operator artifact.

## Approach

- **Files to touch** — `files_scope`: a new plain-Java loader seam
  (`RetrievalGoldenSetLoader`) with its unit test, plus the runner's
  delegation and manifest block. No POM change, no scorer change, no
  fixture change.
- **Steps in implementation order:**
  1. Write `RetrievalGoldenSetLoaderTest` RED (workflow §0):
     `skipsRetiredRecords` (retired target + successor + normal record →
     successor and normal only, order preserved), the absent-successor
     failure-mode leg (skip keyed on the marker), and the one-byte hash
     discrimination leg.
  2. Extract the loader: parse the JSONL content exactly as the runner
     does today (same fields: id, class, query, scope_lang, none_expected,
     relevant_uids, labeled fingerprint), skip records whose `replaced_by`
     is textual, expose the sha256 helper over the raw content bytes
     (P5/P6).
  3. Delegate `loadGoldenSet` to it; extend `writeArtifacts` with
     `golden_set_sha256` + active/retired counts; touch nothing else in
     the runner (P11 — the self-checks at :208-248 consume the loaded
     rows and must not change).
  4. Green the default suite; operator smoke run for the manifest probes
     (any DB matching the labels' fingerprint; the run's own self-checks
     gate it).
- **Controls to preserve (§10):** the runner's self-checks (fingerprint
  drift refusal, double-run uid identity, en-no-translator-calls, fallback
  abort) are enumerated preserved — the skip happens BEFORE any check
  consumes the loaded rows; the POM containment is untouched; the default
  suite gains exactly one plain-JUnit test class.
- **Pitfall→mitigation:** P5→steps 1-2 (skip semantics + RED-first);
  P6→step 2 hash helper + step 3 manifest keys + the discrimination leg;
  P11→`test_plan.modifies` authorization + the untouched-self-checks
  probe; P12→step 4 recorded operator probe, unit legs DB-free.

## Definition of done

`RetrievalGoldenSetLoaderTest` is green in the default suite (skip, order,
absent-successor, hash legs); the runner delegates to the loader and its
manifest carries the golden-set hash + counts; plain `mvn verify` is green
with the runner still absent from the failsafe list; an operator smoke
run's manifest hash equals `sha256sum` of the committed golden set; the
diff touches nothing outside `files_scope`.

## Verification

- P5 → RetrievalGoldenSetLoaderTest.skipsRetiredRecords — feeds retired +
  successor + normal content, asserts the retired id never appears among
  loaded rows; FAILURE-MODE: the absent-successor leg asserts the marker
  alone triggers the skip, and a mutation loading all lines fails the
  first leg; grep shows loadGoldenSet delegates.
- P6 → manifest probes: operator smoke run's `golden_set_sha256` equals
  `sha256sum` of the committed file, counts match; FAILURE-MODE: the
  one-byte-mutation unit leg asserts the digest changes — a hash that
  cannot discriminate answer keys fails.
- P11 → `test_plan.modifies` names both reworked blocks; git diff shows no
  runner change outside the delegation and manifest block; the smoke run's
  self-checks pass (label_fingerprint_match true, no fallback abort).
- P12 → unit legs run with no DB and no operator artifact (plain JUnit);
  the manifest probe is a recorded operator observation in the ticket
  notes, never a CI gate.
- acceptance items → the named legs/probes above; containment via the POM
  grep and the verify-log probe; the fence via git status --porcelain.

## Out-of-scope

Named in `out_of_scope`: any production change; fixture/validator work
(M1-942); the re-baseline and its record (M1-944); scoring changes (the
scorer already handles the new shapes); POM/failsafe edits; spec edits and
CI gating. Pre-existing test-scope code IS modified — `loadGoldenSet`
(delegation) and `writeArtifacts` (manifest keys) — each authorized by
name in `test_plan.modifies` with its new expected behavior
(engineering-rules §8).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-943-eval-harness-retired-skip-and-pin.md
```

## Ticket notes (implementor, 2026-08-27)

- RED run (before fix code): `.scratch/tick-red-M1-943-r1.log` — all
  four loader-test legs failed against the loads-all-lines stub
  (= the runner's pre-fix behavior); this doubles as the acceptance-2
  mutation probe (`skipsRetiredRecords:34 expected: <[new-row,
  keep-row]> but was: <[old-row, new-row, keep-row]>`).
- Operator smoke run (recorded probe, P12; frozen DB pre-checked
  `ready=5214 / max_ready_at=2026-08-24 16:00:57.001472+00`, stack
  postgres+models only, torn down after):
  `.bench/retrieval-eval/results/20260827-150743/manifest.json` —
  `golden_set_sha256` = `d6366ab62e9f5d127f3addf291d10366c4250748
  dc34d9491303eb43d90738ef` = `sha256sum` of the committed
  golden-set.jsonl; `golden_set_active_records` 51 /
  `golden_set_retired_records` 0 (matches the file — zero replaced_by
  rows today); `label_fingerprint_match: true`;
  `translator_fallback_records: []`; run green in 18.76 s. Log:
  `.scratch/tick-run-M1-943-smoke.log`.
- Full verify: `.scratch/tick-test-M1-943-r1.log` (BUILD SUCCESS;
  RetrievalEvalRunnerIT absent from the failsafe run;
  RetrievalGoldenSetLoaderTest 4/4 in the default suite).
