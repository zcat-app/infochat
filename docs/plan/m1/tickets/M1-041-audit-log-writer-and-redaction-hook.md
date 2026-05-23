---
id: M1-041
title: Audit-log writer + RedactionHook + LlmOutputSanitizer audit row
status: done
created: 2026-05-19
last_updated: 2026-05-23
clarity_check:
  date: 2026-05-23
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-041.md
escalations:
  - date: 2026-05-23
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      N/A — clarity-pre-flight FAIL (see clarity_check.blockers)
  - date: 2026-05-23
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      N/A — clarity-pre-flight FAIL (see clarity_check.blockers); second clarity-fail on the post-reopen + post-refine M1-041 spec. Blocker: TEST-CHANGES-AUTHORIZED — §Authorized test changes remains a TBD placeholder while test_plan.preserves references six pre-existing tests + M1-008a/M1-033 audit-row tests.
reopens:
  - date: 2026-05-23
    prior_deferred_reason: post-mvp-audit-writer-consolidation
    prior_deferred_on: []
    reason: M1-044c red-team finding #1 (audit-redaction-hook bypass) is v1-blocker
revisions:
  - date: 2026-05-23
    reason: clarity-fail rework — drop the `docs/spec/security.md §Audit` spec_refs entry; verified the heading does not exist in security.md (no `## Audit` or `### Audit` at any level; nearest substring matches are §Authorization model, §Per-adapter admin threat profile, §User ban, §DB roles where `audit_log_view` access lives). The two remaining refs (§LLM output sanitizer + §Secrets handling) already cover the substantive spec content every acceptance item depends on; no acceptance item references content unique to the audit_log_view / DB-roles section, so dropping rather than retargeting is the minimum-scope fix. WARN-level clarity items (SELF-CONTAINED-CHECK on acceptance item 2's catalogue delegation; TEST-CHANGES-AUTHORIZED TBD placeholder) are intentionally NOT touched in this refine — both are non-blocking and surfacing the warnings on the next clarity pass keeps the audit trail accurate.
    summary: |
      Single-line change: removed `- docs/spec/security.md §Audit`
      from the spec_refs block. No other frontmatter or body changes.
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
          (M1-044a — INVITE_CONSUME + INVITE_BRUTE_FORCE_BREACH
          audit rows on the intake-step invite consume path)
        - infochat-provider/.../source/SourceUpsertService.java
          (M1-036 — ADD_SOURCE audit row on /add-source upsert)
        - infochat-collector/.../bootstrap/BootstrapLoader.java
          (collector bootstrap — BOOTSTRAP_SOURCE_LOAD audit row
          on bootstrap-sources.json load)

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
  - date: 2026-05-23
    reason: clarity-fail rework (second pass) — resolve the
      TEST-CHANGES-AUTHORIZED blocker by reading every existing
      audit-row-asserting test under files_scope and the M1-008a
      append-only test, then writing the per-class authorized-
      changes rationale into the body. The reads also surfaced a
      spec contradiction in acceptance item 2 (and the body's
      "Redaction hook semantics" paragraph) plus three audit-verb
      naming mismatches that the prior outline pass had identified
      but the prior refine left untouched. All four issues are
      load-bearing for the §Authorized test changes rationale to
      stay internally consistent, so they're fixed in this same
      pass rather than deferred to the next plan-writer round.
    summary: |
      Six coupled edits, scoped to make the §Authorized test
      changes block runnable without the rest of the ticket
      contradicting it:

        1. Acceptance item 2 + body §Implementation notes
           "Redaction hook semantics" paragraph: the spec
           (`docs/spec/security.md` §Secrets handling) commits to
           "Audit-log writes pass through a redaction hook that
           masks values matching a closed catalogue of API-key
           shapes" — and "Contact IDs are logged in redacted form
           (prefix + ellipsis + suffix) outside the audit log."
           Read together with the §DB roles passage on
           `audit_log_view` exposing "the same columns as
           `audit_log` minus any redacted fields (raw secrets,
           full contact ids)", the architecture is redact-on-write
           for API-key shapes and redact-on-read (via the view)
           for contact-id columns. The old wording — "the row's
           `details_json` and `target_contact_id` fields" — was
           wrong on both counts (the hook does not touch
           `target_contact_id`; contact-id redaction is the
           view's job, not the writer's hook). The corrected
           item 2 inlines the seven shape families from the spec
           catalogue so the SELF-CONTAINED warning on the prior
           clarity pass is also resolved without delegating to a
           spec re-read.

        2. Acceptance item 9 + revisions: prose: audit verb
           constant in code is `INVITE_CONSUME` (verified at
           `InviteCodeConsumer.INVITE_CONSUME` referenced from
           `InviteCodeConsumerTest.java` line 104). Old ticket
           text used `INVITE_CONSUMED`.

        3. Acceptance item 10 + revisions: prose: audit verb
           constant in code is `ADD_SOURCE` (verified at
           `SourceUpsertServiceIT.java` line 213-214, where
           `readSingleAuditAction` returns the literal
           `"ADD_SOURCE"`). Old ticket text used `SOURCE_ADDED`.

        4. Acceptance item 11 + revisions: prose: audit verb
           literal in code is `BOOTSTRAP_SOURCE_LOAD` (verified
           at `BootstrapLoaderIT.java` line 73, the assertion
           `WHERE action = 'BOOTSTRAP_SOURCE_LOAD'`). Old ticket
           text used `SOURCE_SEEDED`.

        5. `test_plan.preserves`: corrected two file names
           (`SourceUpsertServiceTest` → `SourceUpsertServiceIT`,
           `BootstrapLoaderTest` → `BootstrapLoaderIT` —
           verified via `find` against `infochat-provider/src/test`
           and `infochat-collector/src/test`) and the parenthetical
           verb names per items 2-4 above. Also added
           `AuditLogAppendOnlyTest` (the M1-008a audit-log-touching
           test the prior block referenced indirectly via the
           "M1-008a audit-row tests" prose).

        6. Body §Authorized test changes: TBD placeholder
           replaced with a per-class "no modification required"
           rationale, each backed by what the test actually
           asserts on (verified by reading each file). The
           rationale rests on edit 1 — the redaction hook only
           masks API-key shapes, and none of the seven existing
           tests seeds an API-key-shaped string anywhere that
           reaches the audit row.

      Out of scope of this refine (intentionally left for the
      next plan-writer pass to surface or accept as-is):
        - Acceptance item 5's "M1-033's audit-row tests continue
          to pass" claim is vacuous in practice
          (`StartupReleaseOnStage2FailureWarn` has no direct
          test in any current M1 ticket — verified by
          `grep -rlE 'StartupReleaseOnStage2'` across all test
          modules returning zero matches). The claim is harmless
          (a vacuously-true preserve is not a regression risk)
          and is independent of the TEST-CHANGES-AUTHORIZED
          blocker; orthogonal to this refine.
  - date: 2026-05-23
    reason: plan-writer (outline PASS) flagged two pre-implementation
      refines on the otherwise-runnable ticket. Both are pure
      ticket-text corrections — no behavior change, no
      acceptance-coverage change. Falsifier checks were run and
      quoted before edits landed.
    summary: |
      Two coupled edits:

        1. files_scope path correction: the
           StartupReleaseOnStage2FailureWarn.java entry used the
           wrong package prefix `io/infochat/collector/...`. Verified
           via `find infochat-collector/src/main/java -name
           'StartupReleaseOnStage2*'` returning the single actual
           path under `app/zcat/infochat/collector/eval/stage2/`.
           No `io/infochat/...` directory exists. Corrected the
           prefix in `files_scope`.

        2. Acceptance item 3 + body `out_of_scope` wording: the
           item read "adds LLM_OUTPUT_SANITIZED to the
           audit_log.action CHECK constraint". V5 explicitly
           commits NOT to pin verbs with a SQL CHECK on
           `audit_log.action` (lines 28-29 `-- catalogue honest.
           The set is NOT pinned with a SQL CHECK on` and lines
           272-273 `-- below document the v1 set without pinning
           it via a SQL CHECK — the`). V12 added
           INVITE_BRUTE_FORCE_BREACH as a pure line-comment
           addition (lines 12 + 68), no CHECK clause introduced.
           Verified by `grep -nE "INVITE_BRUTE_FORCE_BREACH|CHECK"
           infochat-core/src/main/resources/db/migration/V12__invite_code_attempt.sql`
           returning two matches, both comments. Rewrote the
           acceptance item to specify the V12 line-comment
           pattern and added a parenthetical that cites the V5
           anchor for "not pinned with a SQL CHECK". Rewrote the
           matching `out_of_scope` parenthetical for consistency.
           The grep predicate (`grep -E 'LLM_OUTPUT_SANITIZED'`)
           is unchanged and satisfied by either pattern.
  - date: 2026-05-23
    reason: implementation-start refine — infochat-core/pom.xml
      addition surfaced after the plan-writer pass during the
      first implementation read. Acceptance item 2 requires
      RedactionHook to be wireable via CDI @Alternative; that
      forces @ApplicationScoped on AuditLogWriter and
      DefaultRedactionHook, which requires jakarta.enterprise
      .cdi-api on the infochat-core compile classpath.
      infochat-core today declares zero production dependencies
      and zero CDI annotations — verified via
      `grep '<artifactId>' infochat-core/pom.xml | head` (shows
      only test-scope deps) and
      `grep -rln 'ApplicationScoped|jakarta.enterprise|jakarta
      .inject' infochat-core/src/main/java` (returns empty).
      The sibling library `infochat-llm-adapter/pom.xml` is the
      established CDI-aware library pattern: adds cdi-api,
      inject-api, annotation-api all with `<scope>provided</scope>`
      so the library compiles against the annotations without
      pulling in a CDI runtime; downstream Quarkus apps (provider,
      collector) supply the runtime via quarkus-arc.
    summary: |
      Two coupled edits:

        1. files_scope: prepend `infochat-core/pom.xml`. The pom
           addition adds three provided-scope deps mirroring the
           infochat-llm-adapter pattern: jakarta.enterprise.cdi-api,
           jakarta.inject-api, jakarta.annotation-api. No production
           deps and no runtime extensions are added — provided scope
           only.

        2. files_budget: 18 → 19 (one new path).

      Why not the lower-scope alternatives:
        - Plain-Java instantiation at call sites (no CDI in
          infochat-core) — reinterprets acceptance item 2's "via
          CDI @Alternative" as constructor injection; tests pass
          alternative RedactionHook into the writer constructor.
          Functionally equivalent for testability but a literal
          deviation from the acceptance text.
        - Producer classes in infochat-provider + infochat-collector
          — keeps infochat-core untouched but requires two new
          @Produces classes (one in each module), neither in
          files_scope. Two new files vs. one pom.xml change; the
          pom addition is the smaller delta and follows the
          existing infochat-llm-adapter pattern.

      No body claims added beyond this revisions entry. No
      acceptance item text changes — the acceptance shape is
      preserved; only the implementation path now has the deps
      it needs.
  - date: 2026-05-23
    reason: implementation-start refine (round 1 mvn verify
      gap) — adding @ApplicationScoped to AuditLogWriter +
      DefaultRedactionHook in infochat-core is necessary to
      compile (the prior refine 5677ecc) but NOT sufficient
      to make ArC discover them at runtime in provider /
      collector. Quarkus ArC does not scan dependency JARs
      for CDI beans unless the JAR carries a META-INF/beans.xml
      marker or a Jandex index, or the consumer declares an
      explicit `quarkus.index-dependency.*` entry. infochat-core
      has none of those today. Without the directive, every
      provider/collector consumer of the new SPI (the 7 call
      sites + LlmOutputSanitizer per-occurrence emitter) fails
      with UnsatisfiedResolutionException at boot. Falsifier
      check (verbatim):

          $ grep -n 'index-dependency' \
              infochat-collector/src/main/resources/application.properties \
              infochat-provider/src/main/resources/application.properties
          infochat-collector/src/main/resources/application.properties:38:quarkus.index-dependency.llm-adapter.group-id=app.zcat.infochat
          infochat-collector/src/main/resources/application.properties:39:quarkus.index-dependency.llm-adapter.artifact-id=infochat-llm-adapter
          infochat-provider/src/main/resources/application.properties:140:quarkus.index-dependency.llm-adapter.group-id=app.zcat.infochat
          infochat-provider/src/main/resources/application.properties:141:quarkus.index-dependency.llm-adapter.artifact-id=infochat-llm-adapter

      The infochat-llm-adapter consumer-side precedent (M1-033)
      is the established pattern for this exact problem: a
      library JAR with @ApplicationScoped beans + no beans.xml /
      no Jandex index, surfaced via `quarkus.index-dependency.*`
      in each consumer's application.properties.
    summary: |
      Two coupled edits:

        1. files_scope: prepend the two consumer application
           .properties paths. Each gets one
           `quarkus.index-dependency.audit-core.{group-id,
           artifact-id}` pair mirroring the existing
           llm-adapter block in the same file.

        2. files_budget: 19 → 21 (two new paths).

      Why not the lower-scope alternatives:
        - jandex-maven-plugin on infochat-core/pom.xml — would
          add a new build plugin (one new dependency entry +
          build phase) to make the JAR carry META-INF/jandex.idx
          out of the box. Rejected (operator instruction
          2026-05-23): introduces a new build dependency where
          an existing repo pattern (consumer-side
          `quarkus.index-dependency.*`) already solves the same
          problem with smaller surface area. Consistency across
          the codebase outweighs the per-decision cleanliness
          of "fix the library, not the consumer."
        - empty META-INF/beans.xml in infochat-core — would
          add a new file outside files_scope and has no
          existing precedent in this repo.

      No body claims added beyond this revisions entry. No
      acceptance item text changes — the acceptance shape is
      preserved.
reviews:
  - round: 1
    date: 2026-05-23
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 21
      added: 1524
      removed: 151
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
      files: 21
      added: 1561
      removed: 151
deferred_reason:
deferred_on: []
blocked_by: []
files_budget: 21
files_scope:
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/application.properties
  - infochat-core/pom.xml
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/RedactionHook.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-core/src/main/resources/db/migration/V<N>__llm_output_sanitized_action.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/StartupReleaseOnStage2FailureWarn.java
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
    rationale: "M1-049 plain JUnit handler-tier test; AddSourceCommandHandler does not write audit rows directly (SourceUpsertService — modified by this ticket — writes the ADD_SOURCE row); the handler-tier mock for SourceUpsertService is unchanged so this test stays green"
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
    rationale: "drives full InboundRouter dispatch with /add-source inbounds; the ADD_SOURCE audit row's shape (action, target_kind, target_id, details_json) is preserved under the writer migration; IT assertions on the audit_log table continue to match"
  - test_class: app.zcat.infochat.provider.command.SummaryAdapterScopeIT
    rationale: "same as SummaryIT — /summary writes no audit rows; new LLM_OUTPUT_SANITIZED rows are net additions invisible to existing assertions"
  - test_class: app.zcat.infochat.provider.command.AddSourceAdapterScopeIT
    rationale: "same as AddSourceIT — ADD_SOURCE audit row shape preserved end-to-end under writer migration"
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
redteam_findings:
  - date: 2026-05-23
    category: AUDIT-EVASION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer — "Every match is
      audit-logged (per-occurrence, not throttled)." Reinforced by
      §Secrets handling which frames "audit-logged" as a write to
      the durable audit_log table.
    gap: |
      infochat-provider/src/main/java/app/zcat/infochat/provider/llm/
      LlmOutputSanitizer.java:215-239 — emitAuditRows opens a per-match
      autoCommit connection, catches SQLException, logs errorf, and
      continues. The closed-list-rewritten user-visible text is
      returned at line 136 regardless of whether the audit row reached
      the database. No retry, no queue, no surrounding transaction.
    repro: |
      Adversary triggers any LLM output emitting a closed-list token
      while the audit_log INSERT path is degraded (pool exhaustion,
      lock contention, network blip). auditLogWriter.write throws
      SQLException; the catch logs and returns. The user receives
      the sanitized reply; no durable audit_log row exists. /audit
      shows zero LLM_OUTPUT_SANITIZED rows for the incident.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-05-23
    run: 1
    verdict: FINDINGS
    base: main
    head: m1/M1-041-audit-log-writer-and-redaction-hook (commit 85c88c9)
    verdict_file: docs/plan/m1/redteam/M1-041-2026-05-23.md
    findings_count: 1
    out_of_model_count: 0
    note: |
      Single medium AUDIT-EVASION finding on the LlmOutputSanitizer
      fail-soft branch. Resolved in-ticket per operator override of
      the no-amend rule (pre-merge, post-/m1-tick commit; the user
      rejected the new-remediation-ticket path). emitAuditRows now
      wraps per-match writes in a single transaction and throws
      IllegalStateException on SQLException; sanitize() bubbles the
      exception so the caller's response build aborts when audit
      cannot be written. New IT auditWriteFailureAbortsSanitizeFailLoud
      pins the fail-loud behavior. See follow-up redteam_audits entry
      for the CLEAN confirmation.
  - date: 2026-05-23
    run: 2
    verdict: CLEAN
    base: main
    head: m1/M1-041-audit-log-writer-and-redaction-hook (commit e7c0c9b — fail-loud fixup on top of 85c88c9)
    verdict_file: docs/plan/m1/redteam/M1-041-2026-05-23.md
    findings_count: 0
    out_of_model_count: 1
    note: |
      CLEAN. The audit-1 medium finding is closed end-to-end:
      emitAuditRows wraps writes in a tx + throws on failure,
      sanitize() bubbles the exception, SummaryCommandHandler does
      not catch, InboundRouter's runtime-exception handler returns
      INTERNAL_ERROR_REPLY (the sanitized text is NOT sent). One
      OUT-OF-MODEL observation: DefaultRedactionHook's watchdog
      placeholder "[REDACTED]" is not valid JSONB, so a watchdog-firing
      input would cause the surrounding admin action to roll back.
      Resolved in audit-3 by operator override (see follow-up entry).
  - date: 2026-05-23
    run: 3
    verdict: CLEAN
    base: main
    head: m1/M1-041-audit-log-writer-and-redaction-hook (commit 72e9297 — JSONB-fallback fixup on top of e7c0c9b)
    verdict_file: docs/plan/m1/redteam/M1-041-2026-05-23.md
    findings_count: 0
    out_of_model_count: 0
    note: |
      CLEAN. The audit-2 OUT-OF-MODEL observation is resolved.
      New REDACTED_FIELD_JSONB constant = '{"_redacted":true,
      "reason":"regex_watchdog_timeout"}' replaces the bare
      "[REDACTED]" string in the DefaultRedactionHook watchdog
      fail-closed branch; the valid-JSONB shape now passes the
      ?::jsonb cast in AuditLogWriter so the surrounding dispatch
      transaction does NOT roll back. The per-match
      REDACTED_PLACEHOLDER stays as-is (valid inside JSON string
      quotes). New AuditLogWriterIT.watchdogFallbackIsValidJsonb
      pins the end-to-end Postgres round-trip. M1-041 is ready
      for /m1-tick merge.
out_of_scope:
  - any change to V5__identity_audit.sql, V6/V8 audit_log GRANTs, or the audit_log column shape itself — this ticket only adds a new audit verb (LLM_OUTPUT_SANITIZED) to the V5 §2.1.8 line-comment verb catalogue (matching the V12 line-comment pattern) and consolidates the application-layer writer
  - any change to audit_log triggers (append-only triggers from M1-008a and the actor-integrity trigger from M1-021 if it has landed by start time)
  - any change to bootstrap admin / Stage 2 release-on-failure logic beyond migrating the audit INSERT call site onto the new writer
  - any feature additions to /audit (the admin review command for sanitizer events lives in a separate T2-G follow-up)
  - any operator-side log retention / log shipping work
  - any change to /summary's prose generation, cluster traversal, or degraded fallback path (M1-040 territory)
acceptance:
  - "AuditLogWriter.java is the SOLE INSERT path into audit_log for application-layer writers. grep -rn 'INSERT\\s+INTO\\s+audit_log' infochat-collector/src/main infochat-provider/src/main infochat-core/src/main returns matches only inside AuditLogWriter.java and SECURITY DEFINER stored procedures (delete_preban_user, approve_quarantine etc. — those carry the carve-out per V5/V6/V10)"
  - "RedactionHook is an SPI interface in infochat-core/audit with a single `redact(AuditRow): AuditRow` entry. The default implementation applies the closed API-key-shape catalogue from docs/spec/security.md §Secrets handling to the row's `details_json` field. The seven shape families from the spec baseline are OpenAI `sk-…` (incl. `sk-proj-…` / `sk-svcacct-…`), Anthropic `sk-ant-…`, GitHub `ghp_/gho_/ghu_/ghs_/ghr_…`, AWS `AKIA[0-9A-Z]{16}` and `ASIA[0-9A-Z]{16}`, Google `AIza[0-9A-Za-z_-]{35}`, Slack `xox[abprs]-…`, and generic 32+-char hex/base64 strings adjacent to the case-insensitive substrings `api[_-]?key|secret|token|password|bearer`. Each match is replaced with `[REDACTED]`; the regex matcher is fail-closed on timeout (per the spec, a timed-out match treats the whole field as redacted rather than emitting it raw). The hook does NOT redact `target_contact_id` — per spec, contact-id redaction is for non-audit logs only (`ContactIds.redact` prefix+ellipsis+suffix shape), while `audit_log` itself stores the full contact id and the `audit_log_view` exposes the redacted form at read time. An alternative implementation can be wired via CDI `@Alternative` for testing."
  - "A new Flyway migration at the next free V<N> integer adds LLM_OUTPUT_SANITIZED to the V5 §2.1.8 line-comment audit-verb catalogue, following the V12 line-comment pattern used for INVITE_BRUTE_FORCE_BREACH (V5 explicitly does NOT pin verbs with a SQL CHECK on audit_log.action — see V5 lines 28-29 and 272-273; the verb set is documented via per-verb line comments). grep -E 'LLM_OUTPUT_SANITIZED' V<N>__llm_output_sanitized_action.sql returns at least one match"
  - "LlmOutputSanitizer emits one audit_log row per sanitizer hit (NOT throttled) via AuditLogWriter, per docs/spec/security.md §LLM output sanitizer 'Every match is audit-logged (per-occurrence, not throttled)'. The action is LLM_OUTPUT_SANITIZED; details_json carries the match-count + match-kind enumeration without the user-visible LLM output text"
  - "StartupReleaseOnStage2FailureWarn migrates its raw-JDBC INSERT onto AuditLogWriter — the writer call site replaces the inline INSERT. M1-033's audit-row tests continue to pass with the new writer (semantics preserved)"
  - "BanCommandHandler migrates its raw `INSERT INTO audit_log` onto AuditLogWriter — both the BAN audit-row write AND the INVITE_REVOKE companion row written inside the same transaction flow through the writer with the existing `request_id` correlation preserved. BanCommandHandlerTest's per-scenario audit-row assertions (BAN row exists; BAN + INVITE_REVOKE rows share `request_id`; rollback discards the row on last-admin trigger) continue to pass with the writer (semantics preserved modulo the new redaction-hook application on `details_json`). Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java` returns ≥1"
  - "UnbanCommandHandler migrates its raw `INSERT INTO audit_log` (the non-preban UNBAN row written audit-before-effect inside the transaction) onto AuditLogWriter. The V5 `delete_preban_user` SECURITY DEFINER procedure path is unchanged — the procedure writes the UNBAN_PREBAN_DELETE row internally per V5/V6/V10 carve-out and does NOT flow through AuditLogWriter. UnbanCommandHandlerTest's audit-row assertions (UNBAN row exists on non-preban path; UNBAN row carries `restored_group_admin` list when applicable; UNBAN_PREBAN_DELETE row is unaffected by this ticket) continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java` returns ≥1; `grep -cE 'CALL\\s+delete_preban_user' infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java` returns ≥1 (the unchanged stored-procedure carve-out)"
  - "InviteCommandHandler migrates its raw `INSERT INTO audit_log` onto AuditLogWriter for all three subcommand paths (create, revoke; list is read-only and writes nothing). Both INVITE_CREATE rows (--contact and --open variants) and the INVITE_REVOKE row flow through the writer. InviteCommandHandlerTest's audit-row assertions (INVITE_CREATE row carries `details_json.invite_type` matching the parsed `--contact`/`--open` shape; INVITE_REVOKE row exists on the success path only; no audit row on the already-REVOKED probe path per M1-044c) continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java` returns ≥1"
  - "InviteCodeConsumer migrates its raw `INSERT INTO audit_log` onto AuditLogWriter for both audit-write paths: the INVITE_CONSUME row written on the Accepted outcome, and the INVITE_BRUTE_FORCE_BREACH row written on the BruteForceThresholdBreached outcome (M1-044a). InviteCodeConsumerTest's audit-row assertions continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java` returns ≥1"
  - "SourceUpsertService migrates its raw `INSERT INTO audit_log` (the ADD_SOURCE row written on /add-source upsert per M1-036) onto AuditLogWriter. SourceUpsertServiceIT's audit-row assertions continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java` returns 0; `grep -cE 'auditLogWriter' infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java` returns ≥1"
  - "BootstrapLoader migrates its raw `INSERT INTO audit_log` (the BOOTSTRAP_SOURCE_LOAD row written when bootstrap-sources.json upserts a source into the seed catalogue) onto AuditLogWriter. BootstrapLoaderIT's audit-row assertions continue to pass. Verify: `grep -cE 'INSERT\\s+INTO\\s+audit_log' infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java` returns 0; `grep -cE 'auditLogWriter' infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java` returns ≥1"
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
    - AuditLogAppendOnlyTest (M1-008a — trigger-tier test; bypasses AuditLogWriter and asserts only on the V5 append-only SQLException message)
    - BanCommandHandlerTest (audit-row assertions hold under writer migration; no details_json assertion)
    - UnbanCommandHandlerTest (audit-row assertions hold under writer migration; V5 procedure carve-out unchanged; one details_json substring assertion is on `restored_group_admin` plus a group UUID — neither is an API-key shape)
    - InviteCommandHandlerTest (audit-row assertions hold under writer migration across create + revoke paths; no details_json assertion)
    - InviteCodeConsumerTest (audit-row assertions hold under writer migration for INVITE_CONSUME + INVITE_BRUTE_FORCE_BREACH paths; no details_json assertion)
    - SourceUpsertServiceIT (audit-row assertion for ADD_SOURCE holds under writer migration; no details_json assertion)
    - BootstrapLoaderIT (audit-row assertion for BOOTSTRAP_SOURCE_LOAD holds under writer migration; no details_json assertion)
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Secrets handling (audit-log redaction hook)
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
  closed API-key-shape catalogue from §Secrets handling to
  `details_json` only — each match (OpenAI/Anthropic/GitHub/AWS/
  Google/Slack/generic-32+-char-adjacent-to-keyword) is replaced
  with `[REDACTED]`; the matcher is fail-closed on timeout. The
  hook does NOT redact `target_contact_id`: per spec, contact-id
  redaction is for non-audit logs only (`ContactIds.redact`
  prefix+ellipsis+suffix), and the `audit_log_view` exposes the
  redacted contact-id at read time per §DB roles. The hook
  receives the constructed audit-row record and returns a
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

The audit-log redaction hook this ticket introduces masks
**API-key shapes only**, per `docs/spec/security.md` §Secrets
handling. Contact-id redaction is "outside the audit log" per
the same section (`audit_log` stores the full id;
`audit_log_view` redacts at read time per §DB roles). The
writer migration therefore does NOT change the stored value of
`target_contact_id`, any contact-id substring inside
`details_json`, or any other column for which an existing test
asserts a value that does not match an API-key shape from the
spec catalogue (OpenAI `sk-…`, Anthropic `sk-ant-…`, GitHub
`ghp_/gho_/ghu_/ghs_/ghr_…`, AWS `AKIA/ASIA[0-9A-Z]{16}`,
Google `AIza[0-9A-Za-z_-]{35}`, Slack `xox[abprs]-…`, or
32+-char hex/base64 adjacent to `api_key|secret|token|password|bearer`).

Verdict per pre-existing test class in `test_plan.preserves`:
**no modification required** for any class. The per-class
rationale below is verifiable by grep on the named file paths;
the developer must NOT mutate any of these test files during
implementation. If implementation surfaces a need to mutate any
of them, escalate (per the round cap and refine procedure) —
do not silently edit:

- `AuditLogAppendOnlyTest`
  (`infochat-core/src/test/java/app/zcat/infochat/core/schema/AuditLogAppendOnlyTest.java`,
  from M1-008a): bypasses the writer entirely — exercises the
  V5 `trg_audit_log_no_update` / `trg_audit_log_no_delete`
  triggers via raw JDBC + bootstrap superuser, asserts only on
  the raised `SQLException`'s message text containing the
  literal substring `"append-only"`. The writer migration is
  invisible to this test by design.
  Verify: `grep -cE 'auditLogWriter|details_json'
  infochat-core/src/test/java/app/zcat/infochat/core/schema/AuditLogAppendOnlyTest.java`
  returns 0.

- `BanCommandHandlerTest`
  (`infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java`,
  from M1-044c): asserts on `target_contact_id LIKE ?`
  (test-namespace prefix `m1-044c-ban-*`), `action`,
  `request_id`, `count(*)` only. No assertion on `details_json`
  text content. Test-namespace strings are short ASCII
  identifiers with no API-key shape.
  Verify: `grep -cE 'details_json'
  infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java`
  returns 0.

- `UnbanCommandHandlerTest`
  (`infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnbanCommandHandlerTest.java`,
  from M1-044c): asserts on `action`, `target_id` (UUID),
  `request_id`, `count(*)`, and ONE `details_json::text`
  substring check (the UNBAN row's details carry a
  `restored_group_admin` list when the unbanned user had
  group-admin rows). The substring assertions are
  `"restored_group_admin"` and a group UUID — neither matches
  an API-key shape per the spec catalogue, so the redaction
  hook is a no-op on this row.
  Verify: `grep -cE "details_json"
  infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnbanCommandHandlerTest.java`
  returns ≤2 (the one assertion plus the SELECT clause); a
  manual read shows both substring checks are non-API-key
  shapes (`restored_group_admin` literal + a UUID).

- `InviteCommandHandlerTest`
  (`infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java`,
  from M1-044c): asserts on `action`, `target_id` (UUID
  toString), `count(*)` only. No `details_json` assertion.
  Verify: `grep -cE 'details_json'
  infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java`
  returns 0.

- `InviteCodeConsumerTest`
  (`infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java`,
  from M1-044a): asserts on `action` (referenced via the
  `InviteCodeConsumer.INVITE_CONSUME` / `INVITE_BRUTE_FORCE_BREACH`
  constants) and `target_contact_id = ?` exact match against
  the test-namespace prefix `invite-test-*`. No `details_json`
  assertion. Test-namespace strings are short ASCII identifiers
  with no API-key shape.
  Verify: `grep -cE 'details_json'
  infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java`
  returns 0.

- `SourceUpsertServiceIT`
  (`infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java`,
  from M1-036; note: file name ends in `IT`, not `Test`):
  asserts on `action` (literal `ADD_SOURCE`), `target_kind`
  (literal `source`), `target_id` (UUID toString),
  `actor_user_id` (UUID), `count(*)` only. No `details_json`
  assertion.
  Verify: `grep -cE 'details_json'
  infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java`
  returns 0.

- `BootstrapLoaderIT`
  (`infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderIT.java`,
  from collector bootstrap; note: file name ends in `IT`, not
  `Test`): asserts on `action = 'BOOTSTRAP_SOURCE_LOAD'` and
  `count(*)` only. No `details_json` assertion.
  Verify: `grep -cE 'details_json'
  infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderIT.java`
  returns 0.

**M1-033 audit-row tests:** none exist. The M1-033 ticket
added Stage 2 worker + router tests but did NOT introduce a
direct test for `StartupReleaseOnStage2FailureWarn` (the bean
acceptance item 5 migrates onto AuditLogWriter). Verified:
`grep -rlE 'StartupReleaseOnStage2'
infochat-collector/src/test/ infochat-core/src/test/
infochat-provider/src/test/` returns zero matches. Acceptance
item 5's "M1-033's audit-row tests continue to pass" clause is
therefore vacuous; preservation of the row's externally-visible
shape (action, target_kind, target_id, actor_user_id,
request_id, details_json keys) is reviewer-verified by
code-reading the migrated `StartupReleaseOnStage2FailureWarn.java`
against the prior raw-INSERT form, not by an existing test
assertion.

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

## Round 1 rework

PARAMETER-CONTRACT-CHECK: FAIL on three Builder setters. All other
checks PASS. Address ONLY the named item, re-run
`mvn -B clean verify > target/m1-tick-test-M1-041-r2.log 2>&1`,
then `/m1-tick review M1-041`.

1. Add `@NonNull` (from `org.jspecify.annotations`) to the three
   Builder setter parameters in
   `infochat-core/src/main/java/app/zcat/infochat/core/audit/RedactionHook.java`:
   - `public Builder action(AuditAction v)` at line 139
   - `public Builder targetKind(String v)` at line 144
   - `public Builder targetId(String v)` at line 149

   These three setters correspond to the three required fields
   enforced by `Builder.build()`; annotating them as `@NonNull`
   makes the contract explicit at the signature, matching the
   sibling setters that already carry `@Nullable` for their
   nullable fields. Alternative (also acceptable per the
   §7a javadoc carve-out): add a one-line `@param v ...` javadoc
   to each setter instead of the annotation.
