---
id: M1-796
title: "Substitute an emptied chat reply before delivery"
status: done
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  EmptyLlmReplyDeliveryIT#aMarkersOnlyReplyIsNeverDeliveredEmptied
  (restored in-tree at start from .scratch/parked-for-M1-795/, with the
  authorized P4 callCount assertion — acceptance item 1) —
  a markers-only LLM reply (sanitize() returns "", pinned by
  LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions)
  is delivered on the live chat path today: the placeholder finalizes with
  "\n\n"+provenance-notice (normal turn) or "" (M1-618 clarify turn).
  Run RED on main, exit=1: "expected: not equal but was: <\n\nNot based
  on your feed posts; answered from general knowledge.>"
  (.scratch/tick-repro-M1-795-red2.log:4467-4516). RED re-run at start
  (.scratch/tick-repro-M1-796-red1.log), same failure.
analysis_ref: docs/plan/m1/tick-analysis/empty-body-live-delivery-wiring.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/EmptyLlmReplyDeliveryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
  - docs/spec/llm.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    THE FRESH-SEND GUARD WIRING — M1-795 owns deliverFresh; this ticket
    touches no StageProgressNotifier/OutboundDelivery code (the
    placeholder finalize is never refused; the substitute reaches it as an
    ordinary non-blank terminal).
  - >-
    CHANGING any sanitize() pass, the closed list, the translation
    pipeline's fallback (M1-793, done), or
    LlmOutputSanitizerPostconditionTest (family P11/P10; the ""-return pin
    stays true at the transform level and is NOT flipped).
  - >-
    ANY InboundRouterChatModeIT change beyond the setUp corpus-clearing
    (P8) — the fix is the two-row DELETE plus its comment; no assertion,
    body, or other-method edit.
acceptance:
  - EmptyLlmReplyDeliveryIT.aMarkersOnlyReplyIsNeverDeliveredEmptied passes — REPRODUCTION (restored from .scratch/parked-for-M1-795/ and run RED at start), with the P4 addition authorized here: an assertion that testLlmProvider.callCount() >= 1, so a breaker-open degrade cannot pass the test vacuously (GREEN-BY-DEGRADE hazard).
  - ChatAgentTest.aReplyThatSanitizesToEmptyDegradesLikeAnAssistantFailure passes — FAILURE-MODE (docs/spec/security.md §Failure handling): feeds a markers-only reply into a turn and asserts the degrade shape — reply is the localized error.chat.unavailable bundle string, pendingCommit is null (the turn is discarded: no chat_session advance, no chat_memory write), provenanceNotice is null.
  - ChatAgentTest.anEmptiedReplyWithAMatchedHelpBlockStillDeliversTheDeterministicBlock passes — FAILURE-MODE (P5, security.md §LLM output sanitizer delivery-ordering contract path (a)): a turn whose prose sanitizes to empty but whose step-3c probe matched still delivers the deterministic block, not the degrade string.
  - InboundRouterChatModeIT.chatModeDispatchesToAgent passes deterministically in isolation (P8) — probe: `./mvnw -B -pl infochat-provider -am verify -Dit.test=InboundRouterChatModeIT -Dfailsafe.failIfNoSpecifiedTests=false` is green; this run shape is RED on main today (.scratch/tick-repro-M1-795-crosscheck.log:4449-4465). The fix is setUp-only: clear the 'topic'/'command_intent' doc_embedding rows so the step-3c help probes deterministically miss, plus a short comment saying why — the exact-body assertion no longer depends on suite ordering (breaker state / corpus presence).
  - docs/spec/llm.md §Failure handling (recap) amendment rides this diff (M1-779 precedent) — probe: `grep -n 'sanitiz' docs/spec/llm.md` shows the new emptied-reply degrade rule inside the amended section (a chat reply the output sanitizer reduces to empty degrades like a chat-agent failure: localized bundle friendly error, turn discarded, placeholder finalized with that string, never blank and never the bare provenance notice), and the amended prose asserts rule-text only — no dates, ticket IDs, or report citations (§12); the amended behavior is pinned by ChatAgentTest.aReplyThatSanitizesToEmptyDegradesLikeAnAssistantFailure above, and the exact wording goes to the user for approval before it lands.
  - mvn -B -pl infochat-provider -am verify is green
  - mvn verify from repo root is green (§5; the restored IT's corpus-clearing setUp is retained — analysis P7/P8)
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/EmptyLlmReplyDeliveryIT.java (restored from parked)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java (new methods)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java (setUp-only: the corpus-clearing DELETE + comment — authorized by acceptance item 4)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/messaging.md §Progress notifications
  - docs/spec/llm.md §Failure handling (recap)
reviews:
  - round: 1
    date: 2026-08-08
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "7 files changed, 198 insertions(+), 11 deletions(-)"
    findings: "0 rework items, 0 critical/high; 4 candidate findings falsified-and-dropped (no-log degrade is covered by the LLM_OUTPUT_SANITIZED audit row; corpus-clearing setUp cannot break sibling ITs; translator-skip on blank operand is the amended rule's promised shape; blank translation cannot reach the degrade past sanity check (c))"
    verdict_file: .scratch/tick-review-M1-796-r1.txt
---

# M1-796: Substitute an emptied chat reply before delivery

## Context

A markers-only LLM chat reply sanitizes to "" (pinned, P8 of the family
analysis) and is delivered on the live path today — as blank lines plus
bare provenance boilerplate on a normal turn, as a literally empty message
on an M1-618 clarify turn (red-log verified,
.scratch/tick-repro-M1-795-red2.log:4463-4472). The placeholder-finalize
leg cannot be REFUSED — the user is already looking at the D31 "Working
on it..." placeholder and messaging.md §Progress notifications guarantees
placeholders are never left dangling — so the emptied body must be
SUBSTITUTED. This ticket makes that decision and implements it at the
reply source; it also fixes, test-only, the pre-existing
InboundRouterChatModeIT suite-isolation defect its own repo-root verify
gate is hostage to (user decision 2026-08-08). M1-795 wires the remaining
fresh-send leg. Shared analysis: `analysis_ref:`.

## Root cause

ChatAgent ships the post-sanitize reply unconditionally: for an `en`
scope `reply = approved` verbatim (ChatAgent.java:566-571) and neither
the router composition (InboundRouter.java:1955-1962) nor the placeholder
terminal (StageProgressNotifier.java:339/:353) refuses an empty body.
The M1-793 English-with-note fallback covers only the translation
pipeline (non-en scopes); the en chat path has no substitution, and no
code path discards the turn when the body empties.

The suite-isolation defect (P8) shares the harness: ChatModeIT's
`chatModeDispatchesToAgent` exact-body assertion
(InboundRouterChatModeIT.java:112-116) does not expect the ChatAgent
step-3c help block, and whether that block fires depends on suite
ordering (breaker state / corpus presence) — so the test fails in
isolation (.scratch/tick-repro-M1-795-crosscheck.log:4449-4465) and
passes in the full suite by accident.

## Pitfalls

Numbered consistently with the analysis document.

- P2: the placeholder finalize is never refused — refusal strands the D31
  placeholder (messaging.md:220-222). The substitute is computed upstream
  and reaches the finalize as an ordinary non-blank terminal.
- P3: substituting at the router would persist the emptied turn — the
  pending commit is stashed inside `dispatchChat`
  (InboundRouter.java:1947-1949) before the body could be inspected, and
  security.md §Failure handling requires the turn discarded with no
  `chat_session`/`chat_memory` write. The degrade therefore lives INSIDE
  ChatAgent with a null `pendingCommit`, mirroring the refusal intercept
  (ChatAgent.java:555-557).
- P4: GREEN-BY-DEGRADE — a breaker-open turn returns the same unavailable
  string without reaching the LLM (ChatAgent.java:358); the restored IT
  must assert `testLlmProvider.callCount() >= 1` (TestLlmProvider.java:79)
  alongside the body assertions.
- P5: the empty check must be evaluated AFTER the step-9b help-block
  composition (ChatAgent.java:591-614) — a check on `approved` upstream
  would degrade turns whose deterministic topic/command block matched,
  suppressing lawful deterministic delivery (security.md:426-440 path
  (a)).
- P6: both emptied shapes die at the same point — the degrade returns a
  null notice, so the router ships the substitute verbatim
  (InboundRouter.java:1955-1956) instead of composing "\n\n"+notice.
- P7: harness mask — StubEmbeddingProvider's single unit vector makes the
  step-3c probes match every turn; the restored IT's corpus-clearing
  setUp is retained verbatim so the emptied body travels unmasked.
- P8: suite isolation — the ChatModeIT leg is FIXED by this ticket:
  ChatModeIT's setUp gains the identical corpus-clearing DELETE (plus a
  comment saying why), so `chatModeDispatchesToAgent` no longer depends
  on boot-built corpora surviving to its turn. Verified before filing
  (full read of InboundRouterChatModeIT.java): no other test in the class
  reads the help corpora — the other nine assert config resolution, the
  body cap, probation, the unavailable error (an early-return path that
  never reaches step 9b), rate-cap call COUNTS (never body bytes),
  chat_session/anchor rows — so the DELETE cannot break a sibling method.
  The fix must stay setUp-only: touching the assertion would be changing
  the test to match behavior (§8).
- P9: the spec amendment is rule-text only and user-approved (§12); no
  `infochat.*` token may appear in it (DocumentedConfigKeyParityTest).

## Approach

- **Files to touch:** the five in `files_scope`.
- **Steps, in order:**
  1. Restore the parked IT in-tree (adding the P4 callCount assertion —
     authorized by acceptance item 1), run RED.
  2. ChatModeIT setUp fix (P8, test-only): add the two-row
     `DELETE FROM doc_embedding WHERE doc_kind IN ('topic', 'command_intent')`
     to `setUp`, with a short comment naming the step-3c mask as the
     reason — mirroring the parked IT's setUp. Run the acceptance-item-4
     isolation probe; it flips RED→GREEN on this change alone.
  3. ChatAgent: after the step-9b composition, if the composed `reply`
     is blank, return
     `new ChatTurnResult(bundleLoader.get(BundleKeys.ERROR_CHAT_UNAVAILABLE, scopeLanguage), null, null)`
     — the exact degrade shape of the refusal intercept (:555-557):
     localized bundle string (D43), null commit, null notice. Skip the
     step-9 translator call when `approved` is already blank (a blank
     operand never reaches TranslationPipeline; the translation fallback
     is M1-793's decided surface and stays untouched).
  4. Add the two ChatAgentTest failure-mode tests (acceptance items 2-3).
  5. Draft the llm.md §Failure handling (recap) amendment (rule text
     only) and take the exact wording to the user (§12).
- **Controls to preserve (§10):** the sanitize() call and its audit rows
  (ChatAgent.java:540 — untouched); the M1-607 delivery-gated commit
  (the degrade's null `pendingCommit` means `takePendingChatCommit()`
  returns null and nothing persists — InboundRouter.java:1401-1403,
  unmodified); the D35 `/stop` stopped terminal (the degrade returns a
  non-null reply, never conflated with cancellation); the M1-665/666
  help-block delivery (P5 placement); the M1-617 notice composition on
  non-degrade turns (byte-identical; ChatAgentProvenanceTest unchanged);
  the `](`-free chokepoint guarantee (downstream, untouched);
  ChatModeIT's ten test methods and every assertion in them (the P8 fix
  is setUp-only).
- **Pitfall→mitigation:** P2→substitute upstream, never refuse the
  finalize; P3→step 3's null commit; P4→acceptance item 1's callCount
  assertion; P5→step 3's post-9b placement + acceptance item 3; P6→null
  notice in the degrade shape; P7→step 1 retains the corpus-clearing
  setUp; P8→step 2's setUp-only fix + acceptance item 4's isolation
  probe; P9→step 5.

## Definition of done

The restored IT passes with the callCount assertion; both failure-mode
ChatAgent tests pass; ChatModeIT passes in isolation under the named
probe; the amendment's wording is user-approved and landed;
provider-module and repo-root verify are green.

## Verification

- P2 → the IT's await on a finalized terminal: the placeholder finalizes
  (never dangles) with a non-blank body.
- P3 → `ChatAgentTest.aReplyThatSanitizesToEmptyDegradesLikeAnAssistantFailure`
  — FAILURE-MODE: feeds a markers-only reply and asserts null
  `pendingCommit` and null notice; the IT drains in-flight tasks with no
  `chat_session` row for the turn.
- P4 → the IT's `testLlmProvider.callCount() >= 1` assertion — a
  breaker-open degrade never reaches the provider, so it cannot pass.
- P5 → `ChatAgentTest.anEmptiedReplyWithAMatchedHelpBlockStillDeliversTheDeterministicBlock`
  — FAILURE-MODE: feeds an emptied-prose turn with a matched topic and
  asserts the deterministic block ships, not the degrade string.
- P6 → the IT's `assertNotEquals("\n\n" + notice, body)` (normal shape)
  plus the unit test's null-notice assertion (clarify shape) — the bare
  boilerplate composition must never ship.
- P7 → the restored IT keeps its corpus-clearing setUp; without it the
  3c mask hides the emptied reply and the IT cannot go RED at `start`.
- P8 → the isolation probe
  `./mvnw -B -pl infochat-provider -am verify -Dit.test=InboundRouterChatModeIT -Dfailsafe.failIfNoSpecifiedTests=false`
  — RED on main today, GREEN after the setUp fix; repo-root `mvn verify`
  stays green (the DELETE matches the parked IT's already-accepted
  mechanism).
- P9 → `grep -n 'infochat\.' docs/spec/llm.md` over the amended hunks
  returns only real keys (DocumentedConfigKeyParityTest via `mvn verify`),
  and `grep -nE 'M1-[0-9]+|2026-' docs/spec/llm.md` over the amended
  hunks returns nothing — the amendment is rule-text only (§12), landed
  only on the user's explicit yes.
- acceptance item 1 → the restored reproduction IT.
- acceptance items 2-3 → the named ChatAgentTest methods.
- acceptance item 4 → the named isolation probe.
- Non-vacuity: removing the ChatAgent blank check fails the IT; moving
  the check before step 9b fails acceptance item 3's test; reverting the
  ChatModeIT setUp DELETE re-reds the isolation probe.

## Out-of-scope

Named in `out_of_scope`: the fresh-send guard wiring (M1-795); any
sanitize()-pass, closed-list, translation-fallback, or postcondition-pin
change; any ChatModeIT change beyond the setUp corpus-clearing. Two
deliberate, authorized test modifications ride this diff: the restored
parked IT gains exactly one assertion beyond its parked form (the P4
`callCount` check — acceptance item 1), and InboundRouterChatModeIT's
`setUp` gains the corpus-clearing DELETE plus its comment (P8 —
acceptance item 4; no ChatModeIT test method or assertion is touched).
No other pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-796-empty-body-placeholder-substitution.md
```
