---
id: M1-041
title: Audit-log writer + RedactionHook + LlmOutputSanitizer audit row
status: deferred
created: 2026-05-19
last_updated: 2026-05-23
revisions:
  - date: 2026-05-23
    reason: pre-reopen scope widening — M1-044c red-team finding #1 (high AUDIT-EVASION) traces the audit-log redaction-hook bypass to three M1-044c handler files that landed AFTER M1-041 was drafted; a parallel grep of `infochat-(collector|provider|core)/src/main` for raw `INSERT INTO audit_log` surfaces three additional sites M1-041's original scope missed (the M1-044a InviteCodeConsumer audit-INSERT for the brute-force-breach + accepted-code rows; the M1-036 SourceUpsertService audit-INSERT for SOURCE_ADDED rows; the BootstrapLoader audit-INSERT for SOURCE_SEEDED rows). Without these six files in `files_scope`, M1-041's existing acceptance item 1 (SOLE-WRITER grep across all three main dirs) cannot be delivered, and the reviewer's negative-space check cannot confirm per-file migration.
    summary: |
      Frontmatter-only refine before `/m1-tick reopen M1-041`. The
      original 2026-05-19 draft anticipated AuditLogWriter
      consolidation against the call-site picture as of M1-008a +
      M1-033 (two sites: bootstrap-admin grant_admin row + Stage 2
      release-on-failure audit). Between then and 2026-05-22 the
      following raw-JDBC `INSERT INTO audit_log` sites landed:

        - infochat-provider/.../command/BanCommandHandler.java
          (M1-044c — BAN + INVITE_REVOKE audit rows on /ban
          transaction)
        - infochat-provider/.../command/UnbanCommandHandler.java
          (M1-044c — UNBAN audit row on non-preban /unban; the
          preban path's UNBAN_PREBAN_DELETE row is written by the
          V5 `delete_preban_user` SECURITY DEFINER procedure and
          stays carved out)
        - infochat-provider/.../command/InviteCommandHandler.java
          (M1-044c — INVITE_CREATE + INVITE_REVOKE audit rows on
          /invite create + /invite revoke)
        - infochat-provider/.../messaging/InviteCodeConsumer.java
          (M1-044a — INVITE_CONSUMED + INVITE_BRUTE_FORCE_BREACH
          audit rows on the intake-step invite consume path)
        - infochat-provider/.../source/SourceUpsertService.java
          (M1-036 — SOURCE_ADDED audit row on /add-source upsert)
        - infochat-collector/.../bootstrap/BootstrapLoader.java
          (collector bootstrap — SOURCE_SEEDED audit row on
          bootstrap-sources.json load)

      Verified by direct grep on 2026-05-23 (each file has ≥1
      `INSERT INTO audit_log` match; AddSourceCommandHandler.java —
      which HANDOFF.md anticipated as the M1-036 audit-INSERT site —
      has ZERO matches, confirming SourceUpsertService is the
      actual M1-036 audit-write site).

      Refine actions:
        1. Append the six file paths to `files_scope` (3 M1-044c
           handlers + 3 service-layer files).
        2. Bump `files_budget` 12 → 18 (six new files).
        3. Add six new per-file acceptance items (items 6-11)
           pinning each file's migration onto AuditLogWriter +
           grep verifying the raw `INSERT INTO audit_log` is gone
           AND the AuditLogWriter call site is present.
        4. Populate `verified_stays_green:` (mandatory — the new
           files_scope entries trigger the
           OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE lint heuristic
           because three are `*Command*.java` under
           provider/src/main/java/ AND InviteCodeConsumer is one
           of the named shared-dispatch-surface services).
        5. Update `test_plan.preserves` to enumerate the
           handler/service tests whose audit-row assertions must
           continue to pass with the new writer (BanCommandHandlerTest,
           UnbanCommandHandlerTest, InviteCommandHandlerTest,
           InviteCodeConsumerTest, SourceUpsertServiceTest,
           BootstrapLoaderTest).

      No body claims added beyond this revisions: entry. No
      acceptance ordering changes. No new behavioral commitments
      beyond what acceptance item 1's SOLE-WRITER grep already
      implies. Per CLAUDE.md `feedback_refine_must_pair_body_claim_with_acceptance_hook`:
      the only new body content is this historical revisions: entry,
      which is itself a verifiable record (grep on the entry's
      file-path list returns the same six paths in `files_scope`).

      Status stays `deferred` until `/m1-tick reopen M1-041` runs
      separately per the M1 workflow rule
      ("Never set status: in-progress manually" — the reopen
      transition uses its own subcommand procedure that appends
      `reopens:` and updates STATUS).
deferred_reason: post-mvp-audit-writer-consolidation
deferred_on: []
blocked_by: []
files_budget: 18
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/RedactionHook.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-core/src/main/resources/db/migration/V<N>__llm_output_sanitized_action.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage2/StartupReleaseOnStage2FailureWarn.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-core/src/test/java/app/zcat/infochat/core/audit/AuditLogWriterIT.java
  - infochat-core/src/test/java/app/zcat/infochat/core/audit/RedactionHookTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
verified_stays_green:
  - test_class: app.zcat.infochat.provider.messaging.HelpCommandHandlerTest
    rationale: "M1-049 plain JUnit handler-tier test; HelpCommandHandler does not write audit rows; the audit-writer migration is transparent to this test"
  - test_class: app.zcat.infochat.provider.command.AddSourceCommandHandlerTest
    rationale: "M1-049 plain JUnit handler-tier test; AddSourceCommandHandler does not write audit rows directly (SourceUpsertService — modified by this ticket — writes the SOURCE_ADDED row); the handler-tier mock for SourceUpsertService is unchanged so this test stays green"
  - test_class: app.zcat.infochat.provider.command.AddSourceBanCheckOrderingTest
    rationale: "M1-049 plain JUnit handler-tier test; calls handler.handle() with mocked collaborators including SourceUpsertService; audit-writer migration internal to SourceUpsertService is invisible to this test"
  - test_class: app.zcat.infochat.provider.command.SummaryCommandHandlerTest
    rationale: "M1-049 plain JUnit handler-tier test; SummaryCommandHandler does not write audit rows; transparent to the writer migration"
  - test_class: app.zcat.infochat.provider.messaging.AdapterRegistryTest
    rationale: "uses RecordingInboundRouter @Alternative that intercepts onMessage(); audit-writer migration is downstream of the router boundary and never reached"
  - test_class: app.zcat.infochat.provider.messaging.AutoRegisterServiceTest
    rationale: "exercises AutoRegisterService directly; AutoRegisterService does not write audit rows (the corresponding row is written by the V5 user-INSERT trigger, which carries the SECURITY DEFINER carve-out)"
  - test_class: app.zcat.infochat.provider.command.SummaryIT
    rationale: "drives full InboundRouter dispatch but sends /summary inbounds only; /summary writes no audit rows (the LLM_OUTPUT_SANITIZED rows added by this ticket are net new — pre-existing IT assertions are unaffected because they predate the new audit rows)"
  - test_class: app.zcat.infochat.provider.command.AddSourceIT
    rationale: "drives full InboundRouter dispatch with /add-source inbounds; the SOURCE_ADDED audit row's shape (action, target_kind, target_id, details_json) is preserved under the writer migration; IT assertions on the audit_log table continue to match"
  - test_class: app.zcat.infochat.provider.command.SummaryAdapterScopeIT
    rationale: "same as SummaryIT — /summary writes no audit rows; new LLM_OUTPUT_SANITIZED rows are net additions invisible to existing assertions"
  - test_class: app.zcat.infochat.provider.command.AddSourceAdapterScopeIT
    rationale: "same as AddSourceIT — SOURCE_ADDED audit row shape preserved end-to-end under writer migration"
  - test_class: app.zcat.infochat.provider.messaging.AdapterRouterIT
    rationale: "drives full InboundRouter dispatch with /help + /unknown-command inbounds; neither writes audit rows so the writer migration is invisible"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterTest
    rationale: "drives the router with /help, /xyz, /boom inbounds; no audit-row writes on these paths so the migration is invisible"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterIntakeOrderingTest
    rationale: "drives the router for M1-044b intake-step ordering; no audit-row writes on the /help path the test uses; writer migration invisible"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterNormalizeTest
    rationale: "exercises the normalize() static helper directly; no router or handler involvement; writer migration unreachable"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterContactIdRedactionTest
    rationale: "drives the router for contact-id redaction in NON-AUDIT logs; orthogonal to the audit-writer redaction-hook layer this ticket introduces"
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
remediates: M1-037 M1-033 M1-044c
out_of_scope:
  - any change to V5__identity_audit.sql, V6/V8 audit_log GRANTs, or the audit_log column shape itself — this ticket only adds a new audit verb (LLM_OUTPUT_SANITIZED) to the action CHECK constraint and consolidates the application-layer writer
  - any change to audit_log triggers (append-only triggers from M1-008a and the actor-integrity trigger from M1-021 if it has landed by start time)
  - any change to bootstrap admin / Stage 2 release-on-failure logic beyond migrating the audit INSERT call site onto the new writer
  - any feature additions to /audit (the admin review command for sanitizer events lives in a separate T2-G follow-up)
  - any operator-side log retention / log shipping work
  - any change to /summary's prose generation, cluster traversal, or degraded fallback path (M1-040 territory)
acceptance:
  - "AuditLogWriter.java is the SOLE INSERT path into audit_log for application-layer writers. grep -rn 'INSERT\\s+INTO\\s+audit_log' infochat-collector/src/main infochat-provider/src/main infochat-core/src/main returns matches only inside AuditLogWriter.java and SECURITY DEFINER stored procedures (delete_preban_user, approve_quarantine etc. — those carry the carve-out per V5/V6/V10)"
  - "RedactionHook is an SPI interface in infochat-core/audit with a single `redact(AuditRow): AuditRow` entry. The default implementation applies the closed redaction catalogue from docs/spec/security.md §Secrets handling to the row's `details_json` and `target_contact_id` fields; an alternative implementation can be wired via CDI alternative for testing"
  - "A new Flyway migration at the next free V<N> integer adds LLM_OUTPUT_SANITIZED to the audit_log.action CHECK constraint (and any other new verbs this consolidation requires). grep -E 'LLM_OUTPUT_SANITIZED' V<N>__llm_output_sanitized_action.sql returns at least one match"
  - "LlmOutputSanitizer emits one audit_log row per sanitizer hit (NOT throttled) via AuditLogWriter, per docs/spec/security.md §LLM output sanitizer 'Every match is audit-logged (per-occurrence, not throttled)'. The action is LLM_OUTPUT_SANITIZED; details_json carries the match-count + match-kind enumeration without the user-visible LLM output text"
  - "StartupReleaseOnStage2FailureWarn migrates its raw-JDBC INSERT onto AuditLogWriter — the writer call site replaces the inline INSERT. M1-033's audit-row tests continue to pass with the new writer (semantics preserved)"
  - "BanCommandHandler migrates its raw `INSERT INTO audit_log` onto AuditLogWriter — both the BAN audit-row write AND the INVITE_REVOKE companion row written inside the same transaction flow through the writer with the existing `request_id` correlation preserved. BanCommandHandlerTest's per-scenario audit-row assertions (BAN row exists; BAN + INVITE_REVOKE rows share `request_id`; rollback discards the row on last-admin trigger) continue to pass with the writer (semantics preserved modulo the new redaction-hook application on `details_json`). Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java` returns ≥1"
  - "UnbanCommandHandler migrates its raw `INSERT INTO audit_log` (the non-preban UNBAN row written audit-before-effect inside the transaction) onto AuditLogWriter. The V5 `delete_preban_user` SECURITY DEFINER procedure path is unchanged — the procedure writes the UNBAN_PREBAN_DELETE row internally per V5/V6/V10 carve-out and does NOT flow through AuditLogWriter. UnbanCommandHandlerTest's audit-row assertions (UNBAN row exists on non-preban path; UNBAN row carries `restored_group_admin` list when applicable; UNBAN_PREBAN_DELETE row is unaffected by this ticket) continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java` returns ≥1; `grep -cE 'CALL\\s+delete_preban_user' infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java` returns ≥1 (the unchanged stored-procedure carve-out)"
  - "InviteCommandHandler migrates its raw `INSERT INTO audit_log` onto AuditLogWriter for all three subcommand paths (create, revoke; list is read-only and writes nothing). Both INVITE_CREATE rows (--contact and --open variants) and the INVITE_REVOKE row flow through the writer. InviteCommandHandlerTest's audit-row assertions (INVITE_CREATE row carries `details_json.invite_type` matching the parsed `--contact`/`--open` shape; INVITE_REVOKE row exists on the success path only; no audit row on the already-REVOKED probe path per M1-044c) continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java` returns ≥1"
  - "InviteCodeConsumer migrates its raw `INSERT INTO audit_log` onto AuditLogWriter for both audit-write paths: the INVITE_CONSUMED row written on the Accepted outcome, and the INVITE_BRUTE_FORCE_BREACH row written on the BruteForceThresholdBreached outcome (M1-044a). InviteCodeConsumerTest's audit-row assertions continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java` returns ≥1"
  - "SourceUpsertService migrates its raw `INSERT INTO audit_log` (the SOURCE_ADDED row written on /add-source upsert per M1-036) onto AuditLogWriter. SourceUpsertServiceTest's audit-row assertions continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java` returns ≥1"
  - "BootstrapLoader migrates its raw `INSERT INTO audit_log` (the SOURCE_SEEDED row written when bootstrap-sources.json upserts a source into the seed catalogue) onto AuditLogWriter. BootstrapLoaderTest's audit-row assertions continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java` returns 0; `grep -cE 'auditLogWriter' infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java` returns ≥1"
  - "AuditLogWriterIT covers: (a) happy-path INSERT round-trips through the redaction hook; (b) redaction hook is applied (a row whose details_json contains an API-key shape emerges with the key redacted); (c) the writer is transaction-safe (calling INSIDE a @Transactional method commits in the same tx as the surrounding work)"
  - "LlmOutputSanitizerAuditRowIT covers: a /summary call whose LLM output triggers two sanitizer hits writes EXACTLY two audit_log rows with action=LLM_OUTPUT_SANITIZED (the per-occurrence promise — NOT one coalesced row per call)"
  - "mvn -B clean verify from the repo root exits 0; all existing audit-log-touching tests continue to pass"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/audit/AuditLogWriterIT.java
    - infochat-core/src/test/java/app/zcat/infochat/core/audit/RedactionHookTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
  preserves:
    - all tests currently green on main
    - BanCommandHandlerTest (audit-row assertions hold under writer migration)
    - UnbanCommandHandlerTest (audit-row assertions hold under writer migration; V5 procedure carve-out unchanged)
    - InviteCommandHandlerTest (audit-row assertions hold under writer migration across create + revoke paths)
    - InviteCodeConsumerTest (audit-row assertions hold under writer migration for INVITE_CONSUMED + INVITE_BRUTE_FORCE_BREACH paths)
    - SourceUpsertServiceTest (audit-row assertion for SOURCE_ADDED holds under writer migration)
    - BootstrapLoaderTest (audit-row assertion for SOURCE_SEEDED holds under writer migration)
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Secrets handling (audit-log redaction hook)
  - docs/spec/security.md §Audit
decision_refs: []
---

# M1-041: Audit-log writer + RedactionHook + LlmOutputSanitizer audit row

## Context

Two related red-team findings cluster on the same architectural
gap — the spec promises a redaction-hook layer for audit-log writes
but no `AuditLogWriter` middleware exists in the repo. Every
`INSERT INTO audit_log` in M1 to date is raw JDBC:

1. **M1-037 finding 2 (medium AUDIT-EVASION)** — The
   `LlmOutputSanitizer` logs sanitizer hits at WARN via JBoss
   logging but writes no `audit_log` row. The spec's §LLM output
   sanitizer commits "Every match is audit-logged (per-occurrence,
   not throttled)." An admin running `/audit` (the spec-promised
   review surface for sanitizer events) sees nothing.

2. **M1-033 OUT-OF-MODEL #3** — `StartupReleaseOnStage2FailureWarn`
   uses raw JDBC for its audit INSERT, bypassing the spec's
   redaction-hook layer. The new row has no user-content fields
   (system-actor + operator-config profile name), so the bypass
   is harmless TODAY — but it's the project-wide pattern, and
   audit-log writes that DO carry user content will start landing
   as soon as T2-A wires `/grant-admin`, `/ban`, `/unban` etc.

## Why this is deferred

**Today's audit-log call sites are 2 — both system-actor, neither
carries user content.** The redaction-hook layer's job is to
catch user-derived data (contact ids, message bodies, post titles)
before it reaches the durable audit row. With only system-actor
rows in play, the hook has nothing to actually redact.

The writer's job is to give every call site one place to flow
through — but with only 2 call sites, the abstraction earns less
than its weight in maintenance overhead. The right time to build
both is when the surface stabilizes:

- **T2-A** lands `/ban`, `/unban`, `/grant-admin`, `/revoke-admin`,
  `/promote`, `/demote`, `/vouch`, invite-consume — every one
  writes an audit row with user-derived fields (`target_contact_id`,
  `details_json` carrying actor display names).
- **T2-B** lands `/save`, `/forget` adjacent paths that write
  audit rows carrying saved-post titles, source URLs.
- **T2-E** lands `/forget` and `/export` — privacy commands whose
  audit rows MUST exclude the very content they're operating on.

After T2-A/B/E are merged, all ~10 audit-write call sites are
known. The redaction hook can be designed against the full
call-site picture; the writer's API is informed by every actual
consumer; the migration of existing call sites + the new
LlmOutputSanitizer hit happens in one coordinated ticket.

## What we accept as residual risk meanwhile

- **`LlmOutputSanitizer` hits are not in audit_log.** They are in
  stdout/journald at WARN with a canonical `error_class` string.
  An operator who needs the signal today can grep the structured
  log. `/audit` (which doesn't exist yet either) will show nothing
  until this ticket lands; this is consistent with the spec's
  `LLM_OUTPUT_SANITIZED` verb being absent from the V5 CHECK
  constraint at M1's current state.

- **Stage 2 release-on-failure audit row bypasses the (nonexistent)
  redaction hook.** Harmless today because the row carries
  no user content. The migration to the writer is mechanical
  when the writer lands.

## Definition of Done

(Authored speculatively — refine before unblocking via
`/m1-tick reopen M1-041`)

- `AuditLogWriter` in `infochat-core/audit/` is the sole
  application-layer INSERT path into `audit_log`. SECURITY
  DEFINER stored procedures (`delete_preban_user`,
  `approve_quarantine`, …) carve out with their own internal
  INSERTs per V5/V6/V10.
- `RedactionHook` is a single-method SPI in `infochat-core/audit/`;
  the default impl applies the spec's closed redaction catalogue
  to `details_json` and `target_contact_id` before INSERT.
- A new Flyway migration adds `LLM_OUTPUT_SANITIZED` to the
  `audit_log.action` CHECK constraint.
- `LlmOutputSanitizer` emits one audit row per sanitizer hit
  via the writer (NOT throttled, NOT coalesced).
- `StartupReleaseOnStage2FailureWarn` migrates its raw INSERT
  onto the writer.
- Tests pin the redaction-hook semantics, the per-occurrence
  audit row count for sanitizer hits, and the transactional
  semantics of the writer.

## Implementation notes

- **Spec the writer from the call-site picture, not from first
  principles.** Before implementing, audit every existing
  `INSERT INTO audit_log` call site (raw JDBC) and every T2-A/B/E
  call site that will land. The writer's API should be the
  minimal shape that ALL of them can call without per-call-site
  ceremony.
- **Redaction hook semantics.** The default impl applies the
  closed redaction catalogue from §Secrets handling — contact ids
  → prefix+ellipsis+suffix; API-key shapes → `[REDACTED]`. The
  hook receives the constructed audit-row record and returns a
  redacted variant; the writer never sees the unredacted form
  past the hook.
- **Per-occurrence sanitizer audit.** §LLM output sanitizer is
  unambiguous: "Every match is audit-logged (per-occurrence, not
  throttled)." Two hits in one /summary reply → two rows. This
  contrasts with `details_json`-coalesced shapes used elsewhere.
- **Don't touch the SECURITY DEFINER procedures.**
  `delete_preban_user` (V5 + V6 from M1-021 when it lands) and
  `approve_quarantine` (V10 from M1-032) carve out an
  internal-INSERT path. Those continue to write directly; the
  writer governs everything else.

## Big-picture notes

- **Order in the dependency graph.** Run this AFTER T2-A/B/E
  are merged but BEFORE the M1 release tag. The spec promise
  ("every match is audit-logged per-occurrence") is currently
  silently downgraded; this ticket closes the gap before tag.
- **`/audit` admin command depends on this.** When `/audit`
  lands (likely T2-G with the rest of the admin-review surface),
  it queries `audit_log` for sanitizer events. Those rows MUST
  exist by then.
- **The redaction-hook layer is the seam for future operator-
  side requirements.** Some operators may want stricter
  redaction (e.g. dropping all `target_contact_id` values to
  meet an external compliance requirement). The hook is the
  hook for that policy.

## Out-of-scope expansion

- See `out_of_scope` block — the deferred state means this
  ticket has not been authored against a specific build of the
  T2-A/B/E call sites; the body above is a placeholder for the
  consolidation work, not a frozen specification.

## Authorized test changes

- (TBD — refine before unblocking; the migration of existing
  M1-008a / M1-033 audit-row tests onto the new writer may
  require listed authorized changes here.)

## Alternatives considered

- **Build the writer now (post-Tier-1).** Rejected — see "Why
  this is deferred". Only 2 call sites today; designing the
  API against 2 vs. 10 produces a different (less informed)
  shape.
- **Skip the writer; just add the sanitizer audit row inline.**
  Rejected — the M1-033 OOM #3 finding is the same root cause.
  Splitting them produces two tickets that solve the same
  architectural gap one row at a time.
- **Build the redaction hook as a JDBC `Connection` wrapper
  rather than an application-layer SPI.** Rejected — the
  redaction must be visible in code review (a hidden wrapper
  is invisible to reviewers and to the spec's promise that
  redaction is auditable).
