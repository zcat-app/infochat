---
id: M1-826
title: "Probe GPU capability and own the llamacpp overlay decision"
status: done
created: 2026-08-13
last_updated: 2026-08-15
flow: tick
reproduction: >-
  Probe (RED on main): `grep -n 'gpu\|GPU' prod/scripts/4-llm.sh` prints
  nothing — both llama.cpp bring-ups (:455-456 generative, :467-468
  embeddings) name only the base compose file, so docker-compose.gpu.yml
  (the M1-744 Vulkan build with /dev/dri passthrough) is never merged even on
  a host with render nodes. Live-observed 2026-08-11
  (.scratch/setup-hurdles.md item 10): a 23 GB Q6 MoE inferred on CPU on a
  GPU host — perceived as "model is slow / thinking must be on". Test:
  LlamacppWiringTest.gpuCapableHostMergesTheVulkanOverlayForBothLlamacppServices
  (written by `start`, run RED on main before any fix code, workflow §0;
  on any render-node-less host the auto probe alone reproduces the
  CPU-only bring-up).
analysis_ref: docs/plan/m1/tick-analysis/llm-wizard-robustness.md
blocked_by: []
files_scope:
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - docker-compose.gpu.yml and docker-compose.yml CONTENT — the overlay's
    image/devices/group_add keys and the base file's no-devices posture are
    already pinned (LlamacppWiringTest:173/:192); this ticket changes which
    -f files the wizard assembles, nothing inside either file.
  - The rootless-ACL gate and the in-container device-visibility
    verification (sibling M1-827) — this ticket lands the probe + the merge
    decision; a rootless host without ACLs still gets the silent trap until
    M1-827 (sequenced, blocked_by).
  - restore.sh's llama.cpp bring-ups (restore.sh:650,657 — batch A); the
    restore host re-runs this family's probe when batch A picks it up
    (analysis option E — no persisted GPU state is written for them).
  - The ComfyUI overlay and 4b-image.sh's hardware gate (batch C).
  - Any host port for the llama.cpp services (security.md §Trust boundaries
    item 8 — exposure beyond the compose network is an explicit operator
    action, never a wizard default).
acceptance:
  - "REPRODUCTION, now passing: LlamacppWiringTest.gpuCapableHostMergesTheVulkanOverlayForBothLlamacppServices — with the probe seam forcing GPU-present (INFOCHAT_LLAMACPP_GPU=on), the drive feeds the SAME positional stdin as the existing pinned-default drive (no new prompt, analysis P10) and asserts the fake-docker argv records BOTH llama.cpp ups carrying `-f docker-compose.yml -f docker-compose.gpu.yml` (base first, overlay second) with --env-file intact; FAILURE-MODE: an overlay merged on only ONE of the two ups fails (analysis P9). RED on main: no -f beyond the base file exists in the script."
  - "Probe keys on the Vulkan device, not ROCm (analysis P8): the auto probe tests /dev/dri/renderD* presence (the overlay passes /dev/dri only, docker-compose.gpu.yml:32-33; /dev/kfd is 4b-image.sh's ROCm lane). Probes: `grep -n 'renderD' prod/scripts/4-llm.sh` hits the probe; `grep -n 'kfd' prod/scripts/4-llm.sh` prints nothing."
  - "Override seam + printed decision (analysis P10): LlamacppWiringTest.forcedGpuOffKeepsTheBaseFileOnly (to-be-written, test_plan.adds) asserts INFOCHAT_LLAMACPP_GPU=off records base-file-only ups even with the GPU forced off against render nodes; unset = auto. The run prints one line naming the probe result, the chosen build (CPU vs Vulkan), and the override var — and never reads it from stdin. Probe: every pre-existing LlamacppWiringTest drive green UNMODIFIED (their positional stdin still lines up, GPU-host CI included)."
  - "Ollama-embeddings shape never merges the overlay (analysis P9 negative): LlamacppWiringTest.ollamaEmbeddingsUpNeverMergesTheGpuOverlay (to-be-written, test_plan.adds) drives emb_backend=ollama with GPU forced on and asserts the recorded llamacpp up carries the overlay while the ollama up (:475-476) does not."
  - "Live render + bring-up proof on the GPU host (analysis P14) — probe: `docker compose -f docker-compose.yml -f docker-compose.gpu.yml --env-file prod/runtime/secrets.env --profile prod --profile llamacpp config` renders image server-vulkan-b9776 and the /dev/dri device mapping for llamacpp; a real wizard run (or the equivalent up) on the GPU host reaches a healthy llamacpp on the Vulkan build (`docker inspect` shows server-vulkan-b9776). Captured to .scratch and cited in the commit message."
  - "Design record: docs/design/07-deployment.md §7.7.2's step-4 row states the wizard probes /dev/dri and applies docker-compose.gpu.yml itself when render nodes are present (override: INFOCHAT_LLAMACPP_GPU); §7.8.7's GPU-overlay paragraph (:1006-1013) notes the wizard now owns that application, with the manual second -f form remaining for non-wizard flows. Probes: `grep -n 'docker-compose.gpu.yml' docs/design/07-deployment.md` hits both sections."
  - "mvn verify from the repo root is green; bash -n prod/scripts/4-llm.sh passes."
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
      — gpuCapableHostMergesTheVulkanOverlayForBothLlamacppServices
      (reproduction), forcedGpuOffKeepsTheBaseFileOnly (override seam),
      ollamaEmbeddingsUpNeverMergesTheGpuOverlay (P9 negative).
  preserves:
    - all tests currently green on main
    - >-
      Every existing drive's positional stdin and every compose-content pin
      (gpuOverlayPinsVulkanImageAndDeviceKeysForBothServices :173,
      baseComposeDeclaresNoDevicePassthrough :192) — this ticket edits
      neither compose file and adds no prompt. The drive layer needs no shim
      change: the probe seam is the shipped INFOCHAT_LLAMACPP_GPU env var
      (the INFOCHAT_RUNTIME_DIR precedent, 4-llm.sh:29).
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/design/07-deployment.md §7.8.7
  - docs/spec/security.md §Trust boundaries
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: PASS
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: "5 files, 119 insertions(+), 19 deletions(-) (r1)"
clarity_check:
  lint: "tick-lint 0 findings, 0 BLOCKERs (2026-08-15)"
  self_check: >-
    All file:line citations verified against the tree (4-llm.sh:455-456/:467-468/:475-476/:643-644/:29,
    docker-compose.gpu.yml:29-44, LlamacppWiringTest:173/:192/:595-644, 07-deployment.md:796/:1006,
    4b-image.sh:777/:814, restore.sh:650/:657, setup.sh:164-167); census grep re-run clean, every
    returned path has a row; analysis pitfalls P3/P8/P9/P10/P14 all carried, P11/P12 belong to M1-827
    per the decomposition; blocked_by empty; no ambiguity. Started after rebasing onto landed M1-823
    (850ec830): its exit-3 class + usage line shift the bring-up line numbers (~+7) but do not change
    this ticket's region; its two new drives join test_plan.preserves ("all tests currently green on
    main"). Live-proof host is this Strix Halo box (renderD128 present, rootless ACLs applied).
---

# M1-826: Probe GPU capability and own the llamacpp overlay decision

## Context

The M1-744 GPU overlay (docker-compose.gpu.yml — Vulkan build of the same
pinned llama.cpp release, /dev/dri passthrough) exists, but applying it is a
manual operator act (07-deployment.md §7.8.7:1006-1013) that no wizard step
performs: 4-llm.sh's bring-ups name only the base file (:455-456, :467-468;
`grep -n 'gpu\|GPU' prod/scripts/4-llm.sh` → nothing). Live result on the GPU
prod host (2026-08-11, evidence item 10): a 23 GB Q6 MoE inferred on CPU,
presenting to the user as "model is slow". Shared analysis: `analysis_ref:`.

## Root cause

Unowned decision, not a defect in the overlay: the wizard's llama.cpp
bring-ups predate any GPU host running the wizard and were never revisited
when M1-744 shipped the overlay. The probe primitive is trivial (`/dev/dri/
renderD*` presence) and 4b-image.sh already owns its own overlay bring-up
(:814) — the pattern exists; 4-llm.sh simply never got it.

## Pitfalls

Numbered consistently with the analysis document.

- P8: probe `/dev/dri/renderD*`, NEVER `/dev/kfd` — the Vulkan overlay passes
  only /dev/dri (docker-compose.gpu.yml:32-33); /dev/kfd is the ROCm device
  4b-image.sh gates on (:777). The brief's "/dev/kfd" phrasing is the
  ROCm/ComfyUI world leaking into the llamacpp lane (analysis §Ground truth
  discrepancy).
- P9: BOTH llama.cpp ups merge the overlay (:456 and :468) — one without the
  other leaves the embeddings server silently on CPU; the ollama-embeddings
  up (:476) must NOT merge it.
- P10: no new stdin prompt — the drive layers feed positional stdin (M1-809's
  hard constraint), and a prompt would make the prompt count host-dependent
  (a GPU dev box prompts where a VPS CI runner doesn't). The decision is
  probe-driven, printed, and env-overridable.
- P3: hermeticity — the seam is shipped env (INFOCHAT_LLAMACPP_GPU=on|off,
  unset=auto), so tests never depend on the host's /dev state. Existing
  drives run unmodified: no existing assertion inspects compose `-f` argv, so
  an auto-probed merge on a GPU dev host breaks nothing (confirmed against
  the assertion helpers, LlamacppWiringTest:595-644).
- P14: hermetic drives pin the wiring; the live render/up proof on the GPU
  host is this ticket's own acceptance item 5 — the overlay decision is
  exactly the class of behavior fakes cannot observe end-to-end.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Write the reproduction + override + ollama-negative drives — run RED on
     main (workflow §0). The reproduction drive feeds the SAME stdin string
     as the pinned-default drive (P10 made structural).
  2. 4-llm.sh: add `GPU_OVERLAY="$REPO_ROOT/docker-compose.gpu.yml"` and the
     probe — `INFOCHAT_LLAMACPP_GPU` honored as on|off; unset → auto =
     `compgen -G "/dev/dri/renderD*"` (or equivalent glob test) succeeds.
     Resolve ONCE in the llamacpp branch, print the one-line decision, and
     assemble the `-f` list used by both llama.cpp ups (P9). The ollama ups
     keep the base-only form.
  3. Live proof on the GPU host (acceptance item 5).
  4. §7.7.2 row + §7.8.7 paragraph (acceptance item 6); `bash -n`; `mvn
     verify` from the repo root.
- **Controls to preserve (§10):** both compose files byte-identical (their
  pins at :173/:192 stay green); `--env-file "$SECRETS_FILE"` position and
  profile lists on every up; the ollama-only ups base-only; no host port
  anywhere (security.md §Trust boundaries item 8); the embeddings-backend
  choice and every downstream config write untouched.
- **Pitfall→mitigation:** P8→step 2's glob + item 2's greps; P9→step 2's
  shared `-f` assembly + items 1/4; P10→step 1's same-stdin drive + item 3;
  P3→the env seam; P14→step 3.

## Definition of done

On a host with /dev/dri render nodes the wizard merges
docker-compose.gpu.yml into both llama.cpp bring-ups (base first) and says
so; INFOCHAT_LLAMACPP_GPU=on|off overrides the probe; the ollama legs and
both compose files are untouched; no prompt was added; the live render/up
proof is captured; the design doc records the wizard owning the decision;
mvn verify green.

## Verification

- P8 → item 2's greps (mutation: a /dev/kfd-keyed probe fails the
  kfd-absence grep).
- P9 → item 1's both-ups assertion + item 4's ollama negative (mutations:
  merging only the generative up, or merging the ollama up, each fail).
- P10 → item 1's same-stdin construction + item 3's all-drives-unmodified
  probe (mutation: an added `read` misaligns every existing drive's timing
  answers and fails them).
- P3 → the env seam drives (on/off) never consult host /dev state.
- P14 → item 5's live render + bring-up capture (failure-mode cover: a
  render that drops the overlay or a container on the CPU build fails the
  capture's image assertion).
- Reproduction → item 1. Docs → item 6's greps.

## Out-of-scope

Named in `out_of_scope`: compose-file content, the rootless-ACL gate and
device verification (M1-827 — sequenced via blocked_by), restore.sh's
bring-ups (batch A), 4b/ComfyUI (batch C), host ports. No pre-existing test
is modified.

## Census

Class: compose bring-ups of the llama.cpp services (re-runnable:
`grep -rn 'profile llamacpp\|up -d llamacpp' prod/`).

| Site | Disposition |
|---|---|
| prod/scripts/4-llm.sh:455-456 (generative up) | FIXED (this ticket) |
| prod/scripts/4-llm.sh:467-468 (embeddings up) | FIXED (this ticket) |
| prod/scripts/4-llm.sh:475-476, :643-644 (ollama ups) | pinned negative — the overlay defines no ollama keys; base-only by design |
| prod/scripts/restore.sh:650, :657 | defer: batch A owns restore.sh; the restore host re-runs this family's probe (analysis option E) |
| prod/setup.sh:164-167 (reset teardown profile list) | out-of-scope: `down`, not `up` — the llama.cpp services exist in the base file, so teardown needs no overlay |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-826-llm-wizard-robustness-4.md
```
