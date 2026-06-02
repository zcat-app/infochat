---
id: M1-127
title: "DB per-service role wiring + audit_log_view redaction"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
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
  - "redact_contact_id and redact_secrets_jsonb (currently no-op RETURN-input stubs at V5__identity_audit.sql:324-336) are reimplemented via a new CREATE OR REPLACE migration per security.md §Secrets handling: redact_contact_id returns contact ids in prefix+ellipsis+suffix form; redact_secrets_jsonb masks values matching the closed seven-family API-key catalogue (exact regexes in docs/design/04-security.md). audit_log_view then returns redacted contact ids and details_json"
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
