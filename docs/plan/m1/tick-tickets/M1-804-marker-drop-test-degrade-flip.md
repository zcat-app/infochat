---
id: M1-804
title: "Flip the marker-drop test to the emptied-reply degrade"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  existing: ChatAgentRefusalInterceptionTest#aMarkerBearingRefusalLineIsDroppedBeforeItCanJoin
  is RED on main — probe `./mvnw -B -pl infochat-provider test
  -Dtest=ChatAgentRefusalInterceptionTest` fails at :129 with
  "expected: <> but was: <error.chat.unavailable>" (.scratch/M1-804-repro.log).
  The test asserts M1-790's blank-reply flip against M1-796's step-9c
  degrade (ChatAgent.java:618-624); nothing to write — the RED is the
  ticket's premise and the fix flips the stale assertion.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptionTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - ChatAgent.java and the step-9c degrade behavior itself — the degrade is
    the newer, spec-amended contract (docs/spec/llm.md §Failure handling
    (recap), llm.md:549-556); this ticket aligns the stale test, never the
    production code.
  - Any spec edit — llm.md:549-556 already carries the emptied-reply rule.
  - The sanitizer-level drop-wholesale pins (LlmOutputSanitizerTest,
    LlmOutputSanitizerPostconditionTest) — sanitizer OUTPUT may be empty;
    the degrade lives at ChatAgent composition, downstream.
  - ChatAgentRefusalInterceptTest and the assembled-marker route
    (aRefusalMarkerSurfacedOnlyBySanitizationDegradesTheTurn) — that path
    detects the marker and degrades to ERROR_CHAT_REFUSED; it is untouched
    by and orthogonal to this contradiction.
acceptance:
  - "ChatAgentRefusalInterceptionTest#aMarkerBearingRefusalLineIsDroppedBeforeItCanJoin passes with the degrade shape pinned: reply equals BundleKeys.ERROR_CHAT_UNAVAILABLE (the harness's bundle stub returns the key literal — observed in the reproduction's `but was: <error.chat.unavailable>`, same stub shape as :65), pendingCommit is null, and sessionPersistCalls stays 0 — the discard pin mirrors :67-70."
  - "The flipped method's assertion message and CONTRACT CHANGE comment state current truth — both halves: the marker-bearing line drops wholesale (M1-790's sanitizer contract stands) AND the emptied reply then degrades via ChatAgent's step 9c to the localized unavailable string — Verify: `grep -n 'unavailable' infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptionTest.java` hits the rewritten comment/assertion, and the stale 'nothing reaches the reader' wording is gone."
  - "The §Census enumeration re-runs clean — Verify: the two §Census grep commands return exactly the sites the table disposes (the second grep returns chat/ChatAgentRefusalInterceptionTest.java:129 alone), each with a disposition row."
  - "mvn verify from repo root is green — this failure is the only red on main today (deepseek's M1-799 verify: 1739 tests, 1 failure, this one), so the suite returns to full green."
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptionTest.java (the one flipped assertion + its message and comment)
  preserves:
    - all tests currently green on main — in particular the M1-796 degrade pins ChatAgentTest#aReplyThatSanitizesToEmptyDegradesLikeAnAssistantFailure and EmptyLlmReplyDeliveryIT#aMarkersOnlyReplyIsNeverDeliveredEmptied, and M1-790's sanitizer pin LlmOutputSanitizerTest#aMarkerBearingLineIsDroppedWholesaleNotExtractedAround
spec_refs:
  - docs/spec/llm.md §Failure handling (recap)
decision_refs:
  - D43
---

# M1-804: Flip the marker-drop test to the emptied-reply degrade

## Context

Main is red on exactly one test — a deterministic cross-ticket contract
contradiction, not flaky. M1-790 (d38cf9ac, "documented flips") rewrote
`aMarkerBearingRefusalLineIsDroppedBeforeItCanJoin` to assert the reply is
`""` when the scaffolding-marker strip drops the marker-bearing line
wholesale. M1-796 (9e9556d5, merged BEFORE M1-790) had already added
ChatAgent's step 9c (`ChatAgent.java:618-624`): any blank composed reply
degrades to the localized `error.chat.unavailable`, and its spec amendment
(docs/spec/llm.md §Failure handling (recap), llm.md:549-556) rules the
emptied reply is "never blank". The markers-only test input now hits 9c
before the assertion ever sees the blank string. The spec amendment is the
newer and authoritative contract, so the test side yields: the wholesale
drop stays pinned at the sanitizer level, and the turn-level expectation
becomes the degrade shape (D43 bundle string, discarded turn).

## Root cause

A semantic merge collision between two concurrently-developed tickets whose
diffs share no file: M1-790's verified-green tree predated M1-796's merge,
and the squash-merge landed it onto a main whose behavior its test suite
no longer described (process fix for the gate hole landed alongside this
ticket — tick-workflow §1/§5). M1-790's `""` assertion was correct against
the pre-9c code it verified; it went stale on arrival. Neither production
behavior is wrong: the sanitizer drops the line wholesale (M1-790) and the
emptied reply degrades (M1-796). Only the test's terminal expectation
contradicts the composition of the two.

## Census

Blank/empty-reply assertions at the chat-turn and sanitizer surfaces:

    grep -rn 'assertEquals("",' infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ infochat-provider/src/test/java/app/zcat/infochat/provider/llm/
    grep -rn 'assertEquals("", result.reply())\|reply()\.isBlank()\|reply()\.isEmpty()' infochat-provider/src/test/java/

| Site | Disposition |
|---|---|
| `chat/ChatAgentRefusalInterceptionTest.java:129` | fix (this ticket) |
| `llm/LlmOutputSanitizerTest.java:777,:779,:793,:803,:832` | out-of-scope: sanitizer-level output pins — an empty sanitizer result is correct; the degrade is downstream at ChatAgent composition |
| `llm/LlmOutputSanitizerPostconditionTest.java:276` | out-of-scope: same — sanitizer-level output pin |
| second grep | returns only :129 — no other delivered-reply blank assertion exists |

## Pitfalls

- P1: the wrong degrade key — the dropped-wholesale route never assembles
  the refusal marker, so this is NOT the ERROR_CHAT_REFUSED path
  (:56-71 covers the assembled-marker route and stays untouched). Assert
  `BundleKeys.ERROR_CHAT_UNAVAILABLE` — llm.md:549-556's "degrades like a
  chat-agent failure" is the unavailable string, not the refusal string.
- P2: do not "fix" by making prose survive the strip (e.g. adding a
  non-marker line to the input) — that silently retires the wholesale-drop
  coverage M1-790 pinned (its turn-level counterpart of
  LlmOutputSanitizerTest#aMarkerBearingLineIsDroppedWholesaleNotExtractedAround).
  The markers-only input stays; only the expected terminal shape changes.
- P3: keep the discard pin — the 9c degrade discards the turn (null
  commit, no persistence, mirroring the refusal intercept), so the flip
  must carry `assertNull(result.pendingCommit())` and
  `assertEquals(0, sessionPersistCalls)` or it weakens the discard
  guarantee while fixing the string.
- P4: same-module in-flight — M1-799 (infochat-provider) is in flight in a
  worktree; per workflow §1's module rule this ticket starts sequentially
  after it, never `--parallel`.

## Approach

- **Files to touch:** `files_scope` — one file, one method.
- **Steps, in order:**
  1. Flip the assertion to `BundleKeys.ERROR_CHAT_UNAVAILABLE` and add the
     discard pins (P3), mirroring the :65-70 assertion shape.
  2. Rewrite the assertion message and the CONTRACT CHANGE comment to both
     halves of current truth (acceptance item 2).
  3. Re-run the §Census greps; confirm the table still disposes every site.
  4. `scripts/verify-serialized.sh` — full suite, expecting a return to
     all-green.
- **Controls to preserve (§10):** no production code touched; no other
  test method, input, or sanitizer pin changes; the markers-only input
  stays markers-only (P2).
- **Pitfall→mitigation:** P1→step 1's key + acceptance item 1; P2→step 1
  keeps the input; P3→step 1's added pins; P4→start sequencing.

## Definition of done

The flipped test passes with the degrade shape (unavailable string, null
commit, zero persists); its comment tells both halves; the census is
disposed; the full suite is green end to end.

## Verification

- P1 → acceptance item 1's key assertion — the bundle stub returns the key
  literal (reproduction's `but was: <error.chat.unavailable>`), so a wrong
  key is a failing assertion, not a vacuous pass.
- P2 → the flipped method keeps the markers-only input (acceptance item 1
  names the method, not a new one); LlmOutputSanitizerTest#aMarkerBearingLineIsDroppedWholesaleNotExtractedAround
  stays green under the full-suite item.
- P3 → acceptance item 1's null-commit / zero-persist pins.
- P4 → no mechanical check; sequencing is the driver's at `/tick start`
  (workflow §1 module enumeration).
- Reproduction probe flips RED→GREEN on this diff alone.
- Non-vacuity: removing step 9c re-reddens the flipped test (the assertion
  would see `""` again).
- The M1-796 pins (ChatAgentTest#aReplyThatSanitizesToEmptyDegradesLikeAnAssistantFailure,
  EmptyLlmReplyDeliveryIT#aMarkersOnlyReplyIsNeverDeliveredEmptied) and the
  M1-790 sanitizer pin stay green — the diff touches one method of one
  test class, so their survival is the full-suite green.

## Out-of-scope

Named in `out_of_scope`: production behavior (9c stands), spec text
(already amended by M1-796), sanitizer-level pins, and the assembled-marker
refusal route. The pre-existing test modified here is
ChatAgentRefusalInterceptionTest#aMarkerBearingRefusalLineIsDroppedBeforeItCanJoin,
with the new expected behavior pinned in acceptance item 1 — this naming
authorizes the edit (engineering-rules §8). No other pre-existing test is
modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-804-marker-drop-test-degrade-flip.md
```
