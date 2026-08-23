---
id: M1-917
title: "Widen and diversity-cap the semanticSearch window"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  SemanticSearchToolDiversityIT#fusedWindowCapsSingleSourceDominance and
  SemanticSearchToolDefaultLimitWiringTest#semanticLimitDefaultIsSixteenAndMatchesProperties
  (both to-be-written — converted at /tick start per workflow §0: written
  first, run RED; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/tool-routing-temporal-queries.md). The wrong
  behaviors they state: (1) seed limit+2 READY posts from ONE source
  nearest the query vector plus in-world candidates from a second source
  farther but inside the threshold, call with the default limit — today's
  fused window is ALL from the first source, because the fused SQL
  (SemanticSearchTool.java:217-259) selects no source column and orders
  purely by fused_score/post_id: grep -n 'source' SemanticSearchTool.java
  returns hits only in the shared world-predicate helper, never in the
  fused SELECT/ORDER BY. (2) The shipped default window is 8
  (application.properties:502 + the @ConfigProperty defaultValue at
  SemanticSearchTool.java:87-88) — too small a peephole: candidates ranked
  beyond 8 are truncated away even when inside the threshold (each arm is
  LIMIT limit, :239/:254, and the fused outer LIMIT is limit, :259).
  Live corroboration (owner session, 2026-08-23): a "top 5 today news"
  turn returned mostly posts from ONE channel (aisearch) with ~2-week-old
  posts included while more recent ones were skipped — output evidence;
  the mechanism (no source dimension, no recency term anywhere in the
  query) is verified in-tree.
analysis_ref: docs/plan/m1/tick-analysis/tool-routing-temporal-queries.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolDiversityIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolDefaultLimitWiringTest.java
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
    The retrieval ARMS in any form — the semantic HNSW probe, the lexical
    tsvector probe, their WHERE clauses (READY + D59 world predicate inside
    each arm), the distance threshold, RRF_K, and enableIterativeScan are
    all untouched (analysis P5/P10/P12). The change is confined to the
    fused-window SELECTION region of queryFusedPosts, the semantic-limit
    DEFAULT (both declarations), and the byte-budget comment clause.
  - >-
    infochat.chat.semantic-THRESHOLD tuning — M1-616 owns calibration; the
    0.40 distance gate is unchanged. A wider window under the same
    threshold admits more of what already clears the gate; it does not
    loosen relevance.
  - >-
    A recency component in the fused ordering — REJECTED (owner accepted
    the analysis reasoning, 2026-08-23): the "2-week-old posts on a today
    query" defect is the routing failure M1-916 fixes; topical search
    keeps similarity order. Not reopened here.
  - >-
    The context-budget ladder and any prompt-compaction work — brief 01's
    lane. This ticket records the byte/token ledger brief 01 inherits
    (Approach, P7); the widened default's fit-margin cost on the shipped
    wizard serving shape is stated, not solved here.
  - >-
    Engagement/notability ranking of any kind — no data collected for it;
    M1-914 is digest-side and shares no file with this ticket.
  - >-
    The emission shape ({uid, title, url, similarity}, similarity null for
    lexical-only rows), the 16 KiB MAX_RESULT_BYTES budget loop, query
    anchoring, and cancellation arming — all carry unchanged (§10).
  - >-
    M1-916's description text — disjoint surface; nothing here pins or
    depends on catalog strings (P14).
acceptance:
  - "SemanticSearchToolDefaultLimitWiringTest.semanticLimitDefaultIsSixteenAndMatchesProperties passes — the WIRING PIN (P15): the @ConfigProperty defaultValue for infochat.chat.semantic-limit reflected off SemanticSearchTool's @Inject constructor parameter (the ConfigDefaultsConvergenceTest reflective pattern, adapted to constructor injection) equals \"16\", AND the base infochat.chat.semantic-limit line in the application.properties classpath resource parses to 16 — the two declarations the :83-84 comment says must not drift are pinned to ONE value; a mutation moving only one fails. Plain JUnit, no container (no DataSource), per the design 08-verification naming guard."
  - "SemanticSearchToolDiversityIT.fusedWindowCapsSingleSourceDominance (the reproduction, converted at start) passes — AT THE NEW DEFAULT: seed 18 READY posts from source A nearest the query and >= 2 READY posts from in-world source B inside the threshold (embeddings at known angles, the HybridIT rig), construct the tool with limit 16 (the value item 1 pins as the shipped default): the returned 16-window contains at most K = min(16, max(2, (16+1)/2)) = 8 posts from A while B candidates exist in the arm pool — the over-cap A rows are replaced by B rows from the pool, in fused order."
  - "SemanticSearchToolDiversityIT.fittingWindowRendersThePreChangeFusedOrder passes — BYTE-IDENTITY-WHEN-FITTING AT THE NEW DEFAULT (P11): a multi-source fixture at limit 16 whose fused pool is not source-skewed past K=8 renders the same set in the same order the pre-change fused ORDER BY fused_score DESC, post_id ASC LIMIT 16 produced (golden expected JSON asserted verbatim) — the cap is inert off the defect case at the shipped default."
  - "SemanticSearchToolDiversityIT.singleSourceWorldStillFillsTheWindow passes — FAILURE-MODE (P11 starvation): a scope whose world has ONE source (or whose pool holds candidates of one source only) still receives up to 16 results — the cap-then-fill rule re-admits over-cap rows in fused order when slots remain; the window never shrinks for lack of diversity."
  - "SemanticSearchToolDiversityIT.cappedWindowNeverSurfacesOutOfWorldOrNonReadyPosts passes — FAILURE-MODE (P10, the M1-589 leak class): an UNSUBSCRIBED source whose posts embed nearest the query and a RAW post from the subscribed dominant source never appear in the capped window — the diversity pass reselects only rows the world-filtered arms returned; SemanticSearchToolHybridIT.lexicalAndFusedPathNeverSurfaceUnsubscribedOrNonReadyPosts and RetrievalWorldPredicateIT pass UNCHANGED."
  - "SemanticSearchToolDiversityIT.windowAtTheNewDefaultStaysUnderTheByteBudget passes — FAILURE-MODE (P7): 16+ in-world READY posts with long titles/urls (entries at the ~400-byte worst case) return a JSON array within MAX_RESULT_BYTES (16 KiB) with order-preserving tail truncation — the widened default cannot blow the aggregate budget: 16 x 400 B = 6.4 KB worst case, the cap binds only near ~40 entries."
  - "D19 determinism holds under the cap at the new default (P9) — SemanticSearchToolDiversityIT.fusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb passes (same DB state, two calls, byte-identical, on a skewed limit-16 fixture exercising the cap), SemanticSearchToolHybridIT.fusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb passes UNCHANGED, and no NEW config key appears — probe: grep -n 'ConfigProperty' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SemanticSearchTool.java returns exactly the pre-existing semantic-threshold/semantic-limit pair (only the limit's defaultValue moves 8 to 16)."
  - "No profile override of the key exists or is added (P15) — probe: grep -n 'semantic-limit' infochat-provider/src/main/resources/application.properties returns exactly ONE line, the unprefixed base declaration; the key was verified base-only pre-change (overrides exist for body-cap/retention/rate-cap, never this key) and stays that way — DocumentedConfigKeyParityTest is unaffected (the key name is unchanged and stays documented in design 05), green in mvn verify."
  - "SemanticSearchToolHybridIT.keywordExactPostBeyondSemanticThresholdIsRetrievedViaLexicalArm and the whole pre-existing SemanticSearchToolIT / SemanticSearchToolHybridIT suites pass UNCHANGED — they construct the tool with EXPLICIT constructor limits (SemanticSearchToolIT:93-95, SemanticSearchToolHybridIT:122-123, RetrievalWorldPredicateIT:92-95), so the default change does not reach them (§10 carry: arms, threshold, RRF, byte budget, emission shape) — probe: mvn -pl infochat-provider -am verify -Dit.test='SemanticSearchToolIT,SemanticSearchToolHybridIT,SemanticSearchToolDiversityIT,RetrievalWorldPredicateIT' -Dtest=none is green."
  - "Spec amendment rides the diff (analysis P13; engineering-rules §12 — the exact wording goes to the user for approval at implementation; rule-text drafts in this ticket's Approach; spec: docs/spec/security.md §Prompt-injection defenses + docs/spec/commands.md §Chat mode): the semanticSearch row records that the fused window applies a deterministic per-source diversity selection over the arm pool, and the §Chat mode hybrid-retrieval paragraph records the same — BOTH number-free (grep-verified: no docs/spec/** text names the semantic-limit value today, and the amendment adds none; §12 rules-only) — probe: grep -n 'diversity' docs/spec/security.md docs/spec/commands.md returns both mentions."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 documents the new default (the '(default 8)' mention at :576-577 becomes 16), the selection rule (the arm pool bound — each arm LIMITs, so the pool is at most 2·limit — the K formula as a fixed code expression of the effective limit, the RRF_K no-config-knob precedent, and the cap-then-fill rule), and the byte ledger — probe: grep -n 'semantic-limit' docs/design/05-llm-and-embeddings.md returns the updated mentions."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolDiversityIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SemanticSearchToolDefaultLimitWiringTest.java
  preserves:
    - all tests currently green on main — explicitly SemanticSearchToolIT,
      SemanticSearchToolHybridIT (isolation, recall-win, determinism), and
      RetrievalWorldPredicateIT, unmodified (explicit constructor limits,
      unaffected by the default change)
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
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
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-917: Widen and diversity-cap the semanticSearch window

## Context

Owner live observation (2026-08-23): a "top 5 today news" turn answered
from `semanticSearch` returned mostly posts from ONE channel (aisearch) and
included ~2-week-old posts while skipping more recent ones — and the
8-result window itself is too small a peephole. M1-916 fixes the routing
half (temporal intents belong at `searchPosts`); this ticket fixes the
window itself: widen the default and add the verified missing diversity
control. **Owner decision 2026-08-23: the result-window widening is
ADOPTED over the analysis's deferral** ("maybe we could widen to get more
relevant data") — recorded here so the review gate does not flag the
reversal of analysis option (a) as drift. The recency arm stays rejected
(owner accepted the reasoning). Shared analysis: `analysis_ref:`.

## Root cause

Verified in-tree, two parts:

1. **No diversity dimension.** `SemanticSearchTool.queryFusedPosts`
   (`SemanticSearchTool.java:217-259`) selects `uid, title, url, distance`
   only (:218), orders `fused_score DESC, post_id ASC` (:258), and
   truncates the fused pool to `limit` (:259). No `source_id` appears
   anywhere in the query. Single-source dominance is possible by
   construction whenever one channel's posts dominate both arms' ranks.
2. **Small default.** The default limit is 8, declared twice —
   `application.properties:502` and the `@ConfigProperty` defaultValue
   (:87-88, drift-warning comment :83-84). Each arm is `LIMIT limit`
   (:239, :254), so candidates ranked 9+ inside the 0.40 threshold are
   truncated away before fusion. Widening to 16 lets the HNSW iterative
   scan walk deeper and admits ranks 9-16 to the fused pool — the "more
   relevant data" mechanism; the diversity cap then distributes the wider
   pool across sources. The two halves are the combined mechanism, not
   independent fixes: widening alone deepens single-source dominance (more
   rows, same skew), capping alone distributes a peephole.

## Pitfalls

Numbered per the analysis document (P5, P7, P9–P14); P15 is ticket-local,
introduced by the owner-adopted widening scope.

- P5: scope fence — arms, threshold, RRF_K, iterative scan, byte budget,
  emission shape untouched; the code diff is confined to the fused-window
  selection region of queryFusedPosts plus the two default declarations.
- P7: prompt-byte cost of the widened default — ADOPTED with an explicit
  ledger (owner override of the analysis's deferral): entries run ~200-400
  bytes (analysis P7), so 8→16 adds ≤ 8 entries ≈ +1.6-3.2 KB ≈ +400-800
  tokens per retrieval injection (~4 chars/token); worst case 16 × 400 B
  = 6.4 KB, far under MAX_RESULT_BYTES = 16 KiB (:50) — the byte cap binds
  only near ~40 entries and is not the constraint. Against brief 01's
  observed 12.9k-token turn the worst case is ~+5% (~+1 s prefill at 683
  tok/s Vulkan); on the shipped wizard serving shape (11,008 tokens/slot,
  M1-905) the fit margin narrows until brief 01's budget lands — stated,
  weighed, and accepted by the owner decision (the ops mitigation to
  30,720/slot is already in place per brief 01). 12 rejected: under-delivers
  the "more relevant data" intent with no binding constraint demanding it.
  20 rejected: ~+1.2k tokens worst case pressures the pre-brief-01 fit
  margin with no evidence ranks 17-20 carry value under the 0.40 threshold.
- P9: D19 determinism — the diversity pass is pure SQL with total orders
  and total tie-breaks; the cap is a fixed code expression of the effective
  limit (the RRF_K precedent) — never a config knob, never a GUC-dependent
  construct (llm.md §Determinism boundary).
- P10: §10 control carry — the selection reselects/reorders ONLY rows the
  world-filtered arms already returned; it can never admit a row the arms
  did not return (the M1-589 redteam leak class). READY + D59 predicates
  stay INSIDE the arms.
- P11: starvation / byte-identity-when-fitting — a HARD cap shrinks
  single-source worlds and would alter the existing one-source HybridIT
  fixtures (unauthorized behavior change); the cap-then-fill rule is
  provably inert whenever the window is not skewed past K, and that
  inertness is pinned by a golden test AT THE NEW DEFAULT.
- P12: over-fetch bound — the arm pool is at most 2·limit rows by
  construction (each arm LIMITs), so dropping the outer `LIMIT limit` in
  favor of selecting from the full pool is bounded; ARM limits must NOT
  move independently of the limit parameter (they scale WITH it — that is
  the widening mechanism — but no separate arm-limit constant may appear).
- P13: spec amendment shape — the security.md semanticSearch row and
  commands.md §Chat mode describe RRF as the whole window mechanism; the
  amendment records the diversity selection as number-free rule-text
  riding this diff (M1-617 precedent), user-approved at implementation
  (§12). The default VALUE never enters spec prose (grep-verified absent
  today; the amendment adds none). NOT a SPEC-GAP: D19/D59/local-embedding/
  threshold promises all hold.
- P14: sibling calibration — M1-916's pins cover description text, this
  ticket's cover result JSON and the default value; disjoint; lands after
  M1-916 (same module, shared design file — never `--parallel`).
- P15 (ticket-local): the default is declared TWICE — properties base line
  and `@ConfigProperty` defaultValue, with an explicit must-not-drift
  comment (:83-84) — and no profile overrides the key (grep-verified:
  `%vps./%pi./%laptop./%remote-llm./%test.` prefixes exist for body-cap,
  retention, and rate-cap, never for semantic-limit). Moving only one
  declaration, or adding a profile override, silently splits the default
  across deployments; the wiring pin (acceptance item 1) and the
  base-only grep probe (item 8) close both doors.

## Approach

Derived from `spec_refs:`: llm.md §Determinism boundary requires the new
selection to be SQL-decided and reproducible; security.md §Prompt-injection
defenses requires the D59 world filtering to stay inside the arms; the
widening + diversity selection is the minimal combined mechanism honoring
both while admitting more relevant candidates and removing single-source
dominance.

- **Files to touch:** `files_scope`.
- **Pre-decided shapes (implementation is execution):**
  1. **Default widening** — `application.properties:502` becomes
     `infochat.chat.semantic-limit=16` and the `@ConfigProperty`
     defaultValue at SemanticSearchTool.java:88 becomes `"16"` (both
     declarations move together, P15). No profile line is added (verified
     base-only today, item 8 pins it).
  2. **Diversity selection** — carry `p.source_id` out of both arms
     (added to each arm's SELECT and surfaced as `COALESCE(s.source_id,
     l.source_id)` in the fused inner select; nothing else about the arms
     changes, P5/P10). Replace the fused outer `ORDER BY fused_score DESC,
     post_id ASC LIMIT limit` (:258-259) with: the full arm pool (bounded
     at 2·limit, P12) ordered by `fused_score DESC, post_id ASC`;
     per-source rank via `ROW_NUMBER() OVER (PARTITION BY source_id ORDER
     BY fused_score DESC, post_id ASC)`; selection = rows with per-source
     rank ≤ K first (in pool order), then the remaining rows (in pool
     order), `LIMIT limit`. K = `min(limit, max(2, (limit+1)/2))` — half
     the window rounded up, floor 2 — a fixed expression of the effective
     limit, computed in SQL from the bound limit parameter (P9). **At the
     new default 16: K = 8** — no source may take more than half the
     16-window while alternatives exist in the pool.
  3. The emission loop (:281-313) is unchanged — same JSON shape, same
     16 KiB budget, same order-preserving truncation (its comment's
     "nearest-first" phrase is updated to name the diversity order — §11
     comment sync, one clause).
- **Spec amendment rule-text drafts (§12 — exact wording approved by the
  user at implementation; number-free, no dates/IDs in spec prose):**
  - security.md semanticSearch row, append: the fused window applies a
    deterministic per-source diversity selection over the rows the
    world-filtered arms returned, so no single source fills the window
    while other in-world candidates exist (the share and selection rule
    live in design notes); set and order remain SQL-decided and
    reproducible on unchanged DB state (D19).
  - commands.md §Chat mode hybrid paragraph, append: the fused window is
    diversity-selected per source in SQL, so one prolific source cannot
    fill the result window while other in-world candidates exist; the
    selection is deterministic (D19).
- **Steps, in implementation order:**
  1. Write the wiring test and the diversity IT RED (item 1 fails: default
     is 8; item 2 fails: window is all-source-A).
  2. Move the two default declarations to 16 (shape 1).
  3. Rework the fused-selection region of queryFusedPosts per shape 2;
     sync the emission-loop comment clause (shape 3).
  4. Land the user-approved spec wording (item 10) and the design 05
     §5.4.6 updates (item 11: default 8→16, the selection rule, the
     ledger).
- **Controls to preserve (§10):** enumerated in `out_of_scope` and item 9 —
  arm SQL/predicates, threshold, RRF_K, `enableIterativeScan`, cancellation
  arming, query anchoring, byte budget, emission shape; the pre-existing
  isolation/recall/determinism ITs pass UNCHANGED (they pin explicit
  constructor limits).
- **Pitfall→mitigation:** P5→item 9's unchanged-suite probe + diff
  confinement; P7→the ledger above + item 6's budget arm; P9→item 7
  (two-call byte-identity on a skewed fixture + no-new-key grep); P10→
  item 5 (hostile unsubscribed-dominant feed); P11→items 3-4; P12→item 2
  (pool larger than limit) + arm-SQL diff check; P13→item 10's approval
  gate; P14→disjoint pins + sequencing note; P15→items 1 and 8.

## Definition of done

The wiring pin proves the default is 16 in BOTH declarations with no
profile override; the reproduction and all failure-mode/determinism/budget
arms of SemanticSearchToolDiversityIT pass at the new default; the
pre-existing semantic-retrieval suites pass UNCHANGED; the spec amendment
(user-approved, number-free wording) and the design 05 updates land; no
new config key; mvn verify green from the repo root.

## Verification

- P5 → acceptance item 9's mvn probe (pre-existing suites unchanged) +
  reviewer diff check that SemanticSearchTool.java code changes are
  confined to the queryFusedPosts selection region and the defaultValue.
- P7 → the ledger (Approach/P7) + SemanticSearchToolDiversityIT.windowAtTheNewDefaultStaysUnderTheByteBudget
  (feeds 16+ worst-case-size entries; asserts ≤ 16 KiB, order-preserving
  truncation).
- P9 → SemanticSearchToolDiversityIT.fusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb
  (skewed limit-16 fixture, two calls, byte-identical) + item 7's
  ConfigProperty grep (no new knob).
- P10 → SemanticSearchToolDiversityIT.cappedWindowNeverSurfacesOutOfWorldOrNonReadyPosts
  feeds an unsubscribed nearest-match source and a RAW post; asserts
  neither surfaces; the unchanged HybridIT isolation test doubles the pin.
- P11 → .fittingWindowRendersThePreChangeFusedOrder (golden inertness at
  16) and .singleSourceWorldStillFillsTheWindow (starvation feed at 16).
- P12 → the reproduction seeds 18 from the dominant source at limit 16, so
  the fill rows must come from BEYOND today's outer LIMIT — a selection
  that merely reorders the old top-limit set fails it (non-vacuity).
- P13 → acceptance item 10's approval posture + grep probe after landing.
- P14 → test_plan names disjoint surfaces from M1-916; sequencing note in
  both tickets.
- P15 → SemanticSearchToolDefaultLimitWiringTest.semanticLimitDefaultIsSixteenAndMatchesProperties
  (a one-sided edit — properties only or annotation only — fails it) +
  item 8's base-only grep probe (a new profile override fails it).
- FAILURE-MODE coverage → items 4, 5, 6 (starvation; out-of-world dominant
  source; byte budget at worst-case entry size) and the item 2 over-fetch
  feed described above.
- acceptance items 8, 10, 11, 12 → the named grep probes; item 12 → mvn
  verify.

## Out-of-scope

Named in `out_of_scope`: the retrieval arms and every control they carry
(threshold — M1-616's lane, RRF_K, iterative scan, world predicates), the
emission shape and byte-budget loop, any recency component (owner-rejected
2026-08-23: the staleness defect is M1-916's routing fix; not reopened),
the context-budget ladder (brief 01 inherits this ticket's ledger),
engagement ranking (M1-914 is digest-side), and M1-916's description text.
No pre-existing test is modified (§8): the determinism/isolation/recall
ITs construct the tool with explicit limits and must pass unchanged — if a
fixture conflicts, escalate rather than edit.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-917-semantic-search-window-relevance.md
```
