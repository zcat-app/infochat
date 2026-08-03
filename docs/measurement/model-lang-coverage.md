# MODEL-LANG-COVERAGE.md — tokenizer language coverage of the on-disk GGUF model set (read-only, 2026-08-03)

**Status: measured once, 2026-08-03 (read-only — GGUF metadata only, no inference).**
Answers "which of the candidate GGUF models on this box can represent Turkish,
Russian, Czech, Thai, Spanish?" at the tokenizer level.

## Bottom line

All 18 models are byte-level BPEs (Gemma is the only sentencepiece + `<0xNN>`
byte-fallback one), so **every model can tokenize all five languages — there is
no out-of-vocabulary wall**. What varies is subword *coverage depth*, which
predicts fluency: a language whose script appears as dense native-script
subwords (whole words/suffixes) will be represented far better than one that
falls back to per-byte or per-letter tokens.

Counts below = tokens in `tokenizer.ggml.tokens` containing ≥1 character of the
script (RU = Cyrillic block U+0400–04FF, TH = Thai U+0E00–0E7F, TR = Turkish
specials ğ Ğ ş Ş ı İ ç Ç ö Ö ü Ü, CS = Czech specials ě š č ř ž ý á í é ú ů ď ť ň
+ uppercase, ES = Spanish ñ Ñ ¿ ¡ + accented vowels).

| model (quant) | vocab | RU | TH | TR | CS | ES |
|---|---|---|---|---|---|---|
| Qwen3.5-122B-A10B (Q4_K_XL) | 248,320 | 18,580 | 5,741 | 2,161 | 4,659 | 5,582 |
| Qwen3.6-35B-A3B (Q6_K_XL) | 248,320 | 18,580 | 5,741 | 2,161 | 4,659 | 5,582 |
| Qwen3.6-27B-UD (Q6_K_XL) | 248,320 | 18,580 | 5,741 | 2,161 | 4,659 | 5,582 |
| Qwen3.6-27B-Fable (Q6_K) | 248,320 | 18,580 | 5,741 | 2,161 | 4,659 | 5,582 |
| ornith-1.0-35b (Q6_K) | 248,320 | 18,580 | 5,741 | 2,161 | 4,659 | 5,582 |
| Gemma-4-12B (BF16) | 262,144 | 13,395 | 2,177 | 2,180 | 4,613 | 5,485 |
| Gemma-4-26B-A4B (Q6_K_XL) | 262,144 | 13,395 | 2,177 | 2,180 | 4,613 | 5,485 |
| Gemma-4-31B (Q6_K_XL) | 262,144 | 13,395 | 2,177 | 2,180 | 4,613 | 5,485 |
| gpt-oss-120b (Q6_K) | 201,088 | 14,212 | 1,561 | 2,103 | 4,094 | 4,800 |
| Inkling-Small (IQ3_XXS) | 201,024 | 14,212 | 1,561 | 2,103 | 4,094 | 4,800 |
| GLM-4.7-Flash-UD (Q6_K_XL) | 154,880 | 9,928 | 145 | 850 | 1,553 | 1,999 |
| GLM-4.7-Flash-REAP (Q6_K_XL) | 154,880 | 9,928 | 145 | 850 | 1,553 | 1,999 |
| Mistral-Small-4-119B (Q4_K_XL) | 131,072 | 7,686 | 567 | 1,505 | 4,138 | 4,889 |
| Nemotron-3-Super-120B-A12B (Q3_K_XL) | 131,072 | 7,686 | 567 | 1,505 | 4,138 | 4,889 |
| Step-3.7-Flash (Q2_K_XL) | 128,896 | 5,252 | 1,267 | 331 | 1,243 | 1,382 |
| DeepSeek-V4-Flash (IQ2_M) | 129,280 | 5,252 | 1,267 | 331 | 1,243 | 1,382 |
| DeepSeek-V4-Flash-0731 (IQ3_XXS) | 129,280 | 5,252 | 1,267 | 331 | 1,243 | 1,382 |
| Laguna-S-2.1 (Q4_K_XL) | 100,352 | 807 | 48 | 72 | 160 | 198 |

## Reading

- **Ranking (multilingual tokenizer support):** Qwen family ≫ Gemma ≈ gpt-oss /
  Inkling > GLM ≈ Mistral / Nemotron > Step / DeepSeek > Laguna.
- **Thai is the discriminating language.** Qwen is the clear pick (5,741
  tokens; distinct-glyph coverage 68/87); GLM is surprisingly weak (145) and
  Laguna is byte-fallback only (48). Thai is also the one language this repo's
  embedder side already flagged as the likeliest weak spot.
- **Turkish** is thin for Step/DeepSeek (331) and Laguna (72); Qwen/Gemma are
  the comfortable ones.
- **Czech and Spanish** are solidly covered everywhere above Laguna (relevant:
  Czech is the only non-English language v1 ships).
- GLM covers all 256 Cyrillic codepoints (single-letter coverage, incl.
  extended) but has few Thai subwords; Qwen has fewer distinct Cyrillic letters
  (101) but ~2× the Cyrillic *subwords* — deeper word-level coverage.

## Tokenizer fingerprints (identical counts ⇒ shared tokenizer)

These pairs/triples have byte-identical script counts, i.e. the same
tokenizer. Useful for provenance, and it means the numbers above should be
deduplicated before they inflate a comparison:

- **Qwen3.5-122B, Qwen3.6-35B, Qwen3.6-27B, Qwen3.6-27B-Fable, ornith-1.0-35b**
  — one 248,320 vocab (ornith is Qwen-based).
- **gpt-oss-120b and Inkling-Small** — one 201k vocab (Inkling-Small is
  gpt-oss / Llama-4-family-based).
- **Step-3.7-Flash and DeepSeek-V4-Flash (both quants)** — one 129k vocab
  (sizes 128,896 vs 129,280 differ only in padding/specials; counts identical).
- **Mistral-Small-4-119B and Nemotron-3-Super-120B** — one 131,072 vocab
  (Llama-3-style).
- **GLM-4.7-Flash-UD and -REAP** — one 154,880 vocab (expected: same base).

## Method + gotchas

- Parse: GGUF header → metadata KVs only (mmap, `tokenizer.ggml.tokens`
  string-array), tensor data never touched. Sharded models: read shard `00001`.
- **Gotcha that made the first two attempts wrong:** non-Gemma tokenizers store
  their vocab in the GPT-2/llama3 **byte-display encoding**, not UTF-8 text —
  each byte of a multi-byte sequence becomes one Latin-1 char (space → `Ġ`,
  `0x00–0x20` → `U+0100+`, `0x7F–0xA0` → `U+0121+`, `0xAD` → `U+0143`). Grepping
  the decoded strings for scripts directly returns garbage (the first run
  "found" 26,858 Turkish tokens in Qwen that were actually display chars).
  The mapping is inverted back to raw bytes first; script detection then runs
  on that byte stream (2- and 3-byte UTF-8 pattern matching, so it works even
  for tokens that don't decode cleanly). Gemma is real UTF-8 + 256 `<0xNN>`
  tokens and is detected as such.
- Thai range: only U+0E00–0E7F (`E0 B8-B9`) counts; `E0 BA+` is Lao/Tibetan and
  is excluded.
- Caveat: tokenizer coverage ≠ quality. Representability is necessary but not
  sufficient — training-data distribution decides actual fluency, and none of
  this was verified by generation. This is a screening tool, not a verdict.
