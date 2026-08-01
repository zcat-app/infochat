---
id: M1-744
title: "llama.cpp compose services: operator-settable resource caps and an opt-in GPU overlay"
status: pending
created: 2026-08-01
last_updated: 2026-08-01
blocked_by: []
files_budget: 4
files_scope:
  - docker-compose.yml
  - docker-compose.gpu.yml
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Choosing values for any particular host. This ticket makes the caps
    SETTABLE and keeps today's numbers as the defaults; it does not
    re-tune them. A diff that changes a default value has left scope.
  - >-
    The postgres / collector / provider caps. Their memory limits are
    coupled to `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=60.0`, which
    M1-512 pins strictly BELOW the container limit so the JVM hits a
    managed heap OOM before the cgroup killer SIGKILLs the container.
    Making that memory settable without deriving the percentage from it
    breaks the invariant, so it needs its own ticket.
  - >-
    Wizard support. `prod/scripts/wizard/4-llm.sh` writing the new keys
    into `secrets.env` is a follow-up; an operator sets them by hand (or
    in their own env-file) until then. The compose defaults mean an
    operator who sets nothing is unaffected.
  - >-
    A ROCm overlay. The 2026-07-30 A/B picked Vulkan (plan.md §0), and a
    second backend overlay is only worth filing if a model-specific
    re-test overturns that.
  - >-
    Re-pinning the BASE image to a newer llama.cpp build. The overlay
    introduces a Vulkan image; bumping `server-b9776` itself is a
    separate change with its own compatibility surface.
  - any application code
acceptance:
  - >-
    `docker compose config` with NO environment overrides renders the
    llama.cpp resource values byte-identical to today: `llamacpp` at
    cpus "3.0" / memory 7g / reservation 3g, and `llamacpp-embeddings`
    at cpus "1.5" / memory 2g / reservation 512m. Defaults preserving
    current behaviour is the ticket's core claim and must be pinned, not
    assumed.
  - >-
    Setting `INFOCHAT_LLAMACPP_CPUS` / `_MEMORY` / `_MEMORY_RESERVATION`
    and the `INFOCHAT_LLAMACPP_EMBED_*` twins changes the corresponding
    rendered values, and only those — a `docker compose config` diff
    against the default render touches no other key.
  - >-
    A new `docker-compose.gpu.yml` overlays ONLY the two llama.cpp
    services with the Vulkan image, `devices: [/dev/dri:/dev/dri]` and
    `group_add` for the render and video GIDs.
    `docker compose -f docker-compose.yml -f docker-compose.gpu.yml config`
    renders those keys; `docker compose -f docker-compose.yml config`
    alone renders none of them, so a host with no `/dev/dri` still
    starts.
  - >-
    `LlamacppWiringTest` KEEPS its existing anti-downgrade image pin on
    the base file (`:129-132`, whose comment states an accidental
    downgrade to a pre-gemma4 build must be impossible) and GAINS the
    matching pin for the overlay's Vulkan image. The control is carried
    across to the new path, not moved off the old one — both files are
    asserted.
  - >-
    `LlamacppWiringTest` gains an assertion that the generative
    `llamacpp` service block declares `LLAMA_ARG_REASONING: "off"`.
    M1-560 (done) set it so the per-task max-tokens caps buy VISIBLE
    output rather than being consumed by thinking-channel tokens
    (F-live-8, host-proven 2026-07-04), but nothing pins it today — the
    only other reference is a comment in `prod/scripts/4-llm.sh:355`, so
    deleting the line would surface as empty or format-broken replies at
    runtime and as nothing at all in the suite. The assertion's message
    names the F-live-8 failure so a future reader knows what it guards.
  - >-
    `docs/design/07-deployment.md` §7.8.3 and §7.8.7 document the new
    env keys with their defaults, and the GPU overlay including its
    rootless-Docker prerequisite: container-root maps to the host user,
    so the host's `render`/`video` group membership does NOT reach into
    the container and the device needs a host-side ACL
    (`setfacl -m u:<user>:rw /dev/dri/renderD128 /dev/dri/card1`, plus a
    udev rule to survive reboot).
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
      — the default render keeps today's six resource values; the
      interpolation form is present for each; the overlay file pins the
      Vulkan image and the device/group_add keys for both services; the
      base file declares no `devices:` key; and the generative service
      declares `LLAMA_ARG_REASONING: "off"` (M1-560, previously
      unpinned).
  preserves:
    - >-
      Every existing `LlamacppWiringTest` assertion — `LLAMA_ARG_MODEL`
      via `${INFOCHAT_LLAMACPP_GGUF` (and the `_EMBED_` twin),
      `LLAMA_ARG_HOST: 0.0.0.0`, `LLAMA_ARG_EMBEDDINGS`, a healthcheck
      on both services, the absence of a `ports:` host publish on either
      (binds stay on the compose network — the security ask), the base
      image pin on both, and the wizard-side `secrets.env` /
      `fetch_gguf` volume assertions the same class exercises.
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.8.3
  - docs/design/07-deployment.md §7.8.7
decision_refs:
  - D49
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-744: llama.cpp compose services — operator-settable resource caps and an opt-in GPU overlay

## Context

M1-512 gave every long-running prod service a `deploy.resources` cap as a
blast-radius measure after the 2026-06-28 resource-exhaustion incident. The
sizing was correct for the host it was written against — a 4 vCPU / 15 GB
zero-swap box — and the Compose comments say so explicitly, calling the numbers
"starting points above the model/JVM footprints, not measured optima — adjust
per the actual generative model."

Two things have made the llama.cpp pair specifically wrong, and they compound:

- **The caps are hardcoded**, so "adjust per the actual generative model" means
  editing a version-controlled file per deployment. `llamacpp` is capped at
  cpus 3.0 / memory 7g against a comment sizing a "vps-profile 3B-8B Q4 ≈ 2-5 GB"
  model. A generative GGUF's footprint spans more than an order of magnitude
  across the shapes this project already supports, and it is the one service
  whose right value is a property of the *operator's model choice*, not of the
  code.
- **There is no GPU wiring at all.** Neither llama.cpp service declares
  `devices:`, `group_add`, or a Vulkan image — the pinned
  `ghcr.io/ggml-org/llama.cpp:server-b9776` is the CPU build. The containerized
  shape therefore does CPU-only inference on any host, including one with a
  supported iGPU. That was invisible while the deployment target was a 4-core
  CPU box and the live config routed all seven tasks to a remote API.

The two are coupled: GPU passthrough alone does not help, because GTT pages are
pinned system memory charged to the container's cgroup, so a 7g limit blocks a
GPU-resident model just as firmly as it blocks a CPU-resident one.

## Approach

**Caps** follow the interpolation pattern the file already uses for operator
data (`${INFOCHAT_LLAMACPP_GGUF:-}`, `${INFOCHAT_SIGNAL_DATA_DIR:-/var/lib/...}`):
`cpus: "${INFOCHAT_LLAMACPP_CPUS:-3.0}"` and so on, with every default equal to
today's literal. An operator overrides through the same `--env-file secrets.env`
path the wizard already drives. Nothing changes for a deployment that sets
nothing — which is what makes this safe to land ahead of any value decision.

**GPU** goes in a separate `docker-compose.gpu.yml` applied with a second `-f`,
rather than a Compose profile. A profile gates whether a *service* starts; it
cannot add keys to a service that is already defined. And the keys cannot live
in the base file unconditionally, because Docker fails container creation when a
`devices:` path is absent — which would break every host without an iGPU,
including the VPS scenario in `docs/spec/deployment.md`. An overlay is the only
shape that keeps the base file startable everywhere.

## Out-of-scope

No value re-tuning (defaults stay). No changes to the postgres/collector/provider
caps — their limits are load-bearing for M1-512's managed-heap-before-cgroup-kill
invariant via `MaxRAMPercentage`. No wizard support. No ROCm overlay. No bump of
the base image tag.

## Notes

- **The image pin is a control, not a version string.** `LlamacppWiringTest:127-132`
  asserts the tag literally, and its comment states that an accidental downgrade
  to a pre-gemma4 build must be impossible. The overlay introduces a *second*
  image, so the assertion must be duplicated for the overlay rather than pointed
  at it — retargeting would leave the base file's downgrade path unguarded.
- **Rootless Docker is the trap that makes this look broken when it is not.**
  Measured 2026-08-01: with the host user in `render` and `video` and
  `vulkaninfo` correctly naming `RADV STRIX_HALO`, a container launched with
  `--device /dev/dri --group-add 990 --group-add 44` still sees the node as
  `65534:65534` and gets `Permission denied`, because those host GIDs are not
  mapped into the user namespace. `llama-server --list-devices` then prints an
  empty list with no error. The fix is host-side (a `setfacl` grant to the user
  container-root maps to, made persistent with a udev rule), so it belongs in
  §7.8.7's operator text, not in the Compose file.
- **Why the reasoning-off pin rides along rather than getting its own ticket.**
  It is a test-only addition to `LlamacppWiringTest`, already in `files_scope`
  for the caps and overlay work, so it adds no file and no production change.
  Its urgency is current: measured 2026-08-01 against a local
  `DeepSeek-V4-Flash` GGUF served WITHOUT the flag, the reply came back with
  `content: ""` and the whole answer in `reasoning_content` — the exact
  F-live-8 shape M1-560 fixed, reproduced on a model that did not exist when
  that ticket landed. The compose value is correct today; only the guard is
  missing.
- `security_relevant: true` because the overlay hands a device node to a service
  that processes untrusted post bodies, and because the documented host ACL
  widens access to `/dev/dri`. Both deserve a look at the trust boundary even
  though neither is application code.
- Measured context for whoever picks values later, not a value proposal:
  on the Strix Halo box a 95.9 GiB DeepSeek GGUF loads in 40 s and sits fully
  resident in GTT with ~24 GiB of host headroom (`.bench/` working notes).
- Pre-flight: `python3 scripts/lint-ticket.py
  docs/plan/m1/tickets/M1-744-compose-llamacpp-caps-and-gpu-overlay.md` is clean.
