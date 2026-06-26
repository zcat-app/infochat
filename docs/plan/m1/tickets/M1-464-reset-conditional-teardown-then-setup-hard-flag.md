---
id: M1-464
title: "setup.sh --reset: only tear down when resources exist (no removal noise on a clean host), then fall through into setup; keep data by default, add --reset --hard to wipe data volumes"
status: pending
created: 2026-06-26
last_updated: 2026-06-26
blocked_by: []
files_budget: 3
files_scope:
  - prod/setup.sh
  - SETUP_GUIDE.md
  - docs/design/07-deployment.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "The wizard STEPS list and the per-step subscripts under prod/scripts/ — unchanged. Only do_reset, the top-level argument parsing, and usage() change in prod/setup.sh."
  - "The secrets.env / --env-file handling inside do_reset (M1-389) — unchanged: secrets are still fed to compose's own dotenv parser via --env-file, never shell-sourced."
  - "M1-463's four-profile list (--profile prod --profile ollama --profile llamacpp --profile llamacpp-embeddings) — KEPT verbatim on every compose down/​down -v invocation; this ticket does not narrow or re-derive it."
  - "What --defaults does inside the wizard steps — unchanged. This ticket only makes --defaults composable with --reset at the arg-parsing layer (so `--reset --defaults` cleans then runs the wizard non-interactively); it does not alter any subscript's --defaults handling."
  - "The bare-metal runtime (docs/design/07-deployment.md §7.8.1) and dev/scripts/down.sh — untouched; the §7.7.1/§7.7.2-overlap note that contrasts the wizard's `docker compose down` reset with dev/scripts/down.sh stays valid (the reset still uses `docker compose down` under the hood)."
  - "Any auto-removal of data WITHOUT the explicit --hard flag. The new default must NEVER drop a data volume; losing the database requires the operator to pass --hard (the repo's confirm-before-delete posture, now expressed as an explicit opt-in flag instead of an interactive prompt)."
acceptance:
  - "ARG PARSING: the top-level flag handling accepts any combination of `--reset`, `--hard`, and `--defaults` (plus `-h`/`--help`), not just a single leading flag. An unrecognised flag still prints usage to stderr and exits 2. `--hard` is only meaningful together with `--reset` (passing `--hard` alone is rejected, or is a no-op with a clear message — implementer's choice, but it must NOT silently wipe volumes without --reset)."
  - "RESET THEN SETUP: with `--reset`, after cleanup the script CONTINUES into the wizard (the STEPS loop runs) instead of `exit 0`. So a single `./prod/setup.sh --reset` cleans up and then runs setup; `./prod/setup.sh --reset --defaults` does the same non-interactively. `.setup-state` is cleared on --reset (so the wizard runs from the first step), and the `rm`/clear is silent when no state file exists."
  - "NO REMOVAL NOISE ON A CLEAN HOST: do_reset detects existing infochat project resources (containers and/or the project network; data volumes additionally for --hard) BEFORE acting. When none exist, it prints NO teardown/removal line, NO `+ docker compose ... down` echo, and NO prompt — it simply proceeds into setup. The `+ docker compose ... down` echo and any 'removed/​cleaned' summary appear only when something is actually torn down."
  - "DEFAULT KEEPS DATA, NO PROMPT: when project containers/network exist, `--reset` runs `docker compose down` (with M1-463's four profiles) to remove containers + the network, KEEPS all data volumes, and proceeds into setup. The interactive `Also drop data volumes (-v)? [y/N]` prompt is removed entirely — `grep -n 'Also drop data volumes' prod/setup.sh` returns no match, and there is no `read -rp` volume prompt in do_reset."
  - "--hard WIPES DATA, GATED + DETECTED: `--reset --hard` additionally drops the project's data volumes (`docker compose down -v` with the four profiles), but the volume-removal action/output is emitted only when a data volume actually exists (no `-v` noise on a host with no infochat volumes). Without --hard, no `down -v` is ever issued."
  - "usage()/--help is updated: the stale `--reset  docker compose down (offers -v to drop volumes) and clear state.` line is replaced to document the new behavior — `--reset` cleans up (keeping data) then runs setup, and `--hard` (with --reset) also wipes data volumes. The Usage synopsis line lists `--hard`."
  - "SETUP_GUIDE.md is updated: the reset instruction that currently reads 'It will offer to also delete the database (your stored posts). Say no ...' (≈ line 268) is replaced with the new behavior (plain `--reset` keeps your data and goes on to set up; `--reset --hard` is the way to also wipe the database), and the options-list line `./prod/setup.sh --reset   # docker compose down + clear wizard state` (≈ line 426) is updated to match (including a `--reset --hard` line). The troubleshooting rows that merely say 'run --reset to clean up a previous attempt' (≈ lines 404) stay valid and need no change."
  - "docs/design/07-deployment.md §7.7.2 is updated: the Reset behavior-contract bullet (≈ line 678, currently 'tears the deployment down (docker compose down, with -v to drop data volumes on explicit confirmation) ... prompting for confirmation before deleting any ... data volume') is rewritten to describe: detect-then-conditional teardown (no-op + no noise on a clean host), keep-data-by-default, the `--hard` opt-in for the data-volume wipe (replacing the interactive confirm), and that --reset now continues into the wizard. The earlier §7.7.1-overlap parenthetical that calls the reset `docker compose down` (≈ line 649) stays accurate and is left as-is."
  - "prod/setup.sh passes `bash -n`."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
---

# M1-464: --reset becomes detect-then-clean-then-setup; data wipe moves to an explicit --hard flag

## Context

Field feedback on `./prod/setup.sh --reset` (2026-06-26): the operator's actual
complaint is that the reset **prints removal/teardown output and asks the
`Also drop data volumes (-v)? [y/N]` question on every run, even when there is
nothing to remove**. Today `do_reset` unconditionally runs `docker compose down`
and unconditionally fires the volume prompt, then `exit 0` — so on a clean host
you get teardown chatter plus a `[y/N]` prompt about deleting a database that
may not exist, and the reset never flows into the setup it is meant to precede.

(M1-463 fixed an adjacent-but-different bug — the `llamacpp-embeddings` container
surviving the down. That profile fix is correct and is KEPT here; it was simply
not the operator's reported annoyance.)

Desired behavior, confirmed with the operator:

- `--reset` should **detect** what infochat resources exist and clean up only
  those — printing nothing about removal when there is nothing to remove — then
  **fall through into the setup wizard** (so reset = clean-if-needed then set up,
  fresh from step 0). `--reset --defaults` = clean then run setup non-interactively.
- The default **keeps the database** (data volumes are never dropped) and there
  is **no `[y/N]` prompt**.
- Wiping data becomes an **explicit opt-in**: `--reset --hard` drops the data
  volumes (option C, operator-chosen flag name `--hard`). The wipe only acts /
  prints when a data volume actually exists.

This replaces the repo's interactive confirm-before-delete on this one path with
an explicit-flag opt-in: strictly safer (no accidental `y`), and it removes the
fixed nag.

## Acceptance / Out-of-scope

See the YAML frontmatter. In short: conditional teardown with zero removal noise
on a clean host; `--reset` continues into the wizard; default keeps data and has
no prompt; `--reset --hard` wipes data volumes (gated + only when they exist);
usage(), SETUP_GUIDE.md, and the §7.7.2 design note updated to match; `bash -n`
green; the full suite stays green.

## Notes

- No shell test harness exists for the wizard subscripts (cf. M1-463, M1-395):
  `bash -n` plus diff inspection are the automated checks. The reviewer should
  weigh the arg-parsing rework (single-`case` → multi-flag loop) and the
  detect-before-act guards hardest, and confirm the `--hard`-only gate on every
  `down -v` path (the no-accidental-wipe invariant in out_of_scope).
- Keep M1-463's four-profile list intact on every `down` / `down -v` invocation;
  do not re-derive or narrow it.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-464-*.md
```
