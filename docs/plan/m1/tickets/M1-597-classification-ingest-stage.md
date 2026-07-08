---
id: M1-597
title: "Collector: real per-post classification ingest stage (ClassifierWorker + post.classification, unknown default)"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 16
files_scope:
  - infochat-core/src/main/resources/db/migration/V57__post_classification.sql
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/resources/prompts/classifier.md
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready/ReadyPromoterIT.java
  - docs/design/02-schema.md
  - docs/design/05-llm-and-embeddings.md
  - docs/spec/architecture.md
  - docs/spec/llm.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The provider render + retrieval surface (EligiblePostQuery,
    ClusterBlockRenderer, the reply bundles, docs/design/03-commands.md). That is
    M1-598 (blocked_by this ticket). This ticket produces and stores the
    classification data ONLY; it does NOT change what /summary emits — the
    renderer keeps showing today's reverted tags-copy stub until M1-598 re-points
    it. Do NOT touch anything under infochat-provider/.
  - >-
    Backfilling real classifications onto the pre-migration post backlog. Existing
    posts get the {unknown} column DEFAULT and are marked classifier_done=TRUE (no
    LLM re-run), mirroring V28's deliberate entity backfill. The post table is on a
    ~4-day fetched_at RANGE-partition TTL (Invariant 6), so the {unknown} backlog
    ages out of retention within days — a one-off backfill job is not worth it.
  - >-
    Reviving classification as a SUMMARIZER LLM output (the legacy §5.4.4 framing).
    Rejected: it would make a shown field LLM-authored at query time, breaking the
    D19/D36 byte-identical-replay property /retry depends on, and would require
    fragile structured-parse of the summarizer reply (the entity extractor already
    shows ~85% schema-violation on some backends). Classification is an INGEST
    evaluation, computed once and stored — like tags.
  - >-
    Folding classification into the TaggerWorker's existing LLM call. Rejected:
    the tagger's three-surface fallback (schema-violating→different prompt,
    zero-valid→retry, unreachable→backoff) and prompt-injection delimiter handling
    are security-sensitive and tuned for the controlled-vocabulary tag concern;
    entangling a second independently-failing field muddies that state machine.
    A separate worker mirroring EntityExtractorWorker is cleaner and keeps the
    tagger untouched. Do NOT modify TaggerWorker.java.
  - >-
    A growable `classification` vocabulary table or controlled-vocab seeding.
    Classification is a FIXED closed enum of six values enforced by a DB CHECK
    constraint + a Java-side membership filter (mirroring post_entity.entity_type),
    NOT a Tier-1 `tag`-style table. No new vocabulary table, no bootstrap seeding.
acceptance:
  - >-
    Migration V57__post_classification.sql adds to the partitioned `post` table:
    (a) `classification TEXT[] NOT NULL DEFAULT ARRAY['unknown']::TEXT[]` with a
    CHECK that every element is in the closed set — `classification <@
    ARRAY['factual','opinion','technical','urgent','ongoing','unknown']::TEXT[]` —
    and a non-empty CHECK (`array_length(classification,1) >= 1`); and (b)
    `classifier_done BOOLEAN NOT NULL DEFAULT FALSE` as the per-stage durable
    cursor (Invariant 5). It backfills existing posts with `UPDATE post SET
    classifier_done = TRUE WHERE tagger_done = TRUE` (classification stays the
    {unknown} default — no LLM re-run), exactly as V28 did for entity_done. The
    migration is a single atomic transaction. No GRANT change (post is already
    collector-write / provider-read).
  - >-
    ModelTask gains `CLASSIFIER("classifier")` and LlmRouter routes it from
    `infochat.llm.classifier.*` config (base-url / api-key / model /
    max-concurrency / poll-interval) added to the collector application.properties,
    mirroring the existing `infochat.llm.entity.*` shape.
  - >-
    ClassifierWorker (new, modeled on EntityExtractorWorker) is a @Scheduled poller
    whose pickup is `status='RAW' AND tagger_done=TRUE AND classifier_done=FALSE
    AND fetched_at >= scanWindowFloor(clock.instant())` (the fetched_at floor read
    from an injected java.time.Clock per engineering-rules §9, mirroring the
    tagger). Per post it makes ONE LLM call via prompts/classifier.md, parses the
    returned label set, normalizes + filters to the closed enum (out-of-enum
    labels dropped), caps the accepted substantive set at 3 (the 1-3 cardinality
    carried over from the retired §5.4.4 prompt), and writes the atomic cursor
    `UPDATE post SET classification=?, classifier_done=TRUE WHERE id=? AND
    fetched_at=?`. `unknown` is a first-class label the model may return when no
    substantive label fits and is never combined with a substantive label (a
    non-empty filtered substantive set yields 1-3 of those labels; an empty one
    yields exactly `{unknown}`); and on schema-violation / empty-after-filter / LLM
    unreachable the worker writes `classification={unknown}, classifier_done=TRUE`
    (graceful — mirrors the entity extractor's "entity_done=TRUE with no rows").
    Untrusted post content is wrapped with the per-call rotating {{id}} delimiter,
    same as the tagger/entity prompts (docs/design/04-security.md §4.3).
  - >-
    ReadyPromoter's readiness gate gains `AND classifier_done = TRUE` (both the
    javadoc-documented predicate and the enumeratePending SQL). This is
    load-bearing: every eval worker's pickup filters on `status='RAW'`, so if a
    post promoted RAW→READY before classification, the classifier's RAW filter
    would exclude it permanently and it would stay {unknown} forever. After the
    change a post promotes only once stage1/(stage2)/tagger/entity/embedding AND
    classifier are all done.
  - >-
    NAMED TESTS. ClassifierWorkerTest (unit, no DB): parse of the classifier reply,
    closed-enum membership filter (out-of-enum labels dropped), the cap (a reply
    with >3 substantive labels is truncated to 3; `unknown` returned alongside
    substantive labels is dropped so the two never co-occur), and the three
    fallback-to-{unknown} surfaces (schema-violating, empty-after-filter,
    unreachable). ClassifierWorkerIT (Testcontainers, mirrors EntityExtractorWorkerIT):
    a RAW tagger-done post is classified and its classification + classifier_done
    are written; a schema-violating reply yields classification={unknown} and
    classifier_done=TRUE. ReadyPromoterIT is extended to prove a post with
    classifier_done=FALSE is NOT promoted to READY and one with it TRUE (others
    done) IS. Red-before / green-after.
  - >-
    Design/spec updated to match the code: docs/design/02-schema.md §2.3.1 `post`
    gains the classification + classifier_done columns and the §2.12 Invariant-5
    coverage row lists classifier_done; docs/design/05-llm-and-embeddings.md gains
    a Classifier prompt-template subsection modeled on §5.4.2 Tagger, the closed
    enum is documented WITH `unknown`, and §5.4.4 Summarizer is reframed so
    classification is described as an ingest-time per-post evaluation (NOT a
    summarizer output); docs/spec/architecture.md §Pipelines adds `classifier` to
    the parallel-after-tagger stage set ({classifier, entity extraction, embedding});
    and docs/spec/llm.md §SPI shape adds CLASSIFIER("classifier") to the closed
    ModelTask enum listing (line ~43) — its design mirror docs/design/05
    §5.1 SPI overview does the same — and the §Per-task routing rules prose that
    enumerates the Collector ingest-pipeline tasks (security judge, tagging,
    entity extraction, embedding) adds classification.
  - mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      infochat-collector/.../eval/classifier/ClassifierWorkerTest.java — parse /
      closed-enum filter / cap / three fallback-to-unknown surfaces.
    - >-
      infochat-collector/.../eval/classifier/ClassifierWorkerIT.java — end-to-end
      RAW→classified + schema-violation→{unknown} (Testcontainers).
  modifies:
    - >-
      infochat-collector/.../eval/ready/ReadyPromoterIT.java — classifier_done is
      now part of the RAW→READY gate.
  preserves:
    - all tests currently green on main
    - >-
      TaggerWorker / EntityExtractorWorker behavior — untouched; their tests stay
      green (the new stage is additive and parallel).
spec_refs:
  - docs/spec/architecture.md §Pipelines
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/llm.md §Bounded concurrency and observability
  - docs/design/02-schema.md §2.3.1 `post` — partitioned by `fetched_at`
  - docs/design/05-llm-and-embeddings.md §5.4.2 Tagger
  - docs/design/05-llm-and-embeddings.md §5.4.3 Entity extractor
  - docs/design/05-llm-and-embeddings.md §5.4.4 Summarizer (cluster mode)
decision_refs:
  - D19
  - D36
redteam_findings: []
redteam_audits: []
reviews: []
escalations:
  - date: 2026-07-08
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL (2 blockers, 2 warnings). Blockers: (1) cap number
      for the classification label set is unspecified in acceptance items 3 & 5;
      (2) docs/spec/llm.md §SPI shape (closed ModelTask enum) is missing from
      files_scope and the item-6 doc checklist, though item 2 widens that enum.
overrides: []
revisions:
  - date: 2026-07-08
    reason: >-
      clarity-fail refine (user-directed via /m1-tick run escalation menu):
      acceptance items 3 & 5 left the classification cap number unstated, and
      files_scope / item-6 doc checklist omitted docs/spec/llm.md §SPI shape
      (the closed ModelTask enum item 2 widens).
    snapshot: |
      files_scope (pre-refine): 13 paths; docs/spec/llm.md absent (spec_refs only).
      acceptance item 3 (verbatim, pre-refine): "... normalizes + filters to the
        closed enum (out-of-enum labels dropped), caps the accepted set, and
        writes the atomic cursor ..."
      acceptance item 5 (verbatim, pre-refine): "... closed-enum membership filter
        (out-of-enum labels dropped), the cap, and the three fallback-to-{unknown}
        surfaces ..."
      acceptance item 6 (pre-refine): ended at docs/spec/architecture.md §Pipelines;
        docs/spec/llm.md §SPI shape not listed.
      clarity blockers (2026-07-08): (1) cap number unspecified; (2) docs/spec/llm.md
        §SPI shape closed enum missing from files_scope + item-6 checklist though
        item 2 adds a 7th ModelTask value.
      resolution: state cap = 3 substantive labels (1-3 carried from retired §5.4.4;
        unknown mutually exclusive); add docs/spec/llm.md to files_scope (14 paths,
        still <= files_budget 16); extend item 6 to require CLASSIFIER in the
        §SPI shape enum listing + design §5.1 mirror + §Per-task routing prose.
        files_budget / complexity / risk / round_cap unchanged.
aborted_attempts: []
reopens: []
---

# M1-597: real per-post classification ingest stage

## Context

`docs/design/03-commands.md` §`/summary` and `05-llm §5.4.4` describe a
per-post **classification** — an enum `{factual, opinion, technical, urgent,
ongoing}` (this ticket adds `unknown`) — as a concept DISTINCT from topic tags.
It was designed from day one but **never implemented with a data source**: there
is no `post.classification` column, and the cluster renderer only ever made
`summary:` LLM-authored, so the classification slot was stubbed as a copy of the
tag union (`joinedTags`). M1-591 then deleted the always-duplicate line; that
deletion was **reverted** (commits `77e96fee`, `57da696a`) because the intended
fix is to give classification a real data source, not to remove the feature.
M1-591 is abandoned/superseded by this ticket + M1-598.

## The fix (this ticket = the collector + schema half)

Compute classification at **ingest**, per post, in the collector's eval
pipeline — the same place and pattern as tagging and entity extraction — and
store it on the post. A separate `ClassifierWorker` (modeled on the proven
`EntityExtractorWorker`, NOT folded into the security-sensitive `TaggerWorker`)
runs one LLM call per post against a fixed closed enum and writes
`post.classification` + the `classifier_done` cursor. `unknown` is both a
first-class label the model returns when nothing substantive fits and the safe
default the worker writes on any failure — so the column is `NOT NULL` and never
empty. `ReadyPromoter` waits for `classifier_done` before promoting RAW→READY.

The provider render side (project `post.classification` into `/summary`, union
it, drop `unknown` unless it is the only label) is **M1-598**, which is
`blocked_by` this ticket.

## Why ingest-time per-post, not summarizer-authored (D19/D36)

Classification is an evaluation, not prose. Computing it at ingest and storing
it keeps `/summary` (and `/retry`) deterministic — the shown field is read from
the DB, so byte-identical replay holds. Making it a summarizer LLM output would
break that and require fragile structured-parse of the summarizer reply. See
frontmatter out-of-scope for the full rationale (and for why a separate worker
beats folding into the tagger).

## `unknown` semantics

`unknown` means "none of the five substantive labels fit" (e.g. a bare `wow` /
`just found this` post) OR "the classifier could not decide / errored." Both
resolve to `{unknown}`. The prompt instructs the model to prefer a substantive
label and fall to `unknown` only when nothing genuinely applies. At the DB it is
a permitted CHECK value; the column DEFAULT is `{unknown}` so existing rows and
any un-run post are non-null.

## Size note

This ticket spans three modules (core migration, llm-adapter, collector) plus a
migration and the ReadyPromoter gate — ~13 files. It is kept whole per the
approved collector+schema / provider split. If review finds it unwieldy, the
natural sub-split is [V57 migration + 02-schema] / [ClassifierWorker + router +
prompt + ReadyPromoter + 05-llm/architecture]; raise it via escalate→decompose
rather than silently splitting.

## Notes

- **Provenance.** Follow-up to the reverted M1-591 (live-test finding
  2026-07-08). The design intent was sound; only the implementation was stubbed.
- **Backfill / TTL.** Existing posts get `{unknown}` + `classifier_done=TRUE`
  (no re-run), and the 4-day `fetched_at` partition TTL ages the backlog out —
  same posture as V28's entity backfill.
- **Security.** The classifier processes untrusted post content through an LLM,
  the same prompt-injection surface as the tagger/entity; the rotating `{{id}}`
  delimiter wrapper is replicated. `security_relevant: true` → redteam gate.
