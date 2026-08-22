---
id: M1-907
title: 8-verify.sh config-freshness leg + boot-verify rule
status: pending
created: 2026-08-22
last_updated: 2026-08-22
flow: tick
reproduction: >-
  to-be-written VerifyWiringTest#staleRuntimeFileWarnsAndNamesServiceAndFile
  (child of a 2-ticket decomposition — analysis
  docs/plan/m1/tick-analysis/d17-restore-retention-and-bootverify.md; `start`
  converts the marker per workflow §0). Statically checkable absence (both
  greps run at analysis time, empty results verified): `grep -n
  'StartedAt\|mtime\|stat \|restart' prod/scripts/8-verify.sh` returns nothing
  — the verify step polls the CURRENTLY RUNNING processes' /q/health and has
  no config-freshness or boot check; `grep -in 'rewrite\|re-verify\|boot-verify\|config.*newer'
  docs/testing/USER_TEST_PLAN.md` hits only the unrelated stale-RAW reaper
  lines (:246-247) — no committed procedure encodes "a config rewrite is not
  done until a boot proves it". Observed campaign sequence (final report §3,
  /home/infochat/infochat/.scratch/V2.0.0-FIX-VERIFICATION-FINAL-REPORT-2026-08-22.md:78-83):
  B4b's boot-fatal JSON-lines rewrite of bootstrap-sources.json (01:01Z) went
  undetected until the next restart 11.5 h later (12:29Z crash-loop,
  RestartCount 16) — "the postflight never boot-verified".
analysis_ref: docs/plan/m1/tick-analysis/d17-restore-retention-and-bootverify.md
blocked_by: []
files_scope:
  - prod/scripts/8-verify.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/VerifyWiringTest.java
  - docs/testing/USER_TEST_PLAN.md
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    prod/scripts/restore.sh — sibling M1-906's surface, and already compliant
    by construction (it restarts everything and runs 8-verify.sh,
    restore.sh:965-967; its `cp -p` placement preserves old bundle mtimes, so
    it can never false-trip the freshness leg). This ticket never edits it.
  - >-
    tag-tree-cutover.sh and its postflight (P17 — M1-902's surface: a static
    DB/file predicate gate, NOT a generic stack postflight; its runbook
    already chains the 8-verify smoke at §7.14 step 9,
    docs/design/07-deployment.md:1721). No restart logic is added there.
    Probe: `git diff --name-only` shows no tag-tree-cutover.sh hunk.
  - >-
    Any auto-restart or mutating behavior in 8-verify.sh (P15 — the script
    observes; the operator owns cutover timing). Probe: `grep -c 'restart\|up
    -d' prod/scripts/8-verify.sh` is 0 post-change.
  - >-
    Any RED / exit-code change (P11 — the exit contract is non-zero iff a
    service never reaches UP, 8-verify.sh:175-181; restore.sh propagates
    non-zero as a cutover blocker, restore.sh:1006-1008). The leg adds WARN
    lines only.
  - >-
    App-side changes (health/readiness semantics, hot-reload of config),
    the wizard scripts (they write config before the first boot — compliant),
    and any second memory note on the claims-vs-sequence CLASS (already
    committed at .agents/memory/audit-claims-vs-sequences.md; this ticket is
    the mechanical step).
acceptance:
  - "VerifyWiringTest.staleRuntimeFileWarnsAndNamesServiceAndFile (the reproduction — new test class on the RestoreWiringTest fake-docker pattern, written and run RED at start: against the current script a stale bootstrap-sources.json produces NO staleness output) passes — a fixture runtime dir whose bootstrap-sources.json mtime is NEWER than the canned collector StartedAt yields: exit 0 (P11), one WARN line naming the file AND infochat-collector AND the restart-and-re-run guidance ('config is read at boot'), the health legs still running and the summary still printing, and `warn_count` suppressing the 'all components healthy.' line (8-verify.sh:175-178 mechanics)."
  - "VerifyWiringTest.freshRuntimeFilesPrintNoStalenessWarning passes — every fixture file's mtime OLDER than both canned StartedAts (the post-restore `cp -p` shape and the wizard's write-then-boot shape, P12) yields zero staleness WARNs and the intact 'all components healthy.' line (a mutation that always warns fails this)."
  - "Failure-mode (P13): VerifyWiringTest.inspectFailureDegradesToSkipNote passes — the fake docker made to fail `inspect` (or `compose ps -q`) yields a one-line skip note, exit 0, no fabricated staleness WARN, and the health summary unaffected (instrumentation failure must not impersonate a stale config or a down service — the health leg owns the not-UP signal, 8-verify.sh:104-107)."
  - "VerifyWiringTest.absentOptionalFileIsSkipped passes — a fixture runtime dir WITHOUT bootstrap-assets.json yields no error and no WARN for that file (absence is legitimate; the script already tolerates a missing CONFIG_FILE, 8-verify.sh:124-126)."
  - "Per-service mount map (P12): a stale bootstrap-assets.json warns against BOTH services and a stale bootstrap-sources.json warns against the Collector ONLY — matching the real mounts (docker-compose.yml:140/146/147 collector, :213/:218 provider; secrets.env via --env-file for both). Verified by the two wiring cases' named-service assertions."
  - "The rule lands in the live-test playbook: docs/testing/USER_TEST_PLAN.md gains the boot-verify rule — after ANY rewrite of a runtime config file (prod/runtime/application.properties, bootstrap-sources.json, bootstrap-assets.json, secrets.env) on a live stack, restart the affected service and re-run prod/scripts/8-verify.sh before declaring the step done, because these files are read at boot (docs/spec/deployment.md §Bootstrap behavior on startup) and a green health poll of the pre-rewrite process says nothing about the rewritten config; the note names 8-verify.sh's freshness leg as the detector. Verify: `grep -n 'newer than the last' docs/testing/USER_TEST_PLAN.md` hits the rule."
  - "The 8-verify contract row is synced: docs/design/07-deployment.md §7.7.2 step 8 (:802) gains one clause recording the config-freshness leg (WARNs when a mounted runtime file is newer than the service's last start; never fails). Verify: `grep -n 'newer than the last' docs/design/07-deployment.md` hits the step-8 row."
  - "mvn verify from repo root is green (engineering-rules §5). No pre-existing test is modified (8-verify.sh has none today — `grep -rn '8-verify' **/*.java` hits only RestoreWiringTest's pointer assertions, which this ticket does not touch)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/VerifyWiringTest.java — staleRuntimeFileWarnsAndNamesServiceAndFile, freshRuntimeFilesPrintNoStalenessWarning, inspectFailureDegradesToSkipNote, absentOptionalFileIsSkipped
  preserves:
    - all tests currently green on main
    - >-
      RestoreWiringTest (sibling M1-906's surface) — untouched here; the new
      VerifyWiringTest is a separate class so the two tickets share no test
      file.
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/deployment.md §Health and observability
  - docs/design/07-deployment.md §7.7.2
decision_refs:
  - D38
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-907: 8-verify.sh config-freshness leg + boot-verify rule

## Context

The v2.0.0 fix-verification campaign rewrote the live test stack's
`bootstrap-sources.json` at 01:01Z into a boot-fatal shape (the bootstrap
parser requires a top-level array) and detected nothing for 11.5 hours —
no restart occurred, the old process kept answering healthy, and the next
boot at 12:29Z crash-looped (RestartCount 16). The fixture was fixed in
place; the open lesson is verbatim "the postflight never boot-verified —
claims-vs-sequence class" (final report §3,
`.scratch/V2.0.0-FIX-VERIFICATION-FINAL-REPORT-2026-08-22.md:78-83). The
lesson CLASS is already recorded (.agents/memory/audit-claims-vs-sequences.md);
what is missing is the MECHANICAL step: after any config rewrite, boot the
affected service and verify ready/healthy before declaring the step done. The
analysis falsified the candidate surfaces: restore.sh already boot-verifies
(coordinate, don't poach — it is sibling M1-906's surface); the cutover
postflight is a static gate by design (M1-902 — do not conflate); the honest
home is BOTH 8-verify.sh (the detector) and the docs/testing playbook (the
imperative). A pure-doc checklist alone was weighed and rejected: the campaign
HAD procedures — claim-level checks without a boot detector are the failure
class itself. Analysis: `analysis_ref:`.

## Root cause

Fully proven. Runtime config is a boot-time input:
docs/spec/deployment.md §Bootstrap behavior on startup (:207-223) — the
Collector loads the bootstrap sources file at startup (D38); nothing
hot-reloads. 8-verify.sh (read in full) polls `/q/health` on the RUNNING
processes (:73-111) and has no freshness or restart check — the absence greps
in `reproduction:` return nothing. docs/testing/USER_TEST_PLAN.md (the
committed live-test playbook; the campaign procedure itself is untracked
`.scratch/`) carries no boot-verify-after-rewrite rule (absence grep in
`reproduction:`). So a green verify over a pre-rewrite process is a true
claim about the wrong state — undetectable by every existing check until the
next boot, which is exactly when the cost is highest.

## Pitfalls

Numbered per the analysis document; this ticket carries P11-P17.

- P11: WARN, never RED — 8-verify.sh's exit contract is non-zero iff a
  service never reaches UP (:175-181, header :13-14), and restore.sh
  propagates a non-zero verify exit as a cutover blocker
  (restore.sh:1006-1008). A RED freshness leg changes the contract and can
  block a legitimate cutover; the embedding probe is the WARN-only precedent
  (:116-122).
- P12: False-positive shapes must be GREEN by construction — restore.sh's
  `cp -p` preserves the bundle's old mtimes (restore.sh:451-457) and the
  wizard writes config before the first boot, so both legitimate flows have
  config OLDER than the container start. The per-service file map must match
  the real mounts (docker-compose.yml:140/146/147 collector, :213/:218
  provider; secrets.env reaches both via --env-file): a file a service never
  mounts must not warn against that service.
- P13: Degrade on instrumentation failure — `docker inspect` / `compose ps`
  failure or an absent container yields a one-line skip note, never a RED,
  never a fabricated WARN; the health leg already owns the not-UP signal
  (:104-107). The M1-822 P10 posture applied to a new script.
- P14: Time handling and test rot — StartedAt is RFC3339Nano; epoch via GNU
  `date -d`, mtime via `stat -c %Y` (GNU userland is already assumed —
  restore.sh:69's GNU-tar reliance); register every tool in the harness's
  restricted PATH (the M1-822 P9 REAL_TOOLS pattern); fixture mtimes are set
  relative to the CANNED StartedAt, never the test-run wall clock (M1-740).
- P15: Detection, never mutation — 8-verify.sh observes; it must not restart
  services itself. A verify step that mutates the stack it audits seizes
  cutover timing from the operator (the single-owner gate posture,
  restore.sh:918-955).
- P16: Cross-references name only real artifacts — the WARN text names the
  restart + re-run action and the spec anchor; the playbook rule names
  8-verify.sh's leg. No pointers to the untracked `.scratch/` campaign
  procedure (it rots; the M1-893 P8 trap).
- P17: Do not conflate with tag-tree-cutover.sh postflight (M1-902) — a
  static DB/file predicate gate by design; no restart logic is added there.
  Its runbook already chains the 8-verify smoke (§7.14 step 9,
  07-deployment.md:1721), which inherits the freshness leg unchanged.

## Approach

Derived from `spec_refs:` — §Bootstrap behavior on startup is the commitment
that makes a rewrite unverified until a boot (config is loaded at startup,
never hot-reloaded); §Health and observability (:402-429) defines the
readiness the verify asserts; design §7.7.2 step 8 (:802) is 8-verify.sh's
contract row, which the amendment syncs. D38 is the bootstrap-file-at-boot
lineage.

- **Files to touch** (plan, not allowlist): `files_scope` — 8-verify.sh (the
  freshness leg), NEW VerifyWiringTest.java (four cases),
  docs/testing/USER_TEST_PLAN.md (the rule), docs/design/07-deployment.md
  §7.7.2 step-8 row (one clause).
- **Steps in order** (each green before the next):
  1. The RED wiring test (workflow §0, P14): the new VerifyWiringTest on the
     RestoreWiringTest fake-docker pattern (ProcessBuilder drive, restricted
     PATH with REAL_TOOLS registration, FAKE-DOCKER argv echo with a
     distinguishing marker per exec) — canned UP health bodies, canned
     StartedAt via `inspect`, fixture runtime dir with `touch -d` mtimes
     relative to the canned StartedAt. The reproduction REDs against the
     current script (no staleness output exists).
  2. The freshness leg (P11, P12, P13): after the embedding probe, before
     the summary — for each service, `compose ps -q <service>` + `docker
     inspect --format '{{.State.StartedAt}}'`; for each present file of that
     service's mount map, `stat -c %Y` vs the StartedAt epoch (`date -d`);
     a newer file → one WARN line per file naming every affected service plus
     the restart-and-re-run guidance; inspect/ps failure or absent container
     → one-line skip note; exit code untouched; the WARNs flow through the
     existing `warn_count`/summary mechanics (:88, :120-122, :175-178).
  3. The usage/header text (P11, P16): one line each recording the leg and
     its WARN-only posture.
  4. The playbook rule (P16): USER_TEST_PLAN.md gains the boot-verify rule
     (the four named files, restart + re-run 8-verify.sh, the
     read-at-boot reason, the freshness leg as detector).
  5. The §7.7.2 step-8 row clause — last; it records the landed shape.
- **Controls to preserve (§10):** the exit contract (:175-181) — WARN lines
  only, so restore.sh's propagation (:1006-1008) sees no new failure mode;
  the health-poll behavior (:73-111) and the embedding probe's WARN-only
  degraded-mode posture (:116-167) untouched; the summary wording mechanics
  (:175-178) — a staleness WARN correctly suppresses "all components
  healthy." through the EXISTING warn_count path, which the fresh case pins;
  no pre-existing test modified (8-verify.sh has none; RestoreWiringTest is
  M1-906's surface).
- **Pitfall→mitigation:** P11→step 2's WARN-only wiring + the reproduction's
  exit-0 assertion; P12→the mount map + the fresh-files case; P13→step 2's
  skip note + the failure-mode case; P14→step 1's harness + canned-relative
  mtimes; P15→the out_of_scope grep probe; P16→steps 3-4's real-artifact
  references; P17→the out_of_scope boundary + git-diff probe.

## Definition of done

The reproduction passes (stale bootstrap-sources.json → exit 0, WARN naming
file + service + restart guidance, health summary intact, "all components
healthy." suppressed); the fresh-files case prints no staleness WARN and the
intact healthy line; the inspect-failure case degrades to a skip note with
exit 0; the absent-optional-file case is silent; the per-service mount map is
pinned by the named-service assertions; the USER_TEST_PLAN rule and the
§7.7.2 row clause land with their grep probes green; the script never
restarts anything and never newly fails; `mvn verify` green with no
pre-existing test touched.

## Verification

- P11 → VerifyWiringTest.staleRuntimeFileWarnsAndNamesServiceAndFile — exit 0
  on stale; a mutation exiting non-zero fails it (and would break restore.sh
  cutovers, restore.sh:1006-1008).
- P12 → VerifyWiringTest.freshRuntimeFilesPrintNoStalenessWarning (all mtimes
  older than both StartedAts → zero WARNs + intact healthy line) and the
  reproduction's per-service named-service assertions (acceptance item 5) —
  a wrong mount map fails one of the two.
- P13 → VerifyWiringTest.inspectFailureDegradesToSkipNote (failure-mode) —
  inspect made to fail; asserts the skip note, exit 0, no fabricated WARN.
- P14 → fixtures set mtimes relative to the CANNED StartedAt; a
  wall-clock-relative fixture is the M1-740 rot shape and fails review;
  missing-tool registration fails loudly under the restricted PATH.
- P15 → probe: `grep -c 'restart\|up -d' prod/scripts/8-verify.sh` is 0
  post-change.
- P16 → the WARN/rule texts name only 8-verify.sh, the restart action, and
  the spec anchor; probe: `grep -c '.scratch' prod/scripts/8-verify.sh
  docs/testing/USER_TEST_PLAN.md` is 0 for the new text.
- P17 → probe: `git diff --name-only` shows no tag-tree-cutover.sh hunk.
- acceptance item 4 → VerifyWiringTest.absentOptionalFileIsSkipped.
- acceptance items 6/7 → the named greps on USER_TEST_PLAN.md and
  07-deployment.md §7.7.2.
- acceptance item 8 → `mvn verify` from repo root (engineering-rules §5).
- Non-vacuity: a leg that warns on every file fails the fresh case; a leg
  that never warns fails the reproduction; a leg that REDs fails the exit-0
  assertion; a leg that warns a service for a file it never mounts fails the
  named-service assertions.

## Out-of-scope

Prose mirror of the YAML list. restore.sh is sibling M1-906's surface and is
already compliant by construction (full restart + 8-verify run,
restore.sh:965-967; `cp -p` keeps bundle mtimes old). tag-tree-cutover.sh's
postflight is M1-902's static gate — no restart logic there (P17). No
auto-restart anywhere: 8-verify.sh observes, the operator owns timing (P15).
No exit-code change: the leg is WARN-only (P11). No app-side change
(health/readiness semantics, config hot-reload). No wizard-script change
(they write config pre-boot — compliant). No second memory note on the
claims-vs-sequence class (already committed; this ticket is the mechanical
step). This ticket modifies NO pre-existing test.

## Census

Class: **committed stack procedures that rewrite runtime config, and the
verify surfaces that vouch for the result.** Re-runnable enumeration:
`grep -ln 'bootstrap-sources.json\|runtime/application.properties'
prod/scripts/*.sh` —
- Wizard writers (1-profile.sh, 4-llm.sh, 4b-image.sh, 5-bootstrap.sh,
  6-adapter.sh) — write `$CONFIG_FILE` BEFORE the first boot; step 8 verifies
  post-boot. Compliant by construction; untouched.
- `prod/scripts/restore.sh` — places config pre-boot (`cp -p`), restarts
  everything, runs 8-verify (:965-967). Compliant; M1-906's surface;
  untouched.
- `prod/scripts/tag-tree-cutover.sh` (reconcile-file rewrites
  bootstrap-sources.json) — a stop-first procedure whose runbook chains the
  8-verify smoke (§7.14 step 9, 07-deployment.md:1721), which inherits this
  ticket's leg. Compliant via the chain; untouched (P17).
- `prod/scripts/8-verify.sh` — THIS ticket's fix (the freshness leg).
- `prod/scripts/upgrade.sh` — its core function IS a rebuild + restart; an
  upgrade cannot declare done on a pre-rewrite process. Compliant by
  construction; untouched.
- docs/testing/ playbooks — the rule lands in USER_TEST_PLAN.md (this
  ticket); the campaign procedure itself is untracked `.scratch/` (nothing to
  edit; P16 forbids referencing it).
Note: `prod/switch-llm.sh` (referenced by M1-571's out-of-scope as a config
re-router) does NOT exist in the tree — verified absent at analysis; no row
needed.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-907`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-907-config-rewrite-boot-verify.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
