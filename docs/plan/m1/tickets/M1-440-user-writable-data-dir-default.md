---
id: M1-440
title: "Default adapter identity data-dir to the wizard-owned runtime dir"
status: done
created: 2026-06-23
last_updated: 2026-06-24
blocked_by: []
files_budget: 5
files_scope:
  - prod/scripts/6-adapter.sh
  - SETUP_GUIDE.md
  - docs/design/07-deployment.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXProvisioningWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Friendly error when an operator OVERRIDES the default with an unwritable custom data-dir (a 6b/6-adapter UX nicety): deferred to a separate small ticket. This ticket only removes the blocker on the documented happy path."
  - "Switching identity storage to a named Docker volume: rejected. The bind mount is deliberate so the operator owns and can back up the identity material (backup.sh tars the data-dir); only the default PATH changes, the mount shape does not."
  - "Remapping the Provider container uid to avoid root-owned host files: a larger change, deferred. Container-root still writes root-owned identity files inside the (now user-owned) runtime dir; this is the pre-existing ownership wrinkle acknowledged at 6b-simplex-provision.sh:94-97, not introduced here."
  - "The docker-compose.yml fallback default literal (`${INFOCHAT_SIMPLEX_DATA_DIR:-/var/lib/infochat/simplex}`, :140-141) stays as-is: it is only reached by a bare `docker compose config` with no --env-file; the wizard always sets INFOCHAT_*_DATA_DIR in secrets.env, fed via --env-file in 7-apps.sh."
  - "Signal out-of-band signal-cli registration steps are unchanged (only the default account-dir constant moves symmetrically with SimpleX)."
acceptance:
  - "prod/scripts/6-adapter.sh DEFAULT_SIMPLEX_DATA_DIR is changed from `/var/lib/infochat/simplex` to a wizard-owned, user-writable path under the runtime dir (e.g. `$PROD_DIR/runtime/simplex`), so 6b-simplex-provision.sh:98 `mkdir -p \"$data_dir\"` succeeds as the non-root operator the wizard targets, with nothing for the operator to pre-create (the runtime dir is already created as the operator in step 2)."
  - "prod/scripts/6-adapter.sh DEFAULT_SIGNAL_DATA_DIR is changed symmetrically from `/var/lib/infochat/signal-cli` to `$PROD_DIR/runtime/signal-cli`."
  - "The new defaults are ABSOLUTE paths (PROD_DIR is resolved absolute at 6-adapter.sh:12), so the docker-compose.yml bind-mount source+target (:140-141, driven by INFOCHAT_*_DATA_DIR from secrets.env per M1-391) resolve correctly; no relative-path mount is introduced."
  - "SETUP_GUIDE.md is updated where it names the default data directory — the prose mention (line ~67) and the worked-example data-dir line (line ~242) — to reflect the runtime-relative default instead of /var/lib."
  - "docs/design/07-deployment.md notes the default adapter identity data-dir is the wizard-owned runtime dir (not /var/lib), with a one-line acknowledgment that container-root still writes root-owned files there (pre-existing; uid-mapping deferred)."
  - "SimpleXProvisioningWiringTest stays green: it seeds its own data-dir and drives the real 6b-simplex-provision.sh, so the 6-adapter.sh DEFAULT change is transparent to it. (Listed in files_scope defensively; expected untouched — verified the test reads data-dir from the seeded config, not the wizard default.)"
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
    - "SimpleXProvisioningWiringTest (drives the real 6b-simplex-provision.sh; the default-path change must not break it)"
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 26
      removed: 17
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-24
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-440: user-writable identity data-dir default

## Context

The 2026-06-23 setup audit found the wizard aborts at step 7 on its own
documented happy path. `6-adapter.sh:25,28` default the SimpleX/Signal
identity data-dirs to `/var/lib/infochat/{simplex,signal-cli}`, and the
README/SETUP_GUIDE tell the operator to press Enter for the default
(`SETUP_GUIDE.md:242`). Then `6b-simplex-provision.sh:98` runs
`mkdir -p "$data_dir"` **on the host as the wizard user** — and
`/var/lib` is root-owned `0755`, so a non-root operator (the wizard's
intended user; there is no sudo/EUID check anywhere, and the only
permission guidance is "add yourself to the docker group") cannot create
it. `set -euo pipefail` then aborts the wizard before any container
starts.

Operator-confirmed direction: do **not** make the operator pre-create
anything. The fix is to default the data-dirs to a path the wizard
already owns — under `prod/runtime/`, created as the operator in step 2 —
so `6b`'s own `mkdir -p` succeeds. This is the same family of defect as
the earlier GGUF download permission bug (M1-394 era), relocated from a
named-volume root to the host identity dir.

## Notes (verified 2026-06-23)

- No test hardcodes `/var/lib/infochat` (grep verified), so the default
  change breaks no pinned-default assertion.
- `SimpleXProvisioningWiringTest` drives the real `6b-simplex-provision.sh`
  but supplies its own writable temp data-dir via the seeded config, so
  the 6-adapter default change is transparent to it; it is kept green as
  proof that 6b still provisions correctly into a runtime-relative dir.
- The runtime dir already holds `secrets.env`, `application.properties`,
  and `.setup-state` (all `0600`/operator-owned), and `backup.sh` already
  archives the identity dir from the runtime area — so this is the
  natural home and keeps the bind-mount-owned-by-operator design intact.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-440-user-writable-data-dir-default.md
```
