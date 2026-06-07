---
id: M1-218
title: "Provider lows: /retry in-flight reply, /invite list-vs-revoke code identity, handle-keyed slot release"
status: done
created: 2026-06-07
last_updated: 2026-06-08
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 13
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/CancellationService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the rate-cap acquisition /retry is missing — M1-183's (it wires tryAcquireLlmRateCap into RetryCommandHandler; same file — serialize)
  - /stop wiring, statement timeouts, and everything else in the chat package — M1-193's (complexity high); this ticket's chat-package and SummaryCommandHandler edits are limited to InFlightTracker's handle-keyed acquire/release API and the mechanical release/tryAcquire call-site migration it forces (deliberate M1-193 overlap accepted 2026-06-08 — M1-193 rebases over this)
  - the group-scope caller-resolution trap in InviteCommandHandler (error.admin_only for a real admin in a group) — M1-198's (same file — serialize)
  - the remaining audit P22/P18 members — recorded as deliberate drops in the batch summary with per-item rationale (ConfirmStateService sweep, tool LIMIT clamp inconsistency, InboundRouter test-subclass guards, admin-handler dedup [deferred until M1-195/M1-198/M1-206 land], /export heap footprint, InMemoryAdapter items)
  - confirm-flow semantics for /invite revoke — the ConfirmStateService handshake stays exactly as shipped; only the code-identity matching changes
acceptance:
  - "/retry while the caller's previous request is still in flight replies with a message naming the in-flight condition, not error.retry.no_anchor: a named test drives tryAcquire failure and asserts the reply is distinguishable from the nothing-to-retry case (today :175-176 returns ERROR_RETRY_NO_ANCHOR for both, telling a user with a live request that there is nothing to retry); both localization bundles carry the new key (D43 build-time completeness)"
  - "/invite list and /invite revoke agree on code identity: an admin can revoke an invite using exactly what /invite list displays — EITHER revoke accepts the displayed 8-char prefix (with a defined ambiguous-prefix rejection) OR list displays the full code revoke requires; named test round-trips list output into a successful revoke (today list prints code.substring(0,8) while revoke matches the full UUID only, so the displayed handle can never revoke)"
  - "In-flight slot release is handle-keyed: a stale release from a finishing worker cannot evict a newer holder's slot — after /stop (or any release/re-acquire interleaving), a late release by the previous worker leaves the new holder's slot intact, pinned by a named test (today InFlightTracker.release is an unconditional map remove by scope key)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D43
  - D44
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 235
      removed: 47
revisions:
  - date: 2026-06-08
    reason: budget-breach rework — leg 3's handle-keyed release changes InFlightTracker's API shape; the call-site sweep (ChatAgent, SummaryCommandHandler, CancellationService) was missing from files_scope
    prior_values: |
      files_budget: 9
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
        - infochat-provider/src/main/resources/bundles
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
        - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
      out_of_scope[1]: "/stop wiring, statement timeouts, and everything
        else in the chat package — M1-193's (complexity high;
        InFlightTracker sits in its directory scope — serialize)"
      (Refine adds the three release/tryAcquire call-site files to
       files_scope, raises files_budget 9 → 12, and narrows the
       chat-package out_of_scope item to M1-193's actual work items so
       the mechanical call-site migration is unambiguously in scope.
       Acceptance unchanged.)
  - date: 2026-06-08
    reason: budget-breach rework #2 — SummaryCommandHandlerTest (field named `tracker`, missed by the `inFlightTracker.` sweep pattern) is a direct tryAcquire/release caller forced to adapt by the handle-keyed API; it lies inside the scoped command-test directory
    prior_values: |
      files_budget: 12
      (Refine raises files_budget 12 → 13. files_scope, out_of_scope,
       and acceptance unchanged — the overflow file is inside the
       already-scoped infochat-provider/src/test/.../command directory.)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations:
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation developer trigger. Acceptance leg 3
      (handle-keyed slot release) changes InFlightTracker.release's
      shape; the call-site sweep finds release/tryAcquire callers in
      chat/ChatAgent.java (:122,:134), command/SummaryCommandHandler.java
      (:183,:239), and chat/CancellationService.java (:65) — none in
      files_scope. Clean remove(key,handle) fix touches 12 files vs
      files_budget 9. In-scope-only alternatives leave the unconditional
      release in place for the chat and /summary paths or trade
      simplicity for an implicit per-thread acquisition record.
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — developer trigger, post-implementation pre-review. Diff is 13
      files vs files_budget 12. Overflow file: SummaryCommandHandlerTest
      (field named `tracker`, missed by the first call-site sweep's
      `inFlightTracker.` pattern; uses tryAcquire/release directly, so the
      handle-keyed API change forces its adaptation). It lies inside the
      scoped command-test directory — files_scope is respected; only the
      numeric budget is exceeded. mvn verify green at 13 files.
---

# M1-218: Provider lows

## Context

The provider members of the audit's misc-lows bucket that survived
triage (unified P22 subset — `deep-code-review/v2/UNIFIED.md` §2; all
were ACCEPTED-tier, so this draft's re-grounding 2026-06-07 is their
first independent verification — all three held):

1. **Misleading /retry reply.** RetryCommandHandler returns
   ERROR_RETRY_NO_ANCHOR on tryAcquire failure (:175-176) — the same
   reply as "nothing to retry" — when the true condition is "your
   previous request is still running". User-visible wrong guidance.
2. **/invite list-vs-revoke mismatch.** list renders an 8-char code
   prefix (:475, :550); revoke's SQL matches the full code only — the
   handle the bot shows an admin can never be used to revoke.
3. **Stale slot release.** InFlightTracker.release does an
   unconditional remove by (user, scope) key; a worker's late finally
   release after /stop + re-acquire evicts the NEW request's handle,
   silently disabling its cancellation.

Triage drops (everything else in P22/P18 and the items parked here by
batch-2 tickets) are recorded per-item in the batch summary, not
silently skipped.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T33 under `deep-code-review/v2/` (provider
  members; kimi-folder prov F9/F11/F12 subset).
- **Serialization:** all three main files overlap pending tickets —
  RetryCommandHandler (M1-183), InFlightTracker via the chat package
  (M1-193, complexity high), InviteCommandHandler (M1-198). Run this
  ticket after those land, or rebase deliberately; the legs are small
  and the overlap is the reason this ticket stays small.
- The slot-release fix shape (remove(key, handle) semantics) follows
  from the defect statement; choosing it or an equivalent is the
  implementer's call.
