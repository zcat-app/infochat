---
id: M1-932
title: "Add a text filter parameter to searchPosts"
status: done
created: 2026-08-26
last_updated: 2026-08-28
flow: tick
reproduction: >-
  SearchPostsToolTest#textFilterNarrowsWithinWindowToPostsMentioningTheText
  (marker converted at /tick start 2026-08-28: written and run RED against
  unmodified code — .scratch/tick-red-M1-932.log, the text-carrying call
  returned the unfiltered four-post set — green after the fix, full
  mvn verify green).
  The wrong behavior it states: a searchPosts call carrying a "text" argument
  is answered with the UNFILTERED window set. Verified in-tree today:
  SearchPostsTool.execute() reads exactly three args — tags, window, limit
  (SearchPostsTool.java:66-71; the file read end to end: no other arg key is
  consulted, and `grep -n 'text' SearchPostsTool.java` returns no argument
  read); the spec input contract matches — security.md:328 searchPosts Inputs
  column is `tags: list<Tier-1 tag>`, `window: duration`, `limit: int ≤
  profile-driven cap`, no text parameter; the catalog advertises the same
  three args (ChatToolCatalog.java:38-40). Live observation motivating the
  change (user, 2026-08-26, carried as brief evidence — output observation,
  mechanism verified in-tree): "What happened with qwen AI in the last day?"
  retrieves poorly because the model must pick tag=ai and then eyeball 50
  date-ordered titles; it cannot express "only posts mentioning qwen". The
  lexical machinery already exists unused by this tool: post.search_tsv
  (V58 generated tsvector + GIN index, rebuilt by V74 over the English
  anchored fields) and the exact compose pattern in SemanticSearchTool's
  lexical arm (SemanticSearchTool.java:262-267).
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolCatalog.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolDispatcherTest.java
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY ranking change — filter-only is a binding user decision (2026-08-26):
    no ts_rank, no relevance ordering, no change to the ORDER BY. The
    COALESCE(published_at, fetched_at) DESC, id DESC ordering and its
    NULLs-first head-seizure bound (security.md:328, schema.md §"`published_at`
    clamp", M1-689 redteam rounds 1-2) are untouched. Ranking-within-recency
    is a separate eval-gated decision.
  - >-
    ANY new tool, and ANY change to semanticSearch — the parameter rides the
    existing searchPosts tool (binding user decision); SemanticSearchTool,
    its fused SQL, threshold, limit default, emission, and M1-927's dated
    emission are untouched.
  - >-
    ANY window-semantics change — the ready_at window binding ("What the
    window measures", commands.md:512-531) narrows WITHIN the window via the
    text filter and never redefines the window; /summary, the digest, and
    their window queries are untouched.
  - >-
    The disclosure shell scripts and SETUP_GUIDE.md — verified data-class
    level, not per-tool leg enumerations (switch-llm.sh:416-424 and
    SETUP_GUIDE.md:764 say "your search query" and point at the spec for the
    leg list; SwitchLlmWiringTest pins those bytes). The new leg is recorded
    in security.md §Rate limiting + §Secrets handling, which those texts are
    kept in sync with BY REFERENCE; no script byte moves.
  - >-
    Golden-set re-labeling and the eval re-run — the golden set is M1-928's
    frozen supersedes-discipline contract and the baseline is M1-930's
    record; this ticket's landing changes the measured surface and the
    owner-run delta is the acceptance reference, but no eval fixture or
    measurement record is edited here.
  - >-
    Topics 5 (temporal parse) and 6 (synthesis + body surfacing) of the RAG
    campaign — they touch the same tool surface AFTER this; cross-referenced
    in Pitfalls P10/P13 (topic 5's parser supersedes the interim temporal
    steering sentence via the byte pin), not started here.
  - >-
    Any docs/spec/commands.md edit — the §Chat mode hybrid-retrieval
    paragraph describes the semanticSearch arms and makes no promise this
    change alters; the §Content window rule is preserved, not amended.
acceptance:
  - "REPRODUCTION closed: SearchPostsToolTest.textFilterNarrowsWithinWindowToPostsMentioningTheText passes — seeds four in-world READY posts inside the window from one subscribed source (two whose title/body mention the keyword, two that do not); a call carrying text=\"<keyword>\" returns EXACTLY the two matching uids; the companion arm on the same fixture with no text arg returns all four (the compose discriminator: a mutation dropping the predicate fails the first arm, a mutation filtering unconditionally fails the second)."
  - "Compose semantics: SearchPostsToolTest.textFilterComposesWithWindowAndTagPredicates passes — (a) a keyword post whose ready_at falls OUTSIDE the requested window is excluded even though it matches the text; (b) a keyword, in-window post outside the requested tag subtree is excluded when tags are also given; (c) a keyword, in-window, on-tag post is returned. The text filter narrows WITHIN window+tags, never replaces them (commands.md §Content, What the window measures)."
  - "FAILURE-MODE (isolation, the M1-589 leak class): SearchPostsToolTest.textFilterNeverSurfacesOutOfWorldOrNonReadyPosts passes — an UNSUBSCRIBED custom source's keyword post and a subscribed source's non-READY keyword post, both matching the text, never appear in the result; the text predicate composes AND inside the one statement with the READY + D59 world predicate, never as a post-filter over an over-fetched set."
  - "FAILURE-MODE (anchoring leg): SearchPostsToolTest.nonEnglishScopeAnchorsTheTextBeforeMatching passes — scope_preferences row language='cs' for the calling scope; a recording fake QueryAnchorTranslator returns a fixed English string; the seeded post carries ONLY that translated string in its body; asserts the post IS returned and the translator was called with (raw text, \"cs\", scopeKind, scopeId) — the scope-partitioned key (R2). Mutations failing it: skipping the translate step (the raw non-English text does not match the seeded English body), or translating without the scope coordinates."
  - "en no-op (the M1-746 safe-no-op property on the new leg): SearchPostsToolTest.enScopeTextFilterIssuesNoTranslatorCall passes — no scope_preferences row (declared language defaults 'en', D43); a translator stub that fails the test on any invocation; the text-filtered result is returned with ZERO translator calls and zero scope-language lookups issuing a translation."
  - "Blank-text semantics: SearchPostsToolTest.blankTextBehavesAsNoFilter passes — text=\"   \" (present but blank) returns the same uid set as a call with no text arg; a blank filter is absent, never an error (the param is optional; its absence-meaning is the superset)."
  - "§8-AUTHORIZED pre-existing-test modifications (engineering-rules §8; this ticket authorizes exactly these, in plain language): (a) ChatAgentTest.renderedInstructionTableIsByteIdentical — ONLY the searchPosts expected line changes, gaining the text example arg and the appended description sentence verbatim from the landed catalog; every other line (semanticSearch, getPost, getReferences, recallMemory, listSaves, helpLookup) stays byte-identical; (b) ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing — the searchPosts expected shapes gain \"text:string\"; (c) ChatAgentTest.toolInstructionsMatchSearchPostsParams — gains the assertion that the instructions document the text param alongside tags/window/limit; (d) SearchPostsToolTest's two direct constructions (:133 and :377 area, the connection-count and tag-batch tests) pass the CDI-injected QueryAnchorTranslator — never invoked on those textless paths."
  - "Steering lands on BOTH model surfaces, single-sourced: ChatToolCatalogTest.searchPostsDescriptionDocumentsTheTextFilter passes — the searchPosts description names the text param, its keyword-narrowing role, the English expectation, and the temporal routing rule (time expressions go to `window`, never `text` — `text` carries entity/topic terms only); ChatToolCatalogTest.wireDeclarationsCarryTheSameRoutingGuidance keeps passing (the wire description is the same single-sourced catalog string); parametersRenderAsValidJsonSchema keeps passing (the JSON-Schema derives the text property from the new ToolArg)."
  - "Filter-only discipline (D19, llm.md §Determinism boundary): the ORDER BY is byte-identical to today's — probe: `grep -n 'ORDER BY' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java` returns exactly the pre-existing COALESCE(published_at, fetched_at) DESC, p.id DESC LIMIT line; `grep -n 'ts_rank\\|search_tsv\\|to_tsquery\\|plainto_tsquery'` over the same file returns hits ONLY in the WHERE composition (never ORDER BY, never SELECT); no ts_rank token exists in the file."
  - "Textless path byte-identical + isolation suites pass UNCHANGED: SearchPostsToolTest's pre-existing tests (ready_at field binding, one-connection-per-call + statement_timeout + pid registration, window binding, undated-post ordering, tag validation, byte cap, tag-mode decoupling), RetrievalWorldPredicateIT, SearchPostsToolTopExpansionIT, SearchPostsToolClockTest, StopToolQueryCancellationIT, ChatToolRegistryTest, ChatToolAllowlistSpecParityTest (its check is names-only — an arg addition does not touch it), QueryAnchorTranslatorTest — probe: `git diff` names no test file outside the §8-authorized set of item 7, and mvn -pl infochat-provider -am verify is green."
  - "Dispatcher boundary already bounds the new param (security.md §Prompt-injection defenses: every free-form string input length-capped before any SQL runs): ChatToolDispatcherTest.rejectsOversizedInput gains a searchPosts arm — an over-cap text (501 chars at the default infochat.chat.tool.input-max-length=500) returns a typed ValidationError naming the input, BEFORE any SQL executes; no new validation code is written in the tool (ChatToolDispatcher.validateValue recurses over every String arg, :221-255 — the arm pins that the param rides the existing cap)."
  - "Spec amendment rides the diff (engineering-rules §12 — the exact wording goes to the user for approval at implementation; rule-text drafts in the Approach; rides-the-diff shape, NOT a SPEC-GAP: the amendment records behavior the closed-tool-table discipline requires recording, and every existing promise of the row is preserved): docs/spec/security.md searchPosts row — Inputs column gains `text: string` (optional free text, length-capped), Notes gains the mechanism sentence (composes AND with tag/window/world; matches post.search_tsv via a parameter-bound to_tsquery('english', …) whose bound value carries ONLY sanitized terms as AND-joined prefix lexemes (term:* — qwen matches qwen/qwen2/qwen3; raw text never interpolated); temporal expressions route to window and text carries entity/topic terms only; never reorders — the COALESCE ordering and its bound unchanged; blank text is no filter) and the anchoring clause (a non-English declared /lang anchors the text to the corpus language under the same bounded exception as the semanticSearch query; en is a strict no-op; failure falls back to the raw text); §Rate limiting's query-anchoring bullet extends its leg enumeration to the searchPosts text filter; §Secrets handling's query-anchoring bullet names the searchPosts text leg — probes: `grep -n 'to_tsquery' docs/spec/security.md` returns the searchPosts row (the semanticSearch row keeps its own plainto_tsquery wording, untouched); `grep -n 'searchPosts text' docs/spec/security.md` returns the §Rate limiting mention."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 synced: the searchPosts catalog-description paragraph records the text param, its keyword-narrowing role, and its temporal-to-window routing sentence, and the query-anchoring paragraph records that the searchPosts text leg anchors identically to the semanticSearch query leg — probe: `grep -n 'text filter' docs/design/05-llm-and-embeddings.md` returns the §5.4.6 mention."
  - "OWNER-RUN live probe (verification ceiling, the M1-916/M1-927 posture — no unit test can prove the model uses the param; phrased owner-run with a recorded outcome, never claimed as a unit result): after landing, the owner re-asks the motivating question (\"What happened with qwen AI in the last day?\") on the test stack and captures the provider-log slice — the slice shows a searchPosts dispatch whose args carry a non-empty text field (a slice whose only searchPosts call carries no text FAILS); the reply's cited posts all match the text. The quantitative acceptance reference is the owner-run log-slice probe above; a quantitative class-delta would need the eval lane's own searchPosts-side extension — M1-929's runner executes the semanticSearch fused path only and never dispatches searchPosts, so this filter cannot move a harness number as specced (the M1-938 posture; any harness extension is the eval lane's separately-decided follow-up)."
  - "mvn verify from the repo root is green (engineering-rules §5)."
  - "PREFIX-LEXEME match (P12, tokenizer conjoining — the stress-test miss): SearchPostsToolTest.textFilterMatchesPrefixLexemes passes — a post titled \"Qwen3-32B released\" (indexed lexemes qwen3, 32b — no bare qwen lexeme exists) IS returned by text=\"qwen\" (the bound value is `qwen:*`); seeded titles \"Qwen\", \"Qwen2\", \"Qwen2.5\" are returned by the same call; a bare-lexeme mutation (plainto_tsquery, no :*) returns ONLY the standalone-\"Qwen\" post and fails the arm."
  - "Version-discriminating AND composition (P12): SearchPostsToolTest.textFilterPrefixLexemesDiscriminateVersions passes — one fixture with a \"Qwen3-35B\" post and a \"Qwen 27B\" post; text=\"qwen 27B\" (bound `qwen:* & 27b:*`) returns EXACTLY the \"Qwen 27B\" uid and NOT the \"Qwen3-35B\" uid (`27b:*` does not prefix-match lexeme `35b`); an OR-widened assembly — the sanitization failure mode — returns both and fails."
  - "Temporal-word-in-text degradation is DEFINED, not accidental (P13): SearchPostsToolTest.temporalWordInTextBehavesAsLiteralTermFilter passes — text=\"qwen today\" (bound `qwen:* & today:*`) returns the seeded post whose body carries BOTH qwen and today, does NOT return the qwen-only post, and still excludes a qwen+today post whose ready_at falls outside the requested window (the window governs time; the text governs words); with the steering sentence pinned by item 8, the degradation is documented over-narrowing — never an error, never a window redefinition."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
      — textFilterNarrowsWithinWindowToPostsMentioningTheText (the
      reproduction), textFilterComposesWithWindowAndTagPredicates,
      textFilterNeverSurfacesOutOfWorldOrNonReadyPosts,
      nonEnglishScopeAnchorsTheTextBeforeMatching,
      enScopeTextFilterIssuesNoTranslatorCall, blankTextBehavesAsNoFilter,
      textFilterMatchesPrefixLexemes, textFilterPrefixLexemesDiscriminateVersions,
      temporalWordInTextBehavesAsLiteralTermFilter.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java
      — searchPostsDescriptionDocumentsTheTextFilter.
  modifies:
    - >-
      ChatAgentTest.renderedInstructionTableIsByteIdentical — the searchPosts
      line only (§8-authorized, item 7a).
    - >-
      ChatAgentTest.toolInstructionsMatchSearchPostsParams — adds the text
      assertion (§8-authorized, item 7c).
    - >-
      ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing — searchPosts
      gains text:string (§8-authorized, item 7b).
    - >-
      SearchPostsToolTest's two direct SearchPostsTool constructions — pass the
      injected translator (§8-authorized, item 7d).
    - >-
      ChatToolDispatcherTest.rejectsOversizedInput — gains the searchPosts/text
      arm (item 11).
  preserves:
    - >-
      all tests currently green on main — explicitly RetrievalWorldPredicateIT,
      SearchPostsToolTopExpansionIT, SearchPostsToolClockTest,
      StopToolQueryCancellationIT, ChatToolRegistryTest,
      ChatToolAllowlistSpecParityTest, QueryAnchorTranslatorTest, and
      SearchPostsToolTest's pre-existing tests, unmodified except item 7d's
      constructor-argument threading.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Secrets handling
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/commands.md §Content
decision_refs:
  - D19
  - D29
  - D43
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
    date: 2026-08-28
    verdict: REWORK
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY FAIL (1 low), MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "10 files, +571/-31"
    rework_items: 1
    verdict_file: .scratch/tick-review-M1-932-r1.txt
  - round: 2
    date: 2026-08-28
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "fix-only: 3 files, +32/-5 (full diff 10 files, +603/-36)"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-932-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: >-
  start 2026-08-28: lint 0 findings. All file:line citations re-verified by
  read; two line-drift-only offsets from later landings (args read :69-74
  not :66-71; byte-pin test at :1412 not :1374-1399) — substance holds
  everywhere. Census re-run clean (no 'text' read, no search_tsv in the
  tool; exactly two direct constructions at :133/:377). No blocked_by
  tests to trace. No §8-authorized fifth test touched. Spec wording
  user-approved with edits (§Secrets retention + snapshot sentences,
  blank-or-wholly-sanitized-away, design 05 "all four legs"); the
  unbackticked searchPosts in §Rate limiting deviates from the Approach
  draft deliberately — the acceptance grep 'searchPosts text' must hit.
escalation_reason:
---

# M1-932: Add a text filter parameter to searchPosts

## Context

Live observation (user, 2026-08-26, one of the failures motivating the RAG
improvement campaign): "What happened with qwen AI in the last day?" retrieves
poorly. `searchPosts` accepts only `tags`/`window`/`limit`
(security.md:328), so the model must pick `tag=ai` and then eyeball 50
date-ordered titles — there is no way to express "only posts mentioning
qwen". The lexical machinery to answer that already exists and is exercised
by the other search tool: `post.search_tsv` (V58 STORED generated tsvector +
GIN index on the partitioned parent; V74 rebuilt it over the English anchored
fields `coalesce(title_en, title) || ' ' || coalesce(body_en, body)`) is
probed by `SemanticSearchTool`'s lexical arm as
`p.search_tsv @@ plainto_tsquery('english', ?)` with the text bound as a
parameter (SemanticSearchTool.java:262-267) — it is simply not exposed as a
searchPosts parameter. Revision round (user stress test, 2026-08-26, on
"qwen today" / "qwen 27B"): Postgres tokenizes `Qwen3-32B` into lexemes
`qwen3`, `32b` — a bare `qwen` lexeme never exists in qwen-family titles,
so the match must be PREFIX-based (P12), and temporal words must route to
`window`, never `text` (P13). This is topic 3 of the RAG campaign's (2,3,7) parallel
batch; the eval's entity/topic classes (M1-928, whose entity-project examples
are literally "qwen, monero/zcash") gain expected hits once this lands (a
quantitative delta needs the eval lane's own searchPosts-side extension —
M1-929's runner never dispatches searchPosts; the owner-run probe is the
live acceptance reference). Single-ticket analysis: `analysis_ref: self` —
this body is the analysis.

## Root cause

Verified in-tree, end to end:

1. **The tool reads three args.** `SearchPostsTool.execute()` consults
   exactly `tags`, `window`, `limit` (SearchPostsTool.java:66-71); the file
   read in full contains no other arg key and no reference to `search_tsv`.
   `queryPosts` builds    `WHERE p.status = 'READY' AND p.ready_at >= ? AND
   <D59 world predicate> [AND tag expansion] ORDER BY COALESCE(p.published_at,
   p.fetched_at) DESC, p.id DESC LIMIT ?` via a StringBuilder + ordered params
   list (:147-166) — the composition point a fourth predicate joins.
2. **The spec contract matches the code.** The security.md:328 Inputs column
   is `tags: list<Tier-1 tag>`, `window: duration`, `limit: int ≤
   profile-driven cap`; the catalog advertises the same three
   (ChatToolCatalog.java:38-40), pinned by
   `everyCatalogArgsShapeMatchesToolParsing`
   (ChatToolCatalogTest.java:48) and the byte-pinned instruction table
   (ChatAgentTest.java:1374-1399). Adding the parameter is therefore a
   spec-amendment-riding-the-diff change (the M1-927 precedent shape), not
   just a code edit.
3. **The lexical pattern to compose already ships.** The semanticSearch
   lexical arm's discipline: regconfig PINNED to `'english'` matching the
   stored generated column (a mismatched or GUC-derived config would
   silently miss the GIN index and make results session-dependent — V58's
   header comment; SemanticSearchTool.java:212-217), the model-supplied text
   reaching `plainto_tsquery` ONLY as a bind parameter
   (security.md:329: "query bound as a parameter — never
   string-concatenated").
4. **The anchoring leg exists and is reusable.** The model-supplied text
   arrives in the model's language; `search_tsv` is English-anchored (D29).
   `QueryAnchorTranslator.translate(query, sourceLanguage, scopeKind,
   scopeId)` already implements the D58 bounded exception end to end — en
   strict no-op (no call, no cache), cached per (scope, query, language),
   scope-partitioned, temperature-0, language-only, falls back to the raw
   text on any failure (QueryAnchorTranslator.java:195-314).
   `SemanticSearchTool.lookupScopeLanguage` (:160-177) resolves the DECLARED
   scope language (`scope_preferences.language`, missing row = 'en' per
   D43) on its own short connection, deliberately NOT on the main pooled
   connection, because the translation HTTP round-trip must not hold a pool
   slot (:115-121).
5. **Not fully proven / implementor note (honesty):** the security.md:328
   row is a single very long line whose tail past the tool's 2000-char
   display truncation was verified by regex probes (it carries the
   "undated post sorts at the top of *its own fetch …*" precise-bound
   sentence; the canonical phrasings are readable at schema.md:403-405 and
   commands.md:525-527). The amendment edits the Inputs column and APPENDS
   to Notes, so the implementer reads the full row at edit time; nothing in
   the approach depends on the unverified tail bytes. The live observation
   itself is user-carried evidence; its mechanism (no text parameter
   exists) is verified above.

## Pitfalls

- P1: §8 pins bite immediately — three pre-existing tests assert today's
  arg set or render: `renderedInstructionTableIsByteIdentical`
  (ChatAgentTest:1374-1399, the searchPosts line changes),
  `everyCatalogArgsShapeMatchesToolParsing` (ChatToolCatalogTest:48, gains
  `text:string`), and SearchPostsToolTest's two direct `new SearchPostsTool(`
  constructions (:133, :377 — the constructor gains QueryAnchorTranslator;
  only those two sites exist, verified by grep). Without plain-language
  authorization the reviewer fails TEST-INTEGRITY-CHECK.
- P2: Filter-only, ordering untouchable — binding user decision. The ORDER
  BY carries the spec-fixed NULLs-first head-seizure bound
  (security.md:328: a bare `published_at` key would let any feed seize the
  head — the position re-injected first into the chat prompt — by omitting
  its date; M1-689 redteam rounds 1-2). Any ordering term derived from the
  text (ts_rank) changes that story and is a separate eval-gated decision.
  llm.md §Determinism boundary: retrieval (which posts come back, in what
  order) is always SQL — the text predicate joins the WHERE, nothing else.
- P3: Isolation composition — the predicate must compose AND inside the ONE
  statement with READY + D59 world + window + tags, exactly as the
  semanticSearch arms carry their predicates INSIDE the arm before any
  LIMIT (security.md:329; the M1-589/M1-617 over-fetch-then-filter leak
  class). No two-phase fetch-then-filter shape, ever.
- P4: Anchoring discipline (D58's four conditions hold by CONSTRUCTION —
  reuse, do not reimplement): translation runs BEFORE the main pooled
  connection is acquired (HTTP round-trip must not hold a pool slot — the
  SemanticSearchTool:115-143 ordering); the scope-language lookup runs ONLY
  when a non-blank text is present, so the textless path keeps its
  pinned exactly-one-connection-per-call behavior
  (SearchPostsToolTest:142-143); en is a strict no-op; the language is the
  DECLARED `scope_preferences` value (missing row = 'en', D43), never
  inferred from the text; any translator failure/breaker/over-cap falls
  back to the raw text (degraded retrieval beats no retrieval). Do NOT
  rewrite a second translator — that forks the D58 determinism contract.
- P5: A new translator call site is a new LEG — security.md §Rate limiting's
  query-anchoring bullet enumerates the legs precisely ("one per turn for
  the D28 pre-fetch, plus one per DISTINCT model-elected `semanticSearch`
  query", :1919-1924), and §Secrets handling states the enumeration is the
  authority the operator disclosure texts sync against (:2315-2321: "a new
  call site is a new leg"). Both spec spots must be amended in the SAME
  diff or the spec under-discloses a private-user-text exposure. The shell
  scripts and SETUP_GUIDE.md are verified data-class level ("your search
  query", switch-llm.sh:416-424, SETUP_GUIDE.md:764) — no script byte
  moves, and SwitchLlmWiringTest's pinned disclosure text is untouched.
- P6: regconfig + injection — the match runs as `to_tsquery('english', ?)`
  with the regconfig PINNED in the SQL text and the ASSEMBLED prefix-query
  string (the `qwen:* & 27b:*` shape) bound as ONE parameter; raw
  user/model text is never interpolated into the SQL — code emits only
  sanitized terms plus the `:*` and ` & ` scaffolding (matching the V74
  generated column). The 1-arg `to_tsquery(?)` form reads
  `default_text_search_config` at call time — session-dependent results
  and a silently-missed GIN index. The `semanticSearch` lexical arm keeps
  its own `plainto_tsquery('english', ?)` — untouched, out of scope.
- P7: Blank/garbage text semantics — present-but-blank text means NO
  filter (the param is optional; blank is not an error: semanticSearch
  rejects a blank query because query is its whole purpose, but here text
  is an optional narrowER). The SAME no-filter meaning covers the
  assembly-empty case: a text whose terms all sanitize away (pure
  punctuation, operator strings) leaves no `T:*` term and filters
  nothing. The dispatcher's generic caps already bound
  the param (input-max-length on every String arg — ChatToolDispatcher
  :221-255; per-turn cache key and call cap include it by construction);
  no new validation code (§7). A text matching nothing returns `[]` — the
  honest empty shape the empty window already produces (M1-927 evidence:
  the model reports it truthfully).
- P8: Prompt-byte ledger + single-source steering — the instruction table
  is NEVER dropped by the compaction ladder (commands.md:1845-1857: "The
  instruction and untrusted-content scaffolding is never dropped"), so the
  added catalog bytes (~25-40 tokens for one arg example + the two-sentence
  steering, temporal routing included) cost EVERY turn; weighed and
  accepted on the M1-916 P7 precedent (~90
  tokens accepted there). The steering text lives ONLY in the catalog
  description — no TOOL_INSTRUCTIONS paraphrase, no second copy (the
  M1-916 P1 drift class); the wire schema derives from the same ToolArg
  record (M1-872 single-source).
- P9: Param name is `text`, NOT `query` — M1-916 drew a load-bearing
  routing line (temporal → searchPosts, free-text topical →
  semanticSearch); a `query` param on searchPosts blurs it and invites the
  misroute back. The surface is name-watched
  (ChatAgentTest.toolInstructionsMatchRecallMemoryParams pins that
  recallMemory must NOT use 'query').
- P10: Landing order — RAG campaign topic 3 of the (2,3,7) batch; topics 5
  (temporal parse) and 6 (synthesis + body surfacing) touch the same files
  (ChatToolCatalog, SearchPostsTool/ChatAgent surfaces) AFTER this ticket:
  cross-reference, don't collide — land this first or coordinate; the
  catalog description text this ticket pins must not be silently reworded
  by a sibling (the byte pin enforces the coordination). The eval tickets
  (M1-928/929/930, all pending, no in-flight work on this surface per
  STATUS-TICK.md) label against the shipped surface; this ticket's landing
  shifts entity/topic-class expected hits — re-labeling goes through
  M1-928's supersedes discipline, never here.
- P11: Scope fence — no new tool (binding), no semanticSearch change, no
  window-semantics change (commands.md:512-531 explicitly binds "the chat
  agent's post-search tool" to the same window rule as /summary and the
  digest), no commands.md edit, no disclosure-script edit, no M1-916
  guidance-text rewrite (append to the description; the four
  searchToolDescriptionsCarryTemporalRoutingGuidance markers keep
  passing).
- P12: Tokenizer conjoining (the "qwen 27B" stress-test miss) — Postgres
  tokenizes `Qwen3-32B` into lexemes `qwen3`, `32b`; the bare lexeme
  `qwen` NEVER exists in qwen-family titles, so a bare-lexeme
  `plainto_tsquery` built from text=`qwen` matches only a standalone
  "Qwen" and misses the family majority. The filter must be a PREFIX
  tsquery: parse the anchored text into terms (split on non-alphanumeric
  runs, mirroring the tokenizer), sanitize each term to
  `to_tsquery`-safe bytes, length-cap per term, cap the term count, and
  assemble each term T as `T:*`, AND-joined (`qwen:* & 27b:*`) — `qwen:*`
  matches qwen, qwen2, qwen3, qwen2.5. `to_tsquery` syntax is
  operator-bearing (`! & | ( ) :`), so the sanitization is load-bearing
  BOTH ways: a model-supplied `qwen|everything` must not widen into an
  OR, and only code — never the model — emits the `:*`/`&` scaffolding.
- P13: Temporal words in text (the "qwen today" stress-test miss) —
  "today"/"yesterday"/"last 2h" passed as text would demand posts whose
  INDEXED TEXT literally contains the lexeme (the filter matches words,
  not time). Routing rule: temporal expressions go to `window`; `text`
  carries entity/topic terms ONLY. The catalog description carries this
  as an explicit steering sentence — the INTERIM guard; RAG topic 5's
  deterministic temporal parser owns the split mechanically when it lands
  (P10: that sibling rewords via the byte pin, never silently). Model
  disobedience is DEFINED degradation, never an accident: text=`qwen
  today` ANDs `qwen:* & today:*` — over-narrowing (possibly `[]`), the
  window untouched, no error thrown.

## Approach

Derived from `spec_refs:` — security.md §Prompt-injection defenses owns the
closed tool table this amendment records (Inputs column + mechanism
sentence; the M1-927 rides-the-diff precedent); §Rate limiting + §Secrets
handling own the translator-leg enumeration a new anchoring call site must
join; llm.md §Determinism boundary confines the change to a WHERE predicate
(set/order stay SQL-decided and unchanged); commands.md §Content's "What the
window measures" is the constraint the filter composes WITH, never alters.

- **Files to touch:** `files_scope` (two production files, four test files,
  one spec, one design doc).
- **Pre-decided shapes (implementation is execution):**
  1. **Tool** — `SearchPostsTool` constructor gains `QueryAnchorTranslator`
     (CDI). `execute()`: after the existing arg reads, read `text`
     (`String`, present-and-non-blank gate). When present: resolve the
     declared scope language with a private `lookupScopeLanguage` replicating
     SemanticSearchTool's (:160-177) — same SQL, same missing-row 'en'
     default, same degrade-to-'en'-and-log posture — on its own short
     connection; then `anchoredText = queryAnchorTranslator.translate(text,
     scopeLanguage, scopeKind, scopeId)` BEFORE acquiring the main pooled
      connection. `queryPosts` gains the anchored text (or null): append
      `AND p.search_tsv @@ to_tsquery('english', ?) ` after the tag arm,
      `params.add(assembledPrefixQuery)` in the same position — where
      `assembledPrefixQuery` is built by pure code from the ANCHORED text
      (P12): split on non-alphanumeric runs into terms (mirroring the
      tokenizer), lowercase, length-cap each term (e.g. ≤64 chars) and
      the term count (e.g. ≤16, inside the dispatcher's 500-char input
      cap), then emit each term T as `T:*` joined by ` & ` — text=`qwen
      27B` binds the single parameter `qwen:* & 27b:*`. Raw user/model
      text is never interpolated: only sanitized terms plus the
      code-emitted `:*` and ` & ` scaffolding reach the bound value; an
      assembly with zero terms is no filter (P7). The SELECT list, ORDER
      BY, LIMIT, byte-budget loop, and emission are untouched.
   2. **Catalog** — searchPosts `ToolArg` list gains
      `new ToolArg("text", "string", false, "\"qwen\"")` between `window` and
      `limit`; the description APPENDS steering of the shape: "An optional
      text filter narrows results to posts whose title or body mention the
      given English keywords — use it with tags and window to focus on named
      things (companies, products, people). Put time expressions (today,
      yesterday, last 2h) in `window`, never in `text`: `text` carries
      entity/topic terms only." (exact wording lands with the byte-pin
      update; the semantic elements the pin asserts: the param name, the
      keyword-narrowing role, the English expectation, and the
      temporal-to-window routing rule). The routing sentence is the INTERIM
      guard: RAG topic 5's deterministic temporal parser owns the split
      mechanically when it lands (P10/P13); until then this sentence is the
      only mechanism keeping "today" out of `text`.
  3. **Spec amendments (rule-text drafts; §12 — exact wording user-approved
     at implementation; number-free prose):** security.md searchPosts row —
      Inputs gains `text: string` (optional, free text, length-capped); Notes
      gains: "An optional `text` filter narrows the result to posts whose
      English-anchored title or body match the text — a PREFIX match over
      `post.search_tsv`: the text is parsed into sanitized terms, each
      carried as a prefix lexeme (`term:*`, AND-joined) in ONE
      parameter-bound `to_tsquery('english', …)` — raw text is never
      interpolated — so `qwen` matches qwen, qwen2, qwen3 — composed with
      the tag, window, and world predicates (AND). Temporal expressions
      belong to `window`; `text` carries entity/topic terms only. It never
      reorders: the `COALESCE(published_at, fetched_at)` ordering and its
      bound are unchanged, and a blank text is no filter. When the scope
      declares a non-English `/lang`, the text is anchored to the corpus
      language under the same bounded conditions as the `semanticSearch`
      query (an `en` scope is a strict no-op; failure falls back to the raw
      text)."
     §Rate limiting query-anchoring bullet: extend "…plus one per DISTINCT
     model-elected `semanticSearch` query" to "…plus one per DISTINCT
     model-elected `semanticSearch` query or `searchPosts` text filter".
     §Secrets handling query-anchoring bullet: name that the leg also
     carries a model-elected `searchPosts` text filter (the user's search
     text, same class).
   4. **Design sync** — design 05 §5.4.6: the searchPosts description
      paragraph records the text param and the temporal-to-window routing
      sentence; the query-anchoring paragraph records the searchPosts text
      leg anchors identically.
- **Steps, in implementation order:**
  1. Write the reproduction + failure-mode tests RED (the marker in
     `reproduction:` converts at start, workflow §0).
  2. SearchPostsTool change (shape 1); thread the constructor through the
     two direct test constructions (item 7d).
  3. Catalog change (shape 2) + the §8-authorized pin updates (items 7a-c,
     8).
  4. Spec + design amendments (shapes 3-4) with the user's wording
     approval — BEFORE the final verify so the parity test that parses
     security.md reads the amended row (green-log freshness is per-test).
  5. Full `mvn verify`; hand the owner-run live probe and the eval-delta
     reference to the user.
- **Controls to preserve (engineering-rules §10):** the ENTIRE textless
  path stays byte-identical — one pooled connection per call,
  statement_timeout arming + pid registration
  (SearchPostsToolTest:120-153), window clamps [1h,30d], tag validation
  (one SELECT, unknown-tag rejection), the COALESCE ordering, the 16 KiB
  whole-entry byte budget, the result shape {uid, title, url, ready_at,
  tags}; the dispatcher boundary (length caps before SQL, per-turn cache,
  call cap) is untouched; `worldPredicateSql` is shared and its signature
  does not move (M1-621 no-drift rule); the M1-916 steering markers and
  M1-927's dated emission stay intact. Pinning tests: the item-10 suite
  list, all green unmodified except the item-7 authorized edits.
- **Alternatives considered (rejected, recorded for the commit message):**
  (B) English-only param, no anchoring — cheaper (no translator leg, no
  §Rate limiting/§Secrets amendment), but it bakes a silent-miss failure
  into the new param for non-en scopes (a Czech text against an
  English-anchored tsvector matches nothing but loanwords) and breaks the
  D58 anchor discipline the other retrieval leg already follows; the
  system prompt's "tool arguments are always written in English"
  (ChatAgent.java:120-122) is an instruction, not a guarantee — semanticSearch
  anchors despite the same instruction. (C) ts_rank ranking inside the
  window — binding user reject: eval-gated later. (D) A separate
  searchPostsText tool — binding user reject: rides the existing tool.
  (E) websearch_to_tsquery for OR-semantics — plainto_tsquery ANDs its
  lexemes; the param's role is NARROWING ("only posts mentioning qwen"),
  which AND expresses; OR-matching is semanticSearch's job. (F) Bare
  `plainto_tsquery('english', ?)` compose — the minimal diff; rejected on
  the stress test: tokenizer conjoining makes text=`qwen` miss
  "Qwen3-32B" (P12), so prefix lexemes are required.

## Definition of done

The reproduction, the five failure-mode/compose tests, and the three
revision-round tests pass (narrowing within window+tags; out-of-world/non-READY
never surface; non-en scope anchors before matching with the scope-partitioned
call; en issues no translator call; blank text is no filter; `qwen` prefix-
matches a "Qwen3-32B released" title; `qwen 27B` matches "Qwen 27B" and not
"Qwen3-35B"; a temporal word in text degrades as the documented literal-term
filter); the three §8-authorized pin
updates land with their intent stated here; the steering reaches the
instruction table and the wire schema single-sourced; the ORDER BY is
byte-identical and no ts_rank exists in the tool; the textless path and all
isolation suites pass unmodified; the over-cap dispatcher arm passes; the
security.md row (Inputs + mechanism + anchoring), the §Rate limiting and
§Secrets query-anchoring bullets, and design 05 §5.4.6 carry the
user-approved rule text; the diff names exactly files_scope; the owner-run
live probe and eval-delta reference are handed over with their record
obligation; mvn verify is green from the repo root.

## Verification

- P1 → acceptance items 1 and 7: the reviewer diffs the four authorized
  test edits against item 7's plain-language authorization (§8); a fifth
  touched test fails the item-10 fence probe.
- P2 → item 9's grep probes (a mutation adding any ordering term, or a
  SELECT-list change, moves the hit set or the ORDER BY line and fails).
- P3 → item 3's leak feed (an over-fetch-then-filter shape admits the
  unsubscribed/non-READY keyword posts and fails the assertion).
- P4 → items 4 and 5: the anchoring test's discriminator (only the
  TRANSLATED string is seeded) fails a skip-the-translation mutation; the
  en test fails any translator invocation; the one-connection pin
  (pre-existing, item 10) fails a lookup that runs on textless calls; the
  before-the-pool-connection ordering is enforced by the pre-existing pool
  discipline — reviewer diff check that translate() precedes
  dataSource.getConnection() for the main query.
- P5 → item 12's grep probes on security.md (the searchPosts row carries
  to_tsquery, the semanticSearch row keeps plainto_tsquery; §Rate limiting
  names the searchPosts text leg).
- P6 → item 1's positive match plus reviewer inspection: the SQL text pins
  'english' and binds the ASSEMBLED prefix-query string as one parameter
  (the V58/SemanticSearchTool rationale; a 1-arg form is reviewable at a
  glance).
- P7 → item 6 (blank = no filter) and item 11 (over-cap text rejected
  before SQL, typed, self-correctable).
- P8 → item 8's single-source assertions (a second copy of the steering
  text, or a table-only edit missing the wire, fails); the token ledger is
  stated in P8 and carried to brief-01's lane, not unit-verified.
- P9 → item 7's byte-pin line contains `"text"` and not a `query` arg on
  searchPosts; the recallMemory negative pin keeps passing.
- P10 → the byte pin itself enforces sibling coordination (any later
  reword fails it until authorized); the eval cross-reference is carried in
  the Context and item 14 as owner-run, not CI.
- P11 → item 10's git-diff fence and the M1-916 marker assertions inside
  item 8's unchanged tests.
- P12 → items 16 and 17: the prefix arm fails a bare-lexeme mutation (no
  `:*` — only the standalone-"Qwen" post returns), the version arm fails
  an OR-widened or term-dropping assembly; reviewer inspection pins the
  match as `to_tsquery('english', ?)` with the assembled string bound
  (item 9's grep set includes to_tsquery).
- P13 → item 18's defined-degradation assertions plus item 8's steering
  pin: the routing rule is documented, and the window — never the text —
  governs time.
- FAILURE-MODE coverage beyond the reproduction → items 3 (leak), 4
  (skip-translation mutation), 5 (stray translator call), 6 (blank), 11
  (over-cap), 16-17 (prefix/sanitization mutations) — each feeds
  hostile/edge input to this diff's own production code and asserts the
  protected behavior.
- acceptance items 12-15 → the named greps, the design-doc grep, the
  owner-run probe protocol, mvn verify; items 16-18 → the three named
  SearchPostsToolTest methods, RED first (workflow §0).

## Out-of-scope

Named in `out_of_scope`: any ranking change (binding user decision — the
COALESCE ordering and its NULLs-first security bound are untouched); any new
tool and any semanticSearch change; any window-semantics change (ready_at
binding preserved; /summary and digest untouched); the disclosure shell
scripts and SETUP_GUIDE.md (verified data-class level — the new leg is
recorded in the spec sections those texts reference); golden-set
re-labeling and the eval re-run (M1-928/M1-930's contracts; the delta is
this ticket's owner-run acceptance reference); RAG topics 5 and 6
(cross-referenced in P10/P13); any docs/spec/commands.md edit. This ticket
modifies EXACTLY four pre-existing tests, each §8-authorized in acceptance
item 7 (plus the dispatcher-test arm of item 11) with the new expected
behavior stated in plain language; every other pre-existing suite must pass
unmodified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-932-searchposts-text-filter.md
```

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-932-r1.txt):

1. Finding 1: add the wholly-sanitized-away arm to
   SearchPostsToolTest.blankTextBehavesAsNoFilter (text="?!" must return the
   same uid set as no-text on the same fixture), evaluated via the round-2 run
   of SearchPostsToolTest with that assertion green plus the mutation probe
   (deleting SearchPostsTool.java:263-265 must fail it).
