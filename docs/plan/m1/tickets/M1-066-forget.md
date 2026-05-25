---
id: M1-066
title: /forget — per-scope privacy purge with remaining-scopes disclosure
status: done
created: 2026-05-24
last_updated: 2026-05-25
blocked_by:
  - M1-061
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ForgetCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ForgetConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ForgetPurgeService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ForgetCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ForgetPurgeServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterForgetIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any /export handler — M1-067 territory
  - any /clear, /compress, /stop, /retry handler — M1-064, M1-065 territory
  - any chat agent or tool registry change — M1-062, M1-063 territory
  - any modification to existing migrations — tables exist from M1-061 and prior
  - any modification to users.is_admin, users.is_banned, group_membership, or audit_log rows — spec explicitly excludes these from the /forget purge set
  - any soft-delete tombstone approach — spec mandates hard purge (D37)
  - any group-scope specific behavior beyond the remaining-scopes count — /forget works identically in DM and group per spec
  - any modification to existing CommandHandler implementations outside files_scope
acceptance:
  - "ForgetCommandHandler.java exists, implements CommandHandler with commandName() returning 'forget', and requires confirm per spec §Conversation control — /forget. Verify: ForgetCommandHandlerTest.requiresConfirm passes"
  - "/forget confirm performs a hard DELETE of: (1) chat_memory rows for (caller, calling_scope), (2) chat_session rows for (caller, calling_scope) — which cascades to chat_message, (3) summary_anchor rows for (caller, calling_scope) with command_kind = 'personal' only, (4) saved_post rows for the caller globally regardless of calling scope. All in one transaction. Verify: ForgetPurgeServiceTest.purgesExactSet passes"
  - "/forget does NOT touch users.is_admin, users.is_banned, group_membership, or any audit_log row (spec: append-only invariant 10). Verify: ForgetPurgeServiceTest.preservesAdminBanMembershipAudit passes"
  - "/forget does NOT touch the group-wide digest anchor (command_kind = 'digest', user_id IS NULL) — that anchor is not user-owned data (spec §/forget). Verify: ForgetPurgeServiceTest.preservesDigestAnchor passes"
  - "The audit row records counts only — chat_memory_count, chat_session_count, summary_anchor_count, saved_post_count — never UID lists, personal tags, or user-authored content (spec §/forget). Verify: ForgetCommandHandlerTest.auditRowRecordsCountsOnly passes"
  - "Idempotent: a second /forget with nothing to remove returns a friendly no-op reply and no audit row is written (spec §/forget). The no-op detection uses the RETURNING row count of the purge transaction; a strictly-zero count is the no-op marker (spec §Invariants — Invariant 7). Verify: ForgetCommandHandlerTest.idempotentNoOp passes"
  - "Remaining-scopes disclosure: when the caller has chat-tier rows in other scopes (DM + groups), the reply discloses the count and instructs them to issue /forget from each scope. The reply does NOT name the other scopes — the count is sufficient (spec §/forget — Remaining-scopes disclosure). Verify: ForgetCommandHandlerTest.disclosesRemainingScopes passes"
  - "When the remaining-scopes count is zero, the disclosure clause is omitted and the reply is the bare confirmation (spec §/forget). Verify: ForgetCommandHandlerTest.omitsDisclosureWhenZero passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ForgetCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ForgetPurgeServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterForgetIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §Secrets handling
  - docs/design/03-commands.md §3.9 Conversation control
decision_refs:
  - D13
  - D37
reviews:
  - round: 1
    date: 2026-05-25
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 1317
      removed: 14
  - round: 2
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 1409
      removed: 20
overrides: []
aborted_attempts: []
reopens: []
revisions:
  - date: 2026-05-25
    reason: budget-breach refine — files_budget 6→9, added BundleKeys.java + en.properties + cs.properties to files_scope
    prior_files_budget: 6
    prior_files_scope_count: 6
escalations:
  - date: 2026-05-25
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A
redteam_findings:
  - date: 2026-05-25
    category: AUDIT-EVASION
    severity: medium
    promise: |
      "The audit log records *intent* (command name, actor, scope, target),
      not user-authored prose." (docs/spec/security.md §Secrets handling) and
      "Audit-log the intent." (docs/spec/security.md §Authorization model step 8)
    gap: |
      ForgetCommandHandler.java insertAudit method builds the AuditRow without
      scope_id, even though the scopeId variable is available at the call site
      in executeForget. An operator reviewing the audit log after a /forget
      cannot determine which scope was purged.
    repro: |
      (1) User A runs /forget confirm from DM scope.
      (2) User A runs /forget confirm from Group-X scope.
      (3) Operator queries audit_log WHERE action = 'FORGET' AND actor_user_id = A.
      (4) Both rows lack scope_id; operator cannot distinguish which scope each
      row corresponds to.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-05-25
    verdict: FINDINGS
    base: main
    head: m1/M1-066-forget
    verdict_file: docs/plan/m1/redteam/M1-066-2026-05-25.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      One medium AUDIT-EVASION finding: the /forget audit row omits scope_id,
      so the operator cannot distinguish which scope was purged. The done
      commit is immutable; the fix lands as a new remediation ticket. One
      out-of-model note about group-scope contactIdOf returning null
      (pre-existing pattern, group dispatch not yet wired).
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-066: /forget — per-scope privacy purge with remaining-scopes disclosure

## Context

`/forget` is the user-facing privacy lever (D37). It performs a hard purge of
everything kept on the calling user's behalf — `chat_memory`, `chat_session`
(cascading to `chat_message`), personal `summary_anchor`, and `saved_post` —
and discloses the count of other scopes where chat-tier rows still exist.
The command is the v1 privacy contract: no soft-delete tombstones, no
residual user content in the system after the purge completes.

## Acceptance

See the YAML `acceptance:` list above. In summary:

1. **Hard DELETE** across the exact four-table purge set in one transaction.
2. **Does NOT touch** admin/ban state, group membership, or audit log rows.
3. **Counts-only audit row** — no user content leaks into the audit surface.
4. **Idempotent** — no-op with friendly reply and no audit row on zero-count.
5. **Remaining-scopes disclosure** — count of other scopes, no names.
6. `mvn verify` is green.

## Out-of-scope

- **No `/export`.** M1-067.
- **No soft-delete.** Spec mandates hard purge (D37).
- **No modification to admin/ban/membership/audit rows.** Explicitly excluded
  by spec.

## Notes

- The purge is a single transaction with `DELETE ... RETURNING` on each table
  to collect counts. The four DELETEs share the same transaction so a partial
  failure rolls back cleanly.
- `saved_post` is purged globally regardless of calling scope (D13: saves are
  per-user-globally). The chat-tier tables are per-scope.
- The remaining-scopes count query: `SELECT COUNT(DISTINCT (scope_kind,
  scope_id)) FROM chat_memory WHERE user_id = ? AND NOT (scope_kind = ?
  AND scope_id = ?)` (or equivalent across chat_session/summary_anchor).
  The query runs AFTER the purge so it reflects the post-purge state.
- Adjacent pattern: `BanConfirm` / `RemoveSourceConfirm` for the confirm
  variant; `SavedPostRepository` for the cascade-style DELETE.
- Relevant design: `docs/design/03-commands.md` §3.9 /forget.

## Round 1 rework

1. **Audit-before-effect ordering (Invariant 7).** `ForgetCommandHandler.executeForget()` writes the audit row AFTER the DELETEs to use the row counts. The spec requires "before". Fix: pre-count rows via `SELECT COUNT(*)` for each table, write the audit row with those pre-counts, then execute the DELETEs. Update the code comment to reflect the new ordering.
