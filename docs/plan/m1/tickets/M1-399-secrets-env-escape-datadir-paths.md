---
id: M1-399
title: "wizard: route the two operator-typed adapter data-dir writes to secrets.env through dotenv_escape (6-adapter.sh), consistent with M1-397"
status: done
created: 2026-06-19
last_updated: 2026-06-19
clarity_check:
  date: 2026-06-19
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: [M1-397]
remediates: M1-391
files_budget: 1
files_scope:
  - prod/scripts/6-adapter.sh
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The three writes M1-397 already hardened (INFOCHAT_LLM_API_KEY in 2-secrets.sh and 4-llm.sh, the bootstrap-admin id in collect_admin) — already escaped; this ticket only extends the same encoding to the two data-dir writes.
  - The dotenv_escape helper itself (introduced into 6-adapter.sh by M1-397) — reused verbatim, not modified.
  - The application.properties data-dir writes (infochat.adapters.<name>.data-dir, written to $CONFIG_FILE) — those are read by Quarkus's properties parser, not compose's dotenv parser; their escaping rules differ and are out of scope here.
  - docker-compose.yml, the --env-file delivery mechanism, and secrets.env permissions (0600) — unchanged.
acceptance:
  - "The two operator-typed adapter data-dir writes to secrets.env in 6-adapter.sh — INFOCHAT_SIMPLEX_DATA_DIR / INFOCHAT_SIGNAL_DATA_DIR (~lines 230-231) — encode the value through the same dotenv_escape helper M1-397 added, so a path containing any of `\"` `\\` `$` `#` or whitespace round-trips byte-for-byte through compose's dotenv parser. The raw `$simplex_data_dir` / `$signal_data_dir` is no longer interpolated into the `KEY=\"...\"` printf without escaping. (Source: M1-397 redteam out-of-model advisory 3 — these two writes use the identical unescaped printf the three M1-397 sites used, but were added later by M1-391 and so fell outside M1-397's acceptance.)"
  - "Regression check: drive the script write path with a data-dir of the literal value `/data/a\"b$d${INFOCHAT_DB_PASSWORD}c#e f\\g`, then run `docker compose --profile prod config`; the resolved INFOCHAT_SIMPLEX_DATA_DIR equals that exact string — not truncated at `#`, not field-broken at `\"`, with `${INFOCHAT_DB_PASSWORD}` left literal (not expanded) and the backslash preserved."
  - "Negative control: an ordinary data-dir path (e.g. /var/lib/infochat/simplex) resolves unchanged from the current behavior (escaping is a no-op on a path with no special characters)."
  - "prod/scripts/6-adapter.sh passes `bash -n`; `docker compose --profile prod config` exits 0; `mvn -B verify` from the repo root exits 0."
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
    date: 2026-06-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 12
      removed: 9
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-19
    verdict: CLEAN
    base: 68f552a44207bdd92cbef4e0ca8ec1112704abbf
    head: m1/M1-399-secrets-env-escape-datadir-paths
    verdict_file: docs/plan/m1/redteam/M1-399-2026-06-19.md
    out_of_model_count: 1
    note: |
      In-progress audit between APPROVE (round 1) and commit. CLEAN, 0 findings.
      One out-of-model advisory: the same operator-typed values are also written
      un-escaped to application.properties (6-adapter.sh:246/254) via the Quarkus
      properties parser — already documented in this ticket's out_of_scope as a
      distinct parser with distinct escaping rules, so not a gap here.
---

# M1-399: escape the operator-typed adapter data-dir writes to secrets.env

## Context

M1-391 made the Provider container bind-mount each adapter's data-dir at the
operator-configured path. To make a custom data-dir reach compose as the mount
source, M1-391 began writing the operator-typed `INFOCHAT_SIMPLEX_DATA_DIR` /
`INFOCHAT_SIGNAL_DATA_DIR` values into `secrets.env` (the only `--env-file` the
orchestrator passes to compose), double-quoted for the dotenv parser:

```sh
simplex) printf 'INFOCHAT_SIMPLEX_DATA_DIR="%s"\n' "$simplex_data_dir" >> "$SECRETS_FILE" ;;
signal)  printf 'INFOCHAT_SIGNAL_DATA_DIR="%s"\n'  "$signal_data_dir"  >> "$SECRETS_FILE" ;;
```

These two writes interpolate an operator-typed value into a double-quoted
`KEY="value"` printf **without escaping** — the exact pattern M1-397 hardened at
its three sites (the API key ×2 and the bootstrap-admin id). M1-397's
`--in-progress` red-team (`docs/plan/m1/redteam/M1-397-2026-06-19.md`,
out-of-model advisory 3) flagged the inconsistency: a data-dir path containing
`"`, `$`, `${...}`, or `\` could prematurely close the quoted field or be
interpolated by compose's dotenv parser, mounting the identity material at the
wrong path. The advisory was out of M1-397's `files_scope` / acceptance (these
writes came from M1-391, after M1-397 was drafted from the M1-389 red-team), so
the fix lands here.

Like M1-397, this is **accident-resistance, not a security fix** — `secrets.env`
content comes only from operator-typed config, which
`docs/spec/security.md §Threat model` places inside the trust boundary. No
in-model adversary can influence it. `security_relevant: true` is carried for
the same conservative posture as M1-397 (the code writes the compose `--env-file`
plumbing, so an extra red-team pass over the diff is cheap insurance).

The fix is a one-helper reuse: M1-397 already added `dotenv_escape` to
`6-adapter.sh`, so the two data-dir writes become:

```sh
simplex) printf 'INFOCHAT_SIMPLEX_DATA_DIR="%s"\n' "$(dotenv_escape "$simplex_data_dir")" >> "$SECRETS_FILE" ;;
signal)  printf 'INFOCHAT_SIGNAL_DATA_DIR="%s"\n'  "$(dotenv_escape "$signal_data_dir")"  >> "$SECRETS_FILE" ;;
```

No new helper, no new file — `blocked_by: M1-397` because the helper this ticket
reuses lands with M1-397.

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-399-*.md
```
