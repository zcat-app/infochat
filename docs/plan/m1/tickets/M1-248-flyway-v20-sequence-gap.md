---
id: M1-248
title: "Flyway V20 gap: comment-only no-op placeholder"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 2
files_scope:
  - infochat-core/src/main/resources/db/migration
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - A renumber of V21–V46 — explicitly rejected: it is a 26-file mechanical churn and there are stale historical references that assume V20 exists (M1-080a freezes "M1-079a's V20 migration"; M1-081a comments "V21 requires V20 to exist"). The placeholder is the cheap, low-risk path.
  - The contents/behavior of any existing migration — unchanged; this only adds the V20 slot.
  - Any schema change — the placeholder is a no-op; it creates/alters nothing.
acceptance:
  - "A new V20__intentionally_skipped.sql (comment-only header + a single no-op statement, e.g. DO $$ BEGIN END $$;) fills the V20 slot so the Flyway sequence is contiguous (V19 → V20 → V21). The header comment states V20 was reserved for groups/group_membership (ticket M1-079a) but dropped because those already exist in V5__identity_audit.sql (commit 424ed48), and that the placeholder exists to keep the sequence contiguous so the gap is not repeatedly re-flagged by audits."
  - "The migration applies cleanly on a fresh DB and is recorded in Flyway history: FlywayMigrationIT still passes (it boots the app and applies all migrations on a DevServices Postgres), proving Flyway accepts the no-op placeholder."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 23
      removed: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-248: Flyway V20 gap — comment-only no-op placeholder

## Context

Source: `deep-code-review/v3/` UNIFIED-REPORT.md T9 (deepseek arch `#F1`). This
finding has been raised repeatedly (kimi v1 §2.5, and the survey before it) and
never actioned — the goal of this ticket is to **stop the recurrence**, not to
fix a defect.

The gap is **intentional**: V20 was a planned migration (ticket M1-079a) that
would have created `groups`/`group_membership`, but both already exist in
`V5__identity_audit.sql`, so M1-079a was rewritten repositories-only and the V20
migration dropped (commit `424ed48`, 2026-05-25). Flyway tolerates version gaps
at runtime, so this is purely cosmetic — but a bare `V19 → V21` jump is a
recurring "what happened to V20?" question that every directory listing (human or
automated audit) re-raises.

A comment-only placeholder that fills the slot and documents the reason at the
exact spot an auditor looks removes the trigger permanently.

## Acceptance

See frontmatter. In prose: add `V20__intentionally_skipped.sql` — a documented
no-op (header comment + `DO $$ BEGIN END $$;`) that keeps the sequence contiguous
and explains the gap, pointing at commit `424ed48`. `FlywayMigrationIT` (which
applies every migration on a fresh DB) staying green is the proof Flyway accepts
it; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The renumber path is rejected; no existing migration's behavior
changes; no schema is touched.

## Notes

- `migration_touch: true` — serializes against other migration-touching tickets
  (M1-245, M1-246). This ticket claims the **V20** slot specifically (not the
  next free version), so it does not collide with new-migration tickets grabbing
  V47+.
- **Why a no-op statement and not pure comments:** there is no comment-only
  migration precedent in this repo (every existing migration has ≥1 executable
  line), and empty-migration acceptance was not empirically verified at draft
  time. The single `DO $$ BEGIN END $$;` guarantees Flyway applies and records
  the version with zero reliance on empty-migration semantics. If the implementer
  confirms (via the green `mvn verify`) that a pure comment-only file is accepted,
  that is an acceptable simplification — the verify gate is the arbiter.
- The header comment is the load-bearing part: it must name commit `424ed48` and
  the M1-079a/V5 reason so a future auditor reads the explanation in place and
  moves on.
</content>
</invoke>
