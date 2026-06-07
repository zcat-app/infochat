---
id: M1-212
title: "ProgressNotifier pipeline: implement minimally, defer by amendment, or remove"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
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
