---
id: M1-163
title: "Shared DB test-seeding seam for the IT suite"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 140
complexity: high
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the per-service DB role split, any datasource username/role change, or any DevServices container/auth change — that is M1-127, which this ticket unblocks
  - changing, adding, or removing any test assertion or test coverage; this is a behavior-preserving refactor only
  - any production (src/main) change — the default datasource and all ~100 src/main @Inject DataSource sites stay exactly as they are
acceptance:
  - "A single test-only DB-access seam exists that DB-backed @QuarkusTest classes route their direct datasource access through, and the seam draws its JDBC connection from ONE configurable point — so a later ticket (M1-127) can repoint that one point at an owner datasource without editing individual tests. Today the seam resolves to the existing default datasource, so behavior is unchanged"
  - "Every test under infochat-provider/src/test and infochat-collector/src/test that currently injects the unqualified default DataSource for direct DB access is migrated to obtain its connection from the seam instead. After the sweep, a grep over both test trees finds no @Inject of the unqualified default DataSource outside the seam's own producer/base — every fixture seed, mutation, and direct-read path resolves through the seam"
  - "Tests that seed fixtures through the default-bound EntityManager (rather than raw JDBC) route those writes through the seam's connection as well, so no fixture write depends on the default datasource"
  - "Behavior-preserving: zero production (src/main) changes, zero migration or schema changes, and no test assertion is changed, added, or removed. The set of passing tests is identical before and after"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - "the seam itself — a test-only qualifier + CDI producer (or package-private base class) under each module's src/test, exposing the single repointable connection source"
  modifies:
    - "the ~99 provider ITs and ~35 collector ITs that inline dataSource.getConnection() for fixture access — migrated to the seam (exact list is every src/test file injecting the unqualified default DataSource)"
  preserves:
    - all tests currently green on main (behavior-preserving refactor — same assertions, same green set)
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
revisions: []
escalations: []
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
      files: 128
      added: 368
      removed: 70
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-163.md
---

# M1-163: Shared DB test-seeding seam for the IT suite

## Context

Surfaced by **M1-127** (DB per-service role wiring). M1-127 makes the security
boundary load-bearing by giving each service's **default** datasource the
least-privileged role (`infochat_collector` / `infochat_provider`) — fail-closed,
so production code that forgets to qualify still gets the safe role, and the
user-facing Provider carries no superuser credentials. For the suite to keep
proving the boundary (acceptance #5: "fix real privilege-mismatched DML surfaced
as IT failures"), the test runtime must run production code under that weak role.

The blocker, measured: **134 ITs** (99 provider + 35 collector) seed and assert
fixtures by inlining `dataSource.getConnection()` against the **default**
datasource — the very connection the weak role would restrict. Provider's weak
role has only `SELECT` on `post` / `source` / `tag` / `post_embedding` /
`post_entity`, so every fixture `INSERT` into a collector-owned table would fail
with `permission denied`. There is **no shared seam today** — no base test class,
no seeding helper; each file opens connections inline. Flipping the default to
the weak role without a seam would be a ~134-file rework tangled into M1-127's
security change (4× its `files_budget`).

This ticket extracts that scattered access into **one** seam, **behavior-
preserving**, so M1-127 (rebased on top) repoints a single connection source at
an owner datasource and the suite seeds as owner while production code runs as
the weak role — a minimal, security-focused diff.

## Acceptance

See frontmatter. The deliverable is purely structural: one repointable test
DB-access seam, every DB-backed IT routed through it, no behavior change. The
seam resolves to the existing default datasource today, so the green set is
identical; M1-127 later changes the one connection source, not 134 tests.

This is a **high-file-count, low-line-count** sweep: each migrated file changes
only how it obtains its connection (a qualifier or a base-class handle), ~2–3
lines, no SQL or assertion touched. The large `files_budget: 140` reflects the
breadth of the uniform sweep, not depth — the actual diff is a few hundred
trivial lines. Calibrated deliberately so the budget does not read as scope
sprawl.

## Out-of-scope

See frontmatter. The role split, the datasource role flip, the owner-datasource
wiring, and all DevServices/auth handling are **M1-127's** job; this ticket only
builds the seam M1-127 will repoint. No production code and no assertions change.

## Notes

- **Seam shape is a design decision for the plan pass.** Two viable shapes: (a) a
  test-only `@SeedDataSource` CDI qualifier + a `@Produces` test bean returning
  the default datasource today; tests change `@Inject DataSource` →
  `@Inject @SeedDataSource DataSource` (one line/file, inline SQL untouched). Or
  (b) a package-private base class exposing `seedConnection()`. (a) is the lower-
  churn, more-uniform option and keeps each test's body almost unchanged; the
  plan-writer should confirm the qualifier resolves under `@QuarkusTest` and that
  M1-127 can repoint the producer to an owner datasource. Keep any test doubles
  top-level (no inner-class proliferation).
- **The EntityManager corner.** The 6 collector tests that touch `EntityManager`
  all also use `getConnection` (verified: zero seed via EM alone), so they are
  inside the 134 — but any fixture *write* they do via the default-bound EM must
  also move onto the seam connection, or M1-127's weak-role default would break
  it. Acceptance item #3 pins this.
- **Resume path for M1-127.** When M1-163 is `done` and merged: `/m1-tick reopen
  M1-127`, rebase its branch (`m1/M1-127-db-roles-audit-redaction`, carries the
  V31 migration WIP: service-role LOGIN + audit_log_view redactors) onto the new
  `main`, repoint the seam producer at an owner datasource, flip the default
  datasource to the weak role, fix any residual surfaced DML, update
  `DbRoleMatrixIT`, then `/redteam`.
