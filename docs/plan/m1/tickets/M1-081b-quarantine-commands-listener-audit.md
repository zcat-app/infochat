---
id: M1-081b
title: Quarantine admin commands + review listener + /audit
status: done
created: 2026-05-25
last_updated: 2026-05-26
blocked_by:
  - M1-081a
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewReconciler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AuditCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewListenerTest.java
  - infochat-provider/src/main/resources/bundles/en.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-collector/** — Collector-side re-eval job, TTL job, NOTIFY emit, and tagger partial-valid are M1-081a
  - infochat-core/src/main/resources/db/migration/** — V21 migration is M1-081a
  - any change to NewPostListener.java or NewPostReconciler.java — the quarantine_review listener is a parallel, independent listener; it does not modify the existing new_post path
  - any change to InboundRouter.java — the new command handlers are discovered by CDI like all existing handlers
  - any modification to existing command handler tests (BanCommandHandlerTest, GrantAdminCommandHandlerTest, etc.) — existing tests continue to pass unchanged
  - any cross-source linking commands or entity references — not T2-G scope
  - infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java — M1-081 umbrella
acceptance:
  - QuarantineCommandHandlerTest.listDefault_showsPendingRows passes — /quarantine list defaults to PENDING rows; reply includes quarantine id, post uid, flagged_by, flagged_at, and rule_id for each row
  - QuarantineCommandHandlerTest.listAll_showsAllStatuses passes — /quarantine list --all (bot-admin only) lists every status including BENIGN_CLOSED, APPROVED, REJECTED
  - QuarantineCommandHandlerTest.list_nonAdmin_rejected passes — non-admin caller receives the error.admin_only response
  - QuarantineCommandHandlerTest.approve_transitionsPendingToApproved passes — /quarantine approve <id> calls approve_quarantine(id, actor_id) stored procedure; quarantine row transitions PENDING→APPROVED; original_html restored into post body; NOTIFY new_post fires for the post so Provider re-renders the unredacted body via the standard high-water-mark path
  - QuarantineCommandHandlerTest.approve_benignClosedToApproved passes — /quarantine approve on a BENIGN_CLOSED row transitions BENIGN_CLOSED→APPROVED
  - QuarantineCommandHandlerTest.approve_nonAdmin_rejected passes — non-admin caller receives error.admin_only
  - QuarantineCommandHandlerTest.reject_transitionsPendingToRejected passes — /quarantine reject <id> transitions PENDING→REJECTED; placeholder becomes permanent
  - QuarantineCommandHandlerTest.reject_benignClosedToRejected passes — /quarantine reject on BENIGN_CLOSED row transitions to REJECTED
  - AuditCommandHandlerTest.audit_readsRedactedView passes — /audit reads through audit_log_view (redacted); bot admin sees deployment-wide audit history; redacted columns surface as masked
  - AuditCommandHandlerTest.audit_actorFilter passes — --actor <contact> resolves against (inbound_adapter, contact_id); only that actor's rows returned
  - AuditCommandHandlerTest.audit_actionFilter passes — --action <verb> filters by audit action enum value
  - AuditCommandHandlerTest.audit_unknownAction_listsAccepted passes — unknown --action verb returns friendly error listing accepted values
  - AuditCommandHandlerTest.audit_unknownActor_returnsNoRows passes — unknown actor id (well-formed contact id, no matching user on inbound adapter) returns same 'no audit rows' reply as a known id with no rows — no existence-vs-no-rows distinction exposed
  - AuditCommandHandlerTest.audit_nonAdmin_rejected passes — non-admin caller receives error.admin_only
  - AuditCommandHandlerTest.audit_pagination passes — --page N is 1-indexed; page size is profile-driven; results are ordered
  - QuarantineReviewListenerTest.onPendingInsert_drivesAdminNotifier passes — Provider's listener receives quarantine_review NOTIFY with payload ('quarantine', quarantine_id, 'PENDING') and fires a throttled admin notification
  - QuarantineReviewListenerTest.onNeedsReview_drivesAdminNotifier passes — payload ('post', post_id, 'NEEDS_REVIEW') fires a throttled admin notification
  - QuarantineReviewListenerTest.terminalTransition_advancesCursor_noNotification passes — BENIGN_CLOSED, APPROVED, and REJECTED transitions advance the Provider's cursor without user-visible effect; no admin notification fires
  - QuarantineReviewListenerTest.casCursor_rejectsBackwardsMove passes — cursor is compound (reviewed_at, target_kind, target_id); CAS UPDATE protects against backwards moves; a duplicate or stale NOTIFY produces no additional side effect
  - QuarantineReviewListenerTest.startupReconciler_catchesUpMissedEvents passes — QuarantineReviewReconciler runs at startup; scans quarantine_review_view and post table for events past the cursor; processes missed PENDING inserts and NEEDS_REVIEW transitions
  - "BundleKeys.java gains constants for all /quarantine and /audit reply and error keys"
  - "mvn -B clean verify from the repo root exits 0"
  - "Every prior test continues to pass"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AuditCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewListenerTest.java
  preserves:
    - all tests currently green on main
    - all tests added by M1-081a
spec_refs:
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/security.md §DB roles
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/schema.md §Operational
decision_refs:
  - D9
  - D34

reviews:
  - round: 1
    date: 2026-05-26
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 2076
      removed: 9
  - round: 2
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 2098
      removed: 11
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-26
    category: DOS
    severity: medium
    promise: |
      Rate limiting... `/quarantine approve` — per-admin bucket.
    gap: |
      QuarantineCommandHandler.java handleApprove calls the approve_quarantine
      stored procedure with no command-level rate limiting. Only the generic
      transport-level rate cap applies.
    repro: |
      A compromised bot-admin sends rapid /quarantine approve <id1>, <id2>, ...
      Each call executes the stored procedure (row lock + body replace + audit
      INSERT + NOTIFY), mass-approving quarantine entries faster than the
      operator can react.
    suggested_fix_class: rate-limit
  - date: 2026-05-26
    category: AUDIT-EVASION
    severity: medium
    promise: |
      Authorization evaluation order step 8: Audit-log the intent. Any new
      command goes through the same audit-before-effect rule.
    gap: |
      AuditCommandHandler and QuarantineCommandHandler handleList perform
      privileged admin-only reads without writing any audit row. The codebase
      pattern audits admin-only reads (LIST_SOURCES_ALL for /list-sources --all).
    repro: |
      A compromised bot-admin issues /audit --action BAN to enumerate all ban
      events, or /quarantine list --all to enumerate quarantine entries. No
      audit row records this reconnaissance.
    suggested_fix_class: audit-log-coverage
  - date: 2026-05-26
    category: AUDIT-EVASION
    severity: low
    promise: |
      /quarantine list defaults to PENDING rows only; BENIGN_CLOSED rows not
      surfaced unless --all. Command signature includes --page N.
    gap: |
      QuarantineCommandHandler handleList hardcodes page=1 and pageSize=20
      with no --page parsing. Entries beyond index 20 are invisible to the
      admin through the chat interface.
    repro: |
      An adversary injects 25+ quarantine-triggering posts. Admin sees only
      the 20 most recent; older, potentially more dangerous entries are hidden.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-05-26
    verdict: FINDINGS
    base: main
    head: m1/M1-081b-quarantine-admin-commands-revi
    verdict_file: docs/plan/m1/redteam/M1-081b-2026-05-26.md
    findings_count: 3
    out_of_model_count: 2
    note: |
      Three findings: 2 medium (missing /quarantine approve rate bucket;
      missing audit-log coverage for /quarantine list and /audit reads) and
      1 low (missing --page support in /quarantine list). All are remediation-
      ticket candidates — the done commit is immutable per workflow rules.
      Two out-of-model observations: fail-closed lookupActor masking DB
      failures as permission denials; fragile substring matching in
      mapStoredProcError.
clarity_check:
  date: 2026-05-25
  verdict: WARN
  warnings:
    - "Acceptance item 21 (BundleKeys.java constants) is a prose assertion with no test method — reviewer must check by diff inspection"
---

# M1-081b: Quarantine admin commands + review listener + /audit

## Context

Provider-side subticket of the T2-G quarantine admin workflow
(M1-081 umbrella). Implements the `/quarantine list|approve|reject`
and `/audit` admin command handlers, the `QuarantineReviewListener`
that consumes the `quarantine_review` NOTIFY channel with tagged
payload, and the startup `QuarantineReviewReconciler` that catches
up missed events via the high-water-mark cursor.

Depends on M1-081a for the V21 migration (stored procedures,
provider_state row, admin_notification_state grant).

Spec contracts: `commands.md` §Admin for the four commands;
`architecture.md` §Inter-service communication for the
`quarantine_review` channel consumer behavior; `security.md`
§Quarantine workflow for approve/reject semantics.

## Acceptance

**Quarantine commands.** `/quarantine list` defaults to PENDING
rows — the active admin queue. `--all` lists every status
(PENDING, BENIGN_CLOSED, APPROVED, REJECTED) for forensic/audit
workflows. Both modes are bot-admin only. `/quarantine approve <id>`
calls the `approve_quarantine` stored procedure under Provider
EXECUTE; the procedure restores the original span, transitions
PENDING→APPROVED (or BENIGN_CLOSED→APPROVED), and fires
`NOTIFY new_post` so the Provider re-renders the unredacted body.
`/quarantine reject <id>` transitions to REJECTED and leaves the
placeholder permanently. Non-admin callers receive
`error.admin_only`.

**Audit command.** `/audit` reads `audit_log_view` (the redacted
view, V5). Bot admin sees deployment-wide history. Filters:
`--actor <contact>` resolved against `(inbound_adapter,
contact_id)`; `--action <verb>` against the closed audit-action
enum (unknown verb returns friendly error listing accepted values);
`--page N` (1-indexed, profile-driven page size). Unknown actor id
returns the same "no audit rows" reply as a known id with no rows
— no existence-vs-no-rows distinction is exposed.

**QuarantineReviewListener.** Parallel to `NewPostListener` —
dedicated LISTEN connection on the `quarantine_review` channel.
Consumes the tagged payload `(target_kind, target_id, new_status)`.
Routes `('quarantine', id, 'PENDING')` and `('post', id,
'NEEDS_REVIEW')` to a throttled admin notification. Routes
`BENIGN_CLOSED`, `APPROVED`, `REJECTED` to cursor-advance only
(no user-visible effect). Cursor is the compound
`(reviewed_at, target_kind, target_id)` with CAS UPDATE.

**QuarantineReviewReconciler.** Startup catch-up scan matching
`NewPostReconciler` pattern — processes events past the cursor
that may have been missed during Provider downtime.

## Out-of-scope

- Collector-side re-eval job, TTL job, NOTIFY emit, tagger
  partial-valid — all M1-081a.
- V21 migration — M1-081a.
- NewPostListener / NewPostReconciler modifications — the
  quarantine_review listener is a separate, parallel listener.
- InboundRouter modifications — new handlers are CDI-discovered.
- The umbrella integration test `QuarantineWorkflowIT` — M1-081.
- Any modification to any existing command handler test.

## Notes

- `QuarantineCommandHandler` follows the `InviteCommandHandler`
  pattern: one class dispatching `list`, `approve`, `reject`
  subcommands based on the first whitespace-delimited token after
  the command name.
- `/quarantine approve` and `/quarantine reject` call stored
  procedures via JDBC `CALL` — the Provider role has EXECUTE but
  no direct quarantine table access. The procedures run with
  `SECURITY DEFINER` so the Provider never touches
  `quarantine.original_html` directly.
- Provider-side admin notifications (for quarantine_review events)
  need write access to `admin_notification_state` — the V21
  migration (M1-081a) grants this. The Provider can either reuse
  the same UPSERT pattern as the Collector's
  `ThrottledAdminNotifier` or extract a shared helper to
  `infochat-core`. Either approach satisfies the acceptance
  criteria — the choice is an implementation decision.
- `QuarantineReviewListener` + `QuarantineReviewReconciler` mirror
  `NewPostListener` + `NewPostReconciler`. The listener uses a
  dedicated long-lived LISTEN connection (never returned to the
  Agroal pool) with reconnect-resilient backoff. The reconciler
  runs at startup with `@Startup @Priority(260)`.
- The `--page N` flag on `/audit` and `/quarantine list` uses
  1-indexed pages with profile-driven page size; this matches the
  pagination pattern already established by existing commands.
- `AuditCommandHandler` reads `audit_log_view` (V5); no raw
  `audit_log` table access. The view already applies
  `redact_contact_id` — the command surfaces the masked values.

## Round 1 rework

1. SCOPE-DRIFT-CHECK FAIL: `en.properties` touched but not in
   `files_scope`. Fix: add the path to `files_scope` (budget of 10
   already accommodates it). Ticket-metadata fix only, no code change.
