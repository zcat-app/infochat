---
id: M1-808
title: "Run wizard download containers on the host network"
status: pending
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  Probe (RED on main; wizard download invocations are shell, partially
  mvn-covered): `grep -rn -- '--network' prod/scripts/` prints NOTHING — no
  one-shot download invocation selects a network context, so all three run on
  the default bridge (4b-image.sh:594, 4-llm.sh:230, restore.sh:232) while
  the preflight runs host curl (4b-image.sh:522). Observed wrong behavior on
  the divergent host (brief probes, 2026-08-10): all five preflight HEADs
  pass, then `curl: (6) Could not resolve host: huggingface.co` kills the
  first download; `docker run --rm --network host curlimages/curl:8.11.1 -sI
  --max-time 8 https://huggingface.co` returns HTTP/2 200 on the same host.
  Intended test (to-be-written at start):
  LlamacppWiringTest.oneShotDownloadContainersUseTheHostNetworkPath.
analysis_ref: docs/plan/m1/tick-analysis/wizard-download-container-network.md
blocked_by: []
files_scope:
  - prod/scripts/4b-image.sh
  - prod/scripts/4-llm.sh
  - prod/scripts/restore.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The 4-llm.sh reachability preflight and every failure-guidance text —
    M1-809 owns preflight parity and operator-facing messages; this ticket
    changes the network context the download uses, nothing printed.
  - The image-BUILD legs of the same failure class (7-apps.sh:62 compose
    build; upgrade.sh:155,272; restore.sh:695; the implicit comfyui build
    under 4b-image.sh:801 on a fresh GPU host) — IN SCOPE of this family as
    the sibling M1-810, which this ticket does not preempt: no compose-file
    edit and no build-site edit here.
  - Host-side Docker daemon DNS configuration (daemon.json) — after this
    family (M1-808 + M1-810) NO repo-launched container depends on
    default-bridge DNS, so this is optional residue for the operator's own
    non-repo containers, never a repo change and never a setup-path
    requirement (analysis §Decomposition end state).
  - The fetch mechanics themselves — -u 0:0, argv-only invocation, pinned
    CURL_IMAGE, skip-if-present probe, SHA enforcement + mismatch-rm, 4b's
    --retry 3 --create-dirs — are preserved UNCHANGED (analysis P2/P7); a
    diff that reorders or rewrites them has left scope.
  - The 4b-image.sh host-dir download branch (already host curl,
    4b-image.sh:584) and the no-network probe containers (ls/sha256sum/rm).
acceptance:
  - "Probe + test — REPRODUCTION, now passing: (a) grep: `grep -c -- '--network host' prod/scripts/4b-image.sh prod/scripts/4-llm.sh prod/scripts/restore.sh` prints a value >= 1 for EACH file, and every hit sits on a download invocation (the `-fL ... -o \"/models/...\"` line); (b) the new test LlamacppWiringTest.oneShotDownloadContainersUseTheHostNetworkPath passes: the fake-docker shim, extended with an opt-in switch so the volume presence probe reports ABSENT and the download invocation is issued and recorded (the M1-442 capture lesson, analysis P4), captures fetch_gguf's download argv and asserts it carries `--network host`."
  - "Lock-step twin (analysis P3): 4-llm.sh:230's and restore.sh:232's download invocations carry IDENTICAL docker flags — probe: `grep -h 'docker run.*-fL -o' prod/scripts/4-llm.sh prod/scripts/restore.sh | sed 's/^[[:space:]]*//' | sort -u | wc -l` prints 1 (FAILURE-MODE: fixing only 4-llm.sh fails this)."
  - "Mechanics preserved (analysis P2, engineering-rules §10): the captured download argv still carries `-u 0:0` and `-v infochat-llamacpp-models:/models`, names the pinned CURL_IMAGE, and stays argv-only (no `--entrypoint sh`, no `bash -c`); 4b's download line keeps `-fL --retry 3 --create-dirs` (probe: `grep -n -- '--retry 3 --create-dirs' prod/scripts/4b-image.sh` hits the --network-host line) and fetch_gguf gains NO retry flag; LlamacppWiringTest.fetchGgufWritesToTheVolumeComposeMounts, RestoreWiringTest (all gates + bring-up drives), and every other pre-existing assertion stay green (FAILURE-MODE: a rewrite that drops -u 0:0 or introduces a shell fails this item)."
  - "Proxy-env parity (analysis P8): each download invocation forwards the host's proxy environment in NAME-ONLY form — `-e HTTP_PROXY -e HTTPS_PROXY -e ALL_PROXY -e NO_PROXY` — never a `${VAR}` expansion (set -u safe); probe: the new test asserts the four name-only flags in the captured argv, and `bash -n` passes on all three scripts. Docker's name-only -e semantics forward a value iff it is set in the wizard environment, so a deployment without proxy env is byte-for-byte unaffected."
  - "Live path proof on the divergent host (brief probe re-run at start): `docker run --rm --network host curlimages/curl:8.11.1 -sI --max-time 8 https://huggingface.co` returns HTTP/2 200, AND one real same-shape download lands a file in a named volume: `docker volume create infochat-probe-net && docker run --rm --network host -u 0:0 -v infochat-probe-net:/models curlimages/curl:8.11.1 -fL --create-dirs -o /models/probe https://huggingface.co/Comfy-Org/Mage-Flow/resolve/main/vae/mage_flow_vae_bf16.safetensors` (the smallest curated asset) exits 0 with the file present, then `docker volume rm infochat-probe-net` cleans up."
  - "mvn verify from the repo root is green (the new test + shim extension are the only Java-side change)."
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
      — oneShotDownloadContainersUseTheHostNetworkPath: with the shim's
      opt-in "volume probe absent" switch enabled, the recorded download argv
      carries `--network host` and the four name-only proxy `-e` flags
      alongside `-u 0:0` and the pinned volume mount.
  preserves:
    - all tests currently green on main
    - >-
      Every existing LlamacppWiringTest assertion (volume argument,
      props/secrets shapes, custom-URL persistence, remote-switch
      reconcile) and every RestoreWiringTest drive (its fake docker keeps its
      ls-no-op shape; restore.sh's download leg is pinned textually by the
      lock-step dedupe probe, per the restore.sh:51-58 sync obligation).
      AUTHORIZED modification of the shared fake-docker shim in
      LlamacppWiringTest ONLY: fakeDockerScript gains an OPT-IN switch
      (env-controlled) making the volume presence probe report absent so the
      download invocation is issued and recorded; the default behavior
      (present → skip) is unchanged, so no existing drive sees a different
      flow.
spec_refs:
  - docs/spec/security.md §Trust boundaries
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-808: Run wizard download containers on the host network

## Context

The wizard's `/image` step passes all five preflight HEAD checks, then the
first download dies with `curl: (6) Could not resolve host: huggingface.co`
on any host where the host network path and the container bridge path diverge
(rootless resolver quirks, VPN DNS splits, corporate proxies) — leaving the
operator with a cryptic curl error mid-multi-GB-setup. The preflight runs
host curl; the download runs in a one-shot default-bridge container; the two
paths share nothing by construction (analysis §Root cause). The same
invocation shape downloads the llama.cpp GGUFs (4-llm.sh) and re-fetches them
on host-clone restore (restore.sh's verbatim twin). This is the first of
three family tickets whose END state is: a first-time user on a divergent
host runs ONLY the scripts and reaches a running deployment (the build legs
are M1-810; preflight + guidance is M1-809). Shared analysis:
`analysis_ref:`.

## Root cause

Path divergence between evidence and action: reachability is proven on the
host path (4b-image.sh:522) and the download executes on the bridge path
(4b-image.sh:594, 4-llm.sh:230, restore.sh:232 — no `--network` flag anywhere
in prod/, grep-verified). Verified on the divergent host 2026-08-10: bridge
invocation fails resolution (the container's /etc/resolv.conf is a copy of
/run/systemd/resolve/resolv.conf — a resolver that answers zero records from
this network), `--network host` returns HTTP/2 200. Compose-network SERVICE
containers are unaffected (their embedded DNS forwards to the host's
127.0.0.53 stub — analysis §Ground truth), which is why this ticket covers
exactly the one-shot download throwaways. The fix makes the download use the
path the preflight already validates — parity by construction.

## Pitfalls

Numbered consistently with the analysis document.

- P2: the fetch mechanics are load-bearing controls — `-u 0:0` root-write
  (fresh named volume is root-owned), argv-only no-shell invocation (M1-394
  injection posture), pinned CURL_IMAGE (M1-004), skip-if-present, SHA
  enforcement, `--retry 3 --create-dirs` on 4b. §10: carry each across; the
  M1-442 volume-arg assertion pins one of them.
- P3: restore.sh:226-244 is a verbatim twin with a stated sync obligation
  (restore.sh:51-58) — move in lock-step or host-clone restore keeps dying.
- P4: the fake-docker shims no-op the download today (ls exits 0 → skip,
  LlamacppWiringTest.java:421; RestoreWiringTest's run branch has the same
  shape) — the M1-442 lesson: capture, don't no-op. The new assertion is only
  honest if the download invocation is actually issued and recorded under
  test.
- P5: `--network host` widens the container to the host netns. Sound here:
  transient client binding no port (§Trust boundaries items 6–8 govern
  exposed services), argv-only pinned image, operator-entered URLs with the
  operator as principal (no escalation over host curl). A shell, a listening
  port, or an unpinned image voids the argument — hence security_relevant.
- P7: keep 4b's `--retry 3 --create-dirs`; add no retry where none existed.
- P8: proxy forwarding must be `set -u`-safe — name-only `-e NAME` form,
  never `${VAR}` expansion.

## Approach

- **Files to touch:** `files_scope` (three scripts at the download
  invocations only; the test class for the pin + shim extension).
- **Steps, in order:**
  1. Extend LlamacppWiringTest's fake-docker shim with the opt-in
     "presence probe absent" switch and write
     oneShotDownloadContainersUseTheHostNetworkPath asserting `--network
     host` + the four name-only proxy `-e` flags on the recorded download
     argv — run RED on main (workflow §0).
  2. Add `--network host` and `-e HTTP_PROXY -e HTTPS_PROXY -e ALL_PROXY
     -e NO_PROXY` (name-only, before the image name) to the three download
     invocations: 4b-image.sh:594, 4-llm.sh:230, restore.sh:232 — 4-llm.sh
     and restore.sh byte-identical in their flags (P3).
  3. Run the live path proof on the divergent host (acceptance item 5).
  4. `mvn verify` from the repo root.
- **Controls to preserve (§10):** enumerated in the analysis §Controls to
  preserve — mechanics unchanged (item 1), existing test pins green incl.
  RestoreWiringTest (items 1–2), resume semantics intact (item 4), restore
  lock-step (item 5).
- **Pitfall→mitigation:** P2→step 2's argument-level edit + item 3;
  P3→step 2's lock-step + item 2; P4→step 1 + item 1b; P5→step 2 keeps
  argv-only pinned image + item 3 + security_relevant gate; P7→item 3's
  retry grep; P8→step 2's name-only form + item 4.

## Definition of done

All three one-shot download invocations run on the host network path with the
host's proxy env forwarded when set; the twin scripts stay flag-identical;
every fetch mechanic survives unchanged; the argv pin and the live path proof
are green; mvn verify green.

## Verification

- P2 → acceptance item 3 (argv carries -u 0:0 / -v / pinned image / no
  shell; retry flags unchanged; existing suite green). Mutation: dropping
  `-u 0:0` or adding `--entrypoint sh` fails it.
- P3 → acceptance item 2's dedupe probe. Mutation: editing only 4-llm.sh
  fails it.
- P4 → acceptance item 1b: the assertion reads a RECORDED download argv; a
  shim that never issues the download fails loudly (non-vacuity, §8).
- P5 → security_relevant: true routes the diff through the SECURITY check
  and a redteam audit; item 3 pins the argv-only pinned-image shape.
- P7 → item 3's `--retry 3 --create-dirs` grep on the new line; absence of
  any new retry flag in fetch_gguf (grep `--retry` 4-llm.sh/restore.sh →
  only the pre-existing sites, i.e. none).
- P8 → item 4's argv assertion + `bash -n` on all three scripts.
- Reproduction → item 1 (grep + named test, both passing).

## Out-of-scope

Named in `out_of_scope`: M1-809's preflight + guidance texts; the build legs
(sibling M1-810 — no compose-file or build-site edit here); daemon DNS host
configuration (optional residue after the family, never a setup-path
requirement); the fetch mechanics (preserved unchanged); the host-dir branch
and the no-network probe containers. No pre-existing test is modified except
the explicitly authorized opt-in shim extension in LlamacppWiringTest
(test_plan.preserves); RestoreWiringTest's shim is NOT modified; no test
assertion is weakened.

## Census

Class: one-shot provisioning containers performing outbound network I/O
(re-runnable: `grep -rn 'docker run' prod/`).

| Site | Disposition |
|---|---|
| prod/scripts/4b-image.sh:594 (fetch_asset, volume branch) | FIXED (this ticket) |
| prod/scripts/4-llm.sh:230 (fetch_gguf) | FIXED (this ticket) |
| prod/scripts/restore.sh:232 (fetch_gguf twin) | FIXED (this ticket, lock-step) |
| prod/scripts/4b-image.sh:584 (fetch_asset, host-dir branch) | out-of-scope: already host curl |
| ls/sha256sum/rm probes (4-llm.sh:221,233,237; 4b-image.sh:387,587; restore.sh:228,235,239,258) | out-of-scope: no network I/O |
| backup.sh:184, pack.sh:255, restore.sh:477 (tar) | out-of-scope: no network I/O |
| restore.sh:273-274 (PRINTED manual-fetch recipe) | guidance text: gains the same flags in M1-809 |
| 7-apps.sh:62, upgrade.sh:155,272, restore.sh:695 (compose build); 4b-image.sh:801 implicit comfyui build | FIXED by sibling M1-810 (declarative build network) |
| 4-llm.sh:319-322,451-452,619-620 + restore.sh:677 (`ollama pull`, service containers) | verified UNAFFECTED — compose-network embedded DNS forwards to the host resolver stub (analysis §Ground truth; change-note fact 1); no fix, no host preflight (P1) |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-808-wizard-downloads-host-network.md
```
