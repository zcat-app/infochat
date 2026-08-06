---
id: M1-775
title: "Confirm redemption ignores retyped arguments, and pending state is never swept"
status: done
created: 2026-08-06
last_updated: 2026-08-06
blocked_by: [M1-774]
files_budget: 20
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClearConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ForgetConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCreateOpenConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteRevokeConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineRejectConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RemoveSourceConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagAllConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClearCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ForgetCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RejectGroupCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RemoveSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmStateServiceTest.java
  - docs/spec/commands.md
complexity: high
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE INTAKE-REJECTION DRAIN. M1-774 decided, for every early return
    in `InboundRouter.onMessage`, whether it drains the pending
    confirm, and amended `commands.md` §Surface conventions and §/retry
    with the exception set. That enumeration is settled and is not
    re-opened here.
  - >-
    THE TTL LENGTH. `infochat.confirm.timeout` stays 60s (90s on `pi`).
    Sweeping expired entries is not the same question as how long the
    window is.
  - >-
    THE PER-GROUP REPLY BUCKET. That the bucket is drawn once per
    inbound while a drained rejection sends two bodies is a recorded,
    accepted residual on M1-774 and a `security.md` question, not a
    confirm-state question.
acceptance:
  - >-
    A CONFIRM LEG WHOSE ARGUMENTS DIFFER FROM THE STORED PAYLOAD MUST
    NOT REDEEM IT. `InboundRouter.isConfirmShape` (`:1686-1693`) tests
    only `startsWith("/" + sweepPrefix + " ")` and
    `endsWith(" confirm")`, so `/ban bob confirm` satisfies a pending
    `BanConfirm("alice")`: the step-4.5 sweep leaves the entry in
    place and `BanCommandHandler:192` pops the STORED payload, so
    `executeBan` runs against alice. The operator is told afterwards
    and `/unban` reverses it, which is what keeps this out of the
    high band — but a destructive admin primitive executing against
    a target absent from the message that triggered it is the defect.
  - >-
    THE FIX MUST NOT ASSUME A BARE CONFIRM LEG. `/quarantine reject
    <id> confirm` carries an argument by design
    (`QuarantineRejectConfirm.sweepPrefix()` is the two-word
    `quarantine reject`), so "accept only `/<command> confirm`" is
    wrong. Decide ONCE between the candidate designs and record which
    and why: (a) `PendingConfirm` gains a payload-vs-body match method
    so the router compares rather than ignores the retyped arguments,
    which keeps the decision in one place but changes the SPI every
    implementation carries; or (b) each handler's confirm leg
    validates the retyped arguments against the popped payload, which
    leaves the SPI alone but spreads the check across nine handlers
    and re-introduces the drift M1-051 centralised the sweep to avoid.
  - >-
    A MISMATCHED CONFIRM LEG IS "ANY OTHER INPUT". Whatever design is
    chosen, the resulting behaviour is that the mismatched body
    cancels the pending confirm with the standard acknowledgement and
    does NOT execute — the same treatment §Surface conventions gives
    every other non-confirming input. It must not silently execute
    the stored payload and must not silently execute the retyped one.
  - >-
    EXPIRED PENDING STATE IS SWEPT. `ConfirmStateService`'s map expires
    only lazily — inside `peek` / `takeAny` / `takeMatching` — so a
    (user, scope) that arms a confirm and never messages again retains
    its payload for the process lifetime. Growth is bounded by
    distinct (user, scope) pairs and by the registered-user gate, so
    this is hygiene, not a flood vector; the reason to fix it here is
    that it is the same object's lifecycle and a sweep is cheap next
    to the match work above. Prefer the simplest thing that works —
    an expiry check on write, or a scheduled pass — over new
    machinery, and use the injected Clock (engineering rules
    §Injectable time), never `Instant.now()`.
  - >-
    THE SPEC SENTENCE MATCHES THE CODE. `docs/spec/commands.md`
    §Surface conventions describes the follow-up as
    `<command> confirm`. If the chosen design accepts arguments on the
    confirm leg, that sentence states which and under what matching
    rule; if it does not, the sentence says so. Do not leave it
    ambiguous — the ambiguity is what let the loose predicate look
    conformant.
  - >-
    NAMED TESTS for the wrong-target case (arm one target, confirm
    another, assert no execution and a cancellation), for the
    legitimate argument-carrying leg (`/quarantine reject <id>
    confirm` still redeems), and for the sweep.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmStateServiceTest.java
  preserves:
    - >-
      M1-774's intake-rejection drains and its enumeration comment.
    - >-
      The step-4.5 sweep's leave-a-matching-shape-alone behaviour for
      a confirm leg that DOES match its payload.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 635
      removed: 28
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-06
    category: AUTH-BYPASS
    severity: low
    promise: |
      docs/spec/security.md §Trust boundaries item 3 ("Authorization →
      execution. Permission checks run in deterministic Java.") and
      §What's intentionally NOT in v1 ("Two-factor confirmation for ban
      — single-step confirm-within-window is enough for v1"): the
      confirm-within-window IS the v1 control binding a destructive
      admin primitive to the target the admin named. This diff's own
      commands.md amendment states a confirm leg is a confirmation
      "only when that argument names the very action awaiting
      confirmation".
    gap: |
      The identity binding is enforced in exactly one place — the
      router's step-4.5 sweep (InboundRouter.isConfirmShape, called
      from InboundRouter:970). The handler-side pop the same javadoc
      calls "the authoritative takeMatching call" was not changed and
      remains target-blind: each confirmable handler forks on a bare
      ` confirm` suffix test and then executes the STORED payload
      (BanCommandHandler:190-199, RejectGroupCommandHandler:136-143,
      QuarantineCommandHandler:333-341, InviteCommandHandler:422-433
      and :762-770, UnfollowTagCommandHandler:199-205). The auditor
      verified no dispatch path reaches a handler while skipping step
      4.5 today (runDispatchStage has one call site; the interruptible
      fork carries no confirm-arming command) — which is why this is
      low — but the defence is one refactor deep.
    repro: |
      1. Admin DMs `/ban alice-contact` → BanConfirm("alice-contact")
         armed.
      2. Admin sends `/ban bob-contact confirm`.
      3. Step 4.5 drains the entry and replies "Pending `ban`
         cancelled"; only because of that drain does the handler's
         takeMatching come back empty.
      4. Remove step 3 — any future path dispatching a slash body
         without the sweep — and BanCommandHandler pops
         BanConfirm("alice-contact") and bans alice from a message
         naming only bob. The primitive itself cannot tell the two
         bodies apart.
    suggested_fix_class: trust-boundary-tightening
    disposition: |
      STATED RESIDUAL (user decision, 2026-08-06). No code change, no
      follow-up ticket. No dispatch path reaches a confirmable handler
      while skipping step 4.5 today (verified by the auditor), and the
      handler-side fix is design (b) from acceptance item 2, which was
      weighed and rejected: six of the nine confirm legs deliberately
      never parse the retyped body, and the cancellation reply belongs
      to the router sweep. The identity binding is a router-tier
      invariant by design.
redteam_audits:
  - date: 2026-08-06
    verdict: FINDINGS
    base: 1c1f69f776d57e74eb6cb753ca44fc35ba7532d8
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-775-2026-08-06.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      One low AUTH-BYPASS finding: the retyped-argument identity check
      lives on the router sweep only, so the confirmable handlers stay
      target-blind and the property is a router invariant rather than a
      property of the primitive. No bypass path exists today (verified
      by the auditor). Two out-of-model items: (1) the write-triggered
      expiry sweep leaves the last expired entries resident for the
      process lifetime — a data-minimisation residual security.md
      discloses for the analogous translation cache but not for this
      map; (2) a verified NON-finding recording that
      ConcurrentHashMap.values().removeIf is value-conditional, so the
      sweep cannot cancel a concurrently re-armed entry.
      Disposition (user, 2026-08-06): finding 1 is a stated residual (no
      code change, no follow-up ticket); out-of-model 1 becomes a
      separate `spec:` commit AFTER this ticket merges, naming the
      pending-confirm map in security.md §Secrets handling; out-of-model
      2 needs no action.
outline_file: target/m1-tick-outline-M1-775.md
clarity_check:
  date: 2026-08-06
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-775: Confirm redemption ignores retyped arguments, and pending state is never swept

## Context

Filed 2026-08-06 out of M1-774's red-team passes. Two auditors reached
the loose match from different directions and neither owned it:
opencode's round-1 AUTH-BYPASS finding routed to it through the M1-038
transport byte cap, and claude's round-2 pass reached the adjacent
lifecycle question. M1-774 fenced the loose match in `out_of_scope`
before any work started — correctly, since it is a different mechanism
in different files — so it is filed here rather than folded in.

## The defect

`isConfirmShape` matches a pending confirmation on prefix and suffix
alone. Every argument between them is ignored, and the handler's
`takeMatching` is keyed on the command name only, so the stored payload
wins over whatever the user retyped:

1. Bot admin DMs `/ban alice` → `BanConfirm("alice")` armed for
   (actor, DM scope).
2. Within the TTL the admin sends `/ban bob confirm`.
3. The step-4.5 sweep sees a confirm-shaped body and leaves the entry
   alone; `BanCommandHandler` pops it and bans **alice**.

No oversized body, no second principal, no timing window is needed —
the two messages alone are sufficient. It is not cross-principal
(`PendingConfirm` is keyed by `(actorUserId, scope)`), the admin sees
the result, and `/unban` reverses it. What makes it worth a ticket is
that the surface is the confirm gate itself: the control the threat
model puts in front of the broadest-blast-radius primitives is the one
mis-reading its own input.

## The secondary item

`ConfirmStateService`'s `ConcurrentHashMap` has lazy expiry only. Nothing
sweeps it, so an entry armed by a user who then goes silent outlives its
deadline for the process lifetime, holding whatever the payload carries
(`BanConfirm` holds a target contact id). This is hygiene rather than
risk — one entry per (user, scope), creatable only by a registered user
running a confirm-gated command, reachable only by heap inspection,
which the threat model does not cover. It is bundled here because it is
the same object's lifecycle, not because it is urgent.

## Census

The class is "every confirm payload and every place one is redeemed".
Enumerate it by INVOCATION, not by a label — the payload records carry
no shared marker beyond the interface, and `UnfollowTagCommandHandler`
passes a constant rather than a literal, so a grep for `"confirm"`
string literals misses it. Both commands are re-runnable from the repo
root:

```
grep -rln "implements ConfirmStateService.PendingConfirm" \
  infochat-provider/src/main/java/app/zcat/infochat/provider/command/
grep -rn "takeMatching(" \
  infochat-provider/src/main/java/app/zcat/infochat/provider/command/ \
  | grep -v ConfirmStateService.java
```

As of 2026-08-06 that is **10 payload types** — `BanConfirm`,
`ClearConfirm`, `ForgetConfirm`, `InviteCreateOpenConfirm`,
`InviteRevokeConfirm`, `QuarantineRejectConfirm`, `RemoveSourceConfirm`,
`SourceEnableConfirm`, `UnfollowTagAllConfirm`, and the one nested
inside `RejectGroupCommandHandler` — and **10 redemption sites across 9
handler files** (`InviteCommandHandler` has two: `invite:create:open`
and `invite:revoke`). Re-run both before starting; dispose of every row
explicitly, including the ones the chosen design leaves untouched, and
say which design left them untouched and why.

## Why the design decision cannot be deferred to the diff

The obvious fix — accept only the exact `/<command> confirm` form —
is wrong: `/quarantine reject <id> confirm` is a legitimate
argument-carrying confirm leg (M1-458 confirm-gates the forensic
`BENIGN_CLOSED` → `REJECTED` path only, and the id identifies which
quarantine row). So the fix has to *compare* the retyped arguments
against the stored payload rather than forbid them, and where that
comparison lives — on the `PendingConfirm` SPI or in each of the nine
handler confirm legs — is a real trade between one changed interface
and nine changed call sites. That is why this carries
`complexity: high` and takes a plan pass before code.
