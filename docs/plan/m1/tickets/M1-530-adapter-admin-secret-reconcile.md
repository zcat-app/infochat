---
id: M1-530
title: "Stale bootstrap-admin credential left in secrets.env when an adapter is de-selected on re-run"
status: pending
created: 2026-06-30
last_updated: 2026-06-30
blocked_by: []
files_budget: 2
files_scope:
  - prod/scripts/6-adapter.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/AdapterAdminPromptWiringTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "The data-dir reconciliation (the `sed -i -e '/^INFOCHAT_SIMPLEX_DATA_DIR=/d' -e '/^INFOCHAT_SIGNAL_DATA_DIR=/d'` line) — it is already correct and is the pattern this ticket extends to admin creds; do not change its behavior."
  - "The collect_admin never-overwrite / skip-if-set idempotency for a STILL-CHOSEN adapter — a chosen adapter's existing admin must NOT be rotated or re-prompted. This ticket only removes creds for adapters NOT in the chosen set."
  - "Any change to the union gate, the per-adapter required/optional prompt logic, or the application.properties block writing."
  - "Rotating or changing an admin credential for a chosen adapter (that remains a documented hand-edit; see flaws.md F6)."
acceptance:
  - >-
    On every run, 6-adapter.sh removes the bootstrap-admin secret for any adapter
    NOT in the chosen set BEFORE the per-adapter loop: if simplex is not chosen,
    INFOCHAT_SIMPLEX_ADMIN_TOKEN is deleted from secrets.env; if signal is not
    chosen, INFOCHAT_SIGNAL_ADMIN_CONTACT_ID is deleted. The delete is SELECTIVE
    (only non-chosen adapters) — it must NOT be an unconditional delete-then-
    re-add of both vars, because that would defeat collect_admin's skip-if-set
    idempotency and re-prompt a chosen adapter's existing admin every run.
  - >-
    A chosen adapter's existing admin credential is preserved untouched: a re-run
    with the SAME adapter set and an already-set admin still prints
    "skip <KEY> (already set)" and does not re-prompt (collect_admin behavior
    unchanged).
  - >-
    AdapterAdminPromptWiringTest gains a @Test for de-selection cleanup: seed a
    secrets.env containing BOTH INFOCHAT_SIMPLEX_ADMIN_TOKEN and
    INFOCHAT_SIGNAL_ADMIN_CONTACT_ID, run 6-adapter.sh choosing only `signal`
    (supplying its admin), then assert the resulting secrets.env contains
    INFOCHAT_SIGNAL_ADMIN_CONTACT_ID but NO INFOCHAT_SIMPLEX_ADMIN_TOKEN line.
  - >-
    `mvn -B -pl infochat-provider -am verify` exits 0; AdapterAdminPromptWiringTest
    passes (the original 3 tests plus the new de-selection test), and the full
    repo-root `mvn verify` reports no regressions.
test_plan:
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/AdapterAdminPromptWiringTest.java — add the de-selection cleanup @Test."
  preserves:
    - all wizard wiring tests currently green on main
spec_refs:
  - "docs/spec/security.md §Per-adapter admin threat profile"
  - "docs/spec/deployment.md §Operator inputs"
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: ""
  verdict: ""
  warnings: []
  blockers: []
---

# M1-530: Reconcile bootstrap-admin secrets on adapter de-selection

## Context

Verified during the setup-wizard review (flaws.md F15). On a re-run,
`6-adapter.sh` reconciles the data-dir vars — line ~298 unconditionally deletes
BOTH `INFOCHAT_*_DATA_DIR` then re-adds only the chosen ones — but the bootstrap
admin credentials are only ever **appended** by `collect_admin`, never deleted.

So a run that enables `simplex` writes `INFOCHAT_SIMPLEX_ADMIN_TOKEN` (a secret
claim-token); a later run with `signal` only drops the simplex config block but
leaves that token in `secrets.env`, referenced by nothing. It is inert while
de-selected (no property references it), but: (a) inconsistent with the data-dir
handling, (b) a secret lingers at rest, and (c) if simplex is re-enabled later,
`collect_admin`'s skip-if-set silently REUSES the stale token instead of
prompting for a fresh one.

## Acceptance

See the YAML `acceptance:` list. In prose: before the per-adapter loop, delete
the admin secret of any adapter NOT in `chosen`; keep chosen adapters' creds
untouched (preserving the never-rotate idempotency); add a de-selection test.

## Out-of-scope

See the YAML `out_of_scope:` list. The load-bearing exclusion: the fix must be a
SELECTIVE delete (non-chosen adapters only). An unconditional delete-then-re-add
(the data-dir style) would break `collect_admin`'s skip-if-set idempotency and
re-prompt a chosen adapter's existing secret every run — a regression.

## Notes

- The natural insertion point is right after `chosen` is computed and before the
  per-adapter registration loop. For each of `{simplex, signal}` not present in
  `chosen`, `sed -i '/^INFOCHAT_<X>_ADMIN_...=/d' "$SECRETS_FILE"`.
- `secrets.env` is created with `touch` earlier in the script, so the sed target
  always exists.
