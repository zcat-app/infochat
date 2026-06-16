---
id: M1-391
title: "wizard/compose: honor an operator-overridden adapter data-dir (the provider bind-mount is hardcoded to the default path)"
status: pending
created: 2026-06-16
last_updated: 2026-06-16
blocked_by:
  - M1-387
  - M1-388
files_budget: 2
files_scope:
  - docker-compose.yml
  - prod/scripts/6-adapter.sh
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The adapter config-key names (M1-387) and the in-image binaries (M1-388) — this ticket assumes those are correct and addresses only the host-path-to-container-mount alignment.
acceptance:
  - "With the operator accepting defaults, the infochat-provider container bind-mounts the simplex / signal data-dir at the same path written to infochat.adapters.<name>.data-dir: `docker compose --profile prod config` shows a mount whose target equals the configured data-dir."
  - "EITHER: when the operator supplies a custom data-dir, the wizard emits a compose-consumable value (into the --env-file or a generated compose override) so that path is the bind-mount source, and `docker compose --profile prod config` shows the mount source matching the configured data-dir; OR: 6-adapter.sh stops prompting for an overridable dir and uses/justifies the fixed documented path, so no prompt can produce a path that is not mounted."
  - "prod/scripts/6-adapter.sh passes `bash -n`; `docker compose --profile prod config` exits 0; `mvn -B verify` exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs:
  - D46
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-391: adapter data-dir mount must match the configured path

## Context

Re-verified at source 2026-06-16. `docker-compose.yml:137-138` hardcodes the
provider's adapter bind-mounts to `/var/lib/infochat/simplex` and
`/var/lib/infochat/signal-cli`, but `6-adapter.sh` prompts the operator for the
dir (`prompt_with_default "  SimpleX identity-dir" ...`). A non-default answer
writes a config value pointing at a path that is **not** mounted into the
container, so the adapter's `validate()` (data-dir must be an existing, writable
directory) fails. M1-386 explicitly deferred this: its Notes say supporting
operator-overridden paths "would need a wizard-emitted compose variable ...
escalate rather than silently widen."

Blocked on M1-387 (the key is `.data-dir`, not `.identity-dir`) and M1-388 (the
mount only matters once an adapter can launch).

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-391-*.md
```
