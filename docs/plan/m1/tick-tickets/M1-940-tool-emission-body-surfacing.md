---
id: M1-940
title: "Surface bounded post content in search tool emissions"
status: done
created: 2026-08-26
last_updated: 2026-08-29
flow: tick
reproduction: >-
  Two tests (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/answer-synthesis-language-pinning.md; both
  written and run RED against the unmodified code on 2026-08-29 — the
  five SearchPostsToolTest additions 5/5 red and the two
  SemanticSearchToolIT additions 2/2 red, every pre-existing test in
  both classes green), each stating
  today's wrong behavior — a retrieved post's CONTENT is invisible to
  the model on every list-shaped search hit:
  SearchPostsToolTest#entriesCarryBoundedBodySummaryForSynthesis — seeds
  one in-world READY subscribed post with body_summary='Qwen3-32B
  released under Apache-2.0.' and one whose body_summary is NULL but
  whose body carries the fact; a searchPosts call returning both emits
  entries with EXACTLY {uid, title, url, ready_at, tags} — no
  body_summary field exists anywhere in the JSON (verified:
  SearchPostsTool.java:147 SELECT lists five columns, :182-188 emission
  appends exactly those five; grep -n 'body' over the file returns hits
  only in comments) — so both content assertions fail today; AND
  SemanticSearchToolIT#fusedEntriesCarryBoundedBodySummaryForSynthesis —
  same fixture shape against the fused query (verified:
  SemanticSearchTool.java:224-275 the fused SELECT carries no body
  column, :307-332 emission carries
  uid/title/url/ready_at/similarity only). Live observation motivating
  the change (user, 2026-08-26, brief-carried — output observation,
  mechanism verified in-tree): chat answers read like post lists because
  even on a perfect retrieval hit the model cannot quote the
  price/number/fact — it lives in body/body_summary, absent from every
  emission except getPost's per-uid fetch (GetPostTool.java:79-91).
analysis_ref: docs/plan/m1/tick-analysis/answer-synthesis-language-pinning.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolDiversityIT.java
  - docs/spec/security.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    M1-941's answer-shaping directive — this ticket surfaces content
    ONLY; the prompt wording that consumes it is the blocked sibling.
  - >-
    getPost (already carries body at MAX_BODY_BYTES — the precedent this
    ticket follows, untouched) and getReferences (the deliberately
    metadata-only "more like this" affordance list — its Output row is
    not amended).
  - >-
    ANY ranking/set/order change (D19): the added SELECT columns touch
    no WHERE/ORDER BY/LIMIT; the COALESCE(published_at, fetched_at)
    ordering and the fused arm orders are byte-identical; M1-932/935's
    pending filter params and M1-938's pending _window compose with this
    change without interaction (their predicates are WHERE-side).
  - >-
    The 16 KiB aggregate MAX_RESULT_BYTES and the whole-entry drop rule
    — unchanged (P11): bigger entries mean fewer admitted, order
    preserved; raising the cap is a separate decision never taken here.
  - >-
    New model calls of any kind (binding user decision): no summarizer,
    no translator, no embed — the field is a column read plus a code
    truncation; the ModelTask.CHAT_AGENT call stays the chat path's only
    call.
  - >-
    The eval lane (M1-859's harness emits the production emission shape;
    M1-928/929/930 fixtures and records) — post-landing harness drift is
    the eval side's own extension, owner-run, never edited here; the
    digest//summary/saved surfaces (unchanged per the brief).
  - >-
    Any ChatAgent/prompt change — wrappers, fold-back, provenance,
    collectPostUids all consume the JSON field-insensitively (verified:
    ChatAgent.java:1288-1315 reads uid only; fitWithinBudget admits
    whole entries); no chat-side file moves.
acceptance:
  - "REPRODUCTION closed (searchPosts half): SearchPostsToolTest.entriesCarryBoundedBodySummaryForSynthesis passes — the post-with-summary entry carries \"body_summary\":\"Qwen3-32B released under Apache-2.0.\"; the NULL-summary post's entry carries the anchored excerpt of its body (truncated at the per-entry cap with the [TRUNCATED] marker when cut); a post with NULL body AND NULL body_summary emits \"body_summary\":null (the three value classes pinned; a field-omitting mutation and a wrong-source mutation each fail an arm)."
  - "REPRODUCTION closed (semanticSearch half): SemanticSearchToolIT.fusedEntriesCarryBoundedBodySummaryForSynthesis passes — the same three value classes through the fused query, per entry, appended after the existing fields; lexical-only rows (similarity null) carry the field exactly like semantic rows."
  - "Bounded excerpt discipline (analysis P11): SearchPostsToolTest.overCapContentIsTruncatedCodeAtThePerEntryByteCap passes — a body of multi-byte text (Czech diacritics) surfaced as fallback is cut at MAX_ENTRY_CONTENT_BYTES = 400 UTF-8 bytes on a code-point boundary (never inside a surrogate pair) with GetPostTool.TRUNCATION_MARKER appended; a body_summary LONGER than the cap is truncated identically (the collector's 500-char cap does not bypass the emission cap); a within-cap value passes verbatim. The truncation REUSES GetPostTool.truncateUtf8 — no second truncation helper (probe: grep -c 'truncateUtf8' over the tool package shows the one definition plus imports)."
  - "FAILURE-MODE (aggregate budget, analysis P11): SearchPostsToolTest.contentBearingEntriesStillDropWholeAtTheAggregateCap passes — a fixture whose content-bearing entries would exceed the 16 KiB aggregate admits a PREFIX of whole entries (uid/url/title/content intact per admitted entry) and drops the tail WHOLE, order preserved; never a mid-entry cut; an entry dropped by budget drops with its content (the existing byte-cap posture, recalibrated to content-bearing entries)."
  - "FAILURE-MODE (untrusted-content discipline, analysis P12): SearchPostsToolTest.wrapperMimickingContentRidesInsideTheUntrustedWrapperUnchanged — a seeded body containing the literal text '<<<UNTRUSTED_CONTENT id=\"x\">>>' and a command-shaped token surfaces verbatim inside the result JSON; the ChatAgent-side fold pins (ChatAgentTest:2361-2365, every admitted entry whole inside the wrapper) pass UNMODIFIED — the field changes what the wrapper carries, never the wrapping (security.md §Prompt-injection defenses; the sanitizer's output regime untouched)."
  - "Anchored read (analysis P13): SearchPostsToolTest/SemanticSearchToolIT.nullSummaryFallsBackToTheEnglishAnchoredBody passes — a post with body_en set and a different original body surfaces the body_en excerpt (the coalesce(body_en, body) D29 anchor read, the EmbeddingWorker/lexical-arm precedent), asserted by a discriminating fixture (only the anchored string carries the seeded marker); a both-fields-NULL post emits null."
  - "D19 set/order untouched: the existing determinism and ordering suites pass UNCHANGED — SemanticSearchToolIT/HybridIT/DiversityIT two-call byte-identity arms, the undated-post ordering tests, RetrievalWorldPredicateIT, SearchPostsToolClockTest, SearchPostsToolTopExpansionIT — probe: git diff shows no WHERE/ORDER BY/LIMIT hunk in either tool (reviewer diff check: the SELECT lists and the emission StringBuilder only)."
  - "Isolation unchanged (the M1-589 leak class): RetrievalWorldPredicateIT — the cross-tool D59 world-predicate suite covering searchPosts, semanticSearch's both fused arms, getPost, and getReferences — passes UNMODIFIED, as do the other world-predicate-consuming suites of item 7 (SearchPostsToolTopExpansionIT, SearchPostsToolClockTest, SemanticSearchToolHybridIT) — the new columns ride the SAME one-statement, predicates-inside shape; no post-filter over an over-fetched set appears — probe: git diff over the two tool files shows every predicate still inside the single SELECT (no second statement, no post-fetch filter over the result set)."
  - "§8-AUTHORIZED pre-existing-test modifications (engineering-rules §8; plain language, calibrated to the family's END state per the M1-785 lesson): (a) SemanticSearchToolIT.java:261-296 — the 'exactly uid/title/url/ready_at/similarity' regexes and the per-entry ready_at regexes gain the body_summary field in their expected shapes (the assertion INTENT — exact per-entry field shape and the ready_at position between url and similarity — is unchanged, the shape now includes content); (b) SemanticSearchToolDiversityIT.java:339-375 — the byte-exact GOLDEN fused JSON gains each seeded entry's body_summary value (the seeded bodies are the excerpt source; the golden is regenerated against the landed emission, and a field-removal mutation must RED the comparison — non-vacuity); (c) SearchPostsToolTest's byte-cap test — its fixture entries gain content so the cap arithmetic reflects content-bearing entries. No OTHER pre-existing test is touched — probe: git diff names no test file outside files_scope."
  - "Spec amendment rides the diff (engineering-rules §12 — the exact wording goes to the user for approval at implementation; rides-the-diff shape per the M1-927 precedent on this very row, NOT a SPEC-GAP): docs/spec/security.md §Prompt-injection defenses — the searchPosts row's Output column becomes list of {uid, title, url, ready_at, tags, body_summary} and the semanticSearch row's Output column becomes list of {uid, title, url, ready_at, similarity, body_summary}, each row's Notes gaining the mechanism sentence (rule-text draft: the field carries the post's stored ingest abstract when present, otherwise a code-truncated excerpt of the English-anchored body, bounded per entry at a fixed byte cap with an explicit truncation marker; a null when neither exists; the aggregate result budget and whole-entry drop are unchanged; the field is ingest-pipeline-derived post-body content of the same vetted class the getPost body carries, and rides the same untrusted-content wrapping as every tool result) — probes: grep -n 'body_summary' docs/spec/security.md returns BOTH tool rows; grep -n 'typed structured value' docs/spec/security.md still returns the unchanged carve-out sentence."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 synced: the emission-shape paragraphs (the pre-fetch paragraph at :601-619 and the RRF paragraph's emission sentence at :666-669) record the body_summary field, and the byte ledger (:681-693) records the content-bearing entry shift (entries grow from ~200-400 B to up to ~0.4 KB + metadata; the 16 KiB aggregate binds at fewer entries — the searchPosts default limit admits fewer than before; order-preserving tail truncation unchanged) — probe: grep -n 'body_summary' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mentions."
  - "OWNER-RUN live probe (verification ceiling, the M1-916/M1-931 posture — content presence is unit-pinned, its effect on answers is not; phrased owner-run with a recorded outcome, never claimed as a unit result): after landing (with M1-941), the owner re-asks a fact-bearing question on the deployment and captures the provider-log slice — the retrieval result JSON carries body_summary fields; the record goes to the ticket/commit. The eval answer-quality gap is noted honestly: M1-928 is retrieval-focused and does not measure answer quality (analysis P19) — no CI claim is made."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java
      — entriesCarryBoundedBodySummaryForSynthesis (the searchPosts-half
      reproduction), overCapContentIsTruncatedCodeAtThePerEntryByteCap,
      contentBearingEntriesStillDropWholeAtTheAggregateCap,
      wrapperMimickingContentRidesInsideTheUntrustedWrapperUnchanged,
      nullSummaryFallsBackToTheEnglishAnchoredBody.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolIT.java
      — fusedEntriesCarryBoundedBodySummaryForSynthesis (the
      semanticSearch-half reproduction) + its anchored-read arm.
  modifies:
    - >-
      SemanticSearchToolIT :261-296 field-shape regexes — gain
      body_summary (§8-authorized, item 9a).
    - >-
      SemanticSearchToolDiversityIT :339-375 GOLDEN fused JSON — gains
      per-entry content values (§8-authorized, item 9b).
    - >-
      SearchPostsToolTest's byte-cap test — content-bearing fixture
      (§8-authorized, item 9c).
  preserves:
    - >-
      all tests currently green on main — explicitly the determinism,
      isolation, ordering, clock, top-expansion, and world-predicate
      suites, ChatToolRegistryTest, ChatToolAllowlistSpecParityTest
      (names-only — a field addition does not touch it), and every
      ChatAgent-side suite (the field is consumed transparently).
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D21
  - D29
  - D58
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
    checks: 'SPEC-TRUTHNESS: PASS, SECURITY: FAIL (F1 medium — the SQL reads
      each hit''s FULL body columns and truncates only in Java; bounded at
      the fetch cap x row count, inherited by the every-turn pre-fetch),
      TEST-ADEQUACY: PASS, MAINTAINABILITY: FAIL (F2 low — stale
      golden-provenance comment), SCOPE: PASS; 5 candidate findings
      falsified-and-dropped (world-boundary leak, wrapper breakout, bind/
      D19 drift, marker-makes-411-bytes, aggregate-blow); 1
      RECOMMENDED-NEW-TICKET (M1-950 eval pin drift, TOUCHED-BY-THIS-DIFF:
      yes, no DECIDE-BEFORE) recorded under Review observations'
    diff_stats: '9 files, +512/-46 (7 files_scope paths + ticket + board
      regen)'
  - round: 2
    date: 2026-08-29
    verdict: APPROVE-WITH-FIXES
    checks: 'SPEC-TRUTHNESS: PASS, SECURITY: PASS, TEST-ADEQUACY: PASS,
      MAINTAINABILITY: WARN (F1 low — the 1200-char SQL cut''s coupling to
      MAX_ENTRY_CONTENT_BYTES recorded nowhere), SCOPE: PASS; r1 items
      dispositioned: 1 SATISFIED, 2 SATISFIED; 4 candidate findings
      falsified-and-dropped (multibyte emission change, NULL propagation,
      bind shift, body_summary half-fix)'
    diff_stats: 'fix hunks: 5 files, +48/-6 (code confined to the 3 named
      files; ticket + board carry the dispatch records)'
    fixes_applied: 'F1 comment-only fix at the three SELECT sites
      (transport-bound coupling note); probes: grep -c ''transport
      bound'' → SearchPostsTool 1, SemanticSearchTool 2; comment-only
      verified (every + line is a // line; no docs/spec, docs/design or
      *.md touched); mvnw -pl infochat-provider -am test-compile BUILD
      SUCCESS (.scratch/tick-fixes-compile-M1-940.log); r2 full-verify
      log remains the log of record; fixed-tree snapshot
      .scratch/tick-fixes-M1-940.tree = 5eb34f5cd70373033900ac08c8261fd8212ad9da'
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-08-29: >-
    Start-time self-check passed with no blocking question. Every
    file:line citation in Root cause/Approach verifies in SUBSTANCE at
    post-sibling-landing line numbers (M1-932/936/938/939 landed after
    the analysis was drafted: SearchPostsTool SELECT now :176/emission
    :220-227, SemanticSearchTool emission :358-374, ChatAgent wrappers
    :912-973/:1165-1172 and collectPostUids :1421, ChatAgentTest fold
    pins :2650/:2715, design-05 ledger :712-718 — the shapes claimed all
    hold; only line numbers drifted, as P20 anticipated). Census
    re-runs clean in substance: the four tool rows sit at
    security.md:328-331 exactly as enumerated (the ticket's literal
    grep string misses the backtick before '{' — cosmetic; the
    enumerated rows and their states are accurate). --parallel module
    check: no tick ticket is in-progress/in-review, so the
    infochat-provider scope has no in-flight overlap.
escalation_reason:
---

# M1-940: Surface bounded post content in search tool emissions

## Context

Chat answers feel like post lists because the model cannot see post
content on a search hit: `searchPosts` and `semanticSearch` — the two
list-shaped retrieval surfaces, including the deterministic pre-fetch
that grounds every turn — emit metadata only
(`{uid,title,url,ready_at[,tags,similarity]}`), so the price/number/fact
the user asked about is invisible until a separate `getPost` round trip
the model has no instruction to make (verified field sets in the
reproduction; the brief's probe grep confirms: no body/body_summary
token in either emission). This is RAG-campaign topic 6's content half;
the answer-shaping directive that consumes the field is M1-941, blocked
on this ticket. Evidence, the NULL-summary corpus reality (the V71
roll-forward left the pre-M1-715 corpus with `body_summary` NULL
forever, so the anchored-excerpt fallback is the COMMON case), and the
falsified alternatives are in the analysis, `analysis_ref:`.

## Root cause

Verified end to end: both SELECTs omit the content columns
(SearchPostsTool.java:147; SemanticSearchTool.java:224-275 — the fused
statement's arms project uid/title/url/ready_at/source_id/post_id/
distance|lex_score only) and both emission loops append exactly their
five fields (:182-188; :307-332). The spec pins those shapes
(security.md:328/:329 Output columns) — the M1-927 precedent amended the
semanticSearch Output column the same way (ready_at), so this is a
rides-the-diff amendment, not a SPEC-GAP. The content columns exist and
are populated: `body_summary` on post-M1-715 over-threshold bodies
(capped 500 chars; NULL on the rolled-forward prefix corpus, on
under-threshold bodies, and on the worker's degraded releases) and
`body_en`/`body` for the anchored read (D29).

## Pitfalls

Numbered per the analysis document; this ticket carries P9-P14 (plus
P19's eval honesty and P20's landing-order context).

- P9: the Output columns are spec text — the amendment rides the diff
  with user-approved wording (§12; the M1-927 precedent on this row);
  tool-NAME parity is names-only and untouched.
- P10: golden/regex recalibration — the "exactly uid/title/url/
  ready_at/similarity" pins and the byte-exact GOLDEN break on any new
  field; §8-authorized updates calibrated to the END state (a fixture
  pinned against the pre-change shape breaks its own sibling — the
  M1-785 lesson).
- P11: byte-budget discipline — ONE per-entry cap (400 UTF-8 bytes,
  `GetPostTool.truncateUtf8`, `[TRUNCATED]` marker) applied to whichever
  value surfaces; the 16 KiB aggregate and whole-entry drop unchanged;
  the design-05 byte ledger records the fewer-entries shift.
- P12: untrusted-content discipline — the field is feed-derived
  untrusted text riding the EXISTING wrappers unchanged (pre-fetch block
  ChatAgent.java:896-902; fold-back scaffold :1030-1038); no sanitizer,
  wrapper, or audit change; the "typed structured value … already
  vetted" sentence stays truthful (ingest-derived body artifact —
  getPost's `body` class).
- P13: anchored read + NULL-summary corpus — fallback reads
  `coalesce(body_en, body)` (the D29 anchor, the EmbeddingWorker
  precedent); the fallback is the common case (V71 roll-forward);
  both-NULL emits null; D19 untouched (no WHERE/ORDER BY/LIMIT change).
- P14: fold/provenance consumers are field-insensitive (collectPostUids
  reads uid; fitWithinBudget admits whole entries; M1-918 corner tests
  use synthetic entries) — verified, they stay green unmodified.
- P19: eval honesty — answer quality is not CI-measured (M1-928 is
  retrieval-focused); acceptance is unit pins + owner-run probe; the
  M1-859 harness drift is the eval lane's extension (M1-950's landed
  characterization pin included — see Out-of-scope).
- P20: landing order — after M1-932/934/935/937/938 (their WHERE-side
  params/window compose with these SELECT-side columns without
  interaction); the §8 pin updates here are against the post-sibling
  state.

## Approach

Derived from `spec_refs:` — security.md §Prompt-injection defenses owns
the closed tool table this amendment records (Output columns + Notes;
the M1-927 rides-the-diff precedent); commands.md §Chat mode's budget
paragraph governs the aggregate the emission feeds; llm.md §Determinism
boundary confines the change to SELECT lists and emission bytes (the
retrieved SET and its order stay SQL-decided and unchanged).

- **Files to touch:** `files_scope` (two production tools, three test
  files, one spec, one design doc).
- **Pre-decided shapes (implementation is execution):**
  1. **Column reads.** `SearchPostsTool.queryPosts` SELECT gains
     `p.body_summary, coalesce(p.body_en, p.body) AS anchored_body`
     (params unchanged — SELECT-side only).
     `SemanticSearchTool.queryFusedPosts`: each arm's inner SELECT gains
     the same two columns on `p`, the fused projection carries them
     through its `COALESCE(s.*, l.*)` list (same shape as title/url/
     ready_at), and the outer SELECT lists them. Bind indexes are
     UNTOUCHED (no new bind parameters) — the statement's parameter
     order is part of its tested shape.
  2. **Emission field.** Both emission loops append
     `,"body_summary":<value>` after the existing fields (after `tags`
     in searchPosts, after `similarity` in semanticSearch), where value
     = `jsonStr(content)` and `content` = the stored body_summary when
     non-null, else `truncateUtf8(anchored_body, 400)` when anchored_body
     is non-null, else null. New constant
     `MAX_ENTRY_CONTENT_BYTES = 400` — one constant, shared shape across
     both tools (defined in SearchPostsTool beside MAX_RESULT_BYTES and
     referenced by SemanticSearchTool, mirroring the existing
     jsonStr/instantStr sharing; a code constant, not config — the
     RRF_K no-config-knob rationale). The per-entry byte accounting of
     the existing budget loops is unchanged (entries simply carry more
     bytes; the drop rule does the rest).
  3. **Spec amendments (rule-text drafts; §12 — exact wording
     user-approved at implementation; number-free prose):** acceptance
     item 10's Output-column extensions + Notes sentence.
  4. **Design sync** — acceptance item 11's §5.4.6 paragraphs + ledger.
- **Steps, in implementation order:** (1) write both reproductions +
  the failure-mode tests RED; (2) the searchPosts half (columns +
  emission + constant); (3) the semanticSearch half (arms + fused
  projection + emission); (4) the §8-authorized pin recalibrations
  (item 9) with the golden regenerated against the landed emission; (5)
  spec + design amendments with the user's wording approval — BEFORE the
  final verify so the parity test that parses security.md reads the
  amended row (green-log freshness is per-test); (6) full `mvn verify`;
  (7) hand the owner-run probe to the user.
- **Controls to preserve (engineering-rules §10):** the dispatcher
  boundary (validation/caps/call budget — no new args); the D59 world
  predicate + READY + window compose unchanged (`worldPredicateSql`
  signature does not move); statement_timeout arming + pid registration;
  the 16 KiB whole-entry aggregate; the UNTRUSTED_CONTENT wrappers and
  the sanitizer's output regime (the field rides inside, never around);
  the isolation suites green unmodified.
- **Alternatives considered (rejected, for the commit message):** full
  bodies per entry (O-C3 — the aggregate admits ~2 entries, destroying
  the window); getReferences surfacing (O-C5 — deliberately
  metadata-only); a per-turn summarizer call (O-C2 — binding user
  reject: no new model calls); directive-only (O-C4 — asks the model to
  synthesize facts it cannot see).

## Definition of done

Both reproductions pass (three value classes each: stored summary /
anchored truncated excerpt / null); the over-cap, aggregate-drop,
wrapper-mimic, and anchored-read failure-mode tests pass; the
determinism/isolation/ordering suites pass unmodified; the three
§8-authorized recalibrations land with intent stated and a
field-removal mutation redding the golden; the security.md rows and
design-05 §5.4.6 carry the user-approved rule text and ledger; the
owner-run probe is handed over with its record obligation; mvn verify
is green from the repo root.

## Verification

- P9 → item 10's grep probes post-approval (both rows carry
  body_summary; the typed-structured-value sentence unchanged).
- P10 → items 1-2 pass AND item 9b's non-vacuity (field removal reds
  the golden); the item-9 fence probe (no test file outside scope).
- P11 → item 3 (code-point-safe cut, marker, both value sources capped)
  + item 4 (whole-entry prefix drop, order kept).
- P12 → item 5 (wrapper-mimic content rides inside; ChatAgent fold pins
  unmodified).
- P13 → item 6 (body_en discriminating fixture; null class).
- P14 → item 7's unmodified suite list.
- P19 → item 12's owner-run phrasing and the honest eval-gap note.
- P20 → the reviewer's diff check that no WHERE/ORDER BY/LIMIT hunk
  exists (item 7) — the siblings' predicates are untouched.
- FAILURE-MODE coverage → items 3-6 (over-cap, aggregate-overflow,
  wrapper-mimic, anchor-fallback/null) each feed hostile/edge input to
  this diff's own production code and assert the protected behavior.
- acceptance items 10-13 → the named greps, the design-doc grep, the
  owner-run protocol, mvn verify.

## Out-of-scope

Named in `out_of_scope`: M1-941's directive; getPost and getReferences;
any ranking/set/order change (D19 — SELECT-side only); the 16 KiB
aggregate and whole-entry rule; any new model call (binding); the eval
lane (harness/golden-set/records — owner-run extensions); digest,
/summary, and saved surfaces; any ChatAgent/prompt-side change. Three
pre-existing test artifacts are modified, each §8-authorized in
acceptance item 9 with the new expected behavior stated in plain
language; every other pre-existing suite must pass unmodified.

Eval-lane interaction, recorded at start (2026-08-29): M1-950 landed
mid-ticket and its characterization harness exact-pins the PRE-change
five-field emission (RetrievalEvalCharacterizationIT's assertEmissionShape,
`Set.of("uid","title","url","ready_at","similarity")`). That suite is
`@Tag("retrieval-eval")` — excluded from the default verify, operator-run
only — so CI stays green and the ticket fence (no eval-lane file edited)
holds; the pin WILL red on the eval lane's next owner run after this
lands, and updating it is the eval side's own extension, owner-run.

## Census

This ticket amends a CLASS of emission sites: every post-corpus SEARCH
tool row whose Output lacks content. Re-runnable enumeration:
`grep -n 'list of {' docs/spec/security.md` over the §Prompt-injection
defenses tool table. Rows (verified at draft time):

- security.md:328 `searchPosts` Output — lacks content → **FIX** (item
  10).
- security.md:329 `semanticSearch` Output — lacks content → **FIX**
  (item 10).
- security.md:330 `getPost` Output — already carries `body` → DISPOSED,
  survives (the budget precedent this ticket follows).
- security.md:331 `getReferences` Output — deliberately metadata-only
  (the affordance list; O-C5 rejected) → DISPOSED, out_of_scope.
- security.md:332-334 `recallMemory`/`listSaves`/`helpLookup` — not
  post-corpus search tools; user-scoped state and the intent index →
  DISPOSED, no row touches them.

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-940-r1.txt):

1. FINDING 1: bound the anchored-body read at the SQL layer — replace the
   plain `coalesce(p.body_en, p.body) AS anchored_body` column with
   `substring(coalesce(p.body_en, p.body) from 1 for 1200) AS anchored_body`
   at SearchPostsTool.java:184-185 and SemanticSearchTool.java:282 and :299
   (behavior-identical emission), evaluated via finding 1's EVALUATED-AS:
   the two named tests pass unchanged plus the three-site grep probe.
2. FINDING 2: reword the stale golden-provenance comment at
   SemanticSearchToolDiversityIT.java:173-175 to state that the set and
   order are the pre-change fused ORDER BY while the entry shape is
   regenerated against the landed emission, evaluated via finding 2's grep
   probe plus test-compile.

## Review observations

- (r1, RECOMMENDED-NEW-TICKET, TOUCHED-BY-THIS-DIFF: yes) The M1-950
  eval-lane characterization pin exact-pins the PRE-change five-field
  emission (RetrievalEvalCharacterizationIT.java:269,
  `Set.of("uid","title","url","ready_at","similarity")`) and will RED on
  the eval lane's next owner run once this lands; the harness's emission
  shape assertion must gain `body_summary`. The fence held (no eval file
  edited; the interaction is recorded above in Out-of-scope) — filing a
  follow-up ticket is the owner's call.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-940-tool-emission-body-surfacing.md
```
