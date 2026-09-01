---
id: M1-971
title: "Dual-query weighted-RRF fusion for the web lane"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  WebSearchFusionTest#nativePrimaryFusionOutranksEnglishOnlyOnLocalTopic
  (to-be-written; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/websearch-grounding-lane.md; converted at
  /tick start: written first, run RED — the fusion class does not
  exist: grep -rn 'RRF\\|reciprocal' infochat-provider/src/main/java
  returns ONLY SemanticSearchTool.java:58-67 (the corpus lane's k=60
  constant), and no class composes two web arms). The wrong behavior it
  states: nothing fuses a native-language and an English web-search arm
  — the dual-query lane's ranking, dedupe, budget, and fallback
  mechanics are absent, so a non-English scope can either miss the
  native pool or drown it in an English-only query.
analysis_ref: docs/plan/m1/tick-analysis/websearch-grounding-lane.md
blocked_by: [M1-970]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/websearch/WebSearchFusion.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/websearch/WebQueryComposer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/websearch/WebSearchFusionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/websearch/WebQueryComposerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any HTTP call of its own, budget guard, or gate logic — the fusion
    component CONSUMES M1-970's client interface (injected seam) and
    the gate; it owns no outbound behavior. Its tests inject arm lists
    directly or a stubbed client — no harness, no network.
  - >-
    ANY prompt injection, notice, or ChatAgent change — M1-972's lane.
    This ticket produces the fused JSON block as a return value; where
    it goes is the sibling's decision.
  - >-
    A crossover-bonus constant (BINDING user decision): NO tunable
    bonus exists in the first increment — the crossover reward emerges as the double RRF
    contribution; a bonus knob would be a free variable with no spike
    data behind it.
  - >-
    Config knobs for the weights or k — fixed code constants, the
    SemanticSearchTool.RRF_K no-config rationale (D19 reproducibility:
    varying them silently changes the grounded set across deployments).
  - >-
    The corpus lane's RRF (SemanticSearchTool fused SQL) — untouched;
    this is the web lane's own fusion in Java over vendor ranks.
  - >-
    Country/locale derivation from anything but the declared scope
    language — search_lang is pinned from scope_preferences.language
    (D43 default 'en'); no egress-IP or model-inferred locale, and no
    country parameter in the first increment (no scope-declared country
    exists; deriving one would be a free variable).
acceptance:
  - "REPRODUCTION closed (the H1 mechanics): WebSearchFusionTest.nativePrimaryFusionOutranksEnglishOnlyOnLocalTopic passes — injected arm lists where a local-topic source ranks native=1/english=absent and an English-only source ranks english=1/native=absent: the fused order puts the native-primary source FIRST (score 2·1/(60+1) vs 1·1/(60+1)); a source present in BOTH arms at rank 1 outranks a native-only rank-1 (the crossover emergence: 2·1/61 + 1/61 > 2·1/61). Mutations failing it: swapped weights (1:2), an unweighted sum, or a rank-based sort that ignores the weights."
  - "WEIGHT/K CONSTANTS (analysis P7, D19): WebSearchFusionTest.fusionWeightsAndKAreFixedCodeConstants — the arithmetic is asserted against literals w_native=2, w_english=1, k=60 in the test's own expected-score computation; probe: grep -n 'WEIGHT\\|RRF_K\\|60' over WebSearchFusion.java returns the two constants with the no-config-knob comment mirroring SemanticSearchTool.java:58-67; no @ConfigProperty reaches the fusion class."
  - "CANONICAL-URL DEDUPE: WebSearchFusionTest.canonicalUrlVariantsCollapseToOneEntry — fixtures carrying http/https, with/without www, and with/without trailing slash of the SAME path collapse to one entry keeping the FIRST arm's representation (native arm wins representation conflicts); distinct paths never collapse; a scheme-relative or empty URL is dropped (never emitted malformed)."
  - "EN COLLAPSE (BINDING): WebQueryComposerTest.enScopeIssuesSingleArmNoTranslator — an 'en' declared scope composes exactly ONE arm (the message text as-is, search_lang=en) and issues ZERO QueryAnchorTranslator calls (a stub that fails on invocation); a non-en scope composes TWO arms (native text with search_lang=scope language; anchored English text with search_lang=en) issuing exactly ONE translate() call with (query, scopeLanguage, scopeKind, scopeId) — the QueryAnchorTranslator signature reused verbatim (QueryAnchorTranslator.java:195-196)."
  - "TRANSLATOR-FAILURE FALLBACK (analysis P8): WebSearchFusionTest.translatorFailureDegradesToNativeOnlyArm — a stubbed translator failure (exception, blank, or over-cap: the QueryAnchorTranslator fallback contract) yields native-only fusion (one arm's ranks, weight arithmetic degenerate to the single-arm sum), never an error and never a skipped lane; the same drive covers breaker-open via the translator's own short-circuit."
  - "TEMPORAL DATE RESOLUTION IN THE QUERY STRING (analysis P9, BINDING design requirement): WebQueryComposerTest.relativeExpressionResolvesToConcreteDatesInBothArms — with the injected Clock pinned and a 'group' scope zone Europe/Prague (the zoneFor seam's SQL stubbed), a query whose anchored text parses under TemporalExpressionParser (e.g. 'today') appends the code-authored concrete ISO date text to BOTH arm queries (asserted verbatim against the pinned instant + zone); a parse miss appends NOTHING (vague recency never infers a window — commands.md:1852-1857's rule carried to the web lane); the appended dates read ONLY the injected Clock (engineering-rules §9);
and the composer's captured call sets the owner-verified `freshness`
parameter (YYYY-MM-DDtoYYYY-MM-DD form) from the SAME parse on both
arms — the query-string dates remain the BINDING requirement and the
parameter a server-side filter lever (a mutation setting one without
the other fails the corresponding arm)."
  - "BYTE-BUDGETED BLOCK (analysis P6, the M1-940 discipline): WebSearchFusionTest.fusedBlockDropsWholeEntriesAtAggregateByteCap — a fixture whose entries would exceed the block's fixed aggregate byte cap emits a PREFIX of whole entries (each entry's fields intact), drops the tail whole, order preserved, and produces valid JSON (parse-back asserted); per-entry field truncation is M1-970's, asserted there."
  - "VENDOR-RANK DETERMINISM (D19): WebSearchFusionTest.sameArmsYieldByteIdenticalBlock — two fusion calls over byte-identical arm lists produce byte-identical fused blocks (order and fields); the vendor ranking is an INPUT, the fusion a pure function of it."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/websearch/WebSearchFusionTest.java
      — nativePrimaryFusionOutranksEnglishOnlyOnLocalTopic (the
      reproduction), the constants pin, canonicalUrlVariantsCollapse…,
      translatorFailureDegradesToNativeOnlyArm,
      fusedBlockDropsWholeEntriesAtAggregateByteCap,
      sameArmsYieldByteIdenticalBlock.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/websearch/WebQueryComposerTest.java
      — enScopeIssuesSingleArmNoTranslator (with its non-en dual-arm
      arm) and relativeExpressionResolvesToConcreteDatesInBothArms.
  preserves:
    - >-
      all tests currently green on main — explicitly
      QueryAnchorTranslatorTest and SemanticSearchTool's suites
      (untouched: the translator is consumed, never modified), and
      every M1-970 websearch suite.
spec_refs:
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/llm.md §Translation flow
  - docs/spec/security.md §Rate limiting
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D19
  - D29
  - D58
---

# M1-971: Dual-query weighted-RRF fusion for the web lane

## Context

For a non-`en` scope, one query cannot serve both the native evidence
pool (where local news and national-culture sources live) and the
global/English pool (where official terminology and global coverage
live). The user's researched design (BINDING): issue the native query
(search_lang = scope language) and the English-translated query in
parallel, fuse by weighted RRF with w_native:w_english = 2:1 and the
house constant k=60 — the native arm is the decision-maker, the English
arm is recall + a global-relevance signal — dedupe by canonical URL, and
emit ONE fused, byte-budget-capped block. The rank input is the
owner-live-verified ranked web-search endpoint's result order (the
context-shaped grounding endpoint exposes no per-result ranking and is
reserved for the deferred T1 single-query shape). Translator failure
degrades to native-only (degraded beats none — the `semanticSearch`
row's own fallback posture, `security.md:329`). Shared analysis:
`analysis_ref:` (this ticket carries P7, P8, P9, P6's block budget).

## Root cause

Verified absence: the only RRF in the tree is the corpus lane's SQL
fusion (`SemanticSearchTool.java:58-67,:274-275`); no class composes two
web arms (grep, 2026-09-01). The pieces this ticket composes all exist
and are reused, not forked: `QueryAnchorTranslator.translate(query,
sourceLanguage, scopeKind, scopeId)` (greedy, cached, scope-partitioned,
en no-op, raw-text fallback — `QueryAnchorTranslator.java:195-314`),
`TemporalExpressionParser` + the `zoneFor` seam + injected `Clock`
(the corpus lane's deterministic temporal parse,
`ChatAgent.java:944-949,:1015-1031`), and M1-970's typed client.

## Pitfalls

Carried from the analysis: P7 (weights/k are fixed constants; the
fusion is a pure function of vendor ranks — D19), P8 (the English arm
is a NEW translator call site: reuse the translator verbatim, never
fork it; en is a strict single-call collapse; failure → native-only),
P9 (relative temporal expressions resolve to concrete dates IN the
query string, from the injected Clock and the scope's zone; parse miss
changes nothing; the verified `freshness` parameter rides the same
parse as a filter lever, never a substitute), P6-tail (the fused block
obeys the aggregate whole-entry budget). Also: no crossover bonus
(BINDING), no country parameter (no free variables), and CLIR
evidence ordering — native
query is the decision-maker because entity mangling in translation
hurts most on local topics (the analysis's research anchors; the spike
falsifies H1/H3 operator-side).

## Approach

Derived from `spec_refs:` — llm.md §Determinism boundary (the fused
set/order is decided deterministically; the LLM only writes prose over
it), §Translation flow (the English pivot's declared-language rule and
the translator's determinism contract), security.md §Rate limiting (the
M1-969 web-lane entry bounds this to one call per turn; the English arm
is the enumerated translator leg), commands.md §Chat mode (the
temporal-parse rule carried to the web query).

- **Files to touch:** `files_scope` (two main classes, two test
  classes — no HTTP anywhere in these tests).
- **Pre-decided shapes (implementation is execution):**
  1. `WebQueryComposer` — input (message text, declared scopeLanguage,
     scopeKind, scopeId, injected Clock, zone seam): truncate to the
     corpus lane's bound (`SEMANTIC_QUERY_MAX_CHARS`=500 shape);
     anchor via `QueryAnchorTranslator` (en: no call); parse the
     anchored text with `TemporalExpressionParser`; on a hit render the
     concrete date range (ISO, code-authored) appended to BOTH arm
     queries and set the verified `freshness` parameter (date-range
     form) on both from the same parse (P9's lever — a filter, never a
     substitute for the query-string dates); output the arm pair
     (nativeQuery, englishQuery|null) with each arm's search_lang.
  2. `WebSearchFusion` — input (arm-rank lists from M1-970's client
     typed results): score each canonical URL by
     `2·1/(60+rank_native) + 1·1/(60+rank_english)` (absent arm
     contributes 0; en scope degenerates to the native/single term);
     tie-break by native rank then first-seen order (deterministic);
     emit the capped JSON block (fixed aggregate constant, whole-entry
     prefix drop, the M1-940 discipline).
  3. Constants `W_NATIVE=2`, `W_ENGLISH=1`, `WEB_RRF_K=60` with the
     no-config comment mirroring `SemanticSearchTool.RRF_K`.
- **Steps, in implementation order:** fusion pure mechanics RED first →
  composer (translator reuse + temporal dates, pinned Clock) → full
  verify. No wiring: M1-972 consumes.
- **Controls to preserve (§10):** `QueryAnchorTranslator` and its cache
  are consumed verbatim (its en-no-op, breaker, fallback, and
  scope-partition tests keep passing untouched);
  `TemporalExpressionParser` is consumed, not modified (its suites
  untouched); M1-970's client surface is called through its public
  method only.
- **Pitfall→mitigation:** P7→the constants pin + determinism test;
  P8→the en-collapse and translator-failure drives; P9→the pinned-Clock
  date test; P6-tail→the aggregate-budget test; no-bonus/no-country →
  the constants grep shows exactly two weights + k and no bonus symbol
  (reviewer diff check).

## Definition of done

All fusion and composer drives pass (weights arithmetic, crossover
emergence, canonical dedupe, en collapse, translator-failure fallback,
temporal dates on the pinned Clock, aggregate whole-entry budget,
byte-identical determinism); the translator and parser suites pass
untouched; no config knob reaches the fusion; `mvn verify` green from
the repo root.

## Verification

- P7 → `nativePrimaryFusionOutranksEnglishOnlyOnLocalTopic` (weight
  mutations fail), the constants pin (no @ConfigProperty grep),
  `sameArmsYieldByteIdenticalBlock`.
- P8 → `enScopeIssuesSingleArmNoTranslator` (a stray translator call
  fails) and `translatorFailureDegradesToNativeOnlyArm` (an erroring or
  skipped lane fails).
- P9 → `relativeExpressionResolvesToConcreteDatesInBothArms`
  (unpinned-clock or zone-ignoring mutations fail; a parse-miss
  appending anything fails).
- P6-tail → `fusedBlockDropsWholeEntriesAtAggregateByteCap`
  (mid-entry-cut and order-breaking mutations fail).
- FAILURE-MODE coverage → the translator-failure and parse-miss drives
  feed the hostile/edge inputs (a dead translator, a vague query) to
  this diff's own production code and assert the protected degraded
  behaviors.
- acceptance item 9 → mvn verify.

## Out-of-scope

Named in `out_of_scope`: any HTTP/budget/gate behavior (M1-970's);
prompt/notice/ChatAgent wiring (M1-972's); a crossover bonus; config
knobs for weights/k; the corpus lane's RRF; country/locale inference
beyond the declared language. No pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-971-websearch-dualquery-fusion.md
```
