---
id: M1-512
title: "Production runtime resource harness: swap, container memory/CPU caps, dev-runtime teardown"
status: done
created: 2026-06-29
last_updated: 2026-06-29
clarity_check:
  date: 2026-06-29
  verdict: WARN
  warnings:
    - "Acceptance item 6 still carries the 'or the nearest existing anchor' qualifier; the §Deployment scenarios anchor exists (docs/spec/deployment.md line 416). Implementer pins the note to §Deployment scenarios and drops the qualifier."
  blockers: []
blocked_by: []
files_budget: 6
files_scope:
  - docker-compose.yml
  - docs/design/07-deployment.md
  - docs/spec/deployment.md
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Retiring D49 embeddings shape (b) (llama.cpp generative + Ollama embeddings as
    two local runtimes). Whether to forbid two LOCAL runtimes and allow only
    "one local (+ optional remote)" is a spec decision that amends/supersedes D49;
    it is tracked separately and is NOT decided here. This ticket only tears down
    the ORPHANED host-level dev Ollama systemd service on boxes whose running
    deployment is pure-llama.cpp (shape a) — it does not change which shapes D49
    permits.
  - >-
    Application-level eval concurrency tuning (infochat.llm.*.max-concurrency,
    infochat.embeddings.max-concurrency per profile). Resource caps here are at the
    OS/container layer; per-profile concurrency is a separate change.
  - >-
    CI / build-host provisioning. "Do not build on the prod box" is captured as an
    operator runbook note only; standing up a separate build runner is out of scope.
  - "Kubernetes / non-Compose orchestration. v1 prod is docker-compose (D49, 07-deployment.md)."
acceptance:
  - >-
    A swapfile is provisioned on the prod host as part of the documented bootstrap:
    07-deployment.md gains a §"Host swap" runbook step (size guidance relative to
    RAM, fallocate/mkswap/swapon, /etc/fstab persistence, vm.swappiness note) so a
    memory overshoot degrades to paging instead of an OOM kill or whole-host stall.
    The current prod host runs with zero swap (root-caused in the 2026-06-28
    resource-exhaustion incident); the runbook closes that gap.
  - >-
    Every long-running prod service in docker-compose.yml (llamacpp,
    llamacpp-embeddings, collector, provider, postgres) declares an explicit
    memory limit AND reservation, and a CPU limit. Limits are sized ABOVE measured
    steady-state with headroom so normal operation never hits them; they act as a
    per-container blast-radius cap, converting a runaway into a single-container
    OOM-kill-and-restart (the llama services already carry restart: unless-stopped
    with the OOM-recovery rationale at docker-compose.yml ~L211/L255) rather than a
    host-wide meltdown. The sizing basis is recorded in a comment.
  - >-
    The two JVM services (collector, provider) set a heap ceiling
    (-XX:MaxRAMPercentage or -Xmx via JAVA_OPTS) strictly below their container
    memory limit, so the JVM hits its own managed heap ceiling first — GC pressure
    and a catchable OutOfMemoryError — before the cgroup OOM-killer SIGKILLs the
    container. Documented inline.
  - >-
    07-deployment.md documents that the dev Ollama (the host systemd ollama.service
    used by the quarkus:dev inner loop per D49) MUST NOT run on a box whose prod
    deployment is pure-llama.cpp (shape a): it is a second, unused local LLM runtime
    that needlessly reserves RAM and is a footgun. The runbook gives the teardown
    (systemctl disable --now ollama.service) and a one-line check that no enabled
    local runtime is unused by the active Compose profile set.
  - >-
    07-deployment.md gains an explicit operator warning that builds (mvn verify,
    docker image builds) MUST NOT run on the prod host while the LLM stack is live —
    they forked test JVMs that pegged all 4 cores and stacked on the resident model
    weights with no swap, which is what triggered the 2026-06-28 incident.
  - >-
    docs/spec/deployment.md §Deployment scenarios (or the nearest existing anchor)
    notes the resource-isolation invariant at spec altitude: the prod host runs the
    LLM stack + DB + Provider/Collector only; swap is required; per-container memory
    caps bound the blast radius. No new operator INPUT is added (so the wizard
    contract is unchanged).
  - "mvn -B verify is green from the repo root (no code paths change; this guards against a compose/doc edit breaking a build-time resource)."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
notes: >-
  Root cause of the 2026-06-28 13:00-15:00 UTC VPS resource-exhaustion (provider
  capped our resources): the 4 vCPU / 15 GB / ZERO-swap host was used as a build
  host (back-to-back mvn verify for M1-507/M1-508 forking test JVMs + docker image
  builds at 14:59-15:03) WHILE running the live LLM stack (two llama.cpp servers,
  the embeddings one taking 1877 CPU-bound inference requests in the window) plus
  multiple Claude Code sessions. CPU pegged 90-95% from 12:40; free RAM fell to
  ~150 MB by 14:10 with commit >110% and rising iowait (page-cache thrash, no swap
  to absorb it). Not an application leak. The collector exit=1 at 18:56 was a
  benign shutdown race (a scheduled EmbeddingWorker/tagger tick firing after ArC
  shutdown), unrelated. This ticket hardens the OS/container envelope so a repeat
  degrades gracefully (swap) and stays contained to one container (caps) instead of
  taking down the host. Evidence: sar /var/log/sysstat/sa28, docker inspect, the
  build-artifact mtimes in target/ and .scratch/.
spec_refs:
  - "docs/spec/deployment.md §Deployment scenarios"
  - "docs/design/07-deployment.md §7.7 Local and containerized stack"
decision_refs:
  - D49
  - D46
reviews:
  - round: 1
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 174
      removed: 7
overrides: []
escalations: []
revisions:
  - date: 2026-06-29
    reason: clarity-fail rework
    snapshot:
      status: draft
      clarity_check:
        date: 2026-06-29
        verdict: FAIL
        blockers:
          - "spec_refs entry 'docs/design/07-deployment.md §7.7 (Compose profiles / LLM serve wiring)' resolves to ANCHOR-NOT-FOUND; the actual heading is '7.7 Local and containerized stack (`docker-compose`)'."
        warnings:
          - "Acceptance item 6 'or the nearest existing anchor' qualifier introduces reviewer ambiguity (the §Deployment scenarios anchor resolves)."
          - "Acceptance item 6 location qualifier unnecessary since the named anchor exists."
      spec_refs_at_snapshot:
        - "docs/spec/deployment.md §Deployment scenarios"
        - "docs/design/07-deployment.md §7.7 (Compose profiles / LLM serve wiring)"
---

## Context

On 2026-06-28 the VPS hosting provider throttled our resources after the host
drained CPU and RAM (incident window ~13:00-15:00 UTC). Forensics (sar history,
docker inspect, build-artifact timestamps) showed the cause was **operational, not
a code defect**: the host was used to run `mvn verify` (which forks a test JVM per
module) and `docker` image builds at the same time the live LLM inference stack
(two llama.cpp servers + the eval pipeline) and several Claude Code sessions were
running, on a 4 vCPU / 15 GB box **with no swap configured**. With no swap, the RAM
overshoot had nowhere to go and the host thrashed.

This ticket makes the production runtime resilient to that class of overshoot at the
OS/container layer. It deliberately does **not** change application behavior, the
eval pipeline, or which LLM shapes D49 permits.

## Approach

1. **Host swap** — runbook step in 07-deployment.md (swapfile sizing, persistence,
   swappiness). Converts host-level memory pressure into paging (graceful slowdown)
   instead of OOM / hard stall.
2. **Per-container caps** — memory limit + reservation + CPU limit on every
   long-running prod service in docker-compose.yml, sized above steady-state with
   headroom. CPU caps only throttle (no kill). Memory caps, if a genuine runaway hits
   them, OOM-kill just that one container, which `restart: unless-stopped` brings
   back — blast-radius containment, not a host meltdown.
3. **JVM heap ceiling below the container cap** — so collector/provider hit a managed,
   catchable heap limit before the cgroup SIGKILLs them.
4. **Dev-runtime teardown + build-isolation runbook** — document that the host dev
   Ollama must be disabled on a pure-llama.cpp prod box, and that builds must not run
   on the live prod host.
