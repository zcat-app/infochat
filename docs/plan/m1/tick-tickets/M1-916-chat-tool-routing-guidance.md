---
id: M1-916
title: "Route temporal/top-news chat intents to searchPosts"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  ChatToolCatalogTest#searchToolDescriptionsCarryTemporalRoutingGuidance
  (to-be-written — converted at /tick start per workflow §0: written first,
  run RED; the catalog type exists, the guidance text does not; child of a
  2+ decomposition, analysis
  docs/plan/m1/tick-analysis/tool-routing-temporal-queries.md). Probe of
  today's wrong posture (grep-verified): the two search-tool descriptions in
  ChatToolCatalog.java are bare one-liners — searchPosts "search posts by
  tags within a time window" (:33), semanticSearch "find posts semantically
  or by keyword related to a free-text query" (:38) — and neither string
  contains any temporal/routing marker: grep -n 'recent\|latest\|today\|top
  news\|time dimension' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolCatalog.java
  returns NO match. Live corroboration (owner session, 2026-08-23): "top 5
  AI news" and a today-phrased variant both routed to semanticSearch (whose
  fused SQL has no time predicate — SemanticSearchTool.java:217-259) while
  searchPosts (real window arg, newest-first ordering) was never called;
  reproduced twice.
analysis_ref: docs/plan/m1/tick-analysis/tool-routing-temporal-queries.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolCatalog.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Tool implementations in ANY form — SearchPostsTool and
    SemanticSearchTool window clamps, ordering, threshold, limits, and SQL
    are correct as shipped (analysis P5). Only the two catalog description
    strings change.
  - >-
    Any docs/spec/** edit — description text is design-tier (the M1-871
    precedent: the spec pins the tool surface, not the prompt wording);
    the closed allowlist, the dispatcher, and the security.md tool table
    are untouched.
  - >-
    Any routing sentence in TOOL_INSTRUCTIONS or anywhere else — the
    catalog is the single description source (P1); a section-level
    sentence is the recorded fallback IF the owner-run live check after
    this ticket still misroutes, and gets its own ticket then.
  - >-
    Engagement/importance ranking for "top" (no data collected for it;
    M1-914 is digest-side only) and any semanticSearch retrieval change —
    the window-relevance mechanism (per-source diversity) is M1-917's.
  - >-
    semantic-limit default tuning and the context-budget ladder (brief 01,
    unanalyzed) — the ~90-token guidance cost is weighed and accepted in
    the analysis (P7).
  - >-
    Bundle/localization work — verified N/A: the description strings are
    single-source prompt-plane English (grep returns only ChatToolCatalog.java
    plus two test fixtures); D43 bundles cover user-facing prose, not
    prompt text (P6).
acceptance:
  - "ChatToolCatalogTest.searchToolDescriptionsCarryTemporalRoutingGuidance (the reproduction, converted at start) passes — asserts the searchPosts description carries the temporal steering (recent/latest/today/top-news → this tool) AND the 'top means most recent, not most important' honesty clause (P4), and the semanticSearch description carries the no-time-dimension disclaimer AND the steer to searchPosts for temporal intents; a mutation dropping any of the four markers fails (non-vacuity)."
  - "ChatToolCatalogTest.wireDeclarationsCarryTheSameRoutingGuidance passes — BOUNDARY SITING (assertion-adequacy): the guidance reaches the tools-bearing wire, not only the text table — ChatToolCatalog.wireDeclarations() for searchPosts and semanticSearch carries the same description strings the instruction table renders (single-source proof, P1; a divergent second copy fails here or against the byte pin)."
  - "ChatAgentTest.renderedInstructionTableIsByteIdentical passes with the AUTHORIZED modification (engineering-rules §8 — this ticket authorizes exactly this change): the searchPosts and semanticSearch expected lines at ChatAgentTest.java:1336-1339 are replaced by the two new lines quoted verbatim in this ticket's Approach; every other line of the pinned table (getPost, getReferences, recallMemory, listSaves, helpLookup) stays byte-identical."
  - "Pre-existing suites pass UNCHANGED — ChatToolCatalogTest's pre-existing registry-name, arg-shape, and JSON-Schema tests, ChatAgentTest's toolInstructionsMatch*Params / everyRegisteredToolIsAdvertised / workedExampleLineParsesWithTheShippedMatcher / finalCallOmitsToolInstructions, ChatToolRegistryTest, ChatToolDispatcherTest, ChatToolAllowlistSpecParityTest (§10: names, arg shapes, the worked example, and the dispatch boundary are untouched) — probe: mvn -pl infochat-provider -am test -Dtest='ChatToolCatalogTest,ChatAgentTest,ChatToolRegistryTest,ChatToolDispatcherTest,ChatToolAllowlistSpecParityTest' is green."
  - "Scope fence (P5/P6): git diff --stat names exactly the files_scope paths — no SearchPostsTool/SemanticSearchTool/dispatcher/registry path, no docs/spec/** path, no bundle resource."
  - "OWNER-RUN live steering probe (verification ceiling, P8 — no unit test can prove routing; this item is an owner-executed probe with a recorded outcome, the M1-838 live-probe posture, not a CI gate): after landing, the owner drives one live DM turn 'top 5 AI news' and one today-phrased variant against the deployment and captures the provider-log slice covering both turns — probe: grep -c 'searchPosts' on that slice returns a searchPosts tool-loop call for EACH of the two turns (a slice whose only search-tool dispatch lines are semanticSearch FAILS the check); the outcome and the log excerpts are recorded in the commit message. The A2/A3/A5 regression claim (corpus-grounded non-temporal legs unaffected, analysis Ground truth) rides the same leg: any observed misroute or A-family regression is reported, not silently accepted."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 documents the routing-guidance posture for the two search tools (one sentence each; temporal intents to searchPosts, topical to semanticSearch, 'top' = most recent) — probe: grep -n 'top' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mention."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java (the two new guidance pins)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java (renderedInstructionTableIsByteIdentical expected literal: the two search-tool lines only, replaced by the lines quoted in the Approach — authorized above)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Chat mode
decision_refs:
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
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-916: Route temporal/top-news chat intents to searchPosts

## Context

Live prod (owner session, 2026-08-23): "top 5 AI news" and a today-phrased
variant both routed to `semanticSearch`, whose fused SQL has no time
dimension at all (`SemanticSearchTool.java:217-259` — verified), so the
answers came from an 8-result similarity window while `searchPosts` (real
`window` arg clamped [1h, 30d], newest-first
`COALESCE(published_at, fetched_at)` ordering) was never called. Reproduced
twice. The user cost: temporal/"top news" questions get stale,
similarity-shaped answers that misstate their own grounding. Shared
analysis: `analysis_ref:`.

## Root cause

Verified: the only model-facing routing surface is the catalog description
pair (single-sourced into both the instruction table —
`ChatAgent.java:118` — and the wire declarations —
`ChatToolCatalog.java:76-81`), and neither search description mentions
time, recency, or steering (`ChatToolCatalog.java:33,38`; the
grep-for-temporal-markers probe in `reproduction:` returns nothing). Only
`helpLookup` carries "Use this when… NEVER…" guidance (:56-64), and help
routing demonstrably works — the missing ingredient is guidance text, not
tool capability.

## Pitfalls

Numbered per the analysis document; this ticket carries P1–P8, P14.

- P1: single-source drift — guidance lives ONLY in the two catalog strings;
  no paraphrase in TOOL_INSTRUCTIONS (the M1-070/M1-871 drift class).
- P2: the byte-identity pin MUST be modified — §8 authorization naming the
  new lines verbatim (in Approach); every other pinned line byte-identical.
- P3: every chat turn's routing shifts — the A2/A3/A5 live legs (verified
  corpus-grounded, non-temporal) are the regression reference; the
  unaffected-claim is stated and owner-falsified live, never unit-claimed.
- P4: "top" must not promise importance ranking that does not exist
  (`SearchPostsTool.java:165` is pure recency) — the text binds "top" to
  most-recent and demands dated, honest presentation (the M1-070 class in
  reverse).
- P5: scope fence — no tool-implementation, dispatcher, registry, or spec
  edit; exactly two strings.
- P6: phantom bundle work — localization verified N/A; no bundle keys.
- P7: prompt-byte cost (~90 tokens/turn) weighed against the pending
  context-budget brief 01 and accepted; no system-prompt sentence on top.
- P8: verification ceiling — unit pins assert bytes on the wire; the
  steering verdict is an owner-run live leg, phrased as such.
- P14: sibling calibration — M1-917 (retrieval SQL) lands after; disjoint
  pins (description text here, result JSON there), same module, never
  `--parallel`.

## Approach

- **Files to touch:** `files_scope`.
- **The two new description strings (exact — the byte-pin and the design
  note quote these verbatim):**
  - searchPosts:
    `search posts by tags within a time window, newest first. Use this for questions about recent, latest, today's or top news posts — anything with a time dimension. 'Top' means most recent, not most important: present the results with their dates and say so.`
  - semanticSearch:
    `find posts semantically or by keyword related to a free-text query, for topical or theme questions with no time dimension. It has no time window and no recency ordering — for recent, latest, today's or top news questions use searchPosts instead.`
- **Steps, in implementation order:**
  1. Write the two new ChatToolCatalogTest pins RED (the reproduction +
     the wire-declarations pin).
  2. Replace the two description strings in ChatToolCatalog.java (:33, :38)
     with the exact text above — nothing else in the file moves.
  3. Update the two expected lines of
     ChatAgentTest.renderedInstructionTableIsByteIdentical (:1336-1339) to
     the rendered form of the new strings (`- searchPosts {"tags":
     ["tag1"], "window": "P7D", "limit": 10} — <new text>` and
     `- semanticSearch {"query": "free-text topic", "limit": 10} — <new
     text>`); this is the ONLY pre-existing test modification, authorized
     by acceptance item 3 (§8).
  4. Add the §5.4.6 design-note sentence pair.
  5. Hand the owner-run live leg to the user with the commit message
     template for recording it (acceptance item 6).
- **Controls to preserve (§10):** the other five tools' description lines
  byte-identical (the updated pin proves it); the worked example and the
  tool-plane English sentence (`ChatAgent.java:112-116`) untouched; the
  dispatch boundary, allowlist, and the M1-654 names-parity guard
  unaffected (description text is not part of the spec-table parity gate);
  no pre-existing suite modified except the one authorized pin.
- **Pitfall→mitigation:** P1→step 2 + acceptance item 2; P2→step 3 +
  item 3; P3→item 6's stated claim + owner leg; P4→item 1's honesty-clause
  assertion; P5→item 5's diff probe; P6→item 5 (no bundle path); P7→
  analysis P7 + out_of_scope (no extra sentence); P8→item 6 phrased
  owner-run; P14→`analysis_ref:` decomposition + sequencing note.

## Definition of done

Both new catalog pins pass; the wire declarations carry the same guidance;
the byte-identity pin passes with exactly the two authorized line
replacements; every pre-existing chat/tool suite passes unchanged; the diff
names exactly files_scope (no spec, no bundles, no tool impls); design 05
§5.4.6 records the routing posture; the owner-run live probe is executed
and its outcome recorded in the commit message; mvn verify green from the
repo root.

## Verification

- P1 → ChatToolCatalogTest.wireDeclarationsCarryTheSameRoutingGuidance +
  the updated byte-identity pin (a paraphrase elsewhere diverges or is
  absent from the diff).
- P2 → acceptance item 3's verbatim authorization; reviewer diffs the test
  change against it (§8).
- P3 → acceptance item 6 (owner-run probe; A2/A3/A5 records are the
  falsification reference — analysis Ground truth). The steering verdict
  itself is a live-only observation by construction (P8): the ticket's CI
  gate is the unit pins on the wire-declaration and instruction-table text
  (items 1-3); the live leg is owner-executed with a named grep probe and
  a recorded outcome, never a claimed unit result.
- P4 → the reproduction test's honesty-clause arm: a searchPosts text that
  drops "most recent, not most important" fails.
- P5/P6 → acceptance item 5's git diff --stat probe.
- P7 → analysis weighing; no code verification owed (honesty constraint on
  ticket text, the M1-856 P8 pattern).
- P8 → acceptance items 1-2 assert bytes only; item 6 is phrased as an
  owner-run probe with the log-slice grep named.
- FAILURE-MODE coverage → item 1 feeds the catalog the two hostile
  mutations (steering dropped; honesty clause dropped) by construction —
  it asserts marker presence, so any reversion to bare one-liners fails;
  item 2's wire-side assertion catches a table-only edit (boundary siting).
- P14 → test_plan.adds/modifies name disjoint surfaces from M1-917's;
  sequencing note carried in both tickets.
- acceptance items 4, 7, 8 → the named mvn probe, the grep probe, mvn
  verify.

## Out-of-scope

Named in `out_of_scope`: tool implementations (clamps/ordering/limits
correct as shipped), any spec edit (design-tier text, M1-871 precedent),
any extra routing sentence (single-source, P1 — recorded fallback only),
engagement ranking ("top" = recency, honestly presented), semantic-limit
tuning and brief-01 budget work, bundle work (verified N/A), and the
semanticSearch window mechanism (M1-917's). This ticket modifies ONE
pre-existing test (§8): ChatAgentTest.renderedInstructionTableIsByteIdentical
— new expected behavior: the two search-tool lines render the new guidance
text quoted in the Approach; all other lines unchanged.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-916-chat-tool-routing-guidance.md
```
