---
id: M1-749
title: "English anchor: ingest translation + embedding gate"
status: done
created: 2026-08-02
last_updated: 2026-08-03
outline_file: target/m1-tick-outline-M1-749.md
blocked_by: []
files_budget: 14
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
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvalVerdictNotifyIT.java
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
    is a fork the parity test would not cover and is rejected. The
    collector-side application ALSO emits the `LLM_OUTPUT_SANITIZED`
    `audit_log` rows the spec attaches to every sanitizer match —
    aggregated per distinct token per sanitize call, carrying the exact
    occurrence count (counted, never throttled), via `AuditLogWriter`
    (the collector role is INSERT-capable on `audit_log`; the
    `RE_EVAL_RELEASED` precedent is the same ingest-side posture). The
    pure transform itself stays audit-free; the emission lives in the
    worker, and a failed audit write fails the translation attempt
    (nothing is stored un-audited — the same durability posture as the
    provider bean, mirrored fail-closed: the post stays
    `translation_done=FALSE` and is retried next tick). A surface that
    takes the strip takes the audit — the red-team round-2 finding
    (M1-749-2026-08-02-r2) pinned that half-application is the worst of
    both readings; (c)
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
reviews:
  - round: 1
    date: 2026-08-03
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 20
      added: 2858
      removed: 554
  - round: 2
    date: 2026-08-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 19
      added: 2889
      removed: 556
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-02
    category: AUDIT-EVASION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer: "Every match is
      audit-logged; rows aggregate per distinct token per sanitize call
      and carry the exact occurrence count — counted, never throttled."
      The same section scopes the sanitizer to "the full set of
      LLM-authored output surfaces: chat-mode replies, on-the-fly
      /summary prose, periodic group digests, /retry re-rolls, and any
      future LLM-emitted text."
    gap: |
      The diff adds a new sanitize call site for LLM-emitted text —
      IngestTranslationWorker.sanitize — which runs the shared
      LlmOutputSanitizerCore closed-list strip over translator output
      before storage but emits only one WARN per distinct token; the
      class javadoc states explicitly that the collector-side
      application emits no audit_log rows, and LlmOutputSanitizerCore
      is declared pure, so no audit path exists anywhere for this
      surface. Not a capability constraint: the collector DB role is
      INSERT-only on audit_log, so the spec-committed
      LLM_OUTPUT_SANITIZED row could have been written from this call
      site.
    repro: |
      An adversary publishing to a subscribed non-English feed laces
      posts with privileged-command tokens (the translator is
      instructed to preserve them verbatim, so its output carries
      them). The worker's sanitize pass redacts them from body_en
      before storage — the preventive control fires — but the only
      record is a WARN in collector stdout; /audit shows nothing, so
      translation-laundering probes at feed scale leave zero
      operator-visible audit trail.
    suggested_fix_class: audit-log-coverage
  - date: 2026-08-02
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §Prompt-injection defenses: "Every prompt
      that includes user-derived text is wrapped in a delimiter block
      whose marker contains a per-call random value... The system
      prompt instructs the model to never follow instructions inside
      the wrapper... and to treat the content as data." The wrapper's
      integrity depends on untrusted bytes never being reinterpreted
      as template structure.
    gap: |
      IngestTranslationWorker.renderPrompt substitutes placeholders
      sequentially: {{id}}, {{SOURCE_LANGUAGE}}, {{title}}, {{body}} —
      in that order. Because {{body}} is replaced AFTER the
      upstream-controlled title is spliced in, a title containing the
      literal string "{{body}}" has the full post body substituted
      into the Title line as well, collapsing the prompt's title/body
      separation the JSON contract assumes.
    repro: |
      A feed publisher sets a post title containing the literal
      {{body}}. The rendered prompt shows the entire body twice and
      the model can legitimately return the whole body translation as
      the "title" value, which parses as VALID, is stored as
      post.title_en, and is indexed into search_tsv and embedded.
      Blast radius is bounded (retrieval pollution of the attacker's
      own feed content, output still normalized/sanitized, fetch body
      caps bound the size), hence low.
    suggested_fix_class: input-sanitization
  - date: 2026-08-02
    category: AUDIT-EVASION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer: "Every match is
      audit-logged; rows aggregate per distinct token per sanitize call
      and carry the exact occurrence count — counted, never throttled."
      The same section scopes the sanitizer to "the full set of
      LLM-authored output surfaces ... and any future LLM-emitted text."
      The spec's re-eval precedent (RE_EVAL_RELEASED) shows the same
      posture on the ingest side: automated security-relevant
      transitions carry a committed audit row precisely so attacker
      probing at feed scale is not invisible to operators.
    gap: |
      Re-audit round 2 — verified NOT closed (the round-2 change was
      the renderPrompt substitution-order hardening only).
      IngestTranslationWorker.sanitize runs the shared
      LlmOutputSanitizerCore closed-list strip over translator output
      before storage but emits only one WARN per distinct token; the
      class javadoc states explicitly that the collector-side
      application emits no audit_log rows, and LlmOutputSanitizerCore
      is declared pure, so no audit path exists anywhere for this
      surface. Not a capability constraint: the collector DB role is
      INSERT-only on audit_log. Scope re-examination: the "delivered
      to a user" anchor cuts toward exclusion, but the diff itself
      concedes the surface is inside the sanitizer's mandate by
      applying the redaction half ("the SAME sanitization pipeline"),
      and the spec attaches the audit row to every match of that
      sanitizer — a surface cannot be inside the mandate for the strip
      and outside it for the audit row. If the user judges the anchor
      excludes retrieval-only fields, the correct resolution is a spec
      amendment stating that boundary, not a silent half-application.
    repro: |
      An adversary publishing to a subscribed non-English feed laces
      posts with privileged-command tokens; the translator's output
      carries them, the sanitize pass redacts them from
      title_en/body_en before storage — the preventive control fires —
      but the only record is a WARN in collector stdout; /audit shows
      nothing, so translation-laundering probes at feed scale leave
      zero operator-visible audit trail, unlike every provider-side
      sanitizer hit and unlike the ingest-side RE_EVAL_RELEASED
      transitions.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-08-02
    verdict: FINDINGS
    base: a150a29f
    head: working tree (fork-point + uncommitted branch work)
    verdict_file: docs/plan/m1/redteam/M1-749-2026-08-02.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      First audit at the /m1-tick run redteam gate (ahead of review).
      One medium (collector-side sanitize emits no audit_log rows) and
      one low (renderPrompt's sequential {{body}}-after-{{title}}
      substitution). Developer falsification attempt (user directive):
      the low was confirmed as a correctness nit and fixed in-band
      (substitution order swapped + unit test); the medium was judged
      a spec-scope misread but re-examined independently in round 2.
      Out-of-model: semantic-pollution via the translation channel —
      retrieval-integrity commitments are not in the threat model.
  - date: 2026-08-02
    verdict: FINDINGS
    base: a150a29f
    head: working tree (fork-point + uncommitted branch work, incl. round-1 nit fix)
    verdict_file: docs/plan/m1/redteam/M1-749-2026-08-02-r2.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Re-audit after the renderPrompt fix. Round-1 INJECTION (low)
      VERIFIED CLOSED. Round-1 AUDIT-EVASION (medium) STANDS — the
      adversary's decisive argument: the diff applies the redaction
      half of the sanitizer to this surface, so it cannot sit outside
      the mandate for the audit row; resolution is user-owned (add
      collector-side audit rows OR spec-amend the commitment's scope).
      Escalated as redteam-finding per the user directive.
  - date: 2026-08-02
    verdict: CLEAN
    base: a150a29f
    head: working tree (fork-point + uncommitted branch work, incl. audit-emission remediation)
    verdict_file: docs/plan/m1/redteam/M1-749-2026-08-02-r3.md
    out_of_model_count: 1
    note: |
      Terminal audit for the gate. AUDIT-EVASION VERIFIED CLOSED by the
      collector-side LLM_OUTPUT_SANITIZED emission (aggregation, exact
      counts, fail-closed durability, no new surface); INJECTION
      RE-CONFIRMED CLOSED. Out-of-model (advisory, carried):
      semantic/retrieval pollution via the translation channel —
      retrieval-integrity commitments are not in the threat model.
clarity_check:
  date: 2026-08-02
  verdict: WARN
  warnings:
    - >-
      CENSUS-PRESENT-IF-CLASS-SCOPED (lint): advisory — the extraction
      touches exactly one class (LlmOutputSanitizer, delegated) and the
      spec-parity CI test pins the single implementation; the ticket
      already enumerates the five direct PostRow-constructing tests and
      the fixture INSERT sites.
    - >-
      Self-check spot-verified (pre-refine pass): LlmOutputSanitizer is
      698 lines; V58 holds search_tsv + idx_post_search_tsv; V73 is the
      latest migration; EmbeddingWorker.enumeratePending projects 5
      fields; ModelTask.TRANSLATOR, IngestTextNormalizer, and the
      TaggerWorkerTest hand-wired LlmRouter pattern all exist as cited;
      ReadyPromoter requires embedding_done; spec_ref anchors resolve.
      Refine delta verified: prompts/ directory exists (translator.md
      lives there) and is confirmed English -> target in direction.
  blockers: []
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

## Round 1 rework

1. SCOPE-DRIFT-CHECK FAIL — the diff carried a stray
   `.agents/memory/MEMORY.md` hunk (a "pre-registration free variable"
   memory-index bullet) that matches no `files_scope` entry, is not
   lifecycle-exempt, and traces to no acceptance item. Drop it from the
   branch's commit; land the memory-index update separately.

   Disposition (developer): the hunk was pre-existing working-tree state,
   not part of this ticket's work. Reverted out of the diff; the full
   pre-revert file is preserved at
   `/tmp/MEMORY.md.pre-registration-preserved` and its backing entry
   `.agents/memory/pre-registration-free-variable.md` remains untracked
   on disk — both can be re-applied as a standalone `process:` commit.
   No testable surface changed, so the round-1 green verify log stays
   valid (M1-272).
