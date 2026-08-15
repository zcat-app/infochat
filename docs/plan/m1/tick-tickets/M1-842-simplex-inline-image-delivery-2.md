---
id: M1-842
title: "Outbound preview generation + SPI preview field"
status: done
created: 2026-08-11
last_updated: 2026-08-15
clarity_check: >-
  2026-08-15: no blocking ambiguity. Lint clean after restoring the
  gitignored analysis doc into the worktree (private artifact, absent
  in fresh worktrees). Citations verified: record components
  OutboundAttachment.java:24-29 + no-retain javadoc :11-15, handler
  strip/spool/send :322-364 with the constructor site :361, census
  grep re-run — exactly the six ticket sites, provider/image holds
  only the four named classes, SimpleXAdapter.java:864 metadata-only
  line, PngMetadataStrip.java:24-25 no-IDAT-inflation. M1-841 (only
  blocked_by) added no tests (adds: [], docs-only diff) — nothing to
  trace. P3 constants from 06-messaging.md §6.2.4: form
  data:image/png;base64 data URI; char ceiling 14,822 accepted /
  16,500 refused (maxEncodedMsgLength 15,602 minus wrapper). Execution
  judgments: preview char budget defaults to the recorded 14,822
  accept boundary; the dimension budget (preview-max-pixels, default
  65536) is a downscale knob derived from the byte ceiling and
  documented with its derivation in 06-messaging.md; the post-strip
  discriminator is pinned at the handler seam (captured generator
  input == strip output) because pixels are identical pre/post strip
  at the generator level.
flow: tick
reproduction: >-
  ImagePreviewGeneratorTest.generatesABoundedPreviewFromStrippedPng
  (written at start and run RED as a compile failure — no preview
  generator existed under infochat-provider/src/main, grep-verified;
  log .scratch/tick-red-M1-842.log): the intended test strips a
  prompt-carrying PNG (PngMetadataStrip first), generates the inline
  preview from the stripped bytes, and asserts the preview carries the
  M1-841-recorded form, stays within the M1-841-recorded byte limit,
  and was produced from POST-strip bytes (the handler seam pins the
  ordering). Turning green is the ticket's contract.
analysis_ref: docs/plan/m1/tick-analysis/simplex-inline-image-delivery.md
blocked_by: [M1-841]
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/OutboundAttachment.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/
  - infochat-provider/src/main/java/app/zcat/infochat/provider/image/
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryAttachmentTest.java
  - docs/design/06-messaging.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The SimpleX codec branch that PUTS the preview on the wire, and any
    change to SimpleXMessageCodec / SimpleXAdapter / their tests (M1-843 —
    this ticket ships no wire-behavior change).
  - The Signal adapter's wire encoding (signal-cli attaches by path; the
    preview field is inert for Signal — the non-coupling pin is M1-843's).
  - Recipient-client rendering behavior (P9 — client-side).
  - Changing PngMetadataStrip, ImageSpool, ImageSpoolSweeper, or
    OutboundDelivery (their controls are preserved, not rerouted).
  - Any spec edit (the rides-the-diff amendment rides M1-843).
acceptance:
  - "ImagePreviewGeneratorTest.generatesABoundedPreviewFromStrippedPng passes — REPRODUCTION (written and run RED at start): the preview is generated from the STRIPPED bytes (strip runs first — analysis P2: the IHDR pixel bound PngMetadataStrip validates is what bounds the decode; commands.md §Content 'image decode is pixel-bounded'), carries the M1-841-recorded form, and is within the M1-841-recorded byte limit taken from docs/design/06-messaging.md §6.2.4, never an invented value (P3)."
  - "ImagePreviewGeneratorTest.refusesOrDegradesHostileInput passes — FAILURE-MODE (analysis P2, security.md §Trust boundaries item 9): endpoint-chosen bytes that fail strip validation never reach the decoder, and bytes whose decode fails or whose downscaled preview still exceeds the probed byte limit degrade to a null preview — never an over-limit emit, never an escaping exception into the delivery path (P10)."
  - "OutboundAttachment gains `@Nullable String imagePreview` (jspecify per engineering-rules §7a; the D75 no-retention javadoc at OutboundAttachment.java:11-15 is extended to cover the preview — P4) and ALL SIX constructor sites are swept (analysis P5 census: ImageCommandHandler.java:361, OutboundDeliveryAttachmentTest.java:38, MessagingAdapterAttachmentSpiTest.java:18, InMemoryAdapterTest.java:150, SignalAdapterAttachmentTest.java:119, SimpleXAdapterAttachmentTest.java:243 — test sites pass honest values, never a convenient non-null where the production caller passes null) — Verify: `mvn -pl infochat-messaging-adapter,infochat-provider -am verify` green (the compile IS the census check)."
  - "The in-memory adapter's attachment recording covers the preview field so Provider-side paths stay testable (the M1-799 seam discipline) — Verify: InMemoryAdapterTest gains an assertion that the recorded payload tuple carries the preview value handed in."
  - "ImageCommandHandler wires strip → preview → spool → send with degrade-to-file semantics (P10, commands.md §Content 'graceful degradation is the contract'): a null preview is sent exactly as today (file form is M1-843's branch; here the record simply carries null), a preview-generation failure writes NO failure audit outcome and does not interact with D76 refund accounting — Verify: a handler-level test (provider command test tree) where the generator degrades asserts delivery still completes with the success outcome and a null preview on the record."
  - "The preview never touches the spool (P4): it lives in memory and in the record only — Verify: a test asserting the spool directory holds exactly one file per image after a preview-carrying delivery (spool-dir listing before/after)."
  - "New `infochat.image.preview-*` config keys (preview dimension/byte budget sourced from the M1-841 record) are documented in docs/design/06-messaging.md — Verify: `grep -n 'infochat.image.preview' docs/design/06-messaging.md` lists every new key and DocumentedConfigKeyParityTest passes via `mvn verify` (the M1-801 P16 gate)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImagePreviewGeneratorTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryAttachmentTest.java (spool-single-file assertion, item 6)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (preview-recording assertion, item 4)
    - infochat-provider command test tree (handler degrade-to-file test, item 5)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingAdapterAttachmentSpiTest.java (P5 constructor sweep, item 3)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterAttachmentTest.java (P5 constructor sweep, item 3)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterAttachmentTest.java (P5 constructor sweep, item 3 — the M1-843 codec test is NOT touched here)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryAttachmentTest.java (P5 constructor sweep, item 3)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/commands.md §Content
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D74
  - D75
  - D76
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN, SCOPE PASS"
    diff_stats: "15 files changed, 548 insertions(+), 27 deletions(-) (r1 diff vs merge-base f140de81; fixes added 5 insertions(+), 4 deletions(-), comment-only)"
    findings: "1 low (MAINTAINABILITY) — two new comments used bare pitfall/acceptance references (\"P2\", \"P10\", \"Acceptance item 6\") unresolvable without the ticket ID; both were prefixed with M1-842 with zero executable-line changes."
    fix_probes: "Fix hunks verified comment-only (git diff vs round-1 tree 5eac6b68: only // and /** lines in ImageCommandHandler.java + OutboundDeliveryAttachmentTest.java; no docs/spec, docs/design, or root-level *.md touched). Literal EVALUATED-AS probes mis-specified: probe 1's regex `(^|[/( ])P[0-9]+:` matches the prescribed fix's own 'M1-842 P2:' via its space alternative and two pre-existing comments predating the merge-base (ImageCommandHandler.java:165, OutboundDeliveryAttachmentTest.java:82), so it cannot return no lines; probe 2 returned 2, not 1, because the fix wraps 'M1-842 P2'/'M1-842 P10' across two lines. Intent-preserving probe run instead: every P-reference in the round's added lines is ticket-ID-prefixed (bare-ref grep over added lines finds only 'M1-842 Pn:' forms), and 'M1-842 item 6' present at OutboundDeliveryAttachmentTest.java:50. ./mvnw -B -pl infochat-messaging-adapter,infochat-provider -am test-compile BUILD SUCCESS (14:49:27). Fixed-tree snapshot .scratch/tick-fixes-M1-842.tree = ae848297cf5c299a0151899a584d69162ff87b98; round-1 green log remains .scratch/tick-test-M1-842-r1.log (mirror target/tick-test-M1-842-r1.log)."
    verdict_file: .scratch/tick-review-M1-842-r1.txt
---

# M1-842: Outbound preview generation + SPI preview field

## Context

M1-841 (blocked_by) records whether the bundled simplex-chat accepts
image-typed composed messages with an inline preview, and the preview's
size/encoding limits. This ticket builds the Provider half the recorded
form needs: the preview field on the SPI payload and the generator that
produces it inside the D75 pipeline. It ships NO wire-behavior change —
M1-843 flips the codec. Shared analysis: `analysis_ref:`.

## Root cause

Feature gap: `OutboundAttachment` (OutboundAttachment.java:24-29) has no
way to carry a preview, and no component produces one — the Provider
pipeline (ImageCommandHandler.java:322-364) goes strip → spool → send
with nothing in between. The adapter's posture is metadata-only
(SimpleXAdapter.java:864: "the adapter never opens the payload"), so the
preview must be generated Provider-side (analysis Option C rejected).

## Pitfalls

Numbered consistently with the analysis document.

- P2: preview generation requires pixel decode, breaking the "Provider
  never inflates IDAT" invariant (PngMetadataStrip.java:24-25, M1-801 P5).
  The decode input is endpoint-chosen bytes (security.md §Trust boundaries
  item 9). The ONLY safe ordering is strip first, decode the stripped
  bytes, decoded raster bounded by the same `maxOutputPixels` the strip
  validated (commands.md §Content: "image decode is pixel-bounded").
- P3: the preview's form and byte limit come from M1-841's
  06-messaging.md §6.2.4 record — never invented (carried from M1-800's
  never-invented-values pitfall). An over-limit preview makes the CLI
  reject the send and burns a non-refundable credit (D76).
- P4: D75 no-retention extends to the preview: memory + record only,
  never a second spool file, never logged; the OutboundAttachment
  no-retain javadoc covers the new field.
- P5: positional-record cascade — six constructor sites (census in
  acceptance item 3); compile is the census; test doubles get honest
  values.
- P10: preview failure degrades to the file form — no new `/image`
  failure mode, no failure audit outcome, no D76 refund interaction
  (commands.md §Content: graceful degradation is the contract).

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. `OutboundAttachment`: add `@Nullable String imagePreview` (§7a);
     extend the no-retention javadoc (P4). Sweep the six constructor
     sites (P5) — production caller passes the generated value (null on
     degradation); test sites pass honest values.
  2. `ImagePreviewGenerator` (new, provider/image): input is STRIPPED
     PNG bytes plus `maxOutputPixels`; downscale to the configured
     preview budget; emit the M1-841-recorded form; enforce the
     M1-841-recorded byte limit — over-limit after downscale returns
     null, never an over-limit emit (P3); decode/encode failures return
     null (P10). Tests first (RED).
  3. ImageCommandHandler wiring: after `PngMetadataStrip.strip`
     (:322-335), before the spool write (:337-347); failure is
     content-free-loggable at most (structural messages only, the
     InvalidPngException precedent at :325-329) and never changes the
     audit outcome (P10).
  4. In-memory adapter records the preview field (item 4).
  5. `infochat.image.preview-*` keys + 06-messaging.md documentation
     (item 7).
- **Controls to preserve (§10):** PngMetadataStrip is untouched and
  stays FIRST on endpoint bytes; ImageSpool invariants untouched —
  preview never enters the spool (item 6); OutboundDelivery's gates,
  ladder, attribution, metrics, and finally-reclaim are untouched — the
  preview rides inside the record; IMAGE_GENERATE stays content-free
  (D75); Signal path untouched (field inert; the pin is M1-843's).
- **Pitfall→mitigation:** P2→step 2's stripped-bytes contract + items
  1-2; P3→step 2's recorded-limit enforcement + item 2; P4→step 1's
  javadoc + item 6; P5→step 1 + item 3's compile census; P10→steps 2-3
  + item 5.

## Definition of done

The reproduction test passes; the record carries the nullable preview
with all six sites swept; the generator produces the probed form within
the probed limit from post-strip bytes; hostile input degrades; the
handler degrades to file-form delivery with the success outcome; the
spool stays single-file-per-image; config keys documented; full verify
green.

## Verification

- P2 → ImagePreviewGeneratorTest.refusesOrDegradesHostileInput — feeds
  strip-failing bytes and decode-failing bytes; asserts the decoder
  never sees pre-strip input and failures return null. Non-vacuity: a
  generator decoding pre-strip bytes fails the reproduction's
  post-strip assertion.
- P3 → the reproduction's limit assertion (a generator emitting over the
  recorded limit fails it) + item 2's over-limit-degrades assertion.
- P4 → item 6's spool-listing test (a second file fails it) + item 3's
  javadoc requirement.
- P5 → `mvn -pl infochat-messaging-adapter,infochat-provider -am verify`
  (compile is the census).
- P10 → item 5's handler test (generator degrades; delivery completes;
  success outcome; null preview) + item 2's no-escaping-exception
  assertion.
- Item 4 → InMemoryAdapterTest's new recording assertion.
- Item 7 → the grep + DocumentedConfigKeyParityTest via `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: the codec branch and every SimpleX adapter-side
change (M1-843 — SimpleXMessageCodecTest is deliberately NOT in this
ticket's sweep beyond the constructor-site mechanical edit in
SimpleXAdapterAttachmentTest, which keeps its assertions intact);
Signal's wire encoding; client rendering; PngMetadataStrip/ImageSpool/
OutboundDelivery internals; spec edits. Fixture calibration: this
ticket's preview-form fixtures are calibrated to the END state M1-843
puts on the wire (analysis P6) — the preview string the generator emits
is the exact member value M1-843's codec places in msgContent, so no
M1-842 pin is broken by the sibling.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-842-simplex-inline-image-delivery-2.md
```
