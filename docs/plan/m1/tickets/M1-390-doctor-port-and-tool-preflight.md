---
id: M1-390
title: "0-doctor.sh: drop the unpublished 8080/8081 port checks (the app services bind no host ports); add a tool-presence preflight (openssl, ss, curl, df)"
status: done
created: 2026-06-16
last_updated: 2026-06-17
blocked_by: []
clarity_check:
  date: 2026-06-17
  verdict: PASS
  warnings: []
  blockers: []
files_budget: 1
files_scope:
  - prod/scripts/0-doctor.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Docker-daemon / Compose-v2 / Linux-host / disk-space checks — unchanged.
  - Adding new preflight categories beyond tool presence and the port-list correction.
acceptance:
  - "REQUIRED_PORTS lists only the host-published port(s) the prod stack actually binds: 5432 (published to 127.0.0.1 by the postgres service). 8080 and 8081 are removed because the infochat-collector / infochat-provider compose services declare no `ports:` mapping (they are reached over the compose network and in-container loopback only). `grep -E '8080|8081' prod/scripts/0-doctor.sh` matches nothing."
  - "0-doctor.sh fails with a named message naming the missing tool if any of openssl, ss, curl, df is absent from PATH, and this check runs before the port loop (which depends on ss)."
  - "prod/scripts/0-doctor.sh passes `bash -n`; on a host with the four tools present and port 5432 free, it exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 30
      removed: 10
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-390: doctor — fix the port checks and add a tool preflight

## Context

Re-verified at source 2026-06-16.

- **Over-checked ports.** `0-doctor.sh` requires host TCP `5432 8080 8081`
  free. But in `docker-compose.yml` the `infochat-collector` and
  `infochat-provider` services have **no `ports:` key** — only `EXPOSE` in the
  Dockerfiles, which is documentation. They are never published to the host;
  they talk over the compose network and bind `/q/health` to in-container
  loopback. Only postgres publishes a host port (`127.0.0.1:5432`). So a host
  that happens to run something on 8080/8081 (common) fails doctor for no
  reason.
- **No tool preflight.** `port_in_use` runs `ss -ltnH ... 2>/dev/null | grep -q
  .`, so a missing `ss` is silently treated as "port free" (false pass).
  `2-secrets.sh` relies on `openssl`, `8-verify.sh` on `curl`, the disk check on
  `df` — none are checked up front.

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-390-*.md
```
