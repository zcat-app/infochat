---
id: M1-839
title: "Re-verify SimpleX text/group wire forms on CLI v7.0.0"
status: done
created: 2026-08-14
last_updated: 2026-08-15
clarity_check: >-
  2026-08-15 start pre-flight: lint green (tick-analysis/ is gitignored —
  local-only in the primary; copied into this worktree, ignored path so no
  diff impact); census grep re-runs clean; analysis P5/P9/P10/P11 all
  landed; M1-838's added test (BundledSimplexCliPinTest, provider/config)
  is off this ticket's seam, preserved trivially; design :665-691/:694-729/
  :795-811 citations are stale by M1-838's +33-line surface-review insert —
  content anchors verified at :765-800 (§6.4.4 field locations), :805+
  (invitation flow), :917 (§6.2 array form); M1-838 tag-diff record read,
  zero shape changes on this ticket's surfaces, no probe-order flags; host
  probe binary confirmed v7.0.0.11. No blocking ambiguity.
flow: tick
reproduction: >-
  SimpleXMessageCodecV7WireTest.v7CapturedDirectDmDecodesToInbound
  (infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/)
  — feeds the codec a direct-DM `newChatItems` frame freshly captured from the
  bundled v7.0.0 binary and asserts it decodes to Inbound with sender id,
  body, and `meta.itemId` intact. Converted from the to-be-written marker at
  start (2026-08-15): the frame was captured (loopback probe pair against the
  M1-838-refreshed v7.0.0.11 host binary), the test written, and run — GREEN,
  i.e. no decode drift on the direct-DM surface (the drifted surface proved
  to be outbound /_update; see the evidence record).
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
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN (1 low, stale never-live-verified comment on encodeUpdateCommandEmitsSingleMsgContentObject), SCOPE PASS"
    diff_stats: "8 files, +1216/-59"
    fix_items: 1
    verdict_file: .scratch/tick-review-M1-839-r1.txt
    fix_probes: >-
      1. grep -n "NOT live-re-verified" SimpleXMessageCodecTest.java → no hits
      (exit 1); every changed line comment-only (diff-over-tree grep exit 1);
      ./mvnw -B -pl infochat-messaging-adapter -am test-compile BUILD SUCCESS;
      fixed tree .scratch/tick-fixes-M1-839.tree = 2c01651b4485f79feaed037f8e93c727a37b3558
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

## Evidence record (2026-08-15, implementation)

All probes ran on this deployment host against the M1-838-refreshed host
binary (`prod/runtime/simplex-clients/bin/simplex-chat`, `--version` ->
`SimpleX Chat v7.0.0.11`); capture logs under `/tmp/opencode/m1-839/`
(throwaway, never committed). D37: fixture bodies are probe canaries
("v7 probe ..."); the address fixture's two link values are same-grammar
synthetic substitutions (SimpleXAddressQueryTest pattern); no real user
traffic was captured, and both throwaway probe data-dirs were deleted.

### Capture method

Throwaway loopback probe pair (design 6.4.4 pattern): two fresh
simplex-chat subprocesses (bot `--create-bot-display-name v7probe-bot` on
15100, user `--user-display-name v7probe-user` on 15101; both bind
127.0.0.1 only), user connects via the bot's `/ad`-created short link over
the public SMP relays, bot accepts the contact request, then the scripted
scenario drives every enumerated surface. Raw frames logged per side;
provenance recorded per fixture in SimpleXMessageCodecV7WireTest.

### Per-surface dispositions (acceptance item 2)

Every disposition cites a v7.0.0-captured fixture, never a v6.5.4.1 capture:

- direct DM `newChatItems` — captured; decodes Inbound (chatInfo.type,
  contact.localDisplayName, meta.itemId, contactId) — UNCHANGED.
- group message with `mentions{}` envelope — captured (see the send-form
  note below): `chatItem.mentions` values are `{memberId, memberRef}`
  objects (memberRef additive, ignored by the codec);
  `chatInfo.groupInfo.membership.memberId` byte-equals the mention's
  memberId — D51 anchor UNCHANGED; formattedText carries the mention span
  and reconstructs the text exactly (span guard holds).
- send-ack `newChatItems` with corrId — captured; SendAck with
  `chatItems[0].chatItem.meta.itemId` — UNCHANGED.
- `receivedGroupInvitation` + `/_join` response pair — captured;
  ReceivedGroupInvitation (groupId, invitedBy.byContactId, type contact) +
  userAcceptedGroupSent corrId response + async userJoinedGroup (both
  Ignored) — UNCHANGED.
- `userContactLink` (`/show_address`) — captured (short link present);
  response shape identical to the v6.5.4.1 fixture — UNCHANGED.
- `chatItemUpdated` live-edit finalize — captured (both the corrId ack on
  the editor's connection and the recipient-side async echo); body path
  `resp.chatItem.chatItem.content.msgContent.text` — UNCHANGED; codec
  Ignores the type as before.
- FAILURE-MODE `chatCmdError` — captured (corrId response to a bare-object
  `/_send` payload): errorType.type enum tag, free-form message never
  surfaced, fails closed PERMANENT — UNCHANGED.

### Drift found and fixed (acceptance item 5 path)

- `/_update` payload requires a `mentions` key. The production codec's
  `{"msgContent":...}`-only edit payload is rejected
  `chatCmdError commandError "Failed reading: empty"` on v7.0.0.11.
  Control run against a v6.5.4.1 binary (extracted from the still-tagged
  pre-M1-838 prod image): REJECTED IDENTICALLY — i.e. this is a latent
  defect on every pinned version, not v6->v7 drift; the pre-ticket design
  itself recorded the update form as "inferred ... not live-reverified".
  Fix: `updatedMessageContent` (encode-side, `mentions:{}` always — the
  bot never mentions); failing-first test
  `SimpleXMessageCodecTest.encodeEditCommandCarriesMentionsKey` run RED,
  then GREEN. Design section 6.2 "Update encoding" re-anchored.
- Harness mention envelope was mention-silent. `getCIMentions`
  (identical in both tags) silently DROPS the whole mentions{} map when the
  message carries no formattedText naming the member —
  `LiveSimpleXClient.encodeMentionSendCommand` (numeric mentions, no
  formattedText) has therefore never delivered a real mention. Fixed to
  the captured working form (formattedText segments +
  `{"mention":"<name>"}` format + numeric id); pins updated in
  `LiveSimpleXHarnessFrameTest` (numeric-value pin retained; adds the
  formattedText pin and the invisible-mention rejection). No production
  surface involved (the bot never mentions); live scenario s07's mention
  steps become non-vacuous on the next live run.

### Live re-run (acceptance item 4) — 2026-08-15, test instance

Executed against the ISOLATED live-test deployment
(`/home/infochat/infochat-test`, compose project `infochat-test`), NOT
prod (prod containers untouched throughout; the user approved the test
instance as the target). The test stack ran CONCURRENTLY with prod via a
ports override (`docker-compose.ports.yml`, host 15432/11435; service
traffic stays on the compose network, so only the loopback operator
publishes moved). The instance was merged to current main, and BOTH app
images were rebuilt from THIS ticket's worktree (code under test incl.
the codec fix). Bot DB pre-migrated v6->v7 offline via the image's
v7.0.0.11 binary one-shot (simplex-chat's own `.bak` copies landed beside
the DBs; NOTE: my tar backup attempts failed on a host uid/chown quirk —
the `.bench` 20260729 archive holds this bot's July identity as the
disaster fallback; no issue arose). Bot profile renamed infochat-bot ->
Admin-Reno for the ITs' BOT_DISPLAY_NAME fixture. LiveAdmin/LiveUser
client identities provisioned fresh (contacts 10/11); admin via the
re-armed claim token; registration flows driven per scenario.

Per-scenario results (all with `-Dinfochat.live.simplex=true`):

- LiveSimpleXRoundTripIT: PASS (admin /help round-trip; ~0.7 s).
- s04 uninvited-dm-rejected: PASS (2.8 s; fixed D44 rejection delivered).
- s03 invite-mint-consume: PASS (3.6 s; --open confirm-gate + D44
  register).
- s07 group-pending-approve-autopromote: PASS, 5/5 steps (4.0 s; mention
  steps 311/317 ms) — the corrected D51 mention envelope (formattedText +
  numeric id) is live-proven on v7: pending reply, /list-groups,
  /approve-group, real group reply, auto-promote + group-admin command.
  The invitation->/_join pair also live-proven (the provider's D47 gate
  accepted the bot's join through the v7 frames).
- s11 zcash-snapshot: PASS (2.8 s).
- s12 chat-mode: PASS (96.3 s; real DeepSeek chat round-trips over v7
  wire).
- s15 full-happy-path: steps 1-4 PASS (invite mint/consume, /help,
  /zcash real price data); step 5 (/summary) did not match the scenario's
  fixed "No posts to summarize yet" expectation — HOST-FIXTURE mismatch,
  not a v7 regression: this instance preserves a 3.8k-post data-plane
  corpus whose ready_at the collector re-stamped in-window on boot, so
  /summary -w 24h produces a REAL digest. A manual drive from the
  live-user client confirmed the full digest body delivers over the v7
  wire (~380 s, LLM-bound).
- s10 summary-and-group-digest: NOT RUN — needs prod/live-seed.sh +
  follows + an approved-group fixture + a config-aimed digest window
  (D-live-8); its summary surface is evidenced by the s15 manual run and
  its group surface by s07. Deferred with this disposition ("as host
  fixtures allow").

Adapter metrics after the runs: `adapter_outbound_total{outcome="ok"} 16`
DM sends, no update-counter series — the production /_update edit path was
not exercised by these flows (the digest delivered via plain sends); the
edit-path v7 evidence is the loopback capture (c7/c8 accepted + the
peer's chatItemUpdated finalize frame, committed as fixtures) plus the
RED->GREEN encode test. No suite change forced anything into
`mvn verify` (skipped-on-CI gate shape unchanged).

Post-run state: test stack stopped (containers/volumes preserved — parked
as found); bot profile left as Admin-Reno for future live runs; prod
untouched.
