---
id: M1-190
title: "Scope the last-admin LOCK TABLE to admin-relevant updates"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 4
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-core/src/test/java/app/zcat/infochat/core/schema
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - the grants migration V39 — M1-189's; keep the two migrations independent
  - the V15 save_count trigger pair and its FOR UPDATE discipline — correct; it is a victim of the table lock, not a cause
  - the chat_memory LRU trigger race (audit D7) — grouped in the mediums-batch chat-persistence ticket (UNIFIED.md T23), not yet filed
  - any change to which transitions the last-admin triggers guard (revoke, ban, ban-self, DELETE) — only WHEN the table lock is taken changes
acceptance:
  - "Per docs/spec/schema.md §Invariants Invariant 2 — \"**The trigger MUST serialize concurrent revocation attempts** so two simultaneous `/revoke-admin` (or ban) operations against different admin rows cannot both observe the pre-state and both succeed, leaving zero admins.\" — LastAdminConcurrentRevocationTest still proves the serialization after the change (two concurrent revocations: exactly one succeeds)"
  - "A users-row UPDATE that touches neither is_admin nor is_banned (e.g. a save_count bump or last_seen_at write) no longer takes the SHARE ROW EXCLUSIVE table lock: a named IT holds one such update open in a transaction and asserts a second, concurrent non-admin update on a different row commits without blocking (today V35's trigger functions take LOCK TABLE users as their first statement on EVERY row update — V15's save_count trigger fires per /save, so every save serializes globally against all user-row writes)"
  - "All existing guarded-path behavior is unchanged: revoking or banning the last admin still fails with the V35 errcode, ban-self still fails, and the DELETE-path guard still holds (LastAdminTriggerTest, CannotBanSelfTriggerTest stay green unmodified)"
  - "mvn -B clean verify from the repo root exits 0 (Flyway ITs prove V40 applies on a fresh DB and on a V39-migrated DB)"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Invariants
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-190: Scope the last-admin LOCK TABLE to admin-relevant updates

## Context

V35's `trg_last_admin_protection_update` and `_delete` take
`LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE` as their **first statement**
(V35:31, :65), and V5:117 wires the UPDATE trigger `BEFORE UPDATE ON users
FOR EACH ROW` with no WHEN clause. SHARE ROW EXCLUSIVE self-conflicts, so
every `users` UPDATE — including V15's save_count bump on every `/save` and
routine last_seen_at writes — serializes globally against every other
user-row write. The lock is only needed when the update could affect the
admin count (is_admin or is_banned transitions on admin rows). Unified
finding D6 (high-perf), `deep-code-review/v2/UNIFIED.md` §2.

## Acceptance

See frontmatter. The invariant's serialization guarantee is preserved
verbatim; the lock simply stops taxing unrelated writes.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T14 under `deep-code-review/v2/` (opus-48 core
  F2).
- Migration version: **V40** (after M1-189's V39; re-sweep worktrees at
  start and renumber if either is taken).
- The trigger's branch conditions read only OLD/NEW row images, which are
  fixed inputs of the invocation — evaluating them before taking the lock
  does not re-open the TOCTOU the lock exists to close, because the lock
  guards the COUNT over other rows, not the triggering row's own values.
  Equivalent alternative per the invariant text: a WHEN clause on the
  trigger itself. Either satisfies the acceptance; argue the choice in the
  commit message.
