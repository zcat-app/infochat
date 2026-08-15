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
(largest discordancy D=4, cs X-arm L=3 — below the D=4 reject bound of 4/4;
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
