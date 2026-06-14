---
id: M1-382
title: "wizard 6a: prod/setup.sh orchestrator + 0-doctor + 1-profile + 2-secrets"
status: pending
created: 2026-06-15
last_updated: 2026-06-15
blocked_by:
  - M1-378
files_budget: 6
files_scope:
  - prod/setup.sh
  - prod/scripts/0-doctor.sh
  - prod/scripts/1-profile.sh
  - prod/scripts/2-secrets.sh
  - prod/config/secrets.env.example
  - .gitignore
complexity: high
risk: low
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The postgres/llm/bootstrap subscripts (M1-383), the adapter subscript (M1-384), and the apps-up/verify subscripts (M1-385).
  - The dev/scripts inner-loop wrappers (separate, not part of the wizard).
  - Any application.properties or compose change — this ticket writes only the runtime config the wizard generates.
acceptance:
  - "prod/setup.sh exists, is executable, prints a numbered menu of the wizard steps, and supports --defaults, --reset, and -h/--help (`prod/setup.sh --help` exits 0 and lists the steps); it records completed steps in a git-ignored .setup-state file and resumes from the first incomplete step on re-run."
  - "0-doctor.sh checks Linux host, docker daemon reachable, Docker Compose v2, that TCP ports 5432/8080/8081 are free, and minimum free disk; it exits non-zero naming the first failed check (manual: run with one check failing — commit-message evidence)."
  - "1-profile.sh prompts for laptop|vps|pi|remote-llm with default laptop (empty input takes laptop) and records quarkus.profile into the runtime config."
  - "2-secrets.sh uses `openssl rand` to generate the three DB-role passwords and writes secrets.env with mode 0600 in the git-ignored runtime directory, skipping any value already present, and optionally prompts for an LLM API key (manual: created secrets.env is mode 600 — `stat -c %a` shows 600)."
  - ".gitignore contains entries that exclude the wizard runtime directory and any generated secrets.env so a stray `git add` cannot commit them (grep -E confirms the entries); prod/config/secrets.env.example is the committed template and contains no real secret."
  - "Every script obeys the §7.7.1 shape (set -euo pipefail, echoes its command, returns the wrapped exit code, has -h/--help) and passes `bash -n` syntax check. mvn -B verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/spec/deployment.md §Operator inputs
decision_refs:
  - D27
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-382: wizard 6a — orchestrator + doctor + profile + secrets

## Context

First slice of the first-run setup wizard (`07-deployment.md` §7.7.2). Builds
the menu/orchestrator `prod/setup.sh` (resumable via a git-ignored `.setup-state`
file, with `--defaults` and `--reset`) and steps 0–2: `0-doctor.sh` preflight,
`1-profile.sh` profile selection, `2-secrets.sh` DB-password generation into a
git-ignored `secrets.env` (mode 0600). Generated secrets live in the runtime
directory, never committed — `prod/config/` carries only the
`secrets.env.example` template (`07-deployment.md` §7.7 Repo-layout block).

`--reset` is plain `docker compose down` (with `-v` on explicit confirmation),
NOT the dev wrapper `down.sh` (§7.7.2 — the wizard runs containers, not
`quarkus:dev`). Blocked on M1-378 for the service-role password env contract
that `2-secrets.sh` writes.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — later steps land in M1-383/384/385.

## Notes

- Prefilled-default UX: every prompt shows its default in brackets; empty input
  takes the default (§7.7.2 Behavior contract).
- security_relevant because 2-secrets.sh mints and writes credentials; the
  0600 mode and the .gitignore coverage are the load-bearing checks.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-382-*.md
```
