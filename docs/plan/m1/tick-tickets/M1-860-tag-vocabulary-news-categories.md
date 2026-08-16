---
id: M1-860
title: "Measure news-category tagger coverage on the local model"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe (evidence gap, verified 2026-08-16): (1) grep -rli
  'fashion\|politics\|sport' docs/measurement/ returns NO file — no
  measurement record covers candidate-category tagging; the only tagger
  numbers there are model-screening scores against the CURRENT vocabulary
  (track-a-screening-in-progress.md:141). (2) The measured corpus cannot
  contain the behavior: grep '"fashion"\|"politics"\|"sport"' over
  .bench/direct-chat-e2e/corpus/posts.jsonl (11,789 posts) returns ZERO
  matches. (3) The shipped default vocabulary cannot classify it:
  prod/config/bootstrap-sources.json's tags[] union is
  ai/development/claude/security/java/video/nostr (read in full), and
  .bench/track-a/vocabulary.pinned.json (25 rows, sha256-pinned) contains
  no mid-band category. Wrong behavior stated: an operator following
  fashion/politics/sport sources gets posts that the production tagger
  cannot tag with the right category — validated against a vocabulary with
  no such names — and NOTHING measures whether adding the names degrades
  the current feed before an operator relies on it.
analysis_ref: docs/plan/m1/tick-analysis/tag-vocabulary-coverage.md
blocked_by: []
files_scope:
  - docs/measurement/tag-vocabulary.md
complexity: high
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code, prompt text, migration, or docs/spec/** edit. This
    ticket produces evidence only; the seed that consumes the winning list
    is M1-861 (blocked_by this ticket). No verdict here lands a tag row.
  - >-
    COMMITTING .bench/ working data (gitignored by design, .gitignore:15;
    the lang-quality/M1-844 posture). Only the promoted record at
    docs/measurement/tag-vocabulary.md is committed.
  - >-
    RE-TUNING the current vocabulary or proposing removals — the 2026-08-16
    user ruling preserves the current categories; append-only is the spec
    commitment (schema.md §Sources and tags, Vocabulary lifecycle).
  - >-
    CHANGING the tagger prompts (tagger.md / tagger-fallback.md), the
    temperature/sampling posture, or TagVocabulary/TaggerWorker — M1-751's
    out-of-scope rulings carry over unchanged; the spike MEASURES the
    production shape, it does not alter it.
  - >-
    Remote/LLM-API model arms — the spike runs the deployment's LOCAL
    ModelTask.TAGGER-route model per the user ruling; other local weights
    already screened in docs/measurement/track-a-screening-in-progress.md
    may be added only as context rows, not as new arms.
acceptance:
  - "Pre-registered thresholds land BEFORE any arm runs and the record proves the order: the committed record opens with the bars — per candidate category a CORRECT-APPLICATION rate, a NO-DRIFT bound per current-feed tag, and a PROMPT-BUDGET ceiling — and `git log --follow docs/measurement/tag-vocabulary.md` shows the thresholds commit predating every results commit (the lang-quality/M1-844 pre-registration posture; analysis P1)."
  - "A same-prompt resample NOISE FLOOR is measured first and every headline bar exceeds it — the M1-751 finding (same tagger prompt resampled: 5/10 identical tag sets, mean Jaccard 0.783, no temperature set) makes single-call percentages meaningless below that floor; the record states the floor and the paired/multi-sample design it forces (spec: docs/spec/llm.md §SPI shape — the zero-or-more validated-tags contract the arms measure; analysis P1) — probe: the gitignored spike artifact .bench/tag-vocab/noise-floor.json holds the resample matrix (per post x per resample, n >= 3), grep -n 'NOISE-FLOOR' docs/measurement/tag-vocabulary.md shows the recorded floor value with every pre-registered headline bar printing a strictly positive margin above it, and a bar at margin 0 or below fails this item (expected relation: the floor cell cites the M1-751 prior 0.5 identical / Jaccard 0.783 as its sanity anchor)."
  - "CORRECT-APPLICATION measured per candidate category: sampled and/or synthetic domain-appropriate content (final candidate set is the spike's OUTPUT, informed by common news taxonomies; input superset at least fashion, sport, politics, business, culture, health, science, travel), Stage-1-SHAPED bodies (NFKC-normalized, no bidi/zero-width, [REDACTED:<id>] placeholders where flagged content belongs) run through the PRODUCTION tagger shape — real prompts/tagger.md AND tagger-fallback.md, {#tags} expanded one line per name in ORDER BY name order, <<<UNTRUSTED_CONTENT>>> wrapper with a fresh random delimiter per call — probe: grep -n 'CORRECT-APPLICATION' docs/measurement/tag-vocabulary.md shows the per-category cells (analysis P2, P3, P5, P15)."
  - "NO-DRIFT measured on current-feed items (campaign-snapshot / track-a corpus posts): per-tag agreement against the baseline-vocabulary arm, plus the ADVERSARIAL leg — AI-policy / AI-regulation content must NOT newly acquire the politics tag beyond the recorded noise floor (a politics that swallows the existing ai/security/regulation-shaped coverage is a rejection, not a win) — and must_not predicates are scored against the model's PROPOSED tags, never the post-validation survivors (vacuous otherwise: TaggerWorker.validate drops out-of-vocab proposals before storage) — probe: grep -n 'NO-DRIFT\\|politics' docs/measurement/tag-vocabulary.md shows the adversarial cells and the proposed-tags scoring statement (spec: docs/spec/llm.md §Failure handling (recap) partial-valid rule; analysis P4)."
  - "PROMPT-BUDGET recorded: rendered prompt bytes (and llama-server token count where available) for the baseline and each enlarged arm, per-call latency, and schema-violation / fallback-shape rates — the production tagger has NO configured prompt budget (verified: only model / max-concurrency / poll-interval / sweep keys exist), so size and destabilization are measured properties — probe: grep -n 'PROMPT-BUDGET' docs/measurement/tag-vocabulary.md shows the table (analysis Ground truth, spec: docs/spec/llm.md §SPI shape)."
  - "Injection-compliance retention measured per arm: the track-a injection-shape fixtures (self-referential tag instructions, redaction-placeholder bodies) re-run under the enlarged vocabulary — a candidate set that buys category coverage by losing wrapper discipline is recorded as a rejection with its numbers, never averaged into a headline (spec: docs/spec/security.md §Prompt-injection defenses (LLM call sites) — the wrapper + treat-as-data promise this leg guards; analysis P5) — probe: the gitignored spike artifact .bench/tag-vocab/injection-retention.json carries the per-arm must_not violation counts scored on PROPOSED tags (the build-tagger-cases.py must_not_scope convention), and grep -n 'INJECTION-RETENTION' docs/measurement/tag-vocabulary.md shows one cell per arm with its count plus a rejection-log row for every arm whose count exceeds the pre-registered bound — an arm over the bound with no rejection row fails this item."
  - "The WINNING LIST is recorded as the exact seed M1-861 cites — each name English, matching ^[a-z0-9][a-z0-9-]{0,47}$, with rejected candidates and their reasons — probe: grep -n 'WINNING\\|rejected' docs/measurement/tag-vocabulary.md shows the list and the rejection log (spec: docs/spec/commands.md §Surface conventions — the normalization every tag value must survive; analysis P15)."
  - "Measured state pinned in the record: the vocabulary snapshot per arm (sha256, the vocabulary.pinned.json convention), the repo commit, and the local model identity actually serving ModelTask.TAGGER during the run — probe: grep -n 'sha256\\|commit\\|model' docs/measurement/tag-vocabulary.md shows the pins (analysis P14, M1-844 measured-surfaces-are-moving)."
  - "The harness reuses the track-a offline pattern (llm.py against llama-server; the app is out) and the record states the render path — probe: grep -n 'harness\\|llama-server' docs/measurement/tag-vocabulary.md names the reuse (analysis P2)."
  - "mvn verify from repo root is green (evidence-only ticket; the build must not regress, engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Evidence-only: the campaign harness lives under .bench/ (gitignored)
      and the promoted record is the single committed artifact, so there is
      no JUnit surface to add (the M1-844 shape). mvn verify covers the
      no-regression leg. No test_plan.modifies entries — any pre-existing
      test edit would be an unauthorized §8 change.
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D5
  - D8
  - D19
  - D22
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

# M1-860: measure news-category tagger coverage on the local model

## Context

The Tier-1 tag vocabulary covers only this deployment's operator profile
(campaign snapshot: 23 tags in use, research=83%/ai=77%, empty mid-band —
verified absence leg: zero grep matches for the candidate names over the
11,789-post corpus). The skew is correct (user ruling 2026-08-16: preserve
current categories); the DEFECT is audience coverage — a deployment
following fashion/politics/sport sources gets a tagger whose output is
validated against a vocabulary with no such tags, so those posts become
research/ai-adjacent noise or `tags='{}'`, invisible to `searchPosts` tag
filters, `/follow-tag`, and the digest's D62 category arithmetic. Before
seeding standardized defaults (M1-861), the local tagger model must be shown
to (1) APPLY them to domain content, (2) NOT drift the current feed's
tagging when the vocabulary grows, and (3) stay inside a sane prompt budget
— the tagger renders the whole vocabulary inline and LLM output is
order-sensitive. Full shared context: `analysis_ref:` (analysis doc,
Pitfalls P1–P15).

## Root cause

Not a code defect: the vocabulary has no product-level default. Its only
automatic seeding is the bootstrap file's per-source `tags[]` union
(deployment.md §Operator inputs requires `tags ≥1` per SOURCE entry — no
vocabulary-without-source slot), and the organic path (`/add-source --tags`,
SourceUpsertService.java:108-111) unions caller-chosen names with no
standardization pressure (the glmai/kimiai vendor tail is that mechanism's
output). No migration seeds `tag` (grep `^INSERT INTO` over migrations: 4
hits, none on tag). The measurement this ticket runs does not exist anywhere
in docs/measurement/ (reproduction probes 1–3).

## Pitfalls

Numbered per the analysis document; the ones that bite THIS ticket:

- P1: sampling noise — the same tagger prompt resampled disagrees with
  itself ~half the time (M1-751: 5/10 identical, Jaccard 0.783; no
  temperature). Bars below a measured noise floor are decoration.
- P2: harness fidelity — render the real prompts, `{#tags}` block, ORDER BY
  name order, untrusted wrapper + fresh random delimiter (M1-751 was filed
  on a harness that did not reproduce production).
- P3: order sensitivity — the enlarged set must render in the same
  deterministic order production uses (M1-751 contract;
  TagVocabularyRefreshTest pins it).
- P4: drift scored on PROPOSED tags — validated-only scoring passes
  vacuously (validate drops out-of-vocab proposals before storage).
- P5: fixture hygiene — Stage-1-shaped bodies; keep the injection-shape
  fixtures so category wins that cost wrapper discipline are rejections.
- P12: .bench working data stays gitignored; only the record lands.
- P14: pin vocabulary shas, repo commit, model identity.
- P15: English, regex-valid candidate names only.

## Approach

Offline measurement campaign, track-a layer-2 pattern (`llm.py` /
`run-arm.py` against llama-server on this box; the app is out — a
measurement through the app measures the flow).

- **Files to touch:** `docs/measurement/tag-vocabulary.md` (new record).
  Everything else lives under `.bench/` (gitignored).
- **Steps, in order:**
  1. Pre-register thresholds and commit the record skeleton (acceptance 1)
     — order is the whole value of pre-registration.
  2. Build the fixture corpus: per-candidate domain content (sampled where
     the corpus/adversarial sets offer it, synthetic otherwise, Stage-1
     shape), current-feed drift items, the AI-policy adversarial set, and
     the track-a injection-shape fixtures (P5).
  3. Measure the same-prompt resample noise floor; adjust nothing downward
     past it (P1).
  4. Run arms: baseline vocabulary vs baseline+candidates, both prompt
     surfaces; record CORRECT-APPLICATION, NO-DRIFT, PROMPT-BUDGET,
     injection-retention cells (P2/P3/P4).
  5. Write the winning list + rejection log + pins; land the record (P14).
- **Controls to preserve:** none rerouted (no production code touched);
  the production prompts, `TagVocabulary`, and `TaggerWorker` are measured
  objects, not edit targets (out_of_scope).
- **Pitfall→mitigation:** P1→step 3; P2/P3→step 4 harness requirements;
  P4→scoring rule in acceptance 4; P5→step 2 fixture mix; P12/P14→step 5.

## Definition of done

Every acceptance item holds with its named probe green: thresholds
pre-registered and provably first; noise floor measured and exceeded;
per-category CORRECT-APPLICATION cells; NO-DRIFT cells including the
AI-policy adversarial leg with proposed-tags scoring; PROMPT-BUDGET table;
injection-retention cells; the winning list (English, regex-valid) +
rejection log; shas/commit/model pins; harness statement; `mvn verify`
green. The record is the single committed artifact.

## Verification

- P1 → record's noise-floor section + `git log --follow` ordering probe
  (acceptance 1, 2).
- P2/P3 → acceptance 3's render-path statement + the record's prompt-file
  shas; a re-ordered render arm documented as a harness check, scored
  against the same-prompt band.
- P4 → acceptance 4: the adversarial AI-policy cells + the explicit
  "scored against PROPOSED tags" method statement.
- P5 → acceptance 6: injection-fixture cells per arm; a compliance loss is
  a named rejection.
- P12 → out_of_scope + the record's artifact list (one file).
- P14 → acceptance 8 probes (sha256/commit/model).
- P15 → acceptance 7: name filter stated in the record.
- Failure-mode legs (mandatory): acceptance 4's adversarial leg (feeds
  AI-regulation content; asserts politics is NOT applied beyond noise while
  ai/security coverage holds) and acceptance 6 (feeds self-describing
  bodies; asserts the wrapper discipline holds under the enlarged
  vocabulary).
- acceptance 10 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — production/spec surfaces (M1-861's job), .bench
commits, vocabulary tuning/removal, prompt or sampling changes, remote
model arms. This ticket modifies NO pre-existing test (`test_plan.modifies`
empty); any such edit is an unauthorized engineering-rules §8 change.

## Census

Not class-scoped: this is a measurement campaign, not a fix guarding a
class of defect sites. (The related no-hardcoded-tag-names property is
probed in M1-861.)
