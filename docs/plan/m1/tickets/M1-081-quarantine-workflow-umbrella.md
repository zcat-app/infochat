---
id: M1-081
title: Quarantine admin workflow + re-evaluation pipeline umbrella
status: done
created: 2026-05-25
last_updated: 2026-05-26
blocked_by:
  - M1-081a
  - M1-081b
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to M1-081a's V21 migration, re-eval job, NOTIFY emitter, tagger partial-valid fix, TTL job, or per-source tracker — that commit is FROZEN at its review round
  - any change to M1-081b's QuarantineCommandHandler, AuditCommandHandler, QuarantineReviewListener, or QuarantineReviewReconciler — FROZEN
  - any change under infochat-core/src/main/resources/db/migration/ — M1-081a's V21 is the only migration; this umbrella adds no schema
  - any change under infochat-collector/src/main/ — all Collector implementation is M1-081a
  - any modification to any pre-existing test in infochat-provider/src/test/, infochat-collector/src/test/, or infochat-core/src/test/ — every prior test continues to pass unchanged
  - any cross-source linking (D6), entity extraction, or post_reference logic — standalone future ticket
  - any embedding model identity guard changes — already implemented
  - any group-scope logic — T2-F; quarantine admin commands are DM-only (bot admin)
acceptance:
  - "infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java exists, ends with *IT suffix so maven-failsafe-plugin runs it under mvn verify, and contains at least one @Test annotation"
  - "The IT is a @QuarkusTest with an inline @TestProfile setting infochat.adapters=inmemory"
  - "Step (a) — quarantine fixture visible in list: pre-seed a PENDING quarantine row (with original_html and a placeholder in the post body) via JDBC; bot-admin issues /quarantine list through the InMemoryAdapter; reply includes the quarantine id, post uid, flagged_by, and rule_id"
  - "Step (b) — approve restores body: bot-admin issues /quarantine approve <id>; the stored procedure runs; SELECT quarantine.status returns 'APPROVED'; SELECT post.body returns the original span (placeholder replaced); post.status is 'READY'"
  - "Step (c) — new_post NOTIFY re-render: after approve, the Provider's NewPostListener picks up the NOTIFY new_post cursor and would re-render the post (the IT verifies the cursor advanced past the approved post's ready_at)"
  - "Step (d) — reject leaves placeholder: pre-seed a second PENDING quarantine row; bot-admin issues /quarantine reject <id>; quarantine.status is 'REJECTED'; post body still contains the placeholder"
  - "Step (e) — list --all shows all statuses: bot-admin issues /quarantine list --all; reply includes both the APPROVED and REJECTED rows from steps (b) and (d)"
  - "Step (f) — audit shows quarantine actions: bot-admin issues /audit --action QUARANTINE_APPROVE; reply includes the approve action from step (b) with masked contact ids per audit_log_view redaction"
  - "Step (g) — non-admin rejection: a non-admin user issues /quarantine list; reply is the error.admin_only message"
  - "Step (h) — non-admin audit rejection: a non-admin user issues /audit; reply is the error.admin_only message"
  - "mvn -B clean verify from the repo root exits 0; QuarantineWorkflowIT runs under failsafe with no failures"
  - "Every prior test continues to pass"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java
  preserves:
    - every test currently green on main
    - every test added by M1-081a and M1-081b
spec_refs:
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/security.md §Re-evaluation job
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/llm.md §Failure handling (recap)
decision_refs:
  - D9
  - D22
  - D34

reviews:
  - round: 1
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 414
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-05-26
    verdict: CLEAN
    base: main
    head: m1/M1-081-quarantine-workflow-umbrella
    verdict_file: docs/plan/m1/redteam/M1-081-2026-05-26.md
    out_of_model_count: 0
    note: |
      Test-only umbrella diff. Production security surface verified
      end-to-end; no gap between threat model and delivery.
clarity_check:
  date: 2026-05-26
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-081: Quarantine admin workflow + re-evaluation pipeline umbrella

## Context

Umbrella commit for the T2-G quarantine group (per
`docs/process/workflow.md` §Ticket-ID placeholder convention —
the umbrella + subticket idiom). M1-081a and M1-081b each ship a
slice of T2-G as its own reviewable commit on `main`:

- **M1-081a** — V21 migration (stored procedures + EXECUTE grants +
  provider_state row + re_eval_attempts column), ReEvaluationJob,
  PerSourceUnknownTracker, AdminReviewTtlJob, QuarantineNotifyEmitter,
  tagger partial-valid fix, Stage2VerdictHandler NOTIFY integration.
- **M1-081b** — QuarantineCommandHandler (/quarantine
  list|approve|reject), AuditCommandHandler (/audit),
  QuarantineReviewListener, QuarantineReviewReconciler, bundle keys.

Each subticket's per-class tests verify its own slice. This
umbrella verifies the **cross-cutting** property the subtickets
cannot verify in isolation: **the full quarantine admin round-trip
— fixture seeding, /quarantine list, /quarantine approve
restoring the original span and firing NOTIFY new_post,
/quarantine reject leaving the placeholder, /audit surfacing the
actions, and permission gates — works end-to-end through the
InMemoryAdapter and stored procedures**.

`security_relevant: true` — every IT step pins a spec commitment
from §Quarantine workflow and §Admin commands. A regression
(approve not restoring the body, reject leaking original_html,
non-admin accessing quarantine commands) would be a security
defect.

## Acceptance

The IT walks eight steps covering the full quarantine admin
round-trip: list pending, approve with body restoration,
new_post cursor advance, reject with placeholder retention,
list --all, audit action visibility, and two non-admin
rejection gates. Each step is a named assertion in the
acceptance list above.

## Out-of-scope

- Changes to any subticket file — both subticket commits are
  frozen.
- Changes to migrations — V21 is M1-081a's commit.
- Collector-side implementation (re-eval, TTL, NOTIFY emit,
  tagger) — M1-081a.
- Cross-source linking (D6) — standalone future ticket.
- Embedding model identity guard — already implemented.
- Group scope — quarantine admin commands are DM-only (bot admin);
  group support is T2-F.
- Any modification to any pre-existing test.

## Notes

- The IT seeds quarantine rows and post rows via raw JDBC
  (PENDING status, original_html set, placeholder in post body)
  before driving admin commands through the InMemoryAdapter.
- The bot-admin row is seeded via JDBC — same pattern as M1-044's
  umbrella IT and M1-079's GroupLifecycleIT.
- The stored procedures (V21, M1-081a) execute within the test DB
  started by Quarkus DevServices — no external DB required.
- The IT does NOT test re-eval job cycling, TTL auto-reject, or
  NOTIFY emit — those are verified by M1-081a's unit/persistence
  tests. This IT tests the admin-facing round-trip only.
- The subticket commits are FROZEN at the umbrella round. If this
  IT exposes a defect, the fix is a NEW ticket — never an
  amendment to a passed commit.
