---
name: qwen38-specdec-landscape
description: "Qwen3.8 speculative-decoding state (2026-08-27): Vulkan spec-decode benchmarks taken before llama.cpp PR #27812 are INVALID (issue #27805 graph-optimizer bug, wrong output at temp 0); DFlash2 merged to staging branch xsn/dflash2, NOT master, with open correctness bugs; DFlash2 sidecar GGUFs exist for dense 27B only — no draft head for Flash-Next; --n-cpu-ffn is a discrete-VRAM tool, no-op on UMA."
metadata:
  node_type: memory
  type: project
---

Researched 2026-08-27 (LLM serving speed for Qwen3.8 on the Strix Halo box;
box-specific measurements live in memory-local, NOT here). Fast-moving
ecosystem — re-check the PR states before relying on them.

## The trap: Vulkan spec-decode numbers were invalid until #27812

- llama.cpp issue #27805: `ggml_vk_graph_optimize` misses dependencies
  between views of one tensor and can reorder across them; the DFlash2
  verify graph triggers it. On **Vulkan** the main model "accepts" draft
  tokens it did not choose — wrong output at temperature 0. CUDA unaffected.
  Fix: PR #27812, open as of 2026-08-27.
- Consequence: **every Vulkan speculative-decoding acceptance/speed
  benchmark taken before the fix is invalid** — including Nathanw1014's
  gfx1151 DFlash2 validation (docs/dflash2-strix.md, "~2x at all depths").
  `GGML_VK_DISABLE_GRAPH_OPTIMIZE=1` restores correctness but is not a
  benchmarking workaround.
- Rule: before trusting or reusing any Vulkan spec-decode figure, confirm
  #27812 (or successor) is in the build.

## Upstream DFlash2 status

- PR #27342 merged 2026-08-27 by ngxson into staging branch `xsn/dflash2`,
  **not master** (base branch was switched minutes before merge). A "merged!"
  Reddit post is not shippability.
- Open bugs at merge time: #27407 (greedy divergence from non-spec baseline
  under batched verification), #27408 (mtmd image chunks leave holes in the
  draft KV cache → `llama_decode` rc=-1 → HTTP 500 with draft-dflash — bites
  image-capable serving, i.e. /image traffic).
- Reference eval (Apple M5 Pro, dense Qwen3.8-27B Q4_K_M, GSM8K-8): 10.4
  bare → 19.3 t/s (1.85x, acceptance ~5/8); draft quant (BF16/Q8_0/Q4_K_M)
  barely matters.

## Draft heads: the real gating factor

- DFlash2 sidecar GGUFs exist for **dense Qwen3.8-27B only**: incoai
  (Apache-2.0), z-lab (Q8_0), agentionai (FP4).
- **No draft head (MTP or DFlash2) published for Qwen3.8-Flash-Next** as of
  2026-08-27. Unverified lead: Nathanw1014's README says the Flash-Next
  "draft head ships as a separate sidecar" needing GTT headroom — the first
  claim anywhere that one exists; no HF repo named. Verify before re-trying.
- Speculation is the only decode lever on bandwidth-bound APUs: both Strix
  Halo forks measure bare single-stream decode at ~80% of theoretical
  bandwidth (memory wall); their kernel work moves prefill, not decode.

## Strix Halo fork landscape (both Vulkan/RADV, gfx1151)

- **LaurentZuijdwijk/llama.cpp**: adaptive speculation
  (`--spec-draft-adaptive --spec-draft-n-min 3`), ROCmFPx quants, batch 3-8
  mat-vec fix (5.4x at n=8), LDS stride fix (+7-13% prefill, RADV ≥ 25.3,
  driver-gated). Dense 27B + FP4 target + FP4 DFlash2 sidecar: 65 t/s
  structured (high-power burst) / 55 everyday profile; the Reddit "60 t/s"
  is the author's own number. Unknown whether it carries the qwen4exp arch
  (PR-27742) — check before expecting Flash-Next to load.
- **Nathanw1014/strix-halo-llamacpp**: FA dequant-once (quantized-KV prefill
  at depth, 2.66-3.26x on hd128), MoE prefill fixes (mmid rowlists, tiled
  concat +45-49%), bundled Mesa 26.3 so no host-driver dependency; explicit
  Flash-Next run docs (mmap flags, --n-cpu-moe, sidecar note). Decode at
  shallow context untouched by construction.
- `--n-cpu-ffn` (PR #26622, merged to master 2026-08-27): offloads first N
  dense-FFN layers to CPU — a discrete-VRAM fitting tool, **no-op on UMA**
  (GTT is system memory). Skip on Strix Halo.

## Re-check triggers

- #27812 merged AND `xsn/dflash2` reached master → upstream DFlash2 becomes
  viable on Vulkan; re-bench before believing any prior Vulkan spec figure.
- A Flash-Next MTP or DFlash2 sidecar GGUF appears on HF → Flash-Next decode
  re-try (memory-local flash entry holds the recipe and rollback context).
