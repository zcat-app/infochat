---
id: M1-854
title: "Recalibrate image preview to real generator output"
status: done
created: 2026-08-16
last_updated: 2026-08-16
reviews:
  - round: 1
    date: 2026-08-16
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY WARN, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "6 files changed, 163 insertions(+), 29 deletions(-)"
    rework_items: 1
    verdict_file: .scratch/tick-review-M1-854-r1.txt
  - round: 2
    date: 2026-08-16
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "fix hunks: 4 files changed, 51 insertions(+), 3 deletions(-) (full diff since merge-base: 6 files, 212 insertions(+), 30 deletions(-))"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-854-r2.txt
clarity_check: >-
  2026-08-16: lint green (analysis doc copied into the worktree — tick-analysis/
  is gitignored, worktrees do not inherit it); every file:line citation in Root
  cause/Approach spot-checked against the tree and true; analysis P1-P5 all
  landed in Pitfalls/acceptance; blocked_by empty; M1-842 suite traced under
  the planned change (ceiling-10 case terminates via the no-progress floor —
  halving below the raster's own pixel count cannot shrink it further); no
  ambiguity blocking implementation.
flow: tick
reproduction: >-
  ImagePreviewGeneratorTest.photographicScaleOutputCarriesAnInCeilingPreviewAtShippedDefaults
  (RED confirmed 2026-08-16 at shipped defaults 65536/14822: generate() returns
  null — .scratch/m1-854-red-run.log, the only failure among 1867 provider
  unit tests): a deterministic high-entropy raster at /image
  generation resolution (1792×1344) — strip-validated, within the configured
  max-output-pixels bound (default 5,000,000, ImageCommandHandler.java:116) —
  is pushed through ImagePreviewGenerator at the SHIPPED defaults read from
  infochat-provider/src/main/resources/application.properties (today
  preview-max-pixels=65536 :233, preview-max-chars=14822 :234). Today the
  65536-px downscale of real-entropy content encodes far over the 14,822
  ceiling (live-probe measurement: a real 1792×1344 /image output → 295×221
  → 71,132 chars, 4.8× over — .scratch/tick-brief-image-v7-probe-defects.md)
  and the char check at ImagePreviewGenerator.java:60 degrades to null, so
  the live-observed wrong behavior is: /image output arrives as a file
  attachment, not an inline picture (probe-v7-image3.log, 2026-08-15 — the
  M1-843 image branch never fires because imagePreview is null at encode).
analysis_ref: docs/plan/m1/tick-analysis/image-v7-live-probe-defects.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/image/ImagePreviewGenerator.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImagePreviewGeneratorTest.java
  - docs/design/06-messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The recorded char ceiling — infochat.image.preview-max-chars stays 14822,
    the M1-841-probed accept boundary (16,500 refused; the over-limit send
    leaks a stray standalone file beside the refused message and burns a
    non-refundable credit, D76). The pixel budget is recalibrated to fit it;
    the ceiling never moves.
  - The SimpleX codec/adapter wire branch (M1-843 — correct, never fires due
    to the null preview; no SimpleX* or Signal* file changes here).
  - Any docs/spec/** edit — messaging.md §Required SPI surface already
    promises the inline preview; this ticket restores the promise, it does
    not change it. The design-record edit is docs/design/06-messaging.md.
  - PngMetadataStrip, ImageSpool, ImageSpoolSweeper, OutboundDelivery,
    ComfyUIClient, ImageCommandHandler (their controls are preserved, not
    rerouted; the handler wiring strip → preview → spool → send at
    ImageCommandHandler.java:332-386 is untouched).
  - JPEG/WebP preview encoding (a future quality knob behind its own probe
    ticket — the live-probed accepted form is a PNG data URI; analysis
    Option D).
acceptance:
  - "ImagePreviewGeneratorTest.photographicScaleOutputCarriesAnInCeilingPreviewAtShippedDefaults passes — REPRODUCTION (to-be-written at start, run RED): a deterministic high-entropy fixture at 1792×1344 through generate() at the SHIPPED defaults (both keys read from the main application.properties, the existing readShippedPreviewCharCeiling filesystem pattern, ImagePreviewGeneratorTest.java:123-146) asserts a NON-NULL preview carrying the recorded data:image/png;base64 form, within the shipped char ceiling, produced from post-strip bytes — the fixture must be pseudo-random content (photographic-scale entropy), never a flat/synthetic PngFixtures.realPng shape (analysis P2: flat-magenta fixtures compress to near-zero and fit any budget, which is how the calibration defect shipped green)."
  - "Adaptive shrink-to-fit lands in ImagePreviewGenerator: when the encoded preview exceeds preview-max-chars after downscale, the pixel budget is halved and the preview re-encoded until it fits or the minimal raster is reached; null ONLY when nothing can fit (undecodable input, or a ceiling no PNG can meet) — Verify: a new test feeding an image whose preview exceeds the ceiling at the initial budget (high-entropy content at the recalibrated default) asserts an in-ceiling preview is still produced (analysis P3; this assertion discriminates the chosen design from config-only recalibration, which returns null here), plus a termination assertion (bounded iterations; the minimal-raster floor exits the loop)."
  - "FAILURE-MODE (analysis P1/P3, security.md §Trust boundaries item 9 — the decode input is endpoint-chosen bytes): refusesOrDegradesHostileInput's protected properties all survive — non-PNG bytes, garbage-IDAT bytes, and over-IHDR-bound images still return null; a char ceiling no PNG data URI can meet (the existing ceiling-10 case) still returns null after exhausting the shrink ladder, and no test path ever emits a preview over the configured ceiling — Verify: the test passes with its four null assertions intact (any over-limit emission fails it)."
  - "AUTHORIZED MODIFICATION (engineering-rules §8): ImagePreviewGeneratorTest is modified — (a) the existing constructor budgets 65_536L (ImagePreviewGeneratorTest.java:49,71) move to the recalibrated shipped default and the new fixture values, because the shipped default itself changes; (b) new fixtures/tests per items 1-2 are added. The 14,822 shipped-ceiling assertion (readShippedPreviewCharCeiling, :40-44) and the post-strip/post-decode semantics of every existing assertion are UNCHANGED — Verify: the modified file's diff shows no weakened assertion; the ceiling assertion still reads 14_822."
  - "The shipped default infochat.image.preview-max-pixels flips 65536 → 8192 (application.properties:233) — the probe-measured anchor (8192 px → 104×78 → 10,668 chars ≈ 28% headroom on the real probe image) — and docs/design/06-messaging.md §6.2.4's config-table row is rewritten to record the derivation and the shrink-to-fit semantics (the pixel budget is the INITIAL downscale target; the recorded char ceiling is the binding constraint; degrade-to-file remains only for genuinely undecodable input or an unreachable ceiling), replacing the falsified 'sized so an encoded preview fits the char ceiling with margin for typical image content' claim (06-messaging.md:578) — Verify: `grep -n 'preview-max-pixels' docs/design/06-messaging.md infochat-provider/src/main/resources/application.properties` shows 8192 in both and the new semantics text; DocumentedConfigKeyParityTest green via mvn verify (key NAMES unchanged)."
  - "Controls preserved (engineering-rules §10; analysis P5): M1-842's suite stays green untouched beyond the item-4-authorized budget values — the spool-single-file assertion (OutboundDeliveryAttachmentTest), the handler degrade-to-file test (delivery completes, success audit outcome, null preview when the generator degrades), the InMemoryAdapter preview-recording assertion, and the Signal non-coupling pin (SignalMessageCodecTest) — Verify: `mvn verify` green with no modification to those files in this diff."
  - "LIVE verification on the test instance (stack up, probe-v7 client registered; never prod): one /image run delivers the INLINE image wire form — the bot's own chat DB (/_get chat via the sidecar) shows msgContent.type=image with the preview member for the sent item — Verify: the probe log/DB excerpt recorded in the ticket's evidence section; the false-failure report that may accompany the send is M1-855's defect and does not fail this item."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImagePreviewGeneratorTest.java (photographicScaleOutputCarriesAnInCeilingPreviewAtShippedDefaults; the shrink-to-fit and termination tests; the exceed-at-initial-budget test; r2-rework underBudgetOverCeilingRasterStillShrinksToFit)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImagePreviewGeneratorTest.java (constructor budget values to the recalibrated default — pre-authorized by acceptance item 4)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/commands.md §Content
decision_refs:
  - D74
  - D75
  - D76
---

# M1-854: Recalibrate image preview to real generator output

## Context

Live probe 2026-08-15 (test instance, bundled CLI v7.0.0.11): `/image` output
arrives as a file attachment, not an inline picture — the pre-M1-843
behavior on code that merged the fix. The M1-843 codec branch is correct but
never fires: `ImagePreviewGenerator` degrades every real image to a null
preview at the char check (ImagePreviewGenerator.java:60), because the
shipped pixel budget (65536, application.properties:233) downscales a real
1792×1344 output to 295×221 ≈ 71,132 base64 chars — 4.8× over the recorded
14,822-char transport ceiling (06-messaging.md §6.2.4, M1-841 probe). The
user's decision (2026-08-15): the fix MUST make the image form fire for real
photographic output; degrade-to-file stays only for genuinely undecodable
input. Shared analysis: `analysis_ref:` (P1-P5 are this ticket's slice).

## Root cause

Proven (analysis §Root cause, defect 1): the `preview-max-pixels` default is
miscalibrated for real generator output — real photographic entropy at the
65,536-px budget encodes ~4.8× over the ceiling, so `generate` returns null
for every real image and the codec's image branch (keyed on preview
presence, SimpleXMessageCodec.java:286-289) never fires. M1-842's fixtures
were near-zero-entropy synthetic PNGs (PngFixtures.java:26-35 — flat fill +
two shapes) that fit any budget, so the suite was green while every real
image degraded. The design record's calibration claim
(06-messaging.md:578) is falsified by the probe. Secondary gap (not a bug in
the mechanism): even a correct fixed budget degrades high-entropy real
images — one image's measured entropy is not a bound — which is why the fix
adds shrink-to-fit rather than only flipping the default.

## Pitfalls

- P1: the ceiling is a recorded transport limit, never a tuning knob —
  14,822 accepted / 16,500 refused (M1-841); an over-limit preview is
  refused (`largeMsg`) AND the filePath file still uploads standalone beside
  the refused message, and the credit is not refunded (D76). The generator
  bound exists to prevent exactly that; the fix strengthens it.
- P2: fixture realism — flat/synthetic fixtures fit any budget (that is how
  this shipped green); the new fixtures must carry deterministic
  photographic-scale entropy at generation resolution.
- P3: never-emit-over-ceiling must survive — the shrink ladder terminates
  (worst-case incompressible RGBA fits ≤ ~2,773 px under 14,822 chars), and
  null remains the outcome only when nothing can fit.
- P4: §8 — ImagePreviewGeneratorTest's constructor budgets are M1-842 pins;
  the modifications are pre-authorized in acceptance item 4 with the new
  expected behavior named there.
- P5: §10 controls — strip-first ordering, IHDR bound, no-retention
  (memory + record only), spool single-file, audit-neutral degrade, Signal
  non-coupling: preserved, and their pinning tests untouched.

## Approach

- **Files to touch:** `files_scope` (generator, provider
  application.properties, generator test, design record).
- **Steps, in order:**
  1. RED: write the reproduction test (item 1) with a deterministic
     pseudo-random 1792×1344 PNG (strip-validate it first — the generator
     input is always stripped bytes); run RED (null at shipped defaults).
  2. ImagePreviewGenerator: keep decode → IHDR bound → downscale as-is; the
     char check becomes the loop condition — on overflow, halve the pixel
     budget and re-downscale/re-encode from the decoded raster until the
     encoded preview fits or the minimal raster is reached; return null only
     then or on undecodable input (P1/P3). The emitted form is unchanged
     (PNG data URI from the recorded shape).
  3. New tests: exceed-at-initial-budget → in-ceiling preview
     (discriminates config-only from shrink-to-fit); termination/floor;
     undecodable → null (P2/P3).
  4. Config + record: flip the default to 8192; rewrite the §6.2.4
     config-table row with the derivation and semantics (item 5).
  5. Full `mvn verify`; live /image run on the test instance; record the
     wire-form evidence (item 7).
- **Controls to preserve (§10):** enumerated in acceptance item 6 — the
  M1-842 suite beyond the authorized budget values, spool single-file,
  handler degrade-to-file (audit-neutral, no D76 refund interaction),
  InMemory preview recording, Signal non-coupling.
- **Pitfall→mitigation:** P1→items 3-4 (ceiling assertion unchanged,
  over-limit never emitted); P2→item 1's fixture requirement; P3→item 2's
  shrink/termination tests + item 3's failure-mode; P4→item 4's §8
  authorization; P5→item 6.

## Definition of done

The reproduction test passes at shipped defaults with a high-entropy
fixture; the generator shrink-to-fits the recorded ceiling and terminates;
hostile input and unreachable ceilings still degrade to null with no
over-limit emission ever; the default is 8192 with the derivation recorded
in 06-messaging.md; the M1-842 control tests stay green; a live /image run
shows the inline image form in the bot's chat DB; `mvn verify` green.

## Verification

- P1 → items 3 (ceiling-10 case still null; no over-limit emit) and 5 (the
  14,822 assertion untouched; the record still names the accept/refuse
  boundary).
- P2 → item 1: the fixture is pseudo-random at 1792×1344; a flat fixture
  would pass today and fail review (non-vacuity: today's code returns null
  on it — the test is RED before the fix).
- P3 → item 2: exceed-at-initial-budget yields an in-ceiling preview
  (config-only recalibration returns null and fails this); termination
  asserted.
- P1/P3 FAILURE-MODE → item 3: ImagePreviewGeneratorTest.refusesOrDegradesHostileInput —
  the failure-mode test feeds the generator hostile input and asserts
  every refuse/degrade leg survives the fix: non-PNG bytes,
  garbage-IDAT bytes, over-IHDR-bound images, and the
  unreachable-ceiling (ceiling-10) case each still return null after
  exhausting the shrink ladder, and no test path ever emits a preview
  over the configured ceiling.
- P4 → item 4's authorization text + the diff showing no weakened
  assertion.
- P5 → item 6: the named control tests green and absent from the diff.
- Item 7 → the live DB/probe excerpt showing type=image with the preview
  member.
- Item 8 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: the char ceiling (recorded transport limit), the
SimpleX codec/adapter branch (M1-843 correct), any docs/spec edit (the
promise already exists — this restores it), the strip/spool/delivery/
ComfyUI/handler internals, and JPEG/WebP preview encoding (needs its own
live probe of the jpg form; analysis Option D records it as a future knob).
One pre-existing test file is modified — ImagePreviewGeneratorTest —
pre-authorized in acceptance item 4; no other pre-existing test is touched.

## Live evidence (acceptance item 7)

Test instance `/home/infochat/infochat-test` (never prod). Provider image
rebuilt from this branch; the running image's
`infochat-provider-1.1.0-SNAPSHOT.jar` was extracted and confirmed to carry
both the shrink-to-fit generator (`shrinkToFit`) and the baked defaults
`preview-max-pixels=8192` / `preview-max-chars=14822`. One `/image` run,
2026-08-16 02:28 local, probe-v7 client registered, transcript
`prod/runtime/test-clients/probe-v7/m1-854-liveverify-1.log`:

    @Admin-Reno /image a small red cube on white background
    Admin-Reno> Working on it...
    Admin-Reno> sends file image-4e0df95c-....png (2.0 MiB / 2075636 bytes)

The bot's own chat DB (`prod/runtime/simplex/simplex_v1_chat.db`, chat item
1657, `item_sent=1`) stores the sent item as an image message carrying the
inline preview member — the M1-843 image branch fired:

    {"sndMsgContent":{"msgContent":{"type":"image","text":"",
      "image":"data:image/png;base64,iVBORw0KGgo..."}}}

- `msgContent.type` = `image` (the inline form); pre-fix runs on this stack
  stored `type=file` (the defect — analysis §Problem, probe-v7-image3.log).
- Preview data-URI length 10,166 chars — within the 14,822 shipped ceiling.
- Sidecar live read (`WsProbe` `/tail @ProbeV7` against the subprocess WS
  :5225) returns the same `"type":"image",...,"image":"data:image/png;
  base64,..."` msgContent.
- Recipient (probe-v7) chat DB chat item 34: `msg_content_tag=image`.

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-854-r1.txt):

1. FINDING 1: In ImagePreviewGenerator.shrinkToFit (infochat-provider/src/main/java/
   app/zcat/infochat/provider/image/ImagePreviewGenerator.java:78-81), replace the
   `downscaled.pixels <= halved` no-progress exit with a minimal-raster exit
   (`halved < 1 || (downscaled.getWidth() == 1 && downscaled.getHeight() == 1)`) so
   under-budget over-ceiling rasters still shrink to fit; add the
   `underBudgetOverCeilingRasterStillShrinksToFit` test to ImagePreviewGeneratorTest
   as specified in EVALUATED-AS (RED on the current code, GREEN after the fix), and
   re-run `mvn verify` from the repo root green with the existing
   `unreachableCeilingExhaustsTheShrinkLadderToNull`,
   `refusesOrDegradesHostileInput`, and `photographicScaleOutputCarriesAnInCeilingPreviewAtShippedDefaults`
   unchanged and passing.
