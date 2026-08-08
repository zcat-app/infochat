---
id: M1-797
title: "ComfyUI GPU compose overlay + ROCm image"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  Probe (no mvn coverage exists for compose artifacts):
  `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml config`
  — observed wrong output on main:
  `open .../docker-compose.comfyui.yml: no such file or directory` (the file
  does not exist; glob-verified: only docker-compose.yml and
  docker-compose.gpu.yml exist). There is no ComfyUI service, so
  `infochat.image.base-url` has nothing to point at and the D73 config gate
  can never open on a one-box deployment.
analysis_ref: docs/plan/m1/tick-analysis/image-generation-feature.md
blocked_by: []
files_scope:
  - docker-compose.comfyui.yml
  - prod/images/comfyui/
  - docs/design/07-deployment.md
  - docs/design/future/image-generation.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The setup-wizard step that offers/configures this overlay (M1-798 owns
    prod/scripts/*, prod/setup.sh, and operator-facing docs).
  - Any Provider- or adapter-side Java (M1-799..M1-803).
  - Hosted/remote third-party image backends (D77 rejects them as a
    supported path).
  - Publishing any ComfyUI port to the host network — the two-box form is
    the operator's explicit action under D77, documented, never shipped as
    a default.
acceptance:
  - "Probe: `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml config` exits 0 and the rendered service publishes NO host port (Provider reaches it only over the compose network, the llamacpp item-8 precedent) — REPRODUCTION, now passing."
  - "Probe: with the overlay up, `docker compose ... exec infochat-provider wget -qO- http://comfyui:8188/system_stats` returns JSON (service healthy on the compose network at the documented port)."
  - "SHIP-BLOCKER (D75, analysis P4/P24): after a real generation run in the container, `docker logs <comfyui-container>` contains NO prompt text — probe: generate with a canary prompt string, then `docker logs ... | grep -F <canary>` returns nothing. If stock ComfyUI prints prompt text, the image's launch configuration suppresses it before this item can pass; what it prints is recorded in docs/design/future/image-generation.md §Open items either way."
  - "SHIP-BLOCKER (D75 backend no-retention end state, image half): the output directory is tmpfs-backed inside the container AND an aged-file janitor in the image removes output files older than the spool-completes window — probe: after a generation, wait past the janitor age and assert the container's output dir is empty (the Provider-side /history clear is M1-802's half)."
  - "The load-bearing measured launch configuration survives containerization verbatim (analysis P21) — probe: `grep -c -E 'disable-mmap|bf16-vae|highvram|TORCH_ROCM_AOTRITON_ENABLE_EXPERIMENTAL' prod/images/comfyui/Dockerfile` matches all four flags and `grep -c -E '/dev/kfd|/dev/dri' docker-compose.comfyui.yml` matches both device mappings; the overlay's header comment carries the M1-744 rootless-docker `group_add` trap and host-side ACL pointer (`grep -n rootless docker-compose.comfyui.yml` hits), copied from docker-compose.gpu.yml's precedent."
  - "Re-measure (design addendum: the 4.38 s Mage-Flow number was conda-measured) — probe: against the running container, submit one warm-up plus five timed generations per curated model (`curl -s http://localhost:8188/prompt` with the model's graph, polling `/history` for completion, wall-clock per run; discard the warm-up) and record the steady-state mean per model in docs/design/future/image-generation.md. PASS threshold: every model completes all five runs AND Mage-Flow's container steady-state is within 2x its conda 4.38 s (a worse regression means the load-bearing flags did not survive containerization). These recorded numbers are what M1-798's wizard prints; no conda number may be presented as a container number (analysis P22). Verify the record landed: `grep -n 'container' docs/design/future/image-generation.md` shows the new measured table."
  - "docs/design/07-deployment.md gains the overlay usage block (second `-f` file form, mirroring the docker-compose.gpu.yml header) — probe: `grep -n 'docker-compose.comfyui.yml' docs/design/07-deployment.md` shows the usage block; every new documented behavior lands in design notes, not spec (no spec edit in this ticket)."
  - "mvn verify from repo root is green (no Java change expected; the run proves nothing else drifted)."
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

# M1-797: ComfyUI GPU compose overlay + ROCm image

## Context

D73 makes `/image` config-gated on `infochat.image.base-url`; D77 restricts
the backend to local or operator-owned infrastructure. Today no ComfyUI
service, image, or overlay exists (glob-verified: only `docker-compose.yml`
and the M1-744 `docker-compose.gpu.yml`). This ticket ships the one-box
runtime: an opt-in overlay plus a purpose-built ROCm ComfyUI image, following
the docker-compose.gpu.yml precedent. Shared analysis: `analysis_ref:`.

## Root cause

Feature gap, not a defect. The design addendum
(docs/design/future/image-generation.md, 2026-08-07) settles the shape:
container not conda, opt-in second `-f` overlay, ROCm base with `/dev/kfd` +
`/dev/dri`, measured launch flags load-bearing, loopback/compose-network-only
bind. There is no official ComfyUI ROCm image, so the image is ours to build.

## Pitfalls

Numbered consistently with the analysis document.

- P4: the container's stdout is part of the no-content chain — if ComfyUI
  logs prompt text, the container log becomes a prompt dossier (D75).
- P21: bind discipline — no host port in the shipped default (security.md
  §Trust boundaries item 8 precedent: exposure beyond the host is an explicit
  operator action, never a default); rootless-docker `group_add` trap;
  launch flags are measured requirements, not tunables.
- P22: numbers honesty — the wizard (M1-798) prints only what THIS ticket
  re-measures inside the container.
- P24: ComfyUI has no delete API for output files — the image carries tmpfs
  output + an aged-file janitor so the D75 end state ("backend-side output
  files removed once Provider has fetched the bytes") is achievable; the
  Provider-side half (history clear + acceptance probe) is M1-802.

## Approach

- **Files to touch:** the four in `files_scope`.
- **Steps, in order:**
  1. `prod/images/comfyui/Dockerfile` — ROCm base, ComfyUI install, launch
     flags `--disable-mmap --bf16-vae --highvram`, env
     `TORCH_ROCM_AOTRITON_ENABLE_EXPERIMENTAL=1`, tmpfs mount point for the
     output dir, and the aged-file janitor (a shell loop or cron inside the
     image removing output files past the age window; the window value lives
     here and is named in the design doc for M1-801's sweeper to exceed).
  2. `docker-compose.comfyui.yml` — new overlay, header comment copied from
     the M1-744 precedent (why an overlay, why not the base file, the
     rootless-docker ACL trap with the §7.8.7 pointer adapted to kfd/dri),
     service on the compose network with NO `ports:` mapping.
  3. Build, bring up with the base file, run one warm-up plus five timed
     generations per curated model (acceptance item 6's probe); capture
     steady-state means; grep container logs for the canary prompt
     (acceptance item 3).
  4. Record the measured numbers and the stdout finding in
     docs/design/future/image-generation.md; write the 07-deployment.md
     usage block.
- **Controls to preserve (§10):** none rerouted — new files only. The
  docker-compose.gpu.yml content is read as precedent, never edited.
- **Pitfall→mitigation:** P4→step 3's canary grep + item 3's suppression
  requirement; P21→step 2's no-host-port shape + header trap comment;
  P22→step 3's in-container measurement; P24→step 1's tmpfs + janitor.

## Definition of done

Overlay renders with no host port; service healthy on the compose network;
a generation's canary prompt never appears in container logs; output files
do not outlive the janitor window; launch flags verbatim; per-model
container timings measured under the item-6 probe, within threshold, and
recorded; deployment design doc updated; repo-root verify green.

## Verification

- P4 → acceptance item 3's canary grep (failure-mode: a real prompt string
  hunted in the log stream).
- P21 → acceptance item 1's `config` render assertion (a `ports:` mapping
  fails it) + item 2's compose-network reachability.
- P22 → acceptance item 6's timed-generation probe and its 2x threshold (a
  container that silently lost `--disable-mmap`-class flags blows past it).
- P24 → acceptance item 4's aged-file probe.
- acceptance item 5 → the item's own `grep -c -E` probes over the
  Dockerfile and overlay.
- Non-vacuity: adding a `ports:` mapping fails item 1; dropping
  `--disable-mmap` fails item 5's grep; deleting the janitor fails item 4.

## Out-of-scope

Named in `out_of_scope`: the wizard step (M1-798), all Java (M1-799+),
hosted backends, host-port publishing. The two-box form needs nothing here:
D77 makes the Provider point `infochat.image.base-url` at the second box and
the wizard (M1-798) prints the firewall-to-Provider-host requirement. No
pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-797-comfyui-gpu-overlay.md
```
