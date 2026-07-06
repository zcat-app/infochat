---
id: M1-582
title: "Single-owner cutover: stop-first pack guidance and a Provider-start gate in restore.sh"
status: done
created: 2026-07-06
last_updated: 2026-07-06
clarity_check:
  date: 2026-07-06
  verdict: WARN
  warnings:
    - >-
      risk: medium may be under-calibrated given the data-integrity stakes
      named in §7.10 itself ("unrecoverable on loss") — see
      COMPLEXITY-RISK-CALIBRATED.
    - >-
      §Context cites stale restore.sh line numbers (":473"/":485"; live file
      has provider-up at :608 and the SINGLE-OWNER banner at :646 after
      M1-580/M1-581 grew the script). The qualitative claim still holds; do
      not anchor edits on the stale numbers — use surrounding text/markers.
  blockers: []
blocked_by: []
files_budget: 5
files_scope:
  - prod/scripts/pack.sh
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - SETUP_GUIDE.md
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Cross-host locking. The M1-009 advisory lock is per-database by design;
    no network mutex between source and clone. The guard here is operator
    consent, not machine enforcement.
  - >-
    Refusing to pack a live source. Packing live stays possible (deliberate —
    e.g. periodic precaution bundles); pack.sh WARNS, it does not gate.
  - "What pack bundles, and the bundle layout. Unchanged."
acceptance:
  - >-
    SETUP_GUIDE's "safe to run while the bot is live" paragraph and pack.sh's
    "safe to run against a still-running deployment" header are corrected to
    one coherent story: pack never mutates the source, but a LIVE pack can tar
    the SimpleX/signal-cli identity stores mid-write, producing either a
    spurious tar failure ("file changed as we read it") or a torn
    SQLite/ratchet snapshot in the bundle — discovered only after cutover, on
    the unrecoverable identity. The documented order (stop the apps with
    apps.sh stop — Postgres stays up for pg_dump — then pack) becomes the one
    recommendation in both files, matching the stop-first order SETUP_GUIDE
    and §7.10.1 already state further down.
  - >-
    pack.sh detects a running provider container and prints a loud WARN naming
    the torn-snapshot risk (warn, not refuse).
  - >-
    restore.sh prints the single-owner invariant BEFORE `compose up -d
    infochat-provider` and gates the Provider start on operator consent: an
    interactive y/N prompt (default No, TTY-checked — the shred-bundle.sh
    consent precedent) or an explicit `--source-stopped` flag for unattended
    runs. Declining (or non-TTY without the flag) stops after the Collector
    with instructions to start the Provider manually once the source host is
    stopped. Today the Provider connects to the messaging identity at :473 and
    the SINGLE-OWNER banner scrolls past at :485 — after the corruption window
    already opened; the scripts' own comments call two live consumers
    session/ratchet corruption.
  - >-
    RestoreWiringTest: a non-TTY drive-past-gates run WITHOUT --source-stopped
    stops before the Provider start (fake-docker argv log contains no provider
    up) with the documented message; WITH --source-stopped the Provider start
    is reached. Collector bring-up is unaffected in both.
  - >-
    §7.10.1 and SETUP_GUIDE document the flag and the prompt, including that
    unattended/scripted restore runs (e.g. a recovery round-trip re-run) must
    pass --source-stopped.
  - "`mvn verify` is green from the repo root."
test_plan:
  adds:
    - "RestoreWiringTest — provider-start gate: refused without consent/flag, reached with --source-stopped."
  modifies:
    - "pack.sh — live-provider WARN + header comment; restore.sh — consent gate before provider up; SETUP_GUIDE + 07-deployment wording."
  preserves:
    - "Collector bring-up and all earlier restore steps; pack's read-only property; existing gate tests."
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
      files: 7
      added: 299
      removed: 41
escalations: []
overrides: []
revisions:
  - date: 2026-07-06
    reason: >-
      clarity-fail refine (bounded self-refine via /m1-tick run — acceptance
      item 5 referenced an artifact that exists nowhere in the repository)
    snapshot: |
      acceptance item 5 (verbatim, pre-refine):
        "§7.10.1 and SETUP_GUIDE document the flag and the prompt; the recovery
         round-trip convention (run-restore.sh) is updated to pass it."
      clarity blockers (2026-07-06):
        1. run-restore.sh exists nowhere in this repository (only trace:
           docs/plan/live-e2e/HANDOFF.md:153, which records an out-of-repo
           operator artifact at /home/infochat/recovery-test/run-restore.sh).
           The clause is not checkable against the diff.
        2. Corollary: files_scope / files_budget (5) cannot cover the
           referenced artifact.
      resolution: option (b) of the clarity verdict — drop the out-of-repo
      clause from acceptance; keep the in-repo documentation requirement
      (docs state unattended runs pass --source-stopped); record the operator
      follow-up in ticket Notes. files_scope / files_budget / out_of_scope
      unchanged.
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-06
    verdict: CLEAN
    base: bfe4c88b27f2b4cf2c00daf8a11b43087c81d837
    head: working tree (uncommitted pre-commit audit, branch m1/M1-582-single-owner-cutover-guard)
    verdict_file: docs/plan/m1/redteam/M1-582-2026-07-06.md
    out_of_model_count: 3
    note: >-
      Pre-commit --in-progress audit (run-orchestrated, after round-1 APPROVE).
      CLEAN — the consent gate, WARN, and doc changes deliver what the threat
      model promises. Three advisory out-of-model items (best-effort fail-silent
      WARN detection in pack.sh; consent-not-verification TOCTOU on the
      single-owner gate; no durable record of the consent decision) reported in
      chat; all sit outside the documented operator-trust boundary, none
      auto-filed.
---

# M1-582: single-owner cutover guard

## Context

MEDIUM finding of the 2026-07-06 audit of M1-567..576. The docs contradict
themselves — SETUP_GUIDE blesses a live pack in step 1 ("safe to run while the
bot is live") and then states the opposite order two paragraphs later ("stop
old → pack → copy → restore + verify → retire old"); pack.sh's header repeats
the blessing. Meanwhile restore.sh auto-starts the Provider before printing
the cutover warning. An operator following the docs literally — pack live,
restore on the new host — opens the two-live-owners window on the
UNRECOVERABLE messaging identity with zero tooling friction; the only guard is
a banner printed after the fact.

Falsified before filing: pg_dump is MVCC-consistent (Postgres stays up), so
stop-first costs nothing on the DB side; apps.sh stop stops only
collector/provider. The identity stores are live-written SQLite/session files
(the SimpleX `simplex_v1_*.db` store and the signal-cli data dir), where a
non-quiesced copy is a well-known torn-snapshot risk. The Collector holds no
messaging identity, so consent gates only the Provider start.

## Notes

- The consent-prompt + flag shape mirrors shred-bundle.sh (TTY check, default
  No), keeping one house pattern for destructive/irreversible steps.
- Audit provenance: finding M3 of the 2026-07-06 audit (memory
  `audit-567-576-open-findings`).
- The live-e2e recovery round-trip script (`run-restore.sh`) is an out-of-repo
  operator artifact at `/home/infochat/recovery-test/` (see
  docs/plan/live-e2e/HANDOFF.md §"SECRET HYGIENE"); updating it to pass
  `--source-stopped` on its next use is an operator follow-up after merge, not
  an acceptance criterion — nothing in-repo realizes that convention.
