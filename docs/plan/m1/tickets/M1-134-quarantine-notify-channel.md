---
id: M1-134
title: "quarantine_review NOTIFY channel completeness (CT2)"
status: pending
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 11
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-collector/src/test/java/app/zcat/infochat/collector
  - infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - the new_price_snapshot orphan channel (covered by the M1-161 investigate-skeleton)
  - any change to the actionable quarantine review UX
acceptance:
  - "approve_quarantine and reject_quarantine fire pg_notify('quarantine_review', …) (currently they update status + audit but skip the channel) — via a CREATE OR REPLACE migration"
  - "PENDING NOTIFY is emitted at row insert (QuarantineDao.insert, same Stage-1 tx via RETURNING id), not deferred to Stage 2; emitQuarantineNotifyForPendingRows is removed so PENDING no longer re-fires per verdict and the BENIGN fast-path no longer skips PENDING"
  - "QuarantineNotifyEmitter takes closed enums (kind, status) instead of String, so the type system enforces the contract; the four call sites compile against the enums"
  - "The V21/V25 pg_notify payloads use jsonb_build_object(...)::text instead of raw || concatenation; the SECURITY DEFINER procedures re-add the spec-mandated actor_contact_id/actor_adapter denormalized columns"
  - "QuarantineWorkflowIT step (c) is updated for the now-live NOTIFY path: after the jsonb payload fix, to_jsonb(timestamptz) renders ISO-8601 and the @Startup NewPostListener processes the approve's NOTIFY, racing the IT's direct NewPostHandler.handle call against the strict-greater cursor CAS — the step must assert the cursor end-state (await-style polling) or tolerate the duplicate-CAS false instead of assertTrue(advanced)"
  - "The replacement migration carries forward V25's SET search_path pin and the actor-admin check verbatim in the re-created approve_quarantine/reject_quarantine bodies (CREATE OR REPLACE discards the prior body; dropping either silently widens the security boundary)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java — step (c) only: replace the direct-handle assertTrue(advanced) premise (built on the dead live-NOTIFY path) with a cursor end-state assertion, per acceptance item 6"
  preserves:
    - all tests currently green on main (QuarantineWorkflowIT step (c) modified per authorization above)
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/security.md §Quarantine workflow
decision_refs: []
reviews: {}
escalations:
  - date: 2026-06-05
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended

      REASON: Test-modification authorization missing — implementing
      acceptance item 4 cannot keep the pre-existing suite reliably green
      without modifying a test the ticket does not authorize. Converting
      approve_quarantine's new_post payload to jsonb_build_object(...)::text
      (acceptance item 4) un-breaks a currently-dead live NOTIFY path: today
      the payload's v_ready_at::TEXT renders in Postgres format (space
      separator, no T), which Instant.parse rejects, so NewPostListener drops
      every procedure-fired new_post event — and the pre-existing
      QuarantineWorkflowIT step (c) is explicitly built on that premise (its
      comment at lines 185-192: "V21's pg_notify payload uses
      TIMESTAMPTZ::TEXT ... which Instant.parse rejects, so the live NOTIFY
      path drops the payload"; it then drives NewPostHandler.handle directly
      and asserts assertTrue(advanced)). After the jsonb fix,
      to_jsonb(timestamptz) renders ISO-8601 with T (parseable on JDK >= 12),
      the always-on @Startup NewPostListener (active inside the @QuarkusTest
      app, 1000 ms-timeout blocking poll) processes the approve's NOTIFY
      within milliseconds of commit, and ProviderStateDao.advanceCursor is a
      strict-greater tuple CAS — so whenever the listener wins the
      millisecond-scale race against the IT's direct handle() call, that call
      returns false and the assertion fails. This makes mvn -B clean verify
      (acceptance item 5) and test_plan.preserves "all tests currently green
      on main" a coin-flip against acceptance item 4. The honest plan must
      update QuarantineWorkflowIT step (c) (e.g. assert cursor end-state with
      await-style polling, or tolerate the duplicate-CAS false), but the
      ticket body names no pre-existing test modifications, and
      infochat-provider/src/test is not even in files_scope, so the fix is
      doubly out of bounds. Everything else audits clean and the refined
      ticket is implementable as bundled: both spec_refs resolve uniquely
      (architecture.md:33 §Inter-service communication; security.md:738
      §Quarantine workflow); the four current emit( call sites are verified
      (Stage2VerdictHandler.java:257, 277; AdminReviewTtlJob.java:126;
      ReEvaluationJob.java:162); QuarantineDao.insert (QuarantineDao.java:
      66-90) is the sole INSERT INTO quarantine path and has no RETURNING id
      yet; users carries adapter/contact_id (V5:59-60) so the procedures can
      denormalize actor_contact_id/actor_adapter (spec mandate at
      schema.md:189-196) without a signature change, sparing the
      QuarantineCommandHandler call sites (SELECT approve_quarantine(?, ?)
      at lines 215, 255) and avoiding the CREATE-OR-REPLACE-overload trap;
      quarantine_review_view (V10:72-76) has no status filter, so the new
      APPROVED/REJECTED NOTIFYs resolve in
      QuarantineReviewListener.lookupEventTime and advance the cursor without
      admin noise (isActionable covers only PENDING/NEEDS_REVIEW). The
      refinement should authorize the QuarantineWorkflowIT step (c) update by
      name, add its path to files_scope, and (while in the frontmatter) fold
      in the two open clarity warnings (security_relevant: true, risk: high)
      plus a note that the new migration must copy forward V25's SET
      search_path pin and actor-admin check verbatim, since CREATE OR REPLACE
      discards the prior body.

      SUGGESTED ESCALATION: refine

      EVIDENCE: infochat-provider/src/test/java/app/zcat/infochat/provider/
      quarantine/QuarantineWorkflowIT.java:185-214 (step (c) premise comment
      + assertTrue(advanced)), versus ticket frontmatter files_scope (no
      provider test path) and ticket body §Out-of-scope/§Notes (no
      test-modification authorization). Supporting chain:
      NewPostListener.java:63 (@Startup), :95-98 (\s*:\s* regexes parse jsonb
      spacing), :343-354 (Instant.parse on the payload);
      NewPostHandler.java:97-125 (returns false on CAS no-op);
      ProviderStateDao.java:100-116 (strict < tuple CAS);
      V25__quarantine_procedure_remediation.sql:62-63 (the v_ready_at::TEXT
      concat payload acceptance item 4 replaces).
revisions:
  - date: 2026-06-05
    reason: outline-fail-refine (QuarantineWorkflowIT step (c) test-modification authorization + clarity-warning fold-in)
    snapshot: |
      files_budget: 10
      files_scope:
        - infochat-core/src/main/resources/db/migration
        - infochat-collector/src/main/java/app/zcat/infochat/collector
        - infochat-provider/src/main/java/app/zcat/infochat/provider
        - infochat-collector/src/test/java/app/zcat/infochat/collector
      risk: medium
      security_relevant: false
      test_plan:
        adds:
          - infochat-collector/src/test/java/app/zcat/infochat/collector
        preserves:
          - all tests currently green on main
      acceptance: 5 items (verbatim items 1-5 unchanged by this refine; refine
      ADDS items 6-7 — QuarantineWorkflowIT step (c) update authorization and
      the V25 SET search_path / actor-admin-check carry-forward requirement)
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
- 2026-06-05 refine (outline-fail): the jsonb payload fix un-breaks the live
  `new_post` NOTIFY path that `QuarantineWorkflowIT` step (c) was explicitly
  built on being dead (its comment cites the `TIMESTAMPTZ::TEXT` /
  `Instant.parse` mismatch, then drives `NewPostHandler.handle` directly and
  asserts `assertTrue(advanced)`). Acceptance item 6 authorizes updating that
  step by name; `files_scope` gains `QuarantineWorkflowIT.java` (exact file);
  acceptance item 7 pins the V25 `SET search_path` / actor-admin-check
  carry-forward. Clarity warnings folded in: `risk: high`,
  `security_relevant: true`.
