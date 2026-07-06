---
id: M1-580
title: "restore.sh fails loud on real pg_restore errors (stderr gate, not just table-presence)"
status: done
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 3
files_scope:
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Passing --exit-on-error or aborting on the two known-ignorable extension
    COMMENT notices. The tolerance for those exists for a reason (postgres-init
    pre-creates vector/pgcrypto, so the dump's COMMENT statements fail
    ownership); this ticket BOUNDS the tolerance, it does not remove it.
  - >-
    ACL/grant-correctness post-checks beyond error-line matching. The M1-570
    redteam advisory's grant-verification post-check idea (a future second
    Flyway-created role) stays a separate follow-up if ever prioritized.
  - "pack.sh, the dump format, and the bundle layout. Unchanged."
acceptance:
  - >-
    restore.sh captures pg_restore's stderr (tee'd to the console as today —
    the operator keeps seeing it live). On non-zero pg_restore exit, restore
    FAILS unless every captured error line matches the enumerable ignorable
    set — currently exactly the "must be owner of extension
    pgcrypto"/"…vector" COMMENT notices the 2026-07-05 live round-trip
    produced. The count of ignored errors and the ignored lines themselves are
    always printed (never silence), replacing today's behavior where exit 1 +
    "errors ignored on restore: N" still yields "DB restored (schema
    present)." whenever at least one table landed.
  - >-
    A genuine pg_restore failure (disk full mid-data-load, invalid data, a
    failed index build) aborts BEFORE image build and bring-up, with a message
    naming the failing lines, that the clone is INCOMPLETE, and the
    partial-state recovery pointer (§7.10.1).
  - >-
    The existing "at least one table present" \dt check is retained as a
    backstop (it catches an empty restore with exit 0), but is no longer the
    sole success criterion.
  - >-
    RestoreWiringTest drives past the precondition gates with the existing
    fake-docker harness and pins BOTH sides behaviorally: (a) fake pg_restore
    exiting 1 with a non-ignorable stderr line → restore.sh exits non-zero and
    the argv log shows no image-build/bring-up step ran; (b) fake pg_restore
    exiting 1 with ONLY the two ignorable notices → restore proceeds past the
    DB step (pins that the proven live round-trip shape still passes).
  - >-
    docs/design/07-deployment.md §7.10.1 documents the bounded tolerance: which
    notices are expected, why, and that anything else fails the restore.
  - "`mvn verify` is green from the repo root."
test_plan:
  adds:
    - "RestoreWiringTest — pg_restore-exit-1 + non-ignorable stderr → fail-before-bring-up; exit-1 + only ignorable notices → proceed."
  modifies:
    - "prod/scripts/restore.sh — pg_restore stderr capture + bounded error-line gate."
  preserves:
    - "All existing gate tests; the ignorable-notice tolerance; the \\dt backstop; pg_restore-before-Flyway ordering (M1-570 pin)."
spec_refs:
  - "docs/design/07-deployment.md §7.10.1"
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 227
      removed: 18
escalations: []
overrides: []
revisions:
  - date: 2026-07-06
    reason: clarity-fail rework (bounded self-refine via /m1-tick run) — drop the unresolvable spec_refs entry "docs/spec/deployment.md §Runtime"
    prior_values: |
      spec_refs: 2 entries ("docs/design/07-deployment.md §7.10.1",
        "docs/spec/deployment.md §Runtime")
      (Clarity pre-flight FAIL, 1 blocker: docs/spec/deployment.md has no
       heading matching "Runtime" — headings are Topology; Operator inputs;
       Bootstrap behavior on startup; Configuration surface (spec level);
       Health and observability; Backups, rotation, secrets; Local
       development; Deployment scenarios; What lives in design notes. Entry
       dropped rather than re-pointed: §Backups, rotation, secrets explicitly
       delegates backup/restore tooling to design notes, so no spec-level
       heading states this ticket's contract, and the clarity
       SELF-CONTAINED-CHECK confirmed the ticket body does not depend on the
       ref. The resolving design ref §7.10.1 remains.)
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-06
    verdict: CLEAN
    base: a6815fc693783566fad4be319a52d26378e76fe6
    head: "m1/M1-580-restoresh-fails-loud-on-real-p (working tree, pre-commit)"
    verdict_file: docs/plan/m1/redteam/M1-580-2026-07-06.md
    out_of_model_count: 3
    note: |
      In-progress audit (post-APPROVE, pre-commit) per the unattended batch
      flow. CLEAN. Three out-of-model advisories: (1) tee write-error could
      truncate the stderr capture and mask residue errors (bounded — the
      later image build fails loudly on a full disk; possible one-line
      PIPESTATUS[1] hardening as a follow-up); (2) ANSI-escape passthrough
      to the operator terminal via raw pg_restore stderr (pre-existing, not
      widened by the diff); (3) the stderr capture can briefly hold
      DB-derived content at rest in the 0700 staging tree (same class as
      the staged secrets.env; removed by the EXIT trap). None auto-filed.
clarity_check:
  date: 2026-07-06
  verdict: PASS
  warnings:
    - >-
      test_plan.modifies lists only prod/scripts/restore.sh; the two new
      RestoreWiringTest behavioral pins are filed under test_plan.adds even
      though RestoreWiringTest is a pre-existing file being extended, not
      created. Informational labeling nit only — the authorization content
      itself (acceptance item 4 + test_plan.adds) is already explicit and
      sufficient. (Same nit the ticket's own prior clarity_check recorded.)
---

# M1-580: restore.sh fails loud on real pg_restore errors

## Context

The 2026-07-06 audit of M1-567..576 found (HIGH) that restore.sh's DB step
declares success on a partially-failed restore: pg_restore runs under `set +e`,
its exit status is captured (`restore_status`), and the only gate is a
non-empty `\dt` — "at least one table exists". Custom-format pg_restore
continues past real errors by default, prints "errors ignored on restore: N",
and exits 1; the script then prints "DB restored (schema present)." and brings
the stack up on a clone silently missing rows/tables/indexes. 8-verify.sh
checks HTTP health only, so a half-restore passes end-to-end.

This is the same masking that hid the M1-570 grants rollback — that one was
caught only because the Collector happened to crash on a heartbeat write. The
M1-570 redteam recorded the ACL sub-case as an out-of-model advisory; the
audit's falsification showed the hole is broader than ACLs (any data/index
error), and it sits in the one tool whose whole job is a faithful clone.

## Approach

- Capture stderr to a file while tee-ing to the console. On
  `restore_status != 0`, filter the error lines against the fixed ignorable
  patterns; any residue → print it and `exit 1` before `compose build`.
- The in-container pg_restore comes from the pinned `pgvector/pgvector:pg16`
  image, so message strings are stable; run the filter under `LC_ALL=C` if
  locale ever matters.
- With M1-570's role pre-creation in place, the expected-error universe on a
  healthy restore is exactly the two extension COMMENT notices — small and
  enumerable, which is what makes this gate tractable now.

## Notes

- Coordinate with M1-581/M1-584 (also touch restore.sh) — run serially.
- Audit provenance: finding H2 of the 2026-07-06 audit (memory
  `audit-567-576-open-findings`).
