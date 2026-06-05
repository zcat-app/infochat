---
id: M1-127
title: "DB per-service role wiring + audit_log_view redaction"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: [M1-163]
files_budget: 10
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - sweeping every privilege-mismatched DML call site (those surface as IT failures and are fixed as they appear — but do not pre-emptively rewrite unrelated handlers)
  - the GRANT matrix definitions themselves (V2/V5/V21 already define them); this ticket makes them load-bearing, it does not re-author them
acceptance:
  - "A new migration grants LOGIN to infochat_collector and infochat_provider (currently set NOLOGIN by V4__nologin.sql); infochat_admin stays NOLOGIN (operator-psql role, not a service login, per security.md §DB roles)"
  - "Runtime datasource wiring is property-only (no Java producer bean): each service's DEFAULT datasource connects as its per-service role (infochat_collector / infochat_provider), so the existing ~99 @Inject DataSource sites stay on the default and are NOT re-qualified; a SEPARATE named owner datasource (user infochat) is configured and Flyway is pointed at it via quarkus.flyway.datasource so migrations run as owner"
  - "redact_contact_id and redact_secrets_jsonb (currently no-op RETURN-input stubs at V5__identity_audit.sql:324-336) are reimplemented via a new CREATE OR REPLACE migration per security.md §Secrets handling: redact_contact_id returns contact ids in prefix+ellipsis+suffix form AND never emits a contact id in full — any id short enough that the prefix and suffix together span its entire length (a 6-char prefix + 4-char suffix ⇒ length ≤ 10) is fully masked, so the length-10 boundary discloses nothing; redact_secrets_jsonb masks values matching the closed seven-family API-key catalogue (exact regexes in docs/design/04-security.md). audit_log_view then returns redacted contact ids and details_json"
  - "docs/design/07-deployment.md canonical datasource block is corrected to match the implemented wiring (default datasource = per-service role; named owner datasource for Flyway). The existing INFOCHAT_COLLECTOR_PASSWORD / INFOCHAT_PROVIDER_PASSWORD operator inputs (already documented at :154,156 and :300-301) are retained — fix any line that currently puts the runtime on a named provider/collector datasource"
  - "mvn -B clean verify from the repo root exits 0 (expect to fix real privilege-mismatched DML surfaced as IT failures; the DbRoleMatrixIT update in §Authorized test changes is the one authorized pre-existing-test modification)"
test_plan:
  adds: []
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java — assert infochat_collector/infochat_provider carry LOGIN and infochat_admin stays NOLOGIN (forced by acceptance #1); see §Authorized test changes"
  preserves:
    - all other tests currently green on main
spec_refs:
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §Authorization model
  - docs/spec/schema.md §Invariants
decision_refs: []
revisions:
  - date: 2026-06-02
    reason: clarity-fail refine — files_scope path infochat-provider/.../provider/audit
      does not exist on disk; the two classes acceptance items #3/#4 reference live
      elsewhere (DefaultRedactionHook/RedactionHook in infochat-core/.../core/audit;
      AuditCommandHandler in infochat-provider/.../provider/command, read-only).
      Replace the empty provider/audit path with infochat-core/.../core/audit so
      acceptance #4 (flip DefaultRedactionHook off pass-through) is in-scope.
    prior_files_scope:
      - infochat-core/src/main/resources/db/migration
      - infochat-collector/src/main/resources/application.properties
      - infochat-provider/src/main/resources/application.properties
      - infochat-provider/src/main/java/app/zcat/infochat/provider/audit
      - docs/design/07-deployment.md
  - date: 2026-06-02
    reason: >-
      outline-fail refine (all findings independently verified against ground
      truth before applying). (1) BLOCKER: acceptance #1 (grant LOGIN)
      structurally breaks DbRoleMatrixIT.applicationRolesAreCreatedAndNologin —
      the only test pinning NOLOGIN (grep confirms one file) — so added a
      §Authorized test changes section + test_plan.modifies authorizing the
      NOLOGIN→LOGIN assertion update (admin stays NOLOGIN). (2) Dropped old
      acceptance #4 (enable DefaultRedactionHook): verified already done on main —
      it is the live @ApplicationScoped bean delegating to Redactor and
      AuditLogWriter:103 routes every row through it; the only pass-through is a
      test @Alternative. (3) Rescoped old #5: the two operator passwords are
      already documented (07-deployment.md:154,156,300-301); the real doc work is
      correcting the canonical datasource block to the implemented shape.
      (4) Pinned the budget-safe wiring in #2 — default datasource = role, named
      owner datasource for Flyway (verified ~99 @Inject DataSource sites: 72
      provider + 27 collector, so the doc's named-role shape would blow
      files_budget:10). (5) Removed files_scope: the IT-failure DML sweep is
      unbounded by design, so a hard path boundary would trip a spurious
      budget-breach on every surfaced handler; files_budget:10 alone bounds it.
    prior_files_scope:
      - infochat-core/src/main/resources/db/migration
      - infochat-collector/src/main/resources/application.properties
      - infochat-provider/src/main/resources/application.properties
      - infochat-core/src/main/java/app/zcat/infochat/core/audit
      - docs/design/07-deployment.md
    prior_acceptance:
      - "A migration grants LOGIN to infochat_collector and infochat_provider (currently NOLOGIN)"
      - "Each service connects via a Quarkus named-datasource: Flyway runs as the infochat owner, the runtime connects as the per-service role"
      - "redact_contact_id and redact_secrets_jsonb (currently no-op RETURN input stubs) implement the redaction policy from docs/spec/security.md so audit_log_view returns redacted contact ids and details_json"
      - "The Provider-side redaction hook (DefaultRedactionHook) is enabled rather than a pass-through stub"
      - "Two new operator password inputs are documented in docs/design/07-deployment.md"
      - "mvn -B clean verify from the repo root exits 0 (expect to fix real privilege-mismatched DML surfaced as IT failures)"
  - date: 2026-06-05
    reason: >-
      redteam-finding refine (round-1 APPROVE, in-review; /redteam M1-127
      returned FINDINGS). Finding 1 (INFO-LEAK, low): redact_contact_id leaks a
      contact id of length exactly 10 in full — the guard char_length(input)<10
      falls through to left(input,6)||'…'||right(input,4), which tiles the whole
      10-char string (6+4=10) and hides nothing. Refined acceptance #3 to make
      the no-full-disclosure contract explicit: any contact id short enough that
      a 6-char prefix + 4-char suffix would cover its entire length (length ≤ 10)
      is fully masked — i.e. the threshold is <= 10, not < 10. Finding 2
      (read-side SQL catalogue can drift from Redactor.CATALOGUE; no CI guard) is
      NOT folded into this ticket: it is future-drift defense-in-depth, retained
      in redteam_findings: for a separate follow-up ticket so this security
      ticket's scope stays on the role split + the length-10 correctness fix.
    prior_acceptance:
      - "A new migration grants LOGIN to infochat_collector and infochat_provider (currently set NOLOGIN by V4__nologin.sql); infochat_admin stays NOLOGIN (operator-psql role, not a service login, per security.md §DB roles)"
      - "Runtime datasource wiring is property-only (no Java producer bean): each service's DEFAULT datasource connects as its per-service role (infochat_collector / infochat_provider), so the existing ~99 @Inject DataSource sites stay on the default and are NOT re-qualified; a SEPARATE named owner datasource (user infochat) is configured and Flyway is pointed at it via quarkus.flyway.datasource so migrations run as owner"
      - "redact_contact_id and redact_secrets_jsonb (currently no-op RETURN-input stubs at V5__identity_audit.sql:324-336) are reimplemented via a new CREATE OR REPLACE migration per security.md §Secrets handling: redact_contact_id returns contact ids in prefix+ellipsis+suffix form; redact_secrets_jsonb masks values matching the closed seven-family API-key catalogue (exact regexes in docs/design/04-security.md). audit_log_view then returns redacted contact ids and details_json"
      - "docs/design/07-deployment.md canonical datasource block is corrected to match the implemented wiring (default datasource = per-service role; named owner datasource for Flyway). The existing INFOCHAT_COLLECTOR_PASSWORD / INFOCHAT_PROVIDER_PASSWORD operator inputs (already documented at :154,156 and :300-301) are retained — fix any line that currently puts the runtime on a named provider/collector datasource"
      - "mvn -B clean verify from the repo root exits 0 (expect to fix real privilege-mismatched DML surfaced as IT failures; the DbRoleMatrixIT update in §Authorized test changes is the one authorized pre-existing-test modification)"
escalations:
  - date: 2026-06-02
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      N/A — caught at /m1-tick start step-0 grounding before the clarity
      subagent ran: files_scope path
      infochat-provider/src/main/java/app/zcat/infochat/provider/audit resolves
      to no files on disk, and acceptance item #4 requires editing
      infochat-core/.../core/audit/DefaultRedactionHook.java which was absent
      from files_scope — the first edit would have tripped budget-breach.
  - date: 2026-06-02
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — plan-writer (complexity:high)

      DECISIVE BLOCKER: Acceptance item #1 ("grants LOGIN to infochat_collector
      and infochat_provider") cannot be implemented without modifying the
      pre-existing, currently-green test
      DbRoleMatrixIT.applicationRolesAreCreatedAndNologin, which asserts all
      three application roles carry rolcanlogin=false
      (infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java:53-54,76-77).
      Granting LOGIN flips that assertion red; there is no implementable path
      that satisfies #1 while keeping it green. test_plan.preserves: [all tests
      currently green on main] + no §Authorized test changes section means this
      required edit is unauthorized. The body's "Expect IT failures … fix those
      as they appear" authorizes fixing privilege-mismatched DML, NOT rewriting
      a test's correctness invariant. Enumerated hard OUTLINE-FAILED condition.

      SECONDARY (reconcile in refine so a re-run Plan does not surface fresh):
      - Acceptance #4 (enable DefaultRedactionHook off pass-through) is ALREADY
        satisfied on main: DefaultRedactionHook.java already delegates to the
        Redactor closed catalogue and redacts details_json; AuditLogWriter.write
        already routes every row through redactionHook.redact()
        (DefaultRedactionHook.java:33-57, AuditLogWriter.java:103). No-op item.
      - Acceptance #5 (two operator password inputs documented) appears ALREADY
        satisfied: INFOCHAT_COLLECTOR_PASSWORD / INFOCHAT_PROVIDER_PASSWORD are
        documented at docs/design/07-deployment.md:154,156 and :300-301. Re-scope
        to the service application.properties (still hardcode username=infochat)
        or name a concrete remaining doc gap.

      WIRING GUIDANCE (to stay within files_budget:10): the only budget-compatible
      shape is making the DEFAULT datasource connect as the per-service role (so
      the ~72 provider + ~31 collector @Inject DataSource sites stay untouched)
      and running Flyway against a SEPARATE named owner datasource — NOT the
      design doc's canonical-block shape (default=infochat owner, named role),
      which would force migrating ~103 injection sites and blow the budget.

      SUGGESTED ESCALATION: refine
  - date: 2026-06-02
    reason: prerequisite-surfaced
    reviewer_verdict_excerpt: |
      Developer-initiated defer during /m1-tick start (post-outline, before
      datasource wiring). Implementation revealed that genuinely exercising the
      role split in test — the intent behind acceptance #5 ("fix real
      privilege-mismatched DML surfaced as IT failures") — requires the app's
      default datasource to connect as the least-privileged role under
      @QuarkusTest. Measured blocker: ~36 provider ITs (and several collector
      ITs) seed fixtures by inlining dataSource.getConnection() + raw INSERT
      against the default datasource (collector-owned tables: post/source/tag/
      post_embedding/post_entity). Flipping the default to the weak role makes
      every such fixture INSERT fail with permission-denied. There is no shared
      seeding seam today (no base test class; ~99 files open connections inline),
      so the flip is a ~36–99-file rework — 4× files_budget:10 and a large
      mechanical diff tangled into a security change. Resolution: defer behind
      M1-163 (shared DB test-seeding seam, behavior-preserving). When M1-163 is
      done, reopen M1-127, rebase its branch (carries the V31 migration WIP:
      service-role LOGIN + audit_log_view redactors), flip the default to the
      weak role via the seam, and finish. The migration half is already written
      and committed on m1/M1-127-db-roles-audit-redaction as a resume point.
  - date: 2026-06-05
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      /redteam M1-127 returned FINDINGS (2 low, both INFO-LEAK, in the V31
      redactors):
      (1) redact_contact_id (V31:65-75) leaks a contact id of exactly 10 chars
          in full — guard is char_length < 10, but at length 10 the 6-char
          prefix and 4-char suffix tile the whole string; only a cosmetic
          ellipsis is inserted. Threshold should be <= 10. Confirmed by
          inspection. SUGGESTED-FIX-CLASS: input-sanitization.
      (2) redact_secrets_jsonb (V31:88-100) hand-copies the seven-family
          Redactor.CATALOGUE as a third independent copy with no CI guard
          against future drift; SQL path also lacks the fail-closed watchdog
          the Java path has. Matches today — gap is future drift, not present
          divergence. SUGGESTED-FIX-CLASS: audit-log-coverage.
      Verdict file: docs/plan/m1/redteam/M1-127-2026-06-05.md
  - date: 2026-06-05
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      Round-2 reviewer returned MANUAL. SCOPE-DRIFT-CHECK: FAIL solely on the
      must-shrink arithmetic (files 10→11, added 267→442, removed 78→81 vs the
      round-1 APPROVE); all other checks PASS, BUILD SUCCESS. Reviewer found the
      entire growth is workflow-mandated lifecycle byproduct of the
      redteam-finding refine (the new redteam verdict file +51 lines = the +1
      file, plus the redteam_findings/redteam_audits/reviews/reopens/refine
      frontmatter blocks — all lifecycle-exempt paths), with the only
      non-lifecycle code delta being the single-line V31 length-10 fix. The
      must-shrink rule presumes a fix-only round after a REWORK; its exception
      (cite a prior REWORK refactor) cannot apply because round 1 was APPROVE,
      so the rule and the lifecycle-artifact mandate are in direct conflict.
      Reviewer: "the override is a user decision, not a reviewer one." Verdict
      file: target/m1-tick-review-M1-127-r2.txt
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 267
      removed: 78
  - round: 2
    date: 2026-06-05
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 442
      removed: 81
    note: |
      MANUAL (not REWORK): the only failing check is the round-2 must-shrink
      arithmetic (files 10→11, added 267→442, removed 78→81 — growth on all
      three dimensions vs the round-1 APPROVE). The reviewer determined the
      entire growth is workflow-mandated lifecycle byproduct of the
      redteam-finding refine — the new redteam verdict file (+51, the +1 file,
      a lifecycle-exempt path) and the redteam/audit/review/refine frontmatter
      blocks — NOT developer scope creep. Among non-lifecycle implementation
      files the touched set is unchanged (8 files both rounds) and the only
      code delta is the single-line V31 redact_contact_id threshold fix
      (< 10 → <= 10) demanded by the redteam length-10 finding. All other
      checks PASS (test-integrity, out-of-scope, negative-space, acceptance,
      spec-conformance); BUILD SUCCESS. Reviewer routes the must-shrink/
      lifecycle-mandate conflict to a user override decision. Verdict file:
      target/m1-tick-review-M1-127-r2.txt
  - round: 2
    date: 2026-06-05
    verdict: OVERRIDE-APPROVE
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    override_ref: 0
    note: |
      User override of the round-2 MANUAL must-shrink SCOPE-DRIFT FAIL. The
      checks above are carried through verbatim from the MANUAL verdict — the
      scope_drift FAIL stands as the reviewer reported it; the verdict alone
      carries the override. See overrides[0].
overrides:
  - date: 2026-06-05
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — round-2 diff grew along ALL THREE must-shrink
      dimensions vs round 1 (files 10→11, lines added 267→442, lines removed
      78→81), and the prior round was an APPROVE — there is no round-1 REWORK
      authorizing a refactor to invoke the must-shrink exception. By the literal
      rule (engineering-rules-verbatim.md §8 Round-N must-shrink), that is an
      automatic SCOPE-DRIFT FAIL.
    user_justification: |
      Must-shrink growth is entirely workflow-mandated redteam lifecycle
      artifacts on an otherwise-clean, all-checks-PASS one-line security fix;
      not developer scope drift. The reviewer itself found the touched
      non-lifecycle implementation set unchanged (8 files both rounds) and the
      only code delta is the single-line V31 redact_contact_id threshold fix
      (< 10 → <= 10) that the redteam length-10 finding demanded. A developer
      cannot shrink files / added / removed without deleting required workflow
      artifacts (the redteam verdict file + the redteam/audit/review/refine
      frontmatter). Established disposition for redteam-finding refines on
      already-approved tickets. Every other check is PASS and the build is green.
aborted_attempts: []
reopens:
  - date: 2026-06-05
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-163
    reason: M1-163 seeding seam landed
redteam_findings:
  - date: 2026-06-05
    category: INFO-LEAK
    severity: low
    promise: |
      "Contact IDs are logged in redacted form (prefix + ellipsis + suffix)"
      and "audit_log_view is a Postgres view that exposes the same columns as
      audit_log minus any redacted fields (raw secrets, full contact ids —
      replaced with the redacted form per §Secrets handling)."
      (docs/spec/security.md §Secrets handling, §DB roles)
    gap: |
      V31__service_role_login_and_audit_redaction.sql:65-75 — redact_contact_id
      masks fully only when char_length(input) < 10, otherwise returns
      left(input,6) || '…' || right(input,4). For a contact id of length
      exactly 10, the 6-char prefix and 4-char suffix are disjoint and
      contiguous (6+4 = 10), so they tile the entire string: every character is
      emitted with only a cosmetic ellipsis in the middle, hiding nothing. The
      comment reasons only about the overlap case (< 10) and misses that the
      no-hidden-middle case extends through length 10; threshold should be <= 10.
    repro: |
      A user whose adapter-assigned contact id is exactly 10 characters is
      involved in any audited action (BAN, INVITE_CREATE --contact,
      GRANT_ADMIN target, etc.). A bot admin runs /audit, which reads through
      audit_log_view; the actor_contact_id / target_contact_id columns render
      the full 10-character contact id rather than a partially-masked form.
    suggested_fix_class: input-sanitization
  - date: 2026-06-05
    category: INFO-LEAK
    severity: low
    promise: |
      "The audit_log writer consumes the same Redactor utility so the two
      cannot drift." / "mirrors app.zcat.infochat.core.log.Redactor.CATALOGUE
      so the read-side SQL mask cannot drift from the write-side Java filter."
      The spec elsewhere makes anti-drift a structural (CI-enforced) property.
      (docs/spec/security.md §Secrets handling, §LLM output sanitizer)
    gap: |
      V31__service_role_login_and_audit_redaction.sql:88-100 hand-duplicates
      the seven-family catalogue from log/Redactor.java:45-54 as PL/pgSQL
      regexp_replace calls — a third, independent copy kept in sync only by a
      comment, with no CI check binding the SQL bodies to Redactor.CATALOGUE.
      If a key family is later added to the Java catalogue, the read-side
      audit_log_view mask silently lags. Also redact_secrets_jsonb runs
      Postgres regexp_replace with no per-input watchdog, unlike the §Ingest
      java.util.regex-plus-watchdog fail-closed discipline. SQL and Java
      catalogues match today; the finding is the absence of a mechanical guard
      against future drift, not a present divergence.
    repro: |
      Future maintainer adds an eighth key family to Redactor.CATALOGUE (a
      design-note edit per §Secrets handling) and updates DefaultRedactionHook
      but not V31. A secret of the new family written to audit_log details_json
      is masked on the write path but the read-side audit_log_view mask — the
      defense-in-depth backstop — surfaces it unredacted.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-06-05
    verdict: FINDINGS
    base: 411734b (fork point of m1/M1-127-db-roles-audit-redaction from main)
    head: working tree (branch tip 59d6193 + uncommitted V31 / role-wiring changes)
    verdict_file: docs/plan/m1/redteam/M1-127-2026-06-05.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Audited in-review branch + working tree (--in-progress-equivalent state,
      most V31 work uncommitted). Two low findings, both in the V31 redactors:
      (1) redact_contact_id length-10 boundary leak (off-by-one, threshold
      should be <= 10), reproduced by inspection; (2) SQL/Java redaction
      catalogue drift — read-side mask has no CI guard against future Java
      catalogue changes. Ticket is in-review, so the fix path is lifecycle
      escalation on the same ticket, not a remediation ticket. Two OUT-OF-MODEL
      items (dev-default DB passwords; Collector owner-datasource superuser
      creds for deterministic PartitionCreator DDL) are operator-trusted /
      no-untrusted-input and outside the documented threat model.
outline_file: target/m1-tick-outline-M1-127.md
clarity_check:
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: files_budget:10 leaves 5 slots for the IT-failure DML sweep after 5 core files. The ticket explicitly identifies this as unbounded-by-design. Be prepared to escalate under round_cap:3 if more than 5 privilege-mismatched DML handler files surface during the IT run."
    - "SELF-CONTAINED-CHECK: Acceptance #3 points to 'exact regexes in docs/design/04-security.md' as implementation guidance for redact_secrets_jsonb. The behavioral contract (seven-family catalogue) is inlined and the spec catalogue is in the already-cited security.md §Secrets handling (lines 1076-1086). At implementation time, consult the spec §Secrets handling and the existing Redactor utility (from M1-041) for the concrete regex patterns rather than relying on the design-file pointer alone."
  blockers: []
---

# M1-127: DB per-service role wiring + audit_log_view redaction

## Context

Both services connect as `quarkus.datasource.username=infochat` (the bootstrap
owner); `infochat_collector`/`infochat_provider`/`infochat_admin` are NOLOGIN and
no `SET ROLE` is issued. Every defense-in-depth layer the spec attaches to the
role split — `audit_log_view` redaction, quarantine SECURITY DEFINER carve-outs,
Invariants 4/10 — is decorative; a SQL-injection foothold in the Provider would
have owner privilege on every table today. V2 carries the explicit "until the
named-datasource wiring ticket lands" note; that ticket never landed.
Coupled (same trust boundary): `audit_log_view`'s `redact_contact_id` /
`redact_secrets_jsonb` are literal `RETURN input` no-op stubs, so `/audit`
surfaces raw contact ids and unredacted `details_json`.

## Acceptance

See frontmatter. A migration grants `LOGIN` to the two service roles (admin
stays NOLOGIN); property-only datasource wiring puts each runtime on its
per-service role by default and runs Flyway against a named owner datasource;
the two `audit_log_view` redactor functions are implemented. The write-side
`DefaultRedactionHook` is already live (M1-041) — there is no Java hook work
here; the remaining redaction gap is the read-side SQL view functions.

## Out-of-scope

See frontmatter. **Expect IT failures** — they surface the real
privilege-mismatched DML the current owner-connection setup hides; fix those as
they appear, but do not pre-emptively rewrite unrelated handlers. **security_relevant**
→ run `/redteam` after. Migration version assigned at start (do not hardcode).

## Authorized test changes

`DbRoleMatrixIT.applicationRolesAreCreatedAndNologin`
(`infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java`)
asserts all three application roles carry `rolcanlogin=false`. Acceptance #1
grants `LOGIN` to `infochat_collector` and `infochat_provider`, which
structurally flips that assertion — it is the only currently-green test that
breaks. **Authorized change:** update the test to assert `infochat_collector`
and `infochat_provider` carry `LOGIN` while `infochat_admin` remains `NOLOGIN`,
and rename the method to match the new invariant (e.g.
`applicationRolesHaveExpectedLoginAttributes`). This is a correctness-invariant
update forced by the role split, NOT a weakening or disabling of the test.

The redaction implementation (acceptance #3) requires NO test change:
`AuditCommandHandlerTest`, `QuarantineWorkflowIT`, and `ExportDataCollectorTest`
all assert only non-redacted columns (action, target_kind, target_id) and
survive `redact_contact_id` / `redact_secrets_jsonb` going live; their stale
"redactor is a stub" comments may be refreshed as ordinary in-scope cleanup.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A4 (DB-OWNER-ROLE, Critical, GROUNDED) +
  §A11 (AUDIT-VIEW-REDACTION, High, GROUNDED); `opus-47-full-handout.md` §F-SEC-03, F-SEC-05;
  `opus-47-only-handout.md` §TP3.
- Loci: `application.properties` collector `:13` / provider `:18`; roles
  `V2__roles.sql`; NOLOGIN `V4__nologin.sql`; redactor stubs + view
  `V5__identity_audit.sql:324-352`; Provider read `AuditCommandHandler.java`.
  Latest migration is `V30` (assign the new version at start, do not hardcode).
  Write-side hook `DefaultRedactionHook` is already implemented (M1-041) — not in scope.
- Plan-writer pass required — datasource wiring + redaction + the IT-failure sweep.
