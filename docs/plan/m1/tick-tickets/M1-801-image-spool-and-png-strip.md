---
id: M1-801
title: "tmpfs spool, age sweeper, PNG strip, delivery path"
status: done
created: 2026-08-08
last_updated: 2026-08-08
clarity_check: start self-check passed 2026-08-08 — lint 0 BLOCKERs (1 WARN: no §Census section, notify-and-continue); blocked_by M1-799 done; citations spot-checked clean; analysis pitfalls P2/P3/P5/P16/P23 all landed; M1-799 seam tests (MessagingAdapterAttachmentSpiTest, InMemoryAdapterTest, AdapterCapabilityContractTest) live in infochat-messaging-adapter, no overlap with files_scope
flow: tick
reproduction: >-
  PngMetadataStripTest.stripsPromptCarryingTextChunks — builds a PNG whose
  tEXt/iTXt chunks carry a canary prompt string (the shape ComfyUI embeds:
  the whole workflow graph), runs the strip, and asserts the output bytes
  contain no prompt substring while the surviving chunks keep valid CRCs.
  Ran RED at start 2026-08-08 (no binary/EXIF sanitizer existed anywhere —
  grep-verified: no PNG handling under infochat-provider/src/main);
  now green on the implementation.
analysis_ref: docs/plan/m1/tick-analysis/image-generation-feature.md
blocked_by: [M1-799]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/image/
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDelivery.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/
  - docs/design/06-messaging.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The SPI declaration and the production codec implementations
    (M1-799/M1-800 — this ticket's delivery path is tested against the
    in-memory adapter fill-in).
  - The ComfyUI client that fetches the bytes (M1-802) and the /image
    command that assembles the flow (M1-803).
  - Any change to the text-only `](`-neutralization in OutboundDelivery
    (attachment sends never carry text bodies through it).
  - Client-side PNG rendering hardening (redteam OUT-OF-MODEL).
acceptance:
  - "PngMetadataStripTest.stripsPromptCarryingTextChunks passes — REPRODUCTION (written and run RED at start): tEXt/zTXt/iTXt chunks are dropped at chunk level, surviving chunks are copied verbatim so their CRCs stay valid, and the output contains no prompt substring (D75: 'The workflow metadata the backend embeds in PNG text chunks is stripped before delivery')."
  - "PngMetadataStripTest.refusesOversizedDimensions passes — FAILURE-MODE (analysis P5, commands.md:635-636): an IHDR whose width×height exceeds the profile-driven pixel bound is rejected BEFORE any strip output is produced; the Provider never inflates IDAT, so the bound check reads IHDR only."
  - "PngMetadataStripTest.refusesTruncatedOrNonPngInput passes — FAILURE-MODE: endpoint-chosen bytes (security.md §Trust boundaries item 9) that are not a well-formed PNG are rejected, never passed through."
  - "The spool is tmpfs-resident with the spec'd capacity bound (commands.md:634-636): ImageSpoolTest.refusesWritesPastTheCapacityBound passes — FAILURE-MODE: an over-capacity write is refused cleanly (no partial file left), because tmpfs exhaustion is host memory exhaustion (redteam finding 6)."
  - "ImageSpoolTest.sweeperEvictsAgedFilesAndKeepsFreshOnes passes: the age-based sweeper (sibling of ChatMemoryPruner) runs on the injected app-wide Clock (engineering-rules §9 — eviction is decision-time logic; QuarkusMock-installable in tests), and delete-on-completion alone is never the only reclaim path (a Provider crash between fetch and send must not leak — analysis P3)."
  - "OutboundDelivery gains the attachment path preserving every chokepoint obligation (§10, analysis P23): the same TRANSIENT full-jitter retry ladder and PERMANENT abort, per-group permanent-failure attribution into the BOT_REMOVED counter, and adapterMetrics emission — Verify: OutboundDeliveryAttachmentTest.transientFailureRetriesThenSucceeds and .permanentGroupFailureFeedsTheBotRemovedCounter."
  - "Provider gates before invoking (messaging.md:139-140): OutboundDeliveryAttachmentTest.neverInvokesAFalseFlagAdapter passes — FAILURE-MODE: an adapter declaring supportsOutboundAttachments=false is never invoked; and .refusesOverCeilingPayloadsBeforeInvoking passes — an over-maxOutboundAttachmentBytes payload is refused before the SPI call (analysis P2)."
  - "Delete-on-completion: after the adapter reports delivery completion the spool file is reclaimed (messaging.md:134-138) — Verify: OutboundDeliveryAttachmentTest.successfulDeliveryReclaimsTheSpoolFile."
  - "New infochat.image.spool-* config keys are documented in docs/design/06-messaging.md (the spool lifecycle's design-note home, messaging.md:550-551) — Verify: `grep -n 'infochat.image.spool' docs/design/06-messaging.md` lists every new key, and DocumentedConfigKeyParityTest passes via `mvn verify` (it gates the documented-vs-real key sets; scripts/lint-config-keys.py was checked and does NOT gate here — it covers required-key base declarations and excludes infochat-provider by default; these keys carry declared defaults, so the parity test plus the grep is the correct gate)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/image/PngMetadataStripTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ImageSpoolTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryAttachmentTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Failure handling
  - docs/spec/commands.md §Content
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D74
  - D75
  - D76
reviews:
  - round: 2
    date: 2026-08-08
    verdict: APPROVE
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: PASS
      MAINTAINABILITY-CHECK: WARN
      SCOPE-CHECK: PASS
    diff_stats: "round-2 fix hunks: 7 files changed, 105 insertions(+), 8 deletions(-); both round-1 REWORK items SATISFIED"
---

# M1-801: tmpfs spool, age sweeper, PNG strip, delivery path

## Context

D74's payload is a file *path*, so Provider owns the file's lifecycle: a
tmpfs spool (never persistent storage — the D75 privacy posture), reclaimed
on adapter-reported completion, guaranteed by an age sweeper; and D75
requires the PNG's embedded workflow metadata (which contains the prompt)
stripped before delivery. messaging.md:128-144 additionally requires the
Provider-side gate: never invoke a false-flag adapter, refuse over-ceiling
payloads pre-invocation, same retry ladder as text. None of this exists
(grep-verified: no image handling under infochat-provider). Shared analysis:
`analysis_ref:`.

## Root cause

Feature gap. The design (:91-110) settles why a spool-and-sweeper, not a
delete step: two files exist (ComfyUI's output + Provider's copy), signal-cli
needs the path alive for the whole send, and SimpleX transfer is async past
`send()`'s return — so delete-on-completion is the happy path and the sweeper
is the crash guarantee.

## Pitfalls

Numbered consistently with the analysis document.

- P3: crash-leak and tmpfs capacity — the sweeper is the invariant-holder;
  it reads time for a decision, so it takes the injected Clock (§9, the
  M1-444 pattern); over-capacity writes refuse, never grow (RAM-backed).
- P5: the strip is chunk-level — drop tEXt/zTXt/iTXt wholesale, copy
  surviving chunks verbatim (CRCs travel with the chunk); the pixel bound is
  an IHDR dimension check; the Provider must NEVER inflate IDAT (a
  decompression bomb would then be our memory bug).
- P2: flag gate and size refusal happen BEFORE the SPI invocation — and
  M1-803 relies on the flag being checkable statically, pre-charge.
- P23: the OutboundDelivery attachment entry preserves the chokepoint's
  incidental obligations: ladder, group BOT_REMOVED attribution, metrics,
  D64 at-least-once acceptance (an ambiguous attachment transmit may
  duplicate exactly as text may — messaging.md:141-144; no correlationId
  dedup, OutboundMessage.java:14-20).
- P16: new `infochat.image.spool-*` keys are documented in 06-messaging.md
  or DocumentedConfigKeyParityTest reds the build.

## Approach

- **Files to touch:** `files_scope` (new provider/image package +
  OutboundDelivery + tests + design doc).
- **Steps, in order:**
  1. `PngMetadataStrip` — signature check, IHDR dimension bound, chunk-walk
     dropping text chunks, verbatim copy otherwise. Tests first (RED).
  2. `ImageSpool` — tmpfs dir config, capacity-bound write, completion
     delete, age query for the sweeper.
  3. `ImageSpoolSweeper` — @Scheduled sibling of ChatMemoryPruner, injected
     Clock, evicts past the age bound (which exceeds M1-797's container-side
     janitor window only in that BOTH bound the same privacy invariant —
     they are independent layers; do not couple the values).
  4. `OutboundDelivery.deliverAttachment` — flag gate, size refusal, the
     existing ladder via `execute(...)`, group attribution, metrics,
     completion-driven spool reclaim.
  5. Design-doc update; full verify.
- **Controls to preserve (§10):** every text-path behavior of
  OutboundDelivery is untouched — the attachment path ADDS an entry point
  and reuses `execute`; `neutralizeLinkSyntax` stays text-only; the
  `deliverLlmReply` empty-body guard (M1-794) is unaffected.
- **Pitfall→mitigation:** P3→steps 2-3 + items 4-5; P5→step 1 + items 1-3;
  P2→step 4 + item 7; P23→step 4 + item 6; P16→step 5 + item 9.

## Definition of done

Strip passes its three tests (prompt-carrying chunks gone, CRCs valid,
oversize/truncated refused); spool refuses over-capacity and sweeps by age
on the injected Clock; the delivery path gates, refuses, retries, attributes,
meters, and reclaims — each pinned by its named test; design doc updated;
full verify green.

## Verification

- P3 → ImageSpoolTest.sweeperEvictsAgedFilesAndKeepsFreshOnes (fixed-Clock)
  and .refusesWritesPastTheCapacityBound (failure-mode).
- P5 → the three PngMetadataStripTest methods; the canary-prompt test is the
  discriminating assertion (a strip that rewrites but keeps one text-chunk
  kind fails it).
- P2/P23 → the four OutboundDeliveryAttachmentTest methods named in
  acceptance.
- P16 → acceptance item 9's grep + DocumentedConfigKeyParityTest via
  `mvn verify`.
- Non-vacuity: a strip that copies tEXt verbatim fails the reproduction;
  a sweeper on `Instant.now()` fails the fixed-Clock test; a size check
  after the SPI call fails .refusesOverCeilingPayloadsBeforeInvoking's
  never-invoked assertion.

## Out-of-scope

Named in `out_of_scope`: the SPI and codecs (M1-799/800), the ComfyUI fetch
(M1-802), the /image assembly (M1-803), text-neutralizer changes,
client-side rendering. No pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-801-image-spool-and-png-strip.md
```

## Round 1 rework

1. Finding 1: replace the overflowing bounds comparison at
   PngMetadataStrip.java:48 with overflow-free arithmetic
   (`length > png.length - offset - 12`), verified via the new
   `PngMetadataStripTest.refusesChunkDeclaringLengthNearIntMax`
   asserting InvalidPngException for a chunk declaring 0x7FFFFFFF.
2. Finding 2: make ImageSpool.write's capacity check and write atomic
   (`synchronized` at ImageSpool.java:35), verified via the new
   `ImageSpoolTest.concurrentWritesNeverExceedTheCapacityBound` plus
   `ImageSpoolTest.refusesWritesPastTheCapacityBound` staying green on
   `mvn verify`.
