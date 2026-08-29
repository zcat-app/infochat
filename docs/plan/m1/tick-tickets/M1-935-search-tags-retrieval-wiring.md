---
id: M1-935
title: "searchPosts topics filter over search_tags"
status: done
created: 2026-08-26
last_updated: 2026-08-30
flow: tick
reproduction: >-
  SearchPostsToolTest#topicsFilterNarrowsWithinWindowToPostsMentioningTheTopic
  (marker converted 2026-08-30: test written and run RED on unmodified main
  25ec1382 — `topics=["czech"]` answered with the UNFILTERED window set
  (all four seeded posts returned); RED log captured at
  /tmp/m1-935-red.log, 3 failures: the reproduction plus
  prefixAndContainmentSemantics and likeMetacharacterValuesNeverWiden;
  the leak and blank-semantics arms pass pre-implementation by
  construction and discriminate mutations after).
  The wrong behavior it states: a searchPosts call carrying a "topics"
  argument is answered with the UNFILTERED window set. Verified in-tree
  today: SearchPostsTool.execute() reads exactly three args — tags,
  window, limit (SearchPostsTool.java:66-71; the file read end to end
  consults no other arg key and no reference to search_tags exists in
  it); the spec input contract matches (security.md:328 searchPosts
  Inputs column: tags/window/limit); the catalog advertises the same
  three (ChatToolCatalog.java:32-40). Live observation motivating the
  change (user, 2026-08-26, brief-carried): "what happened in Czech
  today" returns 1 random post while tens fetched — the tree bottoms
  out at continents (V84 seed: no country leaf) and no chat surface can
  express "posts tagged czechia". The motivating discrimination: the
  test seeds four in-world READY posts inside the window (two carrying
  search_tags ['czechia', …], two carrying none) and a call with
  topics=["czech"] returns EXACTLY the two — the prefix value "czech"
  hitting the canonical "czechia" is the tolerant-matching property the
  bounded tags param cannot express.
analysis_ref: docs/plan/m1/tick-analysis/category-tag-split.md
blocked_by:
  - M1-932
  - M1-934
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
    ANY ranking change — filter-only, the M1-932 binding posture carried
    over: the ORDER BY (COALESCE(published_at, fetched_at) DESC, id DESC,
    security.md:328) and the 16 KiB byte budget are untouched; relevance
    ordering stays a separate eval-gated decision.
  - >-
    THE TAGS PARAM — validateTagsKnown and TagTreeExpansion stay the
    category-tree path exactly as M1-867 landed them; topics values are
    deliberately NOT vocabulary-checked (free tags have no registry —
    analysis O2) and get NO tree expansion.
  - >-
    semanticSearch, getPost, getReferences, and the D28/D58 pre-fetch —
    untouched; "the pre-fetch may gain search_tags as a boost/hint" is an
    eval-gated decision (brief), not this ticket (analysis Chosen approach
    sub-choice; P20's posture).
  - >-
    ANY new tool — the filter rides the existing searchPosts tool
    (M1-932's binding reject of a sibling tool applies verbatim).
  - >-
    A TRANSLATOR/ANCHORING LEG for topics values — rejected: values are
    normalized per the tag rule and non-normalizable (non-ASCII) values
    are DROPPED (documented degradation mirroring the tagger's own
    partial-valid handling of free tags); no new call site, so NO
    §Rate limiting/§Secrets enumeration amendment (contrast M1-932's
    text leg, which anchors because free text degrades silently-miss —
    a dropped ARRAY ELEMENT degrades to absent-filter, an honest,
    spec-recorded shape). The system prompt's tool-args-in-English
    instruction (ChatAgent.java:120-122) is the first line; the write
    side canonicalizes to English (D29) at ingest.
  - >-
    THE DIGEST FOOTER and /topic — M1-936; different surfaces, no shared
    files beyond the column itself.
  - >-
    Golden-set re-labeling and the eval re-run — M1-928/930 contracts;
    the entity/topic-class delta this ticket enables is owner-run
    reference, never edited here (note: M1-929's runner executes the
    semanticSearch fused path only and never dispatches searchPosts — a
    topics-filter delta requires the eval lane's own extension, the
    M1-938 posture).
acceptance:
  - "REPRODUCTION closed: SearchPostsToolTest.topicsFilterNarrowsWithinWindowToPostsMentioningTheTopic — the reproduction fixture above; topics=[\"czech\"] returns EXACTLY the two search_tags-carrying uids, and the companion arm with no topics arg returns all four (the compose discriminator: a mutation dropping the predicate fails the first arm, a mutation filtering unconditionally fails the second)."
  - "Tolerant matching semantics (analysis P10/P13): match = a canonical PREFIX relation — post qualifies iff ANY element of p.search_tags EQUALS the normalized value OR STARTS WITH it, implemented as EXISTS (SELECT 1 FROM unnest(p.search_tags) AS t WHERE t LIKE ? ESCAPE '\\') per value, OR-joined across the (≤ list cap) requested values; each bound parameter is the normalized value with %, _ and the escape character escaped, plus the code-appended trailing % — pinned by SearchPostsToolTest.prefixAndContainmentSemantics (seeds czechia, czech-republic, qwen3 posts; topics=[\"czech\"] returns both czech* posts and NOT qwen3)."
  - "FAILURE-MODE (injection, analysis P10): SearchPostsToolTest.likeMetacharacterValuesNeverWiden — topics=[\"qw%n\", \"a_b\"] (with literal metacharacters) matches NOTHING rather than everything; a mutation that skips the escaping fails this test (the M1-932 P12 sanitization discipline, re-derived for LIKE)."
  - "FAILURE-MODE (isolation, M1-589 leak class): SearchPostsToolTest.topicsFilterNeverSurfacesOutOfWorldOrNonReadyPosts — an UNSUBSCRIBED custom source's topic-matching post and a subscribed source's non-READY topic-matching post never appear; the topics predicate composes AND inside the ONE statement with READY + D59 world + ready_at window (+ tags/text when present), never as a post-filter over an over-fetched set."
  - "Normalization and empty semantics: values run through TagNormalizer at read (the same single pipeline, commands.md §Surface conventions); unnormalizable values (spaces, non-ASCII, >48 chars) are dropped; an absent, empty, or all-dropped topics argument means NO filter — pinned by SearchPostsToolTest.blankAndUnnormalizableTopicsBehaveAsNoFilter (the M1-932 blank-text posture)."
  - "Dispatcher boundary already bounds the param (security.md:313-322 'All free-form string and list inputs … length-bounded'): ChatToolDispatcherTest.rejectsOversizedInput gains a searchPosts/topics arm — an over-cap list and an over-cap single value both return a typed ValidationError BEFORE any SQL executes; no new validation code is written in the tool (the M1-932 item-11 shape)."
  - "Steering lands on BOTH model surfaces, single-sourced: ChatToolCatalogTest.searchPostsDescriptionDocumentsTheTopicsFilter passes — the searchPosts description names the topics param, its specific-named-things role (countries, companies, projects, coins), the prefix tolerance (\"czech\" matches \"czechia\"), and the division of labor vs tags (tags = the bounded category tree; topics = specific free tags); everyCatalogArgsShapeMatchesToolParsing keeps passing with topics:array added; parametersRenderAsValidJsonSchema keeps passing (the wire schema derives from the same ToolArg record, M1-872 single-source)."
  - "§8-AUTHORIZED pre-existing-test modifications (engineering-rules §8; plain language, the M1-932 item-7 pattern against the post-M1-932 catalog state): (a) ChatAgentTest.renderedInstructionTableIsByteIdentical — ONLY the searchPosts expected line changes, gaining the topics example arg and the appended description sentence verbatim from the landed catalog; every other line stays byte-identical; (b) ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing — searchPosts expected shapes gain topics:array; (c) ChatAgentTest.toolInstructionsMatchSearchPostsParams — gains the topics assertion. No SearchPostsTool constructor change (normalization is static; no new collaborator), so no constructor-threading edit."
  - "Filter-only discipline (D19): grep -n 'ORDER BY' over SearchPostsTool.java returns exactly the pre-existing COALESCE line; the unnest/LIKE tokens appear ONLY inside the WHERE composition — probe mirrors M1-932's item 9."
  - "Spec amendment rides the diff (engineering-rules §12; wording user-approved at implementation; rides-the-diff shape per the M1-932 item-12 precedent): docs/spec/security.md searchPosts row — Inputs column gains `topics: list<string>` (optional free search-tag values, length-capped); Notes gains the mechanism sentence (values normalized per the tag rule and non-normalizable values dropped — absent or all-dropped means no filter; prefix match over post.search_tags via a parameter-bound LIKE with code-escaped metacharacters and a code-appended wildcard — raw values never interpolated; composes AND with tag/text/window/world; never reorders) — probes: grep -n 'topics' docs/spec/security.md returns the searchPosts row; grep -n 'search_tags' docs/spec/security.md returns the mechanism sentence; the semanticSearch row is untouched."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 synced: the searchPosts catalog-description paragraph records the topics param and its tags-vs-topics division of labor — probe: grep -n 'topics' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mention."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
      — topicsFilterNarrowsWithinWindowToPostsMentioningTheTopic (the
      reproduction), prefixAndContainmentSemantics,
      likeMetacharacterValuesNeverWiden,
      topicsFilterNeverSurfacesOutOfWorldOrNonReadyPosts,
      blankAndUnnormalizableTopicsBehaveAsNoFilter.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java
      — searchPostsDescriptionDocumentsTheTopicsFilter.
  modifies:
    - >-
      ChatAgentTest.renderedInstructionTableIsByteIdentical — the searchPosts
      line only (§8-authorized, item 8a).
    - >-
      ChatAgentTest.toolInstructionsMatchSearchPostsParams — adds the topics
      assertion (§8-authorized, item 8c).
    - >-
      ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing — searchPosts
      gains topics:array (§8-authorized, item 8b).
    - >-
      ChatToolDispatcherTest.rejectsOversizedInput — gains the
      searchPosts/topics arm (item 6).
  preserves:
    - >-
      all tests currently green on main — explicitly
      SearchPostsToolTopExpansionIT, RetrievalWorldPredicateIT,
      SearchPostsToolClockTest, StopToolQueryCancellationIT,
      ChatToolRegistryTest, ChatToolAllowlistSpecParityTest (names-only
      check), and SearchPostsToolTest's pre-existing tests, unmodified.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Surface conventions
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D29
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
    date: 2026-08-30
    verdict: REWORK
    checks: "SPEC-TRUTHNESS: FAIL; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "10 files, +393/-22 (2 prod, 4 test, 2 docs, ticket+board)"
    notes: >-
      One low FINDING 1: TOPIC_VALUE_PATTERN ^[a-z0-9%_-]{1,48}$ drops a
      fourth unstated category (ASCII punctuation like at&t, c++, s&p500)
      that the approved security.md sentence says survives — all-dropped
      degrades to no filter, returning the unfiltered window for exactly
      the company/project names the catalog steers into topics. Fix:
      printable-whitespace-free-ASCII class per the approved sentence,
      plus the discriminating test leg. Gate agent run
      agent_aae3de26-824e-4265-a065-bc91c2e326fd; verdict at
      .scratch/tick-review-M1-935-r1.txt.
  - round: 2
    date: 2026-08-30
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "r2 fix hunks: 3 files, +70/-8 (pattern + comment, new punctuation test leg, ticket bookkeeping)"
    notes: >-
      REWORK item 1 SATISFIED (disposition in verdict). Gate agent run
      agent_7ecdc4a6-55c5-4da2-bf34-63f880aa602e; verdict at
      .scratch/tick-review-M1-935-r2.txt. Full-suite log r2 green
      (scripts/verify-serialized.sh, BUILD SUCCESS).
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-08-30: all file:line citations re-verified on main 25ec1382
  post-M1-932/934/936. The draft-time "exactly three args" claims
  (SearchPostsTool.java:66-71, ChatToolCatalog.java:32-40,
  security.md Inputs column) drifted to four with M1-932's text param
  exactly as P12 anticipated — the §8-authorized pin updates are
  calibrated to the POST-932 state as the ticket requires. ChatAgent
  tool-args-in-English instruction now at ChatAgent.java:129 (draft
  cited :120-122; M1-939/941 shifted lines). Census re-run: 16 paths
  grep "searchPosts" over provider tests; the 6 beyond the table
  (ChatAgentPromptExceededTest, ChatAgentProvenanceTest,
  ChatAgentRefusalInterceptTest, ChatAgentReplyModeTest,
  ChatAgentToolArgsTest, SemanticSearchToolIT + 2 messaging fixtures)
  verified dispatch-fixture-only — none asserts the arg set or
  instruction bytes. blocked_by test trace: M1-932's nine
  text-filter tests in SearchPostsToolTest never pass topics and the
  topicless SQL path stays byte-identical; M1-934's tests live in
  collector/digest surfaces this ticket never touches.
  ChatToolAllowlistSpecParityTest is names-only set equality — the
  security.md row amendment keeps it green. Route note (execution,
  not drift): the read-side normalization shares the tag rule's NFC +
  Locale.ROOT lowercase steps but NOT the strict ^[a-z0-9-] class —
  the spec row's drop set (acceptance 5: spaces, non-ASCII, >48
  chars) keeps % and _ flowing as literals so the LIKE escaping is
  the load-bearing widening control (acceptance 3's mutation-catching
  requirement; a strict-class drop would make
  likeMetacharacterValuesNeverWiden vacuous, §8 non-vacuity). The
  escaping mirrors EligiblePostQuery.escapeLike (the M1-936 /topic
  discipline, P10). Analysis pitfalls P9/P10/P12/P13/P19 all present;
  P20 posture carried in out_of_scope. No blocking ambiguity.
escalation_reason:
---

# M1-935: searchPosts topics filter over search_tags

## Context

M1-934 lands `post.search_tags` and populates it (new posts + the
sweep-borne backfill); nothing reads it. The chat agent still cannot
express "posts about czechia" — the tags param is bounded-tree-only
(security.md:328) and the motivating Czech query keeps returning noise.
This ticket adds the retrieval half: a `topics` argument on the
EXISTING searchPosts tool, prefix-tolerant over the free tags, composing
with M1-932's text filter (landing order: AFTER M1-932 — both re-pin the
same byte-pinned instruction table; binding brief note). Shared
context: `analysis_ref:` (analysis doc, Pitfalls P9-P13).

## Root cause

Verified: `SearchPostsTool.execute()` consults exactly tags/window/limit
(SearchPostsTool.java:66-71) and the file contains no search_tags
reference (read end to end); the spec row (security.md:328) and the
catalog (ChatToolCatalog.java:32-40) advertise the same three args. The
column M1-934 adds has no reader on the chat path — a missing parameter,
not a broken one.

## Pitfalls

- P9: the predicate must compose AND inside the ONE statement with
  READY + D59 world + window (+ tags/text) — a fetch-then-filter shape
  is the M1-589 leak class; `worldPredicateSql` signature does not move
  (M1-621 no-drift rule).
- P10: LIKE metacharacters — model-supplied values escape `%`, `_`, the
  escape char; only code appends the wildcard (`qw%n` must not
  widen); bound as parameters, never interpolated.
- P12: byte-pin collision with M1-932 — blocked_by M1-932; the pin
  updates authorize against the POST-932 catalog state; the added
  catalog bytes (~20-30 tokens) cost every turn (the M1-916
  prompt-byte-ledger precedent, weighed and accepted).
- P13: canonicalization drift bridged at read — prefix match ("czech" →
  "czechia", "czech-republic"); write-side canonicalization is
  M1-934's; the golden-set drift check is the eval lane, not CI.
- P19: fixtures seed search_tags directly (END state) — never a shape a
  sibling removes; the compose arms carry the M1-932 text param only if
  it has landed (test independence: compose with tags+window+world is
  sufficient; the text compose is asserted by M1-932's own suite).

## Approach

- **Files to touch:** `SearchPostsTool` (arg read + predicate),
  `ChatToolCatalog` (arg + steering sentence), the two pin-test files +
  dispatcher arm, security.md row, design note.
- **Pre-decided shapes:**
  1. **Tool** — `execute()` reads `topics` (List<String>,
     present-and-non-empty gate after normalization+filtering; absent or
     all-dropped = no filter). `queryPosts` appends after the tag arm:
     one `AND (EXISTS (SELECT 1 FROM unnest(p.search_tags) AS t WHERE
     t LIKE ? ESCAPE '\') OR …)` per requested value, each parameter
     the escaped normalized value with the trailing `%` appended by
     code. SELECT list, ORDER BY, LIMIT, byte budget, emission
     untouched. No constructor change.
  2. **Catalog** — searchPosts ToolArg list gains
     `new ToolArg("topics", "array", false, "[\"czechia\"]")`;
     description APPENDS: "An optional topics filter narrows results to
     posts carrying specific free tags (countries, companies, projects,
     coins) — prefix-matched, so \"czech\" matches \"czechia\". Use tags
     for the bounded category tree and topics for specific named
     things." (exact wording lands with the byte-pin update; the pin
     asserts the param name, the role, the prefix tolerance, and the
     tags-vs-topics division).
  3. **Spec** — security.md searchPosts row per acceptance item 9
     (Inputs + mechanism sentence; rule text only).
- **Steps:** RED tests first (reproduction + the four failure/compose
  tests) → tool change → catalog change + the three §8-authorized pin
  updates + dispatcher arm → spec/design with user wording approval →
  full verify.
- **Controls to preserve (engineering-rules §10):** the textless AND
  topicless path stays byte-identical (one pooled connection,
  statement_timeout arming + pid registration, window clamps, tag
  validation, COALESCE ordering, 16 KiB budget, result shape); the
  dispatcher boundary (length caps before SQL, per-turn cache, call
  cap) untouched; D59 world predicate shared and unmoved; M1-916/M1-927
  steering markers and dated emission intact.
- **Pitfall→mitigation:** P9→the leak test + predicate-inside-statement
  shape; P10→the metacharacter test + code-only wildcard; P12→blocked_by
  + the authorized pin updates; P13→the prefix test; P19→fixture
  discipline in test_plan.

## Definition of done

The reproduction and the four failure-mode/compose tests pass (prefix
containment; metacharacter values never widen; out-of-world/non-READY
never surface; blank/unnormalizable = no filter); the dispatcher
over-cap arm passes; the steering reaches instruction table and wire
schema single-sourced; the ORDER BY grep probe holds; the security.md
row carries the user-approved mechanism text; the design note synced;
the three §8-authorized pin updates land with intent stated; mvn verify
green.

## Verification

- Reproduction → acceptance 1.
- P10 → acceptance 2 (the prefix arm) and acceptance 3 (failure mode:
  LIKE-metacharacter values must not widen — the escaping mutation
  fails the arm).
- P9 → acceptance 4 (failure mode: out-of-world and non-READY leak
  feeds never surface — an over-fetch-then-filter mutation fails it).
- P13 → acceptance 2's czech/qwen discrimination.
- P19 → the reproduction and compose fixtures seed search_tags directly
  on the posts (the post-V87 END state, never via tag_candidates or a
  pre-column shape); reviewer diff check over SearchPostsToolTest's
  seeded rows.
- Empty semantics → acceptance 5 (blank/unnormalizable values behave as
  no filter — defined degradation, never an error).
- Dispatcher → acceptance 6.
- P12/steering → acceptances 7 and 8 (single-source assertions; the
  git-diff fence shows no test touched beyond the authorized set).
- D19 → acceptance 9's grep probes.
- Spec/design → acceptance 10's greps + the user-approval record.
- acceptance 12 → mvn verify exit 0.

## Out-of-scope

Named in `out_of_scope:` — no ranking change; tags param and tree
expansion untouched (M1-867's surface); semanticSearch/getPost/
getReferences/pre-fetch untouched (boost/hint is eval-gated, not free);
no new tool; NO translator leg for topics (dropped-element degradation
recorded in the spec row — the rejected anchoring alternative and its
reason live in the analysis); M1-936's surfaces untouched; eval
contracts untouched. This ticket modifies EXACTLY the three §8-authorized
pin surfaces plus the dispatcher-test arm, each with the new expected
behavior stated in plain language.

## Census

Class-scoped (the searchPosts arg-surface pin set — the M1-932 P1
census, re-run against the post-932 state). Mechanical enumeration:
`grep -rn "searchPosts" infochat-provider/src/test/java` — every site
asserting the arg set or rendered instruction bytes gets a row:

| Site | Disposition |
|---|---|
| ChatAgentTest.renderedInstructionTableIsByteIdentical | **Authorized edit** — searchPosts line gains topics (item 8a) |
| ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing | **Authorized edit** — gains topics:array (item 8b) |
| ChatAgentTest.toolInstructionsMatchSearchPostsParams | **Authorized edit** — gains the topics assertion (item 8c) |
| ChatToolDispatcherTest.rejectsOversizedInput | **Authorized edit** — gains the topics arm (item 6) |
| SearchPostsToolTest pre-existing tests | **Unchanged** — textless+topicless path byte-identical |
| SearchPostsToolTopExpansionIT / RetrievalWorldPredicateIT / SearchPostsToolClockTest / StopToolQueryCancellationIT | **Unchanged** — no top-expansion/world/clock/stop semantics touched |
| ChatToolRegistryTest / ChatToolAllowlistSpecParityTest | **Unchanged** — names-only checks; no tool added |

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-935-r1.txt):

1. FINDING 1: change SearchPostsTool's topics drop rule (TOPIC_VALUE_PATTERN
   at SearchPostsTool.java:63-64 plus the comment at :60-62) to reject only
   whitespace-containing, non-ASCII, or over-48-character values as the
   approved security.md sentence states, keeping %, _ and - flowing to the
   escaper; evaluated via the new
   SearchPostsToolTest.punctuationCarryingTopicValuesFilterRatherThanDegrade
   leg (topics=["at&t","s&p500"] → Set.of() while the no-topics call on the
   same fixture returns the full set), with
   blankAndUnnormalizableTopicsBehaveAsNoFilter and
   likeMetacharacterValuesNeverWiden green and unmodified.

## Review observations

- r1 RECOMMENDED-NEW-TICKET (driver-dispositioned, recorded; filing is the
  user's call): the repo now carries two identical private LIKE escapers —
  SearchPostsTool.escapeLike (this diff) and EligiblePostQuery.escapeLike
  (M1-936); a third prefix reader would copy them again and the copies can
  drift (one gains a metacharacter fix the other misses). Candidate: one
  shared helper in app.zcat.infochat.core.util beside TagNormalizer, both
  call sites switched, existing LIKE tests unchanged. TOUCHED-BY-THIS-DIFF:
  yes; no DECIDE-BEFORE ordering constraint.
