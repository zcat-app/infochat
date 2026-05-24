---
id: M1-053
title: Source-management admin commands — list / remove / enable / disable
status: pending
created: 2026-05-24
last_updated: 2026-05-24
clarity_check: {}
escalations:
  - date: 2026-05-24
    reason: outline-fail
    reviewer_verdict_excerpt: |
      OUTLINE FAILED — escalation recommended

      REASON: The ticket's out_of_scope clause #3 declares ConfirmStateService.java
      unchanged and asserts that the handlers only "register two new commandName
      keys (\"remove-source\", \"source-enable\") via remember / takeMatching calls
      inside the handlers." Ground-truth verification of
      infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java
      lines 114–252 shows the remember(UUID, ScopeRef, PendingConfirm) API accepts
      only the sealed interface PendingConfirm permits PendingConfirm.Ban,
      PendingConfirm.InviteCreateOpen, PendingConfirm.InviteRevoke typed payload
      (line 221–224). The commandName() and sweepPrefix() strings are overridden
      per-variant on each record; the three existing permits return "ban" /
      "invite:create:open" / "invite:revoke" and carry typed args (target contact
      id, target adapter, invite UUID) that do not fit a source-id payload. Java
      sealed types require new implementations to be either listed in the permits
      clause or nested in the same source file as the sealed declaration; both
      mechanisms require editing ConfirmStateService.java. There is no Map-based /
      untyped payload escape. Acceptance items 4 and 6 explicitly require
      confirmStateService.remember(actor.id, scope, "remove-source", ...) and
      "source-enable" — these calls cannot compile without new
      PendingConfirm.RemoveSource and PendingConfirm.SourceEnable permits, which
      forces an edit to ConfirmStateService.java, which forces a thirteenth file
      beyond files_budget: 12, and which directly violates out_of_scope clause #3.
      No implementable outline exists within the ticket's files_scope /
      files_budget / out_of_scope constraints.

      Secondary blocker: acceptance items 6 and 8 reference consecutive_failure_count
      = 0 in the UPDATE on source; the actual V6 column is consecutive_failures
      (infochat-core/src/main/resources/db/migration/V6__sources_tags.sql:49), so
      the UPDATE statements as written would fail at runtime — a smaller refinement
      but worth bundling.

      SUGGESTED ESCALATION: refine

      Recommended refinement: amend out_of_scope clause #3 to authorize the
      additive edit to ConfirmStateService.java's sealed PendingConfirm permits
      list and add two new record cases (PendingConfirm.RemoveSource(UUID sourceId)
      and PendingConfirm.SourceEnable(UUID sourceId)); add
      infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java
      to files_scope and bump files_budget to 13; correct the column name in
      acceptance items 6 and 8 from consecutive_failure_count to consecutive_failures.
      After refinement, re-run the plan step to surface any additional API-surface
      or ground-truth gaps that the round-1 audit did not reach.
blocked_by:
  - M1-051
files_budget: 12
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ListSourcesCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RemoveSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceDisableCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ListSourcesCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RemoveSourceCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceEnableCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceDisableCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceManagementIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — docs/spec/commands.md §Source management + §Permission model + docs/spec/schema.md §Sources and tags + docs/spec/security.md §Source URL visibility + §Authorization model are the source of truth
  - any change to the `source` table or its `status` / `deleted_at` columns — V6 already shipped both with the three-status state machine and the soft-delete column; no Flyway migration in this ticket
  - any change to ConfirmStateService — the M1-051 service is consumed unchanged; this ticket only registers two new `commandName` keys (`"remove-source"`, `"source-enable"`) via `remember` / `takeMatching` calls inside the handlers
  - any change to the M1-044b InboundRouter intake-step splice — the step 4.5 confirm-cancel sweep treats the new commands uniformly through their `sweepPrefix` (the command name); no router case-edit, no new step
  - any change to M1-036's `UrlProbe` — the existing HTTP-shaped probe is consumed unchanged by `/source-enable` (HEAD-or-small-range-GET semantics already implemented for `/add-source`)
  - any StreamSource (Nostr / Bluesky / etc.) probe path in `/source-enable` — v1's `/add-source` is HTTP-only today (per the `AddSourceArgs.java` lines 232 to 237 comment which states that the probe layer accepts http/https only, and that wss/ws (Nostr) and other StreamSource shapes are spec'd for the kind resolver but the probe path is HTTP-only in MVP). No StreamSource probe primitive exists in Collector or Provider (no `StreamSourceSupervisor` yet; `FetchScheduler.java` line 54 names it as future work). Soft-deleted / failed / disabled StreamSource rows can only arise from `bootstrap-sources.json`; a separate ticket will land the relay-probe primitive alongside the Nostr fetcher when `StreamSourceSupervisor` arrives. This ticket implements `/source-enable`'s HTTP path only; `/source-enable` against a non-HTTP-shaped source kind returns `error.source_enable.kind_not_supported_in_v1` with no audit row and no state change
  - any new fetcher / FetchScheduler change — the Collector scheduler reads `WHERE status = 'active' AND deleted_at IS NULL` and the new status transitions feed it naturally; the scheduler itself is not modified
  - any /add-source change — M1-036's commit is consumed unchanged
  - any /unfollow-source handler — that command IS in spec §Source management but is per-scope (not admin) and lands in a separate T2-B ticket or a follow-up; this ticket is bot-admin only
  - any TranslationProvider exercise — T2-C territory; new bundle entries are English only
  - any audit-log writer consolidation — the handlers write to `audit_log` directly per the M1-036 / M1-044c / M1-046 precedent; the M1-041 AuditLogWriter consolidation is a separate ticket
  - any /save / /saved / /unsave handler — T2-B.1 territory
  - any /follow-tag / /unfollow-tag / /tag-mode handler — T2-B.3 territory
  - any group-scope admin invocation — all four handlers are DM-only in v1 (the M1-044c precedent for admin commands; T2-F lands the SPI widening that lets group-scope admin commands work); group invocation returns the M1-044c-precedent friendly error
acceptance:
  - "`AuditAction.java` (`infochat-core`) gains two new enum values: `REMOVE_SOURCE_INTENT` and `SOURCE_ENABLE_INTENT`. These are the step-8 audit-on-intent verbs (spec §Authorization model: `7. Permission check ... 8. Audit-log the intent. 9. Execute.`) that fire at the first-call prompt path for `/remove-source` and for `/source-enable` against a soft-deleted row. The existing `REMOVE_SOURCE`, `SOURCE_ENABLE`, `SOURCE_DISABLE` verbs continue to denote the step-9 execute rows. The shape mirrors the M1-051 precedent (`BAN_INTENT`, `INVITE_CREATE_INTENT`, `INVITE_REVOKE_INTENT`)"
  - "`ListSourcesCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"list-sources\"`. Argument shape: optional flags `[--all]`, `[--include-deleted]`, `[--page N]`. Permission gate runs FIRST in the handler: (1) a non-admin caller passing `--all` OR `--include-deleted` receives `error.list_sources.admin_only_flag` — the flag is **NOT** silently stripped (spec §Permission model: 'Admin-only flags are part of command identity'); (2) `--include-deleted` is valid only WITH `--all` — passing `--include-deleted` without `--all` returns `error.list_sources.include_deleted_requires_all` even for an admin caller. On the happy paths: (a) no flags, DM scope — returns the caller's `source_subscription` rows (only) with each source's title/url/kind/status flagged; (b) no flags, group scope — returns the group's `source_subscription` rows (visible to every group member per Decision D7); (c) `--all` (admin) — returns every `source` row globally where `deleted_at IS NULL`, regardless of subscription, with `failed` and `disabled` statuses flagged inline; (d) `--all --include-deleted` (admin) — additionally returns soft-deleted rows (`deleted_at IS NOT NULL`). Replies for the `--all` paths include the spec's required URL-visibility caveat (`security.md` §Source URL visibility): `reply.list_sources.url_visibility_caveat` bundle key text included in the reply header"
  - "ListSourcesCommandHandlerTest is plain JUnit per the M1-049 test pyramid (no `@QuarkusTest`). Scenarios — one `@Test` per branch: `listSourcesNonAdminWithAllFlagReturnsAdminOnlyError`, `listSourcesNonAdminWithIncludeDeletedFlagReturnsAdminOnlyError`, `listSourcesIncludeDeletedWithoutAllReturnsRequiresAllError`, `listSourcesDmReturnsCallerSubscriptionsOnly`, `listSourcesGroupReturnsGroupSubscriptionsForEveryMember`, `listSourcesAllReturnsEveryNonDeletedSourceGlobally`, `listSourcesAllFlagsFailedAndDisabledStatusesInline`, `listSourcesAllIncludeDeletedAdditionallyReturnsSoftDeletedRows`, `listSourcesAllReplyIncludesUrlVisibilityCaveat`"
  - "`RemoveSourceCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"remove-source\"`. Argument shape: positional `<id>` (source id, UUID or short prefix). Permission gate: non-admin caller returns `error.admin_only`. The handler integrates with M1-051 `ConfirmStateService` under the two-call shape: (1) **first call** (body does NOT end in ` confirm`) — validates admin-gate + source-id resolves + source is not already soft-deleted; on validation success, writes the step-8 `REMOVE_SOURCE_INTENT` audit row inside a transaction, registers a pending confirm via `confirmStateService.remember(actor.id, scope, \"remove-source\", <prepared-args-bundle-including-source-id>)`, returns `reply.confirm.prompt.remove_source` (interpolates redacted source name + affected-subscriber count + timeout seconds); (2) **confirm call** (body ends in ` confirm`) — `confirmStateService.takeMatching(actor.id, scope, \"remove-source\")` returns the pending args; on empty Optional → `error.confirm.no_pending`; on non-empty → executes the soft-delete inside ONE transaction: writes the `REMOVE_SOURCE` audit row audit-before-effect, `UPDATE source SET deleted_at = now() WHERE id = ?`, `DELETE FROM source_subscription WHERE source_id = ?` (the cascade-delete per spec §Source management), returns `reply.remove_source.success` with the cascade-deleted subscription count. Validation failures on the first-call path write NO audit row and store NO pending state"
  - "RemoveSourceCommandHandlerTest scenarios: `removeSourceNonAdminReturnsAdminOnlyError`, `removeSourceFirstCallReturnsPromptAndWritesIntentAuditRowOnly` (asserts ONE audit_log row with `action='REMOVE_SOURCE_INTENT'`, no `source.deleted_at` mutation, no `source_subscription` row deletion, ConfirmStateService.peek shows pending state), `removeSourceConfirmWithinWindowExecutesSoftDeleteAndCascade` (asserts second audit row with `action='REMOVE_SOURCE'`, `source.deleted_at IS NOT NULL`, every `source_subscription` row for the source is gone, both audit rows persist), `removeSourceConfirmWithoutPendingReturnsNoPending`, `removeSourceFirstCallAgainstAlreadySoftDeletedReturnsAlreadyDeleted` (no audit row, no pending state)"
  - "`SourceEnableCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"source-enable\"`. Argument shape: positional `<id>`. Permission gate: non-admin returns `error.admin_only`. Kind gate (runs BEFORE state-branching, AFTER admin gate and source-id resolution): if `source.kind` is not HTTP-shaped (v1: only `rss` qualifies; `nostr`, `bluesky`, and any other StreamSource kind do NOT), the handler returns `error.source_enable.kind_not_supported_in_v1` immediately — no probe, no audit row, no state change. The kind gate matches the v1 reality pinned by `AddSourceArgs.java:232-237` (probe layer is HTTP-only). For HTTP-shaped sources, the handler branches on the row's current state: (a) `failed` OR `disabled` (NOT soft-deleted) — runs the probe (HEAD/small-range-GET via M1-036's `UrlProbe`); probe failure leaves the source in its prior state with `error.source_enable.probe_failed`; probe success transitions in one transaction: writes the `SOURCE_ENABLE` audit row audit-before-effect, `UPDATE source SET status = 'active', consecutive_failures = 0 WHERE id = ?`, returns `reply.source_enable.success` (no soft-delete disclosure on this path); NO confirm. (b) Soft-deleted (`deleted_at IS NOT NULL`) — requires confirm. First call: validates admin-gate + source resolves + kind is HTTP-shaped + writes the `SOURCE_ENABLE_INTENT` audit row in a transaction, registers a pending confirm via `remember(actor.id, scope, \"source-enable\", ...)`, returns `reply.confirm.prompt.source_enable_soft_deleted` (interpolates redacted source name + timeout seconds + the 'No subscriptions will be restored' notice). Confirm call: `takeMatching` returns the args; runs the probe; probe failure leaves the row soft-deleted (no audit row, no state change); probe success transitions in one transaction: writes `SOURCE_ENABLE`, `UPDATE source SET deleted_at = NULL, status = 'active', consecutive_failures = 0 WHERE id = ?`, returns `reply.source_enable.success.from_soft_deleted` including the literal disclosure `reply.source_enable.no_subscriptions_restored` (per spec §Source management: 'No subscriptions were restored — affected scopes must /add-source again to re-subscribe'). The disclosure is omitted on the `failed`/`disabled` paths"
  - "SourceEnableCommandHandlerTest scenarios: `sourceEnableNonAdminReturnsAdminOnlyError`, `sourceEnableAgainstNostrKindReturnsKindNotSupportedError` (seed a `kind='nostr'` source in `failed` state; assert reply is `error.source_enable.kind_not_supported_in_v1`, no probe invocation, no audit row, no state change), `sourceEnableFromFailedRunsProbeNoConfirm`, `sourceEnableFromDisabledRunsProbeNoConfirm`, `sourceEnableFromFailedWithFailingProbeLeavesRowFailed`, `sourceEnableFromSoftDeletedFirstCallReturnsPromptAndWritesIntentAuditRowOnly`, `sourceEnableSoftDeletedConfirmWithinWindowRunsProbeAndRevives` (asserts second audit row `action='SOURCE_ENABLE'`, `deleted_at IS NULL`, `status='active'`, reply contains the no-subscriptions-restored disclosure, no `source_subscription` rows were re-created), `sourceEnableSoftDeletedConfirmWithFailingProbeLeavesRowSoftDeleted`, `sourceEnableConfirmWithoutPendingReturnsNoPending`, `sourceEnableFromActiveReturnsAlreadyActive` (no audit row, no state change)"
  - "`SourceDisableCommandHandler` is a CDI bean implementing `CommandHandler` with `name() == \"source-disable\"`. Argument shape: positional `<id>`. Permission gate: non-admin returns `error.admin_only`. NO probe (per spec — operator is intentionally pausing the source). NO confirm. The handler executes in one transaction: validates the source row is `status = 'active' AND deleted_at IS NULL`; if not → `error.source_disable.not_active`; otherwise writes the `SOURCE_DISABLE` audit row audit-before-effect, `UPDATE source SET status = 'disabled' WHERE id = ?`, returns `reply.source_disable.success`"
  - "SourceDisableCommandHandlerTest scenarios: `sourceDisableNonAdminReturnsAdminOnlyError`, `sourceDisableHappyPathTransitionsActiveToDisabled`, `sourceDisableAgainstFailedSourceReturnsNotActive`, `sourceDisableAgainstAlreadyDisabledSourceReturnsNotActive`, `sourceDisableAgainstSoftDeletedSourceReturnsNotActive`"
  - "SourceManagementIT is the cross-cutting `@QuarkusTest`-shaped IT exercising the two confirm-gated paths end-to-end via the InMemoryAdapter (mirrors the M1-051 ConfirmFlowIT pattern). Method `removeSourcePromptThenConfirmCascadeDeletesSubscriptions` — seed an admin user + a source with three `source_subscription` rows (different scopes); deliver `/remove-source <id>` in DM; assert outbound is the confirm prompt AND no subscription deletion has happened AND one audit_log row with `REMOVE_SOURCE_INTENT`; deliver `/remove-source <id> confirm`; assert outbound is the success reply AND `source.deleted_at IS NOT NULL` AND zero `source_subscription` rows reference the source AND a second audit_log row with `REMOVE_SOURCE`. Method `sourceEnablePromptThenConfirmRevivesSoftDeletedRow` — seed an admin user + a soft-deleted source with NO subscriptions; deliver `/source-enable <id>` in DM; assert prompt + `SOURCE_ENABLE_INTENT` audit row + no state change; deliver `/source-enable <id> confirm`; assert success reply containing the no-subscriptions-restored disclosure + `source.deleted_at IS NULL` AND `source.status='active'` + a second `SOURCE_ENABLE` audit row. The IT uses a deterministic test-fixture probe seam (a CDI `@Alternative` UrlProbe that returns SUCCESS) so the test does not depend on outbound network access"
  - "`BundleKeys.java` adds the new constants for every reply / error key referenced above: `ERROR_LIST_SOURCES_ADMIN_ONLY_FLAG`, `ERROR_LIST_SOURCES_INCLUDE_DELETED_REQUIRES_ALL`, `REPLY_LIST_SOURCES_URL_VISIBILITY_CAVEAT`, `REPLY_LIST_SOURCES_HEADER`, `REPLY_LIST_SOURCES_LINE`, `REPLY_LIST_SOURCES_EMPTY`, `ERROR_REMOVE_SOURCE_UNKNOWN_ID`, `ERROR_REMOVE_SOURCE_ALREADY_DELETED`, `REPLY_CONFIRM_PROMPT_REMOVE_SOURCE`, `REPLY_REMOVE_SOURCE_SUCCESS`, `ERROR_SOURCE_ENABLE_UNKNOWN_ID`, `ERROR_SOURCE_ENABLE_PROBE_FAILED`, `ERROR_SOURCE_ENABLE_ALREADY_ACTIVE`, `ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1`, `REPLY_CONFIRM_PROMPT_SOURCE_ENABLE_SOFT_DELETED`, `REPLY_SOURCE_ENABLE_SUCCESS`, `REPLY_SOURCE_ENABLE_SUCCESS_FROM_SOFT_DELETED`, `REPLY_SOURCE_ENABLE_NO_SUBSCRIPTIONS_RESTORED`, `ERROR_SOURCE_DISABLE_UNKNOWN_ID`, `ERROR_SOURCE_DISABLE_NOT_ACTIVE`, `REPLY_SOURCE_DISABLE_SUCCESS`. `bundles/en.properties` adds the corresponding entries (the M1-035c `BundleLoaderTest` reflective check enforces alignment)"
  - "Each of the four handlers consumes the M1-040 `InboundContext` request-scoped bean for the actor lookup (the `(adapter, contact_id)` per-adapter SELECT pattern established by M1-036 / M1-044c / M1-046). Every contact-id appearing in an exception message is interpolated via `ContactIds.redact` per the M1-038 / M1-039 redaction precedent. Group-scope invocation on any of the four handlers returns `error.group_admin_not_in_v1` (the M1-044c precedent — admin commands are DM-only in v1; T2-F lands the widening)"
  - "`mvn -B clean verify` from the repo root exits 0. The pre-existing M1-051 ConfirmStateServiceTest / ConfirmFlowIT continue to pass — this ticket's new commands extend the consumer list but do NOT modify ConfirmStateService itself. The M1-036 AddSourceIT continues to pass — this ticket reads `source` rows but does NOT modify the source-table-write path. The M1-049 plain-JUnit handler tests remain unaffected"
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ListSourcesCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RemoveSourceCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceDisableCommandHandler.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ListSourcesCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RemoveSourceCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceEnableCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceDisableCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceManagementIT.java
  modifies:
    - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
  preserves:
    - all tests currently green on main
    - every M1-035 / M1-036 / M1-044 / M1-045 / M1-046 / M1-051 test
    - M1-051's confirm-cancel sweep semantics for unrelated commands
spec_refs:
  - docs/spec/commands.md §Source management
  - docs/spec/commands.md §Permission model
  - docs/spec/schema.md §Sources and tags
  - docs/spec/security.md §Source URL visibility
  - docs/spec/security.md §Authorization model
decision_refs:
  - D7
reviews: {}
overrides: []
aborted_attempts: []
reopens:
  - date: 2026-05-24
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-057
    reason: M1-057 unsealed PendingConfirm; blocker cleared
redteam_findings: []
---

# M1-053: Source-management admin commands — list / remove / enable / disable

## Context

T2-B.2 — the second of three Tier-2.B DM-command tickets. Lands
the four bot-admin commands that complete the source-management
surface: `/list-sources`, `/remove-source`, `/source-enable`,
`/source-disable`. M1-036 shipped `/add-source` (any non-banned
user — DM or group admin); this ticket lands the admin half.

The spec-load-bearing commitments this ticket pins:

1. **Admin-only flags are part of command identity** (spec
   §Permission model). `/list-sources --all` and
   `/list-sources --include-deleted` are inseparable from
   their flags for permission purposes — a non-admin caller
   passing either receives a friendly permission error and the
   parser **never** silently strips the flag.
2. **Confirm gate on destructive admin paths.** `/remove-source`
   always requires confirm; `/source-enable` against a
   soft-deleted row requires confirm (reviving a deliberately-removed
   source has broader implications than re-enabling an
   operationally-failed one). The M1-051 `ConfirmStateService` is
   consumed unchanged — this ticket registers two new
   `commandName` keys and provides the per-command prompt /
   success replies.
3. **Audit-on-intent at step 8** (spec §Authorization model). The
   first-call (prompt) path on a confirm-gated destructive admin
   action writes a step-8 intent audit row BEFORE storing the
   pending confirm — the M1-051 precedent (`BAN_INTENT`,
   `INVITE_CREATE_INTENT`, `INVITE_REVOKE_INTENT`). This ticket
   adds two new verbs: `REMOVE_SOURCE_INTENT` and
   `SOURCE_ENABLE_INTENT`. The step-9 execute path writes the
   existing `REMOVE_SOURCE` / `SOURCE_ENABLE` verbs unchanged.
4. **Cascade-delete on `/remove-source`.** The soft-delete on
   `source` AND the cascade `DELETE FROM source_subscription` run
   in ONE transaction (the source is gone; scope-level
   subscriptions to it must go too). `/source-enable` from
   soft-deleted does NOT restore subscriptions — the reply
   discloses this explicitly.
5. **URL-visibility caveat on `--all`.** Replies for the admin
   listing path include the spec's required URL-visibility
   disclosure (`security.md` §Source URL visibility).

`complexity: high` — four handlers, two confirm integrations, two
new audit verbs, a probe-call path, an `--all`/`--include-deleted`
flag-as-identity decision, and the cross-cutting end-to-end IT.
`risk: high` — every admin command is a permission commitment; the
admin-flag-as-identity rule and the cascade-delete are
spec-load-bearing security invariants.

`round_cap: 3` — the breadth of the surface makes a single review
round optimistic; the extra round absorbs reviewer-found gaps on
the per-branch test enumeration.

`security_relevant: true` — `/redteam` is recommended after
APPROVE-and-commit.

`migration_touch: false` — V6 already shipped the `source.status`
three-state machine + `deleted_at` column.

## Acceptance

The thirteen items in the YAML `acceptance:` list above pin the
behavioural contract. Highlights:

- The AuditAction enum extension is one item.
- Each of the four handlers gets ONE structural item (the handler
  shape) plus ONE test-enumeration item (the per-scenario method
  names).
- The bundle-keys + BundleLoaderTest alignment is one item.
- The InboundContext / ContactIds.redact / group-DM-only invariant
  is one item.
- The cross-cutting `SourceManagementIT` is one item covering BOTH
  confirm-gated end-to-end flows.
- `mvn -B clean verify` exits 0 closes the list.

## Out-of-scope

The YAML `out_of_scope` list above enumerates fourteen exclusions.
Highlights:

- **No spec or design edits.** Spec §Source management is complete
  and committed.
- **No V<N> migration.** V6 already ships `source.status` (three
  states: `active` / `failed` / `disabled`) and `deleted_at`.
- **No ConfirmStateService internals change.** M1-051's service is
  consumed via `remember` / `takeMatching` only; this ticket
  registers new `commandName` keys but adds no new method to the
  service.
- **No InboundRouter step 4.5 sweep edit.** The router's existing
  `isConfirmShape(normalized, pending)` matches against the
  pending command's `sweepPrefix`; the new commands' sweepPrefixes
  (`"remove-source"` and `"source-enable"`) work through the
  existing match logic.
- **No UrlProbe internals change.** M1-036's HTTP probe (HEAD or
  small-range GET) is consumed as-is for `/source-enable` on
  HTTP-shaped sources.
- **No StreamSource probe in this ticket.** v1's `/add-source` is
  HTTP-only today (`AddSourceArgs.java:232-237` comment); no
  StreamSource probe primitive exists in Collector or Provider.
  `/source-enable` gates on `source.kind` and returns
  `error.source_enable.kind_not_supported_in_v1` for non-HTTP
  kinds (`nostr`, `bluesky`, ...). The relay-probe primitive lands
  in a later ticket alongside `StreamSourceSupervisor`
  (`FetchScheduler.java:54` future-work marker). This bounds the
  ticket to a deterministic surface and removes the mid-flight
  "lift-or-escalate" decision the prior draft left open.
- **No /add-source change.** M1-036 is consumed unchanged.
- **No /unfollow-source handler.** That command is per-scope (not
  admin) and lands separately.
- **No T2-B.1 or T2-B.3 surface.** Save and tag-pref handlers are
  separate tickets.

## Notes

- **Spec anchors (verbatim citations):**
  - `docs/spec/commands.md` §Source management — the full
    `/list-sources` / `/remove-source` / `/source-enable` /
    `/source-disable` paragraphs including the soft-delete revival
    path, the cascade-delete rule, and the URL-visibility caveat.
  - `docs/spec/commands.md` §Permission model — the closed
    bot-admin set listing these four commands plus the
    `--all` / `--include-deleted` flag-as-identity rule.
  - `docs/spec/schema.md` §Sources and tags — the
    `active ↔ disabled ↔ failed` state machine orthogonal to
    `deleted_at`.
  - `docs/spec/security.md` §Source URL visibility — the
    URL-visibility disclosure required on `--all`.
  - `docs/spec/security.md` §Authorization model — the
    7-permission-check / 8-audit-intent / 9-execute order
    that drives the `_INTENT` audit verb pattern.
- **Design anchors:**
  - `docs/design/03-commands.md` §`/remove-source`,
    §`/source-enable`, §`/source-disable` — handler organisation,
    reply layout, and the `No subscriptions were restored`
    disclosure text.
  - `docs/design/03-commands.md` §Confirmation for destructive
    commands — the M1-051 confirm-gate state machine.
- **ConfirmStateService consumption.** Two new keyspace entries
  via `confirmStateService.remember(actor.id, scope,
  "remove-source", ...)` and `confirmStateService.remember(actor.id,
  scope, "source-enable", ...)`. The `sweepPrefix` (used by the
  router's step 4.5 sweep) defaults to the same `commandName`
  string so the existing `isConfirmShape` match logic accepts
  `/remove-source <id> confirm` and
  `/source-enable <id> confirm` body shapes without any router
  edit.
- **Audit-on-intent placement.** The intent audit INSERT runs
  inside the first-call transaction BEFORE the
  `confirmStateService.remember` call so a remember-then-crash
  path still leaves the intent row in `audit_log`. If validation
  fails on the first call, no audit row is written and no
  pending state is stored (matches the M1-051 BAN flow).
- **InboundRouter behavior.** No router edit — `InboundRouter.handleSlash`
  iterates `Instance<CommandHandler>` and matches by `handler.name()`.
  Four new beans land in `app.zcat.infochat.provider.command`; the
  router picks them up automatically.
- **CommandPermissions.** All four commands are bot-admin-only —
  not in the slow-start ALLOWED set, and intentionally so: a
  probation user has no admin privilege to exercise them. The
  M1-045 intake-side step 5 gate runs before the handler is
  reached for non-admin probation users; the handler's admin-only
  reject runs after for the rare case (admin during probation,
  which is invariant-impossible per spec — but the handler
  defensively checks anyway for defence-in-depth, matching the
  M1-039 ban-check precedent).
- **Files-budget tightness.** files_budget is set to 12 — exactly
  the count of paths in `files_scope` (9 new files + 3 modified:
  4 handlers + 4 handler tests + 1 IT, plus AuditAction.java,
  BundleKeys.java, en.properties). The `SourceManagementIT` probe
  fixture follows the M1-036 precedent (`AddSourceIT.java:271`
  defines `LoopbackProbe` as a nested
  `public static class ... extends UrlProbe` annotated
  `@Alternative` inside the IT file — one inner class, well below
  the [[feedback_avoid_test_inner_classes]] >3 threshold), so no
  13th fixture file is needed. If the implementing session finds
  the count rising past 12 for OTHER reasons (e.g., a
  `SourceManagementArgs.java` parser extraction, a shared
  `SourceCommandPermissionCheck` helper), the workflow path is
  the M1-008 / M1-044 umbrella+subs escape hatch — escalate at
  start, split into one umbrella (cross-cutting IT + bundle keys
  + AuditAction edit) and three subs (`/list-sources`,
  `/remove-source`, `/source-enable + /source-disable`). The
  umbrella+subs path is **flagged here** so the operator can
  surface it at `/m1-tick start` rather than mid-implementation.
- **T2-H parallel collision.** T2-B.2 does NOT add a migration
  and does NOT touch BundleKeys constants T2-H would also need;
  the only shared seam is `en.properties` (both groups append).
  The `BundleLoaderTest` catches any rebase drop.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-053-source-management-admin-commands.md
```
