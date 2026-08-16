---
id: M1-862
title: "A/B rig: raw vs prefixed vectors on the deployment embedder"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe (apparatus ticket; no test can exist — the .bench tree is
  gitignored, the M1-844 posture): sed -n '52,63p'
  docs/measurement/retrieval-separability.md returns the committed
  two-space caveat — "The harness embeds with nomic's trained task
  prefixes … production embeds raw unprefixed text on both the query and
  the document side … close but not identical scales … ~±0.01 uncertainty"
  — i.e. the prefix delta on THIS deployment has a caveat and no number,
  and after M1-859 lands, grep -rnE 'search_document|search_query'
  .bench/direct-chat-e2e/harness/ returns NOTHING (M1-859's amended
  acceptance mandates raw-only): no instrument exists that can produce a
  prefixed arm in the deployment's own runtime. Observed consequence: any
  adopt-or-drop decision on the prefix convention today is ungrounded in
  local evidence — the M1-748 record §6 (:337-342) explicitly parks the
  question as "an unmeasured net gain".
analysis_ref: docs/plan/m1/tick-analysis/prefix-convention-ab.md
blocked_by: [M1-859]
files_scope:
  - .bench/prefix-ab/
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code, config, or spec change — infochat/**,
    docs/spec/**, docs/design/** untouched; the embedding pipeline, both
    vector stores, and every threshold stay frozen (D54; docs/spec/llm.md
    §Embedding pipeline "must not change … without a re-embed plan"). mvn
    verify runs as the no-regression leg only.
  - >-
    RUNNING the A/B or writing the measurement record — that is M1-863,
    blocked on this ticket; the rig delivers instrument + self-checks +
    the frozen labelled query set, and embeds no measurement cell.
  - >-
    The GGUF/llama-server harness runtime — both conventions run on ONE
    local Ollama container (the deployment's own embedder class, D54);
    mixing runtimes reintroduces the characterized ~±0.01 cross-runtime
    term (M1-748 record §1) into the arm delta.
  - >-
    PROD containers — never the deployment's Ollama/compose stack, not
    even read-only (measurements-never-ride-prod-containers); an isolated
    local container with the same model only.
  - >-
    The doc-store corpora (doc_embedding command intents/topics) and the
    post↔post linking distribution — the A/B answers the post query→doc
    question; the other surfaces are recorded as not-settled (M1-748 §5.4
    stub-surface trap for the doc side; no labelled same-story set for
    linking, §5.2/§6).
  - >-
    Re-tuning ANY threshold — the 0.60 admit floor is a fixed input
    (production's current value, 1 − 0.40 distance), not a variable;
    languages beyond the enabled five (th/zh/ja/ar anchored rows exist in
    the M1-717 data but are out of scope); completing the M1-717
    pooling_pending labels (M1-748 §6: fixture completion is an entry
    cost only for harness-based threshold derivation; the A/B's
    statistics are arm deltas, which one-sided label incompleteness
    biases identically).
acceptance:
  - "One isolated local Ollama container serves the whole rig, pinned in a manifest: .bench/prefix-ab/manifest.json names the container, the Ollama image tag, the nomic-embed-text model digest (ollama show), the corpus file's sha256 (.bench/direct-chat-e2e/corpus/posts.jsonl, the M1-859-pinned snapshot), and the per-arm convention — and references NO prod URL (the rig's endpoint is localhost; prod's http://ollama:11434 appears nowhere in rig scripts) — probe: grep -n 'sha256' .bench/prefix-ab/manifest.json returns the corpus pin AND grep -rn 'ollama:11434' .bench/prefix-ab/ returns NOTHING."
  - "The corpus is embedded under BOTH conventions through that one container: embed_docs.py (adapting M1-859's landed embed_corpus.py doc-surface composition verbatim — the identical text fed per post, only the convention differs) writes two vector stores, raw and 'search_document: '-prefixed, each with the manifest's convention field — probe: grep -c 'search_document' .bench/prefix-ab/embed_docs.py shows the prefix construction and the two stores' record counts are equal (self-check output asserts count equality with posts.jsonl lines)."
  - "DETERMINISM self-check (P4): the rig embeds one fixed probe document twice per convention and asserts byte-identical vectors — python3 .bench/prefix-ab/selfcheck.py exits nonzero on any mismatch — a nondeterministic runtime cannot produce a scored A/B."
  - "ARM-DISCRIMINATION self-check (P5, FAILURE-MODE): the same probe document embedded raw and prefixed must differ by cosine distance > 0.01 — a runtime that silently strips prefixes, normalizes input, or collapses the arms makes selfcheck.py exit nonzero with a named ABORT line — never a two-labeled one-arm run; the rig also byte-logs the exact input string sent per arm (probe-inputs.log) so the record can state what the runtime actually received."
  - "The labelled query set is FROZEN before any measurement cell runs (P7): coverage.py resolves campaign-fixture context_uids (12-char prefixes against the snapshot's full uids) and M1-717 relevant_uids (full sha256, labelled against the 9,224-post m1-717 corpus) against the snapshot, writes .bench/prefix-ab/coverage.json naming every resolvable query and every counted drop, and the frozen n per (set, language) is stated — probe: python3 .bench/prefix-ab/coverage.py prints per-set n= rows and grep -c 'drop' .bench/prefix-ab/coverage.json counts unresolvable labels (disclosed, never silent; a query with zero resolvable labels is dropped from labelled metrics entirely)."
  - "Non-English campaign-fixture queries are anchored ONCE, ahead of embedding, through M1-859's anchor.py + persistent query-anchor cache (D58(b) determinism-by-construction; M1-746 at-the-tool anchoring), and the anchored English text is held byte-identical across all downstream cells — probe: the rig asserts an identical content hash per query across conventions and grep -n 'query-anchor-cache' .bench/prefix-ab/embed_queries.py shows the M1-859 cache path reused, never a second translator call path; the M1-717 anchored set is consumed pre-anchored (machine_english fields, the deployed translator's recorded outputs — the floor-check convention)."
  - "The rig's README discloses every excluded path (campaign-harnesses-must-disclose-excluded-paths): the lexical arm + RRF fusion (the A/B measures the semantic arm only), the doc-store corpora, the linking distribution, the four non-enabled languages, and the one-sided M1-717 label incompleteness (arm deltas are the statistic) — probe: grep -n 'excluded' .bench/prefix-ab/README.md returns the enumeration."
  - "No production or committed surface changes — probe: git status --porcelain shows no new tracked path (all outputs under gitignored .bench/); mvn verify from repo root is green (no-regression only, engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Apparatus work under gitignored .bench/ (the M1-844 posture); the
      verifications are the rig's own self-checks, the coverage probe, the
      manifests, and the file-level probes above. mvn verify covers the
      no-regression leg.
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
decision_refs:
  - D54
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
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-862: A/B rig: raw vs prefixed vectors on the deployment embedder

## Context

Production embeds raw unprefixed text with nomic-embed-text-v1.5 on both the
query and document side (M1-748 record caveat, retrieval-separability.md
:52-63; re-verified: no `search_document`/`search_query` string anywhere in
either module tree; `infochat.embeddings.model=nomic-embed-text` at provider
application.properties:476 and collector :658). nomic's model card trains
with task prefixes — external evidence this deployment never adopted, and
whether adopting them widens true-match-vs-floor separation HERE is
unmeasured. The committed record parks the question as "an unmeasured net
gain" (:337-342), and the gemma lesson forbids deciding it on the model
card. This ticket builds the instrument M1-863 scores with: one isolated
Ollama container (the deployment's own embedder class — kills the
characterized ~±0.01 cross-runtime term by putting BOTH arms on one
runtime), the corpus embedded under both conventions, the frozen labelled
query set, and the self-checks that make a silent one-arm collapse
impossible. Shared analysis: `analysis_ref:`. Blocked on M1-859 — the rig
adapts its landed embed_corpus.py (doc-surface composition), spot_check
shape, and anchor.py + cache; forking those now would recreate the exact
two-conventions drift this family just paid a premise-fail hurdle to remove
(its amended out_of_scope names this A/B as the follow-up).

## Root cause

An unmeasured decision, not a code defect — see the analysis document's Root
cause. What is proven: the two conventions diverge on both sides (record
caveat + verification greps); the only prefix datum is the M1-748
harness-vs-production scale caveat; no instrument can currently produce a
prefixed arm in the deployment's own runtime (M1-859 lands raw-only by
design). What the rig must make checkable before M1-863 runs: that the
runtime treats the two conventions as different inputs (P5 — Ollama's
server-side behavior toward prefixes is an observed fact, not an
assumption), that labels transfer across the corpus gap (P7 — M1-717
labels were built against a 9,224-post corpus; the A/B runs on the
11,789-post campaign snapshot), and that the whole pipeline is
deterministic on pinned inputs (P4, D19 posture).

## Pitfalls

Numbered per the analysis document; this ticket carries P4, P5, P6, P7,
P8, P10, P13, P14.

- P4: cross-runtime noise — both conventions through ONE pinned Ollama
  container; determinism self-check before anything is scored (the arm
  delta must exceed the ~±0.01 class of noise to mean anything at the
  0.02 effect-size floor M1-863 pre-registers).
- P5: runtime template injection / silent prefix normalization — the
  discrimination self-check aborts nonzero if raw and prefixed vectors of
  the same doc collapse; inputs are byte-logged; the runtime's observed
  behavior is recorded, never assumed from the model card (the transport
  layer of the gemma guard).
- P6: prod containers — an isolated local container only; the manifest and
  scripts reference localhost, never the deployment's stack.
- P7: label transfer — coverage.py resolves every label, counts and lists
  drops, freezes the labelled set and its n BEFORE any cell runs; drops
  are disclosed, never silent.
- P8: fixture similarity fields are retired lexical-approximation
  artifacts — the rig reads only turns/context_uids[].uid from fixtures
  and recomputes every similarity from its own vectors.
- P10: anchor-path divergence — non-English fixture queries anchor once via
  M1-859's anchor.py + persistent cache; anchored text is byte-identical
  across cells (asserted by content hash); the M1-717 set is consumed
  pre-anchored (machine_english).
- P13: record integrity starts here — the README's excluded-paths
  enumeration is part of the measurement's contract, not decoration.
- P14: ordering — blocked_by M1-859 (adapt, don't fork, the landed
  apparatus); M1-863 is blocked on this ticket.

## Approach

- **Files to touch:** `files_scope` — everything under new gitignored
  `.bench/prefix-ab/`: `manifest.json`, `embed_docs.py` (dual-convention
  corpus pass, adapted from M1-859's `embed_corpus.py`), `embed_queries.py`
  (dual-convention query path; anchors via M1-859's cache), `coverage.py`
  (label resolution + freeze), `selfcheck.py` (determinism +
  discrimination + count equality), `probe-inputs.log` (byte-log),
  `README.md` (disclosure), and the two vector stores it writes.
- **Steps, in implementation order:**
  1. Bring up the isolated Ollama container (pinned image; `ollama pull
     nomic-embed-text`; record the digest) — no compose stack, localhost
     only (P6).
  2. `embed_docs.py`: reuse M1-859's doc-surface composition verbatim;
     embed the snapshot twice (raw; `search_document: ` prefix); write both
     stores + manifest (corpus sha256, image, digest, conventions) (P4).
  3. `selfcheck.py`: determinism probe (same doc twice → identical bytes,
     both conventions) and the discrimination probe (raw vs prefixed →
     cosine distance > 0.01, else ABORT nonzero); byte-log the probe
     inputs (P5).
  4. `coverage.py`: resolve campaign context_uids (prefix match) and
     M1-717 relevant_uids (full-uid match) against the snapshot; write
     coverage.json with per-(set, language) frozen n and counted drops
     (P7).
  5. `embed_queries.py`: for each frozen query, anchor non-English fixture
     queries once via M1-859's anchor cache; hold anchored text
     byte-identical; provide the raw / `search_query: ` pair (P10). No
     measurement cell runs here.
  6. `README.md`: the excluded-paths disclosure (lexical+RRF, doc store,
     linking, non-enabled languages, label incompleteness) (P13).
- **Controls to preserve (§10):** no production path is rerouted; the
  controls are the family's integrity rules — prod isolation, frozen
  contract (no `infochat.embeddings.*` or store change), determinism of
  the instrument, disclosure completeness, and mvn verify green.
- **Pitfall→mitigation:** P4→steps 1-3; P5→step 3 (abort-on-collapse) +
  the byte-log; P6→step 1 + the manifest greps; P7→step 4; P8→the rig
  reads no fixture similarity field (assert in code review of the rig);
  P10→step 5; P13→step 6; P14→the blocked_by frontmatter.

## Definition of done

One pinned isolated Ollama container, manifest-named (image, model digest,
corpus sha256, conventions), with the corpus embedded under both
conventions at equal record counts; the determinism and
discrimination self-checks pass (discrimination aborts nonzero on arm
collapse); the labelled query set frozen with per-set n and counted drops
in coverage.json; non-English fixture queries anchored once through
M1-859's cache with byte-identical text across cells; the README's
excluded-paths enumeration in place; git status shows no new tracked path;
mvn verify green from the repo root. No measurement cell has run.

## Verification

- P4 → acceptance items 1-3: the manifest pin greps; count-equality
  assertion; `python3 .bench/prefix-ab/selfcheck.py` exits 0 on
  determinism (same doc twice, byte-identical) — a nondeterministic
  runtime fails red before any scoring exists.
- P5 (FAILURE-MODE) → acceptance item 4: feed the same doc through both
  conventions; a runtime that strips/normalizes prefixes collapses the
  pair, and selfcheck.py must exit nonzero with a named ABORT — the
  hostile input is the runtime itself; probe-inputs.log shows the exact
  bytes sent per arm.
- P6 → acceptance item 1's grep: `grep -rn 'ollama:11434' .bench/prefix-ab/`
  returns NOTHING (no prod endpoint reference).
- P7 → acceptance item 5: coverage.py prints per-set n= rows; drops are
  counted in coverage.json — a silently shrunk labelled set cannot occur.
- P8 → the rig's fixture reads: `grep -n 'similarity'
  .bench/prefix-ab/embed_queries.py .bench/prefix-ab/coverage.py` returns
  no fixture-similarity read (the only similarities are the rig's own
  computed ones).
- P10 → acceptance item 6: the anchor-cache path grep + the per-query
  content-hash equality assertion across conventions.
- P13 → acceptance item 7: `grep -n 'excluded' .bench/prefix-ab/README.md`.
- P14 → frontmatter blocked_by; no M1-859 script is copied — referenced or
  adapted with the source named in the rig's README.
- acceptance item 8 → `git status --porcelain` clean of new tracked paths;
  mvn verify green.

## Out-of-scope

Named in `out_of_scope`: any production/spec/config change (frozen
contract, D54); running the A/B or writing the record (M1-863's); the
GGUF/llama-server runtime (one Ollama runtime for all cells); prod
containers; the doc-store corpora and the linking distribution (recorded
as not-settled by M1-863); threshold tuning (0.60 is a fixed input);
non-enabled languages; completing the M1-717 pooling_pending labels (arm
deltas are the statistic; one-sided incompleteness biases both arms
identically — disclosed, not fixed here). If the discrimination probe
reveals the runtime already applies its own template, that is a FINDING
M1-863 records (production embeds through this runtime — its behavior IS
the production shape), not a rig modification to work around.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-862-prefix-convention-ab-1.md
```
