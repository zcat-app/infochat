---
id: M1-134
title: "quarantine_review NOTIFY channel completeness (CT2)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 10
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - the new_price_snapshot orphan channel (covered by the M1-161 investigate-skeleton)
  - any change to the actionable quarantine review UX
acceptance:
  - "approve_quarantine and reject_quarantine fire pg_notify('quarantine_review', …) (currently they update status + audit but skip the channel) — via a CREATE OR REPLACE migration"
  - "PENDING NOTIFY is emitted at row insert (QuarantineDao.insert, same Stage-1 tx via RETURNING id), not deferred to Stage 2; emitQuarantineNotifyForPendingRows is removed so PENDING no longer re-fires per verdict and the BENIGN fast-path no longer skips PENDING"
  - "QuarantineNotifyEmitter takes closed enums (kind, status) instead of String, so the type system enforces the contract; the four call sites compile against the enums"
  - "The V21/V25 pg_notify payloads use jsonb_build_object(...)::text instead of raw || concatenation; the SECURITY DEFINER procedures re-add the spec-mandated actor_contact_id/actor_adapter denormalized columns"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/security.md §Quarantine workflow
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-134: quarantine_review NOTIFY channel completeness (CT2)

## Context

The `quarantine_review` channel has drifted from its spec contract (fires on
PENDING insert / BENIGN_CLOSED / APPROVED / REJECTED / NEEDS_REVIEW) in four
coordinated ways:

- **A20** — `approve_quarantine`/`reject_quarantine` (`V25:46-104`) update status
  + audit but emit no NOTIFY; the Provider cursor never advances for those
  transitions and the reconciler over-replays on restart.
- **A21** — Stage 2 `emitQuarantineNotifyForPendingRows` fires PENDING at the
  wrong stage: BENIGN never fires PENDING; INJECTION/MALWARE/UNKNOWN re-fires it.
- **B-EMITTER-ENUM** — `QuarantineNotifyEmitter` takes `String` kind/status and
  concatenates without escaping; the spec constrains both to closed enums.
- **C-NOTIFY-CONCAT + C-SECDEF-ACTOR-COLS** — V21/V25 build payloads by raw
  concat; the SECURITY DEFINER procedures dropped the actor-column denormalization.

## Acceptance

See frontmatter. One coordinated migration + DAO plumbing + emitter
enum-ification.

## Out-of-scope

See frontmatter. Migration version assigned at start (do not hardcode).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A20, §A21, §B-EMITTER-ENUM,
  §C-NOTIFY-CONCAT, §C-SECDEF-ACTOR-COLS; `opus-47-full-handout.md` §F-MAINT-08/09/10/29/30, CT2.
- Plan-writer pass recommended — migration + Stage-2 refactor + emitter SPI shape
  + DAO layering all touch the channel together.
