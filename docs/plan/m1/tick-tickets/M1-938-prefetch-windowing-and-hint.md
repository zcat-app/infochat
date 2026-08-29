---
id: M1-938
title: "Window the temporal pre-fetch; deterministic window hint"
status: done
created: 2026-08-26
last_updated: 2026-08-29
flow: tick
reproduction: >-
  Two tests, both RED against unmodified main (bfae640b) on 2026-08-29
  (child of a 2+ decomposition — the recorded live instance is M1-927's:
  "What happened in tech news in the last 2 hours?" served a week-old post
  as a recent highlight from the unwindowed pre-fetch):
  SemanticSearchToolIT#windowedFusedSearchBoundsBothArmsToReadyAtCutoff —
  seeds two READY subscribed posts near the query vector, one fresh
  (ready_at inside) one old (ready_at outside a 2h window), executes the
  tool with {"query": …, "_window": "PT2H"}: RED observed = the extra arg
  is IGNORED (verified: SemanticSearchTool.execute reads exactly
  "query" and "limit", SemanticSearchTool.java:108-113, and the fused SQL
  carries no ready_at predicate — grep -n 'ready_at' over the file returns
  hits only in SELECT lists, the COALESCE, and the emission), so BOTH
  posts return ("a post whose ready_at sits OUTSIDE the dispatched window
  must never surface ... got: [windowed-fresh, windowed-old]"); the
  companion arm WITHOUT _window must keep returning both; AND
  ChatAgentTest#temporalTurnWindowsThePreFetchAndHandsTheModelAWindowHint —
  feeds "what happened in tech news in the last 2 hours?" with the counting
  stub capturing dispatch args: RED observed = the single semanticSearch
  pre-fetch dispatch carries exactly {query} (ChatAgent.java:880-881;
  "expected: <[_window, query]> but was: <[query]>"), the folded header is
  the not-time-filtered one (:896-899), and no window hint exists anywhere
  in the first-call user prompt.
analysis_ref: docs/plan/m1/tick-analysis/temporal-parse-windowing.md
blocked_by: [M1-937]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentAuditActorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentPromptExceededTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyLanguageTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyModeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolDispatcherTest.java
  - docs/spec/security.md
  - docs/spec/commands.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The M1-916 catalog description strings, the byte-pinned instruction
    table, and the wire schema — UNTOUCHED: the window rides an INTERNAL
    semanticSearch arg no prompt surface names (analysis P4; M1-932's P9
    routing line holds). Probe: git diff names no ChatToolCatalog.java or
    ChatToolRegistry.java hunk; ChatAgentTest.renderedInstructionTableIsByteIdentical
    and ChatToolCatalogTest's guidance pins pass UNMODIFIED.
  - >-
    searchPosts in ANY form — its window clamp, ready_at binding, ordering,
    emission, and the M1-932/M1-935 pending param work are untouched
    (beyond M1-937's landed visibility widen); this ticket only consumes
    the SAME clamp constants.
  - >-
    Residue composition into M1-932's text param — deferred with trigger
    (analysis option G): the residue-vs-embedding trade is eval-gated; a
    follow-up ticket owns it if the M1-930 delta shows the model fails to
    narrow by itself. The pre-fetch query text stays the FULL anchored
    message.
  - >-
    Any recency component or time ordering in the fused SQL — the M1-917
    owner-reject stands; the windowed set remains similarity-fused, never
    time-ordered (the spec amendment states this).
  - >-
    Grammar extension — vague recency, "this year", absolute dates,
    number words stay non-matches (M1-937's recorded boundary, analysis
    P8); no per-language date-phrase handling (the parse is English-only on
    the anchored string, D58 (d)).
  - >-
    The eval lane — golden-set fixtures (M1-928's freeze), the harness
    (M1-929 — its windowed/temporal arm is its own extension), and the
    baseline record (M1-930) are untouched; the temporal-class flip is the
    owner-run delta reference, never edited here (analysis P16).
  - >-
    §Rate limiting / §Secrets handling edits — NONE: the dispatch-layer
    translate() replaces the tool-internal call as the cache-filling first
    call of the SAME per-turn leg (identical truncated query, declared
    language, scope coords → the tool's call is a cache hit), so the
    "one per turn for the D28 pre-fetch" enumeration stays true (analysis
    P9; the M1-932 P5 new-leg rule distinguished).
  - >-
    Timezone surface changes — no new command, no users.timezone, no
    groups.timezone mutation; the zone is READ (group scope) or taken from
    infochat.groups.default-timezone (DM), degraded to UTC on failure
    (analysis P5).
acceptance:
  - "REPRODUCTION closed (tool half): SemanticSearchToolIT.windowedFusedSearchBoundsBothArmsToReadyAtCutoff passes — the fresh/old fixture above, pinned Clock, {"query", "_window": "PT2H"} returns EXACTLY the fresh uid; the companion arm on the same fixture WITHOUT _window returns both (the compose discriminator: a mutation dropping the predicate fails the first arm, a mutation filtering unconditionally fails the second). The predicate sits INSIDE both arms before their LIMITs (analysis P6; the security.md semanticSearch row's in-arm promise)."
  - "REPRODUCTION closed (agent half): ChatAgentTest.temporalTurnWindowsThePreFetchAndHandsTheModelAWindowHint passes — the counting stub records the single pre-fetch dispatch carrying _window=\"PT2H\" alongside the truncated query; llmProvider.lastUserPrompt (BOUNDARY SITING: the last surface our code controls before the model) contains the windowed fold header naming the parsed phrase (\"within the last 2 hours\") AND the deterministic hint naming window=PT2H for searchPosts with the ready_at meaning and the do-not-silently-widen instruction quoted in the Approach; RED today on all three (analysis P1/P12)."
  - "Parse-miss byte identity (analysis P2): ChatAgentTest.nonTemporalTurnIsByteIdenticalToThePreChangeShape passes — a non-temporal message dispatches args with EXACTLY the {query} key set (the stub asserts the key set), folds the M1-927 not-time-filtered header VERBATIM, and the prompt carries no hint bytes; SemanticSearchToolDiversityIT/HybridIT.fusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb and ChatAgentTest.identicalModelSemanticCallServedFromSharedPerTurnCache pass UNMODIFIED (their fixtures are non-temporal); reviewer diff check: the fused SQL's no-window branch assembles the byte-identical statement string (the ready_at predicates are conditionally appended, never nullable-parameter always-on)."
  - "FAILURE-MODE (isolation, the M1-589 leak class): SemanticSearchToolIT.windowedArmsNeverSurfaceOutOfWorldOrNonReadyPosts — an UNSUBSCRIBED source's fresh near-match post and a subscribed source's non-READY fresh post (both inside the window) never appear under {"query", "_window"}; RetrievalWorldPredicateIT passes UNCHANGED; reviewer diff check: no post-filter over the fused pool (the predicate composes AND inside the arms)."
  - "Empty-window honest degradation (analysis P10; the M1-927 doctrine): ChatAgentTest.emptyWindowedPreFetchDegradesToGeneralKnowledgeNeverUnwindowed — the stub returns \"[]\" for the windowed dispatch; asserts NO \"subscribed feed\" block in the prompt, EXACTLY ONE semanticSearch dispatch (no unwindowed retry), and the hint STILL present (the model can honestly report the empty window via searchPosts); never a silent widen."
  - "Anchoring-leg discipline (analysis P9): ChatAgentTest.enScopeTemporalParseIssuesNoTranslatorCall — en scope, call-counting translator stub fails the test on ANY invocation; the turn still windows (anchored == raw for en; the M1-746 strict no-op extends to the dispatch-layer call site). ChatAgentTest.nonEnglishScopeParsesTheAnchoredTranslation — declared cs scope, stubbed translator returning a fixed English string carrying \"in the last 2 hours\"; asserts the dispatch carried _window=\"PT2H\", and EVERY recorded translate() invocation received IDENTICAL (truncated raw, \"cs\", scopeKind, scopeId) arguments with exactly ONE underlying generation — the same cache key the tool's internal anchor reads (zero added translator cost; D58's four conditions via the REUSED QueryAnchorTranslator, never a second translator class)."
  - "Breaker gate (analysis P9; security.md:1730-1732 — an OPEN chat breaker means NO translation, no embed round-trip, no pgvector probe): ChatAgentTest.temporalParseSitsInsideTheBreakerGate — chatBreakerOpen=true with a temporal message: ZERO translator invocations, ZERO dispatches; the parse/translate sit inside the existing wouldShortCircuit gate (ChatAgent.java:548-553)."
  - "Timezone wiring (analysis P5): ChatAgentTest/SemanticSearchToolIT groupScopeTodayUsesGroupsTimezone — a group scope whose groups.timezone=Europe/Prague, fixed Clock 09:00Z, message anchored \"today\": the dispatched _window equals PT11H (local midnight 22:00Z prior day) — a UTC-hardcode fails; the DM-scope arm resolves infochat.groups.default-timezone (UTC default); the FAILURE arm (zone lookup throws) degrades to UTC and SafeLogs scope ids only (D37 — no user prose)."
  - "D19 windowed determinism (analysis P3; llm.md §Determinism boundary): SemanticSearchToolIT.windowedFusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb — Clock pinned via QuarkusMock.installMockForType(Clock.fixed(…)); two identical {"query", "_window"} executions return byte-identical JSON; the existing unwindowed two-call identity arms pass unchanged; the hint/header text is a pure function of the parse (no timestamp interpolation beyond the parsed phrase and ISO duration)."
  - "Malformed-window degradation: a {_window: \"not-a-duration\"} dispatch returns the dispatcher's typed, self-correctable ValidationError (the existing DateTimeParseException arm, ChatToolDispatcher.java:196-201) — pinned by a ChatToolDispatcherTest arm feeding the bad string BEFORE any SQL (no new validation code; the dispatcher's String length cap already bounds the arg)."
  - "§8-AUTHORIZED pre-existing-test modifications (engineering-rules §8; this ticket authorizes exactly these, in plain language): (a) ChatAgentTest.preFetchBlockDisclosesItIsNotTimeFiltered — the FIXTURE USER MESSAGE becomes a non-temporal phrasing (e.g. \"what happened in tech news?\") so the test pins the disclosure header on the turn class it now belongs to (parse-miss); its three header assertions, the dated-JSON fold assertion, and the wrapper-order assertion are UNCHANGED in intent — the M1-927 behavior moves to the turns that still have it, and acceptance item 2 pins the windowed header on temporal turns (the M1-785 fixture-calibration lesson applied forward); (b) the ChatAgent constructor gains QueryAnchorTranslator — the EIGHT direct-construction test files (ChatAgentTest, ChatAgentAuditActorTest, ChatAgentProvenanceTest, ChatAgentRefusalInterceptionTest, ChatAgentRefusalInterceptTest, ChatAgentPromptExceededTest, ChatAgentReplyLanguageTest, ChatAgentReplyModeTest) thread a stub/fake translator through their buildAgent/super chains; each stub's ONLY behavior beyond returning its input is the call recording the tests above assert on (the M1-932 item-7d pattern, enumerated). No OTHER pre-existing test is touched — probe: git diff names no test file outside files_scope."
  - "Catalog and pins untouched (analysis P4): ChatToolCatalogTest.searchToolDescriptionsCarryTemporalRoutingGuidance, ChatAgentTest.renderedInstructionTableIsByteIdentical, and everyCatalogArgsShapeMatchesToolParsing pass UNMODIFIED — probe: git diff --name-only shows NO ChatToolCatalog.java/ChatToolRegistry.java hunk and the four M1-916 marker assertions are green; grep -c 'window=' over ChatToolCatalog.java returns 0."
  - "Prompt-byte ledger + hint survival (analysis P12): the hint + windowed header appear ONLY on parse-hit turns (item 3's no-hint assertion pins the zero cost); the hint rides the trusted directive region AFTER the untrusted block and SURVIVES semanticDropped (it addresses the user's question, not the block) — pinned by a compaction-forcing arm or reviewer diff check; the hint constant exists EXACTLY ONCE in production code (ChatAgent) — probe: grep -c 'use window=' over infochat-provider/src/main returns 1."
  - "Spec amendments ride the diff (engineering-rules §12 — exact wording user-approved at implementation; number-free rule text; rides-the-diff shape, NOT a SPEC-GAP: analysis P15): docs/spec/security.md semanticSearch row — Inputs gains `window: duration (optional; set only by the deterministic dispatch layer's temporal parse of the anchored query — never advertised to the model; a model-supplied value is honored identically)` and Notes gains: the optional window composes AND inside BOTH arms before their limits, binds to ready_at exactly as searchPosts's window does (commands.md §Content, What the window measures) under the same clamp; within a window the set and order remain similarity-fused, never time-ordered (D19); docs/spec/commands.md §Chat mode hybrid-retrieval paragraph gains: the dispatch layer deterministically parses explicit relative time expressions from the English-anchored query (regex + java.time, no model — D19); a parse hit windows the pre-fetch to the same ready_at rule and appends a deterministic window hint steering searchPosts; a parse miss changes nothing, and vague recency never infers a window — probes: grep -n 'window' docs/spec/security.md returns the semanticSearch row's new Inputs entry; grep -n 'temporal' docs/spec/commands.md returns the §Chat mode sentence; no date/ticket-id token in the added prose."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 synced: the pre-fetch fold paragraph records the windowed-header variant and the parse posture (anchored input, zone source, clamp, narrowest rule, vague-recency boundary); the byte ledger gains the ~30-40-token temporal-turn-only cost — probe: grep -n 'window' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mention."
  - "OWNER-RUN live re-probe (verification ceiling, the M1-916/M1-927 posture — no unit test can prove the model uses the hint; phrased owner-run with a recorded outcome, never claimed as a unit result): after landing, the owner re-asks the recorded temporal question (\"What happened in tech news in the last 2 hours?\") on the test stack: when the 2h window is empty, the reply must state the empty window (or serve only in-window dated posts) — a week-old post presented as recent FAILS; the eval delta (M1-930's baseline vs a post-landing harness re-run whose temporal arm passes the window — the harness extension is the eval lane's own) is the quantitative reference, recorded against the measurement lane, not CI (analysis P16)."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
      — windowedFusedSearchBoundsBothArmsToReadyAtCutoff (the tool-half
      reproduction), windowedArmsNeverSurfaceOutOfWorldOrNonReadyPosts,
      windowedFusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      — temporalTurnWindowsThePreFetchAndHandsTheModelAWindowHint (the
      agent-half reproduction), nonTemporalTurnIsByteIdenticalToThePreChangeShape,
      emptyWindowedPreFetchDegradesToGeneralKnowledgeNeverUnwindowed,
      enScopeTemporalParseIssuesNoTranslatorCall,
      nonEnglishScopeParsesTheAnchoredTranslation,
      temporalParseSitsInsideTheBreakerGate, groupScopeTodayUsesGroupsTimezone
      (+ the DM-default and zone-failure arms).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolDispatcherTest.java
      — the malformed-_window ValidationError arm (item 10).
  modifies:
    - >-
      ChatAgentTest.preFetchBlockDisclosesItIsNotTimeFiltered — the fixture
      user message becomes non-temporal (§8-authorized, item 11a).
    - >-
      the EIGHT ChatAgent*Test files' constructor chains — thread the
      QueryAnchorTranslator stub (§8-authorized, item 11b).
  preserves:
    - >-
      all tests currently green on main — explicitly RetrievalWorldPredicateIT,
      the SemanticSearchToolIT/HybridIT/DiversityIT suites (isolation,
      recall, determinism, budget), ChatToolCatalogTest,
      ChatToolRegistryTest, ChatToolDispatcherTest (beyond the added arm),
      ChatToolAllowlistSpecParityTest, QueryAnchorTranslatorTest,
      ChatAgentProvenanceTest, and ChatAgentTest's pre-fetch pins (the
      exactly-once dispatch pin, the SEMANTIC_QUERY_MAX_CHARS truncation
      pin, the identical-call cache pin, the breaker-open and
      header-absence pins), unmodified except items 11a/11b.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Chat mode
  - docs/spec/commands.md §Content
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D28
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
    date: 2026-08-29
    verdict: REWORK
    checks: "SPEC-TRUTHNESS: WARN; SECURITY: PASS; TEST-ADEQUACY: FAIL; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "21 files, +736/-89 (2 prod + 1 visibility widen, 11 test files + 1 new double, 3 docs)"
    notes: >-
      2 REWORK items (both low): (1) the unwindowed fused SQL gained one
      stray space per arm — worldPredicateSql followed by "+ \" \"" +
      empty readyBound — breaking acceptance item 3's byte-identical
      statement probe (behavior unaffected); fixed by dropping the "+ \" \""
      (readyBound carries its own spacing), python byte-assembly proof:
      IDENTICAL to pre-change. (2) the REAL groups.timezone read was never
      exercised with a row (the Prague arm overrode the very method that
      reads); fixed by ChatAgentTest.groupScopeZoneReadResolvesTheGroupsRow
      over proxy JDBC stubs through the REAL ChatAgent.zoneFor — green
      normally, red under the UTC-hardcode mutation (PT9H ≠ PT11H),
      reverted. FALSIFIED-AND-DROPPED: item 6's "fails on ANY invocation"
      literal (en no-op is a zero-GENERATION property, pinned as such);
      the two out-of-files_scope constructor-thread files (compile-forced
      11b-class, zero assertion changes, pre-declared); _window overflow
      (DateTimeParseException empirically verified in the typed arm);
      hint-phrase injection (parser grammar bounds the phrase);
      zone-SELECT per-turn cost (PK-indexed read inside existing buckets).
      Gate deviation recorded in the mech report: headless opencode run
      failed (endpoint UnknownError ×2) → Task-tool fresh-context spawn
      with the standard stub per harness-mapping §2.
  - round: 2
    date: 2026-08-29
    verdict: APPROVE
    checks: "round-1 items: 1 SATISFIED, 2 SATISFIED; full suite green; comment-cap 0"
    diff_stats: "fix hunks: 4 files, +123/-9 (probe drops ×2, zone-read test + proxies + DataSource seam, ticket/board bookkeeping)"
    notes: >-
      Both round-1 REWORK items SATISFIED against their EVALUATED-AS
      probes (grep 0 + byte-identity re-verified by the reviewer; test
      green + mutation red). No RECOMMENDED-NEW-TICKET entries, no naming
      suggestions. Verdict: .scratch/tick-review-M1-938-r2.txt.
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  result: pass
  checked: 2026-08-28
  notes: >-
    Lint 0/0. All file:line citations verified on bfae640b (ChatAgent
    548-553/880-881/896-899; SemanticSearchTool 108-113/133-134 with
    ready_at only in SELECT/COALESCE/emission; ChatToolDispatcher
    passthrough + cache key + DateTimeParseException typed arm;
    SearchPostsTool WINDOW_MIN/MAX widened by M1-937; TemporalExpressionParser
    + its test landed; commands.md 512-531; security.md 1730-1732;
    llm.md 475-480). Census re-run returned two paths without rows
    (ChatPromptBudgetTest — static synthetic block fixture, not
    agent-driven; SearchPostsToolTest — comment-only matches): rows
    added in-band. files_scope gained ChatToolDispatcherTest.java
    (named by test_plan.adds item 10; was an omission). P14: M1-935
    (searchPosts topics lane) still pending but shares no file with
    this scope and blocked_by lists only M1-937 (done) — order noted,
    not blocking. --parallel module check: zero tick tickets
    in-progress/in-review, paired with git worktree list (M1-936
    worktree clean, status pending). M1-937's added tests traced:
    TemporalExpressionParserTest and SearchPostsToolTest clamps
    preserved — parser and SearchPostsTool untouched by this diff.
escalation_reason:
---

# M1-938: Window the temporal pre-fetch; deterministic window hint

## Context

M1-937 lands the parser; nothing calls it. This ticket wires it into the
dispatch layer so BOTH grounding surfaces of a temporal turn agree: the
deterministic pre-fetch is windowed to the parsed ready_at bound (no more
week-old posts grounding "the last 2 hours" — the recorded M1-927 failure),
and the model receives a deterministic hint naming the window for its
searchPosts calls (replacing reliance on the M1-916 steering strings alone —
they stay). Absent a temporal expression, every surface is byte-identical to
today. Shared analysis: `analysis_ref:` (Ground truth, Pitfalls P1-P16,
Solution options A-G — B/C/D/E/F rejected there, G deferred).

## Root cause

Verified end to end (analysis Ground truth): the pre-fetch dispatches
`semanticSearch` with the raw truncated message (ChatAgent.java:880-881) and
the fused SQL has no time predicate (SemanticSearchTool.java:198-335 —
`ready_at` only in SELECT/COALESCE/emission); the anchored string exists
only inside the tool (SemanticSearchTool.java:133-134); the tool's executor
reads exactly `query` and `limit` (:108-113) — an extra dispatch arg is
passed through untouched by the dispatcher (ChatToolDispatcher.java:155-205)
and forms its own cache-key entry (:171-172). The window discipline to match
is pinned by commands.md §Content (:512-531, ready_at binding, the
searchPosts clamp [1h,30d]) and llm.md §Determinism boundary (:475-480 —
clock-windowed SQL is that section's own determinism exemplar).

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P4-P7,
P9-P16.

- P1: the M1-927 test's TEMPORAL fixture — authorized fixture move (item
  11a), or the implementor breaks/weakenes a load-bearing disclosure pin.
- P2: parse-miss byte identity is four surfaces (args, SQL, header, hint) —
  each pinned; the SQL predicates are conditionally assembled, never
  nullable-always-on.
- P4: the window must NOT be advertised — internal `_window` arg, zero
  catalog/instruction/wire diff; a model-guessed emission is honored safely
  and recorded in the spec row.
- P5: zone — groups.timezone (group), infochat.groups.default-timezone
  (DM), UTC degrade; never inferred from text.
- P6: in-arm AND composition before each arm's LIMIT; no post-filter over
  the fused pool (leak-shape adjacency AND arm-slot burn).
- P7: clamp from the SHARED SearchPostsTool constants (M1-937 widened them).
- P9: translator reuse (no second translator class); en strict no-op;
  tool-internal call becomes a cache hit (same key discipline); the parse
  sits INSIDE the breaker gate (security.md:1730-1732).
- P10: empty window → no block, hint stays, no unwindowed retry.
- P11: the eight-file constructor thread (item 11b).
- P12: hint only on parse-hit; trusted region; survives compaction; the
  constant exists once.
- P13: cache divergence windowed-vs-unwindowed — disclosed, bounded by the
  call cap; the existing cache pin's fixture is non-temporal and stays
  green.
- P14: land after M1-932/934/935 (brief's order; shared files), blocked_by
  M1-937, same module — never --parallel.
- P15: rides-the-diff amendments, number-free, user-approved wording.
- P16: no eval fixture/harness/record edits; the flip is the owner-run
  delta.

## Approach

Derived from `spec_refs:` — commands.md §Content fixes what the window
means (ready_at, one vocabulary); commands.md §Chat mode fixes where the
determinism lives (the agent's pre-fetch is D28-always-runs, SQL-decided);
security.md §Prompt-injection defenses owns the tool row the amendment
records; llm.md §Determinism boundary fixes how (deterministic parse, SQL
set/order, clock as legitimate input).

- **Files to touch:** `files_scope` (two production files, nine test files,
  two spec sections, one design section).
- **Pre-decided shapes (implementation is execution):**
  1. **ChatAgent parse site** — inside the existing breaker-closed branch,
     before `buildSemanticRetrievalBlock`: `anchored = queryAnchorTranslator
     .translate(truncated, scopeLanguage, scopeKind, scopeId)` (the SAME
     500-char truncation, the SAME declared language — the cache-key
     discipline); `parsed = TemporalExpressionParser.parse(anchored, zone,
     clock.instant())` where zone resolves: group scope → one short
     connection `SELECT timezone FROM groups WHERE id = ?` (degrade UTC +
     SafeLog on failure); DM → the field-injected
     `infochat.groups.default-timezone` (default "UTC"). The Clock is the
     `@Inject Clock` FIELD (SearchPostsTool.java:52-53 precedent — keeps the
     constructor churn to the one translator parameter, §9 pinability).
  2. **Pre-fetch dispatch** — parse-hit: `Map.of("query", q, "_window",
     parsed.window().toString())`; parse-miss: today's `Map.of("query", q)`
     exactly. `buildSemanticRetrievalBlock` takes the parsed window; the
     parse-miss path's bytes are untouched (P2).
  3. **Windowed fold header** — parse-hit turns replace the M1-927 header
     with: `"\n\nPosts from the user's subscribed feed semantically related
     + "to their message — matched by topic similarity within " + <phrase>
     + " (posts that became readable in that window):\n"` + the SAME
     UNTRUSTED_CONTENT wrapper; the not-time-filtered header stays VERBATIM
     for parse-miss turns.
  4. **The hint** — a single ChatAgent constant, appended on parse-hit turns
     to the directive region (both the `promptBuilder.build` turnDirective
     slot and the runToolLoop re-seed), surviving `semanticDropped`:
     `"\n\nThe user asked about " + <phrase> + ". When calling searchPosts,
     use window=" + <ISO duration> + " — its window counts posts by when
     they became readable (ready_at). If the window is empty, say so; do
     not silently widen it."` — static text interpolated only with the
     parse's phrase and clamped ISO duration (D19-safe; the M1-916 steering
     strings stay untouched).
  5. **SemanticSearchTool** — `execute` reads optional `_window` (String,
     present-and-non-blank gate; `Duration.parse` + the shared clamp;
     malformed → IllegalArgumentException/DateTimeParseException → the
     dispatcher's existing typed arm); `@Inject Clock` field; `queryFusedPosts`
     gains the cutoff and conditionally appends `AND p.ready_at >= ?`
     INSIDE each arm's WHERE (bind positions in both arms, params list kept
     in order); the no-window statement string is byte-identical to today's.
  6. **Spec/design amendments** — the rule-text drafts in acceptance item
     14 (user-approved wording; number-free).
- **Steps, in implementation order:** (1) write both reproductions + the
  failure-mode tests RED; (2) tool half (shape 5) — the windowed IT goes
  green, the unwindowed suites stay green; (3) agent half (shapes 1-4) +
  the §8-authorized test edits (item 11); (4) spec + design with the user's
  wording approval; (5) full `mvn verify`; hand the owner-run re-probe and
  the eval-delta reference to the user.
- **Controls to preserve (§10):** the analysis's enumeration — in-arm READY
  + D59 predicates, threshold, RRF_K, perSourceCap, iterative-scan
  strict_order, cancellation arming, query anchoring (reused translator, D58
  four conditions), 16 KiB whole-entry budget, dated emission, the M1-927
  header bytes on parse-miss turns, pre-fetch mechanics (exactly-once
  dispatch, truncation, TurnContext sharing, breaker-open skip extended to
  the translate), provenance notice, dispatcher boundary, en no-op, the
  M1-916 catalog markers, the allowlist parity surface. Pinning tests: the
  item-preserves list.
- **Alternatives considered (rejected, recorded for the commit message):**
  suppression on temporal turns (B — A minus grounding, breaks D28's
  always-runs); hint-only (C — leaves the recorded failure in place);
  recency ordering (D — M1-917 owner-reject); advertised catalog window arg
  (E — breaks the M1-916 routing line, collides with M1-932/935 pins);
  post-filter (F — burns arm slots); residue→text auto-compose (G —
  deferred, eval-gated).

## Definition of done

Both reproductions pass (the windowed tool bounds both arms; the temporal
turn dispatches _window, folds the windowed header, and carries the hint);
parse-miss byte identity holds on all four surfaces; the isolation,
empty-window, determinism, anchoring, breaker, timezone, and
malformed-window arms pass; the §8-authorized fixture move and the
eight-file constructor thread land with intent stated and the fence probe
clean; the catalog and byte-pinned instruction table are untouched; the
security.md row, commands.md §Chat mode, and design 05 §5.4.6 carry the
user-approved number-free rule text; the owner-run re-probe and eval-delta
reference are handed over with their record obligation; mvn verify green
from the repo root.

## Verification

- Reproduction → acceptance items 1 and 2 (the compose discriminator in
  item 1's companion arm; the three-assertion RED in item 2).
- P1 → item 11a (the reviewer diffs the fixture move against the plain-
  language authorization; item 2 pins the moved-in windowed header).
- P2 → item 3 (args key set, header bytes, no-hint, unmodified identity
  arms, conditional-SQL diff check).
- P4 → item 12 (no catalog diff; the four M1-916 markers green; grep
  probes).
- P5 → item 8 (Prague/UTC discrimination; DM default; failure degrade).
- P6 → item 4 (hostile unsubscribed + non-READY feeds; reviewer diff
  check).
- P7 → item 1's clamp reference (the shared constants; M1-937's pin).
- P9 → items 6 and 7 (en zero-call; cs same-key double-read with one
  generation; breaker-open zero/zero).
- P10 → item 5 (no block, one windowed dispatch, hint present).
- P11 → item 11b's enumeration + the fence probe.
- P12 → item 3's no-hint arm + item 13's single-constant probe and
  survival check.
- P13 → disclosed (analysis P13); the unmodified cache pin is the evidence
  the parse-miss sharing still holds.
- P14 → blocked_by + the sequencing note; the board regenerates.
- P15 → item 14's probes + the user-approval record.
- P16 → the fence probe (no eval path in the diff) + item 16's owner-run
  phrasing.
- FAILURE-MODE coverage beyond the reproductions → items 4 (leak feeds),
  5 (empty window), 7 (open breaker), 8's failure arm (zone lookup), 10
  (malformed _window), 6's fallback edge (translation failure → raw text →
  parse-miss → byte-identical turn, asserted by the en-arm companion).
- acceptance items 13-17 → the named probes, greps, the owner-run protocol,
  mvn verify.

## Out-of-scope

Named in `out_of_scope:` — the catalog/instruction-table/wire schema
(untouched; the window is internal); searchPosts changes (M1-932/935's
lane; only the shared clamp constants are consumed); residue composition
(deferred option G, eval-gated); recency ordering (M1-917 owner-reject);
grammar extension (M1-937's boundary); the eval lane (M1-928/929/930
contracts); §Rate limiting/§Secrets edits (leg count unchanged — the
distinguished M1-932 P5 case); timezone surface changes (read-only zone
resolution). This ticket modifies EXACTLY one pre-existing test's fixture
(item 11a) and the eight enumerated constructor threads (item 11b), plus
the dispatcher-test arm; every other pre-existing suite must pass
unmodified.

## Census

Class-scoped (the surfaces that observe the pre-fetch dispatch or the
folded block — re-runnable: `grep -rn 'semanticSearch\|subscribed feed'
infochat-provider/src/test/java/app/zcat/infochat/provider/chat | grep -v
tool/eval`). Every site disposed:

| Site | Disposition |
|---|---|
| ChatAgentTest.preFetchBlockDisclosesItIsNotTimeFiltered | **Authorized edit** — fixture message becomes non-temporal (item 11a) |
| ChatAgentTest pre-fetch pins (exactly-once, truncation, cache, breaker, header-absence) | **Unchanged** — non-temporal fixtures; parse-miss byte identity preserves them |
| ChatAgentTest.identicalModelSemanticCallServedFromSharedPerTurnCache | **Unchanged** — "hi" fixture; windowed/unwindowed key divergence disclosed (P13) |
| The seven sibling ChatAgent*Test constructor chains + ChatAgentAuditActorTest | **Authorized edit** — translator threading only (item 11b) |
| ChatToolCatalogTest / renderedInstructionTableIsByteIdentical / everyCatalogArgsShape | **Unchanged** — no catalog change (P4) |
| SemanticSearchToolIT/HybridIT/DiversityIT, RetrievalWorldPredicateIT | **Unchanged** + three added windowed arms (isolation, identity, bounds) |
| ChatToolDispatcherTest | **Added arm** only (malformed _window) |
| QueryAnchorTranslatorTest, ChatAgentProvenanceTest, ChatToolRegistryTest, ChatToolAllowlistSpecParityTest | **Unchanged** — no translator-behavior, provenance, names, or allowlist change |
| ChatPromptBudgetTest | **Unchanged** — static synthetic SEMANTIC_POSTS_BLOCK fixture, not agent-driven; observes neither dispatch nor production header |
| SearchPostsToolTest | **Unchanged** — comment-only grep matches; searchPosts lane untouched |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-938-prefetch-windowing-and-hint.md
```

## Round 1 rework

REWORK ITEMS (verbatim from `.scratch/tick-review-M1-938-r1.txt`):

1. Finding 1: restore the byte-identical unwindowed SQL — drop the
   unconditional `+ " "` after worldPredicateSql("p") at
   SemanticSearchTool.java:284 and :300 (the windowed readyBound keeps its
   own spacing), evaluated via `grep -c 'worldPredicateSql("p") + " '
   infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java`
   returning 0 plus SemanticSearchToolIT.windowedFusedSearchBoundsBothArmsToReadyAtCutoff
   (windowed and unwindowed arms) green in the round-2 log.
2. Finding 2: add ChatAgentTest#groupScopeZoneReadResolvesTheGroupsRow —
   stubbed groups row returning Europe/Prague through the REAL
   ChatAgent.zoneFor (no override), pinned clock 09:00Z, assert
   _window == "PT11H" (no-row companion: "PT9H") — evaluated via that test
   passing in the round-2 log and failing under the UTC-hardcode mutation
   at ChatAgent.java:997.
