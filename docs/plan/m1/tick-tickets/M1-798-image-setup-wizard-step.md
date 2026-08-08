---
id: M1-798
title: "Setup-wizard /image step with model picker"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  Probe (wizard scripts are not mvn-covered):
  `grep -n image prod/setup.sh` — observed wrong output on main: no match
  (the STEPS list at prod/setup.sh:23-33 registers nine steps, none touching
  image generation), so a first-run operator is never offered
  `infochat.image.base-url` and the D73 config gate can never be opened by
  the supported setup path.
analysis_ref: docs/plan/m1/tick-analysis/image-generation-feature.md
blocked_by: [M1-797]
files_scope:
  - prod/scripts/
  - prod/setup.sh
  - SETUP_GUIDE.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The overlay and image themselves (M1-797).
  - Any Provider- or adapter-side Java, including the /image command and
    the translator-leg disclosure texts in prod/switch-llm.sh +
    prod/scripts/4-llm.sh (M1-803 — those texts name a leg that exists only
    once the handler ships).
  - A live HuggingFace picker or any model beyond the curated 3 × 2 tier
    set (design addendum: hardcoded choices, never a raw repo listing).
  - A chat-level model switch (design: operator-level only, via re-running
    this step).
acceptance:
  - "Probe: `grep -n image prod/setup.sh` shows the new step registered in the STEPS list — REPRODUCTION, now passing; a step is in the run iff it has a STEPS entry (prod/setup.sh:19-22), so the script alone is not sufficient."
  - "Profile gating (analysis P22): on `pi` and `vps` profiles the step offers ONLY the remote-URL (two-box) path or 'not enabled' — probe: run the step's profile check against each of the four VALID_PROFILES values (prod/scripts/1-profile.sh:11) and show the offered menu per profile; `laptop`/`remote-llm` may offer local install, `pi`/`vps` never do."
  - "Model picker (design addendum): exactly three models × two curated tiers (Recommended bf16 / Smaller footprint), each printed with M1-797's CONTAINER-measured latency and the per-model disk table (~16.5 / ~20 / ~33.5 GB bf16; ~13 / ~11.5 / ~19 GB smaller-tier) BEFORE the operator commits to a download — probe: the step's dry-run/menu output contains all six options with numbers; a conda-measured number presented as a container number fails this item."
  - "Preflight before download (analysis P22): the step HEAD-checks every model asset URL and verifies disk space and VRAM for the chosen tier before downloading, and dedupes the shared qwen3vl_4b encoder blob between Mage-Flow and Krea — probe: point the step at one dead asset URL and show it aborts before any download begins (failure-mode)."
  - "D77 firewall disclosure (redteam finding 2 fix; docs/spec/security.md §Trust boundaries off-host-exposure posture): when the operator enters a remote (two-box) URL, the step prints the requirement that the backend port be firewalled to the single Provider host — probe: run the remote-URL path and show the printed text (`grep -n firewall prod/scripts/<new-step>.sh` hits the printed requirement)."
  - "The step writes `infochat.image.base-url` AND the per-model ETA constant (the container-measured warm-steady-state seconds from the healthcheck generation, feeding M1-803's progress-message ETA) into the runtime config (or records 'not enabled'), brings the overlay up for a local install, healthchecks via `/system_stats` (`curl -s http://localhost:8188/system_stats` returns JSON), and supports the re-run/edit path for existing installs (model switch = re-run, recreating the container and offering to delete the previous model's files) — probe: re-run the step on a configured install and show the switch offer."
  - "SETUP_GUIDE.md gains the /image setup section documenting profile gating, the disk/VRAM demand, and the two-box firewall requirement — probe: `grep -n '/image' SETUP_GUIDE.md` shows the new section."
  - "Hardware scope is explicit (design addendum): the picker and the SETUP_GUIDE section state that the local container path is ROCm-only and validated on Strix Halo (gfx1151) alone — other ROCm GPUs unverified, NVIDIA not covered — probe: `grep -n -i 'gfx1151\|ROCm-only' SETUP_GUIDE.md prod/scripts/<new-step>.sh` hits in both."
  - "mvn verify from repo root is green (shell/doc-only diff; proves no drift)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/decisions.md (D73, D77)
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D73
  - D77
---

# M1-798: Setup-wizard /image step with model picker

## Context

D73 makes `/image` exist only when `infochat.image.base-url` is configured;
the setup wizard is the supported configuration path, and its STEPS list
(prod/setup.sh:23-33) has no image step — so the gate can never open for a
wizard-installed deployment. The design addendum settles the shape: a
numbered step script (not a standalone like switch-llm.sh), three hardcoded
models × two curated tiers, preflight checks, operator-level switching.
Shared analysis: `analysis_ref:`.

## Root cause

Feature gap. The wizard is the single registration point for setup steps
("leaf subscripts never self-register", prod/setup.sh:19-22), so the work is
the new script plus its STEPS entry plus the profile-gating adaptation.

## Pitfalls

Numbered consistently with the analysis document.

- P21/P22: the step must never offer a local install on `pi`/`vps` (no
  usable GPU), must print only M1-797's container-measured numbers, and must
  preflight (HEAD-checks, disk, VRAM) before any multi-GB download.
- P22 (firewall half): the two-box path prints the D77 requirement — the
  backend port firewalled to the single Provider host. The wizard documents;
  it cannot verify network topology (design addendum), so the printed
  requirement IS the control — a missing or watered-down line here reopens
  redteam finding 2.
- P24 interplay: model files are operator assets and persist (they are not
  prompt content); only ComfyUI's runtime output/history retention is D75
  scope — do not conflate the model store with the no-content chain when
  writing the delete-previous-model offer.

## Approach

- **Files to touch:** `files_scope` (one new step script under
  prod/scripts/, the STEPS edit, SETUP_GUIDE.md).
- **Steps, in order:**
  1. Write the step script: profile gate → menu (local install on capable
     profiles; remote-URL or not-enabled elsewhere) → picker (3 × 2 with
     M1-797's numbers + disk table) → preflight (HEAD every asset URL, disk,
     VRAM) → download (with qwen3vl_4b dedupe) → write
     `infochat.image.base-url` → bring up the overlay (local) →
     `/system_stats` healthcheck → re-run/edit path.
  2. Register it in prod/setup.sh STEPS (position after the LLM step, before
     apps start — the Provider container reads the key at create time).
  3. SETUP_GUIDE.md section.
  4. Run every probe in `acceptance`.
- **Controls to preserve (§10):** the wizard's resume/state mechanics
  (mark_done/is_done, prod/setup.sh:70-71) are used, not reinvented; no
  existing step script is edited.
- **Pitfall→mitigation:** P21/P22→acceptance items 2-4; firewall
  disclosure→item 5; P24→step 1's offer wording.

## Definition of done

Step registered and profile-gated; picker honest and preflighted; remote
path prints the firewall requirement; config written, overlay up,
healthchecked; re-run path works; guide updated; verify green.

## Verification

- P21 → acceptance item 2's per-profile menu probe (failure-mode: a pi/vps
  profile must never see a local-install offer).
- P22 → item 3's numbers/disk-table probe + item 4's dead-URL abort probe
  (failure-mode: preflight must fire before any download).
- Firewall disclosure → item 5's printed-text probe.
- P24 → item 6's re-run probe; the offer deletes model files only, never
  anything else.
- Non-vacuity: removing the STEPS entry fails item 1; dropping the HEAD
  check fails item 4; omitting the firewall line fails item 5.

## Out-of-scope

Named in `out_of_scope`: the overlay/image (M1-797), all Java, the
translator-leg disclosure texts in switch-llm.sh/4-llm.sh (M1-803 — the
/image leg exists only once the handler ships), non-curated models,
chat-level switching. No pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-798-image-setup-wizard-step.md
```
