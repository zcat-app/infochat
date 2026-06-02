---
id: M1-163
title: "Shared DB test-seeding seam for the IT suite"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 8
complexity: high
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - introducing the per-service DB role split or any datasource username/role change — that is M1-127, which this ticket unblocks
  - changing what any test asserts, or adding new test coverage; this is a behavior-preserving refactor
acceptance:
  - "A shared test-only seeding seam exists (a single point — base class or helper — that hands out the JDBC connection used for fixture INSERTs), and every DB-backed IT that currently inlines dataSource.getConnection() for fixture seeding routes its seeding through it"
  - "The seam obtains its seeding connection from ONE configurable place, so a later ticket can repoint it at an owner datasource without editing each test file"
  - "No production code change; no migration or schema change; no test assertion change — the set of passing tests is byte-identical in intent before and after (same assertions, same green set)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - "the ~36 provider ITs that seed collector-owned tables (post/source/tag/post_embedding/post_entity/...) plus collector ITs doing owner-level fixture setup — exact list pinned at sizing time"
  preserves:
    - all tests currently green on main (this is a behavior-preserving refactor)
spec_refs:
  - docs/spec/verification.md §Verification strategy
decision_refs: []
revisions: []
escalations: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-163: Shared DB test-seeding seam for the IT suite

## Context

Surfaced by **M1-127** (DB per-service role wiring). M1-127 wants the app's
default datasource to connect as the least-privileged per-service role
(`infochat_collector` / `infochat_provider`) under `@QuarkusTest`, so that
over-privileged production DML surfaces as a test failure. The blocker: ~36
provider ITs (and several collector ITs) seed their fixtures by inlining
`dataSource.getConnection()` + raw `INSERT` against the **default** datasource —
the same connection the code-under-test uses. Flip the default to the weak role
and every one of those fixture `INSERT`s into a collector-owned table
(`post`, `source`, `tag`, ...) fails with `permission denied` before the test
even runs. There is **no shared seeding seam today** (grep finds no base test
class and no seeding helper; ~99 test files open connections inline), so the
flip would be a ~36–99-file rework — 4× M1-127's budget and a large mechanical
diff tangled into a security change.

This ticket extracts the inline seeding into a single seam, with **no behavior
change**, so M1-127 (rebased on top) can repoint fixtures at an owner datasource
and flip the default to the weak role with a minimal, security-focused diff.

## Acceptance

See frontmatter. The deliverable is purely structural: a single seeding seam
that all DB-backed ITs route fixture INSERTs through, sourcing its connection
from one configurable point. No role split, no migration, no assertion changes.

## Out-of-scope

See frontmatter. The role split and the owner-datasource repointing are
M1-127's job; this ticket only builds the seam they will use.

## Notes — SKELETON, finalize before `/m1-tick start`

- **Sizing is a placeholder.** `files_budget: 8` is the template default and is
  almost certainly wrong — the real surface is ~36 provider ITs plus collector
  ITs. Before `start`, decide whether this is one large-budget ticket or an
  **umbrella split by module** (e.g. one subticket per package/module) so each
  slice stays reviewable. Clarity will FAIL on `start` until acceptance + sizing
  are pinned — that is the intended forcing function.
- The seam must let a single config point choose the seeding connection (so
  M1-127 can later swap it for an owner datasource while the app default goes
  to the weak role). Likely shape: a package-private base class or a CDI test
  producer exposing a `seedConnection()` / `@DataSource("owner")` handle; decide
  at design time. Keep test doubles top-level (no inner-class proliferation).
- When M1-163 is `done` and merged, reopen M1-127 (`/m1-tick reopen M1-127`),
  rebase its branch (`m1/M1-127-...`, carries the V31 migration WIP) onto the
  new `main`, and finish the role-split wiring + DbRoleMatrixIT update there.
