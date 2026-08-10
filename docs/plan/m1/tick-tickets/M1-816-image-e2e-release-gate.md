---
id: M1-816
title: "Image e2e release gate: configured pipeline proof"
status: pending
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  probe (to-be-written): prod/live-probe-image-e2e.sh — submits the
  CONFIGURED runtime template's default graph and -r graphs (600x600,
  1600x900) to the deployment's own ComfyUI and asserts backend acceptance,
  exact output dimensions, and that the default output passes the
  configured infochat.image.max-output-pixels. Observed wrong output
  (bench/livetest-10-08-26.md E8/E10/E11): no such gate existed — the
  default output (1792x1344, 2,408,448 px) was rejected by the shipped
  2,000,000 ceiling and every -r graph was rejected by the backend
  ("ImageScale 9: Required input is missing: crop"), both discovered in
  live test AFTER round-3 APPROVE, because the stub-server tests validate
  graph shape only. In-suite companion, to-be-written:
  ImageCommandHandlerTest.defaultOutputAtTheShippedCeilingDeliversEndToEnd —
  RED on pre-M1-811 main (the shipped ceiling refuses the measured default
  dimensions through strip → spool → delivery).
analysis_ref: docs/plan/m1/tick-analysis/livetest-image-defects.md
blocked_by: [M1-811, M1-812]
files_scope:
  - prod/live-probe-image-e2e.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandHandlerTest.java
  - docs/design/future/image-generation.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Running the live probe inside `mvn verify` — it needs the GPU backend;
    verification.md §Test layers puts end-to-end smoke in layer 4
    (docker-compose, gates ship), the M1-797/M1-802 probe precedent.
  - A live SimpleX-client attachment probe — the adapter leg is covered
    in-suite by the M1-799/800/801 seam tests plus this ticket's wiring
    test (in-memory adapter); bench's "real adapter attachment" aspiration
    is recorded as operator-side release rehearsal, not automated here.
  - Any change to prod/scripts/4b-image.sh (the wizard's own ETA probe
    already executes the final template at enable time) or to
    ComfyUIClient/ImageCommandHandler production code.
  - The stale committed reference template
    prod/config/comfyui-workflow.json (analysis D-3 — fails the Provider's
    own boot validation; user follow-up, not absorbed here, §1).
  - The M1-802 D75 no-retention probe — preserved unmodified; this probe
    ADDS the delivery-dimension gate, never replaces the retention one.
acceptance:
  - "prod/live-probe-image-e2e.sh exits 0 against the deployment's own backend + runtime template — REPRODUCTION probe (verification.md §Test layers layer-4 shape; commands.md §Content: the configured pipeline must honour its own contract). Assertions: (a) DEFAULT job — backend accepts the baked graph with a canary prompt, completes, and the fetched PNG's IHDR pixel count is ≤ the configured infochat.image.max-output-pixels read from prod/runtime/application.properties (the E8 gate); (b) -r jobs 600x600 and 1600x900 — backend accepts each converter graph (no validation error — the E10 gate), and each fetched PNG's IHDR equals the exact requested WxH; (c) D75 hygiene inherited from the M1-802 probe mechanics — canary prompt only (never a real user prompt), history cleared after each job, no leftover output files, container stdout prompt-free."
  - "ImageCommandHandlerTest.defaultOutputAtTheShippedCeilingDeliversEndToEnd passes — the in-suite wiring half (analysis P14): the stub client returns a valid PNG with IHDR 1792×1344 built by the dimension-parameterized helper M1-811 landed; the handler's maxOutputPixels is READ FROM the shipped infochat-provider/src/main/resources/application.properties (never hardcoded); asserts the strip passes, the spool write happens, the attachment is delivered through the real OutboundDelivery + in-memory adapter path, exactly one IMAGE_GENERATE row {\"outcome\":\"delivered\"}, and the sanitized echo completes the placeholder. RED on pre-M1-811 main; blocked_by calibrates the fixture to the family END state."
  - "The gate statement is recorded: docs/design/future/image-generation.md gains a release-gate note adjacent to the ship-blocker text — mocked graph-shape tests alone do not approve image delivery; the configured pipeline is proven by THIS probe (backend half) and the shipped-ceiling wiring test (Provider half) before an /image-enabled release — Verify: `grep -n 'release gate' docs/design/future/image-generation.md`."
  - "FAILURE-MODE posture: the probe asserts the NEGATIVE too — a -r graph submitted WITHOUT the crop input (the pre-M1-812 shape, reconstructed inside the probe as the hostile baseline) must be REFUSED by the backend, proving the probe discriminates acceptance from rejection (a probe that passes on any submission is decoration; analysis P13)."
  - "The D75 probe is preserved: `git diff --name-only` shows no hunk in prod/live-probe-comfyui-no-retention.sh."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - prod/live-probe-image-e2e.sh
    - ImageCommandHandlerTest.defaultOutputAtTheShippedCeilingDeliversEndToEnd
  preserves:
    - prod/live-probe-comfyui-no-retention.sh unmodified
    - all tests currently green on main (the wiring method joins ImageCommandHandlerTest's existing stub harness; no existing method changes)
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs:
  - D75
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-816: Image e2e release gate: configured pipeline proof

## Context

Live test 2026-08-10 (bench/livetest-10-08-26.md E11 — PROCESS GAP): the
review/test evidence behind the /image build-out was unit-shaped. M1-802's
stub-server tests validate graph shape but never submitted a graph to a
real backend validator (the crop omission shipped green); M1-803's live
`-r` probe was declared outside the gate's evidence set; and no test ran
the configured default template through fetch → strip → spool → attachment
delivery at the SHIPPED ceiling. Green `mvn verify` proved unit contracts,
not production compatibility — E8 and E10 were both discovered in live
test after round-3 APPROVE. Shared analysis: `analysis_ref:` (pitfalls
P13-P15 below match it).

## Root cause

Structural, not a code defect (analysis §Root cause E11): a stub accepts
any graph shape, so backend-schema defects (E10) are invisible in-suite;
unit fixtures never met the shipped config values (the handler test sets
its own 2_000_000L at ImageCommandHandlerTest.java:133 and its PNG fixture
is 1×1 at :643), so the ceiling/output mismatch (E8) was invisible too;
and the one live check that would have caught both was explicitly parked
as a deploy-time check (M1-803 round-2 notes). The fix is verification
surface: what CAN run in `mvn verify` (the wiring at shipped config) lands
in-suite; what CANNOT (real-backend acceptance) lands as a release gate
probe, the verification.md:28-33 layer-4 shape the M1-797/M1-802 precedent
already uses.

## Pitfalls

Numbered consistently with the analysis document.

- P13: a stub accepts any graph — the E8/E10 class escapes unit tests by
  construction. The backend half of the gate must run against the
  deployment's OWN backend and CONFIGURED (runtime) template — analysis
  D-3: the committed reference template is not the production shape, so a
  probe running it would prove nothing. Canary discipline inherited from
  the M1-802 probe: never a real user prompt (D75), history cleared,
  leftover check, stdout prompt-free.
- P14: fixtures calibrate to the family END state — the wiring test
  asserts the SHIPPED ceiling (read from application.properties, never
  hardcoded) accepts the measured default dimensions through the full
  chain; RED before M1-811, green after, hence `blocked_by`. No earlier
  ticket pins the interim value; this test must not either.
- P15: probe hygiene on the GPU box — the probe reads the runtime
  base-url, ceiling, and template path from
  prod/runtime/application.properties; it never re-tunes measured
  requirements (the reanalysis's re-verification discipline) and leaves
  the backend prompt-free.

## Approach

Derived from `spec_refs:` — verification.md commits to a layer-4
end-to-end smoke that gates ship; this ticket realizes it for the image
pipeline at the two layers the medium allows.

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. The in-suite wiring method first (it is the mvn-verify half and the
     blocked_by calibration point): reuse ImageCommandHandlerTest's stub
     harness + M1-811's dimension-parameterized PNG builder; read the
     shipped ceiling from the main application.properties; assert the full
     chain and the delivered row.
  2. The probe script on the M1-802 precedent's mechanics
     (prod/live-probe-comfyui-no-retention.sh): compose against the
     runtime template (prod/runtime/comfyui-workflow.json), canary prompt,
     unique seed; default-job ceiling assertion; -r graphs for 600x600 and
     1600x900 built with the converter's two transforms (latent ratio dims
     at the baked budget /16 + ImageScale width/height/crop=disabled —
     ~15 lines of python mirroring ComfyUIClient.buildGraph; the drift
     risk is stated: ComfyUIClientTest pins the client's shape in-suite,
     the probe pins the backend's acceptance, a mismatch surfaces as a
     probe failure at release); the crop-less negative baseline;
     history-clear + leftover + stdout checks after every job.
  3. Run the probe against the GPU host's container; record the gate
     statement in the design doc.
  4. Full verify.
- **Controls to preserve (§10):** the D75 no-retention probe unmodified
  (this probe ADDS a gate, never replaces the retention acceptance check);
  the canary discipline (no real prompt touches a probe — D75); the
  handler's existing test suite (the wiring method is additive); the M1-802
  probe's compose/health-wait mechanics reused, not re-shaped.
- **Pitfall→mitigation:** P13→step 2's runtime-template targeting +
  acceptance item 1(c)'s hygiene; P14→step 1's config-read fixture +
  blocked_by; P15→step 2's runtime-property reads.

## Definition of done

Every acceptance item green by its named verification: the probe exits 0
on the deployment's own backend with all six assertion groups (default
ceiling, two -r acceptances with exact dims, canary/retention hygiene);
the wiring test passes at the shipped ceiling; the gate statement is
recorded; the negative baseline proves the probe discriminates; the D75
probe is untouched; full verify green.

## Verification

- reproduction → the probe's exit-0 run (item 1) +
  defaultOutputAtTheShippedCeilingDeliversEndToEnd (RED on pre-M1-811
  main — the blocked_by ordering is what makes it green here).
- P13 → acceptance item 1(a)/(b) assert the CONFIGURED template and the
  SHIPPED ceiling (a probe hardcoded to the reference template or to
  2,000,000 fails the item as written); item 1(c)'s canary checks.
- P14 → the wiring test's ceiling comes from application.properties —
  reverting M1-811's value reds it (the family end state is pinned, the
  interim state nowhere).
- P15 → `grep -n 'prod/runtime' prod/live-probe-image-e2e.sh` shows the
  runtime property/template reads; no hardcoded base-url.
- failure-mode → the crop-less negative baseline (item 4): a backend that
  accepted the pre-fix graph would fail the probe's refusal assertion —
  the probe cannot pass on any submission.
- Non-vacuity: re-introducing the crop omission reds the -r assertions;
  lowering the shipped ceiling below 2,408,448 reds both the wiring test
  and the default-job assertion.

## Out-of-scope

Named in `out_of_scope`: running the probe in `mvn verify` (GPU
requirement — layer-4 shape), a live SimpleX attachment probe (adapter leg
covered in-suite; release rehearsal recorded, not automated), any wizard
or production-code change, the stale reference template (analysis D-3,
user follow-up), and the D75 probe itself (preserved). No pre-existing
test is modified — the wiring method is additive to
ImageCommandHandlerTest's existing harness.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-816-image-e2e-release-gate.md
```
