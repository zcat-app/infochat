---
id: M1-745
title: "English anchor: translate non-English posts at ingest and retrieve against the English field"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by: []
files_budget: 24
files_scope:
  - infochat-core/src/main/resources/db/migration/V74__post_english_anchor.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/llm/LlmOutputSanitizerCore.java
  - infochat-core/src/main/java/app/zcat/infochat/core/source/SourceLanguageRegistry.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesEntry.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParser.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceArgs.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/translation/IngestTranslationWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParserTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceArgsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerPickupFloorIT.java
  - docs/design/02-schema.md
  - docs/design/05-llm-and-embeddings.md
  - docs/design/07-deployment.md
  - docs/spec/commands.md
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
  - >-
    Moving or renaming the `LlmOutputSanitizer` BEAN or its package, or
    touching `LanguageRegistry`'s reviewed UI-language set. The bean stays
    in infochat-provider with its current API; only its pure text
    transform is extracted to infochat-core. `LanguageRegistry` remains a
    user-UI-language gate and is not the source-language validator.
acceptance:
  - >-
    V74 adds `post.body_en TEXT` and `post.title_en TEXT` (both nullable),
    `source.language TEXT NOT NULL DEFAULT 'en'`, and
    `post.translation_done BOOLEAN NOT NULL`. Nullable `body_en`/`title_en`
    is deliberate: a row is un-translated until the worker runs, and the
    outbox pattern requires that state be representable. Existing rows
    backfill `translation_done = TRUE` (every current row is English and
    already embedded); the new-row default is `FALSE` — the durable cursor
    the embedding gate below reads. Mechanics, pinned: `translation_done`
    is `ADD COLUMN ... NOT NULL DEFAULT TRUE` followed by `ALTER COLUMN
    ... SET DEFAULT FALSE` (PG fast default via attmissingval — existing
    rows read TRUE, new rows default FALSE, no table rewrite), and the
    `search_tsv` replacement is DROP COLUMN (the cascade takes
    `idx_post_search_tsv` on the parent and per-partition with it) +
    re-ADD (rewrites every partition) + explicit `CREATE INDEX` on the
    parent. `source.language` is INSERT-only at the upsert: the provider
    role's column-scoped UPDATE grant (V31) excludes it, so V74 mirrors
    the `source_origin` precedent — the ON CONFLICT DO UPDATE does not
    overwrite an existing row's language.
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
    `body_summary`-else-first-800-chars composition rule, and its pickup
    query gains `AND translation_done = TRUE`. The coalesce reads happen
    in `enumeratePending`'s SELECT projection so the `PostRow` record
    keeps its exact current 5-field shape — five existing tests construct
    `PostRow` directly (`IngestNotifySmokeIT`, `EmbeddingWorkerBackoffTest`,
    `EmbeddingWorkerNonFiniteTest`, `EmbeddingWorkerDimensionMismatchTest`,
    `EmbeddingWorkerPgvectorRejectionTest`) and a record-shape change
    breaks them all. The vector space is
    unchanged (D54 local nomic-768) — only the input text moves. The gate
    is what stops a Czech post being embedded from Czech text before the
    translator runs: `embedding_done` is the durable cursor and never
    re-fires, so an early embed would be permanent.
  - >-
    A new `IngestTranslationWorker` follows the existing eval-worker
    shape: poll-interval, `infochat.llm.translator.max-concurrency`,
    status gate, and the outbox rehydrate path, so an interrupted run
    resumes rather than losing work. It picks up posts with
    `translation_done = FALSE` joined to their source's declared language.
    A post whose `source.language = 'en'` is marked `translation_done =
    TRUE` with NO translator dispatch — the en-never-dispatched property
    is asserted at the dispatch boundary, not assumed. Today that is the
    whole corpus, so a regression here would silently put every ingested
    post through an LLM call. A non-English post is translated via
    `ModelTask.TRANSLATOR` (title and body), the output is normalized and
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
    `bootstrap-sources.json` entries accept an optional `language`
    (default `en`) parsed through `BootstrapSourcesEntry`/
    `BootstrapSourcesParser` and persisted by `BootstrapLoader`, and
    `/add-source` accepts `--lang <code>` parsed in `AddSourceArgs` and
    persisted through `AddSourceCommandHandler`/`SourceUpsertService`.
    BOTH are validated against a new `SourceLanguageRegistry` in
    infochat-core — a reviewed constant set of supported source languages,
    initially `{en, cs}`, deliberately separate from `LanguageRegistry`'s
    UI-language set — and reject an unknown code rather than silently
    storing it. The user-facing rejection reuses the EXISTING
    `error.lang.unsupported_code` bundle key (its cs twin already exists;
    it takes the valid-codes `{0}` interpolation) — no new bundle keys, so
    `BundleKeys.java`/`en.properties`/`cs.properties` stay untouched.
  - >-
    `post.body` and `post.title` are byte-identical before and after
    translation — asserted directly, since "source bodies are never
    rewritten" is the property D29 now turns on and
    `docs/spec/verification.md` states it as verifiable.
  - >-
    Doc drift disposed: `docs/spec/commands.md`'s `/add-source` grammar
    documents `--lang <code>`, and `docs/design/07-deployment.md` §7.6.1
    documents the bootstrap entry's optional `language` field (default
    `en`).
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
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceArgsTest.java
      — `--lang <code>` parses in both `--flag value` and `--flag=value`
      forms; an unknown code is rejected by `SourceLanguageRegistry`
      rather than stored.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParserTest.java
      — optional `language` defaults to `en`; an unknown code is rejected
      by `SourceLanguageRegistry`.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
      — the declared language round-trips into `source.language` through
      the upsert.
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
operator adding a Czech feed knows it is Czech. Validation lives in a new
`SourceLanguageRegistry` (infochat-core, reviewed constant set, initially
`{en, cs}`) so both the collector bootstrap path and the provider command path
enforce the same list; the UI-facing `LanguageRegistry` is untouched.

**`coalesce(x_en, x)` is what makes this safe to land.** Every existing row has
`language = 'en'` and therefore a NULL `body_en`, so the generated column and
the embedder read exactly the text they read today — the change is a no-op for
the current corpus, which is the property that lets a schema change this
central ship without a backfill.

**`translation_done` is the durable cursor that orders the pipeline.**
`EmbeddingWorker`'s pickup gains `AND translation_done = TRUE`, and the
translator is the only writer that flips it for non-English posts. Without the
gate, `EmbeddingWorker` (no translation condition today) would embed a Czech
post from Czech text before the translator runs — permanently, because
`embedding_done` never re-fires. Retry exhaustion flips the cursor with
`*_en` left NULL, so a permanently failed translation degrades to
embedding-from-original instead of wedging the post out of READY forever.
Existing rows backfill `TRUE` (all English, already embedded); new posts
default `FALSE`.

**The sanitizer is shared, not forked.** Control (b) requires the translator's
output to pass the same sanitization pipeline as `LlmOutputSanitizer`, but the
bean lives in infochat-provider and the collector cannot depend on it. The
pure text transform is extracted into a static `LlmOutputSanitizerCore` in
infochat-core; the provider bean delegates to it (API and behaviour unchanged,
so the spec-parity CI test keeps pinning the single implementation) and the
collector worker calls it directly. Copying the 700-line red-team-hardened
control collector-side is explicitly rejected.

**Translation runs after Stage 1, not before.** Security evaluation reads the
raw normalized body. A translator paraphrasing an injection attempt must not be
able to move it out of regex range on the way in.

## Out-of-scope

The query leg (M1-746) and display-time translation (M1-747). No language
detection. No corpus backfill — there is nothing non-English to backfill. No
per-language regconfig; one `'english'` configuration is the point. The
`TranslationProvider` javadoc fix rides with its own consumer. No move or
rename of the `LlmOutputSanitizer` bean/package, and no change to
`LanguageRegistry`'s UI-language set.

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
- **Scope grew 9 → 21 → 24 files across two outline-fail refines**
  (2026-08-02). The round-2 refine added `EmbeddingWorkerPickupFloorIT`
  (fixture seed above) and pinned: the `error.lang.unsupported_code` key
  reuse, the `PostRow`-shape-preserving SELECT projection, the
  INSERT-only `source.language` upsert, and the V74 DDL mechanics — all
  verified by the second Plan pass, which found every other dimension
  clean. The round-1 refine (9 → 21):
  the first Plan pass verified that the `/add-source --lang` + bootstrap item
  needs `AddSourceArgs`/`SourceUpsertService`/`BootstrapSourcesEntry`/
  `BootstrapSourcesParser`, that the sanitizer control is unreachable from the
  collector without the core extraction, and that the embedding gate needs the
  `translation_done` cursor. Full failure analyses are in the two refine
  commit messages (`M1-745: refine ticket spec (outline-fail rework)`).
- Worker config follows the sibling-worker convention:
  `infochat.llm.translator.max-concurrency` plus a poll-interval key, added to
  the collector `application.properties` (the existing
  `infochat.llm.translator.model` stays; its "translator live call sites run in
  the Provider service" comment is updated by this ticket).
- Measurement behind the model choice lives in `docs/measurement/translator-slot.md`.
  It is evidence, not direction — no acceptance item cites it, and the model
  name stays out of the spec.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-745-english-anchor-ingest-leg.md`
  is clean.

## OUTLINE FAILED (2026-08-02, plan-writer round 2 — post-refine)

REASON: The refined ticket is one file short of implementable, and the missing file is a pre-existing test the ticket never authorizes modifying — the same failure class as the first pass. Ground truth: the acceptance-pinned `AND translation_done = TRUE` pickup conjunct (EmbeddingWorker) plus the pinned `DEFAULT FALSE` for new rows breaks `EmbeddingWorkerPickupFloorIT` loudly — its fixture INSERT (`EmbeddingWorkerPickupFloorIT.java:84-93`) lists every `*_done` flag explicitly but cannot include the new column, so it defaults FALSE, `enumeratePending` returns empty, and `assertTrue(pickedUp.contains(inWindowId))` at lines 69-76 fails. That file is in neither `files_scope` (21/21 fully allocated → adding it is 22 > `files_budget`) nor `test_plan`/`§Notes` (test-modification authorization missing), yet `test_plan.preserves` demands all main-green tests stay green. Second, the `/add-source --lang` unknown-code rejection needs a user-facing friendly error, and `AddSourceArgs.Failure` carries a bundle key: a dedicated key per house style (`error.add_source.unknown_kind`/`unknown_category` precedent, `BundleKeys.java:498-501`) requires `BundleKeys.java` + `en.properties` + `cs.properties` (bilateral en/cs parity is CI-enforced per `BundleLoader` javadoc) — three more out-of-scope files, 25 > 21. The refine should either add those four files and raise `files_budget` to 25, or add only `EmbeddingWorkerPickupFloorIT` (budget 22) and PIN reuse of the existing language-generic `error.lang.unsupported_code` key (`en.properties:512`, cs twin already present, takes the valid-codes `{0}` interpolation). The refine should also pin three things this pass verified but the ticket leaves to guesswork: (a) the `coalesce(title_en, title)`/`coalesce(body_en, body)` reads belong in `EmbeddingWorker.enumeratePending`'s SELECT projection so `PostRow` keeps its 5-field shape — five out-of-scope tests construct `PostRow` directly (`IngestNotifySmokeIT:249`, `EmbeddingWorkerBackoffTest:191`, `EmbeddingWorkerNonFiniteTest:93`, `EmbeddingWorkerDimensionMismatchTest:113`, `EmbeddingWorkerPgvectorRejectionTest:181`) and any record-shape change breaks them too; (b) `source.language` is INSERT-only in `SourceUpsertService`'s `ON CONFLICT DO UPDATE` because the provider role's column-scoped UPDATE grant (V31: `status, consecutive_failures, deleted_at, deleted_by, bootstrap_tags`) excludes it — mirror the `source_origin` precedent or have V74 extend the grant; (c) V74 mechanics on the RANGE(fetched_at)-partitioned `post`: `search_tsv` replacement is DROP COLUMN (cascades, taking `idx_post_search_tsv` parent + per-partition indexes with it) + re-ADD (rewrites every partition) + explicit `CREATE INDEX` on the parent, and `translation_done` as `ADD COLUMN … NOT NULL DEFAULT TRUE` then `ALTER COLUMN SET DEFAULT FALSE` (PG fast-default attmissingval yields existing-rows TRUE / new-rows FALSE with no rewrite). Doc drift to dispose explicitly in the refine: `docs/spec/commands.md:654` grammar omits `--lang`, and `docs/design/07-deployment.md` §7.6.1 omits the entry `language` field — neither file is in `files_scope`. (Verified clean: all three spec_refs resolve; `ModelTask.TRANSLATOR`, `LlmRouter.forTask(ModelTask, String)`, the EntityExtractorWorker retry/release shape, `IngestTextNormalizer.stripBidiAndZeroWidth` + caller-composed NFKC matching `Stage1Pipeline.unicodeNormalize`'s no-fence-carve-out ingest rule, the 698-line `LlmOutputSanitizer` with extractable static transform + bean re-export of `CLOSED_LIST` keeping `matchSetEqualsSpecClosedList` compiling, `SemanticSearchTool`'s inline READY+D59+LIMIT arms reading `search_tsv` transparently, and `ScanWindowFixtureGuardTest` tolerating the new IT provided it pins `Clock.fixed`.)

SUGGESTED ESCALATION: refine

EVIDENCE: ticket acceptance items 1 and 4 (`translation_done` DEFAULT FALSE + `AND translation_done = TRUE` conjunct) vs `infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerPickupFloorIT.java:69-76,84-93` (absent from `files_scope`/`test_plan`); `files_budget: 21` fully allocated; `infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java:498-501` + `infochat-provider/src/main/resources/bundles/en.properties:159-172,512` (dedicated-key precedent vs reusable `error.lang.unsupported_code`) + `BundleLoader.java:50-58` (en/cs bilateral parity CI); `infochat-core/src/main/resources/db/migration/V31__service_role_login_and_audit_redaction.sql:44-45` (column-scoped UPDATE grant excludes `language`); `V58__post_search_tsv.sql:27-33` (generated column + GIN index V74 must replace and recreate).
