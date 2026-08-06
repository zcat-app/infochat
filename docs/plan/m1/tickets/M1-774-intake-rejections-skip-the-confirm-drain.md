---
id: M1-774
title: "Intake rejections skip the confirm drain and the anchor clear"
status: done
created: 2026-08-06
last_updated: 2026-08-06
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - docs/spec/commands.md
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE SINGLE-LINE RULE'S OWN DRAIN. M1-772 already carried the
    step-4.5 drain across the multi-line rejection
    (`InboundRouter:768-780`), unconditionally and without re-using
    `isConfirmShape`. That branch is the reference implementation for
    whatever this ticket does to the other return paths — it is not
    re-opened, re-argued, or restructured.
  - >-
    `isConfirmShape` AND THE STEP-4.5 SWEEP BLOCK. The sweep's
    cancel-vs-leave-alone decision tree for bodies that DO reach it is
    correct and unchanged. This ticket is about return paths that never
    reach it.
  - >-
    THE LOOSE CONFIRM MATCH. `isConfirmShape` accepts any body that
    starts with `/<prefix> ` and ends with ` confirm`, so
    `/ban bob confirm` redeems a pending `/ban alice`. That is
    pre-existing, orthogonal to the ordering question here, and its own
    ticket if it is one at all — the handler's `takeMatching` is keyed
    on commandName by design.
  - >-
    THE TTL. `infochat.confirm.timeout` stays 60s (90s on `pi`). The
    window's length is not the defect; whether it closes early is.
acceptance:
  - >-
    THE DECISION IS MADE ONCE, FOR EVERY EARLY RETURN. Enumerate every
    `return` in `InboundRouter.onMessage` that precedes the step-4.5
    drain and decide, per path, whether it must drain. The known set is:
    the step-1.5 transport rate-cap drop, the step-2 DM invite gate, the
    step-3 group unregistered/preban drop, the step-4 ban reply, the
    step-3.5 group-approval short-circuit, the chat body cap
    (`:721-726`), the command body cap (`:733-738`), and the step-5
    probation block. Some are vacuous (a banned or unregistered sender
    cannot have armed a confirm); say which, and why, rather than
    draining defensively on a path where no entry can exist.
  - >-
    THE TWO BODY-CAP BRANCHES DRAIN, OR THE SPEC SAYS THEY DO NOT. These
    are the non-vacuous cases: an admin arms `/ban`, then sends an
    over-cap body, and the confirm stays armed for the rest of its TTL
    with no `reply.confirm.cancelled`. `takeAny` is an in-memory map
    removal, so draining does NOT violate §Input length caps' "no DB
    write for an oversized message" — that constraint binds the
    step-4.6 anchor clear, not this.
  - >-
    THE SPEC SENTENCE MATCHES THE CODE. `docs/spec/commands.md`
    §Surface conventions says the confirmation "is scoped to (user,
    scope) and any other input cancels it with an explicit
    acknowledgement". After this ticket that is either literally true,
    or the sentence is amended to state the exception set. Do not leave
    it overstated.
  - >-
    §/retry GETS THE SAME TREATMENT. "Any non-`/retry` input from the
    same (user, scope) clears the anchor" has at least four pre-existing
    exceptions — the ban reply, the probation block, the group-approval
    short-circuit and the step-1.5 rate-cap drop all return ahead of the
    step-4.6 clear, as does M1-772's single-line rejection. The clear is
    a DB write, so hoisting it is a real trade, not a free fix; the
    likely resolution is an amended sentence rather than moved code, but
    the enumeration must be done either way.
  - >-
    NAMED TESTS in `InboundRouterConfirmCancelTest` for whichever paths
    gain the drain, in the shape M1-772 established: assert the pending
    entry is drained, the cancellation is emitted BEFORE the rejection
    reply, and no handler dispatches.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  preserves:
    - >-
      The intake step order: the authorization gates (2/3/4/3.5) stay
      ahead of the body caps, and nothing moves ahead of them.
    - >-
      M1-772's single-line rejection and its drain, byte-for-byte.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
  - docs/spec/commands.md §Conversation control
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
      added: 821
      removed: 15
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-06
    category: DOS
    severity: low
    reported_by: [codex, opencode]
    promise: |
      docs/spec/security.md §Rate limiting: "Per-group reply rate (D47)
      — a single bucket per `groups` row bounding total outbound
      replies (fixed or command) within a sliding window", and step 3.5
      "Before sending any reply (fixed or command), check the per-group
      reply rate bucket."
    gap: |
      The bucket is drawn exactly once per admitted inbound, inside
      GroupApprovalCheck.check (GroupApprovalCheck.java:128); sendReply
      never consults it. Each drained rejection therefore emits two
      bodies — the cancellation acknowledgement and the rejection —
      against one token, on all three paths this ticket touches.
    repro: |
      In an approved group, arm a confirm (not admin-gated — /forget
      and /save are available to any registered user), then send an
      over-cap body or a probation-blocked command. One bucket draw,
      two adapter sends.
    suggested_fix_class: rate-limit
    disposition: |
      ACCEPTED AS STATED RESIDUAL (user decision, 2026-08-06). Real,
      but not introduced or widened here: the same one-token/two-send
      shape already exists on the M1-051 step-4.5 sweep and the M1-772
      multiline rejection, and arming a confirm costs its own token and
      its own reply, so the sustained ratio is unchanged. The genuine
      divergence is that security.md describes a per-REPLY check the
      implementation has never had (it is per-inbound); correcting that
      sentence is a security.md amendment this ticket does not own.
      claude examined the same question independently and returned
      CLEAN on it (verdict-claude.txt check 6).
  - date: 2026-08-06
    category: DOS
    severity: low
    reported_by: [opencode]
    promise: |
      The §/retry amendment this ticket itself added to
      docs/spec/commands.md: the listed intake rejections "return
      before the clear and leave the anchor intact — the clear is a DB
      write, and the pre-parser rejections commit to no DB writes for
      the bodies they drop."
    gap: |
      The blanket write-free clause is false. Step 4.1 runs
      groupAutoPromoteService.tryAutoPromote + ensureGroupMembership
      (InboundRouter.java:835-836) BEFORE the step-5 probation gate, so
      a group inbound reaching the probation block has already written.
      Verified in-branch to be broader than reported: the DM invite
      gate INSERTs an invite_code_attempt row on every attempt
      (InviteCodeConsumer.java:118) and the group-approval gate may
      INSERT the pending groups row. Three of the ten listed paths, not
      one. The list itself is correct — all ten do leave the anchor
      intact; only the shared rationale was wrong.
    repro: |
      A probation user @mentions an approved group with a
      probation-blocked command: membership is upserted at step 4.1,
      then step 5 blocks and returns ahead of the step-4.6 clear. The
      spec sentence added by this diff tells the reader that path
      commits no DB write.
    suggested_fix_class: other
    disposition: |
      FIXED IN SCOPE (user decision, 2026-08-06). The §/retry rationale
      is now per-path rather than blanket: the body-length caps and the
      single-line rule are write-free because §Input length caps
      forbids a DB write for a body that never reached the parser; the
      remaining paths stop at their own gate, and the three that have
      already written are named. Same overstatement class as the
      §Surface conventions sentence corrected earlier in this ticket —
      an accurate exception list carrying an inaccurate shared reason.
  - date: 2026-08-06
    category: AUTH-BYPASS
    severity: low
    reported_by: [opencode]
    promise: |
      commands.md §Surface conventions: a pending confirmation "is
      scoped to (user, scope) and any other input cancels it" — the
      property that stops a pending destructive confirm being redeemed
      by input the arming admin did not intend.
    gap: |
      As reported: the M1-038 transport byte cap (InboundRouter:538)
      fires before the users-row read and does not drain, so a >64KiB
      body leaves the entry redeemable with stale args.
    repro: |
      As reported: arm /ban A, paste a >64KiB body, then send
      /ban B confirm — A is banned.
    suggested_fix_class: trust-boundary-tightening
    disposition: |
      NOT A FINDING AGAINST THIS DIFF (user decision, 2026-08-06).
      Falsified in-branch: the oversized body is not load-bearing.
      isConfirmShape (InboundRouter.java:1686-1693) matches on prefix
      and suffix only, so `/ban A` followed directly by
      `/ban B confirm` bans A with no oversized body anywhere in the
      sequence — delete step 2 of the repro and the outcome is
      identical. A byte-capped message also leaves state identical to
      no message at all, so it grants nothing. What survives is the
      loose match itself, which this ticket fences in out_of_scope and
      which is now FILED AS M1-775 (commit 4657b992) together with the
      lazy-expiry item below.
  - date: 2026-08-06
    category: OUT-OF-MODEL
    severity: low
    reported_by: [claude]
    promise: |
      Not a threat-model promise — raised as an out-of-model
      observation for the user to rule on.
    gap: |
      ConfirmStateService's ConcurrentHashMap expires entries only
      lazily, inside peek / takeAny / takeMatching. A (user, scope)
      that arms a confirm and never messages again retains its payload
      past the deadline for the process lifetime; BanConfirm carries a
      target contact id.
    repro: |
      Arm any confirm-gated command, then never message again. The
      entry outlives its TTL until process exit.
    suggested_fix_class: other
    disposition: |
      ACCEPTED AS RESIDUAL, AND FILED (user decision, 2026-08-06).
      Pre-existing and untouched here — this diff strictly increases
      eviction opportunities by adding three drain sites. Not a flood
      vector: one entry per (user, scope), creatable only by a
      registered user running a confirm-gated command, so growth is
      bounded by distinct user/scope pairs rather than by message
      volume. §Secrets handling governs logs, the audit log and the
      DB, not process-local maps, and security.md:2200-2210 already
      accepts the analogous in-memory translation-cache residual — so
      no spec sentence is added. Bundled into M1-775 as the secondary
      item because it is the same object's lifecycle.
  - date: 2026-08-05
    category: OUT-OF-MODEL
    severity: none
    reported_by: [opencode]
    promise: |
      Not a threat-model promise — raised as an out-of-model
      observation about disclosure timing.
    gap: |
      As reported: the caps-path drain surfaces the cancellation
      acknowledgement one message earlier in group scope, so members
      learn a confirm was pending sooner than they would have.
    repro: |
      As reported: hold a pending confirm in a group, send an over-cap
      body, and the group sees "Pending `<command>` cancelled."
    suggested_fix_class: other
    disposition: |
      FALSE POSITIVE — recorded as such, NOT as an accepted risk (user
      decision, 2026-08-06). There is no confirm-existence secrecy to
      erode: the arming prompt is itself emitted with reply(scope, …)
      into the same group the confirm is keyed to, so the group has
      already been told that a confirm was armed and which command it
      was. The cancellation acknowledgement repeats information the
      prompt disclosed a minute earlier. This holds in the
      silent-TTL-expiry case too — the prompt was visible regardless
      of whether an acknowledgement ever follows.
redteam_audits:
  - date: 2026-08-05
    verdict: FINDINGS
    auditors: [codex, opencode, claude]
    base: 51c9aead
    head: working tree
    verdict_file: docs/plan/m1/redteam-multi/M1-774-2026-08-05/
    findings_count: 2
    out_of_model_count: 1
    note: |
      First multi-auditor pass, run before the probation-block drain
      existed. claude returned UNAVAILABLE (host session limit), so
      this pass is 2-auditor coverage. codex raised the per-group
      reply-bucket ratio (medium); opencode raised a stale-payload
      redemption via the M1-038 transport byte cap. The latter was
      falsified in-branch as attributable: its >64KiB step is not
      load-bearing — `/ban A` then `/ban B confirm` executes against A
      with no oversized body anywhere, because isConfirmShape matches
      on prefix+suffix only. That is the loose confirm match this
      ticket fences in out_of_scope.
  - date: 2026-08-06
    verdict: FINDINGS
    auditors: [claude, codex, opencode]
    base: 51c9aead
    head: working tree
    verdict_file: docs/plan/m1/redteam-multi/M1-774-r2-2026-08-05/
    findings_count: 2
    out_of_model_count: 1
    note: |
      Re-audit of the diff after the probation-block drain and the
      enumeration rewrite. claude CLEAN — it checked gate ordering,
      per-(user, scope) keying, the no-DB-write claim, enumeration
      completeness, and whether a surviving confirm can be cashed for
      privilege (it cannot: BanCommandHandler.java:179 re-checks
      isAdmin before takeMatching). codex and opencode both re-raised
      the reply-bucket ratio; the cross-examination report scores it
      "0 corroborated" only because it clustered the two by differing
      file:line anchors. opencode additionally found the §/retry
      write-free clause. Both dispositioned above. Its OUT-OF-MODEL
      item (ConfirmStateService's map has lazy expiry only, so an
      entry from a user who never messages again outlives its TTL for
      the process lifetime) is pre-existing and untouched here.
clarity_check:
  date: 2026-08-06
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-774: Intake rejections skip the confirm drain and the anchor clear

## Context

Filed 2026-08-06 out of M1-772's red-team round 1, which found the
then-new multi-line rejection returning ahead of the step-4.5 confirm
drain. That instance was fixed in M1-772. This ticket is the general
case the fix deliberately did not touch.

## The defect

`docs/spec/commands.md` §Surface conventions promises that a pending
confirmation "is scoped to (user, scope) and any other input cancels it
with an explicit acknowledgement". The implementation delivers a
narrower promise: *any input that reaches step 4.5* cancels it. Several
paths return before that point, and on those the user's armed `/ban` or
`/invite create --open` survives, silently, for the rest of its TTL.

The non-vacuous instances are the two body caps
(`InboundRouter:721-726` and `:733-738`). An admin who arms a
destructive confirm and then sends an over-cap body gets no
cancellation and no acknowledgement; the confirm remains redeemable
until the 60s deadline. The other early returns — ban, unregistered,
preban, unapproved group — are almost certainly vacuous, because a
sender on those paths cannot have armed a confirm in the first place,
but "almost certainly" is exactly what this ticket is for: the
enumeration should be written down once rather than re-derived by the
next reader.

§/retry carries the same shape. "Any non-`/retry` input from the same
(user, scope) clears the anchor" is false for at least four
pre-existing return paths plus M1-772's. The difference is that the
anchor clear is a **DB write**, and §Input length caps forbids a DB
write for a body that never reaches the parser — so hoisting it is a
genuine trade rather than a free correction. The confirm drain has no
such obstacle: `ConfirmStateService.takeAny` is a `ConcurrentHashMap`
removal.

## Why this is low severity, and why it is still worth doing

Nothing here is cross-principal. `PendingConfirm` is keyed by
`(actorUserId, scope)`, so no third party can arm, prolong, or redeem
another actor's confirm, and a rejected body dispatches nothing so it
cannot redeem the armed payload itself. The TTL bounds the exposure at
60 seconds and is not extended by the rejected message.

What is worth doing is the honesty: the spec currently states a
property the code does not have, on the primitives the threat model
singles out as having the broadest blast radius. Either the code should
match the sentence or the sentence should match the code — and one
ticket should decide which, for every path at once, rather than each
new intake rejection re-litigating it. M1-772 is the precedent for how
easily that happens: a new early return inherited the gap without
anyone noticing until the red-team pass.

## Reference implementation

`InboundRouter:768-780` (M1-772, commit `444b41fb`). Note the drain
there is **unconditional** — it does not re-use `isConfirmShape`,
because a multi-line body can satisfy that predicate
(`/ban x\nnote confirm` starts with the prefix and ends with
` confirm`) while still being rejected. Any path that gains the drain
in this ticket has the same property and should drain the same way.

## Notes

- M1-772's `redteam_findings[0].disposition` and its §Notes both name
  this follow-up; the round-2 verdict at
  `docs/plan/m1/redteam/M1-772-2026-08-06-r2.md` records the same gap
  as out-of-model item 2 with the bounded-exposure argument spelled
  out.
- The red-team round-2 pass also verified there is no confidentiality
  consequence to a surviving anchor: §/retry's "Status filter on the
  frozen UID set" re-filters the anchor's UIDs against current post
  status at retry time, so an anchor that outlives a rejected message
  cannot re-surface a post quarantined in the meantime.
