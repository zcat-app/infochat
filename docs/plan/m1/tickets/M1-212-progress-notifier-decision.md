---
id: M1-212
title: "ProgressNotifier pipeline: implement minimally, defer by amendment, or remove"
status: pending
created: 2026-06-07
last_updated: 2026-06-08
blocked_by: []
files_budget: 20
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressNotifier.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressStage.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingException.java
  - docs/spec/messaging.md
  - docs/design/06-messaging.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingSpisLoadTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/spi/AllSpisLoadIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - setTyping behavior on adapters whose capability flag is false — M1-204 makes SimpleXAdapter.setTyping a strict no-op; this ticket's outcome must not contradict that (a typing pulse simply does nothing on such adapters)
  - maxInflightSends/maxSendsPerSecond enforcement and the §6.3.7 bounded inbound queue — M1-205's decision; the notifier's update coalescing (minEditInterval) is a different mechanism and the only rate concern in scope here
  - message-edit support inside the adapters (update/finalizeMessage implementations) — already shipped SPI surface; only the provider-side notifier and its wiring are in question
  - digest and chat-agent wiring beyond the single chosen surface, if the implement direction is picked minimal — follow-up wiring is named, not built
  - per-adapter adapterMin edit-interval exposure — v1 cadence uses a single system-wide systemFloor only; surfacing a per-adapter minimum (which would add a field to CapabilityFlags and ripple into every adapter constructor + test double) is a named follow-up, not built here. The spec's max(adapterMin, systemFloor) degrades to systemFloor in v1
acceptance:
  - "A decision is recorded and applied, consistent with M1-204's setTyping no-op and M1-205's §6.3.7 adjudication (if either is unimplemented at start, record the ordering assumption instead of contradicting them), one of: (a) IMPLEMENT MINIMAL — a concrete ProgressNotifier lands and at least one long-running surface (/summary is the natural first) publishes stage events through it, honoring docs/spec/messaging.md §Progress notifications steps 1–4: placeholder send with captured handle, typing on where the adapter supports it, coalesced update rendering, and \"On terminal COMPLETED / FAILED, calls finalize(handle, text) and turns off typing. Both are guaranteed via try/finally — placeholders are never left dangling.\" — each step pinned by a named test; (b) DEFER BY AMENDMENT — docs/spec/messaging.md §Progress notifications is amended to record the v1 ship state (surface defined, wiring deferred), ratifying design 06-messaging's recorded keep-as-seam verdict and naming where the wiring lands later; or (c) REMOVE — the interface, ProgressStage, and their load-test pins are deleted with the spec section rewritten accordingly (the deepest amendment; D31 is revisited in the decision log)"
  - "If direction (a): the dispatch contract must let /summary's handler own its outbound message lifecycle without the router double-sending. Today InboundRouter calls handler.handle(scope,text).text() and then UNCONDITIONALLY send()s that text (InboundRouter.java:766 → 663 → sendReply → 728), so a notifier that sends a placeholder + finalize()s its own message would yield a duplicate. Authorized resolution (files_scope widened to include InboundRouter.java and CommandHandler.java): a CommandHandler may signal that it has already delivered its reply via the notifier (e.g. CommandHandler.handle returns a @Nullable OutboundMessage and self-sending handlers return null, or an equivalent explicit signal), and InboundRouter performs NO send for that invocation. A named router test proves that when a self-sending handler is dispatched, replyTargets.get(adapterName).send(...) is called exactly once (the notifier's placeholder/finalize), never twice. Existing non-self-sending handlers (/help, asset commands, etc.) are unaffected — the router still sends their returned text. The concrete notifier lives at infochat-provider/.../messaging/StageProgressNotifier.java and resolves the bound adapter via the existing public AdapterRegistry.activatedAdapters() CDI seam keyed by InboundContext.adapterName()"
  - "If direction (a): the ProgressNotifier SPI must carry the terminal payload. publish(ScopeRef, ProgressStage) renders the non-terminal stages (STARTED, RETRIEVING, GENERATING, TRANSLATING, FINALIZING) onto the placeholder via coalesced update(); but the FINALIZED message is the actual /summary content, which publish() cannot carry. The interface therefore gains a payload-carrying terminal call (e.g. complete(ScopeRef, String finalText) for success and fail(ScopeRef) rendering a localized failure string) so finalizeMessage(handle, text) delivers the real summary, not a stage label. ProgressStage keeps all seven values (load tests still pin seven). A named test proves the finalized message body equals the handler's composed summary text"
  - "If direction (a): the seven ProgressStage values resolve to localized strings from the deterministic bundle (D43). Add one BundleKeys constant per stage (progress.* namespace) plus a matching entry in EVERY locale bundle (bundles/en.properties AND bundles/cs.properties) — the existing bundle-coverage test (BundleLoaderTest) fails if any locale is missing a declared key, so both locales gain all seven. No stage string interpolates user-authored text (acceptance item below)"
  - "If direction (a): update coalescing honors a minimum edit interval. v1 uses a single system-wide floor (systemFloor; design 06-messaging records minEditInterval=600ms) configured via application.properties; the spec's max(adapterMin, systemFloor) degrades to systemFloor because per-adapter adapterMin is NOT exposed in v1 (see out_of_scope). A named test proves two stage events closer together than the floor coalesce into at most one update() within the floor window"
  - "If direction (a): per docs/spec/messaging.md §Progress notifications — \"**User input is never interpolated into progress strings**\" and \"Stage strings are template-parameterized only with **deterministic, sanitized scalar values** (post counts, controlled-vocabulary tag names, fixed enum labels). Free-form user-authored text (custom personal tags, free-form chat) is **never** interpolated, even via a 'safe' placeholder.\" — a named test proves a stage string rendered for a request carrying user-authored text contains none of it, and stage strings resolve from the deterministic localization bundle (D43)"
  - "Whichever direction: after this ticket no document claims a wired progress pipeline that does not exist, and no SPI surface exists that neither code nor an explicit deferral note accounts for (today: zero implementations, zero consumers, two load-tests pinning the interface's existence)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
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
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — developer scope finding at implementation start (direction (a),
      IMPLEMENT MINIMAL). About to touch two paths outside files_scope:
      infochat-provider/.../messaging/InboundRouter.java and
      infochat-provider/.../messaging/CommandHandler.java.
      Root cause: the dispatch contract is return-one-text. InboundRouter
      calls handler.handle(scope,text).text() -> String body, then
      sendReply(...) UNCONDITIONALLY does replyTargets.get(adapterName)
      .send(new OutboundMessage(body)) (InboundRouter.java:766, 663, 728).
      A ProgressNotifier for /summary must own the message lifecycle
      (placeholder send -> update -> finalizeMessage; spec step 4
      "placeholders are never left dangling"). If the notifier finalizes
      its own message AND the handler still returns text, the router sends
      a DUPLICATE message. The only fixes (skip-send-on-blank, or a
      "handler already sent" SPI signal) require editing InboundRouter and
      ideally the CommandHandler SPI -- neither in files_scope. Adapter
      RESOLUTION is fine (AdapterRegistry.activatedAdapters() is a public
      CDI seam already used by 4 handlers); only the duplicate-send problem
      forces the out-of-scope edits.
revisions:
  - date: 2026-06-08
    reason: "clarity-fail rework — TEST-CHANGES-AUTHORIZED blocker: test_plan.modifies listed MessagingSpisLoadTest.java with no body authorization naming per-direction (a/b/c) what happens to it and the new expected behavior. Add an 'Authorized test changes' section covering both load tests (MessagingSpisLoadTest + AllSpisLoadIT, the latter added to test_plan.modifies since direction (c) edits it too) for all three directions; add a 'Direction chosen' placeholder to the body for review orientation (clarity WARN)."
    prior_values: |
      status: pending
      test_plan.modifies:
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingSpisLoadTest.java
      (body had no "Authorized test changes" section; no "Direction chosen" placeholder)
  - date: 2026-06-08
    reason: "budget-breach rework (direction (a) chosen, user picked 'one wide ticket'). Implementing a real ProgressNotifier into /summary is a full feature, not a wire-up. THREE scope discoveries at start, each pushing files outside the prior scope: (1) dispatch contract is return-one-text — InboundRouter UNCONDITIONALLY send()s handler.handle().text(), so a notifier owning a placeholder→finalize message double-sends; fix needs InboundRouter.java + a CommandHandler self-send signal (@Nullable return). (2) ProgressNotifier.publish(scope,stage) carries no payload, but the finalized message IS the summary content — the SPI needs a payload-carrying terminal call (complete/fail). (3) stage strings must resolve from the D43 localization bundle (acceptance + security), but zero progress.* keys exist — needs BundleKeys.java + bundles/en.properties + bundles/cs.properties (bundle-coverage test enforces both locales), plus a coalescing systemFloor (application.properties). Refine: files_budget 12→20; complexity medium→high (triggers plan-writer outline at re-start); round_cap 2→3; files_scope += StageProgressNotifier.java, InboundRouter.java, CommandHandler.java, BundleKeys.java, en/cs properties, application.properties, provider/messaging + provider/bundle test dirs; +acceptance items for the dispatch seam, SPI payload, bundle localization, and cadence floor. Per-adapter adapterMin deliberately deferred (out_of_scope) to avoid a CapabilityFlags ripple through every adapter constructor. Because complexity is now high, the budget-breach arm's 'stay in-progress on branch' is overridden (gate-preserving): status→pending, ticket committed to main, empty branch deleted, re-enter via /m1-tick start so clarity + plan-writer re-run against the rewritten ticket."
    prior_values: |
      files_budget: 12
      files_scope:
        - infochat-messaging-adapter/.../ProgressNotifier.java
        - infochat-messaging-adapter/.../ProgressStage.java
        - infochat-messaging-adapter/.../MessagingException.java
        - docs/spec/messaging.md
        - docs/design/06-messaging.md
        - infochat-provider/.../command/SummaryCommandHandler.java
        - infochat-messaging-adapter/.../MessagingSpisLoadTest.java
        - infochat-provider/.../spi/AllSpisLoadIT.java
        - infochat-provider/.../provider/command
      (acceptance had no dispatch-contract / router-double-send item)
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

**(a) IMPLEMENT — full feature** (the "minimal" framing was retired during the
2026-06-08 budget-breach refine; see `revisions:`). Recorded at implementation
start (user call). A concrete provider-side `ProgressNotifier`
(`StageProgressNotifier`) lands and `/summary` (`SummaryCommandHandler`)
publishes stage events through it, honoring `docs/spec/messaging.md` §Progress
notifications steps 1–4. Rationale: the SPI was created in M1-007c with the
explicit expectation that a follow-up "Tier-3 progress-notifier wiring ticket"
would implement it; that wiring ticket was never written (M1-162 noted the
zero-implementation gap and recorded keep-as-seam, deferring the call to here).
The spec mandates the surface in present tense; (a) makes the spec true rather
than rewording it down. M1-183 (shared `SummaryCommandHandler` scope) is `done`,
so the serialize constraint is satisfied. Digest and chat-agent wiring remain
named follow-ups (out_of_scope).

This is a multi-part change (~18-20 files), hence `complexity: high` and a
mandatory plan-writer outline pass at `/m1-tick start`:

1. **Dispatch seam** — `CommandHandler.handle` returns `@Nullable OutboundMessage`;
   `/summary` returns `null` to signal "already delivered via the notifier";
   `InboundRouter` skips its send for a null return (no double-send).
2. **SPI payload** — `ProgressNotifier` gains a payload-carrying terminal call
   (`complete(scope, finalText)` / `fail(scope)`) so the finalized message is the
   real summary content, not a stage label. The interface stays an interface and
   `ProgressStage` keeps seven values, so both SPI load tests are unaffected.
3. **Notifier** — `StageProgressNotifier` resolves the bound adapter via
   `AdapterRegistry.activatedAdapters()` keyed by `InboundContext.adapterName()`,
   holds per-scope state (placeholder handle, last-edit timestamp), and does
   placeholder→typing-on→coalesced-update→finalize+typing-off via try/finally.
4. **Localization** — seven `progress.*` `BundleKeys` constants + matching entries
   in `bundles/en.properties` AND `bundles/cs.properties`; cadence `systemFloor`
   in `application.properties`. No user text in any stage string.

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

- **(a) IMPLEMENT (full feature)** — interface and enum are kept. The
  `ProgressNotifier` SPI gains terminal methods (`complete`/`fail`) but stays an
  interface, and `ProgressStage` keeps its seven values, so **both load tests are
  unchanged** (the pinned surface still exists — interface-ness and the
  seven-value count both hold). New behavior is proven by **added** tests (not by
  editing the load tests): notifier/handler behavior under
  `infochat-provider/src/test/.../command` (placeholder send, typing-on, coalesced
  update, finalize + typing-off via try/finally — spec steps 1–4 — plus the
  injection-prevention assertion and the SPI-payload assertion), the router
  no-double-send test under `infochat-provider/src/test/.../messaging`, and the
  cadence-floor + bundle-coverage assertions under
  `infochat-provider/src/test/.../bundle`. No expected-behavior change to either
  SPI load test.
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
  M1-183 is `done` as of 2026-06-08, so the serialize constraint is
  satisfied.
- The concrete notifier under (a) lives provider-side (new class in
  the provider module); the budget reserves room for it. Concrete
  path chosen at start:
  `infochat-provider/.../messaging/StageProgressNotifier.java`.
- **Dispatch-contract change (budget-breach refine, 2026-06-08).** The
  provider dispatch is return-one-text: `InboundRouter` calls
  `handler.handle(scope,text).text()` then UNCONDITIONALLY `send()`s
  that text (`InboundRouter.java:766 → 663 → 728`). A notifier that
  owns a placeholder→update→finalize message would make the router
  emit a SECOND, duplicate message. Direction (a) therefore edits the
  dispatch contract so a `CommandHandler` can signal "already delivered
  via the notifier" and the router skips its send for that invocation
  (acceptance item added; `InboundRouter.java` + `CommandHandler.java`
  added to files_scope). Adapter resolution needs no new seam —
  `AdapterRegistry.activatedAdapters()` is already public and used by
  four handlers; the notifier filters it by `InboundContext.adapterName()`.
- **SPI-payload change (budget-breach refine, 2026-06-08).**
  `ProgressNotifier.publish(ScopeRef, ProgressStage)` carries no text, but
  the finalized message for `/summary` IS the composed summary content. The
  interface therefore gains a payload-carrying terminal call
  (`complete(scope, finalText)` / `fail(scope)`); `publish` still drives the
  five non-terminal stages. Interface-ness and the seven-value enum are
  preserved, so the two SPI load tests stay green.
- **Localization + cadence (budget-breach refine, 2026-06-08).** No
  `progress.*` bundle keys exist today. D43 + acceptance require stage strings
  to resolve from the bundle (not hardcoded — that is the injection-prevention
  guarantee). Adds seven `BundleKeys` constants + entries in BOTH
  `bundles/en.properties` and `bundles/cs.properties` (the bundle-coverage test
  enforces every locale). Coalescing uses a single `systemFloor`
  (`application.properties`; design `minEditInterval=600ms`); per-adapter
  `adapterMin` is deferred (out_of_scope) to avoid a `CapabilityFlags` ripple
  through every adapter constructor.
- **Re-entry (budget-breach refine, 2026-06-08).** Because the refine raised
  `complexity` to `high`, this ticket goes back to `status: pending` and must be
  re-entered via `/m1-tick start M1-212` so clarity AND the plan-writer outline
  re-run against the rewritten acceptance. This overrides the budget-breach
  escalation arm's usual "stay in-progress on the branch" (gate-preserving: a
  high-complexity ticket may not skip the plan-writer pass).
