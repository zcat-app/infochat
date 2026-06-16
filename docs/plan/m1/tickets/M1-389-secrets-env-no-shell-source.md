---
id: M1-389
title: "wizard: stop sourcing secrets.env as a shell script — operator-pasted values (SimpleX queue addresses with #/&, API keys, tokens) truncate or inject; feed compose via --env-file"
status: done
created: 2026-06-16
last_updated: 2026-06-17
blocked_by: []
clarity_check:
  date: 2026-06-17
  verdict: PASS
  warnings: []
  blockers: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-17
    verdict: CLEAN
    base: main
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-389-2026-06-17.md
    out_of_model_count: 3
    note: |
      In-progress audit of the secrets-env-no-shell-source implementation
      (prod/scripts/* + setup.sh, uncommitted working tree). No threat-model
      gaps: the change moves secrets from shell `source` to compose's --env-file
      dotenv parser, which closes (not opens) an injection surface. 3 out-of-model
      advisories recorded in the verdict file; advisory only, not blockers.
files_budget: 8
files_scope:
  - prod/setup.sh
  - prod/scripts/2-secrets.sh
  - prod/scripts/3-postgres.sh
  - prod/scripts/4-llm.sh
  - prod/scripts/6-adapter.sh
  - prod/scripts/7-apps.sh
  - prod/scripts/8-verify.sh
  - docs/design/07-deployment.md
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The set of secrets stored (DB passwords, LLM key, per-adapter admin ids) — unchanged; only the mechanism by which they reach docker compose changes.
  - secrets.env file permissions (0600 umask + chmod) — already correct, not touched.
acceptance:
  - "prod/setup.sh no longer sources secrets.env into its shell environment: `grep -nE '(^|\\s)(\\.|source)\\s+\"?\\$\\{?SECRETS' prod/setup.sh` matches nothing and there is no `set -a` around a secrets read. The load_secrets function (or its replacement) does not evaluate the file as shell."
  - "Every `docker compose` invocation that needs the secrets (in setup.sh do_reset and in 3-postgres.sh / 4-llm.sh / 7-apps.sh / 8-verify.sh) passes them via `docker compose --env-file \"$SECRETS_FILE\" ...` (or an equivalent non-shell mechanism), so the compose ${INFOCHAT_*} interpolations and the provider environment passthroughs still resolve."
  - "2-secrets.sh, 6-adapter.sh, and 4-llm.sh write each value quoted (KEY=\"value\") so a value containing '#' or whitespace survives the env-file parse."
  - "Regression check: with INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID set to a value containing '#' and the substring '$(touch /tmp/inj)', a `prod/setup.sh` dry path (or a unit of load logic) does NOT create /tmp/inj and the full untruncated value is what `docker compose --profile prod config` resolves into the provider environment."
  - "All seven scripts pass `bash -n`; `docker compose --profile prod config` exits 0; `mvn -B verify` from the repo root exits 0."
  - "docs/design/07-deployment.md §7.7.2 'Runtime config delivery to the containers' (the `secrets.env` → process environment bullet) is updated so the prose describes the new mechanism — each subscript passes `docker compose --env-file \"$SECRETS_FILE\"` so compose's own dotenv parser populates the `${INFOCHAT_*}` interpolations — and no longer states the orchestrator sources `secrets.env` into its environment. (Round-1 reviewer SPEC-CONFORMANCE-CHECK: FAIL; design must move in lockstep with the code.)"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.3 Configuration sources and precedence
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-17
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 9
      added: 64
      removed: 46
  - round: 2
    date: 2026-06-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 134
      removed: 51
escalations:
  - date: 2026-06-17
    reason: budget-breach
    reviewer_verdict_excerpt: |
      SPEC-CONFORMANCE-CHECK: FAIL — docs/design/07-deployment.md §7.7.2,
      the "secrets.env → process environment" bullet (line 666), states "The
      orchestrator sources the runtime secrets.env into its own environment
      before running the steps, so every subscript's docker compose up resolves
      the compose file's ${INFOCHAT_*} interpolations." That is the exact
      mechanism this ticket abolishes; the design prose must move in lockstep,
      but docs/design/07-deployment.md is outside files_scope and the
      files_budget of 7.
revisions:
  - date: 2026-06-17
    reason: |
      budget-breach refine (round 1 rework): the round-1 reviewer's sole REWORK
      item requires updating docs/design/07-deployment.md §7.7.2 in lockstep with
      the code, but that file was outside scope. Added it to files_scope, raised
      files_budget 7 → 8, and added acceptance item #6 for the design-prose
      update. No other criteria changed.
    snapshot:
      files_budget: 7
      files_scope:
        - prod/setup.sh
        - prod/scripts/2-secrets.sh
        - prod/scripts/3-postgres.sh
        - prod/scripts/4-llm.sh
        - prod/scripts/6-adapter.sh
        - prod/scripts/7-apps.sh
        - prod/scripts/8-verify.sh
      acceptance_items: 5
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-389: secrets.env must not be sourced as a shell script

## Context

Re-verified at source 2026-06-16. `setup.sh` load_secrets evaluates the secrets
file as a shell script:

```
load_secrets() {
  [[ -f "$SECRETS_FILE" ]] || return 0
  set -a
  . "$SECRETS_FILE"      # setup.sh:79
  set +a
}
```

The file holds operator-pasted values written **unquoted** (`printf
'%s=%s\\n' "$key" "$val"`). The SimpleX bootstrap-admin contact id is a queue
address / contact link containing `#`, `&`, `?`, `+`, `=`
(`docs/design/06-messaging.md:494-496` — "SimpleX queue address", contact links
/ `xftp://` URIs). When sourced as shell:

- `#` starts a comment → the value is silently **truncated**, so the configured
  bootstrap admin id is wrong; `AdminBootstrap.validateContactId` then rejects
  it and refuses Provider startup;
- `&`, `;`, `` ` ``, `$(...)` → **arbitrary command execution at wizard
  runtime** (the orchestrator runs with the operator's privileges).

The same exposure applies to `INFOCHAT_LLM_API_KEY` and (until M1-387 removes
it) `SIMPLEX_SESSION_TOKEN`.

The sourcing is load-bearing only because compose's `${INFOCHAT_*:-}`
interpolations and the provider `environment:` passthroughs need the values in
the environment. `docker compose --env-file "$SECRETS_FILE"` feeds exactly those
from compose's own dotenv parser, which does **not** evaluate shell — removing
the need to source at all.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- compose's `--env-file` populates the interpolation environment; verify
  ordering (the flag must precede the subcommand or follow compose's documented
  position) so `${VAR}` in the compose file still resolves.
- This is independent of M1-387: even after the phantom session-token is gone,
  the SimpleX admin contact id (with `#`) still flows through secrets.env.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-389-*.md
```

## Round 1 rework

Reviewer verdict round 1: REWORK (SPEC-CONFORMANCE-CHECK: FAIL). One item:

1. Update `docs/design/07-deployment.md` §7.7.2 "Runtime config delivery to the
   containers" (the `secrets.env` → process environment bullet at line 666) so
   the prose matches the new mechanism: the orchestrator no longer **sources**
   `secrets.env` into its own environment — each subscript passes
   `docker compose --env-file "$SECRETS_FILE" ...` so compose's own dotenv parser
   populates the `${INFOCHAT_*}` interpolations, and operator-pasted values
   (SimpleX queue ids with `#` / `&`, API keys) cannot truncate or execute as
   shell. Verified at source: line 666 currently reads "The orchestrator sources
   the runtime `secrets.env` into its own environment …" — the exact mechanism
   this ticket abolishes.

This item requires editing `docs/design/07-deployment.md`, which is outside the
current `files_scope` (the 7 prod scripts) and the `files_budget` of 7. Per the
"never silently expand files_scope/files_budget" rule and the out-of-scope
immediate-escalation trigger, the fix needs `escalate → refine` to add that file
to `files_scope` and raise `files_budget` to 8 before the design edit is made.
