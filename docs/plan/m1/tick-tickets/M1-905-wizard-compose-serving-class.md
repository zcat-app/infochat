---
id: M1-905
title: "Compose+wizard llamacpp serving keys and GPU timing class"
status: pending
created: 2026-08-22
last_updated: 2026-08-22
flow: tick
reproduction: >-
  Probe (run on this checkout, 2026-08-22): the `llamacpp` service block in
  docker-compose.yml (:288-354) declares exactly LLAMA_ARG_MODEL /
  LLAMA_ARG_HOST / LLAMA_ARG_PORT / LLAMA_ARG_REASONING — `grep -c
  'LLAMA_ARG_N_PARALLEL\|LLAMA_ARG_CTX_SIZE' docker-compose.yml` is 0, so
  serving shape (parallel slots, context size) is not operator-settable on
  the tracked surface (the v2.0.0 campaign's GPU pin lived in an UNTRACKED
  overlay); and prod/scripts/4-llm.sh:898-908 has remote / pi / vps-class-CPU
  timing branches but NO GPU/benchmark branch, so a measured 48.8 tok/s
  Vulkan host gets CPU-class 240s/600 values. Closing tests (to-be-written):
  LlamacppWiringTest.composeExposesParallelAndCtxKeysWithSafeDefaults and
  LlamacppWiringTest.gpuHostGetsBenchmarkServingClassAndTiming.
analysis_ref: docs/plan/m1/tick-analysis/v2-acceptance-blockers.md
blocked_by: []
files_scope:
  - docker-compose.yml
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The llamacpp-embeddings service — no evidence the 768-dim embedder
    needs slot/context sizing; single-slot embedding stays.
  - >-
    A new `benchmark`/`gpu` profile. Profiles are `laptop vps pi
    remote-llm` (1-profile.sh:11); the GPU class keys on the wizard's
    existing gpu_on probe, and the profile taxonomy is a bigger surface.
  - >-
    Changing any in-app default (the 30s `orElse(30000)` safety net stays —
    M1-550's out-of-scope honored) and any change to the M1-512/M1-744
    resource-cap values or their keys.
  - >-
    New wizard PROMPTS. The serving keys and GPU timing branch are
    computed-and-written, never asked — the GPU probe precedent is
    "printed, never prompted" (4-llm.sh:630-632), and every existing
    LlamacppWiringTest drive feeds byte-exact positional stdin.
  - >-
    Re-tuning the measured prod candidate (parallel=3, ctx 32768) or the
    10b numbers themselves — they are the owner-accepted recommendation
    (`.scratch/v2-fix-10b-serving-characterization-20260822.md`).
acceptance:
  - "REPRODUCTION closed (compose half): LlamacppWiringTest.composeExposesParallelAndCtxKeysWithSafeDefaults (test_plan.adds) passes — docker-compose.yml's generative `llamacpp` service gains exactly `LLAMA_ARG_N_PARALLEL: \"${INFOCHAT_LLAMACPP_PARALLEL:-1}\"` and `LLAMA_ARG_CTX_SIZE: \"${INFOCHAT_LLAMACPP_CTX:-4096}\"`, and the test asserts both interpolation forms, the default render values, and that `LLAMA_ARG_PARALLEL` (the silently-ignored wrong name — campaign trap, P9) appears NOWHERE in docker-compose.yml or prod/scripts/. ASSUMPTION the implementor verifies and records BEFORE landing the defaults: llama.cpp server-b9776's own defaults for `--parallel` and `--ctx-size` (via the pinned image's `--help`). The parallel default of 1 is a DELIBERATE pin to the acceptance-tested posture, not a render-preservation claim: campaign evidence (final report §1) shows the image's implicit default is slots=4, which quadruples KV on CPU-class hosts and was never a tested configuration; this ticket authorizes that behavior pin explicitly. If `--ctx-size`'s documented default differs from 4096, the default follows the verified server default and the correction is recorded in the commit message."
  - "WIZARD SERVING KEYS (P11, P13): in the llamacpp branch, 4-llm.sh writes INFOCHAT_LLAMACPP_PARALLEL and INFOCHAT_LLAMACPP_CTX to secrets.env via the existing set_secret helper — 3 / 32768 when gpu_on=1 (the 10b prod candidate), 1 / 4096 otherwise — printed, never prompted. LlamacppWiringTest.gpuHostGetsBenchmarkServingClassAndTiming (test_plan.adds) drives the existing INFOCHAT_LLAMACPP_GPU=on seam and asserts both secrets; a CPU-class drive asserts 1/4096."
  - "GPU/BENCHMARK TIMING BRANCH (P11): the timing case (4-llm.sh:898-908) gains a branch keyed on backend==llamacpp AND gpu_on==1 (written `${gpu_on:-0}` — gpu_on is UNSET for the ollama/remote arms) recommending chat 60000/600 + summarizer 60000/400 — derived per the M1-548 invariant (:888-890) from the 10b P3 worst case: 600 tokens at 31.4 tok/s ≈ 19.1s decode + ≤ ~7s prompt (≤700-token prompts at ~100 tok/s) ≈ 26s ≪ 60s, and idle slots cost nothing (10b solo-on-P3 48.5 tok/s), so 60s cannot hide a wedged turn the way 240s did (the D-16 lesson). The same gpuHostGetsBenchmarkServingClassAndTiming drive asserts the written props; every pre-existing drive keeps its 240000/600 + 240000/400 assertions byte-untouched (the branch must not fire for ollama/remote)."
  - "D-15 NIT — CPU-CLASS-ONLY INGEST WRITE (P12): on CPU-class local backends (llamacpp with gpu_on=0, and the ollama branch on laptop/vps/pi), the wizard writes infochat.llm.{security,tagger,entity,classifier,translator}.timeout-ms at the ANSWERED chat timeout value (the prose slider the D-15 recommendation names) via set_prop, no new prompts. GPU-class and remote write NOTHING for ingest roles: 30s is measured adequate ~8x on the benchmark class (93/93 attempt=1, worst 3.76s — `.scratch/v2-fix-d15-measurement-20260822.md`) and remote was sized deliberately by M1-550. LlamacppWiringTest gains a CPU-class drive asserting the five keys at the answered value and the GPU drive asserts their ABSENCE (failure-mode: the measured-adequate class must not inherit the slow-class write)."
  - "FAILURE-MODE: LlamacppWiringTest.forcedGpuOffKeepsCpuServingClass (test_plan.adds) — INFOCHAT_LLAMACPP_GPU=off on the llamacpp branch yields secrets 1/4096, the 240000/600 + 240000/400 timing, and the CPU-class ingest writes: a GPU branch that leaks onto a CPU-forced host fails this drive."
  - "DOCS: docs/design/07-deployment.md §7.8.3's env-key table gains INFOCHAT_LLAMACPP_PARALLEL and INFOCHAT_LLAMACPP_CTX rows (defaults, what they map to, the LLAMA_ARG_N_PARALLEL naming trap); §7.7.2's step-4 row records the GPU-class serving-key writes and timing recommendations; docs/design/05-llm-and-embeddings.md's timeout paragraph (:202-206) records that CPU-class wizard runs scale the five ingest-role timeouts with the prose slider and why (30s in-app default fits fast backends only). Verification: `git diff --stat docs/` shows exactly these two design files."
  - "Drive-layer discipline (P13): every pre-existing LlamacppWiringTest drive's stdin, assertions, and fake docker/curl scripts are byte-untouched — `git diff` shows no hunk inside any existing test method body or fake-script string (the M1-896 discipline)."
  - "`./mvnw -B -pl infochat-llm-adapter test -Dtest='LlamacppWiringTest,RemoteLlmWiringTest'` is green AND mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — composeExposesParallelAndCtxKeysWithSafeDefaults, gpuHostGetsBenchmarkServingClassAndTiming, forcedGpuOffKeepsCpuServingClass, and a CPU-class ingest-write drive (plus its GPU-absence assertion)
  preserves:
    - all tests currently green on main
    - Every pre-existing LlamacppWiringTest drive — stdin strings, assertions, fake docker/curl scripts byte-untouched, including the 240000/600 + 240000/400 timing pins, the M1-744 render pins (default render, overlay pins, LLAMA_ARG_REASONING off pin), and the anti-downgrade image pins.
spec_refs:
  - docs/design/07-deployment.md §7.7.2
  - docs/design/07-deployment.md §7.8.3
  - docs/design/05-llm-and-embeddings.md
decision_refs:
  - D49
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-905: Compose+wizard llamacpp serving keys and GPU timing class

## Context

The v2.0.0 campaign measured the prod serving class on the benchmark
runtime (Radeon 8060S Vulkan, 26B Q6): parallel=3 / ctx 32768, aggregate
94.1 tok/s, idle slots free
(`.scratch/v2-fix-10b-serving-characterization-20260822.md`) — but the pin
lived in an UNTRACKED overlay because the tracked compose surface exposes no
serving-shape keys. The wizard's timing case (4-llm.sh:898-908) tops out at
vps-class CPU, so a 48.8 tok/s Vulkan host gets 240s chat timeouts (a wedged
turn burns 4 minutes — the D-16 lesson). And ingest roles ride the in-app
30s default: measured adequate ~8x on the benchmark class (D-15 downgrade)
but genuinely failing on CPU-class serving (101 failures/3h on the
2.9 tok/s runtime). Owner disposition (DECIDED 2026-08-22): expose
INFOCHAT_LLAMACPP_PARALLEL / INFOCHAT_LLAMACPP_CTX through the tracked
compose/wizard surface, add a GPU/benchmark timing branch, and fix the D-15
consistency nit. Analysis:
`docs/plan/m1/tick-analysis/v2-acceptance-blockers.md`.

## Root cause

Three gaps, all verified: (1) `docker-compose.yml:288-354` declares only
MODEL/HOST/PORT/REASONING for the generative service — M1-744 exposed
resource CAPS, never serving shape; (2) the M1-550/M1-553 timing case
predates any GPU runtime, so its slowest local branch is vps-class CPU
(240s), and profiles carry no GPU signal (`1-profile.sh:11`: `laptop vps pi
remote-llm`) — the only GPU signal is the llamacpp arm's `gpu_on` probe
(4-llm.sh:633-649); (3) M1-550 explicitly scoped ingest-role timeouts out
("eval-task outputs are a handful of tokens and ride the in-app defaults")
— true only when the backend is fast: on CPU-class serving a 338-token
tagger prompt alone takes ~11s of prompt processing and calls time out,
while on the benchmark class the worst observed call is 3.76s.

## Pitfalls

- P9: **The env-name trap** — `LLAMA_ARG_PARALLEL` is silently IGNORED
  (campaign evidence: first attempt left slots=4); only
  `LLAMA_ARG_N_PARALLEL` works. The test asserts the exact name and the
  absence of the wrong one.
- P10: **Default-render claims** — the image's implicit default is slots=4
  (campaign evidence), so `:-1` is a deliberate pin to the
  acceptance-tested posture, stated openly, not hidden behind "unchanged";
  the `:-4096` ctx default claims llama.cpp's documented server default and
  the implementor verifies BOTH defaults on the pinned b9776 image before
  landing them (ASSUMPTION, acceptance 1).
- P11: **Keying the GPU branch on profile** — no benchmark/GPU profile
  exists; the branch keys on `gpu_on`, which is UNSET for the ollama/remote
  arms — the post-`esac` timing block must use `${gpu_on:-0}` or every
  non-llamacpp drive breaks.
- P12: **The D-15 fix applied to the wrong class** — an unconditional
  ingest-role write re-imposes slow-class timeouts where 30s is measured
  adequate (benchmark class) or deliberately sized (remote); a doc-only fix
  leaves the CPU class — the only class with failure evidence — broken.
  CPU-class-only, scaled with the prose slider.
- P13: **Prompt-surface growth breaks every existing wizard drive** —
  LlamacppWiringTest drives feed byte-exact positional stdin; the new
  writes are computed, never prompted (the GPU probe precedent).

## Approach

Derived from docs/design/07-deployment.md §7.7.2 (wizard owns the GPU
overlay decision, printed-never-prompted) + §7.8.3 (operator-settable
serving keys with pinned defaults, the M1-744 pattern) and
docs/design/05-llm-and-embeddings.md (the M1-548 sizing invariant,
timeout semantics).

- **Files to touch:** `docker-compose.yml`, `prod/scripts/4-llm.sh`,
  `LlamacppWiringTest.java`, `docs/design/07-deployment.md`,
  `docs/design/05-llm-and-embeddings.md`.
- **Steps, in order:**
  1. Verify the b9776 server defaults (`--parallel`, `--ctx-size`) and
     record the output in the commit message (acceptance 1's ASSUMPTION).
  2. Compose: add the two interpolated env keys to the generative service
     only; add the wiring test pinning names, forms, and default render.
  3. Wizard: set_secret the two keys in the llamacpp arm (3/32768 when
     gpu_on, else 1/4096); add the GPU timing branch to the timing case
     (`${gpu_on:-0}` guard); add the CPU-class-only ingest-role write after
     the prose answers exist (it uses the ANSWERED chat timeout, so it must
     follow prompt_timing).
  4. Tests: the three new drives, RED first where they pin new behavior
     (workflow §0); pre-existing drives untouched.
  5. Docs (§7.8.3 table rows, §7.7.2 step-4 row, 05-llm timeout paragraph),
     then the module test run + `mvn verify`.
- **Controls to preserve (§10):** the M1-744 render pins (default render of
  every pre-existing key byte-stable; overlay pins; `LLAMA_ARG_REASONING:
  "off"` pin), the M1-512 caps and their keys, the anti-downgrade image
  pins, the `--env-file` seam, the GPU probe's printed-never-prompted
  posture, and every existing wizard drive. The 30s in-app default stays
  the safety net — the wizard writes visible overrides, the code default is
  untouched (M1-550's posture).
- **Pitfall→mitigation:** P9 → step 2's name assertions; P10 → step 1 +
  the explicit pin statement in acceptance 1; P11 → `${gpu_on:-0}` and the
  untouched ollama/remote drives; P12 → the class-conditional write and the
  GPU-absence assertion; P13 → no new prompts, drive-layer discipline
  acceptance.

## Definition of done

Every acceptance item verified by its named test/probe: compose keys with
pinned defaults and the wrong-name grep; wizard secrets for both classes;
the GPU timing branch with its derivation; the CPU-class-only ingest write
with the GPU-absence failure-mode; the forced-GPU-off failure-mode drive;
the doc updates; pre-existing drives byte-untouched; module tests + `mvn
verify` green.

## Verification

- P9 → `composeExposesParallelAndCtxKeysWithSafeDefaults` (exact key names)
  + the acceptance-1 grep over docker-compose.yml and prod/scripts/.
- P10 → the same test's default-render assertions + the implementor's
  recorded `--help` verification (acceptance 1).
- P11 → `gpuHostGetsBenchmarkServingClassAndTiming` (branch fires) + the
  byte-untouched ollama/remote/CPU drives (branch does not fire).
- P12 → the CPU-class drive asserts the five ingest keys at the answered
  chat timeout; the GPU drive asserts their absence.
- P13 → `git diff` shows no hunk inside any pre-existing drive method or
  fake-script string.
- Failure-mode (negative, beyond the reproduction) →
  `forcedGpuOffKeepsCpuServingClass`: a GPU-class leak onto a CPU-forced
  host must never happen — under INFOCHAT_LLAMACPP_GPU=off the drive
  asserts CPU-class secrets (1/4096), the 240000 timing, and the ingest
  writes; the ollama/remote drives (gpu_on unset) prove the GPU branch
  does not fire outside the llamacpp GPU arm.
- acceptance 5 → `forcedGpuOffKeepsCpuServingClass` — GPU forced off must
  yield pure CPU-class output (serving keys 1/4096, 240000 timing, ingest
  writes present).
- acceptance 6 → `git diff --stat docs/` shows exactly the two design
  files.
- acceptance 8 → the named module test run + `mvn verify`.

## Out-of-scope

The embeddings service (no sizing evidence). A new profile (the GPU signal
is the wizard's own probe). In-app defaults and resource-cap values/keys.
New prompts of any kind. Re-tuning the 10b candidate numbers. No
pre-existing test is modified; a drive that genuinely conflicts is a
start-hurdle escalation, not a silent edit (§8). Note for the implementor:
the remote branch keeps its M1-550 60000/1024 prose values and 30s-default
ingest roles — this ticket adds nothing there.
