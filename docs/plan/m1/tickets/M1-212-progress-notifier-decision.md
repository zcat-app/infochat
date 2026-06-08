---
id: M1-212
title: "ProgressNotifier pipeline: implement minimally, defer by amendment, or remove"
status: pending
created: 2026-06-07
last_updated: 2026-06-08
blocked_by: []
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressNotifier.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressStage.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingException.java
  - docs/spec/messaging.md
  - docs/design/06-messaging.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingSpisLoadTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/spi/AllSpisLoadIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - setTyping behavior on adapters whose capability flag is false — M1-204 makes SimpleXAdapter.setTyping a strict no-op; this ticket's outcome must not contradict that (a typing pulse simply does nothing on such adapters)
  - maxInflightSends/maxSendsPerSecond enforcement and the §6.3.7 bounded inbound queue — M1-205's decision; the notifier's update coalescing (minEditInterval) is a different mechanism and the only rate concern in scope here
  - message-edit support inside the adapters (update/finalizeMessage implementations) — already shipped SPI surface; only the provider-side notifier and its wiring are in question
  - digest and chat-agent wiring beyond the single chosen surface, if the implement direction is picked minimal — follow-up wiring is named, not built
acceptance:
  - "A decision is recorded and applied, consistent with M1-204's setTyping no-op and M1-205's §6.3.7 adjudication (if either is unimplemented at start, record the ordering assumption instead of contradicting them), one of: (a) IMPLEMENT MINIMAL — a concrete ProgressNotifier lands and at least one long-running surface (/summary is the natural first) publishes stage events through it, honoring docs/spec/messaging.md §Progress notifications steps 1–4: placeholder send with captured handle, typing on where the adapter supports it, coalesced update rendering, and \"On terminal COMPLETED / FAILED, calls finalize(handle, text) and turns off typing. Both are guaranteed via try/finally — placeholders are never left dangling.\" — each step pinned by a named test; (b) DEFER BY AMENDMENT — docs/spec/messaging.md §Progress notifications is amended to record the v1 ship state (surface defined, wiring deferred), ratifying design 06-messaging's recorded keep-as-seam verdict and naming where the wiring lands later; or (c) REMOVE — the interface, ProgressStage, and their load-test pins are deleted with the spec section rewritten accordingly (the deepest amendment; D31 is revisited in the decision log)"
  - "If direction (a): per docs/spec/messaging.md §Progress notifications — \"**User input is never interpolated into progress strings**\" and \"Stage strings are template-parameterized only with **deterministic, sanitized scalar values** (post counts, controlled-vocabulary tag names, fixed enum labels). Free-form user-authored text (custom personal tags, free-form chat) is **never** interpolated, even via a 'safe' placeholder.\" — a named test proves a stage string rendered for a request carrying user-authored text contains none of it, and stage strings resolve from the deterministic localization bundle (D43)"
  - "Whichever direction: after this ticket no document claims a wired progress pipeline that does not exist, and no SPI surface exists that neither code nor an explicit deferral note accounts for (today: zero implementations, zero consumers, two load-tests pinning the interface's existence)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingSpisLoadTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/spi/AllSpisLoadIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Progress notifications
decision_refs:
  - D31
  - D43
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations:
  - date: 2026-06-08
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL
      test_plan.modifies lists MessagingSpisLoadTest.java as a pre-existing
      test that will be modified. The ticket body has no "Authorized test
      changes" section documenting what the modification is and what the new
      expected behavior will be. The ticket must name, for each direction
      (a/b/c), what happens to MessagingSpisLoadTest.java and (if modified)
      what the new expected behavior is.
revisions:
  - date: 2026-06-08
    reason: "clarity-fail rework — TEST-CHANGES-AUTHORIZED blocker: test_plan.modifies listed MessagingSpisLoadTest.java with no body authorization naming per-direction (a/b/c) what happens to it and the new expected behavior. Add an 'Authorized test changes' section covering both load tests (MessagingSpisLoadTest + AllSpisLoadIT, the latter added to test_plan.modifies since direction (c) edits it too) for all three directions; add a 'Direction chosen' placeholder to the body for review orientation (clarity WARN)."
    prior_values: |
      status: pending
      test_plan.modifies:
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingSpisLoadTest.java
      (body had no "Authorized test changes" section; no "Direction chosen" placeholder)
---

# M1-212: ProgressNotifier — implement minimally, defer by amendment, or remove

## Context

Unified finding A5 (`deep-code-review/v2/UNIFIED.md` §2): zero
`implements ProgressNotifier` and zero consumers anywhere
(re-verified 2026-06-07 — the only references are the interface, the
ProgressStage enum, a MessagingException javadoc mention, and two
SPI load tests).

Re-grounding found prior art the audit did not surface: design
06-messaging already records a verdict on exactly this — its SPI-audit
section "(c) ProgressNotifier — verdict: **keep-as-seam**" reads
"Zero implementations therefore means an unshipped v1 surface, not
dead code. The interface is retained as the v1 seam; wiring a concrete
notifier into the provider handlers is follow-up work, and removing
the surface would require a spec amendment." The spec section
(decision D31), however, is written in the present tense — it promises
a pipeline v1 does not have. The decision is therefore three-way:
build the minimal pipeline now, ratify the seam by spec amendment, or
remove the surface. User call at start.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Direction chosen

To be recorded at implementation start (the user call this ticket exists to
make): one of `a` (IMPLEMENT MINIMAL), `b` (DEFER BY AMENDMENT), `c` (REMOVE).
A reviewer orients on this line before checking the conditional acceptance.

## Authorized test changes

Both load tests pin the ProgressNotifier interface and the ProgressStage enum
today. What happens to them is direction-dependent; the two `test_plan.modifies`
entries fire **only under direction (c)** — under (a) and (b) both load tests
are preserved unchanged.

- `MessagingSpisLoadTest.java` (infochat-messaging-adapter) currently asserts
  `progressNotifierIsLoadableInterface` (ProgressNotifier is an interface) and
  `progressStageIsLoadableEnumWithSpecMandatedValues` (ProgressStage is an enum
  with exactly seven values).
- `AllSpisLoadIT.java` (infochat-provider) lists `ProgressNotifier` among its
  seven `INTERFACE_FQNS`, `ProgressStage` among its two `ENUM_FQNS`, and asserts
  the cross-module SPI surface totals fourteen types.

Per direction:

- **(a) IMPLEMENT MINIMAL** — interface and enum are kept. Both load tests are
  **unchanged** (the pinned surface still exists). New behavior is proven by
  **added** tests under `infochat-provider/src/test/.../command` (not by editing
  the load tests): placeholder send with captured handle, typing-on where the
  adapter supports it, coalesced update rendering, and finalize + typing-off via
  try/finally (spec steps 1–4), plus the injection-prevention assertion of
  acceptance item 2. No expected-behavior change to either load test.
- **(b) DEFER BY AMENDMENT** — surface kept, no code change. Both load tests are
  **unchanged**.
- **(c) REMOVE** — ProgressNotifier and ProgressStage are deleted, so both load
  tests are **modified** to drop the now-absent pins:
  - `MessagingSpisLoadTest.java`: delete `progressNotifierIsLoadableInterface`
    and `progressStageIsLoadableEnumWithSpecMandatedValues`. New expected
    behavior: the smoke test pins only the surviving messaging SPI types
    (`MessagingAdapter`, `TranslationProvider` interfaces; `MessageHandle`,
    `CapabilityFlags` records) — ProgressNotifier/ProgressStage are no longer
    loadable and are no longer asserted.
  - `AllSpisLoadIT.java`: remove `app.zcat.infochat.messaging.ProgressNotifier`
    from `INTERFACE_FQNS` and `app.zcat.infochat.messaging.ProgressStage` from
    `ENUM_FQNS`; change the total-count assertion (and the javadoc count) from
    fourteen to twelve. New expected behavior: the umbrella IT pins exactly
    twelve cross-module SPI types, none of them ProgressNotifier or ProgressStage.

## Notes

- Source: `UNIFIED.md` §3 T31 leg (b) under `deep-code-review/v2/`
  (kimi-folder arch F8).
- Cross-ticket wiring (mandated by the batch prompt): M1-204
  (SimpleXAdapter.setTyping no-op — supportsTypingIndicator is false
  there, so a typing pulse is a legitimate no-op on SimpleX) and
  M1-205 (§6.3.7 enforcement decision) must be named in the recorded
  decision; neither is contradicted by any direction above.
- If (a) is chosen, SummaryCommandHandler is the wiring surface —
  it is also in M1-183's files_scope (rate-cap wiring); serialize.
- The concrete notifier under (a) lives provider-side (new class in
  the provider module); the budget reserves room for it.
