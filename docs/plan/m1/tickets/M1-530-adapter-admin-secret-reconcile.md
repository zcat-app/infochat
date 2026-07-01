---
id: M1-530
title: "Wizard re-run leaves stale credentials on de-selection: bootstrap-admin (6-adapter.sh) + remote LLM api-key (4-llm.sh)"
status: done
created: 2026-06-30
last_updated: 2026-07-01
blocked_by: []
files_budget: 4
files_scope:
  - prod/scripts/6-adapter.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/AdapterAdminPromptWiringTest.java
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
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
  - "The `remote` branch of 4-llm.sh — M1-529 owns it (it clears the embeddings api-key and legitimately writes the generative api-keys). This ticket only adds cleanup to the `ollama`/`llamacpp` branches for the switch-AWAY-from-remote case; do not alter the remote branch's api-key writes."
  - "switch-llm.sh — a separate script for moving individual tasks between backends at runtime; its credential handling is out of scope here (M1-529 confirmed it never touches embeddings). This ticket is about the 4-llm.sh full-backend re-run only."
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
    Sibling fix in 4-llm.sh (M1-529 redteam out-of-model item): when a re-run
    switches the LLM backend AWAY from `remote` to a local backend, the `ollama`
    and `llamacpp` branches remove the now-stale remote-LLM credentials. In each
    of those two branches, before/at config-write, delete any
    `infochat.llm.<task>.api-key` and `infochat.embeddings.api-key` lines from the
    generated application.properties (local backends carry no api-key) AND delete
    `INFOCHAT_LLM_API_KEY` from secrets.env (referenced by nothing once remote is
    de-selected — mirrors this ticket's adapter-admin removal and the data-dir
    reconcile). This does NOT touch the `remote` branch (M1-529 already clears the
    embeddings api-key there and legitimately writes the generative api-keys).
  - >-
    A wiring test in infochat-llm-adapter (extend LlamacppWiringTest, mirroring
    its drive-the-real-4-llm.sh pattern) covers the switch-away cleanup: seed a
    runtime application.properties + secrets.env as a prior `remote` run left them
    (six `infochat.llm.<task>.api-key=${INFOCHAT_LLM_API_KEY}` lines,
    `infochat.embeddings.api-key`, and `INFOCHAT_LLM_API_KEY="..."` in
    secrets.env), run 4-llm.sh choosing a local backend, then assert the generated
    config contains NO `infochat.llm.*.api-key` / `infochat.embeddings.api-key`
    lines and secrets.env contains NO `INFOCHAT_LLM_API_KEY`.
  - >-
    `mvn -B -pl infochat-provider -am verify` exits 0; AdapterAdminPromptWiringTest
    passes (the original 3 tests plus the new de-selection test), the
    infochat-llm-adapter wiring tests pass (LlamacppWiringTest with the new
    switch-away case, plus SwitchLlmWiringTest / RemoteLlmWiringTest unchanged),
    and the full repo-root `mvn verify` reports no regressions.
test_plan:
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/AdapterAdminPromptWiringTest.java — add the adapter de-selection cleanup @Test."
    - "infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — add the switch-away-from-remote api-key cleanup @Test (drive 4-llm.sh over a seeded prior-remote config)."
  preserves:
    - all wizard wiring tests currently green on main
spec_refs:
  - "docs/spec/security.md §Per-adapter admin threat profile"
  - "docs/spec/deployment.md §Operator inputs"
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 116
      removed: 12
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-01
    verdict: CLEAN
    base: 6cfc541c5304e3b5539d978649ba810d8fb42d75
    head: working-tree (uncommitted branch tip m1/M1-530-adapter-admin-secret-reconcile)
    verdict_file: docs/plan/m1/redteam/M1-530-2026-07-01.md
    out_of_model_count: 2
    note: |
      Pre-commit audit (security_relevant). No in-model findings. Two out-of-model
      observations: (1) the whole diff is operator-tier trusted wizard tooling
      outside the threat model's adversary reach; (2) a still-selected SimpleX
      admin token is deliberately preserved by collect_admin skip-if-set (M1-530
      out_of_scope) and could re-arm admin after /revoke-admin — already tracked as
      accepted future work in security.md §Per-adapter admin threat profile, so no
      new ticket filed.
clarity_check:
  date: 2026-07-01
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk: low under-calibrated — ticket deletes bootstrap-admin secrets (INFOCHAT_SIMPLEX_ADMIN_TOKEN, INFOCHAT_SIGNAL_ADMIN_CONTACT_ID) and LLM API keys (INFOCHAT_LLM_API_KEY) from secrets.env; consider risk: medium."
  blockers: []
---

# M1-530: Reconcile stale wizard credentials on de-selection (adapter admin + remote LLM api-key)

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

**Sibling case in `4-llm.sh` (folded in per user, from the M1-529 redteam
out-of-model item, 2026-07-01).** The exact same "stale credential on
de-selection re-run" shape exists for the LLM backend: the `remote` branch
writes `infochat.llm.<task>.api-key=${INFOCHAT_LLM_API_KEY}` (six tasks) plus
`INFOCHAT_LLM_API_KEY` into `secrets.env`, but the `ollama` and `llamacpp`
branches only ever *append* their own config — they never delete those remote
credentials. So a run that chose `remote` and a later run that switches to
`ollama`/`llamacpp` leaves the api-key property lines pointed at a now-local
endpoint (semantically wrong, contradicts the local-only privacy posture) and
the `INFOCHAT_LLM_API_KEY` secret at rest, referenced by nothing. This is the
LLM-backend twin of the adapter-admin reconcile above; M1-529 already fixed the
narrower embeddings-api-key case *inside the remote branch* but not the
switch-away-from-remote direction.

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
- For the `4-llm.sh` sibling: in the `ollama` and `llamacpp` branches, clear the
  stale remote-LLM credentials before/at config-write — e.g.
  `sed -i -e '/^infochat\.llm\..*\.api-key=/d' -e '/^infochat\.embeddings\.api-key=/d' "$CONFIG_FILE"`
  and `sed -i '/^INFOCHAT_LLM_API_KEY=/d' "$SECRETS_FILE"`. `$CONFIG_FILE` exists
  (checked at script top) and `$SECRETS_FILE` is `touch`-created; both `sed`s are
  no-ops on a fresh run with no prior remote credentials. Reuse the existing
  `set_prop`/`set_secret` idempotency idiom where it fits. M1-529's remote-branch
  `sed` that clears `infochat.embeddings.api-key` is the reference pattern.
- The `4-llm.sh` test can extend `LlamacppWiringTest` (it already drives the real
  `4-llm.sh`): pre-write a runtime `application.properties`/`secrets.env` as a
  prior `remote` run would, then drive the local branch and assert the stale
  api-keys are gone.
