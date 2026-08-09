---
id: M1-807
title: "ComfyUI image carve: VAE-Utils node + flag re-verify"
status: pending
created: 2026-08-09
last_updated: 2026-08-09
flow: tick
reproduction: >-
  Probe (no mvn coverage exists for image artifacts):
  `grep -c -E 'VAE-Utils|custom_nodes' prod/images/comfyui/Dockerfile` —
  observed wrong output on main: `0`. The M1-797 image installs no custom
  nodes (Dockerfile read in full, 61 lines), so the node id
  VAEUtils_VAEDecodeTiled that the v1 Krea fit stage requires (Final
  decision 5, docs/design/future/image-generation.md:771-776) is absent:
  with the overlay up, `docker compose -f docker-compose.yml -f
  docker-compose.comfyui.yml exec -T comfyui curl -fsS
  http://127.0.0.1:8188/object_info/VAEUtils_VAEDecodeTiled` fails, and the
  Krea template M1-798 writes cannot execute.
analysis_ref: docs/plan/m1/tick-analysis/image-pipeline-configurability-reanalysis.md
blocked_by: []
decomposed_from: M1-798
files_scope:
  - prod/images/comfyui/
  - docs/design/future/image-generation.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The setup-wizard step (M1-798 owns prod/scripts/*, prod/setup.sh,
    docker-compose.yml, SETUP_GUIDE.md, and the template writing; this
    ticket only puts the node in the image and proves the flags).
  - Any Provider- or adapter-side Java (M1-799..M1-803).
  - Downloading model assets into the models volume (the wizard's job,
    M1-798); the live probes here use the spike assets already on the
    validation host (handoff §5) via the host-dir models override.
  - Changing ANY launch flag, torch pin, or ComfyUI commit — the carve
    RE-VERIFIES the measured stack, never re-tunes it (a re-verification
    failure escalates).
  - Any second custom node (ESRGAN/PiD/SUPIR-class or otherwise) — v1 ships
    ComfyUI-VAE-Utils alone (Final decisions 1 + 5; REJECTED list at
    docs/design/future/image-generation.md:790-793).
acceptance:
  - "Probe: with the overlay up from the REBUILT image, `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml exec -T comfyui curl -fsS http://127.0.0.1:8188/object_info/VAEUtils_VAEDecodeTiled` returns the node's schema JSON — REPRODUCTION, now passing (the node the Krea fit stage needs is registered)."
  - "Supply-chain pin (analysis P33): prod/images/comfyui/Dockerfile installs spacepxl/ComfyUI-VAE-Utils at a PINNED commit (the COMFYUI_COMMIT precedent, prod/images/comfyui/Dockerfile:15), MIT licence recorded in the comment — probe: `grep -n -E 'VAE-Utils' prod/images/comfyui/Dockerfile` shows the pinned clone line; no other custom node appears in the diff (D77: the image is code-execution surface)."
  - "Load-bearing flags survive verbatim (analysis P21, docs/spec/decisions.md D77 one-box form): `grep -c -E 'disable-mmap|bf16-vae|highvram|TORCH_ROCM_AOTRITON_ENABLE_EXPERIMENTAL' prod/images/comfyui/Dockerfile` still matches all four; the tmpfs output/temp + janitor + TTL env are unchanged (M1-797 acceptance items 4/5 probes re-run green); `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml config` renders NO host port."
  - "Flag re-verification with the node present (analysis P34, Final decision 5): on the rebuilt image, one warm-up plus five timed Mage-Flow generations (M1-797 protocol, unique seeds) land within 2× the conda 4.38 s threshold — probe: run the protocol against the container and show the steady-state mean; a regression past the threshold ESCALATES rather than re-tuning flags (FAILURE-MODE: the threshold exists precisely to catch a stack the node addition disturbed)."
  - "The node works with the measured stack (analysis P28/P34): a Krea 6-step @ 0.6 MP graph whose decode stage is VAELoader(Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors) + VAEUtils_VAEDecodeTiled completes in the rebuilt container and the fetched image is ~2× the latent dims (896×672 latent → ~1792×1344, matching the spike's conda measurement at docs/design/future/image-generation.md:739) — probe: submit via `curl -s http://127.0.0.1:8188/prompt` (loopback override per handoff §5), poll /history, fetch /view, assert pixel dimensions; the canary prompt never appears in `docker logs` (D75 image half preserved)."
  - "Record (analysis P22): the re-verification result and the 2× decode container number land in docs/design/future/image-generation.md — probe: `grep -n 'VAE-Utils' docs/design/future/image-generation.md` shows the carve-verification note; only container numbers are recorded as container numbers."
  - "mvn verify from repo root is green (image/doc-only diff; proves no drift)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/decisions.md (D75, D77)
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D75
  - D77
---

# M1-807: ComfyUI image carve: VAE-Utils node + flag re-verify

## Context

The 2026-08-09 Final decision 5 ships the spacepxl 2× VAE-decode fit stage
for Krea in v1: the M1-797 image gains spacepxl/ComfyUI-VAE-Utils (MIT
custom node, node id VAEUtils_VAEDecodeTiled) and the M1-797 load-bearing
flags are re-verified with the node present
(docs/design/future/image-generation.md:771-776). M1-797 is done; this is
the carve that lets M1-798's Krea template execute. Shared analysis:
`analysis_ref:`. Decomposed from M1-798's re-analysis (the brief leaves
"rides with it or lands as its own ticket" to the decomposition; the
analysis §Solution options rejects folding it in).

## Root cause

Feature gap in the image artifact: prod/images/comfyui/Dockerfile installs
no custom nodes (read in full — 61 lines, no custom_nodes), so the node id
the Krea fit stage requires is absent from the only runtime that can run
it. The carve is a prerequisite of M1-798's Krea template and ETA probe,
and Final decision 5 conditions the ship on the flag re-verification.

## Pitfalls

Numbered consistently with the analysis document.

- P21: the image is the D77 one-box surface — no host port may appear; the
  launch flags are measured requirements, not tunables (Dockerfile:5-7);
  the carve adds code WITHOUT disturbing either.
- P22: only container-measured numbers reach the record/wizard — the carve
  records what IT measures on the rebuilt image, nothing from the conda
  column.
- P33: supply-chain pin — the backend endpoint is code execution on the
  hosting box (D77), so the node enters at a pinned commit (COMFYUI_COMMIT
  precedent), MIT only, and nothing else joins it (REJECTED list stands).
  If the node carries a requirements file, dependencies are pinned too or
  the addition escalates.
- P34: re-verification discipline — the M1-797 protocol with its threshold
  is the gate; failure escalates (§2/§6), never silently re-tunes.

## Approach

- **Files to touch:** `files_scope` (the Dockerfile; the design doc
  record).
- **Steps, in order:**
  1. Dockerfile: install ComfyUI-VAE-Utils at a pinned commit into
     /opt/ComfyUI/custom_nodes/ (clone-by-commit or pinned archive, the
     COMFYUI_COMMIT shape), MIT licence + node-id comment; pin any
     dependency the node declares (verify at start — the node is a
     pixel_shuffle utility; if it needs nothing, add nothing).
  2. Rebuild the image; bring up the overlay with the host-dir models
     override (handoff §5 assets: Krea checkpoint/encoder + the Wan2.1
     VAE are on the validation host).
  3. Node-registration probe (acceptance item 1).
  4. Flag re-verification: M1-797 protocol (warm-up + five timed Mage-Flow
     runs, unique seeds, 2× threshold) — acceptance item 4.
  5. Krea + spacepxl 2× decode generation probe — acceptance item 5
     (de-risks M1-798's P28 wiring before the wizard starts).
  6. Record results in the design doc; re-run the M1-797 acceptance probes
     that pin the image invariants (item 3).
- **Controls to preserve (§10):** tmpfs output/temp + janitor + TTL env
  untouched; no `ports:` added; flags verbatim; the canary-prompt-free log
  property re-asserted by item 5's grep; docker-compose.comfyui.yml and
  docker-compose.gpu.yml read as precedent, never edited.
- **Pitfall→mitigation:** P21→steps 1/6 + item 3; P22→step 6 + item 6;
  P33→step 1 + item 2; P34→steps 4/5 + items 4/5.

## Definition of done

Node registered at a pinned commit in the rebuilt image; flags verbatim and
re-verified within the M1-797 threshold; a real Krea + spacepxl 2× decode
generation produces ~2× output with a prompt-free log; image invariants
(no host port, tmpfs/janitor) intact; results recorded; verify green.

## Verification

- P21 → item 3's flag grep + compose config render (FAILURE-MODE: a
  `ports:` mapping or a dropped flag fails the probes).
- P22 → item 6's record probe (FAILURE-MODE: a conda number recorded as a
  container number fails it).
- P33 → item 2's pin grep + diff review (FAILURE-MODE: an unpinned clone or
  a second node fails the review against the REJECTED list).
- P34 → item 4's threshold probe (FAILURE-MODE by construction: a disturbed
  stack trips the 2× threshold and escalates) + item 5's dimension
  assertion (a 1× output means the 2× decode did not engage).
- D75 image half → item 5's canary grep over `docker logs`.
- Non-vacuity: removing the pin fails item 2; dropping the re-measure fails
  item 4; an unchanged image fails the reproduction.

## Out-of-scope

Named in `out_of_scope`: the wizard step and all template writing (M1-798),
all Java, model downloads into the models volume (the probes use the spike
assets on the validation host), any flag/torch/commit change, any second
custom node. No pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-807-comfyui-image-vae-utils-carve.md
```
