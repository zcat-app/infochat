---
id: M1-749
title: "English anchor: ingest translation + embedding gate"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by: []
files_budget: 13
files_scope:
  - infochat-core/src/main/resources/db/migration/V74__post_english_anchor.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/llm/LlmOutputSanitizerCore.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-llm-adapter/src/main/resources/prompts/ingest-translator.md
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerPickupFloorIT.java
  - docs/design/02-schema.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
decomposed_from: M1-745
out_of_scope:
  - >-
    The source-language WRITE PATH: `/add-source --lang`,
    `bootstrap-sources.json` entry `language`, `SourceLanguageRegistry`,
    and the upsert plumbing. That is M1-750. V74 creates the
    `source.language` column (the worker's pickup joins it); nothing here
    writes it beyond the DEFAULT.
  - >-
    The QUERY leg (user's search string -> English). That is D58's bounded
    exception and belongs to M1-746. This ticket makes the CORPUS English;
    it does not touch what a user types.
  - >-
    Display-time translation of a retrieved title or snippet (M1-747).
    Nothing here renders anything to a user.
  - >-
    LANGUAGE DETECTION. The post's language comes from a declared
    `source.language`, never from inference over the body.
  - >-
    Re-translating the existing corpus. Every current row is English
    (`source.language` backfills to `en` via the column DEFAULT), so there
    is no backfill to run. A migration that calls an LLM is out of scope
    in any case.
  - >-
    Per-language FTS regconfig. The whole point of the anchor is ONE
    `'english'` configuration; adding per-language variants is the
    alternative this decision rejected.
  - >-
    Entity extraction reading the English field. `docs/spec/llm.md`
    §Translation flow mentions it, but `EntityExtractorWorker` (pickup
    `status='RAW' AND tagger_done=TRUE AND entity_done=FALSE`, reads
    `title`/`body`) stays as-is — a follow-up ticket owns moving it, and
    citing the spec section here does not pull it into scope.
  - >-
    Moving or renaming the `LlmOutputSanitizer` BEAN or its package. The
    bean stays in infochat-provider with its current API; only its pure
    text transform is extracted to infochat-core.
  - >-
    Retiring `TranslationProvider`'s "source bodies are NEVER translated"
    javadoc — the SPI's contract governs the PRESENTATION path, which is
    unchanged. Filed separately so the fix lands with its consumer.
acceptance:
  - >-
    V74 adds `post.body_en TEXT` and `post.title_en TEXT` (both nullable),
    `source.language TEXT NOT NULL DEFAULT 'en'`, and
    `post.translation_done BOOLEAN NOT NULL`. Nullable `body_en`/`title_en`
    is deliberate: a row is un-translated until the worker runs, and the
    outbox pattern requires that state be representable. Mechanics, pinned:
    `translation_done` is `ADD COLUMN ... NOT NULL DEFAULT TRUE` followed
    by `ALTER COLUMN ... SET DEFAULT FALSE` (PG fast default via
    attmissingval — existing rows read TRUE, new rows default FALSE, no
    table rewrite). No new GRANTs: the collector role holds table-level
    SELECT/INSERT/UPDATE on both `post` (V7) and `source` (V6).
  - >-
    V74 REPLACES the `search_tsv` generated column so it reads
    `coalesce(title_en, title)` and `coalesce(body_en, body)` rather than
    `title`/`body`, regconfig hard-pinned to `'english'` on both sides.
    Mechanics, pinned: DROP COLUMN (the cascade takes `idx_post_search_tsv`
    on the parent and per-partition with it) + re-ADD (rewrites every
    partition) + explicit `CREATE INDEX idx_post_search_tsv` on the parent.
    The `coalesce` fallback keeps an English corpus (every row today)
    byte-identical in behaviour and keeps a post searchable between persist
    and translate.
  - >-
    `idx_post_search_tsv` (GIN) survives the column replacement. Dropping
    and not recreating it turns every lexical query into a sequential scan
    over a partitioned table with no error anywhere.
  - >-
    `EmbeddingWorker` embeds `coalesce(title_en, title)` and the
    `coalesce(body_en, body)`-derived text, preserving its existing
    `body_summary`-else-first-800-chars composition rule, and its pickup
    query gains `AND translation_done = TRUE`. The coalesce reads happen in
    `enumeratePending`'s SELECT projection so the `PostRow` record keeps
    its exact current 5-field shape — five existing tests construct
    `PostRow` directly (`IngestNotifySmokeIT`, `EmbeddingWorkerBackoffTest`,
    `EmbeddingWorkerNonFiniteTest`, `EmbeddingWorkerDimensionMismatchTest`,
    `EmbeddingWorkerPgvectorRejectionTest`) and a record-shape change
    breaks them all. The vector space is unchanged (D54 local nomic-768) —
    only the input text moves.
  - >-
    A new `IngestTranslationWorker` follows the existing eval-worker shape:
    poll-interval, `infochat.llm.translator.max-concurrency`, status gate,
    and the outbox rehydrate path, so an interrupted run resumes rather
    than losing work. Its prompt is loaded from the new
    `infochat-llm-adapter/src/main/resources/prompts/ingest-translator.md`
    classpath resource (the universal collector-worker pattern — the
    existing `prompts/translator.md` is hard-wired to the presentation
    direction English -> target and cannot serve source-language ->
    English ingest). It picks up posts with `translation_done = FALSE`
    joined to their source's declared language. A post whose
    `source.language = 'en'` is marked `translation_done = TRUE` with NO
    translator dispatch — the en-never-dispatched property is asserted at
    the dispatch boundary, not assumed. A non-English post is translated
    via `ModelTask.TRANSLATOR` (title and body), the output normalized and
    sanitized per the controls item, written to `title_en`/`body_en`, and
    `translation_done` set TRUE. On retry exhaustion the worker sets
    `translation_done = TRUE` leaving `body_en`/`title_en` NULL — the post
    proceeds to embedding from its original text and stays retrievable
    through the `coalesce` fallback rather than wedging out of READY
    (ReadyPromoter requires `embedding_done`, which the gate would
    otherwise block forever).
  - >-
    CONTROLS CARRIED ACROSS FROM THE PATH THIS REROUTES (engineering rules
    §10). Retrieval moves from `body` to `body_en`, and `body_en` is
    LLM-authored text derived from upstream-untrusted input, so it inherits
    every control the raw body had, enumerated rather than assumed - (a)
    `IngestTextNormalizer` runs on the translator's OUTPUT before it is
    stored, unconditionally, with no fenced-code carve-out (`security.md`
    §141-143 and step 1.7 — the carve-out is chat-side only); (b) the
    translator's output passes the SAME sanitization pipeline as
    `LlmOutputSanitizer` before storage, because it is model output
    re-entering the corpus — implemented by extracting the bean's pure
    text transform into a static `LlmOutputSanitizerCore` in infochat-core
    that BOTH the provider bean (delegating, behaviour and API unchanged,
    so the spec-parity CI test still pins one implementation) and the
    collector worker call — a collector-side copy of the 700-line control
    is a fork the parity test would not cover and is rejected; (c)
    `SemanticSearchTool`'s two arms keep their inline `status='READY'` +
    D59 world predicate BEFORE their LIMIT — no over-fetch-then-filter
    path is introduced; (d) retrieved rows still fold into the D21
    `UNTRUSTED_CONTENT` wrapper. Each is asserted by a test naming the
    control.
  - >-
    Stage 1's regex scan continues to operate on the raw normalized body.
    Translation happens AFTER security evaluation, never before — a
    translator that paraphrases an injection attempt out of regex range
    must not be able to launder it past Stage 1.
  - >-
    `post.body` and `post.title` are byte-identical before and after
    translation — asserted directly, since "source bodies are never
    rewritten" is the property D29 now turns on and
    `docs/spec/verification.md` states it as verifiable.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
      — an `en` source is marked done with NO
      translator dispatch (spy on the LLM call); a non-English source is
      dispatched; the worker is idempotent over a re-delivered post; retry
      exhaustion sets `translation_done = TRUE` with `body_en` NULL so the
      post still reaches embedding and stays retrievable through the
      `coalesce` fallback rather than dropping out of the corpus.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerIT.java
      — end to end against the real schema - a
      Czech post is persisted, translated, and found by an ENGLISH lexical
      query (which is the behaviour that does not exist today), while
      `post.body` is unchanged; the normalize/sanitize calls on the
      translator output are asserted by a spy, so the carried-across
      controls are pinned to the new path rather than the old one; and the
      Czech post is NOT embedded before `translation_done` flips.
  preserves:
    - >-
      SemanticSearchToolHybridIT in full — both arms, RRF ordering with
      `RRF_K = 60`, the READY + D59 predicates, and the total order. This
      ticket changes what text the arms match, never how they fuse.
    - >-
      EmbeddingWorker's existing composition rule and the
      `summary_done OR length(body) <= threshold` pickup condition (the
      `translation_done` conjunct is additive).
    - >-
      EmbeddingWorkerPickupFloorIT's pickup-window assertions — its
      fixture INSERT lists every `*_done` flag explicitly, so it gains an
      explicit `translation_done = TRUE` seed (without it the new DEFAULT
      FALSE makes `enumeratePending` return empty and the test fails
      loudly).
    - >-
      EmbeddingWorkerIT's `enumeratePending` assertions — its fixture
      INSERTs (lines 419-438, 468-488) list every `*_done` flag
      explicitly, so they gain the same explicit `translation_done = TRUE`
      seed.
    - >-
      LlmOutputSanitizer's existing provider-side tests — the delegation
      to `LlmOutputSanitizerCore` is behaviour-identical.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/verification.md §Spec-level invariants the tests must enforce
  - docs/design/05-llm-and-embeddings.md §5.4.6
decision_refs:
  - D29
  - D19
  - D21
  - D54
  - D59
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-749: English anchor — ingest translation + embedding gate

## Context

Decomposed from M1-745 after three outline-fail passes (the parent's body
carries all three failure analyses; this slice is the part every pass
verified clean). D29 was amended (`21ad3517`) to make English the corpus
anchor: a non-English source post is translated to English once at ingest
into a derived field, the original body is retained unmodified and stays
what the user is shown, and retrieval runs against the English field in
both arms. Today the corpus is 100% English by accident; the moment a
non-English source is added, the lexical arm degrades to noise (English
stemming over Czech tokens) and the semantic arm sits further from English
queries than it should (0.430 vs 0.630 recall@8).

## Approach

**`coalesce(x_en, x)` is what makes this safe to land.** Every existing row
has `language = 'en'` and therefore a NULL `body_en`, so the generated
column and the embedder read exactly the text they read today — the change
is a no-op for the current corpus.

**`translation_done` is the durable cursor that orders the pipeline.**
`EmbeddingWorker`'s pickup gains `AND translation_done = TRUE`, and the
translator is the only writer that flips it. Without the gate,
`EmbeddingWorker` would embed a Czech post from Czech text before the
translator runs — permanently, because `embedding_done` never re-fires.
Retry exhaustion flips the cursor with `*_en` left NULL, so a permanently
failed translation degrades to embedding-from-original instead of wedging
the post out of READY forever.

**The sanitizer is shared, not forked.** Control (b) requires the
translator's output to pass the same sanitization pipeline as
`LlmOutputSanitizer`, but the bean lives in infochat-provider and the
collector cannot depend on it. The pure text transform is extracted into a
static `LlmOutputSanitizerCore` in infochat-core; the provider bean
delegates (API and behaviour unchanged) and the collector worker calls it
directly.

**Translation runs after Stage 1, not before.** Security evaluation reads
the raw normalized body; a translator paraphrasing an injection attempt
must not move it out of regex range on the way in.

## Out-of-scope

The write path for `source.language` (`/add-source --lang`, bootstrap
entry field, validation registry) is M1-750 — V74 only creates the column
with its `'en'` DEFAULT. Query leg (M1-746), display-time translation
(M1-747), language detection, corpus backfill, per-language regconfig,
entity extraction on the English field (follow-up), the
`TranslationProvider` javadoc fix, and any move/rename of the
`LlmOutputSanitizer` bean or package.

## Notes

- **The `search_tsv` replacement is the risky edit.** It is a STORED
  generated column on a RANGE-partitioned table, so the migration rewrites
  every partition; `idx_post_search_tsv` must be recreated explicitly or
  every lexical query silently becomes a sequential scan.
- **Nullable `body_en` is not laziness.** The outbox pattern needs
  "persisted but not yet translated" to be representable, and the
  `coalesce` fallback means that state is still searchable.
- Worker config follows the sibling-worker convention:
  `infochat.llm.translator.max-concurrency` plus a poll-interval key, added
  to the collector `application.properties` (the existing
  `infochat.llm.translator.model` stays; its "translator live call sites
  run in the Provider service" comment is updated by this ticket).
- Test infrastructure is verified to exist: `StubLlmProvider` + hand-wired
  `LlmRouter` (TaggerWorkerTest:445-448 pattern) supports the new worker's
  tests with no new test-support files; `ScanWindowFixtureGuardTest`
  tolerates the new IT provided it pins `Clock.fixed`.
- V73 is the latest migration on main, so V74 is next in sequence.
- Measurement behind the model choice lives in
  `docs/measurement/translator-slot.md` — evidence, not direction; the
  model name stays out of the spec.
