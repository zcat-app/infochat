---
id: M1-795
title: "Wire the empty-body guard into live delivery"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  StageProgressNotifierTest#deliverFreshRefusesAnEmptiedLlmAuthoredBody
  (to-be-written) — M1-794's guard seam OutboundDelivery.deliverLlmReply
  (OutboundDelivery.java:147-154) has NO production caller (grep over
  infochat-provider/src/main returns only the definition); the live
  LLM-authored fresh-send leg StageProgressNotifier.deliverFresh
  (:289-292, only callers SummaryCommandHandler.java:644,647 — /summary
  section bodies embed LLM-authored prose) still routes through plain
  deliver, so an emptied body on that leg ships as an empty message. The
  test feeds a blank body into deliverFresh and asserts refusal (WARN, no
  adapter send); today it ships. `start` writes the test and runs it RED
  before any fix code (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/empty-body-live-delivery-wiring.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/StageProgressNotifierTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    THE PLACEHOLDER-FINALIZE LEG and the chat-path substitution — M1-796
    owns those; this ticket wires only the placeholder-free fresh-send leg
    where refusal cannot dangle a D31 placeholder.
  - >-
    OutboundDelivery.deliver, terminate's no-handle leg
    (StageProgressNotifier.java:353), sendReply (InboundRouter.java:1555),
    and the group paths (deliverToGroup / deliverSequenceToGroup) — the
    deterministic-empty exemption (P1) and the Census dispositions below;
    none gains a refusal.
  - >-
    THE InboundRouterChatModeIT suite-isolation fix — M1-796 owns it
    (test-only setUp change, analysis P8); this ticket touches no IT.
  - >-
    CHANGING any sanitize() pass, the closed list, or
    LlmOutputSanitizerPostconditionTest (family P11/P10; M1-790 touches
    the same pin file in flight — respected by non-overlap).
acceptance:
  - StageProgressNotifierTest.deliverFreshRefusesAnEmptiedLlmAuthoredBody passes — REPRODUCTION (written and run RED at start).
  - StageProgressNotifierTest.deliverFreshDeliversANonBlankBodyUnchanged passes — FAILURE-MODE (P1): a non-blank /summary section body routes through the seam to the same deliver() behavior as today (the adapter send happens exactly once), and a deliberately empty body through plain deliver() still ships — the deterministic command surface (docs/spec/llm.md §Failure handling) never gains a refusal; M1-794's pins (OutboundDeliveryTest.emptyBodyIsRefusedNotShipped, emptyBodyRefusalLogsRatherThanSending) run UNCHANGED.
  - mvn -B -pl infochat-provider -am verify is green
  - mvn verify from repo root is green (§5 full-suite gate; the suite-isolation interaction is P8 of the analysis)
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/StageProgressNotifierTest.java (new methods)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/llm.md §Failure handling (recap)
---

# M1-795: Wire the empty-body guard into live delivery

## Context

M1-794 shipped the empty-body refusal seam
`OutboundDelivery.deliverLlmReply` but — deliberately, per its own
clarity note — wired no production caller; its round-1 review's
RECOMMENDED-NEW-TICKET (quoted verbatim in the merged ticket,
M1-794-empty-body-delivery-guard.md:155-168) is this ticket's source.
This ticket wires the seam into the one live LLM-authored delivery leg
where refusal is the correct semantics: the placeholder-free fresh send
`StageProgressNotifier.deliverFresh`, whose only callers emit /summary
section bodies bearing LLM-authored prose. The chat path's placeholder
leg is M1-796's, not this ticket's. Shared analysis: `analysis_ref:`.

## Root cause

`deliverFresh` (StageProgressNotifier.java:289-292) calls
`outboundDelivery.deliver(...)` directly, bypassing the M1-794 seam; a
body that sanitized to empty on that leg is shipped as an empty message.
Verified: grep for `deliverLlmReply` over infochat-provider/src/main
returns only the definition (OutboundDelivery.java:147); the only callers
are tests (OutboundDeliveryTest.java:398,419).

## Pitfalls

Numbered consistently with the analysis document.

- P1: the deterministic-empty exemption must survive — wiring placed too
  low (guarding `deliver`, `terminate`, or `sendReply`) subjects
  deterministic command output to the refusal, violating the exemption
  (security.md §LLM output sanitizer:360-361; docs/spec/llm.md §Failure
  handling surface). Only `deliverFresh` — whose bytes are LLM-prose-
  bearing /summary sections — is rerouted.
- P2: refusal must never reach the placeholder-finalize leg (dangling D31
  placeholder, messaging.md §Progress notifications:220-222) — this
  ticket touches no placeholder path; M1-796 owns that leg with
  substitution.
- P8: full-suite green is the gate — the analysis's P8 suite-isolation
  record (InboundRouterChatModeIT.chatModeDispatchesToAgent fragility,
  .scratch/tick-repro-M1-795-crosscheck.log:4449-4465) is fixed test-only
  by M1-796's setUp corpus-clearing, not here (§1).

## Approach

- **Files to touch:** the two in `files_scope`.
- **Steps, in order:**
  1. Write the reproduction test in `StageProgressNotifierTest`, run RED.
  2. Route `deliverFresh` through `outboundDelivery.deliverLlmReply`
     (one-line change at StageProgressNotifier.java:291); the refusal
     (WARN + null, no transport call) is the seam's existing, M1-794-
     approved behavior — this ticket does not re-design it.
  3. Add the non-blank failure-mode test (acceptance item 2).
- **Controls to preserve (§10):** the `](`-free adjacency guarantee
  (deliverLlmReply delegates to `deliver`, which neutralizes —
  OutboundDelivery.java:153,142-143); the §6.12 metrics emission inside
  `execute` (unchanged — same code path); `terminate`, `updateInPlace`,
  and every other caller of `deliver` (untouched); M1-794's
  OutboundDeliveryTest pins (unchanged).
- **Pitfall→mitigation:** P1→step 2 touches `deliverFresh` only;
  P2→no placeholder method is modified; P8→acceptance item 4.

## Definition of done

The reproduction and failure-mode tests pass; the deterministic command
surface and every M1-794 pin run unchanged; provider-module and repo-root
verify are green.

## Verification

- P1 → `StageProgressNotifierTest.deliverFreshDeliversANonBlankBodyUnchanged`
  plus M1-794's `OutboundDeliveryTest` pins UNCHANGED — FAILURE-MODE:
  feeds a non-blank body and asserts exactly one adapter send; a
  deliberately empty deterministic body through plain `deliver` still
  ships — the refusal never touches the deterministic surface.
- P2 → `git diff` shows no change to `terminate`, `complete`,
  `completeDelivered`, `fail`, or `finalizeInPlace` — no placeholder path
  is rerouted.
- P8 → `mvn verify` from repo root.
- acceptance item 1 → the reproduction test — FAILURE-MODE: feeds a blank
  body into `deliverFresh` and asserts the body does not reach the
  adapter (no send) and the seam's WARN fires; non-vacuous by mutation:
  reverting step 2's routing fails it.

## Out-of-scope

Named in `out_of_scope`: the placeholder-finalize leg and chat-path
substitution (M1-796); `deliver`, `terminate`'s no-handle leg,
`sendReply`, and the group paths (P1 / Census dispositions); the
ChatModeIT suite-isolation fix (M1-796, test-only); any sanitize()-pass,
closed-list, or postcondition-pin change. No pre-existing test is
modified.

## Census

Class: every live delivery leg that can carry LLM-authored text.
Re-runnable:
`grep -rn 'outboundDelivery\.\(deliver\|deliverToGroup\|deliverSequenceToGroup\|finalizeInPlace\)\|progressNotifier\.\(complete\|completeDelivered\|deliverFresh\)' infochat-provider/src/main`.

| Leg | Site | Disposition |
| --- | ---- | ----------- |
| Chat terminal, placeholder finalize | StageProgressNotifier.java:339 via InboundRouter.java:1374 | fix: M1-796 (substitution at the source) |
| Chat terminal, no-handle fresh send | StageProgressNotifier.java:353 | out-of-scope: unreachable-blank after M1-796 (chat degrades upstream; /summary first sections embed deterministic fields, ClusterBlockRenderer.java:152-194), and refusal here would be user silence (analysis option B, rejected) |
| /summary section fresh sends | deliverFresh, SummaryCommandHandler.java:644,647 | FIX: this ticket |
| /summary first-section finalize | SummaryCommandHandler.java:642 → terminate:339 | out-of-scope: sections embed deterministic labeled fields; cannot be blank |
| Group digests | DigestDelivery.java:243, DigestWorker.java:380, DigestRetryService | out-of-scope: same renderer guarantee; a blank-refusal on the group path would interact with the bot-removed permanent-failure counter (OutboundDelivery.java:194-220) — a separate design if ever wanted |
| Placeholder/stage publishes + updateInPlace | StageProgressNotifier.java:158, :238, :182 | out-of-scope: D43 bundle stage strings only |
| sendReply (command replies, auto-compress notice) | InboundRouter.java:1555 | out-of-scope: deterministic surface, P1 exemption |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-795-wire-empty-body-guard-live-delivery.md
```
