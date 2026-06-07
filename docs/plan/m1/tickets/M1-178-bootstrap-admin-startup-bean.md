---
id: M1-178
title: "Implement the bootstrap-admin startup bean"
status: done
created: 2026-06-07
last_updated: 2026-06-07
escalations:
  - date: 2026-06-07
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — escalated by the developer before review: the implementation is
      complete and compiles, but mvn verify round 1 fails one pre-existing
      test (BanCommandHandlerTest.banOfOnlyAdminSurfacesLastAdminError).
      The @Startup bean now seeds (inmemory, test-bootstrap-contact,
      is_admin=true) in every @QuarkusTest boot, so the test's
      sole-admin premise no longer holds and last-admin protection does
      not fire. The intent-preserving fix (neutralize the bootstrap
      admin inside that scenario) lives in
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java,
      which is outside files_scope, and test_plan carries no modifies
      entry authorizing it.
clarity_check:
  date: 2026-06-07
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 8: comment-inspection acceptance item (Gate 7 comment no longer describes the bean as deferred/future) is verifiable by diff-read rather than by a runnable test command; not a blocker because the target string is unambiguous and greppable"
    - "COMPLEXITY-RISK-CALIBRATED: risk: medium is defensible but marginal for a ticket that seeds the sole source of is_admin=true rows in a fresh deployment; consider risk: high if no-admin-exists is treated as a high-severity failure mode"
  blockers: []
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/startup
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/startup
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - adapter-side contact-id parse validation ("each value MUST be parseable by its own adapter; Provider validates each at startup and refuses to start on a mismatch", deployment.md §Operator inputs item 2) — no SPI surface for adapter-side contact-id parsing exists today; that sentence needs an SPI decision and its own follow-up ticket (not yet filed), and silently half-implementing it here would fake the spec promise
  - AdapterRegistry Gate 7 union validation logic — already implemented and correct; this ticket only updates its comment, which currently claims the bean "will later" exist
  - /grant-admin, /revoke-admin, last-admin counting, ban protection — shipped; this bean only seeds rows
  - the Collector-side bootstrap loaders (sources, assets)
  - any users-table migration — the bean uses the existing users schema
acceptance:
  - "Per docs/spec/deployment.md §Operator inputs item 2 — \"On startup Provider ensures, for every adapter that does have a bootstrap admin, that the configured contact exists with `is_admin = true` (creating the user if needed) and writes a bootstrap row to `audit_log` (decision D9).\" — a named IT starts the bean against a fresh DB with `infochat.adapters.<name>.admin` configured and asserts the user row exists with is_admin = TRUE and an audit_log row with action BOOTSTRAP_ADMIN was written"
  - "Per docs/spec/deployment.md §Bootstrap behavior on startup — \"**Provider** ensures, for every enabled adapter, that its bootstrap-admin user exists and has `is_admin = true` (one bootstrap row per `(adapter, contact_id)`, all audit-logged).\" — a named IT with two enabled adapters each carrying a configured admin asserts two distinct admin rows, one per (adapter, contact_id), each audit-logged"
  - "Per docs/spec/deployment.md §Bootstrap behavior on startup (Bootstrap admin drift) — \"if the configured bootstrap admin contact id for that adapter does not match an existing `is_admin = true` row at `(adapter, contact_id)`, Provider creates a new admin row for that adapter (audit-logged) and **leaves any prior admin rows in place** with their `is_admin = true` flag intact (across this and any other adapter).\" — a named IT seeds a prior admin row, rotates the configured contact id, and asserts both rows carry is_admin = TRUE after startup"
  - "Per docs/spec/deployment.md §Bootstrap behavior on startup — \"A bootstrap-seeded admin row is created with `is_admin = true`, `is_banned = false`, `probation_until = NULL` (bootstrap admins skip the slow-start tier), and `registration_state = 'vouched'`\" — a named IT asserts all four column values on the seeded row"
  - "Per docs/spec/deployment.md §Bootstrap behavior on startup — \"The `audit_log` row written for the bootstrap records the original cause under `details_json.cause = 'bootstrap'`.\" — a named IT asserts the details_json content of the bootstrap audit row"
  - "Re-running startup with unchanged configuration is idempotent: a named IT asserts the second run creates no additional users rows and no additional BOOTSTRAP_ADMIN audit rows"
  - "An adapter without a configured admin is skipped (no row created for it) while the union gate in AdapterRegistry continues to enforce non-emptiness — a named IT covers the one-of-two-adapters-configured shape"
  - "The AdapterRegistry Gate 7 comment no longer describes the bean as deferred/future (\"will later read the same per-adapter property\")"
  - "BanCommandHandlerTest.banOfOnlyAdminSurfacesLastAdminError keeps its original sole-admin premise: the scenario neutralizes the boot-seeded bootstrap admin row the same way it already demotes its guardian admin, and its assertions (error.ban.last_admin surfaced, rollback observed) are unchanged in substance"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/startup
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java (the lastAdmin scenario's sole-admin premise is invalidated by the @Startup bean seeding (inmemory, test-bootstrap-contact, is_admin=true) at boot; the scenario additionally neutralizes that row alongside the existing guardian demotion — no behavioral assertion removed or weakened)
  preserves:
    - all other tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/deployment.md §Bootstrap behavior on startup
decision_refs:
  - D9
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 577
      removed: 15
revisions:
  - date: 2026-06-07
    reason: budget-breach refine (files_scope lacked the one pre-existing
      test whose sole-admin premise the @Startup bean invalidates)
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      files_scope_at_snapshot:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/startup
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/startup
      test_plan_at_snapshot:
        adds:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/startup
        preserves:
          - all tests currently green on main
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-07
    verdict: CLEAN
    base: 12eb765521e56fc584bdc70356e93347764de0ee
    head: 2422056a92e8520d01dba55e2dd60b870a150f60
    verdict_file: docs/plan/m1/redteam/M1-178-2026-06-07.md
    out_of_model_count: 2
    note: |
      CLEAN. Bean is not adversary-reachable (startup-only, before
      adapter activation); all inputs are operator-trusted config or
      D10 cryptographic identity; SQL parameterized; BOOTSTRAP_ADMIN
      audit row written in-transaction on state-changing cases only;
      last-admin V35 triggers untouched. Two OUT-OF-MODEL operator-
      config hardening notes (deferred contact-id parse validation —
      already in this ticket's out_of_scope; promote-in-place over a
      banned/preban row) — advisory, no remediation ticket required.
---

# M1-178: Implement the bootstrap-admin startup bean

## Context

The `@Startup` admin-bootstrap bean that CLAUDE.md §Bootstrap admin & sources
and `docs/spec/deployment.md` promise does not exist. AdapterRegistry's Gate 7
(AdapterRegistry.java:235-237) validates that the union of
`infochat.adapters.<name>.admin` values is non-empty and its own comment says
"The @Startup admin-bootstrap bean (deferred per M1-046's notes) will later
read the same per-adapter property to seed the row" — nothing consumes the
property. The only `INSERT INTO users` sites in provider main are
BanCommandHandler (pre-ban row) and InviteCodeConsumer (invite registration,
never `is_admin = true`), and no migration seeds an admin. A fresh deployment
therefore has zero `is_admin = true` rows: no `/invite`, no registration, the
bot is unusable. Unified finding C2 in `deep-code-review/v2/UNIFIED.md` §1.

The `BOOTSTRAP_ADMIN` audit verb already exists (AuditAction.java:69), so no
enum change is needed.

## Acceptance

See frontmatter — each spec sentence the bean satisfies is transcribed
verbatim and paired with a named IT.

## Out-of-scope

See frontmatter. The contact-id parse-validation sentence is deliberately
carved out: implementing it requires a per-adapter parsing surface on the
MessagingAdapter SPI that does not exist, which is an SPI design decision,
not a startup-bean detail. Flag it during review rather than half-shipping
it.

## Notes

- Source: `UNIFIED.md` §1 C2 under `deep-code-review/v2/` (kimi-folder arch
  F1, gpt S3).
- The bean belongs in `infochat-provider/.../startup/` next to
  InstanceLockGuard (the existing `@Startup` pattern to match).
- Ordering: deployment.md §Bootstrap behavior runs Flyway first, then the
  admin ensure, then the new-post reconciler, then adapter connects. The
  bean must run before adapters start serving traffic.
- `registration_state = 'vouched'` satisfies the DM gate per the spec; no
  invite minting for bootstrap admins.
