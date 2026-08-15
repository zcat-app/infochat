---
id: M1-843
title: "SimpleX codec: image-typed sends with inline preview"
status: done
created: 2026-08-11
last_updated: 2026-08-15
clarity_check: >-
  2026-08-15: one blocking ambiguity, resolved by the user amending
  acceptance item 4 (fixture-preview authorization): the item's
  original "populated preview per M1-842's sweep" premise was false on
  main — M1-842 deliberately swept the SimpleX fixture to null
  ("honest values" clause, review-approved), so the gap is FILLED BY
  THIS TICKET under the amended item 4's engineering-rules §8
  authorization (fixture value only; all six methods' assertions
  byte-identical); verified coherent against
  ImageCommandHandler.java:377-379 (production passes the generated
  preview). Otherwise no blocking ambiguity. Lint clean after
  restoring the gitignored analysis doc into the worktree (same
  private-artifact condition M1-842 recorded). Citations verified:
  fileComposedMessageArray SimpleXMessageCodec.java:276-286,
  encodeSendFileCommand :129-137 (mimeType accepted, never used),
  decode switch completion mapping :356-363, SimpleXAdapter
  sendAttachment :851-862 + metadata-only :864, SignalAdapter
  attach-by-path :462-465 (record consumed at
  SignalJsonRpcClient.java:480-490, scope+filePath only), spec
  messaging.md:128-148. Census re-run clean: new OutboundAttachment(
  sites all in analysis rows; encodeSendFileCommand main-code caller
  is SimpleXAdapter.java:856 only. M1-842's added tests on this seam
  traced: InMemoryAdapterTest preview recording, Provider
  generator/handler/spool tests — none touched by this diff;
  SimpleXAdapterAttachmentTest fixtures (null preview) flip to the
  image form green under the authorized populated fixture.
flow: tick
reproduction: >-
  SimpleXMessageCodecTest.encodeSendFileCommandEmitsTheImageForm
  (written at start and run RED before any branch code; log
  .scratch/tick-red-M1-843.log): compile RED first (no 6-arg
  encodeSendFileCommand existed), then, with the signature scaffolded
  and the branch absent, behavioral RED — "an image MIME with a
  preview emits the image type ==> expected: <image> but was: <file>"
  — fileComposedMessageArray (SimpleXMessageCodec.java) unconditionally
  emitted {"type":"file","text":""}; the live-observed wrong behavior
  is that `/image` output arrives in the SimpleX client as a file
  attachment, not an inline picture (.scratch/simplex-image-delivery.md,
  2026-08-11).
analysis_ref: docs/plan/m1/tick-analysis/simplex-inline-image-delivery.md
blocked_by: [M1-842]
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/
  - docs/spec/messaging.md
  - docs/design/06-messaging.md
complexity: medium
risk: medium
round_cap: 2
reviews:
  - round: 1
    date: 2026-08-15
    verdict: REWORK
    checks: "SPEC-TRUTHNESS WARN (rule-text OK, user-approval record for exact wording missing — record next round), SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "9 files, +237/-62"
    rework_items: 1
    verdict_file: .scratch/tick-review-M1-843-r1.txt
  - round: 2
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS (approval record present, wording matches), SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS; round-1 REWORK item 1 SATISFIED"
    diff_stats: "9 files, +301/-62 (fix hunks: 110 lines, one test method added)"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-843-r2.txt
security_relevant: true
migration_touch: false
out_of_scope:
  - The preview generator, the SPI record field, and the Provider wiring
    (M1-842, done predecessor — this ticket consumes
    OutboundAttachment.imagePreview).
  - Any change to the completion-contract decode switch
    (SimpleXMessageCodec.java:356-363) or to SimpleXAdapterAttachmentTest
    beyond acceptance item 4's authorized fixture-only change —
    M1-841's probe confirmed the image send's frames match the mapped
    contract; a divergence discovered at implementation is an escalation,
    not an inline fix.
  - Inbound attachment handling (D74: out of scope).
  - Recipient-client rendering guarantees (P9 — the amendment promises
    wire content only).
  - The Signal sendAttachment path (attach-by-path unchanged; only the
    non-coupling test pin lands in its test tree).
acceptance:
  - "SimpleXMessageCodecTest.encodeSendFileCommandEmitsTheImageForm passes — REPRODUCTION (written and run RED at start): an image MIME WITH a preview emits the M1-841-verified image-typed msgContent carrying the preview member (the exact member shape is taken from the 06-messaging.md §6.2.4 record, never invented — P1/P3), beside the unchanged filePath and the unchanged DM/group target selectors."
  - "AUTHORIZED MODIFICATION (engineering-rules §8): SimpleXMessageCodecTest.encodeSendFileCommandEmitsTheFileForm (:245-279) is modified so its file-form assertions cover the cases that KEEP the file form — a non-image MIME, and an image MIME with a null preview (P6 fixture discrimination: the new expected behavior is type=file + empty text for both, with the DM/group selector assertions unchanged); a test asserting type=image unconditionally would pin a representation the no-preview path must not have — Verify: the modified test passes and names both file-form cases."
  - "The branch defaults to the file form: anything that is not (image MIME AND preview present) emits byte-for-byte today's form (P6; MIME is Provider-supplied internal data — ImageCommandHandler passes the literal \"image/png\" — so the else-branch IS the design, not boundary defense, §7) — Verify: SimpleXMessageCodecTest asserts the file form for a non-image MIME with a non-null preview (a preview must never leak onto a non-image wire form)."
  - "The completion contract is unchanged (P7): SimpleXAdapter.sendAttachment passes attachment.imagePreview() through to the codec and the ack-then-awaitFileCompletion sequence is untouched — Verify: SimpleXAdapterAttachmentTest (all six methods) green; AUTHORIZED MODIFICATION (engineering-rules §8): the shared attachment() fixture (SimpleXAdapterAttachmentTest.java:242-245) passes a populated preview instead of null — the honest value, since the production caller (ImageCommandHandler.java:377-379) passes the generated preview, and a null preview would leave P4's no-retention pin vacuous for the new field; every assertion in the six methods stays byte-identical, with the expected behavior named: the wire form flips to the M1-841-verified image form carrying the preview beside the unchanged filePath, blocking/failure/no-copy semantics unchanged."
  - "The injection guard survives the signature change: encodeSendFileCommandRejectsInjectionInScopeIds (:281-294) keeps both PERMANENT assertions with the new parameter list — Verify: the test green with assertions intact."
  - "Signal non-coupling pin (§10): the preview field never reaches the signal-cli wire form — Verify: a SignalMessageCodecTest assertion that the encode output for an attachment carrying a preview is identical to today's preview-less form (SignalAdapter attaches by path, SignalAdapter.java:462-465)."
  - "SPEC AMENDMENT, rides-the-diff (M1-779-precedent shape; messaging.md:130 already promises a 'native file/image message', so this RECORDS behavior, it does not change a promise): docs/spec/messaging.md §Required SPI surface — Send attachment gains rule-text recording that a SimpleX image attachment is sent as an image message carrying a small inline preview, the full-resolution file still arriving as an XFTP file invitation the recipient's client accepts itself, non-image attachments and preview-less images remaining plain file messages — the exact wording goes to the user for approval at implementation time (engineering-rules §12); the amendment states rules only (no dates, ticket IDs, or report citations) and promises wire content, never client rendering (P9) — Verify: the diff shows the amendment and the commit message carries the user's approval."
  - "docs/design/06-messaging.md §6.2.4's wire-form record is updated so code and record agree (the M1-800 round-1 finding-1 discipline): the image form is recorded as the verified form for image sends with preview, the file form for everything else — Verify: `grep -n 'type.:.image' docs/design/06-messaging.md` shows the record."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java (new image-form and non-image-with-preview methods)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodecTest.java (non-coupling pin)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java (encodeSendFileCommandEmitsTheFileForm retargeted to the file-form cases; encodeSendFileCommandRejectsInjectionInScopeIds signature sweep — both pre-authorized by acceptance items 2 and 5)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterAttachmentTest.java (fixture-only: the shared attachment() helper passes a populated preview; all six methods' assertions byte-identical — pre-authorized by acceptance item 4)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D74
  - D75
---

# M1-843: SimpleX codec: image-typed sends with inline preview

## Context

M1-841 verified (or refused) the image wire form; M1-842 put the preview
on the SPI payload and generates it Provider-side. This ticket is the
flip: the SimpleX attachment encoder emits the image-typed msgContent
with the inline preview for image MIMEs, ending the live-observed
"`/image` arrives as a file attachment" behavior
(`.scratch/simplex-image-delivery.md`, 2026-08-11). It is the only
ticket in the family that changes what goes on the wire. Shared analysis:
`analysis_ref:`.

## Root cause

`fileComposedMessageArray` (SimpleXMessageCodec.java:276-286) emits
`{"type":"file","text":""}` unconditionally; SimpleX clients render
`type=file` as a file regardless of content. `encodeSendFileCommand`
(:129-137) already accepts `mimeType` but never uses it — the branch key
exists, the branch does not.

## Pitfalls

Numbered consistently with the analysis document.

- P1/P3: the emitted form is copied from M1-841's verified record —
  never reconstructed from memory (M1-800 acceptance item 1's
  discipline).
- P6: the pre-existing file-form test pins exactly what this ticket
  changes. §8 authorization is in acceptance items 2 and 5; the M1-785
  lesson: the retargeted test must discriminate (file form for
  non-image AND for image-without-preview), and the new image-form test
  must catch the mutations of THIS diff (dropping the MIME branch fails
  it; dropping the preview member fails it).
- P7: the completion contract was re-verified for image sends by
  M1-841's probe; this ticket changes NO decode or await logic.
  SimpleXAdapterAttachmentTest green with its six methods' assertions
  byte-identical is the pin (the item-4 §8 authorization covers the
  fixture value only); a divergence discovered here is an escalation,
  not an inline fix (§2/§6).
- P9: the spec amendment promises what the bot controls — the wire
  content. Recipient rendering (client preferences, auto-download) is
  recipient-side; the current spec text's honesty (messaging.md:131-134)
  is the model.
- P4: the adapter still never retains or copies the payload — now
  including the preview field — beyond delivery (messaging.md §Required
  SPI surface; OutboundAttachment javadoc as extended by M1-842).

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. RED: write the image-form test and retarget the file-form test per
     acceptance items 1-2; run RED.
  2. `fileComposedMessageArray` gains the image branch keyed on
     (image MIME AND preview present); `encodeSendFileCommand` gains the
     `@Nullable String imagePreview` parameter (§7a). The file-form
     output for the else-branch is byte-for-byte today's.
  3. `SimpleXAdapter.sendAttachment` (:851-862) passes
     `attachment.imagePreview()` through; nothing else in the method
     changes (readability guard, rate token, ack, awaitFileCompletion
     untouched).
  4. Signal non-coupling pin (item 6).
  5. Spec amendment (item 7 — user approves wording before it lands,
     §12) + 06-messaging.md record update (item 8).
- **Controls to preserve (§10):** the text send path, the inbound decode
  switch (:306-375 including the file-completion mapping :356-363), the
  injection guard, the rate limiter, the completion wait, and the Signal
  wire form are all untouched; the two modified tests keep every
  assertion that does not name the flipped behavior.
- **Pitfall→mitigation:** P1/P3→step 2 sourcing the form from the
  §6.2.4 record + item 8; P6→items 1-3's discriminating tests + the
  §8 authorization; P7→item 4's byte-identical-assertions pin +
  escalation clause; P9→item 7's wording discipline; P4→item 4's
  no-copy test exercised with a populated preview.

## Definition of done

The image-form test passes; the retargeted file-form test pins both
file-form cases; the injection guard survives; SimpleXAdapterAttachmentTest
is green with its assertions byte-identical on the populated-preview
fixture; the Signal pin proves non-coupling; the user-approved spec
amendment and the design record land; full verify green.

## Verification

- P1/P3 → acceptance items 1 and 8 (form matches the recorded probe;
  record and code agree).
- P6 → items 1-3: each test names its mutation — no MIME branch fails
  item 1; no preview member fails item 1; image form for a non-image
  MIME fails item 3; image form for a null preview fails item 2.
- Failure-mode (P6, item 3, negative test): a non-image MIME carrying a
  non-null preview must not emit the image form — the preview never
  leaks onto a non-image wire form, and a branch keyed on
  preview-presence alone fails the item-3 assertion.
- P7 → item 4: any change to the completion mapping reds or dirties
  SimpleXAdapterAttachmentTest, whose assertions this ticket is
  forbidden to touch (the §8 authorization covers the fixture value
  only).
- P9 → item 7's approval gate; SPEC-TRUTHNESS reviews the wording.
- P4 → item 4's adapterRetainsNoCopyOfThePayload with a populated
  preview field.
- Item 5 → the injection-guard test with the new signature.
- Item 6 → SignalMessageCodecTest's identical-output assertion.
- Item 9 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: the generator and SPI field (M1-842), the
completion decode switch and SimpleXAdapterAttachmentTest's assertions
(P7 pin), inbound attachments, client rendering guarantees, the Signal
send path. Three pre-existing tests are modified — all pre-authorized in
acceptance items 2, 4, and 5 with their new expected behavior named
there; no other pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-843-simplex-inline-image-delivery-3.md
```

## Round 1 rework

From `.scratch/tick-review-M1-843-r1.txt` (REWORK ITEMS, verbatim):

1. Finding 1: add SimpleXAdapterAttachmentTest.sendAttachmentPutsTheImageFormOnTheWire
   asserting the fake-transport-captured outbound envelope carries
   `"type":"image"` and the fixture preview `data:image/png;base64,aGVsbG8=`
   beside the unchanged filePath — the end-of-path assertion for the preview
   the adapter passes through at SimpleXAdapter.java:859 — evaluated via the
   new test passing, the pass-`null` mutation at SimpleXAdapter.java:859
   failing it, and the six pinned methods' assertions staying byte-identical.
