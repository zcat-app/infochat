---
id: M1-827
title: "Fail loud when the GPU container would see no device"
status: done
created: 2026-08-13
last_updated: 2026-08-15
clarity_check: >-
  2026-08-15 start pre-flight: lint clean; citations spot-checked
  (docker-compose.gpu.yml:21-28/:34-36, 0-doctor.sh linger block); census
  re-run — all returned paths have rows (0-doctor.sh rootless lines are
  M1-831's linger check, landed after this ticket's reproduction; the
  reproduction's "prints nothing" prose is stale but the premise — no
  wizard step checks the rootless ACL trap — holds; 4b-image.sh's gate now
  sits at :789-790, still the deferred batch-C row); analysis pitfalls
  P3/P8/P11/P12/P14 all landed; M1-826's three drives traced green under
  the plan (fake-docker info branch defaults non-rootless; exec
  list-devices defaults non-empty). No blocking ambiguity. Item 6's
  doctor grep as written ('setfacl\|renderD\|rootless') is stale: M1-831
  (landed after this ticket was drafted) added rootless-linger text to
  0-doctor.sh; the probe's substance — doctor gains no ACL/GPU check —
  holds via 'setfacl\|renderD' printing nothing + DoctorWiringTest green.
flow: tick
reproduction: >-
  Probe (RED on main + M1-826): `grep -rn 'setfacl\|rootless\|list-devices'
  prod/scripts/` prints nothing — the rootless trap is documented only in
  docker-compose.gpu.yml:21-28 and docs/design/07-deployment.md §7.8.7
  (:1015-1026) and checked by no wizard step. Observed mechanism (measured
  2026-08-01, M1-744; live on the prod host until ACLs were applied
  2026-08-10, evidence item 10): under rootless docker, group_add is
  ineffective, the device node shows 65534:65534, and `llama-server
  --list-devices` prints an EMPTY list with no error — a host without the
  /dev/dri ACLs gets a GPU container that sees no device, silently. Test:
  LlamacppWiringTest.rootlessGpuHostWithoutRenderNodeAccessFailsWithTheSetfaclRemedy
  (written at start and run RED before any fix code, workflow §0 — rc 0 with
  both ups recorded, gate absent).
analysis_ref: docs/plan/m1/tick-analysis/llm-wizard-robustness.md
blocked_by: [M1-826]
files_scope:
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - 0-doctor.sh — deliberately UNCHANGED (analysis P12): doctor's M1-439
    contract exits non-zero iff ANY check fails, so an ACL/GPU check there
    hard-blocks operators who will pick ollama/remote on a GPU host. The gate
    belongs at the 4-llm.sh decision point, on the path that uses the device.
  - The GPU probe + overlay merge itself (M1-826, landed — this ticket gates
    and verifies it).
  - 4b-image.sh's local-install gate (4b-image.sh:774-778 checks device
    EXISTENCE only, not rootless ACLs — the same silent-trap exposure for
    ComfyUI): batch C's lane, recorded in the census as the follow-up.
  - Applying the ACLs for the operator (the setfacl/udev runbook needs sudo;
    the wizard surfaces the remedy, §7.8.7 stays the runbook).
  - restore.sh (batch A).
acceptance:
  - "REPRODUCTION, now passing: LlamacppWiringTest.rootlessGpuHostWithoutRenderNodeAccessFailsWithTheSetfaclRemedy — drive with GPU forced on, the fake docker's info branch stubbed rootless (new FAKE_DOCKER_ROOTLESS env switch), and the node list pointed at permission-controlled temp files WITHOUT access (INFOCHAT_LLAMACPP_GPU_NODES seam): asserts rc is NON-ZERO, the docker-argv log records NO compose up (the gate fires before any bring-up), and the output names the rootless cause, the /dev/dri renderD/card nodes, the setfacl remedy, and the udev persistence pointer per §7.8.7. RED at start: no gate exists."
  - "Access-present and rootful paths proceed (FAILURE-MODE against over-blocking): LlamacppWiringTest.rootfulOrAclPresentGpuHostProceeds (to-be-written, test_plan.adds) — two drives assert rc 0 with the compose up recorded: (a) rootful docker (stub default) + GPU forced on proceeds regardless of node permissions (group_add works rootful); (b) rootless + readable/writable node files (the prod host's post-2026-08-10 shape) proceeds."
  - "End-of-path device verification (analysis P11, §8 boundary siting): LlamacppWiringTest.gpuUpFailsLoudWhenTheContainerSeesNoDevice (to-be-written, test_plan.adds) — after EACH GPU-overlay up the wizard execs `llama-server --list-devices` in the started container (generative, and llamacpp-embeddings when that branch runs); the fake docker emits an empty list → asserts rc non-zero naming the trap (a GPU container that sees no device is never a silent success); a non-empty list → rc 0. ASSUMPTION the implementor verifies live: the pinned server-vulkan-b9776 image's `llama-server --list-devices` exits 0 and prints the list on a working host (M1-744 measured the empty-list failure mode, so the verb exists in this build); if the invocation shape differs, record the live-verified shape in the commit message."
  - "CPU path untouched — probe: every pre-existing LlamacppWiringTest drive green UNMODIFIED with GPU off/absent (no docker-info probe requirement, no list-devices exec added to the base-file path)."
  - "Live proof on the prod rootless+ACL host (analysis P14) — probe: a real GPU-selected run's post-start `docker compose -f docker-compose.yml -f docker-compose.gpu.yml --env-file prod/runtime/secrets.env --profile prod --profile llamacpp exec -T llamacpp llama-server --list-devices` prints a NON-EMPTY device list, captured to .scratch (`grep` the capture for the device line) and cited in the commit message. The negative is covered hermetically by items 1/3 (no live de-ACL of the prod host)."
  - "Design record + doctor stays out (analysis P12): docs/design/07-deployment.md §7.8.7's rootless paragraph (:1015-1026) notes the wizard enforces the prerequisite at the GPU decision point — probes: `grep -n 'wizard' docs/design/07-deployment.md` hits the paragraph; `grep -n 'setfacl\\|renderD\\|rootless' prod/scripts/0-doctor.sh` prints nothing and DoctorWiringTest stays green."
  - "mvn verify from the repo root is green; bash -n prod/scripts/4-llm.sh passes."
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
      — rootlessGpuHostWithoutRenderNodeAccessFailsWithTheSetfaclRemedy
      (reproduction), rootfulOrAclPresentGpuHostProceeds (item 2's two
      drives), gpuUpFailsLoudWhenTheContainerSeesNoDevice (P11, both
      services), plus two drive-layer stubs: FAKE_DOCKER_ROOTLESS on the
      fake docker's info branch and the INFOCHAT_LLAMACPP_GPU_NODES node-list
      override.
  preserves:
    - all tests currently green on main
    - >-
      Every M1-826 drive and every pre-existing drive, unmodified: the
      default stubs (rootful info, no node override) reproduce today's
      behavior, and the CPU path adds no probe and no exec. AUTHORIZED
      drive-layer addition only: the fake docker gains an info branch
      (absent today — `docker info` currently falls through to exit 0 with
      empty output, which the script must read as NOT rootless).
spec_refs:
  - docs/design/07-deployment.md §7.8.7
  - docs/spec/security.md §Trust boundaries
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-15
    verdict: REWORK
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 190 insertions(+), 11 deletions(-)"
    rework_items: 1
    verdict_file: .scratch/tick-review-M1-827-r1.txt
  - round: 2
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 230 insertions(+), 11 deletions(-)"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-827-r2.txt
---

# M1-827: Fail loud when the GPU container would see no device

## Context

After M1-826 the wizard merges the Vulkan overlay on GPU hosts — but the
overlay's own header (docker-compose.gpu.yml:21-28) documents the measured
rootless trap: `group_add` is ineffective under rootless docker, the device
node appears as 65534:65534, and `llama-server --list-devices` prints an
empty list **with no error**. The fix is host-side ACLs (§7.8.7:1015-1026),
and no wizard step checks them (grep-verified) — so a rootless host without
ACLs gets a GPU container that silently sees no device: the same "slow model"
symptom as item 10, one layer down, now WITH the overlay applied. The prod
host has the ACLs (applied 2026-08-10, evidence item 10); the next GPU host
may not. Shared analysis: `analysis_ref:`.

## Root cause

The trap lives at the intersection of two verified facts: (1) rootless docker
does not map the host's render/video GIDs into the user namespace, so
`group_add` (docker-compose.gpu.yml:34-36) is a no-op there (M1-744,
measured); (2) nothing in the wizard path evaluates either the prerequisite
(host-user access to the render nodes) or the outcome (the server's device
list). M1-826 adds the overlay merge; this ticket adds the gate and the
outcome check.

## Pitfalls

Numbered consistently with the analysis document.

- P11: end-of-path assertion (§8 boundary siting) — asserting merged `-f`
  flags (M1-826) proves the overlay, not the device. The check that catches
  the trap is the server's own view: `--list-devices` inside the started
  container, after each GPU up, for both llama.cpp services.
- P12: NOT in 0-doctor.sh — doctor hard-blocks on any failure (M1-439,
  0-doctor.sh:138-151), which would punish ollama/remote operators on GPU
  hosts. The gate fires only when the llamacpp GPU path was actually selected
  (the M1-809 same-path principle).
- P8: the node set is /dev/dri/renderD* + /dev/dri/card* (the Vulkan
  overlay's device, and the §7.8.7 remedy's nodes) — never /dev/kfd (4b's
  ROCm lane).
- P3: hermeticity — rootless state comes from a fake-docker info stub; the
  node list comes from the INFOCHAT_LLAMACPP_GPU_NODES override (the
  INFOCHAT_RUNTIME_DIR seam precedent), pointed at permission-controlled
  temp files. No test depends on the host's /dev or docker.
- P14: the negative is hermetic by design (no live de-ACL of the prod host);
  the positive owes the live `--list-devices` capture.

## Approach

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Extend the drive layer (fake-docker info branch with FAKE_DOCKER_ROOTLESS;
     node-list override env) and write the reproduction + item-2/3 drives —
     run RED (workflow §0).
  2. 4-llm.sh, in the M1-826 GPU-selected branch, BEFORE any compose up:
     detect rootless from `docker info` output; when rootless, check read+write
     access as the invoking user to each node in the list (default: the
     /dev/dri/renderD* + card* glob; INFOCHAT_LLAMACPP_GPU_NODES overrides);
     any inaccessible node → hard FAIL naming the rootless cause, the nodes,
     the setfacl remedy, and the udev persistence pointer (point at §7.8.7 —
     do not paraphrase the runbook into a string that can drift).
  3. After EACH GPU-overlay up: exec `llama-server --list-devices` in the
     started container; empty list (or a failed exec — container down counts
     as not-verified, never as passed, mirroring 0-doctor.sh:97-103's
     unverifiable-is-not-passed posture) → FAIL loud with the same remedy
     pointer. CPU path: neither check runs.
  4. Live positive proof on the prod host (acceptance item 5).
  5. §7.8.7 note; `bash -n`; `mvn verify` from the repo root.
- **Controls to preserve (§10):** the M1-826 merge behavior (its drives
  green), the CPU path byte-identical (no info probe, no exec), 0-doctor.sh
  untouched (P12), no host port anywhere (security.md §Trust boundaries item
  8), no new stdin prompt (the gate fails; it never asks).
- **Pitfall→mitigation:** P11→step 3 + item 3; P12→step 2's placement +
  item 6; P8→step 2's node list + census; P3→step 1's stubs; P14→step 4.

## Definition of done

On a rootless host without render-node access, a GPU-selected wizard run
fails BEFORE any bring-up with the §7.8.7 remedy; a rootful or ACL-present
host proceeds; after every GPU bring-up the wizard verifies the server
actually lists a device and fails loud otherwise; the CPU path and doctor are
untouched; the live positive proof is captured; mvn verify green.

## Verification

- P11 → LlamacppWiringTest.gpuUpFailsLoudWhenTheContainerSeesNoDevice
  (failure-mode): the fake docker emits an empty device list and the drive
  asserts rc non-zero naming the trap — a mutation dropping the post-start
  exec, or asserting only the compose argv, leaves the trap undetectable and
  fails this drive; both llama.cpp services are covered.
- P12 → item 6's greps: 0-doctor.sh must not gain an ACL/GPU check
  (`grep -n 'setfacl\|renderD\|rootless' prod/scripts/0-doctor.sh` prints
  nothing) + DoctorWiringTest green.
- P8 → the remedy text names renderD/card nodes; `grep -n 'kfd'
  prod/scripts/4-llm.sh` prints nothing (carried from M1-826).
- P3 → item 1's stubs: permission-controlled temp files, env-forced
  rootless; no host state is consulted by any drive.
- P14 → item 5's live capture: the positive proof on the real host; the
  failure-mode negative stays hermetic (items 1/3) so the prod host is never
  de-ACL'd.
- Over-blocking (failure-mode) → item 2's two proceed drives: a gate that
  fires on rootful hosts, or on node existence rather than access, refuses
  runs that must proceed and fails them.
- Unverifiable-is-not-passed → item 3's failed-exec arm: a mutation treating
  a dead container as "GPU fine" fails it.
- Reproduction → item 1.

## Out-of-scope

Named in `out_of_scope`: 0-doctor.sh (P12 — the rejected placement,
documented in the analysis §Solution options C), the merge itself (M1-826),
4b-image.sh's existence-only gate (batch C follow-up — same trap shape for
ComfyUI), applying ACLs for the operator (sudo runbook, §7.8.7), restore.sh
(batch A). Pre-existing tests: the only authorized change is the additive
drive-layer stub (test_plan.preserves); no assertion is modified.

## Census

Class: wizard GPU/device prerequisite checks (re-runnable:
`grep -rn 'kfd\|renderD\|/dev/dri\|setfacl\|rootless' prod/scripts/`).

| Site | Disposition |
|---|---|
| prod/scripts/4-llm.sh (M1-826 GPU branch) | FIXED (this ticket: rootless-ACL gate + post-start device verification) |
| prod/scripts/4b-image.sh:774-778 (local-install hardware gate) | defer: batch C — checks /dev/kfd + /dev/dri EXISTENCE only, no rootless-ACL check; same silent-trap shape for the ComfyUI overlay (docker-compose.comfyui.yml:13-15 documents the same trap) |
| prod/scripts/0-doctor.sh | out-of-scope by design (P12): every-failure-blocks contract makes it the wrong home for a conditional capability check |
| docker-compose.gpu.yml:21-28 / docker-compose.comfyui.yml:13-15 headers | documentation only — the trap text this ticket operationalizes for llamacpp; unchanged |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-827-llm-wizard-robustness-5.md
```

## Round 1 rework

1. Finding 1: add the read-only-node drive beside
   LlamacppWiringTest.java:761 (perms `r--r--r--`, FAKE_DOCKER_ROOTLESS=1,
   INFOCHAT_LLAMACPP_GPU_NODES), asserting rc non-zero and no "up -d" in
   docker-argv.log — evaluated via
   LlamacppWiringTest.rootlessGpuHostWithReadOnlyRenderNodeAccessFails
   (assertNotEquals(0, run.rc) + assertFalse(argv.contains("up -d"))),
   which must pass on the current production code and go RED when the
   `|| ! -w "$node"` leg at prod/scripts/4-llm.sh:324 is removed.
