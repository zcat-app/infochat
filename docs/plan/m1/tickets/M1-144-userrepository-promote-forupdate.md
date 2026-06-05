---
id: M1-144
title: "UserRepository extraction + /promote FOR UPDATE"
status: done
created: 2026-06-02
last_updated: 2026-06-05
revisions:
  - date: 2026-06-05
    reason: "refine (budget-breach rework, 2nd) — widen files_budget 22→25 for the test-double cascade (3 plain-JUnit tests wire handler DataSource fields directly and must rewire to UserRepository); add test_plan.modifies authorizing DI-rewiring-only edits to those 3 pre-existing test files"
    snapshot:
      files_budget: 22
      test_plan_modifies: absent
  - date: 2026-06-05
    reason: "refine (budget-breach rework) — widen files_budget 20→22 for the grounded 19-file lookup-helper fan-out (+Promote +UserRepository +test); reword acceptance item 3 to name SaveCapConcurrencyIT as the TOCTOU-test mirror (the referenced GrantAdmin TOCTOU test does not exist)"
    snapshot:
      files_budget: 20
      acceptance_item_3: "A regression test mirrors the GrantAdmin TOCTOU test for /promote"
      risk: medium
escalations:
  - date: 2026-06-05
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (escalation is from budget-breach during implementation grounding).
      Grounded fan-out: 19 handler files define a duplicated lookup helper
      (8x lookupUser, 6x lookupActorForUpdate incl. Save/Vouch ActorRow,
      3x lookupUserId, 2x resolveUserId) + PromoteCommandHandler = 20 main
      files; + new UserRepository + new TOCTOU regression test = 22 files
      vs files_budget: 20. Secondary premise defect: acceptance item 3
      references "the GrantAdmin TOCTOU test", which does not exist in the
      test tree (GrantAdminCommandHandlerTest has no race test); the real
      mirror candidate is SaveCapConcurrencyIT (two-thread FOR UPDATE race).
  - date: 2026-06-05
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (second budget-breach, found during round-1 mvn verify).
      Removing StopCommandHandler's now-unused DataSource field broke
      StopCommandHandlerTest (compile: wires handler.dataSource), and the
      Retry delegation NPEs two plain-JUnit tests (RetryCommandHandlerTest,
      RetryDigestCommandTest) whose stub DataSources must now also back a
      UserRepository. 3 pre-existing test files need DI-rewiring-only edits
      (no assertion changes): 22 + 3 = 25 vs files_budget 22. test_plan
      lacks a `modifies:` list authorizing pre-existing-test edits.
blocked_by:
  - M1-133
files_budget: 25
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the JsonEscaper/TagNormalizer/Sha256 extraction (M1-133 — a different primitive; this ticket rebases onto it)
  - changing the per-handler record types (keep them; share only the SQL + row mapping)
acceptance:
  - "A UserRepository bean provides findByAdapterAndContactId, a …ForUpdate(Connection,…) variant, and resolveUserId; the 15+ duplicated lookupUser/lookupActorForUpdate call sites delegate to it"
  - "PromoteCommandHandler reads the actor row FOR UPDATE within its existing transaction (closing the TOCTOU window a concurrent /revoke-admin opens), mirroring the M1-046 PERM-ESCAL closure on the sibling handlers"
  - "A regression test mirrors the SaveCapConcurrencyIT two-thread FOR UPDATE race pattern for /promote: a concurrent transaction holding the actor's users row blocks the /promote actor read until it commits, and a /revoke-admin that lands first causes the /promote to be refused"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  modifies:
    - "StopCommandHandlerTest — DI rewiring only: the stub DataSource now feeds a UserRepository instead of the removed handler.dataSource field; no assertion changes"
    - "RetryCommandHandlerTest — DI rewiring only: wire handler.userRepository from the existing stub DataSource; no assertion changes"
    - "RetryDigestCommandTest — DI rewiring + stub ResultSet serves the canonical projection's extra columns (getString contact_id/registration_state, getInt save_count); no assertion changes"
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/commands.md §Operator note: group-admin race
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: WARN
      acceptance: PASS
    diff_stats:
      files: 26
      added: 496
      removed: 464
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-05
    category: PERM-ESCAL
    severity: medium
    promise: |
      "Authorization → execution. Permission checks run in deterministic
      Java. The LLM is downstream of every authorization decision" and
      "Group admin — one group only ... /promote / /demote by bot admin."
      The diff positions itself as the M1-046 PERM-ESCAL closure: "FOR
      UPDATE locks the actor row ... the is_admin value cannot go stale
      between the admin gate and the membership UPDATEs below (mirrors the
      M1-046 PERM-ESCAL closure on the sibling admin handlers)."
    gap: |
      /demote is the exact sibling of /promote (bot-admin-only, group scope,
      mutates group_membership.is_group_admin inside a transaction) yet was
      NOT converted to the locked read. DemoteCommandHandler.resolveAdmin
      (DemoteCommandHandler.java:141-152) still issues the plain
      "SELECT id, is_admin FROM users" (DemoteCommandHandler.java:35-37)
      with no FOR UPDATE, inside the same setAutoCommit(false) transaction
      (DemoteCommandHandler.java:80-123) that then performs DEMOTE_SQL. This
      file is absent from the diff, so the "sibling admin handlers" set the
      diff claims to cover is incomplete.
    repro: |
      A bot admin holds an open /demote <group-admin> request. Concurrently
      another admin issues /revoke-admin against the first admin; the
      revoke's UPDATE commits during the demote transaction. Because
      Demote's admin-gate SELECT takes no row lock, it reads the pre-revoke
      committed snapshot (is_admin=true), passes the gate, and proceeds to
      clear the target's is_group_admin — a just-revoked actor performs a
      privileged group-admin mutation. Same TOCTOU the diff closes for
      /promote; the users.is_admin last-admin trigger does not backstop it
      because the mutation target is group_membership, not users.is_admin.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-06-05
    verdict: FINDINGS
    base: main
    head: m1/M1-144-userrepository-promote-forupdate
    verdict_file: docs/plan/m1/redteam/M1-144-2026-06-05.md
    findings_count: 1
    out_of_model_count: 0
    note: |
      One medium PERM-ESCAL finding: /demote (the sibling of /promote) still
      read its actor with an unlocked SELECT, leaving the same TOCTOU window
      /promote closes. Disposition (user decision): FIXED IN-BRANCH before
      squash-merge rather than deferred to a new ticket. DemoteCommandHandler
      now routes resolveAdmin through
      UserRepository.findByAdapterAndContactIdForUpdate (mirroring
      PromoteCommandHandler), and DemoteRevokeConcurrencyIT mirrors
      PromoteRevokeConcurrencyIT's two-thread row-lock proof. The fix lands
      on the M1-144 branch as a second commit and folds into the single
      squashed main commit.
clarity_check:
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 3: Name the GrantAdmin TOCTOU test class/method so the regression test shape is unambiguous without a grep."
    - "COMPLEXITY-RISK-CALIBRATED: risk: medium on a PERM-ESCAL-class TOCTOU closure may be underweight; consider risk: high."
    - "SELF-CONTAINED-CHECK: Acceptance item 3 indirectly delegates the test pattern to an unidentified reference test; naming it would make the ticket fully self-contained."
---

# M1-144: UserRepository extraction + /promote FOR UPDATE

## Context

The `SELECT … FROM users WHERE adapter=? AND contact_id=?` pattern is
re-implemented in 15+ handlers + `InboundRouter`, each returning a slightly
different record; a `users`-schema change must touch all of them. Bundled (same
file family): `/promote` reads the actor row without `FOR UPDATE`
(`PromoteCommandHandler.java:90-93,158-169`), leaving a TOCTOU window a
concurrent `/revoke-admin` can exploit — the sibling handlers already added
`FOR UPDATE` in the M1-046 PERM-ESCAL closure; Promote was missed.

## Acceptance

See frontmatter. Introduce `UserRepository`; delegate the call sites; add
`FOR UPDATE` to `/promote`.

## Out-of-scope

See frontmatter. `blocked_by: M1-133` — both sweep the same handler files;
rebase onto the shared-helper extraction to avoid a merge collision. Keep the
per-handler record types.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-LOOKUP-DUP, §B-PROMOTE-FORUPDATE;
  `opus-47-full-handout.md` §F-SIM-04, F-SEC-12; `opus-47-only-handout.md` §S4.
- High `files_budget` for the 15+ call-site fan-out; the refactor is mechanical.
