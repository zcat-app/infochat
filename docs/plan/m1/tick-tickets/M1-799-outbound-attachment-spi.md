---
id: M1-799
title: "Outbound attachment SPI: payload, flags, default method"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  to-be-written: InMemoryAdapterTest.sendAttachmentRecordsThePayloadTuple —
  the intended test hands the in-memory adapter an OutboundAttachment
  (scope, file path, MIME type, display filename) and asserts the recorded
  payload; it cannot compile today because neither the record nor the SPI
  method exists (verified: MessagingAdapter.java:51-469 has no attachment
  method; OutboundMessage.java:22-26 is text-only; CapabilityFlags.java:88-97
  has no attachment flags). `start` writes the test and runs it RED before
  any fix code (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/image-generation-feature.md
blocked_by: []
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/
  - docs/design/06-messaging.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The SimpleX and Signal sendAttachment IMPLEMENTATIONS, wire encoding,
    measured size ceilings, and the XFTP completion-event work (M1-800 —
    both production adapters declare supportsOutboundAttachments=false at
    the end of this ticket; M1-800's flip is pre-authorized here).
  - The Provider-side spool, OutboundDelivery attachment path, and PNG
    strip (M1-801).
  - The /image command and any caller of the new SPI (M1-803).
  - Inbound attachments (D74: out of scope).
acceptance:
  - "InMemoryAdapterTest.sendAttachmentRecordsThePayloadTuple passes — REPRODUCTION (written and run RED at start): the in-memory fill-in records the exact (scope, file path, MIME type, display filename) tuple and reports the completion signal the SPI javadoc specifies."
  - "OutboundAttachment record exists with exactly the D74 shape — scope, file path, MIME type, display filename (a PATH, not bytes: signal-cli attaches by path and SimpleX transfer completes asynchronously past send()'s return, messaging.md:128-138) — plus a correlationId matching OutboundMessage's non-null-only contract; Verify: MessagingAdapterAttachmentSpiTest.thePayloadCarriesAPathNotBytes."
  - "CapabilityFlags gains supportsOutboundAttachments (default false semantics per messaging.md:206-211) and maxOutboundAttachmentBytes (meaningless when false, :212-216), and ALL NINE positional constructor sites are swept (analysis P1 — grep-verified census: InMemoryAdapter.java:60, SimpleXAdapter.java:79, SignalAdapter.java, StartupGatesTest, SimpleXAdminClaimTokenTest, RecordingMessagingAdapter, ProductionAdapterActivationTest, AdapterRegistryTest, ConcurrentSameScopeProgressTest); Verify: mvn -pl infochat-messaging-adapter,infochat-provider -am verify green (the compile IS the census check)."
  - "AdapterCapabilityContractTest pins the interim values (analysis P25): SimpleXAdapter and SignalAdapter declare supportsOutboundAttachments=false; InMemoryAdapter declares true with a test-scale maxOutboundAttachmentBytes — Verify: new assertions in AdapterCapabilityContractTest; M1-800 is pre-authorized to flip the two production values (its test_plan.modifies names this test)."
  - "The default sendAttachment method is never silently successful on an adapter that did not opt in (messaging.md:139-140, :209-211 — Provider never invokes it on a false-flag adapter; the default is the belt): default throws MessagingException categorized PERMANENT — Verify: MessagingAdapterAttachmentSpiTest.defaultSendAttachmentFailsPermanently (FAILURE-MODE: an adapter that declares false and never overrides must fail loudly if misinvoked, not pretend to send)."
  - "docs/design/06-messaging.md gains the attachment payload record shape and the completion contract (messaging.md:550-551 delegates both to design notes): sendAttachment blocks until the transport reports delivery completion (success or classified failure per §Failure handling), the file MUST remain readable for the whole transmit, and the adapter MUST NOT retain or copy the payload beyond delivery — Verify: `grep -n 'OutboundAttachment' docs/design/06-messaging.md` shows the new section carrying both the record shape and the completion contract."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingAdapterAttachmentSpiTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (new method)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java (new assertions)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java (positional constructor sweep, P1)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ (the six positional CapabilityFlags test sites — sweep only, no assertion changes)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
decision_refs:
  - D74
---

# M1-799: Outbound attachment SPI: payload, flags, default method

## Context

D74 / messaging.md:128-144 add the outbound half of a media surface to the
adapter SPI: one payload shape, one capability flag, one size ceiling. Today
the SPI is text-only end to end (verified: MessagingAdapter.java has no
attachment verb; CapabilityFlags has 9 positional components, none
media-related — M1-274 deleted a speculative `supportsAttachments` for
having no consumer; D74 re-adds the flag WITH its consumer). This ticket is
the SPI surface only; the production codec implementations are M1-800 and
the Provider-side delivery/spool path is M1-801. Shared analysis:
`analysis_ref:`.

## Root cause

Feature gap. The shape follows the M1-035a precedent (SPI extension +
in-memory fill-in in the same ticket) with the M1-274 lesson applied:
CapabilityFlags is positional, so the flag addition cascades to exactly nine
`new CapabilityFlags(` sites across two modules (grep-verified census in
acceptance item 3).

## Pitfalls

Numbered consistently with the analysis document.

- P1: the positional-record cascade — a missed site fails compile; a test
  double given convenient-but-dishonest values poisons every consumer test
  downstream.
- P2: the flag's default-false semantics are load-bearing — a caller with
  attachment work gates on it and NEVER invokes the method on a false-flag
  adapter (messaging.md:206-211). The SPI default method must fail loudly
  (PERMANENT), never no-op, so a misinvocation cannot masquerade as a send.
- P25: fixture calibration — this ticket pins `false` for SimpleX/Signal as
  the INTERIM state; M1-800's flip is pre-authorized here and named in its
  test_plan.modifies. InMemoryAdapter declares `true` from this ticket so
  M1-801's Provider-side path is testable before the codecs exist.
- P17/P18 (foreshadowed, owned by M1-800): the SPI javadoc must state the
  completion contract honestly — blocking until the transport's completion
  signal — without inventing the SimpleX event name or the ceiling values,
  both of which M1-800 verifies against the real transports.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. `OutboundAttachment` record (messaging package) — the D74 tuple plus
     correlationId; javadoc citing messaging.md §Required SPI surface.
  2. `MessagingAdapter.sendAttachment(OutboundAttachment)` default method —
     default throws PERMANENT MessagingException; javadoc carries the full
     completion contract (blocks until delivery completion; file readable
     for the whole transmit; adapter never retains/copies beyond delivery;
     Provider gates on the flag and refuses over-ceiling payloads before
     invoking).
  3. CapabilityFlags: add the two components (javadoc per
     messaging.md:206-216), then sweep all nine constructor sites (P1).
  4. InMemoryAdapter fill-in: declares true + test ceiling, records the
     payload tuple (the reproduction test's target).
  5. SimpleX/Signal adapters: declare false + 0 (interim, P25).
  6. AdapterCapabilityContractTest assertions; 06-messaging.md design-note
     update (P16: payload shape + completion contract land in design notes).
- **Controls to preserve (§10):** no existing SPI method, flag, or test
  assertion changes semantics; the nine-site sweep is constructor-shape
  only. The M1-274-deleted speculative flags stay deleted.
- **Pitfall→mitigation:** P1→step 3's sweep + compile-as-census; P2→step
  2's PERMANENT default + acceptance item 5; P25→step 5 + item 4.

## Definition of done

The reproduction test passes; the record, method, and flags exist with the
spec'd semantics; all nine constructor sites swept; capability pins land;
design doc updated; full verify green.

## Verification

- P1 → `mvn -pl infochat-messaging-adapter,infochat-provider -am verify` —
  any unswept site is a compile error.
- P2 → MessagingAdapterAttachmentSpiTest.defaultSendAttachmentFailsPermanently
  — FAILURE-MODE: invoke the default on a bare adapter, assert PERMANENT.
- P25 → AdapterCapabilityContractTest's new pins (false/false/true interim);
  M1-800's flip is pre-authorized in BOTH tickets' text.
- P17/P18 → no new test in this ticket (the verification is M1-800's
  acceptance items 2-3); this ticket's guard is acceptance item 6's grep —
  the design-note completion contract lands WITHOUT naming an unverified
  SimpleX event or ceiling, so M1-800 cannot silently inherit an invented
  one.
- acceptance item 1 → InMemoryAdapterTest.sendAttachmentRecordsThePayloadTuple.
- acceptance item 2 → MessagingAdapterAttachmentSpiTest.thePayloadCarriesAPathNotBytes
  (a record component of type byte[] would fail to compile the test — the
  path shape is pinned at the type level).
- Non-vacuity: removing the default-method throw fails item 5's test;
  declaring InMemoryAdapter false fails item 4.

## Out-of-scope

Named in `out_of_scope`: production codec implementations and ceilings
(M1-800), Provider-side spool/delivery/strip (M1-801), the /image caller
(M1-803), inbound attachments. The provider-test edits are the P1
constructor sweep only — no assertion changes, authorized by acceptance
item 3. No other pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-799-outbound-attachment-spi.md
```
