---
id: M1-057
title: Unseal PendingConfirm + extract variants
status: done
created: 2026-05-24
last_updated: 2026-05-24
escalations:
  - date: 2026-05-24
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — escalation surfaced by developer during round-1 implementation,
      before any reviewer pass. ConfirmStateServiceTest.java (M1-051's plain-
      JUnit service-tier test) holds 14 references to the soon-to-be-removed
      nested types (ConfirmStateService.PendingConfirm.{Ban, InviteCreateOpen,
      InviteRevoke}) on lines 42-43, 66-67, 96, 118-119, 140-143, 175-178.
      After acceptance item 5 removes the nested records, the test cannot
      compile until those references swap to the new top-level names. The
      mechanical swap is authorized by acceptance item 11's verification
      clause and by test_plan.preserves line 63, but the file is absent from
      files_scope (which lists exactly 9 paths, matching files_budget: 9).
      Touching it would breach the budget and the scope fence simultaneously.
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCreateOpenConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteRevokeConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmStateServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any behavioral change to ConfirmStateService — `remember` / `takeMatching` / `takeAny` / `peek` signatures, deadline semantics, lazy-expiry rules, and the `ConfirmKey` / `Stored` private records stay byte-identical
  - any change to the `commandName()` / `sweepPrefix()` return strings on the three existing variants — those strings are wire-level confirm-token semantics consumed by `InboundRouter` step 4.5 sweep and by every handler's `takeMatching` key; renaming would silently break confirm recognition
  - any change to InboundRouter (the step 4.5 sweep logic, the polymorphic calls to `pending.commandName()` / `pending.sweepPrefix()`, or the cancellation reply path)
  - any new `PendingConfirm` variant — M1-053 (`RemoveSourceConfirm`, `SourceEnableConfirm`), M1-054 (`UnfollowTagAllConfirm`), and future tickets (`ClearConfirm`, `ForgetConfirm`, `QuarantineRejectConfirm`) add theirs separately AFTER this lands; this ticket only de-seals and migrates the three existing variants
  - any change to `BanCommandHandler` / `InviteCommandHandler` business logic — only mechanical type-name swaps (`ConfirmStateService.PendingConfirm.Ban` → `BanConfirm`, etc.); no audit-row, reply-key, transaction-shape, or arg-parsing changes
  - any change to the existing test scenarios in `InboundRouterConfirmCancelTest`, `InboundRouterProbationOrderingTest`, `InboundRouterNormalizeTest` — only mechanical type-name swaps; no new test methods, no removed test methods, no assertion changes
  - any change to the M1-051 `ConfirmStateServiceTest` beyond mechanical type-name swaps on the specific variant constructors it uses to seed pending entries (`ConfirmStateService.PendingConfirm.Ban` → `BanConfirm`, `ConfirmStateService.PendingConfirm.InviteCreateOpen` → `InviteCreateOpenConfirm`, `ConfirmStateService.PendingConfirm.InviteRevoke` → `InviteRevokeConfirm`) — the test's storage / expiry / takeMatching scenarios, assertion shapes, fixture setup, and the interface-typed local variables stay unchanged; `git diff main -- infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmStateServiceTest.java` shows only those mechanical swaps
  - any change to `ConfirmFlowIT` (the M1-051 cross-cutting IT) — if it constructs `PendingConfirm.Ban` etc. via the in-memory adapter intake path, the test code path is via the handler (which IS modified), not via direct construction; the IT must continue passing unchanged
  - any new lint rule / clarity-prompt addition forbidding inner-class confirm payloads — the convention is set by the refactor's existence and the three new top-level files; pattern-enforcement-by-lint is over-process and waits until a future ticket demonstrably repeats the gaming
  - any rename of the existing `ConfirmStateService.PendingConfirm` interface itself — the interface stays where it is, in its existing nested-inside-the-service position (it is a *service-defined contract*, distinct from its implementations; nesting an interface is not the same anti-pattern as nesting variants)
acceptance:
  - "`ConfirmStateService.PendingConfirm` interface declaration changes from `public sealed interface PendingConfirm permits PendingConfirm.Ban, PendingConfirm.InviteCreateOpen, PendingConfirm.InviteRevoke { ... }` to `public interface PendingConfirm { ... }`. The `sealed` modifier is removed; the `permits` clause is removed. The two abstract methods (`commandName()`, `sweepPrefix()`) and their javadoc descriptions remain. The interface's class-level javadoc is rewritten to describe the open-extension contract: each confirmable command provides its own top-level record implementing `PendingConfirm`; the record's `commandName()` must equal the takeMatching key the matching handler will pass and must be unique across the confirmable-command catalogue documented at `docs/design/03-commands.md` §Confirmation for destructive commands; `sweepPrefix()` must equal the slash-stripped user-visible prefix the router's step 4.5 sweep matches against."
  - "`BanConfirm.java` exists as a top-level public record in package `app.zcat.infochat.provider.command` with field shape `(String targetContactId, String reason)` IDENTICAL to the pre-refactor nested `PendingConfirm.Ban` (same field names, same field types, same field order). Implements `ConfirmStateService.PendingConfirm`. `commandName()` returns the literal string `\"ban\"` and `sweepPrefix()` returns the literal string `\"ban\"` — IDENTICAL to the pre-refactor nested returns at `ConfirmStateService.java:237-238`."
  - "`InviteCreateOpenConfirm.java` exists as a top-level public record in package `app.zcat.infochat.provider.command` with field shape `(String targetAdapter)`. Implements `ConfirmStateService.PendingConfirm`. `commandName()` returns the literal string `\"invite:create:open\"` and `sweepPrefix()` returns the literal string `\"invite create --open\"` — IDENTICAL to the pre-refactor nested returns at `ConfirmStateService.java:243-244`."
  - "`InviteRevokeConfirm.java` exists as a top-level public record in package `app.zcat.infochat.provider.command` with field shape `(java.util.UUID code)`. Implements `ConfirmStateService.PendingConfirm`. `commandName()` returns the literal string `\"invite:revoke\"` and `sweepPrefix()` returns the literal string `\"invite revoke\"` — IDENTICAL to the pre-refactor nested returns at `ConfirmStateService.java:249-250`."
  - "The three nested record declarations inside `ConfirmStateService.PendingConfirm` (lines 236-251 of the pre-refactor file) are REMOVED. After the refactor, the interface body contains only the two abstract method declarations and their javadoc. ConfirmStateService.java's other members (the producer, fields, constructors, `remember` / `takeMatching` / `takeAny` / `peek` methods, `ConfirmKey` and `Stored` private records, `timeoutSeconds` / `setClock` / `size`) are byte-identical to the pre-refactor file."
  - "`BanCommandHandler.java` updated to import `BanConfirm` (top-level). Every reference to `ConfirmStateService.PendingConfirm.Ban` (3 occurrences at lines 162-163, 217 of the pre-refactor file) is replaced with `BanConfirm`. Every `new ConfirmStateService.PendingConfirm.Ban(...)` call is replaced with `new BanConfirm(...)` preserving the constructor argument order. No other change to the handler's logic, audit-row writes, reply-key references, transaction boundaries, or argument parsing. Verify: existing `BanCommandHandlerTest` (M1-044c / M1-051) passes without modification."
  - "`InviteCommandHandler.java` updated to import `InviteCreateOpenConfirm` and `InviteRevokeConfirm` (top-level). Every reference to `ConfirmStateService.PendingConfirm.InviteCreateOpen` (3 occurrences at lines 221-222, 272 of the pre-refactor file) is replaced with `InviteCreateOpenConfirm`; every reference to `ConfirmStateService.PendingConfirm.InviteRevoke` (3 occurrences at lines 509-510, 547 of the pre-refactor file) is replaced with `InviteRevokeConfirm`. No other change to the handler's logic. Verify: existing `InviteCommandHandlerTest` (M1-051) passes without modification."
  - "`InboundRouterConfirmCancelTest.java` updated: 2 references to `new ConfirmStateService.PendingConfirm.Ban(...)` (lines 58, 87 of the pre-refactor file) replaced with `new BanConfirm(...)`. No other change to test scenarios, assertion text, fixture setup, or fake-service shape. Verify: test class compiles and every `@Test` method continues to pass."
  - "`InboundRouterProbationOrderingTest.java` updated: references to `ConfirmStateService.PendingConfirm` types in the fake-service signatures are unchanged (the fakes return / accept `Optional<ConfirmStateService.PendingConfirm>` which is the interface type, not a specific variant); any test construction of specific variants is replaced with the new top-level names. No other change. Verify: test class compiles and every `@Test` method continues to pass."
  - "`InboundRouterNormalizeTest.java` updated: same shape as `InboundRouterProbationOrderingTest` — only references to specific variant constructors swap to top-level names; interface-typed fake signatures unchanged. Verify: test class compiles and every `@Test` method continues to pass."
  - "`ConfirmStateServiceTest` (the M1-051 service-tier plain-JUnit test) updated with mechanical type-name swaps ONLY: `ConfirmStateService.PendingConfirm.Ban` → `BanConfirm` (occurrences at lines 42-43, 66-67, 96, 140-141, 175-178 of the pre-refactor file), `ConfirmStateService.PendingConfirm.InviteCreateOpen` → `InviteCreateOpenConfirm` (lines 142-143), `ConfirmStateService.PendingConfirm.InviteRevoke` → `InviteRevokeConfirm` (lines 118-119). The test exercises storage / expiry / takeMatching semantics over the `PendingConfirm` interface generically; assertion shapes, scenarios, fixture setup, and interface-typed local variables are unchanged. The verification is: `git diff main -- infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmStateServiceTest.java` shows only mechanical type-name swaps, zero scenario / assertion / fixture changes."
  - "M1-051's `ConfirmFlowIT` (the cross-cutting `@QuarkusTest` IT exercising the full pending→confirm cycle through the in-memory adapter) passes without modification. The IT drives the cycle via the handler-side intake path, not via direct `PendingConfirm` construction; the handler changes (mechanical type-name swaps in `BanCommandHandler` / `InviteCommandHandler`) preserve the wire-level shape end-to-end."
  - "`mvn -B clean verify` from the repo root exits 0. Every pre-existing test passes: `ConfirmStateServiceTest`, `ConfirmFlowIT`, `BanCommandHandlerTest`, `InviteCommandHandlerTest`, `InboundRouterConfirmCancelTest`, `InboundRouterProbationOrderingTest`, `InboundRouterNormalizeTest`, `InboundRouterIntakeOrderingTest`, plus every other M1-001 .. M1-056 test currently green on main."
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanConfirm.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCreateOpenConfirm.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteRevokeConfirm.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmStateServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
  preserves:
    - all tests currently green on main
    - ConfirmStateServiceTest passes after mechanical type-name swaps (storage / expiry / takeMatching semantics unchanged)
    - ConfirmFlowIT passes unchanged (drives via handler intake, not direct construction)
    - BanCommandHandlerTest passes unchanged (handler's behavior is byte-identical)
    - InviteCommandHandlerTest passes unchanged (handler's behavior is byte-identical)
spec_refs:
  - docs/spec/commands.md §Surface conventions
  - docs/design/03-commands.md §Confirmation for destructive commands
decision_refs: []
reviews:
  - round: 1
    date: 2026-05-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 165
      removed: 73
overrides: []
aborted_attempts: []
reopens: []
revisions:
  - date: 2026-05-24
    reason: budget-breach (ConfirmStateServiceTest mechanical swap was authorized in acceptance/test_plan but omitted from files_scope/files_budget)
    snapshot_summary: |
      files_budget: 9 → 10
      files_scope: + ConfirmStateServiceTest.java
      out_of_scope #7: rewrote — old text falsely claimed the test "does not reference any specific variant"; new text bounds the change to mechanical type-name swaps (consistent with acceptance #11 verification clause and test_plan.preserves)
      acceptance #11: removed contradictory "WITHOUT MODIFICATION" headline; the verification clause already correctly bounds the change to mechanical swaps
      test_plan.modifies: + ConfirmStateServiceTest.java (matches files_scope addition)
redteam_findings: []
clarity_check:
  date: 2026-05-24
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-057: Unseal PendingConfirm + extract variants

## Context

M1-051 shipped `ConfirmStateService` with `sealed interface PendingConfirm
permits Ban, InviteCreateOpen, InviteRevoke`. Every confirmable-command ticket
that has tried to consume the service since (M1-053 source-management, M1-054
tag-preferences `/unfollow-tag --all`) has hit the same wall at `/m1-tick start`:
the ticket's natural scope excludes `ConfirmStateService.java`, but the sealed-
type design forces an edit there to add a new permit. Both tickets returned
`OUTLINE FAILED` for that reason in the same week.

Two failures of the same shape is a pattern under the workflow's
"deletion over addition" doctrine. The fix is to remove the forcing
constraint *and* the budget-gaming inner-class accumulation it encourages
([[feedback_avoid_test_inner_classes]] applied to production code: a
service file with 9 nested domain records at end-of-v1 is the same anti-
pattern as the 15-inner-class test file M1-045 caught). After this ticket,
`ConfirmStateService.java` is stable forever; every future confirmable-
command ticket lands its payload record as a top-level file alongside its
handler, paying zero edits to the service.

Spec contract this ticket implements: `docs/spec/commands.md` §Surface
conventions (confirmation for destructive commands) and `docs/design/03-
commands.md` §Confirmation for destructive commands. Neither mandates a
sealed-type implementation — the catalogue closure is a *design-doc*
invariant (the 9 confirmable commands are enumerated in the design), not
a Java-type invariant. Removing the redundant Java-type enforcement does
not relax the spec.

## Acceptance

Mirrored verbatim in the YAML `acceptance:` block above. Summary:

1. `PendingConfirm` interface loses `sealed` and `permits`; class-level
   javadoc is rewritten to describe the open-extension contract.
2. Three new top-level records (`BanConfirm`, `InviteCreateOpenConfirm`,
   `InviteRevokeConfirm`) replace the three nested variants. Field
   shapes, `commandName()` returns, and `sweepPrefix()` returns are
   byte-identical to the pre-refactor nested forms.
3. `BanCommandHandler` and `InviteCommandHandler` updated with the new
   type names; no behavioral change.
4. Three test files updated with the new type names; no scenario change.
5. `mvn -B clean verify` is green; every prior test passes.

## Out-of-scope

The 11-entry `out_of_scope:` block above enumerates every adjacent
temptation. Highlights:

- **No `ConfirmStateService` behavioral change.** API signatures,
  deadline arithmetic, expiry rules, private helpers (`ConfirmKey`,
  `Stored`) are byte-identical. Only the interface declaration and the
  removed nested variants change.
- **No wire-level rename.** `commandName()` returns `"ban"` /
  `"invite:create:open"` / `"invite:revoke"` (literal strings) and
  `sweepPrefix()` returns `"ban"` / `"invite create --open"` /
  `"invite revoke"` post-refactor exactly as pre-refactor. The router's
  step 4.5 sweep and every handler's `takeMatching` call use those
  strings; renaming would silently break confirm-token recognition.
- **No new variants.** M1-053's `RemoveSourceConfirm` /
  `SourceEnableConfirm` and M1-054's `UnfollowTagAllConfirm` land in
  their own tickets after this one merges. Same for future ones
  (`ClearConfirm`, `ForgetConfirm`, `QuarantineRejectConfirm`).
- **No handler logic change.** Only mechanical type-name swaps in
  `BanCommandHandler` and `InviteCommandHandler`.
- **No test scenario change.** Mechanical type-name swaps in the three
  router test files; `ConfirmStateServiceTest` and `ConfirmFlowIT` pass
  unchanged.
- **The `PendingConfirm` interface stays nested inside
  `ConfirmStateService`.** Nesting a *contract* (an interface owned by
  the service) is different from nesting *implementations* (variant
  records owned by their command-handler consumers). The first is a
  legitimate cohesion choice; the second is the gaming pattern this
  ticket removes.
- **No lint / clarity-prompt addition forbidding inner-class confirm
  payloads.** Convention-by-example is enough; pattern-enforcement-by-
  lint is over-process and waits until a future ticket repeats the
  gaming despite the example.

## Notes

- **Why the sealed-type idiom is the wrong fit here.** Three concrete
  signals: (a) no `switch` expression / `instanceof` chain anywhere in
  the codebase exhausts over `PendingConfirm` — the only consumers are
  the router's virtual `commandName()` / `sweepPrefix()` calls
  (`InboundRouter.java:436, 551`) and the handler-side direct casts
  (`BanCommandHandler.java:163`, `InviteCommandHandler.java:222, 509`),
  both indifferent to sealedness; (b) the parallel-tickets skill rule
  requires disjoint `files_scope` lists — sealed types serialize all
  confirmable-command tickets through one file; (c) the inner-class
  accumulation inside the service file matches the
  [[feedback_avoid_test_inner_classes]] anti-pattern applied to
  production code (the test-file rule of thumb is >3 inner classes →
  refactor; the design path would have driven this file to 9 inner
  records by end-of-v1).

- **Why nested *interface* is fine even though nested *variants* aren't.**
  The interface is a contract owned by the service — its location with
  the service signals "this is the API the service exposes for its
  payload type." Variant records, in contrast, are owned by their
  command-handler consumers — their natural location is alongside the
  handler. The cohesion question ("where does this type belong?")
  answers differently for the interface and for the variants.

- **Adjacent reference.** The pattern this ticket migrates toward mirrors
  the `CommandHandler` discovery pattern already in use across the
  project: handlers are CDI beans discovered via
  `Instance<CommandHandler>` iteration, with each handler in its own
  top-level file. No central permits clause; new handlers land in new
  files. After M1-057, the confirm-payload story parallels the
  handler-discovery story.

- **Future consumer list (informational, not commitments).** M1-053's
  `RemoveSourceConfirm(UUID sourceId)` + `SourceEnableConfirm(UUID
  sourceId)`; M1-054's `UnfollowTagAllConfirm(...)`; future tickets'
  `ClearConfirm`, `ForgetConfirm`, `QuarantineRejectConfirm`. Each
  lands as a single top-level file in `app.zcat.infochat.provider.
  command` (or a sub-package the implementing ticket chooses).
  `ConfirmStateService.java` is not touched by any of them.

- **Risk of a missed callsite.** Six call locations across three
  production files and three test files (12 references total per the
  acceptance items). All are mechanical type-name swaps that the
  compiler flags if missed. Round-cap 2 is appropriate; no
  complexity:high needed.

- **`mvn verify` is the green gate.** Per workflow doctrine, this ticket
  does not claim "the refactor preserves behavior" — the test suite
  proves it at runtime. `ConfirmStateServiceTest`, `ConfirmFlowIT`,
  `BanCommandHandlerTest`, `InviteCommandHandlerTest`, the three router
  tests, and every other M1-* test currently green on main are the
  contract.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-057-unseal-pendingconfirm-extract.md
```
