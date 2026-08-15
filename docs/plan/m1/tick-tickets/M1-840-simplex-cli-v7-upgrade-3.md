---
id: M1-840
title: "Re-verify SimpleX XFTP attachment surface on CLI v7.0.0"
status: done
created: 2026-08-14
last_updated: 2026-08-15
clarity_check: >-
  Pre-flight clean 2026-08-15: tick-lint 0 findings (after restoring the
  gitignored analysis doc into this worktree — tick-analysis/ is untracked
  by design, absent in fresh worktrees); citations verified
  (SimpleXAdapter.java:79/:103-105/:851-862/:864-877, codec
  :129-137/:276-286/:356-364/:977-983, design 06-messaging.md:362-408,
  LiveSimpleXRoundTripIT.java:44-47); census re-run clean over
  sndFile*/attachment constants (all hits inside files_scope or untouched
  SPI consumers); analysis pitfalls P5/P6/P7/P8/P11/P12 all landed in the
  ticket; M1-838's added test (BundledSimplexCliPinTest, config seam)
  traced — untouched by this diff. Execution notes: live harness is the
  M1-841 two-identity throwaway pattern (never a second WS conn against
  prod — v6.5.4 Server.hs races one shared outputQ); M1-838 left the
  v6.5.4 + v7.0.0 source tarballs and the v7.0.0.11 binary in /tmp/opencode
  (source determination needs no network); a concurrent capture session
  for M1-839 is live on this host — disjoint scratch dirs and WS ports.
flow: tick
reproduction: >-
  LiveSimpleXFileSendIT.fileSendReleasesOnlyOnSndFileCompleteXFTP
  (infochat-provider/src/test/java/app/zcat/infochat/provider/live/, opt-in
  `-Dinfochat.live.simplex=true`): written at start and run against the
  bundled v7.0.0.11 binary — GREEN 2026-08-15 (8.5 s; first run RED on the
  harness handshake: /ad's default address settings leave auto-accept off —
  fixed harness-side with /auto_accept on, not v7 drift). Observed wire:
  ack newChatItems meta.itemId -> sndFileProgressXFTP x2 ->
  sndFileCompleteXFTP carrying the acked itemId — the adapter's blocking
  contract holds unadapted; over-ceiling (1 GiB + 1 B) and missing-file
  sends refuse PERMANENT with the fileSize / fileNotFound tags.
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
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN (comment-cap flag on LiveSimpleXFileSendIT class javadoc — informational, no trim demanded), SCOPE PASS"
    diff_stats: "5 files, +524/-56"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-840-r1.txt
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

## Evidence record (2026-08-15, implementation)

All probes on this deployment host against the M1-838-extracted binary
`prod/runtime/simplex-clients/bin/simplex-chat`; the IT records its
banner (`SimpleX Chat v7.0.0.11`) in every run log (P8).

### Step 1 — source determination (P6/P7)

- Completion path at the v7.0.0 tag (`Library/Subscriber.hs`):
  `SFDONE` handler → sends file descriptions →
  `checkSndInlineFTComplete` emits `CEvtSndFileCompleteXFTP` for
  XFTP-protocol files on the delivery confirmation
  (`CEvtSndStandaloneFileComplete` when no chat item exists) —
  semantics unchanged from the M1-800-documented v6.5.4 path.
  `CEvtSndFile*` constructors zero-diff per M1-838's tag diff.
- Ceiling: v7.0.0's `cabal.project` pins simplexmq `efaad8e7`;
  `Simplex.FileTransfer.Description` there declares
  `maxFileSize = gb 1` / `maxFileSizeHard = gb 5`. v7 NEW:
  chat-side `maxXFTPFileSize` (Badges.hs) gives badged senders
  gb 2/gb 5; badge-less senders (the bot) keep the soft gb 1.
- Refusal tags: `ChatErrorType` serializes with `dropPrefix "CE"`
  → wire tags `fileSize` / `fileNotFound`.

### Steps 2-3 — live IT vs v7.0.0.11 (P5/P6/P8)

- Reproduction RED (harness-side, not v7 drift): first run stalled at
  the handshake — `/ad`'s default address settings leave auto-accept
  OFF, so the connection request sat pending. Fixed harness-side with
  `/auto_accept on` after `/ad` (the M1-841/M1-839 two-identity
  throwaway pattern; both identities on public SMP/XFTP servers,
  never a second connection against the prod provider).
- GREEN (8.5 s): production `encodeSendFileCommand` output accepted;
  ack `newChatItems` meta.itemId → `sndFileProgressXFTP` ×2
  (65536/65536, 1/1) → `sndFileCompleteXFTP` carrying the SAME
  itemId — the adapter's release contract holds unadapted. No
  standalone/legacy/error completion observed on the ready contact.
- Refusal arms (real v7 frames through the production codec):
  sparse 1 GiB + 1 B → PERMANENT, tag `fileSize`; nonexistent path →
  PERMANENT, tag `fileNotFound`.

### Step 4 — conditional codec/fixture adaptation

NOT TRIGGERED: zero drift observed — SimpleXMessageCodec dispatch,
SimpleXAdapterAttachmentTest fixtures, and
AdapterCapabilityContractTest's 1 GiB pin are untouched (the §8
pre-authorization lapses unused).

### Step 5 — ceiling (P7)

Value unchanged (1 GiB): source `efaad8e7` `maxFileSize = gb 1` +
empirical refusal at 1 GiB + 1 B. `SimpleXAdapter.
MAX_OUTBOUND_ATTACHMENT_BYTES` and the contract pin stay as-is;
the measurement record is in design §6.2.4 next to the M1-800
record.

### Step 6 — design §6.2.4 (P11)

Ceilings paragraph and wire-form/completion paragraph re-anchored to
the v7.0.0 trail (live capture + source citation + superseded-caveat
note: the v6-era "inbound SMP broken → source-verified only" no
longer holds for this surface). Version-reference sweep confined to
touched files: the IT asserts the v7.0.0 banner; LiveSimpleXClient
carried no version references; no other file's version prose
touched.
