---
id: M1-832
title: "Full-stack lifecycle verb prod/scripts/stack.sh"
status: done
created: 2026-08-13
last_updated: 2026-08-15
flow: tick
reproduction: >-
  Probe (RED on main): `ls prod/scripts/stack.sh` — no such file
  (glob-verified: the only lifecycle verb is apps.sh, which covers only
  infochat-collector + infochat-provider, apps.sh:38, and its own header
  :10-14 points at bare `docker compose stop` or the `setup.sh --reset`
  teardown for the whole stack). Stopping or starting the FULL stack around
  a host reboot means hand-assembling four `--profile` flags + the comfyui
  overlay + `--env-file` (the only place that assembly exists is
  setup.sh's do_reset, :156-180); the 2026-08-13 incident (setup-hurdles.md
  item 13 addendum) is what hand-assembly gets wrong — the stack came back
  without `--profile ollama`.   Test:
  StackScriptWiringTest.fullStackStopAssemblesProfilesOverlaysAndEnvFile
  (written at start; RED on main verified — all five drives failed before
  stack.sh existed, workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/lifecycle-reboot-resilience.md
blocked_by: []
files_scope:
  - prod/scripts/stack.sh
  - prod/scripts/apps.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/StackScriptWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Changing apps.sh's app-only scope, command set, or compose() wrapper —
    the fast config-reload path stays (07-deployment.md:698); this ticket's
    only apps.sh touch is the one-line header pointer (analysis P6).
  - upgrade.sh (app-scoped rebuild/restart verb; not a full-stack path —
    censused), setup.sh do_reset (its teardown assembly is correct and
    untouched), 0-doctor.sh (M1-831), docker-compose.yml (M1-830).
  - Merging docker-compose.gpu.yml into any script — it is operator-manual
    by design and must never be merged unconditionally (analysis P5).
  - Any `docker compose down` / teardown semantics — stack.sh stop/start
    preserves containers and volumes, exactly like apps.sh stop.
  - The 2026-08-13 ollama/circuit-breaker surfacing addendum (batch E) and
    restore.sh (batch A).
acceptance:
  - "REPRODUCTION, now passing: StackScriptWiringTest.fullStackStopAssemblesProfilesOverlaysAndEnvFile   (new, infochat-provider wiring package; controlled-PATH fake-docker harness of DoctorWiringTest.java:161-194, fake records its argv) — `stack.sh stop` with a secrets.env present asserts the invocation carries `-f docker-compose.yml`, `-f docker-compose.comfyui.yml`, `--env-file <runtime>/secrets.env`, all four profiles (prod, ollama, llamacpp, llamacpp-embeddings), and the `stop` verb — and does NOT carry `-f docker-compose.gpu.yml` (FAILURE-MODE: dropping the comfyui overlay fails the test — that is the M1-395 leaves-comfyui-running trap setup.sh:174-176 disposes for teardown; adding the gpu overlay fails it — the GPU-less-host create break, analysis P5)."
  - "FAILURE-MODE (analysis P4): StackScriptWiringTest.startNeverRecreates — `stack.sh start` invokes `docker compose start` (resume existing stopped containers) and NEVER `up` — the argv contains `start` and no `up`; a bare `up -d` over the four-profile assembly would create BOTH LLM backends plus unprofiled comfyui regardless of the deployment's chosen D49 shape, and would recreate drifted containers."
  - "FAILURE-MODE: StackScriptWiringTest.startWithNoContainersFailsWithSetupPointer — fake `compose ps -aq` returns empty: `start` exits non-zero with guidance naming `./prod/setup.sh` (a stack that was never created cannot be resumed; the operator gets the setup pointer, not a cryptic compose error or a silent no-op)."
  - "M1-389 env-file discipline (analysis P6): StackScriptWiringTest.missingSecretsFileOmitsTheEnvFileFlag — with secrets.env absent, every verb still runs and no `--env-file` flag is passed (apps.sh:56 precedent: compose's ${VAR:-} defaults keep stop/ps/start functional; secrets are never shell-sourced)."
  - "`stack.sh status` passes the same full assembly to `compose ps` (asserted in the same harness); `restart` is stop then start."
  - "Live probe on a docker host (ASSUMPTION A3 — compose v2 `start` honours the --profile-scoped model and resumes existing stopped containers without recreation; prod host runs compose v5.4.0 per the M1-810 record): `stack.sh status` output matches the hand-assembled `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml --env-file prod/runtime/secrets.env --profile prod --profile ollama --profile llamacpp --profile llamacpp-embeddings ps`, and a `stack.sh stop` → `stack.sh start` round-trip restores the pre-stop running set. If A3 is falsified, the fallback is deriving the stopped-container list from `compose ps -aq` and naming it explicitly — NEVER switching to `up` (record the outcome in the commit message either way)."
  - "Design-note + pointer sync (analysis P6): docs/design/07-deployment.md §7.7.1's ops-scripts table (:696-700) gains the stack.sh row (full-stack stop/start/status around host reboots; complements apps.sh's app-only scope), and apps.sh's header (:10-14) names stack.sh in place of bare `docker compose stop` for the whole stack. Probes: `grep -n 'stack.sh' docs/design/07-deployment.md prod/scripts/apps.sh` hits both."
  - "`bash -n prod/scripts/stack.sh prod/scripts/apps.sh` clean; mvn verify from repo root is green (DoctorWiringTest et al. unaffected)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/StackScriptWiringTest.java
      — fullStackStopAssemblesProfilesOverlaysAndEnvFile (reproduction),
      startNeverRecreates, startWithNoContainersFailsWithSetupPointer,
      missingSecretsFileOmitsTheEnvFileFlag, statusUsesTheSameAssembly.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.1 Developer inner-loop scripts
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs:
  - D49
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN (informational), SCOPE PASS"
    diff_stats: "6 files changed, 331 insertions(+), 15 deletions(-)"
    findings: "0 rework items; 0 critical/high; 3 candidate findings falsified-and-dropped (empty-array expansion under set -u — in-repo precedent apps.sh:57/setup.sh:180 + green missingSecretsFileOmitsTheEnvFileFlag drive; usage-synopsis -h phrasing — style parity with apps.sh:41; start non-zero on missing dependency — unreachable via stack.sh's own verbs, probe record documents fail-loud intent); 0 RECOMMENDED-NEW-TICKET; MAINTAINABILITY WARN is the comment-cap note (4-line class javadoc vs 3-line cap, StackScriptWiringTest.java:20-23, each clause maps to a drive)"
    verdict_file: .scratch/tick-review-M1-832-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  lint: "0 findings, 0 BLOCKERs (after copying the gitignored tick-analysis/ doc into the worktree)"
  result: pass
  notes: >-
    Citations spot-checked green: apps.sh:38/:31/:54-58, setup.sh:156-180
    (profiles :167, env-file guard :156-157, comfyui-overlay merge),
    upgrade.sh:92, docker-compose.yml:266 profile-match comment, gpu-overlay
    header devices: trap, 07-deployment.md:696-707 table + script shape.
    Census re-run returned six further --profile sites without rows
    (switch-llm.sh, pack.sh, 6b-simplex-provision.sh, 7-apps.sh, 8-verify.sh,
    3-postgres.sh) — verified all are single-service or app-scoped verbs that
    need no full-stack assembly; same disposition class as the 4-llm.sh row.
    DoctorWiringTest harness citation :161-194 shifted under the landed M1-831
    drives (controlled-PATH harness now :252-289; pattern unchanged).
    blocked_by empty — no sibling tests to trace. Analysis P4/P5/P6 all landed.
---

# M1-832: Full-stack lifecycle verb prod/scripts/stack.sh

## Context

There is no named verb for stopping or starting the WHOLE stack around a
host reboot. `apps.sh` covers only the two app services by design (its
header points at bare `docker compose stop` or the `setup.sh --reset`
teardown for the rest); the correct full command — four profiles + the
comfyui overlay + `--env-file` — exists only inline in `setup.sh`'s
teardown path (do_reset, setup.sh:156-180) and otherwise must be typed by
hand. Hand-assembly is how the 2026-08-13 incident happened: the stack came
back without `--profile ollama`, and the failure surfaced as a user-visible
error that looked like content refusal (setup-hurdles.md item 13). Shared
analysis: `analysis_ref:`.

## Root cause

Verified: `ls prod/scripts/stack.sh` — absent; the only lifecycle scripts
are app-scoped (apps.sh:38 `APP_SERVICES=(infochat-collector
infochat-provider)`; upgrade.sh:92 the same `--profile prod` shape). The
full assembly is real but buried in a teardown function. The script shape,
the `--env-file`-when-present pattern (apps.sh:54-58), and the
four-profile + comfyui-overlay assembly (setup.sh:167, :180) all exist as
in-repo precedents — the verb assembles them, it does not invent them.

## Pitfalls

Numbered consistently with the analysis document.

- P4: `start` must never be `up -d` — over the four-profile assembly, `up`
  creates BOTH LLM backends (a service starts iff ANY enabled profile
  matches, docker-compose.yml:266) plus unprofiled comfyui, and recreates
  drifted containers. `docker compose start` resumes exactly the existing
  (chosen-shape) container set with no creation. ASSUMPTION A3: compose v2
  `start` honours the profile-scoped model — verified live (acceptance
  item 6); fallback is an explicit stopped-container list, never `up`.
- P5: overlay asymmetry — always merge `-f docker-compose.comfyui.yml`
  (else `stop` leaves a running comfyui up — the M1-395 trap that
  setup.sh:174-176's comment disposes for teardown), never merge
  `-f docker-compose.gpu.yml` (its `devices:` fails container creation on
  GPU-less hosts, overlay header :6-12; no script merges it today,
  grep-verified; stop/start/ps never recreate, so it is not load-bearing).
- P6: don't swallow the app-scoped fast path — apps.sh (config-reload
  restarts) and upgrade.sh stay app-only (07-deployment.md:698); this
  ticket's apps.sh touch is one header line naming stack.sh (§11: the old
  "docker compose stop" pointer goes stale on landing).

## Approach

- **Files to touch:** `files_scope` (one new script, one header line, one
  new test, one design-doc row).
- **Steps, in order:**
  1. Write StackScriptWiringTest's five drives (the reproduction's
     to-be-written class) — run RED on main (workflow §0). Fake docker
     records argv per invocation; a tmp runtime dir steers secrets.env
     presence via INFOCHAT_RUNTIME_DIR (the apps.sh:31 pattern).
  2. Write prod/scripts/stack.sh in the §7.7.1 script shape (set -euo
     pipefail, echo the wrapped command, exit-code pass-through, -h
     synopsis — 07-deployment.md:702-707): one `compose()` wrapper owning
     `-f docker-compose.yml -f docker-compose.comfyui.yml` +
     `--env-file` secrets.env when present + the four profiles; `stop` →
     compose stop; `status` → compose ps; `restart` → stop then start;
     `start` → compose start, with the zero-container guard failing to a
     setup.sh pointer. Header comment states the apps.sh relationship, the
     overlay asymmetry (P5), and why start is not up (P4) — one stable
     reference each, no chronicle (§11).
  3. Update the §7.7.1 ops-scripts table (one row) and the apps.sh header
     pointer (one line).
  4. Live probe (acceptance item 6), `bash -n`, `mvn verify`.
- **Controls to preserve (§10):** M1-389's env-file discipline (compose's
  dotenv parser, never a shell source — operator-pasted values are
  shell-hostile); apps.sh's scope, wrapper, and exit-code semantics
  untouched; setup.sh do_reset untouched (its teardown assembly stays the
  teardown path — stack.sh never runs `down`, containers and volumes are
  preserved across stop/start exactly per apps.sh's stop contract).
- **Pitfall→mitigation:** P4→step 2's compose-start verb + acceptance
  items 2 and 6; P5→step 2's fixed overlay set + acceptance item 1's
  both-directions argv assertions; P6→step 3's one-line touch + the
  out_of_scope guard.

## Definition of done

One named verb owns the full-stack assembly: `stack.sh stop` halts every
service the wizard can start (comfyui included) without touching volumes;
`stack.sh start` resumes exactly the pre-stop set with no creation; status
shows the full model; a never-set-up host gets the setup pointer; the gpu
overlay is never merged; apps.sh's fast path is intact and its header
points here; design table updated; suite green.

## Verification

- P4 → acceptance item 2 — FAILURE-MODE: `up` anywhere in a start
  invocation fails the test; a create/recreate path must never ship (it
  would start both LLM backends plus comfyui regardless of the chosen
  shape). Plus item 6's live stop/start round-trip (A3).
- P5 → acceptance item 1 — FAILURE-MODE both directions: dropping the
  comfyui overlay fails the test (comfyui left running on stop); adding
  the gpu overlay fails it (GPU-less-host create break).
- P6 → acceptance item 4 (env-file-when-present drive) + item 7 (design
  row + apps.sh pointer probes; the apps.sh diff is one line,
  reviewer-visible).
- Reproduction → item 1. Edge inputs → item 3 (start with zero containers
  refuses with the setup pointer, not a silent no-op) and item 4 (no
  secrets file).
- Regression → item 8.

## Out-of-scope

Named in `out_of_scope`: apps.sh's scope/wrapper (one header line only);
upgrade.sh (app-scoped by design — see Census); setup.sh do_reset; any
`down`/teardown semantics (stop preserves containers + volumes — the
apps.sh stop contract extended, not the reset contract); the gpu overlay
(operator-manual by design); compose files (M1-830); doctor (M1-831);
guides (M1-833); batch E's circuit-breaker surfacing and batch A's
restore.sh. No pre-existing test is modified.

## Census

Class: repo entry points that need the full-stack profile+overlay
assembly (re-runnable: `grep -rn '\--profile' prod/ --include='*.sh'` +
`grep -rln 'docker-compose.comfyui.yml' prod/`).

| Site | Disposition |
|---|---|
| setup.sh do_reset (:156-216) | already assembles four profiles + comfyui overlay + env-file for teardown — correct, untouched |
| prod/scripts/stack.sh | FIXED (this ticket: the assembly as a named lifecycle verb) |
| prod/scripts/apps.sh (:54-58) | out-of-scope: deliberately app-scoped fast path (07-deployment.md:698); header pointer updated only |
| prod/scripts/upgrade.sh (:92) | out-of-scope: app-scoped rebuild/restart verb; a full-stack form would rebuild nothing extra and restart services whose images didn't change |
| prod/scripts/restore.sh | out-of-scope: batch A owns it (brief) |
| 4-llm.sh / 4b-image.sh backend bring-ups | out-of-scope: wizard first-provisioning steps that deliberately start ONE chosen backend shape, not the stack |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-832-lifecycle-reboot-resilience-3.md
```
