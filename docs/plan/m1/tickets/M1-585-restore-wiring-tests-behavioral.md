---
id: M1-585
title: "RestoreWiringTest: behavioral pins for persisted-SHA recovery, role-before-restore order, and mount shape"
status: done
created: 2026-07-06
last_updated: 2026-07-07
blocked_by: [M1-580, M1-581, M1-584]
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - prod/scripts/restore.sh
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any restore.sh behavior change. M1-580/581/584 own those; restore.sh is in
    files_scope only for the case a stable marker line is needed for an
    assertion — production behavior is byte-identical.
  - >-
    A pack.sh test harness. pack.sh remains host-validated (M1-567 posture);
    this ticket strengthens the RESTORE pins only.
  - >-
    Enforcing mount CONFINEMENT in the fake docker (refusing writes outside
    the mounted set). The fake re-execs host tar; write-enforcement stays
    HOST validation and is documented as such — this ticket pins the mount
    SHAPE.
acceptance:
  - >-
    Dropping or emptying the `"$persisted_sha"` argument of the custom-GGUF
    recovery call fails the build: the test pins the full three-argument
    `fetch_gguf "$persisted_url" "$file" "$persisted_sha"` invocation (today
    only the `fetch_gguf "$persisted_url"` PREFIX is asserted, and no test
    anywhere references persisted_sha — verified by repo-wide grep — so the
    restore-side SHA verification could be silently deleted with mvn verify
    green).
  - >-
    The M1-570 ordering invariant gains a behavioral pin: a drive-past-gates
    run's fake-docker argv log shows the infochat_admin DO-block psql
    invocation strictly BEFORE the pg_restore invocation (today's pin is
    `indexOf` over the script SOURCE — it catches deletion/reordering but not
    a functionally broken step; the source-order check may stay as a fast
    pre-check). The fake docker echoes an identifying line per exec'd
    in-container command (extending the existing "FAKE-DOCKER:" pattern).
  - >-
    The fake docker RECORDS `-v` mount specs instead of discarding them
    (`-v) shift 2` today), and the extraction test asserts the recorded mount
    set equals exactly the allowlisted data-dir set — so regressing the
    M1-569 mount scoping to no mounts or a `-v /:/host` widening fails the
    build. A test comment states that mount ENFORCEMENT (writes actually
    confined to the mounts) is host-validated, per the M1-569 ticket posture.
  - "`mvn verify` is green from the repo root; restore.sh behavior unchanged."
test_plan:
  adds:
    - "RestoreWiringTest — 3-arg fetch_gguf pin; argv-order role-before-pg_restore; mount-set equality."
  modifies:
    - "RestoreWiringTest fake docker — log -v specs and exec'd commands."
  preserves:
    - "Every existing gate/extraction test; the file's ProcessBuilder execute-the-real-script standard."
spec_refs:
  - "docs/design/07-deployment.md §7.10.1"
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 102
      removed: 15
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-07-07
  verdict: WARN
  warnings:
    - >-
      COMPLEXITY-RISK-CALIBRATED: risk low may undersell the domain pinned
      (restore-ordering data integrity, GGUF SHA verification); test-only scope
      is the counter-argument keeping it low.
    - >-
      TEST-CHANGES-AUTHORIZED: test_plan.adds lists the fetch_gguf 3-arg pin as
      an addition, but acceptance item 1 describes it as tightening a
      pre-existing prefix-only assertion (arguably a .modifies entry).
  blockers: []
---

# M1-585: behavioral pins for the restore wiring tests

## Context

MEDIUM finding of the 2026-07-06 audit of M1-567..576. RestoreWiringTest's
pre-existing standard is to EXECUTE restore.sh via ProcessBuilder with a fake
docker; the M1-570 and M1-571 additions dropped to source-text greps below
that bar (the M1-571 acceptance even claimed parity with "the way
RestoreWiringTest already pins restore's other gated steps"). Three concrete
regression classes currently keep the build green: deleting the SHA argument
from custom-GGUF recovery, functionally breaking (not removing) the role
step, and dropping or widening the M1-569 mounts (the fake discards `-v` args
and re-execs host tar, so the extraction test passes purely on the M1-568
named-member allowlist).

Falsified before filing: the harness demonstrably supports the needed shape —
the M1-568 drive-past-gates case already runs the real extraction end-to-end,
and the fake docker already parses argv and emits "FAKE-DOCKER:" marker lines.

## Notes

- blocked_by M1-580/581/584: they rework the same file and script regions;
  landing this last avoids three rebases of the same assertions. The
  acceptance is independent of their outcomes.
- Audit provenance: finding M7 of the 2026-07-06 audit (memory
  `audit-567-576-open-findings`).
