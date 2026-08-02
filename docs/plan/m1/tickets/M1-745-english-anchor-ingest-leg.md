---
id: M1-745
title: "English anchor: translate non-English posts at ingest and retrieve against the English field"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by: []
files_budget: 9
files_scope:
  - infochat-core/src/main/resources/db/migration/V74__post_english_anchor.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapSourceLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerIT.java
  - docs/design/02-schema.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The QUERY leg (user's search string -> English). That is D58's bounded
    exception and belongs to M1-746. This ticket makes the CORPUS English;
    it does not touch what a user types.
  - >-
    Display-time translation of a retrieved title or snippet (M1-747). D29
    permits it; nothing here renders anything to a user.
  - >-
    LANGUAGE DETECTION. The post's language comes from a declared
    `source.language`, never from inference over the body. Inference would
    put a non-deterministic classifier in the ingest path and make
    `body_en` a function of model output on text the operator never
    reviewed. A diff that adds a detector has left scope.
  - >-
    Re-translating the existing corpus. Every current row is English
    (`source.language` backfills to `en`), so there is no backfill to run.
    A migration that calls an LLM is out of scope in any case.
  - >-
    Per-language FTS regconfig. The whole point of the anchor is ONE
    `'english'` configuration; adding per-language variants is the
    alternative this decision rejected.
  - >-
    Retiring `TranslationProvider`'s "source bodies are NEVER translated"
    javadoc. It is wrong after this ticket, but the SPI belongs to the
    messaging adapter and its contract governs the PRESENTATION path,
    which is unchanged. Filed separately so the fix lands with the
    consumer it describes.
acceptance:
  - >-
    V74 adds `post.body_en TEXT` and `post.title_en TEXT` (both nullable)
    and `source.language TEXT NOT NULL DEFAULT 'en'`. Nullable is
    deliberate: a row is un-translated until the worker runs, and the
    outbox pattern requires that state be representable.
  - >-
    V74 REPLACES the `search_tsv` generated column so it reads
    `coalesce(title_en, title)` and `coalesce(body_en, body)` rather than
    `title`/`body`. The regconfig stays hard-pinned to `'english'` on both
    sides. The `coalesce` fallback is what keeps an English corpus (every
    row today) byte-identical in behaviour, and what keeps a post
    searchable in the window between persist and translate.
  - >-
    `idx_post_search_tsv` (GIN) survives the column replacement. Dropping
    and not recreating it turns every lexical query into a sequential scan
    over a partitioned table with no error anywhere.
  - >-
    `EmbeddingWorker` embeds `coalesce(title_en, title)` and the
    `coalesce(body_en, body)`-derived text, preserving its existing
    `body_summary`-else-first-800-chars composition rule. The vector space
    is unchanged (D54 local nomic-768) — only the input text moves.
  - >-
    A new `IngestTranslationWorker` translates title and body to English
    via `ModelTask.TRANSLATOR` for posts whose `source.language <> 'en'`,
    writing `title_en`/`body_en`. It follows the existing eval-worker
    shape: poll-interval, `max-concurrency`, status gate, and the outbox
    rehydrate path, so an interrupted run resumes rather than losing work.
  - >-
    A post from an `en` source is NEVER sent to the translator — asserted,
    not assumed. Today that is the whole corpus, so a regression here would
    silently put every ingested post through an LLM call.
  - >-
    CONTROLS CARRIED ACROSS FROM THE PATH THIS REROUTES (engineering rules
    §10). Retrieval moves from `body` to `body_en`, and `body_en` is
    LLM-authored text derived from upstream-untrusted input, so it inherits
    every control the raw body had, enumerated rather than assumed - (a)
    `IngestTextNormalizer` runs on the translator's OUTPUT before it is
    stored, unconditionally, with no fenced-code carve-out (`security.md`
    §141-143 and step 1.7 — the carve-out is chat-side only); (b) the
    translator's output passes `LlmOutputSanitizer` before storage, because
    it is model output re-entering the corpus; (c) `SemanticSearchTool`'s
    two arms keep their inline `status='READY'` + D59 world predicate
    BEFORE their LIMIT — no over-fetch-then-filter path is introduced; (d)
    retrieved rows still fold into the D21 `UNTRUSTED_CONTENT` wrapper.
    Each is asserted by a test naming the control.
  - >-
    Stage 1's regex scan continues to operate on the raw normalized body.
    Translation happens AFTER security evaluation, never before — a
    translator that paraphrases an injection attempt out of regex range
    must not be able to launder it past Stage 1.
  - >-
    `bootstrap-sources.json` entries accept an optional `language` (default
    `en`), and `/add-source` accepts `--lang <code>`; both are validated
    against `LanguageRegistry` and reject an unknown code rather than
    silently storing it.
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
      — an `en` source is never dispatched to
      the translator; a non-English source is; the worker is idempotent
      over a re-delivered post; a translator failure leaves `body_en` NULL
      and the post still retrievable through the `coalesce` fallback
      rather than dropping out of the corpus.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerIT.java
      — end to end against the real schema - a
      Czech post is persisted, translated, and found by an ENGLISH lexical
      query (which is the behaviour that does not exist today), while
      `post.body` is unchanged; and the sanitize/normalize calls on the
      translator output are asserted by a spy, so the carried-across
      controls are pinned to the new path rather than the old one.
  preserves:
    - >-
      SemanticSearchToolHybridIT in full — both arms, RRF ordering with
      `RRF_K = 60`, the READY + D59 predicates, and the total order. This
      ticket changes what text the arms match, never how they fuse.
    - >-
      EmbeddingWorker's existing composition rule and its
      `summary_done OR length(body) <= threshold` pickup gate.
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

# M1-745: English anchor — translate non-English posts at ingest, retrieve against the English field

## Context

D29 was amended (`21ad3517`) to make English the corpus anchor: a non-English
source post is translated to English once at ingest into a derived field, the
original body is retained unmodified and stays what the user is shown, and
retrieval runs against the English field in both arms.

Nothing implements it. Today the corpus is 100% English by accident — no
non-English source has ever been added — and the moment one is, two things
break silently:

- **The lexical arm degrades to noise.** `search_tsv` is a STORED generated
  column built with `to_tsvector('english', title || ' ' || body)` (V58), and
  `SemanticSearchTool` queries it with `plainto_tsquery('english', ?)`. English
  stemming over Czech or Russian produces near-garbage tokens. The post stays
  findable by meaning and becomes unfindable by keyword, with no error anywhere.
- **The semantic arm degrades quietly too.** The incumbent embedder is weak
  cross-lingually (0.430 recall@8 on native non-English against 0.630 on
  English), so a non-English document sits further from an English query than it
  should.

Measurement settled the alternative: swapping to a multilingual embedder buys
+0.12 on non-English and costs 0.02–0.07 on English, on a corpus that is
entirely English — while the pivot fixes both arms with one field, needs no
768→1024 migration and no re-embed, and keeps one FTS configuration.

## Approach

**Language is declared, never detected.** `source.language` defaults to `'en'`;
`bootstrap-sources.json` and `/add-source --lang` set it. Inference over the
body would put a non-deterministic classifier in the ingest path and make the
retrievable text a function of model output on text nobody reviewed. The
operator adding a Czech feed knows it is Czech.

**`coalesce(x_en, x)` is what makes this safe to land.** Every existing row has
`language = 'en'` and therefore a NULL `body_en`, so the generated column and
the embedder read exactly the text they read today — the change is a no-op for
the current corpus, which is the property that lets a schema change this
central ship without a backfill.

**Translation runs after Stage 1, not before.** Security evaluation reads the
raw normalized body. A translator paraphrasing an injection attempt must not be
able to move it out of regex range on the way in.

## Out-of-scope

The query leg (M1-746) and display-time translation (M1-747). No language
detection. No corpus backfill — there is nothing non-English to backfill. No
per-language regconfig; one `'english'` configuration is the point. The
`TranslationProvider` javadoc fix rides with its own consumer.

## Notes

- **The `search_tsv` replacement is the risky edit.** It is a STORED generated
  column on a RANGE-partitioned table, so the migration rewrites every
  partition; `idx_post_search_tsv` must be recreated explicitly or every lexical
  query silently becomes a sequential scan. The acceptance item pins the index
  because losing it produces no error, only latency.
- **`body_en` is LLM output entering the corpus**, which is why
  `security_relevant: true` and why the controls list is enumerated rather than
  left to the implementer. The path being rerouted (raw body → retrieval) has
  incidental obligations — normalize, sanitize, the READY + D59 predicates
  inside each arm, the D21 wrapper — that its job description omits. Engineering
  rules §10 is exactly this case.
- **Nullable `body_en` is not laziness.** The outbox pattern needs "persisted
  but not yet translated" to be representable, and the `coalesce` fallback means
  that state is still searchable rather than invisible.
- Measurement behind the model choice lives in `docs/measurement/translator-slot.md`.
  It is evidence, not direction — no acceptance item cites it, and the model
  name stays out of the spec.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-745-english-anchor-ingest-leg.md`
  is clean.
