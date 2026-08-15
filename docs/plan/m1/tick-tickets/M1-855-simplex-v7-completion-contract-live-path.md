---
id: M1-855
title: "SimpleX v7 completion contract on the live bot path"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe-style (a diff mvn verify cannot state today — the divergence lives
  on the live provider+sidecar path no in-tree test drives): against the
  RUNNING test instance (docker project infochat-test, probe-v7 client
  registered, contact 12), send `/image <prompt>` from probe-v7 and observe
  BOTH sides. Wrong behavior, live-observed 2026-08-15 (two runs
  consistent): the recipient receives the file within seconds (XFTP
  complete; /fr 1 fetch verified a valid 1792×1344 PNG —
  /home/infochat/infochat-test/prod/runtime/test-clients/probe-v7/probe-v7-image3.log:4,
  probe-v7-frames.log:2) while the provider, ~98-114 s after the file's
  arrival (transcript file timestamps 23:32/23:36 local vs escalations
  21:33:38/21:37:54 UTC), escalates: `Outbound delivery to channel=simplex
  exhausted retry budget; escalated to permanent` wrapping
  `MessagingException > java.util.concurrent.TimeoutException`
  (OutboundDelivery.java:435-437) and the user is told IMAGE_ERROR_SEND_
  FAILED — delivery reported failed while the image arrived. Timing
  arithmetic (three 30-s ACK_TIMEOUT waits + sub-second backoff ≈ 90-95 s,
  06-messaging.md:632; three 5-min FILE_COMPLETION_TIMEOUT waits ≥ 15 min)
  indicts the ack wait — the capture this ticket takes decides. Intended
  in-tree regression (to-be-written at start from this ticket's own
  capture): SimpleXAdapterAttachmentTest.capturedV7BotPathFramesReleaseTheFileSend
  — the captured ack/completion frames fed through the production
  dispatch release sendAttachment; RED before the adaptation.
analysis_ref: docs/plan/m1/tick-analysis/image-v7-live-probe-defects.md
blocked_by: []
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterAttachmentTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecV7WireTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/live/LiveSimpleXFileSendIT.java
  - docs/design/06-messaging.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The preview-calibration defect (M1-854 — provider module; the two
    tickets are independent).
  - Any change to the timeout values (ACK_TIMEOUT 30 s,
    FILE_COMPLETION_TIMEOUT 5 min, SimpleXAdapter.java:100/:105), the retry
    ladder, or max-attempts — inflating timeouts or weakening the ladder to
    mask the divergence is a workaround (engineering-rules §2), and at-least
    -once duplicates are accepted by spec (D64): no dedup layer is added.
  - Any change to SimpleXAdapterAttachmentTest's release/PERMANENT
    assertions beyond acceptance item 5's captured-drift-conditional fixture
    flip (the M1-843 item-4 §8 pin stands).
  - The text-send path (works on the live v7 bot path — probe transcript
    shows progress and final replies), inbound decoding, the Signal adapter,
    the provisioning/launch surface (M1-838).
  - Any docs/spec/** edit — messaging.md §Required SPI surface and §Failure
    handling already promise classified completion reflecting delivery; this
    ticket restores the contract. If the capture proves v7 emits NO mappable
    ack/completion event on the bot path, that is an ESCALATION (user
    decides between spec amendment and dropping), never an inline bend.
  - Inventing a release signal or inflating timeouts to force a green
    outcome: if the capture shows the v7 bot path emits NO frame the
    adapter can map to ack or completion (no candidate resp.type, no
    itemId anywhere), the ticket STOPS and escalates with the capture as
    its deliverable — the full clause, with its grep trigger on the
    capture log, is Approach step 6; the user decides between a spec
    amendment and dropping.
acceptance:
  - "LIVE CAPTURE (analysis P6/P7/P10): the full frame sequence of a real /image send is captured on the live bot path — the probe: one /image <prompt> send from the registered probe-v7 client against the RUNNING test instance (docker project infochat-test; the same client drive that produced probe-v7-image3.log; NEVER anything named infochat-prod), captured via the recorded harness pattern (test-host HANDOFF /home/infochat/infochat-test/docs/plan/live-e2e/HANDOFF.md:1920-1930: the WsProbe corrId-command sidecar docker-cp'd into infochat-test-infochat-provider-1, issuing corrId-tagged commands against the subprocess WS :5225 — command responses arrive on any connection while async events stay on the adapter's; NEVER a second WS client against the bot's chat server, which races the shared outputQ and steals the adapter's frames — M1-841 clarity note) plus provider frame logging (Ignored frames are DEBUG-logged, SimpleXWebSocketClient.java:675) — and the capture NAMES the stage that times out (the 30-s ack wait in sendCommand vs the 5-min completion wait in awaitFileCompletion) with the exact frame shape(s) at that stage (resp.type value, corrId presence/placement, itemId placement) and the send→escalation timing — Verify: the captured frames are saved to a new capture log under /home/infochat/infochat-test/prod/runtime/test-clients/probe-v7/ (e.g. probe-v7-capture-1.log) and attached to the ticket evidence; grep -nE 'frame ignored|sndFile|newChatItems' /home/infochat/infochat-test/prod/runtime/test-clients/probe-v7/probe-v7-capture-1.log prints the send's frame sequence with timestamps; docker logs infochat-test-infochat-provider-1 2>&1 | grep 'exhausted retry budget' shows the matching escalation timing (the line produced at OutboundDelivery.java:437); grep -n 'bot path' docs/design/06-messaging.md shows the new §6.2.4 trail paragraph naming the timed-out stage and frame shape."
  - "The M1-840 tension is resolved in the record: what differs between LiveSimpleXFileSendIT's harness (which asserted sndFileCompleteXFTP works on v7) and the live bot path — at minimum the harness-vs-production gap (the IT drives LiveSimpleXClient, a test-tree client, not the production SimpleXWebSocketClient dispatch + corrId matching) plus whatever the capture shows (recipient profile age, the bot's migrated v6→v7 DB, file size, registration state) — Verify: a named 'harness vs bot path' paragraph in the §6.2.4 record cites the capture."
  - "SimpleXAdapterAttachmentTest.capturedV7BotPathFramesReleaseTheFileSend passes — REPRODUCTION REGRESSION (to-be-written at start from the capture, run RED before the adaptation): the captured ack and completion frames, as literal fixture constants (the SimpleXMessageCodecV7WireTest captured-frame precedent), fed through the production decode+dispatch (FakeSimpleXProcess driving the real SimpleXWebSocketClient), release sendAttachment — the frames the live v7 bot path actually emits complete the pending future instead of timing out."
  - "The adaptation touches ONLY the stage the capture indicts, derived from the captured shape and never invented (analysis P9): a widened itemId placement or resp.type mapping in SimpleXMessageCodec, or a dispatch/matching change in SimpleXWebSocketClient — each new or widened arm carries its own failing-first captured-frame test (codec-level, in SimpleXMessageCodecV7WireTest) asserting the decoded result; a corrId-less frame must NOT be routed as a send ack (the M1-508 inbound routing stands — SimpleXMessageCodec.java:399-434); Ignored reasons stay fixed sentinels and no transport prose reaches exceptions or logs (security.md §User content in exceptions)."
  - "AUTHORIZED CONDITIONAL MODIFICATION (engineering-rules §8, the M1-840 precedent): SimpleXAdapterAttachmentTest's fixture frames flip to the captured v7 bot-path shapes ONLY on proven drift, with every release/PERMANENT assertion unchanged — the blocks-past-ack-until-XFTP-completion, fileTransferFailureEventFailsClassifiedPermanent, nonXftpFileCompletionFailsClassifiedPermanent, standaloneFileCompletionFailsClassifiedPermanent, adapterRetainsNoCopyOfThePayload, and sendAttachmentPutsTheImageFormOnTheWire semantics all stand — Verify: the six methods green with their assertions byte-identical modulo the §8-authorized fixture strings."
  - "Controls preserved (engineering-rules §10; analysis P8/P11): the sndFileCompleteXFTP-or-PERMANENT mapping (only sndFileCompleteXFTP releases; failure/degradation tags stay PERMANENT — default-to-permanent per messaging.md §Failure handling), the injection guard (encodeSendFileCommandRejectsInjectionInScopeIds with the new signature if any), the one-rate-token-per-frame pacer, the unreadable-path PERMANENT guard, the metadata-only no-retention posture, and the OutboundDelivery ladder/attribution/metrics are untouched — Verify: `grep -n 'ACK_TIMEOUT\\|FILE_COMPLETION_TIMEOUT' infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java` shows the values unchanged and the diff contains no ladder, timeout, or dedup change."
  - "LIVE re-verification on the test instance (stack up, probe-v7 registered; never prod): an /image send completes with NO escalation — sendAttachment returns inside the normal ack+completion window, the recipient receives the file (/fr verified), and the provider log carries no 'exhausted retry budget' line for the send — Verify: the probe run log + provider log excerpt recorded in the ticket evidence; plus the durable regression: LiveSimpleXFileSendIT (or a sibling opt-in IT, -Dinfochat.live.simplex) gains an arm that awaits ack and completion THROUGH THE PRODUCTION SimpleXWebSocketClient dispatch (not LiveSimpleXClient alone), so the harness-vs-production gap this defect exposed stays closed — the IT records the binary banner (SimpleX Chat v7.0.0.11)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterAttachmentTest.java (capturedV7BotPathFramesReleaseTheFileSend)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecV7WireTest.java (captured-frame constants + per-arm decode tests)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterAttachmentTest.java (fixture frames flip to captured v7 shapes ONLY on proven drift — pre-authorized by acceptance item 5)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/live/LiveSimpleXFileSendIT.java (production-dispatch arm — pre-authorized by acceptance item 7)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D64
  - D74
---

# M1-855: SimpleX v7 completion contract on the live bot path

## Context

Live probe 2026-08-15 (test instance, bundled CLI v7.0.0.11, probe-v7
client): both /image sends DELIVERED the file within seconds, and both were
then reported to the user as delivery failures ~1.5–2 min later — the
OutboundDelivery ladder exhausted three TRANSIENT
`MessagingException > TimeoutException` attempts and escalated to permanent
(OutboundDelivery.java:435-437), answering with IMAGE_ERROR_SEND_FAILED
while the user was looking at the picture. The user's decision (2026-08-15):
the completion contract must reflect delivered reality on the live v7 path.
M1-840's opt-in IT asserted the same contract GREEN on v7 in its own
two-identity harness — the tension this ticket must resolve, not wave away.
Shared analysis: `analysis_ref:` (P6-P11 are this ticket's slice).

## Root cause

Proven (analysis §Root cause, defect 2): the ladder exhausted on a
TRANSIENT TimeoutException ≈95–115 s after a frame the transport accepted
and delivered (probe transcript timestamps vs escalation timestamps:
+98 s / +114 s). Strong inference, unconfirmed: the failing wait is the
30-s ACK wait (three × 30 s + sub-second backoff ≈ 90–95 s matches; three
5-min completion waits would be ≥ 15 min) — i.e. the v7 bot-path ack for
the /_send file command never completes the pending corrId future in the
production dispatch (SimpleXWebSocketClient.java:639,
SimpleXMessageCodec.java:975-981/:425-434), while text sends on the same
path work and M1-839's captured v7 TEXT ack decodes with corrId
(SimpleXMessageCodecV7WireTest.v7CapturedSendAckDecodesWithCorrId). NOT
proven — needs this ticket's live frame capture: the exact frame-shape
divergence (resp.type, corrId placement, itemId placement at
SimpleXMessageCodec.java:1010-1013, or an event the production dispatch
never sees) and why M1-840's harness did not expose it (its IT drives
LiveSimpleXClient, a test-tree client, not the production dispatch). The
ticket is safe to start precisely because its first gate is the capture:
every later step is conditional on recorded evidence, with an explicit
escalation when nothing is mappable.

## Pitfalls

- P6: never guess a release signal — the fix derives from captured frames
  on the live bot path; no mappable event ⇒ escalate, not invent
  (M1-800 → M1-840's completion-signal rule).
- P7: fix the stage the evidence indicts — the timing arithmetic points at
  the ack wait; widening only the completion decode (or vice versa) while
  the other stage is the breaker leaves the false-failure defect in place.
  The capture names the stage before any adaptation lands.
- P8: the sndFileCompleteXFTP-or-PERMANENT pin — SimpleXAdapterAttachmentTest's
  six methods are the contract's pin; fixture flips only on proven drift
  (§8 pre-authorization), release/PERMANENT assertions unchanged;
  default-to-permanent stands; releasing on ack-alone or progress frames
  re-opens the spool-reclaim-mid-upload hazard.
- P9: decode-boundary security — placements from capture, never invention;
  fixed-sentinel Ignored reasons; no transport prose into exceptions/logs;
  corrId-less frames must not become send acks (M1-508's inbound routing).
- P10: capture-harness discipline — no second WS client against the bot's
  chat server (shared outputQ race, M1-841); the recorded harness pattern
  (test-host HANDOFF docs/plan/live-e2e/HANDOFF.md:1920-1930 — the WsProbe
  corrId-command sidecar against the subprocess WS :5225; the
  simplex-live-frame-capture memory it cites dangles in both checkouts)
  against infochat-test only; nothing named infochat-prod; committed
  evidence redacts contact ids (D37).
- P11: D64 duplicates — the ladder's re-transmits may have delivered up to
  3 copies; that is at-least-once by spec, NOT fixed here (no dedup, no
  ladder weakening, no timeout inflation).

## Approach

- **Files to touch:** `files_scope` (evidence-only plan; SimpleXWebSocketClient
  only if the capture indicts dispatch/matching rather than decode).
- **Steps, in order:**
  1. Capture (item 1): one /image send from probe-v7 on the running test
     stack, per the recorded harness pattern (P10 — the HANDOFF WsProbe
     corrId-command sidecar plus provider DEBUG frame logging); capture the
     bot-side WS conversation for the whole send (command → ack → progress
     → completion) plus the send→escalation timing, saved to the capture
     log named in item 1. Analyze which wait times out and the exact shape
     at that stage (P6/P7/P10).
  2. Record (item 2): the §6.2.4 trail — the captured frames, the indicted
     stage, the harness-vs-bot-path delta resolving the M1-840 tension.
  3. RED (item 3): the captured frames as fixture constants;
     capturedV7BotPathFramesReleaseTheFileSend through the production
     dispatch; run RED.
  4. Adapt (item 4): exactly the indicted stage — codec placement/type
     mapping and/or dispatch matching — each arm with its own failing-first
     captured-frame test; §8 conditional fixture flip if the six methods'
     fabrications drifted (item 5).
  5. Re-verify live (item 7): /image on the stack — no escalation, file
     received; add the production-dispatch arm to the opt-in live IT.
  6. Escalation clause (P6): if the capture maps to nothing — no frame the
     adapter can map to ack or completion (no candidate resp.type, no
     itemId anywhere; mechanically: grep -nE 'sndFile|newChatItems|sentEvents|chatItem'
     on the capture log returns no ack/completion candidate, or every
     candidate decodes to a fixed-sentinel Ignored with no captured shape
     to derive an arm from) — the ticket STOPS and escalates with the
     unmappable capture as its deliverable: no guessed release signal, no
     timeout inflation; the user decides between a spec amendment and
     dropping. A capture that maps is a fix; a capture that does not is an
     escalation.
- **Controls to preserve (§10):** enumerated in acceptance item 6 — the
  completion mapping and its six-method pin, injection guard, rate token,
  unreadable-path guard, no-retention posture, and the untouched
  OutboundDelivery ladder.
- **Pitfall→mitigation:** P6→item 1 + the escalation clause (step 6);
  P7→items 1-2 (stage named before adaptation); P8→item 5's conditional
  authorization + item 6's grep; P9→item 4's per-arm tests and routing
  rule; P10→item 1's harness constraints; P11→item 6's
  no-ladder/no-timeout/no-dedup grep.

## Definition of done

The capture exists and names the indicted stage and shape; the §6.2.4
record carries the v7 bot-path trail and the harness-vs-production delta;
the captured-frame regression test passes (RED→GREEN); only the indicted
stage changed, each new arm pinned by a captured-frame test; the six
attachment-test methods keep their semantics (fixtures flipped only on
proven drift); a live /image send completes with no escalation while the
recipient receives the file; the opt-in IT drives the production dispatch;
full verify green — or the ticket escalated with the unmappable capture as
its deliverable.

## Verification

- P6 → item 1 + the Approach escalation clause (step 6): the capture and
  its grep-able artifact are the first gate; the escalation clause forbids
  guessing (a mapping invented without a captured frame fails review
  against item 4's never-invented rule).
- P7 → items 1-2: the record names the timed-out stage with timing
  evidence; the adaptation diff touches only that stage (a completion-decode
  change when the capture indicts the ack fails item 4).
- P8 → item 5: six methods green, assertions byte-identical modulo
  authorized fixtures; item 6's grep shows the timeouts unchanged.
- P9 → item 4's per-arm captured-frame tests; fixed-sentinel assertions
  (the V7WireTest error-frame precedent,
  v7CapturedErrorFrameClassifiesPermanentWithoutFreeFormLeak).
- P10 → item 1's harness constraints; committed evidence redacted (a raw
  contact id in a committed fixture/log fails review, D37).
- P11 → item 6: no ladder/timeout/dedup change in the diff.
- Item 7 → the live run log + provider log excerpt (no escalation line) and
  the IT arm awaiting through the production dispatch.
- Item 8 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: the preview calibration (M1-854), timeout/ladder/
dedup changes, the six-method pin beyond the conditional fixture flip, the
text-send path, inbound decoding, Signal, provisioning, any spec edit, and
inventing a release signal or inflating timeouts when the capture maps to
nothing — that path is the Approach step-6 escalation (stop and escalate
with the capture as the deliverable), never an inline bend.
Two pre-existing test files are modified conditionally —
SimpleXAdapterAttachmentTest (fixtures on proven drift) and
LiveSimpleXFileSendIT (production-dispatch arm) — both pre-authorized in
acceptance items 5 and 7; no other pre-existing test is touched.
