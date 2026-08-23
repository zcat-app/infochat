---
id: M1-910
title: "Rootless IPv6 compose enablement, capability-derived"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  Ipv6OverlayWiringTest.v6CapableRootlessHostAppliesIpv6Overlay (to-be-written
  — child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/rootless-ipv6-deployment-surface.md; converted at
  `start` per workflow §0: written first, run RED — no IPv6 surface exists to
  assert). The wrong behavior it states: the tracked deployment surface gives
  containers no outbound IPv6 on hosts that have real v6, so a SimpleX
  deployment on an AAAA-first-resolver host wedges at every fresh stack start
  (zero SMP sessions, D-8 root cause,
  .scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md §10 :787-831) and only
  never-ship test-checkout extra_hosts pins clear it. Verified-absence probe
  on this checkout (2026-08-23): `grep -rn 'enable_ipv6\|networks:'
  docker-compose.yml docker-compose.gpu.yml docker-compose.comfyui.yml
  prod/scripts/` returns ZERO matches — every service rides the implicit v4-only
  default network.
analysis_ref: docs/plan/m1/tick-analysis/rootless-ipv6-deployment-surface.md
blocked_by: []
files_scope:
  - docker-compose.ipv6.yml
  - prod/scripts/7-apps.sh
  - prod/scripts/apps.sh
  - prod/scripts/upgrade.sh
  - prod/scripts/restore.sh
  - prod/scripts/stack.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/Ipv6OverlayWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The base docker-compose.yml — it gains NO networks: key (P4, the M1-744
    base-starts-everywhere invariant); the wiring test pins its absence.
  - >-
    3-postgres.sh, 4-llm.sh, 4b-image.sh, 8-verify.sh, backup.sh, pack.sh —
    they never recreate the provider (census, analysis Ground truth), so they
    never apply the overlay; 8-verify.sh additionally keeps its WARN-only
    posture and exit contract with zero diff (P8).
  - >-
    Any messaging-adapter source or test — the M1-889/890/901 detector is the
    untouched safety net (P7); this ticket changes the environment, never the
    detection.
  - >-
    The no-v6-host fallback (image getaddrinfo v4 preference) — sibling
    M1-911. This ticket's negative drives only pin that a v6-less or
    capability-uncertain host renders byte-identical argv.
  - >-
    signal-cli / JVM resolver behavior (P12 — no shared exposure on the live
    evidence; analysis Ground truth) and any rootful-daemon path (rootless
    remains the supported posture; rootful hosts are handled by the same
    probe predicate if the step-0 probe confirms it, otherwise out of scope).
  - >-
    Removing the TEST checkout's extra_hosts pins — an ops/rollout action
    recorded as evidence (P10), never a repo diff; the pins live outside this
    repo.
acceptance:
  - "STEP-0 GATE (P2, ASSUMPTION A1) — live probe, run before any file edit, recorded in the commit message (the M1-908 acceptance-2 pattern): on the real rootless host, record the RootlessKit version and its v6-relevant flags, create a throwaway `enable_ipv6` network in the landed shape, run a throwaway container on it, and capture outbound v6 connectivity (e.g. curl -6 to a dual-stack host). This decides the branch: BRANCH 1 — no daemon-level change needed (expected on RootlessKit 3.0.2-class per the 2026-08-23 session probe); BRANCH 2 — a per-user daemon config / rootlesskit flag is needed: the wizard-side write must land before any bring-up (P9), disclose the daemon restart and the daemon-wide blast radius (every container of that user's daemon) in docs, and keep the off-override. If NO fail-safe shape exists, STOP and escalate — ship nothing."
  - "REPRODUCTION, now passing: Ipv6OverlayWiringTest.v6CapableRootlessHostAppliesIpv6Overlay (test_plan.adds) — fake docker reporting rootless + fake host v6 route present, driving each of the five provider-recreating scripts (7-apps.sh, apps.sh, upgrade.sh, restore.sh, stack.sh) on the DoctorWiringTest restricted-PATH/fake-docker pattern, asserts the compose argv carries `-f docker-compose.ipv6.yml`; plus a static layer asserting the overlay's landed shape: a v6-enabled network (enable_ipv6, fixed generic ULA /64 literal from the step-0 probe) attached to infochat-provider alongside the default network, and nothing else (FAILURE-MODE mutation: dropping the -f addition from any ONE of the five scripts fails its drive — P1)."
  - "FAILURE-MODE (P3, negative): Ipv6OverlayWiringTest.noV6OrUncertainCapabilityRendersByteIdenticalArgv — host v6 route absent, AND separately the capability predicate undetermined (probe tools absent / branch-2 config missing), each yield compose argv byte-identical to today's, a printed note, and unchanged exit codes: a v6-less or uncertain host is never broken by the enhancement (the M1-890 detector remains the wedge surface there)."
  - "FAILURE-MODE (P3) predicate pin: the applied branch requires BOTH host v6 and daemon capability — a drive with host v6 present but capability negative asserts NO overlay (a mutation that weakens the predicate to host-v6-only fails the build; applying on an incapable daemon fails the whole stack `up`, which this pin exists to prevent)."
  - "BASE-FILE INVARIANT (P4): the test asserts docker-compose.yml contains no `networks:` key and docker-compose.gpu.yml / docker-compose.comfyui.yml gain none — the base render stays byte-identical and startable on every host class (07-deployment.md:1011)."
  - "GENERIC SURFACE (P5, binding steer): the test scans docker-compose.ipv6.yml and the five scripts for `simplex.im`, any IPv4 literal, and any host-derived value — zero hits; the only address literal anywhere is the fixed generic ULA prefix (identical for every deployment, not derived from the probe host)."
  - "OVERRIDE + REVERSIBILITY (P2): an INFOCHAT_IPV6=off drive on a v6-capable host asserts byte-identical argv (operator-exported env, the INFOCHAT_LLAMACPP_GPU=on|off precedent — never a secrets.env key, 07-deployment.md:823); docs record that off + the next provider recreate detaches the v6 network."
  - "DOCS (07-deployment.md): a runbook paragraph records the mechanism (capability-derived overlay application at every provider-recreating bring-up, printed in the echoed compose command), the override, the reversal, the branch-2 daemon-restart + daemon-wide disclosure if the step-0 probe landed branch 2, and a §7.7.2-step-table note where the shape fits; if branch 2 landed, ADMIN_GUIDE.md gains the matching operator disclosure. Verification: `git diff --stat docs/ ADMIN_GUIDE.md` shows exactly those files (M1-911 edits DISJOINT sections of 07-deployment.md — run serially)."
  - "Controls preserved (§10): every pre-existing wiring test (DoctorWiringTest, RestoreWiringTest, UpgradeWiringTest, VerifyWiringTest, LlamacppWiringTest, StackScriptWiringTest) is byte-untouched — `git diff` shows the new test class as the only test-file addition; the M1-905 GPU-overlay threading in 4-llm.sh is untouched (4-llm.sh is not in files_scope); `git diff --name-only` shows no 8-verify.sh hunk (P8) and no messaging-adapter path (P7)."
  - "./mvnw -B -pl infochat-provider test -Dtest='Ipv6OverlayWiringTest' is green AND mvn verify from the repo root is green (engineering-rules §5)."
  - "ROLLOUT EVIDENCE (P10, recorded in the commit message or rollout notes, not a build test): on the test checkout the extra_hosts pins are removed, the stack is fresh-started, and the wedge stays gone (provider holds SMP sessions) with the M1-890 detector green; on a no-v6 host (or simulated negative probe) the scripts render byte-identical argv."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/Ipv6OverlayWiringTest.java
      — v6CapableRootlessHostAppliesIpv6Overlay (reproduction; per-site drives
      for 7-apps.sh, apps.sh, upgrade.sh, restore.sh, stack.sh),
      noV6OrUncertainCapabilityRendersByteIdenticalArgv,
      capabilityNegativeWithHostV6AppliesNothing, ipv6OffOverrideRendersBaseArgv,
      plus the static layer (overlay landed shape, base-file networks:
      absence, generic-surface scan)
  preserves:
    - all tests currently green on main
    - every pre-existing wiring-test class, byte-untouched (the M1-907 separate-class discipline)
spec_refs:
  - docs/spec/deployment.md §Deployment scenarios
  - docs/design/07-deployment.md §7.7.2
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-910: Rootless IPv6 compose enablement, capability-derived

## Context

The D-8 SimpleX wedge (plan §10 :787-831): on any host whose resolver answers
AAAA-first for `*.simplex.im` and whose rootless Docker gives containers no
IPv6 route, the supervised simplex-chat subprocess holds zero SMP sessions at
every fresh stack start, and no restart clears it. The host this was
reproduced on HAS global IPv6 (session probe 2026-08-23: SLAAC v6 + default
route, RootlessKit 3.0.2) — the container simply never gets it. Today nothing
on the tracked surface addresses this: no `networks:` anywhere (verified
absence probe, `reproduction:` above), and the only remedy is the never-ship
test-checkout extra_hosts pins. Analysis: `analysis_ref:`.

## Root cause

Proven live (plan §10 :799-808): the container inherits a v4-only network;
simplex-chat (pinned v7.0.0, Dockerfile.jvm:47) does not fall back to IPv4
when the AAAA-first answer is unreachable. What remains unverified is the
enablement mechanics (ASSUMPTION A1): whether compose `enable_ipv6` alone
gives a rootless container outbound v6 on RootlessKit 3.0.2-class hosts, or a
per-user daemon change is additionally required — the step-0 gate decides
(acceptance 1). The ticket is safe to start because the gate runs before any
file edit and the fail-safe direction (P3) is byte-identical to today.

## Pitfalls

Numbered consistently with the analysis document.

- P1: partial threading flaps the network shape — all five provider-recreating
  scripts must apply the overlay under the same predicate or a later
  upgrade/restore silently strips v6 and reintroduces the wedge.
- P2: ASSUMPTION A1 — rootless v6 mechanics unverified; step-0 live probe
  gates the landed shape; branch 2 adds wizard-write-before-boot, daemon-
  restart and daemon-wide disclosure obligations; no fail-safe shape ⇒
  escalate.
- P3: capability-probe false positive breaks bring-up — fail-safe is INERT
  (no overlay) whenever host v6 is absent or capability is uncertain.
- P4: base file stays startable everywhere — no `networks:` in base compose.
- P5: nothing box-specific ships — no IPs, no relay hostnames (the repo names
  none outside test fixtures — grep-verified), fixed generic ULA only.
- P7: M1-889/890/901 detector untouched (zero messaging-adapter diff).
- P8: 8-verify.sh untouched (WARN-only posture, exit contract).
- P9: wizard write-before-boot for any branch-2 write (M1-907 census).
- P10: the test-checkout pins are the verification fixture, never the diff.

## Approach

Derived from spec_refs: spec/deployment.md §Deployment scenarios commits to
the wizard-driven single-host containerized shapes; design/07-deployment.md
§7.7.2 is the script contract the change rides, and :1011 is the merged
precedent for capability-derived overlay application (GPU: probe `/dev/dri`,
apply at bring-up, `INFOCHAT_LLAMACPP_GPU=on|off` override, printed never
prompted).

- **Files to touch:** `files_scope:` (one new overlay, five scripts, one new
  test class, one docs file).
- **Steps, in order:**
  1. STEP-0 GATE (P2): run the acceptance-1 probe on the real host; record
     output in the commit message; decide branch 1 vs branch 2 and land the
     capability predicate + ULA literal from the verified shape. No fail-safe
     shape ⇒ STOP.
  2. Write Ipv6OverlayWiringTest's drives + static layer — run RED (workflow
     §0; the ticket's reproduction).
  3. `docker-compose.ipv6.yml`: the v6-enabled network (enable_ipv6 + fixed
     generic ULA /64) attached to infochat-provider alongside default.
  4. The five scripts: one small probe block each (host default v6 route +
     capability predicate; `INFOCHAT_IPV6` override), appending
     `-f docker-compose.ipv6.yml` to the compose argv only when positive —
     matching each script's existing boilerplate style (no sourced helper
     exists in prod/scripts; per-script duplication is the house style). The
     echoed command makes every application visible.
  5. Docs (acceptance 8); branch-2 only: the wizard-side write lands before
     any bring-up (P9) and ADMIN_GUIDE.md gains the disclosure.
  6. Module test run + `mvn verify`; rollout evidence (acceptance 11).
- **Controls to preserve (§10):** the echoed-before-run script shape
  (07-deployment.md:703-708); secrets.env never sourced (the override is an
  operator-exported env var); base compose invariants (loopback-only binds,
  M1-512 caps, image pins, no `networks:`); the M1-905 GPU threading in
  4-llm.sh (untouched — not in scope); pre-existing wiring tests
  byte-untouched (new class, M1-907 discipline); 8-verify.sh zero-diff;
  Dockerfile.jvm untouched (M1-911's surface).
- **Pitfall→mitigation:** P1→step 4 + acceptance 2's per-site drives; P2→step
  1 + acceptance 1; P3→acceptances 3-4; P4→acceptance 5; P5→acceptance 6;
  P7/P8→acceptance 9's diff guards; P9→step 5 ordering; P10→acceptance 11.

## Definition of done

Every acceptance item verified by its named test/probe: the step-0 probe
recorded with its branch decision; the overlay landed in the verified shape;
all five provider-recreating scripts apply it iff the probe predicate is
positive, byte-identical argv otherwise, override honored; the base file
carries no `networks:`; nothing box-specific on any shipped surface; docs
record mechanism/override/reversal (+ branch-2 disclosures if applicable);
pre-existing tests byte-untouched; module run + `mvn verify` green; rollout
evidence recorded (pins removed in the test checkout, wedge stays gone,
M1-890 green; negative-probe inertness shown).

## Verification

- P1 → v6CapableRootlessHostAppliesIpv6Overlay per-site drives — drop one
  site's `-f` addition and its drive fails.
- P2 → acceptance 1 (recorded probe) + the test's landed-predicate pin.
- P3 → noV6OrUncertainCapabilityRendersByteIdenticalArgv and
  capabilityNegativeWithHostV6AppliesNothing — hostile steering of the fakes;
  asserts inert output, printed note, unchanged exit.
- P4 → static base-file `networks:` absence assertion.
- P5 → generic-surface scan (acceptance 6).
- P7/P8 → `git diff --name-only` guards (acceptance 9).
- P9 → branch-2 docs/write-ordering review at implementation (acceptance 8).
- P10 → acceptance 11 rollout evidence.
- Reproduction → acceptance 2.

## Out-of-scope

Named in `out_of_scope:` — the base compose file; scripts that never recreate
the provider (3-postgres, 4-llm, 4b-image) plus 8-verify/backup/pack; all
messaging-adapter sources; the M1-911 image-level fallback; Signal/JVM
resolver behavior; any rootful-only mechanism; the test-checkout pin removal
as a repo diff. No pre-existing test is modified — if one fails for a reason
not named here, escalate rather than edit it (§8).

## Census

Class: compose `up -d` sites that can recreate infochat-provider (re-runnable:
`grep -n 'up -d' prod/scripts/*.sh`; provider-touching sites enumerated in the
analysis Ground truth).

| Site | Disposition |
|---|---|
| prod/scripts/7-apps.sh:85/89 | THREADED (this ticket) |
| prod/scripts/apps.sh:65 | THREADED (this ticket) |
| prod/scripts/upgrade.sh:115/117 | THREADED (this ticket) |
| prod/scripts/restore.sh:978/1042 | THREADED (this ticket) |
| prod/scripts/stack.sh:34 (arbitrary-verb passthrough) | THREADED (this ticket) |
| prod/scripts/3-postgres.sh:47 | out-of-scope: postgres only, never the provider |
| prod/scripts/4-llm.sh:471/664/681/690/859 | out-of-scope: ollama/llamacpp only (M1-905 GPU threading untouched) |
| prod/scripts/4b-image.sh:868 | out-of-scope: comfyui only |
| prod/scripts/restore.sh:913/920/935 | out-of-scope: llamacpp/ollama legs, provider untouched there |
| prod/scripts/8-verify.sh / backup.sh / pack.sh (exec/ps only) | out-of-scope: never recreate anything |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-910-rootless-ipv6-compose-enablement.md
```
