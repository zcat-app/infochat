---
id: M1-442
title: "llama.cpp setup: GGUFs must land in the Compose-mounted volume and the pinned image must load the pinned model"
status: done
created: 2026-06-24
last_updated: 2026-06-25
blocked_by: []
files_budget: 3
files_scope:
  - docker-compose.yml
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Do NOT add a host port publish for the llamacpp / llamacpp-embeddings services: both stay bound on the compose network only (the security ask LlamacppWiringTest.llamacppServicesPublishNoHostPort already pins). Only the image tag and the model-volume naming change."
  - "Do NOT change which GGUF is the pinned generative/embeddings default or its SHA-256 (M1-417): the defaults and checksum enforcement are correct. This ticket changes the runtime image that LOADS the pinned model, not the model."
  - "Do NOT add a real-container or network smoke to `mvn verify`: loading a 5.3 GB model is too heavy for the suite (LlamacppWiringTest already documents the live curl/embed smoke as a manual VPS check). The model-load proof for the image bump is the manual VPS smoke run 2026-06-24 (server-b9776: `model loaded`, server listening)."
  - "Do NOT touch the Ollama or remote backends' image/volume wiring, or fetch_gguf's no-shell argv-only container hardening + checksum enforcement (M1-394). This is the llama.cpp model-volume + image shape only."
  - "No design/spec contract change: D49 (separate embeddings backend) and §7.4 GGUF→Compose-via-secrets.env stay verbatim; the defect is a volume-name mismatch and a stale image pin, not the documented wiring."
acceptance:
  - "The Docker volume the wizard's `fetch_gguf` writes/probes/verifies/cleans (prod/scripts/4-llm.sh:162,171,174,178) and the volume Compose mounts into the `llamacpp` and `llamacpp-embeddings` services (docker-compose.yml:207,235; declared :246) MUST resolve to the SAME named volume. Today they do not: `fetch_gguf` uses the bare literal `infochat-llamacpp-models`, but Compose namespaces the declared volume to `<project>_infochat-llamacpp-models` (project = working-dir basename, e.g. `infochat_infochat-llamacpp-models`). The result on a fresh VPS: the GGUFs are downloaded AND SHA-verified into a volume the servers never mount, both servers exit(1) with `gguf_init_from_file: failed to open GGUF file`, the Collector `--wait` then fails, and the Provider is never started. Fix so the two agree REGARDLESS of compose project name — e.g. pin the volume's real name with `name: infochat-llamacpp-models` on the docker-compose.yml volume declaration (:246), or route fetch_gguf through the compose-resolved name. Implementer's choice; the fix must not depend on the working-directory basename."
  - "docker-compose.yml pins BOTH llama.cpp services (image lines 191 and 228) to `ghcr.io/ggml-org/llama.cpp:server-b9776`, replacing `server-b5350`. b5350 predates the `gemma4` architecture and cannot load the wizard's pinned-default generative GGUF (`gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf`, the value LlamacppWiringTest.GEN_GGUF already pins) — it dies with `error loading model architecture: unknown model architecture: 'gemma4'`. server-b9776 loads it (verified on a VPS 2026-06-24). The embeddings model (nomic-bert arch) loads on both b5350 and b9776, so the bump does not regress embeddings."
  - "LlamacppWiringTest gains a static-compose assertion that BOTH llama.cpp services pin the chosen non-b5350 image tag (assert the tag equals a pinned test constant, mirroring how GEN_SHA/EMB_SHA are pinned in lock-step with the script), so an accidental downgrade to a non-gemma4-capable image fails the build."
  - "LlamacppWiringTest's fake-`docker` drive layer is tightened to CAPTURE the `-v <volume>:/models` argument that the real fetch_gguf passes and assert it equals the volume name docker-compose.yml mounts into the services. This is the assertion that would have caught the volume-name bug; the current shim no-ops the `-v` argument, which is why the non-functional service shipped (the same gap M1-417 noted)."
  - "All existing LlamacppWiringTest assertions stay green: no host port for either service; the generative GGUF drives every LLM task; embeddings resolve to the chosen backend and NEVER the generative GGUF; `dimension=768` preserved; both embeddings shapes (second llama.cpp / Ollama nomic)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java — (a) both compose services pin the gemma4-capable image tag (not server-b5350); (b) fetch_gguf's `-v` model-volume argument equals the volume name Compose mounts into the services."
  preserves:
    - all tests currently green on main
    - "LlamacppWiringTest existing assertions (no host port; task→generative-GGUF wiring; embeddings never the generative GGUF; dimension 768; both embeddings shapes)"
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/spec/deployment.md §Deployment scenarios
decision_refs:
  - D49
reviews:
  - round: 1
    date: 2026-06-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 99
      removed: 15
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-25
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-442: llama.cpp GGUFs land in the mounted volume; image can load the model

## Context

A 2026-06-24 VPS setup run (sibling of M1-439/440/441) failed end-to-end on
the documented llama.cpp happy path. Two independent defects in the same
backend, both invisible to the suite:

1. **Wrong volume.** `fetch_gguf` (prod/scripts/4-llm.sh) downloads and
   SHA-verifies the generative + embeddings GGUFs into a volume named with the
   bare literal `infochat-llamacpp-models`. But docker-compose.yml *declares*
   that volume (`:246`) without a `name:`, so Compose namespaces it to
   `<project>_infochat-llamacpp-models` (the project defaults to the
   working-dir basename — here `infochat`, giving
   `infochat_infochat-llamacpp-models`). The `llamacpp` / `llamacpp-embeddings`
   services mount the *namespaced* volume, which is empty. Both servers exit(1)
   with `gguf_init_from_file: failed to open GGUF file` even though the files
   exist (correct SHA) in the other volume. The Collector `--wait` then fails
   and the Provider never starts.

2. **Stale image.** Both services pin `ghcr.io/ggml-org/llama.cpp:server-b5350`.
   That build predates the `gemma4` architecture; loading the pinned-default
   generative GGUF (`gemma-4-E4B-it-qat-…`, also the value LlamacppWiringTest
   already pins as `GEN_GGUF`) fails with `unknown model architecture:
   'gemma4'`. The embeddings (`nomic-bert`) model loads on b5350, masking the
   gap for embeddings only.

Both were confirmed on the box and the fixes proven: the GGUFs loaded once
copied into the Compose-mounted volume, and `server-b9776` loaded the `gemma4`
generative model (`model loaded`, server listening, n_ctx 131072) on the same
host.

These were missed because `LlamacppWiringTest` (M1-417, written precisely to
stop a non-functional llamacpp service shipping) drives the wizard with a
**fake docker** that no-ops the `-v …` download/run, so it never checked the
volume *name*, and it cannot start a real container, so it never loaded a real
model. This ticket closes both holes statically.

## Notes (verified 2026-06-24)

- `server-b9776` exists as a pinnable tag (`docker manifest inspect …:server-b9776`
  succeeds); the rolling `:server` tag currently resolves to build 9776
  (`llama-server --version` → `version: 9776`). Pin the explicit `server-bNNNN`
  for reproducibility, not the rolling tag.
- Preferred volume fix is a one-line `name: infochat-llamacpp-models` on the
  docker-compose.yml volume declaration (:246) — it makes Compose use the exact
  name the script already passes, is project-name-independent, and needs no
  script edit. The alternative (rewriting the five 4-llm.sh literals to the
  prefixed name) is fragile across clone directories; avoid it. `prod/scripts/4-llm.sh`
  is listed in files_scope defensively and is expected untouched if the `name:`
  approach is taken — note which path was chosen in the commit.
- The embeddings server loaded on the SAME corrected volume the moment it was
  populated, confirming the only embeddings-side defect was the volume name.
- LlamacppWiringTest's fake-docker shim is at the bottom of the file (the
  "Minimal fake docker" helper, ~:180); extend it to record argv into a temp
  file the test reads back, the same shape DoctorWiringTest uses for its fake
  `docker`/`ss`/`df`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-442-llamacpp-setup-volume-and-image.md
```
