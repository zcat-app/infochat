---
id: M1-130
title: "ReadyPromoter transaction boundary + IT driven through tick()"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the new_post NOTIFY payload shape (covered by the CT2 / M1-134 NOTIFY work only where it overlaps; here just the same-tx guarantee)
  - other @Scheduled beans
acceptance:
  - "The UPDATE and pg_notify('new_post') in promoteOne run in one transaction on the production path (either promoteOne moves to a separate injected bean called through the CDI proxy, or the transaction is managed explicitly)"
  - "The integration test drives tick() (the production entry point) rather than calling promoteOne directly through the proxy, and asserts the UPDATE + NOTIFY are atomic"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 83
      removed: 58
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-130: ReadyPromoter transaction boundary + IT driven through tick()

## Context

`ReadyPromoter.tick()` (`@Scheduled`, `:113`) calls `promoteOne(...)`
unqualified (`:124`); `promoteOne` is `@Transactional` (`:143-144`). CDI
interceptors don't fire on self-invocation, so the UPDATE and the
`pg_notify('new_post')` (`:179`) run as two separate auto-commits — voiding the
documented same-transaction NOTIFY guarantee. The IT masks it by calling
`promoteOne` through the proxy.

## Acceptance

See frontmatter. Move `promoteOne` to a separate injected bean (call through the
proxy) OR manage the transaction explicitly (`setAutoCommit(false)` + commit).
Update the IT to drive `tick()`, not the proxy method directly.

## Out-of-scope

See frontmatter. The IT edit changes the entry point it drives (CT6: tests must
exercise the production path) — that is the authorized test change.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A15 (READY-PROMOTER-TX, High, GROUNDED);
  `opus-47-full-handout.md` §F-MAINT-07.
- Today `promoteOne` has one write + one NOTIFY, so non-atomicity is observable
  only on a crash in the gap — but any future second mutation inherits silent
  non-atomicity.
