---
id: M1-829
title: "Split 4b-image.sh picker into decision view + --verbose detail"
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  ImageWizardStepTest.pickerLeadsWithDecisionTableNotAuditDetail
  (to-be-written) — drives `prod/scripts/4b-image.sh --dry-run` against a
  temp INFOCHAT_RUNTIME_DIR carrying quarkus.profile=laptop (the
  LlamacppWiringTest harness pattern) and asserts the six-row decision
  table leads, with the disk-arithmetic formulas and latency-footnote
  prose absent from default output and present under --verbose. RED on
  main: print_picker (4b-image.sh:259-287) emits ~24 lines in which a
  4-line preamble precedes the table and ~13 lines of footnotes, disk
  formulas and hardware scope follow it, with no --verbose flag existing
  (the arg loop at :202-210 rejects it with exit 2). Probe observation on
  main: `INFOCHAT_RUNTIME_DIR=<tmp> prod/scripts/4b-image.sh --dry-run`
  prints the wall described in .scratch/setup-hurdles.md item 9.
analysis_ref: docs/plan/m1/tick-analysis/operator-ux.md
blocked_by: []
files_scope:
  - prod/scripts/4b-image.sh
  - SETUP_GUIDE.md
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/ImageWizardStepTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Any behavior or flow change: prompt order, defaults, preflight,
    downloads, template writes, config writes, exit codes — --verbose
    gates PRINTING only.
  - The D77 firewall disclosure text (4b-image.sh:855-860) — a redteam-fix
    control, preserved byte-identical; NOT part of the compaction.
  - The Krea decode-pipeline menu content (choose_krea_decode, :478-498)
    — already a compact 3-option decision menu.
  - 4-llm.sh GGUF/GPU logic (batch B); the M1-828 verb table and any
    1-profile.sh / 4-llm.sh / switch-llm.sh text (sibling M1-828's lane).
  - Any docs/spec/** edit — output re-presentation of behavior D73/D77
    already promise.
acceptance:
  - "REPRODUCTION, now passing: ImageWizardStepTest.pickerLeadsWithDecisionTableNotAuditDetail — the --dry-run default output presents the table first (one-line provenance header + the six rows, steady-state marker inline in the Z-Image row, one-line hardware scope, one-line --verbose pointer); the 2026-08-09-spike provenance preamble, the latency footnotes, and the disk-arithmetic formulas appear ONLY under --verbose, moved verbatim (P7 superset rule)."
  - "Picker honesty preserved, default output (analysis P1/P2; M1-798 acceptance item 3; docs/spec/decisions.md D73 — D73 makes content liability rest on operator model choice, so the picker IS that choice surface and its numbers must stay honest and pre-commit): the default picker output still contains all six curated options with the spike CONTAINER-measured latencies (4.07 / 22.37 / 22.41 s), the per-model disk figures, and the Z-Image steady-state marker — probe: ImageWizardStepTest.dryRunDefaultKeepsDecisionTableAndNumbers asserts each (FAILURE-MODE: a conda-measured number such as 22.14/22.54/53.07 anywhere in picker output fails the test; dropping the steady-state marker fails it)."
  - "Krea licence disclosure survives compaction (analysis P1; M1-798 acceptance item 5 / its pitfall P27): the default (non-verbose) Krea path still prints, BEFORE any download, both community VAE filenames (krea2RealVae_v10.safetensors, Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors), the licence-UNDECLARED label, the spacepxl Apache-2.0 card note, and the ComfyUI-VAE-Utils MIT line — probe: ImageWizardStepTest.kreaLicenceDisclosureSurvivesCompaction drives the Krea local path with fake docker/curl and asserts all four facts in the pre-download output (FAILURE-MODE: deleting the UNDECLARED label or moving the block behind --verbose fails the test); an awk script-order probe asserts print_krea_asset_licences is called before the first head_check in the local branch."
  - "Output-only flag semantics (analysis P7): with and without --verbose, the same drive produces identical prompts, identical exit codes, and an identical written application.properties; the --verbose output contains every moved detail line verbatim (strict superset of the old default's detail) — probe: ImageWizardStepTest.verboseIsOutputSupersetDefaultFlowUnchanged (FAILURE-MODE: an extra prompt, a changed default, or a dropped detail line fails it)."
  - "Input validation preserved (analysis P2): an unknown flag still exits 2 with usage on stderr — probe: ImageWizardStepTest.unknownFlagStillExits2 (FAILURE-MODE: --verbose parsing that swallows an unknown flag fails it); usage() documents --verbose; `bash -n prod/scripts/4b-image.sh` passes."
  - "Guide row: SETUP_GUIDE.md's 4b-image.sh script-table row (:795) documents --verbose, and the step-4b section gains at most one line saying the picker prints the decision table first with detail behind --verbose — probes: `grep -n -- '--verbose' SETUP_GUIDE.md prod/scripts/4b-image.sh` hits both; no other step-4b prose changes (the picker content the guide documents is unchanged — only the script's output layout moves)."
  - "mvn verify from repo root is green (new test class runs under it; proves no drift)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/ImageWizardStepTest.java
      — pickerLeadsWithDecisionTableNotAuditDetail (reproduction),
      dryRunDefaultKeepsDecisionTableAndNumbers (P1/P2 honesty),
      kreaLicenceDisclosureSurvivesCompaction (P1 control),
      verboseIsOutputSupersetDefaultFlowUnchanged (P7),
      unknownFlagStillExits2 (P2 validation)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/decisions.md (D73, D77)
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D73
  - D77
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-829: Split 4b-image.sh picker into decision view + --verbose detail

## Context

Live setup session 2026-08-11 (`.scratch/setup-hurdles.md` item 9):
`print_picker` (prod/scripts/4b-image.sh:259-287) emits ~24 lines before
the 1–6 model choice — preamble, table, latency footnotes, disk-arithmetic
formulas, hardware scope — burying the decision table in audit detail; the
Krea path adds a 9-line licence block on top (:503-514). This is a UX
critique of M1-798's shipped, deliberate output: its acceptance items
gated the NUMBERS (honesty basis), not the line budget. Shared analysis:
`analysis_ref:`.

## Root cause

One output channel carries both decision data and audit provenance with no
verbosity split. M1-798 (done, f2fcd886) required the six curated options
with spike container-measured latencies and per-model disk printed BEFORE
commit (its acceptance item 3) and the Krea licence labels before download
(item 5) — both correct and preserved here — but nothing bounded the
surrounding prose, so provenance (2026-08-09 spike sourcing, per-blob disk
arithmetic, dedupe notes) lands inline by default.

## Pitfalls

Numbered consistently with the analysis document. This ticket carries
M1-798's P27 (licence-UNDECLARED disclosure) forward inside P1; it keeps
its original number wherever M1-798's acceptance items are cited.

- P1: compaction drops a control — the licence block (pre-download legal
  control, M1-798 P27) and the container-numbers table + steady-state
  marker (M1-798 item-3 honesty basis) must stay in DEFAULT output; the
  D77 firewall text (:855-860) is byte-preserved and out of scope (§10).
- P2: `--dry-run` prints gate + picker with no flag (:299-304) and
  M1-798's item-3 probe ran against it — the default view must keep all
  six options, the numbers, and the steady-state marker; the arg-loop edit
  must preserve unknown-flag exit 2 (:207).
- P6: sibling calibration — assert nothing about the usage/header lines
  M1-828 adds to other scripts, and do not let M1-828's probes pin this
  picker's text.
- P7: output-only — `--verbose` gates PRINTING only; prompts, defaults,
  preflight, downloads, template writes, exit codes byte-identical (brief:
  "no behavior change intended"; §1).

## Approach

- **Files to touch:** `files_scope` (one script, one new test class, one
  guide row + at most one guide line).
- **Steps, in order:**
  1. Write `ImageWizardStepTest` on the LlamacppWiringTest harness pattern
     (fake `docker`/`curl` on PATH, temp `INFOCHAT_RUNTIME_DIR` carrying
     `quarkus.profile=laptop`; LlamacppWiringTest.java:473-608) — run RED.
  2. `4b-image.sh`: add `--verbose` to the arg loop and usage(); split
     `print_picker` into the default decision view (one-line provenance
     header; the six-row table with the steady-state marker moved INLINE
     into the Z-Image row; one-line hardware scope; one-line --verbose
     pointer) and the verbose detail block (the moved preamble, footnotes,
     and disk arithmetic verbatim). Compact `print_krea_asset_licences`
     (~9 → ~5 lines) keeping all four facts (P1/P27).
  3. SETUP_GUIDE.md: the :795 row gains `--verbose`; one line in the
     step-4b section names the new output shape.
  4. Run every probe in `acceptance`; mvn verify.
- **Controls to preserve (§10):** all six options + container numbers +
  steady-state marker + per-model disk in default output (M1-798 item 3);
  licence block complete, pre-download, default output (item 5 / P27);
  firewall text byte-identical; hardware-gate FAIL (:777-781) untouched;
  unknown-flag exit 2; `--dry-run` preview contract.
- **Pitfall→mitigation:** P1 (incl. carried P27)→step 2 + items 2/3;
  P2→items 2/5; P6→Out-of-scope; P7→item 4.

## Definition of done

Default picker output leads with the decision table and a one-line
recommendation context; all audit detail reachable verbatim via
`--verbose`; the licence disclosure survives compact but complete and
pre-download; flow, prompts, and written config are provably unchanged;
validation preserved; guide row updated; new test class green; mvn verify
green.

## Verification

- P1 → `ImageWizardStepTest.dryRunDefaultKeepsDecisionTableAndNumbers`
  (FAILURE-MODE: a conda number or a dropped steady-state marker fails it).
- P27 (M1-798's pitfall, carried inside P1) →
  `ImageWizardStepTest.kreaLicenceDisclosureSurvivesCompaction`
  (FAILURE-MODE: dropping the UNDECLARED label or gating the block behind
  --verbose fails it) + the awk script-order probe (licence print precedes
  the first `head_check`).
- P2 → `ImageWizardStepTest.unknownFlagStillExits2` and the item-2 dry-run
  assertions.
- P6 → Out-of-scope: no assertion on M1-828's surfaces.
- P7 → `ImageWizardStepTest.verboseIsOutputSupersetDefaultFlowUnchanged`
  — same drive ± --verbose: identical prompts/exit codes/written config;
  verbose output a verbatim superset of the moved detail (FAILURE-MODE:
  any flow delta or dropped line fails it).
- Non-vacuity: removing the table-first ordering fails the reproduction
  test; moving a number behind --verbose fails item 2; deleting a licence
  fact fails item 3; adding a prompt fails item 4; swallowing a bad flag
  fails item 5.

## Out-of-scope

Named in `out_of_scope`: any behavior/flow change; the firewall text
(byte-preserved redteam-fix control); the decode menu content; 4-llm.sh
(batch B); M1-828's verb table and script usage text; spec edits (D73/D77
already promise this behavior — output re-presentation only). No
pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-829-operator-ux-2.md
```
