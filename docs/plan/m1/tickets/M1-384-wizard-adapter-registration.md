---
id: M1-384
title: "wizard 6c: 6-adapter.sh — SimpleX/Signal registration capture + bootstrap-admin union"
status: done
created: 2026-06-15
last_updated: 2026-06-15
clarity_check:
  date: 2026-06-15
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 1: 'commit-message evidence' is an informal, not machine-checkable gate; acceptable for an interactive wizard script but the reviewer cannot mechanically verify it."
    - "ACCEPTANCE-RUNNABLE item 3: no explicit verification method stated for the application.properties output content check."
    - "SELF-CONTAINED-CHECK: acceptance item 3 delegates to §7.4 for the specific property key list without inlining it; implementer must read §7.4 to know the exact keys to write."
  blockers: []
blocked_by:
  - M1-382
files_budget: 1
files_scope:
  - prod/scripts/6-adapter.sh
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-384.md
out_of_scope:
  - The orchestrator/steps 0-2 (M1-382), postgres/llm/bootstrap (M1-383), apps-up/verify (M1-385).
  - Any change to the messaging adapter Java code or its bot-identity derivation (06-messaging.md) — this subscript only drives the out-of-band client registration and captures its on-disk output into the runtime config.
  - Shipping containers for simplex-cli / signal-cli — they remain out-of-band per §7.7 operator note.
acceptance:
  - "6-adapter.sh offers SimpleX and Signal and, for each chosen adapter, drives the out-of-band registration (SimpleX queue creation; Signal phone-number + captcha) and captures the resulting identity-dir (plus SIMPLEX_SESSION_TOKEN for SimpleX) into the runtime config and secrets.env (manual end-to-end for at least one adapter — commit-message evidence)."
  - "It collects a bootstrap-admin-contact-id for each configured adapter and refuses to proceed — non-zero exit with a message naming the constraint — when the union of admin contacts across the chosen adapters is empty, matching §7.6.3 (manual: run choosing an adapter but giving no admin contact exits non-zero)."
  - "It writes the infochat.adapters list and the per-adapter property blocks (§7.4) into the runtime application.properties; it does not write a bot contact-id property (that is derived by the adapter at startup, not operator-typed — §7.5)."
  - "The script obeys the §7.7.1 shape (set -euo pipefail, echo, exit-code passthrough, -h/--help) and passes `bash -n`. mvn -B verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/design/07-deployment.md §7.6.3
  - docs/spec/deployment.md §Operator inputs
decision_refs:
  - D46
reviews:
  - round: 1
    date: 2026-06-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 231
      removed: 7
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-15
    verdict: CLEAN
    base: 6758fcd7b6671051129fde2bf223dc6931be3153
    head: working-tree (m1/M1-384-wizard-adapter-registration, uncommitted)
    verdict_file: docs/plan/m1/redteam/M1-384-2026-06-15.md
    out_of_model_count: 1
    note: |
      In-progress audit after review APPROVE, before commit. No findings.
      One advisory out-of-model observation recorded in the verdict file;
      does not block commit. Adversary focused on the §7.6.3 admin-union
      gate and the secret/config split (session token to mode-0600
      secrets.env; bot contact-id never written, §7.5).
---

# M1-384: wizard 6c — adapter registration

## Context

The hardest wizard step (`07-deployment.md` §7.7.2 step 6). SimpleX queue
creation and Signal phone/captcha enrolment are inherently interactive and
out-of-band (§7.7 operator note); the wizard's contribution is to sequence
them, capture each client's on-disk identity material into the right
`identity-dir`, collect the per-adapter `bootstrap-admin-contact-id`, and
enforce the non-empty admin union the Provider requires to start (§7.6.3). It
writes `infochat.adapters` and the per-adapter property blocks into the runtime
config.

Blocked on the orchestrator/state machine (M1-382). security_relevant: it
captures bot identity material and the admin bootstrap — the union enforcement
is the load-bearing check.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — the human registration steps stay manual; this only drives
and captures them.

## Notes

- Do not write a per-adapter bot contact-id property: that value is derived
  from the adapter's own identity material at startup (§7.5), not operator-typed.
- The union check is global across the chosen adapters, mirroring the Provider's
  own startup gate (§7.6.3).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-384-*.md
```
