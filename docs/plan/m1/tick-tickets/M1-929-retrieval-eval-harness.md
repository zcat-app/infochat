---
id: M1-929
title: "Harness: score golden set over production fused SQL"
status: done
created: 2026-08-26
last_updated: 2026-08-27
flow: tick
reproduction: >-
  Probe (harness ticket; the runner cannot exist before it is written — the
  M1-859 posture): `grep -rn 'queryFusedPosts\|RRF_K' --include='*.py'
  .bench/ scripts/` returns NOTHING (verified 2026-08-26), and the only
  in-tree construction of the fused query is SemanticSearchTool.java itself
  (`grep -rn 'queryFusedPosts' --include='*.java' .` returns exactly
  SemanticSearchTool.java:145,:198). Observed consequence: no instrument can
  produce a Recall@16/MRR number over shipped retrieval — the one harness that
  models retrieval explicitly excluded it ("lexical+RRF fusion deliberately
  unbuilt", docs/measurement/direct-chat-e2e.md:410-419, Remaining harness
  divergences item 7), so every retrieval-affecting change (M1-916/917/927)
  landed unmeasurable. Intended runner entry (to-be-written, this ticket):
  RetrievalEvalRunnerIT — invoked operator-side via -Dit.test filtering
  (legal per memory: mvn--Dtest-filtering-is-blocked-by-a-tripwire) — runs the
  golden set through the PRODUCTION SemanticSearchTool bean and fails its
  self-checks on any divergence.
analysis_ref: docs/plan/m1/tick-analysis/golden-set-retrieval-eval.md
blocked_by: [M1-928]
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalRunnerIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalScorer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalScorerTest.java
  - infochat-provider/pom.xml
  - .bench/retrieval-eval/
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — infochat-provider/src/main/** is
    untouched (probe: git diff --name-only shows no src/main path); the
    SemanticSearchTool, QueryAnchorTranslator, and embedding stack are the
    EXECUTED path, never the modification target. The only build-file hunk is
    the failsafe excludedGroups entry for the runner's own tag.
  - >-
    Changing fixture content or class floors — M1-928 owns the golden set;
    this ticket consumes it read-only.
  - >-
    The baseline run and its measurement record — M1-930. This ticket may
    smoke-run on the test DB to prove the plumbing, but publishes no numbers.
  - >-
    Threshold or limit tuning — 0.40/16 are inputs read from the production
    config keys (application.properties:501-502), never variables (the
    M1-862 fixed-input pattern).
  - >-
    ANY retrieval improvement and any CI-gating automation — the harness is
    operator-run on demand (binding user decision); CI wiring is a later,
    separately-decided topic.
  - >-
    PROD containers — the runner targets the test DB and the test stack's
    configured model endpoints only (measurements-never-ride-prod-containers);
    probe: no prod URL in any harness path (grep the M1-862 pattern).
acceptance:
  - "The runner executes the PRODUCTION fused retrieval path: RetrievalEvalRunnerIT boots the provider (Quarkus test profile with devservices DISABLED, explicit operator JDBC config) against the operator-named test DB, injects the real SemanticSearchTool bean, and calls execute() per golden record at the DEFAULT limit (so threshold/limit come from the live infochat.chat.semantic-threshold / infochat.chat.semantic-limit keys — never literals) — probe: grep -n 'new SemanticSearchTool' over the eval package returns NOTHING (no manual construction), and git diff --name-only names no src/main path (analysis P1/P2/P3)."
  - "Every run writes a manifest + per-query JSONL under .bench/retrieval-eval/results/<ts>/: repo commit; DB fingerprint (world-visible READY post count, max ready_at, sha256 over the ordered uid set of the eval scopes' world); effective config values (threshold, limit, embedder + translator endpoints/models); per record: returned uid list IN ORDER, similarity values, ready_at, and for non-English-scope records the anchored query text actually used with cache hit/miss and the fallback counter — probe: a smoke run's output contains a non-empty anchored_text for every xling row and config.threshold=0.40 (or the moved key's current value) (analysis P3/P7/P9)."
  - "Metrics live in pure-Java RetrievalEvalScorer with RetrievalEvalScorerTest green in the DEFAULT suite (no DB): capped Recall@16 = |top16 ∩ E| / min(|E|,16) AND raw recall both reported; MRR over the returned order (0 contribution when no expected uid is returned; none_expected rows excluded); over-return for none_expected rows (mean returned count + median post age from ready_at); lexical-only share (similarity:null rows); per-class slices with n — probe: RetrievalEvalScorerTest.cappedRecallAndMrrOverCannedToolJson feeds a canned tool JSON and asserts each number (analysis P5/P12)."
  - "FAILURE-MODE (scorer boundaries, analysis P5/P12): RetrievalEvalScorerTest feeds an expected set of 20 with 16-of-20 retrieved (capped 1.0, raw 0.8) and a none_expected row returning 12 posts (over-return 12, contributes to NO recall/MRR denominator) — a mutation pooling none_expected rows into recall, or reporting only raw recall, fails."
  - "DETERMINISM self-check (D19, docs/spec/llm.md:475-480): within one invocation the runner executes the full set TWICE and asserts per-query uid lists are byte-identical; it also refuses to compare runs across differing DB fingerprints, reporting fingerprint drift instead of scoring it (analysis P2/P7)."
  - "Translator-leg fidelity (D58, analysis P8): the en scope issues NO translator call (a call counter asserted 0); a non-zero fallback counter (breaker/failure/over-cap) ABORTS scoring of the xling class with a named error naming the affected records — the run never silently scores a fallback-degraded anchored leg (the M1-859 counted-fallback rule); FAILURE-MODE probe: a scratch run pointed at a dead translator endpoint exits nonzero with the fallback count, not a completed score."
  - "Default-suite containment (engineering-rules §8 assumptions-that-always-skip ban; §5): the runner carries @Tag(\"retrieval-eval\") and the provider POM gains exactly one failsafe excludedGroups entry for that tag — probe: `grep -n 'excludedGroups' infochat-provider/pom.xml` shows the single entry; plain `mvn verify` from the repo root is green AND its failsafe run list contains no RetrievalEvalRunnerIT (verify log probe); the operator invocation (documented in the runner javadoc) uses -Dit.test filtering (the tripwire-legal form) with the exclusion overridden."
  - "Residual-divergence disclosure (memory: campaign-harnesses-must-disclose-excluded-paths): .bench/retrieval-eval/README.md enumerates every way the runner's measured path differs from production (expected: only the cancellation-arming statement-timeout leg and the eval scopes' empty subscription set, neither result-affecting) — probe: grep -n 'excluded\\|divergence' .bench/retrieval-eval/README.md returns the enumeration (analysis P1/P15)."
  - "No committed surface beyond files_scope — probe: git status --porcelain shows no new tracked path outside the four repo files (results and README live under gitignored .bench/); mvn verify green from repo root."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalScorerTest.java
      — CI-covered metric math incl. the failure-mode legs above (plain
      JUnit, no DB).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalRunnerIT.java
      — operator-run only (tagged, POM-excluded); its verifications are the
      self-checks + probes above.
  preserves:
    - all tests currently green on main (the failsafe exclusion must not
      match any existing test — verified by the verify-log probe in
      acceptance item 7)
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
    date: 2026-08-27
    verdict: REWORK
    checks: "SPEC-TRUTHNESS: WARN; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: WARN; SCOPE: PASS"
    diff_stats: "7 files, +900/-10 (runner 534, scorer 168, scorer test 164, POM 21, test props 5, board+ticket 18)"
    rework: 2
    notes: "smoke-run + dead-translator probes accepted as evidence; 2 low findings (fallback dedup, dead accumulators); 4 attempted falsifications dropped"
  - round: 2
    date: 2026-08-27
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "fix diff: 3 files, +37/-10 (code: runner +5/-3, scorer +0/-6; rest = round-1 ticket record); build green 09:03, scorer 5/5, runner absent from failsafe list"
    notes: "both r1 items SATISFIED (dispositions in .scratch/tick-review-M1-929-r2.txt); 3 attempted falsifications dropped; 1 informational note (fully-qualified java.util.Set at 3 sites)"
overrides: []
aborted_attempts: []
reopens: []
clarity_check: "start 2026-08-27: citations re-verified (queryFusedPosts sole in-tree construction, worktree hits are gitignored stale trees; config keys at :501-502; worldPredicateSql :216-227; QueryTranslationCache get/put; MeteredLlmProvider emits llm.calls.total{task,provider,model,outcome} — the P8 counters come from an injected MeterRegistry, no main-source change). Test-classpath stubs TestLlmProvider/StubEmbeddingProvider are globally-enabled @Priority alternatives (M1-644) — the runner's TestProfile must quarkus.arc.unselected-alternatives them or it measures a stub (README-disclosed control, not a divergence). Fallback detection = cache-row absence after a non-en execute (all four fallback legs skip the cache write; the retention-belt skip is unreachable at default input-max-length 500 — belt boundary recorded in the manifest via the effective input-max-length). Fingerprint uid_sha256 concatenation to be reproduced against the frozen test DB at smoke time (labels pin ready=5214/06ed0de1…). No @Tag exists anywhere in the test tree today — the excludedGroups entry can match only the runner."
escalation_reason:
---

# M1-929: Harness: score golden set over production fused SQL

## Context

The golden set (M1-928) has nothing to run it: no harness executes the
production fused retrieval query anywhere — the M1-859 campaign harness
explicitly left "lexical+RRF fusion deliberately unbuilt" as a disclosed
divergence (docs/measurement/direct-chat-e2e.md:410-419). This ticket builds
the instrument M1-930 scores with: an operator-invoked test-scope runner that
drives the REAL SemanticSearchTool bean against the test DB per golden query,
a pure scorer (Recall@16 capped+raw, MRR, over-return, lexical-only share,
per-class slices), the run manifest/self-checks, and the POM containment that
keeps the live-DB runner out of the default suite. Shared analysis:
`analysis_ref:`. Blocked on M1-928 — the runner's operator leg consumes the
committed set and its validator.

## Root cause

Not a code defect — a missing instrument (analysis document). What is proven:
the fused SQL exists only in `SemanticSearchTool.queryFusedPosts`
(SemanticSearchTool.java:198-335, the sole in-tree construction); its result depends
on execution-mode prerequisites a copy would miss (SET LOCAL
hnsw.iterative_scan = strict_order at :192-196, in-arm D59 predicates, RRF
rank windows, perSourceCap at :67-69, the 16 KiB emission budget at :50) and
on config-injected values (0.40/16, :92-95); the anchor leg
(QueryAnchorTranslator, D58 four conditions) is cache- and scope-partitioned.
Re-implementing any leg measures a different system (the M1-859 lesson);
executing the production bean is the only shape where fidelity holds by
construction.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P5
(the scorer half: capped+raw recall and the |E|>k failure mode), P8, P9,
P11, P12 (the scoring half: none_expected rows scored by over-return,
never recall), P15 (prod isolation — no prod URL in any harness path),
plus the fingerprint half of P7 and the world half of P10.

- P1: approximation drift — run the production bean; disclose every residual
  divergence in the .bench README; never fork the SQL.
- P2: execution-mode prerequisites — running the bean carries the GUC, arm
  orders, cap, and budget; the determinism double-run self-check is the tripwire.
- P3: config hardcoding — threshold/limit from the booted config keys; the
  manifest logs the effective values per run.
- P8: translator-leg fidelity — en is a no-op (counter asserted 0); fallbacks
  counted and scoring of the xling class ABORTS on any fallback, never
  silently degrades; cache hit/miss recorded so first-run vs cached-run state
  is visible.
- P9: no-op translation invisibility — the anchored text per xling row is
  recorded in the output so untranslated/passthrough legs are visible in the
  record.
- P11: default-suite containment — @Tag + a single explicit POM
  excludedGroups entry; no assumption-always-skip; metric math stays CI-green
  via the pure scorer test.
- P7 (fingerprint half): every output stamped with the DB fingerprint;
  cross-fingerprint comparison refused and reported as drift.
- P10 (world half): the runner uses the five seeded eval scopes (M1-928's
  seed SQL); the world uid-set fingerprint pins the world per run.

## Approach

- **Files to touch** — `files_scope`: the runner IT, the scorer, the scorer
  test, one provider-POM failsafe hunk, and gitignored `.bench/retrieval-eval/`
  (results + README).
- **Steps in implementation order:**
  1. `RetrievalEvalScorer` + `RetrievalEvalScorerTest` first (pure metric
     math, CI-green before any live leg exists) — the numbers the whole
     family will quote are test-covered from day one.
  2. POM containment hunk (tag + excludedGroups) and the empty runner shell —
     plain `mvn verify` green with the shell present (P11 proven before the
     live leg is written).
  3. The runner: Quarkus boot with devservices off and operator JDBC config;
     inject SemanticSearchTool; per record: resolve the eval scope for
     `scope_lang`, call `execute()` at the default limit, capture the JSON,
     the anchored text (via the translator cache-read path or a capture seam
     in the runner — never by re-translating), and the counters; write
     manifest + per-query JSONL; then score via the scorer.
  4. Self-checks: double-run byte-identity, fingerprint stamp/refusal,
     en-no-translator-call, fallback abort (P2/P7/P8).
  5. `.bench/retrieval-eval/README.md`: residual-divergence enumeration +
     the operator invocation command.
- **Controls to preserve (§10):** no production path is rerouted; the default
  suite's composition is unchanged except the added scorer test; existing
  SemanticSearchTool ITs (D19 byte-identical pins, isolation arms, M1-917
  budget ledger) untouched; DB writes confined to eval-scope translation-cache
  rows on the test DB.
- **Pitfall→mitigation:** P1→step 3 (bean injection + no-construction probe)
  and step 5 (disclosure); P2→step 4 double-run; P3→step 3 manifest; P8→
  step 4 counters/abort; P9→step 3 anchored-text capture; P11→step 2; P7→
  step 4 fingerprint; P10→step 3 eval scopes.

## Definition of done

The scorer test is green in the default suite; plain `mvn verify` is green
without the runner; the operator invocation executes the full committed
golden set through the production bean on the test DB, producing manifest +
per-query JSONL + per-class scores; the determinism, fingerprint, en-no-call,
and fallback-abort self-checks pass on a clean run (and the fallback abort
fires on a dead-endpoint scratch run); the .bench README enumerates residual
divergences; the diff touches nothing outside `files_scope`.

## Verification

- P1/P2 → acceptance items 1, 5, 8: the no-construction grep, the double-run
  byte-identity self-check, and the divergence enumeration (a forked or
  GUC-less reimplementation fails the self-check or must disclose itself).
- P3 → acceptance item 2: the manifest's config values match the booted
  keys (a hardcoded 0.40 diverges the day the key moves).
- P5/P12 → acceptance items 3-4: scorer unit tests over canned tool JSON,
  including the 20-expected and none_expected failure modes.
- P7 → acceptance item 5: fingerprint stamped on every output; drift refused.
- P8 → acceptance item 6: en call counter 0; dead-endpoint scratch run exits
  nonzero with the counted fallback (FAILURE-MODE).
- P9 → acceptance item 2: anchored_text non-empty on every xling row.
- P10 → the runner reads the five seeded eval scopes; world fingerprint in
  the manifest.
- P11 → acceptance item 7: the POM grep, the verify-log probe, and green
  plain verify.
- P15 → acceptance items 8-9: no prod URL in harness paths; no tracked file
  outside files_scope.

## Out-of-scope

Named in `out_of_scope`: any production/main-source change (the executed path
is never the target); fixture changes (M1-928); the baseline record (M1-930);
threshold tuning (fixed inputs); retrieval improvements; CI gating; prod
containers. If a self-check exposes a production-query surprise, that is a
finding to record and escalate — never a harness tweak to mask.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-929-retrieval-eval-harness.md
```

## Round 1 rework

1. Finding 1: make fallbackRecords a deduplicated collection
   (LinkedHashSet) at RetrievalEvalRunnerIT.java:191 so the abort message
   and manifest count/list the 12 affected records once, evaluated via
   the dead-translator probe re-run ("translator fallback on 12
   cross-lingual record(s)", each xl-* id once, manifest list length 12)
   plus green repo-root `mvn verify` with the runner still absent from
   the failsafe run list.
2. Finding 2: delete the dead accumulators matched/expectedTotal/
   cappedTotal at RetrievalEvalScorer.java:99-101 and :142-144, evaluated
   via the grep probe returning nothing plus RetrievalEvalScorerTest 5/5
   green in the default suite.

## Review observations

- Round 1 RECOMMENDED-NEW-TICKET (recorded, no decision requested): the
  derived no-op-translation signal (per-language share of xling rows whose
  anchored text equals the source query, analysis P9's "no-op flag") is
  not computed anywhere; the raw anchored_text per xling row IS recorded
  in queries.jsonl, so the signal is derivable when the baseline record
  is authored — fold into M1-930's record work or grow a scorer slice
  field there.
