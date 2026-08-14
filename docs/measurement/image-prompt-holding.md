# Krea native-prompt holding vs the translation leg

Does Krea 2 output reflect a native-language `/image` prompt as faithfully as
the production-translated English one? Per-(tier, language) campaign record;
the evidence gate behind the per-model translation-skip flag direction
(docs/plan/future-features.md §D6) under D5's bar-clearing rule: measured, or
the leg stays.

**Status: PRE-REGISTERED — thresholds and protocol locked before any arm
runs. No results in this revision.**

## 1. The bar (pre-registered, locked before any arm runs)

Per (tier, language) pair, the native arm PASSES iff BOTH hold:

1. **Tie.** The native arm (A) ties the production-translated arm (B) on
   element presence/faithfulness. Tie statistic: **one-sided exact binomial
   on discordant scene-element pairs, α = 0.05** — the track-a T2 convention
   (lang-quality.md:24-26). Each scored image contributes one judgment per
   scene element; a pair is an (element, seed) instance where the two arms
   disagree; the test asks whether discordances skew against arm A.
   p ≤ 0.05 → the tie is rejected → the pair FAILS.
2. **Zero hard hygiene defects** in the native arm's cell. Hard defects,
   closed list:
   - **whole-scene subject collapse** — the scene's subject is absent or
     unrecognizable (the image is of something else);
   - **gibberish burned-in text** — burned-in text that is not the requested
     string (applies to scenes carrying a text element).
   A hard defect FAILS its (tier, language) pair regardless of the tie
   verdict. Hygiene columns are reported beside the headline, never averaged
   into it.

The per-(tier, language) PASS/FAIL matrix (§7 at results time) is the artifact
the skip-flag wizard table cites; a pair with any hard defect records FAIL
regardless of its tie verdict.

## 2. Cells, arms, seeds

- **Tiers (2):** `krea_bf16` (krea2_turbo_bf16 + qwen3vl_4b_bf16 encoder) and
  `krea_small` (krea2_turbo_int8_convrot + qwen3vl_4b_fp8_scaled encoder) —
  the two Krea tiers' shipped encoder variants (prod/scripts/4b-image.sh:106-107);
  the multilingual surface lives in the encoder, so the tiers are separate
  cells, never inferred from each other.
- **Languages (5):** cs, es, ru, tr + the en reference.
- **Arms (2):** arm A = the native prompt verbatim; arm B = the SAME prompt
  through the production translation leg (§4). For en, arm B = arm A by
  construction (the translator's en short-circuit), so en renders once and
  scores for both arms; en is the reference arm, not a test pair.
- **Scenes × seeds:** 4 scenes × 2 seeds per cell. Seeds are fixed per scene
  and IDENTICAL across arms and tiers, so each comparison isolates prompt
  language: S1 {1001, 1002}, S2 {2001, 2002}, S3 {3001, 3002}, S4 {4001, 4002}.
- **Graph:** the production-shaped Krea graph — the deployment's live
  workflow template (896×672 latent, 6 steps euler/simple cfg 1.0, Wan2.1 2x
  decode VAE, lanczos fit to 2.296875 MP) for krea_small, and the identical
  shape with the krea_bf16 weights for the bf16 tier (the wizard's per-tier
  graph shape is identical across the two Krea tiers).
- **Cell count:** 2 tiers × 5 languages × 2 arms × 4 scenes × 2 seeds =
  160 cell images, 144 renders (the 16 en cell images are shared by
  construction).

## 3. Scene set (extends the 2026-08-07 five-element protocol)

| scene | elements scored (presence + faithfulness) |
|---|---|
| S1 | red bicycle · blue wooden door · wicker basket on the handlebar · yellow lemons in the basket · black cat sitting on the saddle |
| S2 | wooden kitchen table · exactly three green glass bottles · silver teapot · red checkered tablecloth · window with morning sunlight |
| S3 | sign reading exactly 'CAFE LUNA' · entrance door below the sign · exactly two wooden chairs outside · striped awning · street café facade |
| S4 | brown dog · yellow frisbee being caught · deep snow · red scarf around the neck · pine trees in the background |

S1 is the 2026-08-07 scene verbatim in element content (the prior's single
scene, extended here to four). S3 carries the burned-in-text element the
gibberish-text hygiene class applies to. Native prompts per language are
fixed in the fixture set (§5 decision log); the en canonical texts:

- S1: "A red bicycle leaning against a blue wooden door, a wicker basket on
  the handlebar filled with yellow lemons, a black cat sitting on the saddle"
- S2: "A wooden kitchen table with exactly three green glass bottles, a
  silver teapot, and a red checkered tablecloth, morning sunlight through a
  window"
- S3: "A small street café with a sign reading 'CAFE LUNA' above the
  entrance, two wooden chairs outside, a striped awning"
- S4: "A brown dog catching a yellow frisbee in deep snow, a red scarf around
  its neck, pine trees in the background"

## 4. Arm B is the production translation leg, reproduced exactly

Arm B's English prompt is the output of QueryAnchorTranslator's exact prompt
shape against the deployment's translator routing — never a human reference
translation. Reproduced byte-for-byte from
`infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/QueryAnchorTranslator.java`:
the D58 (d) language-only prompt template with the per-call UUID
`<<<UNTRUSTED_CONTENT>>>` wrapping, then the provider wire shape for
`ModelTask.TRANSLATOR` — `model=deepseek-v4-flash`, `max_tokens=1024`,
`temperature=0` (hard-coded for the TRANSLATOR task), empty system message,
DeepSeek `thinking`-disabled field — against `https://api.deepseek.com`
(the deployment's `infochat.llm.translator.provider=deepseek` routing).
Fallback semantics (failure/blank/over-cap → the original prompt) do not
apply to the campaign calls: a failed translation call is re-run, never
silently substituted, and every such retry is logged in the decision log.

## 5. Fixture discipline and decision log (M1-717 protocol)

Non-English prompts were native-authored; every fixture was
back-translation-verified through the production leg (§4 shape) against the
en canonical: all five elements must survive with their attributes. A fixture
failing verification is VOIDED, not scored; corrections are recorded here.

| fixture | disposition |
|---|---|
| S1.cs, S1.es, S1.ru | verified clean |
| S1.tr | VERIFIED WITH OBSERVATION — native text unambiguous ("selede oturan siyah bir kedi" = black cat sitting on the saddle); the back-translation leg itself returned "sitting in the basket", a translation drift on this very sentence. The fixture stands (native-authored, meaning established by authorship); the drift is arm B's legitimate production input and is exactly the effect under test. |
| S2.cs, S2.es, S2.ru, S2.tr | verified clean |
| S3.cs, S3.es, S3.ru, S3.tr | verified clean (sign text 'CAFE LUNA' preserved in all) |
| S4.cs, S4.es, S4.tr | verified clean |
| S4.ru | CORRECTED — first draft "на фоне сосны" back-translated singular ("a pine tree"); corrected to "сосны на заднем плане", re-verified clean. Original draft never rendered. |

**Voided fixtures: none.**

## 6. Scoring protocol

- **Blind pass.** All cell images are renamed to shuffled blind ids; the
  primary scoring reads blind ids only (no tier/language/arm label), one
  judgment per element: present-and-faithful vs not, plus a free-text note.
  The blind key is sealed (written to disk) BEFORE primary scoring starts and
  opened only after the primary pass completes.
- **Reviewer round.** An independent fresh-context reviewer, given only the
  blind ids, the scene element lists, and this protocol (never the arm
  structure or the campaign's hypothesis), re-scores every image.
  Disagreements go to a recorded adjudication pass (the lang-quality
  over-strictness lesson, lang-quality.md:139-141: reviewers stay in the
  loop).
- **Hygiene columns** (whole-scene subject collapse, gibberish burned-in
  text) are recorded per cell beside the headline element scores, never
  averaged into them.
- A scene whose native arm collapses stays in its cell as a hard defect —
  never dropped, never averaged away.

## 7. Run context (commit pin — the measured-surfaces-are-moving rule,
translator-slot.md:69-71)

| | |
|---|---|
| repo commit | `d6d050c9` (main at campaign start; the harness reproduces QueryAnchorTranslator from THIS tree) |
| deployment provider image | built from prod tree `c731dacb`; its QueryAnchorTranslator/ImageCommandHandler/ComfyUIClient are byte-identical to the pinned repo commit (diff-verified) |
| translator routing | `infochat.llm.translator.provider=deepseek`, `model=deepseek-v4-flash`, base `https://api.deepseek.com` |
| ComfyUI | container `infochat-prod-comfyui-1`, image `infochat-comfyui:rocm-gfx1151`, ComfyUI pin `6f7cd7fceaaf60d2669b554936394a7412c6fde5` (v0.30.0-12), ROCm torch 2.12.0a0+rocm7.13, gfx1151 |
| model files | krea2_turbo_bf16.safetensors / krea2_turbo_int8_convrot.safetensors; qwen3vl_4b_bf16.safetensors / qwen3vl_4b_fp8_scaled.safetensors; Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors |
| hardware | Strix Halo, 128 GB unified, ROCm (Vulkan-beats-ROCm decided EXCEPT for this container path, which is ROCm-only) |
| harness | `.bench/image-prompt-holding/` (gitignored, this record is the only committed artifact) |

Re-pin and re-run if arms are compared across a code change.

## 8. Latency protocol

Translator-leg latency is measured per arm-B call as the wall-clock
round-trip from the deployment box to the translator endpoint (the
round-trip the skip saves). Reported as min/median/max over all arm-B calls.

## 9. Prior evidence and its limits

The 2026-08-07 micro-measurement (docs/design/future/image-generation.md:306-310)
is prior evidence, not campaign-grade: Krea 2 scored 5/5 en, 5/5 cs, 4.5/5
tr, 5/5 es. Its limits: ONE five-element scene, ONE image per (model,
language) cell, cs/tr/es only (no ru — the shipped set is en/cs/es/ru/tr),
and a native-English reference arm rather than the production-translated
English a non-en scope actually submits — it compared native-X against
native-en, a scene-phrasing confound this campaign removes by construction
(arm B is the production leg's own output). Its "(Qwen3-VL encoder)"
causal aside is also unproven: Mage-Flow ships the SAME qwen3vl_4b_bf16
encoder blob yet degraded hard on cs/tr, so this campaign measures per
(tier, language) and never assumes encoder-level transfer.

## 10. What this record will NOT settle

- Nothing about Mage-Flow or Z-Image — the skip question is Krea-only; they
  keep the translation leg regardless (§D6).
- No verdict here enables the skip for any model or tier — the per-(tier,
  language) matrix seeds the wizard table; enablement is the sibling
  ticket's surface.
- Per-scene results do not generalize beyond the scene set's difficulty
  range (four mid-complexity element scenes); text-heavy or typographic
  prompts are covered only by S3's single sign element.
