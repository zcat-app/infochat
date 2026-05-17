---
id: M1-035d
title: Wire AutoRegisterService into InboundRouter intake
status: pending
created: 2026-05-17
last_updated: 2026-05-17
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/io/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/io/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java
  - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "No change to the InboundMessage record (do NOT add an adapter field — adapter identity reaches the router via the AdapterRegistry wiring, not the SPI payload). The infochat-messaging-adapter module stays frozen at M1-035a's commit."
  - "No change to AutoRegisterService internals (UPSERT_SQL column list, ON CONFLICT clause, return-value semantics, idempotence guarantees) — that surface is M1-035c's frozen commit."
  - "No invite-code gating, ban check, or slow-start probation filter at the intake point — those are T2-A and stay deferred."
  - "No AUTO_REGISTER audit-log row (per docs/design/00-mvp.md §4 the V5 closed action set has no such verb; T2-A's INVITE_CONSUME row replaces this MVP-legacy path when invite-gating lands)."
  - "No group-scope dispatch and no group @mention auto-register path (T2-F)."
  - "No deletion of the existing five InboundRouterTest assertions — they remain the regression net for normalization / empty-drop / chat-mode / unknown-command / exception-path behavior. The @BeforeEach may grow a row-cleanup step (test-fixture hygiene); the existing five @Test bodies stay byte-identical."
  - "No edit to the M1-035c-authored comment in InboundRouter.java lines 58–59 beyond a one-token ticket-reference update (M1-035c → M1-035d). The substance of the comment stays."
  - "No new CommandHandler implementations, no /help wiring change, no BundleLoader change — M1-035c shipped those."
acceptance:
  - "InboundRouter.onMessage (or the adapter-name-aware entry surface it now exposes) invokes AutoRegisterService.resolveOrRegister(msg.sender(), adapterName) exactly once per inbound, AFTER Unicode normalization and the empty-body short-circuit, and BEFORE the slash-dispatch / chat-mode-reply branch. Verified by a new @Test that deliverDm('fresh-contact-1', '/xyz') results in exactly one row in users with adapter='inmemory' and contact_id='fresh-contact-1'."
  - "The adapter name passed to resolveOrRegister equals the source adapter's name() — not a hardcoded literal. Verified by a new @Test that deliverDm via the InMemoryAdapter produces a row with adapter='inmemory'."
  - "Auto-register also fires on chat-mode (non-slash) inbound — confirming the wiring sits upstream of the slash-vs-chat split. Verified by a new @Test that deliverDm('fresh-contact-2', 'hello there') produces a users row AND the chat-mode-not-in-MVP reply (the existing CHAT_MODE_REPLY literal)."
  - "Auto-register is idempotent across repeated DMs from the same contact. Verified by a new @Test that two consecutive deliverDm('same-contact', ...) calls produce exactly one users row (resolveOrRegister's ON CONFLICT DO NOTHING is the load-bearer; this acceptance pins that the wiring does not double-insert)."
  - "All five existing InboundRouterTest @Test methods pass unchanged in body and assertions. The @BeforeEach may grow a row-cleanup step but the five test bodies remain byte-identical to commit a6e97ec."
  - "AdapterRegistryTest's singleAdapterHappyPathActivatesInMemoryAndRegistersRouter and any other deliverDm-driven assertion in that class continue to pass. A row-cleanup @BeforeEach step is permitted; assertions are not modified."
  - "grep -rn 'resolveOrRegister' infochat-provider/src/main/ returns at least one match in InboundRouter.java or AdapterRegistry.java (i.e., the wiring is in production code, not just tests) — the very gap the M1-035 umbrella IT exposed."
  - "mvn -pl infochat-provider verify is green; mvn verify from the repo root is green (no regressions in collector or other modules)."
test_plan:
  adds:
    - "≥3 new @Test methods covering: (a) first-DM slash inbound inserts a users row before dispatch; (b) first-DM chat-mode inbound inserts a users row before reply; (c) repeated DMs from the same contact produce exactly one row. Placement is implementer's choice — either appended to InboundRouterTest or a new InboundRouterAutoRegisterTest class. If a new class is used it is counted against files_budget."
    - "Optional @BeforeEach row-cleanup step in InboundRouterTest and/or AdapterRegistryTest, scoped to a 'fresh-' / 'auto-reg-' / 'same-contact-' contact_id namespace (mirroring AutoRegisterServiceTest's 'test-' / 'race-' / 'dup-' cleanup pattern), so the new tests do not race the deferred bootstrap-admin row or other tests' fixtures."
  preserves:
    - "All five existing InboundRouterTest @Test bodies (empty-drop, leading-whitespace-slash, chat-mode-reply, unknown-command, exception-path) — byte-identical assertions."
    - "All AdapterRegistryTest @Test assertions — only fixture hygiene may change."
    - "AutoRegisterServiceTest's four invariants — that file is not in files_scope."
    - "StartupGatesTest's six gate-test bodies — that file is not in files_scope."
    - "HelpCommandHandlerTest's four assertions — that file is not in files_scope."
    - "All tests currently green on main."
spec_refs:
  - "docs/spec/security.md §Authorization model"
  - "docs/design/00-mvp.md §4. Messaging adapter and commands"
  - "docs/design/00-mvp.md §6. MVP exit criteria"
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-035d: Wire AutoRegisterService into InboundRouter intake

## Context

Skeleton drafted as the defer target of M1-035 (the M1-035a/b/c
umbrella). M1-035's umbrella IT discovered that
`AutoRegisterService.resolveOrRegister` is never invoked from
production code: `InboundRouter.onMessage` goes directly from
`normalize()` to `handleSlash()` with no AutoRegisterService
injection. `grep -rn 'resolveOrRegister' infochat-provider/src/main/`
returns only the method definition.

M1-035c's own ticket body committed to *"the auto-register-on-first-DM
service the InboundRouter calls before slash-prefix dispatch"*, but
the production wiring was omitted from commit `a6e97ec`. M1-035c is
FROZEN per the umbrella + subticket idiom (never amend a passed
commit); this ticket carries the wiring forward.

## Definition of Done

The legacy auto-register-on-first-DM path from
`docs/design/00-mvp.md` §4 is wired into production code, not just
declared by a comment. Concretely:

1. `InboundRouter` injects `AutoRegisterService` and, on every inbound
   that survives the normalization + empty-body short-circuit,
   invokes `autoRegisterService.resolveOrRegister(msg.sender(), adapterName)`
   exactly once **before** the slash-vs-chat dispatch branch. The
   returned `UUID` is currently unused (no probation / ban check is
   wired in MVP per `out_of_scope`); the call site is a seam for
   T2-A to retro-fit invite-gating.
2. The `adapterName` passed in is the `name()` of the adapter that
   delivered the inbound — not a hardcoded literal. Plumbing choice
   is the implementer's call among the three Implementation notes
   options below; the constraint is that the value reflects the real
   source adapter so multi-adapter deployments (T3-A) inherit the
   correct behavior with no further intake-layer change.
3. The existing five `InboundRouterTest` assertions remain a
   regression net (their bodies stay byte-identical to commit
   `a6e97ec`); the test-fixture `@BeforeEach` may grow a row-cleanup
   step but no existing assertion is weakened or deleted.
4. `mvn verify` from the repo root is green. The umbrella M1-035's IT
   (the one that exposed this gap) is **not** unblocked by this
   ticket alone — that happens when the user runs `/m1-tick reopen
   M1-035`. This ticket's success criterion is the wiring + the new
   tests; the umbrella's re-attempt is downstream.

## Implementation notes

Likely shape:

- `InboundRouter` injects `AutoRegisterService`.
- `onMessage(InboundMessage msg)`: after `normalize()`, before the
  empty-body short-circuit (or before `handleSlash`, depending on the
  spec read), call `autoRegisterService.resolveOrRegister(msg.sender(), adapterName)`.
- The adapter name needs to reach the router. Either:
  (a) `AdapterRegistry.start()` passes a per-adapter `InboundHandler`
      bound to the adapter's name (small wiring change — wrap the
      router per adapter via a lambda that captures `adapter.name()`;
      `InboundRouter` exposes a new `onMessage(InboundMessage, String adapterName)`
      method and drops `implements MessagingAdapter.InboundHandler` —
      the SPI itself stays frozen, only the Provider-internal entry
      surface changes), or
  (b) extend the SPI so `InboundMessage` carries the adapter name
      alongside the scope (intrusive — touches M1-035a's frozen SPI
      and the messaging-adapter module — ruled out by `out_of_scope`), or
  (c) the router consults `replyTarget.name()` at intake (simplest;
      but `replyTarget` is volatile last-bound-wins so this is
      correct only for single-adapter MVP and silently breaks the
      multi-adapter case — acceptable per the existing InboundRouter
      javadoc but less future-proof than (a)).
- Per `docs/spec/security.md` §Authorization model, auto-register
  runs AFTER Unicode normalization. The MVP-legacy path documented
  in `docs/design/00-mvp.md` §4 **replaces** the spec's step-2
  invite-check for DM unknown contacts; T2-A layers the invite-gate
  back on top. The spec's step-3 group-auto-register path is T2-F
  and stays out of scope.

Relevant code:

- `infochat-provider/src/main/java/io/infochat/provider/messaging/InboundRouter.java`
- `infochat-provider/src/main/java/io/infochat/provider/messaging/AdapterRegistry.java`
- `infochat-provider/src/main/java/io/infochat/provider/messaging/AutoRegisterService.java` (read-only — frozen)
- `infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java`
  (existing five-branch test — preserve assertion bodies unchanged;
  add new @Test(s) for the wiring; @BeforeEach may grow row-cleanup)
- `infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java`
  (existing deliverDm round-trip — preserve assertions; @BeforeEach
  may grow row-cleanup for the inserted users row)

## Big-picture notes

- This ticket unblocks M1-035 (the umbrella's IT). On `done`, the
  user runs `/m1-tick reopen M1-035` to bring the umbrella back to
  `pending` for re-attempt.
- The umbrella + subticket idiom (per
  `docs/process/workflow.md` §Ticket-ID placeholder convention)
  permits hand-authored suffix-IDs like `M1-035d`. This ticket is a
  late-arriving sibling of M1-035a/b/c that shipped after the
  umbrella's IT-authoring attempt surfaced the missing wire.
- M1-035c's audit comment in `InboundRouter.java` (line 58-59) that
  says *"M1-035c adds the AutoRegisterService at the same intake
  point"* gets a one-token ticket-reference update (M1-035c →
  M1-035d) — the substance of the comment stays.

## Out-of-scope expansion

- The InboundMessage SPI stays frozen — no new field for adapter
  name. Plumbing happens via `AdapterRegistry` wiring (option (a)
  above) or `replyTarget.name()` lookup (option (c)) — both keep the
  infochat-messaging-adapter module byte-identical to M1-035a's
  commit.
- AutoRegisterService internals stay frozen — no SQL change, no
  column-list edit, no return-type change, no idempotence-semantics
  change. This ticket is wiring only.
- No invite-code gating, no ban check, no probation filter at
  intake. T2-A wires those upstream of the auto-register call site;
  the seam is intentional.
- No AUTO_REGISTER audit-log verb (V5 closed action set has no such
  verb; adding it would be a spec amendment + a separate `spec:`
  commit, both explicitly out of this ticket's scope).
- No group-scope dispatch and no group @mention auto-register path
  (T2-F).
- No deletion or rewording of the M1-035c-authored comment in
  `InboundRouter.java` beyond the one-token ticket-reference update.
- No new CommandHandler implementations, no /help wiring change.

## Authorized test changes

- **InboundRouterTest** — add ≥3 new `@Test` methods covering
  acceptance items 1, 3, and 4 (first-DM slash inserts a row before
  dispatch; first-DM chat-mode inserts a row before reply; repeated
  DMs from the same contact produce exactly one row). The existing
  five `@Test` bodies (`emptyAndWhitespaceAndInvisibleOnlyBodiesAreDropped`,
  `leadingWhitespaceBeforeSlashCommandParsesAsTheCommand`,
  `chatModeBodyProducesDeterministicNotInMvpReply`,
  `unknownCommandProducesFriendlyUnknownCommandReply`,
  `commandHandlerExceptionProducesInternalErrorReplyWithoutLeakingMessage`)
  remain byte-identical in body and assertions.
- The `@BeforeEach resetAdapterState` in InboundRouterTest may grow
  a row-cleanup step that deletes from `users` where `contact_id`
  matches a per-test prefix (e.g., `fresh-%`, `auto-reg-%`,
  `same-contact-%`), mirroring AutoRegisterServiceTest's pattern.
  This is a fixture-hygiene addition — not a modification to the
  existing five tests' assertions.
- **AdapterRegistryTest** — preserve all assertions. The
  `@BeforeEach` (if absent) may gain a `users`-cleanup step scoped
  to the `alice` contact_id used by `singleAdapterHappyPathActivatesInMemoryAndRegistersRouter`,
  OR the test may switch to a per-test fresh contact_id (e.g.,
  `alice-adapter-registry`) to avoid leaking state across runs. The
  assertion on the unknown-command reply text stays unchanged.
- **Alternative test placement**: instead of extending
  InboundRouterTest, the implementer may add a new
  `InboundRouterAutoRegisterTest` class. If chosen, the new file
  counts against `files_budget: 8` (current scope uses 4 files; 1
  more leaves 3 headroom).
- Files explicitly NOT authorized to change: `AutoRegisterServiceTest`,
  `HelpCommandHandlerTest`, `StartupGatesTest`, and any test in
  `infochat-messaging-adapter` or `infochat-collector`.

## Alternatives considered

- **(a) Per-adapter handler wrap in AdapterRegistry**: the
  recommended path. `AdapterRegistry.start()` builds a lambda
  `msg -> inboundRouter.onMessage(msg, adapter.name())` and passes
  it to `adapter.setInboundHandler(...)`. `InboundRouter` no longer
  implements `MessagingAdapter.InboundHandler` directly; it exposes
  `onMessage(InboundMessage, String adapterName)`. SPI stays frozen;
  multi-adapter correctness is preserved for free.
- **(b) Extend InboundMessage SPI**: ruled out by `out_of_scope` —
  the messaging-adapter module is frozen and the SPI's silence on
  adapter identity is intentional (the adapter knows itself; the
  registry plumbs the name).
- **(c) `replyTarget.name()` consult**: simplest mechanically but
  correct only for single-adapter MVP (the volatile last-bound-wins
  replyTarget breaks for multi-adapter). Acceptable for the strict
  MVP shape under gate 5 (production-exclusion of inmemory), but
  (a) is preferred because it carries forward to T3-A without
  rework. Implementer chooses; either is accepted by the acceptance
  items.
