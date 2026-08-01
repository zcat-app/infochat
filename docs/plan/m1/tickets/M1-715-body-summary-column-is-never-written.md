---
id: M1-715
title: "post.body_summary is never written, yet EmbeddingWorker prefers it as embedding input"
status: done
created: 2026-07-29
last_updated: 2026-08-01
blocked_by: []
files_budget: 13
files_scope:
  - infochat-core/src/main/resources/db/migration/V7*__post_summary_done.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/summary/BodySummaryWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-llm-adapter/src/main/resources/prompts/body-summary.md
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/summary/BodySummaryWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/summary/BodySummaryWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready/ReadyPromoterIT.java
  - prod/switch-llm.sh
  - docs/design/05-llm-and-embeddings.md
  - docs/design/02-schema.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    Any re-embed of existing posts: no UPDATE/DELETE against
    `post_embedding`, no job that recomputes stored vectors. The 2026-08-01
    decision is roll-forward; a re-embed is an optional separate ticket.
  - >-
    Fetcher or ingest changes: no feed-`<description>` capture, no
    `PostPersister` INSERT change. The persister stays the sole ingest
    write path; `body_summary` is written post-ingest by
    `BodySummaryWorker` UPDATE.
  - >-
    The embedding model identity, the 768-dim invariant, and
    `EmbeddingMetadataStartupGuard`. The input TEXT changes for newly
    summarized posts; the model and dimensionality do not.
  - >-
    `EmbeddingWorker`'s input-preference branch (line ~488): it stays
    exactly as-is and becomes live. Only its pickup predicate changes.
  - >-
    Provider-side `/summary`, digest summaries, and `summary_anchor` —
    a different feature that shares `ModelTask.SUMMARIZER` routing but
    nothing else.
  - >-
    Dropping `body_summary` — the rejected alternative (b), recorded in
    the decision.
  - >-
    `M1-723`'s social-signal columns (`likes`, `reposts`, `social_score`)
    — same defect class, separate investigation.
  - any test not listed in test_plan
acceptance:
  - >-
    Migration `V71__post_summary_done.sql` adds
    `post.summary_done BOOLEAN NOT NULL DEFAULT FALSE`, backfills
    `summary_done = TRUE` for every pre-existing row that has passed the
    tagger (the prefix-embedded corpus and in-flight RAW posts; the
    V28/V57 backfill shape), and applies cleanly
    (Flyway apply green under `mvn verify`).
  - >-
    `BodySummaryWorkerIT`: a `RAW` post with `tagger_done=TRUE`,
    `summary_done=FALSE`, and `length(body)` above
    `infochat.summarizer.threshold-chars` gets a worker-written
    `body_summary` and `summary_done=TRUE`.
  - >-
    `BodySummaryWorkerIT`: a post at or below the threshold is never
    picked for summarization and costs zero LLM calls; the matching gate
    escapes are pinned in `EmbeddingWorkerIT` (an under-threshold post is
    embedded with `summary_done` staying FALSE) and `ReadyPromoterIT`
    (promoted likewise).
  - >-
    `BodySummaryWorkerIT`: after two failed LLM attempts the worker
    writes `summary_done=TRUE` with `body_summary` NULL (degraded
    release, canonical error class, `ThrottledAdminNotifier.notifyOnce`),
    and the post is then embedded from the first-800-chars fallback.
  - >-
    `EmbeddingWorkerIT`: an over-threshold post with
    `summary_done=FALSE` is NOT embedded; once `summary_done=TRUE` with a
    non-empty `body_summary`, the embed input built for it
    (`EmbeddingWorker.buildInputText`) equals
    `title + "\n\n" + body_summary`.
  - >-
    `ReadyPromoterIT`: an over-threshold post with `summary_done=FALSE`
    is not promoted to READY; it is promoted once `summary_done=TRUE`.
  - >-
    `BodySummaryWorkerTest`: an over-long LLM reply is truncated to the
    configured hard cap before storage, and the rendered prompt wraps the
    body in the per-call-id untrusted-content delimiter (classifier.md
    precedent).
  - >-
    The diff contains no UPDATE or DELETE against `post_embedding` and no
    path that re-embeds an already-embedded post (roll-forward only).
  - >-
    `docs/design/05-llm-and-embeddings.md` records the 2026-08-01
    decision (populate via `BodySummaryWorker`, roll-forward, no required
    re-embed, with the experiment evidence) and
    `docs/design/02-schema.md` documents `body_summary` as
    populated-by-`BodySummaryWorker` plus the `summary_done` flag.
  - >-
    Refusal marker (redteam r1 finding, INJECTION/low): the prompt
    instructs the §4.3 structured refusal; a reply of
    `{"summary": "[refused-action]"}` is persisted as NULL
    `body_summary` with `summary_done=TRUE`, no retry, and a
    notification under `summarizer.refusal` — the marker never reaches
    `post.body_summary` (`BodySummaryWorkerTest` unit,
    `BodySummaryWorkerIT` end-to-end).
  - >-
    Disclosure (redteam r1 finding, INFO-LEAK/low): `prod/switch-llm.sh`'s
    per-task privacy disclosure names the summarizer's ingest-side
    exposure (every long fetched post's body), keeping the
    §Secrets-handling "exactly which tasks and what each exposes"
    commitment accurate.
  - >-
    `scripts/verify-serialized.sh` (mvn -B clean verify) is green.
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/summary/BodySummaryWorkerTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/summary/BodySummaryWorkerIT.java
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready/ReadyPromoterIT.java
  preserves:
    - >-
      All tests currently green on main. In particular, existing
      embedding/ready IT fixtures with long bodies may need
      `summary_done=TRUE` in their INSERT column lists (see §Notes on the
      fixture blast radius).
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/schema.md §Posts and derivatives
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 1690
      removed: 153
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-08-01
    verdict: FINDINGS
    base: 7f9f621215e2b0303dcc8d7a0255bf93e4f30966
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-715-2026-08-01.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Two low findings on the in-progress diff: missing structured refusal
      marker in prompts/body-summary.md (INJECTION, embedding-poisoning
      channel, bounded), and the switch-llm.sh privacy disclosure's
      ingest-task enumeration not covering the now-ingest-side SUMMARIZER
      task (INFO-LEAK, public-post content). Both fixed in-branch per user
      directive (prompt + parseSummary refusal branch + summarizer.refusal
      class; disclosure line), re-audited r2.
  - date: 2026-08-01
    verdict: CLEAN
    base: 7f9f621215e2b0303dcc8d7a0255bf93e4f30966
    head: working tree
    verdict_file: docs/plan/m1/redteam/M1-715-2026-08-01-r2.md
    out_of_model_count: 2
    note: |
      Re-audit of the post-remediation diff: both r1 findings verified
      closed, no new findings on the remediation surface. Two advisory
      out-of-model notes (coaxed-refusal reverts to prefix baseline;
      per-post LLM cost amplification is the pre-existing per-post class).
clarity_check:
  date: 2026-08-01
  verdict: PASS
  warnings:
    - >-
      Refined 2026-08-01 (user directive): doc-only investigation became
      decision-plus-implementation; the follow-up-ticket acceptance item
      was dropped by the user. Re-linted clean after the rewrite.
  blockers: []
escalation_reason:
---

# M1-715: post.body_summary is never written — decision + populate implementation

## Context

`post.body_summary` has existed since the V7 schema (V7__joins_post.sql:143)
and was designed as an LLM abstract populated when body length exceeds a
threshold. That writer was never built: `PostPersister`'s INSERT omits the
column, no code path UPDATEs it, no test writes it, and the live corpus
confirms 0 of 9,236 posts have a non-empty value. `EmbeddingWorker`
nevertheless prefers it as embedding input (EmbeddingWorker.java:488), so
its first branch was dead and every one of the 9,224 embedded posts carries
a vector built from `title + first 800 chars of body`.

The ticket was filed as a doc-only investigation to choose between
populating the column and dropping it. The investigation (2026-08-01, this
session) decided **populate**, and the user then directed this ticket to
carry the implementation itself (dropping the original follow-up-ticket
acceptance item) rather than spawning a separate ticket.

## Decision (2026-08-01, recorded per the original acceptance)

**`post.body_summary` SHALL be populated** by an ingest-time LLM abstract
writer (`BodySummaryWorker`), from `body`, when body length exceeds a
configured threshold. Roll-forward: **no re-embed of the existing corpus is
required.**

Rationale, with the evidence gathered before deciding:

- An A/B experiment (synthetic 24-post corpus mirroring the live shape,
  `nomic-embed-text` 768-d, pgvector cosine, 11 gold-labeled queries in
  three classes) measured `title + ≤300-char summary` against
  `title + first 800 chars of body`: MRR 0.955 vs 0.803, summary arm never
  worse, strictly better on the long-body/late-content class that
  motivated the ticket. The dominant mechanism is boilerplate dilution:
  the first 800 chars of odysee-style bodies (live average 2,445 chars)
  are channel promo, which pollutes the vector.
- Cross-population safety (the re-embed question): probing a
  prefix-embedded corpus with summary-embedded posts kept the same-topic
  nearest neighbour at rank 1 in 24/24 cases (separation margins +0.09 to
  +0.26 over the best foreign match). Old prefix vectors and new summary
  vectors coexist; a bulk re-embed is an optional uniformity pass, not a
  correctness requirement.
- The rejected alternative (b) — mark vestigial and drop the column —
  would discard the measured retrieval gain to save one trivial migration.

Caveats recorded with the decision: the corpus was synthetic and the
summaries ideal-authored (ceiling case); production summary quality
variance is de-risked by the failure path (fall back to prefix) rather
than by a pilot gate.

## Acceptance

Mirrors the YAML `acceptance:` list — migration + worker + ordering +
failure-path ITs, unit cap/prompt test, roll-forward guarantee, design-doc
updates, green `mvn verify`.

## Out-of-scope

No re-embed, no fetcher/persister changes, no embedding-model or
dimension changes, no provider-side summary features, no column drop,
M1-723's columns. See frontmatter.

## Notes

- **Design: new `summary_done` cursor flag** (V28 `entity_done` / V57
  `classifier_done` precedent — ADD COLUMN DEFAULT FALSE + backfill UPDATE).
  Rejected alternative: overloading `body_summary` with an `''` sentinel to
  avoid a migration — deviates from the per-stage-flag idiom every
  post-tagger worker follows, and the load-bearing ReadyPromoter rule
  (ReadyPromoter.java:31-47 — every post-tagger flag must join its gate or
  promotion strands posts) is cleaner with an explicit flag.
- **Why embedding must wait for the summarizer**: both workers poll on
  seconds-scale schedules, but the summarizer pays an LLM call per post
  while embedding batches — without a gate, embedding wins the race every
  time and the feature is dead on arrival. Hence `summary_done=TRUE`
  (or `length(body) <= threshold`) joins `EmbeddingWorker.enumeratePending`
  and `ReadyPromoter.enumeratePending`.
- **Backfill choice**: the migration backfills `summary_done=TRUE` for
  rows that had passed the tagger at migration time (the V28/V57 shape).
  Sweeping the old corpus would spend LLM tokens for zero effect — those
  posts already carry prefix vectors and re-embedding them is out of
  scope. Not-yet-tagged rows stay FALSE and are summarized when they
  reach the stage; they carry no vector yet, so that is steady-state
  behavior, not a re-embed.
- **Fixture blast radius**: any existing test INSERT with
  `length(body) > threshold` that expects embedding/promotion will now
  stall unless it sets `summary_done=TRUE` (or shortens its body).
  Enumerate before implementing (grep test INSERTs into `post`); most
  fixtures have short bodies and pass through the `length(body) <=
  threshold` escape. `scripts/lint-partitioned-test-inserts.py` constrains
  fixture edits (D72).
- **LLM wiring is already routed**: `ModelTask.SUMMARIZER`
  (infochat-llm-adapter ModelTask.java:28) and
  `infochat.llm.summarizer.model` (collector application.properties:510)
  exist for the provider's `/summary`; the collector-side worker reuses
  that task, so `LlmRouterStartupGuard` needs no new keys.
- **Prompt**: new `prompts/body-summary.md` in infochat-llm-adapter,
  classifier.md shape — `<<<UNTRUSTED_CONTENT id="{{id}}">>>` wrapper with
  per-call rotating UUID, JSON reply, `LlmJson.stripCodeFence` parsing.
  This is why the ticket is `security_relevant: true`: untrusted post
  bodies enter an LLM prompt.
- **Disable idiom**: no `.enabled` keys exist in this codebase; the worker
  is disabled by `infochat.llm.summarizer.poll-interval=off`
  (`infochat.linking.interval=off` precedent). Defaults stay enabled;
  per-profile token-cost tuning (pi) is a config decision at deploy time.
- **V-number**: the migration takes the next free V-number at
  implementation time. Queue state 2026-08-01: main has V68; M1-741 (in
  flight) holds V69, M1-742 plans V70; M1-727/M1-707 are stale on V67/V68
  and owe renumbers. The user owns migration ordering — coordinate the
  number before writing the file. (Resolved: V71, user-approved.)
- **Scope expansion 2026-08-01 (user directive)**: the redteam r1
  audit's two low findings are fixed IN this ticket rather than
  escalated — the `[refused-action]` refusal marker (prompt +
  parse branch + refusal notification class) within the original scope,
  and the `switch-llm.sh` disclosure line via an explicit user-approved
  `files_scope` expansion (`prod/switch-llm.sh` added, `files_budget`
  13). The refusal follows the §4.3 convention: the model replies
  `{"summary": "[refused-action]"}` to in-wrapper action requests; the
  worker matches it only as a LEADING token (mid-text occurrences are
  content), persists NULL, advances the cursor, and notifies under the
  distinct `summarizer.refusal` error class — no retry (a refusal is a
  final answer, not a failure).
