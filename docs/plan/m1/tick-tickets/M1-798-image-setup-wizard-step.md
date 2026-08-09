---
id: M1-798
title: "Setup-wizard /image step: picker, templates, ETA probe"
status: done
created: 2026-08-08
last_updated: 2026-08-10
flow: tick
reproduction: >-
  Probe (wizard scripts are not mvn-covered):
  `grep -n image prod/setup.sh` — observed wrong output on main: no match
  (the STEPS list at prod/setup.sh:23-33 registers nine steps, none touching
  image generation), so a first-run operator is never offered
  `infochat.image.base-url` and the D73 config gate can never be opened by
  the supported setup path.
analysis_ref: docs/plan/m1/tick-analysis/image-pipeline-configurability-reanalysis.md
blocked_by: [M1-807]
files_scope:
  - prod/scripts/
  - prod/setup.sh
  - docker-compose.yml
  - SETUP_GUIDE.md
  - docs/design/07-deployment.md
  - docs/design/future/image-generation.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The overlay and image themselves (M1-797, done) and the ComfyUI-VAE-Utils
    node carve + flag re-verification inside the image (M1-807 — this ticket
    CONSUMES the node in the Krea template; it never edits the Dockerfile).
  - Any Provider- or adapter-side Java, including the /image command, the
    converter guardrail, and the translator-leg disclosure texts in
    prod/switch-llm.sh + prod/scripts/4-llm.sh (M1-803 — those texts name a
    leg that exists only once the handler ships; ComfyUIClient's template
    validation is consumed unchanged, never edited).
  - A live HuggingFace picker or any model beyond the curated 3 × 2 tier set
    (design addendum: hardcoded choices, never a raw repo listing).
  - A chat-level model switch or any runtime pipeline knob (design: operator-
    level only, via re-running this step; steps/VAE/fit stage are BAKED into
    the template the step writes).
  - Any diffusion upscaler (ESRGAN family, PiD) — excluded from v1 by the
    2026-08-09 Final decisions; no upscaler download, no upscaler option.
  - The Provider bind-mount-across-restart residual of the template mount
    (P32 DECIDE-BEFORE M1-803) and the converter's DECIDE-BEFORE (how the
    handler learns the baked sampling budget — M1-803's analysis).
acceptance:
  - "Probe: `grep -n image prod/setup.sh` shows the new step (4b-image.sh) registered in the STEPS list between the LLM step and bootstrap — REPRODUCTION, now passing; a step is in the run iff it has a STEPS entry (prod/setup.sh:19-22), so the script alone is not sufficient."
  - "Profile gating (analysis P22): on `pi` and `vps` the step offers ONLY remote (two-box) or 'not enabled' — probe: run the step's `--dry-run` gate against each of the four VALID_PROFILES values (prod/scripts/1-profile.sh:11) and show the offered menu per profile; `laptop`/`remote-llm` may offer local install, `pi`/`vps` never do (FAILURE-MODE: a local-install offer must never appear for pi/vps, and a forced `local` mode on pi/vps exits non-zero)."
  - "Picker honesty (analysis P22/P26, docs/spec/decisions.md D73 honesty basis): exactly three models × two curated tiers, each printed with its spike CONTAINER-measured latency (Mage-Flow 4.07 s @ 1024/4 st; Z-Image 22.37 s @ 1024/8 st steady state; Krea 22.41 s @ 0.6 MP/6 st — the container table at docs/design/future/image-generation.md:846-859) and the per-model disk table BEFORE the operator commits — probe: `--dry-run` output contains all six options with those numbers; the script's latency table matches the container table verbatim and contains NO conda-measured value (23.78/22.14/22.54/53.59/53.07…) presented as a container number (FAILURE-MODE: a conda number in the latency table fails this item); the Z-Image row notes 22.37 s is the steady state (28.64 first-pass was an autotune artifact)."
  - "VAE picker for Krea (Final decision 4): a Krea install offers the decoder choice — krea2RealVae labelled RECOMMENDED (drop-in stock VAELoader, visibly crisper, no tint) and stock qwen_image_vae labelled FALLBACK (right choice for text-heavy renders) — probe: the Krea branch's menu output shows both options with those labels; `grep -n 'krea2RealVae' prod/scripts/4b-image.sh` hits both the label and the asset URL."
  - "License printing for community assets (analysis P27, Final decision 5): the Krea path prints the two license-UNDECLARED VAE weights (krea2RealVae_v10.safetensors, artsyww/KREA2REALVAE; Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors, spacepxl) as community assets with the undeclared-licence label, and the ComfyUI-VAE-Utils node repo as MIT — probe: run the Krea local path's pre-download disclosure and show the printed text; `grep -n -i 'undeclared\\|community' prod/scripts/4b-image.sh` hits in the Krea branch."
  - "Preflight before download (analysis P22): the step HEAD-checks every asset URL of the chosen tier — for Krea INCLUDING the two community VAE files — and verifies disk space and memory/VRAM for the chosen tier (Krea's demand now includes the measured VAE sizes, recorded per item 10) before downloading, and dedupes the shared qwen3vl_4b encoder blob between Mage-Flow and Krea — probe: point the step at one dead asset URL and show it aborts before any download begins (FAILURE-MODE)."
  - "Template content (analysis P15, D77 server-built graph): for each model the step writes an API-format template baking the per-model diffusion steps (Mage-Flow 4, Z-Image 8, Krea 2 6), the per-model VAE and fit stage (Krea: the spacepxl 2× decode stage via node id VAEUtils_VAEDecodeTiled + lanczos exact fit; Mage/Z-Image: VAEDecode + lanczos exact fit via ImageScale), with latent and fit dimensions numeric, exactly ONE INFOCHAT_PROMPT_PLACEHOLDER text field, and exactly ONE KSampler with a numeric seed — probe: generate each model's template and assert it passes the exact validation ComfyUIClient enforces (ComfyUIClient.java:483-524) via a `python3 -c` JSON check (one placeholder field, one numeric-seed KSampler, baked steps value) — a template mutated to carry TWO placeholder fields must fail the check (FAILURE-MODE); the committed reference template prod/config/comfyui-workflow.json stays untouched (client-validation reference for the live probe)."
  - "ETA constant is a probe of the FINAL template, never a table lookup (analysis P26/P29, brief gain 4): the LOCAL path runs one warm-up plus five timed generations of the written template against the deployment's own container, unique seed per run (a fixed seed measures the node cache), warm-up discarded, and writes the steady-state mean as `infochat.image.steady-state-seconds` — probe: run the local path and show the key carries a numeric value plus the script's probe loop uses per-run unique seeds and discards the warm-up (FAILURE-MODE: a constant copied from any static latency table fails this item)."
  - "D77 firewall disclosure (redteam image-spec-promotion finding 2 fix; docs/spec/security.md §Trust boundaries off-host-exposure posture): when the operator enters a remote (two-box) URL, the step prints — BEFORE the URL prompt commits — the requirement that the backend port be firewalled to the single Provider host, that the box be operator-owned infrastructure, and that prompts cross the LAN in cleartext HTTP — probe: run the remote path and show the printed text; `grep -n firewall prod/scripts/4b-image.sh` hits the printed requirement."
  - "Remote path leaves the ETA constant UNSET (analysis P30 — round-1 REWORK finding 1 of the old-brief attempt, valid under the new brief): the remote branch performs no probe against the entered URL and writes no `infochat.image.steady-state-seconds` — probe: run the remote path against a stub URL, then `grep -c '^infochat.image.steady-state-seconds=' prod/runtime/application.properties` prints 0 while `grep -c '^infochat.image.model='` prints 1 (FAILURE-MODE)."
  - "Config writes, bring-up, healthcheck, re-run/switch/disable, and reset (analysis P24/P31/P32): the step writes `infochat.image.base-url`, `infochat.image.workflow-file` (the in-container mount path), `infochat.image.model`, and — local only — the ETA constant (or records 'not enabled'); brings the overlay up for a local install; healthchecks via `/system_stats`; supports keep|switch|disable on re-run (switch recreates the container and offers to delete ONLY the previous install's files, skipping every blob the new install shares — encoder AND VAE files); a directory at the template mount source is rmdir'd before the file is written (P32 write side); and `prod/setup.sh --reset` tears down the comfyui container — probes: re-run the step on a configured install and show the switch offer; `awk '/^do_reset\\(\\)/,/^}/' prod/setup.sh | grep -c 'docker-compose.comfyui.yml'` prints a value ≥ 1; with the comfyui container running, `prod/setup.sh --reset` leaves `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml ps -q comfyui` empty (FAILURE-MODE — round-1 REWORK finding 2 of the old-brief attempt, valid under the new brief)."
  - "Docs: SETUP_GUIDE.md gains the /image setup section documenting profile gating, the disk/VRAM demand (including the two Krea VAE files, with measured sizes), the VAE choice, the community-asset licence labels, the two-box firewall requirement, and the hardware scope (ROCm-only, validated on Strix Halo gfx1151 alone) — probes: `grep -n '/image' SETUP_GUIDE.md` shows the section and `grep -n -i 'gfx1151\\|ROCm-only' SETUP_GUIDE.md prod/scripts/4b-image.sh` hits in both; docs/design/07-deployment.md §7.7.2 gains the 4b step-table row and the template-mount seam note — probe: `grep -n '4b-image' docs/design/07-deployment.md` hits; the Krea disk footprint in docs/design/future/image-generation.md gains the two measured VAE sizes — probe: `grep -n 'krea2RealVae' docs/design/future/image-generation.md` shows the recorded sizes."
  - "mvn verify from repo root is green (shell/doc/compose-only diff; proves no drift)."
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
reviews:
  - round: 1
    date: 2026-08-09
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "8 files changed, 1005 insertions(+), 15 deletions(-)"
    findings: "3 rework items (1 medium, 2 low); 0 critical/high; 6 candidate findings falsified-and-dropped; 0 RECOMMENDED-NEW-TICKET entries"
    verdict_file: .scratch/tick-review-M1-798-r1.txt
  - round: 2
    date: 2026-08-10
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "8 files changed, 1060 insertions(+), 20 deletions(-)"
    findings: "0 rework items; 0 critical/high; all 3 round-1 REWORK items dispositioned SATISFIED; 0 RECOMMENDED-NEW-TICKET entries"
    verdict_file: .scratch/tick-review-M1-798-r2.txt
clarity_check: "start 2026-08-09 pass — tick-lint 0 findings; reproduction RED on main (grep -n image prod/setup.sh → no match); citations verified (setup.sh:19-33 STEPS, 1-profile.sh:11 VALID_PROFILES, design doc :846-871 container table 4.07/22.37/22.41 verbatim, ComfyUIClient.java:483-524 validatePromptSlot/validateSampler); analysis pitfalls P15/P22/P24/P26/P27/P28/P29/P30/P31/P32 + firewall half all present; prior-art artifacts READ per start.md superseded-implementation rule (worktree r1 REWORK verdict: 2 findings carried as acceptance items 10/11, 4 falsified-and-dropped — the picker-honesty falsification was against the OLD 3.75/21.81/53.07 table and is re-established against the new table by item 3; P32 restart residual out of scope to M1-803); P28 decode-wiring assumption already disproved live by M1-807 item 5 (exact VAELoader+VAEUtils_VAEDecodeTiled graph ran 896×672→1792×1344 @ 22.73 s in the rebuilt container); old-brief harvest staged as the starting point per analysis §Prior art disposition (mechanics survive falsification, content does not); blocked_by M1-807 done and added no tests, preserves-trace vacuous. D-3/P28 RESOLVED 2026-08-09 (user decision): the Krea template bakes the 2x stage — VAELoader(Wan2.1_VAE_upscale2x) + VAEUtils_VAEDecodeTiled(upscale=-1) + lanczos exact fit; node source at the pinned commit verified auto-upscale (12ch head -> pixel_shuffle 2x; 3ch VAEs decode 1x). The item-4 picker is implemented as an honest three-option menu: spacepxl 2x (DEFAULT, decision 5), krea2RealVae 1x (RECOMMENDED decoder label, decision 4), stock qwen_image_vae 1x (FALLBACK label, decision 4) — the stage node is VAEUtils_VAEDecodeTiled for every choice, so item 7's shape holds; a two-option menu whose choice the 2x-baked template ignores would repeat the round-1 SPEC-TRUTHNESS failure shape"
---

# M1-798: Setup-wizard /image step: picker, templates, ETA probe

## Context

D73 makes `/image` exist only when `infochat.image.base-url` is configured;
the setup wizard is the supported configuration path, and its STEPS list
(prod/setup.sh:23-33) has no image step — so the gate can never open for a
wizard-installed deployment. The 2026-08-09 measurement spike changed what
the step must write (Final decisions 1–7,
docs/design/future/image-generation.md:749-793): a VAE picker for Krea
(krea2RealVae recommended / stock fallback), the spacepxl 2× VAE-decode fit
stage (node provided by M1-807), per-model diffusion steps baked into the
templates (4/8/6), the spike's container numbers, license printing for the
two undeclared community VAE weights, and an ETA constant that is a live
probe of the FINAL template — never a table lookup, never written on the
remote path. Shared analysis: `analysis_ref:`. Prior art (old-brief worktree
implementation + its REWORK review) is disposed in the analysis §Prior art
disposition; mechanics survive falsification, content does not.

## Root cause

Feature gap plus a changed brief. The wizard is the single registration
point for setup steps ("leaf subscripts never self-register",
prod/setup.sh:19-22), so the work is the new script plus its STEPS entry
plus the profile-gating adaptation — and the 2026-08-09 Final decisions
re-specified the step's content (template shape, picker content, ETA
mechanism) after the original draft. The spacepxl fit stage additionally
needs the ComfyUI-VAE-Utils node in the M1-797 image before any Krea
template can execute — hence blocked_by M1-807 (M1-797 itself is done).

## Pitfalls

Numbered consistently with the analysis document.

- P15: the step now AUTHORS graph templates for an endpoint that executes
  them (D77). Each template keeps exactly one INFOCHAT_PROMPT_PLACEHOLDER
  text field and exactly one numeric-seed KSampler — the contract
  ComfyUIClient enforces at Provider boot (ComfyUIClient.java:483-524, :91);
  the negative prompt is static baked text, never a second placeholder.
- P22/P26: print only CONTAINER numbers as container numbers — the VAE-lever
  figures 22.14/22.54 s are conda-env measurements (design doc :734), and
  presenting them as container numbers is exactly the P22 trap; preflight
  (HEAD/disk/memory) before any multi-GB download; dedupe qwen3vl_4b; local
  install never offered on pi/vps.
- P24: model files are operator assets that persist; the delete-previous
  offer on switch names only the previous install's files, skips every
  shared blob (encoder AND VAE files), and is not part of the D75
  no-content chain.
- P27: the two VAE weights are license-UNDECLARED on HuggingFace — printed
  as community assets with that label; the node repo as MIT; never claim a
  licence the HF card does not state.
- P28: the Krea template's decode wiring (picked decoder × spacepxl 2× fit
  stage in one baked template) is an ASSUMPTION the committed record does
  not pin — verify against the spike graphs (/tmp/opencode/img-measure/,
  volatile) or the spacepxl/fblissjr docs; the proof is execution (the ETA
  probe generation runs the FINAL template).
- P29: probe protocol traps — unique seed per run (fixed seeds hit the node
  cache), warm-up discarded (first-run autotune leak).
- P30: the remote path writes NO ETA constant (no probe runs against the
  entered URL; unset → position without ETA once M1-803 lands).
- P31: do_reset must tear down the comfyui overlay (M1-395 invariant,
  prod/setup.sh:156-165; §10).
- P32: template mount auto-created directory — write side rmdirs it; the
  Provider-restart residual is M1-803's DECIDE-BEFORE, not this ticket's.
- P22 (firewall half): the two-box path prints the D77 requirement — the
  wizard documents; it cannot verify network topology, so the printed text
  IS the control; a missing or watered-down line reopens redteam finding 2.

## Approach

- **Files to touch:** `files_scope` (one new step script under
  prod/scripts/, the STEPS edit + do_reset fix, the compose template mount,
  SETUP_GUIDE.md section, 07-deployment.md row + seam note, design-doc disk
  sizes).
- **Steps, in order:**
  1. Write `prod/scripts/4b-image.sh`: profile gate → menu (local on capable
     profiles; remote or not-enabled elsewhere) → picker (3 × 2 with the
     spike container numbers + disk table incl. the two Krea VAEs) → Krea
     VAE choice (krea2RealVae recommended / stock fallback) → community-
     asset licence disclosure → preflight (HEAD every asset URL incl. both
     VAE files, disk, memory) → download (qwen3vl_4b dedupe; HF-stall retry
     loop per handoff §7) → write the per-model template (steps/VAE/fit
     stage baked; one placeholder; one numeric-seed KSampler; numeric
     latent/fit dims) → bring up the overlay (local) → `/system_stats`
     healthcheck → ETA probe (warm-up + five timed runs of the FINAL
     template, unique seeds, steady-state mean) → config writes → re-run/
     switch/disable path. Remote branch: firewall disclosure → URL prompt →
     template write → config writes WITHOUT the ETA constant.
  2. Register the step in prod/setup.sh STEPS (after the LLM step, before
     apps start — the Provider reads the keys at create time) and extend
     do_reset's probe + down with `-f docker-compose.comfyui.yml` (P31).
  3. docker-compose.yml: Provider service gains the template bind mount
     `./prod/runtime/comfyui-workflow.json:/app/comfyui-workflow.json:ro`
     (absent until step 4b runs; the write side rmdirs an auto-created
     directory, P32).
  4. SETUP_GUIDE.md section; docs/design/07-deployment.md §7.7.2 step-table
     row + mount seam note; record the measured Krea VAE sizes in the
     design doc's footprint table.
  5. Run every probe in `acceptance`.
- **Controls to preserve (§10):** mark_done/is_done resume mechanics used,
  not reinvented (prod/setup.sh:70-71); no existing step script edited;
  M1-389 `--env-file` compose discipline; M1-464 reset-silence posture; the
  M1-395 reset invariant extended to comfyui (P31); ComfyUIClient's
  validation contract consumed unchanged (no Java edits); 4-llm.sh's
  disclosure-before-commit shape for the firewall text; fetch_gguf's
  skip-if-present precedent for dedupe.
- **Pitfall→mitigation:** P15→step 1's template generator + item 7;
  P22/P26→items 3 + 6; P24→step 1's switch offer wording + item 11;
  P27→item 5; P28→step 1's verification duty + item 8's execution proof;
  P29→item 8; P30→item 10; P31→step 2 + item 11; P32→step 3 + item 11;
  firewall→item 9.

## Definition of done

Step registered and profile-gated; picker honest (container numbers only)
with the Krea VAE choice and community-asset licence labels; preflighted
downloads incl. both VAE files; templates bake steps/VAE/fit stage and pass
the client validation contract; local ETA is the live probe mean, remote
leaves it unset; firewall requirement printed; config written, overlay up,
healthchecked; re-run/switch/disable works; `--reset` stops the comfyui
container; guide + deployment design doc updated; verify green.

## Verification

- P15 → item 7's template-validation probe (FAILURE-MODE: a two-placeholder
  mutation fails the check, exactly as ComfyUIClient rejects it at boot).
- P22/P26 → item 2's per-profile menu probe (FAILURE-MODE: pi/vps never see
  local), item 3's numbers probe (FAILURE-MODE: conda number in the
  container table), item 6's dead-URL abort probe (FAILURE-MODE: preflight
  fires before any download).
- P24 → item 11's switch probe; the delete offer removes only the previous
  install's files and never a shared blob (FAILURE-MODE: switching Krea
  tiers must leave the shared encoder and VAE files intact).
- P27 → item 5's printed-label probes.
- P28 → item 8: the ETA probe generation EXECUTES the FINAL template in the
  container and produces an image — a mis-wired Krea decode stage fails it.
- P29 → item 8's protocol assertions (unique seeds, discarded warm-up).
- P30 → item 10's remote-path probe (key absent, model present).
- P31 → item 11's do_reset awk probe + live reset probe (FAILURE-MODE: a
  running comfyui container must not survive `--reset`).
- P32 → item 11: a directory at the mount source is replaced by the file.
- Firewall disclosure → item 9's printed-text probe.
- Non-vacuity: removing the STEPS entry fails item 1; dropping a HEAD check
  fails item 6; omitting the firewall line fails item 9; writing the ETA on
  the remote path fails item 10; a table-lookup ETA fails item 8.

## Out-of-scope

Named in `out_of_scope`: the overlay/image (M1-797) and the node carve
(M1-807 — this ticket consumes the node, never edits the Dockerfile); all
Java including ComfyUIClient (its validation contract is consumed
unchanged); the /image handler, converter guardrail, and translator-leg
disclosure texts (M1-803); non-curated models; chat-level or runtime
pipeline knobs; any diffusion upscaler (v1 exclusion); the P32 restart
residual and the converter DECIDE-BEFORE (M1-803's analysis). The
auto-created-directory write side IS in scope (item 11); only the
already-created-container restart question is deferred. No pre-existing
test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-798-image-setup-wizard-step.md
```

## Round 1 rework

1. FINDING 1: stop/remove the comfyui container and run the
   delete-previous-files offer on a local-to-remote switch, in
   prod/scripts/4b-image.sh's remote branch (:818-858), reusing
   stop_comfyui_if_present (:329-334) and the offer block (:759-802) with
   an empty shared-blob set — verified by
   `awk '/^  remote\)/,/^  none\)/' prod/scripts/4b-image.sh | grep -c stop_comfyui_if_present`
   >= 1 and the live switch probe leaving `ps -q comfyui` empty with the
   delete offer printed.
2. FINDING 2: reconcile the spacepxl licence label — verify the HF card and
   either record Apache-2.0 in docs/design/future/image-generation.md's
   licence paragraph or print the undeclared-licence label at
   prod/scripts/4b-image.sh:452-455 and SETUP_GUIDE.md:384-386 — verified
   by the grep probe in FINDING 2's EVALUATED-AS.
3. FINDING 3: replace the conda-derived "~22.5 s" with the container
   figure "~22.7 s" (or no number) at prod/scripts/4b-image.sh:425 —
   verified by `grep -n '22\.5 s' prod/scripts/4b-image.sh` printing
   nothing.
