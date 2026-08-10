---
id: M1-812
title: "ComfyUI ImageScale crop input on -r graphs"
status: done
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  ComfyUIClientTest.resolutionGraphCarriesTheImageScaleCropInput —
  builds client.buildGraph(prompt, 512, 768) against the wizard-shape
  template fixture, re-parses, and asserts the swapped fit node's inputs
  carry crop="disabled" next to width/height. RED on main: the builder sets
  width/height and removes the baked scalars but never sets crop
  (ComfyUIClient.java:140-146), exactly the graph the live backend rejected
  pre-sampling — "ImageScale 9: Required input is missing: crop"
  (bench/livetest-10-08-26.md E10).
analysis_ref: docs/plan/m1/tick-analysis/livetest-image-defects.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/image/ComfyUIClient.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ComfyUIClientTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Cropping as a product behavior — the latent is sampled at the requested
    ratio (/16) and the fit is a lanczos exact W/H (design doc :769-778,
    addendum decision 4 resolution (2)/(3)); crop is the schema-completing
    literal "disabled", never "center".
  - The template's baked fit node (ImageScaleToTotalPixels) and the
    no-flag graph — untouched (buildGraphWithoutResolutionKeepsTheBakedGraph
    stays green unedited).
  - The template validation contract (ComfyUIClient.java:596-680), the
    submit/poll/cancel/clear mechanics, the breaker — all untouched.
  - Real-backend validation — the stub cannot provide it; M1-816's live
    probe is that gate (analysis P13).
  - The stale committed reference template prod/config/comfyui-workflow.json
    (analysis D-3) — recorded for the user as a follow-up, not fixed here
    (§1).
acceptance:
  - "ComfyUIClientTest.resolutionGraphCarriesTheImageScaleCropInput passes — REPRODUCTION (written and run RED at start): the -r graph's swapped fit node carries class_type ImageScale with width, height, upscale_method AND crop=\"disabled\" (commands.md §Content: --resolution is the output-size contract — the submitted graph must be executable by the configured backend, which requires crop)."
  - "AUTHORIZED pre-existing test modification (§8): ComfyUIClientTest.buildGraphHonoursAnExactPerJobOutputSize gains the crop assertion — this ticket changes the -r graph to carry the installed ImageScale schema's required crop input, and E11 names THIS test as the one that never asserted it; the new assertion is crop=\"disabled\" on node 9's inputs, next to the existing width/height/scalar-removal assertions. No other line of that test changes."
  - "ComfyUIClientTest.resolutionGraphsForTheLiveFailedRatiosCarryCrop passes — FAILURE-MODE: for each live-failed or constructively-failed ratio (1600x900, 600x600, 1024x768 = 4:3, 768x1024 = 3:4) the built graph carries ImageScale with crop=\"disabled\" and the exact requested width/height — the class of defect the live test found at two ratios and predicted for the rest (bench E10)."
  - "The P15 property survives (analysis P3): ComfyUIClientTest.promptLandsInExactlyOneGraphStringField and buildGraphWithoutResolutionKeepsTheBakedGraph pass UNEDITED — the fix adds one static literal input, interpolates nothing, and leaves the flag-free graph byte-identical."
  - "The crop value is verified against the pinned backend: the design doc's converter/builder note records crop ∈ {disabled, center} at the pinned ComfyUI commit with \"disabled\" chosen (ASSUMPTION to verify at implementation against the pinned commit's ImageScale schema — the live error proves the input is REQUIRED; the enum value and default are confirmed against the pinned commit and recorded). Verify: `grep -n 'crop' docs/design/future/image-generation.md` shows the record."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - ComfyUIClientTest.resolutionGraphCarriesTheImageScaleCropInput
    - ComfyUIClientTest.resolutionGraphsForTheLiveFailedRatiosCarryCrop
  modifies:
    - ComfyUIClientTest.buildGraphHonoursAnExactPerJobOutputSize (the crop assertion — authorized by acceptance item 2)
  preserves:
    - all other tests currently green on main (promptLandsInExactlyOneGraphStringField, buildGraphWithoutResolutionKeepsTheBakedGraph, the sampling-dims tests, the stub-server lifecycle tests)
spec_refs:
  - docs/spec/commands.md §Content
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-10
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 48 insertions(+), 12 deletions(-)"
    verdict_file: .scratch/tick-review-M1-812-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-10
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-812: ComfyUI ImageScale crop input on -r graphs

## Context

Live test 2026-08-10 (bench/livetest-10-08-26.md E10): EVERY explicit
`-r` resolution builds an invalid ComfyUI graph. The probe reached the
backend, which rejected it pre-sampling — `ImageScale 9: Required input is
missing: crop` — for 1600x900 and 600x600, with 4:3/3:4 failing by
construction through the same builder. Every `-r` attempt refunds (the GPU
never ran) but burns a cooldown window and returns the generic failure.
Shared analysis: `analysis_ref:` (pitfalls P3/P4 below match it).

## Root cause

Verified at ComfyUIClient.java:140-146: the converter's fit-node swap puts
`class_type=ImageScale`, removes the baked `megapixels`/`resolution_steps`
scalars, and puts `width`/`height` — but never sets `crop`, which the
installed node's schema requires (live error above; the node at the pinned
backend commit declares `crop` ∈ {disabled, center}). The stub-server tests
cannot see a backend schema, so the omission shipped green (analysis E11):
`buildGraphHonoursAnExactPerJobOutputSize` (ComfyUIClientTest.java:141-162)
asserts class_type, width, height, and the scalar removal — never crop.
Cropping itself is never the product contract: the latent is sampled at the
requested ratio (/16) and the fit is a lanczos exact W/H (design doc
:769-778), so the schema-completing value is `"disabled"`.

## Pitfalls

Numbered consistently with the analysis document.

- P3: the graph is code execution on the GPU box (D77; the feature
  analysis's graph-injection pitfall) — the fix sets ONE static literal
  input via the JSON serializer
  (`fitInputs.put("crop", "disabled")`), interpolates nothing, and changes
  no other field; the one-string-field property stays pinned.
- P4: the insufficient test is the fix's witness — extend it WITH the §8
  authorization stated in acceptance item 2 (what it asserts and why),
  never silently.

## Approach

Derived from `spec_refs:` — commands.md §Content makes `--resolution` the
output-size contract; a graph the backend rejects before sampling cannot
honour it.

- **Files to touch:** `files_scope` (+ the design-doc record of acceptance
  item 5).
- **Steps, in order:**
  1. Write the reproduction test RED.
  2. Verify the pinned backend's ImageScale schema (crop enum + default);
     record in the design doc.
  3. One line in the swap: `fitInputs.put("crop", "disabled")`
     (ComfyUIClient.java:140-146 region).
  4. Extend buildGraphHonoursAnExactPerJobOutputSize per the authorization;
     add the four-ratio failure-mode test.
  5. Full verify; confirm the untouched-tests list green.
- **Controls to preserve (§10):** the one-string-field property and its
  test; the baked flag-free graph and its test; the template validation
  contract (consumed, never edited); submit/poll/cancel/clear and breaker
  semantics; the GraphRejectedException refund typing (unchanged — a
  backend rejection still refunds; this fix removes one cause of it).
- **Pitfall→mitigation:** P3→step 3's single literal + step 5's untouched
  tests; P4→step 4 + acceptance item 2's plain-language authorization.

## Definition of done

Every acceptance item green by its named test/verification: the
reproduction passes; the authorized extension lands; the four live-failed
ratios all carry the input; the P15 property and baked graph survive
unedited; the schema value is verified and recorded; full verify green.

## Verification

- P3 → promptLandsInExactlyOneGraphStringField +
  buildGraphWithoutResolutionKeepsTheBakedGraph, both unedited (a builder
  that interpolates or touches the baked graph fails them).
- P4 → the extended buildGraphHonoursAnExactPerJobOutputSize (deleting the
  new crop assertion is an unauthorized test edit the reviewer catches;
  a builder omitting crop fails it).
- reproduction → resolutionGraphCarriesTheImageScaleCropInput (RED on
  main, green on the fix).
- failure-mode → resolutionGraphsForTheLiveFailedRatiosCarryCrop — feeds
  1600x900, 600x600, 1024x768, 768x1024; asserts crop + exact dims on
  each (a builder fixed for one ratio only fails it).
- Non-vacuity: removing the one added line reds all three new/extended
  tests; setting crop="center" reds them on the value assertion.
- Real-backend acceptance is M1-816's live probe (the analysis's
  stubs-accept-any-graph pitfall), named here, not owned here.

## Out-of-scope

Named in `out_of_scope`: cropping as behavior, the baked fit node and
flag-free graph, the validation contract and job mechanics, real-backend
validation (M1-816), and the stale reference template (analysis D-3,
user follow-up). One pre-existing test is modified — named and authorized
in acceptance item 2 with its exact new assertion; no other pre-existing
test is touched.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-812-comfyui-imagescale-crop-input.md
```
