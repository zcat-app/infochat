---
id: M1-067
title: /export — user data export with field-level positive list
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by:
  - M1-061
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportDataCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportPaginator.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ExportCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ExportDataCollectorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterExportIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any /forget handler — M1-066 territory
  - any /clear, /compress, /stop, /retry handler — M1-064, M1-065 territory
  - any chat agent or tool registry change — M1-062, M1-063 territory
  - any modification to existing migrations
  - any out-of-band download link or external URL generation — spec mandates in-band delivery
  - any modification to existing CommandHandler implementations outside files_scope
  - any group-scope specific behavior beyond the scope filter column in the positive list — /export follows the same shape in DM and group per spec
  - any row outside the listed tables — the CI shape test asserts the output contains only the listed keys
acceptance:
  - "ExportCommandHandler.java exists, implements CommandHandler with commandName() returning 'export'. Audit-logged before effect. Verify: ExportCommandHandlerTest.auditLoggedBeforeEffect passes"
  - "The /export command is rate-limited in the 'parser-only + DB-read paginated' bucket (spec §Rate limiting). Verify: InboundRouterExportIT.rateLimitedInCorrectBucket passes"
  - "ExportDataCollector.java queries exactly the tables in the spec's field-level positive list (docs/spec/commands.md §/export, docs/design/03-commands.md §3.9 /export): chat_memory, scope_preferences, scope_tag, chat_session, source_subscription, summary_anchor, saved_post, users (minus authorization fields), audit_log_view (actor = self). Scope filtering per the spec table. Verify: ExportDataCollectorTest.collectsExactPositiveList passes"
  - "The users row excludes is_admin, banned_by, ban_reason, banned_at, probation_until (authorization-state fields per spec §/export). Verify: ExportDataCollectorTest.excludesAuthorizationFields passes"
  - "audit_log_view rows include only rows where actor_user_id = caller. Rows mentioning the caller as target without being authored by them are NOT included (spec §/export). Verify: ExportDataCollectorTest.auditOnlyActorRows passes"
  - "saved_post rows are the full library regardless of calling scope (D13: per-user-globally). Verify: ExportDataCollectorTest.savedPostGlobalRegardlessOfScope passes"
  - "Group /export is scoped to the calling (user, group) for per-scope tables — never another user's rows, never group-wide content, never rows outside the listed tables (spec §/export). Verify: ExportDataCollectorTest.groupExportScopedCorrectly passes"
  - "Output format is JSON, UTF-8, wrapped in triple backticks. Each page is a valid, independently-parseable JSON object. Tables are emitted in the spec positive-list order; rows are packed greedily until the next row would exceed the effective page cap (chat-mode body cap minus 32-char header budget). A table spanning pages appears as a key in each page with its respective row subset. A single row exceeding the cap occupies its own page (cap is soft at row granularity). When the export fits in one page, no page marker is emitted. Multi-page replies prepend 'page=N/T' before the opening fence. Verify: ExportCommandHandlerTest.paginatesLargeExport passes AND ExportCommandHandlerTest.eachPageIsValidJson passes"
  - "ExportPaginator.java is a pure-function utility: input is the table-to-rows map (in positive-list order) and the effective page cap; output is a list of valid JSON object strings. Row order is preserved; the union across all pages equals the full export; no page exceeds the cap except when a single row is larger (soft cap at row granularity). Verify: ExportCommandHandlerTest.paginatorPreservesAllRows passes"
  - "The CI export-shape test asserts the output JSON contains only the keys from the positive list and refuses any additional table the implementation might leak (spec §/export — 'No row outside the listed tables'). Verify: ExportDataCollectorTest.ciShapeTestRefusesExtraKeys passes"
  - "Delivery is in-band: the export is sent as reply messages on the same adapter channel — no external URLs or out-of-band download links (spec §/export). Verify: InboundRouterExportIT.deliveredInBand passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ExportCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ExportDataCollectorTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterExportIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control — /export
  - docs/spec/security.md §Rate limiting
  - docs/design/03-commands.md §3.9 /export
decision_refs:
  - D13
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-067: /export — user data export with field-level positive list

## Context

`/export` returns the calling user's own data as an in-band JSON reply. The
export is defined by an explicit table list and a field-level positive list
(`docs/spec/commands.md` §/export, `docs/design/03-commands.md` §3.9), not
by a vague "the user's contributions" rule — the boundary is testable and
CI-asserted. This is the companion to `/forget` (M1-066): `/forget` purges,
`/export` extracts. Both are T2-E (privacy commands).

## Acceptance

See the YAML `acceptance:` list above. In summary:

1. **Handler + audit** — implements CommandHandler, audit-logged before effect.
2. **Rate-limit bucket** — registered in parser-only + DB-read paginated bucket.
3. **ExportDataCollector** queries exactly the spec-listed tables with the
   correct scope filter per table and the field-level positive list.
4. **Authorization fields excluded** from the users row.
5. **Audit log** includes only actor=self rows via the redacted view.
6. **Saved posts** are global regardless of calling scope (D13).
7. **Pagination** — each page is valid JSON, split at row boundaries, 32-char
   header budget, soft cap at row granularity.
8. **ExportPaginator** — pure-function contract; union across pages = full export.
9. **CI shape test** refuses any extra keys beyond the positive list.
10. **In-band delivery** — no external URLs.
11. `mvn verify` is green.

## Out-of-scope

- **No `/forget`.** M1-066.
- **No out-of-band delivery.** Spec mandates in-band.
- **No rows outside the listed tables.** The CI shape test enforces this
  structurally.

## Notes

- **Header budget** is 32 characters. Effective JSON payload cap per page =
  chat-mode body cap (§3.1: `laptop` 2048, `vps` 1024, `pi` 512,
  `remote-llm` 4096) minus 32. The budget covers `page=N/T\n` + triple-
  backtick fences with language hint.
- The `audit_log_view` (not the raw `audit_log` table) is the Provider's
  read path — the view applies the redaction per `docs/design/04-security.md`.
- The field-level positive list is pinned at spec level. Adding a column to
  the export requires a spec amendment — the CI shape test catches silent
  additions.
- Adjacent pattern: `SavedCommandHandler` for the existing paginated-reply
  pattern; `AuditLogViewRepository` (if it exists) for the audit read path.
- Relevant design: `docs/design/03-commands.md` §3.9 /export (field-level
  positive list table).
