---
id: M1-397
title: "wizard: escape operator-pasted secret values so a literal quote / backslash / ${...} can't corrupt or interpolate the secrets.env entry"
status: done
created: 2026-06-17
last_updated: 2026-06-19
clarity_check:
  date: 2026-06-18
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: [M1-389]
remediates: M1-389
files_budget: 3
files_scope:
  - prod/scripts/2-secrets.sh
  - prod/scripts/4-llm.sh
  - prod/scripts/6-adapter.sh
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The openssl-generated passwords written by ensure_secret (2-secrets.sh) — hex output is provably free of special characters, so it needs no escaping.
  - The --env-file delivery mechanism and the set of secrets stored (M1-389) — unchanged; only the on-disk encoding of operator-pasted values is hardened.
  - secrets.env file permissions (0600) — unchanged.
  - docs/design/07-deployment.md prose — the §7.7.2 mechanism is unchanged (compose's dotenv parser still reads --env-file); only how a value is escaped on the way in changes. If the implementer finds the design asserts byte-for-byte fidelity that the prose should now spell out, escalate rather than silently widen scope.
acceptance:
  - "The three operator-pasted secret writes — INFOCHAT_LLM_API_KEY in 2-secrets.sh (the read -rsp path, ~line 70) and 4-llm.sh (~line 217), and the per-adapter bootstrap-admin id in 6-adapter.sh collect_admin (~line 101) — encode the value before writing it to secrets.env so that a value containing any of `\"` `\\` `$` `#` or whitespace round-trips byte-for-byte through compose's dotenv parser. The raw `$llm_key` / `$val` is no longer interpolated directly into the `KEY=\"...\"` printf without escaping. (Source: M1-389 redteam out-of-model advisories 1 & 2 — a literal `\"` could prematurely close the quoted field; a literal `${X}` could be interpolated by compose's dotenv parser on some versions.)"
  - "Regression check: write an admin contact id (or the LLM key) with the literal value `ab\"c$d${INFOCHAT_DB_PASSWORD}e#f g\\h` through the script's write path, then run `docker compose --profile prod config`; the value resolved into the provider environment equals that exact string — not truncated at `#`, not field-broken at `\"`, with `${INFOCHAT_DB_PASSWORD}` left literal (not expanded) and the backslash preserved."
  - "Negative control: with no special characters in the value, the resolved provider-environment value is unchanged from the M1-389 behavior (escaping is a no-op on ordinary hex / ASCII values)."
  - "All three scripts pass `bash -n`; `docker compose --profile prod config` exits 0; `mvn -B verify` from the repo root exits 0."
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
      files: 5
      added: 54
      removed: 11
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-19
    verdict: CLEAN
    base: 85bc10aa6c662de5f70bf13cedcbaa1ba8e249c4
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-397-2026-06-19.md
    out_of_model_count: 3
    note: |
      Adversarial pass over the working-tree diff. CLEAN — no in-model finding.
      The change only hardens on-disk encoding of operator-pasted secret values,
      which the threat model places inside the trust boundary; no
      attacker-reachable surface is touched. Three out-of-model advisories are
      recorded verbatim in the verdict file (advisory only, no action required
      for this ticket).
---

# M1-397: escape operator-pasted secret values in secrets.env

## Context

M1-389 stopped sourcing `secrets.env` as a shell script and switched every
`docker compose` invocation to `--env-file "$SECRETS_FILE"`, so compose's own
dotenv parser reads the values. M1-389 also began writing each value
double-quoted (`KEY="value"`) so a `#` or whitespace survives the parse.

The `--in-progress` red-team of M1-389 (`docs/plan/m1/redteam/M1-389-2026-06-17.md`)
returned **CLEAN** but flagged two out-of-model robustness residues in the
double-quoted encoding — both about operator-pasted values whose content the
wizard does not control byte-for-byte:

1. **Literal `"` in a pasted value** could prematurely close the quoted field
   under compose's dotenv parser, corrupting the bootstrap-admin id or API key.
2. **Literal `${OTHER}`** could be interpolated by compose's dotenv parser on
   some docker-compose versions, expanding to an unexpected value.

Both are **outside the documented threat model** — `secrets.env` content comes
only from operator-pasted config and `openssl`-generated hex, and
`docs/spec/security.md §Threat model` places operator-set config inside the
trust boundary. No in-model adversary (messaging-app user, feed publisher, LLM)
can influence `secrets.env`. This ticket is therefore **accident-resistance**,
not a security fix: it protects an operator who fat-fingers a value containing
`"`, `$`, `${...}`, or `\` from a silently-corrupted credential — it does **not**
close an attacker-reachable surface.

Even though the red-team classified the residue as out-of-model, this ticket
carries `security_relevant: true`: the code touches credential-writing plumbing,
so the conservative posture (an extra red-team pass over the diff) is cheap
insurance and is preferred here over the strict out-of-model classification.

The three affected write sites all interpolate an operator-pasted value into a
double-quoted printf:

- `2-secrets.sh:~70` — `INFOCHAT_LLM_API_KEY` (`read -rsp`)
- `4-llm.sh:~217` — `INFOCHAT_LLM_API_KEY` (`read -rsp`)
- `6-adapter.sh:~101` — per-adapter bootstrap-admin id (`read -rp`)

The `openssl rand -hex 24` passwords written by `ensure_secret` (2-secrets.sh)
are provably special-character-free and are out of scope.

A dotenv-literal encoding of an arbitrary value inside a double-quoted field is
`\` → `\\`, `"` → `\"`, `$` → `\$` (applied in that order); the implementer may
use any equivalent that makes compose's dotenv parser treat the value as a
literal and satisfies the regression check.

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-397-*.md
```
