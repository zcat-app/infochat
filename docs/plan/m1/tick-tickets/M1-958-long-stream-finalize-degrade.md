---
id: M1-958
title: "Long-stream finalize degrade: enforce the label revert"
status: done
created: 2026-08-30
last_updated: 2026-09-01
flow: tick
reproduction: >-
  Child of a 2+ decomposition (analysis
  docs/plan/m1/tick-analysis/retrieval-campaign-followups.md); the brief
  authorizes a to-be-written marker (a live repro needs a 3.5-min stream —
  the operator evidence is the observed-output probe: the memory-local note
  .agents/memory-local/test-stack-deployed-939-and-frozen-db-superseded.md
  §NEW DEFECT OBSERVED; client item 17 / bot item 2427, version history
  answer->degrade at 11:53:22; observed output = the full Czech answer
  delivered live, then the FINAL edit replaced it with the
  error.chat.unavailable bundle string; no provider log line, no audit row
  beyond CHAT_MODE). The wrong behavior, verified in-tree: the streamed
  tool-iteration label revert rides the ORDINARY coalesced publish —
  StageProgressNotifier.publishText DISCARDS any update inside
  max(systemFloor, adapterMin) silently (StageProgressNotifier.java:188-202;
  floor default 600 ms :105-106) — so the revert
  ChatLiveTextStreamer.showGeneratingLabel() issues when an iteration's
  text ends in a tool call (ChatLiveTextStreamer.java:131-140/:170-176;
  runToolLoop :1146-1149) is silently DROPPED and the full live answer
  stays displayed, while docs/spec/security.md §LLM output sanitizer
  (Streamed surfaces) promises "an iteration that ends in a tool call never
  leaves its text displayed; the display reverts to the localized stage
  label". When the turn's terminal post-pipeline text then goes blank, the
  step-9c emptied-reply degrade (ChatAgent.java:835-838 — the ONLY
  error.chat.unavailable return with no log and no audit row: both catch
  arms log at :492/:513 and the refusal arm returns a different bundle and
  logs at :768) finalizes OVER the still-displayed answer
  (InboundRouter.java:1390-1391 -> StageProgressNotifier.terminate
  :355-377 finalizeInPlace). Intended entry (to-be-written, converted at
  start): StageProgressNotifierLiveTextTest#
  emptiedFinalTurnNeverReplacesPublishedLiveTextWithoutTheLabelRevert — the
  streamed rig at the REAL 600 ms floor, streamingSequences =
  [List.of("<full answer> TOOL_CALL: searchPosts {\"tags\": []}"),
  List.of("")] (a complete answer followed by a trailing tool call, then an
  empty final call); asserts the wire's update history shows the localized
  GENERATING label BETWEEN the last live answer edit and the degrade
  terminal. RED today: the revert is coalesced away and the wire goes
  answer -> degrade with no label.
analysis_ref: docs/plan/m1/tick-analysis/retrieval-campaign-followups.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatLiveTextStreamer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/StageProgressNotifierLiveTextTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    CONTENT-PRESERVING FINALIZE — finalizing with the last published live
    text when the terminal degrades is a SPEC AMENDMENT (messaging.md
    §Progress notifications' terminal rule carries the emptied-reply degrade
    and forbids the last streamed prefix as the finalize; security.md's
    accepted residuals name the transient-display-then-degrade case) —
    surfaced to the driver here, never implemented on this ticket (analysis
    P13; probe: the diff's finalize carries only post-pipeline text).
  - >-
    Any sanitizer pass, closed list, match-set derivation, or audit-row
    change — the streamed audit regime (per-turn aggregation, abort on audit
    write failure) is untouched.
  - >-
    The tool protocol's fail-closed display semantics — prose preceding a
    tool opener stays fail-closed for display (aToolCallIterationRevertsToTheStageLabel's
    pins); no tool-loop, grammar, or strip change.
  - >-
    The batch (non-streamed) emptied-reply degrade's shape and silence —
    M1-796's landed rule (llm.md §Failure handling (recap)) is followed,
    never amended; only the STREAMED turn gains attribution.
  - >-
    ChatAgent's catch arms, breaker, refusal intercept, prompt-exceeded
    degrade — all verified already logging; untouched.
  - >-
    Any eval-lane file (M1-957/M1-959 scope) and any spec edit (the revert
    is already promised; this ticket enforces, never amends).
acceptance:
  - "REPRODUCTION closed: StageProgressNotifierLiveTextTest.emptiedFinalTurnNeverReplacesPublishedLiveTextWithoutTheLabelRevert passes — the rig at the REAL 600 ms floor (set explicitly via the rig's setField), streamingSequences = [List.of(\"<full answer>\\n\\nTOOL_CALL: searchPosts {\\\"tags\\\": []}\"), List.of(\"\")]; asserts (a) the update history shows the localized GENERATING label between the last live answer edit and the terminal, (b) the terminal carries the error.chat.unavailable bundle string (the step-9c degrade — spec-following, never the streamed prefix), (c) no TOOL_CALL bytes ever shipped. RED today on (a): the revert is coalesced away."
  - "The protocol revert is force-transmitted (analysis P10): the revert-to-label publish is NOT silently discardable when the last transmitted body was live text — a forced in-place edit (or equivalent deferral that GUARANTEES shipment before any terminal) carries it; ORDINARY live-text updates keep the coalescing economics byte-identically (messaging.md §Progress notifications' cadence ceiling). FAILURE-MODE discriminator: a leg asserting an ordinary in-floor LIVE update is still coalesced (one update on the wire, not two) — a mutation that force-transmits everything reds it (StageProgressNotifierLiveTextTest, the coalescing leg)."
  - "Mechanism independence (analysis P11): a second rig leg drives the budget-break/cap shape — iteration 1 a plain tool call (reveal reset, label), the final call returning EMPTY (ChatAgent.java:1191-1196's path) — asserting the SAME invariant: the label is on the wire before any terminal that differs from the published live text. Both empty-terminal mechanisms are separately pinned."
  - "Attribution (analysis P12; D37): the step-9c emptied-reply degrade on a turn WITH a live reveal emits one WARN naming the scope only in SafeLog-redacted form (no user prose, no reply bytes, no anchored text) — pinned by a log-capture double in the streamed rig (a StageProgressNotifierLiveTextTest leg asserting the WARN fired on the reproduction turn); the batch path's behavior is unchanged (its M1-796 pins pass unmodified)."
  - "Controls preserved (engineering-rules §10): every pre-existing StageProgressNotifierLiveTextTest leg passes UNMODIFIED (aLiveTextTurnStreamsSanitizedPrefixesThenFinalizes, aRefusalPrefixedStreamPublishesNothing, aSanitizedAssembledRefusalPrefixPublishesNothing, aSanitizedAssembledToolOpenerNeverShipsOnTheWire, aToolCallIterationRevertsToTheStageLabel, aLiveUpdateBreaksLinkAdjacencyAtDelivery, the eligibility-collapse legs) — probe: git diff names no existing test method beyond the rig's floor/sequence setup the new legs need, each authorized in plain language here; sanitizeStreamed's per-turn audit aggregation untouched; the D31 placeholder lifecycle untouched (no path may dangle); the terminal rule untouched."
  - "mvn verify from the repo root is green."
test_plan:
  adds:
    - >-
      StageProgressNotifierLiveTextTest —
      emptiedFinalTurnNeverReplacesPublishedLiveTextWithoutTheLabelRevert (the
      reproduction, real 600 ms floor), the budget-break-shape sibling leg
      (P11), the coalescing discriminator leg (P10's failure-mode), and the
      WARN attribution leg (P12; log-capture double).
  modifies: []
  preserves:
    - >-
      every pre-existing leg of StageProgressNotifierLiveTextTest and the
      M1-796/M1-795 pins (ChatAgentTest.aReplyThatSanitizesToEmptyDegradesLikeAnAssistantFailure,
      anEmptiedReplyWithAMatchedHelpBlockStillDeliversTheDeterministicBlock,
      OutboundDeliveryTest/StageProgressNotifierTest) — byte-identical.
    - all tests currently green on main.
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/messaging.md §Progress notifications
  - docs/spec/llm.md §Failure handling (recap)
decision_refs:
  - D31
  - D37
  - D43
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-09-01
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS — 3 falsification candidates dropped with citations (WARN-not-via-SafeLog defeated by the refusal-arm analog ChatAgent.java:768 + SafeLog.java:69-71 having no throwable-less signature + a constant message whose only parameter is the userId UUID; budget-break-leg-shape divergence defeated by 30000 chars = 7500 tokens > the 6144 rig budget driving the post-break final call at ChatAgent.java:1198-1203 — the acceptance's own cited path — while a literal plain-tool-call first iteration would publish no live text and pin nothing; coalescing-leg timing flake defeated by the stub delivering both chunks back-to-back in one synchronous loop, StageProgressNotifierLiveTextTest.java:642-655). Verdict: .scratch/tick-review-M1-958-r1.txt"
    diff_stats: "6 files, +238/-15 (StageProgressNotifier.java +28 forced-revert publish; ChatLiveTextStreamer.java 2-line revert call-site switch; ChatAgent.java +7 step-9c streamed-degrade WARN; StageProgressNotifierLiveTextTest.java +178 four new legs — reproduction, budget-break, coalescing discriminator, WARN log-capture; ticket frontmatter bookkeeping; board regen)"
    notes: >-
      0 rework items, 0 critical/high. Gate confirmed the revert reaches
      the wire before any terminal when the last shipped body was live
      text, ordinary in-floor updates stay coalesced byte-identically, the
      WARN carries no user-derived byte (constant message + userId UUID
      only), all 11 live-text legs green in the full-suite verify log
      (BUILD SUCCESS, finished 2026-09-01T01:18:19), and no file outside
      files_scope + bookkeeping touched. Full-suite mvn verify green from
      the repo root (target/tick-test-M1-958-r1.log).
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-09-01
  result: >-
    Self-check clean, no blocking question. Lint 0 findings. All file:line
    citations spot-checked true: StageProgressNotifier.java :105-106 floor
    default 600, :188-202 silent in-floor discard, :355-377 terminate
    finalizeInPlace; ChatLiveTextStreamer.java :131-140 opener revert,
    :170-176 onIterationToolCall; ChatAgent.java :492/:513 logging catch
    arms, :768 refusal arm, :835-838 step-9c no-log degrade, :1146-1149
    runToolLoop revert call, :1191-1196 budget-break/cap final call;
    InboundRouter.java :1390-1391 completeDelivered terminal. Census grep
    re-runs clean, all four rows match. Analysis P10-P14 all landed; every
    preserves leg exists in-tree. One shorthand resolved: the acceptance
    literal writes the reproduction's first sequence as one chunk holding
    answer + opener, but onChunk never publishes live text once the prefix
    carries an opener, so the described wire (answer shipped, revert
    coalesced away) requires the opener in a LATER chunk of the same call
    — the reproduction splits the first sequence into [answer, opener]
    chunks (the literal's <full answer> token is a placeholder either way).
escalation_reason:
---

# M1-958: Long-stream finalize degrade: enforce the label revert

## Context

During the M1-939 live probe (2026-08-29) a 3.5-minute streamed tech-news
turn (~90 live edits) delivered the full Czech answer live, then the FINAL
edit replaced it with the `error.chat.unavailable` bundle string — silently:
no provider log line, no audit row beyond CHAT_MODE (operator evidence:
the memory-local 939 note; client item 17 / bot item 2427, answer→degrade
at 11:53:22). Short/medium turns finalize fine. The lineage (M1-789/791/
795/796) built the sanitize/degrade/empty-reply surface this defect lives
on; M1-846 built the live-text surface. Shared analysis: `analysis_ref:`
(Ground truth, Pitfalls P10-P14, options A-D).

## Root cause

Proven from operator evidence + code reading: the final in-place edit
replaced the displayed answer with the step-9c emptied-reply degrade
(ChatAgent.java:835-838) — the only `error.chat.unavailable` return with
no log and no audit row (both catch arms log at :492/:513; the refusal arm
returns a different bundle and logs at :768), matching the empty evidence
trail exactly. The spec-promised display sequence did not play out: the
tool-iteration revert to the localized stage label rides the ORDINARY
coalesced publish and is silently DISCARDED inside the edit floor
(StageProgressNotifier.java:188-202) — the opener bytes of a trailing tool
call arrive milliseconds after the answer's last live edit — so the
answer stayed displayed into a degrade finalize, violating security.md
§LLM output sanitizer (Streamed surfaces): "the display reverts to the
localized stage label". NOT fully proven: WHICH empty-terminal mechanism
produced the blank (a trailing tool call after a complete answer followed
by an empty next/final call, or the budget-break/cap final call returning
a degenerate empty completion — both code-consistent; the reproduction
pins both). The ticket is safe to start because the fix targets the
invariant both mechanisms share.

## Pitfalls

Carried from the analysis, numbered identically; this ticket carries
P10-P14.

- P10: the coalesced label revert — publishText silently discards in-floor
  updates; the protocol revert rides it; the unit rig's default floor (0)
  masks the defect, so the reproduction sets the real 600 ms floor.
- P11: mechanism discrimination — ≥2 code-consistent empty-terminal
  mechanisms; fix the invariant (a degrade finalize must never replace
  still-displayed live answer text without the promised label revert
  having shipped), pin BOTH mechanisms separately.
- P12: silent degrade observability — step-9c is the only
  error.chat.unavailable return with no log/audit; M1-796's "covered by
  the LLM_OUTPUT_SANITIZED audit row" rationale fails when sanitizer
  matches are empty (a clean answer produces none). The WARN (D37 shape)
  is part of the fix.
- P13: do not preserve the streamed answer at finalize — the terminal rule
  carries the degrade and forbids the last streamed prefix; that shape is
  a spec amendment, out of scope.
- P14: the live 3.5-min repro is operator evidence, never a CI gate.

## Approach

Derived from `spec_refs:` — security.md §LLM output sanitizer owns the
Streamed-surfaces regime whose bullet 3 promises the revert; messaging.md
§Progress notifications owns the live-text publisher mode, its cadence
ceiling (ordinary updates stay coalesced) and its terminal rule (followed,
never amended); llm.md §Failure handling (recap) is the emptied-reply
degrade rule M1-796 landed (followed on the streamed path).

- **Files to touch** — `files_scope`: the notifier (the forced revert
  publish), the streamer (the revert call site uses it), ChatAgent (the
  streamed-degrade WARN), the live-text test (four new legs).
- **Pre-decided shapes:**
  1. **RED first (workflow §0):** the reproduction leg at the real floor —
     trailing-tool-call sequence + empty final call.
  2. **The forced revert:** `StageProgressNotifier` gains a
     forced-publish variant used ONLY by the protocol label revert when
     the last transmitted body was live text (bypasses the edit floor for
     that one edit, or a deferral shape that guarantees wire shipment
     before any terminal — the implementor picks, the legs pin the
     behavior, not the mechanism); `ChatLiveTextStreamer`'s revert paths
     call it. Ordinary live updates keep `publishText` byte-identically.
  3. **The WARN:** at the step-9c site, when the turn carried a live
     reveal, emit one `SafeLog.warn` naming the class + redacted scope —
     no prose, no reply bytes.
  4. **The discriminator + budget-break legs** per the acceptance items.
- **Steps in order:** (1) the four legs RED where applicable; (2) the
  forced revert (shape 2); (3) the WARN (shape 3); (4) full `mvn verify`.
- **Controls to preserve (§10):** the sanitize-pure-output regime (every
  live update remains the sanitizer's output over the full prefix — the
  revert changes WHEN the label ships, never WHAT live text carries); the
  fail-closed refusal hold-backs; the per-turn audit aggregation; the D31
  placeholder lifecycle; the terminal rule; the cadence ceiling for
  ordinary updates; D37 in the new WARN.
- **Pitfall→mitigation:** P10→shape 2 + the coalescing discriminator leg;
  P11→the budget-break leg; P12→shape 3 + the log-capture leg; P13→the
  out-of-scope block + the reviewer's finalize check; P14→the reproduction
  is in-tree, the live probe cited as evidence only.
- **Alternatives considered (rejected; the commit message cites them):**
  content-preserving finalize (B — spec amendment, P13); fixing one
  hypothesized mechanism, e.g. treating a trailing tool call after
  non-blank prose as final text (C — ambiguous against the fail-closed
  display discipline the existing pins enforce, leaves the other mechanism
  live); observability only (D — leaves the promised revert droppable and
  the user-visible wrong standing).

## Definition of done

The reproduction passes at the real floor (label on the wire between the
answer and the degrade terminal); the protocol revert is force-transmitted
while ordinary live updates stay coalesced (discriminator leg); the
budget-break mechanism is pinned separately; the streamed degrade emits
the D37-shaped WARN; every pre-existing live-text leg and the M1-795/796
pins pass unmodified; repo-root `mvn verify` is green.

## Verification

- P10 → the reproduction leg (RED today) + the coalescing discriminator
  leg (a mutation force-transmitting everything reds it).
- P11 → the budget-break-shape sibling leg (the same invariant on the
  other mechanism).
- P12 → the WARN log-capture leg (asserts the line fires on the streamed
  degrade and carries no user prose); the batch legs unmodified.
- P13 → the reviewer's diff check: the finalize carries only post-pipeline
  text; no finalize-content change anywhere in the diff.
- P14 → the live probe is cited in Context as operator evidence only; no
  acceptance item depends on a live run.
- FAILURE-MODE coverage → the reproduction's hostile input (a full answer
  followed by a trailing tool call and an empty final call) and the
  discriminator leg's coalesced ordinary update.
- acceptance item 6 → repo-root `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: the content-preserving finalize (a spec amendment
surfaced to the driver, P13); any sanitizer/audit change; the tool
protocol's fail-closed display semantics; the batch degrade's shape and
silence; ChatAgent's already-logging catch arms; any eval-lane file; any
spec edit. No pre-existing test method is modified — `test_plan.modifies`
is empty; the new legs' rig setup (the explicit 600 ms floor and the
streaming-sequences fixture) is additive.

## Census

Class-scoped: **paths that can replace still-displayed live text at the
terminal.** Re-runnable:
`grep -n 'terminate(\|finalizeInPlace\|completeDelivered\|publishStageText\|publishLiveText' infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java`.
Rows (verified at draft time):

| Site | Disposition |
|---|---|
| `terminate` → `finalizeInPlace` (:355-377) | **Guarded by this ticket's invariant legs** (the finalize path itself unchanged; the invariant is enforced upstream at the revert) |
| `terminateAbandoned` (:336-340) → FAILED text | **Unchanged** — the M1-334 drain finalizes a placeholder whose operation abandoned; live text had been published only by an operation that then ran to its own terminal; out of the observed class |
| `fail` (:290-293) | **Unchanged** — the router's failure arm; the streamed-audit-failure reroute (ChatAgent.java:506-508) lands here after an audit-write abort, before any live text was lawfully publishable without its audit trail |
| `publishStageText` / `publishLiveText` (:132-156) | **FIX** — the forced-revert shape rides here (stage text gains the forced variant used by the protocol revert; live text unchanged) |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-958-long-stream-finalize-degrade.md
```
