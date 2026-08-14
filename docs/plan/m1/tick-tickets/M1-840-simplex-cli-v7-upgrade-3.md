---
id: M1-840
title: "Re-verify SimpleX XFTP attachment surface on CLI v7.0.0"
status: pending
created: 2026-08-14
last_updated: 2026-08-14
flow: tick
reproduction: >-
  to-be-written: LiveSimpleXFileSendIT.fileSendReleasesOnlyOnSndFileCompleteXFTP
  (infochat-provider/src/test/java/app/zcat/infochat/provider/live/, opt-in
  `-Dinfochat.live.simplex=true` like LiveSimpleXRoundTripIT) — drives a real
  file send through the bundled v7.0.0 binary via LiveSimpleXClient and
  asserts the ack's meta.itemId, observed sndFileProgressXFTP frames, and
  that ONLY the verified completion event releases the send; it cannot exist
  today because the harness has no file-send drive and the v7.0.0 binary
  lands only with M1-838. The completion contract sendAttachment blocks on
  (sndFileCompleteXFTP, design 06-messaging.md:381-408) is v6.5.4.1-verified
  truth — on v7.0.0 it is UNVERIFIED (analysis P5/P6): if upstream renamed or
  reshaped the event, every attachment send hangs to the 5-minute
  FILE_COMPLETION_TIMEOUT or releases mid-upload. `start` converts the
  marker — write the IT, run it against the upgraded binary — before any fix
  code (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/simplex-cli-v7-upgrade.md
blocked_by: [M1-838]
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/live/
  - infochat-provider/src/test/java/app/zcat/infochat/messaging/impl/simplex/LiveSimpleXClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterAttachmentTest.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/
  - docs/design/06-messaging.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - Text/group/edit/mention/invitation/address wire re-verification
    (M1-839).
  - The version pin, image build, launch flags, provisioning (M1-838, done
    predecessor).
  - SimpleX INLINE IMAGE delivery and image-type composed messages (batch H
    — probes against the upgraded CLI after this lands; analysis P12).
  - The Signal attachment path and its 150 MiB ceiling (untouched by a
    simplex-chat bump).
  - Any docs/spec/** edit (the attachment SPI's promises are
    version-agnostic, messaging.md §Required SPI surface).
acceptance:
  - "LiveSimpleXFileSendIT.fileSendReleasesOnlyOnSndFileCompleteXFTP passes — REPRODUCTION (written at start, run against the M1-838-upgraded binary): a real file send through the bundled v7.0.0 binary acks with meta.itemId, emits upload progress, and the release event is the SAME event the adapter blocks on (sndFileCompleteXFTP per design §6.2.4) — if v7.0.0 emits a different completion shape, the IT's observation scopes the adaptation, and if NO completion event exists the ticket escalates rather than guesses (analysis P6, M1-800's completion-signal rule)."
  - "The composed-message filePath parser acceptance is re-verified on v7.0.0 (the /_send <target> json [{\"filePath\":…,\"msgContent\":{\"type\":\"file\",…}}] form, SimpleXMessageCodec.java:126-137,275-282): the bundled binary's command surface (`/file` help text or the v7.0.0 source tag) plus the live probe's acceptance of the emitted command — Verify: the IT's send is the codec's own encodeSendFileCommand output (no hand-written probe command — the production encoder is what must be accepted, analysis P5)."
  - "The v7.0.0 completion semantics are pinned from the source tag (v7.0.0 Subscriber.hs SFDONE / checkSndInlineFTComplete path) AND the live progress frames; the ready-contact completion frame may remain source-verified rather than live-captured exactly as on v6.5.4.1 (the probe host's inbound SMP is broken, design :406-408) — Verify: the determination, its method, and any live-vs-source caveat are recorded in docs/design/06-messaging.md §6.2.4 (`grep -n 'v7.0.0' docs/design/06-messaging.md` shows the updated wire-form paragraph)."
  - "FAILURE-MODE: the degradation and error frames still classify PERMANENT on v7.0.0-shaped frames — sndStandaloneFileComplete (contact-not-ready degradation), sndFileError, and a non-XFTP sndFileComplete on our own chat item never release the send (design :399-402) — Verify: SimpleXAdapterAttachmentTest's nonXftpFileCompletionFailsClassifiedPermanent / standaloneFileCompletionFailsClassifiedPermanent / fileTransferFailureEventFailsClassifiedPermanent, with fixtures updated to the v7.0.0 shapes iff the captures proved drift (pre-authorized conditional modification, Out-of-scope)."
  - "Ceiling re-measured, never carried over (analysis P7, M1-800's ceiling-measurement rule): the SimpleX maxOutboundAttachmentBytes value is re-extracted from the bundled v7.0.0 binary (the simplexmq maxFileSize checkSndFile path enforces) with the method recorded in design §6.2.4 next to the M1-800 record; AdapterCapabilityContractTest pins the re-measured value and SimpleXAdapter.MAX_OUTBOUND_ATTACHMENT_BYTES changes only to match it — Verify: AdapterCapabilityContractTest green with the measured value; `grep -n 'maxOutboundAttachmentBytes' docs/design/06-messaging.md` shows the v7.0.0 measurement record."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/live/LiveSimpleXFileSendIT.java (opt-in, -Dinfochat.live.simplex=true — layer 4, never forced into mvn verify)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/messaging/impl/simplex/LiveSimpleXClient.java (a sendFile/awaitFileEvent harness drive — test-harness code only, no production path)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterAttachmentTest.java (ONLY on proven frame drift: completion/error fixtures move to the v7.0.0 shapes, same PERMANENT/release assertions — pre-authorized in Out-of-scope)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java (ONLY iff the re-measured ceiling differs: the pinned value flips to the recorded measurement)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D74
---

# M1-840: Re-verify SimpleX XFTP attachment surface on CLI v7.0.0

## Context

M1-838 lands the bundled v7.0.0 binary. The attachment surface is the most
consequential v6.5.4.1-pinned estate: the `filePath` composed-message form,
the `sndFileCompleteXFTP` completion event `sendAttachment` blocks past the
ack on (SimpleXAdapter.java:852-862, FILE_COMPLETION_TIMEOUT 5 min at :105),
the PERMANENT-classified degradation/error frames, and the 1 GiB ceiling
extracted from the old binary's linked simplexmq (design
06-messaging.md:362-408). All of it is evidence about a binary we no longer
ship. Batch H (inline image delivery) builds on this surface next, so the
re-verification must be re-runnable, not a one-off spike. Shared analysis:
`analysis_ref:`.

## Root cause

Evidence-base invalidation (analysis §Root cause). Proven: the depended
contract and its v6.5.4.1 verification trail (M1-800 acceptance items 1-3;
design :381-408). Unknown until probed: whether v7.0.0 kept the
composed-message `filePath` acceptance, the completion event's tag/shape/
timing, the degradation frames, and the XFTP size ceiling.

## Pitfalls

Numbered consistently with the analysis document.

- P5: re-running fake-process unit tests proves fixture parsing, not v7.0.0
  behavior — the release event must be observed from the real binary.
- P6 (carried from M1-800's completion-signal pitfall): a guessed
  completion signal releases early (spool reclaimed mid-upload) or never
  (5-min TRANSIENT burn). Source-tag determination + live progress
  observation; escalate if no event exists.
- P7 (carried from M1-800's ceiling re-measurement pitfall): the ceiling
  flag carries the re-measured value, never the carried-over 1 GiB; the
  contract-test pin flips only with the recorded measurement.
- P8: the IT must exec the M1-838-refreshed host binary; record its
  `--version` in the run log so the evidence names its binary.
- P11: §6.2.4's "verified against the bundled simplex-chat v6.5.4.1"
  paragraphs are re-anchored to the v7.0.0 trail; historical M1-800 ticket
  text stays untouched.
- P12: image-type composed messages are batch H's probe — this ticket
  re-verifies the existing `type:"file"` form only.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Read M1-838's surface-review record for the XFTP/composed-message
     findings; then the v7.0.0 source tag for the completion path
     (Subscriber.hs SFDONE / checkSndInlineFTComplete) and the simplexmq
     maxFileSize constant (P6/P7 — source first, as on v6.5.4.1).
  2. Extend LiveSimpleXClient with a file-send drive (harness-side only)
     and write LiveSimpleXFileSendIT: send via the PRODUCTION
     encodeSendFileCommand output, capture ack/progress/completion frames
     (P5; the IT is the committed, re-runnable probe batch H will reuse).
  3. Run the IT against the upgraded binary; record the run log incl. the
     binary's `--version` (P8). A drifted/absent completion event scopes
     the codec adaptation — or escalates (P6).
  4. Conditional: adapt the codec's completion dispatch
     (SimpleXMessageCodec.java:357-363,975) and the
     SimpleXAdapterAttachmentTest fixtures per the captured shapes, each
     with a failing-first test (§8 pre-authorization in Out-of-scope).
  5. Re-measure the ceiling from the bundled v7.0.0 binary; flip
     SimpleXAdapter.MAX_OUTBOUND_ATTACHMENT_BYTES and the
     AdapterCapabilityContractTest pin only to the measured value (P7).
  6. Update design §6.2.4: ceilings paragraph and wire-form/completion
     paragraph re-anchored to the v7.0.0 trail with its method and caveats
     (P11).
- **Controls to preserve (§10):** the blocking completion contract and its
  classified-failure ladder semantics (messaging.md §Failure handling);
  the no-retention property (SimpleXAdapterAttachmentTest
  adapterRetainsNoCopyOfThePayload stays green untouched); the
  unreadable-path PERMANENT guard (SimpleXAdapter.java:865-877); the
  provider-side spool/reclaim path (M1-801 — untouched; only its completion
  signal is re-verified); the opt-in gate shape of the live ITs (layer 4 —
  the new IT is skipped, never failed, without the flag).
- **Pitfall→mitigation:** P5→steps 2-3 + items 1-2; P6→step 1 + item 3's
  escalation clause; P7→step 5 + item 5; P8→step 3's version record;
  P11→step 6 + item 3's grep; P12→out_of_scope.

## Definition of done

The live file-send IT passes against the bundled v7.0.0 binary with its run
log attached; the completion determination + caveat recorded in §6.2.4;
failure-mode classifications re-pinned on v7.0.0-shaped frames; the ceiling
re-measured, recorded, and pinned; any codec adaptation carries
failing-first tests; full verify green.

## Verification

- P5/P6 → items 1-3: the IT observes the real frames; the source-tag
  citation pins the semantics; an absent/changed completion event
  escalates. Non-vacuity: an IT that accepted ANY frame as "completion"
  fails item 1's "only the verified event releases" assertion (the ack
  alone must not release — the M1-800 contract).
- P7 → item 5: the measurement record grep + AdapterCapabilityContractTest
  green on the measured value (a carried-over value without a record fails
  the grep clause).
- P8 → the run log's `--version` line; the IT asserts the binary exists
  (LiveSimpleXRoundTripIT.java:46 pattern).
- failure mode → item 4's three named tests: non-XFTP completion,
  standalone degradation, and transfer-error frames each fed to
  sendAttachment; a PERMANENT→TRANSIENT misclassification retries a dead
  transfer through the whole ladder.
- P11/P12 → item 3's grep + the out_of_scope review check.

## Out-of-scope

Named in `out_of_scope`: text/group surfaces (M1-839), the pin/launch work
(M1-838), batch-H image types, the Signal path, and any spec edit.
Pre-existing tests this ticket is AUTHORIZED to modify, conditionally on
recorded v7.0.0 captures (§8): SimpleXAdapterAttachmentTest's
completion/error fixture strings (same release/PERMANENT assertions on the
v7.0.0 shapes) and AdapterCapabilityContractTest's ceiling pin (flips only
to the re-measured value). No other pre-existing test is modified; no
production file outside infochat-messaging-adapter/.../simplex/ changes,
and that only on proven drift.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-840-simplex-cli-v7-upgrade-3.md
```
