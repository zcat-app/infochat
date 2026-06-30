---
id: M1-531
title: "Wizard step 5: directory given as a custom sources/assets path yields a raw cp error"
status: pending
created: 2026-06-30
last_updated: 2026-06-30
blocked_by: []
files_budget: 1
files_scope:
  - prod/scripts/5-bootstrap.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "JSON syntax / schema validation of the custom file (that is flaws.md F12 / a separate ticket); this ticket only fixes the file-type check."
  - "Any change to the bundled-default copy path, the self-heal logic, the asset enable/disable flow, or the basename/mount contract."
  - "The custom-path prompt wording (already clarified for absolute-vs-relative in the wizard review)."
acceptance:
  - >-
    Both custom-path readability checks in 5-bootstrap.sh (the sources path and
    the assets path) require a REGULAR FILE that is readable: the guard becomes
    `[[ -f "$path" && -r "$path" ]]` (not the current `[[ -r "$path" ]]`, which
    is true for a directory and then makes `cp` fail with the raw
    "omitting directory" error under set -e). grep -nE '\[\[ -f .* && -r ' or
    equivalent shows both call sites now test -f AND -r.
  - >-
    Giving a directory as a custom sources or assets path fails with the
    script's own clear "not readable" / "not a regular file" message and exits
    non-zero, NOT a raw `cp: -r not specified; omitting directory` error.
  - >-
    A normal readable regular file still copies exactly as before (no behavior
    change on the happy path); an unreadable regular file still fails with the
    existing clear message.
  - >-
    `mvn -B clean verify` from the repo root exits 0 (no wizard wiring test pins
    5-bootstrap.sh, so this is a no-regression check).
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/spec/deployment.md §Bootstrap sources"
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

# M1-531: Step 5 must reject a directory given as a custom path with a clear message

## Context

Verified during the setup-wizard review (flaws.md F13). The custom sources and
custom assets path handlers in `prod/scripts/5-bootstrap.sh` guard with
`[[ -r "$path" ]]`, which is TRUE for a readable directory. The subsequent
`cp "$path" "$RUNTIME"` (no `-r`) then fails with
`cp: -r not specified; omitting directory '<path>'` under `set -e` — safe (no
bad data is copied) but an unfriendly raw error instead of the script's own
clear failure message.

Falsification refined the fix: `[[ -f ]]` ALONE is wrong — it would lose the
readability check (a regular-but-unreadable file would pass `-f` then fail `cp`
on permissions). The correct guard tests BOTH: `[[ -f "$path" && -r "$path" ]]`.

## Acceptance

See the YAML `acceptance:` list. In prose: change both custom-path guards from
`[[ -r ]]` to `[[ -f && -r ]]` so a directory (or any non-regular file) fails
with the script's clear message, while a readable regular file copies unchanged.

## Out-of-scope

See the YAML `out_of_scope:` list. JSON validity is a separate concern (F12).

## Notes

- Two call sites: the sources path check and the assets path check. Keep the
  existing failure message wording; only the test expression changes.
