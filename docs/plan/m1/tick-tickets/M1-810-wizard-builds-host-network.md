---
id: M1-810
title: "Run wizard image builds on the host network"
status: done
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  Probe (RED on main; compose artifacts have no mvn coverage today):
  `grep -n 'network' docker-compose.yml docker-compose.comfyui.yml` matches
  only comments — none of the three compose build blocks
  (docker-compose.yml:96-98 collector, :168-170 provider,
  docker-compose.comfyui.yml:22-24 comfyui) declares a build network, so
  every repo image build runs on the default bridge. Observed wrong behavior
  on the divergent host: image BUILD containers are default-bridge class
  (change-note fact 2, 2026-08-10); the M1-797 comfyui build died on DNS here
  (`apt-get update` exited 0 with empty indexes → "Unable to locate package")
  and only worked with `docker build --network=host` (local memory entry) —
  no script-provided way through for a first-time user, who dies at step 7
  (7-apps.sh:62) and, on a fresh GPU host, at 4b's implicit comfyui build
  (4b-image.sh:801). Test (written at start, run RED on main):
  BuildHostNetworkWiringTest.appImageBuildsDeclareTheHostNetwork.
analysis_ref: docs/plan/m1/tick-analysis/wizard-download-container-network.md
blocked_by: [M1-809]
files_scope:
  - docker-compose.yml
  - docker-compose.comfyui.yml
  - prod/scripts/7-apps.sh
  - prod/scripts/upgrade.sh
  - prod/scripts/restore.sh
  - prod/scripts/0-doctor.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/BuildHostNetworkWiringTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/DoctorWiringTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The one-shot DOWNLOAD containers (M1-808, landed) and the download
    preflight + guidance (M1-809, landed) — this ticket consumes both: its
    guidance text may state the family end state (downloads AND builds on the
    host path) precisely because blocked_by guarantees both have landed.
  - Any change to the build CONTENT — Dockerfiles, build contexts,
    dockerfile paths, the comfyui `image:` tag — stays untouched; only the
    build NETWORK key is added (analysis §Controls to preserve item 6).
  - Runtime container networking: no service gains `network_mode` — host
    networking at RUNTIME stays forbidden (analysis P5; §Trust boundaries).
    Compose-network service legs (runtime egress, ollama pull) are verified
    UNAFFECTED and get no change, no preflight, no text.
  - Host-side Docker daemon DNS configuration (daemon.json) — after this
    ticket no repo-launched container depends on default-bridge DNS; the
    daemon remedy is optional residue for the operator's own non-repo
    containers, never a repo change, and this family deliberately does not
    document it in the setup path (analysis §Decomposition end state).
  - The restore.sh comfyui gap (host-clone restore does not re-provision the
    /image overlay) — orthogonal to network context; censused, not fixed
    here.
acceptance:
  - "REPRODUCTION, now passing: BuildHostNetworkWiringTest.appImageBuildsDeclareTheHostNetwork (written at start, RED on main; infochat-provider wiring package, repo-root compose-read pattern of RestoreWiringTest/LlamacppWiringTest) — asserts the infochat-collector and infochat-provider service blocks in docker-compose.yml and the comfyui block in docker-compose.comfyui.yml each declare `network: host` inside their build: section, and that NONE of the three files declares `network_mode` on any service (FAILURE-MODE: a service-level network_mode — runtime host networking, forbidden as a default per docs/spec/security.md §Trust boundaries — fails the test, as does a missing key under any build: block). RED on main: no compose file declares a build network today (grep-verified)."
  - "Live render proof on the divergent host (analysis P11): `docker compose -f docker-compose.yml --env-file prod/runtime/secrets.env --profile prod config` prints `network: host` under BOTH app services' build: blocks, and `docker compose -f docker-compose.yml -f docker-compose.comfyui.yml config` prints it under comfyui — proves the host's compose (v5.4.0) accepts and renders the key rather than rejecting or silently ignoring it."
  - "Live build proof on the divergent host: one cold rebuild completes end-to-end — `docker compose -f docker-compose.yml --env-file prod/runtime/secrets.env --profile prod build --no-cache infochat-collector` exits 0 (RUN-step egress — mvn dependency tree + apt-get — works over the host path; this IS the step-7 first-run shape), then the warm-cache rebuild the wizard would run is re-verified green."
  - "Mechanics preserved (analysis §Controls to preserve item 6, engineering-rules §10): the compose change adds ONLY the network key — probes: `grep -n 'context:\\|dockerfile:' docker-compose.yml` still hits :97-98 and :169-170 unchanged, `grep -n 'image: infochat-comfyui' docker-compose.comfyui.yml` still hits the tag; upgrade.sh's rollback flow keeps its ordering and exit behavior (guidance wraps only — probe: `bash -n` on every touched script); RestoreWiringTest stays green — including nonIgnorablePgRestoreErrorAbortsBeforeImageBuildAndBringUp (no build argv after a failed gate) and sourceStoppedFlagReachesProviderStart (bring-up ordering through the build argv) — and DoctorWiringTest + LlamacppWiringTest stay green; mvn verify from the repo root is green."
  - "Build-failure guidance at all four explicit sites (analysis P13 + P6; wizard contract docs/design/07-deployment.md §7.7.2 First-run setup wizard — actionable remedy, never a cryptic failure): 7-apps.sh:62 and restore.sh:695 gain an if-wrap around the bare build command; upgrade.sh's step-4 failure branch (:272) AND the rollback rebuild (:155) carry the same text. The text states the build used the host's own network path, names the actionable cause classes (connectivity / VPN / proxy / firewall), and — since downloads are already host-network (M1-808 landed) — may state the family end state: no infochat container depends on container DNS. Probes: `grep -n 'network path' prod/scripts/7-apps.sh prod/scripts/upgrade.sh prod/scripts/restore.sh` hits all four build sites; `bash -n` passes on all three scripts (FAILURE-MODE: guidance that blames 'container DNS' or presents daemon DNS configuration as a requirement fails this item)."
  - "Compose-floor duty (analysis P11): the implementor verifies the minimum compose version that supports `build: network` (compose spec/release notes or a probe on the oldest supported compose). If it exceeds the repo's current any-v2 gate (0-doctor.sh:79-85), 0-doctor.sh gains a minimum-version check with an actionable remedy in the M1-439 accumulate-and-report style, DoctorWiringTest's fake compose version (v2.20.0, DoctorWiringTest.java:56) moves in the same AUTHORIZED edit, and the §7.7.2 'Docker Compose v2' prerequisite sentence (07-deployment.md:774) is aligned; if the floor is at or below the current gate, the verified floor is recorded in the commit message instead. Probe: the recorded floor + DoctorWiringTest green either way."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/BuildHostNetworkWiringTest.java
      — appImageBuildsDeclareTheHostNetwork (reproduction): static compose
      assertions over docker-compose.yml (collector + provider build blocks)
      and docker-compose.comfyui.yml (comfyui build block): `network: host`
      present under build:, `network_mode` absent everywhere.
  preserves:
    - all tests currently green on main
    - >-
      RestoreWiringTest untouched (its drives pin the build's POSITION and
      the no-build-after-failed-gate invariant; the guidance wraps change
      neither). AUTHORIZED conditional modification of DoctorWiringTest ONLY
      if the compose-floor check lands: the fake compose version string moves
      to the verified floor; no assertion is weakened.
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/spec/security.md §Trust boundaries
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-10
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS WARN, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "17 files changed, 384 insertions(+), 29 deletions(-) (incl. sibling-stream docs/plan churn, falsified-and-dropped by the gate)"
    findings: "2 fix items (0 critical/high, both low SPEC-TRUTHNESS evidence gaps): missing compose-config render capture (acceptance item 2); unrecorded compose floor for build: network (acceptance item 6, commit-body record). 6 candidate findings falsified-and-dropped (sibling docs/plan churn, restore.sh partial-state note, upgrade.sh rollback exit behavior, BuildKit proxy sentence, warm-cache capture, compose build --network ASSUMPTION, 17:27 ComfyUIClientTest flake)"
    fix_probes: "ITEM 1 applied: render captured to .scratch/tick-render-M1-810.log on the divergent host (compose v5.4.0; prod/runtime/secrets.env absent on this box — repo-shipped prod/config/secrets.env.example stood in, noted in the log) — `grep -c 'network: host'` prints 3, key inside infochat-collector, infochat-provider and comfyui build blocks. ITEM 2 deferred to /tick commit by the verdict's own wording: commit body must carry the verified compose floor for build: network + source (probe: `git log -1 --format=%B | grep -iE 'compose floor|build: network'`). Zero file lines changed by the fixes (comment-only constraint trivially satisfied); ./mvnw -B -pl infochat-provider -am test-compile green 2026-08-10 (.scratch/tick-fixes-M1-810-testcompile.log, BUILD SUCCESS, 6/6 modules); fixed-tree snapshot .scratch/tick-fixes-M1-810.tree = 276e2f16. Round's green log of record: target/tick-test-M1-810-r1.log."
    verdict_file: .scratch/tick-review-M1-810-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-08-10
  result: >-
    Pass, no blocking question. Citations spot-checked: docker-compose.yml:96
    and :168, docker-compose.comfyui.yml:22, 7-apps.sh:62, upgrade.sh:155 and
    :272, 0-doctor.sh:79-85, DoctorWiringTest.java:56 all exact. Two line
    drifts from M1-808/M1-809 landing after the analysis: restore.sh's build
    is now :704 (ticket :695) and 4b-image.sh's implicit comfyui up is now
    :815 (ticket :801) — sites unambiguous, wraps go where the command sits.
    Census re-run: grep hits restore.sh:287 (help-text echo) and
    4-llm.sh:161 (comment) with no rows — neither launches a build; the grep
    also misses 7-apps.sh:62's `compose -f ... build` shape, covered by the
    table from the analysis — census is complete in substance. Analysis
    pitfalls P5/P6/P11/P12/P13 all landed. blocked_by M1-809's tests are all
    LlamacppWiringTest drives of 4-llm.sh (disjoint from this ticket's seams);
    its restore.sh download-guidage region is disjoint from the build wrap.
    RestoreWiringTest pins verified present (:457 no-build-after-failed-gate,
    :555 bring-up ordering through the build argv) — an if-wrap around the
    same command at the same position preserves both.
escalation_reason:
---

# M1-810: Run wizard image builds on the host network

## Context

M1-808 moved the wizard's downloads onto the host network path and M1-809
gave them a same-path preflight — but a first-time user on the divergent host
class still dies at the next wall: every image BUILD the scripts launch runs
on the default bridge, whose resolver answers zero records there. Step 7's
`docker compose build` (7-apps.sh:62), the fresh-GPU-host comfyui build
implicitly triggered by 4b's `up -d --force-recreate` (4b-image.sh:801), and
the same explicit build shape in upgrade.sh (:155 rollback, :272 step 4) and
restore.sh (:695) all hit it — verified broken on this host at M1-797 time
(`docker build --network=host` workaround, local memory entry) and re-probed
2026-08-10 (change-note fact 2: build containers are default-bridge class).
The user's product stance makes these legs in-family: the wizard's contract
is bare-host → running deployment with the operator running ONLY the scripts
(design §7.7.2: 07-deployment.md:767, step-7 row "Build both images" :800).
This is the family's last ticket: after it, NO repo-launched container
depends on default-bridge DNS (analysis §Decomposition end state). Shared
analysis: `analysis_ref:`.

## Root cause

Same structural class as M1-808, build-side: image BUILD containers are
default-bridge one-shots (change-note fact 2), so every network-hungry RUN
step — the app Dockerfiles' `mvn -am clean install` (Dockerfile.jvm:23 both
services) and `apt-get update` (:31-33) plus the provider's simplex-chat /
signal-cli curl fetches (:49-60); the comfyui Dockerfile's apt (:18-26), pip
torch from the ROCm nightly index (:34-38), and curl tarballs (:40-42,
:51-55) — resolves through the broken resolver copy. Proven on this host:
M1-797's comfyui build died on DNS and worked with `--network=host` (local
memory entry). The compose CLI exposes no per-invocation build `--network`
flag (ASSUMPTION — analyst has no shell; implementor verifies), so the fix is
the declarative `build: network: host` key — read by EVERY compose-initiated
build, explicit (`compose build`) or implicit (up-time build of an absent
image), which is exactly the coverage the four explicit sites plus
4b-image.sh:801 and apps.sh:65 need (analysis P12).

## Pitfalls

Numbered consistently with the analysis document.

- P5: `build: network: host` puts the build container in the host netns — it
  can reach host-loopback services, and postgres IS up during the
  restore.sh:695 and upgrade.sh builds. Sound here: the build's entire
  content is repo-committed Dockerfile source the operator already trusts to
  run at all, the container is transient and binds no port, and
  §Trust boundaries items 6–8 govern exposed SERVICES, which this does not
  widen. A service-level `network_mode` (runtime host networking) voids the
  argument and stays forbidden — hence security_relevant and the test's
  network_mode negative.
- P6: message honesty, end-state-calibrated: a host-network build failure is
  a HOST-egress failure — the guidance says so and never blames "container
  DNS" or presents daemon DNS config as a requirement (after this ticket no
  repo leg needs it). Because blocked_by guarantees M1-808/M1-809 have
  landed, this ticket's text MAY state the completed end state (downloads
  AND builds on the host path).
- P11: the declarative key has a compose version floor the repo does not
  currently gate (0-doctor.sh:79-85 checks v2 PRESENCE only;
  DoctorWiringTest's fake reports v2.20.0; host runs v5.4.0). A key the
  floor's compose rejects breaks every compose call against the file; a key
  it silently ignores fixes nothing. Verify the exact minimum; raise the
  doctor gate conditionally.
- P12: implicit builds (4b-image.sh:801 fresh GPU host; apps.sh:65 up with
  absent images) are covered ONLY by the declarative shape — a call-site
  flag fix would miss exactly the first-time-user path the family promise
  names. The comfyui overlay pin in the new test guards this.
- P13: build-failure honesty has FOUR sites including the rollback:
  7-apps.sh:62 and restore.sh:695 are bare commands under set -e (need
  if-wraps); upgrade.sh:272 has an existing if-fail branch (guidance joins
  it); upgrade.sh:155 is the rollback's OWN rebuild and must not die
  cryptically either. Wraps change messages, never exit codes or ordering
  (RestoreWiringTest pins the no-build-after-failed-gate invariant).

## Approach

- **Files to touch:** `files_scope` (three compose build blocks; four build
  call sites across three scripts; conditionally 0-doctor.sh +
  DoctorWiringTest; the new static test).
- **Steps, in order:**
  1. Write BuildHostNetworkWiringTest.appImageBuildsDeclareTheHostNetwork —
     run RED on main (workflow §0).
  2. Add `network: host` under the three build: blocks
     (docker-compose.yml:96-98, :168-170; docker-compose.comfyui.yml:22-24),
     each with a one-line trap comment carrying ONE stable reference
     (§11: this ticket/analysis, no chronicle).
  3. Wrap the four explicit build sites in failure guidance (P13): shared
     text shape per P6 — 7-apps.sh:62 (new if-wrap, keep the M1-392
     attributable-build-phase shape), upgrade.sh:272 (join the existing
     branch) and :155 (rollback), restore.sh:695 (new if-wrap; the wrap sits
     where the build already sits — RestoreWiringTest ordering pins stay
     green).
  4. Compose-floor duty (P11): verify the minimum compose version for
     `build: network`; conditionally raise 0-doctor.sh's gate (M1-439 style,
     actionable remedy), move DoctorWiringTest's fake version in the same
     authorized edit, align 07-deployment.md:774's prerequisite sentence —
     or record the verified floor in the commit message if no gate change is
     needed.
  5. Live proofs on the divergent host (acceptance items 2–3): config render
     + one cold `--no-cache` rebuild of one app service.
  6. `mvn verify` from the repo root.
- **Controls to preserve (§10):** analysis §Controls to preserve item 6 —
  build sections change only by the key (context/dockerfile/image pins);
  upgrade.sh rollback mechanics unchanged; RestoreWiringTest gate/ordering
  pins green (incl. no-build-after-failed-gate); M1-389 --env-file
  discipline untouched; no runtime network_mode. Verification duty
  (ASSUMPTION, flagged): the Docker builder's predefined proxy build args
  (HTTP_PROXY/HTTPS_PROXY/NO_PROXY, automatic and undeclared) are assumed to
  reach RUN steps under BuildKit for explicit-proxy hosts — the implementor
  verifies on such a host or notes the limitation in the guidance text; no
  acceptance item rides on it (the demonstrated defect class is DNS).
- **Pitfall→mitigation:** P5→step 2's build-only key + item 1's
  network_mode negative + security_relevant gate; P6→step 3 + item 5's
  FAILURE-MODE; P11→step 4 + items 2 and 6; P12→step 2's overlay key +
  item 1's comfyui assertion; P13→step 3 + item 5's four-site grep.

## Definition of done

All three compose build blocks declare `network: host`; every explicit build
site (7-apps.sh, upgrade.sh step 4 AND rollback, restore.sh) fails with
honest host-path guidance; the host's compose renders the key and a cold
rebuild completes on the divergent host; the compose floor is verified (and
gated in doctor if needed); the static pin and the full suite are green. The
family end state now holds: a first-time user on a divergent host runs only
the scripts — downloads and builds both on the host path — and reaches a
running deployment; no repo-launched container depends on default-bridge DNS.

## Verification

- P5 → acceptance item 1's `network_mode` negative (FAILURE-MODE: runtime
  host networking fails it) + security_relevant: true (SECURITY check +
  redteam audit of the netns widening).
- P6 → acceptance item 5's text probes (FAILURE-MODE: "container DNS" blame
  or a daemon-config requirement fails it).
- P11 → acceptance item 2 (the host's compose v5.4.0 RENDERS the key —
  catches both rejection and silent-ignore on this host) + item 6 (floor
  verification; conditional doctor gate; DoctorWiringTest green).
- P12 → acceptance item 1's comfyui-overlay assertion (the implicit
  fresh-GPU-host build is pinned alongside the app services) + the Census
  rows for 4b-image.sh:801 and apps.sh:65.
- P13 → acceptance item 5's four-site grep + item 4 (RestoreWiringTest
  ordering/no-build-after-gate pins green — wraps changed no control flow).
- Reproduction → item 1. Mechanics → item 4. Live path → items 2–3.

## Out-of-scope

Named in `out_of_scope`: the download legs and their preflight/guidance
(M1-808/M1-809, consumed via blocked_by); build content (Dockerfiles,
contexts, image tags — the key is the only compose change); runtime
networking (no `network_mode` anywhere; compose-network service legs are
verified unaffected and get nothing); daemon DNS host configuration
(optional residue after this ticket, never documented in the setup path);
the restore.sh comfyui re-provisioning gap (orthogonal; censused below).
Pre-existing tests: RestoreWiringTest and LlamacppWiringTest untouched;
DoctorWiringTest modified ONLY if the floor gate lands, authorized in
test_plan.preserves (version string move, no weakened assertion).

## Census

Class: repo-launched image builds (re-runnable: `grep -rn 'compose build\|docker build' prod/` + `grep -n 'build:' docker-compose*.yml`).

| Site | Disposition |
|---|---|
| docker-compose.yml:96-98 (collector build block) | FIXED (this ticket: network: host) |
| docker-compose.yml:168-170 (provider build block) | FIXED (this ticket) |
| docker-compose.comfyui.yml:22-24 (comfyui build block) | FIXED (this ticket) |
| prod/scripts/7-apps.sh:62 (explicit, wizard step 7) | FIXED via declarative key + guidance (this ticket) |
| prod/scripts/upgrade.sh:272 (explicit, step 4) | FIXED via key + guidance (this ticket) |
| prod/scripts/upgrade.sh:155 (explicit, rollback rebuild) | FIXED via key + guidance (this ticket) |
| prod/scripts/restore.sh:695 (explicit, host-clone restore) | FIXED via key + guidance (this ticket) |
| prod/scripts/4b-image.sh:801 (IMPLICIT up-time build, fresh GPU host) | FIXED via key (compose reads the build config for up-time builds — P12) |
| prod/scripts/apps.sh:65 (IMPLICIT up-time build when images absent) | FIXED via key (P12) |
| docker-compose.gpu.yml | out-of-scope: image-only overlay, no build: section (grep-verified) |
| restore.sh's missing comfyui re-provisioning on host clone | out-of-scope: orthogonal to network context — noted for the user |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-810-wizard-builds-host-network.md
```
