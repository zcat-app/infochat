---
id: M1-920
title: "Tracked llamacpp prompt-cache RAM key, sized per class"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  LlamacppWiringTest.composeExposesCacheRamKeyWithClassWrites
  (to-be-written — converted at /tick start per workflow §0: written
  first, run RED; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md).
  Verified absence probe on this checkout (2026-08-23): grep -rn
  'LLAMA_ARG_CACHE' docker-compose.yml docker-compose.gpu.yml
  prod/scripts/ returns ZERO matches — llama-server's prompt-cache MiB
  limit (a FIXED 8192 MiB default that does NOT scale with ctx) is not
  operator-settable on the tracked surface, so every default deployment
  ships the churn-prone 8 GiB cache. Live corroboration
  (.agents/memory-local/prod-state-post-upgrade-20260823.md): the eval
  backlog LRU-evicted the chat turn's prefix between turns; raising the
  limit to 16384 in prod's UNTRACKED overlay measured an identical
  11.7k-token chat prompt at 9.9 s cold → 0.97 s cached (f_keep=0.995).
analysis_ref: docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md
blocked_by:
  - M1-909
files_scope:
  - docker-compose.yml
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    LLAMA_ARG_CACHE_REUSE in any form — verified REJECTED by the pinned
    server ("cache_reuse is not supported by this context" — hybrid-
    attention KV layout; prod-state file). Never re-proposed.
  - >-
    The llamacpp-embeddings service — the cache key applies to the
    generative service only (embedding prompts are tiny; the M1-905
    generative/embeddings split); the wiring test pins its ABSENCE there.
  - >-
    Raising the BASE compose render default — `:-8192` keeps today's
    byte-stable render (the image's own default): a larger base default
    would exceed the CPU-class 7g container cap (P12). The raised value
    is a GPU-class wizard write only.
  - >-
    The GPU-class ctx/parallel re-derivation — sibling M1-921 (blocked by
    M1-918 + this ticket). This ticket adds no ctx/parallel assertion and
    changes none.
  - >-
    docker-compose.gpu.yml — the cache limit is not GPU-bound wiring; the
    key lives in the base file beside the M1-905 serving keys and the
    wizard sizes it per class. The overlay stays image/devices/ngl only.
  - >-
    Removing prod's untracked docker-compose.mtp.yml overlay (which
    carries CACHE_RAM=16384 today) — an ops action riding rollout, not
    this diff.
acceptance:
  - "REPRODUCTION closed: LlamacppWiringTest.composeExposesCacheRamKeyWithClassWrites (test_plan.adds) passes — the generative llamacpp service in docker-compose.yml gains exactly `LLAMA_ARG_CACHE_RAM: \"${INFOCHAT_LLAMACPP_CACHE_MB:-8192}\"`; the test asserts the interpolation form, the 8192 default render (byte-stable: a deployment that sets nothing renders the image's own default, the M1-905 P10 discipline), the key's ABSENCE on llamacpp-embeddings, and the absence of the plausible wrong names LLAMA_ARG_CACHE_SIZE / LLAMA_ARG_CACHE_MB / LLAMA_ARG_PROMPT_CACHE across docker-compose.yml, docker-compose.gpu.yml and prod/scripts/ (P11; the M1-908 surface-scan pattern)."
  - "ENV-NAME + DEFAULT VERIFICATION (P11, ASSUMPTION the implementor verifies and records BEFORE landing): on the pinned image ghcr.io/ggml-org/llama.cpp:server-vulkan-b9776, `llama-server --help` documents the cache-RAM flag and its env name is LLAMA_ARG_CACHE_RAM with default 8192 MiB; the recorded probe output rides the commit message. If --help disagrees, the key name/default follow the verified output and the correction is recorded in the commit message; the acceptance-1 test asserts the landed literal so a later rename fails the build."
  - "GPU-CLASS WRITE WITH ARITHMETIC (P12): in the llamacpp GPU branch (gpu_on=1), 4-llm.sh writes INFOCHAT_LLAMACPP_CACHE_MB=16384 via set_secret (printed, never prompted — the M1-905 posture), with the sizing comment at the write site citing its inputs: the 40g GPU-class container cap minus ~21 GB Q6_K_XL weights minus KV for the serving shape minus ~2 GB spec-decode head (M1-909) leaves room for a 16 GiB prompt cache, while the CPU-class 7g cap does not. The gpuHostGetsBenchmarkServingClassAndTiming drive asserts the new secret; the CPU-class drive and forcedGpuOffKeepsCpuServingClass assert its ABSENCE (an unconditional write leaking 16384 onto a 7g host fails the build — the M1-905 P15 absence-twin shape)."
  - "FAILURE-MODE (P12, negative): the acceptance-1 test's default-render assertion fails if the `:-8192` default is raised — the base render must stay inside the base 7g container cap; and the wrong-name surface scan fails if any LLAMA_ARG_CACHE_* variant outside the verified pair appears."
  - "DRIVE-LAYER DISCIPLINE (P14; §8): every pre-existing LlamacppWiringTest drive's stdin, assertions, and fake scripts are byte-untouched except the single authorized extension of gpuHostGetsBenchmarkServingClassAndTiming / forcedGpuOffKeepsCpuServingClass with the cache-secret presence/absence assertions named in item 3; the M1-908/M1-909 drives (this ticket is blocked behind them) read the same end state. Verification: git diff on the test file shows only the new method plus those assertion additions, and ./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest' is green."
  - "DOCS (docs/design/07-deployment.md §7.8.3, §7.7.2): §7.8.3's operator-key table gains the INFOCHAT_LLAMACPP_CACHE_MB row (default 8192 = the image default; maps to LLAMA_ARG_CACHE_RAM, name live-verified on the pinned image; the token cache limit scales with ctx but this MiB limit does NOT) and the GPU-class paragraph records the 16384 wizard write with its memory arithmetic and the measured effect (identical 11.7k-token prompt 9.9 s cold → 0.97 s cached, citing .agents/memory-local/prod-state-post-upgrade-20260823.md); §7.7.2's step-4 row records the GPU-class write. Verification: git diff --stat docs/ shows exactly docs/design/07-deployment.md."
  - "./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest' is green AND mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — composeExposesCacheRamKeyWithClassWrites (static compose layer: interpolation form, 8192 default render, embeddings absence, wrong-name surface scan) plus the GPU-drive cache-secret assertion and its CPU-side absence twins
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — AUTHORIZED (acceptance item 5): cache-secret presence/absence assertions added to gpuHostGetsBenchmarkServingClassAndTiming and forcedGpuOffKeepsCpuServingClass; stdin and every other assertion byte-untouched
  preserves:
    - all tests currently green on main
    - every other pre-existing LlamacppWiringTest method, byte-untouched (the M1-905 drive-layer discipline), including the M1-905 serving-key pins this ticket does not change (P14: M1-921 owns the ctx re-derivation)
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
  - docs/design/07-deployment.md §7.8.3
  - docs/design/07-deployment.md §7.7.2
decision_refs:
  - D49
---

# M1-920: Tracked llamacpp prompt-cache RAM key, sized per class

## Context

llama-server's prompt-cache MiB limit is a FIXED 8192 MiB default — it
does not scale with ctx (the token limit does) — and no tracked key
covers it (verified grep, `reproduction:`). Under prod's eval backlog the
cache LRU-evicted the chat turn's prefix between turns: identical
11.7k-token prompt 9.9 s cold → 0.97 s cached once prod's untracked
overlay set `LLAMA_ARG_CACHE_RAM=16384`
(`.agents/memory-local/prod-state-post-upgrade-20260823.md`). Every
default deployment silently ships the churn-prone 8 GiB cache. This
ticket puts the key on the tracked surface with a per-class sizing story;
it does NOT re-derive the serving ctx (M1-921) and never touches the
verified-rejected `CACHE_REUSE`. Analysis: `analysis_ref:`.

## Root cause

M1-905 exposed serving shape (parallel/ctx) and M1-744 the caps, but the
cache-RAM knob post-dates both — its effect was only measured on
2026-08-23 (the probe rig, `.scratch/VULKAN-MTP-PROBE-2026-08-23.md`).
The generative service declares MODEL/HOST/PORT/REASONING/N_PARALLEL/
CTX_SIZE and nothing else (docker-compose.yml:304-331). No defect in
existing code — an absent config surface, reproduced by the RED wiring
test.

## Pitfalls

Numbered with the analysis document; this ticket carries P11, P12, P14.

- P11: env-name trap — a misspelled `LLAMA_ARG_*` name is silently
  ignored (the M1-905 `LLAMA_ARG_PARALLEL` lesson; M1-908's env-name
  pitfall). The name and default are `--help`-verified on the pinned
  image BEFORE landing, and the test pins exact name + wrong-name
  absence. `CACHE_REUSE` is verified-rejected on this server — never
  proposed.
- P12: cache vs container memory cap — the prompt cache lives in the
  container's address space. A raised default inside the CPU-class 7g
  cgroup is an OOM invitation; the 16384 write rides ONLY the GPU-class
  branch (40g cap) with the arithmetic (weights ≈ 21 GB + KV + 16 GiB
  cache + ~2 GB spec head) recorded at the write site. The base render
  stays 8192, byte-stable (M1-905's default-render discipline).
- P14: sibling calibration — the serving lane serializes
  M1-908 → M1-909 → this ticket → M1-921 (shared compose file, shared
  wizard branch, shared wiring test; never `--parallel`). This ticket
  pins no ctx/parallel value so it cannot break M1-921's mandated move
  (the fixtures-calibrated-to-end-state rule), and M1-921's own text
  pre-authorizes its ctx pin change.

## Approach

Derived from `spec_refs:`: docs/design/07-deployment.md §7.8.3 (the
M1-744/M1-905 operator-settable-key pattern with pinned, byte-stable
defaults) and docs/spec/deployment.md §Deployment scenarios (D49: the
operator owns local-backend serving choices).

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Run the acceptance-2 `--help` verification on the pinned image and
     record the output in the commit message — this decides the key
     name/default BEFORE any file edit (P11).
  2. Compose base: add the interpolated key to the generative service
     beside the M1-905 serving keys, with a comment in the M1-905 style
     (fixed-default rationale + the scales-with-ctx distinction).
  3. Wizard: set_secret the key in the GPU branch only, with the
     sizing comment (P12).
  4. Tests: the new static-drive method RED first (workflow §0), then
     the authorized GPU/CPU assertion additions.
  5. Docs (§7.8.3 row + paragraph, §7.7.2 step-4 row), then the module
     run + `mvn verify`.
- **Controls to preserve (§10):** the M1-905/M1-744 render pins (default
  render byte-stable; GPU keys only under the class condition), the
  M1-908 spec keys and M1-909 wizard writes (this ticket is blocked
  behind them and touches neither), the REASONING=off pin, the
  anti-downgrade image pins, and every pre-existing drive outside the
  two authorized assertion additions.
- **Pitfall→mitigation:** P11→step 1 + the wrong-name scan; P12→step 3's
  class-conditional write + the absence twins + the default-render pin;
  P14→blocked_by + the fixtures note above.

## Definition of done

The tracked key renders on the generative service with the verified
name and the byte-stable 8192 default; the wizard writes 16384 on the
GPU class only, with the arithmetic recorded; the embeddings service
stays free of the key; the wrong-name scan and default-render pin hold;
pre-existing drives are untouched except the two authorized assertion
additions; the docs land; module tests + `mvn verify` green.

## Verification

- P11 → the recorded `--help` probe (acceptance 2) +
  composeExposesCacheRamKeyWithClassWrites's exact-name and wrong-name
  assertions.
- P12 → the GPU drive asserts 16384; the CPU drive and
  forcedGpuOffKeepsCpuServingClass assert ABSENCE (deleting the gpu_on
  condition turns them RED); the default-render pin fails a raised base
  default.
- P14 → blocked_by ordering + the test-file diff shape (acceptance 5).
- FAILURE-MODE coverage (beyond the reproduction) → acceptance item 4
  (raised-default mutation; stray-name mutation).
- acceptance items 5, 6, 7 → the diff-shape check, the docs diff-stat
  probe, the named module run + `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: CACHE_REUSE (verified-rejected), the embeddings
service, any base-default raise (P12), the ctx/parallel re-derivation
(M1-921), the GPU overlay, and the prod overlay's removal (ops action).
Pre-existing test modification (§8): exactly the two drives named in
`test_plan.modifies`, assertion-additions only; any other conflict is a
start-hurdle escalation, not a silent edit.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-920-llamacpp-cache-ram-key.md
```
