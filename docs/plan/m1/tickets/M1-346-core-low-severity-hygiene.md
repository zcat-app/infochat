---
id: M1-346
title: "infochat-core: schema-test javadoc, audit-denorm IT package, contact-id parity comment"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 4
files_scope:
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/PostgresSchemaTestBase.java
  - infochat-core/src/test/java/app/zcat/infochat/DeletePrebanUserAuditDenormIT.java
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/DeletePrebanUserAuditDenormIT.java
  - infochat-core/src/main/java/app/zcat/infochat/core/log/ContactIds.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any executable/behavioral change. PostgresSchemaTestBase and ContactIds changes are comment/javadoc only; the IT change is a package move (no test-body change).
  - The ContactIds redaction algorithm and MIN_REDACTABLE_LENGTH value — unchanged (the threshold-entropy concern is a separate design-coordination item, not in scope here).
  - The Redactor catalogue (the separate 7-pass perf item) — not touched.
acceptance:
  - "PostgresSchemaTestBase's class Javadoc no longer claims Flyway applies 'V1..V5' or that 'V5 doesn't exercise pgvector'. It is rewritten self-maintaining: the fixture applies every migration under classpath:db/migration (V1 through the current head) — the migrate() call has no target — and the pgvector note references V1's CREATE EXTENSION plus V11's vector(N) columns. No hard-coded migration number remains in the comment."
  - "DeletePrebanUserAuditDenormIT moves from package app.zcat.infochat (module test root) to app.zcat.infochat.core.schema, co-located with its sibling delete_preban_user / identity-schema tests (DeletePrebanUserTest, DeletePrebanActorCheckTest, LastAdminTriggerTest, AuditActorIntegrityTest). The package declaration changes accordingly and the now-same-package import of PostgresSchemaTestBase is removed. Test body is otherwise unchanged; it still runs under failsafe via the *IT suffix."
  - "ContactIds.redact carries a contract comment stating the SQL-parity (redact_contact_id V31) holds only for BMP inputs — length()/substring() count UTF-16 units while SQL char_length/left/right count code points — and that contact ids are ASCII/BMP-shaped cryptographic identifiers (D10) so the two never diverge in practice, warning a future non-BMP id alphabet would break the parity ContactIdsSqlParityIT pins. No code-point rewrite (that would be defensive code for a D10-excluded scenario)."
  - "mvn -B clean verify from the repo root exits 0 (DeletePrebanUserAuditDenormIT runs from its new package; ContactIdsSqlParityIT and the schema ITs stay green)."
test_plan:
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/DeletePrebanUserAuditDenormIT.java (relocated)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D10
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-346: infochat-core — low-severity test/comment hygiene sweep

## Context

Three low-severity deep-review v5.5 findings on `infochat-core`, grouped because
all three are comment/test-placement hygiene with no behavioral change (verified
at source 2026-06-14):

- **opus-48 `02-module-infochat-core.md` F1** — stale migration-range Javadoc on
  `PostgresSchemaTestBase` (the 23-subclass shared fixture). The javadoc says
  "applying V1..V5" and "V5 doesn't exercise pgvector"
  (PostgresSchemaTestBase.java:29-40), but `migrate()` has no `target` and applies
  every migration through the head; the fixture is the base for parity ITs that
  assert the cursor reached V33.

- **opus-48 `02-module-infochat-core.md` F2** — `DeletePrebanUserAuditDenormIT`
  sits at package `app.zcat.infochat` while every sibling `delete_preban_user` /
  schema test lives under `app.zcat.infochat.core.schema`
  (DeletePrebanUserAuditDenormIT.java:1, confirmed siblings present). It even
  imports `PostgresSchemaTestBase` rather than picking it up same-package.

- **opus-48 `02-module-infochat-core.md` F3** — the contact-id redaction gate
  uses UTF-16 `length()`/`substring()` while the SQL mirror `redact_contact_id`
  uses code-point `char_length`/`left`/`right` (ContactIds.java:104-109). Currently
  unreachable (D10 contact ids are ASCII/BMP) but the parity invariant rests on an
  unstated assumption, not a comment or a parity-test sample.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- The ContactIds fix is a comment (not a code-point rewrite): the values are
  constrained at the adapter contact-id boundary (D10) outside this helper, so a
  code-point rewrite would be defensive code for a scenario the trust boundary
  forbids (§7). Document the invariant; do not add the handling.
- The schema-test javadoc fix is self-maintaining ("V1 through the current head")
  rather than bumping the number to V50, which would re-rot on the next migration.
