---
id: M1-116
title: "Remediate D47 migration: map legacy group_only rows to preban, not invited"
status: deferred
created: 2026-05-29
last_updated: 2026-05-29
deferred_on: M1-117
deferred_reason: spec-amend
remediates: M1-111
blocked_by:
  - M1-111
files_budget: 2
files_scope:
  - infochat-core/src/main/resources/db/migration/V27__d47_remove_group_only.sql
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-provider/** — no production-code change; InboundRouter (step-3 preban group drop + step-4 BanCheck), BanCheck, and UnbanCommandHandler already handle the canonical preban+banned row shape unchanged. The fix is migration-only.
  - infochat-collector/** — no collector changes.
  - infochat-core/src/main/java/** — no SPI/Java changes; only the existing V27 migration file is edited.
  - M1-111's other deliverables (AutoRegisterService deletion, /vouch simplification, InboundRouter step-3 rewrite, BundleKeys edits) — already landed on the M1-111 branch; NOT re-touched here.
  - The post-V27 CHECK value set — unchanged from M1-111 (still removes 'group_only'; result stays IN ('preban','invited','vouched')).
  - Migration version renumbering — stays V27 (the file M1-111 introduced on the branch). V28 only becomes correct if M1-093 takes V27 on main before M1-111 merges; that is M1-111's merge-time concern, not this ticket's.
  - New migration IT / new test — the data-transform branch is dormant in CI (Quarkus Dev Services spins a fresh empty Postgres per run, so the UPDATE matches zero rows); post-V27 preban+banned intake behavior is already covered by existing BanCheck/InboundRouter tests.
  - banned_by population / per-row audit — out of scope; one aggregate 'system' audit row, banned_by left NULL (system migration, no admin actor).
acceptance:
  - "V27's data step maps legacy rows with `UPDATE users SET registration_state='preban', is_banned=TRUE, banned_at=NOW(), ban_reason=<D47 non-punitive cause> WHERE registration_state='group_only'`. It MUST set `is_banned=TRUE` (not registration_state alone): the DM invite gate keys off row-absence, so an existing row is blocked in DM only by the step-4 BanCheck (`SELECT is_banned`), while `registration_state='preban'` alone triggers only the step-3 group drop. Verify: V27 sets both `is_banned = TRUE` and `registration_state = 'preban'`; grep -E \"=\\s*'invited'\" on V27 returns ZERO matches."
  - "The `UPDATE` precedes the `ALTER TABLE ... ADD CONSTRAINT` so no lingering 'group_only' row violates the narrowed CHECK during the migration. Flyway wraps the script in one transaction on PostgreSQL, so UPDATE-then-ALTER is atomic."
  - "The conditional audit_log row is preserved: a CTE feeds an INSERT guarded by `HAVING count(*) > 0`, so a fresh DB (zero affected rows) writes NO audit row. The row's `action` reflects the preban conversion (not 'consolidation'); `target_kind='system'` (the only closed-set value per the V5 audit_log CHECK that fits a schema-wide migration); `details_json` carries the affected-row count. Verify: grep shows `HAVING count(*) > 0` present and the action string changed from `D47_REGISTRATION_STATE_CONSOLIDATION`."
  - "The CHECK constraint is altered to `registration_state IN ('preban','invited','vouched')` — unchanged from M1-111. Verify: the two `ALTER TABLE users ... CONSTRAINT users_registration_state_chk` statements are present and the new value set omits 'group_only'."
  - "`banned_by` is NOT set by the UPDATE (left NULL — a system migration has no admin actor; the column is nullable per V5). `ban_reason` is a non-punitive string naming the D47 cause. Verify: the UPDATE SET clause does not reference `banned_by`."
  - "Migration-only change: M1-116 modifies no production Java or test file of its own. Because this branch forks from the unmerged M1-111 tip (7c3e16a), isolate M1-116's delta against THAT base, not main (main...HEAD would also show M1-111's inherited .java/test changes). Verify: `git diff --name-only 7c3e16a..HEAD` contains exactly ONE production file — the V27 migration — and NO path ending in `.java`. The remaining entries are lifecycle/doc byproducts folded into this commit: this ticket file; M1-111's redteam_findings/redteam_audits frontmatter and the docs/plan/m1/redteam/M1-111-2026-05-29.md verdict file (the audit record motivating this remediation); and STATUS.md."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies: []
  preserves:
    - all tests currently green on the M1-111 branch (the V27 edit changes only the data-transform target state; no test seeds 'group_only' post-V27, and a fresh test DB applies the UPDATE against an empty users table, matching zero rows)
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Invite-code registration
  - docs/spec/security.md §User ban
  - docs/spec/security.md §What's intentionally NOT in v1
  - docs/spec/schema.md §Identity and access
decision_refs:
  - D44
  - D45
  - D47
redteam_findings: []
reviews:
  - round: 1
    date: 2026-05-29
    verdict: MANUAL
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 311
      removed: 19
    manual_reason: |
      SPEC-CONFORMANCE-CHECK FAIL. The diff conforms to security.md
      §Invite-code registration / §User ban (no group-side bypass; canonical
      preban shape) but contradicts a DIFFERENT cited spec section,
      schema.md §Identity and access "Migration (D47)" (lines 64-70), which
      verbatim prescribes group_only→'invited' "preserves their access". The
      spec is internally inconsistent: security.md forbids the bypass,
      schema.md mandates it. M1-111 implemented schema.md. The fix lives
      outside this migration-only ticket's scope (a spec amendment to
      schema.md §Identity and access, reconciling it with security.md, plus
      adding the group_only→preban one-time migration transition to the
      closed transition set). Routed to MANUAL for user adjudication. Verdict
      file: target/m1-tick-review-M1-116-r1.txt.
escalations:
  - date: 2026-05-29
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      VERDICT: MANUAL — SPEC-CONFORMANCE-CHECK FAIL. schema.md §Identity and
      access "Migration (D47)" prescribes group_only→'invited' ("preserves
      their access"), the inverse of this diff's preban+banned disposition
      and of the security.md no-bypass guarantee. Spec is internally
      inconsistent; resolution is a spec amendment (out of this ticket's
      migration-only scope), not a developer rework loop.
clarity_check:
  date: 2026-05-29
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-116: Remediate D47 migration — map legacy group_only rows to preban, not invited

## Context

This remediates **M1-111** (done, unmerged on its branch). M1-111's
`/redteam` audit (2026-05-29, see
`docs/plan/m1/redteam/M1-111-2026-05-29.md`) found one AUTH-BYPASS
finding (high): V27's data step

```sql
UPDATE users SET registration_state = 'invited'
 WHERE registration_state = 'group_only'
```

promotes every legacy `group_only` row — a contact auto-registered by
the now-removed group @mention path who **never presented an
admin-issued invite** — into `'invited'`, the state that signifies
"passed the DM invite gate." That grants DM + full group access D47
promises is reachable only via an invite (security.md §Invite-code
registration: "there is no group-side registration bypass";
§What's intentionally NOT in v1: "the auto-registration path is
permanently closed").

## Why preban+banned and not DELETE or registration_state-only

Ground-truthed against the schema and intake code:

- **DELETE is unsafe.** `DELETE on users` is revoked from the service
  roles (`delete_preban_user` is the only sanctioned delete path);
  `group_membership.user_id`, `saved_post.user_id`, `chat_*.user_id`
  are `NOT NULL REFERENCES users(id)` with no `ON DELETE` (RESTRICT),
  and a `group_only` user definitionally has a membership row; and
  `audit_log.actor_user_id REFERENCES users(id)` with audit
  immutability (Invariant 7) makes deleting an audited user impossible
  without destroying history.
- **`registration_state='preban'` alone does NOT block DM.** The DM
  invite gate (InboundRouter step 2) fires only when no users row
  exists; an existing row is blocked in DM solely by the step-4
  `BanCheck.isBanned` (`SELECT is_banned`). `registration_state='preban'`
  only triggers the step-3 group drop. So `is_banned=TRUE` is required
  to close the DM half of the bypass.
- **The canonical preban shape closes both halves with zero code
  changes.** `BanCommandHandler` mints a preban row as
  `(is_banned=TRUE, registration_state='preban', banned_at=NOW(), ...)`.
  Migrating `group_only` rows to that exact shape makes them
  indistinguishable from a legitimately pre-banned contact: blocked in
  DM (step 4) and group (step 3), recoverable only via admin `/unban`
  (→ `delete_preban_user` → "fresh invite required"), which routes the
  contact back through the invite gate — precisely what the threat
  model demands. No InboundRouter / BanCheck / Unban change is needed.

D47 (decisions.md) removes the `group_only` enum value but does not
prescribe the legacy-row disposition, so the preban conversion is in
bounds.

## Greenfield note (severity calibration)

M1 is greenfield: no released production DB, and tests use Quarkus Dev
Services (a fresh empty Postgres per run). Flyway applies V27 against an
empty `users` table, so the `UPDATE` matches **zero rows** in CI and on
the first real deploy — the `→invited` vs `→preban` choice touches
nothing there. The transform can fire only on a **persistent local dev
DB** that accumulated `group_only` rows under pre-M1-111 code. The fix
is therefore correctness/hardening for that latent path (and any future
non-greenfield upgrade), at near-zero cost: migration-only, breaks no
test. This is why no migration IT is added — the data-transform branch
is unreachable in CI, and the post-V27 preban+banned *behavior* is
already covered by existing BanCheck/InboundRouter tests.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. The headline: this is a single-file migration edit. No
Java changes, no test changes, no migration-version bump.

## Notes

- **Branch base.** This ticket's branch forks from the **M1-111 branch
  tip** (not `main`), because it edits the V27 file M1-111 introduced,
  which is not yet on `main`. Merge order: M1-111 first, then M1-116.
- **Flyway checksum.** Editing V27 here (before it is ever merged /
  applied to a persistent DB) keeps a single correct V27 reaching
  `main`. The edit changes V27's checksum relative to the M1-111
  branch's intermediate version, which is immaterial because no
  persistent DB has applied the intermediate (testcontainers are
  ephemeral). Do not introduce a separate V28 for this — it is the same
  migration, corrected before release.
- **M1-111 acceptance is frozen.** M1-111 is done; its acceptance items
  1–2 (which describe the `→invited` UPDATE) are a historical record and
  are NOT rewritten. The `remediates: M1-111` link records the
  supersession.
