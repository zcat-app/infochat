# Sanitize() caller postcondition census (M1-792)

Header (re-derived at start, 2026-08-08):

- grep: `grep -rn '\.sanitize(\|LlmOutputSanitizerCore\.' --include='*.java' infochat-provider/src/main infochat-collector/src/main`
- hit count: 14 rows below (13 bean call sites + 1 collector composition).
  The roster was re-derived from the grep at `start`, not copied from the
  analysis: line numbers reflect main after M1-778/M1-789 landed.
- Grounded against `docs/spec/security.md` §LLM output sanitizer. Each row
  names the sanitize UNIT, the postcondition the caller assumes (line
  count / emptiness / leading bytes / token synthesis / shrinkage), whether
  the caller's checks run before or after the call, and the pinning test —
  or an explicit `follow-up: M<N>-XXX` row.
- Row count is enforced by
  `LlmOutputSanitizerPostconditionTest.everyBeanCallSitePostconditionIsPinned`
  (count + per-row resolution: a named pinning test resolved in-tree, or a
  filed follow-up ticket).

## Shared transform contract (the pins every caller row leans on)

- `LlmOutputSanitizerCore.applyClosedListStripWithMatches` (infochat-core
  .../core/llm/LlmOutputSanitizerCore.java:646-652) — on zero matches the
  caller's ORIGINAL bytes are returned (:646-647), on any match the
  CANONICAL form is returned (:652). The canonical-form return is the
  pre-existing synthesis channel (zero-width-prefixed token → canonical
  form carries the token at index 0) that M1-791's detector move and the
  follow-ups below exist because of.
  - pin: `LlmOutputSanitizerPostconditionTest#sanitizeReturnsOriginalBytesOnNoClosedListMatch`
  - pin: `LlmOutputSanitizerPostconditionTest#sanitizeMayReturnTheCanonicalFormOnMatch`
- Deletion shapes the shared transform introduces — marker-only line
  dropped (M1-789, live), thematic-break line and emphasis-joined token
  (M1-790, NOT yet landed — the pins document the current contract so
  M1-790's diff must update them deliberately), "" a possible return today
  (P8 — the empty-body follow-up must update the pin deliberately).
  - pin: `LlmOutputSanitizerPostconditionTest#deletionShapesMatchTheirDocumentedPostconditions`

## Call-site rows

- ChatAgent.java:570 — UNIT: whole assistant reply (post stripToolCalls,
  pre-persist); ASSUMES: leading bytes (the prefix-only `[REFUSAL:`
  intercept at :563 ran on RAW text), token non-synthesis (stripToolCalls
  at :542 ran on raw text); CHECK: before — 
  pin: `ChatAgentRefusalInterceptTest#refusalMarkerReplacedWithBundleStringAndNothingPersisted`,
  follow-up: `M1-791` (detectors move post-sanitize).
- CategoryRollupGenerator.java:221 — UNIT: roll-up text; ASSUMES: leading
  bytes (two-sided `[REFUSAL:` check at :216-220 on raw text), token
  non-synthesis; CHECK: before —
  pin: `CategoryRollupGeneratorTest#refusalMarkerYieldsCategoryWithoutPrefix`,
  follow-up: `M1-791`.
- ClusterBlockRenderer.java:183 — UNIT: one post's chosen prose field
  (render site); ASSUMES: token non-synthesis (renderers re-sanitize and
  never trust record bytes — redteam 2026-07-25 control), shrinkage to
  input; CHECK: after (render-side re-sanitize) —
  pin: `ClusterBlockRendererTest#headlineShapedLikeCommandIsRedacted`.
- DigestRenderer.java:610 — UNIT: one post's prose (render site); ASSUMES:
  token non-synthesis, shrinkage to input; CHECK: after —
  pin: `DigestRendererTest#renderSections_stripsAdminCommandTokens_beforePersistenceAndReplay`.
- DigestRenderer.java:881 — UNIT: one post's prose (render site); ASSUMES:
  token non-synthesis, shrinkage to input; CHECK: after —
  pin: `DegradedDigestRendererTest#render_redactsAClosedListEntrySpanningTheAnchorAndTheOriginal`.
- DisplayHeadline.java:144 — UNIT: one field (flattened); ASSUMES: line
  count 1 (flattened first); CHECK: after —
  pin: `DisplayHeadlineTest#newlinesAreFlattenedToOneLine`.
- DisplayHeadline.java:309 — UNIT: the field PAIR (derive — one sanitize
  call over anchor+original, M1-697 control); ASSUMES: line count >= 2
  (the collapse branch at :314-322 reads `lines.length < 2` as exactly one
  cause), leading bytes / emptiness (`AnchoredHeadline.isEmpty()` keys off
  `readerLine`), canonical-form synthesis (zero-width anchor erased —
  :333-339); CHECK: after —
  pin: `DisplayHeadlineTest#aSpanThatSwallowsTheSeparatorCollapsesToOneUnanchoredLine`,
  pin: `DisplayHeadlineTest#aNonRedactionCollapseOmitsTheHeadline`,
  pin: `DisplayHeadlineTest#aZeroWidthOnlyAnchorDegradesToTheOriginalRatherThanSuppressingTheHeadline`,
  pin: `LlmOutputSanitizerPostconditionTest#sanitizeMayReturnTheCanonicalFormOnMatch`.
- DisplayHeadline.java:348 — UNIT: one field (boundForScan); ASSUMES: line
  count 1 (flattened first); CHECK: after —
  pin: `DisplayHeadlineTest#newlinesAreFlattenedToOneLine`.
- DisplayHeadline.java:740 — UNIT: one translated field
  (prepareTranslatedHeadline, display-hit leg); ASSUMES: emptiness
  (`AnchoredHeadline.isEmpty()` keys off `readerLine`), token
  non-synthesis (the display-hit leg evaluates echo/script checks on the
  SANITIZED form — the one leg already shaped the safe way); CHECK: after —
  pin: `DisplayHitTranslationTest#translatorOutputIsFlattenedToOneLineBeforeSanitizer2`.
- TranslationPipeline.java:276 — UNIT: translator reply (prose-leg
  sanitizer-2); ASSUMES: shrinkage-to-empty (the never-empty promise at
  :517 — broken for a deletable-shape reply), leading bytes (conditions
  (b)/(c) at :197-226 evaluate the RAW translator reply BEFORE sanitize);
  CHECK: before (conditions) + after (sanitizer-2) —
  pin: `TranslationPipelineTest#runWithCsScopeOnEmptyTranslationReturnsEnglishTextPlusNote`,
  follow-up: `M1-793` (conditions (b)/(c) raw-text checks),
  follow-up: `M1-794` (empty-body delivery guard, P8).
- SavedCommandHandler.java:387 — UNIT: one row's tag (filter echo);
  ASSUMES: token non-synthesis (echo renders only after byte-matching a
  stored personal tag; sanitize is display-only, raw args.tag still drives
  the match); CHECK: after —
  pin: `SavedCommandHandlerTest#savedRedactsCommandShapedTitleAndTagInGroupBroadcast`.
- SavedCommandHandler.java:516 — UNIT: one row's title (echo); ASSUMES:
  token non-synthesis; CHECK: after —
  pin: `SavedCommandHandlerTest#savedRedactsCommandShapedTitleAndTagInGroupBroadcast`.
- SavedCommandHandler.java:521 — UNIT: one row's tags (echo); ASSUMES:
  token non-synthesis; CHECK: after —
  pin: `SavedCommandHandlerTest#savedRedactsCommandShapedTitleAndTagInGroupBroadcast`.
- IngestTranslationWorker.java:775-777 — UNIT: translator reply (collector
  composition site — explicit `applyMarkdownLinkStrip` +
  `applyClosedListStripWithMatches` + own audit; does NOT call
  `sanitize()`); ASSUMES: token non-synthesis and shrinkage-to-input on
  the STORED corpus (P13 — deliberately not rewired); CHECK: n/a
  (composition site) —
  pin: `IngestTranslationWorkerIT#czechPost_translatedAndFoundByEnglishLexicalQuery`
  (controls (a)/(b): stored `body_en` normalized, flattened, redacted).

14 rows. Every row resolves to a named pinning test (resolved in-tree) or
a filed follow-up ticket; `everyBeanCallSitePostconditionIsPinned` fails
on any row that has neither.
