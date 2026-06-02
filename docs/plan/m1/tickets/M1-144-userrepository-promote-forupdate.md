---
id: M1-144
title: "UserRepository extraction + /promote FOR UPDATE"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by:
  - M1-133
files_budget: 20
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
  - "A regression test mirrors the GrantAdmin TOCTOU test for /promote"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/commands.md §Operator note: group-admin race
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
