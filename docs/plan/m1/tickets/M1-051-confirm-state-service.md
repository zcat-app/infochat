---
id: M1-051
title: ConfirmStateService — pre-dispatch confirm gate for /ban + /invite create --open + /invite revoke
status: done
created: 2026-05-23
last_updated: 2026-05-23
clarity_check:
  date: 2026-05-23
  verdict: PASS
  warnings: []
reviews:
  - round: 1
    date: 2026-05-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 1872
      removed: 46
  - round: 2
    date: 2026-05-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 18
      added: 2308
      removed: 59
    must_shrink_citation: |
      Round-2 grew along all three dimensions (16→18 files, +1872→+2308,
      -46→-59) per the explicit redteam-finding remediation in
      escalations[1] (workflow_override: true). The reviewer ruled the
      must-shrink exception applies — the cumulative growth is precisely
      scoped to closing the AUDIT-EVASION gap.
redteam_findings:
  - date: 2026-05-23
    category: AUDIT-EVASION
    severity: low
    promise: |
      docs/spec/security.md §Authorization model: "Authorization
      evaluation order on every inbound message: ... 7. Permission
      check ... 8. Audit-log the intent. 9. Execute. 10. LLM only
      enters for chat-mode replies, summary prose, and the eval
      pipeline."
    gap: |
      For confirmable destructive commands (/ban, /invite create
      --open, /invite revoke), the first-call path passes the admin
      gate and self-ban guard (the spec's step-7 permission check),
      then stores the validated intent in ConfirmStateService.remember
      and returns a prompt — but writes NO audit_log row. Sites:
      BanCommandHandler.java:170-178; InviteCommandHandler.java:254-264
      (--open) and :506-516 (revoke). Existing test
      banFirstCallReturnsPromptAndWritesNoStateChange asserts this as
      a deliberate invariant.
    repro: |
      A bot-admin who has passed admin-gate issues /ban target in DM.
      Router stores pending in-memory, sends the prompt, returns. The
      admin never types /ban confirm; the 60s timeout lapses, lazy-
      expiry removes the entry on the next peek/take, and no audit_log
      row exists. Effect: an admin can probe-and-abandon /ban target
      repeatedly (within transport-rate-cap) to determine whether the
      system would proceed to the confirm step against any given
      target (e.g., learn the target is registered + not last admin)
      without leaving an audit trail. Same shape for /invite create
      --open (probe open-cap) and /invite revoke (probe code-prefix
      acceptability).
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-05-23
    verdict: FINDINGS
    base: main
    head: 08fc2ca
    verdict_file: docs/plan/m1/redteam/M1-051-2026-05-23.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      One LOW AUDIT-EVASION finding — confirmable destructive commands
      pass step-7 admin gate but skip step-8 audit-on-intent; audit
      fires only inside the execute leg. Diff explicitly tests the
      no-audit-on-prompt invariant, so closing this requires a spec-
      amend (does step 8 fire when step 9 is "remember pending + send
      prompt" rather than the destructive action?) plus a remediation
      ticket. Two OUT-OF-MODEL notes: isConfirmShape relaxation
      accepts retyped args (UX footgun, audit-accurate) and the
      step-4.5 peek+takeAny pair is non-atomic across two CHM
      operations (race-relevant only if the adapter ever delivers
      concurrently from the same actor).
  blockers: []
outline_file: target/m1-tick-outline-M1-051.md
escalations:
  - date: 2026-05-23
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (escalation surfaced at start by plan-writer subagent risk-2 + risk-1
      ground-truth: adding @Inject ConfirmStateService to InboundRouter NPEs in
      three plain-JUnit tests (InboundRouterContactIdRedactionTest,
      InboundRouterIntakeOrderingTest, InboundRouterNormalizeTest) that
      hand-construct the router and explicitly set every collaborator field.
      These tests are NOT in files_scope and the §Authorized test changes
      section does not authorize them. Refine needed to expand files_scope
      (+3), bump files_budget (13→16), authorize the Noop wiring edits, and
      pin the no-CallLog-pollution requirement for IntakeOrderingTest's
      per-step call-order assertions.
  - date: 2026-05-23
    reason: redteam-finding
    workflow_override: true
    workflow_override_note: |
      Round-1 APPROVE landed as branch commit 08fc2ca. Post-commit /redteam
      surfaced 1 LOW AUDIT-EVASION finding (see redteam_findings[0] + the
      per-audit verdict file docs/plan/m1/redteam/M1-051-2026-05-23.md).
      User elected to fix in this ticket rather than draft a remediation
      ticket per the workflow's documented refusal (escalate.md line 14:
      "REFUSED if the operand ticket has status: done"). Override scope:
      (1) status flipped done → in-progress manually (normally forbidden);
      (2) branch reset via `git reset --soft HEAD~1` to unstage 08fc2ca
      while preserving the implementation in the working tree; (3) audit-
      on-intent fix folded in alongside the round-1 implementation; (4)
      round 2 will re-review the cumulative diff. Round-cap remains 3.
    reviewer_verdict_excerpt: |
      N/A — escalation surfaced by /redteam, not /m1-tick review. The
      finding's promise/gap/repro live verbatim in redteam_findings[0].
revisions:
  - date: 2026-05-23
    reason: budget-breach
    snapshot:
      files_budget: 13
      files_scope_count: 12
blocked_by: []
files_budget: 17
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmStateServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmFlowIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
verified_stays_green:
  - test_class: app.zcat.infochat.provider.messaging.HelpCommandHandlerTest
    rationale: "M1-049 plain JUnit, calls handler.handle() directly; does not consult ConfirmStateService and dispatches command name \"help\" which has no confirm requirement; cannot be intercepted by the new router-side step 4.5 sweep because it does not exercise the router"
  - test_class: app.zcat.infochat.provider.command.AddSourceCommandHandlerTest
    rationale: "M1-049 plain JUnit, calls handler.handle() directly; /add-source has no confirm requirement and the handler does not consult ConfirmStateService; cannot be intercepted by the new router-side step 4.5 sweep"
  - test_class: app.zcat.infochat.provider.command.AddSourceBanCheckOrderingTest
    rationale: "M1-049 plain JUnit, calls handler.handle() directly; no router involvement, no confirm requirement on /add-source"
  - test_class: app.zcat.infochat.provider.command.SummaryCommandHandlerTest
    rationale: "M1-049 plain JUnit, calls handler.handle() directly; /summary has no confirm requirement and the handler does not consult ConfirmStateService"
  - test_class: app.zcat.infochat.provider.command.UnbanCommandHandlerTest
    rationale: "/unban is NOT in the confirmable-command catalogue (design 03-commands.md §Confirmation for destructive commands); UnbanCommandHandler does not consult ConfirmStateService and is not modified by this ticket"
  - test_class: app.zcat.infochat.provider.messaging.AdapterRegistryTest
    rationale: "uses RecordingInboundRouter @Alternative that intercepts onMessage(); real router step 4.5 sweep is bypassed"
  - test_class: app.zcat.infochat.provider.messaging.AutoRegisterServiceTest
    rationale: "exercises AutoRegisterService directly without the router; pending-confirm state never enters the picture"
  - test_class: app.zcat.infochat.provider.command.SummaryIT
    rationale: "drives the full InboundRouter dispatch path but sends /summary inbounds only; the new step 4.5 sweep is a no-op when no pending state exists for the test's (user, DM) — and /summary itself never registers pending state"
  - test_class: app.zcat.infochat.provider.command.AddSourceIT
    rationale: "drives the full InboundRouter dispatch path but sends /add-source inbounds only; same no-op-on-empty-pending argument as SummaryIT"
  - test_class: app.zcat.infochat.provider.command.SummaryAdapterScopeIT
    rationale: "drives the full InboundRouter dispatch path but sends /summary inbounds only; same no-op-on-empty-pending argument"
  - test_class: app.zcat.infochat.provider.command.AddSourceAdapterScopeIT
    rationale: "drives the full InboundRouter dispatch path but sends /add-source inbounds only; same no-op-on-empty-pending argument"
  - test_class: app.zcat.infochat.provider.messaging.AdapterRouterIT
    rationale: "drives the full InboundRouter dispatch path but sends /help and /unknown-command inbounds only; neither registers pending state and the step 4.5 sweep is a no-op when no pending exists"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterTest
    rationale: "drives the router via /help, /xyz, /boom only; never registers pending state; the new step 4.5 sweep is a no-op when no pending exists"
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
remediates: M1-044c
out_of_scope:
  - any change to the spec — docs/spec/commands.md §Surface conventions ("Confirmation for destructive commands") and docs/spec/security.md §What's intentionally NOT in v1 ("Two-factor confirmation for ban — single-step confirm-within-window is enough for v1.") are the source of truth
  - any change to the M1-044a services (RateCapBucket, InviteCodeConsumer, BanCheck, AutoRegisterService, V12 migration) — consumed unchanged
  - any change to the M1-044b intake-step ordering for steps 1.5 / 1.7 / 2 / 3 / 4 / 7 — this ticket inserts a NEW step 4.5 between the ban check and the existing step 6 dispatch and does not modify any pre-existing step
  - any change to UnbanCommandHandler — /unban is NOT in the confirmable-command catalogue (design 03-commands.md §Confirmation for destructive commands enumerates /clear, /remove-source, /ban, /forget, /unfollow-tag --all, /source-enable against soft-deleted, /invite create --open, /invite revoke, /quarantine reject)
  - any change to /invite create --contact — spec §Admin (commands.md line 881) says explicitly "No confirmation required (risk is bounded to one specific identity)"
  - any change to /invite list — read-only command, no spec confirm requirement
  - any /grant-admin / /revoke-admin handler — M1-046 territory; the spec does not currently list /grant-admin or /revoke-admin in the confirmable catalogue (no entry in design 03-commands.md §Confirmation for destructive commands), so retrofitting those onto ConfirmStateService is a spec-amend conversation tracked separately
  - any /quarantine handler — T2-G territory; ConfirmStateService is intentionally designed to be consumed by future destructive admin handlers (see Big-picture notes) but no /quarantine code lands here
  - any /clear / /remove-source / /forget / /unfollow-tag --all handler — none implemented in v1 yet; same future-consumer note applies
  - any /stop handler implementation — /stop is currently unimplemented and falls through to the unknown-command reply; the step 4.5 sweep handles /stop's pending-cancellation side effect via the same "any other input cancels" branch it uses for every non-confirm inbound, so the spec promise that "/stop cancels a pending confirmation as a side effect of its 'any other input' treatment" holds against the current /stop dispatch shape
  - any audit-log writer consolidation — M1-041 territory; the step 4.5 sweep itself writes NO audit row because the cancellation is a UX-only acknowledgement (no state mutation past clearing in-memory pending), per spec which treats the prompt-then-cancel cycle as a single failed-to-confirm intent
  - any persistence of confirm state across Provider restart — spec §Surface conventions ("Confirmation state is in-memory only") commits to losing pending state on restart; no DB table, no Flyway migration
  - any TranslationProvider exercise — T2-C territory; new bundle entries are English only
  - any test outside the twelve files in files_scope — the enumeration above under verified_stays_green covers every shared-dispatch-surface test the heuristic catches
acceptance:
  - "infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java is a `@ApplicationScoped` CDI bean holding pending confirmations in a `ConcurrentHashMap` keyed by `(UUID actorUserId, ScopeRef scope)` (a single composite key class is acceptable; the per-key value is the pending confirm). The service exposes at minimum: (a) a remember entry point that stores a pending confirm tagged with a `commandName` String and a deadline computed as `clock.instant() + timeout` where `timeout` is read from `infochat.confirm.timeout` via `@ConfigProperty`; (b) a take-matching entry point that returns the pending value AND removes it from the map IF the stored `commandName` equals the argument AND the stored deadline is after `clock.instant()`; (c) a take-any entry point that returns the pending value AND removes it from the map regardless of `commandName`, deadline-checked the same way; (d) a peek entry point that returns the pending value WITHOUT removing it, deadline-checked. Lazy expiry: a take/peek call whose stored entry is past deadline removes the entry and returns empty. No background sweep thread is required. `Clock` is `@Inject`ed (CDI producer for `Clock.systemUTC()` ships in this ticket) so tests can substitute a fake clock. Verify: `grep -E '@ApplicationScoped' ConfirmStateService.java` returns ≥1 match; `grep -E 'ConcurrentHashMap' ConfirmStateService.java` returns ≥1 match; `grep -E '@ConfigProperty.*infochat\\.confirm\\.timeout' ConfirmStateService.java` returns ≥1 match; `grep -E 'Clock' ConfirmStateService.java` returns ≥1 match"
  - "infochat-provider/src/main/resources/application.properties declares `infochat.confirm.timeout=60s` as the base default AND per-profile overrides matching design 03-commands.md §Confirmation for destructive commands (laptop=60s, vps=60s, pi=90s, remote-llm=60s) under the existing `%<profile>.infochat.*` namespace convention. Verify: `grep -E '^infochat\\.confirm\\.timeout\\s*=\\s*60s' application.properties` returns 1 match; `grep -E '^%pi\\.infochat\\.confirm\\.timeout\\s*=\\s*90s' application.properties` returns 1 match; `grep -E '^%laptop\\.infochat\\.confirm\\.timeout\\s*=\\s*60s' application.properties` returns 1 match; `grep -E '^%vps\\.infochat\\.confirm\\.timeout\\s*=\\s*60s' application.properties` returns 1 match; `grep -E '^%remote-llm\\.infochat\\.confirm\\.timeout\\s*=\\s*60s' application.properties` returns 1 match"
  - "ConfirmStateServiceTest scenario: remember-then-takeMatching with the same commandName and a fake clock advanced ZERO seconds returns the stored value AND the map no longer contains the key (a second takeMatching returns empty). Verify: `grep -iE 'void\\s+\\w*rememberThenTakeMatchingReturnsValueAndRemovesEntry\\w*\\s*\\(' ConfirmStateServiceTest.java` returns ≥1 match"
  - "ConfirmStateServiceTest scenario: remember-then-takeMatching with a DIFFERENT commandName returns empty AND the map STILL contains the original entry (a subsequent peek with the original commandName returns the value). Verify: `grep -iE 'void\\s+\\w*takeMatchingWithDifferentCommandNameReturnsEmptyAndPreservesEntry\\w*\\s*\\(' ConfirmStateServiceTest.java` returns ≥1 match"
  - "ConfirmStateServiceTest scenario: remember-then-advance-fake-clock-past-deadline-then-takeMatching returns empty AND removes the expired entry (a subsequent peek returns empty). Verify: `grep -iE 'void\\s+\\w*takeMatchingPastDeadlineReturnsEmptyAndRemovesExpiredEntry\\w*\\s*\\(' ConfirmStateServiceTest.java` returns ≥1 match"
  - "ConfirmStateServiceTest scenario: takeAny returns the stored value regardless of commandName and removes the entry. Verify: `grep -iE 'void\\s+\\w*takeAnyReturnsValueAndRemovesEntry\\w*\\s*\\(' ConfirmStateServiceTest.java` returns ≥1 match"
  - "ConfirmStateServiceTest scenario: two remembers for the SAME `(actorUserId, scope)` with different commandNames — the second remember OVERWRITES the first (a takeMatching for the FIRST commandName returns empty; a takeMatching for the SECOND returns the value). The same-key second remember acts as a replace, not a multi-pending merge. Verify: `grep -iE 'void\\s+\\w*secondRememberOverwritesFirstPendingForSameKey\\w*\\s*\\(' ConfirmStateServiceTest.java` returns ≥1 match"
  - "ConfirmStateServiceTest scenario: remembers for two DIFFERENT `actorUserId` values are isolated — a takeMatching by either actor returns only that actor's value. Verify: `grep -iE 'void\\s+\\w*remembersForDifferentActorIdsAreIsolated\\w*\\s*\\(' ConfirmStateServiceTest.java` returns ≥1 match"
  - "InboundRouter.java inserts a NEW step labeled `Step 4.5 — confirm-cancel sweep` between the existing step 4 (ban check) and step 6 (parse + dispatch). The step logic: if the resolved user snapshot is non-empty AND `confirmStateService.peek(snapshot.id(), msg.scope())` returns non-empty AND the normalized body does NOT equal `\"/\" + pendingCommandName + \" confirm\"` (the exact post-normalize confirm token) AND the normalized body does NOT start with `\"/\" + pendingCommandName + \" \"` followed by anything ending in ` confirm` (the per-handler reparse case for commands whose original args are retyped before `confirm`), then `confirmStateService.takeAny(snapshot.id(), msg.scope())` is called to drain the pending entry and a cancellation acknowledgement is sent via `sendReply(msg.scope(), bundleLoader.get(BundleKeys.REPLY_CONFIRM_CANCELLED).formatted(pendingCommandName))` BEFORE proceeding to step 6. If the snapshot IS empty (unregistered DM contact, or unregistered group contact arriving via step 3 auto-register), the sweep is skipped (a fresh user cannot have pending confirm state). Verify: `grep -E 'Step 4\\.5|step 4\\.5|confirm-cancel sweep' InboundRouter.java` returns ≥1 match; `grep -E 'confirmStateService' InboundRouter.java` returns ≥2 matches (the peek call AND the takeAny call); `grep -E 'REPLY_CONFIRM_CANCELLED' InboundRouter.java` returns ≥1 match"
  - "BanCommandHandler.handle entry point dispatch shape: after admin-gate + parse, if the normalized body ENDS with the literal token ` confirm` (whitespace-then-confirm-at-end), the handler calls `confirmStateService.takeMatching(actor.id(), scope, \"ban\")` — empty Optional returns `error.confirm.no_pending` (a friendly reply pointing at the original /ban command); non-empty Optional carries the previously-stored target+reason and the handler proceeds with the existing transaction (audit-before-effect + UPDATE/INSERT + INVITE_REVOKE per M1-044c). On the FIRST-call path (body does NOT end with ` confirm`), the handler validates admin-gate + parse + target-exists + not-self + not-last-admin (the existing pre-flight checks) AND on validation success calls `confirmStateService.remember(actor.id(), scope, \"ban\", <prepared-target-and-reason>)` and returns `reply.confirm.prompt.ban` (the bundle template interpolates the redacted target contact id AND the timeout-in-seconds derived from the same `infochat.confirm.timeout` value the service reads). Validation FAILURES on the first-call path (non-admin, unknown contact, self-ban, last-admin) return the same friendly errors as M1-044c and write NO pending state. Verify: `grep -E 'confirmStateService\\.takeMatching' BanCommandHandler.java` returns ≥1 match; `grep -E 'confirmStateService\\.remember' BanCommandHandler.java` returns ≥1 match; `grep -E 'REPLY_CONFIRM_PROMPT_BAN' BanCommandHandler.java` returns ≥1 match; `grep -E 'ERROR_CONFIRM_NO_PENDING' BanCommandHandler.java` returns ≥1 match"
  - "InviteCommandHandler.handleCreate routing shape: /invite create --contact <id> takes the FIRST-call path identical to M1-044c (no confirm gate, no ConfirmStateService call). /invite create --open takes the confirm gate: first call validates + stores pending under commandName `\"invite:create:open\"` + returns `reply.confirm.prompt.invite_create_open` (interpolating the target adapter AND timeout seconds); a body ending in ` confirm` takes-matching against the same commandName + executes the existing M1-044c INSERT INTO invite_code path on success, or returns `error.confirm.no_pending` on miss. Verify: `grep -E 'invite:create:open|INVITE_CREATE_OPEN' InviteCommandHandler.java` returns ≥1 match; `grep -E 'REPLY_CONFIRM_PROMPT_INVITE_CREATE_OPEN' InviteCommandHandler.java` returns ≥1 match"
  - "InviteCommandHandler.handleRevoke routing shape: first call (body is `/invite revoke <code>` without trailing ` confirm`) validates the code shape + admin-gate, stores pending under commandName `\"invite:revoke\"` capturing the code, returns `reply.confirm.prompt.invite_revoke` (interpolating the redacted code-prefix AND timeout seconds). A follow-up body ending in ` confirm` takes-matching + executes the existing M1-044c PENDING→REVOKED UPDATE (and writes the INVITE_REVOKE audit row audit-before-effect inside the existing transaction) on success, or returns `error.confirm.no_pending` on miss. Verify: `grep -E 'invite:revoke|INVITE_REVOKE_CMD' InviteCommandHandler.java` returns ≥1 match; `grep -E 'REPLY_CONFIRM_PROMPT_INVITE_REVOKE' InviteCommandHandler.java` returns ≥1 match"
  - "BundleKeys.java adds the new public constants: ERROR_CONFIRM_NO_PENDING, REPLY_CONFIRM_CANCELLED, REPLY_CONFIRM_PROMPT_BAN, REPLY_CONFIRM_PROMPT_INVITE_CREATE_OPEN, REPLY_CONFIRM_PROMPT_INVITE_REVOKE. bundles/en.properties adds the corresponding entries. The cancellation template uses MessageFormat `{0}` for the cancelled command's display name (e.g. `\"Pending {0} cancelled.\"`). Each prompt template uses MessageFormat `{0}` for the timeout seconds AND `{1}` for the target reference (redacted contact id for /ban, target adapter for /invite create --open, redacted code prefix for /invite revoke). The M1-035c reflective `BundleLoaderTest` assertion catches any missing key automatically. Verify: `grep -E '^reply\\.confirm\\.cancelled\\s*=' bundles/en.properties` returns 1 match; `grep -E '^reply\\.confirm\\.prompt\\.ban\\s*=' bundles/en.properties` returns 1 match; `grep -E '^reply\\.confirm\\.prompt\\.invite_create_open\\s*=' bundles/en.properties` returns 1 match; `grep -E '^reply\\.confirm\\.prompt\\.invite_revoke\\s*=' bundles/en.properties` returns 1 match; `grep -E '^error\\.confirm\\.no_pending\\s*=' bundles/en.properties` returns 1 match"
  - "BanCommandHandlerTest scenario: an admin's first /ban returns the confirm prompt, writes NO `users` row mutation, NO `invite_code` mutation, AND writes exactly ONE `audit_log` row with `action='BAN_INTENT'`, `target_kind='user'`, `target_contact_id=<target>` and `details_json` containing the parsed reason (the spec §Authorization model step 8 'Audit-log the intent' row, written between the step-7 permission check and the step-9 execute that confirm-gating defers); `ConfirmStateService.peek` shows pending state for `(actor.id, scope, \"ban\")`. Verify: `grep -iE 'void\\s+\\w*banFirstCallReturnsPromptAndWritesIntentAuditRowOnly\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: an admin's first /ban followed by `/ban confirm` within the timeout EXECUTES the M1-044c ban transaction end-to-end (the existing M1-044c assertions about `users` row state + audit row + invite revoke all hold) AND clears pending state. Verify: `grep -iE 'void\\s+\\w*banConfirmWithinWindowExecutesBanTransaction\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: a `/ban confirm` issued with NO prior /ban (no pending state) returns `error.confirm.no_pending` AND writes NO state change. Verify: `grep -iE 'void\\s+\\w*banConfirmWithoutPendingReturnsNoPending\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "BanCommandHandlerTest scenario: an admin's first /ban followed by `/ban confirm` AFTER the timeout (fake clock advanced past `infochat.confirm.timeout`) returns `error.confirm.no_pending` AND writes NO state change. Verify: `grep -iE 'void\\s+\\w*banConfirmAfterTimeoutReturnsNoPending\\w*\\s*\\(' BanCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: /invite create --adapter inmemory --contact x — the FIRST-call happy path executes the M1-044c create transaction directly (NO confirm prompt, NO pending state stored) per spec §Admin line 881 (`No confirmation required` for --contact). The existing M1-044c scenario `inviteCreateContactBoundHappyPath` is REPURPOSED to assert NO pending state appears on `ConfirmStateService.peek`. Verify: `grep -iE 'void\\s+\\w*inviteCreateContactBoundHappyPathDoesNotInvokeConfirmGate\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: /invite create --adapter inmemory --open — the FIRST-call path returns the prompt, writes NO `invite_code` row, AND writes exactly ONE `audit_log` row with `action='INVITE_CREATE_INTENT'` and `details_json` carrying the requested target adapter (the spec §Authorization model step 8 audit-on-intent row); `ConfirmStateService.peek` shows pending state under commandName `\"invite:create:open\"`. Verify: `grep -iE 'void\\s+\\w*inviteCreateOpenFirstCallReturnsPromptAndWritesIntentAuditRowOnly\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: /invite create --adapter inmemory --open followed by `/invite create --open confirm` within the timeout EXECUTES the M1-044c INSERT INTO invite_code (the existing `inviteCreateOpenHappyPath` assertions about the row's invite_type/expected_contact_id/PENDING status + the INVITE_CREATE audit row all hold) AND clears pending state. Verify: `grep -iE 'void\\s+\\w*inviteCreateOpenConfirmWithinWindowExecutesCreateTransaction\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InviteCommandHandlerTest scenario: /invite revoke <code> against a PENDING row — the FIRST-call path returns the prompt, writes NO `invite_code` mutation (the row STAYS in PENDING), AND writes exactly ONE `audit_log` row with `action='INVITE_REVOKE_INTENT'`, `target_kind='invite'`, `target_id=<code>` (the spec §Authorization model step 8 audit-on-intent row); a follow-up `/invite revoke confirm` within the timeout EXECUTES the M1-044c PENDING→REVOKED transition + writes the second `INVITE_REVOKE` audit row (the step-9 completion row). Both audit rows persist; the intent row records WHO attempted, the completion row records THAT IT EXECUTED. Verify: `grep -iE 'void\\s+\\w*inviteRevokeFirstCallThenConfirmExecutesRevokeTransaction\\w*\\s*\\(' InviteCommandHandlerTest.java` returns ≥1 match"
  - "InboundRouterConfirmCancelTest is plain JUnit per the M1-049 test-pyramid convention (handler-tier handlers were unit-tested without @QuarkusTest; this test exercises the router's step 4.5 sweep with mocked collaborators including a fake ConfirmStateService that returns a fixed pending value). The test covers: (a) router sees an inbound whose body does NOT match the pending-confirm shape, calls confirmStateService.takeAny, sends `reply.confirm.cancelled` BEFORE proceeding to dispatch; (b) router sees an inbound whose body IS the pending-confirm shape (`/ban confirm` matching pending `\"ban\"`), does NOT call takeAny in step 4.5 (leaves the entry for the handler's takeMatching call), proceeds directly to dispatch with NO cancellation acknowledgement sent; (c) router sees an inbound when peek returns empty, takes the no-op path (no takeAny, no cancellation send) and proceeds to dispatch. Verify: `grep -cE '@QuarkusTest' InboundRouterConfirmCancelTest.java` returns 0; `grep -iE 'void\\s+\\w*nonMatchingInputCancelsPendingAndSendsAcknowledgement\\w*\\s*\\(' InboundRouterConfirmCancelTest.java` returns ≥1 match; `grep -iE 'void\\s+\\w*matchingConfirmInputDoesNotCancelAndProceedsToDispatch\\w*\\s*\\(' InboundRouterConfirmCancelTest.java` returns ≥1 match; `grep -iE 'void\\s+\\w*emptyPendingTakesNoOpPath\\w*\\s*\\(' InboundRouterConfirmCancelTest.java` returns ≥1 match"
  - "ConfirmFlowIT is a `@QuarkusTest`-shaped IT against a Testcontainers Postgres + the in-memory adapter wiring (mirrors AddSourceIT / SummaryIT pattern). The IT covers the full end-to-end pending-then-confirm cycle for /ban specifically: (a) seed an admin user + a target user; (b) issue `/ban target --reason foo` via adapter.deliverDm; assert the response is the prompt AND `users.is_banned` for target is still FALSE; (c) issue `/ban confirm` via adapter.deliverDm; assert the response is the M1-044c ban-success reply AND `users.is_banned` for target is now TRUE AND one BAN audit_log row exists. The same end-to-end shape is also covered for /invite create --open + `/invite create --open confirm` in a second test method (asserting the invite_code row appears only after confirm). Verify: `grep -iE 'void\\s+\\w*banPromptThenConfirmExecutesBanEndToEnd\\w*\\s*\\(' ConfirmFlowIT.java` returns ≥1 match; `grep -iE 'void\\s+\\w*inviteCreateOpenPromptThenConfirmExecutesCreateEndToEnd\\w*\\s*\\(' ConfirmFlowIT.java` returns ≥1 match"
  - "ConfirmFlowIT covers the cross-command cancellation case end-to-end: (a) seed an admin user + a target; (b) issue `/ban target` via adapter.deliverDm; assert the response is the prompt; (c) issue `/help` via adapter.deliverDm; assert the response is the cancellation acknowledgement followed by the /help reply (two outbounds OR one combined outbound — either shape is acceptable as long as both literal strings appear in the captured outbound queue in the right order) AND `users.is_banned` for target is STILL FALSE AND ConfirmStateService.peek for the admin's (id, dm-scope) returns empty. Verify: `grep -iE 'void\\s+\\w*nonMatchingInputAfterBanPromptCancelsPendingAndDispatchesNewCommand\\w*\\s*\\(' ConfirmFlowIT.java` returns ≥1 match"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass per the verified_stays_green: enumeration above (in particular every M1-044c handler test still passes once its scenarios are augmented per the acceptance items above — the existing 24 M1-044c scenarios are PRESERVED, this ticket only ADDS scenarios; no existing scenario method is renamed or deleted)"
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmStateServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ConfirmFlowIT.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
    - infochat-provider/src/main/resources/application.properties
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
  preserves:
    - all tests currently green on main
    - every M1-044c handler test scenario (24 total) — this ticket ADDS new scenarios alongside the existing ones; no rename or deletion
spec_refs:
  - docs/spec/commands.md §Surface conventions
  - docs/spec/security.md §What's intentionally NOT in v1
  - docs/spec/security.md §User ban
  - docs/spec/security.md §Invite-code registration
  - docs/spec/security.md §Authorization model
  - docs/design/03-commands.md §Confirmation for destructive commands
decision_refs: []
---

# M1-051: ConfirmStateService — pre-dispatch confirm gate for /ban + /invite create --open + /invite revoke

## Context

Remediation of the high-severity AUTH-BYPASS finding from the
M1-044c red-team audit (docs/plan/m1/redteam/M1-044c-2026-05-22.md
finding #2). Spec docs/spec/security.md §What's intentionally NOT
in v1 commits "Two-factor confirmation for ban — single-step
confirm-within-window is enough for v1" (i.e. single-step
confirm-within-window IS in v1), and spec §Invite-code
registration commits `/invite create --open` and `/invite revoke`
both require confirm. M1-044c shipped all three command surfaces
without the gate as an acknowledged deliberate temporary spec
deviation; the gap allows an attacker who briefly gains an admin's
chat session to /ban a legit user, mint an open invite for a
hostile adapter, or revoke a pending invite — all in one round
trip with no window for an inattentive admin to notice.

This ticket builds the canonical in-memory ConfirmStateService
(spec §Surface conventions timeout model) and retrofits the
pre-dispatch confirm gate across the three M1-044c handlers that
should have had it. The service is intentionally designed to be
consumed by every future destructive admin command in the
confirmable catalogue (design 03-commands.md §Confirmation for
destructive commands enumerates: /clear, /remove-source, /ban,
/forget, /unfollow-tag --all, /source-enable against soft-deleted,
/invite create --open, /invite revoke, /quarantine reject). This
ticket lands the service AND the three M1-044c retrofits; future
tickets implementing the other confirmable commands consume it
unchanged.

`complexity: high` because the spec compliance bar covers four
moving pieces in one ticket: the service state-machine + per-profile
timeout config, the InboundRouter step 4.5 sweep + cancel-ack
wiring, the per-handler dispatch reshape (first-call vs confirm-call
paths), and end-to-end coverage with a fake clock to verify the
timeout boundary. `risk: high` because a bug in the gate could lock
admins out of /ban or accept stale confirms past the window. The
`round_cap: 3` accommodates likely-to-fail-first acceptance items.

`security_relevant: true` — closes a high AUTH-BYPASS finding.

`migration_touch: false` — spec explicitly forbids persisting
confirm state to the DB.

`remediates: M1-044c`.

## Definition of Done

- A new `ConfirmStateService` CDI bean holds pending confirmations
  in process memory keyed by `(actorUserId, scope)`, expires entries
  lazily against a per-profile `infochat.confirm.timeout`, and
  exposes remember / takeMatching / takeAny / peek entry points.
- `InboundRouter` inserts a new step 4.5 between the ban check and
  the existing parse+dispatch step: a pending confirm whose body
  does NOT match the confirm shape is drained AND a cancellation
  acknowledgement is sent BEFORE dispatch proceeds; an empty pending
  is a no-op. The matching `<cmd> confirm` shape is forwarded to
  dispatch unchanged so the handler can take-matching.
- `BanCommandHandler`, `InviteCommandHandler` (`create --open` and
  `revoke` branches only) gain the first-call-prompt vs confirm-call-execute
  dispatch reshape. `/invite create --contact` continues to execute
  on first call per spec §Admin line 881 (`No confirmation required`).
- New bundle keys + entries land for the prompt + cancellation +
  no-pending-error replies, in English only.
- Per-profile `infochat.confirm.timeout` defaults match design
  03-commands.md §Confirmation for destructive commands
  (laptop/vps/remote-llm 60s, pi 90s).
- Service-tier tests use a fake `Clock` to verify the deadline
  boundary deterministically; handler-tier tests verify the
  prompt-vs-execute behavior; an integration test verifies the
  full pending-then-confirm cycle end-to-end against a
  Testcontainers Postgres + the in-memory adapter.
- `mvn -B clean verify` exits 0; every prior test continues to
  pass per the verified_stays_green: enumeration.

## Implementation notes

- **ConfirmStateService shape.** A `@ApplicationScoped` singleton.
  Internal state is a `ConcurrentHashMap<ConfirmKey, PendingConfirm>`
  where `ConfirmKey` records the `(UUID actorUserId, ScopeRef scope)`
  pair (a small `record` is acceptable; the equality contract on
  `ScopeRef` already covers DM vs Group discrimination by adapter +
  scope id). `PendingConfirm` records `(String commandName,
  Map<String, String> args, Instant deadline)` — `args` is the
  pre-parsed argument bag the handler needs to execute on confirm
  (target user id, ban reason, target adapter, code, etc.). A sealed
  per-command record (`PendingConfirm.Ban`, `PendingConfirm.InviteRevoke`,
  etc.) is equally acceptable and may be clearer; the acceptance
  items pin only the behavior, not the data shape. Lazy expiry: every
  read path checks `clock.instant().isBefore(deadline)` and removes
  the entry if not, returning empty. No background sweep thread is
  required — the spec allows pending entries to outlive the timeout
  in memory as long as no read path returns them.
- **Clock injection.** Add a CDI `@Produces @ApplicationScoped Clock`
  bean that returns `Clock.systemUTC()` in production. Tests substitute
  via `@InjectMock Clock` or a CDI `@Alternative` returning a
  hand-controlled `Clock.fixed` + `Clock.offset`. The service code
  reads `clock.instant()` instead of `Instant.now()` for the timeout
  arithmetic — a small but load-bearing seam for the deadline-boundary
  tests.
- **InboundRouter step 4.5 placement.** Between the existing step 4
  (ban check, line 312-315 of InboundRouter.java) and the existing
  step 6 (parse + dispatch, line 319-329). Use the user id from the
  already-resolved `snapshot` at line 267 — no second SELECT. When
  `snapshot.isEmpty()`, skip the sweep entirely (an unregistered
  contact cannot have pending confirm state because every
  remember-path runs only inside a handler that has admin-gated the
  caller, which requires a registered users row). M1-045's reserved
  step 5 lives between step 4 and the new step 4.5 in the ordering
  comment; choose any numeric label that preserves ordering clarity
  (4.5 / 5.5 / 6.0 — the prose-tag matters for the verified_stays_green:
  ordering test InboundRouterIntakeOrderingTest, not the literal number).
- **Sweep dispatch logic.** Peek pending. If empty → no-op. If
  pending: compute the normalized confirm tokens that would match the
  pending command (e.g. for pending `"ban"` the matching token is
  `"/ban confirm"` or `"/ban <args> confirm"` — any body ending in
  ` confirm` whose first slash-stripped token equals the pending
  command name). If the inbound body matches, leave the pending entry
  for the handler's takeMatching call and proceed to dispatch unchanged.
  Else takeAny → send cancellation ack via the existing `sendReply`
  helper using `bundleLoader.get(REPLY_CONFIRM_CANCELLED).formatted(pendingCommandName)`
  → proceed to dispatch. The cancellation ack is a separate outbound
  from the subsequent normal dispatch reply per design 03-commands.md
  example (`▎ Pending /clear cancelled.` then `▎ (and the bot answers
  what's the weather? normally)`). Two outbounds is the canonical
  shape; one combined outbound is also acceptable as long as both
  literal strings appear in order.
- **Per-handler dispatch reshape.** Each confirm-gated handler grows
  a two-mode entry point. The first mode (body does NOT end in
  ` confirm`) runs the same admin-gate + parse + pre-flight
  validation as M1-044c — every error path returns the same friendly
  reply with NO pending state stored. On validation success, the
  handler calls `confirmStateService.remember(actor.id, scope,
  commandName, args)` and returns the prompt bundle template. The
  second mode (body ends in ` confirm`) calls
  `confirmStateService.takeMatching(actor.id, scope, commandName)`;
  empty → friendly `error.confirm.no_pending` reply; non-empty → the
  existing M1-044c transaction with the captured args. Audit-before-effect
  semantics are preserved unchanged because the audit row is still
  the FIRST write inside the same execution transaction on the
  confirm path — the prompt path writes nothing to the DB.
- **Why not auto-cancel on duplicate /ban.** Spec says "any other
  input cancels," which covers the same-command-different-args case
  (a second /ban for a different target). The router's step 4.5
  sweep treats the second /ban as "any other input" because the body
  doesn't end in ` confirm`; the old pending /ban is cancelled, the
  cancellation ack is sent, and the new /ban runs through the handler
  which stores a fresh pending. This is the same behavior as /help
  arriving after a pending /ban — the spec does not carve out
  same-command repeats.
- **`/stop` interaction.** /stop is unimplemented (falls through to
  the unknown-command reply). Its pending-confirm cancellation
  promise (spec §Surface conventions: `/stop cancels a pending
  confirmation as a side effect of its 'any other input' treatment`)
  is satisfied by the step 4.5 sweep, which treats /stop the same as
  any non-confirm inbound. When /stop's full implementation lands,
  the step 4.5 sweep already does the right thing.
- **Audit posture.** The cancellation flow writes NO audit row.
  Spec treats the prompt-then-cancel cycle as a single failed-to-confirm
  intent (no state mutation past in-memory pending), and the
  redteam-finding scope is the AUTH-BYPASS that the confirm-WITHIN-window
  closes, not audit completeness of the cancel path. Audit-on-intent
  hardening for handler-level rejections is a separate M1-044 follow-up
  ticket (see HANDOFF.md follow-up #4).
- **Out-of-band consumers.** `M1-046` (`/grant-admin` / `/revoke-admin`)
  and the future T2-G `/quarantine reject` should adopt this service
  when they land. Whether `/grant-admin` and `/revoke-admin` require
  confirm at all is a separate spec-amend conversation tracked in
  HANDOFF.md follow-up #3 — design 03-commands.md §Confirmation for
  destructive commands does not currently list them. This ticket
  delivers the service shape that future consumers can wire into via
  the same `remember` + `takeMatching` calls.

## Big-picture notes

- The service is intentionally designed for reuse: every future
  destructive admin command in the confirmable catalogue (design
  03-commands.md §Confirmation for destructive commands) consumes
  the same `ConfirmStateService` API surface. No per-command service
  classes proliferate. The `commandName` string is the dispatch
  discriminator; `Map<String, String> args` (or a sealed-record
  alternative) holds whatever context the handler needs to execute
  on confirm.
- Future-consumer list (informational, no implementation here):
  /clear, /remove-source, /forget, /unfollow-tag --all, /source-enable
  against soft-deleted, /quarantine reject for forensic-path rows.
  Each one's future ticket adds its prompt bundle key + its first-call
  vs confirm-call dispatch reshape; the service itself stays unchanged.
- The router's step 4.5 sweep is the SINGLE cancel-on-any-other-input
  enforcement point. Handlers do NOT individually probe pending state
  to cancel; they only own the prompt-vs-execute decision for their
  own command. This split keeps the "any other input cancels" semantics
  uniform across all confirmable commands, including future ones the
  handler authors don't have to remember to enforce.
- The redteam-finding scope is the AUTH-BYPASS that single-step
  confirm-within-window closes. The four other M1-044c findings
  (audit-redaction-hook bypass, /invite revoke not auditing failed
  probes, cross-adapter /invite create perm-escal, SQLException-cause
  contact-id leak, unbounded --reason, handler-level rejections
  unaudited) are NOT in this ticket's scope — they live in M1-041
  (reopened), a future M1-044 hardening subticket, and the cross-adapter
  spec-amend discussion (HANDOFF.md follow-ups #2, #3, #4).

## Out-of-scope expansion

`out_of_scope` lists thirteen entries above. The shape: every
adjacent surface that someone might be tempted to bundle into this
ticket is named, with the reason it stays out.

- The spec is the source of truth — this ticket implements existing
  spec text, no spec amendment.
- M1-044a services + M1-044b intake-step ordering + M1-044c handlers
  (other than BanCommandHandler and InviteCommandHandler) stay
  unchanged. UnbanCommandHandler is explicitly preserved because
  /unban is not confirmable per spec.
- /grant-admin, /revoke-admin (M1-046), /promote, /demote (T2-F),
  /quarantine (T2-G), /clear, /remove-source, /forget,
  /unfollow-tag --all (not yet implemented) — none of these handlers
  land here even though several would eventually become
  ConfirmStateService consumers. Pulling them into this ticket would
  blow the budget and entangle the redteam-finding remediation with
  unrelated handler work.
- Audit-log writer consolidation (M1-041 territory) is left alone —
  the step 4.5 cancellation writes no audit row by design (no state
  mutation), and the existing M1-044c handler audit-INSERT sites are
  not touched by this ticket.
- Persisting confirm state to the DB is forbidden by spec — no
  Flyway migration, no `migration_touch: true`.
- TranslationProvider exercise (T2-C) is out — new bundle entries
  are English only.

## Authorized test changes

This ticket modifies five pre-existing test files:

- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java`
  — ADDS new `@Test` scenarios (banFirstCallReturnsPromptAndWritesIntentAuditRowOnly
  [round-2 rename from banFirstCallReturnsPromptAndWritesNoStateChange to reflect the
  audit-on-intent fix landing in this round],
  banConfirmWithinWindowExecutesBanTransaction,
  banConfirmWithoutPendingReturnsNoPending,
  banConfirmAfterTimeoutReturnsNoPending). The round-1 method-name carve-out
  (`NoStateChange` → `IntentAuditRowOnly`) is the only rename in this file; no
  existing scenario is deleted; the M1-044c happy-path scenarios are augmented
  to drive the handler via the confirm flow (an existing `ban happy
  path` test that asserted the M1-044c transaction now drives the
  flow via prompt-then-confirm, but the SAME assertions about the
  end-state of `users` + `audit_log` + `invite_code` hold — the
  intermediate state-after-prompt becomes a new explicit assertion).
  ADDS a `countAuditByActionAndTarget(action, contactId)` helper to
  assert per-(action, target) audit row counts (used by the new BAN_INTENT
  assertions in the renamed first-call scenario, the augmented
  `banAndInviteRevokeAuditRowsShareRequestId`, and the augmented
  `banOfOnlyAdminSurfacesLastAdminError`).
- `infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java`
  — ADDS new `@Test` scenarios for the prompt-then-confirm path on
  `--open` and `revoke` (with the round-2 rename
  `inviteCreateOpenFirstCallReturnsPromptAndWritesNoStateChange` →
  `inviteCreateOpenFirstCallReturnsPromptAndWritesIntentAuditRowOnly`
  to reflect the audit-on-intent fix); the existing `--contact` scenarios are
  preserved with an additional assertion that no pending state is
  recorded; the `--open` and `revoke` happy-path scenarios are
  similarly augmented to drive the flow via prompt-then-confirm; the
  `inviteRevokeFirstCallThenConfirmExecutesRevokeTransaction` scenario
  gains INVITE_REVOKE_INTENT assertions for both the first-call leg and
  post-confirm persistence. ADDS a
  `countAuditByActorAndAction(actorId, action)` helper.
- `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java`,
  `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java`,
  `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java`
  — ADDS a `router.confirmStateService = new NoopConfirmStateService();`
  wiring line after the existing per-field wiring block in each test,
  plus a small `NoopConfirmStateService` private static inner class
  per test file (three inner classes, no new files — keeps `files_scope`
  the same three test files). The inner class returns
  `Optional.empty()` from `peek` / `takeAny` / `takeMatching` and is
  a no-op for `remember`. CRITICAL invariant: the Noop's `peek`
  method (and any other method the router calls inside step 4.5)
  MUST NOT log into `InboundRouterIntakeOrderingTest`'s `CallLog` —
  that test's scenarios `groupOnlyDmGateShortCircuitsBeforeDispatch`
  and `groupMentionAutoRegistersAndDispatchesNormally` pin precise
  per-step call-order assertions (lines 298-306 + 326-334) that
  would break if step 4.5's peek call appeared in the log. NO existing
  scenario method is renamed or deleted across these three files;
  the only changes are the Noop wiring line and the inner-class
  declaration.

These edits are authorized per CLAUDE.md §Engineering rules
§"Authorized test changes" — every existing M1-044c assertion is
preserved.

## Alternatives considered

- **Alt A:** wire the confirm gate inside each handler with no
  router-side step 4.5. Rejected: spec's "any other input cancels"
  applies UNIFORMLY across all inbounds, not just inbounds dispatched
  to the same handler. A non-confirm-gated handler (/help, /summary)
  arriving after a pending /ban must still trigger the cancellation
  acknowledgement. Without a router-side sweep, every handler — including
  future read-only ones — would have to remember to probe pending state.
- **Alt B:** store confirm state in a per-user database table with a
  TTL column and a cleanup-on-read trigger. Rejected: spec §Surface
  conventions explicitly forbids it ("Confirmation state is in-memory
  only. ... persisting confirmation tokens across restarts would
  require a cleanup sweep and a TTL gate identical to the in-memory
  timeout, with no gain in UX."). A Provider restart cancels every
  pending confirmation by design.
- **Alt C:** widen the `CommandHandler` SPI with a `requiresConfirm(rawText)`
  predicate the router queries before dispatch. Rejected: SPI changes
  ripple across every existing handler (HelpCommandHandler, AddSourceCommandHandler,
  SummaryCommandHandler, UnbanCommandHandler, and the M1-046
  Grant/RevokeAdminCommandHandler that's already drafted). The
  handler-side prompt-vs-execute split keeps the SPI untouched and
  contains the confirm-gate logic to the three handlers that need it.
- **Alt D:** make the confirm token a magic string in the body
  (e.g. `<command> --confirm`). Rejected: spec §Surface conventions
  is explicit — the confirmation message is `<command> confirm`
  (positional, no flag), and "any other input cancels." A `--confirm`
  flag would conflict with the spec's existing `<command> confirm`
  follow-up-message model.

## Pre-flight self-check (author-side)

Before committing, run `scripts/lint-ticket.py` against this file.
The linter encodes the recurring clarity-check failure patterns from
M1; catching them at author-time avoids paying ~4 minutes of clarity-subagent
time per defect.

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-051-confirm-state-service.md
```
