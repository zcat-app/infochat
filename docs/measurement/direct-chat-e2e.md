# Grounded in-language chat with the tool loop — measurement record (M1-844)

Status: thresholds were LOCKED in commit d03f5d38 before any arm ran; the
disclosed G7 amendment (db18111e) and the results (7685fd68) landed in
later commits on this branch. Per pre-registration the bar below predates
every result in this record.

## Pre-registered bar (LOCKED 2026-08-15, before any arm ran)

Cell = (model × language × leg). Models: gemma-4-26b-a4b (candidate),
DeepSeek-V4-Flash (incumbent reference). Languages: en, cs, es, ru, tr.
Legs: GROUNDED-EN-context, GROUNDED-X-context (cs/es/ru/tr only),
TOOL-LOOP. The direct-mode bar-clearing matrix this record produces — the
artifact the M1-845 amendment rule and the M1-848 registry seed cite — is
stated per (model, language) pair: a pair is PASS only when the candidate
passes BOTH GROUNDED-EN-context AND TOOL-LOOP for that language.

### L0 gates (mechanical, hard-fail, never averaged into any headline)

| gate | definition | pass |
|---|---|---|
| G1 language | reply prose = scope language. HARD defect: whole-turn wrong language (incl. English refusal token replacing a reply, whole-turn collapse after code-switch/English turns). MINOR blemish (logged, not cell-failing): isolated foreign fragment inside an otherwise target-language reply | 0 HARD defects |
| G3 well-formed | no empty/malformed output; 4096-guard hit (finish_reason=length) = defect; any `[REFUSAL: …]` token on injection-free fixtures = wrong-refusal defect; loop = ≥5 non-overlapping repeats of an ≥8-token sequence | 0 defects |
| G4 numbers | numeric mentions consistent across turns and matching fixture-declared facts | 0 defects |
| G5 citations | every URL in the reply ∈ the scenario's injected/tool-returned set; every expected citation cited; citing a distractor as a source = defect | 0 defects |
| G6 tool protocol | (TOOL-LOOP) every emission parses (`TOOL_CALL:\s*(\w+)\s*(\{`) with balanced braces, no prose sharing a call line, no forged tool results; terminates in plain text within the 10-iteration cap; cap-hit = defect | 0 defects |
| G7 bleed | (non-en) ≥8 consecutive verbatim words copied untranslated from injected English context = defect | 0 defects |

### Decision bar (per cell)

A candidate PASSES a cell iff BOTH:
1. **Tie**: one-sided exact binomial (sign) test over discordant scenario
   pairs does NOT reject candidate inferiority at α=0.05, per the locked
   table below; Wilson 95% CIs reported on both pass rates; power caveat at
   the locked n is logged; and
2. **Zero L0 defects** on every scenario of the cell.

Quality never launders hygiene: a judgement win with any L0 defect = FAIL.

Locked rejection table (generated mechanically; L = candidate-loses
discordants, D = total discordants — reject candidate when L reaches bound):

| D | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 |
|---|---|---|---|---|---|----|----|----|----|----|----|----|
| reject L ≥ | 5 | 6 | 7 | 7 | 8 | 9 | 9 | 10 | 10 | 11 | 12 | 12 |

(D < 5: never reject.) n locked: GROUNDED = 16 scenarios/language,
TOOL-LOOP = 8/language; zero fixtures voided.

### Judgement layer

PRIMARY: per-scenario must_convey pass/fail (ALL items). Judge: remote
DeepSeek-V4-Flash, temp 0, blind (random ids, no arm identity) — the judge
model is also the incumbent reference arm; that self-judging residual is
disclosed and the reviewer round is its counterweight. Reviewer: codex
(GPT) over the full blind set (52 disagreements, 14%; the initially
drafted second reviewer, opencode/GLM, was disqualified by user ruling —
multilingual weakness; working notes only, never cited). Disputes
user-adjudicated by class (2026-08-15, campaign decision 19): 43 rows
flipped on fixture-note/mechanical evidence, 8 kept to the judge
(hallucinated-availability reading), 1 moot. SECONDARY: blind 1-5 quality
— tie-break only. The 25% per-cell disagreement trigger fired on three
cells, all discharged by the class rulings.

### A/B rule (per language)

The direct-mode prompt design inherits the EN-context arm unless the
X-context arm BEATS it (judgement tie test favors X AND strictly fewer
total defects). A tie or a wash → EN arm (cheaper). Verdict recorded per
language with both arms' columns side by side.

### Refusal re-check

The incumbent's parametric refusal misfires (lang-quality.md:50, :122-125)
re-checked under grounded conditions; refusal-token counts per arm per
language recorded either way.

### Amendment (2026-08-15, post-lock, user authority — the only bar change)

G7 bleed, as locked, counted any ≥8-consecutive-word verbatim span from the
injected English context — including post TITLES quoted to identify a
citation (pilot t01: a Czech reply naming the article by its English
title). User ruling: quoting the original title is valid citation
behavior; translation-bleed remains a defect for PROSE copying only.
Amended G7: verbatim spans that are substrings of an injected or
tool-returned post title are EXEMPT; every other ≥8-word verbatim copy
from the English context still defects. Applied uniformly to all arms;
logged in the campaign decision log (decision 15) and THRESHOLDS.md,
never silently rewritten.

## Pins (measured-surfaces-are-moving rule, translator-slot.md:69-71)

- Repo commit: `244fcf66` — postdates every landed batch ticket touching the
  measured surfaces (chat prompt builders, tool instructions, directives,
  chat tool loop); start-time census in the ticket's P20 entry.
- Prompt surfaces byte-cited at the pin: CHAT_SYSTEM_PROMPT_TEMPLATE
  (ChatPromptBuilder.java:40-61, wordTarget 461), TOOL_INSTRUCTIONS
  (ChatAgent.java:71-94), REPLY_LANGUAGE_DIRECTIVE (ChatAgent.java:201-205;
  pivot reference arm), the pre-registered direct-mode directive (campaign
  decision 3), tool-loop assembly + iteration cap 10
  (ChatAgent.java:783-843), tool result JSON shapes
  (SemanticSearchTool.java:290-312, GetPostTool.java:76-91).
- Corpus snapshot: 11,789 READY posts extracted read-only from the
  deployment DB 2026-08-15, sha256
  `580706dd522d88f9144e4de71e5f91bab7e11fe9a180094d67424a768806ef7e`.
  Harness tool results serve from this snapshot only.
- gemma-4-26b-a4b weights: the deployment's own
  `gemma-4-26B-A4B-it-UD-Q6_K_XL.gguf` (23,295,391,456 bytes) from the prod
  llamacpp volume; campaign llama-server pinned binary, greedy decode,
  flags per lang-quality. Incumbent arm: remote DeepSeek-V4-Flash
  (thinking disabled), METERED.
- Fixtures: 16 grounded + 8 tool-loop scenarios per language, rendered via
  DeepL + back-translation round-trip (M1-717 protocol), verification
  rounds r1/r2 complete, corrections logged (campaign decisions 11-13);
  native user review: cs (one fix applied); es/ru/tr per the round-trip
  protocol. Working data under `.bench/direct-chat-e2e/` (gitignored) —
  this record is the only committed artifact.

## Results (2026-08-15, all arms + judge + reviewer + adjudication complete)

Arms: gemma-4-26b-a4b (the deployment's own GGUF, greedy, pinned llama-server)
vs DeepSeek-V4-Flash (incumbent reference, remote, thinking disabled). Both
run the pre-registered direct-mode shape. Judgement: blind DeepSeek judge
(temp 0) → codex (GPT) reviewer over all 368 transcripts → user-adjudicated
class rulings (decisions 18-19); the judgement file is
`judgements-adjudicated.jsonl` (working data).

### GROUNDED per-language cells (judgement pass-rate vs L0 defects)

| arm/lang | en | cs | es | ru | tr |
|---|---|---|---|---|---|
| gemma EN-ctx pass | 16/16 | 15/16 | 13/16 | 13/16 | 15/16 |
| gemma EN-ctx L0 | 1 | 1 | 2 | 1 | 3 |
| gemma X-ctx pass | – | 12/16 | 13/16 | 13/16 | 14/16 |
| gemma X-ctx L0 | – | 1 | 3 | 0 | 1 |
| incumbent EN-ctx pass | 15/16 | 14/16 | 14/16 | 14/16 | 14/16 |
| incumbent EN-ctx L0 | 11 | 11 | 7 | 2 | 11 |
| incumbent X-ctx pass | – | 15/16 | 14/16 | 12/16 | 12/16 |
| incumbent X-ctx L0 | – | 8 | 9 | 7 | 11 |

Language holding (G1): gemma — zero HARD defects in any grounded cell (the
X-arm ru cell is fully CLEAN); the qwen-style whole-turn collapse never
fired. The incumbent carries one G1-HARD (ru, latin-dominant listing) plus
its citation deficit below. No-bleed (G7, title-exempt per amendment): zero
prose-bleed defects both arms.

Tie test (one-sided sign, locked table): the tie HOLDS in every gemma cell
(largest discordancy D=4 on tr X-arm with L=1; the largest loss count is
cs X-arm L=3 at D=3 — every cell sits below the D<5 never-reject bound;
n=16 per cell, power caveat logged: at D≤4 the test is weak, so "tie holds"
means "no demonstrated inferiority", not "proven equality").

### Defect characterization (gemma, all legs)

All gemma L0 failures are G5 citation misses: 13 single-cell events of
"expected citation not cited" (reply grounded correctly in the post's
content but omitted the bare URL — the wordy-register cells g01/g04/g12
dominate) plus one **URL hallucination cluster** (tr, g12: two mutated
nitter URLs — a digit changed and a truncated id). The incumbent's L0
deficit is the SAME class, 7-11 events per cell: it grounds and answers
well but systematically omits citations. The zero-defect bar is therefore
the binding constraint for BOTH models; judgement quality is not the
differentiator anywhere.

### TOOL-LOOP leg (protocol adherence, judgement, tie vs L0)

| arm/lang | en | cs | es | ru | tr |
|---|---|---|---|---|---|
| gemma expected-call hit | 0/7 | 0/7 | 0/7 | 0/7 | 0/7 |
| gemma mean iterations | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| gemma judgement pass (n=8) | 2/8 | 3/8 | 2/8 | 3/8 | 2/8 |
| deepseek expected-call hit | 2/7 | 2/7 | 2/7 | 3/7 | 1/7 |
| deepseek mean iterations | 0.22 | 0.33 | 0.22 | 0.56 | 0.22 |
| deepseek judgement pass (n=8) | 4/8 | 3/8 | 3/8 | 3/8 | 4/8 |
| discordant pairs (D, L) | 2, 2 | 0, 0 | 1, 1 | 0, 0 | 2, 2 |

Denominators: the locked n=8 counts every scenario per language; the
expected-call rows are /7 because the eighth scenario, t07, is the
no-unnecessary-call control — it expects NO call by design (calling there
is the protocol-efficiency defect being probed), so it contributes no
expected call to hit. Judgement rows and L0 gates cover all 8/8.

Tie test (locked table, same convention as the grounded leg): D ≤ 2 in
every cell — below the D<5 never-reject bound — so candidate inferiority
is NOT rejected on this leg in any language, with the same weak-test
caveat ("no demonstrated inferiority", not proven equality). The low
absolute pass rates are the symmetric expected-call deficit: the seven
expected-call scenarios carry the tool-use item inside must_convey, and
neither arm lands most expected calls (gemma 0/7, incumbent 1-3/7) — the
quantity the tie test compares is the difference in deficits, which never
approaches the rejection bound.

gemma NEVER emits a TOOL_CALL under the shipped instruction block — it
answers from the injected titles or its own knowledge and CLAIMS not-in-feed
without looking (recorded, never dropped: every tool-loop cell carries this
as its defect row). The incumbent calls tools in 10/35 expected scenarios
(29%). One protocol COLLAPSE recorded: gemma tr t02 emitted
`<|tool_call>call:searchPosts {…}` — a malformed marker outside the shipped
grammar, delivered as user-visible text (G1-HARD + G6 defect, stays in its
cell). L0: gemma tool-loop CLEAN except tr (that collapse); incumbent one
G5 (ru t05: URL outside the scenario's permitted set).

Harness divergence disclosure: the snapshot dispatcher approximates
semanticSearch with lexical token overlap (cosine→lexical, campaign
decision logged) and does NOT model the production query-anchor
translation (M1-746, D58: in a declared non-English scope every
semanticSearch query — pre-fetch and model-initiated — is translated to
the English corpus anchor before the retrieval arms). The model-issued
non-English-query zero-result misses recorded above are therefore a
property of the stand-in, not production behavior; the searchPosts tags
path (English controlled vocabulary, no translation) genuinely misses as
observed and stands.

### A/B verdict — context translation (per language)

| lang | EN-better pairs | X-better pairs | defects EN / X | inherits |
|---|---|---|---|---|
| cs | 3 | 0 | 1 / 1 | **EN** |
| es | 0 | 0 | 2 / 3 | **EN** |
| ru | 0 | 0 | 1 / 0 | **EN** |
| tr | 1 | 0 | 3 / 1 | **EN** |

The cheaper hypothesis is CONFIRMED for every shipped language: gemma reads
English retrieved context directly; translating the context adds cost and
defects without judgement gain. The direct-mode prompt design inherits the
EN-context arm.

### LATENCY (deployment box, medians of 3, GPU-quiet window; co-resident
prod llamacpp + ComfyUI idle at probe time — disclosed)

| arm | prefill (tok/s) | first-token (s) | steady-state (tok/s) | total (s, ~400-char reply) |
|---|---|---|---|---|
| gemma EN-ctx | 774 | 0.131 | 41.7 | 3.25 |
| gemma X-ctx | 678 | 0.128 | 42.1 | 3.00 |
| deepseek EN-ctx (remote) | – | 0.357 | – | 4.57 |

The prefill-vs-generation split is measured, not assumed: prefill is the
cold-rep prompt-eval rate and steady-state the median per-rep decode rate,
both off the pinned llama-server's per-request timing (911-token EN /
1115-token X prompt) — a ~16-19x split locally (774 vs 41.7, 678 vs 42.1
tok/s). gemma's local first-token beats the remote incumbent's network
round-trip ~2.7x; a full short reply completes ~30% faster locally.
(First rep of each gemma probe carries prompt-cache warm-up ~1.3 s; ttfb
medians exclude it as cold-start, and reps 2-3 prompt-eval only 1 cached
token, so prefill reads from the cold rep. The remote reference arm's
streaming probe captured no token usage — per the lock the three-quantity
recording covers the local context arms; its row carries first-token and
total only.)

### Refusal re-check (acceptance item 9)

ZERO `[REFUSAL:` tokens across all 368 transcripts, both arms, both legs.
The incumbent's parametric refusal misfires (lang-quality.md:50, :122-125)
VANISHED under grounded conditions — confirming that campaign's
"fixable-in-the-prompt" reading: with retrieved context present, the
refusal-token path never fires.

### Bar-clearing matrix (the artifact M1-845's amendment rule and M1-848's registry seed cite)

| (model, language) | GROUNDED EN-ctx | TOOL-LOOP | PAIR |
|---|---|---|---|
| gemma × en | FAIL (L0=1) | PASS | **FAIL** |
| gemma × cs | FAIL (L0=1) | PASS | **FAIL** |
| gemma × es | FAIL (L0=2) | PASS | **FAIL** |
| gemma × ru | FAIL (L0=1) | PASS | **FAIL** |
| gemma × tr | FAIL (L0=3) | FAIL (L0=1) | **FAIL** |

TOOL-LOOP column derivation: each gemma PASS = tie not rejected (per-language
D, L in the tool-loop section — every D ≤ 2, inside the locked never-reject
bound) AND zero L0 on the leg; tr is FAIL on the collapse L0=1 even though
its tie is not rejected (D=2, L=2).

No (gemma, language) pair clears the bar as measured: every pair fails the
zero-L0 conjunct on citation-discipline defects (G5), and tr additionally
on the protocol collapse. Per the zero-defect bar these are FAIL verdicts
regardless of the judgement ties — quality does not launder hygiene, the
campaign's own rule. NOTE for the amendment (M1-845): the failure class is
narrow (citation omission + one collapse), NOT language incapacity —
language holding, no-bleed, grounding accuracy, and refusal behavior are
clean in all four non-English languages; the incumbent fails the same
conjunct. This record is evidence, not a direction: the registry (M1-848)
seeds from these FAIL cells as-is unless a new measurement clears them.

### Fixture decision log (summary; full log in DECISIONS.md, gitignored)

Zero voided fixtures. Two verification rounds (r1: broken back-translation
leg, scaffolding leakage, Lazarus declension, referent drifts; r2:
pre-training mistranslation, phrasing) — 10 corrections + 1 user fix
(g02-t1 exploit terminology, applied to all four languages). Native user
review: cs; es/ru/tr per the DeepL round-trip protocol (decisions 11-13).

### Commit pin

All arms, judge, reviewer, adjudication, and this record executed against
repo commit `244fcf66` (P20 census in the ticket); corpus snapshot sha256
`5807…39d6`; gemma weights = the deployment's own GGUF (see Pins above).

---

# Re-measure campaign (M1-858): the levers landed — LOCKED pre-registration

Status: this section's bar was LOCKED in the commit that added it, before
any re-measure arm ran; every results commit of this campaign postdates it
(git log --follow this file). Append-only per the record's terminal-evidence
rule: the 244fcf66 section above is byte-intact.

## What this campaign measures (the named delta)

The levers landed after the 244fcf66 campaign, each on a measured surface:

- M1-856 (c284d974): TOOL_INSTRUCTIONS now carries a worked example and a
  tool-plane language sentence (ChatAgent.java:78-106 at the pin); a second
  accepted emission dialect — the native opener `<|tool_call>` with
  `call:NAME {…}` and a balanced-brace scan, no closer required
  (NATIVE_TOOL_CALL_PATTERN, ChatAgent.java:70-72) — dispatched through the
  unchanged ChatToolDispatcher boundary on earliest-match precedence
  (earliestToolCallMatch, :1094-1106); stripToolCalls covers both dialects
  (balanced removed exactly, unbalanced through end-of-text, a native opener
  without an args brace is prose and preserved; :1113-1159).
- M1-857 (0ffea3c5): the framing citation sentence now demands every relied
  post cited by its bare source URL "copied exactly as it appears in the
  retrieved post or tool result; never invent, modify, or guess a URL"
  (CHAT_SYSTEM_PROMPT_TEMPLATE, ChatPromptBuilder.java:41-64 at the pin),
  and the post-tool-result instruction line carries the same demand
  (POST_TOOL_RESULT_INSTRUCTION, ChatAgent.java:111-114).
- M1-859 (64400886): the harness models production retrieval — real cosine
  over deployment-embedder vectors (nomic-embed-text-v1.5, the deployment's
  GGUF, floor-check server shape), the production 0.40 distance floor
  (admit similarity ≥ 0.60), the semantic arm's deterministic order, and
  queries anchored per M1-746 for non-English scopes (live greedy remote
  translator, append-only memoised cache, counted fallbacks — zero in this
  campaign so far).

The harness's G6 tool-protocol gate is EXTENDED to the two accepted
emission dialects: a bridged native emission is protocol-adherent, not a
collapse; and a matchable protocol fragment surviving in DELIVERED text
(impossible in production — the loop dispatches it or the strip removes it)
is a G6 defect. Both legs run the production tool-loop shape (the grounded
leg is no longer a single call without tool instructions — every chat turn
carries the 856/857 surfaces, as production does).

## Bar (LOCKED, restated — same as above, never re-negotiated)

Identical to the 2026-08-15 lock: cell = (model × language × leg); models
gemma-4-26b-a4b (candidate) and DeepSeek-V4-Flash (incumbent reference);
languages en, cs, es, ru, tr; L0 gates G1/G3/G4/G5/G6/G7 with the G7 title
exemption (decision 15); zero-defect conjunct — quality never launders
hygiene; decision bar = one-sided exact binomial tie test over discordant
pairs (α=0.05, the locked rejection table above: D=5→L≥5, 6→6, 7→7, 8→7,
9→8, 10→9, 11→9, 12→10, 13→10, 14→11, 15→12, 16→12; D<5 never rejects)
AND zero L0 defects; Wilson 95% CIs reported, power caveat logged; judgement
layer = blind DeepSeek-V4-Flash temp 0 judge, codex (GPT) reviewer over the
full blind set, user adjudication of disputes (decisions 17-19); t07
no-unnecessary-call control (zero false calls per arm per language; a false
call is a recorded cell defect, never averaged). No free variable: same
fixtures, same n (GROUNDED=16, TOOL-LOOP=8 per language), zero voids
re-verified, no new thresholds, no new knobs.

## Pins (start-time census, P20)

- Repo commit: `02ca356c` — postdates the landings of M1-856 (c284d974),
  M1-857 (0ffea3c5) and M1-859 (64400886), mechanically verified
  (merge-base ancestry). Census of pending tickets touching chat
  prompt/tool surfaces at start: none landed after this pin before any arm;
  M1-848 (pending) touches the chat reply path but nothing of it is landed,
  and M1-862/863 (pending) touch retrieval vectors only — both named here,
  neither changes the measured surfaces.
- Prompt surfaces byte-cited at the pin: CHAT_SYSTEM_PROMPT_TEMPLATE
  (ChatPromptBuilder.java:41-64, wordTarget 461), TOOL_INSTRUCTIONS
  (ChatAgent.java:78-106), POST_TOOL_RESULT_INSTRUCTION (:111-114),
  NATIVE_TOOL_CALL_PATTERN (:70-72), earliestToolCallMatch (:1094-1106),
  stripToolCalls (:1113-1159), REPLY_LANGUAGE_DIRECTIVE (:221-226),
  CLARIFY/AFFORDANCE (:169-188), tool-loop assembly + iteration cap 10
  (:803-865), tool result JSON shapes (SemanticSearchTool.java:298-302,
  GetPostTool.java:81-90). Harness copies byte-verified by pin_check.py.
- Corpus snapshot: re-used, NOT re-extracted — 11,789 READY posts, sha256
  `580706dd522d88f9144e4de71e5f91bab7e11fe9a180094d67424a768806ef7e`;
  embeddings per the M1-859 manifest (GGUF sha256 ed3a84b5…, dimension 768).
- gemma-4-26b-a4b weights: the deployment's own
  `gemma-4-26B-A4B-it-UD-Q6_K_XL.gguf` (23,295,391,456 bytes), campaign
  llama-server b10221 pinned binary, greedy, flags per the record's Pins.
  Incumbent arm: remote DeepSeek-V4-Flash (thinking disabled), METERED.
- Fixtures: the SAME committed scenario sets (16 grounded + 8 tool-loop per
  language, zero voided), working data under `.bench/direct-chat-e2e/`
  (gitignored) in the new campaign results tree; this record is the only
  committed artifact.

## Scope (settled legs are NOT re-litigated)

Re-run: GROUNDED-EN-context and TOOL-LOOP for both models, all five
languages. NOT re-run: the X-context arm (the EN-context inheritance
verdict is final), the parametric legs, LATENCY (data-only, no bar), and
the refusal re-check (optional color only — zero-refusal is unchanged
surface). G5 citation columns are recorded per cell for BOTH arms beside
the headline; the epistemic-stance residual (t05/t06/t08) is recorded as
defect rows wherever it persists, never dropped, never prompt-engineered
mid-campaign (P8: the English-plane sentence is anchor agreement, not a
calling lever — no arm re-tests the ab negative).

## Remaining harness divergences from production (enumerated, never silent)

1. The sanitizer pass is not modeled: fixtures are injection-free and the
   harness's strip runs on raw model text (production strips sanitized
   bytes; sanitize can assemble fragments — not reachable from clean
   fixtures). 2. The `[REFUSAL:` prefix intercept degrade is not modeled —
   refusal tokens are recorded raw in transcripts and defect under G3.
   3. No translation display leg (the pre-registered direct-mode shape,
   decision 3: the model writes in-language). 4. Deterministic help-delivery
   probes (topic/command) are not modeled. 5. Delimiter ids are fixed
   (bench-*) where production uses random per-call UUIDs. 6. Memory
   pre-fetch block unused (no prior memory in fixtures). 7. Retrieval-side
   residuals per the M1-859 README: lexical+RRF fusion deliberately
   unbuilt; serving GGUF via the pinned llama-server vs Ollama
   (±0.01 similarity noise, M1-748); posts >1200 chars embed the 800-char
   body fallback where production embeds LLM summaries (59.3% of the
   snapshot); uid-ASC tie-break; 8 KiB budgets on searchPosts/getReferences;
   one declared scope per driver run; the embedding server co-resides on
   the GPU and starts lazily. 8. Audit rows, persistence, and session
   compression are outside the transcript surface.

## Working data (gitignored)

Results under `.bench/direct-chat-e2e/results/remeasure-2026-08-16/`
(gen.py `--root`); campaign decisions continue in DECISIONS.md (decisions
22+); the corpus/query-anchor-cache.jsonl is append-only and
scope-partitioned as production's.

## Results

(2026-08-16, all arms + judge + reviewer + adjudication complete. Arms:
gemma-4-26b-a4b (deployment GGUF, greedy, pinned llama-server) vs
DeepSeek-V4-Flash (incumbent reference, remote, thinking disabled, metered).
Judgement: blind DeepSeek judge temp 0 → codex (GPT) reviewer over the full
240-transcript blind set → 25 disagreements, user-adjudicated by class
ruling (campaign decision 24): class A (17 rows, tool-call items — the
mechanical iteration record shows no call; judge over-credited) → codex;
class B (5 rows, g06 numbers — G4 mechanically passed) → codex; class C
(3 rows, g12 announcement framing — the replies keep the preview/soon
distinction the fixture trap demands) → codex. The 25% per-cell
disagreement trigger fired on seven toolloop cells; all seven are covered
and discharged by these class rulings. The adjudicated judgement file is
`judgements-adjudicated.jsonl` under the new campaign's results tree.)

### GROUNDED per-language cells (judgement pass-rate vs L0 defects)

| arm/lang | en | cs | es | ru | tr |
|---|---|---|---|---|---|
| gemma EN-ctx pass | 14/16 | 14/16 | 14/16 | 14/16 | 14/16 |
| gemma EN-ctx L0 | 1 | 0 | 2 | 0 | 0 |
| incumbent EN-ctx pass | 15/16 | 14/16 | 14/16 | 14/16 | 14/16 |
| incumbent EN-ctx L0 | 5 | 4 | 6 | 0 | 1 |

Wilson 95% CIs (gemma, all five cells): 14/16 → [0.640, 0.965]; incumbent
en 15/16 → [0.717, 0.989] (score interval, z=1.96, derivation logged as
campaign decision 26). Language holding (G1): zero HARD defects either
arm, any cell. No-bleed (G7): zero both arms.

### G5 citation columns per cell (the actual bar blocker)

Expected-citation misses (single-cell events, adjudicated record):

| arm/lang | en | cs | es | ru | tr |
|---|---|---|---|---|---|
| gemma grounded | g16 | – | g09, g16 | – | – |
| incumbent grounded | g01, g12, g13, g16 | g04, g07, g15, g16 | g01, g02, g07, g09, g15, g16 | – | g03 |

URL-outside-set events: zero for gemma in any cell (the old tr g12
mutated-URL cluster is gone); incumbent ru t05 tool-loop URL outside the
permitted set (techtransparencyproject.org) — same class as 244fcf66.

### TOOL-LOOP leg (protocol adherence, judgement, tie vs L0)

| arm/lang | en | cs | es | ru | tr |
|---|---|---|---|---|---|
| gemma expected-call hit | 1/7 | 0/7 | 0/7 | 0/7 | 1/7 |
| gemma mean iterations | 0.10 | 0.00 | 0.00 | 0.00 | 0.10 |
| gemma judgement pass (n=8) | 1/8 | 1/8 | 2/8 | 2/8 | 2/8 |
| deepseek expected-call hit | 2/7 | 2/7 | 1/7 | 3/7 | 2/7 |
| deepseek mean iterations | 0.20 | 0.30 | 0.10 | 0.40 | 0.20 |
| deepseek judgement pass (n=8) | 2/8 | 1/8 | 1/8 | 4/8 | 2/8 |
| discordant pairs (D, L) | 1, 1 | 0, 0 | 1, 0 | 2, 2 | 2, 1 |

Denominators: n=8 counts every scenario per language; the expected-call
rows are /7 because t08's sibling t07 is the no-unnecessary-call control.

The t07 no-unnecessary-call control HOLDs: zero false tool calls per arm
per language — t07 emissions in every (arm × language) cell are 0 (gemma:
0/5 cells any call; incumbent: 0/5). The levers did not buy calling with
false positives.

The M1-856 bridge works on the deployment model in the campaign: the
worked example + bridge converted the old tr t02 collapse — gemma's native
`<|tool_call>call:searchPosts {…}` (the observed `<tool_call|>` closer
shape) now dispatches through the harness's earliest-match bridge in BOTH
en t02 and tr t02, and the delivered replies are grounded, in-language,
protocol-clean answers. Zero protocol fragments reached any delivered
reply (G6: the extended gate is CLEAN in every gemma cell; the 244fcf66
tr t02 G1-HARD+G6 collapse class has no occurrence).

Gemma tool-loop L0: CLEAN in all five languages (244fcf66: tr FAIL L0=1 on
that collapse). Incumbent tool-loop L0: cs t05 one wrong-refusal token
(G3 — a `[REFUSAL: …]` on an injection-free fixture) and ru t05 one G5
url-not-in-set (above).

### Epistemic-stance residual (recorded per scenario, never dropped)

| scenario | gemma | incumbent |
|---|---|---|
| t05 chained semanticSearch→getPost | still-zero in every language (0/5) | still-zero in every language (0/5) |
| t06 check-before-claiming-absence | expected semanticSearch still absent (0/5 cells) | expected call absent on turn 1 in all five (cs turn 2 only) |
| t08 two-fetch comparison | still-zero in every language (0/5) | still-zero in every language (0/5) |

The epistemic-stance class has NO lever (P8) and stays recorded in its
cells: t05/t08 remain the tool-loop leg's residual; the expected
semanticSearch before an absence claim is still absent (gemma 0/5 cells;
incumbent turn-1 absent in all five, one cs turn-2 call only). The judged
honesty items (honest not-in-feed wording) pass 5/5 for both arms — a
fixture property (the t06 must_convey items never demand the call), not a
behavior change (correction logged as campaign decision 26).

### Refusal re-check (optional color)

One `[REFUSAL:` token across all 240 transcripts: incumbent cs t05
(recorded as its cell's G3 defect). Zero for gemma in any cell. The
incumbent's 244fcf66 zero-refusal result does not reproduce here.

### Tie test (one-sided sign, locked table)

Tie HOLDS in every cell — the largest discordancy is D=2 (gemma tool-loop
ru L=2, W=0) and every D is below the locked D<5 never-reject bound, so
candidate inferiority is not demonstrated in any (leg, language) cell.
n=8 on the tool-loop leg and n=16 on the grounded leg; the power caveat is
unchanged from 244fcf66: at D ≤ 3 the test is weak, so "tie holds" means
"no demonstrated inferiority", not "proven equality". (D=1 grounded en:
gemma loses one discordant pair, g16.)

### Defect characterization (gemma, re-measured surfaces)

The gemma grounded residual is the same narrow class the 244fcf66 NOTE
identified — citation omission, now down to THREE cells: en g16 (the
wordy-register scenario), es g09 and g16 (same class). Every other gemma
cell is L0-clean on both legs, including zero G5 events in cs/ru/tr. The
mutated-URL cluster is gone. Judgement quality is not the differentiator
anywhere: the incumbent's grounded cells carry the SAME citation class at
1-6 events per cell (its ru cell is clean), and its cs t05 refusal is new.

### Bar-clearing matrix — restated from the new cells

| (model, language) | GROUNDED EN-ctx | TOOL-LOOP | PAIR |
|---|---|---|---|
| gemma × en | FAIL (L0=1) | PASS | **FAIL** |
| gemma × cs | PASS | PASS | **PASS** |
| gemma × es | FAIL (L0=2) | PASS | **FAIL** |
| gemma × ru | PASS | PASS | **PASS** |
| gemma × tr | PASS | PASS | **PASS** |

Derivation: each PASS = tie not rejected AND zero L0 on the leg (the
matrix rule of 244fcf66, unchanged). Three of five pairs now clear the
bar. The artifact this matrix updates — M1-848's registry seed — reads
these cells as-is per the record's own rule; the two FAIL cells (en, es)
trace to three named citation misses.

### Commit pin

All arms, judge, reviewer, adjudication, and this section executed against
repo commit `02ca356c` (census in the lock above); corpus snapshot sha256
`580706dd522d88f9144e4de71e5f91bab7e11fe9a180094d67424a768806ef7e`
(re-used, not re-extracted); gemma weights = the deployment's own GGUF;
every corrected or derived number is logged in the campaign DECISIONS.md
(decisions 22+).
