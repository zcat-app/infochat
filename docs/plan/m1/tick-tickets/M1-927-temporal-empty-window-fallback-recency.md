---
id: M1-927
title: "Date and honestly frame the chat semantic grounding set"
status: done
created: 2026-08-24
last_updated: 2026-08-24
flow: tick
reproduction: >-
  SemanticSearchToolIT.entriesCarryReadyAtSoTheModelCanDateWhatItServes
  and
  ChatAgentTest.preFetchBlockDisclosesItIsNotTimeFiltered (both written
  at start and run RED against the unmodified code before any fix code) —
  both state today's wrong behavior: (1) a seeded
  semanticSearch result emits entries of exactly {uid, title, url,
  similarity} with NO date field (verified in-tree: the fused SELECT is
  `SELECT uid, title, url, distance` at SemanticSearchTool.java:225 and
  the emission loop at :314-318 appends uid/title/url/similarity only —
  grep -n 'ready_at\|published_at\|fetched_at' over the file returns no
  hit); (2) the deterministic pre-fetch folds that JSON under the header
  "Posts from the user's subscribed feed semantically related to their
  message:" (ChatAgent.java:896-900) — no time qualifier anywhere.
  Probe-with-observed-output evidence grounding both (TEST instance,
  rootful, 2026-08-24 16:19:08Z, leg V4 of the M1-916 live verification;
  admin chat-DB verbatim capture, test-clients/admin/simplex_v1_chat.db,
  gitignored on the TEST checkout, instance stopped — output evidence,
  not re-derivable in-tree): DM "What happened in tech news in the last
  2 hours? Give me the highlights." → the delivered reply first states
  "I couldn't find any specific news posts from the last 2 hours in your
  feed." (TRUE per DB: searchPosts-window posts matching the cited
  topics = 0 rows), then continues "However, here are some recent
  highlights from your subscribed topics:" and serves as its first
  example an aisearch.substack.com post DB-verified published_at
  2026-08-17 19:39 — a week old — as "recent". The undated, unwindowed
  grounding set was relabeled with the recency the user asked about.
  Verbatim reply + per-row DB queries live in the session record.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolDiversityIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolHybridIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    searchPosts in ANY form — window clamps, ready_at filter, COALESCE
    ordering, emission, and the bare `[]` empty-result shape are correct
    as shipped (SearchPostsTool.java:73-74,:150,:165,:185-186,:199; the
    spec row at docs/spec/security.md:328). The observed reply's FIRST
    half (the truthful empty-window statement) is the model reading that
    tool correctly; this ticket changes what the OTHER grounding surface
    discloses, not the window tool.
  - >-
    The M1-916 catalog description strings — byte-pinned by
    ChatAgentTest.renderedInstructionTableIsByteIdentical and
    ChatToolCatalogTest's guidance pins; no routing text moves.
  - >-
    A recency component or recency ordering in semanticSearch —
    owner-rejected 2026-08-23 (M1-917 out_of_scope: "topical search
    keeps similarity order"). Not reopened here; this ticket adds a DATE
    FIELD, never a time filter or time ordering.
  - >-
    Deterministic temporal-intent classification (suppressing the
    pre-fetch, or forcing searchPosts, on temporal-shaped turns) — the
    exact surface the M1-916 analysis rejected as Option C; the
    deterministic pre-fetch stays the D28 always-runs pattern.
  - >-
    A deterministic empty-window bundle reply or directive replacing
    model prose when a windowed search comes back empty — the model
    already states the empty window truthfully; replacing general-
    assistant prose on the tool-loop surface is a design change, not a
    defect fix.
  - >-
    getReferences emission dating — same defect CLASS, but model-
    initiated-only (never pre-fetch) and unobserved in the V4 evidence;
    disposed in the Census below as defer. getPost, listSaves,
    recallMemory already emit their date fields.
  - >-
    The context-budget ladder and any compaction work (brief 01's lane);
    the header bytes ride inside the semantic block, which the ladder
    already drops whole.
  - >-
    The pre-existing design-05 drift (the §5.4.6 quoted prompt block at
    docs/design/05-llm-and-embeddings.md:524-541 quotes rules the code
    template lacks — "Tool failures are not catastrophic — fall back to
    summarizing what you have" appears nowhere in
    ChatPromptBuilder.CHAT_SYSTEM_PROMPT_TEMPLATE, verified by grep) —
    NOTED here, fixed only for the paragraphs this ticket owns; the
    wholesale reconciliation is its own doc change.
acceptance:
  - "REPRODUCTION closed (dating half): SemanticSearchToolIT.entriesCarryReadyAtSoTheModelCanDateWhatItServes passes — seeds two READY subscribed posts with DISTINCT ready_at instants at known embedding angles, executes the tool, and asserts EVERY returned entry carries a \"ready_at\" whose value equals that post's seeded instant, positioned between \"url\" and \"similarity\". RED pre-change: no ready_at key exists in the emission (SemanticSearchTool.java:314-318). Non-vacuity: a mutation dropping the emitted field, or emitting it only on the semantic-arm shape, fails the per-entry equality assertion (analysis P1/P6)."
  - "REPRODUCTION closed (framing half): ChatAgentTest.preFetchBlockDisclosesItIsNotTimeFiltered passes — with a canned non-empty semanticSearch Success (entries carrying ready_at), asserts the FIRST-call user prompt (llmProvider.lastUserPrompt — BOUNDARY SITING: the last surface our code controls before the model) carries the pre-fetch header with the honesty qualifier quoted verbatim in the Approach (matched by topic similarity, not filtered by time, ready_at says when each post became readable), AND that the tool's JSON bytes are folded verbatim inside the UNTRUSTED_CONTENT wrapper. RED pre-change: the header at ChatAgent.java:896-897 has no time qualifier. Non-vacuity: a mutation reverting the header, or moving the qualifier out of the folded block, fails it (analysis P4)."
  - "§8-AUTHORIZED pre-existing-test modifications (engineering-rules §8; this ticket authorizes exactly these, in plain language): (a) SemanticSearchToolIT.resultCarriesDisplayFieldsAndNeverTheRawVector — the exact-shape regex (:260-263) gains a ready_at group between url and similarity; the assertion's intent becomes \"each entry must carry exactly uid/title/url/ready_at/similarity\"; the raw-vector assertFalse (:264-265) is UNCHANGED; (b) SemanticSearchToolDiversityIT.fittingWindowRendersThePreChangeFusedOrder — the golden expected JSON (:340-370) entries gain their seeded ready_at values (derived from the fixture's seed instants, never free-typed constants); (c) SemanticSearchToolHybridIT's lexical-only arm (the \"similarity\":null assertion at :209) additionally asserts the lexical-only entry carries ready_at — similarity null, date present."
  - "FAILURE-MODE (lexical-only edge, analysis P6): the HybridIT lexical-only arm from item 3(c) feeds a post with NO post_embedding row and asserts its entry renders \"similarity\":null AND a non-null ready_at matching the seeded READY-transition instant — a mutation that dates only semantic-arm rows (or that skips ready_at when distance is null) fails it."
  - "D19 determinism preserved (analysis P2): SemanticSearchToolDiversityIT.fusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb and SemanticSearchToolHybridIT.fusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb pass — same DB state, two calls, byte-identical; and the added column never becomes a temporal term — probe: grep -n 'ready_at' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java returns hits ONLY in the arm SELECT lists, the fused COALESCE, and the emission entry — never in an ORDER BY, WHERE, or RRF term."
  - "M1-917 §10 carry (analysis P3): SemanticSearchToolDiversityIT.windowAtTheNewDefaultStaysUnderTheByteBudget passes with the larger dated entries — the ledger records +~26 bytes/entry (ISO-8601 ready_at), worst case 16 × ~426 B ≈ 6.8 KB, still far under the 16 KiB MAX_RESULT_BYTES; order-preserving WHOLE-entry truncation unchanged, so an entry dropped by budget drops WITH its date — a date never appears without its post, and a post never without its date."
  - "M1-917 §10 carry (isolation path): RetrievalWorldPredicateIT, SemanticSearchToolIT's isolation arms, and SemanticSearchToolHybridIT.lexicalAndFusedPathNeverSurfaceUnsubscribedOrNonReadyPosts pass UNCHANGED — the arms' READY + D59 predicates, the distance threshold, RRF_K, perSourceCap, iterative scan, cancellation arming, and query anchoring are untouched (reviewer diff check: the SemanticSearchTool hunk is confined to SELECT lists, the fused COALESCE, and the emission entry)."
  - "Pre-fetch mechanics unchanged: ChatAgentTest's pre-fetch pins — the exactly-once dispatch pin (\"the turn must always dispatch semanticSearch (M1-589)\", :787-792 area), the query-truncation pin (SEMANTIC_QUERY_MAX_CHARS, :1050-1052 area), the identical-call cache pin (:918-946 area), and the header-absence pins (:821 empty-pre-fetch, :2190 breaker-open — \"Posts from the user's subscribed feed\" never appears when the block was not folded) — pass UNMODIFIED under mvn verify — probe: git diff names no ChatAgentTest.java hunk outside the added framing-half reproduction test; ChatPromptBudgetTest is untouched (its SEMANTIC_POSTS_BLOCK fixture at :68-80 is self-contained — it feeds build() directly and does not consume ChatAgent's header; it becomes a stale echo per the M1-916 OpenAiCompatibleProviderToolCallTest precedent and is left alone, engineering-rules §1)."
  - "Spec amendment rides the diff (analysis P9; engineering-rules §12 — the exact wording goes to the user for approval at implementation; rule-text draft in the Approach): docs/spec/security.md §Prompt-injection defenses semanticSearch row's Output column becomes list of {uid, title, url, ready_at, similarity} (similarity null for lexical-only rows) and the row gains the dated-emission clause — probe: grep -n 'url, ready_at, similarity' docs/spec/security.md returns the semanticSearch row."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 synced: the emission-shape sentences (\"the result carries uid/title/url/similarity\", :611 and :662-663 area) gain ready_at; the pre-fetch fold paragraph (:600-602 area) gains the header-honesty sentence (similarity-matched, not time-filtered, ready_at is the readable-since date); the byte-ledger paragraph (:674-682) gains the per-entry date cost — probe: grep -n 'ready_at' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mentions."
  - "Scope fence (analysis P10): git diff --stat names exactly the files_scope paths — no searchPosts/dispatcher/registry/catalog path, no bundle resource, no docs/spec path other than security.md; the catalog strings are byte-identical — probe: grep -c 'Use this for questions about recent' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolCatalog.java returns 1."
  - "OWNER-RUN live re-probe (verification ceiling, analysis P5 — no unit test can prove the model stops mislabeling; phrased owner-run per the M1-916 acceptance-item-6 posture): after landing, the owner re-asks the V4 temporal question ('What happened in tech news in the last 2 hours?') on the test stack and records the reply: when the window is empty and off-window posts are still served, the reply must present their dates or ages (or not present them at all) — a reply labeling undated week-old posts as recent FAILS the probe; the outcome is recorded against this ticket, never claimed as a unit result."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
      — entriesCarryReadyAtSoTheModelCanDateWhatItServes (the dating-half
      reproduction; per-entry ready_at equality against seeded instants).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      — preFetchBlockDisclosesItIsNotTimeFiltered (the framing-half
      reproduction; asserts the qualified header AND the verbatim dated
      JSON fold on the first-call user prompt).
  modifies:
    - >-
      SemanticSearchToolIT.resultCarriesDisplayFieldsAndNeverTheRawVector —
      exact-shape regex gains the ready_at group (§8-authorized, item 3a).
    - >-
      SemanticSearchToolDiversityIT golden fixture — entries gain seeded
      ready_at values (§8-authorized, item 3b).
    - >-
      SemanticSearchToolHybridIT lexical-only arm — asserts ready_at
      present alongside similarity null (§8-authorized, items 3c/4).
  preserves:
    - all tests currently green on main — explicitly RetrievalWorldPredicateIT, SemanticSearchToolHybridIT (isolation/recall/determinism), SemanticSearchToolDiversityIT (cap/starvation/budget arms), ChatAgentTest's pre-fetch and instruction-table pins, ChatToolCatalogTest, ChatToolDispatcherTest, ChatToolAllowlistSpecParityTest, ChatPromptBudgetTest (self-contained fixture), ChatAgentProvenanceTest (uid-counted provenance is date-independent)
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D58
  - D28
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
    date: 2026-08-24
    verdict: APPROVE-WITH-FIXES
    checks: {SPEC-TRUTHNESS: WARN, SECURITY: PASS, TEST-ADEQUACY: PASS, MAINTAINABILITY: PASS, SCOPE: PASS}
    diff_stats: "11 files, 152 insertions(+), 42 deletions(-) vs merge-base d8a0f3da"
    fix_probes: "approval-record grep 'Each entry carries the post's' = 2 (>=1) in .scratch/tick-mech-M1-927-r1.txt addendum; git diff vs reviewed tree 21373f42 = empty (identity held); ./mvnw -B -pl infochat-provider -am test-compile = BUILD SUCCESS (.scratch/tick-fixes-compile-M1-927.log)"
    fix_applied: "Finding 1 (low, SPEC-TRUTHNESS): user approved the exact landed security.md:329 wording via driver prompt ('Approve as landed', 2026-08-24); recorded in .scratch/tick-mech-M1-927-r1.txt addendum; zero file edits"
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-927: Date and honestly frame the chat semantic grounding set

## Context

Live-test finding (TEST instance, v2.0.0 regression campaign, 2026-08-24,
leg V4 of the M1-916 verification): asked "What happened in tech news in
the last 2 hours? Give me the highlights." in DM, the bot replied —
verbatim from the client chat DB — "I couldn't find any specific news
posts from the last 2 hours in your feed." (TRUE: the DB holds 0
window-matching rows) and then "However, here are some recent highlights
from your subscribed topics:" serving as its first example an
aisearch.substack.com post DB-verified as published 2026-08-17 19:39 — a
week old. The user is materially misled about recency: the stated filter
and the served content disagree inside one reply. The M1-916 verdict on
this leg was routing-PASS/honesty-FAIL, and the honesty failure is real —
but its mechanism is NOT M1-916's diff (verified below): it is the
chat agent's deterministic semantic grounding set carrying no temporal
facts at all. Every chat turn folds that set into the prompt
(`ChatAgent.buildSemanticRetrievalBlock`, ChatAgent.java:872-902), so
every turn in which the user's temporal filter comes back empty is one
model improvisation away from the same mislabel. Single-ticket analysis:
`analysis_ref: self` — this body is the analysis.

## Root cause

Verified in-tree, end to end:

1. **The deterministic pre-fetch folds semanticSearch on EVERY turn**
   (the D28 always-runs pattern, M1-589 created 2026-07-08; hybrid arms
   M1-617 created 2026-07-12). `doHandle` step 3 dispatches
   `semanticSearch` with the user's message
   (ChatAgent.java:532-553, :872-902) and folds a non-empty result into
   the prompt as: `"Posts from the user's subscribed feed semantically
   related to their message:"` + UNTRUSTED_CONTENT wrapper + the tool's
   JSON (ChatAgent.java:896-900). The header carries no time qualifier.
2. **semanticSearch emits no date.** The fused SELECT is
   `SELECT uid, title, url, distance` (SemanticSearchTool.java:225) and
   each entry appends uid/title/url/similarity only (:314-318); grep for
   ready_at/published_at/fetched_at over the file returns nothing. The
   model literally cannot know a retrieved post is a week old. (Contrast:
   searchPosts emits `ready_at` per entry, SearchPostsTool.java:185-186;
   getPost's spec row emits ready_at, docs/spec/security.md:330.)
3. **M1-916 correctly routes the temporal intent** — the searchPosts
   catalog description steers "recent/latest/today/top" to searchPosts
   and demands dated presentation (ChatToolCatalog.java:33-37); the model
   called it, the 2h window on `ready_at >= cutoff`
   (SearchPostsTool.java:150) returned the bare array `[]`
   (queryPosts :199), and the model truthfully reported the empty window.
   Then it obeyed the system prompt's standing order — "When the prompt
   includes posts retrieved from the user's subscribed feed, ground your
   answer in them" (ChatPromptBuilder.java:67-69) — using the only feed
   content it had: the undated pre-fetch set, relabeled with the recency
   the user had asked about.
4. **Disposition of the brief's scope question.** (a) Content selection
   is NOT the miss: the pre-fetch is unwindowed BY DESIGN (D28;
   semanticSearch is the topical corpus tool — a recency component was
   owner-rejected in M1-917, 2026-08-23). (b) The reply copy
   ("recent highlights") is a symptom: it is model-authored prose — no
   bundle key or literal exists (grep for "highlights" over
   infochat-provider/src/main returns nothing) — and the codebase's only
   deterministic levers over it are the FACTS handed in (dates) and the
   FRAMING around them (the block header). (c) IS the miss: the
   grounding set handed to the chat LLM loses the temporal constraint
   entirely — undated entries under a header that never says the block is
   not time-filtered — which both invites the mislabel and makes it
   undetectable to the model.
5. **What the honest-degradation doctrine requires.** commands.md
   §Chat mode (D58 provenance, :1836-1838) commits the chat surface to
   retrieval honesty the user can check: "the user can always tell a
   'found nothing' answer from a 'didn't look' answer". security.md
   §Failure handling's posture ("A complete LLM outage degrades quality,
   not safety", :1782; the M1-912 is_degraded kin) is that a degrade is
   friendly AND truthful — never silently re-labeled. Applied here: when
   the window filter degrades to empty and the turn falls back to the
   general grounding set, that fallback must be DATABLE (every grounding
   entry carries its date) and DISCLOSED (the deterministic framing
   states the set is not time-filtered). The reply may then serve
   nothing (the first half of the observed reply, already truthful), or
   serve dated, disclosed-as-unwindowed content — never undated content
   under a recency label.
6. **Dating the behavior (the brief's falsification ask).** M1-916's
   files_scope is exactly ChatToolCatalog.java + two test files +
   design 05 — the undated emission and the unqualified header predate it
   (M1-589 2026-07-08 / M1-617 2026-07-12). The V4 honesty-FAIL is the
   NEW correct routing EXPOSING an OLD information deficit: pre-M1-916
   the same turn was mis-routed to semanticSearch and answered stale
   with no window claim at all. Caveat: `git log -S` dating was not
   runnable in this analysis harness (no shell); the dating above is
   ticket-record + code-read based — the implementor may corroborate
   with `git log -S 'Posts from the user's subscribed feed'` (expected:
   the M1-589-era commit). Two brief discrepancies noted: there is no
   code artifact named "fallback phase" (the mechanism is the pre-fetch
   block plus model composition — the design note's "fall back to
   summarizing what you have" sentence at design 05:532 is quoted-prompt
   drift, absent from the shipped ChatPromptBuilder template); and the
   per-row DB queries are session-record evidence, not re-derivable
   in-tree (TEST checkout DB is gitignored, instance stopped) — carried
   as observed-output evidence per the brief, with the mechanism
   verified in-tree.

## Pitfalls

- P1: §8 exact-shape pins — SemanticSearchToolIT's regex asserts each
  entry is EXACTLY uid/title/url/similarity (:260-263, `matches()`), and
  SemanticSearchToolDiversityIT's golden fixture asserts the rendered
  JSON verbatim (:340-370). Adding ready_at REQUIRES modifying both;
  without the plain-language authorization in acceptance item 3 the
  reviewer fails TEST-INTEGRITY-CHECK. Golden values must be derived
  from the seeded ready_at instants — a free-typed constant that drifts
  from the seed fails confusingly.
- P2: D19 determinism (llm.md §Determinism boundary :463-480) — the new
  column must be pure emission: never an ORDER BY, WHERE, RRF, or
  cap-then-fill term; the two-call byte-identity arms must hold; never a
  recency component (owner-rejected in M1-917 — re-opening it here would
  be scope creep against a recorded decision).
- P3: M1-917 §10 carry — the 16 KiB byte-budget loop, whole-entry
  truncation, threshold, RRF_K, perSourceCap, iterative-scan arming,
  cancellation, query anchoring, and the arms' READY + D59 predicates
  all carry unchanged; the ledger must record the +~26 B/entry cost
  (worst case ≈ 6.8 KB at the 16 default, still ≪ 16 KiB). An entry
  dropped by budget must drop WITH its date.
- P4: single-source framing (the M1-916 P1 drift class) — the honesty
  qualifier lives ONLY in the pre-fetch header constant
  (ChatAgent.java:896-897); no paraphrase in TOOL_INSTRUCTIONS, the
  catalog strings, or POST_TOOL_RESULT_INSTRUCTION. Prompt-byte cost
  (~55-60 tokens/grounded turn) is weighed and accepted: it rides
  INSIDE the semantic block, which the compaction ladder drops whole
  (design 05:562-566) — unlike the never-drop tool table — so it never
  pressures an over-budget turn (brief 01's lane inherits the fact).
- P5: verification ceiling (the M1-916 P8 posture) — no unit test can
  prove the model stops writing "recent highlights"; the pins prove the
  FACTS (per-entry dates) and the FRAMING (qualified header) reach the
  first-call prompt, boundary-sited at llmProvider.lastUserPrompt. The
  steering verdict is the owner-run live re-probe (acceptance item 12),
  phrased exactly that honestly.
- P6: lexical-only rows — similarity is emitted null for rows with no
  post_embedding (SemanticSearchTool.java:310-312; HybridIT :209 pins
  `"similarity":null`), but ready_at is a POST column present on every
  READY row (HybridIT's own comment :374: "ready_at is only set for
  READY rows" — and every row both arms return is READY by the
  in-arm predicate). The date must not become null with the similarity:
  that would recreate the information deficit exactly on the
  keyword-matched (CVE-id-shaped) rows.
- P7: emit `ready_at`, NOT published_at — one window definition across
  surfaces (the M1-689 rule the searchPosts spec row states: "the
  window filter binds to ready_at … it arrived in that window"). A
  source-supplied nullable published_at would render null dates for
  undated feeds (recreating the deficit) and give one conversation two
  recency stories; COALESCE(published_at, fetched_at) is searchPosts's
  ORDER key, not an emitted fact. The header must say ready_at means
  "became readable", never "published".
- P8: stale echoes — ChatPromptBudgetTest's SEMANTIC_POSTS_BLOCK
  (:68-80) hardcodes the old header and undated entries but constructs
  its own block and feeds build() directly; it stays valid and is left
  untouched (engineering-rules §1; the M1-916
  OpenAiCompatibleProviderToolCallTest precedent). Do not
  "helpfully" modernize it.
- P9: spec-amendment shape (§12) — the security.md semanticSearch row's
  Output column names today's shape, so recording the dated emission is
  a rides-the-diff amendment (the M1-917/M1-617 precedent), rule-text
  only, no dates/ticket IDs in spec prose, wording user-approved at
  implementation. NOT a SPEC-GAP: every promise the row makes (D19
  set/order, D59 isolation in-arm, D54 local embeddings, threshold
  gating, diversity selection) is preserved.
- P10: scope fence — no searchPosts change of any kind (its window,
  ordering, emission, and empty shape are correct as shipped), no
  catalog-string change, no change to the `[]` empty-result shape, no
  new empty-window directive or deterministic intent gate, no
  getReferences work (Census-deferred).

## Approach

Derived from `spec_refs:` — security.md §Prompt-injection defenses pins
the semanticSearch tool surface (the row this amendment records);
commands.md §Chat mode's provenance-honesty doctrine ("the user can
always tell …") is the promise this fix extends to recency; llm.md
§Determinism boundary confines the change to emission (SQL-decided
set/order untouched). The mechanism fix answers the brief's scope
question at (c): date the grounding set and disclose its framing — the
model's copy then has the facts it was missing.

- **Files to touch:** `files_scope` (two production files, three test
  files, one spec section, one design section).
- **Pre-decided shapes (implementation is execution):**
  1. **Dated emission** — both arms of `queryFusedPosts` add `p.ready_at`
     to their SELECT lists; the fused inner select surfaces
     `COALESCE(s.ready_at, l.ready_at) AS ready_at`; the emission entry
     (SemanticSearchTool.java:313-318) inserts
     `,"ready_at":<SearchPostsTool.jsonStr(instantStr(…))>` between
     `url` and `similarity` — mirroring searchPosts's field order
     ({uid, title, url, ready_at, tags}) so one conversation holds one
     field order. Nothing else in the statement moves (P2/P3).
  2. **Honest pre-fetch header** — ChatAgent.java:896-897 becomes
     (verbatim; the framing-half pin quotes it):
     `"\n\nPosts from the user's subscribed feed semantically related "
     + "to their message — matched by topic similarity only, not "
     + "filtered by time: they may be of any age, and each post's "
     + "ready_at says when it became readable:\n"`
     — three semantic elements the pin asserts: not-time-filtered, any
     age, ready_at = became-readable (P4/P7).
  3. **Spec amendment rule-text draft** (§12 — exact wording approved by
     the user at implementation; number-free): security.md
     semanticSearch row, Output column →
     `list of {uid, title, url, ready_at, similarity}` (`similarity`
     null for lexical-only rows), plus: "Each entry carries the post's
     `ready_at` — the same READY-transition timestamp `searchPosts`
     emits — so dates can be presented honestly; the entry set and its
     order remain similarity-fused, never time-filtered or
     time-ordered." Design 05 §5.4.6 syncs the emission sentences, the
     fold paragraph, and the byte ledger.
- **Alternatives considered (rejected, recorded for the commit
  message):** (B) a deterministic localized empty-window reply when a
  windowed searchPosts returns `[]` — replaces model prose on the
  tool-loop surface, requires deterministic temporal-intent detection
  (the M1-916 Option C reject), and discards the genuinely useful
  dated-topical follow-up the fixed reply can now give; (C) suppress
  the pre-fetch on temporal-shaped turns — same classification surface,
  and it removes topical context instead of disclosing it; (D) a
  recency term in the fused ordering — owner-rejected 2026-08-23
  (M1-917); (E) emit published_at (or both dates) — P7.
- **Steps, in implementation order:**
  1. Write both reproduction tests RED (the marker in `reproduction:`
     converts at start, workflow §0).
  2. SemanticSearchTool emission change (shape 1); fix the
     compiler/enumerated pin breaks via the three §8-authorized test
     updates (item 3).
  3. ChatAgent header change (shape 2); the framing pin goes GREEN.
  4. Spec amendment (user-approved wording) + design 05 sync (shape 3).
  5. Hand the owner-run live re-probe to the user (acceptance item 12).
- **Controls to preserve (§10):** the M1-917 enumeration — arms' READY +
  D59 predicates inside the arms, distance threshold, RRF_K,
  perSourceCap, `enableIterativeScan` strict_order, cancellation
  arming, query anchoring, the 16 KiB whole-entry budget loop — plus
  the ChatAgent pre-fetch mechanics (exactly-once dispatch,
  SEMANTIC_QUERY_MAX_CHARS truncation, identical-call TurnContext
  cache, breaker-open skip, whole-block compaction drop), the
  uid-counted provenance notice (date-independent), and the
  post-sanitize strip/refusal/translate path (the header is prompt-side;
  the reply pipeline is untouched). Pinning tests: RetrievalWorldPredicateIT,
  SemanticSearchToolIT/HybridIT/DiversityIT suites, ChatAgentTest's
  pre-fetch and absence pins, ChatAgentProvenanceTest — all green
  unmodified except the three authorized edits.
- **Pitfall→mitigation:** P1→acceptance items 1/3; P2→item 5 (identity
  arms + grep probe); P3→items 6/7; P4→item 2 (verbatim header pin) +
  the ledger; P5→item 12 phrased owner-run; P6→items 3c/4; P7→item 1's
  per-entry equality + the header's became-readable wording; P8→item 8's
  untouched-fixture clause; P9→item 9's approval posture; P10→item 11's
  fence probes.

## Definition of done

Both reproduction tests pass (every semanticSearch entry carries its
seeded ready_at; the folded pre-fetch block carries the not-time-filtered
header and the verbatim dated JSON on the first-call prompt); the three
§8-authorized test updates land with the intent stated here; the
determinism, budget, and isolation arms pass; the pre-existing pre-fetch
and provenance pins pass unmodified; the security.md row and design 05
§5.4.6 carry the user-approved rule-text; the diff names exactly
files_scope; the owner-run live re-probe is handed over with its record
obligation; mvn verify green from the repo root.

## Verification

- P1 → acceptance items 1 and 3: the per-entry ready_at equality catches
  a dropped/misplaced field; the reviewer diffs the three authorized
  test edits against item 3's plain-language authorization (§8).
- P2 → item 5's two byte-identity arms (a mutation that orders or filters
  on the date breaks byte-identity or the grep probe's hit set).
- P3 → item 6's budget arm at worst-case entry size (a mid-entry cut or
  an aggregate breach fails it); item 7's unchanged isolation suites.
- P4 → item 2 asserts the exact qualifier bytes on the assembled prompt;
  grep of the diff shows the qualifier string exists exactly once in
  production code (ChatAgent) — a second copy fails the fence or the pin.
- P5 → item 12 is an owner-run probe with a named FAIL condition (a
  reply labeling undated week-old posts as recent); no unit item claims
  model prose.
- P6 → item 4's lexical-only feed: similarity null AND ready_at present
  — a mutation dating only semantic-arm rows fails it.
- P7 → item 1's equality against seeded ready_at (a mutation emitting
  published_at where seeds differ, or null for undated fixtures, fails);
  the header pin asserts "became readable", not "published".
- P8 → item 8 names ChatPromptBudgetTest as untouched; a diff touching
  it fails the fence probe.
- P9 → item 9's grep probe confirms the row records the dated output;
  the wording itself is user-approved at implementation, never
  test-pinned (§12).
- P10 → item 11's git-diff and catalog-string probes.
- FAILURE-MODE coverage beyond the reproductions → items 4 (lexical-only
  null-date mutation) and 6 (budget-truncation shape), plus item 1's
  named mutations (field dropped / semantic-arm-only dating).
- acceptance items 5-11, 13 → the named probes, suites, and mvn verify.

## Out-of-scope

Named in `out_of_scope`: searchPosts in any form (window, ordering,
emission, `[]` shape — correct as shipped); the M1-916 catalog strings
(byte-pinned); any recency ordering (owner-rejected, M1-917);
deterministic temporal-intent classification and empty-window reply
replacement (design changes, not defect fixes — the M1-916 Option C
reject); getReferences dating (Census-deferred); the context-budget
ladder (brief 01's lane — the header rides the ladder-droppable block);
the wholesale design-05 quoted-prompt reconciliation (only this ticket's
paragraphs sync). This ticket modifies EXACTLY three pre-existing tests,
each §8-authorized in acceptance item 3 with its new expected behavior
stated in plain language; every other pre-existing suite must pass
unmodified.

## Census

The defect class: "post-corpus chat grounding the model can be asked to
date but that carries no date". Enumerated over the closed tool surface
(security.md §Prompt-injection defenses table + the emission loops):

| Site | Date field today | Disposition |
|---|---|---|
| `searchPosts` emission (SearchPostsTool.java:185-186) | `ready_at` | unchanged — correct as shipped |
| `getPost` (spec row security.md:330) | `ready_at` | unchanged |
| `semanticSearch` emission (SemanticSearchTool.java:314-318) | NONE | **fix** — dated here (acceptance items 1, 3a, 9) |
| Deterministic pre-fetch framing (ChatAgent.java:896-900) | no time qualifier | **fix** — disclosed here (acceptance items 2, 10) |
| `getReferences` emission (spec row security.md:331) | NONE | defer — model-initiated-only (never pre-fetch), unobserved in the V4 evidence; its own ticket if live evidence shows the same mislabel |
| `listSaves` (security.md:333) | `saved_at` | unchanged |
| `recallMemory` (security.md:332) | `compressed_at` | unchanged (not feed grounding) |

## Review observations

Recorded from the round-1 gate verdict (2026-08-24) — observations only, no
tickets filed (filing is the owner's call in every case):

- `getReferences` still emits `{uid, title, url, link_type, score}` with no
  date — the same defect class this ticket fixes for semanticSearch, on a
  model-initiated-only surface. The §Census already disposes this as
  defer-with-trigger ("its own ticket if live evidence shows the same
  mislabel").
- The pre-existing design-05 §5.4.6 quoted-prompt drift
  (docs/design/05-llm-and-embeddings.md:524-541 quotes rules the shipped
  ChatPromptBuilder template lacks, e.g. "fall back to summarizing what you
  have") remains unreconciled — already NOTED in this ticket's out_of_scope;
  wholesale reconciliation is its own doc change.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-927-temporal-empty-window-fallback-recency.md
```
