---
id: M1-800
title: "SimpleX + Signal sendAttachment codecs and ceilings"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  to-be-written: SimpleXMessageCodecTest.encodeSendFileCommandEmitsTheFileForm —
  the intended test feeds the codec (corrId, scope, path, MIME, filename) and
  asserts the emitted simplex-chat wire command is the file-send form carrying
  the path; it cannot compile today because SimpleXMessageCodec is text-only
  (verified: encodeSendCommand at SimpleXMessageCodec.java:117-124 emits only
  `/_send <target> json <text>`; the whole impl/simplex tree has no file-send
  encoder — grep for file/XFTP returns only subprocess data-dir code).
  SignalMessageCodec is likewise text-only (encodeSend/encodeGroupSend at
  SignalMessageCodec.java:76-98 emit {account, recipient|groupId, message}).
  `start` writes the test and runs it RED before any fix code (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/image-generation-feature.md
blocked_by: [M1-799]
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/
  - docs/design/06-messaging.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The SPI declaration itself (payload record, default method, flag
    components — M1-799, done predecessor).
  - The Provider-side spool, OutboundDelivery attachment path, and PNG
    strip (M1-801).
  - The /image command (M1-803).
  - Inbound attachment decode (D74: inbound stays out of scope; the
    codecs' inbound non-text drop behavior is untouched).
  - Chunking attachment bytes as text (SimpleXOutboundChunker is text-only
    and stays that way — attachments bypass it entirely).
acceptance:
  - "SimpleXMessageCodecTest.encodeSendFileCommandEmitsTheFileForm passes — REPRODUCTION (written and run RED at start): the emitted wire command is the simplex-chat file-send form carrying the file path, verified against the bundled simplex-chat version's actual command surface (the exact command syntax is an ASSUMPTION to verify at implementation against the shipped binary — analysis D-4 — never invented from memory)."
  - "SHIP-BLOCKER (SimpleX XFTP file lifetime, design §Open items): the completion signal SimpleX sendAttachment awaits is determined from the live transport (which event marks the local file safe to release — e.g. an upload-complete / file-sent event) and the implementation blocks on exactly that signal — probe: the verified event is recorded in docs/design/06-messaging.md (`grep -n -i 'xftp\|completion' docs/design/06-messaging.md` shows it); if NO such event exists, escalate rather than guess (analysis P17): the age sweeper is the backstop, never the primary."
  - "SHIP-BLOCKER (SimpleX/Signal attachment size ceilings, future-features §B2 'Unverified'): both transports' single-attachment ceilings are measured/verified against the real transports and recorded in docs/design/06-messaging.md — probe: `grep -n 'maxOutboundAttachmentBytes' docs/design/06-messaging.md` shows the recorded values with their measurement method; the flags carry the verified values, not invented ones (analysis P18)."
  - "Signal sendAttachment attaches by file path through signal-cli JSON-RPC (the file stays visible to the adapter subprocess for the whole send, including OutboundDelivery retries — analysis P18) — Verify: SignalMessageCodecTest.encodeSendWithAttachmentCarriesThePath."
  - "Both production adapters flip supportsOutboundAttachments to true with the verified ceilings, updating the M1-799-pinned AdapterCapabilityContractTest assertions (pre-authorized: M1-799 acceptance item 4; test_plan.modifies below) — Verify: AdapterCapabilityContractTest passes with the flipped values."
  - "FAILURE-MODE: an attachment whose path is unreadable at send time fails as a classified MessagingException (never an uncaught IOException escaping the SPI, never a silent skip) — Verify: SimpleXMessageCodecTest / SignalAdapter-level test feeding a nonexistent path and asserting the failure category."
  - "The adapter retains nothing: after sendAttachment returns, no copy of the payload exists on the adapter side (messaging.md:137-138) — Verify: a test asserting the adapter created no copy (e.g. data-dir diff before/after, or direct code-path assertion that no copy call exists)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java (new methods)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodecTest.java (new methods)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java (the M1-799 interim false pins flip to true with verified ceilings — pre-authorized by M1-799 acceptance item 4)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D74
---

# M1-800: SimpleX + Signal sendAttachment codecs and ceilings

## Context

M1-799 lands the attachment SPI with both production adapters declaring
`supportsOutboundAttachments=false`. This ticket makes the declaration true:
the SimpleX and Signal `sendAttachment` implementations, their wire
encoding, the verified size ceilings, and the SimpleX completion signal the
SPI's blocking contract waits on. Both underlying protocols carry files;
the gap is entirely ours (design: "the adapter SPI is the real work").
Shared analysis: `analysis_ref:`.

## Root cause

Feature gap, localized and verified: SimpleXMessageCodec.java:117-124 emits
only text `/_send` forms; SignalMessageCodec.java:76-98 emits only
`{account, recipient|groupId, message}`; SimpleXAdapter.send (:797-808)
routes bodies through the text-only SimpleXOutboundChunker. No file-send
path exists in either adapter (grep-verified).

## Pitfalls

Numbered consistently with the analysis document.

- P17: XFTP file lifetime — SimpleX file transfer completes asynchronously
  past `send()`'s return (messaging.md:131-134). The completion event is
  UNVERIFIED (analysis D-4); verify it against the bundled simplex-chat
  binary, and if the transport exposes no completion event, escalate — a
  guessed signal that fires early deletes the file mid-upload.
- P18: ceilings are measured, never invented — they bound `/image
  --resolution` however the pixels are produced (commands.md:596-599).
  signal-cli attaches by path, so the spool file must remain visible to the
  adapter subprocess for the whole send including retries.
- P2 interplay: the flag flips to true ONLY with a verified ceiling — a
  true flag with an invented ceiling would let Provider admit payloads the
  transport then rejects, burning GPU work (D76: no refund once the GPU
  ran, including over-limit attachments).
- P23 interplay: failures must arrive as classified MessagingException
  (TRANSIENT/PERMANENT per §Failure handling) so M1-801's OutboundDelivery
  ladder works unchanged; a raw IOException escaping the SPI breaks the
  chokepoint's classification.
- P25: the flip modifies the M1-799 interim capability pins — pre-authorized
  in both tickets; no other assertion in that test changes.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Verify against the bundled binaries: the simplex-chat file-send command
     syntax and its completion event (P17), the signal-cli attach-by-path
     parameter, and both size ceilings (P18). Record all three in
     docs/design/06-messaging.md (messaging.md:552-553 delegates wire
     encoding and ceilings to design notes).
  2. SimpleX: codec file-send encoder + adapter sendAttachment that awaits
     the verified completion signal, then returns; classified failure on
     transport errors.
  3. Signal: codec encode-with-attachment + adapter sendAttachment
     (path-based; completion = the JSON-RPC send response).
  4. Flip both CapabilityFlags declarations with the verified ceilings;
     update the pre-authorized capability-test pins.
  5. Failure-mode tests (acceptance items 6-7).
- **Controls to preserve (§10):** the text send paths are untouched —
  encodeSendCommand/encodeSend keep their exact output; the inbound
  non-text drop stays as-is (D74); SimpleXOutboundChunker remains text-only.
- **Pitfall→mitigation:** P17→step 1 + item 2 (escalate, don't guess);
  P18→step 1's recorded measurements; P2→step 4 (flip only with the
  verified ceiling) + item 3's probe; P23→step 2/3's classified failures +
  item 6; P25→step 4's pinned flip.

## Definition of done

Both codecs emit the verified file-send wire forms; both adapters implement
sendAttachment with the blocking completion contract; ceilings measured and
recorded; flags flipped with the pre-authorized test update; failure-mode
tests green; full verify green.

## Verification

- P17 → acceptance item 2's recorded-event grep (and its escalation clause
  if no event exists).
- P18 → acceptance item 3's measurement-record grep; the recorded value and
  method land in 06-messaging.md.
- P2 → acceptance items 3 and 5 read together: AdapterCapabilityContractTest
  passes only with the flipped true values AND item 3's grep requires the
  recorded measurement behind them — a true flag whose ceiling was never
  measured fails item 3.
- P23 → item 6's nonexistent-path failure-mode test asserting the failure
  CATEGORY (a TRANSIENT misclassification would retry a permanently-missing
  file through the whole ladder).
- P25 → AdapterCapabilityContractTest green with flipped values only.
- acceptance items 1/4 → the named codec tests.
- Non-vacuity: emitting the text form for a file payload fails item 1;
  returning before the completion signal fails the item-2 contract review;
  an unclassified IOException fails item 6.

## Out-of-scope

Named in `out_of_scope`: the SPI declaration (M1-799), Provider-side
spool/delivery/strip (M1-801), the /image caller (M1-803), inbound
attachments, attachment chunking. One pre-existing test is modified —
AdapterCapabilityContractTest's two interim flag pins flip to the verified
values, pre-authorized by M1-799 acceptance item 4. No other pre-existing
test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-800-adapter-attachment-codecs.md
```
