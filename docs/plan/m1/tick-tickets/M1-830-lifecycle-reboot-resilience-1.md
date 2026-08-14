---
id: M1-830
title: "Port prod restart-policy drift into docker-compose.yml"
status: pending
created: 2026-08-13
last_updated: 2026-08-13
flow: tick
reproduction: >-
  Probe (RED on main; compose artifacts have no mvn coverage today):
  `grep -c 'restart: unless-stopped' docker-compose.yml` prints 2 — only
  llamacpp (:296) and llamacpp-embeddings (:362) carry the policy; postgres,
  infochat-collector, infochat-provider and ollama carry none, so after an OS
  reboot or a rootless-dockerd bounce the deployment silently comes back
  half-alive (LLM backends up, database + apps dead; setup-hurdles.md item
  13 / item H, host-proven 2026-08-12). Test:
  RestartPolicyWiringTest.everyLongRunningServiceRestartsUnlessStopped
  (to-be-written — `start` writes the class and runs it RED on main before
  any fix code, workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/lifecycle-reboot-resilience.md
blocked_by: []
files_scope:
  - docker-compose.yml
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestartPolicyWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any change to environment:, ports:, volumes:, healthcheck, depends_on,
    or deploy.resources blocks — the port adds ONE key per service plus its
    comment; every neighbouring key stays byte-identical.
  - docker-compose.gpu.yml (image/devices overlay; its services inherit the
    base file's policy) and prod/scripts/** (M1-831/M1-832 own those).
  - The 2026-08-13 ollama/circuit-breaker surfacing addendum (batch E) and
    restore.sh (batch A).
  - docs/** — design-note and guide text lands in M1-831 (one row) and
    M1-833.
acceptance:
  - "REPRODUCTION, now passing: RestartPolicyWiringTest.everyLongRunningServiceRestartsUnlessStopped (to-be-written at start; new, infochat-provider wiring package; service-block assertion pattern of BuildHostNetworkWiringTest.java:55-65) — asserts each of postgres, infochat-collector, infochat-provider, ollama, llamacpp, llamacpp-embeddings in docker-compose.yml AND comfyui in docker-compose.comfyui.yml declares `restart: unless-stopped` inside its service block (FAILURE-MODE: deleting the key from any one of the seven services fails the test — the half-alive regression cannot ride a future compose edit)."
  - "FAILURE-MODE negative (analysis P2): the same test asserts NO service in any docker-compose*.yml declares `restart: always` — `always` would resurrect containers after a deliberate operator `docker compose stop` / stack.sh stop, defeating the shutdown verb M1-832 ships."
  - "Port fidelity (analysis P1): the diff is the prod checkout's verified drift — `restart: unless-stopped` after the `profiles:` line of infochat-collector and infochat-provider, after `profiles: [dev, ollama]` on ollama, and on postgres WITH its 3-line comment, all ported verbatim from /home/infochat/infochat-prod/docker-compose.yml (:46-49, :100, :175, :277). Probes: `grep -c 'restart: unless-stopped' docker-compose.yml` prints 6; `grep -n 'loginctl enable-linger' docker-compose.yml` hits the ported postgres comment."
  - "Header-comment truthfulness (analysis P1, engineering-rules §11 — the one deliberate deviation from verbatim): the M1-512 header parenthetical at docker-compose.yml:22-23 no longer singles out the llama services, since every service now carries the policy. Probe: `grep -n 'the llama services carry' docker-compose.yml` prints nothing."
  - "Live render: `docker compose -f docker-compose.yml config` exits 0 and the rendered model shows `restart: unless-stopped` (or the normalized `RestartPolicy: unless-stopped` form) under all six base services — proves the host's compose accepts the file rather than rejecting or silently dropping the key."
  - "mvn verify from repo root is green (BuildHostNetworkWiringTest, LlamacppWiringTest and all other compose-reading tests stay green — the port changes no key they pin)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestartPolicyWiringTest.java
      — everyLongRunningServiceRestartsUnlessStopped (reproduction +
      P2 negative): static per-service-block assertions over
      docker-compose.yml (six services) and docker-compose.comfyui.yml
      (comfyui); `restart: unless-stopped` present per service,
      `restart: always` absent everywhere.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.8.7 Host resource hardening (swap, container caps, build isolation)
  - docs/spec/deployment.md §Deployment scenarios
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-830: Port prod restart-policy drift into docker-compose.yml

## Context

After an OS reboot the prod deployment comes back half-alive: the llama.cpp
and ComfyUI containers return (they carry `restart: unless-stopped`) while
postgres, the Collector, the Provider and ollama stay dead until an operator
hand-assembles the full compose up — and nothing surfaces the state
(setup-hurdles.md item 13; item H's 2026-08-12 logout-kill incident, where
the missing policies turned a rootless dockerd SIGKILL into a full outage
that ate both digest windows). The fix is already applied and host-verified
on the prod host as uncommitted drift in the prod checkout; this ticket
ports it into the repo and pins it. Shared analysis: `analysis_ref:`.

## Root cause

Verified by direct read. This checkout's `docker-compose.yml` carries
`restart: unless-stopped` only on llamacpp (:296) and llamacpp-embeddings
(:362); postgres (:44-80), infochat-collector (:94), infochat-provider
(:168) and ollama (:269) have no restart policy, so a dockerd stop strands
them. The prod checkout's docker-compose.yml carries the host-verified fix:
a +7-line diff (prod :46-49 comment+key on postgres; bare key at :100, :175,
:277) — port it, do not reinvent it. One caveat the prod drift left behind:
the M1-512 header comment at docker-compose.yml:22-23 says "(the llama
services carry restart: unless-stopped)" — true today, false the moment the
port lands (engineering-rules §11: a stale comment keeps asserting a premise
the code stopped satisfying).

## Pitfalls

Numbered consistently with the analysis document.

- P1: reinvention vs port — the prod diff is the host-verified artifact;
  rewording or re-siting it drifts from what was validated (§1). The ONE
  deliberate deviation is the header-parenthetical fix (§11). And a port
  with no static pin lets the next compose edit silently drop one service's
  key — the test must fail on that concrete mutation.
- P2: policy semantics — `unless-stopped`, never `always`: Docker restarts
  containers independently on daemon recovery (depends_on does not order
  daemon-driven restarts; the Collector's fail-fast + Docker restart backoff
  IS the ordering mechanism, as host-verified), and `always` would
  resurrect containers after a deliberate operator stop. Assert the exact
  policy and the absence of `always`.

## Approach

- **Files to touch:** `files_scope` (one compose file, one new test).
- **Steps, in order:**
  1. Write RestartPolicyWiringTest.everyLongRunningServiceRestartsUnlessStopped
     (the reproduction's to-be-written test) — run RED on main (workflow
     §0). Reuse BuildHostNetworkWiringTest's `serviceBlock` pattern
     (per-service block extraction) so each assertion is scoped to one
     service's block.
  2. Port the drift verbatim: `restart: unless-stopped` onto postgres (with
     its 3-line comment copied from the prod checkout :46-49),
     infochat-collector, infochat-provider, ollama (bare key, same
     indentation/placement as prod).
  3. Fix the M1-512 header parenthetical (:22-23) to state the new truth
     (every service carries the policy) — one clause, no chronicle (§11).
  4. Run the live render probe (acceptance item 5), then `mvn verify`.
- **Controls to preserve (§10):** every environment: empty-pass-through
  (secrets stay env-delivered, security.md §Secrets handling), every
  loopback ports: bind (security.md §Trust boundaries items 6/8), every
  healthcheck, depends_on ordering, and M1-512 deploy.resources cap stays
  byte-identical — the diff is four keys + four comment lines + one header
  clause. BuildHostNetworkWiringTest/LlamacppWiringTest pin neighbouring
  shapes and must stay green.
- **Pitfall→mitigation:** P1→step 2's verbatim port + step 3's named
  exception + acceptance item 3's probes; P2→acceptance item 2's
  `restart: always` negative.

## Definition of done

All six base services + comfyui carry `restart: unless-stopped` (comfyui
already does — the test pins it against regression); no `restart: always`
anywhere; the diff matches the prod checkout's verified drift line-for-line
except the header-comment fix; the header no longer singles out the llama
services; the host's compose renders the file; the full suite is green.

## Verification

- P1 → acceptance item 3 (grep fidelity probes against the prod source
  lines) + item 4 (header staleness probe).
- P2 → acceptance item 2 — FAILURE-MODE: a mutation switching any service
  to `restart: always` fails the test, because `always` would resurrect
  containers after a deliberate operator stop.
- Reproduction → item 1 — FAILURE-MODE: deleting the `restart:
  unless-stopped` key from any one of the seven asserted service blocks
  fails the test (non-vacuous per-service siting, not a file-level grep).
- Live path → item 5 (compose render).
- Regression → item 6 (full suite; the compose-reading wiring tests pin the
  untouched keys).

## Out-of-scope

Named in `out_of_scope`: any other compose key (the port is additive — one
key per service); the gpu overlay (its two services inherit the base file's
policy — verified it declares no restart keys of its own); all scripts
(M1-831 owns 0-doctor.sh, M1-832 owns stack.sh/apps.sh); docs (M1-831's one
table row and M1-833); the 2026-08-13 ollama/circuit-breaker surfacing
addendum (batch E); restore.sh (batch A). No pre-existing test is modified.

## Census

Class: long-running services across the repo's compose files (re-runnable:
`grep -n 'restart:' docker-compose.yml docker-compose.comfyui.yml docker-compose.gpu.yml`
plus the service lists in each file).

| Service (file) | Disposition |
|---|---|
| postgres (docker-compose.yml) | FIXED (this ticket: key + ported comment) |
| infochat-collector (docker-compose.yml) | FIXED (this ticket) |
| infochat-provider (docker-compose.yml) | FIXED (this ticket) |
| ollama (docker-compose.yml) | FIXED (this ticket) |
| llamacpp (docker-compose.yml) | already carries it (:296) — pinned by the new test |
| llamacpp-embeddings (docker-compose.yml) | already carries it (:362) — pinned by the new test |
| comfyui (docker-compose.comfyui.yml) | already carries it (:29) — pinned by the new test |
| llamacpp / llamacpp-embeddings (docker-compose.gpu.yml) | out-of-scope: overlay overrides image/devices only and declares no services of its own — inherits the base file's key (grep-verified) |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-830-lifecycle-reboot-resilience-1.md
```
