---
id: M1-290
title: "SECURITY DEFINER procedures reject banned admins as actors"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/QuarantineActorCheckTest.java
  - infochat-core/src/test/java/app/zcat/infochat/DeletePrebanUserAuditDenormIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - The Java-side authorization gates — they already check the live-admin conjunction; this is the defense-in-depth SQL layer only.
  - V40's trigger-level last-admin protection — already implements the conjunction; untouched.
  - Any other behaviour of the three routines (NOTIFY emission, audit denormalisation) — only the actor predicate changes.
acceptance:
  - "A new Flyway migration (next free version; V50 at drafting time — re-verify and serialize per the MIG-lane rule) redeclares delete_preban_user, approve_quarantine, and reject_quarantine so the actor check requires is_admin = TRUE AND is_banned = FALSE (today V45:37, V48:42 and V48:114 check is_admin = TRUE only); it applies cleanly on a fresh DB and on a DB migrated through the current head."
  - "Named banned-actor tests: for each of the three routines, a user with is_admin=TRUE, is_banned=TRUE is rejected as actor with the same error shape a non-admin actor gets."
  - "U-56 rider (⚠ user confirms the drop at start — confirm-before-delete): the same migration (or an immediately-following one in this diff) drops the dead column scope_preferences.digest_enabled (V7:89; zero readers/writers — the live flag is groups.digest_enabled; already recorded in docs/plan/m1/drafts/v4-deep-review-backlog.md as 'bundle with the next schema-touching ticket'). If the user declines, the drop is removed from the diff and this acceptance item is satisfied by recording the decision in the commit message."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/QuarantineActorCheckTest.java
    - infochat-core/src/test/java/app/zcat/infochat/DeletePrebanUserAuditDenormIT.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-290: SECURITY DEFINER procedures reject banned admins as actors

## Context

Deep-review v5 verified MEDIUM **U-08** (+ LOW **U-56** rider)
(`deep-code-review/v5/UNIFIED-REPORT.md` §3/§4; sources
`deep-code-review/v5/fable-5/02-module-infochat-core.md#F2`,
`deep-code-review/v5/gpt-55/report.md#M-02`; U-56:
`fable-5/02#F4`, `gpt-55#L-10` — gitignored; all load-bearing facts inlined):

The SECURITY DEFINER routines accept a banned admin as actor:
`delete_preban_user` (V45:37) and `approve_quarantine`/`reject_quarantine`
(V48:42, :114) check `is_admin = TRUE` without `AND is_banned = FALSE`
(verified 2026-06-11 by grep of both migration files). The spec defines a
live admin as the conjunction, and V40's last-admin triggers already
implement it. Under the SQL-injection-foothold threat model these actor
checks were added for (an attacker who can call procedures but not bypass
them), a banned admin's id remains a usable actor — a defense-in-depth gap.

## Acceptance

See frontmatter. Follow the V48 CREATE-OR-REPLACE chain precedent
(V21→V25→V32→V41→V48) — redeclare the full bodies, change only the
predicate.

## Out-of-scope

See frontmatter.

## Notes

- Migration version: **V50** was free on main and across all in-flight
  worktrees at drafting time (swept 2026-06-11 per the MIG-lane rule);
  re-verify at implementation time.
- The U-56 column drop is destructive: surface it explicitly at start and
  get the user's yes before the migration carries it.
- Banned-actor test fixtures: QuarantineActorCheckTest already exercises
  the actor predicate for the quarantine pair; extend it rather than
  duplicating harness code.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-290-*.md
```
