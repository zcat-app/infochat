---
id: M1-839
title: "Re-verify SimpleX text/group wire forms on CLI v7.0.0"
status: pending
created: 2026-08-14
last_updated: 2026-08-14
flow: tick
reproduction: >-
  to-be-written: SimpleXMessageCodecV7WireTest.v7CapturedDirectDmDecodesToInbound
  (infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/)
  — feeds the codec a direct-DM `newChatItems` frame freshly captured from the
  bundled v7.0.0 binary and asserts it decodes to Inbound with sender id,
  body, and `meta.itemId` intact; the test cannot exist today because no
  v7.0.0-captured frame exists in the tree (every SimpleXMessageCodecTest
  fixture was captured from v6.5.4.1, M1-510), and the capture requires the
  M1-838-upgraded binary. The codec's behavior on v7.0.0 frames is currently
  UNVERIFIED — a green suite proves only that 2026-06 v6.5.4.1 captures still
  parse (analysis P5). `start` converts the marker — capture the frame, write
  the test, run it RED against any proven drift — before any fix code
  (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/simplex-cli-v7-upgrade.md
blocked_by: [M1-838]
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/
  - infochat-provider/src/test/java/app/zcat/infochat/messaging/impl/simplex/
  - infochat-provider/src/test/java/app/zcat/infochat/provider/live/
  - docs/design/06-messaging.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The attachment/XFTP surface, the size ceiling, and design §6.2.4
    (M1-840).
  - The version pin, image build, launch flags, and provisioning script
    (M1-838, done predecessor).
  - SimpleX inline image composed-message types (batch H).
  - Any docs/spec/** or decisions-register edit (analysis P10 — D51/D52-
    shaped drift escalates).
acceptance:
  - "SimpleXMessageCodecV7WireTest.v7CapturedDirectDmDecodesToInbound passes — REPRODUCTION (written from a fresh v7.0.0 capture and run RED at start iff the current codec mis-decodes it): the v7.0.0 direct-DM plural newChatItems frame decodes to Inbound via chatInfo.type / contact.localDisplayName / chatItem.meta.itemId (design 06-messaging.md:665-691)."
  - "Every depended inbound/outbound TEXT-and-GROUP surface is re-captured from the v7.0.0 binary and each lands as a committed fixture test: group message with mentions{} envelope (D51 anchor: groupInfo.membership.memberId), send-ack newChatItems with corrId, receivedGroupInvitation + the /_join response pair (D52), userContactLink (/show_address), and a chatItemUpdated live-edit finalize — Verify: `grep -c 'v7.0.0-captured' infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecV7WireTest.java` returns one hit per enumerated surface and `mvn -pl infochat-messaging-adapter test -Dtest=SimpleXMessageCodecV7WireTest` passes; an 'unchanged' disposition cites the v7.0.0 fixture, never the old v6.5.4.1 capture (analysis P5)."
  - "FAILURE-MODE: a v7.0.0-captured chatCmdError frame still classifies fail-closed PERMANENT and the free-form error message is never surfaced (design :686-691, security.md §User content in exceptions) — Verify: a test feeding the captured error frame and asserting the category and the non-leak."
  - "Live re-run against the upgraded deployment: LiveSimpleXRoundTripIT plus the LiveSimpleXScenarioSuiteIT transport scenarios (s03/s04/s07/s10/s11/s12/s15 as host fixtures allow) pass with `-Dinfochat.live.simplex=true` against the M1-838-refreshed binary — Verify: the run log (scenario ids + latencies) is attached to the ticket record; skipped-on-CI stays the gate's shape (no suite change to force them into mvn verify)."
  - "If and only if a v7.0.0 capture proves drift: the codec adaptation lands with its own failing-first test per drifted surface, and the pre-existing v6.5.4.1 fixture tests it replaces are modified ONLY as enumerated in this ticket's Out-of-scope section (§8 authorization) with assertions at equal strength — Verify: the named tests pass on the new fixtures."
  - "docs/design/06-messaging.md's 'live v6.5.4.1' text/group sections (§6.2 send content shape :795-811, §6.4.4 field locations :665-691, invitation flow :694-729) are re-anchored to the v7.0.0 evidence — Verify: `grep -n 'v6.5.4' docs/design/06-messaging.md` shows no stale current-truth claim in those sections (historical attributions in tickets/HANDOFF stay untouched, analysis P11)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecV7WireTest.java (one v7.0.0-captured fixture per re-verified surface)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java (ONLY on proven drift: the drifted v6.5.4.1 fixture methods are replaced by v7.0.0 captures asserting the same decode contract — pre-authorized in Out-of-scope)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAddressQueryTest.java (same conditional rule)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXEditFallbackTest.java (same conditional rule)
    - infochat-provider/src/test/java/app/zcat/infochat/messaging/impl/simplex/LiveSimpleXHarnessFrameTest.java (same conditional rule)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D51
  - D52
  - D74
---

# M1-839: Re-verify SimpleX text/group wire forms on CLI v7.0.0

## Context

M1-838 lands the bundled v7.0.0 binary. The adapter's text, edit, group,
mention, invitation, and address surfaces were all pinned empirically to
v6.5.4.1 frames (M1-508/510/511/515; design 06-messaging.md:665-729,
795-824). A green suite after the bump is not evidence (analysis P5): the
fixtures are v6.5.4.1 captures. This ticket re-establishes each surface on
v7.0.0 and adapts the codec only where a fresh capture proves drift.
Shared analysis: `analysis_ref:`.

## Root cause

Evidence-base invalidation, not a known defect: the codec's decode contract
(design :665-691 field locations) is live-verified truth about v6.5.4.1 and
unchecked truth about v7.0.0. Proven: the depended field paths and the
fixture provenance. Unknown until capture: whether v7.0.0 moved any of
them — the M1-838 changelog/tag-diff record narrows where to look first.

## Pitfalls

Numbered consistently with the analysis document.

- P5: concluding "unchanged" from old fixtures — the M1-508 failure shape
  (hand-rolled fixtures dropped 100% of real inbound). Every disposition
  cites a v7.0.0 capture.
- P9: the M1-838 tag-diff record directs the probe order; release-note
  silence is not evidence of no-change for the JSON frame shapes.
- P10: `mentions{}`/`memberId` or `receivedGroupInvitation` mechanism drift
  breaks what D51/D52 promise — that path is escalation, never a codec
  rider; docs/spec/** and the decisions register are not edited here.
- P11: version references in touched files (codec javadoc "v6.5.4.1",
  test-class provenance comments) are re-read as claims about the NEW
  evidence; historical tickets/HANDOFF/STATUS references stay untouched
  (§1 surgical bound).

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Read M1-838's surface-review record; order the probes
     notes-flagged-surfaces first (P9).
  2. Capture v7.0.0 frames via a throwaway loopback probe (design :667-669
     pattern) or the LiveSimpleXClient harness against the M1-838-refreshed
     binary: direct DM, group message with a bot mention, send ack, group
     invitation + join pair, userContactLink, live-edit finalize (P5).
  3. Write SimpleXMessageCodecV7WireTest from the captures; run each test
     — a RED result IS the drift signal and scopes step 4.
  4. Conditional: adapt SimpleXMessageCodec per proven drift, each
     adaptation with its failing-first test; replace the drifted pre-existing
     fixtures only as pre-authorized (Out-of-scope), equal assertion
     strength (§8).
  5. Re-run the opt-in live ITs against the upgraded deployment; attach the
     run log.
  6. Re-anchor the design sections to the v7.0.0 evidence (P11).
- **Controls to preserve (§10):** decode-time fail-closed PERMANENT on
  unknown error tags and the never-surface-free-form-message rule (design
  :686-691); queue-address validation at encode (SimpleXMessageCodec.java
  :224-237); the D51 byte-equality mention anchor and the D52
  registered-inviter gate semantics (adapter surfaces, Provider decides);
  D37 — captures committed as fixtures carry canary/probe content, never
  real user traffic.
- **Pitfall→mitigation:** P5→steps 2-3 + items 1-2; P9→step 1; P10→step 4's
  escalation clause + out_of_scope; P11→step 6 + item 6.

## Definition of done

Every text/group/edit/address surface carries a v7.0.0-captured fixture
test (green); the error-frame failure-mode test green; the live round-trip
+ scenario re-run log attached; codec adaptations (iff drift) each with
their failing-first test; design re-anchored; no spec or register edit;
full verify green.

## Verification

- P5 → items 1-2: a disposition without a v7.0.0 fixture fails item 2's
  "cites the fixture" clause (non-vacuity: hand-rolling a fixture from
  memory reintroduces M1-508 — the capture provenance is part of the
  fixture comment).
- P9 → item 2's per-surface enumeration crossed against M1-838's record.
- P10 → item 5's conditional + the out_of_scope spec/register ban; a
  D51/D52-shaped capture escalates with the frame attached.
- P11 → item 6's grep.
- failure mode → item 3: the captured error frame fed to decode; a
  PERMANENT misclassification to TRANSIENT, or a surfaced free-form message,
  fails it.
- items 1/4/5 → the named tests / live IT run log.

## Out-of-scope

Named in `out_of_scope`: the attachment surface (M1-840), the pin/launch
work (M1-838), batch-H image types, and any spec/decisions edit.
Pre-existing tests this ticket is AUTHORIZED to modify, conditionally on a
recorded v7.0.0 capture proving drift (§8): SimpleXMessageCodecTest,
SimpleXAddressQueryTest, SimpleXEditFallbackTest, LiveSimpleXHarnessFrameTest
— in each, only the drifted fixture methods move, the replacement asserts
the same decode contract (same field paths or their v7.0.0 successors, same
failure classification), and the ticket record carries the before/after
frames. No other pre-existing test is modified.

## Census

Class: the SimpleX inbound frame types the codec's decode dispatch handles
— the set whose v7.0.0 shape this ticket re-pins (re-runnable:
`grep -n 'case "' infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java`).

| Site | Disposition |
|---|---|
| SimpleXMessageCodec.java:350 `newChatItem` | re-captured: single-item form covered by the direct-DM v7.0.0 fixture (acceptance item 1) |
| SimpleXMessageCodec.java:351 `newChatItems` | re-captured: direct DM, group + mentions{}, send-ack corrId, and live-edit finalize fixtures (items 1-2) |
| SimpleXMessageCodec.java:352 `receivedGroupInvitation` | re-captured: invitation + /_join response-pair fixture (item 2; D52-shaped drift escalates per P10) |
| SimpleXMessageCodec.java:353 `sentMessage` / `apiSendMessageResponse` | re-captured: send-ack corrId fixture (item 2) |
| SimpleXMessageCodec.java:354 `userContactLink` | re-captured: /show_address fixture (item 2) |
| SimpleXMessageCodec.java:355 `chatCmdError` / `chatItemUpdateError` | re-captured FAILURE-MODE: v7.0.0 error frame asserts fail-closed PERMANENT + free-form non-leak (item 3; also covers SimpleXEditFallbackTest's update-error path) |
| SimpleXMessageCodec.java:359-364 `sndFile*` completion/progress/error tags | out-of-scope: M1-840 owns the XFTP attachment tags |

Outbound command surfaces (/_send array form, /_update live=on|off, /_join,
/show_address) carry no dispatch tag; each is exercised by the item-2
capture batch and lands as a SimpleXMessageCodecV7WireTest fixture.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-839-simplex-cli-v7-upgrade-2.md
```
