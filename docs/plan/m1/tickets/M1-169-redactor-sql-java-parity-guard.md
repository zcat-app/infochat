---
id: M1-169
title: "Drift guard: V31 audit redactors vs Redactor.CATALOGUE"
status: done
created: 2026-06-05
last_updated: 2026-06-05
blocked_by: []
files_budget: 3
files_scope:
  - infochat-core/src/test/java/app/zcat/infochat/core/log/RedactorSqlParityIT.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
remediates: M1-127
out_of_scope:
  - any change to V31__service_role_login_and_audit_redaction.sql or any other Flyway migration (the read-side SQL redactors match the Java catalogue today per the /redteam M1-127 finding; this ticket adds the missing drift guard, it does not change the redactors — if the guard ever reveals a real divergence, the corrective SQL is a separate ticket)
  - any change to infochat-core/.../log/Redactor.java or its CATALOGUE (production code is unchanged; the guard READS CATALOGUE, it does not modify it, and does not add a public accessor — the test lives in the log package so the package-private field is visible)
  - a per-input regex watchdog / statement_timeout on redact_secrets_jsonb (accepted residual risk — see Notes; the closed seven-family patterns are linear and Postgres's regex engine is not backtracking-based, so the catastrophic-backtracking DoS the Java-side watchdog defends against does not apply to the SQL path for the current catalogue)
  - redact_contact_id (the length-10 INFO-LEAK was Finding 1, already fixed in M1-127; this ticket covers only Finding 2, the secrets-catalogue drift)
  - infochat-provider/** and infochat-collector/** (the guard is a core-module test; the redactors live in infochat-core)
acceptance:
  - "A new integration test infochat-core/src/test/java/app/zcat/infochat/core/log/RedactorSqlParityIT.java exists, runs against a Testcontainers Postgres with all Flyway migrations (including V31) applied, and is green under mvn verify."
  - "The test declares a fixed table of exactly one representative secret sample per Redactor.CATALOGUE family, and asserts samples.size() == Redactor.CATALOGUE.size() — so adding a family to the Java CATALOGUE without adding a corresponding sample fails the build (drift tripwire #1: Java catalogue grew, guard not updated)."
  - "For every sample, the test asserts BOTH masks fire: Redactor.redact(sample) returns the value containing the [REDACTED] sentinel (write-side Java filter) AND SELECT redact_secrets_jsonb(jsonb_build_object('k', <sample>)) returns JSON whose value is masked to [REDACTED] (read-side SQL view function). A family present in the Java catalogue but absent from V31's regexp_replace list fails this assertion (drift tripwire #2: read-side mask lags the write-side)."
  - "The test includes a negative control: a non-secret string (e.g. \"plain non-secret text\") is left UNCHANGED by both Redactor.redact and redact_secrets_jsonb — guarding against an over-broad SQL regex that masks everything (which would mask drift by always passing tripwire #2)."
  - "mvn -B clean verify from the repo root exits 0; all tests currently green on main continue to pass."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/log/RedactorSqlParityIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §DB roles
decision_refs: []
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
      files: 3
      added: 170
      removed: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-169: Drift guard: V31 audit redactors vs Redactor.CATALOGUE

## Context

Remediates Finding 2 from `/redteam M1-127` (frontmatter `redteam_findings:` on
M1-127, verbatim record at `docs/plan/m1/redteam/M1-127-2026-06-05.md`). M1-127's
V31 migration implemented `redact_secrets_jsonb` as a PL/pgSQL function that
hand-copies the closed seven-family API-key catalogue from
`app.zcat.infochat.core.log.Redactor.CATALOGUE` as a sequence of
`regexp_replace` calls. The spec (`docs/spec/security.md` §Secrets handling)
promises the write-side audit filter and the catalogue "cannot drift" — a
guarantee the console filter and the `DefaultRedactionHook` audit-write path
deliver structurally by calling the *same* Java `Redactor.redact`. The V31 SQL
function is a *third, independent* copy of the catalogue, kept in sync only by a
comment. The read-side `audit_log_view` mask exists precisely as the
defense-in-depth backstop for a secret the write hook misses; if a future ticket
adds an eighth family to `Redactor.CATALOGUE` and updates `DefaultRedactionHook`
but not V31, the backstop silently lags and `/audit` could surface a secret of
the new family unredacted.

The SQL and Java catalogues match today (the redteam confirmed no present
divergence) — so the gap is the *absence of a mechanical guard*, not a live bug.
This ticket adds that guard: a parity test that fails the build the moment the
two copies diverge.

## Acceptance

See frontmatter. A new `RedactorSqlParityIT` in the `app.zcat.infochat.core.log`
package (so it can read the package-private `Redactor.CATALOGUE`) stands up a
Testcontainers Postgres with V31 applied and asserts, per catalogue family, that
a representative secret sample is masked by BOTH the Java `Redactor.redact` and
the SQL `redact_secrets_jsonb`. A size assertion ties the sample table to
`CATALOGUE.size()` so a new Java family forces a new sample, which then also
exercises the SQL function — chaining the two tripwires so drift in either
direction (Java grew, or SQL lagged) fails the build. A negative control on a
non-secret string guards against an over-broad SQL regex that would mask
everything and thereby hide drift.

## Out-of-scope

See frontmatter. The redactors themselves are correct today and are NOT touched
— this is a test-only ticket. `migration_touch: false` and `security_relevant:
false` are deliberate: the diff changes no runtime trust boundary (it only adds a
build-time guard), so a `/redteam` re-run on a test-only diff would add nothing.
The `redact_contact_id` length-10 leak was Finding 1 and is already fixed in
M1-127; this ticket is Finding 2 only. The "no per-input watchdog on the SQL
regex path" sub-observation from Finding 2 is accepted residual risk (see Notes).

## Notes

- **Why test-only is the right fix.** The finding is "no anti-drift guard," not
  "the SQL is wrong." The spec's anti-drift commitments elsewhere (tool-registry
  byte-for-byte check, sanitizer match-set derivation) are CI-enforced; this
  brings the read-side SQL mask under the same kind of mechanical check.
- **Package placement.** `Redactor.CATALOGUE` is `static final List<Pattern>`
  with package-private visibility. The guard must live in
  `app.zcat.infochat.core.log` to read it; do NOT widen `CATALOGUE`'s visibility
  for the test. The existing `app.zcat.infochat.core.schema.PostgresSchemaTestBase`
  is the Testcontainers harness the schema ITs use — reuse it if cross-package
  visibility allows, otherwise stand up the container the same way it does.
- **Calling the SQL function directly.** `redact_secrets_jsonb(input JSONB)` is a
  plain function; the test can call `SELECT redact_secrets_jsonb(jsonb_build_object('k', ?))`
  with the sample bound as the value, then assert the returned JSONB's `k` field
  equals `[REDACTED]` and does not contain the original secret. No need to go
  through `audit_log_view`.
- **The watchdog sub-concern is accepted residual risk.** Finding 2 noted the SQL
  path lacks the per-input `java.util.regex`-plus-watchdog fail-closed discipline
  the Java side has. That discipline defends against catastrophic backtracking on
  attacker-influenced input. The seven V31 patterns are all linear (no nested or
  overlapping quantifiers), and Postgres's regex engine is not an NFA-backtracking
  engine of the kind that suffers exponential blowup, so the DoS class the Java
  watchdog addresses does not apply to the current catalogue on the SQL path. If a
  future family introduces a non-linear pattern, the watchdog question reopens as
  its own ticket. The parity guard added here will, separately, force that future
  family to be added to V31 in the first place.
- **Adjacent code** (read-only references, not touched by this ticket): the
  `Redactor` class in `app.zcat.infochat.core.log` (the catalogue), the
  `redact_secrets_jsonb` function in migration V31 (the SQL mirror), and
  `DefaultRedactionHook` in `app.zcat.infochat.core.audit` (the write-side path
  that shares `Redactor.redact`).

## Pre-flight self-check (author-side)

Run `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-169-redactor-sql-java-parity-guard.md`
before `/m1-tick start M1-169`.
