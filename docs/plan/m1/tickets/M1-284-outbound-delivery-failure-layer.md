---
id: M1-284
title: "Outbound delivery failure layer: retry, cap escalation, cleanup"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 26
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RejectGroupCommandHandler.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RejectGroupCommandHandlerTest.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingException.java
  - docs/design/06-messaging.md
complexity: high
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-284.md
out_of_scope:
  - Adapter-side classification fixes (SimpleX classifyError substring matching is M1-294; adapters MUST NOT grow their own retry wrapper — the spec's "No silent extension" rule).
  - The edit/finalize fallback-to-fresh-send path (M1-285) — this ticket owns fresh-send failure handling; M1-285 owns the in-place-edit failure fork.
  - "Chat-memory write ordering on PERMANENT delivery failure (the spec's 'chat_memory is not written' / 'the context window remains as if the message was never generated' clause). ChatSessionRepository.persistTurn commits chat_message/chat_session inside ChatAgent.handle BEFORE the reply reaches the send-site chokepoint, so honoring it requires reordering chat-turn persistence + auto-compress to after send-success in provider/chat — owned by M1-313."
  - §6.12 AdapterMetrics counters — no metrics surface exists (M1-305 decides schedule-or-amend).
  - Native membership events (supportsMembershipEvents=true adapters) — only the permanent-failure-driven fallback path is in scope.
  - Group state purge beyond what the spec commits — the spec says "Group state … is not purged automatically"; cleanup of long-removed groups is a v2 admin command.
acceptance:
  - "A single Provider-side outbound-delivery chokepoint exists and InboundRouter, StageProgressNotifier (terminal sends), DigestWorker, and command reply paths (ApproveGroupCommandHandler, RejectGroupCommandHandler) route sends through it; a grep over infochat-provider/src/main shows no direct adapter send/update/finalize call outside the chokepoint (test code exempt)."
  - "TRANSIENT failures are retried per spec (messaging.md §Failure handling, verbatim: 'Maximum attempts: 3 (the original send plus two retries)', 'exponential back-off with full jitter'; start delay/growth/jitter window are profile-driven design values): a named test delivers on attempt 2 after one TRANSIENT failure; a named test asserts a third consecutive TRANSIENT failure stops retrying."
  - "Cap exhaustion escalates per spec (verbatim: 'the failure is escalated to permanent for the rest of this reply's lifecycle … and the throttled-admin-notification path (security.md §Failure handling) fires per (channel, error_class)'): a named test asserts the throttled admin notification fires exactly once per (channel, error_class) window on exhaustion and the user is not pinged."
  - "PERMANENT failures abort the affected reply immediately and are never retried (spec messaging.md §Failure handling: permanent failures 'abort the affected reply ... never retried'; 'The retry queue does not re-attempt permanent failures'): a named test asserts a PERMANENT failure is not retried and the reply is aborted. The spec's 'chat_memory is not written' half is out of scope here (see out_of_scope / M1-313) — it is unreachable at a send-site chokepoint because chat-turn persistence commits inside ChatAgent.handle before the reply reaches the send site."
  - "Bot-removed cleanup per spec: repeated permanent group-send failures past a profile-driven threshold (spec: 'The permanent-failure threshold is always greater than 1') set groups.removed_at = NOW() and cancel the periodic-digest scheduler entries for that group; a named test crosses the threshold and asserts both effects; a second named test asserts a single permanent failure does NOT trigger cleanup."
  - "User-left cleanup per spec §Failure handling 'User left group': a permanent send failure attributed to a specific group member soft-clears the group_membership row (removed_at = NOW()), preserves chat_memory/chat_session/summary_anchor/subscription rows, and clears is_group_admin in the same transaction when the departing user was group admin; a named test covers the admin case."
  - "MessagingException's javadoc (currently naming a nonexistent 'outbound retry layer') names the real layer landed by this ticket."
  - "The cap-exhaustion admin notification must not leak the raw transport exception body: OutboundDelivery.onCapExhausted passes only channel, error_class, and attempt count to ThrottledAdminNotifier.notifyOnce — never last.getMessage() (security.md §'User content in exceptions'/D37: exception message bodies never reach non-audit logs; SafeLog is the mandated path, and onCapExhausted's own second log call already uses SafeLog.warn). A named test asserts the captured ADMIN-NOTIFY message for an exhausted (channel, error_class) omits the exception message body."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 26
      added: 1043
      removed: 71
  - round: 2
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 29
      added: 1366
      removed: 88
escalations:
  - date: 2026-06-11
    reason: outline-fail
    reviewer_verdict_excerpt: |
      OUTLINE FAILED — two acceptance items unsatisfiable within files_scope.
      (1) Item 1's chokepoint grep over infochat-provider/src/main hits direct
      adapter sends in provider/command (ApproveGroupCommandHandler.java:258,
      RejectGroupCommandHandler.java:302) — provider/command NOT in files_scope.
      (2) Item 4's "chat_memory is not written on PERMANENT failure" is
      unreachable: ChatAgent.handle calls ChatSessionRepository.persistTurn
      (step 7), which opens its own connection and commits chat_message/
      chat_session rows BEFORE the reply string returns to InboundRouter →
      sendReply (the only outbound-send site). A send-site chokepoint has no
      transactional relationship to the already-committed chat_memory write;
      provider/chat is NOT in files_scope.
      Suggested escalation: refine (widen files_scope to include
      provider/command + provider/chat and re-scope the chat-memory ordering,
      OR narrow items 1 and 4). Two sound items to preserve on refine: V5
      trigger trg_group_membership_clear_admin already clears is_group_admin on
      removed_at NULL→non-NULL; DigestScheduler.queryActiveGroups() filters
      removed_at IS NULL, so GroupRepository.markRemoved alone achieves both
      cleanup effects in items 5/6.
  - date: 2026-06-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer-raised). Implementation complete and mvn -B clean verify
      green, but the diff touches 26 files vs files_budget: 22. Every file is
      within files_scope; the 4-over is mechanical test-wiring: injecting the
      new OutboundDelivery chokepoint bean into InboundRouter and DigestWorker
      forces wiring `outboundDelivery` into every plain-JUnit test that
      constructs those beans directly (9 InboundRouter*Test + DigestWorkerTest
      + StageProgressNotifierTest). User chose refine → bump files_budget to 26.
  - date: 2026-06-12
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      /redteam M1-284 --in-progress returned FINDINGS: 1 medium INFO-LEAK
      (critical=0 high=0 medium=1 low=0; out-of-model=2). OutboundDelivery
      .onCapExhausted interpolates last.getMessage() (raw adapter
      MessagingException message) into ThrottledAdminNotifier.notifyOnce,
      which emits it on the application logger; the notifier strips control
      chars + truncates but does NOT drop the body or apply the Redactor,
      bypassing the SafeLog body-drop that security.md §"User content in
      exceptions"/D37 mandates. The method's own second log call already uses
      SafeLog.warn. Full finding: redteam_findings[0] +
      docs/plan/m1/redteam/M1-284-2026-06-12.md. User chose refine.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-12
    category: INFO-LEAK
    severity: medium
    promise: |
      security.md §Secrets handling — "User content in exceptions":
      "Exception messages and stack traces emitted via the application
      logger MUST NOT contain user-authored prose (chat-mode message
      bodies, post bodies, saved-post annotations, command arguments).
      The application provides a `SafeLog` utility that drops the
      exception message body, retains only the exception class name...
      The original `Throwable` is never passed to the underlying SLF4J
      logger." (and §Secrets handling: chat-mode message bodies / saved
      bodies / annotations "never appear in non-audit logs, at any log
      level (decision D37)").
    gap: |
      OutboundDelivery.onCapExhausted (infochat-provider/.../messaging/
      OutboundDelivery.java, diff lines 463-475 — the new file's
      onCapExhausted method) builds the admin-notification message by
      interpolating the raw adapter exception message:
          adminNotifier.notifyOnce(key, errorClass,
              "Outbound delivery to channel " + channel
              + " exhausted the retry budget (" + maxAttempts
              + " attempts) and was escalated to permanent: "
              + last.getMessage());
      `last` is the MessagingException raised by the transport adapter at
      its throw site. The spec's whole reason for SafeLog is that callers
      MUST NOT trust an exception message to be free of user-authored prose
      — SafeLog.formatSafe (infochat-core/.../log/SafeLog.java:68-84)
      deliberately appends only `t.getClass().getName()` and the cause-chain
      class names, never `t.getMessage()`. The SECOND log call in
      onCapExhausted correctly uses SafeLog.warn(log, "...", last) (drops the
      body), but the FIRST call routes `last.getMessage()` verbatim into
      ThrottledAdminNotifier.notifyOnce, which emits it as an
      `ADMIN-NOTIFY key=... error=... message=%s` WARN line on the
      application logger (ThrottledAdminNotifier.runNotify:305-309). The
      notifier's sanitize() (ThrottledAdminNotifier.java:120-127) only strips
      control chars and truncates to 2048 chars; it does NOT drop the
      exception body the way SafeLog mandates, and does not apply the API-key
      Redactor either. The admin-notify WARN line is a non-audit application
      log line, so D37's "never in non-audit logs at any level" applies.
    repro: |
      An adversary repeatedly drives a reply-producing inbound (e.g. a
      chat-mode message or a DM reply) to a scope whose transport is
      returning TRANSIENT failures for every send (a transport rate-limit /
      "try again later" condition the adversary can induce by flooding at
      the per-user cap, or that occurs naturally under transport
      back-pressure). After the 3-attempt budget exhausts, onCapExhausted
      fires. If the adapter's MessagingException message echoes any portion
      of the rejected outbound payload or a transport "policy violation"
      string that quotes the message body (a documented PERMANENT/TRANSIENT
      example in design 06-messaging.md §6.3.6 is "message rejected as
      policy violation by the transport" / "transport-side oversize
      rejection"), that prose — derived from the user-facing reply content,
      which can in turn contain user-supplied chat text echoed back — lands
      verbatim in the ADMIN-NOTIFY WARN line. The spec forbids exactly this
      and supplies SafeLog so no caller has to reason about whether a given
      adapter's exception message is clean. The chokepoint bypasses that
      guarantee on the one branch where it interpolates getMessage().
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-06-12
    verdict: FINDINGS
    base: f2c24950
    head: "working-tree (branch m1/M1-284-outbound-delivery-failure-layer, uncommitted)"
    verdict_file: docs/plan/m1/redteam/M1-284-2026-06-12.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      In-progress audit (--in-progress; working-tree vs fork point f2c24950).
      One medium INFO-LEAK: OutboundDelivery.onCapExhausted routes
      last.getMessage() (raw adapter MessagingException message) into
      ThrottledAdminNotifier.notifyOnce, which emits it on the application
      logger; the notifier strips control chars + truncates but does NOT drop
      the body or apply the Redactor, bypassing the SafeLog guarantee that
      security.md §"User content in exceptions" / D37 mandates for exception
      messages on non-audit logs. Verified real against source. Two
      out-of-model notes (advisory): synchronous retry back-off occupies the
      single per-adapter dispatch thread under sustained TRANSIENT failure;
      system-initiated group soft-removal writes no audit row. Recorded on the
      branch (squashes into M1-284); fix decision held for in-worktree
      discussion.
revisions:
  - date: 2026-06-11
    reason: outline-fail rework
    snapshot:
      status: escalated
      escalation_reason: outline-fail
      files_budget: 18
      files_scope_at_snapshot:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
        - infochat-provider/src/main/java/app/zcat/infochat/provider/digest
        - infochat-provider/src/main/java/app/zcat/infochat/provider/group
        - infochat-provider/src/main/resources/application.properties
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
        - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingException.java
        - docs/design/06-messaging.md
      clarity_check:
        date: 2026-06-11
        verdict: WARN
        warnings:
          - "SECURITY-FLAG-CONSISTENT: security_relevant: false but the user-left cleanup path clears is_group_admin in a transaction (admin-tier gate)."
          - "SELF-CONTAINED-CHECK: body §Acceptance re-read instruction; 'Group deleted upstream' path has no named test."
      outline_fail_blockers:
        - "Item 1 chokepoint grep over infochat-provider/src/main hits direct adapter sends in provider/command (ApproveGroupCommandHandler.java:258, RejectGroupCommandHandler.java:302) — provider/command not in files_scope."
        - "Item 4 'chat_memory not written on PERMANENT failure' unreachable: ChatSessionRepository.persistTurn commits chat_message/chat_session before the reply returns to the send site; provider/chat not in files_scope."
      resolution: |
        Refine (widen item 1, peel off item 4's chat-memory half), confirmed
        at source 2026-06-11:
        (1) Added provider/command/ApproveGroupCommandHandler.java +
        RejectGroupCommandHandler.java (main + tests) to files_scope and bumped
        files_budget 18→22 so both command-reply sends route through the
        chokepoint — item 1's own text already names "command reply paths", so
        this corrects a files_scope omission rather than expanding scope.
        (2) Narrowed item 4 to its reachable half (no-retry + abort) and moved
        the "chat_memory is not written" clause to out_of_scope, owned by the
        new M1-313 (reorder chat-turn persistence + auto-compress to after
        send-success in provider/chat). Items 2/3/5/6/7/8 unchanged.
  - date: 2026-06-12
    reason: budget-breach rework
    snapshot:
      status: in-progress
      escalation_reason: budget-breach
      files_budget: 22
    resolution: |
      Refine (files_budget 22→26), confirmed against the green round-1 diff
      2026-06-12. Acceptance, files_scope, and out_of_scope are UNCHANGED — the
      bump is purely a count correction. The diff is 26 files, all within
      files_scope: 9 production/doc files + 6 new test files (OutboundDeliveryTest,
      OutboundDeliveryCleanupIT, FailingMessagingAdapter, RecordingAdminNotifier,
      RecordingGroupRepository, TestOutboundDelivery) + 11 test files that only
      gain a one-line `outboundDelivery` wire (StageProgressNotifierTest, the 9
      InboundRouter*Test that construct the router directly, DigestWorkerTest).
      The call-site sweep for the new injected chokepoint dependency drove the
      over-budget breadth; acceptance item 1 (all sends route through the
      chokepoint) is unsatisfiable without the InboundRouter/DigestWorker
      injection that forces it.
  - date: 2026-06-12
    reason: redteam-finding rework
    snapshot:
      status: escalated
      escalation_reason: redteam-finding
      files_budget: 26
    resolution: |
      Refine — added one acceptance item (the cap-exhaustion admin
      notification must omit the raw transport exception body) closing the
      medium INFO-LEAK in redteam_findings[0]. Acceptance items 1-8,
      files_scope, out_of_scope, and files_budget are otherwise UNCHANGED:
      the fix is a one-expression edit in OutboundDelivery.onCapExhausted
      (drop "+ last.getMessage()") plus a test assertion in an existing
      messaging test — no new files, no budget change. security_relevant is
      already true. Re-implement on the existing branch, then round-2 review.
clarity_check:
  date: 2026-06-11
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 7: MessagingException javadoc check is by-inspection, weaker than the grep/named-test bar of the other items (not a blocker)."
    - "SECURITY-FLAG-CONSISTENT: security_relevant: false should be true — acceptance item 6 clears is_group_admin in a transaction (admin-tier gate). Flagged in two consecutive clarity passes."
    - "SELF-CONTAINED-CHECK: body 're-read §Failure handling in full' treats messaging.md as load-bearing; the 'Group deleted upstream' path (group-not-found permanent failure → removed_at + scheduler cancel) has no acceptance item or named test. Flagged in two consecutive passes."
---

# M1-284: Outbound delivery failure layer: retry, cap escalation, cleanup

## Context

Deep-review v5 verified HIGH **U-01** (`deep-code-review/v5/UNIFIED-REPORT.md`
§2; sources `deep-code-review/v5/fable-5/01-architecture.md#F1`,
`deep-code-review/v5/gpt-55/report.md#H-02` — gitignored, primary checkout
only; provenance, not needed for implementation: everything load-bearing is
inlined here):

`MessagingException.category()` (TRANSIENT/PERMANENT) has producers in both
production adapters and **zero consumers** — grep of
`infochat-provider/src/main` finds no `FailureCategory` reference (verified
2026-06-11). The spec's entire §Failure handling contract (retry budget,
cap-exhaustion escalation, permanent-failure-driven cleanup) is
unimplemented: `InboundRouter` (~:860) and `DigestWorker` (~:123) catch and
log only. Every transient send failure today permanently drops the reply.
Because SimpleX declares `supportsMembershipEvents=false`,
permanent-failure-driven cleanup is the **only** spec'd membership-cleanup
mechanism for the flagship adapter — departed users and admins keep live
`group_membership` rows indefinitely.

## Acceptance

See frontmatter. The spec sentences quoted there are transcribed from
`docs/spec/messaging.md` §Failure handling (verified verbatim 2026-06-11) —
every load-bearing clause is inlined in the acceptance items above; treat
those items as the contract. The one spec case NOT inlined here is "Group
deleted upstream" (group-not-found permanent failure → `removed_at` +
scheduler cancel): it is consciously deferred to M1-314, because
distinguishing the group-not-found signal from a generic PERMANENT failure
requires the adapter-side error classification owned by M1-294 (out of
scope). Treated as a generic permanent failure today, it already rides the
item-5 threshold path. The §Failure handling section remains the background
reference, but no clause beyond the deferred one is load-bearing outside the
inlined items.

## Out-of-scope

See frontmatter — adapter-side classification (M1-294), edit fallback
(M1-285), chat-memory write ordering on permanent failure (M1-313), metrics
(M1-305).

## Notes

- **⚠ User decision at start:** both top reports insist this must not stay
  implicit. Default = **implement in v1** (written into acceptance): the spec
  text is unambiguous and SimpleX membership cleanup depends on it. The
  alternative the report allows is an explicit spec amendment deferring the
  whole §Failure handling contract to v2 — if the user picks that, refine
  this ticket to a doc-only amendment; do not implement half.
- `complexity: high` — expect a plan-writer outline before code. The fix
  sketch from fable-5: one `OutboundDelivery` bean wrapping
  `MessagingAdapter` send/update/finalize; retry loop on TRANSIENT with
  jittered backoff; per-(group) and per-(group,member) permanent-failure
  counters feeding `groups.removed_at` / membership soft-clear; throttled
  admin notification on cap exhaustion (ThrottledAdminNotifier exists in
  core).
- **Item 1 routing (command reply paths):** ApproveGroupCommandHandler:258
  and RejectGroupCommandHandler:302 send a group approval/rejection
  announcement via `targetAdapter.send(msg)` directly today; route both
  through the chokepoint — they are the "command reply paths" named in
  acceptance item 1. Their existing tests (ApproveGroupCommandHandlerTest,
  RejectGroupCommandHandlerTest) do not assert on `.send(`, so the
  rerouting is low-friction (verified 2026-06-11).
- **Item 4 split (M1-313):** the spec's "chat_memory is not written on
  permanent failure" clause is unreachable at a send-site chokepoint —
  ChatAgent.handle persists both turns and runs auto-compress before
  returning the reply string that the chokepoint later sends. Reordering
  that lifecycle to after send-success is owned by M1-313 (blocked on this
  ticket's chokepoint existing); this ticket keeps only the no-retry/abort
  half of item 4.
- Profile-driven values (backoff start/growth/jitter, permanent-failure
  threshold) belong in `application.properties` per profile + design 06
  documents them (spec commits the shape only).
- Coordination: M1-285 (edit fallback) is the other half of the
  silent-reply-loss story (fable-5 CT1). Sequence consciously — this ticket
  first is the natural order; if M1-285 lands first, its fallback sends
  must route through this chokepoint when it exists.
- The DigestWorker/group package touch is for cleanup wiring
  (digest-scheduler cancellation on bot-removed); keep the diff surgical.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-284-*.md
```
