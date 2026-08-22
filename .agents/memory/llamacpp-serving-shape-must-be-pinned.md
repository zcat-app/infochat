---
name: llamacpp-serving-shape-must-be-pinned
description: "llama-server's LLAMA_ARG_PARALLEL is silently IGNORED (only LLAMA_ARG_N_PARALLEL works), and an unpinned serving shape renders auto slots=4 x model-max ctx — always pin N_PARALLEL / CTX_SIZE / N_GPU_LAYERS, and raise the memory cap for GTT."
metadata:
  type: project
---

Two independent traps, both measured live (v2.0.0 campaign, b9776 images):

1. **The env name.** llama-server reads `LLAMA_ARG_N_PARALLEL`; a
   `LLAMA_ARG_PARALLEL` export is accepted by nobody and produces no warning —
   the server just comes up with its own default. First campaign attempt left
   `n_slots = 4` while believing it had pinned 1.
2. **The unpinned render is never the tested shape.** With no N_PARALLEL /
   CTX_SIZE the server logs `n_parallel is set to auto, using n_parallel = 4`
   and allocates each slot the model's maximum context (262144 for
   Gemma-4-26B) plus an 8 GiB prompt cache — a shape no benchmark or
   acceptance run ever used, and far from the accepted class (parallel 1,
   ctx 8192 → ~49 tok/s decode on the reference Vulkan host; prod candidate
   parallel=3 / ctx 32768).

**Vulkan does not relax the memory cap.** GTT pages are pinned system memory
charged to the container's cgroup, so a GPU-resident 26B-Q6-class model OOMs
under the base 7g cap — the cap must fit weights + KV (40g measured) even
though execution is on the GPU. CPUs likewise: the base 3.0 is VPS sizing.

**How to apply:** never run the base compose render for a serving decision —
pin `LLAMA_ARG_N_PARALLEL` / `LLAMA_ARG_CTX_SIZE` / `LLAMA_ARG_N_GPU_LAYERS`
explicitly and size `INFOCHAT_LLAMACPP_MEMORY` / `_CPUS` to the host class.
Tracked knobs: `INFOCHAT_LLAMACPP_{PARALLEL,CTX}` env keys and the overlay's
ngl 999 pin (M1-905); resource caps (M1-744). Verify after every recreate:
log lists the Vulkan device, `n_slots` and per-slot ctx match the pin.
